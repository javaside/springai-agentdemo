package io.github.javaside.springai.codetui.agent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves context-window sizes from explicit overrides, built-in metadata, then a conservative fallback. */
final class ModelContextWindows {

    static final long DEFAULT_UNKNOWN_WINDOW = 128_000L;

    private static final Map<String, Long> BUILT_INS = Map.ofEntries(
            Map.entry("openai:gpt-5.6-sol", 1_050_000L),
            Map.entry("openai:gpt-5.6-terra", 1_050_000L),
            Map.entry("openai:gpt-5.6-luna", 1_050_000L),
            Map.entry("deepseek:deepseek-v4-pro", 1_000_000L),
            Map.entry("deepseek:deepseek-v4-flash", 1_000_000L),
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

    static ModelContextWindows fromEnvironment() {
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

    long resolve(String providerId, String modelId) {
        String key = normalize(providerId + ":" + modelId);
        Long override = overrides.get(key);
        if (override != null) return override;
        return BUILT_INS.getOrDefault(key, unknownWindow);
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
