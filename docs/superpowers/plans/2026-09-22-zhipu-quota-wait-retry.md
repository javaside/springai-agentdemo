# 智谱 Coding Plan 限额感知重试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 识别智谱 Coding Plan 限额错误（429 + 业务码 1308/1310/1316-1321），解析 message 内嵌的重置时刻，回合挂起睡到重置点自动重试；L1/L2/子 agent 三路径全覆盖，UI 倒计时，Esc 可取消。

**Architecture:** 全部收在现有重试真相源 `RetryPolicy`（`agent/llm` 包）：`QuotaLimitDetector` 识别+解析（纯函数），`backoffRetry` 加限额分支（须过层 filter、豁免只针对耗尽判定、普通预算按 `totalRetries − quotaWaits` 扣减）。UI 复用 RETRYING 态动画重绘帧从 deadline 现算倒计时（无 ticker）。串行子 agent 补执行线程登记 + `cancelTurn` interrupt。

**Tech Stack:** Java 21（spring-ai 2.0.1 / openai-java-core 4.49.0 / reactor-core 3.8.7），JUnit 5 + reactor-test（StepVerifier），JLine 内联 TUI。

**Spec:** `docs/superpowers/specs/2026-09-22-zhipu-quota-wait-retry-design.md`（v2 终稿——本计划从该 spec 推导，spec 的 §3 设计分节与本文任务一一对应；执行者须同时读 spec）

## Global Constraints

