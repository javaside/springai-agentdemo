package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingStrengthKind;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 各 LlmProvider 的装配/可用性/options 单测：全部用假 key，不发任何网络请求。 */
class LlmProviderTest {

    @Test
    void deepseek_withKey_isAvailableAndBuildsModel() {
        DeepSeekProvider p = new DeepSeekProvider("fake-key");
        assertEquals("deepseek", p.id());
        assertTrue(p.available());
        assertEquals("deepseek-v4-pro", p.defaultModel());
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
        assertEquals("claude-opus-5", p.defaultModel());
        assertTrue(p.chatModel() != null);
        org.springframework.ai.anthropic.AnthropicChatOptions opts =
                (org.springframework.ai.anthropic.AnthropicChatOptions) p.options("claude-opus-5");
        assertEquals("claude-opus-5", opts.getModel());
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
        assertEquals("gpt-5.6-sol", p.defaultModel());
        assertTrue(p.chatModel() != null);   // 实测网络无关：build() 从 options.apiKey 派生 client
        assertEquals("gpt-5.4", p.options("gpt-5.4").getModel());
    }

    @Test
    void openai_withoutKey_isUnavailable() {
        OpenAiProvider p = new OpenAiProvider("");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    @Test
    void zhipu_withKey_availableAndOptionsCarryModel() {
        ZhipuProvider p = new ZhipuProvider("fake-key");
        assertEquals("zhipu", p.id());
        assertTrue(p.available());
        assertEquals("glm-5.2", p.defaultModel());
        assertFalse(p.models().isEmpty());
        assertTrue(p.chatModel() != null);   // 复用 OpenAiChatModel：build() 从 options 派生 client，网络无关
        assertEquals("glm-5.1", p.options("glm-5.1").getModel());
    }

    @Test
    void zhipu_withoutKey_isUnavailable() {
        ZhipuProvider p = new ZhipuProvider("  ");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    @Test
    void qwen_withKey_availableAndOptionsCarryModel() {
        QwenProvider p = new QwenProvider("fake-key");
        assertEquals("qwen", p.id());
        assertTrue(p.available());
        assertEquals("qwen3.7-max", p.defaultModel());
        assertFalse(p.models().isEmpty());
        assertTrue(p.chatModel() != null);   // 复用 OpenAiChatModel：build() 从 options 派生 client，网络无关
        assertEquals("qwen3-coder-next", p.options("qwen3-coder-next").getModel());
    }

    @Test
    void qwen_withoutKey_isUnavailable() {
        QwenProvider p = new QwenProvider(null);
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    @Test
    void opencodeGo_withKey_availableAndOptionsCarryModel() {
        OpencodeGoProvider p = new OpencodeGoProvider("fake-key");
        assertEquals("opencode-go", p.id());
        assertTrue(p.available());
        assertEquals("deepseek-v4-pro", p.defaultModel());
        assertFalse(p.models().isEmpty());
        assertTrue(p.chatModel() != null);   // 复用 OpenAiChatModel：build() 从 options 派生 client，网络无关
        assertEquals("glm-5.2", p.options("glm-5.2").getModel());
    }

    @Test
    void opencodeGo_withoutKey_isUnavailable() {
        OpencodeGoProvider p = new OpencodeGoProvider("  ");
        assertFalse(p.available());
        assertThrows(IllegalStateException.class, p::chatModel);
    }

    /** qwen3.7-max 仅暴露在 Anthropic /messages 端点，走 OpenAI 兼容通路的默认清单必须不含它。 */
    @Test
    void opencodeGo_defaultListExcludesMessagesOnlyQwen() {
        OpencodeGoProvider p = new OpencodeGoProvider("fake-key");
        List<String> ids = p.models().stream().map(ModelOption::id).toList();
        assertTrue(ids.contains("qwen3.8-max"), "同门旗舰 qwen3.8-max 应可经 /chat/completions 使用");
        assertFalse(ids.contains("qwen3.7-max"), "qwen3.7-max 仅走 /messages，不得进默认清单");
    }

    /** 自定义 base-url：非空即覆盖，仍网络无关地建出 model。 */
    @Test
    void customBaseUrl_isAcceptedAndModelStillBuilds() {
        assertTrue(new DeepSeekProvider("fake-key", "https://proxy.example/ds").chatModel() != null);
        assertTrue(new AnthropicProvider("fake-key", "https://proxy.example/an").chatModel() != null);
        assertTrue(new OpenAiProvider("fake-key", "https://proxy.example/oa").chatModel() != null);
        assertTrue(new QwenProvider("fake-key", "https://proxy.example/qw").chatModel() != null);
        assertTrue(new OpencodeGoProvider("fake-key", "https://proxy.example/go").chatModel() != null);
    }

    /** 空/null base-url：回落各家内置默认，仍能建出 model。 */
    @Test
    void blankBaseUrl_fallsBackToDefaultAndStillBuilds() {
        assertTrue(new DeepSeekProvider("fake-key", "   ").chatModel() != null);
        assertTrue(new AnthropicProvider("fake-key", null).chatModel() != null);
        assertTrue(new OpenAiProvider("fake-key", "").chatModel() != null);
        assertTrue(new QwenProvider("fake-key", "").chatModel() != null);
        assertTrue(new OpencodeGoProvider("fake-key", null).chatModel() != null);
    }

    /** 思考强度：网关 reasoning_effort 全集虽大，但只暴露全上游通用的 low/medium/high 三档。 */
    @Test
    void opencodeGo_thinkingCapabilities_effortLowMediumHigh() {
        var caps = new OpencodeGoProvider("fake-key").thinkingCapabilities("glm-5.2");
        assertTrue(caps.configurable());
        assertTrue(caps.supportsDisable());
        assertEquals(ThinkingStrengthKind.EFFORT, caps.strengthKind());
        assertEquals(List.of("low", "medium", "high"), caps.effortValues());
    }

    /** 思考装配：ENABLED+effort → reasoning_effort=档位；DISABLED → none；DEFAULT → 不带 reasoning_effort。 */
    @Test
    void opencodeGo_optionsThinking_effortAndDisable() {
        OpencodeGoProvider p = new OpencodeGoProvider("fake-key");
        OpenAiChatOptions opts =
                (OpenAiChatOptions) p.options("glm-5.2", ThinkingConfig.enabledEffort("high"));
        assertEquals("glm-5.2", opts.getModel());
        assertEquals("high", opts.getReasoningEffort());

        opts = (OpenAiChatOptions) p.options("glm-5.2", ThinkingConfig.disabled());
        assertEquals("none", opts.getReasoningEffort());

        opts = (OpenAiChatOptions) p.options("glm-5.2", ThinkingConfig.defaults());
        assertEquals("glm-5.2", opts.getModel());
        assertNull(opts.getReasoningEffort());
    }
}
