package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetryPolicy（瞬态判据 + 指数退避的唯一真相源）：RetryingChatModel.shouldRetry/backoffMsAfter
 * 必须是它的<b>纯委托</b>。等价性用<b>比对式</b>（两边调用 assertEquals），不抄期望值——
 * 将来有人在 RetryingChatModel 上解开委托、改回本体，这里立即红。
 */
class RetryPolicyTest {

    /** 模拟 openai-java 的 OpenAIInvalidDataException（按类名后缀匹配，无需真依赖）。 */
    private static final class FakeInvalidDataException extends RuntimeException {
        FakeInvalidDataException() {
            super("Error reading response", new RuntimeException("No content to map due to end-of-input"));
        }
    }

    /** 模拟 openai-java 的 OpenAIIoException（OkHttp 断连：Request failed / Stream failed）。 */
    private static final class FakeIoException extends RuntimeException {
        FakeIoException(String message) {
            super(message, new IOException("connection closed"));
        }
    }

    /** 瞬态命中分支：解析失败 / IOException 家族 / EOF 中断 body / 限流 / 5xx。 */
    private static List<Throwable> transientCases() {
        List<Throwable> cases = new ArrayList<>();
        // 「2xx + 坏 body」解析失败：类名后缀 / Jackson message
        cases.add(new FakeInvalidDataException());
        cases.add(new RuntimeException("wrapper", new RuntimeException("No content to map due to end-of-input")));
        // IOException 家族：EOF / SocketTimeout / 裸 IOException / 类名后缀 IoException
        cases.add(new RuntimeException(new EOFException("EOF reached while reading")));
        cases.add(new RuntimeException(new SocketTimeoutException("read timed out")));
        cases.add(new RuntimeException(new IOException("connection reset")));
        cases.add(new FakeIoException("Request failed"));
        // 200 OK 但 body 中途断（WebClientResponseException 摊平 EOF 进顶层 message）
        WebClientResponseException ok = WebClientResponseException.create(200, "OK", null, null, null);
        cases.add(new RuntimeException(
                "200 OK from POST https://api.deepseek.com/chat/completions, "
                        + "but response failed with cause: java.io.EOFException: EOF reached while reading",
                ok));
        // 限流：SseException 文案 / 429
        cases.add(new RuntimeException("200: Upstream rate limit exceeded, please retry later"));
        cases.add(new RuntimeException("429 Too Many Requests: RATE LIMIT hit"));
        // 网关 5xx
        WebClientResponseException badGateway = WebClientResponseException.create(502, "Bad Gateway", null, null, null);
        cases.add(new RuntimeException("upstream failed", badGateway));
        // 流式专属瞬态（Task 1 引入、Task 2 的 L1 依赖）：空闲超时 / 空流——
        // 追加进等价性集合，钉住 RetryingChatModel 委托对新类型也生效（Task 1 评审 Minor ①）
        cases.add(new StreamIdleTimeoutException("等待模型流数据超时"));
        cases.add(new EmptyStreamException("LLM 流式响应为空（无文本、无工具调用）——疑似网关空响应"));
        // 429 限流（Task 2 补）：WCRE 状态 429——「请求没病、服务端在节流」的唯一可重试 4xx
        // （spec §5 L1 行「零下发 429 → 重试成功」点名；文案含 rate limit 的旧口径不变）
        cases.add(WebClientResponseException.create(429, "Too Many Requests", null, null, null));
        // SDK 系 5xx（2026-09-06 生产事故补）：智谱网关 503 抛 InternalServerException——
        // 非 IOException、非 WCRE、message 无关键词，旧判据全落空直接杀回合（实测 60 次）。
        // CompletionException 包装按真实传播形态（CompletableFuture 链异步路径）。
        cases.add(new java.util.concurrent.CompletionException(
                sdkServerException(503)));
        // SDK 系 429：RateLimitException——与 WCRE 429 同语义（状态码类内固定，Builder 无 statusCode）
        cases.add(new java.util.concurrent.CompletionException(
                RateLimitException.builder()
                        .headers(com.openai.core.http.Headers.builder().build()).build()));
        return cases;
    }

