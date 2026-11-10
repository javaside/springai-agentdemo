package io.github.javaside.springai.tamboui.demo;

/**
 * 第 7 课「行数目标」的进度判定（纯逻辑）：实际行数对照目标行数，给出达标状态与展示文案。
 * 目标值由目标面板的 + / - 调整（onKeyEvent 惯例练习），驱动统计面板的实时对照——
 * 交互有真实用途，而不是一个孤立变动的数字。
 */
public record GoalProgress(boolean reached, String label) {

    public static GoalProgress of(int actualLines, int targetLines) {
        if (actualLines >= targetLines) {
            return new GoalProgress(true, "✓ 已达标");
        }
        return new GoalProgress(false, "还差 " + (targetLines - actualLines) + " 行");
    }
}
