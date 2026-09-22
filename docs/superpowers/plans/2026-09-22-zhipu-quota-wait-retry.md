# 智谱 Coding Plan 限额感知重试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 识别智谱 Coding Plan 限额错误（429 + 业务码 1308/1310/1316-1321），解析 message 内嵌的重置时刻，回合挂起睡到重置点自动重试；L1/L2/子 agent 三路径全覆盖，UI 倒计时，Esc 可取消。

**Architecture:** 全部收在现有重试真相源 `RetryPolicy`（`agent/llm` 包）：`QuotaLimitDetector` 识别+解析（纯函数），`backoffRetry` 加限额分支（须过层 filter、豁免只针对耗尽判定、普通预算按 `totalRetries − quotaWaits` 扣减）。UI 复用 RETRYING 态动画重绘帧从 deadline 现算倒计时（无 ticker）。串行子 agent 补执行线程登记 + `cancelTurn` interrupt。

**Tech Stack:** Java 17（spring-ai 2.0.1 / openai-java-core 4.49.0 / reactor-core 3.8.7 / spring-web 7.0.9），JUnit 5 原生断言 + reactor-test（StepVerifier），JLine 内联 TUI。

**Spec:** `docs/superpowers/specs/2026-09-22-zhipu-quota-wait-retry-design.md`（v2 终稿——本计划从该 spec 推导，spec 的 §3 设计分节与本文任务一一对应；执行者须同时读 spec）

## Global Constraints

- 模块：`springai-code-tui`（包根 `io.github.javaside.springai.codetui`）。测试命令一律在仓库根执行：
  `mvn -pl springai-code-tui -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false -q`
- **测试断言风格：JUnit 5 原生**（`assertEquals`/`assertTrue`/`assertThrows`，`org.junit.jupiter.api.Assertions`）——模块**没有** assertj 依赖（pom 无、全 test 目录零使用），新测试一律照此，不引新依赖。StepVerifier 用法参照 `RetryingStreamChatModelTest`（`RetryPolicyTest` 是纯判据单测，无 StepVerifier/无 delayScale 用法）。
- 限额码集合（字符串，逐字复制）：`"1308","1310","1316","1317","1318","1319","1320","1321"`；**排除** 1113（欠费）、1309（套餐过期）、1311（权限）——它们不会自愈，不等。
- 限额等待下限 `MIN_QUOTA_WAIT_MS = 30_000L`；单层连续限额等待上限 `MAX_QUOTA_WAITS = 5`（L1/L2/子 agent 各自独立）。
- **实现红线（spec §3.2）**：`backoffRetry` 限额分支必须过 `filter.test(failure)`（不豁免层白名单）；普通分支耗尽判定必须用 `sig.totalRetries() - quotaWaits.get() >= maxRetries`；`quotaWaits` 必须声明在 `Retry.from(companion -> {...})` lambda 体内（订阅级）。
- **实现红线（spec §3.4）**：`RetryingChatModel.call()` 改 while 循环；单次迭代恰一次 `sleeper.accept`；限额判定排在 `attempt >= MAX_ATTEMPTS` bail 之前。
- `quotaWaits == 0` 时 `backoffRetry` 行为与现状逐字节一致（现有 RetryPolicyTest / RetryingStreamChatModelTest / RetryingChatModelTest 必须零修改通过）。
- 不动现有普通重试判据/预算/退避参数（`BACKOFF_MS`/`CAP_BACKOFF_MS`/`RETRY_AFTER_CAP_MS`/`L1_RETRIES`/`L2_RESUMES`/`MAX_ATTEMPTS` 原值）。
- javadoc 风格与包内既有类一致（中文、讲 Why）；`agent/llm` 新类标注「内部类型：升 public 仅为跨包装配，勿在 agent 包外依赖」。
- 提交信息格式 `feat(code-tui): ...` / `test(code-tui): ...`，中文一行主题。

---

### Task 1: `QuotaLimit` + `QuotaLimitDetector`（识别与重置时刻解析）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimit.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimitDetector.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/Quota429s.java`（测试 fixture，后续任务共用）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimitDetectorTest.java`

**Interfaces:**
- Produces: `record QuotaLimit(String code, java.time.Instant resetAt)`（resetAt 可 null）；`QuotaLimitDetector.detect(Throwable): java.util.Optional<QuotaLimit>`；包私有 `QuotaLimitDetector.parseResetAt(String): Instant`；测试 fixture `Quota429s.quota429(String code, String message): com.openai.errors.RateLimitException`。

- [ ] **Step 1: 写失败测试 + fixture**

`Quota429s.java`：

```java
package io.github.javaside.springai.codetui.agent.llm;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;

/** 测试 fixture：构造智谱形态的 429（业务码 + 中文 message 内嵌重置时刻）。 */
public final class Quota429s {
    private Quota429s() { }

    public static RateLimitException quota429(String code, String message) {
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder().code(code).message(message).build())
                .build();
    }
}
```

`QuotaLimitDetectorTest.java`（矩阵覆盖 spec §5-1）：

