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

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

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
        private final Thread thread;

        private RunnerFixture(InlineTuiRunner runner, RecordingBackend backend) {
            this.runner = runner;
            this.backend = backend;
            this.thread = new Thread(() -> {
                try {
                    runner.run((event, activeRunner) -> false, frame -> {
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
