# LLM 流空闲超时设计

## 背景

OpenCode Go 套餐额度耗尽时，网关可能不返回 402/429 或错误响应，而是让 HTTP/2 请求永久停在等待响应头。当前 OpenAI SDK 接线把 `request` 超时设为 `Duration.ZERO`，以避免固定总时长误杀正常长流式请求；现有 socket read timeout 在该 HTTP/2 响应头等待路径上没有终止请求。结果是模型流既不完成也不报错，`CodingAgent` 一直保持运行状态。

## 目标

- 模型流订阅后长时间没有首个响应时，以明确超时错误终止。
- 模型流开始后，任意两个下游 chunk 之间长时间没有新数据时，以同类错误终止。
- 持续产生数据的长流式任务不受总耗时限制。
- 超时后沿用现有 `CodingAgent.handleError()` 路径，把会话恢复到 `IDLE`。
- 主 agent 和通过流式聚合调用模型的子 agent 都获得相同保护。

## 非目标

- 不识别或解析 OpenCode Go 私有额度错误格式。
- 不重新启用 OkHttp/SDK 的固定总请求时长 `callTimeout`。
- 不改变 provider 的 connect/read/write 超时接线。
- 不增加新的环境变量。
- 不自动重试额度耗尽或网关挂起的请求。

## 方案

新增一个职责单一的 `ChatModel` 装饰器，在 `stream(Prompt)` 返回的 `Flux<ChatResponse>` 上应用 Reactor 空闲超时。超时时长复用 `LlmTimeouts.readTimeout()`，即现有 `CODETUI_LLM_READ_TIMEOUT_SECONDS`；默认 300 秒，继续使用现有的 `[10, 3600]` 钳制规则。

超时从订阅时开始计时。收到每个 `ChatResponse` 后重新计时。因此它同时覆盖：

1. HTTP/2 已建立但一直收不到响应头或首个模型 chunk；
2. 已收到部分流式输出，随后上游不再发数据且不结束；
3. 上游通过代理保持 TCP 连接、但没有向模型流产生有效事件。

该计时不是整个调用的固定截止时间。只要每个相邻 chunk 的间隔没有超过配置值，一个总耗时超过 300 秒的正常流仍可继续运行。

装饰器的同步 `call(Prompt)` 原样委托。当前子 agent 的兼容路径通过 `RetryingChatModel.streamAndAggregate()` 使用流式调用，因此会受保护；不为同步调用引入新的语义。

## 接线

在 `ProviderRegistry` 完成具体 provider `ChatModel` 选择后、交给 `CodingAgent` 和其他已有装饰器之前统一包装。包装位置必须确保：

- 所有 provider 都覆盖，而不是只处理 OpenCode Go/OpenAI；
- 主 agent 的 `ChatClient.prompt().stream()` 覆盖；
- 子 agent 的流式聚合覆盖；
- `UsageRecordingChatModel` 的正常完成、错误和取消记账语义保持不变。

具体装饰器顺序以现有 `ProviderRegistry` 接线为准，实施时通过测试确认超时取消能传播到最底层模型流，且 usage 装饰器收到正常的取消/错误终止。

## 错误处理

超时应转换为稳定、可读的运行时异常，消息包含：

- 等待模型流数据超时；
- 实际阈值秒数；
- 可通过 `CODETUI_LLM_READ_TIMEOUT_SECONDS` 调整。

异常继续进入现有 Reactor `onError` 链：

`ChatModel stream timeout -> CodingAgent.handleError() -> listener.onError() -> ConversationState.IDLE`

不在装饰器里直接操作 UI 或会话状态。Reactor 取消应向上游传播，使 SDK/OkHttp call 有机会关闭 HTTP/2 stream。

## 测试

新增装饰器单元测试，使用 Reactor 虚拟时间或短测试时长覆盖：

1. `Flux.never()`：订阅后无首包，阈值到达即报可读超时错误；
2. `Flux.just(first).concatWith(Flux.never())`：首个 chunk 后停流，阈值到达即报错；
3. 持续输出：总耗时超过单次阈值，但相邻 chunk 间隔均小于阈值，正常完成；
4. 正常完成：值和完成信号不改变；
5. 上游错误：原始错误不被替换；
6. 下游取消：取消传播到上游，不额外产生超时错误；
7. 同步 `call()`：原样委托。

补充接线测试，确认注册表返回的主模型和子 agent 使用的模型流都经过空闲超时保护。保留现有 `OpenAiTimeoutsTest` 与 `ProviderTimeoutRuntimeTest`，它们继续验证 SDK/socket 层超时，不承担 Reactor 活性保护的覆盖职责。

## 成功标准

- OpenCode Go 额度耗尽并保持 HTTP/2 请求悬挂时，默认最多等待约 300 秒，随后 UI 显示超时错误并恢复可提交状态。
- 设置 `CODETUI_LLM_READ_TIMEOUT_SECONDS=N` 后，首包和相邻 chunk 空闲阈值均为 N 秒。
- 正常持续输出的流不会因总时长超过 N 秒而失败。
- 相关单元测试、模块测试和项目构建通过。
