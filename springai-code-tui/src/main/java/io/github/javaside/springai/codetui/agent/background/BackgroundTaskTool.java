package io.github.javaside.springai.codetui.agent.background;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * {@code TaskOutput} 工具：取回后台任务的结果。
 *
 * <p><b>两条回收路径的其中一条</b>（另一条是空闲时自动送达）。取到即经
 * {@link BackgroundTaskRegistry#markConsumed} 标记已消费，避免同一个结果被自动送一遍。
 *
 * <p><b>阻塞等待超时后返回“仍在运行”而不是失败</b>：超时不等于任务死了。
 * 报成失败会让模型放弃这个任务，而它可能再过十秒就好了。
 */
public final class BackgroundTaskTool {

    private static final String DESCRIPTION = """
            Retrieve the result of a background subagent task started with Task(run_in_background=true).

            - Pass block=false to check status without waiting.
            - Pass block=true to wait until the task finishes (bounded timeout; on timeout it
              simply reports that the task is still running).
            - Results of finished tasks are also delivered automatically when the session is idle,
              so you do not have to poll. Use this tool when you want the result *now*, inside the
              current turn.
            """;

    /** 轮询间隔：200ms。够快（人感知不到），又不会把 CPU 转满。 */
    private static final long POLL_INTERVAL_MS = 200;

    /** 工具入参。 */
    public record Query(
            @ToolParam(description = "The task id returned by Task(run_in_background=true)") String task_id,
            @ToolParam(required = false, description = "Wait until the task finishes (default false)")
            Boolean block) {

        public boolean blocking() {
            return Boolean.TRUE.equals(block);
        }
    }

    private final BackgroundTaskRegistry registry;
    private final TaskResultStore results;
    private final int timeoutSeconds;

    public BackgroundTaskTool(BackgroundTaskRegistry registry, TaskResultStore results, int timeoutSeconds) {
        this.registry = registry;
        this.results = results;
        this.timeoutSeconds = Math.min(3600, Math.max(1, timeoutSeconds));
    }

    /** 构建名为 "TaskOutput" 的 ToolCallback。 */
    public static ToolCallback create(BackgroundTaskRegistry registry, TaskResultStore results,
                                      int timeoutSeconds) {
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, results, timeoutSeconds);
        return FunctionToolCallback.builder("TaskOutput", (Query q) -> tool.fetch(q))
                .description(DESCRIPTION)
                .inputType(Query.class)
                .build();
    }

    /** 取结果。包私有可见性足够测试直接调，不必经 ToolCallback 绕一圈。 */
    String fetch(Query q) {
        String id = q.task_id();
        BackgroundTask t = registry.find(id);
        if (t == null) {
            return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存）。";
        }
        if (!t.finished() && q.blocking()) {
            t = awaitFinish(id);
        }
        if (t == null) {
            return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存）。";
        }
        return switch (t.status()) {
            case RUNNING -> "任务 " + id + " 仍在运行（" + t.agentName() + " · " + t.description()
                    + "）。稍后再取，或等待完成通知。";
            case KILLED -> "任务 " + id + " 已被终止，没有结果。";
            case DONE -> {
                registry.markConsumed(id);
                yield "任务 " + id + " 已完成（" + t.agentName() + " · " + t.description() + "）：\n"
                        + results.storeAndTruncate(id, t.result());
            }
            case FAILED -> {
                registry.markConsumed(id);
                yield "任务 " + id + " 执行失败（" + t.agentName() + " · " + t.description() + "）：\n"
                        + results.storeAndTruncate(id, t.result());
            }
        };
    }

    /**
     * 轮询等待任务结束，最长 {@link #timeoutSeconds}。
     *
     * <p><b>轮询而不是条件变量</b>：注册表刻意不认识“谁在等它”（见其类注释），
     * 加通知机制要么让注册表持有等待者、要么加一层事件总线，为一个 200ms 精度的等待不值得。
     *
     * <p><b>被中断时立刻返回当前状态并<u>重新置位中断标志</u></b>：吞掉中断会让“回合已取消”
     * 这个信号在这里消失，上层再也看不到。
     */
    private BackgroundTask awaitFinish(String id) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            BackgroundTask t = registry.find(id);
            if (t == null || t.finished()) return t;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return registry.find(id);
            }
        }
        return registry.find(id);
    }
}
