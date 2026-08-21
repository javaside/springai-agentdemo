# 设计文档：DeepSeek 视觉模型支持（deepseek-v4-flash-vision-exp）

- 日期：2026-08-21
- 模块：`springai-code-tui`
- 类型：设计（brainstorming spec）
- 前置：[视觉输入 期 1 实施计划](../plans/2026-08-02-vision-input-phase1.md) / [视觉输入 期 2 设计](2026-08-02-vision-user-attachments-design.md) —— 本文是这套通用视觉链路在 **DeepSeek provider** 上的补齐。

> **一句话**：DeepSeek 官方已上线视觉模型 `deepseek-v4-flash-vision-exp` 与 Files API，而
> spring-ai-deepseek 2.0.0 的消息序列化会<b>静默丢弃图片</b>——本设计用项目已有的「HTTP 层改写
> 请求体」先例（`DeepSeekThinkingBodyCodec`）补一条 DeepSeek 专属的图片注入通道，复用既有
> 兑现/预算/UI 全链路，并把 Files API 上传做成可切换的第二通道。

---

## 1. 背景与官方事实（2026-08-21 调研）

### 1.1 视觉模型

- 模型名：`deepseek-v4-flash-vision-exp`（**实验性质**，2026-08-21 上线）。
- 纯文本能力与 `deepseek-v4-flash` 持平；视觉 Agent Benchmark 接近 Opus-4.8。
- **仅此一个模型接受图片**；其它模型传图返回 400「This model does not support image」。
- 图片计费：每张图最多 **384 token**（模型侧先自动缩放：小图放大至约 384×384、大图缩至约
  800×800 等效像素，2000×2000 与 5000×5000 的图 token 相同），价格与 V4-Flash 一致。
- 限制：base64 / 外部 URL 单图 **32 MiB**；Files API `file_id` 单图 **64 MiB**；请求体 48 MiB
  （含 `file_id` 时最高 200 MiB）；单请求最多 **600** 张图；单边最长 8192 px（≥15 张时 4096）。
- 三种传图方式：
  1. **base64 内联**：`{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,<...>"}}`
     —— 必须是带 MIME 前缀的 data URI，不是纯 base64 字符串。
  2. **外部 URL**：`{"type":"image_url","image_url":{"url":"https://...","detail":"low"}}`。
  3. **Files API**：`{"type":"file","file_id":"file-api-xxx"}`（`file` 块也可用 `file_data` + `filename`
     内联，与 `file_id` 互斥）。
- 流式：Chat Completions 的 SSE 流式与 OpenAI 同构（本项目的流式通路无需额外适配）。

### 1.2 Files API

- 上传：`POST https://api.deepseek.com/files`，`Authorization: Bearer <key>`，
  `multipart/form-data`，字段 `file`（必填）+ `purpose`（必填，**只能 `user_data`**）+
  可选 `expires_after[anchor]=created_at`、`expires_after[seconds]=3600..2592000`。
  不传 `expires_after` 默认**永久有效**。
- 限制：单文件 64 MiB、10 分钟内传完、文件名 ≤512 字符、单用户 25 GiB / 10000 个文件。
- 其它端点：`GET /files`（after/limit/order/purpose）、`GET /files/{file_id}`、
  `DELETE /files/{file_id}`。
- 文件归属于 API key，可被任一 API 家族引用；通过 Anthropic 兼容端点引用需带
  `anthropic-beta: files-api-2025-04-14` 头（本项目走 OpenAI 兼容 `/chat/completions`，不需要）。
- 响应：`{"id":"file-api-xxx","object":"file",...}`。

### 1.3 本项目现状（关键事实，已反编译 spring-ai-deepseek 2.0.0 核实）

- **spring-ai-deepseek 2.0.0 序列化消息时只用 `Message.getText()`，`UserMessage` 上的
  `Media` 被静默丢弃**（`DeepSeekChatModel.lambda$createRequest$15`：USER/SYSTEM 分支仅取
  text 构造 `ChatCompletionMessage(String, Role)`）。对比：spring-ai-openai 2.0.0 原生把
  `Media` 序列化成 `image_url` 块。**结论：DeepSeek 视觉不能靠「挂 Media」直接送达，必须在
  HTTP 层改写序列化后的 JSON。**
