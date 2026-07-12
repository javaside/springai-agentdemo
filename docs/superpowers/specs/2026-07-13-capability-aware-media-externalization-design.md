# 设计文档：能力感知的媒体与二进制外置（非文本内容不入会话）

- 日期：2026-07-13（2026-07-13 修订：纳入外部评审）
- 模块：`springai-code-tui`
- 类型：设计（brainstorming spec）
- 分支：`feat/tool-output-limit`

> 范围声明：本设计只解决**媒体与二进制**工具结果外置，**不是**「工具输出撑爆上下文」的总修复。纯大文本（Bash 长输出 / 大 UTF-8 文件 / 海量 grep）不在本期范围（见非目标）。

## 1. 背景与目标

工具结果里的**非文本内容**（chrome-devtools 截图的 base64、`Read` 读出的二进制、将来的视频等）被原样存进 `ToolResponseMessage.responseData`、每轮随历史重发给模型 → 撑爆上下文（DeepSeek 400 上下文超长 / OpenAI 中转 404）。根因详见 `2026-07-12-tool-output-context-overflow.md`。

**本期目标（聚焦、只做这一件）**：非文本内容（图片/视频/二进制）**不再以字节形式进入会话**。当前模型**无视觉能力 → 不发送这些非文本数据**，会话里只留紧凑引用。

**核心原则——能力感知**：「这个媒体发不发、怎么发」由**发起该工具调用的那次请求的模型能力快照**决定（不是工具完成时的实时模型）。现在全是文本模型 → 一律外置 + 引用；以后接入视觉模型且链路支持注入 → 同一张图改走原生 image 块。设计必须**留好扩展位**，接视觉时零架构改动。

### 非目标（本期不做）

- 大**文本**（纯文本大文件 / Bash 长输出 / 海量 grep）截断——与「非文本」无关。评审建议加一个 64–128K 的全局兜底上限（`ToolOutputLimiter`）以兜住媒体漏判与巨型文本；因先前已明确「不做全局截段」，**本期不加**，记录在此备查、可后续反悔。
- 视觉 image 块的**真注入**（Spring AI 工具结果是 String、需绕开）——留扩展位、留桩，视觉模型落地时做（Path B）。
- **旧坏会话自愈**：已落盘含 246 万字符 base64 的历史会话，本期**不自动迁移**。这类会话须删除 / 新建；本设计只拦截未来工具调用，`FileSessionRepository` 不改。
- 视频转写 / 抽帧、OCR 摘要——更后续。

## 2. 设计总览

```
工具结果（单一装饰点：AgentTools 装饰循环拦截）
  → 读取本次调用的【能力快照】：ToolContext 优先 > ProviderRegistry 兜底 > TEXT_ONLY
  → 检测非文本内容：
       ├─ MCP：结果 JSON 内容块含 image/binary（顶层数组 或 {content:[...]}）
       └─ Read：结果疑似二进制 → 用 toolInput 里的原路径回读原始字节（不复制、就地引用）
  → 外置 / 引用：
       ├─ 已在项目内的文件（Read 命中）：不复制，直接结构化引用其相对路径
       └─ MCP 内联字节：存 <root>/.codetui/artifacts/<full-sha>.<ext>（内容寻址）
  → ToolResultMediaHandler.canDeliver(kind, caps) ?
       ├─ 否（本期全部）：结果里换成【结构化文本引用】，字节永不进会话   ← 本期实现
       └─ 是（以后，注入器就绪）：登记为待注入的原生 image 块          ← 扩展位，留桩
```

会话里存的是**结构化引用**（类型 / 尺寸 / 大小 / 路径 / artifactId），不是字节。原始文件在 `.codetui/artifacts/`（已 gitignore）或项目内原位。

## 3. 两个扩展位（关键）

### 扩展位 ①：`ModelCapabilities` —— 能力快照（按模型、随请求冻结）

`/model` 按模型切换、视觉能力常是模型级，故**按模型**判定。图与视频分离（支持图不代表支持视频）：

```java
public record ModelCapabilities(boolean supportsImageInput, boolean supportsVideoInput) {
    public static final ModelCapabilities TEXT_ONLY = new ModelCapabilities(false, false);
}
```

`LlmProvider` 增默认方法（现在全部返回 `TEXT_ONLY`，零行为变化）：

```java
default ModelCapabilities capabilities(String modelId) { return ModelCapabilities.TEXT_ONLY; }
```

**能力必须绑定「发起调用的模型」，在 submit 时冻结快照进 `ToolContext`**（规避「工具慢→期间切模型→完成时读到新模型」的时序错配，子 agent / 并行工具尤甚）：

