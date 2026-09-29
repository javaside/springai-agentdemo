package io.github.javaside.springai.codetui.agent.goal;

import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /goal 循环的可调参数（CODETUI_* env 解析 + 钳制）。
 *
 * <p>解析纪律照 {@code LlmTimeouts}：null/空白→默认；{@code Long.parseLong(raw.trim())} 解析后
 * 双向钳制 {@code Math.max(MIN, Math.min(MAX, v))}（超出上下界的巨值被钳到 MIN/MAX，而非报错）；
 * NumberFormatException→默认值 + warn。
 * env 读取经 {@link #from(Function)} 注入以便测试，不碰真实进程环境。
 */
public record GoalConfig(int maxTurns, int stalledLimit, long tokenBudget, int turnGapSeconds,
                         int errorRetry, int evalFailLimit, int protocolFailLimit,
                         int evalTimeoutSeconds, String evaluatorModel) {

    public static final String MAX_TURNS_ENV = "CODETUI_GOAL_MAX_TURNS";
    public static final String STALLED_LIMIT_ENV = "CODETUI_GOAL_STALLED_LIMIT";
    public static final String TOKEN_BUDGET_ENV = "CODETUI_GOAL_TOKEN_BUDGET";
    public static final String TURN_GAP_ENV = "CODETUI_GOAL_TURN_GAP_SECONDS";
    public static final String ERROR_RETRY_ENV = "CODETUI_GOAL_ERROR_RETRY";
    public static final String EVAL_FAIL_LIMIT_ENV = "CODETUI_GOAL_EVAL_FAIL_LIMIT";
    public static final String PROTOCOL_FAIL_LIMIT_ENV = "CODETUI_GOAL_PROTOCOL_FAIL_LIMIT";
    public static final String EVAL_TIMEOUT_ENV = "CODETUI_GOAL_EVAL_TIMEOUT_SECONDS";
    public static final String EVALUATOR_MODEL_ENV = "CODETUI_GOAL_EVALUATOR_MODEL";

    private static final Logger log = LoggerFactory.getLogger(GoalConfig.class);

    static final int DEFAULT_MAX_TURNS = 25, MIN_MAX_TURNS = 1, MAX_MAX_TURNS = 200;
    static final int DEFAULT_STALLED_LIMIT = 3, MIN_STALLED_LIMIT = 1, MAX_STALLED_LIMIT = 10;
    static final long DEFAULT_TOKEN_BUDGET = 5_000_000L, MAX_TOKEN_BUDGET = Long.MAX_VALUE; // 0=关
    static final int DEFAULT_TURN_GAP = 3, MIN_TURN_GAP = 0, MAX_TURN_GAP = 60;
    static final int DEFAULT_ERROR_RETRY = 2, MIN_ERROR_RETRY = 0, MAX_ERROR_RETRY = 10;
    static final int DEFAULT_EVAL_FAIL_LIMIT = 2, MIN_EVAL_FAIL_LIMIT = 1, MAX_EVAL_FAIL_LIMIT = 10;
    static final int DEFAULT_PROTOCOL_FAIL_LIMIT = 3, MIN_PROTOCOL_FAIL_LIMIT = 1, MAX_PROTOCOL_FAIL_LIMIT = 10;
    static final int DEFAULT_EVAL_TIMEOUT = 60, MIN_EVAL_TIMEOUT = 5, MAX_EVAL_TIMEOUT = 300;

    public GoalConfig {
        Objects.requireNonNull(evaluatorModel, "evaluatorModel");
        if (maxTurns < MIN_MAX_TURNS || maxTurns > MAX_MAX_TURNS
                || stalledLimit < MIN_STALLED_LIMIT || stalledLimit > MAX_STALLED_LIMIT
                || tokenBudget < 0
                || turnGapSeconds < MIN_TURN_GAP || turnGapSeconds > MAX_TURN_GAP
                || errorRetry < MIN_ERROR_RETRY || errorRetry > MAX_ERROR_RETRY
                || evalFailLimit < MIN_EVAL_FAIL_LIMIT || evalFailLimit > MAX_EVAL_FAIL_LIMIT
                || protocolFailLimit < MIN_PROTOCOL_FAIL_LIMIT || protocolFailLimit > MAX_PROTOCOL_FAIL_LIMIT
                || evalTimeoutSeconds < MIN_EVAL_TIMEOUT || evalTimeoutSeconds > MAX_EVAL_TIMEOUT) {
            throw new IllegalArgumentException("goal config 越界");
        }
    }

    public static GoalConfig fromEnv() { return from(System::getenv); }

    public static GoalConfig from(Function<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new GoalConfig(
                resolveInt(env.apply(MAX_TURNS_ENV), DEFAULT_MAX_TURNS, MIN_MAX_TURNS, MAX_MAX_TURNS),
                resolveInt(env.apply(STALLED_LIMIT_ENV), DEFAULT_STALLED_LIMIT, MIN_STALLED_LIMIT, MAX_STALLED_LIMIT),
                resolveLong(env.apply(TOKEN_BUDGET_ENV), DEFAULT_TOKEN_BUDGET, 0L, MAX_TOKEN_BUDGET),
                resolveInt(env.apply(TURN_GAP_ENV), DEFAULT_TURN_GAP, MIN_TURN_GAP, MAX_TURN_GAP),
                resolveInt(env.apply(ERROR_RETRY_ENV), DEFAULT_ERROR_RETRY, MIN_ERROR_RETRY, MAX_ERROR_RETRY),
                resolveInt(env.apply(EVAL_FAIL_LIMIT_ENV), DEFAULT_EVAL_FAIL_LIMIT, MIN_EVAL_FAIL_LIMIT, MAX_EVAL_FAIL_LIMIT),
                resolveInt(env.apply(PROTOCOL_FAIL_LIMIT_ENV), DEFAULT_PROTOCOL_FAIL_LIMIT, MIN_PROTOCOL_FAIL_LIMIT, MAX_PROTOCOL_FAIL_LIMIT),
                resolveInt(env.apply(EVAL_TIMEOUT_ENV), DEFAULT_EVAL_TIMEOUT, MIN_EVAL_TIMEOUT, MAX_EVAL_TIMEOUT),
                env.apply(EVALUATOR_MODEL_ENV) == null ? "" : env.apply(EVALUATOR_MODEL_ENV).trim());
    }

    private static int resolveInt(String raw, int def, int min, int max) {
        return (int) resolveLong(raw, def, min, max);
    }

    private static long resolveLong(String raw, long def, long min, long max) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Math.max(min, Math.min(max, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            log.warn("无效的 CODETUI_GOAL_* 值 '{}'，回退默认 {}", raw, def);
            return def;
        }
    }
}
