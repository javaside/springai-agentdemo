package io.github.javaside.springai.codetui.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;

/** 为 LLM 流增加首个响应与相邻响应之间的空闲超时。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class StreamIdleTimeoutChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Duration idleTimeout;

    public StreamIdleTimeoutChatModel(ChatModel delegate, Duration idleTimeout) {
        this.delegate = delegate;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
                .timeout(idleTimeout, Flux.error(() -> new RuntimeException(
                        "等待模型流数据超时（" + formatSeconds(idleTimeout) + " 秒无新数据）。"
                                + "可通过 " + LlmTimeouts.READ_TIMEOUT_ENV + " 调整。")));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @SuppressWarnings("removal")
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static String formatSeconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1_000 == 0
                ? Long.toString(millis / 1_000)
                : Double.toString(millis / 1_000.0);
    }
}
