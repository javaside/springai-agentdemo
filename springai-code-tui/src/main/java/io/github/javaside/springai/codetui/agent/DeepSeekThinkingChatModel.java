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
    public ChatOptions getDefaultOptions() {
        return defaultDelegate.getDefaultOptions();
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