- `DeepSeekApi.ChatCompletionMessage.content` 是 `String` 类型（record），结构上就装不下
  content 数组——**不可能在对象模型层表达多模态，改写必须发生在 JSON 序列化之后**。
- 项目已有同构先例：`DeepSeekThinkingBodyCodec.decorate`（阻塞，RestClient interceptor）+
  `decorateStreaming`（流式，`DeepSeekThinkingClientHttpConnector` 改写请求体）——注入 thinking
  字段与 `stream_options.include_usage`。**视觉注入可并入同一改写点。**
- 通用视觉链路已就绪且与 provider 无关：`VisionMaterializingChatModel`（兑现装饰器）→
  `VisionMaterializer`（当轮兑现）→ `ImagePreparer`（缩放/转码/字节上限）→ `VisionBudget`
  （分来源配额 + 每回合累计）→ `VisionModels.supportsImage`（模型名单判定）→
  `FileReference`（delivery 五态）。DeepSeekProvider 的 `capabilities()` 已按模型走
  `VisionModels`，只差名单。
- DeepSeek 专属装饰链：`DeepSeekProvider.chatModel()` 返回 `DeepSeekThinkingChatModel`
  （按 ThinkingConfig 路由到固定配置的 native `DeepSeekChatModel`）；在 `AgentTools.build`
  中 `VisionMaterializingChatModel.wrap(provider.chatModel(), root)` 包在其外。

---

## 2. 目标与范围

**目标**：`deepseek-v4-flash-vision-exp` 上，用户贴图与工具产图（Read 一张 png 等）能真正进入
模型；提供 base64 内联与 Files API 双通道；计费与上下文占用沿用既有硬上限。

**本期做**：

1. 模型名单与清单：`VisionModels` 加前缀；`DeepSeekProvider.MODELS` 加模型项（用户可选，默认
   模型仍为 `deepseek-v4-pro`）。
2. DeepSeek 专属图片注入通道（HTTP 层改写，内联 data URI）。
3. Files API 通道（上传 + `file_id` 引用，可按 env 切换）。
4. 预算口径与字节上限按 DeepSeek 实际能力校准（384 token/张、32/64 MiB）。
5. 单测 + 真机探针。

**本期不做**（YAGNI / 边界）：

- 外部 URL 传图：code-tui 的图都来自本地文件，无 URL 入口。
- 子 agent 视觉：`SubagentRunner` 走裸 `provider.chatModel()`，未包视觉兑现装饰器（现状即无
  视觉），本期不扩——与既有行为一致。
- Files API 的过期/配额主动管理（`expires_after`、`DELETE`）：默认永久有效，sha 幂等复用即可；
  不主动删，避免误删他人引用。

---

## 3. 总体架构

```
输入框/工具产图 ──► [file reference] 引用块（会话存储，恒文本）
                        │
                        ▼
        VisionMaterializingChatModel（通用兑现：挂 Media 到 UserMessage）
                        │
                        ▼
        DeepSeekThinkingChatModel（★本期加：把当轮 UserMessage 的 Media 注册进
        │                            DeepSeekVisionMediaRegistry，按「消息序号:图片序号」）
                        │
                        ▼
        native DeepSeekChatModel（序列化——media 被丢，但文本/结构保留）
                        │
                        ▼
        HTTP 层改写（★本期扩展 DeepSeekThinkingBodyCodec / ClientHttpConnector）：
        │    遍历 messages，对每条 user 消息按序号查注册表，
        │    命中则 content 从 string 改写为数组 [text 块 + image_url/file 块]
                        │
                        ▼
        DeepSeek API（内联 data URI 或 file_id）
```

**为什么不在 `VisionMaterializer` 里直接做**：它是通用层，不知道请求最终落到哪家 provider；
OpenAI/Qwen/智谱靠挂 `Media` 就够，只有 DeepSeek 需要序列化后改写。故注入逻辑放在 DeepSeek
专属链路上，其它 provider 零影响。

**为什么用注册表 + 消息序号**：HTTP 改写器只拿得到序列化 JSON，图片字节必须经注册表从
「对象层」传到「JSON 层」。key 用「消息在 `prompt.getInstructions()` 里的下标:该消息内 media
序号」，因为 `DeepSeekChatModel` 转换消息时按 List 顺序遍历、不重排不合并（反编译核实）；
每次请求前清空注册表，天然无泄漏。

