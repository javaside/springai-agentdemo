package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionModelsTest {

    @Test
    void knownVisionModelsAreSupported() {
        assertTrue(VisionModels.supportsImage("gpt-5.6-sol"), "gpt-5.6-sol");
        assertTrue(VisionModels.supportsImage("claude-opus-5"), "claude-opus-5");
        assertTrue(VisionModels.supportsImage("qwen-vl-max"), "qwen-vl-max");
        assertTrue(VisionModels.supportsImage("glm-4v-plus"), "glm-4v-plus");
    }

    @Test
    void textOnlyModelsAreNotSupported() {
        assertFalse(VisionModels.supportsImage("deepseek-chat"), "deepseek-chat");
        assertFalse(VisionModels.supportsImage("deepseek-reasoner"), "deepseek-reasoner");
    }

    /** 关键：自定义 / 兼容层转发的未知 id 一律当作不支持——判错方向必须安全。 */
    @Test
    void unknownModelDefaultsToUnsupported() {
        assertFalse(VisionModels.supportsImage("my-private-model"), "自定义 id");
        assertFalse(VisionModels.supportsImage(""), "空串");
        assertFalse(VisionModels.supportsImage(null), "null");
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(VisionModels.supportsImage("GPT-5.6-Sol"), "大小写混写的已知 id");
    }

    /** kill switch 的语义：只有明确的 off（忽略大小写与空白）关闭，其余一律开启。 */
    @Test
    void killSwitchRecognisesOnlyExplicitOff() {
        assertFalse(VisionModels.enabledFor("off"), "off 应关闭");
        assertFalse(VisionModels.enabledFor("  OFF  "), "大小写与空白应忽略");
        assertTrue(VisionModels.enabledFor(null), "未配置 = 开启");
        assertTrue(VisionModels.enabledFor(""), "空串 = 开启");
        assertTrue(VisionModels.enabledFor("on"));
        assertTrue(VisionModels.enabledFor("false"), "刻意不认 false/0/no——只认 off，少一种猜法");
    }
}
