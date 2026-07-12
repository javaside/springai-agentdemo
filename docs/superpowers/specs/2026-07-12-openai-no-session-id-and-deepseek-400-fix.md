# 修复纪要：切 gpt 报「No session ID」+ 切 DeepSeek 报 400（同一根因）

- 日期：2026-07-12
- 模块：`springai-code-tui`
- 类型：根因分析 + 修复方案（post-mortem，供下次排障复用）
- 分支：`fix/openai-session-memory-and-deepseek-400`

> ⚠️ **更正（务必先读）**：本文把 DeepSeek 400 归为「连续同角色」——**是误诊**。后来用 DeepSeek 真实 400 响应体证实：**用户报的那个 400 其实是「上下文超长」**，真因是 chrome-devtools 截图/读图把巨型二进制灌进上下文，详见 [`2026-07-12-tool-output-context-overflow.md`](2026-07-12-tool-output-context-overflow.md)。
>
> 本文修的两个 bug（**gpt 空流致 `after()` 抛 No session ID**、**gpt 失败堆连续 `USER`**）是**真实但次要**的问题，与那个 400 正交、可保留；但它们**不是**用户报的 400 的根因。当时未取到 400 响应体、只凭会话形状推断，是本次误诊的教训（systematic-debugging：没取证不下结论）。

## 1. 症状

- 切到任意 **gpt/OpenAI** 模型后，回合失败，日志刷 `IllegalStateException: No session ID found in advisor context`（与账户额度无关，DeepSeek 正常）。
- 之后切回 **DeepSeek**，出现**新**问题：`400 Bad Request from POST https://api.deepseek.com/chat/completions`，且此后每条请求都 400。

## 2. 根因（一个 bug，两种表现）

### 2.1 关键事实（均已核对库源码 / 实测）

- **本项目里 `SessionMemoryAdvisor`(SMA) 位于 tool 循环<b>内</b>**：其 order 为库默认 `HIGHEST_PRECEDENCE+1000`，`AgentTools` 从不覆盖。自动注册的 `ToolCallingAdvisor` 在 `+300`（更外层）。`DefaultChatClient.autoRegisterToolCallingAdvisor` 检测到「下游有更高 order 的 MemoryAdvisor」，遂把 tool advisor 的 `conversationHistoryEnabled` 设为 `false`——**由 SMA 逐个 tool 迭代负责历史**，故 `before()/after()` 每迭代各跑一次。
- **空流也会触发 after()**：`SMA.adviseStream` 用 `ChatClientMessageAggregator` 聚合模型流。该聚合器以**空 `HashMap` 起底** context，只从**流入元素**的 `context()` 累加（`.mapNotNull(ccr -> context.putAll(ccr.context()))`）；底层 `MessageAggregator.aggregate` 在 **`doOnComplete`** 里回调完成处理器——**空流也会 complete、也会回调**。
- `ChatModelStreamAdvisor`（最内层）给每个 chunk 都贴上 `Map.copyOf(request.context())`（含 session id）。所以：**只要模型流非空，context 必非空**；**context 为空 ⟺ 模型流为空**。

### 2.2 表现一：gpt「No session ID」

OpenAI 走的是**官方 OpenAI OkHttp SDK**（`OpenAiProvider` 用 `OpenAIOkHttpClientAsync`），与 DeepSeek 的 `spring-ai-deepseek`(WebClient) 是两套流式栈。在某个 tool 迭代上，OpenAI 的模型流对 SMA 的聚合器呈现为**空流** → 聚合 context 恒为 `{}` → `after()` → `getSessionId({})` 抛 `No session ID`。DeepSeek 流式从不为空，故不触发。

栈可作指纹：
```
SessionMemoryAdvisor.getSessionId(237) ← after(198) ← lambda$adviseStream$3(229)
  ← ChatClientMessageAggregator$1(53) ← MessageAggregator.aggregate$2(190) ← onComplete
```

### 2.3 表现二：DeepSeek 400（表现一的**后果**）

`SMA.before()` 会**先**把本回合 user 落盘，`after()` 才落 assistant。gpt 回合 `after()` 抛错 → assistant 落不了盘，但 user 已落盘。反复切 gpt → 历史里堆出**连续 `USER`**。磁盘实证（`/private/tmp/gmt/.codetui/sessions/20260712T090118-c80a14.json`，73 事件）：
```
USER×5 → [ASSISTANT+toolCalls → TOOL]×… → USER → … → USER×4
```
DeepSeek 严格要求 user/assistant 交替，拒收连续同角色 → **400**。而既有的 `SessionEvents.sanitize()` 只修「悬空 tool_calls / 孤儿 tool 结果」，**从不折叠连续同角色**，所以坏历史常驻内存、每条请求都 400。

## 3. 修复（3 部分，均 TDD 离线验证）

### A. 空流守卫 —— 治「No session ID」根因
新增 `agent/SessionIdStreamGuardAdvisor.java`：一个只实现 `StreamAdvisor` 的守卫，order `HIGHEST_PRECEDENCE+1001`（紧贴 SMA 内侧、`ChatModelStreamAdvisor` 外侧）。当下游本迭代流为空时，用 `switchIfEmpty` 补发**一条**携带 `req.context()`（含 session id）+ 空 `AssistantMessage` 的合成响应：
- SMA 聚合 context 非空 → `after()` 正常落盘一条（空文本）assistant；
- `ToolCallingAdvisor` 见到无 tool_call 的终止响应而干净收尾。

