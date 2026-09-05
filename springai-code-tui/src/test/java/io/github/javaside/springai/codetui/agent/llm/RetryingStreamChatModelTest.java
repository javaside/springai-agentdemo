package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetryingStreamChatModel（主 agent L1 零下发透明重试）：只对「零下发瞬态失败」重订阅；
 * mid-stream 瞬态失败包装 {@link StreamInterruptedException} 放行给 L2；空流视同零下发失败。
 */
class RetryingStreamChatModelTest {

    private static final Prompt PROMPT = new Prompt("hi");

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** usage-only 收尾 chunk：Generation(AssistantMessage("")) + response metadata 带 usage（无文本无 toolCalls）。 */
    private static ChatResponse usageOnlyChunk() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(10, 5, 15, null, 0L, 0L))
                        .build());
    }

    private static ChatModel delegate(java.util.function.Function<Integer, Flux<ChatResponse>> script,
                                      AtomicInteger calls) {
        return new ChatModel() {
            @Override public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> script.apply(calls.incrementAndGet()));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 记录到的重试事件（attempt, backoffMs, reason）。 */
    private record Report(int attempt, long backoffMs, String reason) {}

    private static final class RecordingReporter implements RetryReporter {
        final List<Report> reports = new CopyOnWriteArrayList<>();
        @Override public void report(int attempt, long backoffMs, String reason) {
            reports.add(new Report(attempt, backoffMs, reason));
        }
    }

    private static WebClientResponseException wcre429(String message) {
        return WebClientResponseException.create(429, message, null, null, null);
    }

    // 1 零下发 429 ×2 后成功 → 3 次调用、结果 "done"
    @Test
    void retriesZeroEmission429AndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> n <= 2
                ? Flux.error(wcre429("Too Many Requests"))
                : Flux.just(chunk("done")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextMatches(r -> "done".equals(r.getResult().getOutput().getText()))
                .verifyComplete();
        assertEquals(3, calls.get());
    }

    // 2 零下发 401 → 1 次调用、异常原样冒泡（4xx 红线）
    @Test
    void doesNotRetryZeroEmission4xx() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                Flux.error(WebClientResponseException.create(401, "Unauthorized", null, null, null)), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectError(WebClientResponseException.class)
                .verify();
        assertEquals(1, calls.get());
    }

    // 3 首字节超时 ×1 后成功 → 重试成功
    @Test
    void retriesStreamIdleTimeoutAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> n == 1
                ? Flux.error(new StreamIdleTimeoutException("等待模型流数据超时"))
                : Flux.just(chunk("done")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextMatches(r -> "done".equals(r.getResult().getOutput().getText()))
                .verifyComplete();
        assertEquals(2, calls.get());
    }

    // 4 mid-stream：发 2 个内容 chunk 后 Flux.error(EOF 包装) → StreamInterruptedException、
    //   emittedChunks()==2、getMessage()==null、调用数==1（不 L1 重试）
    @Test
    void midStreamTransientFailureWrapsStreamInterrupted() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException eof = new RuntimeException(
                "200 OK from POST https://api.deepseek.com/chat/completions, "
                        + "but response failed with cause: java.io.EOFException: EOF reached while reading");
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> Flux.just(chunk("Hel"), chunk("lo"))
                .concatWith(Flux.error(eof)), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextCount(2)
                .expectErrorSatisfies(ex -> {
                    StreamInterruptedException sii = assertInstanceOf(StreamInterruptedException.class, ex);
                    assertEquals(2, sii.emittedChunks());
                    assertNull(sii.getMessage(), "message 必须置空，formatError 才能看到根因文案");
                    assertEquals(eof, sii.getCause(), "cause 应为原始网络异常");
                })
                .verify();
        assertEquals(1, calls.get(), "mid-stream 不 L1 重试");
    }

    // 5 空白-only chunk 后断流 → 仍判 mid-stream（emittedChunks==1，text 非空口径含纯空白）
    @Test
    void blankOnlyChunkStillCountsAsMidStream() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException eof = new RuntimeException(new java.io.EOFException("EOF reached while reading"));
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> Flux.just(chunk("  \n"))
                .concatWith(Flux.error(eof)), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextCount(1)
                .expectErrorSatisfies(ex -> {
                    StreamInterruptedException sii = assertInstanceOf(StreamInterruptedException.class, ex);
                    assertEquals(1, sii.emittedChunks(), "纯空白 chunk 已下发进 UI，必须计数（R6-m1）");
                })
                .verify();
        assertEquals(1, calls.get());
    }

    // 6 usage-only 空收尾后断流 → emitted==0 → L1 重试路径
    @Test
    void usageOnlyTailThenInterruptRetriesAsZeroEmission() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException eof = new RuntimeException(new java.io.EOFException("EOF reached while reading"));
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                n == 1
                        ? Flux.just(usageOnlyChunk()).concatWith(Flux.error(eof))
                        : Flux.just(chunk("done")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                // usage-only chunk 照常透传（守卫只看内容口径、不拦截下发；concatWith 无法撤回已发信号）
                .expectNextCount(1)
                .expectNextMatches(r -> "done".equals(r.getResult().getOutput().getText()))
                .verifyComplete();
        assertEquals(2, calls.get());
    }

    // 7 空流（emptyThenOk）→ EmptyStreamException 转 error → 重试成功（调用数==2）。
    //   注：attempt1 的空 chunk（text=""，非内容口径）会透传给下游——「零下发」指零<b>非空</b> chunk
    //   （spec §3.2 不变式），concatWith 守卫无法撤回已发信号；下游 UI 对空 text chunk 无观测效应。
    @Test
    void retriesEmptyStreamAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> n == 1
                ? Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("")))))
                : Flux.just(chunk("done")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextMatches(r -> r.getResult().getOutput().getText().isEmpty())   // attempt1 空 chunk 透传
                .expectNextMatches(r -> "done".equals(r.getResult().getOutput().getText()))
                .verifyComplete();
        assertEquals(2, calls.get());
    }

    // 8 空流（带 usage chunk）→ 重试成功（usage 记账两笔的断言在 Task 6 用例 16，这里只验 L1 行为）
    @Test
    void retriesEmptyStreamWithUsageChunkAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n -> n == 1
                ? Flux.just(usageOnlyChunk())
                : Flux.just(chunk("done")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextMatches(r -> r.getResult().getOutput().getText().isEmpty())   // usage-only 透传
                .expectNextMatches(r -> "done".equals(r.getResult().getOutput().getText()))
                .verifyComplete();
        assertEquals(2, calls.get());
    }

    // 9 L1 耗尽：scripted 桩按订阅序抛不同实例 WCRE ×5 → onError 收到 WCRE 且 message 含 "#5"，
    //   Exceptions.isRetryExhausted(ex)==false（已解包）
    @Test
    void exhaustionUnwrapsLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                Flux.error(wcre429("Too Many Requests #" + n)), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectErrorSatisfies(ex -> {
                    WebClientResponseException wcre = assertInstanceOf(WebClientResponseException.class, ex);
                    assertTrue(wcre.getMessage().contains("#5"), "应解包放行最后一次失败，实际=" + wcre.getMessage());
                    assertFalse(reactor.core.Exceptions.isRetryExhausted(ex), "已解包，不再是 RetryExhaustedException");
                })
                .verify();
        assertEquals(5, calls.get(), "总尝试 = 1 + L1_RETRIES(4)");
    }

    // 10 emitted 重置（回归钉子）：attempt1 空流、attempt2 发 1 chunk 后断 → emittedChunks()==1
    //    （attempt1 的空 chunk 透传不计入 emitted——内容口径）
    @Test
    void emittedResetsOnResubscription() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException eof = new RuntimeException(new java.io.EOFException("EOF reached while reading"));
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                n == 1
                        ? Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("")))))
                        : Flux.just(chunk("partial")).concatWith(Flux.error(eof)), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectNextMatches(r -> r.getResult().getOutput().getText().isEmpty())   // attempt1 空 chunk 透传
                .expectNextMatches(r -> "partial".equals(r.getResult().getOutput().getText()))
                .expectErrorSatisfies(ex -> {
                    StreamInterruptedException sii = assertInstanceOf(StreamInterruptedException.class, ex);
                    assertEquals(1, sii.emittedChunks(), "attempt2 的计数不得混入 attempt1 的下发");
                })
                .verify();
        assertEquals(2, calls.get());
    }

    // 11 取消：Flux.error(CancellationException) → 1 次调用、原样冒泡
    @Test
    void doesNotRetryCancellation() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                Flux.error(new CancellationException("回合已取消")), calls), null);
        StepVerifier.create(m.stream(PROMPT))
                .expectError(CancellationException.class)
                .verify();
        assertEquals(1, calls.get());
    }

    // 12 RetryReporter + 退避序列：VTS 接管 Retry.backoff 的退避 delay → 完整 4 跳（jitter(0)）
    @Test
    void reportsAttemptBackoffAndReasonWithVirtualTime() {
        AtomicInteger calls = new AtomicInteger();
        RecordingReporter reporter = new RecordingReporter();
        ChatModel m = RetryingStreamChatModel.wrap(delegate(n ->
                n <= 5 ? Flux.error(wcre429("Too Many Requests")) : Flux.just(chunk("done")), calls), reporter);
        // supplier 内部调用 stream——VTS 接管发生在 supplier 求值前
        StepVerifier.withVirtualTime(() -> m.stream(PROMPT))
                .thenAwait(Duration.ofSeconds(25))   // 0.5+1+2+4=7.5s，上界取全局预算值
                .expectError(WebClientResponseException.class)
                .verify();
        assertEquals(5, calls.get());
        // reason 非空且含根因特征（防工人自发明 reason 实现静默流入 UI）；
        // reasonOf 沿 cause 链取首个非空 message——WCRE 的 message 即 "429 Too Many Requests"
        // （statusText 含 reasonPhrase 前缀， WebClientResponseException 构造语义）
        assertEquals(List.of(
                        new Report(2, 500L, "429 Too Many Requests"),
                        new Report(3, 1000L, "429 Too Many Requests"),
                        new Report(4, 2000L, "429 Too Many Requests"),
                        new Report(5, 4000L, "429 Too Many Requests")),
                reporter.reports);
        for (Report r : reporter.reports) {
            assertTrue(r.reason().contains("Too Many"), "reason 应含根因特征，实际=" + r.reason());
        }
    }

    /** 固定抛 StreamInterruptedException 的桩（模拟 L2 上游已包装的终态错误被 L1 再穿一次）。 */
    private static final class StreamInterruptedBridge implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                calls.incrementAndGet();
                return Flux.error(new StreamInterruptedException(3,
                        new RuntimeException(new java.io.EOFException("EOF reached while reading"))));
            });
        }
        @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
    }

    // 13 类型穿透标记用例（最小版）：stream() 抛 StreamInterruptedException 经 retryWhen 不拦截 →
    //    原样到达 subscriber 的 onError（完整版落位 Task 6 用例 3）
    @Test
    void streamInterruptedPassesThroughUntouched() {
        StreamInterruptedBridge upstream = new StreamInterruptedBridge();
        ChatModel m = RetryingStreamChatModel.wrap(upstream, null);
        StepVerifier.create(m.stream(PROMPT))
                .expectErrorSatisfies(ex -> {
                    StreamInterruptedException sii = assertInstanceOf(StreamInterruptedException.class, ex);
                    assertEquals(3, sii.emittedChunks(), "原样穿透：字段不得被改写");
                })
                .verify();
        assertEquals(1, upstream.calls.get());
    }

    // 14 getOptions() 转发（照抄 forwardsGetOptionsToDelegate）
    @Test
    void forwardsGetOptionsToDelegate() {
        ChatOptions marker = ChatOptions.builder().model("marker-model").build();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getOptions() { return marker; }
        };
        assertEquals("marker-model", RetryingStreamChatModel.wrap(delegate, null).getOptions().getModel());
    }

    // ---- reasonOf 规则钉子（reason 直接流入 UI ↻ 行，规则不得自行发明）----

    @Test
    void reasonOfFollowsCauseChainForFirstNonBlankMessage() {
        // 同 formatError 口径：沿 cause 链取首个非空 message
        assertEquals("root cause text", RetryingStreamChatModel.reasonOf(
                new RuntimeException(null, new IllegalStateException("root cause text"))));
        // 包装层 message 为 null/blank 时向下穿透
        assertEquals("inner", RetryingStreamChatModel.reasonOf(
                new RuntimeException("   ", new RuntimeException("inner"))));
    }

    @Test
    void reasonOfFallsBackToSimpleClassName() {
        assertEquals("NullPointerException",
                RetryingStreamChatModel.reasonOf(new NullPointerException()));
        assertEquals("unknown", RetryingStreamChatModel.reasonOf(null));
    }

    @Test
    void reasonOfTruncatesToDisplayWidth60WithEllipsis() {
        // 70 个 ASCII（显示宽 70）→ 截到 59 + "…" = 显示宽 60
        String wide = "x".repeat(70);
        String reason = RetryingStreamChatModel.reasonOf(new RuntimeException(wide));
        assertEquals(60, dev.tamboui.text.CharWidth.of(reason), "截断后显示宽应为 60");
        assertTrue(reason.endsWith("…"), "应尾加省略号，实际=" + reason);
        // 显示宽而非字符数：一个 CJK 字符宽 2，宽字符不可再分——
        // 29 个 CJK（58）+ "…"（1）= 59 ≤ 预算 60（预算是上界，不是必须取满）
        String cjk = "错".repeat(40);   // 显示宽 80
        String cjkReason = RetryingStreamChatModel.reasonOf(new RuntimeException(cjk));
        assertTrue(dev.tamboui.text.CharWidth.of(cjkReason) <= 60,
                "截断后显示宽不得超预算，实际=" + dev.tamboui.text.CharWidth.of(cjkReason));
        assertTrue(cjkReason.endsWith("…"), "应尾加省略号，实际=" + cjkReason);
        // 短 message 原样返回
        assertEquals("short", RetryingStreamChatModel.reasonOf(new RuntimeException("short")));
    }
}
