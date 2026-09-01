package io.github.javaside.springai.codetui.ui.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UiUpdateCoordinator} 的合并与一次性调度契约（事件驱动 UI 的 Task 3）：
 *
 * <ul>
 *   <li>高频并发 onUiChanged 只产生有界数量的已调度 UI update；</li>
 *   <li>所有 bits（含 OUTPUT | VIEW | CONTROL 组合）都被送达 processor，不丢；</li>
 *   <li>清 scheduled 之后的复查窗口内新 publish 不会丢唤醒（必要时第二个批）；</li>
 *   <li>outputRemaining=true 时无需生产者事件即安排 continuation 并最终排空；</li>
 *   <li>preview/resize/animation 每类至多一个在飞 generation；resize 替换后旧 action 不执行；</li>
 *   <li>stop() 取消所有 timer；迟到回调与后续 publish 都是 no-op；</li>
 *   <li>processor 抛异常不会永久持有 scheduled=true；</li>
 *   <li>8 线程 × 500 次 token 通知压测（Task 2 结转）断言调度上界有界。</li>
 * </ul>
 *
 * <p>测试通过 {@link UiUpdateCoordinator#UiUpdateCoordinator(Consumer, ScheduledExecutorService, UpdateProcessor)}
 * 接缝构造 coordinator：该接缝与生产构造函数（接收 {@code InlineTuiRunner}）只差
 * {@code requestUiUpdate} 的目标，行为完全一致。{@link QueuingRunnerSeam} 把投递的 update
 * 存起来由测试显式执行，保证确定性；{@link InlineRunnerSeam} 在投递线程立即执行，
 * 用于并发压测。
 */
class UiUpdateCoordinatorTest {

    /**
     * 确定性 runner 接缝：记录投递的 update，由测试在受控时机执行。
     * 只有持锁的投递者能进入队列；CAS 保证每个时刻至多一个已调度 update。
     */
    private static final class QueuingRunnerSeam implements Consumer<Runnable> {
        final AtomicInteger delivered = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void accept(Runnable action) {
            delivered.incrementAndGet();
            pending.add(action);
        }

        /** 逐个执行已投递的 update（含执行期间新投递的），直到队列为空。 */
        void deliverAll() {
            while (!pending.isEmpty()) {
                List<Runnable> snapshot = new ArrayList<>(pending);
                pending.clear();
                for (Runnable action : snapshot) {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        // 与 InlineTuiRunner 的 Throwable 防护等价：记录并继续下一个 action
                        failures.add(t);
                    }
                }
            }
        }
    }

    /** 内联 runner 接缝：投递线程立即执行 update（压测用）。 */
    private static final class InlineRunnerSeam implements Consumer<Runnable> {
        final AtomicInteger delivered = new AtomicInteger();

        @Override
        public void accept(Runnable action) {
            delivered.incrementAndGet();
            action.run();
        }
    }

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "coordinator-test-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("1000 个并发 onUiChanged(OUTPUT) 只产生一次 update 投递且 OUTPUT 位送达")
    void thousandConcurrentPublishesAreCoalescedIntoBoundedWork() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.start();

            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> publishes = new ArrayList<>();
                for (int i = 0; i < 1_000; i++) {
                    publishes.add(callers.submit(() -> {
                        start.await();
                        coordinator.onUiChanged(UiDirty.OUTPUT);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> publish : publishes) {
                    publish.get(10, TimeUnit.SECONDS);
                }
            } finally {
                callers.shutdownNow();
            }

            assertTrue(coordinator.updateScheduled(), "publisher 完成后必须有已调度 update");
            // 4000 次通知远未执行任何 update（seam 只排队），投递数必须为 1（CAS 赢家）
            assertEquals(1, seam.delivered.get(),
                    "未执行任何批时只允许一次投递，实际=" + seam.delivered.get());
            seam.deliverAll();

            assertEquals(1, seen.size(), "单个批必须吃掉全部合并位");
            assertTrue(UiDirty.contains(seen.get(0), UiDirty.OUTPUT));
            assertEquals(0, coordinator.pendingDirtyBits());
            assertFalse(coordinator.updateScheduled(), "批后无新 publish 不得再调度");
            assertEquals(1, seam.delivered.get(), "执行批不得追加投递");
        }
    }

    @Test
    @DisplayName("OUTPUT | VIEW | CONTROL 组合位全部送达")
    void combinedBitsAreAllDeliveredInOneBatch() {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.start();
            coordinator.onUiChanged(UiDirty.OUTPUT);
            coordinator.onUiChanged(UiDirty.VIEW);
            coordinator.onUiChanged(UiDirty.CONTROL);
            seam.deliverAll();

            assertEquals(1, seen.size(), "三个 publish 应合并为单个批");
            int bits = seen.get(0);
            assertTrue(UiDirty.contains(bits, UiDirty.OUTPUT));
            assertTrue(UiDirty.contains(bits, UiDirty.VIEW));
            assertTrue(UiDirty.contains(bits, UiDirty.CONTROL));
            assertEquals(0, coordinator.pendingDirtyBits());
            assertFalse(coordinator.updateScheduled());
        }
    }

    @Test
    @DisplayName("清 scheduled 与复查之间发布的位不丢：进入复查发现的第二个批")
    void publishBetweenClearAndRecheckProducesSecondBatch() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        // 关键编排：第一个批执行中（清 scheduled 之前）卡住 processor；
        // 主线程此刻 publish VIEW —— 由于 scheduled 仍为 true，这个 publish 不投递；
        // runBatch 在 finally 清 scheduled 后必须复查 dirty/generation，
        // 发现 VIEW 未消费 → 重新取得调度权并投递第二个 update。
        CountDownLatch firstBatchEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);
        AtomicReference<UiUpdateCoordinator> coordinatorRef = new AtomicReference<>();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    if (seen.size() == 1) {
                        firstBatchEntered.countDown();
                        try {
                            assertTrue(releaseFirstBatch.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinatorRef.set(coordinator);
            coordinator.start();
            coordinator.onUiChanged(UiDirty.OUTPUT);
            assertEquals(1, seam.delivered.get());

            Thread batchThread = new Thread(seam::deliverAll, "batch-thread");
            batchThread.start();
            assertTrue(firstBatchEntered.await(5, TimeUnit.SECONDS),
                    "第一个批必须进入 processor");

            // processor 尚未返回 → scheduled 仍为 true → 此 publish 只 OR 位
            coordinator.onUiChanged(UiDirty.VIEW);
            assertEquals(1, seam.delivered.get(), "批执行期间 publish 不得立即投递");

            releaseFirstBatch.countDown();
            batchThread.join(5_000);
            assertFalse(batchThread.isAlive());

            // 复查发现 VIEW → 第二个批
            seam.deliverAll();
            assertEquals(2, seen.size(), "复查必须补第二个批: " + seen);
            assertTrue(UiDirty.contains(seen.get(0), UiDirty.OUTPUT));
            assertTrue(UiDirty.contains(seen.get(1), UiDirty.VIEW));
            assertEquals(0, coordinator.pendingDirtyBits());
            assertFalse(coordinator.updateScheduled());
        }
    }

    @Test
    @DisplayName("outputRemaining=true 无生产者事件也安排 continuation 并最终排空")
    void outputRemainingSchedulesContinuationWithoutProducerEvent() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        AtomicInteger batches = new AtomicInteger();
        CountDownLatch drained = new CountDownLatch(1);
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    boolean more = batches.incrementAndGet() < 3;
                    if (!more) {
                        drained.countDown();
                    }
                    return new UiUpdateCoordinator.UpdateResult(more, false);
                })) {
            coordinator.start();
            // 初始生产者事件一次
            coordinator.onUiChanged(UiDirty.OUTPUT);
            seam.deliverAll();
            assertEquals(1, batches.get());

            // 此后无任何生产者事件：continuation 必须靠 UpdateResult.outputRemaining 续排
            awaitTrue(() -> {
                seam.deliverAll();
                return drained.getCount() == 0;
            }, Duration.ofSeconds(5), "continuation 链必须最终排空到 idle");
            assertEquals(3, batches.get(), "两个 continuation 批后必须 idle");
        }
    }

    @Test
    @DisplayName("preview 至多一个在飞 generation：重复调度只触发一次到期批")
    void previewKeepsSingleGeneration() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger batches = new AtomicInteger();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    batches.incrementAndGet();
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.start();
            for (int i = 0; i < 5; i++) {
                coordinator.schedulePreview(Duration.ofMillis(40));
            }
            Thread.sleep(120);
            seam.deliverAll();
            Thread.sleep(80);
            seam.deliverAll();
            assertEquals(1, batches.get(),
                    "5 次 schedulePreview 在同一窗口内只允许一个到期 VIEW 批，实际=" + batches.get());
        }
    }

    @Test
    @DisplayName("替换 resize settle 后旧 action 不执行，新 action 执行")
    void replacingResizeSettleSuppressesStaleAction() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger staleRuns = new AtomicInteger();
        AtomicInteger freshRuns = new AtomicInteger();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> UiUpdateCoordinator.UpdateResult.idle())) {
            coordinator.start();
            coordinator.scheduleResizeSettle(Duration.ofMillis(80), staleRuns::incrementAndGet);
            coordinator.scheduleResizeSettle(Duration.ofMillis(10), freshRuns::incrementAndGet);

            // settle 到期后 uiAction 经 requestUiUpdate 投递：轮询投递直到新 action 执行
            awaitTrue(() -> {
                seam.deliverAll();
                return freshRuns.get() >= 1;
            }, Duration.ofSeconds(5), "新 settle 必须执行");
            Thread.sleep(150); // 旧 settle 的到期时间也过去
            seam.deliverAll();
            assertEquals(1, freshRuns.get());
            assertEquals(0, staleRuns.get(), "被替换的旧 settle action 不得执行");
        }
    }

    @Test
    @DisplayName("animation demand 驱动帧且只保留一个在飞 generation")
    void animationFramesAreDemandDriven() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger frames = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    int frame = frames.incrementAndGet();
                    if (frame >= 3) {
                        done.countDown();
                        return new UiUpdateCoordinator.UpdateResult(false, false);
                    }
                    return new UiUpdateCoordinator.UpdateResult(false, true);
                })) {
            coordinator.start();
            coordinator.updateAnimationDemand(true, Duration.ofMillis(10));
            // 动画帧由 timer 到期 publish VIEW 驱动：轮询投递直到 3 帧完成
            awaitTrue(() -> {
                seam.deliverAll();
                return done.getCount() == 0;
            }, Duration.ofSeconds(5), "动画帧必须按需推进");
            assertEquals(0, done.getCount(), "3 帧必须完成");
            assertTrue(frames.get() >= 3, "至少 3 帧，实际=" + frames.get());
        }
    }

    @Test
    @DisplayName("animation 关闭后不再产生帧")
    void animationStopsWhenDemandDisappears() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger frames = new AtomicInteger();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    frames.incrementAndGet();
                    // processor 永远宣称 animation 活跃：必须由 demand=false 停止
                    return new UiUpdateCoordinator.UpdateResult(false, true);
                })) {
            coordinator.start();
            coordinator.updateAnimationDemand(true, Duration.ofMillis(10));
            coordinator.updateAnimationDemand(false, Duration.ofMillis(10));
            Thread.sleep(150);
            seam.deliverAll();
            int after = frames.get();
            Thread.sleep(120);
            seam.deliverAll();
            assertEquals(after, frames.get(), "动画关闭后不得继续产生帧");
        }
    }

    @Test
    @DisplayName("stop() 取消所有 timer，迟到回调与后续 publish 都是 no-op")
    void stopCancelsTimersAndLateCallbacksAreNoOps() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        AtomicInteger resizeActions = new AtomicInteger();
        UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    return UiUpdateCoordinator.UpdateResult.idle();
                });
        coordinator.start();
        coordinator.schedulePreview(Duration.ofMillis(60));
        coordinator.scheduleResizeSettle(Duration.ofMillis(60), resizeActions::incrementAndGet);
        coordinator.scheduleOutputContinuation(Duration.ofMillis(60));
        coordinator.updateAnimationDemand(true, Duration.ofMillis(20));

        coordinator.stop();

        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, coordinator.lifecycle());
        Thread.sleep(200);
        seam.deliverAll();
        assertEquals(0, seen.size(), "stop 后到期 timer 不得触发 processor");
        assertEquals(0, resizeActions.get(), "stop 后 resize action 不得执行");

        // 迟到 publish 与再调度全部 no-op
        coordinator.onUiChanged(UiDirty.ALL);
        assertFalse(coordinator.updateScheduled());
        coordinator.schedulePreview(Duration.ofMillis(1));
        coordinator.scheduleOutputContinuation(Duration.ofMillis(1));
        coordinator.scheduleResizeSettle(Duration.ofMillis(1), resizeActions::incrementAndGet);
        coordinator.updateAnimationDemand(true, Duration.ofMillis(1));
        Thread.sleep(120);
        seam.deliverAll();
        assertEquals(0, seen.size());
        assertEquals(0, resizeActions.get());
        assertEquals(0, coordinator.pendingDirtyBits());
        coordinator.close();
    }

    @Test
    @DisplayName("processor 抛异常不永久持有 scheduled=true，后续 publish 可再调度")
    void processorFailureReleasesScheduled() {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger calls = new AtomicInteger();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("processor failed");
                    }
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.start();
            coordinator.onUiChanged(UiDirty.OUTPUT);
            seam.deliverAll();

            assertFalse(coordinator.updateScheduled(), "processor 失败后 scheduled 必须被 finally 释放");
            assertEquals(0, coordinator.pendingDirtyBits(), "失败批的位已被取走，不得残留");
            assertEquals(1, seam.failures.size(), "processor 异常必须照原样上抛给 runner 防护");

            coordinator.onUiChanged(UiDirty.OUTPUT);
            seam.deliverAll();
            assertEquals(2, calls.get(), "后续 publish 必须能再次调度");
            assertEquals(1, seam.failures.size(), "第二个批必须成功");
        }
    }

    @Test
    @DisplayName("8 线程 × 500 次 token 通知压测：调度上界有界（Task 2 结转）")
    void eightThreadsFiveHundredTokenNotificationsKeepSchedulingBounded() throws Exception {
        InlineRunnerSeam seam = new InlineRunnerSeam();
        CopyOnWriteArrayList<Integer> seen = new CopyOnWriteArrayList<>();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    seen.add(bits);
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.start();

            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> publishes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    publishes.add(callers.submit(() -> {
                        start.await();
                        for (int n = 0; n < 500; n++) {
                            coordinator.onUiChanged(UiDirty.OUTPUT);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> publish : publishes) {
                    publish.get(30, TimeUnit.SECONDS);
                }
            } finally {
                callers.shutdownNow();
            }

            // 内联接缝下最坏情形：每个 publish 恰逢 scheduled==false 的空窗 → 各投一个批。
            // 上界证明分两层：
            //   1) 结构上界：delivered ≤ 通知数 4000，且批内位合并不丢；
            //   2) 有界性（不随通知数线性增长）：当 UI 线程滞后（真实 runner 场景）时，
            //      scheduled 标志把并发窗口内的全部通知合并进一个批 —— 用"滞后投递"
            //      场景直接断言 4000 次通知只产生 1 个已调度 update。
            assertTrue(seam.delivered.get() >= 1);
            assertTrue(seam.delivered.get() <= 4_000,
                    "投递数不得超通知数: " + seam.delivered.get());
            int combined = 0;
            for (int bits : seen) {
                combined |= bits;
            }
            assertTrue(UiDirty.contains(combined, UiDirty.OUTPUT), "位不得丢失");
            assertEquals(0, coordinator.pendingDirtyBits(), "压测结束不得残留位");
            assertFalse(coordinator.updateScheduled());
        }

        // 滞后投递场景：UI 线程不消费（QueuingRunnerSeam 只排队），
        // 4000 次并发通知必须只产生 1 个已调度 update —— 调度上界与通知数无关。
        QueuingRunnerSeam lagging = new QueuingRunnerSeam();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                lagging, scheduler,
                bits -> UiUpdateCoordinator.UpdateResult.idle())) {
            coordinator.start();
            ExecutorService callers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> publishes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    publishes.add(callers.submit(() -> {
                        start.await();
                        for (int n = 0; n < 500; n++) {
                            coordinator.onUiChanged(UiDirty.OUTPUT);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> publish : publishes) {
                    publish.get(30, TimeUnit.SECONDS);
                }
            } finally {
                callers.shutdownNow();
            }
            assertEquals(1, lagging.delivered.get(),
                    "UI 滞后时 4000 次通知只允许 1 个已调度 update，实际=" + lagging.delivered.get());
            lagging.deliverAll();
            assertEquals(0, coordinator.pendingDirtyBits());
        }
    }

    @Test
    @DisplayName("生命周期：NEW → RUNNING → STOPPED；start/stop 幂等")
    void lifecycleTransitions() {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler, bits -> UiUpdateCoordinator.UpdateResult.idle());
        assertEquals(UiUpdateCoordinator.Lifecycle.NEW, coordinator.lifecycle());
        coordinator.start();
        assertEquals(UiUpdateCoordinator.Lifecycle.RUNNING, coordinator.lifecycle());
        coordinator.stop();
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, coordinator.lifecycle());
        coordinator.stop(); // 幂等
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, coordinator.lifecycle());

        UiUpdateCoordinator second = new UiUpdateCoordinator(
                seam, scheduler, bits -> UiUpdateCoordinator.UpdateResult.idle());
        second.start();
        second.start(); // 幂等
        assertEquals(UiUpdateCoordinator.Lifecycle.RUNNING, second.lifecycle());
        second.close();
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, second.lifecycle());
        coordinator.close();
    }

    @Test
    @DisplayName("未 start 的 coordinator：publish 与 continuation 都是 no-op")
    void publishAndContinuationBeforeStartAreNoOps() throws Exception {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        AtomicInteger calls = new AtomicInteger();
        try (UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler,
                bits -> {
                    calls.incrementAndGet();
                    return UiUpdateCoordinator.UpdateResult.idle();
                })) {
            coordinator.onUiChanged(UiDirty.ALL);
            assertFalse(coordinator.updateScheduled());
            coordinator.scheduleOutputContinuation(Duration.ofMillis(1));
            coordinator.schedulePreview(Duration.ofMillis(1));
            Thread.sleep(100);
            seam.deliverAll();
            assertEquals(0, calls.get(), "未 start 不得触发任何批");
            assertEquals(0, seam.delivered.get(), "未 start 不得投递任何 update");
            assertEquals(0, coordinator.pendingDirtyBits());
        }
    }

    @Test
    @DisplayName("close() 等价于 stop()")
    void closeDelegatesToStop() {
        QueuingRunnerSeam seam = new QueuingRunnerSeam();
        UiUpdateCoordinator coordinator = new UiUpdateCoordinator(
                seam, scheduler, bits -> UiUpdateCoordinator.UpdateResult.idle());
        coordinator.start();
        coordinator.close();
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, coordinator.lifecycle());
        // 重复 close 幂等
        coordinator.close();
        assertEquals(UiUpdateCoordinator.Lifecycle.STOPPED, coordinator.lifecycle());
    }

    @Test
    @DisplayName("UpdateResult.idle() 的 coordinator follow-up 全 false")
    void updateResultIdleHasNoCoordinatorFollowUp() {
        UiUpdateCoordinator.UpdateResult idle = UiUpdateCoordinator.UpdateResult.idle();
        assertFalse(idle.outputRemaining());
        assertFalse(idle.animationActive());
        // previewPending 与 contextUsageDirty 均已删除：两者由 View 直接调各自 controller，
        // runBatch 没有消费者，经 UpdateResult 透传只会形成死参数与双重所有权。
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private static void awaitTrue(java.util.function.Supplier<Boolean> condition,
            Duration deadline, String message) throws InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }
}
