# code-tui 会话记忆改造报告（spring-ai-session）

> 适用版本：spring-ai-session **0.5.0**、Spring AI 2.0.0、DeepSeek v4-flash（1M 上下文 / 384K 输出）。
> 本文所有实现结论均以 0.5.0 jar 的 `javap` 反编译 + 内置 schema SQL 为准，非照抄官方文档（文档与实现有出入处已标注）。

## 1. 一句话总览

把 code-tui 的会话记忆从 **`MessageChatMemoryAdvisor` 定长滑窗**（按条数硬截断、会断层失忆、可能撕裂工具调用序列）换成 **`SessionMemoryAdvisor`（内存版）**：事件溯源存储 + 回合/token 感知的**摘要压缩**。

## 2. 运行时数据流

```
CodeTuiApplication.main
  └─ DeepSeekChatModel(默认 deepseek-v4-flash)
  └─ AgentTools.build(model, root, listener)  ──► 装配 ChatClient
  └─ new CodingAgent(client, listener, sessionId="code-tui-session", activeTurnId)

用户输入一行 ──► CodingAgent.submit(text)
  ├─ turnId = activeTurnId++
  ├─ listener.onTurnStarted / onUserMessage
  └─ chatClient.prompt()
        .user(text).options(当前 /model 选的模型)
        .toolContext(turnId)
        .advisors(param SESSION_ID_CONTEXT_KEY = sessionId)   ★会话键
        .stream() ──► onAssistantToken / onError / onTurnComplete
```

`SESSION_ID_CONTEXT_KEY` 的实际值就是 `chat_memory_conversation_id`。

## 3. 每轮 advisor 做了什么

1. **解析会话**：从上下文键取 sessionId；不存在则**按需自动创建**（归属 `defaultUserId="code-tui-user"`）。
2. **加载历史**：把该会话存活事件（含此前生成的摘要）拼进本轮 prompt。
3. **持久化**：本轮用户消息 + 助手回复作为新 `SessionEvent` 追加。
4. **判断压缩**：`TokenCountTrigger` 对当前所有事件 token 求和，`> 400_000` 触发 `RecursiveSummarizationCompactionStrategy`——把**较早**事件滚动摘要成一条摘要事件，**逐字保留最近 120 条**，覆盖写回。

压缩**尊重回合边界**，不会拆散某轮的 `tool_call` / `tool_result`——这正是旧滑窗对工具密集编码 Agent 最大的隐患。

## 4. 压缩是「销毁式」的——重要更正

> ⚠️ 本节更正了早期口头报告里两处**错误**结论。

**结论：被压缩掉的逐字原文会被永久丢弃，内存版和 JDBC 版都一样。**

`DefaultSessionService.compactWith` 的真实动作（字节码核实）：

```
result = strategy.compact(request)   // result{ compactedEvents(摘要+保留120), archivedEvents(被压掉的原文) }
sessionRepository.replaceEvents(sessionId, result.compactedEvents(), version)   // 只写回这一份，覆盖
```

`archivedEvents`（被压掉的原文）**从不传给仓库、从不落库**，仅用于判空和返回值统计（`tokensEstimatedSaved`），随后被 GC。

- **内存版 `InMemorySessionRepository`**：每会话只有一个 `events` 列表（`SessionData` 无归档字段），`replaceEvents` 直接替换。
- **JDBC 版 `JdbcSessionRepository`**：schema 只有 `AI_SESSION`、`AI_SESSION_EVENT` 两张表，**无归档表**；`replaceEvents` 实为
  `DELETE FROM AI_SESSION_EVENT WHERE session_id=?` + 批量 `INSERT` 压缩后集合（CAS 版另加 `UPDATE ... event_version` 乐观锁）。
  文档说的「`AI_SESSION_EVENT` append-only / 压缩后仍可搜全量」，**仅在从不触发压缩时才成立**。

