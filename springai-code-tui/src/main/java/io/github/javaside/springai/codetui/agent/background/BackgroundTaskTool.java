package io.github.javaside.springai.codetui.agent.background;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.function.BooleanSupplier;

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

    /**
     * 查无此 id 的解释——<b>三件事都要说</b>。
     *
     * <p>原来只说「可能来自已结束的进程」，但那只是其中一种：注册表满 64 条时
     * {@link BackgroundTaskRegistry} 会淘汰最旧的<b>已结束</b>任务，其中完全可能含已完成但还没送达的
     * ——那是<b>本进程</b>刚刚丢掉的结果。只给前一种解释，模型会以为这是上个进程的陈旧 id 而就此放弃，
     * 而实际上它该做的是重新派一次。
     *
     * <p><b>第三件是当前清单</b>：模型手上的 id 不对，而正确的下一步取决于「现在到底有哪些任务」。
     * 这一刻正是它最需要看清单的时刻，只回一句「未知任务」等于让它去猜。
     * 措辞走 {@link BackgroundDigest}，与 {@code ListTasks} 和 {@code /continue} 保持一致。
     */
    private String unknownTask(String id) {
        return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存；"
                + "也可能是本进程后台任务过多，这条已结束的记录被淘汰了。若仍需要结果，请重新派发）。\n"
                + BackgroundDigest.full(registry.all());
    }

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
    private final BooleanSupplier interjectionPending;

    /**
     * @param interjectionPending 「此刻有没有未送达的用户插话」。<b>必填、不给默认值</b>：
     *                            漏接的后果是阻塞等待重新变回一堵墙，而这是个纯时序问题——
     *                            功能测试全绿、界面看着也正常，只有真人在等的时候才发作。
     *                            强制每个调用点显式表态，比留一个 {@code () -> false} 的重载安全。
     */
    public BackgroundTaskTool(BackgroundTaskRegistry registry, TaskResultStore results, int timeoutSeconds,
                              BooleanSupplier interjectionPending) {
        this.registry = registry;
        this.results = results;
        this.timeoutSeconds = Math.min(3600, Math.max(1, timeoutSeconds));
        this.interjectionPending = interjectionPending;
    }

    /** 构建名为 "TaskOutput" 的 ToolCallback。 */
    public static ToolCallback create(BackgroundTaskRegistry registry, TaskResultStore results,
                                      int timeoutSeconds, BooleanSupplier interjectionPending) {
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, results, timeoutSeconds, interjectionPending);
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
            return unknownTask(id);
        }
        if (!t.finished() && q.blocking()) {
            t = awaitFinish(id);
        }
        if (t == null) {
            return unknownTask(id);
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
     *
     * <p><b>有未送达插话就提前收工</b>。这一等最长 300 秒，而它跑在<b>主 agent 的工具线程</b>上：
     * 期间主回合不结束（用户打字走插话分支），主 agent 也不发模型调用——而插话的唯一送达点
     * 就是模型调用。不让路的话，「后台」这两个字被 {@code block=true} 抵消得干干净净，
     * 用户要等的时长和前台 Task 一模一样。反正每 200ms 醒一次，顺路问一句几乎不要钱。
     *
     * <p><b>返回值刻意不加特殊措辞</b>（还是那句「仍在运行…稍后再取」）：用户那句话会出现在
     * <b>紧接着的同一次</b>模型调用里，模型自己看得见发生了什么；再编一句「因为有人插话所以我提前
     * 返回了」是在描述模型无从据此改变行为的管道细节，还多一处要跟着改的字符串。
     *
     * <p>不会来回空转：让路之后那次模型调用会把插话取走，队列即空，模型若再 {@code block=true}
     * 就正常等下去。
     */
    private BackgroundTask awaitFinish(String id) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            BackgroundTask t = registry.find(id);
            if (t == null || t.finished()) return t;
            if (interjectionPending.getAsBoolean()) return t;
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