---

## 4. 详细设计

### 4.1 模型名单与清单

**改 `VisionModels.java`**（`VISION_PREFIXES` 加一项）：

```java
"deepseek-v4-flash-vision",   // DeepSeek 视觉实验模型（2026-08-21 上线）
```

注意**只能加 `deepseek-v4-flash-vision` 前缀，不能加 `deepseek-v4-flash`**——后者会误伤纯文本
flash。前缀匹配恰好唯一命中 `deepseek-v4-flash-vision-exp`，且未来 `-exp` 后缀移除或改名
（如 `deepseek-v4-flash-vision` 正式版）仍命中。

**改 `DeepSeekProvider.MODELS`**（追加一项，保持 `models.get(0)` = `deepseek-v4-pro` 为默认）：

```java
new ModelOption("deepseek-v4-flash-vision-exp", "deepseek-v4-flash-vision-exp",
        "视觉 · 实验 · 快（图最多 384 token/张）")
```

用户可在 `/model` 切换；`DEEPSEEK_MODELS` 环境变量仍可覆盖整份清单（`ModelListEnv` 逻辑不变）。

**改 `DeepSeekProvider.thinkingCapabilities`**：`deepseek-v4-flash-vision-exp` 是 flash 系非思考
款，`thinking` 字段对它的行为需真机验证；本期先保持现状（`effort(true, low/high/max)`），
默认 `DEFAULT` 不注入任何字段，无行为影响。真机探针顺带记录。

### 4.2 DeepSeek 专属图片注入通道（内联）

**新建 `agent/media/DeepSeekVisionMediaRegistry.java`**：

```java
/**
 * 对象层 → HTTP 改写层的图片字节通道。key = "消息下标:media序号"。
 * 生命周期：一次请求前 putAll、请求体写出后 clear；并发由调用方（请求是串行构建的）保证。
 */
public final class DeepSeekVisionMediaRegistry {
    public record Entry(byte[] bytes, String mimeType) {}
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    public void put(int msgIndex, int mediaIndex, byte[] bytes, String mimeType) { ... }
    public Entry take(String key) { return entries.remove(key); }   // take：消费即删
    public void clear() { ... }
}
```

**改 `DeepSeekThinkingChatModel.java`**（`call` / `stream` 在调 delegate 之前）：

```java
// 新增依赖：VisionMaterializer.isSynthetic 用来跳过合成消息? 否——合成消息也要注册（工具图）
// 遍历 prompt.getInstructions()，对每条 UserMessage（含合成的）取其 getMedia()，
// 逐张按 (i, j) 注册进 registry；之后 delegate.call/stream(prompt)；
// 完成后（call 同步 / stream 的 doOnTerminate）registry.clear()。
```

要点：

- **用户图与工具图都要注册**：用户图挂在锚点 UserMessage 上；工具图挂在合成 UserMessage 上
  （`SYNTHETIC_KEY` 标记）。两者对改写器都是「某条 user 消息 + media 列表」，统一按序号处理，
  无需区分。
- 注册是**幂等且便宜**的（只存引用，不复制字节；PreparedImage 已由 VisionMaterializer 缓存）。
- **未命中不报错**：改写器查不到 key（理论不该发生）就原样放行——绝不让图片注入失败连累请求。

**扩展 `DeepSeekThinkingBodyCodec.java`**：在 `decorate` / `decorateStreaming` 内追加视觉改写
（或拆出独立方法 `decorateVision(byte[], registry)` 组合调用，实现上二选一，倾向后者保持
thinking 与视觉职责分离）。核心逻辑：

```java
JsonNode root = MAPPER.readTree(body);
JsonNode messages = root.get("messages");
for (int i = 0; i < messages.size(); i++) {
    ObjectNode msg = (ObjectNode) messages.get(i);
    if (!"user".equals(msg.path("role").asText())) continue;
    List<ObjectNode> blocks = new ArrayList<>();
    // 文本块：原 content（string）保持为 {type:text,text:...}；若已是数组则保留原有块
    // 图片块：按 (i, j) 从 registry.take(key) 取字节，base64 → data URI →
    //   {"type":"image_url","image_url":{"url":"data:<mime>;base64,<...>"}}
    // 既有数组（理论无，DeepSeek 序列化只产 string）——防御：有则保留，图片块追加
    msg.set("content", MAPPER.valueToTree(blocks));
}
```

