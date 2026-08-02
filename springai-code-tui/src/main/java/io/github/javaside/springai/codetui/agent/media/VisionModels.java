package io.github.javaside.springai.codetui.agent.media;

import java.util.List;
import java.util.Locale;

/**
 * 「哪些模型支持图片输入」的名单。<b>未知一律判不支持</b>。
 *
 * <p><b>为什么默认必须是不支持</b>：判错的两个方向代价不对称。误判「不支持」只是拦住用户，
 * 提示可见、可改配置；误判「支持」会把图真发出去吃一个 400——浪费上传时间与费用，而且
 * 各家的错误信息未必看得出是图片的问题（本项目在 DeepSeek 兼容层上踩过类似的错误归因）。
 *
 * <p>名单必然过期：用户可用 {@code *_MODELS} 环境变量配任意模型 id（见 {@code ModelListEnv}），
 * 也可能经兼容层把已知 id 转发到别处。这两种情况都落到「不支持」，代价可接受。
 *
 * <p>{@code CODETUI_VISION=off} 全局关闭（省钱逃生口）。
 */
public final class VisionModels {

    private VisionModels() {}

    /** 前缀名单（小写比较）。加新模型时只动这里。 */
    private static final List<String> VISION_PREFIXES = List.of(
            "gpt-5.", "gpt-4o", "o4-",
            "claude-",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
            "glm-4v", "glm-4.1v", "glm-4.5v"
    );

    /** 该模型是否接受图片输入。null / 空 / 不在名单 → false。 */
    public static boolean supportsImage(String modelId) {
        if (!enabled() || modelId == null || modelId.isBlank()) {
            return false;
        }
        String id = modelId.trim().toLowerCase(Locale.ROOT);
        for (String p : VISION_PREFIXES) {
            if (id.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 全局开关：{@code CODETUI_VISION=off} 时整个视觉链路停用（引用照常，零行为变化）。 */
    public static boolean enabled() {
        String v = System.getenv("CODETUI_VISION");
        return v == null || !v.trim().equalsIgnoreCase("off");
    }
}
