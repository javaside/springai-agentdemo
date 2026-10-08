package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** clearContext：换一个新的 sessionId（volatile 写），不依赖 chatClient/session/estimator，故全传 null。 */
class CodingAgentClearContextTest {

    @Test
    void clearContext_swapsSessionId_toNewNonEmptyValue() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        assertEquals("old-session", agent.sessionId(), "初始 id");

        agent.clearContext();

        String after = agent.sessionId();
        assertNotEquals("old-session", after, "clear 后应换新 id");
        assertNotEquals(null, after, "新 id 非空");
    }

    @Test
    void clearContext_resetsTodoReminder() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder reminder =
                new io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder(1);
        reminder.onControllerTodoWritten(java.util.List.of(
                new org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem(
                        "旧会话任务",
                        org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress,
                        "旧会话任务（进行中）")));
        agent.bindTodoReminder(reminder);

        agent.clearContext();

        assertNull(reminder.reminderOrNull(9L, "Bash"),
                "/clear 后快照须清空——旧清单不该吓唬从没写过它的新会话");
    }
}
