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

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RetryingChatModel：call 桥接到流式聚合；只对「2xx+坏 body」瞬态解析失败与空流重试，取消/中断与业务异常直抛。 */
class RetryingChatModelTest {

    /** 模拟 openai-java 的 OpenAIInvalidDataException（按类名后缀匹配，无需真依赖）。 */
    private static final class FakeInvalidDataException extends RuntimeException {
        FakeInvalidDataException() {
            super("Error reading response", new RuntimeException("No content to map due to end-of-input"));
        }
    }

    /** 前 failTimes 次流失败（抛 failure），之后流成功返回 "done"。计数在订阅时增长。 */
    private static ChatModel flaky(int failTimes, RuntimeException failure, AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (calls.incrementAndGet() <= failTimes) return Flux.error(failure);
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
                });
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 前 emptyTimes 次返回「正常完成但零内容」的空流，之后返回 "done"。 */
    private static ChatModel emptyThenOk(int emptyTimes, AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (calls.incrementAndGet() <= emptyTimes) {
                        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
                    }
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
                });
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    @Test
    void retriesTransientParseFailureAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(flaky(2, new FakeInvalidDataException(), calls));
        ChatResponse r = m.call(new Prompt("hi"));
        assertEquals("done", r.getResult().getOutput().getText());
        assertEquals(3, calls.get());   // 2 败 + 1 成
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(flaky(99, new FakeInvalidDataException(), calls));
        assertThrows(FakeInvalidDataException.class, () -> m.call(new Prompt("hi")));
        assertEquals(RetryingChatModel.MAX_ATTEMPTS, calls.get());
    }

    @Test
    void doesNotRetryPlainBusinessException() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(flaky(99, new IllegalStateException("bad api key"), calls));
        assertThrows(IllegalStateException.class, () -> m.call(new Prompt("hi")));
        assertEquals(1, calls.get());   // 不重试
    }

    @Test
    void doesNotRetryCancellation() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(flaky(99, new CancellationException(), calls));
        assertThrows(CancellationException.class, () -> m.call(new Prompt("hi")));
        assertEquals(1, calls.get());
    }

    /** call() 桥接到流式：多个增量 chunk 应聚合为单响应的完整文本。 */
    @Test
    void callBridgesToStreamAndAggregatesChunks() {
        ChatModel chunked = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("Hello, ")))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("world")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ChatResponse r = RetryingChatModel.wrap(chunked).call(new Prompt("hi"));
        assertEquals("Hello, world", r.getResult().getOutput().getText());
    }

    /** 流式增量里的工具调用必须在聚合结果里保留——否则工具循环拿不到 tool_calls、子 agent 变哑巴。 */
    @Test
    void callPreservesToolCallsFromStream() {
        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "Read", "{}")))
                .build();
        ChatModel toolCalling = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(withToolCall))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ChatResponse r = RetryingChatModel.wrap(toolCalling).call(new Prompt("hi"));
        assertTrue(r.getResult().getOutput().hasToolCalls(), "聚合结果应保留工具调用");
        assertEquals("Read", r.getResult().getOutput().getToolCalls().get(0).name());
    }

    /** 空流守卫：正常完成但零内容零工具调用 → 视同瞬态失败重试，重试拿到内容则成功。 */
    @Test
    void retriesEmptyStreamAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(emptyThenOk(1, calls));
        ChatResponse r = m.call(new Prompt("hi"));
        assertEquals("done", r.getResult().getOutput().getText());
        assertEquals(2, calls.get());
    }

    /** 空流重试穷尽后抛出（不把空串静默交回主 agent）。 */
    @Test
    void emptyStreamExhaustsRetriesAndThrows() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel m = RetryingChatModel.wrap(emptyThenOk(99, calls));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> m.call(new Prompt("hi")));
        assertTrue(ex.getMessage().contains("空"), "异常应说明空响应，实际=" + ex.getMessage());
        assertEquals(RetryingChatModel.MAX_ATTEMPTS, calls.get());
    }

    /** 必须转发 getOptions()：漏了会落到接口 default（裸 DefaultChatOptions），ChatClient 取不到家族 options → CCE/丢工具。 */
    @Test
    void forwardsGetOptionsToDelegate() {
        ChatOptions marker = ChatOptions.builder().model("marker-model").build();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getOptions() { return marker; }
        };
        assertEquals("marker-model", RetryingChatModel.wrap(delegate).getOptions().getModel());
    }

    // ---- shouldRetry 纯函数 ----

    @Test
    void shouldRetryMatchesInvalidDataBySuffixAndJacksonMessage() {
        assertTrue(RetryingChatModel.shouldRetry(new FakeInvalidDataException()));
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException("wrapper", new RuntimeException("No content to map due to end-of-input"))));
    }

    @Test
    void shouldRetryRejectsCancellationEvenWhenWrappedWithParseFailure() {
        // 取消优先：中断/取消出现在链上就绝不重试（Esc 要立即退出）
        FakeInvalidDataException parse = new FakeInvalidDataException();
        RuntimeException cancelled = new RuntimeException("outer", new CancellationException());
        cancelled.getCause().initCause(parse);
        assertFalse(RetryingChatModel.shouldRetry(cancelled));
        assertFalse(RetryingChatModel.shouldRetry(
                new RuntimeException("outer", new InterruptedException("sleep interrupted"))));
    }

    // ---- shouldRetry 扩容：日志实测的四类瞬态故障（2026-08-17 / springai-code-tui-1.14.0/logs）----

    /** 模拟 openai-java 的 OpenAIIoException（OkHttp 断连：Request failed / Stream failed）。 */
    private static final class FakeIoException extends RuntimeException {
        FakeIoException(String message) {
            super(message, new java.io.IOException("connection closed"));
        }
    }

    /** java.io.IOException 家族（EOFException/SocketTimeout/Connect 全是子类）→ 瞬态重试。 */
    @Test
    void shouldRetryIoExceptionFamily() {
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException(new EOFException("EOF reached while reading"))));
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException(new SocketTimeoutException("read timed out"))));
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException(new IOException("connection reset"))));
        // 类名后缀匹配（provider 中立，与 InvalidDataException 同法）
        assertTrue(RetryingChatModel.shouldRetry(new FakeIoException("Request failed")));
    }

    /** WebClientResponseException 包着 EOF（200 OK 但 body 中途断）→ 按 message 特征匹配。 */
    @Test
    void shouldRetryEofMidBody() {
        WebClientResponseException wrapped = WebClientResponseException.create(
                200, "OK", null, null, null);
        RuntimeException eofBody = new RuntimeException(
                "200 OK from POST https://api.deepseek.com/chat/completions, "
                        + "but response failed with cause: java.io.EOFException: EOF reached while reading",
                wrapped);
        assertTrue(RetryingChatModel.shouldRetry(eofBody));
    }

    /** 限流（SseException: 200: Upstream rate limit exceeded, please retry later）→ 大小写不敏感。 */
    @Test
    void shouldRetryRateLimit() {
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException("200: Upstream rate limit exceeded, please retry later")));
        assertTrue(RetryingChatModel.shouldRetry(
                new RuntimeException("429 Too Many Requests: RATE LIMIT hit")));
    }

    /** 网关 5xx（502/504 上游坏）→ 瞬态重试。 */
    @Test
    void shouldRetry5xxFromGateway() {
        WebClientResponseException badGateway = WebClientResponseException.create(
                502, "Bad Gateway", null, null, null);
        assertTrue(RetryingChatModel.shouldRetry(new RuntimeException("upstream failed", badGateway)));
    }

    /** 红线：401/403（欠费、密钥错）绝不重试——重试只会更慢更花钱（2026-08-17 生产事故：403 预扣费失败）。 */
    @Test
    void shouldNotRetryUnauthorizedOrForbidden() {
        WebClientResponseException unauthorized = WebClientResponseException.create(
                401, "Unauthorized", null, null, null);
        assertFalse(RetryingChatModel.shouldRetry(new RuntimeException("auth failed", unauthorized)));
        WebClientResponseException forbidden = WebClientResponseException.create(
                403, "Forbidden", null, null, null);
        assertFalse(RetryingChatModel.shouldRetry(new RuntimeException("forbidden", forbidden)));
    }

    // ---- 指数退避 ----

    /** backoffMsAfter 纯函数：500/1000/2000/4000，封顶后不再增长。 */
    @Test
    void backoffSequenceIsExponentialCapped() {
        assertEquals(500, RetryingChatModel.backoffMsAfter(1));
        assertEquals(1000, RetryingChatModel.backoffMsAfter(2));
        assertEquals(2000, RetryingChatModel.backoffMsAfter(3));
        assertEquals(4000, RetryingChatModel.backoffMsAfter(4));
        assertEquals(4000, RetryingChatModel.backoffMsAfter(99), "封顶后不再增长");
    }

    /** 全部失败时按指数序列真实休眠（经注入桩收集），且总尝试次数 = MAX_ATTEMPTS。 */
    @Test
    void exhaustsRetriesWithExponentialBackoff() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> slept = new java.util.ArrayList<>();
        RetryingChatModel m = new RetryingChatModel(
                flaky(99, new FakeIoException("Request failed"), calls), slept::add);
        assertThrows(RuntimeException.class, () -> m.call(new Prompt("hi")));
        assertEquals(RetryingChatModel.MAX_ATTEMPTS, calls.get());
        assertEquals(List.of(500L, 1000L, 2000L, 4000L), slept);
    }

    /** 休眠中被中断：保留中断标志、立即抛出，不再继续重试（Esc 语义）。 */
    @Test
    void interruptionDuringBackoffStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();
        RetryingChatModel m = new RetryingChatModel(
                flaky(99, new FakeIoException("Request failed"), calls), ms -> {
                    // 与生产休眠器同款行为：置中断标志再抛
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(new InterruptedException("sleep interrupted"));
                });
        assertThrows(RuntimeException.class, () -> m.call(new Prompt("hi")));
        assertEquals(1, calls.get(), "休眠即中断：只有首次尝试发生");
        assertTrue(Thread.interrupted(), "中断标志必须保留");
    }
}
