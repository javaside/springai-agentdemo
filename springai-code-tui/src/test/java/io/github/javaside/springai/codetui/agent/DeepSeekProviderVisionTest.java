package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.VisionModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProviderVisionTest {

    // ---- VisionModels 名单：只认视觉模型，不误伤纯文本 flash ----

    @Test
    void visionModelId_isSupported() {
        assertTrue(VisionModels.supportsImage("deepseek-v4-flash-vision-exp"),
                "deepseek-v4-flash-vision-exp 应判为支持视觉");
    }

    @Test
    void plainFlash_and_pro_areNotSupported() {
        assertFalse(VisionModels.supportsImage("deepseek-v4-flash"), "纯文本 flash 不得误判为视觉");
        assertFalse(VisionModels.supportsImage("deepseek-v4-pro"), "pro 不得误判为视觉");
        assertFalse(VisionModels.supportsImage("deepseek-v4"), "前缀必须精确：不在名单则不支持");
    }

    @Test
    void globalKillSwitch_stillApplies() {
        assertFalse(VisionModels.enabledFor("off"), "CODETUI_VISION=off 时全关");
        assertTrue(VisionModels.enabledFor(null), "未配置默认开启");
    }

    // ---- 内置清单：视觉模型可选项存在，默认模型不变 ----

    @Test
    void builtinModels_includeVision_exp_butDefaultStaysPro() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        List<ModelOption> models = provider.models();
        assertEquals("deepseek-v4-pro", provider.defaultModel(), "默认模型必须仍是 deepseek-v4-pro");
        assertTrue(models.stream().anyMatch(m -> m.id().equals("deepseek-v4-flash-vision-exp")),
                "内置清单应含 deepseek-v4-flash-vision-exp");
    }

    @Test
    void capabilities_followModelId() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        assertTrue(provider.capabilities("deepseek-v4-flash-vision-exp").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-pro").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-flash").supportsImageInput());
    }

    // ---- 传输开关纯函数（Task 5 复用）----

    @Test
    void transport_parsing() {
        assertEquals(DeepSeekProvider.VisionTransport.FILES,
                DeepSeekProvider.visionTransportFor("files"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("inline"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor(null));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("  FILES  "));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("garbage"));
    }
}
