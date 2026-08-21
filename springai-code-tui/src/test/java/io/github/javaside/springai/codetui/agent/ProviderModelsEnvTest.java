package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals("claude-opus-5", new AnthropicProvider("key", null, null).defaultModel());
    }

    @Test
    void openai_modelsEnv_overridesList_firstIsDefault() {
        OpenAiProvider p = new OpenAiProvider("key", null, "gpt-x");
        assertEquals("gpt-x", p.defaultModel());
    }

    @Test
    void openai_noEnv_builtInDefault() {
        assertEquals("gpt-5.6-sol", new OpenAiProvider("key", null, null).defaultModel());
    }

    @Test
    void opencodeGo_modelsEnv_overridesList_firstIsDefault() {
        OpencodeGoProvider p = new OpencodeGoProvider("key", null, "go-x, go-y");
        assertEquals("go-x", p.defaultModel());
        assertEquals(2, p.models().size());
    }

    @Test
    void opencodeGo_noEnv_builtInDefault() {
        assertEquals("deepseek-v4-pro", new OpencodeGoProvider("key", null, null).defaultModel());
    }
}