```java
package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaLimitDetectorTest {

    // 1316 文档原文形态（反引号包裹北京时间）
    static final String M1316 = "429: 已达到 5 小时使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-22 15:30:00` 重置。";
    static final String M1317 = "429: 已达到 7 天使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-23 08:00` 重置。";   // 无秒位

    @Test
    void detectQuotaCodeWithResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1316", M1316));
        assertTrue(q.isPresent());
        assertEquals("1316", q.get().code());
        // 北京时间 15:30 == UTC 07:30（解析按 Asia/Shanghai）
        assertEquals(Instant.parse("2026-09-22T07:30:00Z"), q.get().resetAt());
    }

    @Test
    void detectMinutePrecision() {
        assertEquals(Instant.parse("2026-09-23T00:00:00Z"),
                QuotaLimitDetector.detect(Quota429s.quota429("1317", M1317)).get().resetAt());
    }

    @Test
    void detectIsoOffset() {
        RateLimitException ex = Quota429s.quota429("1308",
                "429: 已达到 100 USD 5hour 使用上限，将在 2026-09-22T15:30:00+08:00 重置。");
        assertEquals(Instant.parse("2026-09-22T07:30:00Z"),
                QuotaLimitDetector.detect(ex).get().resetAt());
    }

    @Test
    void quotaCodeWithoutTimeYieldsNullResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1310",
                "429: 已达到每周使用上限。"));
        assertTrue(q.isPresent());
        assertNull(q.get().resetAt());
    }

    @Test
    void keywordFallbackRequiresBothKeywords() {
        // code() 缺失（ErrorObject 不设 code）+ 双关键词 → 命中
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429(null, M1316)).isPresent());
        // 单关键词「使用上限」无「重置」→ 不命中
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429(null,
                "429: 已达到使用上限")).isEmpty());
    }

    @Test
    void plainRateLimit429NotDetected() {
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1302",
                "429: 您的访问频率过高")).isEmpty());
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1113",
                "429: 账户欠费")).isEmpty());
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1309",
                "429: 套餐已到期")).isEmpty());
    }

    @Test
    void webClient429NotDetected() {
        WebClientResponseException wcre =
                WebClientResponseException.create(429, "Too Many Requests", null, null, null);
        assertTrue(QuotaLimitDetector.detect(wcre).isEmpty());
    }

    @Test
    void traversesCauseChainAndSiiWrapper() {
        // Spring AI 包一层 RuntimeException 的真实传播形态
        assertTrue(QuotaLimitDetector.detect(new RuntimeException("wrap",
                Quota429s.quota429("1316", M1316))).isPresent());
        // SII 包装穿透：message 置空、cause 保留（L2 路径）
        assertTrue(QuotaLimitDetector.detect(new StreamInterruptedException(3,
                Quota429s.quota429("1316", M1316))).isPresent());
    }

    @Test
    void nonSdkThrowableNotDetected() {
        assertTrue(QuotaLimitDetector.detect(new RuntimeException("boom")).isEmpty());
    }

    @Test
    void parseResetAtNullSafe() {
        assertNull(QuotaLimitDetector.parseResetAt(null));
        assertNull(QuotaLimitDetector.parseResetAt("没有任何时间"));
    }

    @Test
    void pastTimestampStillParsed_fieldIsKeptDescriptive() {
        // detect 是纯描述：过去时刻不过滤（quotaWaitMs 兜底，spec §3.1）
        RateLimitException ex = Quota429s.quota429("1316", "429: 上限，将于 `2020-01-01 00:00:00` 重置。");
        assertEquals(Instant.parse("2019-12-31T16:00:00Z"),
                QuotaLimitDetector.detect(ex).get().resetAt());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=QuotaLimitDetectorTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（QuotaLimit/QuotaLimitDetector 不存在）。

- [ ] **Step 3: 实现**

`QuotaLimit.java`：

```java
package io.github.javaside.springai.codetui.agent.llm;

import java.time.Instant;
import java.util.Optional;

/**
 * 智谱 Coding Plan 限额错误的检测结果（纯数据，spec §3.1）：{@code code} 为业务码字符串，
 * {@code resetAt} 为从 message 解析出的重置时刻——<b>可为 null</b>（文案改版/解析失灵时的
 * 安全网形态，调用方按普通 429 退化处理，行为退回现状）。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public record QuotaLimit(String code, Instant resetAt) {

    /** 解析不出重置时刻的形态（code 命中但 message 无时间）。 */
    public static QuotaLimit withoutResetAt(String code) {
        return new QuotaLimit(code, null);
    }

    /** resetAt 的 Optional 视图（调用方免手工 null 判）。 */
    public Optional<Instant> resetAtOpt() {
        return Optional.ofNullable(resetAt);
    }
}
```

`QuotaLimitDetector.java`：

```java
package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智谱 Coding Plan 限额错误识别与重置时刻解析（spec §3.1，纯函数）。
 *
 * <p><b>判据</b>：cause 链上的 {@link OpenAIServiceException} 且 {@code statusCode()==429} 且
 * （业务码 ∈ {@link #QUOTA_CODES} 或 message 双关键词兜底「使用上限」+「重置」——单关键词会把
 * 同走 openai-java 栈的 Qwen 等家的中文 429 误判进来）。Spring WCRE 路径刻意不识别（provider 中立）。
 * 1113（欠费）/1309（套餐过期）/1311（权限）不在集合内——等到天亮也不自愈，不等（spec §1.1）。
 *
 * <p><b>SII 穿透</b>：{@link StreamInterruptedException} message 置空但 cause 保留原始 429——
 * 沿 cause 链可命中（L2 路径）。
 *
 * <p><b>观测钩子</b>：命中即 WARN（业务码 + resetAt + message 原文）——真实 message 格式无真机
 * 样本（限额错误无法主动构造），首个真实命中后凭日志校准解析规则（spec §3.1）。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class QuotaLimitDetector {

    private static final Logger log = LoggerFactory.getLogger(QuotaLimitDetector.class);

    /** 限额业务码（docs.bigmodel.cn 错误码文档 2026-09-22 核实）。 */
    static final Set<String> QUOTA_CODES =
            Set.of("1308", "1310", "1316", "1317", "1318", "1319", "1320", "1321");

    /** 无偏移时间的解释时区：bigmodel.cn 国内站按北京时间。 */
    static final ZoneId ZHIPU_ZONE = ZoneId.of("Asia/Shanghai");

    /** `yyyy-MM-dd HH:mm[:ss]`（文档形态带反引号包裹；兼容 T 分隔）。 */
    private static final Pattern LOCAL_DT =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2})(?::(\\d{2}))?");
    /** ISO-8601 带偏移/Z（自带时区语义；逗号毫秒兼容）。 */
    private static final Pattern ISO_DT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})");

    private QuotaLimitDetector() { }

    /** 识别限额错误并解析重置时刻；非限额（含普通限流 1302/1305、WCRE 429）返回 empty。 */
    public static Optional<QuotaLimit> detect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof OpenAIServiceException svc && svc.statusCode() == 429) {
                String code = svc.code().orElse(null);
                String message = svc.getMessage();
                boolean codeHit = code != null && QUOTA_CODES.contains(code);
                boolean keywordHit = message != null && message.contains("使用上限") && message.contains("重置");
                if (codeHit || keywordHit) {
                    Instant resetAt = parseResetAt(message);
                    String effectiveCode = code != null ? code : "keyword";
                    log.warn("智谱限额错误：code={} resetAt={} message={}", effectiveCode, resetAt, message);
                    return Optional.of(resetAt != null
                            ? new QuotaLimit(effectiveCode, resetAt)
                            : QuotaLimit.withoutResetAt(effectiveCode));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从 message 全文解析重置时刻（不依赖措辞，只认时间模式）：ISO-8601 带偏移优先
     * （自带时区），其次 `yyyy-MM-dd HH:mm[:ss]` 按 {@link #ZHIPU_ZONE}；均失败返回 null
     * （调用方退化普通 429 行为——spec §4 安全网）。取<b>首个</b>匹配（message 只有一个时刻）。
     */
    static Instant parseResetAt(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher iso = ISO_DT.matcher(message);
        if (iso.find()) {
            String v = iso.group().replace(',', '.');
            // '+' 偏移无冒号形态（+0800）补冒号，Instant.parse 只认 +08:00
            v = v.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
            try {
                return Instant.parse(v);
            } catch (Exception ignore) {
                // 落到本地格式再试
            }
        }
        Matcher m = LOCAL_DT.matcher(message);
        if (m.find()) {
            try {
                // 整段匹配即 "yyyy-MM-dd HH:mm[:ss]"（[ T] 两分隔都认），按有无秒位选 formatter，
                // 一次 parse 到 LocalDateTime（拆 LocalDate/LocalTime 再拼的写法在日期-only 文本上
                // LocalDateTime.parse(LocalDate...) 必抛 DateTimeException——勿走回头路）
                LocalDateTime ldt = LocalDateTime.parse(m.group(),
                        m.group(3) == null
                                ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return ldt.atZone(ZHIPU_ZONE).toInstant();
            } catch (Exception ignore) {
                // fail-open：返回 null，调用方退化
            }
        }
        return null;
    }
}
```

（⚠ 不要用 `LocalDateTime.parse(m.group(1), LOCAL_DATE)` 拆拼写法——`LocalDateTime.from` 要求日期+时间字段齐全，日期-only 解析必抛 `DateTimeException` 被 catch 吞掉，本地两形态全灭；上面整段 parse 是唯一正确形态。`LOCAL_DATE`/`LOCAL_TIME` 两个 formatter 常量随之不需要，勿创建。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test -Dtest=QuotaLimitDetectorTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（11 个用例）。

（fixture 说明：`RateLimitException.builder().headers(...).error(ErrorObject...)` 形态已对 openai-java-core 4.49.0 字节码核验——`ErrorObject.builder().code(String)` 允许 null（`JsonField.ofNullable`）、`RateLimitException` 的 message 派生为 `"429: " + error.message`；若未来 SDK 升级后 builder 形态变化，以 `svc.code()`/`svc.statusCode()` 访问器对称的 setter 为准调整 fixture。）

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimit.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimitDetector.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/Quota429s.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/QuotaLimitDetectorTest.java
git commit -m "feat(code-tui): 智谱限额错误识别与重置时刻解析（QuotaLimitDetector）"
```

---

### Task 2: `RetryPolicy` 限额分支（filter 门控 + 预算扣减 + QuotaWaitHook）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicy.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicyTest.java`（扩展）

**Interfaces:**
- Consumes: Task 1 的 `QuotaLimit`、`QuotaLimitDetector.detect`。
- Produces: `RetryPolicy.quotaWaitMs(QuotaLimit): long`（resetAt null → −1；否则 `max(resetAt−now, 30_000)`）；包私有 `quotaWaitMs(QuotaLimit, Instant now)`（测试可控 now）；嵌套接口 `RetryPolicy.QuotaWaitHook { void onQuotaWait(long waitMs, long resetAtEpochMs, String reason); }`；`backoffRetry(long maxRetries, Predicate<Throwable> filter, RetryHook onRetry, QuotaWaitHook onQuotaWait)` 四参重载（既有三参语义不变，内部委托四参传 null）；包私有常量 `MIN_QUOTA_WAIT_MS=30_000L`、`MAX_QUOTA_WAITS=5`。**onRetry 的 totalRetries 入参语义变更：改为扣减后的普通重试数**（quotaWaits=0 时与旧值恒等）。

- [ ] **Step 1: 写失败测试（追加到 RetryPolicyTest；该文件目前是纯判据单测——StepVerifier/`setDelayScaleForTest` 用法参照 `RetryingStreamChatModelTest`，断言一律 JUnit5 原生）**

```java
    // ---- 限额分支（spec §3.2）：filter 门控 / 预算扣减 / 独立上限 / 订阅级计数 ----

    @Test
    void quotaWaitMsFuturePastAndNull() {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");
        assertEquals(90_000, RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.plusSeconds(90)), now));
        // 过去/临近：MIN 下限兜底，杜绝 0ms 轰炸
        assertEquals(RetryPolicy.MIN_QUOTA_WAIT_MS, RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.minusSeconds(60)), now));
        assertEquals(RetryPolicy.MIN_QUOTA_WAIT_MS, RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.plusSeconds(5)), now));
        assertEquals(-1, RetryPolicy.quotaWaitMs(QuotaLimit.withoutResetAt("1316"), now));
    }

    @Test
    void quotaWaitRetriesWithoutConsumingBudget() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            // 注意：message 经 HH:mm:ss 格式化截断到秒——期望值也要按秒截断对齐
            Instant reset = Instant.now().plusSeconds(90).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            AtomicLong waitSeen = new AtomicLong(-1);
            AtomicLong resetSeen = new AtomicLong(-1);
            AtomicInteger normalRetries = new AtomicInteger();
            // 序列：限额 429 ×2 → 普通 IOException ×2 → 成功。maxRetries=2（普通预算）。
            AtomicInteger emissions = new AtomicInteger();
            Flux<Object> src = Flux.defer(() -> {
                int n = emissions.incrementAndGet();
                if (n <= 2) return Flux.error(Quota429s.quota429("1316",
                        "429: 上限，将在 `" + reset.atZone(ZoneId.of("Asia/Shanghai"))
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "` 重置。"));
                if (n <= 4) return Flux.error(new java.io.IOException("eof"));
                return Flux.just("ok");
            });
            StepVerifier.create(src.retryWhen(RetryPolicy.backoffRetry(2, e -> true,
                    (r, ms, f) -> normalRetries.incrementAndGet(),
                    (ms, resetAt, reason) -> { waitSeen.set(ms); resetSeen.set(resetAt); })))
                    .expectNext("ok")
                    .verifyComplete();
            // 2 次限额等待均未消耗普通预算：2 次普通失败仍各获重试（普通重试回调恰好 2 次）
            assertEquals(2, normalRetries.get());
            assertTrue(waitSeen.get() >= 89_000 && waitSeen.get() <= 91_000);
            assertEquals(reset.toEpochMilli(), resetSeen.get());   // 秒截断口径对齐
            assertEquals(5, emissions.get());                      // 2 限额 + 2 普通重试 + 1 成功
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void quotaBranchRespectsFilter() {
        // filter 否决（L1 mid-stream 语义：emitted>0）→ 限额分支不拦截，直接终态（防重放，spec §3.2 必修）
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            Flux<Object> src = Flux.error(Quota429s.quota429("1316",
                    "429: 上限，将在 `2099-01-01 00:00:00` 重置。"));
            StepVerifier.create(src.retryWhen(RetryPolicy.backoffRetry(3, e -> false, null, null)))
                    .verifyErrorSatisfies(e -> assertTrue(e instanceof com.openai.errors.RateLimitException));
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void consecutiveQuotaWaitsCapped() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger n = new AtomicInteger();   // 必须计数钉住 MAX_QUOTA_WAITS（只 verifyError 钉不住）
            Flux<Object> src = Flux.defer(() -> {
                n.incrementAndGet();
                return Flux.error(Quota429s.quota429("1316", "429: 上限，将在 `2099-01-01 00:00:00` 重置。"));
            });
            StepVerifier.create(src.retryWhen(RetryPolicy.backoffRetry(9, e -> true, null, null)))
                    .verifyErrorSatisfies(e -> assertTrue(e instanceof com.openai.errors.RateLimitException));
            assertEquals(6, n.get());   // 5 次限额等待（重订阅）+ 第 6 次失败终态
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void quotaWithNullResetAtFallsToNormalBranch() {
        // 解析失败安全网：落普通分支，计入预算（maxRetries=1 → 1 次重试后耗尽）
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger n = new AtomicInteger();
            Flux<Object> src = Flux.defer(() -> {
                n.incrementAndGet();
                return Flux.error(Quota429s.quota429("1310", "429: 已达到每周使用上限。"));
            });
            StepVerifier.create(src.retryWhen(RetryPolicy.backoffRetry(1, e -> true, null, null)))
                    .verifyError();
            assertEquals(2, n.get());   // 首次 + 1 次普通重试
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void quotaWaitsArePerSubscription() {
        // 同一 Retry 装配给第二个 Flux：计数器独立（订阅级红线，spec §3.2）
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            Retry retry = RetryPolicy.backoffRetry(1, e -> true, null, null);
            java.util.function.Supplier<Flux<Object>> once = () -> {
                AtomicInteger n = new AtomicInteger();
                return Flux.defer(() -> n.incrementAndGet() == 1
                        ? Flux.error(Quota429s.quota429("1316", "429: 上限，将在 `2099-01-01 00:00:00` 重置。"))
                        : Flux.just("ok"));
            };
            StepVerifier.create(once.get().retryWhen(retry)).expectNext("ok").verifyComplete();
            StepVerifier.create(once.get().retryWhen(retry)).expectNext("ok").verifyComplete();
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }
```

（import 按需补：`QuotaLimit`、`Quota429s`、`java.time.Instant`、`java.time.ZoneId`、`java.time.format.DateTimeFormatter`、`java.util.concurrent.atomic.*`、`reactor.core.publisher.Flux`、`reactor.test.StepVerifier`、`org.junit.jupiter.api.Assertions.*`。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=RetryPolicyTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（quotaWaitMs/四参 backoffRetry/MIN_QUOTA_WAIT_MS 不存在）。

- [ ] **Step 3: 实现（RetryPolicy.java）**

新增常量（与 `RETRY_AFTER_CAP_MS` 并列）：

```java
    /** 限额等待下限（30s）：解析出的重置时刻已在过去/临近（服务端时钟偏差、刚重置未生效）时的兜底退避——杜绝 0ms 循环轰炸。 */
    static final long MIN_QUOTA_WAIT_MS = 30_000L;
    /** 单层连续限额等待上限（5）：防「到点重试→又限额→又等」异常死循环；正常场景等 1 次即成功。L1/L2/子 agent 各自独立计数。 */
    static final int MAX_QUOTA_WAITS = 5;
```

新增方法（与 `retryAfterMs` 并列）：

```java
    /** 限额等待毫秒（唯一真相源）：{@code max(resetAt − now, MIN_QUOTA_WAIT_MS)}；quota null 或 resetAt null 返回 -1（调用方退化普通分支）。 */
    public static long quotaWaitMs(QuotaLimit quota) {
        return quotaWaitMs(quota, Instant.now());
    }

    /** 测试可见的可控 now 变体（纯函数）。 */
    static long quotaWaitMs(QuotaLimit quota, java.time.Instant now) {
        if (quota == null || quota.resetAt() == null) {
            return -1;
        }
        long ms = Duration.between(now, quota.resetAt()).toMillis();
        return Math.max(ms, MIN_QUOTA_WAIT_MS);
    }
```

（`import java.time.Instant;` 加入。）

新增接口（与 `RetryHook` 并列）：

```java
    /**
     * 限额等待已排定的回调（退避 delay 前同步执行，时序纪律同 {@link RetryHook}）。
     * 与普通重试互斥——限额等待场景不触发 onRetry（UI 的 ↻ 行与 ⏳ 行互斥，spec §3.3）。
     */
    @FunctionalInterface
    public interface QuotaWaitHook {
        /**
         * @param waitMs        即将等待的毫秒数（含 MIN 下限兜底；与真实 delay 同公式现算）
         * @param resetAtEpochMs 解析出的重置时刻（显示用绝对时间）
         * @param reason        根因摘要（{@link #firstNonBlankMessage}，未截断——截断由 UI 层做）
         */
        void onQuotaWait(long waitMs, long resetAtEpochMs, String reason);
    }
```

改造 `backoffRetry`（原三参方法**整体替换**为下面两个；普通分支唯一行为差异是 effective 扣减——quotaWaits=0 时恒等）：

```java
    /** 三参重载语义不变：不限额上报、限额分支照常生效（spec §3.2）。 */
    public static Retry backoffRetry(long maxRetries, Predicate<Throwable> filter, RetryHook onRetry) {
        return backoffRetry(maxRetries, filter, onRetry, null);
    }

    /**
     * 构造 L1/L2 共用的 reactive {@link Retry} 策略（唯一真相源）。延迟按 {@link #nextDelayMs}
     * 现算（含 Retry-After），jitter 显式关闭（单用户 TUI 无并发，换退避可预测 + 单测好写）。
     *
     * <p><b>限额分支（spec §3.2）</b>：识别智谱限额错误（{@link QuotaLimitDetector}）且解析出
     * 未来重置时刻时，睡到重置点（{@link #quotaWaitMs}，不受 60s 封顶约束）。两条红线：
     * <ol>
     *   <li><b>不豁免 filter</b>——L1 的 emitted==0 闸门 / L2 的 SII 白名单照常生效，否则
     *       mid-stream 限额被 L1 重订阅重放已下发内容；豁免的只有普通耗尽判定；</li>
     *   <li><b>预算扣减</b>——reactor 的 totalRetries 由框架自增（限额等待也推高它，无法重置），
     *       普通分支耗尽判定用 {@code totalRetries − quotaWaits}（quotaWaits 恰计限额次数，
     *       扣减精确；quotaWaits==0 时与旧判定逐字节一致）。</li>
     * </ol>
     *
     * <p><b>quotaWaits 声明位置红线</b>：必须在本方法 lambda 体内（订阅级——FluxRetryWhen 每次
     * 订阅重调 generateCompanion，随（重）订阅重建）。挪到方法体级=装配级（侥幸无害语义错）；
     * 提为静态/实例字段=跨回合累积（真事故）。
     *
     * @param maxRetries  最大普通重试次数（不含首次尝试；限额等待不计入）
     * @param filter      该次失败是否参与重试（各层白名单；限额分支同样受它门控）
     * @param onRetry     普通重试的退避前回调（totalRetries 入参为<b>扣减后</b>的普通重试数）
     * @param onQuotaWait 限额等待的退避前回调；null = 不上报
     */
    public static Retry backoffRetry(long maxRetries, Predicate<Throwable> filter, RetryHook onRetry,
                                     QuotaWaitHook onQuotaWait) {
        return Retry.from(companion -> {
            AtomicInteger quotaWaits = new AtomicInteger();   // 订阅级：严禁挪出本 lambda
            return companion.concatMap(sig -> {
                Throwable failure = sig.failure();
                Optional<QuotaLimit> quota = QuotaLimitDetector.detect(failure);
                if (quota.isPresent() && quota.get().resetAt() != null && filter.test(failure)) {
                    if (quotaWaits.incrementAndGet() > MAX_QUOTA_WAITS) {
                        return Mono.error(failure);              // 限额兜底终态（5 次仍限额）
                    }
                    long waitMs = quotaWaitMs(quota.get());
                    if (onQuotaWait != null) {
                        onQuotaWait.onQuotaWait(waitMs, quota.get().resetAt().toEpochMilli(),
                                firstNonBlankMessage(failure, failure.getClass().getSimpleName()));
                    }
                    return Mono.delay(Duration.ofMillis(delayMsForTest.applyAsLong(waitMs)))
                            .thenReturn(sig.totalRetries());
                }
                long effective = sig.totalRetries() - quotaWaits.get();   // 普通重试数（扣限额）
                if (effective >= maxRetries || !filter.test(failure)) {
                    return Mono.error(failure);                  // 耗尽/不匹配：终态
                }
                long backoffMs = nextDelayMs((int) effective + 1, failure);
                if (onRetry != null) {
                    onRetry.accept(effective, backoffMs, failure);
                }
                long sleepMs = delayMsForTest.applyAsLong(backoffMs);
                return Mono.delay(Duration.ofMillis(sleepMs)).thenReturn(sig.totalRetries());
            });
        });
    }
```

（`import java.time.Instant;`、`import java.util.Optional;`、`import java.util.concurrent.atomic.AtomicInteger;` 按需加；原三参方法的 javadoc 并入四参。）

- [ ] **Step 4: 跑本类测试 + 既有三处回归（零修改必须全绿）**

Run: `mvn -pl springai-code-tui -am test "-Dtest=RetryPolicyTest,RetryingStreamChatModelTest,RetryingChatModelTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（新增 6 例 + 既有全绿——`quotaWaits==0` 恒等性由既有用例守护）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicy.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryPolicyTest.java
git commit -m "feat(code-tui): RetryPolicy 限额分支——睡到重置点、filter 门控、预算扣减"
```

---

### Task 3: L1 接线（`RetryingStreamChatModel` 四参 wrap）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModel.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModelTest.java`（扩展）

**Interfaces:**
- Consumes: Task 2 的 `RetryPolicy.QuotaWaitHook` 与四参 `backoffRetry`；Task 1 的 `Quota429s`。
- Produces: `RetryingStreamChatModel.wrap(ChatModel delegate, RetryReporter reporter, RetryPolicy.QuotaWaitHook quotaHook)` 三参重载（既有两参委托之，传 null）。

- [ ] **Step 1: 写失败测试（追加；桩复用该文件既有 `delegate(Function<Integer,Flux<ChatResponse>>, AtomicInteger)`——script 入参是 1 基调用序号，内部 Flux.defer；`chunk(String)` 也已有）**

三个用例（spec §5-3，断言 JUnit5 原生）：

```java
    // ---- 限额等待（spec §3.3）：握手期等待 / mid-stream 不重放 / dispose 取消 ----

    /** 造 message 内嵌 now+deltaSeconds 重置时刻的 1316。 */
    private static String quotaMessage(long deltaSeconds) {
        String at = java.time.Instant.now().plusSeconds(deltaSeconds)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "429: 已达到 5 小时使用上限。您的限额将在 `" + at + "` 重置。";
    }

    @Test
    void handshakeQuotaWaitFiresQuotaHookNotRetryReporter() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger subs = new AtomicInteger();
            ChatModel delegate = delegate(n -> n == 1
                    ? Flux.error(Quota429s.quota429("1316", quotaMessage(90)))
                    : Flux.just(chunk("hi")), subs);
            List<String> quotaReasons = new java.util.ArrayList<>();
            List<Integer> retryReports = new java.util.ArrayList<>();
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate,
                    (attempt, backoffMs, reason) -> retryReports.add(attempt),
                    (waitMs, resetAt, reason) -> quotaReasons.add(reason));
            StepVerifier.create(wrapped.stream(new Prompt("x")))
                    .expectNextCount(1)
                    .verifyComplete();
            assertEquals(1, quotaReasons.size());
            assertTrue(retryReports.isEmpty());      // ↻ 行与 ⏳ 行互斥
            assertEquals(2, subs.get());
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void midStreamQuotaNotReplayed_wrapsAsSii() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger subs = new AtomicInteger();
            ChatModel delegate = delegate(n -> Flux.concat(
                    Flux.just(chunk("seen")),               // 已下发 → emitted>0
                    Flux.error(Quota429s.quota429("1316", quotaMessage(90)))), subs);
            List<Long> quotaFired = new java.util.ArrayList<>();
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate, null,
                    (waitMs, resetAt, reason) -> quotaFired.add(waitMs));
            StepVerifier.create(wrapped.stream(new Prompt("x")))
                    .expectNextCount(1)                        // chunk 原样下发一次
                    .verifyErrorSatisfies(e ->
                            assertTrue(e instanceof StreamInterruptedException));   // 交 L2，不在 L1 重放
            assertEquals(1, subs.get());                       // 无重订阅
            assertTrue(quotaFired.isEmpty());                  // L1 不等待（filter 门控）
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void disposeDuringQuotaWaitCancels() throws InterruptedException {
        RetryPolicy.setDelayScaleForTest(ms -> 50L);   // 等待压到 50ms：若 dispose 未取消定时器，300ms 内必重订阅
        try {
            AtomicInteger subs = new AtomicInteger();
            ChatModel delegate = delegate(n -> {
                throw Quota429s.quota429("1316", quotaMessage(90));   // script 体内抛 → defer 转 onError
            }, subs);
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate, null, null);
            wrapped.stream(new Prompt("x")).subscribe().dispose();
            assertEquals(1, subs.get());
            Thread.sleep(300);                          // 跨过至少一个等待窗口
            assertEquals(1, subs.get());                // 定时器已随 dispose 取消：无重订阅（硬断言，防假阳性）
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（三参 wrap 不存在）。

- [ ] **Step 3: 实现**

字段与构造：`private final RetryPolicy.QuotaWaitHook quotaHook;`；两参 `wrap` 委托三参：

```java
    /** 包裹一个 ChatModel 为 L1 零下发重试装饰器（生产装配入口；reporter 可 null）。 */
    public static ChatModel wrap(ChatModel delegate, RetryReporter reporter) {
        return wrap(delegate, reporter, null);
    }

    /**
     * 全参装配（spec §3.3）：quotaHook 为限额等待的 UI 上报通道（⏳ 行）；null = no-op。
     * 限额分支判据/退避在 {@link RetryPolicy#backoffRetry} 四参重载内分流——本类只透传钩子。
     */
    public static ChatModel wrap(ChatModel delegate, RetryReporter reporter, RetryPolicy.QuotaWaitHook quotaHook) {
        return new RetryingStreamChatModel(delegate, reporter, quotaHook);
    }

    private RetryingStreamChatModel(ChatModel delegate, RetryReporter reporter, RetryPolicy.QuotaWaitHook quotaHook) {
        this.delegate = delegate;
        this.reporter = reporter;
        this.quotaHook = quotaHook;
    }
```

`stream()` 的 `retryWhen` 改四参（onRetry 回调体原样保留——`totalRetries` 入参已是 Task 2 的扣减口径，`attempt = totalRetries + 2` 不用改）：

```java
                .retryWhen(RetryPolicy.backoffRetry(L1_RETRIES,
                        ex -> emitted.get() == 0 && RetryPolicy.shouldRetry(ex),
                        (totalRetries, backoffMs, failure) -> {
                            int attempt = (int) totalRetries + 2;
                            String reason = reasonOf(failure);
                            log.warn("主 agent 流式请求失败（第 {}/{} 次），{}ms 后重试：{}",
                                    attempt, L1_RETRIES + 1, backoffMs, reason);
                            if (reporter != null) {
                                reporter.report(attempt, backoffMs, reason);
                            }
                        },
                        quotaHook))
```

类 javadoc 的退避段补一句：「限额等待（智谱 Coding Plan 429 限额码）经 `RetryPolicy.backoffRetry` 限额分支分流：睡到重置点、不占普通预算、⏳ 钩子上报——见 spec `2026-09-22-zhipu-quota-wait-retry-design.md`」。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test -Dtest=RetryingStreamChatModelTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（既有用例零修改全绿 + 新增 3 例）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingStreamChatModelTest.java
git commit -m "feat(code-tui): L1 装配限额等待钩子（三参 wrap，mid-stream 不重放）"
```

---

### Task 4: 子 agent 阻塞路径（`RetryingChatModel` while 化 + 限额 + Retry-After 对齐）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModel.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModelTest.java`（扩展）

**Interfaces:**
- Consumes: Task 1 `Quota429s`/`QuotaLimitDetector`；Task 2 `quotaWaitMs`/`MAX_QUOTA_WAITS`/`QuotaWaitHook`/`scaledDelayMsForTest`。
- Produces: `RetryingChatModel.wrap(ChatModel delegate, RetryPolicy.QuotaWaitHook quotaHook)` 两参重载（既有单参委托之）；包私有构造 `(ChatModel, LongConsumer sleeper, RetryPolicy.QuotaWaitHook quotaHook)`（测试注入）。

- [ ] **Step 1: 写失败测试（追加；混合序列桩按既有 `flaky` 风格写匿名 ChatModel——`stream()` 返回 `Flux.defer`，checked 异常一律 `Flux.error(...)` 包裹（blockLast 会包 RuntimeException，cause 链可命中 shouldRetry）；断言 JUnit5 原生）**

```java
    // ---- 限额等待（spec §3.4）：等待不占预算 / 5 次上限 / Retry-After 对齐 ----

    private static String quotaMessage(long deltaSeconds) {
        String at = java.time.Instant.now().plusSeconds(deltaSeconds)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "429: 已达到 5 小时使用上限。您的限额将在 `" + at + "` 重置。";
    }

    /** 混合序列桩：script 收 1 基调用序号，返回该次订阅的 Flux（Flux.error / Flux.just(ChatResponse)）。 */
    private static ChatModel scripted(java.util.function.IntFunction<Flux<ChatResponse>> script,
                                      AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> script.apply(calls.incrementAndGet()));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void quotaWaitSleepsUntilResetWithoutConsumingAttempts() {
        List<Long> sleeps = new java.util.ArrayList<>();
        List<String> quotaReasons = new java.util.ArrayList<>();
        // 序列：1316 ×2 → IOException ×1 → 成功
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> {
            if (n <= 2) return Flux.error(new java.util.concurrent.CompletionException(
                    Quota429s.quota429("1316", quotaMessage(120))));
            if (n == 3) return Flux.error(new java.io.IOException("eof"));
            return Flux.just(textResponse("ok"));
        }, calls);
        RetryingChatModel model = new RetryingChatModel(delegate,
                sleeps::add,
                (waitMs, resetAt, reason) -> quotaReasons.add(reason));
        assertEquals("ok", model.call(new Prompt("x")).getResult().getOutput().getText());
        // 2 次限额等待（≈120s，±5s 容差）+ 1 次普通退避（1s），无双睡
        assertEquals(3, sleeps.size());
        assertTrue(sleeps.get(0) >= 115_000 && sleeps.get(0) <= 125_000);
        assertTrue(sleeps.get(1) >= 115_000 && sleeps.get(1) <= 125_000);
        assertEquals(1000L, sleeps.get(2));
        assertEquals(2, quotaReasons.size());
    }

    @Test
    void quotaWaitsCappedAtFive() {
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> Flux.error(new java.util.concurrent.CompletionException(
                Quota429s.quota429("1316", quotaMessage(3600)))), calls);
        RetryingChatModel model = new RetryingChatModel(delegate, sleeps::add, null);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> model.call(new Prompt("x")));
        // 5 次等待 = 6 次调用后抛出原始 429
        assertTrue(java.util.stream.Stream.of(thrown).anyMatch(t ->
                t instanceof com.openai.errors.RateLimitException
                        || (t.getCause() instanceof com.openai.errors.RateLimitException)));
        assertEquals(6, calls.get());
        assertEquals(5, sleeps.size());
    }

    @Test
    void quotaWithoutResetAtFallsBackToNormalBudget() {
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> Flux.error(new java.util.concurrent.CompletionException(
                Quota429s.quota429("1310", "429: 已达到每周使用上限。"))), calls);   // 无时间 → 普通分支
        RetryingChatModel model = new RetryingChatModel(delegate, sleeps::add, null);
        assertThrows(RuntimeException.class, () -> model.call(new Prompt("x")));
        assertEquals(7, calls.get());   // 全预算 MAX_ATTEMPTS=7
        assertEquals(6, sleeps.size());
    }

    @Test
    void backoffNowHonorsRetryAfterHeader() {
        // 既有偏差修复（spec §3.4.1）：429 WCRE 带 Retry-After: 3 → 退避 3s（旧实现恒 1s）。
        // create 的第 3 参就是 HttpHeaders，retryAfterMs 经 wcre.getHeaders().getFirst 读到。
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Retry-After", "3");
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> Flux.error(
                org.springframework.web.reactive.function.client.WebClientResponseException
                        .create(429, "Too Many Requests", headers, null, null)), calls);
        RetryingChatModel model = new RetryingChatModel(delegate, sleeps::add, null);
        assertThrows(RuntimeException.class, () -> model.call(new Prompt("x")));
        assertEquals(3000L, sleeps.get(0));
    }
```

（import 按需补：`Quota429s`、`java.util.List`、`java.util.concurrent.atomic.AtomicInteger`、`org.junit.jupiter.api.Assertions.*` 的 `assertThrows` 该文件已有。**注意**：`quotaWaitsCappedAtFive` 的异常形态断言用 cause 链遍历——`call` 直接 throw 原始 failure（429 可能被 CompletionException 包着），两种形态都接受。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=RetryingChatModelTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（三参构造不存在）+ 新用例 FAIL。

- [ ] **Step 3: 实现（call() 整体重写为 while 循环）**

```java
    private final RetryPolicy.QuotaWaitHook quotaHook;

    /** 生产装配（既有语义；quota 上报 no-op）。 */
    public static ChatModel wrap(ChatModel delegate) {
        return wrap(delegate, null);
    }

    /** 全参装配（spec §3.4.4）：quotaHook 为限额等待 UI 上报；null = no-op。 */
    public static ChatModel wrap(ChatModel delegate, RetryPolicy.QuotaWaitHook quotaHook) {
        return new RetryingChatModel(delegate, quotaHook);
    }

    private RetryingChatModel(ChatModel delegate, RetryPolicy.QuotaWaitHook quotaHook) {
        this(delegate, defaultSleeper(), quotaHook);
    }

    /** 既有两参构造（既有测试在用，保留委托——H3：删了会编译失败）。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper) {
        this(delegate, sleeper, null);
    }

    /** 测试可见：注入休眠器与限额钩子。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper, RetryPolicy.QuotaWaitHook quotaHook) {
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.quotaHook = quotaHook;
    }

    /** 生产 sleeper（原构造内匿名体提取为方法，两处构造共用；换算纪律不变——调用点传 raw 值）。 */
    private static LongConsumer defaultSleeper() {
        return ms -> {
            try {
                Thread.sleep(RetryPolicy.scaledDelayMsForTest(ms));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        };
    }

    /**
     * 阻塞调用 + 瞬态重试（while 形态，spec §3.4 实现红线：单次迭代恰一次睡眠；
     * 限额判定先于 attempt 预算 bail——否则第 7 次尝试上的限额直接抛）。
     * 限额等待不递增 attempt（预算豁免），由 quotaWaits ≤ {@link RetryPolicy#MAX_QUOTA_WAITS} 兜底。
     *
     * <p><b>空流豁免 shouldRetry（语义钉）</b>：原 for 实现对空流是合成异常后<b>无条件</b>重试
     * （不问 shouldRetry——合成异常无瞬态特征，问就是必抛）。while 版用 emptyStream 标志保留该语义。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        int attempt = 1;                 // 即将进行的尝试（1 基）
        int quotaWaits = 0;              // 本 call 内的连续限额等待（每次调用重建，天然回合级）
        while (true) {
            boolean emptyStream;
            RuntimeException failure;
            try {
                ChatResponse aggregated = streamAndAggregate(prompt);
                if (!isEffectivelyEmpty(aggregated)) {
                    return aggregated;
                }
                emptyStream = true;      // 空流：不设 shouldRetry 门（原 for 语义）
                failure = new RuntimeException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应，已尝试 "
                        + attempt + "/" + MAX_ATTEMPTS + " 次");
                log.warn("LLM 返回空流（疑似网关坏响应），第 {}/{} 次尝试{}", attempt, MAX_ATTEMPTS,
                        attempt < MAX_ATTEMPTS ? "，将重试" : "，放弃");
            } catch (RuntimeException ex) {
                emptyStream = false;
                failure = ex;
            }
            // 限额分支：先于普通预算 bail；取消/中断类失败（shouldRetry 否决）与空流绝不等待
            java.util.Optional<QuotaLimit> quota = (!emptyStream && RetryPolicy.shouldRetry(failure))
                    ? QuotaLimitDetector.detect(failure) : java.util.Optional.empty();
            if (quota.isPresent() && quota.get().resetAt() != null) {
                if (quotaWaits++ >= RetryPolicy.MAX_QUOTA_WAITS) {
                    throw failure;                                 // 限额兜底终态
                }
                long waitMs = RetryPolicy.quotaWaitMs(quota.get());
                log.warn("智谱限额已到（code={}，resetAt={}），等待 {}ms 后重试（quotaWait {}/{}）：{}",
                        quota.get().code(), quota.get().resetAt(), waitMs, quotaWaits,
                        RetryPolicy.MAX_QUOTA_WAITS,
                        RetryPolicy.firstNonBlankMessage(failure, failure.getClass().getSimpleName()));
                if (quotaHook != null) {
                    quotaHook.onQuotaWait(waitMs, quota.get().resetAt().toEpochMilli(),
                            RetryPolicy.firstNonBlankMessage(failure, failure.getClass().getSimpleName()));
                }
                sleeper.accept(waitMs);
                continue;                                          // attempt 不递增：不占普通预算
            }
            if ((!emptyStream && !shouldRetry(failure)) || attempt >= MAX_ATTEMPTS) {
                throw failure;
            }
            if (!emptyStream) {   // 空流只打上面那条日志，别双打
                log.warn("LLM 流式请求失败（疑似网关坏响应），第 {}/{} 次尝试后重试：{}",
                        attempt, MAX_ATTEMPTS, failure.getMessage());
            }
            sleeper.accept(RetryPolicy.nextDelayMs(attempt, failure));   // raw 值：生产 sleeper 体内做换算
            attempt++;
        }
    }
```

（原 `for` 版 `call` 与 `last` 变量删除——while 版语义覆盖；`import java.util.Optional;` 按需加。类 javadoc 退避段补限额说明，同 Task 3 风格引用 spec。⚠ 空流路径在 attempt 耗尽时与原版一致抛出合成异常 ✓；中断传播：sleeper 抛 `RuntimeException(InterruptedException)` 直接冒出 while ✓。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test "-Dtest=RetryingChatModelTest,SubagentRunnerOkTest,SubagentRunnerParallelTest,SubagentRunnerBackgroundTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（既有用例零修改全绿 + 新增 4 例；既有 sleeper 断言 `[1000..30000]` 序列的用例不受影响——无 Retry-After 时 `nextDelayMs` 恒等 `backoffMsAfter`。SubagentRunner 系走的就是 call() 路径，call() 重写必须带上它们回归。）

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/RetryingChatModelTest.java
git commit -m "feat(code-tui): 子 agent 限额等待（while 化、不占预算）+ 退避对齐 Retry-After"
```

---

### Task 5: UI 状态（`AgentListener` 新事件 + `ConversationState` 限额等待态）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateQuotaWaitTest.java`（新文件）

**Interfaces:**
- Produces: `AgentListener.onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason)`（default 空）；`ConversationState` 覆写之；`ConversationState.quotaWaitDeadline(): Long`、`quotaWaitReason(): String`（渲染层现算用）；public static `ConversationState.formatQuotaRemaining(long remainMs): String`；`clearRetryState()` 并入两字段清除（契约注释同步扩展）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 限额等待态（spec §3.5）：进入/清除纪律 + -1 丢弃 + 剩余时间格式化。进入接受态用既有 API onTurnStarted(long)。 */
class ConversationStateQuotaWaitTest {

    @Test
    void quotaWaitEntersRetryingWithDeadline() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        long reset = System.currentTimeMillis() + 90_000;
        s.onQuotaWaitScheduled(1L, reset, "429: 已达到 5 小时使用上限");
        assertEquals(ConversationState.Status.RETRYING, s.status());
        assertEquals("⏳ 限额等待", s.retryLabel());
        assertEquals(reset, s.quotaWaitDeadline());
        assertNotNull(s.quotaWaitReason());
        assertTrue(s.quotaWaitReason().contains("使用上限"));
    }

    @Test
    void staleTurnIdDropped() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(999, System.currentTimeMillis() + 90_000, "r");
        assertNull(s.quotaWaitDeadline());
    }

    @Test
    void backgroundMinusOneDropped() {
        // 后台子 agent turnId=-1：空闲态 acceptingTurnId==-1 会穿透过滤（spec §3.4.4）——必须丢弃
        ConversationState s = new ConversationState();
        s.onQuotaWaitScheduled(-1, System.currentTimeMillis() + 90_000, "r");
        assertNull(s.quotaWaitDeadline());
    }

    @Test
    void leavingEventsClearDeadline() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(1L, System.currentTimeMillis() + 90_000, "r");
        assertNotNull(s.quotaWaitDeadline());
        s.onTurnComplete(1L);                       // 任一离开事件
        assertNull(s.quotaWaitDeadline());          // clearRetryState 并入清除
        assertNull(s.retryLabel());
    }

    @Test
    void retryScheduledAlsoClearsDeadline() {
        // spec §3.5 清除纪律：onRetryScheduled 本身不清 retryLabel（它设置新值），必须显式清 deadline——
        // 否则限额等待→到点→普通瞬态失败时，状态栏用过期 deadline 现算「即将重试」盖掉新退避
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(1L, System.currentTimeMillis() + 90_000, "r");
        s.onRetryScheduled(1L, 2, 7, 1000, "传输");
        assertNull(s.quotaWaitDeadline());
        assertEquals("↻ 重试中", s.retryLabel());   // 普通重试态正常进入
    }

    @Test
    void formatQuotaRemainingMatrix() {
        assertEquals("即将重试", ConversationState.formatQuotaRemaining(-1));
        assertEquals("45s", ConversationState.formatQuotaRemaining(45_000));
        assertEquals("2m30s", ConversationState.formatQuotaRemaining(150_000));
        assertEquals("3h5m", ConversationState.formatQuotaRemaining(3 * 3600_000L + 5 * 60_000L));
        assertEquals("6d23h", ConversationState.formatQuotaRemaining(6L * 24 * 3600_000 + 23 * 3600_000L));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=ConversationStateQuotaWaitTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（方法不存在）。

- [ ] **Step 3: 实现**

`AgentListener.java`（`onRetryScheduled` 后面加）：

```java
    /**
     * 限额等待已排定（spec §3.5）：回合挂起睡到重置时刻。与 {@link #onRetryScheduled} 互斥
     * （同一次失败只会走其一）。turnId 迟到过滤纪律同 onRetryScheduled；后台子 agent 的 -1
     * 由 ConversationState 侧丢弃。
     */
    default void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) { }
```

`ConversationState.java`：

字段（`retryBackoffText` 旁）+ getter：

```java
    /** 限额等待的重置时刻（epoch ms）；null = 非限额等待。渲染层每帧现算剩余（RETRYING 态动画帧持续重绘，无 ticker）。 */
    private volatile Long quotaWaitDeadline;
    private volatile String quotaWaitReason;

    public Long quotaWaitDeadline() { return quotaWaitDeadline; }
    public String quotaWaitReason() { return quotaWaitReason; }
```

覆写（`onRetryScheduled` 后面，骨架复用其锁纪律）：

```java
    @Override
    public void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) {
        Change change = null;
        synchronized (this) {
            // -1（后台子 agent）显式丢弃：空闲态 acceptingTurnId==-1 会穿透比较（onToolStarted 踩过同坑）
            if (turnId < 0 || turnId != acceptingTurnId) return;
            int bits = flushStreaming();
            if ((bits & UiDirty.OUTPUT) != 0) {
                pending.add(new OutputLine("", OutputLine.Kind.INFO));
            }
            String at = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(resetAtEpochMs));
            String prefix = "⏳ 限额等待：将于 " + at + " 自动重试（Esc 取消）：";
            pending.add(new OutputLine(prefix
                    + summarizeRetryReason(reason, 80 - CharWidth.of(prefix)), OutputLine.Kind.INFO));
            status = Status.RETRYING;
            retryLabel = "⏳ 限额等待";
            retryBackoffText = formatQuotaRemaining(
                    Math.max(0, resetAtEpochMs - System.currentTimeMillis()));
            quotaWaitDeadline = resetAtEpochMs;
            quotaWaitReason = reason;
            change = changed(UiDirty.ALL);
        }
        publish(change);
    }
```

**既有 `onRetryScheduled` 的一处修改**（M2 清除纪律：它不调 `clearRetryState`，须显式清——否则限额等待→到点→普通瞬态失败时状态栏用过期 deadline 现算，spec §3.5「新一轮 onRetryScheduled 也是离开等待事件」）——锁内 `retryBackoffText = formatBackoff(backoffMs);` 之后补：

```java
            quotaWaitDeadline = null;      // spec §3.5：新一轮普通重试也是「离开限额等待」事件
            quotaWaitReason = null;
```

`clearRetryState()` 扩展（契约注释同步改「…重试/限额等待状态的路径必须调用」）：

```java
    private void clearRetryState() {
        retryLabel = null;
        retryBackoffText = null;
        quotaWaitDeadline = null;
        quotaWaitReason = null;
    }
```

格式化（`formatBackoff` 旁，public 供 View 现算）：

```java
    /** 限额等待剩余时间（渲染帧现算用）：45s / 2m30s / 3h5m / 6d23h；过期显示「即将重试」。 */
    public static String formatQuotaRemaining(long remainMs) {
        if (remainMs <= 0) return "即将重试";
        long s = remainMs / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m" + (s % 60) + "s";
        long h = m / 60;
        if (h < 24) return h + "h" + (m % 60) + "m";
        return (h / 24) + "d" + (h % 24) + "h";
    }
```

- [ ] **Step 4: 跑测试确认通过 + UI 包回归**

Run: `mvn -pl springai-code-tui -am test "-Dtest=ConversationStateQuotaWaitTest,CodeTuiViewInterjectionStatusTest,ConversationStatePlanTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（clearRetryState 扩展不破坏既有重试态用例）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateQuotaWaitTest.java
git commit -m "feat(code-tui): 限额等待 UI 态（⏳ 行 + deadline 现算 + -1 丢弃 + 清除纪律）"
```

---

### Task 6: 状态栏倒计时渲染（`CodeTuiView`，帧现算无 ticker）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（RETRYING 渲染分支，约 4080-4087 行）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewQuotaWaitRenderTest.java`（新文件）

**Interfaces:**
- Consumes: Task 5 的 `quotaWaitDeadline()`/`formatQuotaRemaining`。
- Produces: 无新公开接口（渲染分支内部改动）。

- [ ] **Step 1: 写失败测试（渲染分支抽出包私有 helper `quotaBackoffText` 直测——比 ViewScreen 渲染断言更轻更硬；格式化矩阵已在 Task 5 覆盖，这里钉「deadline 存在 → 现算倒计时；deadline 为 null → 用静态 retryBackoffText」）**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 限额倒计时渲染（spec §3.5）：RETRYING 态每帧从 deadline 现算剩余时间（无 ticker）。 */
class CodeTuiViewQuotaWaitRenderTest {

    @Test
    void quotaDeadlineRendersCountdownInsteadOfStaticBackoff() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        long now = System.currentTimeMillis();
        // deadline 存在：现算剩余（2h13m 前推的 deadline，now 对齐）
        s.onQuotaWaitScheduled(1L, now + 2 * 3600_000L + 13 * 60_000L, "429: 上限");
        assertEquals("2h13m", CodeTuiView.quotaBackoffText(s, now + 1000));   // 1s 后剩 2h12m59s→按整分截断口径由 helper 定
        // deadline 为 null：回落静态 retryBackoffText（普通重试路径不受影响）
        s.onRetryScheduled(1L, 2, 7, 30_000, "传输");
        assertEquals("30.0s", CodeTuiView.quotaBackoffText(s, now + 1000));
    }
}
```

（helper 签名 `static String quotaBackoffText(ConversationState state, long nowMillis)`：`Long d = state.quotaWaitDeadline(); return d != null ? ConversationState.formatQuotaRemaining(d - nowMillis) : state.retryBackoffText();`——第一个断言的期望值按「2h13m 减 1s 后整分展示」自行精确化（`formatQuotaRemaining` 对 2h12m59s 输出 `2h12m`）——执行时先写 helper 语义再对齐断言，两处一致即可。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewQuotaWaitRenderTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（渲染仍用静态 retryBackoffText）。

- [ ] **Step 3: 实现（RETRYING 分支改造 + helper）**

先在 `CodeTuiView` 加包私有 helper（测试直测点；渲染分支只调它）：

```java
    /**
     * RETRYING 态的退避显示文本（spec §3.5）：限额等待时每帧从 deadline 现算剩余
     * （RETRYING 态动画协调器持续重绘 ~66ms，与 compactElapsedNanos 同款现算模式——不新增 ticker）；
     * 非限额等待回落静态 retryBackoffText（普通重试路径行为不变）。
     */
    static String quotaBackoffText(ConversationState state, long nowMillis) {
        Long d = state.quotaWaitDeadline();
        return d != null ? ConversationState.formatQuotaRemaining(d - nowMillis) : state.retryBackoffText();
    }
