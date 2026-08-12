package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResizeSettle} 的状态机契约：只在「变过 + 连续静默满窗口」时触发一次，
 * 触发后归于安静；中途再变则重新计时。
 */
class ResizeSettleTest {

    @Test
    @DisplayName("从未 changed：怎么喂 tick 都不触发")
    void neverChangedNeverFires() {
        ResizeSettle settle = new ResizeSettle(3);
        for (int i = 0; i < 10; i++) {
            assertFalse(settle.onTick());
        }
    }

    @Test
    @DisplayName("生产窗口 4 tick：前三帧不触发，第四帧触发")
    void productionWindowFiresOnFourthTick() {
        ResizeSettle settle = new ResizeSettle(4);
        settle.changed();
        assertFalse(settle.onTick());
        assertFalse(settle.onTick());
        assertFalse(settle.onTick());
        assertTrue(settle.onTick());
    }

    @Test
    @DisplayName("changed 后静默满窗口触发一次，之后归于安静")
    void firesOnceAfterQuietWindow() {
        ResizeSettle settle = new ResizeSettle(3);
        settle.changed();
        assertFalse(settle.onTick());   // quiet=1
        assertFalse(settle.onTick());   // quiet=2
        assertTrue(settle.onTick());    // quiet=3 → 触发
        // 触发后不再重复触发
        for (int i = 0; i < 5; i++) {
            assertFalse(settle.onTick());
        }
    }

    @Test
    @DisplayName("静默中途再 changed：重新计时（拖拽未停稳不触发）")
    void changedResetsQuietCounter() {
        ResizeSettle settle = new ResizeSettle(3);
        settle.changed();
        assertFalse(settle.onTick());   // quiet=1
        assertFalse(settle.onTick());   // quiet=2
        settle.changed();                        // 拖拽又动了
        assertFalse(settle.onTick());   // quiet=1
        assertFalse(settle.onTick());   // quiet=2
        assertTrue(settle.onTick());    // quiet=3 → 这次才触发
    }

    @Test
    @DisplayName("连报多次 changed 只欠一次重放")
    void multipleChangesCoalesce() {
        ResizeSettle settle = new ResizeSettle(2);
        settle.changed();
        settle.changed();
        settle.changed();
        assertFalse(settle.onTick());
        assertTrue(settle.onTick());
        assertFalse(settle.onTick());   // 只触发一次
    }

    @Test
    @DisplayName("触发后再次 changed 可再次触发（每轮 resize 各整理一次）")
    void refiresAfterNewChange() {
        ResizeSettle settle = new ResizeSettle(1);
        settle.changed();
        assertTrue(settle.onTick());
        settle.changed();
        assertTrue(settle.onTick());
    }

    @Test
    @DisplayName("quietTicks 下限钳到 1：0/负数也至少静默一帧")
    void quietTicksClampedToOne() {
        ResizeSettle settle = new ResizeSettle(0);
        settle.changed();
        assertTrue(settle.onTick());

        ResizeSettle negative = new ResizeSettle(-5);
        negative.changed();
        assertTrue(negative.onTick());
    }
}
