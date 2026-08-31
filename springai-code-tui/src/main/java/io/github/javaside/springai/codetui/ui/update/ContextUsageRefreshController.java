package io.github.javaside.springai.codetui.ui.update;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.javaside.springai.codetui.ui.ContextUsage;

/**
 * 上下文用量刷新的<b>按需、防抖、单飞</b>调度器（设计 §10.4，Task 6）。
 *
 * <p>取代旧「视图 drain 每 ~1s 无条件刷一次」：现在只有会影响上下文统计的事件
 * （{@link #markDirty()}）才触发刷新，且按防抖窗口合并突发；同一时刻至多一个
 * {@link ContextUsage#refresh()} 在飞；在飞期间的新标脏只记欠账（追赶标志），
 * 完成后复查欠账<b>立即</b>再启恰好一次——「追版本但不追平」：{@code refresh()} 读的是
 * 实时会话源，欠账只需表达「又有变化」，与标记次数无关，故合并成一个追赶标志即可。
 *
 * <h2>状态机（全部原子量，无锁）</h2>
 * <pre>
 *   IDLE ──markDirty──► ARMED（防抖 timer）──到期──► 提交 refresh，进入 REFRESHING
 *   ARMED 期间再 markDirty：不重排 timer（首个到期为准，突发合并）
 *   REFRESHING 期间 markDirty：只置 DEBT，不提交任务（单飞）
 *   REFRESHING 完成：DEBT=true → 立即再提交恰好一个追赶 refresh（单飞权直接移交，
 *                    任务间不存在无主窗口）；DEBT=false → 归还单飞权回到 IDLE
 * </pre>
 *
 * <ul>
 *   <li><b>回调条件</b>：仅当 {@code refresh()} 返回 true（缓存的可见数据真实变化）才回调
 *       {@code onRefreshed}——生产接线里它发布 VIEW（设计 §10.4「refresh 完成发布 VIEW」），
 *       数据没变就不打扰 UI；</li>
 *   <li><b>异常安全</b>：{@code refresh()} 自身吞掉 source 异常（保留旧缓存返回 false），
 *       本类再兜一层 {@code Throwable}——调度设施绝不被业务异常击穿、单飞权绝不泄漏；</li>
 *   <li><b>停止</b>：{@link #stop()} 后一切 markDirty / 迟到的防抖到期 / 完成回调为 no-op；
 *       在飞中的 refresh 无法安全中断，允许跑完，但完成路径立即静默（不回调、不追赶）。</li>
 * </ul>
 *
 * <p>线程纪律（设计 §6.2 / §12.1）：timer 回调只提交任务，绝不在 scheduler 线程触碰 View
 * 状态；{@code refresh} 全程跑在注入的 executor（生产为 CodeTuiView 的
 * {@code context-usage-refresh} 单线程池——大会话 token 估算可达数百 ms，绝不占 UI 线程）；
 * {@code onRefreshed} 在该 executor 线程回调，调用方只做无锁 publish（如
 * {@code coordinator.onUiChanged(UiDirty.VIEW)}），不回读重状态。
 *
 * <p>Task 6 只交付本类与 {@link ContextUsage#refresh()} 的返回值语义；CodeTuiView 仍由
 * animTick 周期触发旧刷新路径，接线（markDirty 挂事件源、onRefreshed 发布 VIEW、删除
 * 周期触发）在 Task 7 完成。
 */
