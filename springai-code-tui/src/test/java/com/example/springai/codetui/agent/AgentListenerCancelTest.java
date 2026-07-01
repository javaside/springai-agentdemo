package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「取消后再过滤」回归：
 * turn=1 进行中 → cancelCurrent() → turn=1 的所有迟到事件全部忽略；
 * 随后 onTurnStarted(2) → turn=2 事件正常记录。
 */
class AgentListenerCancelTest {

    @Test
    void cancelThenFilter_turn1Ignored_turn2Recorded() {
        // ConversationState 是 AgentListener 的实现，用接缝类型持有以证明是纯 Java 接缝
        ConversationState impl = new ConversationState();
        AgentListener listener = impl;

        // turn=1 进行中
        listener.onTurnStarted(1L);
        listener.onUserMessage(1L, "q1");
        listener.onAssistantToken(1L, "partial");
        assertFalse(impl.isIdle(), "turn=1 进行中");

        // 取消
        impl.cancelCurrent();
        assertTrue(impl.isIdle(), "取消后回 IDLE");

        int sizeAfterCancel = impl.transcriptSnapshot().size();

        // turn=1 的迟到事件全部被忽略
        listener.onAssistantToken(1L, "MORE");
        listener.onToolStarted(1L, "read", "x");
        listener.onToolFinished(1L, "read", "y", true);
        listener.onTodoUpdated(1L, List.of("stale"));
        listener.onTurnComplete(1L);

        assertEquals(sizeAfterCancel, impl.transcriptSnapshot().size(),
                "取消后 turn=1 的迟到事件不得改变 transcript");
        assertTrue(impl.transcriptSnapshot().stream().noneMatch(l -> l.contains("MORE")),
                "迟到 token 丢弃");
        assertTrue(impl.transcriptSnapshot().stream().noneMatch(l -> l.contains("read")),
                "迟到工具事件丢弃");
        assertTrue(impl.todoSnapshot().stream().noneMatch(l -> l.contains("stale")),
                "迟到 todo 丢弃");
        assertTrue(impl.isIdle(), "turn=1 的迟到 onTurnComplete 不应改变状态语义（仍 IDLE）");

        // turn=2 正常记录
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

        List<String> snap = impl.transcriptSnapshot();
        assertTrue(snap.stream().anyMatch(l -> l.equals("你> q2")), "turn=2 用户消息记录");
        assertTrue(snap.stream().anyMatch(l -> l.equals("AI> answer")), "turn=2 助手行记录");
        assertEquals(List.of("fresh"), impl.todoSnapshot(), "turn=2 todo 记录");
    }
}
