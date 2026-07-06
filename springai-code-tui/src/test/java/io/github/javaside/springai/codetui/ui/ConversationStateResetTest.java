package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** resetForNewSession：清空 todo / 排队消息 / notice（面板与状态回到刚启动）。 */
class ConversationStateResetTest {

    @Test
    void resetForNewSession_clearsTodoQueueAndNotice() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        s.onTodoUpdated(1, List.of("[ ] 任务一", "[ ] 任务二"));
        s.enqueue("排队消息", null);
        s.setNotice("某提示");
        assertEquals(2, s.todoSnapshot().size(), "前置：todo 有 2 条");
        assertEquals(1, s.queuedCount(), "前置：排队 1 条");

        s.resetForNewSession();

        assertTrue(s.todoSnapshot().isEmpty(), "todo 应清空");
        assertEquals(0, s.queuedCount(), "排队应清空");
        assertEquals("", s.notice(), "notice 应清空");
    }
}
