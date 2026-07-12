# 设计文档：文件内容不入会话记忆（媒体即时外置 + 文本回合间外置）

- 日期：2026-07-13（三次修订：纳入外部评审 + 更正工具循环时序）
- 模块：`springai-code-tui`
- 类型：设计（brainstorming spec）
- 分支：`feat/tool-output-limit`

> 核心原则：**文件内容不驻留会话记忆**。会话历史里存的是引用（文件名/路径 + 元信息），不是字节/全文。内容只在「产生它的那一回合」出现在模型上下文里，回合结束后塌成引用；下次要用，模型重读。

## 1. 背景与目标

工具结果里的文件内容（chrome-devtools 截图 base64、`Read` 的文件全文/二进制、将来视频）被原样存进 `ToolResponseMessage.responseData`、**每轮随历史全量重发** → 撑爆上下文（DeepSeek 400 上下文超长 / OpenAI 中转 404）。根因见 `2026-07-12-tool-output-context-overflow.md`。

**目标**：任何文件的内容都不进持久会话历史，只留紧凑引用。当前模型无视觉能力 → 图片/二进制字节根本不发。留扩展位，后续接视觉模型时同一张图改走原生 image 块。

### 非目标（本期不做，均已记录为有意识取舍）

- **大纯文本的全局字符硬上限**（评审建议的 `ToolOutputLimiter` 64–128K 兜底）——与「按回合边界外置文件内容」是两条线；先前已定「不做全局截段」，本期不加、备查可反悔。
- **视觉 image 块真注入**（Spring AI 工具结果是 String、需绕开）——留桩，Path B。
- **旧坏会话自愈**（已落盘的巨型 base64 历史）——不自动迁移，须删/新建；`FileSessionRepository` 不改。
- **单回合内预算**：一条消息里读太多大文件，那一回合在途仍可能超限（罕见，真出事的是单张图）——Path B。
- 视频抽帧/OCR——更后续。

## 2. 关键时序：为什么拦截点是「回合之间」，不是「落库处」（务必先读）

已用字节码核实 spring-ai 2.0 的工具循环：

- `ToolCallingAdvisor`（order **+300**，外层）驱动工具循环——每步 `internalStream → chain.nextStream(下一请求)`，**反复重进内层 advisor 链**。
- `SessionMemoryAdvisor`（order **+1000**，内层）因此**每个工具迭代都跑一次 `before()`**；`before()` 内 `SessionService.getEvents()` **无条件从存储重载历史**，`merged = 存储历史 + 本请求消息`，并 `appendMessage` 把最新一条 tool/user 消息**当场写回存储**。

**推论**：若在「工具结果写入存储那一刻」就外置成引用，则读完 A、递归去读 B 时，`before()` 重载到的 A 已是引用 → **任务没跑完 A 就丢了**。故**「落库处/mid-loop 外置」是错误方案**，本文档留档防再犯。

**正确时机 = 回合与回合之间，只动过往事件：**

| 拦截点 | 处理 | 安全性 |
|---|---|---|
| **`submit()` 开头**（发请求前，`CodingAgent.java:166` 已有 `sanitize`/`trimDangling` 批次） | 遍历**已存在历史事件**（全属**过往回合**），把文件/文本内容换成引用 | 此刻本回合尚无任何工具结果；被外置的都是上一回合及更早，碰不到本回合的读 |
| **本回合工具循环内**（读 A→B→C…） | **完全不碰**，全文一路留在在途上下文 | 本回合的读要到**下一次** `submit()` 开头才外置 |
| **装饰器（媒体专用）** | 图片/视频/二进制**当场**抽引用 | 单张图光当回合就能撑爆、且文本模型对图字节没用，必须即时；经 `appendMessage` 落成引用、后续重载也是引用 |

**净效果**：文件内容在产生它的那一回合全程可见（读 A 后读 B，A 还在）→ 任务正常跑完；下一条用户消息进来，上一回合文件内容塌成引用，要用再重读。

## 3. 两类内容、两条外置路径

```
① 媒体（图片/视频/二进制）——即时、连当回合都不进模型
   工具装饰点（AgentTools 装饰循环，ToolEventCallback 内层）
     → 检测：MCP 内容块 image/binary、Read 疑似二进制
     → 外置：MCP 内联字节存 artifact；Read 用 toolInput 原路径就地引用（不复制）
     → 结果串换成结构化引用 → appendMessage 落成引用 → 永不进模型

② 文本文件内容——回合间、当回合保留全文
   submit() 开头，遍历已存在历史事件（过往回合）
     → 找到携带文件全文的 ToolResponseMessage（Read 大文本等）
     → 外置：优先引用原文件路径（Read 场景，文件在项目内，不复制）；无源则存 artifact
     → 用结构化引用替换该事件的内容，replaceEvents 写回
   本回合工具循环内的读：不动，全文在途
```

## 4. 两个扩展位（接视觉零架构改动）

