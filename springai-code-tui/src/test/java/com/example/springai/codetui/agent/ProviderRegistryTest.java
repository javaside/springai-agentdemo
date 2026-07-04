package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryTest {

    @Test
    void aggregatesOnlyAvailableProviders() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"),
                new AnthropicProvider("k"),
                new OpenAiProvider("")));        // 不可用
        assertEquals("deepseek", reg.active().id());
        assertEquals("deepseek-v4-flash", reg.activeModelId());
        List<String> ids = reg.allModels().stream().map(ModelOption::id).toList();
        assertTrue(ids.contains("deepseek-v4-flash"));
        assertTrue(ids.contains("claude-sonnet-4-5"));
        assertFalse(ids.contains("gpt-4o"));   // openai 不可用，不列出
    }

    @Test
    void selectCrossProviderModelSwitchesActive() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new AnthropicProvider("k")));
        reg.select("claude-opus-4-5");
        assertEquals("anthropic", reg.active().id());
        assertEquals("claude-opus-4-5", reg.activeModelId());
        assertEquals("claude-opus-4-5", reg.activeChatOptions().getModel());
    }

    @Test
    void selectUnknownModelIsIgnored() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("k")));
        reg.select("no-such-model");
        assertEquals("deepseek-v4-flash", reg.activeModelId());
    }
}
