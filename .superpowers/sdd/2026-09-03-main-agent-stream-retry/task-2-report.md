# Task 2 报告：StreamInterruptedException + RetryingStreamChatModel（L1）+ RetryReporter

**状态：DONE_WITH_CONCERNS**（2 处判据修正落在 RetryPolicy——见「过程偏差」，功能验收全达成）
**Commit：`b0956390`** — `feat(llm): RetryingStreamChatModel——主 agent L1 零下发透明重试 + StreamInterruptedException/RetryReporter`（分支 `feat/stream-retry`，7 files changed, +630/−7，提交后 worktree 干净）

## 改动文件

| 文件 | 动作 | 内容 |
|---|---|---|
| `.../agent/llm/StreamInterruptedException.java` | 新建 | `public final class extends RuntimeException`；字段 `long emittedChunks` + 访问器 `emittedChunks()`；构造 `(long, Throwable)` 调 `super(null, cause)`（**message 置空**——UI formatError 沿 cause 链落到根因文案）。javadoc 写明类型穿透要求与 L2 语义。 |
| `.../agent/llm/RetryReporter.java` | 新建 | `@FunctionalInterface public interface`，`void report(int attempt, long backoffMs, String reason)`。javadoc 钉死 attempt=即将进行的尝试 2..5（与 backoffMsAfter 的 1 基序号差 1）、doBeforeRetry 时序纪律（退避 delay 前同步执行 → 严格早于新 chunk）、turnId 绑定纪律（桥接在装配层）。 |
| `.../agent/llm/RetryingStreamChatModel.java` | 新建 | 按 brief Step 3 骨架逐行实现：`stream()` = delegate → `doOnSubscribe(emitted.set(0))` → `doOnNext(hasContent 计数)` → `concatWith(空流守卫)` → `retryWhen(Retry.backoff(4, 500ms).jitter(0d).maxBackoff(4s).doBeforeRetry(report).filter(emitted==0 && shouldRetry))` → `transformDeferred(classify)`。classify 三出口（isRetryExhausted→解包 cause 放行 / shouldRetry&&emitted>0→SII(emitted, ex) / 原样）。`reasonOf`（cause 链首个非空 message → 兜底 SimpleName → CharWidth.truncateWithEllipsis 显示宽 60 尾加 …）。`call()` 委托、`getOptions()/getDefaultOptions()` 转发照抄 RetryingChatModel。常量 `BACKOFF_MS=500/CAP_BACKOFF_MS=4000/L1_RETRIES=4`。 |
| `.../agent/llm/RetryPolicy.java` | **修改（偏差，见下）** | ① WCRE 状态 `== HttpStatus.TOO_MANY_REQUESTS` 入瞬态（与 5xx 同分支）；② `t instanceof StreamInterruptedException` 加入链首优先短路红线（与 InterruptedException/CancellationException 并列）。javadoc 同步。 |
| `springai-code-tui/pom.xml` | 修改 | 新增 test 依赖 `io.projectreactor:reactor-test`（无版本号，随 spring-boot-dependencies BOM 解析为 **3.8.6**，与 reactor-core 同版）。 |
| `.../agent/llm/RetryingStreamChatModelTest.java` | 新建 | 17 个用例（brief 14 + reasonOf 规则钉子 3），见下表。 |
| `.../agent/llm/RetryPolicyTest.java` | 修改 | 顺手项（Task 1 评审 Minor ①）：`StreamIdleTimeoutException`/`EmptyStreamException` 实例追加进 `transientCases()`；另追加裸 `WCRE-429`（transient）与 `StreamInterruptedException(2, EOF)`（redLine）钉住本任务的判据修正。 |

## 每用例结果（RetryingStreamChatModelTest 17/17）

