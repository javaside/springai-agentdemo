package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 各 LlmProvider 的装配/可用性/options 单测：全部用假 key，不发任何网络请求。 */
class LlmProviderTest {

    @Test
    void deepseek_withKey_isAvailableAndBuildsModel() {
        DeepSeekProvider p = new DeepSeekProvider("fake-key");
        assertEquals("deepseek", p.id());
        assertTrue(p.available());
        assertEquals("deepseek-v4-flash", p.defaultModel());
        assertFalse(p.models().isEmpty());
        assertTrue(p.chatModel() != null);
        assertEquals("deepseek-v4-pro", p.options("deepseek-v4-pro").getModel());
    }

    @Test
    void deepseek_withoutKey_isUnavailable() {
        DeepSeekProvider p = new DeepSeekProvider("  ");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    @Test
    void anthropic_withKey_availableAndOptionsCarryModelAndMaxTokens() {
        AnthropicProvider p = new AnthropicProvider("fake-key");
        assertEquals("anthropic", p.id());
        assertTrue(p.available());
        assertEquals("claude-sonnet-5", p.defaultModel());
        assertTrue(p.chatModel() != null);
        org.springframework.ai.anthropic.AnthropicChatOptions opts =
                (org.springframework.ai.anthropic.AnthropicChatOptions) p.options("claude-opus-4-8");
        assertEquals("claude-opus-4-8", opts.getModel());
        assertEquals(8192, opts.getMaxTokens());
    }

    @Test
    void anthropic_withoutKey_isUnavailable() {
        AnthropicProvider p = new AnthropicProvider(null);
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    @Test
    void openai_withKey_availableAndOptionsCarryModel() {
        OpenAiProvider p = new OpenAiProvider("fake-key");
        assertEquals("openai", p.id());
        assertTrue(p.available());
        assertEquals("gpt-5.5", p.defaultModel());
        assertTrue(p.chatModel() != null);   // 实测网络无关：build() 从 options.apiKey 派生 client
        assertEquals("gpt-5.4-mini", p.options("gpt-5.4-mini").getModel());
    }

    @Test
    void openai_withoutKey_isUnavailable() {
        OpenAiProvider p = new OpenAiProvider("");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    /** 自定义 base-url：非空即覆盖，仍网络无关地建出 model。 */
    @Test
    void customBaseUrl_isAcceptedAndModelStillBuilds() {
        assertTrue(new DeepSeekProvider("fake-key", "https://proxy.example/ds").chatModel() != null);
        assertTrue(new AnthropicProvider("fake-key", "https://proxy.example/an").chatModel() != null);
        assertTrue(new OpenAiProvider("fake-key", "https://proxy.example/oa").chatModel() != null);
    }

    /** 空/null base-url：回落各家内置默认，仍能建出 model。 */
    @Test
    void blankBaseUrl_fallsBackToDefaultAndStillBuilds() {
        assertTrue(new DeepSeekProvider("fake-key", "   ").chatModel() != null);
        assertTrue(new AnthropicProvider("fake-key", null).chatModel() != null);
        assertTrue(new OpenAiProvider("fake-key", "").chatModel() != null);
    }
}
