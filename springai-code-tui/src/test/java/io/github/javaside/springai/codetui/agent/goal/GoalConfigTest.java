package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalConfigTest {

    @Test
    void defaultsWhenEnvUnset() {
        GoalConfig c = GoalConfig.from(name -> null);
        assertEquals(25, c.maxTurns());
        assertEquals(3, c.stalledLimit());
        assertEquals(5_000_000L, c.tokenBudget());
        assertEquals(3, c.turnGapSeconds());
        assertEquals(2, c.errorRetry());
        assertEquals(2, c.evalFailLimit());
        assertEquals(3, c.protocolFailLimit());
        assertEquals(60, c.evalTimeoutSeconds());
        assertEquals("", c.evaluatorModel());
    }

    @Test
    void parsesAndClampsEachVariable() {
        GoalConfig c = GoalConfig.from(name -> switch (name) {
            case "CODETUI_GOAL_MAX_TURNS" -> "10";
            case "CODETUI_GOAL_STALLED_LIMIT" -> "5";
            case "CODETUI_GOAL_TOKEN_BUDGET" -> "123456";
            case "CODETUI_GOAL_TURN_GAP_SECONDS" -> "0";
            case "CODETUI_GOAL_ERROR_RETRY" -> "1";
            case "CODETUI_GOAL_EVAL_FAIL_LIMIT" -> "4";
            case "CODETUI_GOAL_PROTOCOL_FAIL_LIMIT" -> "6";
            case "CODETUI_GOAL_EVAL_TIMEOUT_SECONDS" -> "30";
            case "CODETUI_GOAL_EVALUATOR_MODEL" -> "deepseek:deepseek-chat";
            default -> null;
        });
        assertEquals(10, c.maxTurns());
        assertEquals(5, c.stalledLimit());
        assertEquals(123456L, c.tokenBudget());
        assertEquals(0, c.turnGapSeconds());
        assertEquals(1, c.errorRetry());
        assertEquals(4, c.evalFailLimit());
        assertEquals(6, c.protocolFailLimit());
        assertEquals(30, c.evalTimeoutSeconds());
        assertEquals("deepseek:deepseek-chat", c.evaluatorModel());
    }

    @Test
    void clampsOutOfRangeIntoBounds() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "99999" : null);
        assertEquals(200, c.maxTurns());
        GoalConfig zero = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "0" : null);
        assertEquals(1, zero.maxTurns());
    }

    @Test
    void budgetZeroDisables() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_TOKEN_BUDGET".equals(name) ? "0" : null);
        assertEquals(0L, c.tokenBudget());   // 0=关闭，不钳下限
    }

    @Test
    void invalidNumberFallsBackToDefault() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "abc" : null);
        assertEquals(25, c.maxTurns());
    }

    @Test
    void rejectsValuesAboveUpperBound() {
        // maxTurns 201 > MAX_MAX_TURNS(200)
        assertThrows(IllegalArgumentException.class,
                () -> new GoalConfig(201, 3, 0, 3, 2, 2, 3, 60, ""));
        // evalTimeoutSeconds 301 > MAX_EVAL_TIMEOUT(300)，另一字段超界
        assertThrows(IllegalArgumentException.class,
                () -> new GoalConfig(25, 3, 0, 3, 2, 2, 3, 301, ""));
    }

    @Test
    void acceptsValuesAtUpperBound() {
        GoalConfig c = new GoalConfig(200, 10, 5_000_000L, 60, 10, 10, 10, 300, "");
        assertEquals(200, c.maxTurns());
        assertEquals(60, c.turnGapSeconds());
        assertEquals(300, c.evalTimeoutSeconds());
    }
}
