package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
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
}
