package io.github.javaside.springai.codetui.agent.thinking;

import java.util.List;
import java.util.Objects;

public record ThinkingCapabilities(
        boolean configurable,
        boolean supportsDisable,
        ThinkingStrengthKind strengthKind,
        List<String> effortValues,
        Integer minBudget,
        Integer maxBudget) {

    public ThinkingCapabilities {
        Objects.requireNonNull(strengthKind, "strengthKind");
        effortValues = effortValues == null ? List.of() : List.copyOf(effortValues);
        if (strengthKind != ThinkingStrengthKind.EFFORT && !effortValues.isEmpty()) {
            throw new IllegalArgumentException("非 effort 能力不能有 effortValues");
        }
        if (strengthKind != ThinkingStrengthKind.TOKEN_BUDGET && (minBudget != null || maxBudget != null)) {
            throw new IllegalArgumentException("非 token budget 能力不能有预算范围");
        }
        if (minBudget != null && minBudget <= 0) {
            throw new IllegalArgumentException("minBudget 必须为正整数");
        }
        if (maxBudget != null && maxBudget <= 0) {
            throw new IllegalArgumentException("maxBudget 必须为正整数");
        }
        if (minBudget != null && maxBudget != null && minBudget > maxBudget) {
            throw new IllegalArgumentException("预算范围无效");
        }
    }

    public static ThinkingCapabilities unsupported() {
        return new ThinkingCapabilities(false, false, ThinkingStrengthKind.NONE, List.of(), null, null);
    }

    public static ThinkingCapabilities toggle(boolean supportsDisable) {
        return new ThinkingCapabilities(true, supportsDisable, ThinkingStrengthKind.NONE, List.of(), null, null);
    }

    public static ThinkingCapabilities effort(boolean supportsDisable, List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("effortValues 不能为空");
        }
        return new ThinkingCapabilities(true, supportsDisable, ThinkingStrengthKind.EFFORT, values, null, null);
    }

    public static ThinkingCapabilities tokenBudget(boolean supportsDisable, int minBudget, Integer maxBudget) {
        return new ThinkingCapabilities(true, supportsDisable, ThinkingStrengthKind.TOKEN_BUDGET,
                List.of(), minBudget, maxBudget);
    }

    public void validate(ThinkingConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.mode() == ThinkingMode.DEFAULT) {
            if (config.effort() != null || config.thinkingBudget() != null) {
                throw new IllegalArgumentException("DEFAULT 不能携带思考强度");
            }
            return;
        }
        if (!configurable) {
            throw new IllegalArgumentException("当前模型不可配置思考模式");
        }
        if (config.mode() == ThinkingMode.DISABLED) {
            if (!supportsDisable) {
                throw new IllegalArgumentException("当前模型不支持关闭思考");
            }
            return;
        }
        if (config.effort() != null) {
            if (strengthKind != ThinkingStrengthKind.EFFORT || !effortValues.contains(config.effort())) {
                throw new IllegalArgumentException("当前模型不支持 effort: " + config.effort());
            }
            return;
        }
        if (config.thinkingBudget() != null) {
            if (strengthKind != ThinkingStrengthKind.TOKEN_BUDGET) {
                throw new IllegalArgumentException("当前模型不支持 token budget");
            }
            if (minBudget != null && config.thinkingBudget() < minBudget) {
                throw new IllegalArgumentException("thinkingBudget 小于最小值 " + minBudget);
            }
            if (maxBudget != null && config.thinkingBudget() > maxBudget) {
                throw new IllegalArgumentException("thinkingBudget 大于最大值 " + maxBudget);
            }
            return;
        }
        if (strengthKind != ThinkingStrengthKind.NONE) {
            throw new IllegalArgumentException("当前模型必须设置思考强度");
        }
    }

    public String summary(ThinkingConfig config) {
        validate(config);
        return switch (config.mode()) {
            case DEFAULT -> "默认";
            case DISABLED -> "关闭";
            case ENABLED -> config.effort() != null ? config.effort()
                    : config.thinkingBudget() != null ? config.thinkingBudget() + " tokens" : "开启";
        };
    }
}
