package io.github.javaside.springai.codetui.agent.goal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 把 /goal 的评估调用挪出 UI 线程，并给它加一层总超时（spec §6.3 调度面）。
 *
 * <p><b>为什么必须挪走</b>：评估是一次阻塞的模型调用（生产上限 60s）。若在 UI 线程的空闲批里直接
 * {@code evaluator.evaluate(...)}，内联 TUI 会连渲染、输入、两级 Esc 一起僵住到模型返回。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li><b>单线程 daemon</b>：一个线程，命名 {@value #THREAD_NAME}，daemon。daemon 是刻意的取舍
 *       （同 {@code TimeLimitedToolCallback}）：超时只换结果、不中断底层阻塞调用，评估线程可能滞留到
 *       请求自行结束——daemon 保证它绝不阻止 JVM 退出。单线程顺带把「评估串行」变成结构性事实，模型侧
 *       不会同时吃两次评估请求。</li>
 *   <li><b>超时从任务开始执行起算</b>（I2）：不用 {@code orTimeout}——它在 submit 时就武装，单线程
 *       executor 被上一个已超时但仍阻塞的 HTTP 调用占住时，排队评估的超时在<b>未执行</b>时就耗尽，
 *       假 PAUSED(EVALUATOR)。改为：worker 任务开头在专用 watchdog 调度器（单线程 daemon，命名
 *       {@value #WATCHDOG_NAME}）上 schedule 看门狗；正常/异常完成时 CAS {@code done} 并 cancel 看门狗；
 *       看门狗触发时 CAS {@code done} 并 {@code completeExceptionally(TimeoutException)}——CAS 保证
 *       恰一路胜出，迟到的结果/异常落到已完成的 future 上直接丢弃。</li>
 *   <li><b>超时归类为调用失败</b>：到点以 {@link TimeoutException} 走 {@code onFailure}，调用处据此按
 *       {@code evalFailLimit} 熔断——与「无 VERDICT 行的协议失败」（{@code protocolFailLimit}）分开
 *       计数（spec §7）。超时后迟到的返回值落到已完成的 future 上直接丢弃，<b>不</b>补第二次回调。</li>
 *   <li><b>回调即终点、恰一次，且必须非阻塞</b>：{@code onSuccess}/{@code onFailure} 二选一触发一次——
 *       正常/评估器异常完成在 executor 线程触发，<b>超时路径在 watchdog（或完成）线程触发</b>，回调里
 *       不得做任何长 IO/锁等待。回调方（{@code GoalManager}）自身线程安全，UI 侧经
 *       {@code UiChangeSource} publish 回 UI 线程，故 Runner 不做任何再调度、不碰 View。</li>
 *   <li><b>异常原样</b>：评估器抛出的异常剥掉 {@link CompletionException} 包装后按<b>同一实例</b>送达
 *       ——调用处靠 {@link GoalVerdict.GoalProtocolException} 的 instanceof 把协议失败分流到
 *       {@code onProtocolFailure}，包一层就会让它静默降级成调用失败。</li>
 *   <li><b>调用方先 CAS 再 submit</b>：Runner 不防重复（不做去重/排队上限）；「同代只许一次评估」由
 *       {@code GoalManager.beginEvaluation()} 的 CAS 保证。</li>
 * </ul>
 *
 * <p><b>代际防陈旧不在这里</b>：{@code epoch} 不参与本类的任何调度决策，Runner 也不丢弃陈旧结果——权威
 * 判定在 {@code GoalManager} 的回调门（epoch + phase + dispatchSerial）。签名保留 {@code epoch} 只为让
 * 调用处与回调闭包对齐（Task 11 的 {@code v -> gm.onVerdict(epoch, v)} 闭的是同一个值）。
 *
 * <p><b>关闭</b>：{@link #close()} 关两个池（幂等），在飞评估照跑完（daemon，不拖住 JVM）；关闭后的
 * {@code submit} 抛 {@link java.util.concurrent.RejectedExecutionException}（app 退出路径的正常竞态）。
 */
public final class GoalEvaluationRunner implements AutoCloseable {

    private static final String THREAD_NAME = "goal-evaluator";
    private static final String WATCHDOG_NAME = "goal-eval-watchdog";

    private final ExecutorService exec;
    /** 超时看门狗调度器：单线程 daemon——任务<b>开始执行</b>时才武装，排队等待不计入超时（I2）。 */
    private final ScheduledExecutorService watchdog;
    private final long timeoutMs;

    /** 生产构造：超时从 {@link GoalConfig#evalTimeoutSeconds()}（秒）换算毫秒。 */
    public GoalEvaluationRunner(GoalConfig config) {
        this(config, config.evalTimeoutSeconds() * 1000L);
    }

    /**
     * 包私有：毫秒超时直通（仅测试用）。{@code GoalConfig} 把 {@code evalTimeoutSeconds} 钳在
     * [5,300]，50ms 级的超时进不去——测试从这一口进来，生产构造仍走 config 换算。
     */
    GoalEvaluationRunner(GoalConfig config, long timeoutMsForTest) {
        Objects.requireNonNull(config, "config");
        if (timeoutMsForTest <= 0) {
            throw new IllegalArgumentException("评估超时必须为正毫秒: " + timeoutMsForTest);
        }
        this.timeoutMs = timeoutMsForTest;
        this.exec = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, WATCHDOG_NAME);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 提交一次评估：{@code evaluator.evaluate(input)} 在本类线程上跑，成功/失败/超时各回调<b>一次</b>
     * （CAS {@code done} 三路恰一胜出；排队等待不计入超时，看门狗在任务开始执行时才武装）。
     *
     * @param epoch   调用处的代（本类不用，见类 javadoc；与回调闭包同名以便对读）
     * @param evaluator 评估器（实现只管看滚动记录给结论，异常直捅出来）
     * @param input   锁内取齐的评估快照
     * @param onSuccess 拿到 verdict 时（executor 线程）调一次——回调必须非阻塞
     * @param onFailure 超时（watchdog 线程）或抛异常（executor 线程）时调一次，携带根因——回调必须非阻塞
     */
    public void submit(long epoch, GoalEvaluator evaluator, EvaluationInput input,
                       Consumer<GoalVerdict> onSuccess, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        CompletableFuture<GoalVerdict> outer = new CompletableFuture<>();   // 手工完成：超时权归看门狗
        AtomicBoolean done = new AtomicBoolean();                          // 终点 CAS：三路恰一
        exec.execute(() -> {                       // executor 拒绝（已 close）时同步抛 RejectedExecutionException
            ScheduledFuture<?> guard = watchdog.schedule(() -> {
                if (done.compareAndSet(false, true)) {
                    outer.completeExceptionally(new TimeoutException("goal 评估超时（" + timeoutMs + "ms）"));
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);  // 任务此刻才开跑：排队耗时不计入
            try {
                GoalVerdict verdict = evaluator.evaluate(input);
                guard.cancel(false);
                if (done.compareAndSet(false, true)) {
                    outer.complete(verdict);        // 超时已判（CAS 败）则丢弃迟到结果，不补回调
                }
            } catch (Exception e) {
                guard.cancel(false);
                if (done.compareAndSet(false, true)) {
                    // 受检异常进 future 的唯一口子；unwrap 负责剥回原实例
                    outer.completeExceptionally(new CompletionException(e));
                }
            }
        });
        outer.whenComplete((verdict, ex) -> {
            if (ex != null) {
                onFailure.accept(unwrap(ex));
            } else {
                onSuccess.accept(verdict);
            }
        });
    }

    /** 关池（幂等）：在飞评估照跑完——它是 daemon，不拖住 JVM。 */
    @Override
    public void close() {
        exec.shutdown();
        watchdog.shutdown();
    }

    /**
     * 剥掉异步边界自己加的包装（worker 把评估器抛出物裹成 {@link CompletionException}），让评估器
     * 的<b>原始异常实例与类型</b>到达 {@code onFailure}——调用处靠 instanceof 分流协议失败。
     * {@link TimeoutException} 不经包装，原样通过。只剥一层即够（评估器若自己抛
     * {@code CompletionException}，剥一层后拿到的正是它本人）。
     */
    private static Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }
}
