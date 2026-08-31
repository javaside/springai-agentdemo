package io.github.javaside.springai.codetui.ui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.ui.ContextUsage;

/**
 * {@link ContextUsageRefreshController} 的按需/防抖/单飞/追赶契约（设计 §10.4，Task 6）：
 *
 * <ul>
 *   <li>markDirty 突发合并为一个防抖任务（窗口内重复标脏不叠加）；</li>
 *   <li>同时至多一个 refresh 在飞（大会话 token 估算数百 ms，绝不并行重算）；</li>
 *   <li>在飞期间再标脏只记欠账（追赶标志），完成后最多再启一次；</li>
 *   <li>{@code refresh()} 返回 false（可见数据未变）不回调 onRefreshed，true 才回调；</li>
 *   <li>refresh 异常：保留旧缓存、返回 false、不回调、不卡死后续标脏；</li>
 *   <li>stop 后一切 markDirty / 迟到的防抖到期都是 no-op，不再向 executor 提交任务。</li>
 * </ul>
 *
 * <p>确定性接缝：手动 executor（入队不执行，测试受控 drive）+ 真实单线程 scheduler
 * （防抖到期真实异步发生）。生产环境里 executor 是 CodeTuiView 的
 * {@code context-usage-refresh} 单线程池、scheduler 是 coordinator 共享 scheduler；
 * 被测对象与生产构造完全一致，只有驱动时机受控。
 */
class ContextUsageRefreshControllerTest {

    /** 一条 100-token 用户消息的快照：events=1（有历史的可见下限）。 */
    private static final ContextStats STEP = new ContextStats(1, 1, 0, 0, 0, 100L, 0L, 0L, 0, 0, 0, 0L);
    /** 与 STEP 每字段相等的新 record 实例（可见输出不变 → refresh 必须返回 false）。 */
    private static final ContextStats STEP_SAME = new ContextStats(1, 1, 0, 0, 0, 100L, 0L, 0L, 0, 0, 0, 0L);
    /** events/token 变化（可见输出变化）。 */
    private static final ContextStats STEP_MORE = new ContextStats(2, 1, 1, 0, 0, 200L, 0L, 0L, 0, 0, 0, 0L);

    /**
     * 手动 executor：任务入队不执行，测试在受控时机 {@link #runNext} 执行。
     * 语义与生产单线程 executor 一致——FIFO、一次一个任务。
     */
    private static final class ManualExecutor implements Executor {
        private final CopyOnWriteArrayList<Runnable> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        /** 执行最早入队的任务（FIFO）。 */
        void runNext() {
            tasks.remove(0).run();
        }

        int queued() {
            return tasks.size();
        }
    }

    /**
     * 可编程的 {@link ContextUsage} 源：测试中途可换脚本（换值 / 抛异常 / 阻塞）。
     * {@code reads} 同时就是 refresh 执行次数——每次 refresh 必现算一次 source。
     */
    private static final class ScriptedSource {
        final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<Supplier<ContextStats>> behavior =
                new AtomicReference<>(() -> STEP);

        void set(Supplier<ContextStats> next) {
            behavior.set(next);
        }

        ContextStats get() {
            reads.incrementAndGet();
            return behavior.get().get();
        }

        ContextUsage usage(Consumer<String> sink) {
            return new ContextUsage(this::get, sink);
        }
    }

    private ScheduledExecutorService scheduler;
    private ManualExecutor executor;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ctx-usage-test-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor = new ManualExecutor();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 等待手动 executor 队列出现至少 n 个任务（防抖到期在 scheduler 线程异步发生，轮询收敛）。 */
    private void awaitQueued(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (executor.queued() < n) {
            assertTrue(System.nanoTime() < deadline, "等待 executor 任务超时（期望 " + n + " 个）");
            // noinspection BusyWait
            Thread.sleep(5);
        }
    }

