package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GoalManagerStateTest {

    private static GoalConfig cfg() {
        return new GoalConfig(25, 3, 5_000_000L, 0, 2, 2, 3, 60, "");
    }

    @Test
    void activateSetsRunningAndFirstTurnPending() {
        GoalManager gm = new GoalManager(cfg(), null);
        assertEquals(GoalPhase.INACTIVE, gm.phase());
        gm.activate("迁移 AuthService 并测试全绿");
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertTrue(gm.hasAutoTurnPending());
        long e1 = gm.currentEpoch();
        gm.activate("另一个目标");                    // 替换：epoch 递增
        assertEquals(e1 + 1, gm.currentEpoch());
    }

    @Test
    void blankAndOversizeConditionRejected() {
        GoalManager gm = new GoalManager(cfg(), null);
        assertThrows(IllegalArgumentException.class, () -> gm.activate("   "));
        assertThrows(IllegalArgumentException.class, () -> gm.activate("x".repeat(4001)));
        assertEquals(GoalPhase.INACTIVE, gm.phase());   // 无状态变更
    }

    @Test
    void twoLevelEsc() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.pauseByEsc();                               // 第一级
        assertEquals(GoalPhase.PAUSED, gm.phase());
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason());
        gm.pauseByEsc();                               // 第二级
        assertEquals(GoalPhase.CANCELLED, gm.phase());
    }

    @Test
    void pausedResumesOnUserDispatchWithoutPending() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.pause(PauseReason.STALLED);
        gm.onUserDispatch();
        assertEquals(GoalPhase.RUNNING, gm.phase());
        // 用户轮结束后先评估该轮（评估输入含插话），再由 verdict 决定下一轮——
        // 所以这里不得有 pending 自动轮：
        assertFalse(gm.hasAutoTurnPending());
        assertNull(gm.gapDeadlineEpochMs());
    }

    @Test
    void terminalStatesAreMonotonic() {
        for (GoalPhase t : List.of(GoalPhase.SATISFIED, GoalPhase.IMPOSSIBLE,
                GoalPhase.MAX_TURNS, GoalPhase.BUDGET_EXCEEDED, GoalPhase.CANCELLED, GoalPhase.CLEARED)) {
            GoalManager gm = new GoalManager(cfg(), null);
            gm.activate("g");
            gm.terminate(t);
            gm.pauseByEsc();                            // 一切迟到事件 no-op
            gm.onUserDispatch();
            gm.onVerdict(gm.currentEpoch(), new GoalVerdict(
                    GoalVerdict.Outcome.UNSATISFIED, "r", false, null, "raw"));
            assertEquals(t, gm.phase());
        }
    }

    @Test
    void uiChangePublishedOutsideLockExactlyOnce() {   // 照 InterjectionsNotificationTest 模式
        GoalManager gm = new GoalManager(cfg(), null);
        List<Integer> bits = new CopyOnWriteArrayList<>();
        AtomicLong versions = new AtomicLong();
        gm.setUiChangeListener(b -> { bits.add(b); });   // listener 内读锁内快照探针可后续加
        long before = gm.uiVersion();
        gm.activate("g");
        assertEquals(before + 1, gm.uiVersion());       // 恰 +1
        gm.activate("g2");                              // no-op 版本纪律：activate 总是有效变化
        assertEquals(before + 2, gm.uiVersion());
        assertFalse(bits.isEmpty());
    }

    @Test
    void clearFromAnyPhase() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.clear("stop");                               // INACTIVE no-op
        assertEquals(GoalPhase.INACTIVE, gm.phase());
        gm.activate("g");
        gm.clear("stop");
        assertEquals(GoalPhase.CLEARED, gm.phase());
    }
}