| # | 用例 | 断言要点 | 结果 |
|---|---|---|---|
| 1 | retriesZeroEmission429AndSucceeds | 429 ×2 后成功；3 次调用、结果 "done" | ✅ |
| 2 | doesNotRetryZeroEmission4xx | 401 → 1 次调用、WCRE 原样冒泡 | ✅ |
| 3 | retriesStreamIdleTimeoutAndSucceeds | 首字节超时 ×1 后成功；2 次调用 | ✅ |
| 4 | midStreamTransientFailureWrapsStreamInterrupted | 2 内容 chunk 后 EOF → SII、`emittedChunks()==2`、`getMessage()==null`、cause 为原始异常、**1 次调用（不 L1 重试）** | ✅ |
| 5 | blankOnlyChunkStillCountsAsMidStream | 空白-only chunk 后断流 → SII、emittedChunks==1（text 非空口径 R6-m1） | ✅ |
| 6 | usageOnlyTailThenInterruptRetriesAsZeroEmission | usage-only 收尾后断流 → emitted==0 → 重试成功（2 次调用）；usage chunk 照常透传 | ✅ |
| 7 | retriesEmptyStreamAndSucceeds | emptyThenOk → 守卫转 EmptyStreamException → 重试成功（2 次调用）；attempt1 空 chunk 透传（见偏差 B） | ✅ |
| 8 | retriesEmptyStreamWithUsageChunkAndSucceeds | 带 usage chunk 空流 → 重试成功（usage 记账两笔断言在 Task 6 用例 16） | ✅ |
| 9 | exhaustionUnwrapsLastFailure | scripted 桩按订阅序 WCRE(429,"#"+n) ×5 → onError 收 WCRE 含 "#5"、`isRetryExhausted==false`（解包）、5 次调用 | ✅ |
| 10 | emittedResetsOnResubscription | attempt1 空流、attempt2 1 chunk 后断 → SII.emittedChunks()==1（回归钉子） | ✅ |
| 11 | doesNotRetryCancellation | CancellationException → 1 次调用、原样冒泡 | ✅ |
| 12 | reportsAttemptBackoffAndReasonWithVirtualTime | **VTS：supplier 内部调 `model.stream(prompt)`** → `.thenAwait(25s)` → `expectError(WebClientResponseException.class)`；reporter 收 `(2,500)/(3,1000)/(4,2000)/(5,4000)`，reason="429 Too Many Requests"（含 "Too Many"）；**brief 的 `verifyComplete` 是笔误**：4 个 report ⇔ 4 次重试全部发生 ⇔ 第 5 次失败后耗尽并发出 `onError`，故实际 `expectError(WCRE)` 才与验收语义一致。无需 StepVerifierOptions.vtsLookup fallback——Retry.backoff 默认 Schedulers.parallel 被 VTS 全局接管，7.5s 退避虚拟时间走完 | ✅ |
| 13 | streamInterruptedPassesThroughUntouched | SII 经 retryWhen 不拦截 → 原样到达 onError、emittedChunks 字段不被改写、1 次调用（完整版落位 Task 6 用例 3） | ✅ |
| 14 | forwardsGetOptionsToDelegate | marker-model 转发 | ✅ |
| +15 | reasonOfFollowsCauseChainForFirstNonBlankMessage | null/blank message 向下穿透取 cause 链首个非空 | ✅ |
| +16 | reasonOfFallsBackToSimpleClassName | 全空链 → SimpleName；null 输入 → "unknown" | ✅ |
| +17 | reasonOfTruncatesToDisplayWidth60WithEllipsis | ASCII 70→显示宽 60 尾加 …；CJK 按显示宽截（宽字符不可分，≤60 上界）；短 message 原样 | ✅ |

用例 12 的 VTS 断言证据（虚拟时间完整 4 跳，jitter(0) 下与真实 delay 严格相等）：
```
expected: [Report[attempt=2, backoffMs=500, ...], Report[attempt=3, backoffMs=1000, ...],
           Report[attempt=4, backoffMs=2000, ...], Report[attempt=5, backoffMs=4000, ...]]
  actual: 同上（无 fallback、无 .scheduler() 注入）
```

## TDD 红绿证据

