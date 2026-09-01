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
import dev.tamboui.tui.event.ResizeEvent;

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
            // 编辑帧可能晚于 latch 到达再切一次内容（一次重武装）——允许 8..10，但必须是
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

    // ── Task 9 fix round：backend→runner 的 resize 事件链 ─────────────────

    /**
     * backend 的 onResize 回调（生产里由 JLine WINCH handler 触发）必须被 runner
     * 翻译成 {@link ResizeEvent} 交给 handler，且随后发生<b>新宽度</b>的 draw；
     * 同尺寸重复触发不得再发事件（去重契约——JLine 对一次拖拽可能回调多次）。
     *
     * <p>这条链是 resize_smoke.py 失败排查后补上的缺口：PTY 冒烟只覆盖「内核
     * SIGWINCH → JLine → onResize」的前半段（且依赖 harness 正确设置 controlling
     * terminal），「onResize → ResizeEvent → handler → 新宽度重画 → 同尺寸去重」
     * 全在这里以纯 Java 钉死，不再依赖真实终端。
     *
     * <p>宽度真相取自 draw 时 renderer 收到的 {@link Frame}——InlineViewport 每次
     * draw 前都会 {@code syncArea}（display.render → syncWidth 查询 backend），
     * 所以 frame 宽度就是「这帧真正画出来的宽度」，与 resize_smoke.py 断言屏幕
     * 上输入框宽度是同一语义。
     */
    @Test
    void backendResizeCallbackDeliversResizeEventAndRedrawsAtNewWidthWithoutDuplicates() throws Exception {
        MutableSizeBackend backend = new MutableSizeBackend(new Size(60, 24));
        InlineTuiRunner runner = InlineTuiRunner.create(backend, config(POLL_TIMEOUT));

        List<ResizeEvent> resizeEvents = new ArrayList<>();
        List<Integer> frameWidths = new ArrayList<>();
        CountDownLatch resizeHandled = new CountDownLatch(1);
        CountDownLatch frameAt100 = new CountDownLatch(1);

        Thread thread = new Thread(() -> {
            try {
                runner.run((event, activeRunner) -> {
                    if (event instanceof ResizeEvent) {
                        resizeEvents.add((ResizeEvent) event);
                    }
                    return false;   // 返回 false：ResizeEvent 后的 draw 由 runner 自己负责（run 源码语义）
                }, frame -> {
                    frameWidths.add(frame.width());
                    if (frame.width() == 100) {
                        frameAt100.countDown();
                    }
                });
            } catch (Throwable t) {
                // 生命周期异常在 quit/close 断言里暴露，不在测试线程外裸抛。
            }
        }, "inline-runner-resize-test");
        try {
            thread.start();
            awaitFirstFrame(frameWidths);
            assertEquals(List.of(60), distinctWidths(frameWidths),
                    "初始 60 列下 run() 首帧必须以 60 列宽度绘制");

            backend.setSize(new Size(100, 24));
            backend.fireResize();   // 生产语义：JLine WINCH handler 调 onResize 注册的 Runnable

            assertTrue(resizeHandled.await(1, TimeUnit.SECONDS) || !resizeEvents.isEmpty(),
                    "onResize 回调后 handler 必须收到 ResizeEvent");
            assertTrue(frameAt100.await(1, TimeUnit.SECONDS),
                    "收到 ResizeEvent 后必须发生一次新宽度（100 列）的 draw");
            assertEquals(1, resizeEvents.size(), "一次宽度变化只该送达一个 ResizeEvent");
            assertEquals(100, resizeEvents.get(0).width());
            assertEquals(24, resizeEvents.get(0).height());

            // 同尺寸重复触发（一次拖拽中 JLine 可能回调多次）：不得再发事件、不得再画。
            awaitQuietWidths(frameWidths, 300);
            int drawsBefore = frameWidths.size();
            backend.fireResize();
            Thread.sleep(400);      // 与其余测试的「静止窗口」同一量级
            assertEquals(1, resizeEvents.size(),
                    "同尺寸重复 onResize 不得再投递 ResizeEvent（去重契约）");
            assertEquals(drawsBefore, frameWidths.size(),
                    "同尺寸重复 onResize 不得触发额外 draw");
        } finally {
            runner.quit();
            thread.join(1_000);
            runner.close();
        }
        assertFalse(thread.isAlive());
    }

    /** 等第一帧（来自 run() 的 initial draw）落进宽度记录。 */
    private static void awaitFirstFrame(List<Integer> frameWidths) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (frameWidths.isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertFalse(frameWidths.isEmpty(), "run() 的首帧应在 2 秒内发生");
    }

    /** 等帧宽记录静止（连续 quietMillis 无新帧），供重复触发前排除在飞的帧。 */
    private static void awaitQuietWidths(List<Integer> frameWidths, long quietMillis)
            throws InterruptedException {
        int last;
        int stable;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            last = frameWidths.size();
            TimeUnit.MILLISECONDS.sleep(quietMillis);
            stable = frameWidths.size();
        } while (stable != last && System.nanoTime() < deadline);
    }

    /** 按出现顺序去重的宽度序列（首帧断言用）。 */
    private static List<Integer> distinctWidths(List<Integer> widths) {
        List<Integer> distinct = new ArrayList<>();
        for (int width : widths) {
            if (distinct.isEmpty() || distinct.get(distinct.size() - 1) != width) {
                distinct.add(width);
            }
        }
        return distinct;
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

    private static class RecordingBackend implements Backend {
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

    /**
     * 尺寸可变的 RecordingBackend：{@code setSize} 改变 {@link #size()} 的答案，
     * {@code fireResize} 触发 {@link #onResize(Runnable)} 注册的回调——与生产
     * JLineBackend 完全同一语义（WINCH handler 里查 size + 调回调）。
     */
    private static final class MutableSizeBackend extends RecordingBackend {
        private volatile Size size;
        private volatile Runnable resizeHandler;

        MutableSizeBackend(Size initial) {
            this.size = initial;
        }

        @Override public Size size() { return size; }

        @Override public void onResize(Runnable handler) {
            this.resizeHandler = handler;
        }

        void setSize(Size newSize) {
            this.size = newSize;
        }

        /** 模拟一次 SIGWINCH：JLine 的 handler 语义是「回调里现查 size」。 */
        void fireResize() {
            Runnable handler = this.resizeHandler;
            if (handler != null) {
                handler.run();
            }
        }
    }
}
