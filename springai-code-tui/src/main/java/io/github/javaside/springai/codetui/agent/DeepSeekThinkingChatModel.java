package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Routes immutable thinking settings to a fixed-config native DeepSeek delegate. */
final class DeepSeekThinkingChatModel implements ChatModel {

    private final ChatModel defaultDelegate;
    private final Function<ThinkingConfig, ChatModel> delegateFactory;
    private final Map<ThinkingConfig, ChatModel> delegates = new ConcurrentHashMap<>();

    DeepSeekThinkingChatModel(ChatModel defaultDelegate, Function<ThinkingConfig, ChatModel> delegateFactory) {
        this.defaultDelegate = defaultDelegate;
        this.delegateFactory = delegateFactory;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        return delegate(config).call(withNativeOptions(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        return delegate(config).stream(withNativeOptions(prompt));
    }

    @Override
    public ChatOptions getOptions() {
        // 必须返回「思考包装」而非裸 native 选项：ChatClient 合并链以 getOptions().mutate() 起 base，
        // 若这里返回 native DeepSeekChatOptions，其 Builder 在 combineWith(每回合的 DeepSeekThinkingChatOptions.Builder)
        // 时既不匹配 DefaultChatOptionsBuilder 也不匹配 DeepSeek 的 AbstractBuilder，模型与思考配置都会被静默丢掉。
        return new DeepSeekThinkingChatOptions(defaultNativeOptions(), ThinkingConfig.defaults());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    private org.springframework.ai.deepseek.DeepSeekChatOptions defaultNativeOptions() {
        return (org.springframework.ai.deepseek.DeepSeekChatOptions) defaultDelegate.getOptions();
    }

    int delegateCount() {
        return delegates.size();
    }

    private ChatModel delegate(ThinkingConfig config) {
        if (config.mode() == ThinkingMode.DEFAULT) {
            return defaultDelegate;
        }
        return delegates.computeIfAbsent(config, delegateFactory);
    }

    private Prompt withNativeOptions(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof DeepSeekThinkingChatOptions thinking) {
            return new Prompt(prompt.getInstructions(), thinking.nativeOptions());
        }
        return prompt;
    }

    private static ThinkingConfig configOf(ChatOptions options) {
        return options instanceof DeepSeekThinkingChatOptions thinking
                ? thinking.thinkingConfig() : ThinkingConfig.defaults();
    }
}
