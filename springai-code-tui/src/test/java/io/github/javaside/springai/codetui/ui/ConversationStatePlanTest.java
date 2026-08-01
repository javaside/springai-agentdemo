package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStatePlanTest {

    private static PlanRequest req(long turnId, List<PlanOutcome> sink) {
        return new PlanRequest(turnId, "# 计划", (o, f) -> sink.add(o));
    }

    @Test
    @DisplayName("当前回合的计划请求进模态队列，不立刻应答（等 UI 弹面板）")
    void currentTurnEnqueues() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = req(1L, sink);

        s.onPlanSubmitted(1L, r);

        assertSame(r, s.peekModal());
        assertTrue(sink.isEmpty(), "入队阶段不得应答——那会把用户的真实选择挤掉");
    }

    @Test
    @DisplayName("迟到请求（回合已切换）立刻 CANCEL，不弹面板")
    void staleTurnCancelled() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(2L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();

        s.onPlanSubmitted(1L, req(1L, sink));

        assertEquals(List.of(PlanOutcome.CANCEL), sink);
        assertNull(s.peekModal(), "迟到请求不该进队列");
    }

    @Test
    @DisplayName("取消回合会唤醒排在队列里的计划请求——否则工具线程永久 park")
    void cancelDrainsPlanRequests() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPlanSubmitted(1L, req(1L, sink));

        s.cancelCurrent();

        assertEquals(List.of(PlanOutcome.CANCEL), sink);
    }

    @Test
    @DisplayName("队满 → KEEP_PLANNING（不是 CANCEL：面板挤不该杀掉整个回合）+ 用户可见的一行提示")
    void queueFullKeepsPlanning() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            s.onPlanSubmitted(1L, req(1L, sink));
        }
        assertTrue(sink.isEmpty(), "填满阶段每个都该入队，不该有人被应答");

        s.onPlanSubmitted(1L, req(1L, sink));

        assertEquals(List.of(PlanOutcome.KEEP_PLANNING), sink);
        assertTrue(s.drainPending().stream()
                        .anyMatch(l -> l.kind() == ConversationState.OutputLine.Kind.ERROR
                                && l.text().contains("模态")),
                "队满溢出必须留下用户看得见的一行，否则用户以为模型自己改了主意");
    }
}