- 模块：`springai-code-tui`（包根 `io.github.javaside.springai.codetui`）。测试命令一律在仓库根执行：
  `mvn -pl springai-code-tui -am test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false -q`
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaLimitDetectorTest {

    // 1316 文档原文形态（反引号包裹北京时间）
    static final String M1316 = "429: 已达到 5 小时使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-22 15:30:00` 重置。";
    static final String M1317 = "429: 已达到 7 天使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-23 08:00` 重置。";   // 无秒位

    @Test
    void detectQuotaCodeWithResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1316", M1316));
        assertThat(q).isPresent();
        assertThat(q.get().code()).isEqualTo("1316");
        // 北京时间 15:30 == UTC 07:30（解析按 Asia/Shanghai）
        assertThat(q.get().resetAt()).isEqualTo(Instant.parse("2026-09-22T07:30:00Z"));
    }

    @Test
    void detectMinutePrecision() {
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429("1317", M1317)).get().resetAt())
                .isEqualTo(Instant.parse("2026-09-23T00:00:00Z"));
    }

    @Test
    void detectIsoOffset() {
        RateLimitException ex = Quota429s.quota429("1308",
                "429: 已达到 100 USD 5hour 使用上限，将在 2026-09-22T15:30:00+08:00 重置。");
        assertThat(QuotaLimitDetector.detect(ex).get().resetAt())
                .isEqualTo(Instant.parse("2026-09-22T07:30:00Z"));
    }

    @Test
    void quotaCodeWithoutTimeYieldsNullResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1310",
                "429: 已达到每周使用上限。"));
        assertThat(q).isPresent();
        assertThat(q.get().resetAt()).isNull();
    }

    @Test
    void keywordFallbackRequiresBothKeywords() {
        // code() 缺失（ErrorObject 不设 code）+ 双关键词 → 命中
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429(null, M1316))).isPresent();
        // 单关键词「使用上限」无「重置」→ 不命中
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429(null,
                "429: 已达到使用上限"))).isEmpty();
    }

    @Test
    void plainRateLimit429NotDetected() {
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429("1302",
                "429: 您的访问频率过高"))).isEmpty();
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429("1113",
                "429: 账户欠费"))).isEmpty();
        assertThat(QuotaLimitDetector.detect(Quota429s.quota429("1309",
                "429: 套餐已到期"))).isEmpty();
    }

    @Test
    void webClient429NotDetected() {
        WebClientResponseException wcre =
                WebClientResponseException.create(429, "Too Many Requests", null, null, null);
        assertThat(QuotaLimitDetector.detect(wcre)).isEmpty();
    }

    @Test
    void traversesCauseChainAndSiiWrapper() {
        // Spring AI 包一层 RuntimeException 的真实传播形态
        assertThat(QuotaLimitDetector.detect(new RuntimeException("wrap",
                Quota429s.quota429("1316", M1316)))).isPresent();
        // SII 包装穿透：message 置空、cause 保留（L2 路径）
        assertThat(QuotaLimitDetector.detect(new StreamInterruptedException(3,
                Quota429s.quota429("1316", M1316)))).isPresent();
    }

    @Test
    void nonSdkThrowableNotDetected() {
        assertThat(QuotaLimitDetector.detect(new RuntimeException("boom"))).isEmpty();
    }

    @Test
    void parseResetAtNullSafe() {
        assertThat(QuotaLimitDetector.parseResetAt(null)).isNull();
        assertThat(QuotaLimitDetector.parseResetAt("没有任何时间")).isNull();
    }

    @Test
    void pastTimestampStillParsed_fieldIsKeptDescriptive() {
        // detect 是纯描述：过去时刻不过滤（quotaWaitMs 兜底，spec §3.1）
        RateLimitException ex = Quota429s.quota429("1316", "429: 上限，将于 `2020-01-01 00:00:00` 重置。");
        assertThat(QuotaLimitDetector.detect(ex).get().resetAt())
                .isEqualTo(Instant.parse("2019-12-31T16:00:00Z"));
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

    private static final DateTimeFormatter LOCAL_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("HH:mm");

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
                LocalDateTime ldt = LocalDateTime.parse(m.group(1), LOCAL_DATE)
                        .plusNanos(LocalTime.parse(m.group(2) + (m.group(3) == null ? "" : ":" + m.group(3)),
                                m.group(3) == null
                                        ? DateTimeFormatter.ofPattern("HH:mm")
                                        : DateTimeFormatter.ofPattern("HH:mm:ss"))
                                .toSecondOfDay() * 1_000_000_000L);
                return ldt.atZone(ZHIPU_ZONE).toInstant();
            } catch (Exception ignore) {
                // fail-open：返回 null，调用方退化
            }
        }
        return null;
    }
}
```

（注：`LocalTime` 需 `import java.time.LocalTime;`。上面 `parseResetAt` 的日期+时间拼装如嫌绕，可等价简化为两个 `DateTimeFormatter`——`yyyy-MM-dd HH:mm:ss` 与 `yyyy-MM-dd HH:mm` 各试一次 `LocalDateTime.parse` 后 `atZone(ZHIPU_ZONE)`；以测试矩阵全绿为准。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test -Dtest=QuotaLimitDetectorTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（12 个用例）。

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

- [ ] **Step 1: 写失败测试（追加到 RetryPolicyTest，沿用该文件既有的 StepVerifier/reactor 测试写法与 `setDelayScaleForTest` 纪律）**

```java
    // ---- 限额分支（spec §3.2）：filter 门控 / 预算扣减 / 独立上限 / 订阅级计数 ----

    @Test
    void quotaWaitMsFuturePastAndNull() {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");
        assertThat(RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.plusSeconds(90)), now)).isEqualTo(90_000);
        // 过去/临近：MIN 下限兜底，杜绝 0ms 轰炸
        assertThat(RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.minusSeconds(60)), now)).isEqualTo(RetryPolicy.MIN_QUOTA_WAIT_MS);
        assertThat(RetryPolicy.quotaWaitMs(new QuotaLimit("1316",
                now.plusSeconds(5)), now)).isEqualTo(RetryPolicy.MIN_QUOTA_WAIT_MS);
        assertThat(RetryPolicy.quotaWaitMs(QuotaLimit.withoutResetAt("1316"), now)).isEqualTo(-1);
    }

    @Test
    void quotaWaitRetriesWithoutConsumingBudget() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            Instant reset = Instant.now().plusSeconds(90);
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
            assertThat(normalRetries.get()).isEqualTo(2);
            assertThat(waitSeen.get()).isBetween(89_000L, 91_000L);
            assertThat(resetSeen.get()).isEqualTo(reset.toEpochMilli());
            assertThat(emissions.get()).isEqualTo(5);   // 2 限额 + 2 普通重试 + 1 成功
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
                    .verifyErrorSatisfies(e -> assertThat(e).isInstanceOf(com.openai.errors.RateLimitException.class));
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void consecutiveQuotaWaitsCapped() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger n = new AtomicInteger();
            Flux<Object> src = Flux.defer(() -> Flux.error(Quota429s.quota429("1316",
                    "429: 上限，将在 `2099-01-01 00:00:00` 重置。")));
            StepVerifier.create(src.retryWhen(RetryPolicy.backoffRetry(9, e -> true, null, null)))
                    .verifyErrorSatisfies(e -> assertThat(e).isInstanceOf(com.openai.errors.RateLimitException.class));
            // 5 次限额等待（重订阅）后终态：MAX_QUOTA_WAITS=5
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
            assertThat(n.get()).isEqualTo(2);   // 首次 + 1 次普通重试
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

