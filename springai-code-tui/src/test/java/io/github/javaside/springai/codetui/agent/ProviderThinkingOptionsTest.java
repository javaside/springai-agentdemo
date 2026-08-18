package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderThinkingOptionsTest {

    @Test
    void defaultOptionsCarryNoThinkingOverrides() {
        assertNull(((OpenAiChatOptions) new OpenAiProvider("k").options("gpt-5.6-sol", ThinkingConfig.defaults())).getReasoningEffort());
        assertNull(((OpenAiChatOptions) new QwenProvider("k").options("qwen3.7-max", ThinkingConfig.defaults())).getExtraBody());
        assertNull(((OpenAiChatOptions) new ZhipuProvider("k").options("glm-5.2", ThinkingConfig.defaults())).getExtraBody());
        AnthropicChatOptions anthropic = (AnthropicChatOptions) new AnthropicProvider("k")
                .options("claude-opus-5", ThinkingConfig.defaults());
        assertNull(anthropic.getThinking());
        assertNull(anthropic.getOutputConfig());
    }

    @Test
    void openAiMapsEffortAndDisabled() {
        OpenAiProvider provider = new OpenAiProvider("k");
        assertEquals("high", ((OpenAiChatOptions) provider.options("gpt-5.6-sol",
                ThinkingConfig.enabledEffort("high"))).getReasoningEffort());
        assertEquals("none", ((OpenAiChatOptions) provider.options("gpt-5.6-sol",
                ThinkingConfig.disabled())).getReasoningEffort());
    }

    @Test
    void qwenMapsToggleAndBudgetIntoExtraBody() {
        OpenAiChatOptions enabled = (OpenAiChatOptions) new QwenProvider("k")
                .options("qwen3.7-max", ThinkingConfig.enabledBudget(32768));
        assertEquals(Boolean.TRUE, enabled.getExtraBody().get("enable_thinking"));
        assertEquals(32768, enabled.getExtraBody().get("thinking_budget"));
        OpenAiChatOptions disabled = (OpenAiChatOptions) new QwenProvider("k")
                .options("qwen3.7-max", ThinkingConfig.disabled());
        assertEquals(Map.of("enable_thinking", false), disabled.getExtraBody());
    }

    @Test
    void qwenCoderNextAcceptsToggleButRejectsBudget() {
        QwenProvider provider = new QwenProvider("k");
        assertEquals(Map.of("enable_thinking", true), ((OpenAiChatOptions) provider
                .options("qwen3-coder-next", ThinkingConfig.enabledWithoutStrength())).getExtraBody());
        assertThrows(IllegalArgumentException.class, () -> provider
                .options("qwen3-coder-next", ThinkingConfig.enabledBudget(1024)));
    }

    @Test
    void zhipuGlm53CarriesEffortAndCannotDisable() {
        ZhipuProvider provider = new ZhipuProvider("k");
        OpenAiChatOptions options = (OpenAiChatOptions) provider
                .options("glm-5.3", ThinkingConfig.enabledEffort("max"));
        assertEquals(Map.of("type", "enabled"), options.getExtraBody().get("thinking"));
        assertEquals("max", options.getReasoningEffort());
        // 官方文档：glm-5.3 仅支持开启思考；档位 low/high/max。
        assertFalse(provider.thinkingCapabilities("glm-5.3").supportsDisable());
        assertEquals(List.of("low", "high", "max"), provider.thinkingCapabilities("glm-5.3").effortValues());
        assertThrows(IllegalArgumentException.class, () -> provider
                .options("glm-5.3", ThinkingConfig.disabled()));
        assertThrows(IllegalArgumentException.class, () -> provider
                .options("glm-5.3", ThinkingConfig.enabledEffort("medium")));
    }

    @Test
    void zhipuOnlyGlm52CarriesEffort() {
        ZhipuProvider provider = new ZhipuProvider("k");
        OpenAiChatOptions options = (OpenAiChatOptions) provider
                .options("glm-5.2", ThinkingConfig.enabledEffort("max"));
        assertEquals(Map.of("type", "enabled"), options.getExtraBody().get("thinking"));
        assertEquals("max", options.getReasoningEffort());
        assertThrows(IllegalArgumentException.class, () -> provider
                .options("glm-5.1", ThinkingConfig.enabledEffort("max")));
    }

    @Test
    void anthropicUsesAdaptiveAndFableCannotDisable() {
        AnthropicProvider provider = new AnthropicProvider("k");
        AnthropicChatOptions enabled = (AnthropicChatOptions) provider
                .options("claude-opus-5", ThinkingConfig.enabledEffort("high"));
        assertTrue(enabled.getThinking().isAdaptive());
        assertEquals("high", enabled.getOutputConfig().effort().orElseThrow().asString());
        assertThrows(IllegalArgumentException.class,
                () -> provider.options("claude-fable-5", ThinkingConfig.disabled()));
        assertFalse(provider.thinkingCapabilities("claude-fable-5").supportsDisable());
    }

    @Test
    void customModelsUseProviderFallbackCapabilities() {
        assertTrue(new OpenAiProvider("k", null, "private-gpt").thinkingCapabilities("private-gpt").configurable());
        assertTrue(new QwenProvider("k", null, "private-qwen").thinkingCapabilities("private-qwen").configurable());
        assertTrue(new ZhipuProvider("k", null, "private-glm").thinkingCapabilities("private-glm").configurable());
        assertTrue(new AnthropicProvider("k", null, "private-claude").thinkingCapabilities("private-claude").configurable());
    }
}
