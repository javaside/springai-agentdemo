package dev.tamboui.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;

class InlineTuiRunnerEventDrivenTest {

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(25);

    @Test
    void requestUiUpdateWakesRunnerWhenTicksAreDisabled() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            CountDownLatch actionRan = new CountDownLatch(1);
            CountDownLatch updateDraw = fixture.drawNumber(2);

            fixture.runner.requestUiUpdate(actionRan::countDown);

            assertTrue(actionRan.await(1, TimeUnit.SECONDS));
            assertTrue(updateDraw.await(1, TimeUnit.SECONDS));
            assertEquals(2, fixture.draws.get());
        }
    }

    @Test
    void concurrentRenderRequestsAreCoalescedAfterRunnerStartsAndActionPrecedesDraw() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(32);
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            AtomicBoolean actionCompleted = new AtomicBoolean();
            AtomicBoolean actionCompletedAtDraw = new AtomicBoolean();
            CountDownLatch actionStarted = new CountDownLatch(1);
            CountDownLatch releaseAction = new CountDownLatch(1);
            fixture.onDraw = draw -> {
                if (draw == 2) {
                    actionCompletedAtDraw.set(actionCompleted.get());
                }
            };
            CountDownLatch requestsDraw = fixture.drawNumber(3);
            fixture.runner.requestUiUpdate(() -> {
                actionStarted.countDown();
                try {
                    assertTrue(releaseAction.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                actionCompleted.set(true);
            });
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS));

            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> requests = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                requests.add(callers.submit(() -> {
                    start.await();
                    fixture.runner.requestRender();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) {
                request.get(1, TimeUnit.SECONDS);
            }
            releaseAction.countDown();

            assertTrue(requestsDraw.await(1, TimeUnit.SECONDS));
            assertTrue(actionCompletedAtDraw.get());
            assertEquals(3, fixture.draws.get());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void actionSubmittedAfterWakeBoundaryRunsInFollowingBatch() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            AtomicInteger completedActions = new AtomicInteger();
            List<Integer> actionsSeenByDraw = new ArrayList<>();
            CountDownLatch firstActionStarted = new CountDownLatch(1);
            CountDownLatch releaseFirstAction = new CountDownLatch(1);
            fixture.onDraw = draw -> actionsSeenByDraw.add(completedActions.get());
            CountDownLatch secondBatchDraw = fixture.drawNumber(3);

            fixture.runner.requestUiUpdate(() -> {
                firstActionStarted.countDown();
                try {
                    assertTrue(releaseFirstAction.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                completedActions.incrementAndGet();
            });
            assertTrue(firstActionStarted.await(1, TimeUnit.SECONDS));
            fixture.runner.requestUiUpdate(completedActions::incrementAndGet);
            releaseFirstAction.countDown();

            assertTrue(secondBatchDraw.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), actionsSeenByDraw);
            assertEquals(3, fixture.draws.get());
        }
    }

    @Test
    void actionFailureDoesNotKillFollowingActions() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            CountDownLatch followingAction = new CountDownLatch(1);
            CountDownLatch updateDraw = fixture.drawNumber(2);

            fixture.runner.requestUiUpdate(() -> {
                throw new AssertionError("expected test failure");
            });
            fixture.runner.requestUiUpdate(followingAction::countDown);

            assertTrue(followingAction.await(1, TimeUnit.SECONDS));
            assertTrue(updateDraw.await(1, TimeUnit.SECONDS));
            assertTrue(fixture.thread.isAlive());
        }
    }

    @Test
    void idleRunnerDoesNotRenderWithoutEvents() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            int drawsAfterStart = fixture.draws.get();
            CountDownLatch unexpectedDraw = fixture.drawNumber(drawsAfterStart + 1);

            assertFalse(unexpectedDraw.await(POLL_TIMEOUT.multipliedBy(4).toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(drawsAfterStart, fixture.draws.get());
        }
    }

    @Test
    void quitWakesBlockedEventLoop() throws Exception {
        try (RunnerFixture fixture = startRunner(Duration.ofSeconds(5))) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));

            fixture.runner.quit();

            fixture.thread.join(500);
            assertFalse(fixture.thread.isAlive());
        }
    }

    @Test
    void updateRequestsBecomeNoOpsAfterClose() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        InlineTuiRunner runner = InlineTuiRunner.create(backend, config(POLL_TIMEOUT));
        AtomicInteger actions = new AtomicInteger();
        runner.close();

        runner.requestUiUpdate(actions::incrementAndGet);
        runner.requestRender();

        assertEquals(0, actions.get());
    }

    // ── Task 8：IME 光标带修复的按需 follow-up 帧 ─────────────────────────

    /** 等待帧流静止：连续 {@code quietMillis} 无新帧即认为稳定（返回静止时的累计帧数）。 */
    private static int awaitQuietFrames(RunnerFixture fixture, int minFrames, long quietMillis)
            throws InterruptedException {
        int last;
        int stable;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            last = fixture.draws.get();
            TimeUnit.MILLISECONDS.sleep(quietMillis);
            stable = fixture.draws.get();
        } while (stable != last && System.nanoTime() < deadline);
        return stable;
    }

    /**
     * 一次 draw 触及光标带（武装 IME 修复窗口）后，runner 必须按需补排后续 render，
     * 且窗口耗尽后回到静止零输出——不恢复全局 tick。
     *
     * <p>场景：先用「两帧间切换光标带内单元格」的渲染器武装窗口（模拟中文编辑活动），
     * 再冻结内容——窗口内的 8 帧重申全部由 runner 的 follow-up 调度驱动（无全局 tick、
     * 无外部 requestRender），耗尽后必须静止。
     */
    @Test
    void cursorBandRepairArmsDemandDrivenFollowUpFramesThenStops() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            AtomicBoolean editing = new AtomicBoolean();
            AtomicBoolean wide = new AtomicBoolean();
            fixture.renderBody = frame -> {
                Buffer buffer = frame.buffer();
                if (editing.get()) {
                    // 编辑活动：每帧切换光标带内单元格（真实 diff → 触及光标带 → 武装/续期窗口）。
                    buffer.setString(1, 0, wide.getAndSet(!wide.get()) ? "中" : "a", Style.EMPTY);
                } else {
                    // 冻结：内容不再变化——后续帧只剩窗口内的光标带重申（bandTail）。
                    buffer.setString(1, 0, wide.get() ? "中" : "a", Style.EMPTY);
                }
                frame.setCursorPosition(1, 0);
            };

            // 一次编辑帧武装窗口，随后立即冻结内容。
            editing.set(true);
            fixture.runner.requestRender();
            assertTrue(fixture.awaitDraws(2, 1, TimeUnit.SECONDS), "编辑帧必须被绘制");
            int drawsAtFreeze = fixture.draws.get();
            editing.set(false);   // 冻结：后续帧的唯一驱动就是 follow-up 调度

            // follow-up 调度把窗口内的帧补完；补完后帧流静止。
            int settled = awaitQuietFrames(fixture, drawsAtFreeze, 300);
            int followUps = settled - drawsAtFreeze;
            // 编辑帧可能晚于 latch 到达再切一次内容（一次重武装）——允许 8 或 9，但必须是
            // 有界的（任何常驻循环都会远超这个数）。
            assertTrue(followUps >= 8 && followUps <= 10,
                    "窗口内必须由 follow-up 调度补帧（8 帧重申 ±1 次重武装），实际补了 "
                            + followUps + " 帧");

            // 窗口耗尽后静止：不再有任何帧。
            int drawsAfterWindow = fixture.draws.get();
            CountDownLatch unexpected = fixture.drawNumber(drawsAfterWindow + 1);
            assertFalse(unexpected.await(400, TimeUnit.MILLISECONDS),
                    "光标带修复窗口耗尽后必须停止补帧（静止零输出）");
            assertEquals(drawsAfterWindow, fixture.draws.get());
        }
    }

    /** 静止内容（无重申窗口）不得触发任何 follow-up 帧：首帧后的窗口耗尽即永久静止。 */
    @Test
    void idleRunnerWithoutCursorBandActivityDoesNotRenderFollowUps() throws Exception {
        try (RunnerFixture fixture = startRunner(POLL_TIMEOUT)) {
            assertTrue(fixture.initialDraw.await(1, TimeUnit.SECONDS));
            fixture.renderBody = frame -> {
                // 内容完全静止：首帧（从空到有内容）会武装一次窗口，之后窗口耗尽即静止。
                frame.buffer().setString(1, 0, "steady", Style.EMPTY);
                frame.setCursorPosition(1, 0);
            };
            fixture.runner.requestRender();
            // 等首帧后的 follow-up 窗口全部耗尽（帧流静止）。
            awaitQuietFrames(fixture, 2, 300);
            int after = fixture.draws.get();
            CountDownLatch unexpected = fixture.drawNumber(after + 1);
            assertFalse(unexpected.await(400, TimeUnit.MILLISECONDS),
                    "静止内容不得产生 follow-up 帧");
            assertEquals(after, fixture.draws.get());
        }
    }

    private static RunnerFixture startRunner(Duration pollTimeout) throws Exception {
        RecordingBackend backend = new RecordingBackend();
        InlineTuiRunner runner = InlineTuiRunner.create(backend, config(pollTimeout));
        return startRunner(runner, backend);
    }

    private static RunnerFixture startRunner(InlineTuiRunner runner, RecordingBackend backend) {
        RunnerFixture fixture = new RunnerFixture(runner, backend);
        fixture.thread.start();
        return fixture;
    }

    private static InlineTuiConfig config(Duration pollTimeout) {
        return InlineTuiConfig.builder(4)
                .noTick()
                .pollTimeout(pollTimeout)
                .build();
    }

    private static final class RunnerFixture implements AutoCloseable {
        private final InlineTuiRunner runner;
        private final RecordingBackend backend;
        private final AtomicInteger draws = new AtomicInteger();
        private final AtomicReference<CountDownLatch> targetDraw = new AtomicReference<>();
        private final AtomicInteger targetDrawNumber = new AtomicInteger(Integer.MAX_VALUE);
        private final CountDownLatch initialDraw = drawNumber(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile IntConsumer onDraw = draw -> { };
        /** 可编程帧内容（Task 8 follow-up 测试用）：设置后每帧渲染它而非空帧。 */
        private volatile java.util.function.Consumer<Frame> renderBody;
        private final Thread thread;

        private RunnerFixture(InlineTuiRunner runner, RecordingBackend backend) {
            this.runner = runner;
            this.backend = backend;
            this.thread = new Thread(() -> {
                try {
                    runner.run((event, activeRunner) -> false, frame -> {
                        java.util.function.Consumer<Frame> body = renderBody;
                        if (body != null) {
                            body.accept(frame);
                        }
                        int draw = draws.incrementAndGet();
                        onDraw.accept(draw);
                        CountDownLatch latch = targetDraw.get();
                        if (draw >= targetDrawNumber.get() && latch != null) {
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "inline-runner-test");
        }

        private CountDownLatch drawNumber(int drawNumber) {
            CountDownLatch latch = new CountDownLatch(1);
            targetDrawNumber.set(drawNumber);
            targetDraw.set(latch);
            if (draws.get() >= drawNumber) {
                latch.countDown();
            }
            return latch;
        }

        /** 等待累计绘制数达到 {@code target}（从当前值起最多增加 target-current）。 */
        private boolean awaitDraws(int target, long timeout, TimeUnit unit) throws InterruptedException {
            return drawNumber(target).await(timeout, unit);
        }

        @Override
        public void close() throws Exception {
            runner.quit();
            thread.join(1_000);
            runner.close();
            assertFalse(thread.isAlive());
            assertEquals(null, failure.get());
            assertTrue(backend.closed);
        }
    }

    private static final class RecordingBackend implements Backend {
        private volatile boolean closed;

        @Override public void draw(DiffResult diff) { }
        @Override public void flush() { }
        @Override public void clear() { }
        @Override public Size size() { return new Size(80, 24); }
        @Override public void showCursor() { }
        @Override public void hideCursor() { }
        @Override public Position getCursorPosition() { return new Position(0, 0); }
        @Override public void setCursorPosition(Position position) { }
        @Override public void enterAlternateScreen() { }
        @Override public void leaveAlternateScreen() { }
        @Override public void enableRawMode() { }
        @Override public void disableRawMode() { }
        @Override public void writeRaw(byte[] data) throws IOException { }
        @Override public void onResize(Runnable handler) { }
        @Override public int read(int timeoutMs) { return -2; }
        @Override public int peek(int timeoutMs) { return -2; }
        @Override public void close() { closed = true; }
    }
}
