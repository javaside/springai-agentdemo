package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCapabilitiesTest {

    @Test
    void openAiVisionModelsReportImageSupport() {
        LlmProvider p = new OpenAiProvider("sk-fake");
        assertTrue(p.capabilities("gpt-5.6-sol").supportsImageInput(), "gpt-5.6-sol 应支持视觉");
    }

    @Test
    void deepSeekReportsNoImageSupport() {
        LlmProvider p = new DeepSeekProvider("sk-fake");
        assertFalse(p.capabilities("deepseek-chat").supportsImageInput(), "DeepSeek 无视觉模型");
    }

    /** 自定义模型清单里的未知 id：即使配在 OpenAI 家，也判不支持——判错方向必须安全。 */
    @Test
    void unknownCustomModelIsNotVisionCapable() {
        LlmProvider p = new OpenAiProvider("sk-fake", null, "my-private-model");
        assertFalse(p.capabilities("my-private-model").supportsImageInput(),
                "未知 id 必须判不支持，否则会真发出去吃 400");
    }

    /** 视频本期一律不支持——字段保留但恒 false。 */
    @Test
    void videoIsNeverSupportedThisPhase() {
        assertFalse(new OpenAiProvider("sk-fake").capabilities("gpt-5.6-sol").supportsVideoInput(),
                "本期不投递视频");
    }
}