要点：

- 只改 `role=user` 的消息；`system`/`assistant`/`tool` 一概不动（图片只出现在 user 消息）。
- 文本块与原 content **逐字一致**（保留引用块、delivery 行等），模型照常能读「这是哪张图」。
- 无任何注册命中 → 返回原 body（零行为变化，纯文本请求不受影响）。
- 流式与阻塞共用同一改写函数（`decorateStreaming` 已委托 `decorate` + 注入 stream_options）。

### 4.3 Files API 通道

**新建 `agent/media/DeepSeekFileStore.java`**：

```java
/**
 * Files API 客户端：按 sha256 幂等上传、进程内缓存 file_id。
 * 上传失败（网络/配额/超限）→ 返回 empty，调用方降级内联——Files 通道是增强，不是依赖。
 */
public final class DeepSeekFileStore {
    public DeepSeekFileStore(String apiKey, String baseUrl, Duration read, Duration connect) { ... }
    public Optional<String> fileIdFor(byte[] bytes, String filename) { ... }
    // 内部：sha256(bytes) → 缓存命中直接返回；否则 multipart POST {file, purpose=user_data}
    // 用 RestClient（项目已有 HttpComponents 超时先例），响应取 id
}
```

**接线**：`DeepSeekProvider` 构造 `DeepSeekFileStore`（key/baseUrl 同源），经
`DeepSeekThinkingChatModel` 传入注册表——注册表 Entry 增加「走 files 还是 inline」：
由 env **`DEEPSEEK_VISION_TRANSPORT=inline|files`**（默认 `inline`）决定；`files` 时注册时即调
`fileIdFor` 上传，改写器写 `{"type":"file","file_id":"..."}`；上传失败或超 64 MiB → 降级内联
（`ImagePreparer` 已把图压到 4 MiB 内，内联永远兜底）。

**为什么默认 inline**：ImagePreparer 把图缩到长边 1568 / ≤4 MiB，远低于 32 MiB 内联上限；
内联零额外往返、无文件生命周期。Files 通道的价值在「原图较大、想省请求体/走官方推荐路径」，
作为可选项保留——用户明确要求双通道，故两者都做、默认内联。

### 4.4 预算与字节上限校准

- **token 预算**：DeepSeek 每张图**最多 384 token**（服务端自动缩放）。现有
  `ImagePreparer.tokensOf = w*h/750`（Anthropic 口径）对 384×384 图估 ≈196，对 1568 边估
  ≈3278 ——**偏保守**（DeepSeek 实际更低），`VisionBudget.MAX_REQUEST_TOKENS=6000` 下不会爆。
  本期**不改**估算函数（通用层保持单一口径），文档注明 DeepSeek 实际计费更省。
- **字节上限**：`ImagePreparer.MAX_BYTES=4 MiB` 已低于 32 MiB 内联上限，**不改**；Files 通道
  上限 64 MiB，但注册前图片已过 ImagePreparer（≤4 MiB），故 Files 通道实际上传的都是 4 MiB
  内小图——**若将来要发挥 64 MiB 价值，需另开「原图直传」路径，本期不做**（见 §2 边界）。

### 4.5 开关与降级

- 视觉能力开关沿用 `CODETUI_VISION=off`（`VisionModels` 全局开关，零改动）。
- Files 传输开关：`DEEPSEEK_VISION_TRANSPORT=files` 显式开启（默认 inline）。
- 降级链：Files 上传失败 → 内联 data URI → 两者都不行（超限/格式不支持，`ImagePreparer`
  已挡）→ 引用块保持原样（`delivery` 不改写），模型看到 `not_in_view` 提示可再 Read。
- **未知模型保护**：`VisionModels` 未知一律不支持（名单外模型即使配置进 `DEEPSEEK_MODELS`
  也不会发图），延续既有「未知即不支持」纪律。

---

## 5. 文件清单

**新建**（`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/`）：

| 文件 | 职责 |
|---|---|
| `DeepSeekVisionMediaRegistry.java` | 对象层 → JSON 改写层的图片字节通道（消息序号:media 序号 → 字节） |
| `DeepSeekFileStore.java` | Files API 上传客户端（sha 幂等 + file_id 缓存 + 失败降级） |