（import 按需补：`QuotaLimit`、`Quota429s`、`java.time.Instant`、`java.time.ZoneId`、`java.time.format.DateTimeFormatter`、`java.util.concurrent.atomic.*`、`reactor.core.publisher.Flux`、`reactor.test.StepVerifier`、`assertj`。若 RetryPolicyTest 已有部分 import 则不重复。）

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

- [ ] **Step 1: 写失败测试（追加；沿用该文件既有桩 ChatModel 写法与 `setDelayScaleForTest` 纪律）**

三个用例（spec §5-3）：

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
            ChatModel delegate = stubChatModel(p -> {
                if (subs.incrementAndGet() == 1) {
                    throw Quota429s.quota429("1316", quotaMessage(90));
                }
                return Flux.just(chunk("hi"));
            });
            List<String> quotaReasons = new java.util.ArrayList<>();
            List<Integer> retryReports = new java.util.ArrayList<>();
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate,
                    (attempt, backoffMs, reason) -> retryReports.add(attempt),
                    (waitMs, resetAt, reason) -> quotaReasons.add(reason));
            StepVerifier.create(wrapped.stream(new Prompt("x")))
                    .expectNextCount(1)
                    .verifyComplete();
            assertThat(quotaReasons).hasSize(1);
            assertThat(retryReports).isEmpty();      // ↻ 行与 ⏳ 行互斥
            assertThat(subs.get()).isEqualTo(2);
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void midStreamQuotaNotReplayed_wrapsAsSii() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));
        try {
            AtomicInteger subs = new AtomicInteger();
            ChatModel delegate = stubChatModel(p -> {
                subs.incrementAndGet();
                return Flux.concat(Flux.just(chunk("seen")),   // 已下发 → emitted>0
                        Flux.error(Quota429s.quota429("1316", quotaMessage(90))));
            });
            List<Long> quotaFired = new java.util.ArrayList<>();
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate, null,
                    (waitMs, resetAt, reason) -> quotaFired.add(waitMs));
            StepVerifier.create(wrapped.stream(new Prompt("x")))
                    .expectNextCount(1)                        // chunk 原样下发一次
                    .verifyErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(StreamInterruptedException.class);  // 交 L2，不在 L1 重放
                    });
            assertThat(subs.get()).isEqualTo(1);               // 无重订阅
            assertThat(quotaFired).isEmpty();                  // L1 不等待（filter 门控）
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }

    @Test
    void disposeDuringQuotaWaitCancels() {
        RetryPolicy.setDelayScaleForTest(ms -> ms);   // 真实睡眠 90s 量级，dispose 立即取消
        try {
            ChatModel delegate = stubChatModel(p -> {
                throw Quota429s.quota429("1316", quotaMessage(90));
            });
            ChatModel wrapped = RetryingStreamChatModel.wrap(delegate, null, null);
            reactor.core.Disposable d = wrapped.stream(new Prompt("x")).subscribe();
            d.dispose();   // Mono.delay 定时器随订阅取消，测试不挂死即证明取消生效
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }
```

（`stubChatModel`/`chunk` 为该测试文件既有或新增的桩 helper：`stubChatModel(java.util.function.Function<Prompt, Flux<ChatResponse>>)` 返回抛异常/发流的 ChatModel 桩——若文件里已有等价桩（如 delegating ChatModel 匿名类）直接复用其命名，保持一致。）

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

- [ ] **Step 1: 写失败测试（追加；沿用既有 FakeChatModel/sleeper 收集桩写法）**

```java
    // ---- 限额等待（spec §3.4）：等待不占预算 / 5 次上限 / Retry-After 对齐 ----

    private static String quotaMessage(long deltaSeconds) {
        String at = java.time.Instant.now().plusSeconds(deltaSeconds)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "429: 已达到 5 小时使用上限。您的限额将在 `" + at + "` 重置。";
    }

    @Test
    void quotaWaitSleepsUntilResetWithoutConsumingAttempts() {
        List<Long> sleeps = new java.util.ArrayList<>();
        List<String> quotaReasons = new java.util.ArrayList<>();
        // 序列：1316 ×2 → IOException ×1 → 成功
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = fakeChatModel(p -> {
            int n = calls.incrementAndGet();
            if (n <= 2) throw new java.util.concurrent.CompletionException(Quota429s.quota429("1316", quotaMessage(120)));
            if (n == 3) throw new java.io.IOException("eof");
            return java.util.Optional.of(chatResponseWithText("ok"));
        });
        RetryingChatModel model = new RetryingChatModel(delegate,
                ms -> sleeps.add(ms),
                (waitMs, resetAt, reason) -> quotaReasons.add(reason));
        org.assertj.core.api.Assertions.assertThat(model.call(new Prompt("x")).getResult().getOutput().getText())
                .isEqualTo("ok");
        // 2 次限额等待（≈120s，±5s 容差）+ 1 次普通退避（1s），无双睡
        org.assertj.core.api.Assertions.assertThat(sleeps).hasSize(3);
        org.assertj.core.api.Assertions.assertThat(sleeps.get(0)).isBetween(115_000L, 125_000L);
        org.assertj.core.api.Assertions.assertThat(sleeps.get(1)).isBetween(115_000L, 125_000L);
        org.assertj.core.api.Assertions.assertThat(sleeps.get(2)).isEqualTo(1000L);
        org.assertj.core.api.Assertions.assertThat(quotaReasons).hasSize(2);
    }

    @Test
    void quotaWaitsCappedAtFive() {
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = fakeChatModel(p -> {
            calls.incrementAndGet();
            throw new java.util.concurrent.CompletionException(Quota429s.quota429("1316", quotaMessage(3600)));
        });
        RetryingChatModel model = new RetryingChatModel(delegate, ms -> sleeps.add(ms), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(new Prompt("x")))
                .hasRootCauseInstanceOf(com.openai.errors.RateLimitException.class);
        org.assertj.core.api.Assertions.assertThat(calls.get()).isEqualTo(6);   // 5 次等待 = 6 次调用
        org.assertj.core.api.Assertions.assertThat(sleeps).hasSize(5);
    }

    @Test
    void quotaWithoutResetAtFallsBackToNormalBudget() {
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = fakeChatModel(p -> {
            calls.incrementAndGet();
            throw new java.util.concurrent.CompletionException(
                    Quota429s.quota429("1310", "429: 已达到每周使用上限。"));   // 无时间 → 普通分支
        });
        RetryingChatModel model = new RetryingChatModel(delegate, ms -> sleeps.add(ms), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(new Prompt("x")))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThat(calls.get()).isEqualTo(7);   // 全预算 MAX_ATTEMPTS=7
        org.assertj.core.api.Assertions.assertThat(sleeps).hasSize(6);
    }

    @Test
    void backoffNowHonorsRetryAfterHeader() {
        // 既有偏差修复（spec §3.4.1）：429 WCRE 带 Retry-After: 3 → 退避 3s（旧实现恒 1s）
        List<Long> sleeps = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = fakeChatModel(p -> {
            calls.incrementAndGet();
            throw new org.springframework.web.reactive.function.client.WebClientResponseException(
                    429, "Too Many Requests", null, null, null) {
                @Override public org.springframework.http.HttpHeaders getHeaders() {
                    return org.springframework.http.HttpHeaders.EMPTY.patched() {{
                        set("Retry-After", "3");
                    }};
                }
            };
        });
        RetryingChatModel model = new RetryingChatModel(delegate, ms -> sleeps.add(ms), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.call(new Prompt("x")))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThat(sleeps.get(0)).isEqualTo(3000L);
    }
```

（`fakeChatModel`/`chatResponseWithText` 用该文件既有桩命名；若既有桩签名不同——如返回 ChatResponse 而非 Optional——按既有桩微调用例，断言不变。WCRE 匿名子类若既有测试已有 `WebClientResponseException.create(429,...)` 加 header 的 helper，用 helper。）

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
        this(delegate, ms -> { /* 原 sleeper 体不变 */ }, quotaHook);
    }

    /** 测试可见：注入休眠器与限额钩子。 */
    RetryingChatModel(ChatModel delegate, LongConsumer sleeper, RetryPolicy.QuotaWaitHook quotaHook) {
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.quotaHook = quotaHook;
    }

    /**
     * 阻塞调用 + 瞬态重试（while 形态，spec §3.4 实现红线：单次迭代恰一次睡眠；
     * 限额判定先于 attempt 预算 bail——否则第 7 次尝试上的限额直接抛）。
     * 限额等待不递增 attempt（预算豁免），由 quotaWaits ≤ {@link RetryPolicy#MAX_QUOTA_WAITS} 兜底。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        int attempt = 1;                 // 即将进行的尝试（1 基）
        int quotaWaits = 0;              // 本 call 内的连续限额等待（每次调用重建，天然回合级）
        while (true) {
            RuntimeException failure;
            try {
                ChatResponse aggregated = streamAndAggregate(prompt);
                if (!isEffectivelyEmpty(aggregated)) {
                    return aggregated;
                }
                failure = new RuntimeException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应，已尝试 "
                        + attempt + "/" + MAX_ATTEMPTS + " 次");
                log.warn("LLM 返回空流（疑似网关坏响应），第 {}/{} 次尝试{}", attempt, MAX_ATTEMPTS,
                        attempt < MAX_ATTEMPTS ? "，将重试" : "，放弃");
            } catch (RuntimeException ex) {
                failure = ex;
            }
            // 限额分支：先于普通预算 bail；取消/中断类失败（shouldRetry 否决）绝不等待
            java.util.Optional<QuotaLimit> quota = RetryPolicy.shouldRetry(failure)
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
                sleeper.accept(RetryPolicy.scaledDelayMsForTest(waitMs));
                continue;                                          // attempt 不递增：不占普通预算
            }
            if (!shouldRetry(failure) || attempt >= MAX_ATTEMPTS) {
                throw failure;
            }
            log.warn("LLM 流式请求失败（疑似网关坏响应），第 {}/{} 次尝试后重试：{}",
                    attempt, MAX_ATTEMPTS, failure.getMessage());
            sleeper.accept(RetryPolicy.scaledDelayMsForTest(RetryPolicy.nextDelayMs(attempt, failure)));
            attempt++;
        }
    }
```

（原 `for` 版 `call` 与 `last` 变量删除——while 版语义覆盖；`import java.util.Optional;` 按需加。类 javadoc 退避段补限额说明，同 Task 3 风格引用 spec。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui -am test -Dtest=RetryingChatModelTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（既有用例零修改全绿 + 新增 4 例；既有 sleeper 断言 `[1000..30000]` 序列的用例不受影响——无 Retry-After 时 `nextDelayMs` 恒等 `backoffMsAfter`）。

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

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 限额等待态（spec §3.5）：进入/清除纪律 + -1 丢弃 + 剩余时间格式化。 */
class ConversationStateQuotaWaitTest {

    @Test
    void quotaWaitEntersRetryingWithDeadline() {
        ConversationState s = new ConversationState();
        long turn = s.beginAccept(Instant.now());   // 既有 API：进入接受态拿 turnId（名字以实际为准）
        long reset = System.currentTimeMillis() + 90_000;
        s.onQuotaWaitScheduled(turn, reset, "429: 已达到 5 小时使用上限");
        assertThat(s.status()).isEqualTo(ConversationState.Status.RETRYING);
        assertThat(s.retryLabel()).isEqualTo("⏳ 限额等待");
        assertThat(s.quotaWaitDeadline()).isEqualTo(reset);
        assertThat(s.quotaWaitReason()).contains("使用上限");
    }

    @Test
    void staleTurnIdDropped() {
        ConversationState s = new ConversationState();
        s.onQuotaWaitScheduled(999, System.currentTimeMillis() + 90_000, "r");
        assertThat(s.quotaWaitDeadline()).isNull();
    }

    @Test
    void backgroundMinusOneDropped() {
        // 后台子 agent turnId=-1：空闲态 acceptingTurnId==-1 会穿透过滤（spec §3.4.4）——必须丢弃
        ConversationState s = new ConversationState();
        s.onQuotaWaitScheduled(-1, System.currentTimeMillis() + 90_000, "r");
        assertThat(s.quotaWaitDeadline()).isNull();
    }

    @Test
    void leavingEventsClearDeadline() {
        ConversationState s = new ConversationState();
        long turn = s.beginAccept(Instant.now());
        s.onQuotaWaitScheduled(turn, System.currentTimeMillis() + 90_000, "r");
        assertThat(s.quotaWaitDeadline()).isNotNull();
        s.onTurnComplete(turn);                       // 任一离开事件
        assertThat(s.quotaWaitDeadline()).isNull();   // clearRetryState 并入清除
        assertThat(s.retryLabel()).isNull();
    }

    @Test
    void formatQuotaRemainingMatrix() {
        assertThat(ConversationState.formatQuotaRemaining(-1)).isEqualTo("即将重试");
        assertThat(ConversationState.formatQuotaRemaining(45_000)).isEqualTo("45s");
        assertThat(ConversationState.formatQuotaRemaining(150_000)).isEqualTo("2m30s");
        assertThat(ConversationState.formatQuotaRemaining(3 * 3600_000L + 5 * 60_000L)).isEqualTo("3h5m");
        assertThat(ConversationState.formatQuotaRemaining(6L * 24 * 3600_000 + 23 * 3600_000L)).isEqualTo("6d23h");
    }
}
```

（`beginAccept` 为示意——先看 `ConversationState` 现有测试（如 `CodeTuiViewInterjectionStatusTest`/`ConversationStatePlanTest`）怎么进入接受态/拿 turnId，用**实际 API 名**替换；核心断言不变。）

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

- [ ] **Step 1: 写失败测试（参考同包 CodeTuiView 状态渲染测试的 ViewScreen/桩渲染写法，断言 RETRYING + deadline 时状态栏含「⏳ 限额等待」与「h…m」形态剩余）**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 限额倒计时渲染（spec §3.5）：RETRYING 态每帧从 deadline 现算剩余时间（无 ticker）。 */
class CodeTuiViewQuotaWaitRenderTest {

    @Test
    void formatQuotaRemainingUsedForDeadlineBackoff() {
        // 纯函数层（渲染分支直接委托 formatQuotaRemaining，格式断言在 ConversationState 侧已覆盖）
        assertThat(ConversationState.formatQuotaRemaining(2 * 3600_000L + 13 * 60_000L)).isEqualTo("2h13m");
    }

    @Test
    void quotaDeadlineRendersCountdownInsteadOfStaticBackoff() {
        // 集成：构造 ConversationState 进入限额等待，渲染状态栏（ViewScreen 桩），断言含 ⏳ 与倒计时
        // 写法参考 CodeTuiViewInterjectionStatusTest 的渲染断言模式（ViewScreen/render 桩）。
        // 步骤：state.onQuotaWaitScheduled(turn, now+2h13m, "429: 上限")；view 渲染；断言输出含 "⏳ 限额等待" 且含 "2h13m"。
        // （具体 ViewScreen 桩命名以同包既有测试为准——本用例是防「deadline 存在却渲染静态 30.0s」的回归钉。）
    }
}
```

（第二个用例落位时按同包既有 ViewScreen 桩补全具体断言代码——若该文件既有渲染测试模式过重，可退化为对渲染分支抽出的包私有 helper `quotaBackoffText(state, now)` 的直测：`assertThat(CodeTuiView.quotaBackoffText(state, now)).isEqualTo("2h13m")`。两条路线择一，不许留空用例。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewQuotaWaitRenderTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（渲染仍用静态 retryBackoffText）。

- [ ] **Step 3: 实现（RETRYING 分支改造）**

```java
            case RETRYING -> {
                // 限额等待：每帧从 deadline 现算剩余（RETRYING 态动画协调器持续重绘 ~66ms，
                // 与 compactElapsedNanos 同款现算模式——不新增 ticker，spec §3.5）
                Long quotaDeadline = state.quotaWaitDeadline();
                String label = state.retryLabel() == null ? "↻ 重试中" : state.retryLabel();
                String backoff = quotaDeadline != null
                        ? ConversationState.formatQuotaRemaining(quotaDeadline - System.currentTimeMillis())
                        : state.retryBackoffText();
                String backoffTail = terminalWidth() >= 100 && backoff != null ? " · 退避 " + backoff : "";
                String suffix = qs + ijs + ns + backoffTail + " · Esc 取消" + projectSuffix;
                yield richText(statusBar.shimmer(label, suffix, THINK, animTick, mode));
            }
```

（改动即 backoff 一行的三目；deadline 过期时 formatQuotaRemaining 返回「即将重试」——自愈显示，下一事件必到并清除。）

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

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.subagent;

// 桩构造参考 SubagentRunnerTest 既有写法（ProviderRegistry/listener 桩），此处列骨架与核心断言：
//
// 1) serialQuotaWaitInterruptedByCancelTurn：
//    - 桩 provider.chatModel() 的 stream() 首订阅抛 1316（now+3600s 重置）
//    - 测试线程 A 调 runner.run(spec, "p", "d", 77L)；run 会进入真实 Thread.sleep(≈1h)（RetryPolicy
//      恢复恒等 delay——不 setDelayScaleForTest）
//    - 主线程 await runStarted latch 后调 runner.cancelTurn(77L)
//    - 断言：A 在 5s 内抛出 RuntimeException（cause 为 InterruptedException），inFlight 归零
//
// 2) serialThreadRegistryCleanedUp：run 正常结束后 serialThreadsByTurn 无残留
//    （通过反射读私有 map 断言 empty，或以第二次 cancelTurn 无副作用佐证）
//
// 3) quotaHookBridgesToListenerForForegroundAndDropsBackground：
//    - execute 路径（经 run 或直接反射）注入 1316 失败一次后成功（压缩 delay）
//    - 前台（parentTurnId=77）：listener.onQuotaWaitScheduled(77, resetAt, "子任务 …") 恰一次
//    - 后台路径（runInBackground 或直接构造 toolContext TURN_ID_KEY=-1）：listener 不收到
class SubagentRunnerSerialInterruptTest { /* 用例按上述骨架落地 */ }
```

（测试骨架必须落成可运行代码：latch 用 `CountDownLatch`，桩 listener 用 `AtomicReference` 收事件；`SubagentRunner` 构造参数照既有测试复制。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=SubagentRunnerSerialInterruptTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL（用例 1 挂起至 5s 超时——cancelTurn 不 interrupt 串行线程）。

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

`run()` 改造（登记/清理，其余原样）：

```java
        inFlight.incrementAndGet();
        java.util.Set<Thread> threads = serialThreadsByTurn
                .computeIfAbsent(parentTurnId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        threads.add(Thread.currentThread());
        try {
            publish(changed());
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                           ToolEventCallback.TASK_ID_KEY, taskId));
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
        } catch (RuntimeException ex) {
            /* 原 catch 体原样 */
        } finally {
            threads.remove(Thread.currentThread());
            if (threads.isEmpty()) {
                serialThreadsByTurn.remove(parentTurnId);
            }
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

- [ ] **Step 1: 写失败测试（QuotaWiringTest：桥 bind 前后行为 + 装配后链路通）**

```java
package io.github.javaside.springai.codetui.agent;

// 三个用例（桥测试不需要完整 build，直测桥 + CodingAgent 桩路径）：
//
// 1) l1QuotaBridgeNoOpBeforeBind：new L1QuotaBridge() 未 bind 时 onQuotaWait(...) 不抛（null 守卫）
// 2) l1QuotaBridgeForwardsAfterBind：bind 记录器 → onQuotaWait(90_000, t, "r") → 记录器收到同参
// 3) codingAgentL1QuotaSinkFiltersStaleTurn：
//    - 构造 CodingAgent（参考既有 CodingAgent 测试的最小构造）+ 录制 listener 桩
//    - submit 一个回合（桩 ChatClient 立即成功），回合结束后调 agent.onL1QuotaWait(...)（模拟迟到回调）
//      → listener.onQuotaWaitScheduled 不被调用（activeTurnQuotaSink 已被新回合闭包整体替换，旧值失配丢弃）
//      —— 若构造完整 submit 过重，可反射置 activeTurnQuotaSink 后直测 onL1QuotaWait 转发 + turnId 失配路径
class QuotaWiringTest { /* 落地 */ }
```

（骨架必须落成可运行代码；桥用例 1/2 是纯单元测试可直接写死，用例 3 参考同包既有 CodingAgent 测试的最小构造。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl springai-code-tui -am test -Dtest=QuotaWiringTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: COMPILATION ERROR（L1QuotaBridge/onL1QuotaWait 不存在）。

- [ ] **Step 3: 实现（CodingAgent）**

字段（`activeTurnL1Sink` 旁）：

```java
    /** L1 限额等待事件的本回合 sink（spec §3.6，镜像 {@link #activeTurnL1Sink}）。 */
    private volatile RetryPolicy.QuotaWaitHook activeTurnQuotaSink;

    /** L2 限额等待后的续跑文案标记：quota 回调置位（等待前）、prepareResume 消费复位（重订阅后）——回合串行下无交错。 */
    private volatile boolean lastResumeFromQuota;
```

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

`submit` 内（`activeTurnL1Sink = ...` 赋值块**后面**紧邻加，`lastResumeFromQuota = false` 放 submit 早期与 l2Enabled 同段）：

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
                            lastResumeFromQuota = true;             // prepareResume 消费（spec §3.5 文案二选）
                            listener.onQuotaWaitScheduled(turnId, resetAtEpochMs, reason);
                        }))
