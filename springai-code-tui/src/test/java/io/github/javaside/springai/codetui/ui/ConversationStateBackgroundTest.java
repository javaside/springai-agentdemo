package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStateBackgroundTest {

    private static ConversationState started(long turnId) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(turnId);
        return s;
    }

    @Test
    void backgroundStartAddsPanelRowAndScrollbackLine() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查登录失败");

        assertEquals(1, s.backgroundTasks().size());
        assertEquals("explore", s.backgroundTasks().get(0).agentName());
        assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("task_ab12")),
                "启动要在 scrollback 留一行，否则用户不知道派出去了");
    }

    @Test
    void backgroundTaskSurvivesNewTurn() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.onTurnStarted(2L);

        assertEquals(1, s.backgroundTasks().size(),
                "后台任务跨回合存活——onTurnStarted 清空的是 ⟐ 面板，不是 ⏱ 面板");
    }

    @Test
    void backgroundEventsAreNotDroppedByLateFilter() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.cancelCurrent();                              // acceptingTurnId = -1，前台事件此后全被丢弃
        s.onBackgroundTaskFinished("task_ab12", "结论", true);

        assertEquals(ConversationState.BackgroundStatus.DONE, s.backgroundTasks().get(0).status(),
                "后台事件不带 turnId，绝不能被迟到过滤丢弃");
    }

    @Test
    void backgroundToolEventUpdatesPanelButNeverEntersScrollback() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.drainPending();                               // 清掉启动行

        s.onToolStarted(-1L, "task_ab12", "Grep", "{\"pattern\":\"x\"}");

        assertEquals("Grep", s.backgroundTasks().get(0).currentTool());
        assertTrue(s.drainPending().isEmpty(),
                "后台任务的工具行绝不能插进你与主 agent 的对话里");
    }

    /**
     * 后台任务的 turnId 恒为 -1，而 acceptingTurnId 在<b>空闲/被取消时也是 -1</b>——
     * 此刻迟到过滤挡不住任何东西，唯一拦住工具行进 scrollback 的就是那句前置 return。
     * 上一个用例在回合活跃时跑，恰好被迟到过滤顺手挡了，杀不掉「删掉 return」这个变异。
     */
    @Test
    void backgroundToolEventStaysOutOfScrollbackEvenWhenIdle() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.cancelCurrent();                              // acceptingTurnId 复位成 -1，与后台的 turnId 相同
        s.drainPending();

        s.onToolStarted(-1L, "task_ab12", "Grep", "{}");
        s.onToolFinished(-1L, "task_ab12", "Grep", "out", false);   // 失败态：前台路径此时会出告警行

        assertEquals("Grep", s.backgroundTasks().get(0).currentTool());
        assertTrue(s.drainPending().isEmpty(),
                "空闲时迟到过滤形同虚设，后台工具行只能靠前置分流 return 拦住");
    }

    /**
     * {@code /clear} 只清 ⏱ 镜像，任务线程还在跑（{@code shutdownNow} 只是 interrupt，卡在 HTTP 上的
     * 线程照跑）。此后它的工具事件带着 taskId 回来，镜像里已经找不到 → 退回 turnId 迟到过滤，
     * 而 Esc 取消过的空闲态 acceptingTurnId 恰好也是 -1，过滤放行 → 旧任务的工具行漏进新会话。
     */
    @Test
    void backgroundToolEventAfterClearNeverEntersNewSession() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.cancelCurrent();                              // Esc：acceptingTurnId 复位成 -1
        s.resetForNewSession();                         // /clear：⏱ 镜像被清空，线程仍在跑
        s.drainPending();

        s.onToolStarted(-1L, "task_ab12", "Grep", "{}");
        s.onToolFinished(-1L, "task_ab12", "Grep", "out", false);

        assertTrue(s.drainPending().isEmpty(),
                "/clear 后仍在跑的旧任务，其工具行绝不能漏进新会话的对话里");
    }

    /** 两份镜像（⏱ 后台 / ⟐ 前台子 agent）都没有的 taskId：来路不明，不进 scrollback。 */
    @Test
    void toolEventWithUnknownTaskIdIsDropped() {
        ConversationState s = started(1L);

        s.onToolStarted(1L, "task_ghost", "Grep", "{}");
        s.onToolFinished(1L, "task_ghost", "Grep", "out", false);

        assertTrue(s.drainPending().isEmpty(),
                "镜像登记一定早于同一任务的工具事件，找不到就只可能是迟到或已清空");
    }

    @Test
    void foregroundSubagentToolEventStillEntersScrollback() {
        ConversationState s = started(1L);
        s.onSubagentStarted(1L, "task_front", "explore", "前台调查");
        s.drainPending();

        s.onToolStarted(1L, "task_front", "Grep", "{}");

        assertFalse(s.drainPending().isEmpty(), "前台子 agent 的工具行行为必须一字不变");
    }

    @Test
    void finishAddsScrollbackLineAndMarksStatus() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.drainPending();

        s.onBackgroundTaskFinished("task_ab12", "结论正文", true);

        List<ConversationState.OutputLine> lines = s.drainPending();
        assertTrue(lines.stream().anyMatch(l -> l.text().contains("task_ab12")));
        assertEquals(ConversationState.BackgroundStatus.DONE, s.backgroundTasks().get(0).status());
    }

    @Test
    void failedFinishIsMarkedAsFailure() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_cd34", "bash", "跑测试");
        s.onBackgroundTaskFinished("task_cd34", "连接被重置", false);
        assertEquals(ConversationState.BackgroundStatus.FAILED, s.backgroundTasks().get(0).status());
    }

    @Test
    void multiLineDescriptionIsCollapsedToOnePhysicalLine() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "第一行\n第二行\n第三行");
        for (ConversationState.OutputLine l : s.drainPending()) {
            assertFalse(l.text().contains("\n"),
                    "一个 OutputLine = 一个物理行，含换行会被 println 塌成一行截断");
        }
    }

    @Test
    void resetForNewSessionClearsBackgroundPanel() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.resetForNewSession();
        assertTrue(s.backgroundTasks().isEmpty(), "/clear 要连 ⏱ 面板一起清");
    }
}
