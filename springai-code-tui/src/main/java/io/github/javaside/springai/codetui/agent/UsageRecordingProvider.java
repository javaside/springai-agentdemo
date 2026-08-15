package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * {@link LlmProvider} 装饰器：把 {@link #chatModel()} 包上 {@link UsageRecordingChatModel}，使主 agent /
 * 子 agent / 摘要三条路径（都经 {@code registry.*().chatModel()}）统一采集 token 用量。
 * 在 {@code ProviderRegistry} 构造前对每家 provider 各包一次。
 */
public final class UsageRecordingProvider implements LlmProvider {

    private final LlmProvider inner;
    private final TokenUsageAccumulator accumulator;
    private volatile ChatModel recorded;   // 幂等缓存（chatModel() 契约是单例）

    public UsageRecordingProvider(LlmProvider inner, TokenUsageAccumulator accumulator) {
        this.inner = inner;
        this.accumulator = accumulator;
    }

    @Override public String id() { return inner.id(); }
    @Override public boolean available() { return inner.available(); }

    @Override
    public ChatModel chatModel() {
        ChatModel m = recorded;
        if (m == null) {
            m = new UsageRecordingChatModel(inner.chatModel(), accumulator);
            recorded = m;
        }
        return m;
    }

    @Override public ChatOptions options(String modelId) { return inner.options(modelId); }
    @Override public ThinkingCapabilities thinkingCapabilities(String modelId) { return inner.thinkingCapabilities(modelId); }
    @Override public ChatOptions options(String modelId, ThinkingConfig config) { return inner.options(modelId, config); }
    @Override public List<ModelOption> models() { return inner.models(); }
    @Override public String defaultModel() { return inner.defaultModel(); }
    @Override public ModelCapabilities capabilities(String modelId) { return inner.capabilities(modelId); }
}
