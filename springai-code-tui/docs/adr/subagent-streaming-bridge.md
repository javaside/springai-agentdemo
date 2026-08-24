# 子 agent 流式传输桥接（RetryingChatModel）

> 适用版本：Spring AI 2.0.0、openai-java SDK 4.39.1（经 spring-ai-openai 间接依赖）。
> 根因结论以 **curl 直连网关实测**（2026-07-15）为准，非日志推断；修复经 400 用例 + 用户实机确认。

## 1. 一句话总览

子 agent（`Task` / `ParallelTasks`）间歇性报 `Error reading response`，根因是**代理网关的非流式端点存在分钟级坏窗口**（HTTP 200 + 空 body）；治本方案是把子 agent 的阻塞式 `call()` 在 ChatModel 层**桥接到流式** `stream()` + `MessageAggregator` 聚合（`RetryingChatModel`），重试只作二道防线。

## 2. 故障现象与根因

- **现象**：子 agent 偶发失败，SDK 抛 `OpenAIInvalidDataException("Error reading response")`，cause 是 Jackson `No content to map due to end-of-input`——即 HTTP 2xx 但 body 为空/不可解析。主 agent（流式）全天稳定。
- **根因（curl 实锤）**：从运行中进程 `ps eww <pid>` 拿到真实 `OPENAI_BASE_URL` 环境变量后 curl 直连复现——网关**非流式** `/chat/completions` 端点存在**长达数分钟的坏窗口**，期间几乎 100% 请求返回 200 + 空 body（一轮实测 8/8 全空；好窗口则 3 发 2 中）。同窗口**流式**端点持续正常。
- **SDK 行为**：openai-java 的 `JsonHandler` 在 2xx 上直接反序列化 body，失败即抛；SDK 内置重试只覆盖 429/5xx/连接错误，**不覆盖「2xx + 坏 body」**。
- **另一副面孔**：坏窗口下网关也可能回「正常完成但零内容」（不抛异常），导致子 agent 把空串静默交回主 agent、主 agent 误判「子代理返回空响应」。

## 3. 三轮修复的取舍（方法论教训）

| 轮次 | 动作 | 结果 |
|---|---|---|
| 1 | 只加诊断（cause 链摊平 + 全栈日志） | 抓到 Jackson end-of-input，证实空 body，但未修复 |
| 2 | `call()` 级重试（当时是 300ms 线性退避 ×3） | **穿不过分钟级坏窗口**——用户看到「一直在重试」 |
| 3 | 换传输路径：call 桥接流式聚合 | 修复；重试降级为二道防线 |

**核心教训：评估重试方案前先量化故障持续时长。** 秒级抖动重试有效；分钟级窗口无论退避多少次都穿不过去，必须绕道（换到实测健康的传输路径）。

## 4. 方案：为什么在 ChatModel 层桥接

```
SubagentRunner.run
  └─ ChatClient.builder(RetryingChatModel.wrap(provider.chatModel()))
        └─ ToolCallingAdvisor（框架自动注册）→ 工具循环，每次 LLM call 调 ↓
              └─ RetryingChatModel.call(prompt)
                    └─ delegate.stream(prompt) + MessageAggregator 聚合 → 单个 ChatResponse
```

- **对工具循环透明**：`ToolCallingAdvisor.adviseCall` 在桥接层之上，感知不到底层换了传输；重试保持「单次 LLM call」粒度——循环中途某次失败不丢已完成的工具迭代。
- **聚合用框架自带 `MessageAggregator`**：与 ToolCallingAdvisor 流式路径同款，工具调用增量已由各 provider 的 stream 实现合并成完整 ToolCall，聚合结果对工具循环等价。
- **空流守卫**：聚合结果既无文本也无工具调用 → 视同瞬态失败重试，穷尽后抛出——绝不把空串静默交回主 agent。
- **不重试取消/中断**：cause 链上出现 `InterruptedException` / `CancellationException` 直抛（Esc 取消回合要立即退出，且中断标志位保留）。
- **`shouldRetry` 判据**：cause 链逐层判断，初版只认 `*InvalidDataException` 类名后缀 + Jackson `No content to map`；`cbba97f` 按生产日志扩到五类瞬态并加了红线，见下方「7. 参数与判据」。

## 5. 踩坑：装饰 ChatModel 必须转发 2.0 的 getOptions()

第一版装饰器只覆写了 deprecated 的 `getDefaultOptions()`，漏了 Spring AI 2.0 新增的 `getOptions()`，引发两个故障：

