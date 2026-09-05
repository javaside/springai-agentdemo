package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 各 Provider 的 *_MODELS 环境变量接入：配置来的清单生效且首项为默认；不配置回退内置清单。 */
class ProviderModelsEnvTest {

    @Test
    void deepseek_modelsEnv_overridesList_firstIsDefault() {
        DeepSeekProvider p = new DeepSeekProvider("key", null, "m-a, m-b");
        assertEquals(java.util.List.of("m-a", "m-b"),
                p.models().stream().map(ModelOption::id).toList());
        assertEquals("m-a", p.defaultModel());
    }

    @Test
    void deepseek_noEnv_builtInList_defaultIsFirst() {
        DeepSeekProvider p = new DeepSeekProvider("key", null, null);
        assertEquals("deepseek-v4-pro", p.defaultModel());
        assertEquals("deepseek-v4-pro", p.models().get(0).id());   // 内置清单首项 = 默认（本 Task 调序）
        assertEquals(3, p.models().size());   // pro + flash + flash-vision-exp（2026-08-21 视觉实验模型）
    }

    @Test
    void zhipu_modelsEnv_overridesList_firstIsDefault() {
        ZhipuProvider p = new ZhipuProvider("key", null, "glm-x, glm-y");
        assertEquals("glm-x", p.defaultModel());
        assertEquals(2, p.models().size());
    }

    @Test
    void zhipu_noEnv_builtInDefault() {
        assertEquals("glm-5.3", new ZhipuProvider("key", null, null).defaultModel());
    }

    @Test
    void qwen_modelsEnv_overridesList_firstIsDefault() {
        QwenProvider p = new QwenProvider("key", null, "qwen-x");
        assertEquals("qwen-x", p.defaultModel());
        assertEquals(1, p.models().size());
    }

    @Test
    void qwen_noEnv_builtInDefault() {
        assertEquals("qwen3.7-max", new QwenProvider("key", null, null).defaultModel());
    }

    @Test
    void anthropic_modelsEnv_overridesList_firstIsDefault() {
        AnthropicProvider p = new AnthropicProvider("key", null, "claude-x, claude-y");
        assertEquals("claude-x", p.defaultModel());
    }

    @Test
    void anthropic_noEnv_builtInDefault() {
        AnthropicProvider p = new AnthropicProvider("key", null, null);
        assertEquals("claude-opus-5", p.defaultModel());
        // 2026-09 在售：fable-5-1 / opus-5 / sonnet-5 / haiku-4-5，fable-5 与 opus-4-8 转上代保留。
        assertEquals(6, p.models().size());
        assertTrue(p.models().stream().anyMatch(m -> m.id().equals("claude-fable-5-1")),
                "新旗舰 claude-fable-5-1 应出现在内置清单");
        assertTrue(p.models().stream().anyMatch(m -> m.id().equals("claude-fable-5")),
                "上代旗舰 claude-fable-5 应保留在清单末尾");
    }

    @Test
    void openai_modelsEnv_overridesList_firstIsDefault() {
        OpenAiProvider p = new OpenAiProvider("key", null, "gpt-x");
        assertEquals("gpt-x", p.defaultModel());
    }

    @Test
    void openai_noEnv_builtInDefault() {
        assertEquals("gpt-6-astra", new OpenAiProvider("key", null, null).defaultModel());
    }

    @Test
    void opencodeGo_modelsEnv_overridesList_firstIsDefault() {
        OpencodeGoProvider p = new OpencodeGoProvider("key", null, "go-x, go-y");
        assertEquals("go-x", p.defaultModel());
        assertEquals(2, p.models().size());
    }

    @Test
    void opencodeGo_noEnv_builtInDefault() {
        OpencodeGoProvider provider = new OpencodeGoProvider("key", null, null);
        assertEquals("deepseek-v4-pro", provider.defaultModel());
        assertTrue(provider.models().stream()
                .anyMatch(model -> model.id().equals("deepseek-v4-flash-vision-exp")),
                "OpenCode Go 官方视觉模型应出现在内置清单");
    }

    @Test
    void opencodeGo_onlyOfficialVisionModel_acceptsImages() {
        OpencodeGoProvider provider = new OpencodeGoProvider("key");

        assertTrue(provider.capabilities("deepseek-v4-flash-vision-exp").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-flash").supportsImageInput());
        assertFalse(provider.capabilities("gpt-5.6-luna").supportsImageInput(),
                "Go 网关未声明支持视觉的模型不能沿用全局前缀名单");
        assertFalse(provider.capabilities("custom-vision-model").supportsImageInput());
    }
}