### 红（Step 2，pom 加依赖后）
```
mvn -B -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false
→ COMPILATION ERROR: 找不到符号 类 StreamInterruptedException / 类 RetryReporter / 变量 RetryingStreamChatModelTest（多处）
→ BUILD FAILURE
```
（先加 reactor-test 再写测试：依赖解析验证 `dependency:list` → `io.projectreactor:reactor-test:jar:3.8.6:test`，红形态是「类不存在」而非缺依赖编译错——brief Step 1 的预防措施生效。）

### 中间红（Step 4 首跑：14 用例 7 败 → 定位 3 处偏差）
```
Tests run: 14, Failures: 7
  retriesZeroEmission429AndSucceeds / exhaustionUnwrapsLastFailure / reportsAttemptBackoffAndReasonWithVirtualTime
    → RetryPolicy 4xx 红线吞掉裸 WCRE-429（偏差 A）
  retriesEmptyStreamAndSucceeds / retriesEmptyStreamWithUsageChunkAndSucceeds / usageOnlyTail… / emittedResetsOnResubscription
    → 空 chunk 透传与我的断言冲突（偏差 B：测试断言错，机制符合 spec）
  streamInterruptedPassesThroughUntouched → 5 次调用 + 7.5s 真实退避（shouldRetry(SII) 沿 cause 链命中 EOF）
    → SII 被 retryWhen 拦截重试（偏差 C）
```

### 绿（Step 4 终态）
```
mvn -B -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false
→ Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS
```

### 回归（RetryPolicy 改动的波及面验证）
```
mvn -B -pl springai-code-tui -am test -Dtest='RetryPolicyTest,RetryingChatModelTest,RetryingStreamChatModelTest' -Dsurefire.failIfNoSpecifiedTests=false
→ RetryingChatModelTest 19/19（零改动——429/SII 判据修正对子 agent 无回归）
→ RetryingStreamChatModelTest 17/17；RetryPolicyTest 8/8
→ Tests run: 44, Failures: 0 / BUILD SUCCESS

mvn -B -pl springai-code-tui -am test   （全模块）
→ Tests run: 1862, Failures: 0, Errors: 0, Skipped: 13 / BUILD SUCCESS
   （13 skips 均为既有 key 门控 smoke/spike：CodingAgentSpikeTest 5、DeepSeekVisionSmokeTest 1、
    QwenRealStreamingToolCallSmokeTest 1、ZhipuVisionSmokeTest 3、McpStreamableHttpSmokeTest 1、
    BochaWebSearchSmokeTest 1、BraveWebSearchSmokeTest 1——与本任务无关）
```

## 过程偏差（3 处，A/C 落实现、B 落测试断言——全部有 spec/plan 依据）

### A. RetryPolicy 补 429 瞬态（实现偏差，唯一真相源内修正）

brief 用例 1/9/12 用 `WebClientResponseException.create(429, …)` 作桩并要求「重试成功」，spec §5 L1 行明文「零下发 429 → 重试成功」。但 Task 1 原样搬移的旧判据里，WCRE 的 4xx 分支 `return false` 先于 message 匹配执行——裸 WCRE-429（message 仅 "429 Too Many Requests"，不含 "rate limit"）被红线吞掉。旧口径只覆盖「文案含 rate limit 的 429」与「200-wrapped SseException」两个形态。

**修正位置选在 RetryPolicy（唯一真相源）而非 L1 内部 if**——否则 L1 与子 agent `RetryingChatModel` 判据分叉，恰是 Task 1 建立单一真相源要防的事。修法：WCRE 状态 `== HttpStatus.TOO_MANY_REQUESTS` 与 `is5xxServerError()` 并列入瞬态；其余 4xx 红线不变。429 是「请求没病、服务端在节流」（Retry-After 语义）的唯一可重试 4xx。`RetryPolicyTest.transientCases()` 追加裸 WCRE-429 语义锚 + `RetryingChatModelTest` 19 用例零改动全绿（子 agent 同享该修正，方向一致：限流本就该重试）。**Task 8 spec 回写建议追加第 ⑲ 条：§3.1 判据清单「限流」行补「WCRE 状态 429（非仅 message 特征）」。**

### B. 空 chunk / usage-only chunk 透传（测试断言偏差，机制符合 spec）