### 被更正的两处早期说法
1. ~~「压缩不是丢弃，原文仍在仓库，conversation_search 能捞回压缩掉的原文」~~ → **错**。压缩即删，search 只能覆盖存活窗口（摘要 + 最近 120 条）。
2. ~~「将来换 JDBC 仓库即可持久化全部历史用于召回」~~ → **不准确**。JDBC 能跨重启持久化「当前存活集」，但同样是销毁式压缩，**找不回被压掉的老原文**。

## 5. 为何不挂 conversation_search 工具

在「摘要压缩」这套配置下，`SessionEventTools.conversation_search` 搜的是 `getEvents` 即**存活事件**（摘要 + 最近 120 条），而这批本就已被 advisor 注入 prompt → **工具边际价值极低**，且多占一个提示位、增误调用概率。故**不注册**。

要真正实现「搜回很久以前的原文」，需换**另一种范式**（与摘要压缩互斥，0.5.0 无第二张归档表）：

| | A. 摘要压缩（现状） | B. append-only + filter + 全量 recall |
|---|---|---|
| 早期上下文 | 压成摘要留在 prompt（不失忆、有损） | 不进 prompt，靠模型 search 主动够 |
| 老原文可搜 | ❌ 压缩即删 | ✅ 全在 |
| 存储 | 有界 | 无限增长，需 JDBC 持久化 |
| conversation_search | 基本没用 | 核心机制 |

方案 B 做法：不挂压缩策略 → 用 advisor 的 `eventFilter`（`EVENT_FILTER_CONTEXT_KEY`）只约束注入 prompt 的一段 → 事件表 append-only 无限增长 → 再挂 `SessionEventTools`。

## 6. 参数与不变量

| 参数 | 值 | 含义 |
|---|---|---|
| `COMPACTION_TOKEN_THRESHOLD` | 400_000 | 累计 token 超过才压缩（约 1M 窗口 40%，当「偶尔兜底」；距 1M 留 ~600k 给响应+工具，避免破 1M 报错） |
| `MAX_EVENTS_TO_KEEP` | 120 | 压缩时逐字保留的最近事件数（约最近 30 轮） |
| `DEFAULT_USER_ID` | "code-tui-user" | 单会话归属占位 |

**不变量**：`threshold` 须**明显大于**「`MAX_EVENTS_TO_KEEP` 个事件的 token 量」，否则压缩压不到阈值以下 → 每轮空转重复摘要。初版 24_000/40 违反了它（且对 1M 窗口过早压缩），已改为 400_000/120。

## 7. 过程中的踩坑

- **按 0.5.0 用 `javap` 核实真实包名/签名**，真实包 `org.springframework.ai.session.*`。
- **文档示例有坑**：`TokenCountTrigger.builder().threshold(n).build()` 省略 `tokenCountEstimator` 会运行时抛 `IllegalArgumentException`；`RecursiveSummarizationCompactionStrategy` 同理。已显式注入 `JTokkitTokenCountEstimator`（spring-ai-commons 提供，本就在类路径），trigger 与策略共用一个实例。
- **摘要用「裸」ChatClient**（同模型、无工具、无记忆 advisor），避免压缩时的摘要调用递归触发记忆/工具循环。
- **文档 recall 承诺与 0.5.0 实现不符**（见第 4 节）。

## 8. 存储与生命周期

- 现为**内存仓库**：进程退出即失效。
- 若要**跨重启持久化当前会话**：切 JDBC 仓库（`SessionRepository` 同一 SPI，advisor/agent 代码不动）。注意：这只保住「存活集」，**不等于**能召回压缩掉的老原文（见第 4 节）。

## 9. 遗留 / 建议后续

- **测试盲区**：现有测试覆盖装配与多轮记忆，但无「压缩后早期信息仍在摘要里可用」的针对性验证。建议加一条小阈值测试。
- **可选微调**：给 `RecursiveSummarizationCompactionStrategy` 设 `overlapSize`，把最近几条喂进摘要提示做衔接，减少摘要处语义断点。

## 10. 提交记录

- `434cae4` feat：换用 spring-ai-session 事件溯源 + 压缩，替代滑窗
- `8bbd76f` tune：压缩阈值 24k→400k、保留窗口 40→120
- （本次）refactor：摘掉 conversation_search 工具 + 落文本报告
