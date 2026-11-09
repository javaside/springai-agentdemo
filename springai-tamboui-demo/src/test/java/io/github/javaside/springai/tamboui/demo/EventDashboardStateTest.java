package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 4 课状态机测试：事件只改状态，返回「是否需要重绘」。
 *
 * <p>这是 TUI 交互的全部骨架：KeyEvent/进帧回调 → 状态迁移 →（需要时）请求重绘。
 * 状态机纯逻辑化后可脱离终端单测。</p>
 */
class EventDashboardStateTest {

    @Test
    void jAdvancesSelectionAndRequestsRedraw() {
        EventDashboardState state = new EventDashboardState();
        assertTrue(state.onKey(KeyEvent.ofChar('j')));
        assertEquals(1, state.lessonIndex());
        assertTrue(state.onKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(2, state.lessonIndex());
    }

    @Test
    void kMovesBackAndClampsAtZero() {
        EventDashboardState state = new EventDashboardState();
        state.onKey(KeyEvent.ofChar('j'));
        state.onKey(KeyEvent.ofChar('j'));
        assertTrue(state.onKey(KeyEvent.ofChar('k')));
        assertEquals(1, state.lessonIndex());
        state.onKey(KeyEvent.ofKey(KeyCode.UP));
        state.onKey(KeyEvent.ofKey(KeyCode.UP));
        state.onKey(KeyEvent.ofKey(KeyCode.UP));
        assertEquals(0, state.lessonIndex(), "选到第一条后应钳在 0");
    }

    @Test
    void selectionClampsAtLastLesson() {
        EventDashboardState state = new EventDashboardState();
        for (int i = 0; i < 12; i++) {
            state.onKey(KeyEvent.ofChar('j'));
        }
        assertEquals(5, state.lessonIndex(), "共 6 课，选到最后一条后应钳住");
    }

    @Test
    void uninterestingKeyDoesNotRequestRedraw() {
        EventDashboardState state = new EventDashboardState();
        state.onKey(KeyEvent.ofChar('j'));   // 先产生一个有意义的 lastKey
        String before = state.lastKey();
        assertFalse(state.onKey(KeyEvent.ofChar('x')));
        // 纪律自洽：无关键既不请求重绘，也不改动任何状态（否则「返回 false = 无变化」名不副实）
        assertEquals(before, state.lastKey(), "无关键不得改动 lastKey");
    }

    @Test
    void lastKeyIsRecordedForDisplay() {
        EventDashboardState state = new EventDashboardState();
        state.onKey(KeyEvent.ofChar('j'));
        assertTrue(state.lastKey().contains("j"));
    }

    @Test
    void tickAdvancesGaugeAndWraps() {
        EventDashboardState state = new EventDashboardState();
        assertEquals(62, state.gaugePercent());
        state.onTick();
        assertEquals(63, state.gaugePercent());
        EventDashboardState nearEnd = new EventDashboardState();
        nearEnd.setGaugeForTest(100);
        nearEnd.onTick();
        assertEquals(0, nearEnd.gaugePercent(), "到 100 后应回到 0");
    }
}
