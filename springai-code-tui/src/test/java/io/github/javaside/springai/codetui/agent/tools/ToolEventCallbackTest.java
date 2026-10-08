package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/**
 * 钉死 ToolEventCallback 的三件事：
 * 1) 正常路径按 onToolStarted → onToolFinished(ok=true) 顺序发事件，返回值透传；
 * 2) 异常路径发 onToolFinished(ok=false, 消息=异常信息) 且异常照常抛出；
 * 3) turnId 缺失（ctx 为 null）时兜底为 -1，不 NPE；
 * 4) ThreadLocal：委托 call() 期间 currentTurnId() 能读到「正在执行」的 turnId
 *    （证明 TodoWriteTool.todoEventHandler 同线程同步触发时能拿到正确的 turnId），
 *    call() 返回后恢复为调用前的值（顶层为 -1），保证嵌套/清理安全。
 */
class ToolEventCallbackTest {

    /** 手写录制型 AgentListener，不用 mock 框架。 */
    private static final class RecordingListener implements AgentListener {
        final List<String> events = new ArrayList<>();

        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }

        @Override
        public void onToolStarted(long turnId, String toolName, String input) {
            events.add("started:" + turnId + ":" + toolName + ":" + input);
        }

        @Override
        public void onToolFinished(long turnId, String toolName, String output, boolean ok) {
            events.add("finished:" + turnId + ":" + toolName + ":" + output + ":" + ok);
        }

        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }
    }

    private static ToolDefinition readToolDefinition() {
        return ToolDefinition.builder().name("read").description("d").inputSchema("{}").build();
    }

    @Test
    void normalPath_startedThenFinished_returnValuePassedThrough() {
        RecordingListener listener = new RecordingListener();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return readToolDefinition(); }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) { return "OUT:" + toolInput; }
        };
        ToolEventCallback cb = new ToolEventCallback(delegate, listener);

        ToolContext ctx = new ToolContext(Map.of("turnId", 7L));
        String result = cb.call("in", ctx);

        assertEquals("OUT:in", result, "返回值应透传");
        assertEquals(List.of(
                "started:7:read:in",
                "finished:7:read:OUT:in:true"
        ), listener.events, "应先 started 后 finished，且顺序正确");
    }

    @Test
    void exceptionPath_finishedWithFailureRecorded_andRethrown() {
        RecordingListener listener = new RecordingListener();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return readToolDefinition(); }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) {
                throw new RuntimeException("boom");
            }
        };
        ToolEventCallback cb = new ToolEventCallback(delegate, listener);
        ToolContext ctx = new ToolContext(Map.of("turnId", 7L));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> cb.call("in", ctx));

        assertEquals("boom", ex.getMessage());
        assertEquals(List.of(
                "started:7:read:in",
                "finished:7:read:boom:false"
        ), listener.events, "异常也应记录 finished(ok=false)，消息为异常信息");
    }

    @Test
    void missingTurnId_defaultsToMinusOne_noNpe() {
        RecordingListener listener = new RecordingListener();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return readToolDefinition(); }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) { return "OUT"; }
        };
        ToolEventCallback cb = new ToolEventCallback(delegate, listener);

        String result = cb.call("in", null);

        assertEquals("OUT", result);
        assertEquals(List.of(
                "started:-1:read:in",
                "finished:-1:read:OUT:true"
        ), listener.events, "turnId 缺失应兜底为 -1，不应 NPE");
    }

    @Test
    void threadLocal_exposesCurrentTurnIdDuringCall_andRestoresAfter() {
        RecordingListener listener = new RecordingListener();
        List<Long> observedDuringCall = new ArrayList<>();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return readToolDefinition(); }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) {
                // 模拟 TodoWriteTool.todoEventHandler 在同线程同步触发时读取当前执行回合的 turnId
                observedDuringCall.add(ToolEventCallback.currentTurnId());
                return "OUT";
            }
        };
        ToolEventCallback cb = new ToolEventCallback(delegate, listener);

        assertEquals(-1L, ToolEventCallback.currentTurnId(), "顶层调用前应为 -1");

        ToolContext ctx = new ToolContext(Map.of("turnId", 7L));
        cb.call("in", ctx);

        assertEquals(List.of(7L), observedDuringCall, "call() 执行期间 ThreadLocal 应暴露正在执行的 turnId");
        assertEquals(-1L, ToolEventCallback.currentTurnId(), "call() 返回后应恢复为调用前的值");
    }

    @Test
    void passesTaskIdFromContextToListener() {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        AgentListener listener = new StubListener() {
            @Override public void onToolStarted(long turnId, String taskId, String toolName, String input) {
                seen.set(taskId);
            }
        };
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return readToolDefinition(); }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) { return "ok"; }
        };
        ToolCallback probe = new ToolEventCallback(delegate, listener);
        probe.call("{}", new ToolContext(java.util.Map.of("turnId", 1L, "taskId", "task_42")));
        org.junit.jupiter.api.Assertions.assertEquals("task_42", seen.get());
    }

    /** 固定名/固定返回值的委托桩：提醒逻辑只关心工具名与返回文本。 */
    private static final class FixedTool implements ToolCallback {
        private final String name;
        private final String out;

        FixedTool(String name, String out) {
            this.name = name;
            this.out = out;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        }

        @Override public String call(String toolInput) { return call(toolInput, null); }

        @Override public String call(String toolInput, ToolContext toolContext) { return out; }
    }

    private static TodoStaleReminder reminderWithUnfinished(String content) {
        TodoStaleReminder reminder = new TodoStaleReminder(2);
        reminder.onControllerTodoWritten(List.of(new org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem(
                content,
                org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress,
                content + "（进行中）")));
        return reminder;
    }

    @Test
    void staleTodoReminder_appendedAfterOnFinished_onlyForControllerCalls() {
        TodoStaleReminder reminder = reminderWithUnfinished("钉住的任务");
        RecordingListener recorder = new RecordingListener();
        ToolCallback cb = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder, reminder);
        ToolContext ctx = new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 5L));

        assertEquals("raw-out", cb.call("{}", ctx), "第 1 次未到阈值不加尾巴");
        String second = cb.call("{}", ctx);
        assertTrue(second.startsWith("raw-out\n\n[任务面板提醒]"),
                "第 2 次到阈值追加提醒，实际=" + second);
        assertTrue(second.contains("钉住的任务"), "提醒须含快照条目");
        // UI 侧事件携带原始输出：提醒只给模型，scrollback 不受污染
        assertEquals(2, recorder.events.stream()
                        .filter(e -> e.startsWith("finished:5:Bash:")).count(), "两次调用各一条 finished");
        assertTrue(recorder.events.contains("finished:5:Bash:raw-out:true"),
                "onToolFinished 须收到原始输出（第 2 次的记录也不含提醒）");
        assertTrue(recorder.events.stream().noneMatch(e -> e.contains("[任务面板提醒]")),
                "任何 UI 事件都不得出现提醒文本");
    }

    @Test
    void staleTodoReminder_neverForSubagentCalls_andNullReminderKeepsOldBehavior() {
        TodoStaleReminder reminder = reminderWithUnfinished("t");
        RecordingListener recorder = new RecordingListener();
        ToolCallback cb = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder, reminder);
        ToolContext subagentCtx = new ToolContext(Map.of(
                ToolEventCallback.TURN_ID_KEY, 5L, ToolEventCallback.TASK_ID_KEY, "sub-1"));
        for (int i = 0; i < 5; i++) {
            assertEquals("raw-out", cb.call("{}", subagentCtx), "taskId!=null 的调用永不提醒（第 " + (i + 1) + " 次）");
        }

        // 旧两参构造 = 停用：到阈值也不加尾巴（McpRegistry 等既有调用点行为不变）
        ToolCallback legacy = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder);
        assertEquals("raw-out", legacy.call("{}", new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 5L))));
        assertEquals("raw-out", legacy.call("{}", new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 5L))));
    }

    @Test
    @DisplayName("提醒器拿得到 toolInput：git commit 才算完成事件")
    void eventAlignedReminder_usesToolInputToDetectCommit() {
        TodoStaleReminder reminder = reminderWithUnfinished("提交后要更新的任务");
        RecordingListener recorder = new RecordingListener();
        ToolCallback cb = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder, reminder);
        ToolContext ctx = new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 11L));

        assertEquals("raw-out", cb.call("cat README.md", ctx), "普通 Bash 命令不武装提醒");
        assertEquals("raw-out", cb.call("cd /p && git commit -m \"docs: x\"", ctx),
                "commit 当刻不提醒——此刻清单还没机会更新");
        String after = cb.call("{}", ctx);
        assertTrue(after.startsWith("raw-out\n\n[任务面板提醒]"),
                "commit 后第一个跳过更新的调用应带提醒（toolInput 没透传给提醒器？），实际=" + after);
        assertTrue(after.contains("提交后要更新的任务"), "提醒须含快照");
    }

    @Test
    void staleTodoReminder_notAppendedOnFailure() {
        TodoStaleReminder reminder = reminderWithUnfinished("t");
        RecordingListener recorder = new RecordingListener();
        ToolCallback boom = new ToolEventCallback(new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("Bash").description("d").inputSchema("{}").build();
            }

            @Override public String call(String toolInput) { return call(toolInput, null); }

            @Override public String call(String toolInput, ToolContext toolContext) {
                throw new RuntimeException("boom");
            }
        }, recorder, reminder);
        ToolContext ctx = new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 5L));

        assertThrows(RuntimeException.class, () -> boom.call("{}", ctx));
        // 失败的调用不该提醒：阈值若被消耗，下一次成功调用将提前/延后触发——这里钉「异常路径不追加文本」即可
        assertEquals("raw-out", new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder, reminder)
                .call("{}", ctx), "异常后下一次成功调用第 1 次不提醒（计数未被异常消耗）");
    }
}
