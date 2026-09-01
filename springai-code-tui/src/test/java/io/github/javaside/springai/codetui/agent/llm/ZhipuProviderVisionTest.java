package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智谱视觉接入的 provider 侧元数据：内置清单、按模型判定的视觉能力、glm-5.3-flash 的
 * thinking 限制（官方文档：与 glm-5.3 一致——仅支持开启、effort low/high/max）。
 */
class ZhipuProviderVisionTest {

    @Test
    void builtinListIncludesNativeMultimodalFlashButDefaultStaysFlagship() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.models().stream().anyMatch(m -> "glm-5.3-flash".equals(m.id())),
                "内置清单应含 glm-5.3-flash（2026-08-26 原生多模态）");
        assertEquals("glm-5.3", p.defaultModel(),
                "默认仍是旗舰 glm-5.3——加模型不悄悄改默认，已存偏好与新项目都不受影响");
    }

    /** 视觉按模型判定：flash 命中，纯文本旗舰不命中；自配的 glm-4.6v 也命中。 */
    @Test
    void visionCapabilityIsPerModel() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.capabilities("glm-5.3-flash").supportsImageInput(), "glm-5.3-flash 原生多模态");
        assertTrue(p.capabilities("glm-4.6v").supportsImageInput(), "用户经 ZHIPU_MODELS 自配的 glm-4.6v");
        assertFalse(p.capabilities("glm-5.3").supportsImageInput(), "glm-5.3 纯文本");
        assertFalse(p.capabilities("glm-5.2").supportsImageInput(), "glm-5.2 纯文本");
    }

    /** 官方文档：glm-5.3-flash 文本参数与 glm-5.3 一致——思考不可关、effort low/high/max。 */
    @Test
    void flashThinkingMirrorsGlm53() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertFalse(p.thinkingCapabilities("glm-5.3-flash").supportsDisable(), "思考不可关闭");
        assertEquals(List.of("low", "high", "max"), p.thinkingCapabilities("glm-5.3-flash").effortValues());
        assertThrows(IllegalArgumentException.class,
                () -> p.options("glm-5.3-flash", ThinkingConfig.disabled()));
    }

    @Test
    void flashOptionsCarryEffort() {
        ZhipuProvider p = new ZhipuProvider("k");
        OpenAiChatOptions opts = (OpenAiChatOptions) p.options("glm-5.3-flash",
                ThinkingConfig.enabledEffort("max"));
        assertEquals("max", opts.getReasoningEffort());
        assertEquals("glm-5.3-flash", opts.getModel());
    }
}
