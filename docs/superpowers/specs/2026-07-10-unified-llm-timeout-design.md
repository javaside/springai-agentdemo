# 统一 LLM 请求超时（修复流式 callTimeout 误用 + 可配 read 超时）

> 修复「切到 OpenAI/智谱就 Stream failed」的真凶：spring-ai-openai 把 SDK 的 request 超时映射成 OkHttp
> callTimeout 并对流式照套，叠加 read 默认 60s 过短，导致流式对话被无条件砍断。改用适配流式的 read 超时、
> 禁用 callTimeout，并统一为四家 provider 提供一个可配的读超时入口。

## 背景与现象

用户报告：模型切到 **OpenAI 或智谱**都会 `Stream failed`（单发一条普通消息即可复现，与并行/子 agent 无关），
而 **DeepSeek 不会**。日志根因链（多次复现一致）：

```
com.openai.errors.OpenAIIoException: Stream failed
  └─ java.io.InterruptedIOException: timeout
      └─ okhttp3.internal.http2.StreamResetException: stream was reset: CANCEL
```

## 根因（字节码坐实）

反编译 `spring-ai-openai-2.0.0` 的 `SpringAiOpenAiHttpClient.newCall(...)`：它把官方 OpenAI Java SDK
`com.openai.core.Timeout` 的四个维度全量映射到 OkHttp：

| SDK Timeout 维度 | 默认值 | 映射到 OkHttp |
|---|---|---|
| `connect` | 1 min | `connectTimeout` |
| `read` | **1 min** | `readTimeout` |
| `write` | 1 min | `writeTimeout` |
| `request` | **10 min** | **`callTimeout`** |

（默认值来自 `com.openai.core.Timeout` 字节码：connect/read/write=`ofMinutes(1)`、request=`ofMinutes(10)`。）

两个致命点，对**流式（SSE）**请求：
1. **`readTimeout` 默认 60s 是主凶**：SSE 下 readTimeout 管的是「两个数据块之间的最大间隔」。强推理模型
   （deepseek-v4-pro、GLM-5.2 思考模式）或慢 relay 在**思考期 >60s 不吐下一个 token**，readTimeout 触发 →
   cancel 流 → 正是上面的异常链。表现为「一下/很快就 Stream failed」。
2. **`callTimeout`（=request 10min）是次凶**：callTimeout 管**整个调用总时长**（含流式响应体读完），到点
   **无条件 CANCEL 整条 HTTP/2 流**。长回答迟早触顶被砍。callTimeout 对持续生成的流式响应是**错误的超时类型**。

**为何 OpenAI 与智谱同时中招**：智谱 provider 复用 spring-ai-openai（GLM 与 OpenAI 兼容），底层是**同一套**
`SpringAiOpenAiHttpClient`，故共享此缺陷。
**为何 DeepSeek 不中招**：DeepSeek 走 Spring `RestClient`，其 `ClientHttpRequestFactory` **没有 callTimeout 概念**，
只有 connect/read；天然没有「总时长砍流」问题。这解释了用户观察到的「只有 DeepSeek 没超时」。

**结论**：这不是「超时太长」，而是**超时类型对流式用错了**。单纯调大 callTimeout 治标不治本——长对话仍会触顶，
且 read 60s 的主凶未解决。正确修法是**换类型**：用合理的 read 超时、**禁用 callTimeout**。与并行子 agent、
与本仓 `runAll` 代码均无关。

## 决策总览（已与用户逐项确认）

| 决策点 | 结论 |
|---|---|
| 超时粒度 | 单一配置入口（一个环境变量），非多维度暴露 |
| read 默认值 | **300s**（取代祸首 60s；容忍推理思考期/慢 relay 首字节） |
| callTimeout(request) | **禁用（设 0）**——流式绝不能用总时长超时 |
| connect | 固定 30s，不暴露配置 |
| 覆盖范围 | **三家 OpenAI-SDK 家族**（OpenAI/智谱/Anthropic，共享 callTimeout 流式 bug）+ 主 agent(stream) 与子 agent(call)；**DeepSeek 保持现状不接**（无此 bug；见下） |
| 配置入口 | 环境变量 `CODETUI_LLM_READ_TIMEOUT_SECONDS`，默认 300，钳制 [10, 3600] |

## 架构与组件