```

RETRYING 分支（`CodeTuiView` 约 4080-4086 行）的 backoff 一行改为调 helper：

```java
            case RETRYING -> {
                String label = state.retryLabel() == null ? "↻ 重试中" : state.retryLabel();
                String backoff = quotaBackoffText(state, System.currentTimeMillis());
                String backoffTail = terminalWidth() >= 100 && backoff != null ? " · 退避 " + backoff : "";
                String suffix = qs + ijs + ns + backoffTail + " · Esc 取消" + projectSuffix;
                yield richText(statusBar.shimmer(label, suffix, THINK, animTick, mode));
            }
```

（deadline 过期时 formatQuotaRemaining 返回「即将重试」——自愈显示，下一事件必到并清除。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test "-Dtest=CodeTuiViewQuotaWaitRenderTest,CodeTuiViewInterjectionStatusTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewQuotaWaitRenderTest.java
git commit -m "feat(code-tui): 状态栏限额倒计时（渲染帧现算，无 ticker）"
```

---

### Task 7: 子 agent 可取消 + 限额 UI 桥（`SubagentRunner`）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerSerialInterruptTest.java`（新文件；构造桩参考既有 `SubagentRunnerTest`）

**Interfaces:**
- Consumes: Task 4 的 `RetryingChatModel.wrap(ChatModel, RetryPolicy.QuotaWaitHook)`；Task 5 的 `AgentListener.onQuotaWaitScheduled`。
- Produces: `cancelTurn` 对串行执行线程 interrupt（行为扩展，签名不变）；`execute` 内 wrap 传 quota 桥闭包（-1 丢弃 + 日志）。

