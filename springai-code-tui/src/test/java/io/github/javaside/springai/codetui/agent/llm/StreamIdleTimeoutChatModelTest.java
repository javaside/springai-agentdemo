package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamIdleTimeoutChatModelTest {

    private static final Prompt PROMPT = new Prompt("hi");
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatModel model(Flux<ChatResponse> stream) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return response("call"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return stream; }
        };
    }

    @Test
    void noFirstChunk_timesOutWithReadableMessage() {
        var wrapped = new StreamIdleTimeoutChatModel(model(Flux.never()), TIMEOUT);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertTrue(error.getMessage().contains("等待模型流数据超时"));
        assertTrue(error.getMessage().contains("0.1 秒"));
        assertTrue(error.getMessage().contains(LlmTimeouts.READ_TIMEOUT_ENV));
    }

    @Test
    void stallsAfterFirstChunk_timesOut() {
        var wrapped = new StreamIdleTimeoutChatModel(
                model(Flux.concat(Flux.just(response("first")), Flux.never())), TIMEOUT);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertTrue(error.getMessage().contains("等待模型流数据超时"));
    }

    @Test
    void regularChunksCanRunLongerThanOneTimeoutWindow() {
        Flux<ChatResponse> source = Flux.interval(Duration.ZERO, Duration.ofMillis(60))
                .take(4)
                .map(i -> response("chunk-" + i));
        var wrapped = new StreamIdleTimeoutChatModel(model(source), TIMEOUT);

        List<ChatResponse> values = wrapped.stream(PROMPT).collectList().block();

        assertNotNull(values);
        assertEquals(4, values.size());
    }

    @Test
    void normalCompletionIsPreserved() {
        ChatResponse expected = response("ok");
        var wrapped = new StreamIdleTimeoutChatModel(model(Flux.just(expected)), TIMEOUT);

        assertSame(expected, wrapped.stream(PROMPT).blockLast());
    }

    @Test
    void originalErrorIsPreserved() {
        IllegalStateException boom = new IllegalStateException("boom");
        var wrapped = new StreamIdleTimeoutChatModel(model(Flux.error(boom)), TIMEOUT);

        RuntimeException seen = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertSame(boom, seen);
    }

    @Test
    void upstreamTimeoutExceptionIsNotRelabeledAsStreamIdleTimeout() {
        TimeoutException upstream = new TimeoutException("provider-timeout");
        var wrapped = new StreamIdleTimeoutChatModel(model(Flux.error(upstream)), TIMEOUT);

        RuntimeException seen = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertSame(upstream, seen.getCause());
        assertFalse(seen.getMessage().contains("等待模型流数据超时"));
    }

    @Test
    void downstreamCancellationPropagatesUpstreamWithoutTimeoutError() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean errored = new AtomicBoolean();
        Flux<ChatResponse> source = Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
        var disposable = new StreamIdleTimeoutChatModel(model(source), Duration.ofSeconds(5))
                .stream(PROMPT)
                .doOnError(ignored -> errored.set(true))
                .subscribe();

        disposable.dispose();

        assertTrue(cancelled.get());
        assertFalse(errored.get());
    }

    @SuppressWarnings("removal")
    @Test
    void callAndOptionsAreForwardedByIdentity() {
        ChatResponse callResponse = response("call");
        ChatOptions options = DeepSeekChatOptions.builder().model("deepseek-v4-pro").build();
        ChatOptions defaultOptions = DeepSeekChatOptions.builder().model("deepseek-v4-flash").build();
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return callResponse; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
            @Override public ChatOptions getOptions() { return options; }
            @Override public ChatOptions getDefaultOptions() { return defaultOptions; }
        };
        var wrapped = new StreamIdleTimeoutChatModel(delegate, TIMEOUT);

        assertSame(callResponse, wrapped.call(PROMPT));
        assertSame(options, wrapped.getOptions());
        assertSame(defaultOptions, wrapped.getDefaultOptions());
    }
}