```java
.toolContext(Map.of(
    "turnId", turnId,
    "providerId", active.id(),
    "modelId", activeModelId,
    "capabilities", capabilities   // 快照，非引用
))
```

装饰器读能力的优先级：**`ToolContext` 快照 > `ProviderRegistry` 兜底 > `TEXT_ONLY`**。子 agent 在自己的 toolContext 里同样带快照。接视觉模型时，只需对应 provider 覆写 `capabilities()`。

### 扩展位 ②：`ToolResultMediaHandler` —— 能否投递 + 表示策略

```java
interface ToolResultMediaHandler {
    /** 当前模型能力下能否把该类媒体真正投递给模型（模型能力 && 注入器已实现）。 */
    boolean canDeliver(MediaKind kind, ModelCapabilities caps);
    /** 产出该媒体在工具结果里的表示（引用 或 登记原生块）。 */
    String represent(MediaArtifact media, ModelCapabilities caps);
}
```

- 「投递模式」不单看 `caps`，而是 `canDeliver = 模型支持该类输入 && 本链路已接注入器`。本期**没有注入器**，故 `canDeliver` 恒 `false` → 全部走 REFERENCE_ONLY。避免「`supportsImageInput=true` 就以为图已真进模型」的误解。
- 本期只实现 `TextReferenceMediaHandler`：`canDeliver`→恒 false；`represent`→结构化文本引用。vision 分支仅注释留桩。
- 视觉注入（Path B）：将来 `canDeliver=true` 时，`represent` 登记一条待注入 `Media`，由消息装配处注入本回合 user 消息——**本期不实现，仅接口 / 注释预留**。

## 4. 组件（本期实现）

- **`MediaArtifact`**（record）：`id, path, relativePath, mimeType, declaredMimeType?, kind{IMAGE|VIDEO|BINARY}, size, width?, height?, source{EXISTING_FILE|MATERIALIZED}, ownedByStore`。
  - `source=EXISTING_FILE`：Read 命中的项目内原文件，`ownedByStore=false`，`path` 指原位，不复制。
  - `source=MATERIALIZED`：MCP 内联字节落进 artifact store，`ownedByStore=true`。
  - `relativePath` 根相对、短、落在 `FileSystemTools` 沙箱内，模型真需要时可 `Read`。
- **`MediaArtifactStore`**：`put(byte[], declaredMimeType) → MediaArtifact`。
  - **内容寻址**：`full-sha = SHA-256(bytes)`；**文件名用完整 sha**，`id` = 前 16 位仅作显示（规避 64bit 碰撞担忧）。
  - **MIME 不可信**：magic-byte sniff 实际类型，与声明冲突时**信 magic**、引用里两值都记、未知→`.bin`。至少识别 PNG/JPEG/GIF/WebP/PDF/ZIP/MP4/WebM。
  - **原子写**：temp + move 或 `CREATE_NEW`，防并发部分写；owner-only 权限；不跟不可信符号链接；惰性建目录。
  - IO 失败走 slf4j 降级、**日志不含 base64**、**绝不 stdout**（撕 TUI）。
  - **生命周期**：本期随项目保留、不自动 GC；后续按引用扫描 / 最后访问做 GC（记于 §8）。
- **`ImageDimensions`**：只解 PNG / JPEG 头拿宽高（不解码），越界 / 未知返回空。
- **媒体检测（在装饰器内）**：
  - **MCP**：结果解析为 JSON 内容块——**容顶层数组与 `{content:[...]}` 两形**；块 `type:"image"` 或含 `data`(base64)+`mimeType`/`mime_type` → 图像 / 视频媒体；未知块原样保留；单块 base64 解码失败只替该块、不毁其余 text。防「JSON 里恰好有 data+mimeType 文本」误判（需 base64 可解 + magic 命中）。
  - **`Read` 二进制**：结果疑似二进制（空字节 / 大量非法 UTF-8）→ **解析 toolInput 里的文件路径**，按 root 安全解析并回读**原始文件字节**做 sniff/hash/dimensions，`source=EXISTING_FILE` 就地引用；**拿不到 / 越界路径 → 只返回文本告示、绝不造伪文件**（见 §7）。
  - 非媒体（普通文本、含合法 JSON 文本）→ 原样放行（本期不碰）。
- **`MediaExternalizingCallback`**（`ToolCallback` 装饰器）：`delegate.call` 在 guard 外（工具自身异常照常传播）；仅「检测 + 外置 + represent」被 guard，抛错降级为**简短占位**（不得退回原始字节，见 §7）。装在 `ToolEventCallback` 内层（保 `CURRENT_TURN` ThreadLocal 与 `reloadableSkill` 身份判断不变）。

