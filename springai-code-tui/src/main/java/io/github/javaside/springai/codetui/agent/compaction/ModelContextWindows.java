package io.github.javaside.springai.codetui.agent.compaction;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves context-window sizes from explicit overrides, built-in metadata, then a conservative fallback.
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
public final class ModelContextWindows {

    static final long DEFAULT_UNKNOWN_WINDOW = 128_000L;

    private static final Map<String, Long> BUILT_INS = Map.ofEntries(
            Map.entry("openai:gpt-5.6-sol", 1_050_000L),
            Map.entry("openai:gpt-5.6-terra", 1_050_000L),
            Map.entry("openai:gpt-5.6-luna", 1_050_000L),
            Map.entry("deepseek:deepseek-v4-pro", 1_000_000L),
            Map.entry("deepseek:deepseek-v4-flash", 1_000_000L),
            Map.entry("deepseek:deepseek-v4-flash-vision-exp", 1_000_000L),
            Map.entry("zhipu:glm-5.3", 1_000_000L),
            // 智谱视觉线（2026-09-01 官方文档核实）：flash 1M / 4.6v 128K / 4.5v 64K；
            // glm-4v / glm-4.1v 窗口未核实，不进表——落保守兜底，用户可经 CODETUI_CONTEXT_WINDOWS 覆盖。
            Map.entry("zhipu:glm-5.3-flash", 1_000_000L),
            Map.entry("zhipu:glm-4.6v", 128_000L),
            Map.entry("zhipu:glm-4.5v", 64_000L),
            Map.entry("anthropic:claude-opus-5", 1_000_000L),
            Map.entry("anthropic:claude-fable-5", 1_000_000L),
            Map.entry("anthropic:claude-sonnet-5", 1_000_000L),
            Map.entry("anthropic:claude-haiku-4-5", 200_000L));

    private final Map<String, Long> overrides;
    private final long unknownWindow;

    private ModelContextWindows(Map<String, Long> overrides, long unknownWindow) {
        this.overrides = Map.copyOf(overrides);
        this.unknownWindow = unknownWindow > 0 ? unknownWindow : DEFAULT_UNKNOWN_WINDOW;
    }

    /**
     * <b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ModelContextWindows fromEnvironment() {
        return parse(System.getenv("CODETUI_CONTEXT_WINDOWS"),
                positiveLong(System.getenv("CODETUI_UNKNOWN_CONTEXT_WINDOW"), DEFAULT_UNKNOWN_WINDOW));
    }

    static ModelContextWindows parse(String raw, long unknownWindow) {
        Map<String, Long> parsed = new HashMap<>();
        if (raw != null) {
            for (String entry : raw.split(",")) {
                int equals = entry.lastIndexOf('=');
                if (equals <= 0) continue;
                String key = normalize(entry.substring(0, equals));
                long value = positiveLong(entry.substring(equals + 1), -1L);
                if (!key.isEmpty() && value > 0) parsed.put(key, value);
            }
        }
        return new ModelContextWindows(parsed, unknownWindow);
    }

    /**
     * <b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public long resolve(String providerId, String modelId) {
        String key = normalize(providerId + ":" + modelId);
        Long override = overrides.get(key);
        if (override != null) return override;
        Long builtIn = BUILT_INS.get(key);
        if (builtIn != null) return builtIn;
        // 同名模型回退：聚合网关（如 opencode-go）提供的模型与某家原厂模型同名（如 deepseek-v4-pro），
        // 精确键（opencode-go:deepseek-v4-pro）不在表里，但窗口应等同原厂条目而非保守兜底。
        // 显式覆盖优先于内置值（用户对该模型名说过话）；多命中且值不一致 → 不猜，落保守兜底。
        String model = normalize(modelId);
        if (model.isEmpty()) return unknownWindow;
        Long byOverride = matchByModelId(overrides, model);
        if (byOverride != null) return byOverride;
        Long byBuiltIn = matchByModelId(BUILT_INS, model);
        return byBuiltIn != null ? byBuiltIn : unknownWindow;
    }

    /** 在表里按 modelId 后缀找同名条目；多个命中且窗口值互相矛盾 → null（不猜）。 */
    private static Long matchByModelId(Map<String, Long> table, String modelId) {
        String suffix = ":" + modelId;
        Long found = null;
        for (Map.Entry<String, Long> entry : table.entrySet()) {
            if (!entry.getKey().endsWith(suffix)) continue;
            if (found != null && !found.equals(entry.getValue())) return null;
            found = entry.getValue();
        }
        return found;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long positiveLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
