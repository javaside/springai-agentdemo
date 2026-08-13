package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
/** Project-only wrapper; native DeepSeek options are restored before invoking Spring AI. */
final class DeepSeekThinkingChatOptions implements ChatOptions {

    private final org.springframework.ai.deepseek.DeepSeekChatOptions nativeOptions;
    private final ThinkingConfig thinkingConfig;

    DeepSeekThinkingChatOptions(org.springframework.ai.deepseek.DeepSeekChatOptions nativeOptions,
                                ThinkingConfig thinkingConfig) {
        this.nativeOptions = nativeOptions;
        this.thinkingConfig = thinkingConfig;
    }

    org.springframework.ai.deepseek.DeepSeekChatOptions nativeOptions() {
        return nativeOptions;
    }

    ThinkingConfig thinkingConfig() {
        return thinkingConfig;
    }

    @Override public String getModel() { return nativeOptions.getModel(); }
    @Override public Double getFrequencyPenalty() { return nativeOptions.getFrequencyPenalty(); }
    @Override public Integer getMaxTokens() { return nativeOptions.getMaxTokens(); }
    @Override public Double getPresencePenalty() { return nativeOptions.getPresencePenalty(); }
    @Override public List<String> getStopSequences() { return nativeOptions.getStopSequences(); }
    @Override public Double getTemperature() { return nativeOptions.getTemperature(); }
    @Override public Integer getTopK() { return nativeOptions.getTopK(); }
    @Override public Double getTopP() { return nativeOptions.getTopP(); }

    @Override
    public ChatOptions.Builder<?> mutate() {
        return new Builder(nativeOptions.mutate(), thinkingConfig);
    }

    private static final class Builder implements ChatOptions.Builder<Builder> {
        private final org.springframework.ai.deepseek.DeepSeekChatOptions.Builder delegate;
        private final ThinkingConfig thinkingConfig;

        private Builder(org.springframework.ai.deepseek.DeepSeekChatOptions.Builder delegate,
                        ThinkingConfig thinkingConfig) {
            this.delegate = delegate;
            this.thinkingConfig = thinkingConfig;
        }

        @Override public Builder clone() { return new Builder(delegate.clone(), thinkingConfig); }
        @Override public Builder model(String value) { delegate.model(value); return this; }
        @Override public Builder frequencyPenalty(Double value) { delegate.frequencyPenalty(value); return this; }
        @Override public Builder maxTokens(Integer value) { delegate.maxTokens(value); return this; }
        @Override public Builder presencePenalty(Double value) { delegate.presencePenalty(value); return this; }
        @Override public Builder stopSequences(List<String> value) { delegate.stopSequences(value); return this; }
        @Override public Builder temperature(Double value) { delegate.temperature(value); return this; }
        @Override public Builder topK(Integer value) { delegate.topK(value); return this; }
        @Override public Builder topP(Double value) { delegate.topP(value); return this; }

        @Override
        public DeepSeekThinkingChatOptions build() {
            return new DeepSeekThinkingChatOptions(delegate.build(), thinkingConfig);
        }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            delegate.combineWith(other);
            return this;
        }
    }
}