```

续跑文案二选：`RESUME_NOTICE` 旁加常量，`prepareResume` 与 `composeResumeUser` 使用点改为按标记选值：

```java
    /** 限额等待后的续跑通知（spec §3.5：按失败类型二选，防「网络中断」文案失真）。 */
    private static final String RESUME_NOTICE_QUOTA = "<system-notice>\n"
            + "额度限额等待结束，你的上一段输出未被保留。请从中断处继续完成回答，不要重复已输出的内容。\n"
            + "</system-notice>";

    /** 本次续跑的通知文本（消费 lastResumeFromQuota 并复位；仅 resub>1 路径调用）。 */
    private String resumeNotice() {
        String notice = lastResumeFromQuota ? RESUME_NOTICE_QUOTA : RESUME_NOTICE;
        lastResumeFromQuota = false;
        return notice;
    }
```

（`prepareResume` 的 `interjections.setResumeNotice(RESUME_NOTICE)` 与 `composeResumeUser` 的两处 `RESUME_NOTICE` 拼接全部改调 `resumeNotice()`。）

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

Run: `mvn -pl springai-code-tui -am test "-Dtest=QuotaWiringTest,AuxClientNotRetryWrappedTest,CodingAgentTurnResumeTest" -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（若 `AuxClientNotRetryWrappedTest`/`CodingAgentTurnResumeTest` 实名不同，跑 `mvn -pl springai-code-tui -am test -Dtest="*Wiring*,*Resume*,*Aux*" ...` 等价覆盖）。

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
2. **Placeholder scan**：Task 5/6/7/8 中四处「以既有桩/实际 API 名为准」的提示是**落地指引**而非省略——每处均给了断言与桩来源；执行者落地时替换实名。其余步骤均含完整代码。
3. **Type consistency**：`QuotaLimit(String, Instant)`、`QuotaWaitHook.onQuotaWait(long,long,String)`、`onQuotaWaitScheduled(long,long,String)`、`formatQuotaRemaining(long)`、`L1QuotaBridge.bind(QuotaWaitHook)` 在 T2/T3/T5/T7/T8 间签名一致；`wrap` 三参（L1）与两参（子 agent）按各自类定义不冲突。