### 扩展位 ①：`ModelCapabilities`——能力快照（按模型、随请求冻结）

```java
public record ModelCapabilities(boolean supportsImageInput, boolean supportsVideoInput) {
    public static final ModelCapabilities TEXT_ONLY = new ModelCapabilities(false, false);
}
```

`LlmProvider` 增默认方法（现全返 `TEXT_ONLY`，零行为变化）：`default ModelCapabilities capabilities(String modelId){ return TEXT_ONLY; }`。

**能力绑定「发起调用的模型」**：`submit()` 冻结快照进 `toolContext`（规避工具执行期间切模型的时序错配，子 agent/并行工具尤甚）：

```java
.toolContext(Map.of("turnId", turnId, "providerId", active.id(),
                    "modelId", activeModelId, "capabilities", capabilities))
```

装饰器读能力优先级：**`ToolContext` 快照 > `ProviderRegistry` 兜底 > `TEXT_ONLY`**。

### 扩展位 ②：`ToolResultMediaHandler`——能否投递 + 表示

```java
interface ToolResultMediaHandler {
    boolean canDeliver(MediaKind kind, ModelCapabilities caps); // 模型能力 && 注入器已实现
    String  represent(MediaArtifact media, ModelCapabilities caps);
}
```

- 投递模式 = `模型支持该类输入 && 本链路已接注入器`。本期**无注入器** → `canDeliver` 恒 false → 全走 REFERENCE_ONLY（避免「`supportsImageInput=true` 就以为图已真进模型」误解）。
- 本期只实现 `TextReferenceMediaHandler`：`canDeliver`→false；`represent`→结构化引用。vision 分支仅注释留桩（Path B）。

## 5. 组件（本期实现）

- **`MediaArtifact`**（record）：`id, path, relativePath, mimeType, declaredMimeType?, kind{IMAGE|VIDEO|BINARY|TEXT}, size, width?, height?, lineCount?, source{EXISTING_FILE|MATERIALIZED}, ownedByStore`。
  - `EXISTING_FILE`：Read 命中的项目内文件，`ownedByStore=false`，`path` 指原位，不复制。
  - `MATERIALIZED`：MCP 内联字节 / 无源文本 → 落 artifact store，`ownedByStore=true`。
- **`MediaArtifactStore`**：`put(byte[], declaredMimeType) → MediaArtifact`。内容寻址：**文件名用完整 SHA-256**，`id`=前 16 位仅显示。**MIME 不可信**：magic-byte sniff，与声明冲突信 magic、两值都记、未知→`.bin`；至少识别 PNG/JPEG/GIF/WebP/PDF/ZIP/MP4/WebM。**原子写**（temp+move / `CREATE_NEW`）、owner-only、不跟不可信符号链接、惰性建目录。IO 失败走 slf4j、**日志不含 base64**、**绝不 stdout**（撕 TUI）。**生命周期**：本期随项目留、不自动 GC（Path B 做按引用扫描 GC）。
- **`ImageDimensions`**：只解 PNG/JPEG 头拿宽高，越界/未知返回空。
- **媒体检测器（装饰器内，路径①）**：
  - **MCP（S0 已用字节码核实真实线格式）**：`SyncMcpToolCallback.call()` 返回 `JsonHelper.toJson(CallToolResult.content())` = 内容块 `List` 的 JSON = **顶层数组**（此版本非 `{content:[...]}`）。`Content` 带 `@JsonTypeInfo(property="type")`，每块有权威判别符 **`type`** ∈ `text|image|audio|resource`；`ImageContent` = `type:"image"` + `data`(base64) + `mimeType`(**驼峰**)。检测**以 `type` 为准**（`image`/`audio` → 媒体；`resource` 的 `BlobResourceContents`(`blob`+`mimeType`) → 二进制媒体；`text` → 保留），**不靠「碰巧含 data/mimeType」启发式**（消除误判）。单块 base64 解码失败只替该块；未知 `type` 原样留。为防他家 server/中转差异，`{content:[...]}` 包裹与 `mime_type` 蛇形作**兼容降级**保留但非主路径。
  - **`Read` 二进制**：疑似二进制（空字节/大量非法 UTF-8）→ 解析 toolInput 路径、按 root 安全解析、回读**原文件字节**做 sniff/hash/dim，`EXISTING_FILE` 就地引用；**拿不到/越界路径 → 只返回文本告示、绝不造伪文件**。
