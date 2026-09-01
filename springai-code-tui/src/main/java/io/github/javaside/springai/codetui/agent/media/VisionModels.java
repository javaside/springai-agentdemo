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
            "glm-4v", "glm-4.1v", "glm-4.5v",
            "glm-4.6v",                        // ★ 2025-12 上线，首个原生 Function Call 的视觉线
            "glm-5.3-flash",                   // ★ 2026-08-26 上线，GLM-5 系首个原生多模态（注意：glm-5.3 本身仍是纯文本）
            "glm-5v",                          // ★ GLM-5V 系（2026-04 GLM-5V-Turbo，多模态 Coding 基座）
            "deepseek-v4-flash-vision"         // ★ DeepSeek 视觉实验模型（2026-08-21 上线）
    );

    /**
     * <b>本次运行能否给该模型投递图片。</b>注意这不是纯粹的「模型静态能力」——
     * 它<b>包含了全局开关</b>（{@code CODETUI_VISION=off} 时对所有模型返回 false）。
     *
     * <p>之所以把两个维度揉在一起：每个调用点想要的恰好都是「现在能不能投」。
     * 尤其 {@link TextReferenceMediaHandler} 据此决定引用里写 {@code reference_only}
     * 还是 {@code not_in_view}——开关关着时写 reference_only 是对的，因为此时模型
     * Read 回来确实看不见，写 not_in_view 会骗它白 Read 一次。
     *
     * <p>调用方<b>不要</b>把它和 {@link #enabled()} 当成两个独立维度再判一次，那是重复判断。
     *
     * <p>null / 空 / 不在名单 → false。
     */
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
        return enabledFor(System.getenv("CODETUI_VISION"));
    }

    /** 开关值的解析（纯函数，供单测——{@code System.getenv} 在进程内无法注入，
     *  不抽出来这个 kill switch 就永远没有测试覆盖）。 */
    public static boolean enabledFor(String envValue) {
        return envValue == null || !envValue.trim().equalsIgnoreCase("off");
    }
}
