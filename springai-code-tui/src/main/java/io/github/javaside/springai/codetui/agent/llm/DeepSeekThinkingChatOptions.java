package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * Project-only wrapper; native DeepSeek options are restored before invoking Spring AI.
 *
 * <p>实现了 {@link ToolCallingChatOptions}（而非裸 {@link ChatOptions}）是<b>必须</b>的：ChatClient 在构建
 * 请求时走 {@code getOptions().mutate() → combineWith(每回合 options) → build()} 的合并链（见
 * {@code DefaultChatClientUtils}）。若本类只实现 {@code ChatOptions}，它的 Builder 在 {@code combineWith}
 * 里既不匹配 {@code DefaultChatOptionsBuilder}、也不匹配原生 DeepSeek 的 AbstractBuilder，模型与思考配置都会
 * 被静默丢掉（合并结果退回 native 默认模型 + 无思考字段），且 {@code ToolCallingAdvisor} 会因为
 * {@code !(builder instanceof ToolCallingChatOptions.Builder)} 而整个跳过、工具全部静默丢失。
 */
final class DeepSeekThinkingChatOptions implements ToolCallingChatOptions {

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
    @Override public List<ToolCallback> getToolCallbacks() { return nativeOptions.getToolCallbacks(); }
    @Override public Map<String, Object> getToolContext() { return nativeOptions.getToolContext(); }

    @Override
    public Builder mutate() {
        return new Builder(nativeOptions.mutate(), thinkingConfig);
    }

    static final class Builder implements ToolCallingChatOptions.Builder<Builder> {
        private final org.springframework.ai.deepseek.DeepSeekChatOptions.Builder delegate;
        private ThinkingConfig thinkingConfig;

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
        @Override public Builder toolCallbacks(List<ToolCallback> value) { delegate.toolCallbacks(value); return this; }
        @Override public Builder toolCallbacks(ToolCallback... value) { delegate.toolCallbacks(value); return this; }
        @Override public Builder toolContext(Map<String, Object> value) { delegate.toolContext(value); return this; }
        @Override public Builder toolContext(String key, Object value) { delegate.toolContext(key, value); return this; }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            if (other instanceof Builder that) {
                // 同型包装：透传 native 字段，并按「非默认才覆盖」合并思考配置（DEFAULT 视为无覆盖）。
                delegate.combineWith(that.delegate);
                if (that.thinkingConfig != null && that.thinkingConfig.mode() != ThinkingMode.DEFAULT) {
                    this.thinkingConfig = that.thinkingConfig;
                }
            } else {
                // 裸 native 或其它 provider 的 builder：仅透传 native 字段，思考配置保持本侧。
                delegate.combineWith(other);
            }
            return this;
        }

        @Override
        public DeepSeekThinkingChatOptions build() {
            return new DeepSeekThinkingChatOptions(delegate.build(), thinkingConfig);
        }
    }
}