brief 用例 7/8/10 的直觉写法（`expectNextMatches("done")` 直接期望首元素）与 `concatWith` 守卫的真实信号序不符：attempt1 的空 text chunk / usage-only chunk 在守卫（挂在 onComplete 之前）触发**之前已 onNext 下发**，Reactor 无法撤回。即「零下发」实际指**零非空内容 chunk**（spec §3.2 不变式原文：「L1 重订阅安全 ⇔ emitted==0 ⇔ 下游未收到任何<b>非空</b> chunk」），空 text chunk 对下游 UI/聚合器无观测效应，重放无害。修正测试断言为 `空 chunk 透传 → "done"`。机制实现与 brief 骨架逐字一致，未改。

### C. RetryPolicy 把 StreamInterruptedException 入红线（实现偏差，类型穿透的必要条件）

brief 用例 13 要求 SII「经 .retryWhen 不拦截 → 原样到达 onError」，但 `shouldRetry(SII)` 沿 cause 链命中其 EOF 根因 → true → filter 接受 → L1 重试 SII 4 次（实测 5 次调用 + 7.5s 真实退避）——既违背「不拦截」，更会在真实链路上把 L2 的续跑标记类型吃掉。**SII 本身携带「已下发 chunk」语义（重试 = 向下游重放已见内容），且它是 L1 自己的出口包装——L1 重试自己刚包装放行的错误在语义上自相矛盾。** 修法：`t instanceof StreamInterruptedException` 加入链首优先短路红线（与 InterruptedException/CancellationException 同列——同属「中断」家族，Esc 语义同源）。`RetryPolicyTest.redLineCases()` 追加 `SII(2, EOF)` 锚（cause 是瞬态也否决）。**Task 8 spec 回写建议追加第 ⑳ 条：§3.1 红线清单补 StreamInterruptedException。**

### D. 骨架两处编译期修正（不影响语义）

- brief 骨架 `Mono.defer(() -> emitted.get()==0 ? Flux.error(...) : Mono.empty())` 三元混合 Flux/Mono 编译不过 → 改 `Mono.error(...)`（同为 0 元素 error Publisher，语义相同）。
- usage-only chunk 桩初版手写 `Usage` 匿名类 + `ChatGenerationMetadata.builder().usage(...)`，与 Spring AI 2.0 API 不符 → 照抄 `UsageRecordingChatModelTest` 先例：`DefaultUsage(10,5,15,null,0L,0L)` 挂 `ChatResponseMetadata`。

## 子 agent call() 路径空闲超时 worst-case 量化（Task 1 评审 Minor ②，显式承诺）

**装配链事实**：`StreamIdleTimeoutChatModel` 在生产装配链上（`CodeTuiApplication.wrap()` 对**全部 6 家 provider** 生效，子 agent 经 `RetryingChatModel.call()` 桥接 stream 聚合，同样穿过它）；空闲超时值 = `LlmTimeouts.readTimeout()`，**默认 300s**（`DEFAULT_READ_SECONDS=300`，env 可调）。

**worst-case 推演**（子 agent 单次 call() 遇持续空闲超时、修复窗口永不来的极端情形）：

| 情形 | 尝试次数 | 空闲超时累计 | 退避累计（500+1000+2000+4000） | 总计（wall clock） |
|---|---|---|---|---|
| Task 1 之前（空闲超时为裸 RuntimeException，**不命中**旧判据） | 1 | 300s | 0 | **~5 min**（超时异常直抛，无重试） |
| Task 1 之后（`StreamIdleTimeoutException` 入瞬态，**命中**重试） | 5 | 5×300s=1500s | 7.5s | **~25 min 7.5s** |
| 本任务之后 | 5（不变） | 1500s | 7.5s | **~25 min 7.5s（无变化）** |

