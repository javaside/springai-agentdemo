package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「取消后再过滤」回归（行内滚动模型）：
 * turn=1 进行中 → cancelCurrent() → turn=1 的所有迟到事件全部忽略（不进 pending/streaming）；
 * 随后 onTurnStarted(2) → turn=2 事件正常定稿进 pending。
 */
class AgentListenerCancelTest {

    @Test
    void cancelThenFilter_turn1Ignored_turn2Recorded() {
        ConversationState impl = new ConversationState();
        AgentListener listener = impl;   // 以纯 Java 接缝类型持有

        listener.onTurnStarted(1L);
        listener.onUserMessage(1L, "q1");
        listener.onAssistantToken(1L, "partial");
        assertFalse(impl.isIdle(), "turn=1 进行中");

        impl.cancelCurrent();
        assertTrue(impl.isIdle(), "取消后回 IDLE");
        List<String> afterCancel = impl.drainPending();     // 应含 q1 与已产出的 partial（定稿）
        assertTrue(afterCancel.stream().anyMatch(l -> l.contains("q1")), "用户消息已定稿");
        assertTrue(afterCancel.contains("partial"), "取消把已产出部分定稿");

        // turn=1 的迟到事件全部忽略
        listener.onAssistantToken(1L, "MORE");
        listener.onToolStarted(1L, "read", "x");
        listener.onToolFinished(1L, "read", "y", true);
        listener.onTodoUpdated(1L, List.of("stale"));
        listener.onTurnComplete(1L);
        assertTrue(impl.drainPending().isEmpty(), "取消后 turn=1 迟到事件不产生任何输出");
        assertEquals("", impl.streaming(), "无迟到在建内容");
        assertTrue(impl.isIdle(), "迟到 onTurnComplete 不改变状态");

        // turn=2 正常
        listener.onTurnStarted(2L);
        assertFalse(impl.isIdle(), "turn=2 进行中");
        listener.onUserMessage(2L, "q2");
        listener.onAssistantToken(2L, "answer");
        listener.onToolStarted(2L, "write", "f");
        assertEquals(ConversationState.Status.RUNNING_TOOL, impl.status());
        listener.onToolFinished(2L, "write", "done", true);
        assertEquals(ConversationState.Status.THINKING, impl.status());
        listener.onTodoUpdated(2L, List.of("fresh"));
        listener.onTurnComplete(2L);
        assertTrue(impl.isIdle(), "turn=2 完成回 IDLE");

        List<String> t2 = impl.drainPending();
        assertTrue(t2.stream().anyMatch(l -> l.contains("q2")), "turn=2 用户消息");
        assertTrue(t2.contains("answer"), "turn=2 助手行定稿");
        assertTrue(t2.stream().anyMatch(l -> l.equals("✓ write")), "turn=2 工具完成");
        assertTrue(t2.stream().anyMatch(l -> l.contains("fresh")), "turn=2 todo");
        assertTrue(t2.stream().noneMatch(l -> l.contains("MORE") || l.contains("stale")),
                "无 turn=1 迟到内容混入");
    }
}