public final class ContextUsageRefreshController implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ContextUsageRefreshController.class.getName());

    private final ContextUsage usage;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Duration debounce;
    private final Runnable onRefreshed;

    /** 单飞权：仅持有人可在 executor 上执行 refresh；追赶路径直接移交，不重复获取。 */
    private final AtomicBoolean inFlight = new AtomicBoolean();
    /** 防抖 timer 已武装：窗口内重复 markDirty 不重排（首个到期为准）。 */
    private final AtomicBoolean armed = new AtomicBoolean();
    /** 在飞 refresh 期间又标脏的欠账：完成复查时合并消化为恰好一次追赶。 */
    private final AtomicBoolean debt = new AtomicBoolean();
    /** stop 后不再接受任何新工作。 */
    private final AtomicBoolean stopped = new AtomicBoolean();
    /** 已武装的防抖 timer：stop 时取消。 */
    private final AtomicReference<ScheduledFuture<?>> debounceFuture = new AtomicReference<>();

    public ContextUsageRefreshController(ContextUsage usage,
                                         Executor executor,
                                         ScheduledExecutorService scheduler,
                                         Duration debounce,
                                         Runnable onRefreshed) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.debounce = Objects.requireNonNull(debounce, "debounce");
        this.onRefreshed = Objects.requireNonNull(onRefreshed, "onRefreshed");
    }

    /**
     * 标脏（任意线程）：上下文统计可能已变化，按防抖窗口安排一次刷新。
     * 突发合并——已武装窗口内重复调用不叠加任务；在飞期间调用只记欠账（由完成路径立即消化，
     * 无需再等防抖——防抖的意义是合并「启动前的突发」，单飞本身已保证执行不并发）。
     * stop 后 no-op。
     */
    public void markDirty() {
        if (stopped.get()) {
            return;
        }
        if (inFlight.get()) {
            debt.set(true);
            return;
        }
        if (armed.get()) {
            return;   // 防抖已武装：窗口内重复标脏合并，首个到期为准
        }
        if (armed.compareAndSet(false, true)) {
            ScheduledFuture<?> future;
            try {
                future = scheduler.schedule(this::debounceFired, debounce.toNanos(), TimeUnit.NANOSECONDS);
            } catch (Throwable t) {
                armed.set(false);   // 归还武装位，之后 markDirty 可重试（scheduler 关闭等为终态）
                log.log(Level.FINE, "context usage debounce schedule rejected", t);
                return;
            }
            ScheduledFuture<?> previous = debounceFuture.getAndSet(future);
            if (previous != null) {
                previous.cancel(false);   // 理论上不可达（armed 互斥），防御性取消
            }
        }
    }

    /** 防抖到期（scheduler 线程）：只提交 refresh 任务，绝不在此触碰 View。 */
    private void debounceFired() {
        armed.set(false);
        if (stopped.get()) {
            return;
        }
        try {
            executor.execute(() -> runRefresh(false));
        } catch (Throwable t) {
            // 提交被拒（executor 已关闭等）：武装位已清，之后的 markDirty 可重新武装
            log.log(Level.FINE, "context usage refresh submit rejected", t);
        }
    }

    /**
     * 是否有一个 refresh 正在执行（诊断/测试）。
     * <b>不反映 {@link #stop()}</b>：stop 后在飞的 refresh 仍报 {@code true} 直至自然跑完
     * （设计取舍——在飞任务无法安全中断，允许跑完但完成路径静默：不回调、不追赶）。
     */
    public boolean refreshInFlight() {
        return inFlight.get();
    }

    /**
     * 单个 refresh 任务体（executor 线程）。
     *
     * @param permitTransferred 追赶路径为 true：单飞权已由上一个完成者直接移交到手，
     *                          跳过获取；false 时以 CAS 取得单飞权
     */
    private void runRefresh(boolean permitTransferred) {
        if (stopped.get()) {
            if (permitTransferred) {
                inFlight.set(false);   // stop 后接管到的空权也要归还，免诊断值失真
            }
            return;
        }
        if (!permitTransferred && !inFlight.compareAndSet(false, true)) {
            return;   // 已有在飞（理论上不会：防抖与追赶都互斥），放弃本次
        }
        boolean changed = false;
        try {
            changed = usage.refresh();   // 自身吞 source 异常：保留旧缓存、返回 false
        } catch (Throwable t) {
            // 防御性兜底（refresh 已容错，这里只挡 Error 级）：记录后按「无变化」处理
            log.log(Level.FINE, "context usage refresh failed", t);
        }
        boolean notify = changed && !stopped.get();   // stop 后即使变了也不回调

        // ── 完成复查：消化欠账决定是否立即再启一次（先于归还，避免无主窗口丢唤醒）──
        boolean catchUp = !stopped.get() && debt.getAndSet(false);
        if (catchUp) {
            boolean submitted = false;
            try {
                // 追赶恰好一个（欠账合并）；单飞权保持持有并移交 给后续任务
                executor.execute(() -> runRefresh(true));
                submitted = true;
            } catch (Throwable t) {
                log.log(Level.FINE, "context usage catch-up submit rejected", t);
            }
            if (submitted) {
                if (notify) {
                    notifyRefreshed();
                }
                return;   // 单飞权已随任务移交，本栈不再触碰
            }
            // 提交失败：归还单飞权并改走防抖路径重试（欠账已清，但「有变化未刷」这一事实
            // 由本次重试的防抖窗口接住；executor 拒绝通常意味着关闭，重试也会静默失败）
            inFlight.set(false);
            if (!stopped.get()) {
                markDirty();
            }
        } else {
            inFlight.set(false);
            // 关闭丢唤醒窗口：markDirty 在「消费欠账 → 归还单飞权」之间看到在飞而置的欠账，
            // 此刻已无人消费——补武装一个防抖窗口（markDirty 自带 armed/inFlight 互斥，安全）。
            if (!stopped.get() && debt.get()) {
                debt.set(false);
                markDirty();
            }
        }
        if (notify) {
            notifyRefreshed();
        }
    }

    /** onRefreshed 只在缓存的可见数据真实变化时到达；回调异常吞掉，不影响调度设施。 */
    private void notifyRefreshed() {
        try {
            onRefreshed.run();
        } catch (Throwable t) {
            log.log(Level.FINE, "context usage onRefreshed callback failed", t);
        }
    }

    /**
     * 停止（设计 §13.2）：之后一切 markDirty / 迟到的防抖到期 / 完成回调为 no-op；
     * 已武装的防抖 timer 被取消；在飞中的 refresh 允许自然跑完但不产生任何回调或追赶。
     * 幂等。
     */
    public void stop() {
        if (stopped.getAndSet(true)) {
            return;
        }
        ScheduledFuture<?> future = debounceFuture.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
        armed.set(false);
        debt.set(false);
    }

    /** 等价于 {@link #stop()}（try-with-resources 用）。 */
    @Override
    public void close() {
        stop();
    }
}