**结论：Task 2 对子 agent call() 路径的空闲超时 worst-case 零变化（仍 ~25min）**——本任务新增的 429 判据与 SII 红线均不影响 `StreamIdleTimeoutException` 的瞬态归类（Task 1 已定），`RetryingChatModel.call()` 的 MAX_ATTEMPTS=5 与退避序列也未动（`RetryingChatModelTest` 19 用例零改动全绿为证）。~25min 的拉长是 **Task 1 引入**（方向被全局约束背书：空闲超时本就是瞬态），非本任务增量。风险面备注：该 worst-case 仅在「网关持续挂起 25 分钟且用户不 Esc」同时成立时触达；子 agent 有独立取消通道，且该窗口与「非流式端点分钟级坏窗口」是两类故障（后者靠流式桥接绕过，前者重试有意义）。

## Concerns（不阻塞，供后续任务/评审参考）

1. **RetryPolicy 判据修正跨了任务边界（A/C 两处）**：brief 只授权「实现 L1 + 测试」，但用例 1/9/12/13 的验收客观上要求判据修正，且修正点必须在唯一真相源。已在 commit message 与本报告双处留痕；建议 Task 8 回写清单追加 ⑲/⑳ 两条（上文）。若评审认为应拆独立 commit，revert `RetryPolicy.java` 的两处后用例 1/9/12/13 将立即红——修正不可与 L1 分离。
2. **空 chunk 透传的可观测性**（偏差 B 的延伸）：attempt1 的空 text / usage-only chunk 会真实到达下游（advisor 链、聚合器、UI）。对 `MessageAggregator` 与 UI 无内容效应（空串追加、空 token 无渲染），但 **Task 6 的 `UsageRecordingChatModel` 若按「每个带 usage 的 chunk 记账」会把 attempt1 的 usage 记入**——brief 用例 8 已预见并把「两笔记账」断言留给 Task 6 用例 16 的迷你装配，实现 Task 6 时需按「如实记账」声明处理，勿在 L1 侧吞 chunk（吞了反而破坏 Reactor 语义）。
3. **`emitted` 实例字段的并发前提**：brief 明示「主链同一时刻仅一个活跃订阅（单回合串行约束）」，照骨架用实例字段 + doOnSubscribe 重置。若未来主 agent 引入并发订阅（如并行 prefetch 两个回合共用一个 ChatModel 实例），计数会串扰——届时按 brief 备选改 stream() 局部变量（行为等价，机械替换）。已在类 javadoc 写明该前提。
4. **reasonOf 依赖 `dev.tamboui.text.CharWidth`**：agent.llm 包首次依赖 tamboui-core（模块 classpath 已有、ui 包大量在用，非新依赖）。取其 `truncateWithEllipsis` 的显示宽语义（CJK/emoji 宽 2）与 UI 现行口径一致；若评审介意 llm 包引 UI 库，可换手写 30 行宽表，但会引入第二份宽度真相——不建议。
5. **VTS 用例 12 的稳定性**：依赖「Retry.backoff 默认调度器被 withVirtualTime 全局接管」这一 Reactor 实现细节（brief 已实测背书，本机 reactor 3.8.6 复验通过）。若将来升级 Reactor 改变默认调度器行为，fallback 路径（StepVerifierOptions.vtsLookup + .scheduler() 注入）已写在 brief 与测试注释中。

## 验收核对

- [x] pom 先加 reactor-test（Step 1 内）再写测试；红形态=「类不存在」而非缺依赖编译错。
- [x] brief 14 用例全覆盖（含 VTS 用例 12 supplier 内部调用 stream、无 fallback）+ reasonOf 规则钉子 3 个（规则钉死：cause 链首个非空 message/兜底类名/显示宽 60 截断）。
- [x] report(attempt, backoffMs, reason) 的 reason 一律 reasonOf 规则，无自发明。
- [x] jitter(0d) 显式关闭（注释标 R4）。
- [x] StreamInterruptedException：`super(null, cause)`、`emittedChunks()`。
- [x] RetryReporter：attempt=即将进行的尝试 2..5（`(int) sig.totalRetries() + 2`）。
- [x] classify 三出口逐字对齐 spec §3.2 判定树。
- [x] 顺手项：RetryPolicyTest 追加两个新类型实例（transientCases）；另追加 429/SII 判据锚。
- [x] `RetryingChatModelTest` 19 用例零改动全绿（委托等价 + RetryPolicy 修正无回归）。
- [x] 全模块 1862/1862（13 skips 均为既有 key 门控）。
- [x] Commit 文件清单覆盖 brief Step 5 全部文件 + 判据修正的 RetryPolicy/RetryPolicyTest（commit message 留痕）；提交后 worktree 干净。
- [x] 子 agent call() 空闲超时 worst-case 显式量化（上文表格：本任务零变化，~25min 为 Task 1 增量）。

