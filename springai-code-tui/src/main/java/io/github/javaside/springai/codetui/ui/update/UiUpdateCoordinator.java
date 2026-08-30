package io.github.javaside.springai.codetui.ui.update;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tamboui.tui.InlineTuiRunner;

/**
 * 事件驱动 UI 的合并与一次性调度中心（设计 §6.2、§9.2、§10）。
 *
 * <p>职责：把任意并发生产者的 {@link UiChangeListener#onUiChanged(int)} 通知合并为
 * <b>有限数量</b>的 UI update（{@code runner.requestUiUpdate}），并为 continuation /
 * preview / resize settle / animation 四类按需任务维护「每类至多一个在飞 generation」
 * 的一次性调度。它不保存 token、modal 或任何业务快照——通知只表示“某类状态可能已变化”，
 * UI 醒来后自行回读 {@code ConversationState} 等真相源。
 *
 * <h2>生产者协议（设计 §6.2）</h2>
 * <ol>
 *   <li>原子 OR 新 dirty bits，并递增 coordinator 自持的全局 generation
 *       （<b>不</b>比较来源局部的版本号，避免把不相干的计数器错当唤醒依据）；</li>
 *   <li>只有把 {@code scheduled} 从 false CAS 到 true 的赢家调用
 *       {@code runner.requestUiUpdate(this::runBatch)}；</li>
 *   <li>已有 update 待处理时后来的 publish 只合并位，不再堆事件——
 *       瞬间数千 token 也至多一个已调度 update + 一个必要 continuation（§9.3）。</li>
 * </ol>
 *
 * <h2>消费者协议（设计 §6.2 / §8）</h2>
 * {@link #runBatch()} 在 UI 线程执行：
 * <ol>
 *   <li>原子取走（getAndSet(0)）本轮 dirty bits；</li>
 *   <li>调用 {@link UpdateProcessor#processUpdates(int)} <b>恰好一次</b>；</li>
 *   <li>按 {@link UpdateResult} 安排 demand-driven follow-ups
 *       （outputRemaining → continuation；animationActive → 下一帧）；</li>
 *   <li>{@code finally} 中清 {@code scheduled}；</li>
 *   <li>复查 dirty bits：期间出现新变化则重新取得调度权并入队下一批
 *       （关闭“消费者清 scheduled 与生产者发通知交错”的丢唤醒窗口）。</li>
 * </ol>
 * 绝不在单个 UI action 内循环到空——批与批之间事件循环可以处理按键、粘贴和 resize（§9.2）。
 * processor 抛出的异常照原样上抛（由 {@code InlineTuiRunner} 的既有 Throwable 防护记录并
 * 保持事件循环存活），{@code finally} 保证 {@code scheduled} 被释放，不会永久卡住后续调度。
 *
 * <h2>timer 纪律（设计 §10）</h2>
 * <ul>
 *   <li>每个类别（continuation / preview / resize / animation）至多一个在飞 generation；</li>
 *   <li>timer 回调只 publish dirty bits 或请求 UI action，
 *       <b>绝不</b>在 timer 线程直接触碰 View 状态；</li>
 *   <li>新的 resize settle 以新 generation 替换旧的并取消其 future：
 *       <b>被替换的旧 {@code uiAction} 永不执行</b>；</li>
 *   <li>preview 窗口内重复调度保持首个到期（§10.1 节流语义）；</li>
 *   <li>{@link #stop()} 取消全部 timer；stop 之后一切 publish / 迟到回调为 no-op。</li>
 * </ul>
 *
 * <p>线程安全性：所有状态为原子量或不可变 record；任何线程都可 publish / 调度 / stop。
 */
public final class UiUpdateCoordinator implements UiChangeListener, AutoCloseable {

    private static final Logger log = Logger.getLogger(UiUpdateCoordinator.class.getName());

    /** 生命周期：未启动 → 运行中 → 停止中 → 已停止（设计 §6.2）。 */
    public enum Lifecycle { NEW, RUNNING, STOPPING, STOPPED }

    /**
     * 一个有界 UI 批的处理器，在 UI 线程执行，必须对给定 dirty bits 恰好处理一批
     * （不循环到空）。返回值描述 demand-driven follow-up 需求。
     */
    @FunctionalInterface
    public interface UpdateProcessor {
        UpdateResult processUpdates(int dirtyBits);
    }

    /**
     * 一批处理后的后续需求。
     *
     * @param outputRemaining   输出存量未清空 → 需要一次性 continuation（§9.2），
     *                          无新生产者事件也必须最终排空；
     * @param previewPending    流式残行预览待处理（本任务只透传标记，Task 8 接管节流）；
     * @param animationActive   仍有动态状态 → 续排下一动画帧（§10.3）；
     * @param contextUsageDirty 上下文用量标脏（Task 6 的 refresh controller 接管）。
     */
    public record UpdateResult(boolean outputRemaining,
                               boolean previewPending,
                               boolean animationActive,
                               boolean contextUsageDirty) {
        public static UpdateResult idle() {
            return new UpdateResult(false, false, false, false);
        }
    }