- [ ] **Step 1: 写失败测试（桩照抄 `SubagentRunnerOkTest` 实名形态：匿名 `LlmProvider` 桩 + `new ProviderRegistry(List.of(provider(model)))` + `new SubagentRunner(reg, List.of(), listener, "")` + `new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of())`；listener 桩 extends `agent.seam.StubListener`；断言 JUnit5）**

```java
package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.Quota429s;
import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 串行子 agent 可取消 + 限额 UI 桥（spec §3.4.3/§3.4.4）。 */
class SubagentRunnerSerialInterruptTest {

    private static String quotaMessage(long deltaSeconds) {
        String at = java.time.Instant.now().plusSeconds(deltaSeconds)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "429: 已达到 5 小时使用上限。您的限额将在 `" + at + "` 重置。";
    }

    /** 脚本桩：第 n 次（1 基）订阅返回 script 的 Flux。 */
    private static ChatModel scripted(java.util.function.IntFunction<Flux<ChatResponse>> script,
                                      AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> script.apply(calls.incrementAndGet()));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 假 LlmProvider（照抄 SubagentRunnerOkTest.provider）。 */
    private static LlmProvider provider(ChatModel model) {
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<LlmProvider.ModelOption> models() { return List.of(new LlmProvider.ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    /** 收限额事件的 listener（extends StubListener，照抄 OkTest.RecordingListener 模式）。 */
    private static final class QuotaRecordingListener extends StubListener {
        final List<Long> quotaTurnIds = new CopyOnWriteArrayList<>();
        @Override public void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) {
            quotaTurnIds.add(turnId);
        }
    }

    @Test
    void serialQuotaWaitInterruptedByCancelTurn() throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> {
            firstCall.countDown();
            return Flux.error(new java.util.concurrent.CompletionException(
                    Quota429s.quota429("1316", quotaMessage(3600))));   // 1h 等待：生产 sleeper 真睡
        }, calls);
        QuotaRecordingListener lis = new QuotaRecordingListener();
        SubagentRunner runner = new SubagentRunner(
                new ProviderRegistry(List.of(provider(delegate))), List.of(), lis, "");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                runner.run(spec(), "p", "d", 77L);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        assertTrue(firstCall.await(5, TimeUnit.SECONDS));   // 已进入限额等待（Thread.sleep 真睡）
        runner.cancelTurn(77L);                              // interrupt 串行线程 → sleeper 抛 RuntimeException
        worker.join(5000);
        assertFalse(worker.isAlive());                       // 不再挂死（未实现 interrupt 时此断言红：worker 仍在睡）
        assertNotNull(failure.get());                        // run 以异常收场
    }

    @Test
    void quotaHookBridgesToListenerForForeground() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));   // 压缩等待，run 可跑完
        try {
            AtomicInteger calls = new AtomicInteger();
            ChatModel delegate = scripted(n -> n == 1
                    ? Flux.error(new java.util.concurrent.CompletionException(
                            Quota429s.quota429("1316", quotaMessage(3600))))
                    : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))), calls);
            QuotaRecordingListener lis = new QuotaRecordingListener();
            SubagentRunner runner = new SubagentRunner(
                    new ProviderRegistry(List.of(provider(delegate))), List.of(), lis, "");
            assertEquals("done", runner.run(spec(), "p", "d", 77L));
            assertEquals(List.of(77L), lis.quotaTurnIds);   // 前台桥通（reason 带「子任务」前缀，另断言可加）
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }
}
```