    /** 供脚本用：阻塞至 latch 打开再返回给定快照（模拟耗时 token 估算）。 */
    private static Supplier<ContextStats> blockedThen(CountDownLatch entered, CountDownLatch release,
                                                      ContextStats result) {
        return () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("unexpected interrupt", e);
            }
            return result;
        };
    }

    @Test
    @DisplayName("markDirty 突发被防抖合并为一个 refresh 任务")
    void markDirtyBurstIsDebouncedIntoOneRefresh() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        ScriptedSource source = new ScriptedSource();
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(50), callbacks::incrementAndGet)) {

            assertFalse(controller.refreshInFlight(), "尚未执行任何 refresh");
            for (int i = 0; i < 50; i++) {
                controller.markDirty();
            }

            awaitQueued(1);
            assertEquals(1, executor.queued(), "防抖窗口内 50 次标脏只产生 1 个 refresh 任务");

            executor.runNext();
            assertEquals(1, source.reads.get(), "恰好一次 refresh（source 现算一次）");
            assertEquals(1, callbacks.get(), "可见数据变化（empty→有历史）→ 恰好一次回调");
            assertFalse(controller.refreshInFlight(), "完成后单飞权归还");
        }
    }

    @Test
    @DisplayName("防抖窗口内的重复 markDirty 不叠加第二个任务")
    void debounceWindowRepeatedMarksStayOneTask() throws Exception {
        ScriptedSource source = new ScriptedSource();
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(80), () -> { })) {

            controller.markDirty();
            Thread.sleep(30);
            controller.markDirty();
            Thread.sleep(30);
            controller.markDirty();

            awaitQueued(1);
            Thread.sleep(150);   // 后续窗口全部滑过，不允许再排第二个任务
            assertEquals(1, executor.queued(), "滑动窗口内重复标脏不叠加任务");
            assertEquals(0, source.reads.get(), "任务未执行前不得触碰 source");
        }
    }

    @Test
    @DisplayName("在飞 refresh 期间再标脏只记欠账，完成后最多再启一次")
    void dirtyDuringInFlightLeadsToExactlyOneCatchUp() throws Exception {
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        // 第一刷：可见数据变化，但阻塞在 source 里，直到测试在飞期间标脏后放行
        ScriptedSource source = new ScriptedSource();
        source.set(blockedThen(refreshEntered, releaseRefresh, STEP));
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), callbacks::incrementAndGet)) {

            controller.markDirty();
            awaitQueued(1);

            Thread worker = new Thread(executor::runNext, "refresh-worker");
            worker.start();
            assertTrue(refreshEntered.await(10, TimeUnit.SECONDS), "refresh 必须已进入 source");
            assertTrue(controller.refreshInFlight(), "阻塞在 source 期间必须报在飞");

            // 在飞期间连标 3 次脏：只记欠账，不得堆任务（欠账合并为一个追赶标志）
            controller.markDirty();
            controller.markDirty();
            controller.markDirty();
            assertEquals(0, executor.queued(), "在飞期间不得提交新 refresh 任务");

            releaseRefresh.countDown();
            worker.join(10_000);

            // 完成复查吃到欠账：恰好再排一个追赶任务（单飞权随之移交，保持持有状态）
            awaitQueued(1);
            assertEquals(1, executor.queued(), "追赶最多再启一次");
            executor.runNext();
            assertEquals(2, source.reads.get(), "共两次 refresh：首刷 + 一次追赶（3 次标脏合并）");
            assertEquals(1, callbacks.get(), "追赶刷数据未变（仍 STEP）→ 不新增回调");
            assertFalse(controller.refreshInFlight(), "追赶完成后单飞权归还");
        }
    }

    @Test
    @DisplayName("在飞期间未再标脏：完成后不排任何追赶任务")
    void completionWithoutNewDirtySchedulesNothing() throws Exception {
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        ScriptedSource source = new ScriptedSource();
        source.set(blockedThen(refreshEntered, releaseRefresh, STEP));
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), () -> { })) {

            controller.markDirty();
            awaitQueued(1);
            Thread worker = new Thread(executor::runNext, "refresh-worker");
            worker.start();
            assertTrue(refreshEntered.await(10, TimeUnit.SECONDS));
            releaseRefresh.countDown();
            worker.join(10_000);

            Thread.sleep(150);
            assertEquals(0, executor.queued(), "无新标脏 → 完成后不排追赶任务");
            assertEquals(1, source.reads.get());
            assertFalse(controller.refreshInFlight());
        }
    }

    @Test
    @DisplayName("缓存可见数据未变：不回调 onRefreshed")
    void noCallbackWhenCacheUnchanged() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        // 每次都返回等值快照：首刷 empty→STEP_SAME 是变化，其后全部不变
        ScriptedSource source = new ScriptedSource();
        source.set(() -> STEP_SAME);
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), callbacks::incrementAndGet)) {

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(1, callbacks.get(), "首刷 empty→有数据是可见变化 → 回调一次");

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(1, callbacks.get(), "等值新快照 → 可见输出一字不变 → 不回调");
            assertFalse(controller.refreshInFlight());
        }
    }

    @Test
    @DisplayName("缓存可见数据变化：每次真实变化都恰回调一次")
    void callbackFiresForEachVisibleChange() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ScriptedSource source = new ScriptedSource();
        source.set(() -> {
            int n = calls.incrementAndGet();
            return n == 1 ? STEP : n == 2 ? STEP_MORE : STEP;
        });
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), callbacks::incrementAndGet)) {

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(1, callbacks.get(), "empty → STEP：变化");

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(2, callbacks.get(), "STEP → STEP_MORE：变化");

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(3, callbacks.get(), "STEP_MORE → STEP：变化（变回去也是变化）");
        }
    }

    @Test
    @DisplayName("refresh 抛异常：不回调、不卡死，后续标脏仍可正常刷新")
    void exceptionIsSwallowedAndControllerStaysAlive() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        ScriptedSource source = new ScriptedSource();
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), callbacks::incrementAndGet)) {

            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(1, callbacks.get(), "先建立非空缓存");

            // 第二刷：source 抛异常（ContextUsage 容错：保留旧缓存、返回 false）
            source.set(() -> {
                throw new IllegalStateException("token estimation failed");
            });
            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(1, callbacks.get(), "异常 → false → 不回调");

            // 第三刷：source 恢复且可见数据变化 → 控制器仍活着、正常回调
            source.set(() -> STEP_MORE);
            controller.markDirty();
            awaitQueued(1);
            executor.runNext();
            assertEquals(2, callbacks.get(), "异常不卡死后续刷新");
            assertFalse(controller.refreshInFlight());
        }
    }

    @Test
    @DisplayName("stop 后 markDirty 不再排任务，已武装的防抖到期也是 no-op")
    void stopRejectsNewWorkAndCancelsArmedDebounce() throws Exception {
        ScriptedSource source = new ScriptedSource();
        ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(20), () -> { });
        try {
            controller.markDirty();   // 武装防抖（20ms 后到期）
        } finally {
            controller.stop();        // 到期前 stop：取消 timer
        }

        controller.markDirty();
        controller.markDirty();
        Thread.sleep(150);            // 迟到的防抖窗口全部滑过
        assertEquals(0, executor.queued(), "stop 后不得再提交任何 refresh 任务");
        assertEquals(0, source.reads.get(), "stop 后从未触碰 source");
        assertFalse(controller.refreshInFlight());
    }

    @Test
    @DisplayName("stop 在 refresh 在飞时调用：完成路径不再续排、不再回调")
    void stopDuringInFlightRefreshEndsQuietly() throws Exception {
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        ScriptedSource source = new ScriptedSource();
        source.set(blockedThen(refreshEntered, releaseRefresh, STEP));
        ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), callbacks::incrementAndGet);
        controller.markDirty();
        awaitQueued(1);
        Thread worker = new Thread(executor::runNext, "refresh-worker");
        worker.start();
        assertTrue(refreshEntered.await(10, TimeUnit.SECONDS));
        controller.stop();            // 在飞期间 stop
        controller.markDirty();       // 追赶候选：必须被拒绝
        releaseRefresh.countDown();
        worker.join(10_000);

        assertEquals(1, source.reads.get(), "stop 前已进入的第一刷允许完成（无法安全中断）");
        assertEquals(0, callbacks.get(), "stop 后即使数据变了也不回调");
        assertEquals(0, executor.queued(), "stop 后不排追赶任务");
        Thread.sleep(100);
        assertEquals(0, executor.queued(), "迟到窗口滑过后仍无任务");
    }

    @Test
    @DisplayName("close() 等价 stop()")
    void closeIsStop() throws Exception {
        ScriptedSource source = new ScriptedSource();
        ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), executor, scheduler,
                Duration.ofMillis(10), () -> { });
        controller.close();
        controller.markDirty();
        Thread.sleep(80);
        assertEquals(0, executor.queued(), "close 后不再接受新任务");
    }

    @Test
    @DisplayName("8 线程并发标脏压测：source 绝不并发进入，refresh 次数远小于标脏次数")
    void concurrentMarkDirtyNeverOverlapsAndCoalesces() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        ScriptedSource source = new ScriptedSource();
        source.set(() -> {
            int now = inside.incrementAndGet();
            maxInside.accumulateAndGet(now, Math::max);
            inside.decrementAndGet();
            return STEP;
        });
        ExecutorService realExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ctx-usage-stress");
            t.setDaemon(true);
            return t;
        });
        try (ContextUsageRefreshController controller = new ContextUsageRefreshController(
                source.usage(line -> { }), realExecutor, scheduler,
                Duration.ofMillis(5), () -> { })) {

            ExecutorService markers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> publishes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    publishes.add(markers.submit(() -> {
                        start.await();
                        for (int j = 0; j < 200; j++) {
                            controller.markDirty();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> publish : publishes) {
                    publish.get(10, TimeUnit.SECONDS);
                }
            } finally {
                markers.shutdownNow();
            }

            // 静默收敛：连续 100ms 无新 refresh（> 防抖 5ms + 完成复查）即视为排空
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int lastReads;
            do {
                lastReads = source.reads.get();
                long inner = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                while (controller.refreshInFlight() && System.nanoTime() < inner) {
                    // noinspection BusyWait
                    Thread.sleep(5);
                }
                // noinspection BusyWait
                Thread.sleep(100);
            } while (source.reads.get() != lastReads && System.nanoTime() < deadline);

            assertFalse(controller.refreshInFlight(), "压测排空后不得残留单飞权");
            assertTrue(source.reads.get() >= 1, "至少刷新过一次");
            assertEquals(1, maxInside.get(), "source 绝不并发进入（单飞 + 单线程 executor 双保险）");
            assertTrue(source.reads.get() < 8 * 200,
                    "refresh 次数必须远小于 1600 次标脏（合并生效），实际=" + source.reads.get());
        } finally {
            realExecutor.shutdownNow();
        }
    }
}
