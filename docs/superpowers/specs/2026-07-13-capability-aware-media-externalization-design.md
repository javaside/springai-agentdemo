# 设计文档：能力感知的媒体外置（非文本内容不入会话）

- 日期：2026-07-13
- 模块：`springai-code-tui`
- 类型：设计（brainstorming spec）
- 分支：`feat/tool-output-limit`

## 1. 背景与目标

工具结果里的**非文本内容**（chrome-devtools 截图的 base64、`Read` 读出的二进制、将来的视频等）被原样存进 `ToolResponseMessage.responseData`、每轮随历史重发给模型 → 撑爆上下文（DeepSeek 400 上下文超长 / OpenAI 中转 404）。根因详见 `2026-07-12-tool-output-context-overflow.md`。

**本期目标（聚焦、只做这一件）**：非文本内容（图片/视频/二进制）**不再以字节形式进入会话**。当前模型**无视觉能力 → 不发送这些非文本数据**，会话里只留紧凑引用。

**核心原则——能力感知**：「这个媒体发不发、怎么发」由**当前激活模型有没有视觉能力**决定。现在全是文本模型 → 一律外置 + 引用；以后接入视觉模型 → 同一张图改走原生 image 块。设计必须**留好扩展位**，接视觉时零架构改动。

### 非目标（本期不做）

- 大**文本**（纯文本大文件 / Bash 长输出）截断——与「非文本」无关，另议。
- 视觉 image 块的**真注入**（Spring AI 工具结果是 String、需绕开）——留扩展位、留桩，视觉模型落地时做。
- 视频转写 / 抽帧、OCR 摘要——更后续。

## 2. 设计总览

```
工具结果（单一装饰点：AgentTools 装饰循环拦截）
  → 检测非文本内容：MCP 的 {"data":base64,"mimeType":...} 块 / Read 读出的二进制
  → 外置：原始字节存 <root>/.codetui/artifacts/<id>.<ext>（内容寻址；工具已 filePath 存盘则直接引用其路径）
  → ToolResultMediaHandler(media, 当前模型能力)：
       ├─ supportsVision=false（现在）：结果里换成【文本引用】，字节永不进会话   ← 本期实现
       └─ supportsVision=true （以后）：登记为待注入的原生 image 块              ← 扩展位，留桩
```

会话里存的是**引用**（类型 / 尺寸 / 大小 / 路径 / artifactId），不是字节。原始文件在 `.codetui/artifacts/`（已 gitignore）。

## 3. 两个扩展位（关键）

### 扩展位 ①：`ModelCapabilities` —— 视觉能力开关（按模型）

`/model` 按模型切换、视觉能力常是模型级，故**按模型**判定。

```java
public record ModelCapabilities(boolean supportsVision) {
    public static final ModelCapabilities TEXT_ONLY = new ModelCapabilities(false);
}
```

`LlmProvider` 增一个默认方法（现在全部返回 `TEXT_ONLY`，零行为变化）：

```java
default ModelCapabilities capabilities(String modelId) { return ModelCapabilities.TEXT_ONLY; }
```

装饰器在 `call` 时经 `ProviderRegistry`（`active().capabilities(activeModelId())`）读**当前激活模型**能力——切模型即时生效。接视觉模型时，只需该 provider 覆写 `capabilities()` 对相应模型返回 `supportsVision=true`。**这是唯一的总开关。**

### 扩展位 ②：`ToolResultMediaHandler` —— 表示策略

```java
interface ToolResultMediaHandler {
    /** 给定检测到的媒体产物与当前模型能力，产出它在工具结果里的表示。 */
    String represent(MediaArtifact media, ModelCapabilities caps);
}
```

- 本期只实现 `TextReferenceMediaHandler`：内部按 `caps.supportsVision()` 分支——`false` → 返回**文本引用**；`true` → 留桩（暂退回引用 + `// TODO 视觉：注入原生 image 块`）。当前 caps 恒 `TEXT_ONLY`，故实际只走引用分支。
- 视觉注入需绕开「工具结果 = String」（Spring AI 无媒体工具结果通道）：将来在此策略的 vision 分支登记一条待注入的 `Media`，由消息装配处注入本回合 user 消息——**属 Path B，本期不实现，仅在接口 / 注释里预留位置**。

## 4. 组件（本期实现）