装配：`AgentTools.build` 每个 provider 的 `.defaultAdvisors(memoryAdvisor, new SessionIdStreamGuardAdvisor())`（执行序由 `getOrder` 决定，非参数位置）。DeepSeek 上流非空 ⇒ `switchIfEmpty` 永不触发 ⇒ 零回归。副作用（落一条空 assistant）已评估可接受，且反而阻断连续 USER 堆积；`CodingAgent.handleChunk` 的 `!delta.isEmpty()` 已过滤该合成块，无多余 UI token。

**只实现 `StreamAdvisor`**（不碰 call/阻塞路径），对子 agent、辅助 client 零影响。

### B1. 折叠连续同角色 —— 治并自愈 DeepSeek 400
`agent/SessionEvents.java` 把 `sanitize` 拆成三遍纯函数流水线（每遍无改动返回同一引用，保 `assertSame` 契约）：
`removeOrphanToolResults` → **`collapseConsecutiveSameRole`(新)** → `trimToCleanPrefix`。
collapse 合并相邻 `UserMessage` 与相邻**普通** `AssistantMessage`（`!hasToolCalls()`），文本用 `"\n\n"` 拼接、**不丢内容**、保留首条信封。类型判定即安全边界：`ToolResponseMessage` 与带 tool_calls 的 assistant 永不合并，tool 配对分毫不动。**排在删孤儿之后**（删掉夹在两 user 间的孤儿 tool 会新暴露连续 user）。因 `FileSessionRepository`（加载）与 `CodingAgent.trimDanglingToolCalls`（提交）都已调 `sanitize`，坏会话**自动自愈**，无需手改文件。

### B2. 折出站尾部 user —— 补上 collapse 够不到的边界
`agent/CodingAgent.java` 在 `submit` 里 `injectSkill` 之后加 `foldTrailingUserIntoOutbound(sessionId, effectiveText)`：若历史尾部残留一条 `UserMessage`，把它文本折进本回合出站消息、并从会话删除。
**为何 collapse 不够**：`SMA.before()` 会**无条件**把本回合 user 追加到历史，若历史已以 user 结尾即成连续 user → 400，且发生在**模型调用之前**（出站 sanitize 只改会话不改本回合待发 user、空流守卫也够不到）。只影响发给模型的文本，不动 `onUserMessage` 的 UI 展示（与 `injectSkill` 同纪律）。因 collapse 已把尾部连续 user 折成一条，最多折一次即净。

## 4. 如何验证 / 复现

- 离线（模块作用域）：`mvn -pl springai-code-tui test`
  - `SessionIdStreamGuardAdvisorTest`：假 `ChatModel`（`stream()` 返回 `Flux.empty()`）驱动真实 `ChatClient`+`SessionMemoryAdvisor`。**对照组**（不装守卫）证明 assistant 落不了盘；装守卫则正常落盘。
  - `SessionEventsTest`：连续 user 折叠、tool 配对不动、删孤儿后再折、普通/带 tool_call assistant 区分、已交替 no-op、复刻真实 73 事件坏形状。
  - `CodingAgentTrimTest`：submit 折出尾部 user、尾部非 user 时 no-op。
  - 全套 320 用例绿。
- **需真实 key 的端到端**：离线只复现了「空流」这一失败条件，**不证明** OpenAI SDK 生产中确实产空流。最终确认真实 GPT-5.6 tool 回合能落盘作答、不再抛 No session ID，需一次 `OPENAI_API_KEY` 实跑。

## 5. 下次排障的抓手（复用清单）

- 看到 `No session ID found in advisor context` + 上面那条聚合器栈 → **空流触发 after()**，不是「忘了设 param」。定位到 provider 的流式栈差异。
- 看到 DeepSeek `400` 且此前切过 gpt → 先看会话事件的**角色序列**（`.codetui/sessions/*.json` 的 `messageType`）有没有连续同角色；有就是本类腐化，`sanitize` 会在下次加载/提交自愈。
- 记住：**SMA 在 tool 循环内**（order +1000 > tool advisor +300），`before/after` 每 tool 迭代各跑一次；`before` 先落 user，`after` 后落 assistant——`after` 抛错就会留下孤儿 user。
- 上游可改进点（可提 issue）：`ChatClientMessageAggregator` 应从 request 起底 context，而非只从流入元素累加——那样空流也不会丢 session id。

## 6. 涉及文件

- 新增：`agent/SessionIdStreamGuardAdvisor.java`（+ 测试 `SessionIdStreamGuardAdvisorTest.java`）
- 改：`agent/AgentTools.java`（装守卫）、`agent/SessionEvents.java`（三遍流水线 + collapse）、`agent/CodingAgent.java`（`foldTrailingUserIntoOutbound`）
- 改测试：`SessionEventsTest.java`、`CodingAgentTrimTest.java`
- 无改动：`FileSessionRepository`（已在加载时 sanitize）、各 Provider、`ProviderRegistry`