    /** 红线否决分支：4xx 确定态 / 取消（嵌套瞬态）/ 中断 / 业务异常。 */
    private static List<Throwable> redLineCases() {
        List<Throwable> cases = new ArrayList<>();
        WebClientResponseException unauthorized = WebClientResponseException.create(401, "Unauthorized", null, null, null);
        cases.add(new RuntimeException("auth failed", unauthorized));
        WebClientResponseException forbidden = WebClientResponseException.create(403, "Forbidden", null, null, null);
        cases.add(new RuntimeException("forbidden", forbidden));
        // 取消优先：链上同时挂着瞬态解析失败也绝不重试（Esc 要立即退出）
        FakeInvalidDataException parse = new FakeInvalidDataException();
        RuntimeException cancelled = new RuntimeException("outer", new CancellationException());
        cancelled.getCause().initCause(parse);
        cases.add(cancelled);
        cases.add(new RuntimeException("outer", new InterruptedException("sleep interrupted")));
        cases.add(new IllegalStateException("bad api key"));
        // StreamInterruptedException 红线（Task 2 补）：L1 的 mid-stream 出口包装类型——
        // 它本身携带「已下发 chunk」语义（重试 = 向下游重放已见内容），且必须原样穿透 L1 的
        // retryWhen 命中 L2 白名单（spec §3.2 类型穿透）；cause 是瞬态也不得因此被重试
        cases.add(new StreamInterruptedException(2,
                new RuntimeException(new java.io.EOFException("EOF reached while reading"))));
        // SDK 系 4xx 红线：与 WCRE 4xx 同口径（请求本身有病/欠费，重试只会更慢更花钱）。
        // CompletionException 包装按真实传播形态；状态码类内固定（401/400），Builder 无 statusCode。
        cases.add(new java.util.concurrent.CompletionException(
                UnauthorizedException.builder()
                        .headers(com.openai.core.http.Headers.builder().build()).build()));
        cases.add(new java.util.concurrent.CompletionException(
                BadRequestException.builder()
                        .headers(com.openai.core.http.Headers.builder().build()).build()));
        // SDK 系非 5xx 非标准 4xx（如网关回 3xx/418）：既非瞬态也非红线确定态 → 否决（同 WCRE 口径）
        cases.add(new java.util.concurrent.CompletionException(
                UnexpectedStatusCodeException.builder().statusCode(418)
                        .headers(com.openai.core.http.Headers.builder().build()).build()));
        return cases;
    }

    /** 构造 SDK 5xx 异常（500..599 全区间映射 InternalServerException，503 为生产实例）。 */
    private static InternalServerException sdkServerException(int statusCode) {
        return InternalServerException.builder()
                .statusCode(statusCode)
                .headers(com.openai.core.http.Headers.builder().build())
                .build();
    }

    /** 覆盖 RetryingChatModel.shouldRetry 的全部判据分支。 */
    private static List<Throwable> representativeThrowables() {
        List<Throwable> all = new ArrayList<>(transientCases());
        all.addAll(redLineCases());
        return all;
    }

    /** 等价性【比对式，非抄写式】：委托不被解开 + 免双份期望值维护。 */
    @Test
    void shouldRetryDelegatesToRetryPolicyForEveryKnownCase() {
        for (Throwable t : representativeThrowables()) {
            assertEquals(RetryingChatModel.shouldRetry(t), RetryPolicy.shouldRetry(t),
                    "RetryingChatModel.shouldRetry 必须与 RetryPolicy.shouldRetry 一致，t=" + t);
        }
    }

