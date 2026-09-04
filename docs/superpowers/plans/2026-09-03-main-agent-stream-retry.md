# 主 Agent 流式重试与界面重试提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec 给主 agent 流式路径补两层重试（L1 传输层零下发透明重试 / L2 回合级续跑）并在 UI 显示 ↻ 重试提示，含 CODETUI_STREAM_RETRY 计费总闸。

**Architecture:** L1 是新 `RetryingStreamChatModel`（agent/llm 包，`retryWhen` 非阻塞退避，判据经 `RetryPolicy` 与子 agent 同源）；L2 在 `CodingAgent.submit` 的 reactive 链内用 `Flux.defer + retryWhen(StreamInterruptedException 白名单)` 重走 advisor 管线，按会话尾部形状分流（文本段断→composeResumeUser 重放 / 工具中途断→不重放 user 借 notice 续写）。UI 走新事件 `onRetryScheduled` → ↻ INFO 行 + `Status.RETRYING`（80 列宽度预算）。

**Tech Stack:** Java 21 / Spring AI 2.0.0（reactor-core 3.7+）/ JUnit 5 / 该项目既有 TDD 纪律（mvn 单测命令带 `-Dsurefire.failIfNoSpecifiedTests=false`）。

**Spec:** `docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md`（终稿 v11，10 轮评审）——本计划一切决策以 spec 为准；执行者应同时读 spec 与本计划。

## Global Constraints

- 所有 mvn 单测命令：`mvn -B -pl springai-code-tui -am test -Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false`（项目纪律，见记忆）。
- commit message：本计划各任务的 message 均为单行，可直接 `-m "..."`（HEREDOC 纪律适用于多行消息）。
- 判据唯一真相源：`RetryPolicy.shouldRetry` / `RetryPolicy.backoffMsAfter`；`RetryingChatModel` 全部改为委托（既有 `RetryingChatModelTest` 必须零改动全绿）。
- L1 参数：`L1_RETRIES=4`（总尝试 5）、退避 500ms×2ⁿ 封顶 4s、**`jitter(0d)` 显式关闭**（两处 retryWhen 都要——R4 的坑别踩第二次）。
- L2 参数：`Retry.backoff(2, 1s).maxBackoff(4s).jitter(0d)`；`attempt` 口径=续跑序号 1..2（`sig.totalRetries()+1`）。
- `StreamInterruptedException` **message 置空**（null）、带 `emittedChunks` 字段（`formatError` 沿 cause 链取首个非空 message）。
- 装饰链顺序（主链，`AgentTools` 装配）：`Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))`。
- aux / 子 agent 路径**不得**包 `RetryingStreamChatModel`（守卫测试钉死）。
- 计数器纪律：`resubscriptions`、L1 桥计数均为 **submit 闭包局部变量**，严禁实例字段（跨回合炸弹，spec R3-M2）。
- 空流/超时判据：`StreamIdleTimeoutException` / `EmptyStreamException` 入 `RetryPolicy` 瞬态清单。
- `emitted` 口径：text 非空（`!=null && !isEmpty()`，含纯空白）或 hasToolCalls；usage-only/finish-only 不计。
- UI：`onRetryScheduled` 发布 `UiDirty.ALL`；RETRYING label ≤17 显示列、退避串仅 ≥100 列附加、省略 cacheHit。

---

### Task 0: 前置验证 V1/V3（advisor 空 user 行为 + usage/finish chunk 形态）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/AdvisorResubscribeBehaviorTest.java`（新）

**Interfaces:**
- Produces: 无代码产物——产出**验证结论**写回 spec §4（V1 三断言 + V3 结论），并决定 Task 6 中 strip 步骤是否保留（若 advisor 不重跑，strip 变 no-op 但保留无害）。

- [ ] **Step 1: 写 V1 单测（伪造 advisor 链，验证重订阅语义）**

测试直接装配真实 Spring AI `SessionMemoryAdvisor`（或 `DefaultChatClient` + 桩 ChatModel），断言三点：
1. 首次订阅时 `before()` 向会话 append 一条本回合 user；
2. 请求无 user（spec 重建、续跑轮不调 `.user()`）时 `before()` append 的是**空文本** `UserMessage("")` 且**本轮出站 prompt 不含**该空 user；
3. 对同一 cold flux 二次订阅，before() 再次执行（冷流确证）。

```java
package io.github.javaside.springai.codetui.agent.llm;

// 装配骨架整段照抄 SessionIdStreamGuardAdvisorTest（agent/llm 包，「真实 ChatClient + 真实
// SessionMemoryAdvisor + 假 ChatModel」的完整先例）；会话基建不自写桩：
// DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build())
// - recordingModel.stream() 返回 Flux.error(StreamInterruptedException)（触发重订阅路径由 CodingAgent 层管，这里只验 advisor 行为）
// 场景A（断言1/3）：Flux f = client.prompt().user("问题").stream().chatClientResponse();（**复用同一实例**）
//   f.blockLast(); f.blockLast();（两次订阅同一 cold Flux）→ 桩仓库 events 里 user("问题") 被追加两次（before() 冷流重跑确证）
// 场景B（断言2）：client.prompt()（不调 .user()）订阅一次 →
//   ① 桩仓库最后一条事件是 UserMessage 且 getText().isEmpty()（before() 落库空 user）
//   ② recordingModel 收到的 prompt.getInstructions() 中**不存在** getText().isEmpty() 的 UserMessage
//      （noneMatch——出站不含空 user；⚠ 不能写「最后一条 user 文本为空」：出站根本没有那条消息）
// 桩 SessionService/SessionRepository：参考既有测试（SessionMemoryAdvisor 相关测试或 InMemory stub）的写法
```

- [ ] **Step 2: 跑测试确认三断言与 spec 推演一致（或记录偏差）**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=AdvisorResubscribeBehaviorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS 且结论=spec §3.3 步骤 3 的行为（空 user 落库、出站不含）。
若**不符**：照常完成 Step 3/4（结论注释与测试入库作为证据）→ 在最终报告写明「V1 三断言实测为 X，与 spec 推演不符，Task 6 的形状分流/strip 需重新设计」并**停止——报告等待人工/主 agent 决策，不要自行调整后续任务方案**。符合则照常继续。

- [ ] **Step 3: V3 结论（抓帧，只读不改码）**

检查既有测试 `DeepSeekUsageStreamingTest` / `QwenChunkMergerHypothesisTest` 中的 SSE 样本，回答：usage-only 收尾 chunk 的 `AssistantMessage.getText()` 是 null 还是 ""；DeepSeek reasoning chunk 是否进 `getText()`。把结论写成注释加在本测试文件头部（V3 结论区）。若既有样本不足，在本测试里补一个最小桩断言（reasoning 放 metadata 不放 content 的 chunk → `hasContent` 应为 false → 零下发口径成立）。

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/AdvisorResubscribeBehaviorTest.java
git commit -m "test(spec): 前置验证 V1/V3——advisor 重订阅空 user 行为与收尾 chunk 口径"
```

---

### Task 1: `RetryPolicy` 提取 + 两个新异常类型入判据

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicy.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamIdleTimeoutException.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/EmptyStreamException.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModel.java`（shouldRetry/backoffMsAfter 改为委托 RetryPolicy，本体删除——**两个 static 方法保留在类上**，既有测试直调它们）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamIdleTimeoutChatModel.java`（Step 5 换异常类型）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicyTest.java`（新）

