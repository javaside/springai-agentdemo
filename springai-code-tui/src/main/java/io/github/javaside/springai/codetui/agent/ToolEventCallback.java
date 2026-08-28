package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** ToolCallback 装饰器：执行前后经 AgentListener 发工具事件；turnId 从 ToolContext 取。
 *  另用 ThreadLocal 把「正在执行的工具」的 turnId 暴露给同线程同步触发的 TodoWriteTool.todoEventHandler，
 *  使 Todo 事件与其它工具事件同源（不读实时 activeTurnId，取消/并发下不串轮）。 */
public final class ToolEventCallback implements ToolCallback {
    /** <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
    public static final String TURN_ID_KEY = "turnId";
    /** <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
    public static final String TASK_ID_KEY = "taskId";

    /**
     * 后台任务标记：值为 taskId，仅后台派发时存在。
     *
     * <p><b>与 {@link #TASK_ID_KEY} 分开而不是复用</b>：taskId 对前台子 agent 也有值，
     * 用它判「是不是后台」会把前台子 agent 一并误判成后台，于是前台子 agent 的审批面板不再弹出——
     * 一个只在「派了子 agent 且需要审批」时才出现的静默降级。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static final String BACKGROUND_TASK_ID_KEY = "backgroundTaskId";

    /** TodoWriteTool 的 todoEventHandler 只收 Todos、拿不到 turnId；用 ThreadLocal 把当前执行回合的
     *  turnId 传给同线程同步触发的 handler。 */
    private static final ThreadLocal<Long> CURRENT_TURN = ThreadLocal.withInitial(() -> -1L);
    public static long currentTurnId() { return CURRENT_TURN.get(); }
    private static final ThreadLocal<String> CURRENT_TASK = new ThreadLocal<>();
    /** 当前正在执行的子 agent 工具的 taskId（无则 null）；供同线程同步触发的 handler 读取。 */
    public static String currentTaskId() { return CURRENT_TASK.get(); }

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
        String taskId = extractTaskId(toolContext);
        String name = delegate.getToolDefinition().name();
        listener.onToolStarted(turnId, taskId, name, toolInput);
        Long prevTurn = CURRENT_TURN.get();
        String prevTask = CURRENT_TASK.get();
        CURRENT_TURN.set(turnId);
        CURRENT_TASK.set(taskId);
        try {
            String out = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            listener.onToolFinished(turnId, taskId, name, out, true);
            return out;
        } catch (RuntimeException ex) {
            listener.onToolFinished(turnId, taskId, name, String.valueOf(ex.getMessage()), false);
            throw ex;
        } finally {
            CURRENT_TURN.set(prevTurn);
            CURRENT_TASK.set(prevTask);
        }
    }

    /** 包私有：{@link PermissionCallback} 在本装饰器<b>外层</b>也要取 taskId，复用同一份实现避免漂移。 */
    static String extractTaskId(ToolContext ctx) {
        if (ctx == null) return null;
        Object v = ctx.getContext().get(TASK_ID_KEY);
        return (v instanceof String s) ? s : null;
    }

    /** 包私有：{@link PermissionCallback} 据此判断本次调用是否来自后台任务。 */
    static String extractBackgroundTaskId(ToolContext ctx) {
        if (ctx == null) return null;
        Object v = ctx.getContext().get(BACKGROUND_TASK_ID_KEY);
        return v == null ? null : String.valueOf(v);
    }

    /** 包私有：{@link PermissionCallback} 在本装饰器<b>外层</b>取 turnId（那时 ThreadLocal 还没压入）。 */
    static long extractTurnId(ToolContext ctx) {
        if (ctx == null) return -1L;
        Object v = ctx.getContext().get(TURN_ID_KEY);   // getContext():Map<String,Object>（已核实）
        return (v instanceof Long l) ? l : -1L;
    }
}
