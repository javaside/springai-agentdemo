package io.github.javaside.springai.codetui.agent.thinking;

import java.util.Objects;

public record ThinkingConfig(ThinkingMode mode, String effort, Integer thinkingBudget) {

    public ThinkingConfig {
        Objects.requireNonNull(mode, "mode");
        if (mode != ThinkingMode.ENABLED && (effort != null || thinkingBudget != null)) {
            throw new IllegalArgumentException("只有 ENABLED 可携带思考强度");
        }
        if (effort != null && thinkingBudget != null) {
            throw new IllegalArgumentException("effort 与 thinkingBudget 不能同时设置");
        }
        if (effort != null && effort.isBlank()) {
            throw new IllegalArgumentException("effort 不能为空");
        }
        if (thinkingBudget != null && thinkingBudget <= 0) {
            throw new IllegalArgumentException("thinkingBudget 必须为正整数");
        }
    }

    public static ThinkingConfig defaults() {
        return new ThinkingConfig(ThinkingMode.DEFAULT, null, null);
    }

    public static ThinkingConfig disabled() {
        return new ThinkingConfig(ThinkingMode.DISABLED, null, null);
    }

    public static ThinkingConfig enabledWithoutStrength() {
        return new ThinkingConfig(ThinkingMode.ENABLED, null, null);
    }

    public static ThinkingConfig enabledEffort(String value) {
        return new ThinkingConfig(ThinkingMode.ENABLED, value, null);
    }

    public static ThinkingConfig enabledBudget(int value) {
        return new ThinkingConfig(ThinkingMode.ENABLED, null, value);
    }
}
