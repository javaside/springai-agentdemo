package io.github.javaside.springai.codetui.agent;

/**
 * 一个「带 provider 归属」的可选模型条目，供 {@code /model} 选择器展示与切换。
 *
 * <p>与 {@link ModelOption} 的区别：{@link ModelOption} 是单个 provider 自己的模型清单项
 * （只含 id/label/desc），而 {@code ProviderModel} 是在 {@code ProviderRegistry} 把多家 provider
 * 聚合到一起时，为每条模型<b>钉上它出自哪家</b>的条目。
 *
 * <p>为什么需要它：多家 provider 会提供<b>同名</b>模型（如 DeepSeek 原生与 OpenCode Go 聚合网关
 * 都提供 {@code deepseek-v4-pro}）。一旦模型身份只靠裸 {@code modelId} 定位，同名模型就会互相
 * 串号——选中/思考设置都会命错 provider。带上 {@code providerId} 后，UI 与注册表都能精确区分。
 *
 * @param providerId 所属 provider 的稳定 id（{@link LlmProvider#id()}）
 * @param modelId    传给 API 的模型标识（如 {@code deepseek-v4-flash}）
 * @param label      展示名
 * @param desc       一句话说明（右侧暗色提示）
 */
public record ProviderModel(String providerId, String modelId, String label, String desc) {

    /** 与 {@link ModelOption#id()} 对齐的便捷访问器，减少调用方把 modelId 改名带来的改动。 */
    public String id() { return modelId; }
}