1. `ChatClient` 构建请求时从 `getChatModel().getOptions().mutate()` 取基础 options，漏转发会落到接口 default（裸 `DefaultChatOptions`）→ provider ChatModel 强转家族 options 直接 `ClassCastException: DefaultChatOptions → OpenAiChatOptions`；
2. options 不是 `ToolCallingChatOptions` 时 `ToolCallingAdvisor` **整个跳过**——子 agent 静默丢全部工具，比崩溃更隐蔽。

**测试为什么没拦住**：测试用的假 ChatModel 也只实现了旧式方法，与生产装饰器「错得一致」。修复后补了 `forwardsGetOptionsToDelegate` 用例，并把所有假 ChatModel 转成流式实现（`call()` 抛 `UnsupportedOperationException`），钉死桥接路径。

## 6. 配套：失败文本摊平 cause 链

SDK 顶层 message 笼统（`Error reading response`），根因在 cause 里，而工具异常处理器只把 `getMessage()` 交回模型。`SubagentRunner.describe()` 把 cause 链摊平成 `顶层 ← Cause类名: 根因` 文本（去重相邻重复、循环链封顶 5 层），重抛 `SubagentFailedException`（message=摊平文本、cause 保留供日志全栈）。

## 7. 参数与判据

> 本节在 `cbba97f` 随代码更新过一次。初版参数（`MAX_ATTEMPTS=3`、`BACKOFF_MS=300` 线性）
> 是「桥接已治本、重试只兜秒级残余抖动」的假设下定的；生产日志推翻了这个假设，详见下方。

| 参数 | 值 | 说明 |
|---|---|---|
| `MAX_ATTEMPTS` | 5 | 总尝试次数（1 原始 + 4 重试） |
| `BACKOFF_MS` | 500 | 首次重试退避，之后**指数**（500、1000、2000、4000ms） |
| `CAP_BACKOFF_MS` | 4000 | 退避封顶 |

`Thread.sleep` 抽成可注入的 sleeper，测试不必真实等待。

**为什么从 3 次线性改成 5 次指数**：1.14.0 运行目录的生产日志（2026-08-17）显示子 agent 失败
10 次而重试层**触发 0 次**——判据太窄，匹配不到网关实际抛出的故障类型。扩容后退避也跟着放宽，
因为真实瞬态故障（限流、5xx）的恢复时间比「秒级残余抖动」长。

**瞬态判据**（五类，cause 链逐层判断）：

| 类别 | 判据 |
|---|---|
| 「2xx + 坏 body」解析失败 | `*InvalidDataException` 类名后缀（openai/anthropic SDK 同名后缀，按类名匹配保持 provider 中立）或 Jackson `No content to map` |
| 网络断连 | `IOException` 家族（EOF/SocketTimeout/Connect 均其子类），或类名以 `IoException` 结尾（openai-java 的 `OpenAIIoException`，同法不引新依赖） |
| 流中途断开 | message 含 `EOF reached while reading`（`WebClientResponseException` 把 `EOFException` 摊平进顶层 message、cause 链上只剩自身的场景） |
| 限流 | message 含 `rate limit`（大小写不敏感；覆盖 200-wrapped 的 SseException 与 429） |
| 网关 5xx | cause 链上的 `WebClientResponseException` 且 `is5xxServerError` |

**红线不重试**：

- 401/403 与其余 4xx —— 确定态，重试无意义且欠费场景下更花钱。**2xx 不在此列**——「200 OK 但 body 坏」
  正是网关坏窗口的形态，交给 EOF/解析特征判定（这条边界是 TDD 逼出来的修正）。
- `InterruptedException` / `CancellationException` —— 优先短路，见 §4。

把 `IOException` 全家族视为瞬态有理论误伤面（证书错误等），但误伤代价只是几次快速失败，
漏掉代价是整个子 agent 报废重跑。

判据扩容的完整推导见 `docs/superpowers/specs/2026-08-18-subagent-retry-transient-expansion-design.md`（仓库根目录下）。

## 8. 诊断手法备忘

- **curl 直连是拿 ground truth 最快的路**：从运行中进程 `ps eww` 拿真实 env（base url、key），比读日志/反编译字节码猜快得多。
- 字节码（`javap -c`）只能证明「一条代码路径存在」，证明不了「线上走的是哪条」——真实抓包/复现才是根因。

## 9. 提交记录

- `d3747b4` fix(code-tui)：子 agent 改走流式传输，修复网关坏窗口致「Error reading response」
- `cbba97f` fix(tui)：子 agent 重试覆盖真实瞬态故障，改指数退避（§7 参数与判据随此提交更新）
