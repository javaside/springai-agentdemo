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
 * 智谱视觉接入的 provider 侧元数据：内置清单、按模型判定的视觉能力、glm-5.3-flash 与
 * glm-5.4 的 thinking 限制（官方文档：与 glm-5.3 一致——仅支持开启、effort low/high/max）。
 */
class ZhipuProviderVisionTest {

    @Test
    void builtinListIncludesNativeMultimodalFlashButDefaultStaysFlagship() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.models().stream().anyMatch(m -> "glm-5.3-flash".equals(m.id())),
                "内置清单应含 glm-5.3-flash（2026-08-26 原生多模态）");
        assertTrue(p.models().stream().anyMatch(m -> "glm-5.4".equals(m.id())),
                "内置清单应含 glm-5.4（2026-11-23 上线，视觉+泛编码旗舰）");
    }

    /** 默认旗舰切 glm-5.4：2026-11-23 发布当天经用户拍板的<b>刻意</b>变更，非悄悄改默认。 */
    @Test
    void defaultModelSwitchesToGlm54() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertEquals("glm-5.4", p.defaultModel(),
                "默认旗舰 = glm-5.4（本次显式变更；注意 glm-5.4 仅 Coding Plan 端点可用，"
                        + "未配 ZHIPU_BASE_URL 的按量付费用户需自配端点或换回 glm-5.3）");
    }

    /** 视觉按模型判定：glm-5.4 与 flash 命中，纯文本上代旗舰不命中；自配的 glm-4.6v 也命中。 */
    @Test
    void visionCapabilityIsPerModel() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertTrue(p.capabilities("glm-5.4").supportsImageInput(), "glm-5.4 原生多模态（本次发布的主打能力）");
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

    /**
     * 官方切换指南（2026-11-23）：glm-5.4 服务端把 thinking.type + reasoning_effort 映射为
     * low/high/max 三档，「关闭思考」的任何取值实际也落到 low——即与 glm-5.3 系同构，不可真正禁用。
     */
    @Test
    void glm54ThinkingMirrorsGlm53() {
        ZhipuProvider p = new ZhipuProvider("k");
        assertFalse(p.thinkingCapabilities("glm-5.4").supportsDisable(), "关思考也落到 low，不可真正禁用");
        assertEquals(List.of("low", "high", "max"), p.thinkingCapabilities("glm-5.4").effortValues());
        assertThrows(IllegalArgumentException.class,
                () -> p.options("glm-5.4", ThinkingConfig.disabled()));
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
