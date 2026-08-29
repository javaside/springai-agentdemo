package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.util.List;

/** 给一家 provider 的流式模型调用统一增加空闲超时。 */
public final class StreamIdleTimeoutProvider implements LlmProvider {

    private final LlmProvider inner;
    private final Duration idleTimeout;
    private volatile ChatModel timed;

    public StreamIdleTimeoutProvider(LlmProvider inner, Duration idleTimeout) {
        this.inner = inner;
        this.idleTimeout = idleTimeout;
    }

    @Override public String id() { return inner.id(); }
    @Override public boolean available() { return inner.available(); }

    @Override
    public ChatModel chatModel() {
        ChatModel model = timed;
        if (model == null) {
            model = new StreamIdleTimeoutChatModel(inner.chatModel(), idleTimeout);
            timed = model;
        }
        return model;
    }

    @Override public ChatOptions options(String modelId) { return inner.options(modelId); }
    @Override public ThinkingCapabilities thinkingCapabilities(String modelId) {
        return inner.thinkingCapabilities(modelId);
    }
    @Override public ChatOptions options(String modelId, ThinkingConfig config) {
        return inner.options(modelId, config);
    }
    @Override public List<ModelOption> models() { return inner.models(); }
    @Override public String defaultModel() { return inner.defaultModel(); }
    @Override public ModelCapabilities capabilities(String modelId) { return inner.capabilities(modelId); }
}
