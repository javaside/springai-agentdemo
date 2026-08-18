# 子 agent 重试瞬态判定扩容与指数退避 设计

日期：2026-08-18
状态：已批准（in-chat 设计，用户拍板"只修 subagent 路径"）

## 背景（日志取证）

生产日志 `/Users/zxh/springai-code-tui-1.14.0/logs/`（2026-08-17）显示：

- 子 agent 失败 10 次，全部冒泡成 `SubagentFailedException`，前功尽弃；
- 主 agent 回合失败 7 次（本次不修，走另一套流式直连路径）；
- **`RetryingChatModel` 的重试 WARN 全天 0 条**——重试层存在但从未对真实故障触发过。

根因：`shouldRetry` 只匹配 `*InvalidDataException` 类名后缀与 `"No content to map"` message，
而网关实测抛出的故障是四类完全不同的异常：

| 日志实测故障 | 次数 | 是否被旧 shouldRetry 匹配 |
|---|---|---|
| `OpenAIIoException: Request failed / Stream failed`（OkHttp 断连） | 4+1 | ✗ |
| `WebClientResponseException: 200 OK ... EOFException: EOF reached while reading` | 2 | ✗ |
| `SseException: 200: Upstream rate limit exceeded, please retry later` | 1 | ✗ |
| `BadRequestException: 400: Upstream rejected the request`（网关侧 400） | 1 | ✗（刻意不修） |

失败后主 agent 只能整包重派 subagent（上下文从零），日志可见「修复 Task6 → 继续修复 →
完成修复 → 复审」的连环重派链——这是任务延期的直接机制。

另有 2026-08-16 的 `DefaultChatOptions → DeepSeekChatOptions` ClassCastException 批量失败，
属**另一个独立 bug**，不在本设计范围。

## 改动

只改 `RetryingChatModel.java` 与其测试 `RetryingChatModelTest.java`。范围仅 subagent 路径
（`SubagentRunner.execute` → `ChatClient.call()` → `RetryingChatModel.call()` 桥流式）。

### 1. shouldRetry 扩容

新增四类瞬态匹配（均遍历 cause 链逐层判断，取消/中断优先短路不变）：

1. **网络断连**：cause 链含 `java.io.IOException`（EOF/SocketTimeout/Connect 均子类），
   或类名以 `IoException` 结尾（openai-java 的 `OpenAIIoException`，按类名后缀保持 provider
   中立——与既有 `InvalidDataException` 同法，不引新依赖）；
2. **流中途断开**：message 含 `"EOF reached while reading"`（WebClientResponseException
   把 EOFException 摊平进 message 的场景）；
3. **限流**：message 含 `"rate limit"`（大小写不敏感；覆盖 200-wrapped 与 429 两种形态）；
4. **网关 5xx**：cause 链上有 `WebClientResponseException` 且 `is5xxServerError()`。

**红线（不重试，一个不动）**：401/403（欠费/密钥错——重试只会更慢更花钱，2026-08-17 有
403 预扣费失败实锤）、真 400（请求本身有病）、中断/取消（Esc 立即退出语义）。

**取舍声明**：把 IOException 全家族视为瞬态有理论误伤面（如证书错误也是 IOException），
但误伤代价只是多几次快速失败，漏掉代价是整个子 agent 报废重跑——收益远大于风险。

### 2. 指数退避

- `BACKOFF_MS = 300` 线性（300/600）→ **500ms 指数**：500/1000/2000/4000（封顶 4000）；
- `MAX_ATTEMPTS` 3 → **5**。最坏累计等待 ~7.5s，换取救回可能已跑几分钟的子 agent。

### 3. 休眠可注入（可测性）

`Thread.sleep` 抽成包私有 `sleeper` 字段（`RetryingChatModel::ms → run`），生产用
`Thread::sleep`，测试注入 no-op——退避节奏不必在测试里真实等待。新增静态测试可见的
退避序列断言。

## 测试

`RetryingChatModelTest` 新增：

- `shouldRetryIoExceptionFamily`：EOFException / SocketTimeoutException / IOException /
  类名后缀 IoException → 均重试；
- `shouldRetryEofMidBody`：200 OK + message 含 EOF reached → 重试；
- `shouldRetryRateLimit`：rate limit 大小写两种形态 → 重试；
- `shouldRetry5xxFromGateway`：502 → 重试；
- `shouldNotRetryUnauthorizedOrForbidden`：401/403 → 不重试；
- `shouldNotRetryPlain400`：400 → 不重试；
- `givesUpAfterMaxAttempts` 更新为 5 次；
- 退避序列断言（注入 no-op sleeper 后收集间隔）。

既有测试全部保持通过（取消优先、空流守卫、getOptions 转发等）。

## 非目标

- 主 agent 流式路径的重试（另行立项，本次只修 subagent）；
- 子 agent 级"带部分上下文恢复"（改动大，判定扩容后大概率不再需要）；
- 08-16 的 DeepSeek options CCE bug（独立问题）。