## Fix round 1（评审 A: Spec Pass with follow-up / B: Needs fixes）

### Finding 修复映射

| Finding | 修复位置 | 覆盖测试 |
|---|---|---|
| Important I-1：L1 每次重试缺独立 warn | `RetryingStreamChatModel.java` 的 `doBeforeRetry`：把 `attempt/backoffMs/reason` 提到 reporter 判空之外；每次重试先 `log.warn("主 agent 流式请求失败（第 {}/{} 次），{}ms 后重试：{}", attempt, 5, backoffMs, reason)`，再按需调用 reporter。日志与 reporter 共用同一组口径值，reporter 为 null 时日志仍存在。 | `RetryingStreamChatModelTest` 全部 17 例覆盖 `doBeforeRetry` 的成功重试、耗尽与 reporter 路径；指定回归全绿。 |
| Important I-2：Task 8 Step 2 缺 ⑱⑲⑳ | `docs/superpowers/plans/2026-09-03-main-agent-stream-retry.md` Task 8 Step 2：在 ⑰ 后追加 ledger ruling 的 `.stream()`/defer 重建措辞（⑱）、WCRE 429 瞬态且其余 4xx 红线不变（⑲）、SII 入红线并放行 L2（⑳）。 | 文档清单；相关行为锚由 `RetryPolicyTest` 的 WCRE-429/SII 用例及 `RetryingStreamChatModelTest` 类型穿透用例覆盖。 |
| Minor M-1：SII Javadoc 与红线矛盾 | `StreamInterruptedException.java` 类 Javadoc：改为「属 RetryPolicy 红线类型；L1 双保险拒绝（红线短路 + emitted==0 恒等式），原样穿透命中 L2 白名单」。 | `RetryPolicyTest` 红线案例 + `RetryingStreamChatModelTest#streamInterruptedPassesThroughUntouched`。 |
| Minor M-2：用例 9 墙钟等待 7.5s | `RetryingStreamChatModelTest#exhaustionUnwrapsLastFailure`：改用 `StepVerifier.withVirtualTime(() -> m.stream(PROMPT))` 与 `thenAwait(Duration.ofSeconds(8))`，保留 `#5`、`isRetryExhausted==false`、calls==5 全部断言，无 sleep。 | 单例验证 1/1 通过（测试本体 0.162s）；指定回归中该类 17/17 通过。 |
| Minor M-3：报告误写 verifyComplete | 本报告用例 12 行：明确 brief 的 `verifyComplete` 是笔误；4 个 report 意味着第 5 次仍失败并耗尽，终态必为 `onError`，实际 `expectError(WebClientResponseException.class)` 正确。 | `RetryingStreamChatModelTest#reportsAttemptBackoffAndReasonWithVirtualTime`。 |

### 验证命令与输出摘要

```bash
mvn -B -pl springai-code-tui -am test -Dtest='RetryingStreamChatModelTest#exhaustionUnwrapsLastFailure' -Dsurefire.failIfNoSpecifiedTests=false
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS；测试耗时 0.162s（虚拟时间，无 7.5s 墙钟等待）

mvn -B -pl springai-code-tui -am test -Dtest='RetryingStreamChatModelTest,RetryPolicyTest,RetryingChatModelTest' -Dsurefire.failIfNoSpecifiedTests=false
# RetryingChatModelTest 19/19；RetryingStreamChatModelTest 17/17；RetryPolicyTest 8/8
# Tests run: 44, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS

git diff --check
# 无输出（通过）
```

### 修复提交

- Commit：`6e55d93a` — `fix(llm): 补齐流式重试日志与虚拟时间测试`（非 amend，独立追加于原提交 `b0956390` 之后）。