（后台 -1 的 UI 丢弃已由 `ConversationStateQuotaWaitTest.backgroundMinusOneDropped` 钉住 UI 侧守卫；SubagentRunner 桥闭包的 `turnId < 0` 分支为两行早退，代码评审覆盖，不构造后台注册表——YAGNI。`ModelOption` 的包路径以 OkTest import 为准（可能在 `agent.llm` 或 models 子包），照抄即可。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=SubagentRunnerSerialInterruptTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: 用例 1 FAIL（`assertFalse(worker.isAlive())` 红——cancelTurn 不 interrupt 串行线程，worker 仍在 1h 睡眠中，join(5000) 超时后存活）；用例 2 FAIL（编译错：wrap 两参不存在）。

- [ ] **Step 3: 实现**

字段（`poolsByTurn` 旁）：

```java
    /**
     * 回合 → 该回合串行（前台 Task 工具内同步执行）子 agent 的执行线程。串行路径无池
     * （javadoc 原注「无法强制打断」）——限额等待把不可打断窗口拉到小时/天级且 busy 闸门
     * 排队后续输入造成假死，故登记线程由 {@link #cancelTurn} interrupt（spec §3.4.3 必修）。
     * 与 {@link #poolsByTurn} 同构；同 turn 串行 run 同时至多一个（主 agent 工具循环串行）。
     */
    private final java.util.Map<Long, java.util.Set<Thread>> serialThreadsByTurn = new java.util.concurrent.ConcurrentHashMap<>();
```

