package io.github.javaside.springai.tamboui.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 7 课「行数目标」的进度判定（纯逻辑）：+ / - 调整的目标值驱动统计面板的达标对照——
 * 让 onKeyEvent 惯例的练习有真实用途（此前数字只变不做任何事，用户实报「有什么用呢」）。
 */
class GoalProgressTest {

    @Test
    void reachedWhenActualMeetsTarget() {
        GoalProgress g = GoalProgress.of(3, 3);
        assertTrue(g.reached());
        assertEquals("✓ 已达标", g.label());
    }

    @Test
    void reachedWhenActualExceedsTarget() {
        assertTrue(GoalProgress.of(5, 3).reached());
    }

    @Test
    void notReachedShowsRemainingLines() {
        GoalProgress g = GoalProgress.of(1, 4);
        assertFalse(g.reached());
        assertEquals("还差 3 行", g.label());
    }

    @Test
    void zeroTargetIsTriviallyReached() {
        assertTrue(GoalProgress.of(0, 0).reached());
    }
}