**修改**：

| 文件 | 改动 |
|---|---|
| `agent/media/VisionModels.java` | `VISION_PREFIXES` 加 `"deepseek-v4-flash-vision"` |
| `agent/DeepSeekProvider.java` | `MODELS` 加模型项；构造 `DeepSeekFileStore` 并透传（`DEEPSEEK_VISION_TRANSPORT`） |
| `agent/DeepSeekThinkingChatModel.java` | `call`/`stream` 注册当轮 Media 到 registry，请求后清理 |
| `agent/DeepSeekThinkingBodyCodec.java` | `decorate`/`decorateStreaming` 追加视觉改写（注册表查 key → content 数组） |
| `agent/DeepSeekThinkingClientHttpConnector.java` | 透传 registry 给 codec（构造参数） |

---

## 6. 测试策略

单测（JUnit 5，`mvn test -pl springai-code-tui -Dtest=...`，沿用模块断言纪律——**JUnit
`Assertions`，不用 AssertJ**）：

1. **`VisionModels` 名单**：`deepseek-v4-flash-vision-exp` → true；`deepseek-v4-flash` /
   `deepseek-v4-pro` → false；`CODETUI_VISION=off` 下全 false。
2. **`DeepSeekVisionMediaRegistry`**：put/take 幂等、take 消费即删、clear 全清、未知 key 返回
   empty。
3. **`DeepSeekThinkingBodyCodec` 视觉改写**（纯函数，最重的测试）：
   - user 消息 + 注册命中 → content 变数组，文本块逐字一致 + image_url 块 base64 正确；
   - 无命中 → 原 body 逐字节不变；
   - 非 user 消息不碰；system/assistant/tool 带图片块（防御分支）不动；
   - 流式入口（`decorateStreaming`）同断言 + `stream_options` 仍注入。
4. **`DeepSeekThinkingChatModel` 注册行为**：构造带注册表探测的桩 delegate，断言 call/stream
   前后 registry 内容正确、请求后清空。
5. **`DeepSeekFileStore`**（mock RestClient 或本地 `http.server`）：sha 幂等（同字节两次调用只
   上传一次）、multipart 字段（file + purpose=user_data）、失败返回 empty。
6. **`DeepSeekProvider`**：`capabilities("deepseek-v4-flash-vision-exp").supportsImageInput()` 为
   true；`MODELS` 含新项且 `defaultModel()` 仍为 `deepseek-v4-pro`。

真机探针（可选，挂 `DEEPSEEK_API_KEY`，不进 CI，同 `CodingAgentSpikeTest` 模式）：

- 用 `deepseek-v4-flash-vision-exp` 发一张含文字的小图，断言回答命中图内文字；
- `thinking` 字段对该模型的行为记录（是否报错/忽略）；
- Files 通道上传 + `file_id` 引用一次。

---

## 7. 风险与回退

| 风险 | 影响 | 对策 |
|---|---|---|
| spring-ai-deepseek 升级后消息顺序/序列化改变 | 注册表 key 错位 → 图挂错消息或丢 | 改写器查不到 key 一律放行（fail-open）；单测锁定当前 2.0.0 行为 |
| `deepseek-v4-flash-vision-exp` 是实验模型，行为可能变 | 400/降级 | 名单外不发图；错误在引用块层面可见 |
| Files API 配额/费用（25 GiB、10000 文件） | 上传失败 | sha 幂等 + 默认 inline + 失败降级内联 |
| thinking 字段对 flash 系行为未验证 | 400 或忽略 | 默认 `DEFAULT` 不注入；真机探针记录 |
| base64 使请求体膨胀 ~33% | 略增体积 | 图已压到 ≤4 MiB；Files 通道可切换省体积 |

**回退**：删掉注册/改写两处钩子 + 名单两项即可整体回退；`CODETUI_VISION=off` 为既有逃生口。

---

## 8. 实施建议（后续转 plans）

按依赖顺序：① 名单与清单（最小可用，先让能力判定/模型可选）→ ② 注册表 + 改写器（内联通道，
核心）→ ③ ChatModel 接线 + 单测 → ④ Files 通道 + env 开关 → ⑤ 真机探针。每步独立可测可提交。