`run()` 改造（登记/清理——⚠ M3 纪律：登记必须**在 try 内**作首段语句。本类成文纪律是「`inFlight.incrementAndGet()` 是 try 前**最后一条**语句、publish 是 try 首语句——即便抛 Error，递减也在同一 try 的 finally」。若把 `computeIfAbsent/add` 插在 increment 与 try 之间，这两步抛 Error 时 inFlight 已增而 finally 未挂上——正是该纪律要封的泄漏窗口。摘除用 `computeIfPresent` 原子完成（消掉 remove/checkEmpty 两步竞态，与 runAll 池摘除同款）；其余原样）：

```java
        inFlight.incrementAndGet();
        try {
            java.util.Set<Thread> threads = serialThreadsByTurn
                    .computeIfAbsent(parentTurnId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            threads.add(Thread.currentThread());
            publish(changed());
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                           ToolEventCallback.TASK_ID_KEY, taskId));
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
        } catch (RuntimeException ex) {
            /* 原 catch 体原样 */
        } finally {
            serialThreadsByTurn.computeIfPresent(parentTurnId, (k, set) -> {
                set.remove(Thread.currentThread());
                return set.isEmpty() ? null : set;   // remapping 返回 null 即删除 entry，防泄漏
            });
            inFlight.decrementAndGet();
            publish(changed());
        }
```