- **`MediaExternalizingCallback`**（`ToolCallback` 装饰器，路径①）：`delegate.call` 在 guard 外（工具异常照传）；仅「检测+外置+represent」被 guard，抛错降级为**简短占位**（不得退回原始字节，见 §7）。装在 `ToolEventCallback` 内层（保 `CURRENT_TURN` ThreadLocal 与 `reloadableSkill` 身份判断不变）。
- **`SessionFileExternalizer`**（路径②，`submit()` 开头调用）：对携带文件全文的 `ToolResponseMessage` 外置为引用，`replaceEvents` 写回。与既有 `SessionEvents.sanitize` / `trimDanglingToolCalls` **同批次、搭同一趟遍历**（不新增 O(n) 扫描）。**增量而非全量**：
  - **水位线**：记「已外置到的事件版本号」（用 `getEventVersion` 的版本/事件 id，**不用下标**——`trimDanglingToolCalls`/`replaceEvents` 会删事件致下标错位）。每回合只处理**水位线之后的新事件**（≈上一回合新增的读，一条或几条），处理完推进水位线 → **O(上回合新增)，非 O(全历史)**。
  - **幂等安全网**：判断「已是引用则跳过」；即便水位线记错也不漏改/重复改。
  - **无新全文 → 纯 no-op**：不调 `replaceEvents`、不写盘。
  - **只碰过往事件**：本回合 `submit()` 开头时尚无本回合工具结果，天然不动本回合的读。

**结构化引用（稳定、可再解析、语言无关）**：

```
[file reference]
id: sha256:<16hex>
kind: image            # image | video | binary | text
mime_type: image/png
size_bytes: 519531
dimensions: 2400x1632   # 图；文本用 lines: 8900
path: src/main/java/Foo.java   # EXISTING_FILE 指原路径；MATERIALIZED 指 .codetui/artifacts/<sha>.<ext>
delivery: reference_only
reason: content externalized from session memory; re-read to view
[/file reference]
```

MCP 同数组的 `text` 块（如「Took a screenshot」）保留。UI 可把引用块渲成中文短句。

## 6. 装配接线

- `AgentTools.build`：装饰循环前构造一次 `MediaArtifactStore` + handler；循环内 `decorated[i] = new ToolEventCallback(new MediaExternalizingCallback(all.get(i), store, handler), listener)`。能力快照由装饰器从 `ToolContext` 读。子 agent 走 `decoratedList` 自动继承。
- `CodingAgent.submit`：①开头调 `SessionFileExternalizer.externalize(sid)`（路径②，紧邻现有 sanitize）；②`toolContext` 加 `ModelCapabilities` 快照（主+子 agent）。
- **不改** `AgentRuntime` / `ToolEventCallback` / `McpClientManager` / `FileSessionRepository` 公共行为。

## 7. 测试（离线、模块作用域）

- **前置调查（第一步）**：接本地假 MCP server（返回 text+image 混合），抓 `McpClientManager.toolCallbacks()` **真实序列化串**建 fixture——不靠手写 JSON 定协议。
- MCP 契约：两形/`type:"image"`/`mimeType`&`mime_type`/text+多 image 混合/未知块保留/单块失败隔离/data+mimeType 文本不误判。
- 路径①媒体：MCP 图像块 → 外置+引用（返回串无 base64、产物 magic 有效、`text` 块保留）；Read 二进制 → `EXISTING_FILE` 就地引用（sha/size/dim 基于原文件、不复制）；路径不可解 → 告示、无产物。
- 路径②文本：`SessionFileExternalizer` 把历史里携带文件全文的 tool 结果换成引用、`replaceEvents` 写回；**幂等**（二次调用 no-op）；**只动过往、不动本回合**（构造「尾部即当前回合」的用例断言尾部全文保留）。
- 能力快照：`ToolContext` `supportsImageInput=false`→引用；`true`+无注入器→`canDeliver=false` 仍引用；`true`+模拟注入器→vision 桩（证扩展位通）。
- `MediaArtifactStore`：完整 sha 文件名 / magic 优先 / 原子写 / 惰性建目录；`ImageDimensions` PNG/JPEG。
- delegate 抛错 → 传播（`assertThrows`）。

## 8. 风险与取舍

- **降级不灌字节**：`represent`/外置抛错兜底返回简短占位，绝不退回原始媒体串。
- **不造伪 artifact**：Read 乱码 String 重编码 ≠ 原文件；只走 toolInput 原路径回读，不可得则文本告示。
- **落库处外置是错的**（§2）：`before()` 每迭代重读存储会撕掉本回合的读；必须回合间、只动过往。
- **跨消息重读**：上一回合读的文件到下一回合变引用，要用重读一次（cheap、可命中缓存）；想更顺可加「近 K 次读保留全文」的按新近度分级（接既有 `RecursiveSummarizationCompactionStrategy`）——Path B。
- **旧坏会话不自愈**、**单回合内预算缺席**、**全局文本上限缺席**：均已知、归 Path B / 备查。

## 9. 扩展路线（Path B）

视觉真注入（`canDeliver=true` → vision 分支注入原生 `Media`/image 块，有界视觉 token）、旧会话 `LegacySessionMediaSanitizer`、按新近度分级保全文、artifact GC、单回合预算、全局文本兜底。地基即本设计的 `ModelCapabilities` + `ToolResultMediaHandler` + `MediaArtifactStore` + 两条外置路径。
