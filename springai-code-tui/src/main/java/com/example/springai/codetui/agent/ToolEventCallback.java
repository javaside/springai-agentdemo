package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ToolCallback 装饰器：执行前后经 AgentListener 发工具事件；turnId 从 ToolContext 取。
 *  另用 ThreadLocal 把「正在执行的工具」的 turnId 暴露给同线程同步触发的 TodoWriteTool.todoEventHandler，
 *  使 Todo 事件与其它工具事件同源（不读实时 activeTurnId，取消/并发下不串轮）。 */
public final class ToolEventCallback implements ToolCallback {
    static final String TURN_ID_KEY = "turnId";

    /** TodoWriteTool 的 todoEventHandler 只收 Todos、拿不到 turnId；用 ThreadLocal 把当前执行回合的
     *  turnId 传给同线程同步触发的 handler。 */
    private static final ThreadLocal<Long> CURRENT_TURN = ThreadLocal.withInitial(() -> -1L);
    public static long currentTurnId() { return CURRENT_TURN.get(); }

    private final ToolCallback delegate;
    private final AgentListener listener;

    public ToolEventCallback(ToolCallback delegate, AgentListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long turnId = extractTurnId(toolContext);
        String name = delegate.getToolDefinition().name();
        listener.onToolStarted(turnId, name, toolInput);
        Long prev = CURRENT_TURN.get();
        CURRENT_TURN.set(turnId);   // 供 TodoWriteTool.todoEventHandler 同线程读取真正执行回合的 turnId
        try {
            String out = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            listener.onToolFinished(turnId, name, out, true);
            return out;
        } catch (RuntimeException ex) {
            listener.onToolFinished(turnId, name, String.valueOf(ex.getMessage()), false);
            throw ex;
        } finally {
            CURRENT_TURN.set(prev);
        }
    }

    private static long extractTurnId(ToolContext ctx) {
        if (ctx == null) return -1L;
        Object v = ctx.getContext().get(TURN_ID_KEY);   // getContext():Map<String,Object>（已核实）
        return (v instanceof Long l) ? l : -1L;
    }
}