    /** 代表性集合的语义锚：瞬态命中（防比对式两边同为 false 的假绿）。 */
    @Test
    void representativeTransientCasesActuallyRetry() {
        for (Throwable t : transientCases()) {
            assertTrue(RetryPolicy.shouldRetry(t), "应为瞬态可重试，t=" + t);
        }
    }

    /** 代表性集合的语义锚：红线否决（4xx/取消/中断/业务异常绝不重试）。 */
    @Test
    void representativeRedLinesNeverRetry() {
        for (Throwable t : redLineCases()) {
            assertFalse(RetryPolicy.shouldRetry(t), "红线不重试，t=" + t);
        }
    }

    // ---- 新增瞬态判据（本任务引入；Task 2 的流式重试依赖）----

    @Test
    void shouldRetryStreamIdleTimeoutException() {
        assertTrue(RetryPolicy.shouldRetry(new StreamIdleTimeoutException("等待模型流数据超时")));
    }

    @Test
    void shouldRetryEmptyStreamException() {
        assertTrue(RetryPolicy.shouldRetry(new EmptyStreamException("空流")));
    }

    /** 包装后仍命中：cause 链遍历内同样生效。 */
    @Test
    void shouldRetryWrappedNewTransientTypes() {
        assertTrue(RetryPolicy.shouldRetry(
                new RuntimeException("stream failed", new StreamIdleTimeoutException("等待模型流数据超时"))));
        assertTrue(RetryPolicy.shouldRetry(
                new RuntimeException("stream failed", new EmptyStreamException("空流"))));
    }

    // ---- SDK 系（OpenAIServiceException）5xx/429 判据（2026-09-06 生产事故补）----

    /** 生产实例精确复现：智谱网关 503 → InternalServerException，CompletionException 包装。 */
    @Test
    void shouldRetrySdk503ProductionShape() {
        assertTrue(RetryPolicy.shouldRetry(new java.util.concurrent.CompletionException(
                sdkServerException(503))), "503 必须瞬态重试（生产事故：60 次直接杀回合）");
    }

    /** 5xx 区间边界：500 与 599 都命中（SDK 把整个 500..599 映射为 InternalServerException）。 */
    @Test
    void shouldRetrySdk5xxRangeBounds() {
        assertTrue(RetryPolicy.shouldRetry(sdkServerException(500)));
        assertTrue(RetryPolicy.shouldRetry(sdkServerException(599)));
    }

    /** 取消红线优先于 SDK 5xx：链上有取消（Esc 回合取消伴生）即使混着 503 也不重试。 */
    @Test
    void cancellationShortCircuitsBeforeSdk5xx() {
        RuntimeException cancelled = new RuntimeException("outer", new CancellationException());
        cancelled.getCause().initCause(sdkServerException(503));
        assertFalse(RetryPolicy.shouldRetry(cancelled), "取消优先短路，绝不重试");
    }

    // ---- 指数退避（attempt 为 1 基尝试序号：第 n 次尝试失败后的退避）----

    @Test
    void backoffSequenceIsExponentialCapped() {
        assertEquals(1000, RetryPolicy.backoffMsAfter(1));
        assertEquals(2000, RetryPolicy.backoffMsAfter(2));
        assertEquals(4000, RetryPolicy.backoffMsAfter(3));
        assertEquals(8000, RetryPolicy.backoffMsAfter(4));
        assertEquals(16000, RetryPolicy.backoffMsAfter(5));
        assertEquals(30000, RetryPolicy.backoffMsAfter(6));
        assertEquals(30000, RetryPolicy.backoffMsAfter(99), "封顶后不再增长");
    }

    /** 退避同样委托：两个入口必须逐点一致。 */
    @Test
    void backoffDelegatesToRetryPolicy() {
        for (int attempt = 1; attempt <= 6; attempt++) {
            assertEquals(RetryingChatModel.backoffMsAfter(attempt), RetryPolicy.backoffMsAfter(attempt),
                    "backoffMsAfter(" + attempt + ") 两入口应一致");
        }
    }

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
}