- **`MediaArtifact`**（record）：`id, path, relativePath, mimeType, kind{IMAGE|VIDEO|BINARY}, size, width?, height?`。`relativePath` 根相对（`.codetui/artifacts/<id>.<ext>`），短且落在 `FileSystemTools` 沙箱内、模型真需要时可 `Read`。
- **`MediaArtifactStore`**：`put(byte[], mimeType) → MediaArtifact`；落 `<root>/.codetui/artifacts/<id>.<ext>`；**内容寻址 id = SHA-256 前 16 位**（确定性、幂等、去重、测试可断言，规避随机 / 时间）；扩展名按 mimeType；惰性建目录；IO 失败走 slf4j 降级、**绝不 stdout**（撕 TUI）。
- **`ImageDimensions`**：只解 PNG / JPEG 头拿宽高（不解码），越界 / 未知返回空。
- **媒体检测（在装饰器内）**：
  - MCP：结果解析为 JSON 内容块数组，块含 `data`(base64) + `mimeType` → 图像 / 视频 / 二进制媒体。
  - 内置 `Read` / `Bash`：结果为纯文本但**含空字节 / 大量非法 UTF-8** → 二进制媒体。
  - 非媒体（普通文本、含合法 JSON 文本）→ 原样放行（本期不碰）。
- **`MediaExternalizingCallback`**（`ToolCallback` 装饰器）：`delegate.call` 在 guard 外（工具自身异常照常传播）；仅「检测 + 外置 + represent」被 guard，抛错降级为**简短占位**（不得退回原始字节，见 §7）。装在 `ToolEventCallback` 内层（保 `CURRENT_TURN` ThreadLocal 与 `reloadableSkill` 身份判断不变）。

**引用文案（对文本模型有用、可 Read）**：

```
[图像已离线 image/png 2400x1632 507KB → .codetui/artifacts/<id>.png (artifactId=<id>)；当前模型无视觉，未发送图像内容]
```

MCP 同数组的 `text` 块（如「Took a screenshot」）保留，模型仍有语义上下文。

## 5. 装配接线

`AgentTools.build`：装饰循环前构造一次 `MediaArtifactStore`（`root.resolve(".codetui").resolve("artifacts")`）+ handler + 从 `ProviderRegistry` 取能力的 supplier；循环内 `decorated[i] = new ToolEventCallback(new MediaExternalizingCallback(all.get(i), store, handler, capsSupplier), listener)`。子 agent 走 `decoratedList` 自动继承。**不改** `build` 公共签名 / `AgentRuntime` / `ToolEventCallback` / `McpClientManager` / `FileSessionRepository`。

## 6. 测试（离线、模块作用域，仿 `ToolEventCallbackTest` 手写桩）

- MCP 图像块 → 外置 + 文本引用（返回串无 base64、产物文件存在、引用含尺寸 / 大小 / 路径、`text` 块保留）。
- Read 二进制 → 引用（含 sha / hex / 路径，无长串乱码）。
- 普通文本 → 原样放行、无产物。
- 畸形 JSON / 非内容块数组 `[1,2,3]` → 不误判、不崩。
- delegate 抛错 → 传播（`assertThrows`）。
- `ModelCapabilities`：`supportsVision=false` 走引用分支；`true`（模拟）走 vision 桩（断言留桩行为，证明扩展位通）。
- `MediaArtifactStore` 确定性 id / 扩展名 / 惰性建目录；`ImageDimensions` PNG / JPEG 解析。

## 7. 风险与取舍

- **降级不能又灌字节**：`represent` 抛错时，兜底返回「简短占位」而非原始媒体串（否则退回撑爆）。
- **二进制转文本有损**：Spring AI 无字节通道，`Read` 拿到的已是乱码 String；artifact 存的是该 best-effort 表示（size / sha 基于乱码文本，非原文件字节）。可接受（目标是把乱码移出会话）；真·字节恢复属 Path B。
- **能力判定按模型**：需 `ProviderRegistry` 暴露当前 `capabilities`；接视觉模型时仅覆写对应 provider 的 `capabilities()`。

## 8. 扩展路线（Path B，后续另分支）

视觉模型接入：`capabilities().supportsVision=true` → `ToolResultMediaHandler` 的 vision 分支把 artifact 作为原生 `Media` / image 块注入本回合消息（有界视觉 token）；配合 token 感知压缩老化旧媒体。本设计的 `ModelCapabilities` + `ToolResultMediaHandler` + `MediaArtifactStore` 即其地基。