**Interfaces:**
- Consumes: `RetryingChatModel` 既有 `shouldRetry(Throwable)`/`backoffMsAfter(int)` 逻辑（原样搬移）。
- Produces:
  - `public final class RetryPolicy`（static）：`static boolean shouldRetry(Throwable ex)`、`static long backoffMsAfter(int attempt)`
  - `public final class StreamIdleTimeoutException extends RuntimeException`（message 构造）
  - `public final class EmptyStreamException extends RuntimeException`
  - 后续任务依赖：`RetryingChatModel.shouldRetry` 保留同名静态方法委托（既有测试零改动）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.llm;

class RetryPolicyTest {
    // 1) 等价性【比对式，非抄写式】：一组代表性 Throwable（FakeInvalidData/EOF/SocketTimeout/IOException/
    //    FakeIoException/EOF-mid-body/429/5xx/401/403/取消嵌套）逐个断言
    //    assertEquals(RetryingChatModel.shouldRetry(t), RetryPolicy.shouldRetry(t))
    //    ——钉住「委托不被解开」（将来有人在 RetryingChatModel 改本体立即红），且免双份期望值维护
    // 2) 新判据：assertTrue(RetryPolicy.shouldRetry(new StreamIdleTimeoutException("等待模型流数据超时")));
    //    assertTrue(RetryPolicy.shouldRetry(new EmptyStreamException("空流")));
    // 3) backoffMsAfter 序列：500/1000/2000/4000/封顶 4000
}
```

- [ ] **Step 2: 跑测试确认失败（类不存在）**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=RetryPolicyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（找不到符号 RetryPolicy）。

- [ ] **Step 3: 实现 RetryPolicy + 两个异常类**

`RetryPolicy`：把 `RetryingChatModel.shouldRetry` 的方法体（L182-212）与 `backoffMsAfter`（L86-92）原样搬入，追加两条判据（`ex instanceof StreamIdleTimeoutException || ex instanceof EmptyStreamException` 放在类名后缀判断旁，cause 链遍历内）。类 javadoc 注明「判据唯一真相源；RetryingChatModel 与 RetryingStreamChatModel 共用；红线（4xx/中断/取消）不动」。
`StreamIdleTimeoutException`/`EmptyStreamException`：简单 extends RuntimeException（构造传 message）。

- [ ] **Step 4: 跑新测试 + 既有 RetryingChatModelTest 全绿**

Run: `mvn -B -pl springai-code-tui -am test -Dtest='RetryPolicyTest,RetryingChatModelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（RetryingChatModelTest 零改动通过=委托等价验收）。

- [ ] **Step 5: `StreamIdleTimeoutChatModel` 换异常类型（同任务收口，零行为变化）**

`StreamIdleTimeoutChatModel.stream()` 的 `new RuntimeException(...)` 改为 `new StreamIdleTimeoutException(...)`（message 文本原样保留）。

Run: `mvn -B -pl springai-code-tui -am test -Dtest='StreamIdleTimeoutChatModelTest,StreamIdleTimeoutProviderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（异常类型换了、message 未变，既有断言不受影响）。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicy.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamIdleTimeoutException.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/EmptyStreamException.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModel.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamIdleTimeoutChatModel.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicyTest.java
git commit -m "feat(llm): RetryPolicy 判据唯一真相源 + StreamIdleTimeout/EmptyStream 异常入瞬态清单"
```

---

### Task 2: `StreamInterruptedException` + `RetryingStreamChatModel`（L1）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamInterruptedException.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModel.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryReporter.java`
- Modify: `springai-code-tui/pom.xml`（**新增 test 依赖 `io.projectreactor:reactor-test`**——用例 12 的 StepVerifier.withVirtualTime 必需，当前 pom 没有它、全仓零 StepVerifier 引用；版本随 reactor BOM）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModelTest.java`

**Interfaces:**
- Consumes: `RetryPolicy.shouldRetry`、`EmptyStreamException`、`StreamIdleTimeoutException`。
- Produces:
  - `public final class StreamInterruptedException extends RuntimeException`：`long emittedChunks()`；构造 `(long emittedChunks, Throwable cause)`，**super(null, cause)**（message 置空）。
  - `@FunctionalInterface public interface RetryReporter { void report(int attempt, long backoffMs, String reason); }`（attempt=即将进行的第几次尝试，2..5）。
  - `public final class RetryingStreamChatModel implements ChatModel`：
    - `public static ChatModel wrap(ChatModel delegate, RetryReporter reporter)`
    - （无 passthrough——冗余 API：装配层三元 `l1Enabled() ? wrap(...) : delegate` 更直白，守卫测试两种写法等价断言）
    - stream() 内 `AtomicInteger emitted`（实例字段，doOnSubscribe 重置）+ `concatWith` 空流守卫 + `retryWhen(Retry.backoff(4, 500ms).jitter(0d).maxBackoff(4s).filter(...))` + `transformDeferred(classify)`
  - classify 三出口（见 spec §3.2）：`Exceptions.isRetryExhausted → getCause() 放行`；`shouldRetry && emitted>0 → 包装 StreamInterruptedException(emitted, ex)`；否则原样。

- [ ] **Step 1: 写失败测试（覆盖 spec §5 L1 行全部用例）**（先在 `springai-code-tui/pom.xml` 加 test 依赖 `io.projectreactor:reactor-test`——版本随 boot/reactor BOM，无须写死——用例 12 的 StepVerifier 必需，不加则 Step 2 的失败形态是缺依赖编译错而非「类不存在」）

```java
package io.github.javaside.springai.codetui.agent.llm;

