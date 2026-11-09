package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 第 4 课的交互状态机（纯逻辑、可单测）：事件只改状态并回答「是否需要重绘」。
 *
 * <p>键盘 j/k（或 ↓/↑）移动课程选中项，TickEvent 每到一次 Gauge 前进一格并回绕——
 * Tick 在 TamboUI 里的语义只是「请求重绘的脉冲」，不带时间；要按真实时间做动画
 * 应自己计时（本课为教学简洁按 tick 计数）。</p>
 */
public final class EventDashboardState {

    public static final int LESSON_COUNT = 6;
    private static final int GAUGE_MAX = 100;

    private int lessonIndex;
    private int gaugePercent = 62;
    private String lastKey = "（按 j/k 或方向键试试）";

    /**
     * 处理一个按键。
     *
     * @return 状态是否变化（变化才请求重绘——这是「按需重绘」的纪律）
     */
    public boolean onKey(KeyEvent key) {
        lastKey = key.toString();
        if (key.isChar('j') || key.isKey(KeyCode.DOWN)) {
            lessonIndex = Math.min(LESSON_COUNT - 1, lessonIndex + 1);
            return true;
        }
        if (key.isChar('k') || key.isKey(KeyCode.UP)) {
            lessonIndex = Math.max(0, lessonIndex - 1);
            return true;
        }
        return false;
    }

    /** 每个 TickEvent 前进一格，到 100 回 0（动画的最小形态）。 */
    public void onTick() {
        gaugePercent = (gaugePercent + 1) % (GAUGE_MAX + 1);
    }

    public int lessonIndex() {
        return lessonIndex;
    }

    public int gaugePercent() {
        return gaugePercent;
    }

    public String lastKey() {
        return lastKey;
    }

    /** 仅供测试构造边界（100 → 0 回绕用例）。 */
    void setGaugeForTest(int value) {
        this.gaugePercent = value;
    }
}