**结构化引用（稳定、可再解析、语言无关）**：

```
[media artifact]
id: sha256:<16hex>
kind: image
mime_type: image/png
declared_mime_type: image/png
size_bytes: 519531
dimensions: 2400x1632
path: .codetui/artifacts/<full-sha>.png
delivery: reference_only
reason: active model pipeline has no native image delivery
[/media artifact]
```

Read 拿不到原路径时的告示：`[Read 返回疑似二进制内容，已从会话移除；无法恢复原始字节]`。
MCP 同数组的 `text` 块（如「Took a screenshot」）保留，模型仍有语义上下文。UI 可把引用块渲成中文短句。

## 5. 装配接线

`AgentTools.build`：装饰循环前构造一次 `MediaArtifactStore`（`root.resolve(".codetui").resolve("artifacts")`）+ handler；循环内 `decorated[i] = new ToolEventCallback(new MediaExternalizingCallback(all.get(i), store, handler), listener)`——能力快照由装饰器从 `ToolContext` 读，不再向装饰器注入 registry supplier。子 agent 走 `decoratedList` 自动继承。`CodingAgent.submit` 侧新增：把 `ModelCapabilities` 快照放进本回合 `toolContext`（主 + 子 agent）。**不改** `AgentRuntime` / `ToolEventCallback` / `McpClientManager` / `FileSessionRepository` 公共行为。

## 6. 测试（离线、模块作用域）

- **前置调查（第一步）**：接本地假 MCP server（返回 text+image 混合），抓 `McpClientManager.toolCallbacks()` 的**真实序列化串**建 fixture——不靠手写 JSON 定协议。
- MCP 契约：顶层数组 / `{content:[...]}` / `type:"image"` / `mimeType`&`mime_type` / text+多 image 混合 / 未知块保留 / 单块解码失败隔离 / data+mimeType 文本不误判。
- MCP 图像块 → 外置 + 结构化引用（返回串无 base64、产物文件存在且 magic 有效、引用含尺寸 / 大小 / 路径、`text` 块保留）。
- Read 二进制（真 PNG）→ `source=EXISTING_FILE` 就地引用（sha/size/dim 基于**原文件**、不复制、无乱码长串）；路径不可解 → 文本告示、**无产物文件**。
- 普通文本 → 原样放行、无产物。
- 畸形 JSON / 非内容块数组 `[1,2,3]` → 不误判、不崩。
- delegate 抛错 → 传播（`assertThrows`）。
- 能力快照：`ToolContext` 带 `supportsImageInput=false` 走引用；`true` + 无注入器 → `canDeliver=false` 仍走引用（证 §3 收敛正确）；`true` + 模拟注入器 → 走 vision 桩（证扩展位通）。
- `MediaArtifactStore`：完整 sha 文件名 / magic 优先于声明 MIME / 原子写 / 惰性建目录；`ImageDimensions` PNG / JPEG 解析。

## 7. 风险与取舍

- **降级不能又灌字节**：`represent` 抛错时，兜底返回「简短占位」而非原始媒体串（否则退回撑爆）。
- **不造伪 artifact**：Read 的乱码 String 重编码 ≠ 原文件，是无效图片且 hash/size 全错、诱导再 Read 死循环。故只走 toolInput 原路径回读原字节；不可得则文本告示，**绝不落假文件**。
- **能力快照按模型冻结**：绑定发起调用的请求，规避工具执行期间切模型的时序错配。
- **旧坏会话不自愈**：本期只拦未来调用；已落盘的巨型历史须删 / 新建（评审列为阻断项，此处按项目决策接受）。
- **全局文本上限缺席**：媒体漏判或巨型纯文本仍可能撑爆；因「不做全局截段」先前决策，本期不加兜底，风险已知。

## 8. 扩展路线（Path B / 后续）

- **视觉注入**：`capabilities().supportsImageInput=true` 且注入器就绪 → `canDeliver=true` → `ToolResultMediaHandler` vision 分支把 artifact 作原生 `Media` / image 块注入本回合消息（有界视觉 token）；配 token 感知压缩老化旧媒体。
- **旧会话迁移**：`LegacySessionMediaSanitizer`（`FileSessionRepository` load / `CodingAgent` submit 前调用），识别内联 base64、外置、替稳定引用；不依赖动态能力。
- **全局兜底上限** / **artifact GC** / **视频抽帧 OCR**。
- 本设计的 `ModelCapabilities` + `ToolResultMediaHandler` + `MediaArtifactStore` 即以上地基。
