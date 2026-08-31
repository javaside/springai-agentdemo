/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tamboui.inline.InlineDisplay;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.tui.event.UiRunnable;

/**
 * Event loop for inline displays.
 *
 * <p>本类是 {@code dev.tamboui:tamboui-tui:0.4.0} 的<b>同包同名 shadow（覆盖）类</b>，
 * 由 {@code springai-tamboui-inline-patch} 模块通过 classpath 顺序（该模块 jar 排在
 * {@code tamboui-tui} 之前，见 {@code springai-code-tui} 的 MANIFEST Class-Path）优先加载。
 *
 * <p><b>为什么需要它</b>：原版 {@code InlineTuiRunner.run()} 的事件循环对
 * {@link #run(InlineEventHandler, Renderer)} 里的 {@code handler.handle(...)} 与
 * {@code UiRunnable.run()} <b>没有任何 try-catch</b>。而全屏版 {@link TuiRunner#run} 对这两处都有
 * {@code try{...}catch(Throwable){ handleRenderError(t); continue; }} 防护。两者对比之下，行内版
 * 一旦 UI 按键处理链（{@code CodeTuiView.onInputKey → onModelPickerKey / onAskKey / …}）抛出任何未捕获
 * {@link RuntimeException}（最典型的是 {@code % n} 除零当 n==0），异常会<b>直接逃出
 * {@code while(running.get())}</b> 循环 → 渲染线程终结；而 {@code running} 仍为 {@code true}，
 * 输入线程继续往 {@code eventQueue} 投事件却<b>无人消费</b> → TUI 永久定格、任何按键（输入框打字、
 * {@code /model} 选择器回车）一概失效。这正是「卡死、输入不了」的根因。
 *
 * <p>修复只做一件事：前述两处包上 {@code try(Throwable)}，捕获后记录日志并 {@code continue}，
 * 让渲染线程<b>永不因 handler 异常而死亡</b>。其余构造/生命周期逻辑与原版<b>逐字一致</b>。
 *
 * @see InlineDisplay
 * @see TuiRunner
 */
public final class InlineTuiRunner implements AutoCloseable {

    private static final Logger log = Logger.getLogger(InlineTuiRunner.class.getName());

    /**
     * IME 光标带 follow-up 帧间隔（毫秒）。
     *
     * <p>沿用原视觉节拍的量级：code-tui 旧帧长 100ms（见 InlineDisplay 的
     * {@code CURSOR_BAND_REPAIR_FRAMES} 注释「帧数 × 当前帧长」），窗口 8 帧 ≈ 800ms。
     * 事件驱动后没有全局 tick，本值就是窗口的<b>实际帧长</b>——保持 ~100ms 使修复窗口的
     * 时长与重构前同量级（窗口覆盖 IME 异步清理的滞后区间，砍短会漏修复）。
     */
    private static final long IME_FOLLOW_UP_DELAY_MS = 100;

    private final Backend backend;
    private final InlineViewport viewport;
    private final InlineTuiConfig config;
    private final BlockingQueue<Event> eventQueue;
    private final AtomicBoolean running;
    private final AtomicBoolean cleanedUp;
    private final Object uiActionsLock = new Object();
    private ConcurrentLinkedQueue<Runnable> uiActions = new ConcurrentLinkedQueue<>();
    private boolean renderRequested;
    private final AtomicBoolean uiUpdateQueued = new AtomicBoolean();
    private volatile Renderer activeRenderer;
    private final ScheduledExecutorService scheduler;
    private final boolean schedulerOwned;
    private final AtomicLong frameCount;
    private final Thread shutdownHook;
    private final AtomicReference<Instant> lastTick;
    private final AtomicReference<Instant> nextTickTime;
    private final AtomicReference<Size> lastSize;
    private final TerminalInputReader inputReader;
    /**
     * IME 光标带修复的 follow-up 帧在飞标志（Task 8）。
     *
     * <p>一次 draw 武装了修复窗口（{@code viewport.needsFollowUpFrame() == true}）时，runner
     * 在自身 scheduler 上排一个<b>一次性</b>延迟任务请求合并重绘，到位后 draw 再看窗口是否仍在。
     * CAS 保证至多一个 follow-up 在飞；窗口耗尽（draw 后 {@code needsFollowUpFrame() == false}）
     * 即不再续排，静止界面零帧输出。<b>绝不 scheduleAtFixedRate</b>——那是被删除的常驻 tick。
     */
    private final AtomicBoolean imeFollowUpScheduled = new AtomicBoolean();