class RetryingStreamChatModelTest {
    // 桩模式照抄 RetryingChatModelTest 的 flaky/emptyThenOk（Flux.defer + AtomicInteger 订阅计数）
    // 1 零下发 429(Flux.error(WebClientResponseException.create(429,...))) ×2 后成功 → 3 次调用、结果 "done"
    // 2 零下发 401 → 1 次调用、异常原样冒泡（Flux.error 的 401；spec 写 400/401——RetryPolicyTest 等价性已覆盖 4xx 分类，此处 401 代表）
    // 3 首字节超时(Flux.error(new StreamIdleTimeoutException(...))) ×1 后成功 → 重试成功
    // 4 mid-stream：attempt1 发 2 个内容 chunk 后 Flux.error(EOF 包装) → 断言抛 StreamInterruptedException、
    //   emittedChunks()==2、getMessage()==null、调用数==1（不 L1 重试）
    // 5 空白-only chunk 后断流 → 仍判 mid-stream（emittedChunks==1）
    // 6 usage-only 空收尾（AssistantMessage("") 且无 toolCalls 的 chunk）后断流 → emitted==0 → L1 重试路径
    // 7 空流(emptyThenOk) → EmptyStreamException 转 error → 重试成功（调用数==2）
    // 8 空流(带 usage chunk：Generation(AssistantMessage(""))+metadata usage) →重试成功；
    //   （usage 记账两笔的完整断言在 **Task 6 用例 16** 的迷你装配里做，这里只验 L1 行为）
    // 9 L1 耗尽：scripted 桩按订阅序抛不同实例 WebClientResponseException.create(429, "Too Many Requests #" + n, ...) ×5 →
    //    onError 收到的是 WCRE 且 message 含 "#5"（最后一次）、Exceptions.isRetryExhausted(ex)==false（已解包）
    //    （flaky 的固定单例区分不了「最后一次」，须用按 calls.get() 生成 message 的 scripted 变体）
    // 10 emitted 重置（回归钉子，非机制证明——filter 恒等式使带内容尝试永不重试，doOnSubscribe 重置公开不可观测，纯防御）：
    //    attempt1 空流、attempt2 发 1 chunk 后断 → StreamInterruptedException.emittedChunks()==1
    // 11 取消：Flux.error(new CancellationException()) → 1 次调用、原样冒泡
    // 12 RetryReporter + 退避序列：StepVerifier.withVirtualTime(() -> model.stream(prompt))（**supplier 内部**调用 stream——
    //    VTS 接管发生在 supplier 求值前；实测无需 .scheduler() 注入，Retry.backoff 默认 parallel 已被 VTS 全局接管）
    //    → .thenAwait(Duration.ofSeconds(25)) → verifyComplete（上界取全局预算值，L1 实际退避仅 7.5s）；断言 reporter 收到 (2,500)/(3,1000)/(4,2000)/(5,4000)
    //    完整 4 跳（jitter(0) 下与真实 delay 严格相等）。依赖 reactor-test（见 Files 的 pom 修改）。
    //    fallback：若 thenAwait(25s) 后测试卡死（VTS 未接管 Retry.backoff 的 delay）——改用显式
    //    StepVerifierOptions.vtsLookup + Retry.backoff(...).scheduler(vts.scheduler()) 注入后重断言
    // 13 类型穿透标记用例（最小版）：stream() 抛 StreamInterruptedException 经 .retryWhen 不拦截 → 原样到达 subscriber 的 onError
    //    （**完整版落位 Task 6 用例 3**：SII 穿真实 SessionMemoryAdvisor 命中 L2 白名单并续跑，非 Task 7）
    // 14 getOptions() 转发（照抄 forwardsGetOptionsToDelegate）
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现**

```java
public final class RetryingStreamChatModel implements ChatModel {
    static final long BACKOFF_MS = 500;
    static final long CAP_BACKOFF_MS = 4000;
    static final long L1_RETRIES = 4;   // 总尝试 5

    private final ChatModel delegate;
    private final RetryReporter reporter;   // 可 null
    private final AtomicInteger emitted = new AtomicInteger();

    public static ChatModel wrap(ChatModel delegate, RetryReporter reporter) { return new RetryingStreamChatModel(delegate, reporter); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
            .doOnSubscribe(s -> emitted.set(0))
            .doOnNext(r -> { if (hasContent(r)) emitted.incrementAndGet(); })
            .concatWith(Mono.defer(() -> emitted.get() == 0
                ? Flux.error(new EmptyStreamException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应"))
                : Mono.empty()))
            .retryWhen(Retry.backoff(L1_RETRIES, Duration.ofMillis(BACKOFF_MS))
                .jitter(0d)                                  // R4：显式关闭，序列 0.5/1/2/4
                .maxBackoff(Duration.ofMillis(CAP_BACKOFF_MS))
                .doBeforeRetry(sig -> {
                    if (reporter != null) {
                        int attempt = (int) sig.totalRetries() + 2;   // 即将进行的第几次尝试（首重试=2）
                        reporter.report(attempt, RetryPolicy.backoffMsAfter((int) sig.totalRetries() + 1), reasonOf(sig.failure()));
                    }
                })
                .filter(ex -> emitted.get() == 0 && RetryPolicy.shouldRetry(ex)))
            .transformDeferred(this::classify);
    }

    private Publisher<ChatResponse> classify(Flux<ChatResponse> f) {
        return f.onErrorMap(ex -> {
            if (reactor.core.Exceptions.isRetryExhausted(ex)) {
                return ex.getCause();                          // 出口1：解包放行最后一次原始失败
            }
            if (RetryPolicy.shouldRetry(ex) && emitted.get() > 0) {
                return new StreamInterruptedException(emitted.get(), ex);   // 出口2：L2 白名单
            }
            return ex;                                        // 出口3：原样
        });
    }

    static boolean hasContent(ChatResponse r) {
        if (r == null || r.getResult() == null) return false;
        AssistantMessage out = r.getResult().getOutput();
        boolean nonEmptyText = out.getText() != null && !out.getText().isEmpty();   // 含纯空白（R6-m1）
        return nonEmptyText || out.hasToolCalls();
    }
    // call() 委托、getOptions()/getDefaultOptions() 转发（照抄 RetryingChatModel）
}
```

- [ ] **Step 4: 跑测试全绿**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS 全部。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/pom.xml \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamInterruptedException.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModel.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryReporter.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModelTest.java
git commit -m "feat(llm): RetryingStreamChatModel——主 agent L1 零下发透明重试 + StreamInterruptedException/RetryReporter"
```

---

### Task 3: `StreamRetryConfig`（CODETUI_STREAM_RETRY 开关）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamRetryConfig.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/StreamRetryConfigTest.java`

**Interfaces:**
- Produces: `public enum StreamRetryMode { ALL, L1_ONLY, L2_ONLY, OFF }`；`public record StreamRetryConfig(StreamRetryMode mode)`：
  - `public static StreamRetryConfig fromEnv()` / `from(Function<String,String> env)`（env key `CODETUI_STREAM_RETRY`，值 all/l1/l2/off，非法→ALL + warn 日志）
  - `public boolean l1Enabled()`、`public boolean l2Enabled()`
- 消费方：Task 7 装配（l1Enabled 决定 三元 wrap/不包装）、Task 6（l2Enabled 决定白名单恒 false）。

- [ ] **Step 1: 写失败测试**

```java
class StreamRetryConfigTest {
    // from(env)：null/缺省→ALL；"all"→ALL；"l1"→L1_ONLY；"l2"→L2_ONLY；"off"→OFF；
    // "garbage"→ALL（回退）；大小写 "OFF"→OFF（trim+lowercase）
    // l1Enabled/l2Enabled 矩阵：ALL→t/t；L1_ONLY→t/f；L2_ONLY→f/t；OFF→f/f
}
```

- [ ] **Step 2: 跑测试确认失败 → Step 3: 实现（照抄 LlmTimeouts.from 形态）→ Step 4: 全绿**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=StreamRetryConfigTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/StreamRetryConfig.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/StreamRetryConfigTest.java
git commit -m "feat(llm): StreamRetryConfig——CODETUI_STREAM_RETRY 计费总闸（all/l1/l2/off）"
```

---

### Task 4: `AgentListener.onRetryScheduled` + `ConversationState`（↻ 行 / RETRYING / 宽度预算）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java`（`onError` 声明后加 default 方法）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`（Status 枚举加 RETRYING、onRetryScheduled、onAssistantToken 迁移）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（statusLine 穷尽 switch 补 RETRYING 分支）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/StatusBar.java`（如需 label 帮助方法；尽量不加）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateRetryTest.java`（新）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/RetryStatusBarWidthTest.java`（新）

**Interfaces:**
- Produces:
  - `AgentListener`: `default void onRetryScheduled(long turnId, int attempt, int maxAttempts, long backoffMs, String reason) { }`
    （⚠ 5 参——spec §3.5 原为 4 参；多出的 `maxAttempts` 用于区分 L1 `(2/5·传输)` / L2 `(1/2·续跑)` 文案。L1 调用 `(attempt=2..5, maxAttempts=5)`；L2 调用 `(attempt=1..2, maxAttempts=2)`。ConversationState 公开方法同签名。Task 8 回写 spec。）
  - `ConversationState` 新字段访问器（View 用）：`public synchronized String retryLabel()`（null=非重试）、`public synchronized String retryBackoffText()`（如 "1.0s"；onAssistantToken 时与 retryLabel 一并清 null）
  - `ConversationState.Status` 新值 `RETRYING`（isIdle 天然 false，零其他改动）
  - `ConversationState`: `public void onRetryScheduled(long turnId, int attempt, int maxAttempts, long backoffMs, String reason)`（与 AgentListener 5 参同构；锁内迟到过滤+flushStreaming+空行+INFO 行+status；锁外 publish(UiDirty.ALL)；`retryTag(attempt, maxAttempts)` 双参——单参无法区分 L1 attempt=2 与 L2 attempt=2 撞号）

- [ ] **Step 1: 写失败测试（ConversationStateRetryTest）**（所有 pending 行断言经 `state.drainPending()`——List<OutputLine>，破坏性读取一次拿全列表后按 kind()/text() 与相对顺序断言；ConversationState 无 pendingSnapshot，这是既有测试的统一读法）

```java
class ConversationStateRetryTest {
    // onTurnStarted(1)+onAssistantToken(1,"半截") 后 onRetryScheduled(1, 1, 2, 1000, "流中断")：
    //   1) pending 末尾出现 Kind.INFO 且文本含 "↻ 重试中 (1/2·续跑)" 与 "流中断" 与 "1.0s 后重发"
    //   2) 半截残行落定：pending 中存在 Kind.ASSISTANT 且文本 "半截"（保留不删）
    //   3) ↻ 行之前有一条空 INFO 行（残行非空时分隔）
    //   4) status()==RETRYING、isIdle()==false
    //   5) 随后 onAssistantToken(1,"新") → status()==THINKING（迁移）
    // L1 场景（无残行）：onRetryScheduled(1, 2, 5, 500, "429") 直接收 → 无空行分隔行、INFO 行文本含 "(2/5·传输)"
    // 迟到过滤：acceptingTurnId=2 时 onRetryScheduled(1,...) → pending 不增
    // retryLabel 字段：onRetryScheduled 后为 "↻ 重试中 2/5·传输"（或 "1/2·续跑"）；onAssistantToken 后清 null（供 View 用，见 Step 3）
    // onToolFinished 在 RETRYING 下：status→THINKING 不炸（现有代码已如此，钉死）
    // RETRYING 期间 onCompactionStarted/Finished → status 仍 RETRYING
    // reason 单行化：onRetryScheduled(..., reason=300 显示宽的长文本) → ↻ 行文本显示宽 ≤80（CharWidth 断言）
    // 发布 bits：setUiChangeListener 记录 UiDirty → onRetryScheduled 后收到的 bits 含 CONTROL 位（UiDirty.ALL）
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=ConversationStateRetryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，失败形态=**编译错**（找不到符号 onRetryScheduled 的 5 参重载），非断言红。

- [ ] **Step 3: 实现 ConversationState**（⚠ 本步之后 `CodeTuiView.statusLine()` 的穷尽 switch 编译失败是**预期**——必须与 Step 4 的 RETRYING 分支同任务落地后再跑测试；全仓 `state.status()` switch 仅此一处）

```java
// Status 枚举：IDLE, THINKING, RUNNING_TOOL, RETRYING
// 新字段（compacting 三件套旁）：private volatile String retryLabel;  // 如 "↻ 重试中 续跑 1/2"，null=非重试
public void onRetryScheduled(long turnId, int attempt, int maxAttempts, long backoffMs, String reason) {
    Change change = null;
    synchronized (this) {
        if (turnId != acceptingTurnId) return;
        int bits = flushStreaming();                     // 残行落定（保留显示）
        if ((bits & UiDirty.OUTPUT) != 0) {              // 残行非空才有分隔（flushStreaming 空缓冲返回 NONE）
            pending.add(new OutputLine("", OutputLine.Kind.INFO));   // 空行分隔（仅残行非空）
        }
        // retryBackoffText = formatBackoff(backoffMs)，存字段供 View ≥100 列附加
        pending.add(new OutputLine("↻ 重试中 (" + retryTag(attempt, maxAttempts) + ")：" + summarize(reason)
                + "，" + formatBackoff(backoffMs) + " 后重发", OutputLine.Kind.INFO));
        status = Status.RETRYING;
        retryLabel = "↻ 重试中 " + retryTag(attempt, maxAttempts);
        change = changed(UiDirty.ALL);
    }
    publish(change);
}
// retryTag(attempt, maxAttempts)：maxAttempts==5 → "2/5·传输"（L1）；maxAttempts==2 → "1/2·续跑"（L2）
// INFO 行："↻ 重试中 (" + retryTag + ")：" + summarize(reason) + "，" + formatBackoff(backoffMs) + " 后重发"
// retryLabel（供 View 状态行）："↻ 重试中 " + retryTag（≤17 显示列，R7-M4）
// onAssistantToken：方法开头加 if (status == Status.RETRYING) status = Status.THINKING;
// formatBackoff：1000→"1.0s"、500→"0.5s"（(ms/1000.0) 保留 1 位）
```

- [ ] **Step 4: 实现 CodeTuiView RETRYING 分支 + 宽度测试**

```java
// statusLine() switch 补：
case RETRYING -> {
    String label = state.retryLabel() == null ? "↻ 重试中" : state.retryLabel();
    // 退避剩余时长仅宽终端附加（R7-M4：label ≤17 显示列；80 列下省略 cacheHit）
    String backoffTail = terminalWidth() >= 100 ? " · 退避 1.0s" : "";   // 值经 state.retryBackoffText() 取
    String suffix = qs + ijs + ns + backoffTail + " · Esc 取消";        // 省略 cacheHit（R7-M4）
    yield richText(statusBar.shimmer(label, suffix, THINK, animTick, mode));
}
// retryLabel 访问器：ConversationState 加 synchronized String retryLabel() / 或并入 snapshot——照 compacting 字段的既有暴露方式
```

`RetryStatusBarWidthTest`：克隆 `CacheHitStatusBarWidthTest` 的 ViewScreen 断言法——① 默认（terminalWidth 恒 80）下「↻ 重试中」与「Esc 取消」完整可见且**不含退避串**。断言 ②（宽终端含「退避」）**不能靠 ViewScreen.of(view, width)**——那个 width 只改离屏回读 Buffer，改不到视图内部 `terminalWidth()`（测试态恒 80，核查已证）；须给 CodeTuiView 加**包私有的宽度测试注入口**（如 `void terminalWidthForTest(int w)`，写一个覆盖字段、`terminalWidth()` 优先读它），断言 ② 走该入口设 120 后再渲染断言含「退避」。注意 ViewScreen 测试里 `drain()` 不自动跑（见记忆：`ctxUsage.cached` 教训）。该注入口与断言 ② 同在本任务落地。

- [ ] **Step 5: 跑全部新测试 + 既有 UI 测试**

Run: `mvn -B -pl springai-code-tui -am test -Dtest='ConversationStateRetryTest,RetryStatusBarWidthTest,CacheHitStatusBarWidthTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/StatusBar.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateRetryTest.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/RetryStatusBarWidthTest.java
# StatusBar/CodeTuiApplication 若实际未改动，git add 会报错——去掉对应行即可（条件文件）
git commit -m "feat(ui): onRetryScheduled 事件 + ↻ 提示行 + RETRYING 状态（80 列宽度预算）"
```

---

### Task 5: `Interjections.resumeNotice/refillForResume` + `InterjectingChatModel` 重构

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/Interjections.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/InterjectingChatModel.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/interjection/ResumeNoticeTest.java`（新）

**Interfaces:**
- Produces:
  - `Interjections`: `public synchronized void setResumeNotice(String text)`（写 volatile 字段 `resumeNotice`）；**`public synchronized String takeResumeNotice()`（读即清的原子操作，null 安全返回 null——inject 的唯一读通道，peek+clear 两步有锁外撕裂风险，禁止）**；`public synchronized List<String> refillForResume()`（delivered 非空时把 delivered 拆行 addFirst 回 pending、清 delivered/anchor、返回回填列表；空则 no-op 不通知）；`public synchronized void clearResumeNotice()`；`public synchronized String peekResumeNoticeForTest()`（测试钩子）。
  - `InterjectingChatModel.inject(prompt)` 重构的**固定排序**：① `drainForInjection(anchor)`（fireDelivered 仅在其返回非空时、且**在锁外**调——现有纪律不变，notice 不 fire）；② `takeResumeNotice()`；两者都空才早退返回原 prompt；合并产物**恒一条** `UserMessage(wrapText(插话) + "\n\n" + notice)`——**仅 notice 时裸 notice 文本，不套 `[interjection]` 包裹**（套了会混进 delivered 补历史路径，违反「notice 不落会话」）。
  - `InterjectingChatModel.inject(prompt)`：重构早退分支——无插话但有 resumeNotice 时仍追加；合并纪律：注入产物恒为**一条** UserMessage（插话在前 notice 在后，`\n\n` 拼接；drainForInjection 先例为 `\n`，此处升双换行隔离）。
  - `drainForRefill()`：锁内加 `resumeNotice = null`（Esc 清理纪律，spec R4-M2）。

- [ ] **Step 1: 写失败测试**

```java
class ResumeNoticeTest {
    // setResumeNotice + inject（无插话）→ prompt 末尾一条 UserMessage，文本含 <system-notice>；peekResumeNoticeForTest()==null（注入即清）
    // setResumeNotice + pending 插话 2 条 + inject → 恰一条 UserMessage（"插1\n插2\n\n<system-notice>…"）——合并纪律
    // Esc：drainForRefill 后 peekResumeNoticeForTest()==null
    // refillForResume：offer A → drainForInjection(注入) → offer B → refillForResume() →
    //   pendingSnapshot()==[A, B]（A addFirst 在前）——旧插话不被新插话插队
    // takeForHistory 语义不变：refill 后 delivered==null → takeForHistory() 为 empty（不落库）
    // fireDelivered 不触发（notice 不进 delivered 路径）
}
```

- [ ] **Step 2: 确认失败**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=ResumeNoticeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错（找不到符号 setResumeNotice/takeResumeNotice/refillForResume）。

- [ ] **Step 3: 实现 → Step 4: 全绿（命令见 Step 4 两段 Run）**

Run: `mvn -B -pl springai-code-tui -am test -Dtest='ResumeNoticeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: ResumeNoticeTest 全绿。

Run: `mvn -B -pl springai-code-tui -am test -Dtest='*Interject*,MidTurnInjectionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 既有 Interjecting/Interjections 家族 + MidTurnInjectionTest（类名是 Injection——重构 inject 的最直接端到端契约）全绿。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/Interjections.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/InterjectingChatModel.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/interjection/ResumeNoticeTest.java
git commit -m "feat(interjection): resumeNotice 一次性通道 + refillForResume + 注入合并纪律"
```

---

### Task 6: CodingAgent 续跑管线（L2 主体）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`（submit 重构 + prepareResume/composeResumeUser/stripTrailingTurnUser/unwrapL2/失败文案拼接 + handleComplete 清伪影 + l1RetryCounter 桥）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentTurnResumeTest.java`（新）

**Interfaces:**
- Consumes: `StreamInterruptedException`（Task 2）、`RetryReporter`（Task 2）、`onRetryScheduled(5参)`（Task 4）、`Interjections.setResumeNotice/refillForResume`（Task 5）、`StreamRetryConfig`（Task 3）。
- Produces:
  - **接线载体硬约定（B1，违反即复刻 spec R3-M2 跨回合污染）**：
    1. CodingAgent 新增全参构造器入参 `StreamRetryConfig retryConfig`；**所有旧 telescoping 重载委托时默认 `new StreamRetryConfig(StreamRetryMode.ALL)`**（不读环境变量——测试必须可控）；
    2. L1 桥计数载体 = submit 开头**整体替换**的 `private volatile RetryReporter activeTurnL1Sink`（闭包捕获局部 `AtomicInteger l1Retries` 与 turnId）。CodingAgent 包私有方法 `void onL1Retry(int attempt, long backoffMs, String reason)`：**只读 volatile sink，null 直接 return**（首回合前的防御）；turnId 比对（`activeTurnId.get() == turnId`，long 基本类型无装箱）在 **sink 闭包内**（RetryReporter 签名无 turnId，onL1Retry 层没有可比对的值）。旧闭包失配即丢弃，回合终态不清空也无害。
  - `private enum ResumeShape { FROM_TEXT, FROM_TOOLS }`（嵌套于 CodingAgent）
  - `private ResumeShape prepareResume(String sid)`（执行序：trim → 分流判定 → strip → 全域清 blank user → refill → notice；返回形状）
  - `private String composeResumeUser(String effectiveText, List<String> refilled)`（**入参 = prepareResume 里 refillForResume() 的返回列表**——refill 返回即已出队，Interjecting 该轮 pending 已空、早退分支天然不触发（spec 第 5 轮 B1 的防双 user 400 关键）；拼接 `text + "\n\n" + String.join("\n", refilled) + "\n\n" + NOTICE`，空列表退化为两段；重放表达式恒从 submit 闭包原始值重建）
  - `private void stripTrailingTurnUser(String sid)` / `private void purgeBlankUserEvents(String sid)`
  - `static Throwable unwrapL2(Throwable err)`（**包装异常集合写死 = `reactor Exceptions.isRetryExhausted(ex)==true` 或 `ex instanceof StreamInterruptedException`**——沿 cause 链剥到首个不属于集合的异常；SII 的 message 为 null 无妨，retryFailurePrefix 的文案拼接自行沿 cause 链取首个**非空** message）
  - `static String retryFailurePrefix(int l1Retries, int l2Retries)`（"已自动重试（传输 a 次/续跑 b 次）仍失败："；全 0 → ""）
  - `RESUME_NOTICE` 常量（`<system-notice>…</system-notice>` 文本）
  - `static long resumeBackoffMs(int totalRetries)`（纯函数：totalRetries 0→1000 / 1→2000，封顶 4000——即 1s×2ⁿ，与 Retry.backoff(2,1s) 同公式，jitter(0) 下与真实 delay 严格相等）

- [ ] **Step 1: 写失败测试**

```java
class CodingAgentTurnResumeTest {
    // 测试装配：全参构造（registry + 单 entry clientsByProvider + 记录型 listener + 真实
    //   DefaultSessionService + InMemory/文件桩 SessionRepository + 桩 SubagentRunner——先例
    //   MidTurnInjectionTest / CodingAgentTrimTest）+ 桩 ChatModel（计数桩照抄 RetryingChatModelTest.flaky
    //   的 Flux.defer + AtomicInteger 模式）。ChatClient 显式挂 SessionMemoryAdvisor（真实 advisor，
    //   兼得类型穿透覆盖——spec §3.2 点名的防退化用例）。
    //   注：registry==null 的单-client 桩路径 subagentRunner 恒 null（L164），用例 7 需要桩 SubagentRunner
    //   记录 cancelTurn，故必须走全参构造。
    //   注：既有 StubListener/AgentListenerAdapter 对 onRetryScheduled 是空 default——**在本测试内定义
    //   recording listener 并 override onRetryScheduled**（勿复用空桩，否则用例 6/7/9/11 静默拿不到事件）。
    // 用例：
    // 1 形状①：CapturingModel 记录每次订阅的 prompt（prompts 列表 + awaitPrompts(2)，先例 MidTurnInjectionTest）——
    //   attempt1 user("问题") 流出 "半截" 后 error(StreamInterruptedException) → 重订阅；断言：
    //   prompts.get(1).getInstructions() 尾部 UserMessage 文本 == 问题 + "\n\n" + <system-notice>（composeResumeUser）；
    //   会话尾部 user 在 prepareResume 后被删又由 before() 重落（V1 已证）
    // 2 形状①+插话：断流前 offer("插话") → 第 2 次订阅 user == 问题+"\n\n"+插话+"\n\n"+notice（单条）；
    //   Interjecting 未再注入（出站仅一条 user）
    // 3 形状②（⚠ 不能预置会话——首次订阅经真实 SessionMemoryAdvisor.before() 无条件 append 本回合 user，
    //   预置 [..tool] 尾部时断流后 trim 掉悬空 tool_calls、尾部仍是本回合 user → 必判 FROM_TEXT，断言必挂）：
    //   正确编排 = 真跑一轮工具循环：CapturingModel 第 1 次订阅返回 tool_call chunk（照抄 MidTurnInjectionTest 的
    //   slowTool/CapturingModel 模式），工具结果落库后第 2 次订阅流出 1 chunk 再 error(StreamInterruptedException) →
    //   断言第 3 次订阅出站 instructions 末条为 notice 注入条（Interjecting 注入），**不含新的独立 user**
    //   （类型穿透完整版隐式覆盖于此：SII 穿过真实 SessionMemoryAdvisor 的 publishOn+聚合器命中 L2 白名单）
    // 4 多轮续跑后 blank user 清除：连断 2 次后成功 → 会话事件中无 getText().isEmpty() 的 UserMessage
    // 5 上限：scripted=[SII, SII, SII]（首次+2 次续跑全断）→ 耗尽 → doOnError 收到的是最后一次 StreamInterruptedException
    //   的根因（unwrapL2 解包）且 listener.onError 文本含 "已自动重试" 与根因 message；总订阅数 == 3（L1 关：桩不模拟 L1）
    // 6 白名单外：error(IllegalStateException("bad")) → 1 次订阅、onError 直达、无 onRetryScheduled
    // 7 Esc：断流后退避等待期 dispose() → 无后续 onAssistantToken/onRetryScheduled/onError；cancelTurn 被调（subagentRunner 桩记录）；
    //    时序配方：recording listener 在 onRetryScheduled 上 countDown → dispose → 断 cancelTurn 已记录（即时确定）→
    //    略超 1s 退避后断言事件计数不变；**repository 桩计数 replaceEvents：dispose 后不再增长**（第 9 轮 M1 验收，
    //    即 defer 体内 disposed 检查生效）
    // 8 Esc 窗口（latch）：开窗点在 **attempt2 的 advisor 链**（⚠ 不是 ChatModel.stream——Interjecting 先 inject 再调
    //   delegate.stream，stream 开窗时 notice 已被消费测不到泄漏）：包一层 SessionRepository 桩，第 N 次 getEvents/
    //   findById（SessionMemoryAdvisor.before 内）先 entered.countDown() 再 gate.await()——此刻 notice 已 set、
    //   inject 未跑，正是窗口。【完整模拟 Esc = dispose() + View 层 takeBackInterjections 路径（drainForRefill——
    //   notice 清理在这里，不在 dispose 里）】→ 放行 gate → 下一回合（新 submit）出站不含 <system-notice>；禁 sleep
    // 9 同 turnId：全程 listener 收到的 turnId 相同
    // 10 L2 关（StreamRetryConfig L1_ONLY，l2Enabled=false）：断流 → 不续跑直接 onError；OFF 双关同断
    // 11 l1 计数桥：桩 ChatModel 零下发失败 2 次后成功（真实 L1 在装配层，这里桩 RetryReporter 路径）
    //     → listener.onRetryScheduled(turnId=1, 2, 5, 500, reason) 事件时序在成功前
    // 12 失败前缀：零重试错误（401 直抛）→ onError 文本不含 "已自动重试"
    // 13 ①→②混变防双份：第 2 轮 FROM_TEXT 成功注入 notice 落历史后、第 3 轮判 FROM_TOOLS →
    //    不再 setResumeNotice（尾部 user 已含 <system-notice> 标记则跳过）——断言出站 notice 仅一份
    // 14 ②→①边界误判（R11 声明行为）：形状②轮断流早于 tool 结果落库、trim 裁回空 user →
    //    误判 FROM_TEXT 重放问题 → 会话含两份问题文本、形状合法、不丢数据（断言行为而非修掉）
    // 15 续跑后新回合卫生（resubscriptions 局部性）：回合 A 续跑 1 次成功 → 回合 B 全新 submit →
    //    B 的首次订阅出站无 notice、无 strip 副作用（prepareResume 未在 B 首轮执行）
    // 16 空流带 usage 记两笔（spec §5 L1 行）：**迷你装配按生产序** RetryingStreamChatModel.wrap(
    //    new UsageRecordingChatModel(stubModel, acc), null)（需 Usage 层在内——默认装配没有它看不到两笔）→
    //    空 content 但带 usage metadata 的流 ×1 后成功 → acc 断言记 2 笔、cacheHitPercent 不失真
    // 17 执行序对抗性夹具（spec §5 L2 行）：attempt1 走完工具循环（尾部=悬空 tool_calls）+ 会话尾部 user 残留 →
    //    断流 → 第 2 次订阅出站形状正确（判定读的是 trim 后尾部：若判定先于 trim 必误判 FROM_TEXT →
    //    出站多出重放 user——断言出站仅一条尾部 user 即编码了执行序；如需直测抽包私有 prepareResumeForTest）
}
```

- [ ] **Step 2: 确认失败**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=CodingAgentTurnResumeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错（找不到符号 StreamInterruptedException/CodingAgent 构造器新参数）。

- [ ] **Step 3: 实现（spec §3.3 伪码为蓝本；红绿循环以本任务为单位不拆，原子性落在下面三个检查点——每个检查点后跑一条命令看对应用例转绿）**

要点（实现时逐条对照 spec §3.3）：

**检查点 3a（defer 骨架 + disposed + L1 桥）**——完成后跑：
`mvn -B -pl springai-code-tui -am test -Dtest='CodingAgentTurnResumeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 用例 6/7/9/11/15 绿（其余仍红属预期）。
**检查点 3b（prepareResume 家族 + retryWhen 白名单）**——同命令，Expected: 用例 1/2/3/5/8/10/13/14/17 转绿。
**检查点 3c（失败文案 + handleComplete purge）**——同命令，Expected: 全部 17 用例绿。
1. submit 内局部：`AtomicInteger resubscriptions`、`AtomicBoolean disposed`、`AtomicInteger l1Retries`；submit 开头 `activeTurnL1Sink = (attempt, backoffMs, reason) -> { if (activeTurnId.get() == turnId) { l1Retries.incrementAndGet(); listener.onRetryScheduled(turnId, attempt, 5, backoffMs, reason); } };`（整体替换，见 Interfaces 接线硬约定）；
2. `Disposables.composite(reactive, () -> { disposed.set(true); cancelTurn; })`；
3. defer 体内首行 disposed 检查 → `Flux.error(new CancellationException("回合已取消"))`；
4. `resub==1 → outboundUser=effectiveText`；否则 `shape=prepareResume(sid)`，FROM_TEXT → composeResumeUser；FROM_TOOLS → null；
5. spec 构造（client.prompt() 重建）+ options/system/toolContext/advisors 同构设置——把现行 builder 组装段整体移入 defer，**provider/options/modelGrounding/capabilitiesSnapshot() 快照留在 defer 外**（capabilities 读 registry.active()，若随 defer 每订阅重取，退避等待期 /model 切换会让续跑轮能力快照与冻结的 provider 漂移——同回合模型漂移的小号版本；`permissionMode()` 是运行期值（Shift+Tab），**留在 defer 内**每轮现取合理）。**热路径迁移三条（防回归）**：
   a. **现行外层 `catch (RuntimeException)`（L483-489）必须删除**——defer+subscribeOn 后组装异常变成 error 信号由 doOnError 统一处理；保留 catch 会与 doOnError **双发 listener.onError**（双 trimDanglingToolCalls、双终态事件）；
   b. provider 快照段的 `IllegalStateException`（L433-435，client==null）在 defer 外，维持现状（继续同步抛给调用方，与今日一致）；
   c. `.subscribe(null, err -> {})` 的空 errorConsumer **保留**（防 onErrorDropped 噪音，现行 L475 注释点名）；
6. `.subscribeOn(Schedulers.boundedElastic())` 在 defer 与 retryWhen 之间；
7. retryWhen：`Retry.backoff(2, 1s).maxBackoff(4s).jitter(0d).filter(ex -> l2Enabled && ex instanceof StreamInterruptedException).doBeforeRetry(sig -> listener.onRetryScheduled(turnId, (int)sig.totalRetries()+1, 2, resumeBackoffMs((int)sig.totalRetries()), "流中断"))`（attempt=续跑序号 1..2；maxAttempts 参数=2 传给 UI 拼文案）；
8. `handleError` 侧包装：doOnError(err -> handleErrorWithRetryPrefix(unwrapL2(err), turnId, l1Retries.get(), resubscriptions.get()-1))——文案一次性拼好（spec §3.6）。**口径注**：`l1Retries` 是本回合**跨续跑累计**（不随 L2 重订阅清零——↻ 行序号归零是 UI 层的 retryWhen 重建所致，两者语义不同，勿照 UI 序号清零计数）；耗尽时序推演：首订阅 resub=1，续跑 1 次=2、2 次=3，doOnError 时 3-1=2 ✓；
9. prepareResume 执行序（spec §3.3 步骤 1-6）：trimDanglingToolCalls → 分流判定（尾部 UserMessage→FROM_TEXT）→ stripTrailingTurnUser（仅 FROM_TEXT）→ purgeBlankUserEvents（全域，严格 isEmpty）→ refillForResume → setResumeNotice（仅 FROM_TOOLS 且尾部 user 不含 `<system-notice>` 标记）；
10. `handleComplete`：persistInterjection 之后、onTurnComplete 之前调 `purgeBlankUserEvents(sessionId)`；
11. MCP 工具快照注入逻辑随 spec 构造进 defer（每次订阅重取 activeTools——与冷流重走语义一致）。

⚠ 既有回归风险：submit 是项目最热路径，改动后必须全量跑 `CodingAgent*Test`（见 Step 4）。

- [ ] **Step 4: 全量回归**

Run: `mvn -B -pl springai-code-tui -am test -Dtest='CodingAgent*Test,CodingAgentTurnResumeTest,MidTurnInjectionTest,*Interject*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 新测试全绿 + 既有 CodingAgent 全家 + 插话端到端契约 + Interjecting 家族全绿。

Run: `mvn -B -pl springai-code-tui -am test`（模块全量——submit 是最热路径，重构后本任务内全检一次，不留到 Task 8）
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentTurnResumeTest.java
git commit -m "feat(agent): CodingAgent 回合级续跑——形状分流/prepareResume/retryWhen 白名单/取消语义"
```

---

### Task 7: 装配（AgentTools 主链）+ 全部守卫测试

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（装配段约 L629：**provider.chatModel() 与 VisionMaterializingChatModel.wrap 之间**插入 RetryStream——不是在 Vision 外再包）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（如装配入口需传 StreamRetryConfig/桥——按实际结构调整）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsRetryWiringTest.java`（新）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AuxClientNotRetryWrappedTest.java`（新，**agent 包**——auxChatModel 是 AgentTools 包私有，agent.llm 包访问不到；与被参照的 AuxClientNotVisionWrappedTest 同包）

**Interfaces:**
- Consumes: `RetryingStreamChatModel.wrap`（不包装时装配层三元直接用 delegate，无 passthrough API）、`RetryReporter`、`StreamRetryConfig`。
- Produces: 主链装饰顺序 `Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))`；`L1ReporterBridge`（AgentTools 内部类；两段式：装配期建、CodingAgent 构造后经 `AgentTools.wireL1` 接线）。

- [ ] **Step 1: 写失败测试**

```java
class AgentToolsRetryWiringTest {
    // 参照 AuxClientNotVisionWrappedTest / AgentToolsMediaWiringTest 的装配断言方式：
    // 1 链序：build 出的 client unwrap（反射或 instanceof 逐层）→ 依次 Interjecting → Vision → RetryingStream → Usage → IdleTimeout
    // 2 开关：env CODETUI_STREAM_RETRY=off → 链中无 RetryingStreamChatModel；=l2 → 同样无（L1 不包装）；
    //   =l1 → 有 RetryingStream 但 CodingAgent L2 关（此断言在 Task 6 的 L2_ONLY 测试已覆盖，这里只验装配面）
    // 3 aux 链：AgentTools.build 的 auxClient 底层不含 RetryingStreamChatModel（AuxClientNotRetryWrappedTest 的主体）
    // 4 子 agent 链：SubagentRunner 用的 provider.chatModel() 不被 RetryStream 包（SubagentRunner 在 AgentTools 外层包 RetryingChatModel——断言双层不含）
    // 4 dispose 守卫落位说明：spec §5 把「dispose 后 boundedElastic 不再 replaceEvents」挂在装配行——**实际落位 Task 6 用例 7**（repository 桩计数），本任务不重复。
    // 5 blank-user 闸门守卫（spec §5 点名三处）：① UI isBlank 早退（引用既有测试名）；② offer isBlank——断言 Interjections.offer("") 后
    //   pendingCount()==0；③ skill 注入非空前缀（注入路径加 <skill_instruction> 前缀，引用既有测试名）——R12 缓解依赖点名全清单
}
```

- [ ] **Step 2: 确认失败 → Step 3: 实现装配**

```java
// AgentTools 装配段（L629 附近，provider.chatModel() 的包法——Vision 在 RetryStream 外面）：
// ⚠ retryConfig 不可在 build 内写死 fromEnv()（测试无法改 System.getenv，且开发者本机真设了
//   CODETUI_STREAM_RETRY 会让链序断言随机红）：build 加注入重载
//   build(registry, root, listener, mcpRegistry, permissionEngine, StreamRetryConfig)（build 已有
//   3 层兼容重载先例 L693/706），原重载默认 fromEnv()，测试走注入重载。
StreamRetryConfig retryConfig = /* 来自重载入参，默认 fromEnv() */;
L1ReporterBridge bridge = new L1ReporterBridge();           // 两段式持有者：volatile 消费者字段
ChatModel base = retryConfig.l1Enabled()
        ? RetryingStreamChatModel.wrap(provider.chatModel(), bridge)
        : provider.chatModel();                             // off/l2 直通（不包装，守卫测试据此断言）
VisionMaterializingChatModel visionModel = VisionMaterializingChatModel.wrap(
        base, root, modelId -> provider.capabilities(modelId).supportsImageInput());
// InterjectingChatModel.wrap(visionModel, interjections)   ← 最外层不变
// 最终链：Interjecting(Vision(RetryStream(Usage(IdleTimeout(raw)))))——与 spec §3.7 一致
// 接线（⚠ bind 不能写在 CodeTuiApplication——它在 codetui 包，访问不到 agent 包私有的
//   codingAgent::onL1Retry，方法引用直接编译失败）：
//   ① bridge 是 **AgentTools 内部类**（agent 包）；
//   ② AgentRuntime（record）增组件 L1ReporterBridge bridge（grep 确认 new AgentRuntime 仅
//      AgentTools.build L672 一处、测试 0 处直构——record 加尾参即可，无需兼容重载）；
//   ③ AgentTools 加静态接线方法，CodeTuiApplication 只调它：
//        public static void wireL1(AgentRuntime rt, CodingAgent agent) { rt.bridge().bind(agent::onL1Retry); }
//   （装配期建、构造后 bind——两段式时序见 spec §3.2）
```

- [ ] **Step 4: 全量回归（含既有 AgentTools*WiringTest 全家）**

Run: `mvn -B -pl springai-code-tui -am test -Dtest='AgentToolsRetryWiringTest,AuxClientNotRetryWrappedTest,AgentTools*WiringTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全绿（AgentTools*WiringTest 覆盖 Media/Compaction/WebSearch/Mcp/Background 五家）。

Run: `mvn -B -pl springai-code-tui -am test`（模块全量——record 改构造+主链装配后的一次全检）
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsRetryWiringTest.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AuxClientNotRetryWrappedTest.java
git commit -m "feat(assemble): 主链插入 RetryingStreamChatModel + L1 桥接线 + 守卫测试（aux/子 agent 不被误包）"
```

---

### Task 8: 全量回归 + spec 回写 + 发布记录

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md`（§3.5 签名修订 5 参、§4 V1/V3 结论回写）
- Modify: `CHANGELOG.md`（新版本行）
- Modify: `docs/release-notes/v1.20.0.md`（新建，若版本号节奏不同按项目惯例）

- [ ] **Step 1: 模块全量测试**（发布门：clean+package 含打包校验。Task 6/7 的全量是中途回归闸，三者目的不同、不可互相替代、不可省）

Run: `mvn -B -pl springai-code-tui -am clean package`
Expected: BUILD SUCCESS（全模块绿）。

- [ ] **Step 2: spec 回写**：① §3.5 `onRetryScheduled` 签名更新为 5 参（attempt, maxAttempts, backoffMs, reason）；② §4 V1/V3 状态列写「已验证（Task 0）」+ 结论一句话；③ §7 切分标注「已按本计划实施」；④ §3.1/§3.7 的「wrap 直通」表述改为「装配层三元不包装」，§5 装配行同步；⑤ §5 L1 行三项落位注明（类型穿透完整版=测试类名、退避序列改 VTS 全局接管、用例 10 为回归钉子非机制证明）；⑥ §3.3 补一句「现行外层 catch(RuntimeException) 删除、组装异常转 error 信号统一走 doOnError」（防按 spec 直实现双发 onError）；⑦ unwrapL2 剥穿 SII 到根因的口径（spec 原文停在 SII，用户可见文案等价但 Throwable 身份不同）；⑧ L2 标签格式统一为「1/2·续跑」（spec §3.5 两处写的「续跑 1/2」跟随）；⑨ §5 装配行「dispose 守卫」落位改为 CodingAgentTurnResumeTest 用例 7。

- [ ] **Step 3: Commit & 收尾**

```bash
git add docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md CHANGELOG.md docs/release-notes/v1.20.0.md
git commit -m "docs: 主 agent 流式重试 spec 回写（签名/V1V3 结论）+ v1.20.0 发布记录"
```

---

## Self-Review 记录（写完计划后跑过；第 1-9 轮计划评审修订后复核仍成立）
**计划评审修订摘要**（9 轮，与 spec 评审同法）：R1 全量初审（L1 桥载体/链序反/参数矛盾）；R2 自洽性（包位陷阱/迁移三说明/断言措辞）；R3 测试质量（reactor-test 依赖、形状②编排、Esc 开窗点）；R4 模拟执行（wireL1/注入重载/takeResumeNotice/删 passthrough）；R5 覆盖终检（spec §5 五行全映射/回写清单扩至⑨）；R6 执行友好度（pom 时序/三检查点/停止语义）；R7 事实核查（30+ 行号全命中，ViewScreen 宽度改注入口）；R8 横切一致性（回写补⑥⑦⑧⑨）；R9 Step2 Run 块错位归位。本段防执行者对已裁决项翻烧饼。


1. **Spec coverage**：spec §3.1→Task 1/3；§3.2→Task 2；§3.3→Task 6；§3.4→Task 5；§3.5→Task 4；§3.6→Task 6 Step3-8；§3.7→Task 7；§4 V1/V3→Task 0；V2（真机断流 dump）属人工验证，Task 8 记录不做（真机步骤不在自动化计划内，spec 已注明）。gap：无。
2. **Placeholder scan**：无 TBD/TODO；测试用例均为可落地的桩+断言描述（桩模式照抄 RetryingChatModelTest 既有先例）。
3. **Type consistency**：`onRetryScheduled` 5 参签名在 Task 4 定义、Task 6 消费一致（Task 7 经 RetryReporter→onL1Retry 桥间接消费，不直接调 5 参事件）；`ResumeShape`/`composeResumeUser`/`unwrapL2` 命名前后一致；`RetryReporter.report(attempt, backoffMs, reason)` 与 Task 7 桥一致；桥名统一 `L1ReporterBridge`。