`cancelTurn` 扩展：

```java
    public void cancelTurn(long parentTurnId) {
        Set<ExecutorService> turnPools = poolsByTurn.get(parentTurnId);
        if (turnPools != null) {
            for (ExecutorService pool : turnPools) {
                pool.shutdownNow();
            }
        }
        // 串行子 agent（限额等待长睡的 Thread.sleep 响应 interrupt → sleeper 抛 RuntimeException 杀循环）。
        // best-effort：若线程恰在网络 IO，interrupt 标志位置位、下次 sleep 立即抛——与并行池语义一致。
        java.util.Set<Thread> serials = serialThreadsByTurn.get(parentTurnId);
        if (serials != null) {
            for (Thread t : serials) {
                t.interrupt();
            }
        }
    }
```

`execute()` 的 wrap 换两参 + quota 桥闭包：

```java
    private String execute(SubagentSpec spec, String prompt, Map<String, Object> toolContext) {
        ProviderRegistry.RequestSelection selection = resolveSelection(spec);
        long turnId = toolContext.get(ToolEventCallback.TURN_ID_KEY) instanceof Long l ? l : -1L;
        String taskId = String.valueOf(toolContext.get(ToolEventCallback.TASK_ID_KEY));
        RetryPolicy.QuotaWaitHook quotaHook = (waitMs, resetAtEpochMs, reason) -> {
            if (turnId < 0) {
                // 后台子 agent：-1 会穿透 ConversationState 空闲态过滤（acceptingTurnId==-1），
                // 打进空闲界面——丢弃 UI 上报只留日志（spec §3.4.4）。
                log.info("后台子 agent 限额等待（taskId={}）：{}ms 后重试", taskId, waitMs);
                return;
            }
            listener.onQuotaWaitScheduled(turnId, resetAtEpochMs, "子任务 " + reason);
        };
        ChatClient client = ChatClient.builder(
                        RetryingChatModel.wrap(selection.provider().chatModel(), quotaHook),
                        ObservationRegistry.NOOP, null, null,
                        ToolCallingAdvisor.builder().toolCallingManager(TurnToolLimitWiring.create()))
                .defaultTools(effectiveTools(spec).toArray())
                .build();
        /* 其余原样 */
```

（`import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;` 按需加。javadoc 类注释「串行 run() 无池、无法强制打断」一句更新为「串行经 serialThreadsByTurn 登记 + cancelTurn interrupt」。）

- [ ] **Step 4: 跑测试确认通过 + 子 agent 包回归**

Run: `mvn -pl springai-code-tui -am test "-Dtest=SubagentRunnerSerialInterruptTest,SubagentRunnerTest,SubagentRunnerParallelTest,SubagentRunnerBackgroundTest,SubagentRunnerOkTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerSerialInterruptTest.java
git commit -m "feat(code-tui): 子 agent 限额 UI 桥 + 串行执行线程登记、cancelTurn interrupt"
```

---

### Task 8: 主链桥接（`CodingAgent` L1 sink/L2 直连/续跑文案 + `AgentTools` 装配）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/QuotaWiringTest.java`（新文件）

**Interfaces:**
- Consumes: Task 2/3/5 的 `QuotaWaitHook`/三参 wrap/`onQuotaWaitScheduled`。
- Produces: `CodingAgent` 包私有 `onL1QuotaWait(long waitMs, long resetAtEpochMs, String reason)`（供 `AgentTools.wireL1` 方法引用）；`AgentTools.L1QuotaBridge`（镜像 `L1ReporterBridge`）+ `AgentRuntime` record 新字段 `L1QuotaBridge quotaBridge` + `wireL1` 同时 bind 两桥。

- [ ] **Step 1: 写失败测试（桥用例完整落地；CodingAgent 侧用例照抄 `AgentToolsRetryWiringTest` 的反射模式——该文件已有 `getDeclaredField + setAccessible` 大量先例；断言 JUnit5）**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 限额等待主链桥（spec §3.6）：L1QuotaBridge 两段式 + CodingAgent.onL1QuotaWait 转发。 */
class QuotaWiringTest {

    static final class RecordingHook implements RetryPolicy.QuotaWaitHook {
        final List<Long> waits = new CopyOnWriteArrayList<>();
        final List<Long> resets = new CopyOnWriteArrayList<>();
        @Override public void onQuotaWait(long waitMs, long resetAtEpochMs, String reason) {
            waits.add(waitMs);
            resets.add(resetAtEpochMs);
        }
    }

    @Test
    void l1QuotaBridgeNoOpBeforeBindAndForwardsAfter() {
        AgentTools.L1QuotaBridge bridge = new AgentTools.L1QuotaBridge();
        assertDoesNotThrow(() -> bridge.onQuotaWait(1, 2, "r"));   // 未 bind：null 守卫 no-op
        RecordingHook recorder = new RecordingHook();
        bridge.bind(recorder);
        bridge.onQuotaWait(90_000L, 123L, "r");
        assertEquals(List.of(90_000L), recorder.waits);
        assertEquals(List.of(123L), recorder.resets);
    }

    @Test
    void codingAgentOnL1QuotaWaitForwardsToSinkOrNullGuards() throws Exception {
        // 构造最小 CodingAgent（照抄 AgentToolsRetryWiringTest / CodingAgentTurnResumeTest 的最小构造段：
        // 桩 ChatClient + listener + sessionId + activeTurnId + sessionService + manualStrategy + tokenCountEstimator）。
        // 1) 未置 sink（新实例默认 null）→ onL1QuotaWait no-op 不抛
        // 2) 反射置 activeTurnQuotaSink = recorder → onL1QuotaWait(90_000, 123, "r") → recorder 收到同参
        // 断言骨架：
        //   CodingAgent agent = minimalAgent();   // 照抄先例构造
        //   assertDoesNotThrow(() -> agent.onL1QuotaWait(1, 2, "r"));
        //   RecordingHook recorder = new RecordingHook();
        //   Field f = CodingAgent.class.getDeclaredField("activeTurnQuotaSink");
        //   f.setAccessible(true);
        //   f.set(agent, recorder);
        //   agent.onL1QuotaWait(90_000L, 123L, "r");
        //   assertEquals(List.of(90_000L), recorder.waits);
    }
}
```

（用例 2 的构造段照抄先例文件的具体桩代码落地——反射置位与断言部分已给全；submit 内 sink 闭包的 turnId 过滤由 Task 9 全量回归与 `CodingAgentTurnResumeTest` 既有迟到过滤模式同构守护，不重复构造阻塞回合。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=QuotaWiringTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（L1QuotaBridge/onL1QuotaWait 不存在）。

- [ ] **Step 3: 实现（CodingAgent）**

字段（`activeTurnL1Sink` 旁）：

```java
    /** L1 限额等待事件的本回合 sink（spec §3.6，镜像 {@link #activeTurnL1Sink}）。 */
    private volatile RetryPolicy.QuotaWaitHook activeTurnQuotaSink;
```

⚠ **续跑文案标记不得用实例字段（M4）**：`CodingAgent.submit` 内已有成文纪律「回合局部状态……不得为实例字段——跨回合残留会让下一回合健康首轮误执行 prepareResume」。故限额续跑标记用 **submit 局部 `AtomicBoolean resumeFromQuota = new AtomicBoolean()`**，经闭包与参数传递（L2 quota 回调闭包捕获 set；`prepareResume`/`composeResumeUser` 增参 `AtomicBoolean resumeFromQuota` 消费 `getAndSet(false)`——时序：回调在等待前置位、prepareResume 在重订阅后消费，多轮交错每轮消费复位，无错标；局部性天然免疫跨回合残留）。

包私有转发（`onL1Retry` 旁）：

```java
    /** L1 限额等待事件入口（AgentTools.wireL1 bind；spec §3.6）。 */
    void onL1QuotaWait(long waitMs, long resetAtEpochMs, String reason) {
        RetryPolicy.QuotaWaitHook sink = activeTurnQuotaSink;
        if (sink != null) {
            sink.onQuotaWait(waitMs, resetAtEpochMs, reason);
        }
    }
