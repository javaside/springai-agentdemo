package dev.tamboui.inline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import dev.tamboui.testutil.ExpectedLog;

/**
 * {@link AsyncPtyWriter} 的行为契约测试：渲染线程的 write/flush 必须永不阻塞在慢 pty 上。
 *
 * <p>根因背景（见 InlineDisplay/InlineTuiRunner 注释）：macOS pty 内核写缓冲仅 ~1-2 KiB，
 * 终端读端停摆（IME 合成 / 终端渲染积压）时阻塞 write 可挂数秒到无限期；旧实现里
 * 这笔写发生在渲染线程（processUiWake → submit → backend.writeRaw+flush），按键全部
 * 排队——用户感知「输出时打字卡死」。
 */
class AsyncPtyWriterTest {

    /** 基准挂钟（毫秒）：单次提交若阻塞超过它即视为「卡到渲染线程」。 */
    private static final long CALLER_LATENCY_LIMIT_MS = 300;

    // ── 契约 1：提交永不阻塞（慢消费者） ────────────────────────────────

    /**
     * 核心回归钉：底层 backend 卡死（永不返回）时，连续提交大批 payload 的调用线程
     * 必须在时限内返回——渲染线程不能跟慢 pty 同生共死。
     */
    @Test
    void submitNeverBlocksWhenBackendIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        try (AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 64 * 1024)) {
            long t0 = System.nanoTime();
            int accepted = 0;
            for (int i = 0; i < 8; i++) {
                if (writer.submit(new String(new char[32 * 1024]).replace('\0', 'x'))) {
                    accepted++;
                }
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < CALLER_LATENCY_LIMIT_MS,
                    "8×32KiB 提交耗时 " + elapsedMs + "ms：提交方被慢 backend 拖住");
            // 软预算 64KiB、单块 32KiB：第 3 块起被拒（64+32 > 64），绝不阻塞。
            assertTrue(accepted == 2, "64KiB 软预算下 32KiB 大块只该接受 2 块，实际 " + accepted);
            assertTrue(writer.isSaturated(), "被拒后必须处于饱和态");
            // 小帧豁免：1KiB 小块在硬预算（128KiB）内仍被接受——打字回显不被大块饿死。
            assertTrue(writer.submit(new String(new char[1024]).replace('\0', 'y')),
                    "小帧在硬预算内必须接受");
        } finally {
            release.countDown();
        }
    }

    /** 大块撞软预算立即拒绝（不等设备）；小帧豁免软预算、只撞硬预算才拒——打字回显不被输出饿死。 */
    @Test
    void largeBatchRejectedImmediatelyWhenSaturated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        try (AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 8 * 1024)) {
            // 首块 6KiB（大块，> 软预算/16）：0+6K ≤ 8K 接受；第二块 6K+6K=12K > 软预算 8K 立即拒绝。
            assertTrue(writer.submit(payload(6 * 1024)));
            long t0 = System.nanoTime();
            boolean second = writer.submit(payload(6 * 1024));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertFalse(second, "大块撞软预算必须立即拒绝");
            assertTrue(elapsedMs < 50, "拒绝必须立即（实测 " + elapsedMs + "ms）");
            // 小帧豁免：64B 小块在硬预算（2×8K）内仍被接受——打字回显不能被大块饿死。
            assertTrue(writer.submit(payload(64)), "小帧在硬预算内必须接受");
        } finally {
            release.countDown();
        }
    }

    // ── 契约 2：顺序与 flush 语义 ──────────────────────────────────────

    /** 提交顺序 = 写出顺序（pty 协议流不能乱序）。 */
    @Test
    void writesAreOrderedAndEventuallyFlushed() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024)) {
            for (int i = 0; i < 100; i++) {
                writer.submit("line-" + i + "\n");
            }
            writer.flush();
            assertTrue(backend.awaitCount(100, 2, TimeUnit.SECONDS), "100 块应全部写出");
            List<String> seen = backend.chunks;
            for (int i = 0; i < 100; i++) {
                assertEquals("line-" + i + "\n", seen.get(i), "写出顺序必须与提交顺序一致");
            }
            assertTrue(backend.flushes.get() >= 1, "flush 请求最终必须反映到底层");
        }
    }

    /**
     * flush 的时序语义：{@code awaitFlushed} 返回后，此前全部提交必须已写到底层并 flush。
     * 这是异步化后保住「帧边界」的关键（ScreenCleaner 清屏后重放前需要确定语义）。
     */
    @Test
    void awaitFlushedDrainsPriorSubmissions() throws Exception {
        SlowBackend backend = new SlowBackend(5);   // 每块 5ms
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024)) {
            for (int i = 0; i < 20; i++) {
                writer.submit("payload-" + i);
            }
            writer.flush();
            writer.awaitFlushed(2, TimeUnit.SECONDS);
            assertEquals(20, backend.writtenCount(), "awaitFlushed 返回时全部 payload 必须已写出");
            assertTrue(backend.flushes.get() >= 1);
        }
    }

    // ── 契约 3：饱和诊断与恢复 ─────────────────────────────────────────

    /** 饱和状态可观测：isSaturated 供渲染线程在 UI 层降级（延迟 continuation）。 */
    @Test
    void saturationFlagReflectsQueueState() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        try (AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 4 * 1024)) {
            assertFalse(writer.isSaturated(), "初始不应饱和");
            writer.submit(payload(2 * 1024));
            writer.submit(payload(2 * 1024));
            assertTrue(writer.isSaturated(), "字节预算耗尽必须反映在 isSaturated");
        } finally {
            release.countDown();
        }
    }

    /** 消费恢复后 isSaturated 应回落，后续提交继续被接受（背压解除）。 */
    @Test
    void saturationClearsAfterConsumerRecovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StuckBackend stuck = new StuckBackend(release);
        try (AsyncPtyWriter writer = new AsyncPtyWriter(stuck, 8 * 1024)) {
            // 4×2KiB 大块：第 4 块入队后 bytesQueued=8KiB ≥ 软预算 → 饱和。
            for (int i = 0; i < 4; i++) {
                assertTrue(writer.submit(payload(2 * 1024)));
            }
            assertTrue(writer.isSaturated());
            release.countDown();   // 解除卡死：写线程开始消化
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writer.isSaturated() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertFalse(writer.isSaturated(), "消费恢复后饱和必须回落");
        }
        stuck.awaitClosed(2, TimeUnit.SECONDS);
    }

    // ── 契约 4：生命周期 ───────────────────────────────────────────────

    /** close 必须排空剩余队列（尽力而为 + 有界等待），不丢已接受的内容。 */
    @Test
    void closeDrainsQueuedWrites() throws Exception {
        SlowBackend backend = new SlowBackend(2);
        AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024);
        for (int i = 0; i < 50; i++) {
            writer.submit("chunk-" + i);
        }
        writer.close(2, TimeUnit.SECONDS);
        assertEquals(50, backend.writtenCount(), "close 应排空全部已接受块");
    }

    /** close 后提交是 no-op（不抛、不写字节）。 */
    @Test
    void submitAfterCloseIsNoOp() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024);
        writer.submit("before");
        writer.close(2, TimeUnit.SECONDS);
        int after = backend.writtenCount();
        writer.submit("after");
        writer.flush();
        TimeUnit.MILLISECONDS.sleep(100);
        assertEquals(after, backend.writtenCount(), "close 后不得再写出任何字节");
    }

    // ── 契约 5：设备死亡族（审核 M1/M-2 的回归钉） ────────────────────

    /**
     * IOException 转设备死亡：isDead、isSaturated 恒 false（防 freeze-forever）、后续提交 no-op。
     * 死亡必须留下 WARNING 诊断记录（含根因异常）——经 {@link ExpectedLog} 断言式接管，
     * 不泼进构建输出。
     */
    @Test
    void ioFailureMarksDeadAndSaturatedNeverTrue() throws Exception {
        ThrowingBackend backend = new ThrowingBackend();
        try (ExpectedLog writerLog = ExpectedLog.capture(AsyncPtyWriter.class);
                AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            writer.submit(payload(1024));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!writer.isDead() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertTrue(writer.isDead(), "writeRaw 抛 IOException 必须转设备死亡");
            LogRecord logged = writerLog.awaitRecord(Level.WARNING,
                    "pty 写失败，异步写线程转入设备死亡态", 1, TimeUnit.SECONDS);
            assertEquals("simulated pty failure", logged.getThrown().getMessage(),
                    "markDead 必须携带根因 IOException（供诊断）");
            assertFalse(writer.isSaturated(), "死亡后 isSaturated 必须恒 false（否则上层闸永久关死，审核 M1）");
            assertTrue(writer.submit(payload(1024)), "死亡后提交按已接受（no-op）处理");
            writer.close(1, TimeUnit.SECONDS);
            int atDeath = backend.writtenCount();
            writer.submit(payload(1024));   // 死亡后再提交：必须 no-op
            writer.flush();
            TimeUnit.MILLISECONDS.sleep(100);
            assertEquals(atDeath, backend.writtenCount(), "死亡后不得再写出任何字节（只有触发死亡的那一次）");
        }
    }

    /** 错误探针（PrintWriter.checkError 语义）触发设备死亡：markDead 不再依赖 IOException。 */
    @Test
    void errorProbeTriggersMarkDead() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AtomicBoolean probeFlag = new AtomicBoolean();
        try (ExpectedLog writerLog = ExpectedLog.capture(AsyncPtyWriter.class);
                AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024, probeFlag::get)) {
            writer.submit(payload(1024));
            writer.flush();
            writer.awaitFlushed(2, TimeUnit.SECONDS);
            probeFlag.set(true);   // 第一块消化后探针开始报错
            writer.submit(payload(1024));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!writer.isDead() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertTrue(writer.isDead(), "探针为真必须触发设备死亡（PrintWriter 吞异常的补偿，审核 M-2）");
            LogRecord logged = writerLog.awaitRecord(Level.WARNING,
                    "pty 写失败，异步写线程转入设备死亡态", 1, TimeUnit.SECONDS);
            assertEquals("pty error probe reported failure (checkError)", logged.getThrown().getMessage(),
                    "探针路径的 markDead 必须携带说明性异常（checkError 无原始异常可带）");
        }
    }

    /** 死亡后排空 latch 立即通过（flush 不悬空）。 */
    @Test
    void flushAfterDeathCompletesImmediately() throws Exception {
        ThrowingBackend backend = new ThrowingBackend();
        try (ExpectedLog writerLog = ExpectedLog.capture(AsyncPtyWriter.class);
                AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            writer.submit(payload(1024));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!writer.isDead() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            // 不另行断言消息/异常——与 ioFailureMarksDead 用例钉的是同一条 WARNING；
            // 此处消费掉预期记录只为过 close 绊线（ExpectedLog 纪律：预期噪音必须显式声明）。
            writerLog.awaitRecord(Level.WARNING,
                    "pty 写失败，异步写线程转入设备死亡态", 1, TimeUnit.SECONDS);
            long t0 = System.nanoTime();
            boolean ok = writer.flush().await(200, TimeUnit.MILLISECONDS);
            assertTrue(ok, "死亡后 flush 必须立即通过");
            assertTrue((System.nanoTime() - t0) < TimeUnit.MILLISECONDS.toNanos(200));
            writer.close(1, TimeUnit.SECONDS);
        }
    }

    // ── 契约 6：队空豁免前进性（修复新增行为的锚） ──────────────────────

    /** 超软预算的单批在队空时必须被接受（否则恢复后死锁——审核确认的修复）。 */
    @Test
    void oversizedBatchAcceptedOnEmptyQueue() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            assertTrue(writer.submit(payload(20 * 1024)),
                    "队空豁免：20KiB 批 > 8KiB 软预算，队空时必须接受（前进性）");
            assertTrue(writer.flush().await(2, TimeUnit.SECONDS));
            assertEquals(20 * 1024, backend.totalCharsWritten(), "分片不得丢字（字符级总量）");
        }
    }

    /**
     * 超软预算单批的分片契约（规格 §10 写侧平滑）：对外一次 submit 返回 true；
     * 队列侧按 ≤软预算切片、按提交序落盘——顺序钉（拼接还原原 payload）防乱序，
     * 片长钉（每片 ≤ 软预算）防「假分片」。
     */
    @Test
    void oversizedSingleBatchIsShardedInOrderIntoBudgetSizedChunks() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            String payload = payload(20 * 1024);
            assertTrue(writer.submit(payload), "队空时超预算单批必须接受（对外语义不变）");
            writer.flush();
            assertTrue(writer.awaitFlushed(3, TimeUnit.SECONDS));
            assertTrue(backend.writtenCount() >= 2,
                    "20KiB 对 8KiB 预算应分片为多次 writeRaw（实测 " + backend.writtenCount() + " 次）");
            assertEquals(payload, String.join("", backend.chunks),
                    "分片必须保序、不丢不重（字节序 = 提交序）");
            for (String piece : backend.chunks) {
                assertTrue(piece.length() <= 8 * 1024,
                        "每片不得超过软预算（实测 " + piece.length() + " chars）");
            }
        }
    }

    /**
     * 分片边界不得切开 UTF-16 代理对（审核 MAJOR-1）：被切开的两片各自经终端
     * PrintWriter 编码 UTF-8 时，不成对代理落为替换符 {@code ?}——内容永久损坏。
     * 奇数 ASCII 前缀 + 重复星面字符构造 >8KiB 批，使 8192 边界必然落在某个代理对中间。
     *
     * <p><b>断言必须有分辨力（终审 R5）</b>：RecordingBackend 只记录 String 引用，
     * char 层切开再拼回 equals 恒真——观测不到损坏。故本例用<b>往返编码桩</b>：
     * writeRaw 时做一次 UTF-8 编码再解码（new String(data.getBytes(UTF_8), UTF_8)，
     * 与生产 PrintWriter 的编码路径同损坏语义），孤立代理立即变 ?，拼接立红。
     * 验证方式：临时删掉实现里的代理对回退（end--）跑本例必须红，恢复后必须绿。
     */
    @Test
    void shardingNeverSplitsSurrogatePairs() throws Exception {
        RoundTripBackend backend = new RoundTripBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            String astral = "\uD834\uDD1E";   // U+1D11E（𝄞），高+低代理对
            StringBuilder sb = new StringBuilder("x");   // 奇数前缀：8192 边界落在代理对中间
            while (sb.length() < 20 * 1024) {
                sb.append(astral);
            }
            String payload = sb.toString();
            assertTrue(writer.submit(payload));
            writer.flush();
            assertTrue(writer.awaitFlushed(3, TimeUnit.SECONDS));
            assertEquals(payload, String.join("", backend.chunks),
                    "分片边界切开代理对 → 孤立代理经 UTF-8 往返变 ? → 拼接损坏——"
                            + "实现必须回退边界避开代理对");
        }
    }

    /** 往返编码桩：模拟生产 PrintWriter 的 UTF-8 编码路径（孤立代理 → ?）。 */
    private static final class RoundTripBackend extends NoopBackend {
        final java.util.List<String> chunks = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void writeRaw(String data) {
            chunks.add(new String(data.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void flush() {
        }
    }

    // ── 契约 7：并发 flush 各等各的标记（审核 m1/m-2 的回归钉） ──────────

    @Test
    void concurrentFlushesEachAwaitTheirOwnMarker() throws Exception {
        SlowBackend backend = new SlowBackend(20);
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024)) {
            for (int i = 0; i < 5; i++) {
                writer.submit("payload-" + i);
            }
            CountDownLatch latch1 = writer.flush();
            CountDownLatch latch2 = writer.flush();
            assertTrue(latch1.await(3, TimeUnit.SECONDS), "第一个 flush 标记必须被消费");
            assertTrue(latch2.await(3, TimeUnit.SECONDS), "第二个 flush 标记必须被消费（各等各的）");
            assertTrue(backend.flushes.get() >= 2);
        }
    }

    // ── 契约 8：唤醒武装后单发（事件驱动「无常驻 tick」承诺的钉） ────────

    @Test
    void drainListenerFiresOnlyOncePerArmAndNeverUnarmed() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        AtomicInteger fires = new AtomicInteger();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 64 * 1024)) {
            writer.setDrainListener(fires::incrementAndGet);
            // 未武装：写 + 排空 → 零回调（静止界面零唤醒）。
            writer.submit("data");
            writer.flush();
            writer.awaitFlushed(2, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(150);
            assertEquals(0, fires.get(), "未武装时排空不得触发回调（常驻 tick 不得回魂）");
            // 武装一次：排空后恰一次；再排空仍是那一次。
            writer.armWakeup();
            writer.submit("more");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fires.get() == 0 && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertEquals(1, fires.get(), "武装后单发：恰好一次");
            writer.submit("even-more");
            writer.flush();
            writer.awaitFlushed(2, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(150);
            assertEquals(1, fires.get(), "单发已消费：后续排空不得再触发");
        }
    }

    /** writeRaw 抛 IOException 的 backend（设备死亡路径）。 */
    private static final class ThrowingBackend extends NoopBackend {
        private final AtomicInteger written = new AtomicInteger();

        @Override public void writeRaw(String data) throws IOException {
            written.incrementAndGet();
            throw new IOException("simulated pty failure");
        }

        int writtenCount() {
            return written.get();
        }
    }

    // ── 测试桩 ─────────────────────────────────────────────────────────

    private static String payload(int size) {
        return new String(new char[size]).replace('\0', 'p');
    }

    /** 卡死 backend：每块写都等 latch；close 也等 latch（避免测试挂死）。 */
    private static final class StuckBackend extends NoopBackend {
        private final CountDownLatch release;
        private volatile boolean closed;

        StuckBackend(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void writeRaw(String data) {
            awaitRelease();
        }

        @Override
        public void flush() {
            awaitRelease();
        }

        private void awaitRelease() {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        void awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (!closed && System.nanoTime() < deadline) {
                unit.sleep(10);
            }
        }
    }

    /** 逐块记录的 backend（线程安全：写线程 add、测试线程读）。 */
    private static class RecordingBackend extends NoopBackend {
        final List<String> chunks = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger flushes = new AtomicInteger();
        private final AtomicInteger chars = new AtomicInteger();

        @Override
        public void writeRaw(String data) {
            chunks.add(data);
            chars.addAndGet(data.length());
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        int writtenCount() {
            return chunks.size();
        }

        int totalCharsWritten() { return chars.get(); }

        boolean awaitCount(int target, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (writtenCount() < target && System.nanoTime() < deadline) {
                unit.sleep(10);
            }
            return writtenCount() >= target;
        }
    }

    /** 每块 sleep 指定毫秒的慢 backend。 */
    private static final class SlowBackend extends RecordingBackend {
        private final long perWriteMs;

        SlowBackend(long perWriteMs) {
            this.perWriteMs = perWriteMs;
        }

        @Override
        public void writeRaw(String data) {
            try {
                TimeUnit.MILLISECONDS.sleep(perWriteMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.writeRaw(data);
        }
    }

    /** 最小 Backend 桩：其余方法全 no-op。 */
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
