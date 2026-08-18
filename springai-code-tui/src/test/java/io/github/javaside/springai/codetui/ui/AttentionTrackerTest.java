package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AttentionTracker} 边沿检测状态机的纯函数测试：只断言「哪一拍动作」，不碰 IO。
 * 真实 BEL/标题写入由 pty 冒烟钉（反射 backend 路径单测够不着）。
 */
class AttentionTrackerTest {

    @Test
    @DisplayName("空闲平态：不动作")
    void idleStaysQuiet() {
        AttentionTracker t = new AttentionTracker();
        assertEquals(AttentionTracker.Action.NONE, t.advance(false, false, false));
        assertEquals(AttentionTracker.Action.NONE, t.advance(false, false, false));
        assertEquals(AttentionTracker.Phase.IDLE, t.phase());
    }

    @Test
    @DisplayName("模态出现：ALERT_WAITING 恰一次；持续等待不重响")
    void modalArrivalFiresOnce() {
        AttentionTracker t = new AttentionTracker();
        assertEquals(AttentionTracker.Action.ALERT_WAITING, t.advance(true, true, false));
        assertEquals(AttentionTracker.Action.NONE, t.advance(true, true, false));
        assertEquals(AttentionTracker.Action.NONE, t.advance(true, true, false));
        assertEquals(AttentionTracker.Phase.WAITING_USER, t.phase());
    }

    @Test
    @DisplayName("回合完成：忙→闲下降沿 ALERT_DONE；DONE 是保持态，下一拍不重响")
    void busyToIdleFiresDoneOnce() {
        AttentionTracker t = new AttentionTracker();
        assertEquals(AttentionTracker.Action.NONE, t.advance(false, true, false));    // IDLE→BUSY 静默
        assertEquals(AttentionTracker.Action.ALERT_DONE, t.advance(false, false, false)); // BUSY→闲
        assertEquals(AttentionTracker.Action.NONE, t.advance(false, false, false));  // DONE 保持
        assertEquals(AttentionTracker.Phase.DONE, t.phase());
    }

    @Test
    @DisplayName("用户按键收场 DONE：下一拍 RESTORE 恢复默认标题，不响铃")
    void userActedClearsDone() {
        AttentionTracker t = new AttentionTracker();
        t.advance(false, true, false);
        t.advance(false, false, false);
        assertTrue(t.showingAttention());
        t.userActed();
        assertEquals(AttentionTracker.Action.RESTORE, t.advance(false, false, false));
        assertFalse(t.showingAttention());
        assertEquals(AttentionTracker.Action.NONE, t.advance(false, false, false));
    }

    @Test
    @DisplayName("用户 Esc 取消的忙→闲不响「已完成」：降级为 RESTORE")
    void userCancelledEdgeSuppressed() {
        AttentionTracker t = new AttentionTracker();
        t.advance(false, true, false);
        AttentionTracker.Action a = t.advance(false, false, true);
        assertEquals(AttentionTracker.Action.RESTORE, a, "取消路径不响铃，只恢复标题");
        assertEquals(AttentionTracker.Phase.IDLE, t.phase());
    }

    @Test
    @DisplayName("答完模态、活继续跑：不响铃但恢复标题（等待提示不许挂在跑动中的 tab 上）")
    void answeredModalResumesBusy() {
        AttentionTracker t = new AttentionTracker();
        t.advance(true, true, false);                          // ALERT_WAITING
        assertEquals(AttentionTracker.Action.RESTORE, t.advance(false, true, false));
        assertEquals(AttentionTracker.Phase.BUSY, t.phase());
        // 这轮跑完仍要响 DONE
        assertEquals(AttentionTracker.Action.ALERT_DONE, t.advance(false, false, false));
    }

    @Test
    @DisplayName("IDLE 直接来模态（无忙期）：照样 ALERT_WAITING")
    void modalFromIdle() {
        AttentionTracker t = new AttentionTracker();
        assertEquals(AttentionTracker.Action.ALERT_WAITING, t.advance(true, false, false));
        // 模态被 Esc 取消（cancelTurnFor → cancelCurrent）：空闲 + cancelled → 不响 DONE、恢复标题
        assertEquals(AttentionTracker.Action.RESTORE, t.advance(false, false, true));
        assertEquals(AttentionTracker.Phase.IDLE, t.phase());
    }
}