    private InlineTuiRunner(Backend backend, InlineViewport viewport, InlineTuiConfig config) {
        this.backend = backend;
        this.viewport = viewport;
        this.config = config;
        this.eventQueue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(true);
        this.cleanedUp = new AtomicBoolean(false);
        this.frameCount = new AtomicLong(0);
        this.lastTick = new AtomicReference<>(Instant.now());
        this.nextTickTime = new AtomicReference<>(
                config.tickRate() != null ? Instant.now().plus(config.tickRate()) : null);
        this.lastSize = new AtomicReference<>(readCurrentSize(backend));

        backend.onResize(() -> {
            try {
                Size newSize = backend.size();
                Size previous = lastSize.getAndSet(newSize);
                if (!newSize.equals(previous)) {
                    eventQueue.offer(ResizeEvent.of(newSize.width(), newSize.height()));
                }
            } catch (IOException e) {
                // Ignore resize errors
            }
        });

        // Set up scheduler - use provided scheduler or create one
        Schedulers.Scheduler scheduler = Schedulers.resolve(config.scheduler());
        this.scheduler = scheduler.scheduler();
        this.schedulerOwned = scheduler.owned();

        // Only schedule the internal callback if ticks are enabled
        if (config.ticksEnabled() && config.tickRate() != null) {
            long periodMs = config.tickRate().toMillis();
            this.scheduler.scheduleAtFixedRate(this::schedulerCallback, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }

        // Create and start the input reader thread
        this.inputReader = new TerminalInputReader(backend, eventQueue, config.bindings(), running, config.pollTimeout());
        this.inputReader.start();

        // Register shutdown hook
        this.shutdownHook = new Thread(this::cleanup, "inline-tui-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    /**
     * Creates an InlineTuiRunner with the specified height and default configuration.
     *
     * @param height the number of lines for the inline display
     * @return a new InlineTuiRunner
     * @throws Exception if terminal initialization fails
     */
    public static InlineTuiRunner create(int height) throws Exception {
        return create(InlineTuiConfig.defaults(height));
    }

    /**
     * Creates an InlineTuiRunner with the specified configuration.
     *
     * @param config the configuration to use
     * @return a new InlineTuiRunner
     * @throws Exception if terminal initialization fails
     */
    public static InlineTuiRunner create(InlineTuiConfig config) throws Exception {
        return create(BackendFactory.create(), config);
    }

    static InlineTuiRunner create(Backend backend, InlineTuiConfig config) throws Exception {
        InlineDisplay display;

        try {
            // Enable raw mode for key events
            backend.enableRawMode();
            if (config.bracketedPaste()) {
                backend.enableBracketedPaste();
            }

            // Create inline display using shared backend (no alternate screen)
            display = InlineDisplay.withBackend(config.height(), backend);
            if (config.clearOnClose()) {
                display.clearOnClose();
            }

            InlineViewport viewport = new InlineViewport(display);
            return new InlineTuiRunner(backend, viewport, config);
        } catch (Exception e) {
            try {
                backend.disableRawMode();
            } catch (Exception ignored) {
            }
            backend.close();
            throw e;
        }
    }

    /**
     * Runs the main event loop with the given handler and renderer.
     *
     * <p>与全屏版 {@link TuiRunner#run} 对齐：事件循环对 {@code handler.handle} 与
     * {@code UiRunnable.run} 各包一层 {@code try(Throwable)}，异常被记录并<b>丢弃</b>后
     * {@code continue}，渲染线程不会因一次按键处理异常而退出。这正是行内版卡死的根因修复。
     *
     * @param handler  the event handler
     * @param renderer the UI renderer
     * @throws Exception if an error occurs during execution
     */
    public void run(InlineEventHandler handler, Renderer renderer) throws Exception {
        // Mark this thread as the render thread
        RenderThread.markAsRenderThread();

        activeRenderer = renderer;
        try {
            // Initial draw
            drawAndMaybeScheduleImeFollowUp(renderer::render);

            while (running.get()) {
                Event event = pollEvent(config.pollTimeout());
                if (event != null) {
                    // Handle UiRunnable events (scheduled work from other threads)
                    if (event instanceof UiRunnable) {
                        try {
                            ((UiRunnable) event).run();
                        } catch (Throwable t) {
                            handleThrowable(t);
                        }
                        continue;
                    }

                    if (event instanceof ResizeEvent) {
                        try {
                            handler.handle(event, this);
                        } catch (Throwable t) {
                            handleThrowable(t);
                        }
                        if (running.get()) {
                            drawAndMaybeScheduleImeFollowUp(renderer::render);
                        }
                        continue;
                    }

                    boolean shouldRedraw;
                    try {
                        shouldRedraw = handler.handle(event, this);
                    } catch (Throwable t) {
                        handleThrowable(t);
                        continue;
                    }
                    if (shouldRedraw && running.get()) {
                        drawAndMaybeScheduleImeFollowUp(renderer::render);
                    }
                }
            }
        } finally {
            activeRenderer = null;
            RenderThread.clearRenderThread();
        }
    }

    /**
     * draw 一次，随后按需补排 IME 光标带修复的后续帧（Task 8）。
     *
     * <p><b>为什么要补排</b>：macOS 终端把 IME 预编辑串画在硬件光标处，其清理是异步的、
     * 会越界擦坏相邻行右缘（见 InlineDisplay 的光标带修复注释）。修复窗口（8 帧）内的
     * 整行重申依赖「每帧都画」——事件驱动关掉全局 tick 后，这 8 帧只能由 runner 自己
     * 按需驱动。窗口仍在（draw 后 {@code needsFollowUpFrame()==true}）→ 排一个一次性
     * {@link #requestRender()}；耗尽 → 不再排，静止界面零帧零字节。
     *
     * <p><b>纪律</b>：一次性（{@code scheduler.schedule}，绝不用 {@code scheduleAtFixedRate}）；
     * CAS 至多一个在飞（连续多帧触发也只挂一个 timer）；回调只经既有
     * {@link #requestRender()} 合并请求，不直接画（渲染仍只在渲染线程）。
     */
    private void drawAndMaybeScheduleImeFollowUp(java.util.function.Consumer<Frame> renderFunction) {
        viewport.draw(renderFunction);
        if (!viewport.needsFollowUpFrame()) {
            return;   // 窗口未武装或已耗尽：无事可做（绝大多数帧走这里，零开销）
        }
        if (imeFollowUpScheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(() -> {
                    imeFollowUpScheduled.set(false);
                    if (running.get()) {
                        requestRender();   // 合并请求：真正的 draw 在事件循环里发生
                    }
                }, IME_FOLLOW_UP_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                imeFollowUpScheduled.set(false);   // scheduler 已关闭（停止路径）：放弃并归还
                log.log(Level.FINE, "IME follow-up frame schedule rejected", t);
            }
        }
    }

    /**
     * 记录一次 handler/UI 线程异常并让事件循环继续存活。
     *
     * <p>行内 TUI 没有全屏版的错误覆盖层，这里采用「记录并继续」策略：异常多半来自某个按键处理
     * （如 {@code % n} 除零），记录到日志后丢弃即可，循环照常处理后续事件。若异常反复出现，日志里会
     * 留下可诊断的堆栈。绝不 {@code quit()}——那会与「治本：渲染线程永不死亡」的目标背道而驰。
     */
    private void handleThrowable(Throwable t) {
        // 用带 Throwable 的重载：完整保留异常类型、消息与堆栈，供诊断。
        log.log(Level.SEVERE, "InlineTuiRunner 事件循环捕获到未处理异常，已记录并继续", t);
        if (!running.get()) {
            return;
        }
        // 保持循环存活；不重绘，避免用半坏状态渲染。
    }

    /**
     * Polls for the next event with the specified timeout.
     *
     * @param timeout the maximum time to wait
     * @return the next event, or null if timeout expires
     */
    public Event pollEvent(Duration timeout) {
        try {
            // Prioritize input events over tick events
            Event event = findInputEvent();
            if (event != null) {
                return event;
            }

            return eventQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Searches the queue for an input event (non-tick), removing and returning it.
     */
    private Event findInputEvent() {
        List<Event> ticks = new ArrayList<>();
        Event inputEvent = null;

        Event e;
        while ((e = eventQueue.poll()) != null) {
            if (e instanceof TickEvent) {
                ticks.add(e);
            } else {
                inputEvent = e;
                break;
            }
        }

        for (Event tick : ticks) {
            eventQueue.offer(tick);
        }

        return inputEvent;
    }

    /**
     * Prints a plain text message above the viewport.
     *
     * @param message the message to print
     */
    public void println(String message) {
        viewport.println(message);
    }

    /**
     * Prints styled text above the viewport.
     *
     * @param text the styled text to print
     */
    public void println(Text text) {
        viewport.println(text);
    }

    /**
     * Sets the content height for the next draw.
     *
     * <p>
     * This controls how many terminal lines are allocated for the inline display.
     * Calling this before rendering allows the display to grow or shrink dynamically.
     *
     * @param height the desired content height in lines
     */
    public void setContentHeight(int height) {
        viewport.setContentHeight(height);
    }

    /**
     * Executes an action on the render thread.
     *
     * <p>
     * If called from the render thread, the action is executed immediately.
     * If called from another thread, the action is queued for execution.
     *
     * @param action the action to execute
     */
    public void runOnRenderThread(Runnable action) {
        if (RenderThread.isRenderThread()) {
            action.run();
        } else {
            eventQueue.offer(new UiRunnable(action));
        }
    }

    /**
     * Queues an action to be executed on the render thread.
     *
     * <p>
     * Unlike {@link #runOnRenderThread(Runnable)}, this method always queues
     * the action even if called from the render thread.
     *
     * @param action the action to execute
     */
    public void runLater(Runnable action) {
        eventQueue.offer(new UiRunnable(action));
    }

    /**
     * Queues an action for the render thread and requests one coalesced draw after it runs.
     *
     * @param action the UI action; null actions are ignored
     */
    public void requestUiUpdate(Runnable action) {
        if (action == null || !running.get()) {
            return;
        }
        synchronized (uiActionsLock) {
            uiActions.offer(action);
            renderRequested = true;
        }
        enqueueUiWake();
    }

    /**
     * Requests one coalesced draw on the render thread.
     */
    public void requestRender() {
        if (!running.get()) {
            return;
        }
        synchronized (uiActionsLock) {
            renderRequested = true;
        }
        enqueueUiWake();
    }

    private void enqueueUiWake() {
        if (running.get() && uiUpdateQueued.compareAndSet(false, true)) {
            eventQueue.offer(new UiRunnable(this::processUiWake));
        }
    }

    private void processUiWake() {
        ConcurrentLinkedQueue<Runnable> batch;
        boolean shouldRender;
        synchronized (uiActionsLock) {
            batch = uiActions;
            uiActions = new ConcurrentLinkedQueue<>();
            shouldRender = renderRequested;
            renderRequested = false;
        }
        try {
            Runnable action;
            while ((action = batch.poll()) != null) {
                try {
                    action.run();
                } catch (Throwable t) {
                    handleThrowable(t);
                }
            }

            Renderer renderer = activeRenderer;
            if (shouldRender && running.get() && renderer != null) {
                drawAndMaybeScheduleImeFollowUp(renderer::render);
            }
        } finally {
            uiUpdateQueued.set(false);
            if (running.get() && hasPendingUiWork()) {
                enqueueUiWake();
            }
        }
    }

    private boolean hasPendingUiWork() {
        synchronized (uiActionsLock) {
            return !uiActions.isEmpty() || renderRequested;
        }
    }

    /**
     * Signals the runner to stop.
     */
    public void quit() {
        running.set(false);
        eventQueue.offer(new UiRunnable(() -> { }));
    }

    /**
     * Returns whether the runner is still running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the viewport width.
     *
     * @return the width in characters
     */
    public int width() {
        return viewport.width();
    }

    /**
     * Returns the viewport height.
     *
     * @return the height in lines
     */
    public int height() {
        return viewport.height();
    }

    /**
     * Draws the UI using the given renderer.
     *
     * @param renderer the render function
     */
    public void draw(Consumer<Frame> renderer) {
        viewport.draw(renderer);
    }

    /**
     * Returns the shared scheduler for scheduling tasks.
     *
     * <p>
     * This scheduler runs on a dedicated daemon thread. Tasks scheduled here
     * execute on the scheduler thread, not the render thread. To modify UI state
     * from a scheduled task, use {@link #runOnRenderThread(Runnable)}.
     *
     * @return the scheduler (never null)
     */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    private static Size readCurrentSize(Backend backend) {
        try {
            return backend.size();
        } catch (IOException e) {
            return new Size(80, 24);
        }
    }

    /**
     * Scheduler callback that generates tick events.
     */
    private void schedulerCallback() {
        if (!running.get()) {
            return;
        }

        if (config.ticksEnabled() && config.tickRate() != null) {
            Instant now = Instant.now();
            Instant targetTime = nextTickTime.get();

            if (targetTime != null && !now.isBefore(targetTime)) {
                Instant previous = lastTick.getAndSet(now);
                Duration elapsed = Duration.between(previous, now);

                nextTickTime.set(targetTime.plus(config.tickRate()));

                long frame = frameCount.incrementAndGet();
                eventQueue.offer(TickEvent.of(frame, elapsed));
            }
        }
    }

    /**
     * Closes this runner and releases resources.
     *
     * <p>
     * If a scheduler was provided via configuration, it is NOT shut down -
     * the caller retains ownership and is responsible for its lifecycle.
     * If no scheduler was provided, the internally-created scheduler is shut down.
     */
    @Override
    public void close() {
        running.set(false);

        // Stop input reader thread
        if (inputReader != null) {
            inputReader.stop(config.pollTimeout().toMillis() * 2);
        }

        // Remove shutdown hook
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down
        }

        // Shutdown scheduler only if we own it
        if (schedulerOwned) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        cleanup();
    }

    /**
     * Performs cleanup. This is idempotent and safe to call multiple times.
     */
    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        try {
            viewport.release();
        } catch (Exception ignored) {
        }

        if (config.bracketedPaste()) {
            try {
                backend.disableBracketedPaste();
            } catch (Exception ignored) {
            }
        }

        try {
            backend.disableRawMode();
        } catch (Exception ignored) {
        }

        try {
            viewport.close();
        } catch (Exception ignored) {
        }

        try {
            backend.close();
        } catch (Exception ignored) {
        }
    }
}