    /** resize settle 的在飞 generation：新调用整体替换，旧 future 取消、旧 action 失效。 */
    private record ResizeSettleHandle(long generation, Runnable uiAction, ScheduledFuture<?> future) { }

    private final Consumer<Runnable> uiUpdateSink;
    private final ScheduledExecutorService scheduler;
    private final UpdateProcessor processor;

    private final AtomicInteger dirtyBits = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);

    // ── 每类至多一个在飞 generation 的一次性任务 ─────────────────────────
    private final AtomicReference<ScheduledFuture<?>> continuationFuture = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> previewFuture = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> animationFuture = new AtomicReference<>();
    private final AtomicReference<ResizeSettleHandle> resizeSettle = new AtomicReference<>();

    /** View 最近一次声明的动画帧间隔；runBatch 续排下一帧时沿用。 */
    private volatile Duration animationFrameDelay = Duration.ofMillis(66);

    /** 停止路径与 resize 替换的簿记互斥（不影响 timer 回调的无锁读）。 */
    private final Object resizeLock = new Object();

    /**
     * 生产构造函数：UI update 投给 TamboUI 唯一 UI 执行器
     * （{@link InlineTuiRunner#requestUiUpdate(Runnable)}，Task 1）。
     */
    public UiUpdateCoordinator(InlineTuiRunner runner,
                               ScheduledExecutorService scheduler,
                               UpdateProcessor processor) {
        this(runner == null ? null : runner::requestUiUpdate, scheduler, processor);
    }

    /**
     * 接缝构造函数：{@code uiUpdateSink} 是 {@code requestUiUpdate} 的直接等价物，
     * 供确定性测试（受控 runner）与装配替换使用；合并/调度行为与生产构造函数完全一致。
     */
    public UiUpdateCoordinator(Consumer<Runnable> uiUpdateSink,
                               ScheduledExecutorService scheduler,
                               UpdateProcessor processor) {
        this.uiUpdateSink = Objects.requireNonNull(uiUpdateSink, "uiUpdateSink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /** 进入 RUNNING。幂等：重复调用无效果。 */
    public void start() {
        lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.RUNNING);
    }

    /**
     * 生产者入口（任意线程）：OR bits + 递增全局 generation；
     * 只有 CAS 赢家投递一个 UI update。stop 后 no-op。
     */
    @Override
    public void onUiChanged(int bits) {
        if (bits == 0 || lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        dirtyBits.accumulateAndGet(bits, (cur, add) -> cur | add);
        generation.incrementAndGet();
        maybeSchedule();
    }

    /** 若未调度则取得调度权并投递一个 UI update。 */
    private void maybeSchedule() {
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        if (scheduled.compareAndSet(false, true)) {
            uiUpdateSink.accept(this::runBatch);
        }
    }

    /**
     * 输出存量未清空时的一次性 continuation（§9.2）。
     * 每次只存在一个；到期回调只 publish OUTPUT，不在 timer 线程触碰 View。
     */
    public void scheduleOutputContinuation(Duration delay) {
        scheduleOneShot(continuationFuture, delay, () -> publishFromTimer(UiDirty.OUTPUT));
    }

    /**
     * 流式残行预览的一次性 VIEW 调度（§10.1）。
     * 节流窗口内重复调用保持<b>首个</b>到期（窗口到期只安排一次 VIEW update）。
     */
    public void schedulePreview(Duration delay) {
        scheduleOneShot(previewFuture, delay, () -> publishFromTimer(UiDirty.VIEW));
    }

    /**
     * resize 静默窗口后的一次性 settle（§10.2）。
     * 新调用以新 generation 替换旧的：旧 future 被取消、<b>旧 {@code uiAction} 永不执行</b>。
     * {@code uiAction} 通过 {@code requestUiUpdate} 转入 UI 线程执行。
     */
    public void scheduleResizeSettle(Duration delay, Runnable uiAction) {
        if (uiAction == null || lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        synchronized (resizeLock) {
            if (lifecycle.get() != Lifecycle.RUNNING) {
                return;
            }
            long newGeneration = generation.incrementAndGet();
            ResizeSettleTask task = new ResizeSettleTask(newGeneration, uiAction);
            ScheduledFuture<?> future = safeSchedule(delay, task);
            if (future == null) {
                return;
            }
            ResizeSettleHandle previous =
                    resizeSettle.getAndSet(new ResizeSettleHandle(newGeneration, uiAction, future));
            if (previous != null) {
                previous.future().cancel(false);
                log.log(Level.FINE, "resize settle generation {0} replaced by {1}",
                        new Object[] {previous.generation(), newGeneration});
            }
        }
    }

    /** resize settle 任务：generation 不匹配（被替换/停止）时 no-op。 */
    private final class ResizeSettleTask implements Runnable {
        private final long expectedGeneration;
        private final Runnable uiAction;

        ResizeSettleTask(long expectedGeneration, Runnable uiAction) {
            this.expectedGeneration = expectedGeneration;
            this.uiAction = uiAction;
        }

        @Override
        public void run() {
            if (lifecycle.get() != Lifecycle.RUNNING) {
                return;
            }
            ResizeSettleHandle current = resizeSettle.get();
            if (current == null || current.generation() != expectedGeneration) {
                return; // 已被更新的 resize 替换：旧 action 不执行
            }
            uiUpdateSink.accept(uiAction); // 请求 UI action，不在 timer 线程触碰 View
        }
    }

    /**
     * 动画需求开关（§10.3）：active=true 时保持至多一个在飞帧 generation，
     * 每帧到期 publish VIEW；active=false 立即取消当前帧，状态消失即停止。
     * {@link UpdateResult#animationActive()} 为真时 {@link #runBatch()} 也会按
     * 最近一次声明的 {@code frameDelay} 续排。
     */
    public void updateAnimationDemand(boolean active, Duration frameDelay) {
        if (!active) {
            cancelTimer(animationFuture);
            return;
        }
        if (frameDelay != null && !frameDelay.isNegative() && !frameDelay.isZero()) {
            animationFrameDelay = frameDelay;
        }
        scheduleOneShot(animationFuture, animationFrameDelay,
                () -> publishFromTimer(UiDirty.VIEW));
    }

    /** timer 线程只 publish，不触碰 View 状态（设计 §6.2 / Task 3 纪律）。 */
    private void publishFromTimer(int bits) {
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        onUiChanged(bits);
    }

    /**
     * 在 slot 内安排一次性任务：已有未完成的同类任务 → 保持现有 generation（合并）；
     * 否则新任务 CAS 入槽，竞争失败（别的线程刚放了新的）则取消自己。
     */
    private void scheduleOneShot(AtomicReference<ScheduledFuture<?>> slot, Duration delay,
            Runnable body) {
        if (lifecycle.get() != Lifecycle.RUNNING) {
            return;
        }
        ScheduledFuture<?> existing = slot.get();
        if (existing != null && !existing.isDone()) {
            return; // 每类至多一个在飞 generation
        }
        ScheduledFuture<?> future = safeSchedule(delay, body);
        if (future == null) {
            return;
        }
        if (!slot.compareAndSet(existing, future)) {
            future.cancel(false);
        }
    }

    private ScheduledFuture<?> safeSchedule(Duration delay, Runnable task) {
        try {
            return scheduler.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (Throwable t) {
            // scheduler 已关闭（停止路径）等：记录并放弃本任务，不影响其余状态
            log.log(Level.FINE, "coordinator one-shot schedule rejected", t);
            return null;
        }
    }

    private void cancelTimer(AtomicReference<ScheduledFuture<?>> slot) {
        ScheduledFuture<?> future = slot.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * UI 线程执行的一个有界批。绝不在本 action 内循环到空；
     * follow-up 通过一次性 timer 转入后续批。
     */
    void runBatch() {
        try {
            int bits = dirtyBits.getAndSet(0);
            UpdateResult result = processor.processUpdates(bits);
            // demand-driven follow-ups：安排一次性任务，不在本 action 内循环
            if (result != null) {
                if (result.outputRemaining()) {
                    scheduleOneShot(continuationFuture, Duration.ZERO,
                            () -> publishFromTimer(UiDirty.OUTPUT));
                }
                if (result.animationActive()) {
                    scheduleOneShot(animationFuture, animationFrameDelay,
                            () -> publishFromTimer(UiDirty.VIEW));
                }
            }
        } finally {
            scheduled.set(false);
            // 关键复查（设计 §6.2 第 6 步）：清 scheduled 后若又有新位，
            // 重新取得调度权入队下一批，关闭丢唤醒窗口
            if (dirtyBits.get() != 0) {
                maybeSchedule();
            }
        }
    }

    public Lifecycle lifecycle() {
        return lifecycle.get();
    }

    /** 尚未被任何批取走的 dirty bits（诊断/测试）。 */
    public int pendingDirtyBits() {
        return dirtyBits.get();
    }

    /** 是否存在一个已投递、尚未执行完的 UI update（诊断/测试）。 */
    public boolean updateScheduled() {
        return scheduled.get();
    }

    /**
     * 停止（设计 §13.2）：进入 STOPPING → 取消全部一次性 timer → STOPPED；
     * 之后一切 publish 与迟到回调为 no-op。幂等。
     */
    public void stop() {
        if (!lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.STOPPING)) {
            lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STOPPED);
            return;
        }
        try {
            cancelTimer(continuationFuture);
            cancelTimer(previewFuture);
            cancelTimer(animationFuture);
            synchronized (resizeLock) {
                ResizeSettleHandle settle = resizeSettle.getAndSet(null);
                if (settle != null) {
                    settle.future().cancel(false);
                }
            }
        } finally {
            lifecycle.set(Lifecycle.STOPPED);
        }
    }

    /** 等价于 {@link #stop()}（try-with-resources 用）。 */
    @Override
    public void close() {
        stop();
    }
}
