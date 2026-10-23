package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * switchContext：/resume 与 /clear 共用的会话切换不变量。PermissionEngine/Interjections/GoalManager
 * 三项清理由「clearContext() 一行委托 switchContext(newId())」的结构保证（同体不漂移），此处断言可观察的三项。
 */
class CodingAgentSwitchContextTest {

    private static CodingAgent agentWithAccumulator(TokenUsageAccumulator acc) {
        // 23 参全参构造器（accumulator 是 final 字段、无 bind 方法，7 参桩路径硬编码 null）。
        // skills 必须传 List.of()——null 会在构造器 List.copyOf 处 NPE；其余依赖测试不触及，传 null。
        // 参数序以 CodingAgent 全参构造器签名为准（执行时对齐，断言语义不变）。
        return new CodingAgent(null, null, null, "old-session", new AtomicLong(), null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null, null, null,
                acc, 0L, null);
    }

    @Test
    void switchContext_swapsToTargetSessionId() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        agent.switchContext("20261023T000000-target1");
        assertEquals("20261023T000000-target1", agent.sessionId(), "切到指定 id 而非新 id");
    }

    @Test
    void switchContext_resetsTodoReminder() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        TodoStaleReminder reminder = new TodoStaleReminder(1);
        reminder.onControllerTodoWritten(List.of(new TodoItem("旧任务", Status.in_progress, "旧任务")));
        agent.bindTodoReminder(reminder);

        agent.switchContext("t2");

        assertNull(reminder.reminderOrNull(9L, "Bash"), "旧会话清单不吓唬新会话");
    }

    @Test
    void switchContext_resetsUsageAccumulator() {
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        // Usage 接口无静态工厂；先例见 TokenUsageAccumulatorTest（new DefaultUsage(...)）
        acc.record(new org.springframework.ai.chat.metadata.DefaultUsage(100, 20, 120, null, 0L, 0L));
        CodingAgent agent = agentWithAccumulator(acc);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TokenUsageAccumulator.Snapshot.empty(), acc.snapshot(), "前置：已记一笔用量");

        agent.switchContext("t3");

        assertEquals(TokenUsageAccumulator.Snapshot.empty(), acc.snapshot(), "换会话后用量从 0 重新累计");
    }

    @Test
    void clearContext_delegatesToSwitchContext_newIdGenerated() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        agent.clearContext();
        String after = agent.sessionId();
        assertTrue(after != null && !after.equals("old-session"), "clear 语义不变：换新 id");
    }
}
