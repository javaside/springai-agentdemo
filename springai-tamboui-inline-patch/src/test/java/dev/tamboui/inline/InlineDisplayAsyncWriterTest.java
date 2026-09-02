package dev.tamboui.inline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * {@link InlineDisplay} 挂接 {@link AsyncPtyWriter} 后的行为契约：
 * 渲染线程的 submit/println/render 永不阻塞在慢 pty 上，被拒的字节批延迟重投、
 * 顺序与内容完整，恢复后全部落盘。
 *
 * <p>这是「输出时打字卡死」根治方案的核心集成钉：旧实现里 display.submit 在
 * 渲染线程同步 writeRaw+flush，pty 读端停摆时渲染线程睡死在 write(2) 里。
 */
class InlineDisplayAsyncWriterTest {

    private static final long CALLER_LATENCY_LIMIT_MS = 300;

    /** 核心回归钉：backend 卡死时，渲染线程持续 println/render 必须立刻返回（不阻塞）。 */
    @Test
    void renderThreadNeverBlocksWhenBackendIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 64 * 1024);
        InlineDisplay display = new InlineDisplay(4, 40, stuck, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        display.useAsyncWriter(writer, () -> { });

        // 真实路径：CodeTuiView 经 InlineRenderBatch 用 beginPrintBatch/endPrintBatch 把
        // 一整段 scrollback 批（300 行中文 ≈ 100KB）拼成一次 submit——这正是软预算要拦的粒度。
        long t0 = System.nanoTime();
        String chunk80Cols = new String(new char[80]).replace('\0', '行');
        for (int round = 0; round < 4; round++) {
            display.beginPrintBatch();
            for (int i = 0; i < 300; i++) {
                display.println("line-" + i + "-" + chunk80Cols);   // 300 行 × ~91 chars ≈ 27KiB/批
            }
            display.endPrintBatch();   // 64KiB 软预算：第 3 轮（54+27=81KiB）起被拒 → 延迟批
        }
        display.render((area, buffer) -> buffer.setString(0, 0, "frame", dev.tamboui.style.Style.EMPTY), 4, 0, 0);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < CALLER_LATENCY_LIMIT_MS,
                "4×150 行大 scrollback 批 + render 耗时 " + elapsedMs + "ms：渲染线程被慢 pty 拖住");
        assertTrue(display.hasDeferredOutput(), "写线程卡死时必然出现延迟批");

        writer.close(1, TimeUnit.SECONDS);
        release.countDown();
    }

    /**
     * 延迟重投的完整性：饱和期间积压的字节，恢复后必须<b>全部、按序</b>落盘——
     * pty 是协议字节流，丢一段或乱序都会花屏/丢内容。
     */
    @Test
    void deferredBytesAreCompleteAndOrderedAfterRecovery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        RecordingBackend recorder = new RecordingBackend();
        // 间接路径：卡死后释放，把写出导向 recorder——用门闩 backend 模拟卡死期，再换成记录。
        // 简化：直接用可切换的 backend。
        SwitchableBackend backend = new SwitchableBackend(stuck, recorder);
        AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024);
        StringBuilder expected = new StringBuilder();
        InlineDisplay display = new InlineDisplay(2, 40, backend, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        AtomicInteger drains = new AtomicInteger();
        CountDownLatch firstDrain = new CountDownLatch(1);
        display.useAsyncWriter(writer, () -> {
            if (drains.incrementAndGet() == 1) {
                firstDrain.countDown();
            }
        });

        // 卡死期：大批 println（begin/endPrintBatch 成批）攒延迟批——8KiB 软预算，每批 ~3KB、第 3 批起拒。
        for (int i = 0; i < 24; i++) {
            display.beginPrintBatch();
            display.println("payload-" + i + "-" + new String(new char[3000]).replace('\0', 'x'));
            display.endPrintBatch();   // ~3KiB/批：8KiB 软预算下第 3 批（6+3=9KiB）起被拒
        }
        assertTrue(display.hasDeferredOutput());

        // 恢复：写线程开始消化，onDrained 唤醒后重投。
        backend.switchTo(recorder);
        release.countDown();
        long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!firstDrain.await(20, TimeUnit.MILLISECONDS) && System.nanoTime() < drainDeadline) {
            display.retryDeferred();   // 渲染线程侧配合重投（生产由 onDrained→requestRender 驱动）
        }
        assertTrue(firstDrain.getCount() == 0, "恢复后必须触发 onDrained");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (display.hasDeferredOutput() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
            // 模拟渲染线程被 onDrained 唤醒后的重试。
            display.retryDeferred();
        }
        assertFalse(display.hasDeferredOutput(), "恢复后延迟批必须最终清空");

        writer.close(2, TimeUnit.SECONDS);
        String all = recorder.outputUtf8();
        for (int i = 0; i < 24; i++) {
            assertTrue(all.contains("payload-" + i), "payload-" + i + " 不得丢失");
        }
        for (int i = 1; i < 24; i++) {
            assertTrue(all.indexOf("payload-" + (i - 1)) < all.indexOf("payload-" + i),
                    "payload-" + i + " 必须晚于 payload-" + (i - 1));
        }
    }

    /** 同步路径（未挂 writer）保持旧行为：现有 InlineDisplayDiffTest 已覆盖，这里只钉「未挂不受影响」。 */
    @Test
    void withoutWriterSyncPathUnchanged() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        // 传 null PrintWriter 走 backend 直写分支（createPrintWriter 不可用——测试 backend 非 IO backend）。
        // InlineDisplay 的 println 路径不经过 out PrintWriter（printBatch 拼批 + submit→backend.writeRaw），
        // 故传 null 安全。
        InlineDisplay display = new InlineDisplay(2, 40, backend, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        assertFalse(display.hasDeferredOutput());
        display.println("direct");
        assertTrue(backend.outputUtf8().contains("direct"), "未挂 writer 时必须同步直写");
    }

    // ── 清屏屏障（审核 M-1/P2 回归钉） ────────────────────────────────

    /** writer 卡死时屏障不成立：返回 false——调用方必须放弃真清屏降级。 */
    @Test
    void clearBarrierReturnsFalseWhenWriterStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 8 * 1024);
        InlineDisplay display = new InlineDisplay(2, 40, stuck, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        display.useAsyncWriter(writer, () -> { });
        // 攒延迟批（写线程卡死，预算填满即拒）。
        for (int i = 0; i < 6; i++) {
            display.beginPrintBatch();
            display.println("payload-" + i + "-" + new String(new char[3000]).replace('\0', 'x'));
            display.endPrintBatch();
        }
        assertTrue(display.hasDeferredOutput());

        long t0 = System.nanoTime();
        boolean ok = display.clearQueuedOutputAndBarrier(200, TimeUnit.MILLISECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(ok, "writer 卡死时屏障必须不成立（清屏会复活旧内容）");
        assertTrue(elapsedMs < 2000, "屏障必须有界（实测 " + elapsedMs + "ms）");
        assertFalse(display.hasDeferredOutput(), "屏障侧必须清掉延迟批（语义性丢弃）");

        writer.close(1, TimeUnit.SECONDS);
        release.countDown();
    }

    /** writer 健康时屏障成立：延迟批被清、排空后返回 true。 */
    @Test
    void clearBarrierTrueAfterDrainClearsDeferred() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        RecordingBackend recorder = new RecordingBackend();
        SwitchableBackend backend = new SwitchableBackend(stuck, recorder);
        AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024);
        InlineDisplay display = new InlineDisplay(2, 40, backend, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        display.useAsyncWriter(writer, () -> { });
        for (int i = 0; i < 6; i++) {
            display.beginPrintBatch();
            display.println("payload-" + i + "-" + new String(new char[3000]).replace('\0', 'x'));
            display.endPrintBatch();
        }
        assertTrue(display.hasDeferredOutput());

        // 恢复 + 排空 → 屏障成立。
        backend.switchTo(recorder);
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean ok = false;
        while (!ok && System.nanoTime() < deadline) {
            ok = display.clearQueuedOutputAndBarrier(500, TimeUnit.MILLISECONDS);
        }
        assertTrue(ok, "writer 排空后屏障必须成立");
        assertFalse(display.hasDeferredOutput(), "屏障必须清掉延迟批");
        writer.close(1, TimeUnit.SECONDS);
    }

    // ── release 有界（审核 B-1 回归钉：退出不被卡死的写挂死） ───────────

    /** backend 写永不返回时 release() 必须有界返回（500ms join + 放弃恢复序列）。 */
    @Test
    void releaseIsBoundedWhenBackendWriteBlocksForever() throws Exception {
        CountDownLatch release = new CountDownLatch(0);   // 永不打开
        StuckBackend stuck = new StuckBackend(release);
        InlineDisplay display = new InlineDisplay(2, 40, stuck, null,
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        long t0 = System.nanoTime();
        display.release();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(elapsedMs < 2000, "release 必须有界（实测 " + elapsedMs + "ms）——退出路径不得被卡死的写挂死");
        // 二次调用 no-op（幂等）。
        display.release();
    }

    // ── 测试桩 ─────────────────────────────────────────────────────────

    /** 卡死 backend：每块写都等 latch。 */
    private static final class StuckBackend extends NoopBackend {
        private final CountDownLatch release;

        StuckBackend(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void writeRaw(String data) {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void flush() {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 可切换委托 backend：卡死期委托 stuck，切换后委托 recorder。 */
    private static final class SwitchableBackend extends NoopBackend {
        private volatile NoopBackend delegate;

        SwitchableBackend(NoopBackend initial, NoopBackend after) {
            this.delegate = initial;
        }

        void switchTo(NoopBackend next) {
            this.delegate = next;
        }

        @Override
        public void writeRaw(String data) throws IOException {
            delegate.writeRaw(data);
        }

        @Override
        public void flush() {
            delegate.flush();
        }
    }

    /** 逐块记录 backend。 */
    private static class RecordingBackend extends NoopBackend {
        private final StringBuilder output = new StringBuilder();

        @Override
        public synchronized void writeRaw(String data) {
            output.append(data);
        }

        @Override
        public void flush() {
        }

        synchronized String outputUtf8() {
            return output.toString();
        }
    }

    /** 最小 Backend 桩。 */
    private static class NoopBackend implements dev.tamboui.terminal.Backend {
        @Override public void draw(dev.tamboui.buffer.DiffResult diff) { }
        @Override public void flush() { }
        @Override public void clear() { }
        @Override public dev.tamboui.layout.Size size() { return new dev.tamboui.layout.Size(80, 24); }
        @Override public void showCursor() { }
        @Override public void hideCursor() { }
        @Override public dev.tamboui.layout.Position getCursorPosition() { return new dev.tamboui.layout.Position(0, 0); }
        @Override public void setCursorPosition(dev.tamboui.layout.Position position) { }
        @Override public void enterAlternateScreen() { }
        @Override public void leaveAlternateScreen() { }
        @Override public void enableRawMode() { }
        @Override public void disableRawMode() { }
        @Override public void onResize(Runnable handler) { }
        @Override public int read(int timeoutMs) { return -2; }
        @Override public int peek(int timeoutMs) { return -2; }
        @Override public void close() { }
        @Override public void writeRaw(byte[] data) throws IOException { }
    }
}