**新增单元 `LlmTimeouts`（单一职责：超时配置解析）**
- 读 `CODETUI_LLM_READ_TIMEOUT_SECONDS`：null/blank/非法 → 300；否则 `parseInt` 后钳制到 `[10, 3600]`。
- 暴露 `Duration readTimeout()`（配置值）、`Duration connectTimeout()`（固定 30s）。
- 纯函数、无副作用，便于单测全覆盖。各 provider 复用它，避免重复读环境变量。

**三家接线（共用 `LlmTimeouts` 解析出的 read/connect；OpenAI/智谱/Anthropic 均走各自 SDK 的
`httpClientBuilderCustomizer`，在其中构造带 `request=ZERO` 的 Timeout——精确禁用 callTimeout）**

| Provider | 接线机制（规划期已从字节码坐实 API） |
|---|---|
| OpenAI / 智谱 | `OpenAiChatModel.Builder.httpClientBuilderCustomizer(c)`；`c` 内 `builder.timeout(com.openai.core.Timeout.builder().connect(connect).read(read).write(read).request(Duration.ZERO).build())`。两家逻辑相同，抽成共享 helper。 |
| Anthropic | `AnthropicChatModel.Builder.httpClientBuilderCustomizer(c)`；`c` 内 `builder.timeout(com.anthropic.core.Timeout.builder()...request(Duration.ZERO).build())`。**同 OpenAI-SDK 家族、同 callTimeout bug**，故同样用 customizer + request=ZERO（**不**用 `options.timeout(Duration)`——那映射到单一 request/callTimeout，是错的维度）。 |
| DeepSeek | **不改**。DeepSeek 走 Spring `RestClient`/`WebClient`，无 callTimeout 概念，实测不超时；全覆盖需同时配 RestClient+WebClient 两处，收益低、给工作正常的 provider 引入风险，故保持现状。环境变量对 DeepSeek 不作用。 |

**数据流**：`LlmTimeouts.fromEnv()` → 各 `*Provider.chatModel()` 在建 ChatModel 时读取并注入底层 client 超时 →
主 agent `.stream()` 与子 agent `.call()` 都经此 client 发请求，故超时对两者统一生效。

## 错误处理与 UI

- **主 agent（流式）**：read 超时 → 流 `onError` → 现有 `CodingAgent.handleError(err, turnId)` 上报 → UI 显示错误、
  回合结束。行为不变，只是**最长等 300s 就干净失败**，不再被 60s 主凶砍或 10min 卡死。
- **子 agent（阻塞 `.call()`）**：read 超时 → `.call()` 抛异常 → 被 `runAll` 失败隔离捕获成 `"失败：…"` 槽，
  或单个 `Task` 的现有异常路径。已有行为，无需改。
- 超时错误按底层异常原样上报，不新增接缝。（可选 nice-to-have：识别 `InterruptedIOException`/timeout 时补一句
  「响应超时，可重试或检查网络/代理」——不阻塞。）

## 测试策略（TDD）

1. **`LlmTimeouts` 单测（核心，纯函数全覆盖）**：默认 300；非法/空回退 300；钳制边界（<10→10、>3600→3600）；
   `readTimeout()` 返回配置值、`connectTimeout()` 返回固定 30s。
2. **provider 装配单测**：四家用假 key 构建 ChatModel 不抛异常（网络无关，沿用现有 `LlmProviderTest` 套路）。
   **不**用反射断言底层 OkHttp callTimeout 具体值（进 SDK 私有字段太脆，不值当）。
3. **实机验证（用户执行）**：设 `CODETUI_LLM_READ_TIMEOUT_SECONDS=5` + 慢/挂的 relay，确认约 **5 秒**就干净失败
   （不再 60s 或卡死），UI 正常回 IDLE。这是唯一能真正验证「超时类型改对」的手段。

## 验证命令基线

`mvn -pl springai-code-tui test`（模块作用域）。

## YAGNI / 明确不做

- **不做**自动重试（超时后重试是独立功能，另议）。
- **不暴露** connect/callTimeout 配置（只给一个旋钮：read）。
- **不改**四家 provider 的其它行为，仅注入超时。
- **不碰**并行子 agent（`feat/parallel-subagents` 的 Task 5 实机验证独立挂着）。
- **不美化**错误文案为硬需求（友好提示仅作可选项）。