```

`submit` 内（`activeTurnL1Sink = ...` 赋值块**后面**紧邻加；`AtomicBoolean resumeFromQuota = new AtomicBoolean()` 声明在 submit 局部与 `l2Enabled` 同段）：

```java
        activeTurnQuotaSink = (waitMs, resetAtEpochMs, reason) -> {
            if (activeTurnId.get() == turnId) {
                listener.onQuotaWaitScheduled(turnId, resetAtEpochMs, reason);
            }
        };
```

L2 `retryWhen` 改四参（回调体追加 quota 分支）：

```java
                .retryWhen(RetryPolicy.backoffRetry(L2_RESUMES,
                        ex -> l2Enabled && ex instanceof StreamInterruptedException,
                        (totalRetries, backoffMs, failure) -> listener.onRetryScheduled(turnId,
                                (int) totalRetries + 1,
                                (int) L2_RESUMES,
                                backoffMs,
                                "流中断"),
                        (waitMs, resetAtEpochMs, reason) -> {
                            resumeFromQuota.set(true);               // prepareResume 消费（spec §3.5 文案二选）
                            listener.onQuotaWaitScheduled(turnId, resetAtEpochMs, reason);
                        }))
```

续跑文案二选：`RESUME_NOTICE` 旁加常量，`prepareResume` 与 `composeResumeUser` 使用点改为按标记选值：

```java
    /** 限额等待后的续跑通知（spec §3.5：按失败类型二选，防「网络中断」文案失真）。 */
    private static final String RESUME_NOTICE_QUOTA = "<system-notice>\n"
            + "额度限额等待结束，你的上一段输出未被保留。请从中断处继续完成回答，不要重复已输出的内容。\n"
            + "</system-notice>";

    /** 本次续跑的通知文本（消费 submit 局部 resumeFromQuota 并复位；仅 resub>1 路径调用——增参传入，不用实例字段）。 */
    private static String resumeNotice(java.util.concurrent.atomic.AtomicBoolean resumeFromQuota) {
        return resumeFromQuota.getAndSet(false) ? RESUME_NOTICE_QUOTA : RESUME_NOTICE;
    }
```

（`prepareResume` 的 `interjections.setResumeNotice(RESUME_NOTICE)` 与 `composeResumeUser` 的两处 `RESUME_NOTICE` 拼接全部改调 `resumeNotice(resumeFromQuota)`——`prepareResume`/`composeResumeUser` 增加 `AtomicBoolean resumeFromQuota` 参数，由 defer 内调用点传入（submit 局部变量在 defer 闭包内可见）。消费点统一经本方法，防 `lastUserHasResumeNotice` 分支跳过时的标志滞留。）

- [ ] **Step 4: 实现（AgentTools）**

`L1ReporterBridge` 旁加镜像桥：

```java
    /** L1 限额等待事件的两段式桥（spec §3.6，完整镜像 {@link L1ReporterBridge}：装配期 null 守卫 + bind 后转发）。 */
    static final class L1QuotaBridge implements RetryPolicy.QuotaWaitHook {
        private volatile RetryPolicy.QuotaWaitHook sink;          // 装配期 null；wireL1 后有值
        void bind(RetryPolicy.QuotaWaitHook h) { this.sink = h; }
        @Override public void onQuotaWait(long waitMs, long resetAtEpochMs, String reason) {
            RetryPolicy.QuotaWaitHook s = sink;                   // 快照
            if (s != null) s.onQuotaWait(waitMs, resetAtEpochMs, reason);
        }
    }
```

`AgentRuntime` record 末尾加字段 `L1QuotaBridge quotaBridge`；`build()` 内 `L1ReporterBridge bridge = new L1ReporterBridge();` 旁加 `L1QuotaBridge quotaBridge = new L1QuotaBridge();`；wrap 调用改 `RetryingStreamChatModel.wrap(provider.chatModel(), bridge, quotaBridge)`；`return new AgentRuntime(...)` 参数列表补 `quotaBridge`（全仓 `new AgentRuntime(` 仅 build 一处，编译器把关）。`wireL1` 扩展：

```java
    public static void wireL1(AgentRuntime rt, CodingAgent agent) {
        rt.bridge().bind(agent::onL1Retry);
        rt.quotaBridge().bind(agent::onL1QuotaWait);
    }
```

（`import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;` 按需加。）

- [ ] **Step 5: 跑测试 + 装配守卫回归**

Run: `mvn -pl springai-code-tui -am test "-Dtest=QuotaWiringTest,AuxClientNotRetryWrappedTest,CodingAgentTurnResumeTest,AgentToolsRetryWiringTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（`AgentToolsRetryWiringTest` 是「wrap 进链的 reporter 与 runtime.bridge 同一实例 + 链序」的守卫测试——本次改 wrap 签名与 AgentRuntime 字段的直接受影响面，必须带上回归）。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/QuotaWiringTest.java
git commit -m "feat(code-tui): 限额等待主链桥接（L1 两段式桥 + L2 直连 + 续跑文案二选）"
```

---

### Task 9: 全量回归 + spec 回写

**Files:**
- Modify: `docs/superpowers/specs/2026-09-22-zhipu-quota-wait-retry-design.md`（状态行）

**Interfaces:** 无。

- [ ] **Step 1: 全模块测试**

Run: `mvn -pl springai-code-tui -am test -q`
Expected: 全绿（既有零修改 + 新增全绿；任何既有用例失败 = 违反 Global Constraints 的恒等性约束，回去修实现而不是改测试）。

- [ ] **Step 2: 编译产物冒烟（可选但推荐）**

Run: `mvn -pl springai-code-tui -am package -DskipTests -q`
Expected: BUILD SUCCESS。

- [ ] **Step 3: spec 状态行回写**

状态行改为：`状态：**已按实施计划交付（2026-09-22，docs/superpowers/plans/2026-09-22-zhipu-quota-wait-retry.md）**`（保留 v2 修订记录）。

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-09-22-zhipu-quota-wait-retry-design.md
git commit -m "docs(spec): 限额感知重试设计回写已交付"
```

---

## Self-Review（已执行）

1. **Spec coverage**：spec §3.1→Task 1；§3.2→Task 2；§3.3→Task 3；§3.4→Task 4+7；§3.5→Task 5+6（含 RESUME_NOTICE 二选在 Task 8）；§3.6→Task 8；§4 边界表的「过去时刻下限（T2）/解析失败退化（T1/T2/T4）/1113 排除（T1）/Esc reactive（T3）/Esc 串行 interrupt（T7）/后台 -1（T5/T7）」均有对应测试；§5 测试计划 6 组→T1/T2/T3/T4/T5/T7/T8 全落。无缺口。
2. **Placeholder scan**：Task 5/6/7/8 中「照抄既有桩/实际 API 名」处均已替换为实名（`delegate(n,calls)`、`scripted`、`onTurnStarted(1L)`、OkTest 桩、AgentToolsRetryWiringTest 反射模式），每处给出断言与桩来源；其余步骤均含完整代码。
3. **Type consistency**：`QuotaLimit(String, Instant)`、`QuotaWaitHook.onQuotaWait(long,long,String)`、`onQuotaWaitScheduled(long,long,String)`、`formatQuotaRemaining(long)`、`quotaBackoffText(ConversationState,long)`、`L1QuotaBridge.bind(QuotaWaitHook)` 在 T2/T3/T5/T6/T7/T8 间签名一致；`wrap` 三参（L1）与两参（子 agent）按各自类定义不冲突。

## v2 修订记录（2026-09-22，双 subagent 审核后）

**高危 6 项（全部修订）**：H1 `parseResetAt` 本地分支 `LocalDateTime.parse(日期-only)` 必抛 → 改整段匹配两 formatter；H2 while 化把空流接入 `shouldRetry` 门控破坏既有空流重试语义 → `emptyStream` 标志豁免（保留 attempt 预算与限额判定）；H3 删既有两参构造 → 补回委托重载；H4 全部新测试用 assertj（模块无此依赖，亲验 pom 与全 test 目录）→ 一律改 JUnit5 原生并写入 Global Constraints；H5 `HttpHeaders.EMPTY.patched()` 在 spring-web 7.0.9 不存在（javap 核验）→ `new HttpHeaders()+set` + `create(...,headers,...)`；H6 `resetSeen` 毫秒断言恒红 → 秒截断口径。

**中危 6 项**：M1 `consecutiveQuotaWaitsCapped` 补重订阅计数断言（钉死 MAX_QUOTA_WAITS=5）；M2 `onRetryScheduled` 补清 deadline（spec §3.5 清除纪律的遗漏腿）；M3 串行线程登记挪进 try（本类 increment/try 成文纪律）+ `computeIfPresent` 原子摘除；M4 `lastResumeFromQuota` 实例字段违反 CodingAgent 成文纪律 → submit 局部 `AtomicBoolean` 增参传递；M5 dispose 测试假阳性（Mono.delay 不阻塞主线程恒过）→ 50ms 压缩 + 300ms 后断言无重订阅；M6 回归命令补 `SubagentRunner*`（Task 4，走 call() 路径）与 `AgentToolsRetryWiringTest`（Task 8，wrap 签名/AgentRuntime 字段直接受影响面）。

**低危采纳**：L1 RetryPolicyTest 参照物描述失实修正；L2 桩名全部对齐实名；L3 `scaledDelayMsForTest` 双重换算收敛到生产 sleeper 单处；L4 Task 6 空壳用例落成 `quotaBackoffText` helper 直测；L5 `beginAccept` → `onTurnStarted(1L)`；L6 Task 7/8 骨架落成可运行代码；「Java 21」→ 17；用例数 12→11。

**正面验证（无需改）**：Task 2 四参 backoffRetry 与旧三参的恒等性（quotaWaits=0 时耗尽判定/onRetry/退避三处逐字节一致）；Task 3 mid-stream 链路推演（emitted 计数→filter 拒绝→SII 包装）；Task 5 锁纪律与 onRetryScheduled 逐行同构；Quota429s fixture 的 SDK builder 形态（字节码核验）；任务依赖顺序与前驱覆盖；`new AgentRuntime(` 全仓仅 build 一处。
