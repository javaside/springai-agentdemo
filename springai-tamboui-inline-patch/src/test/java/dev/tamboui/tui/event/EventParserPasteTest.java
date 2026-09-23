/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.bindings.BindingSets;

/**
 * 括号粘贴（bracketed paste）读取的时序钉。
 *
 * <p><b>为什么要 shadow {@code EventParser}</b>：上游 {@code readPasteContent} 逐字符
 * {@code read(50ms)} 读粘贴内容，超时（-2）立刻 break。真机大粘贴（日志几百 KB）时终端
 * 是分块灌 pty 的——pty 输入缓冲写满后终端写阻塞、稍后恢复，块间间隙一旦超过 50ms，
 * 粘贴事件被提前掐断：剩余字节回到普通按键解析，日志里的 {@code \n} 变 Enter、
 * 到达 UI 即「自动发送」（code-tui 实报：贴大日志被分批发出去）。本 shadow 把块间等待
 * 放宽到累计静默 {@value #EXPECTED_QUIET_BUDGET_MS}ms 才放弃；人手敲不出 ESC[200~ 起始
 * 标记，放宽无副作用。这三条测试就是该行为契约：
 * <ol>
 *   <li>块间间隙（&gt;50ms、&lt;静默预算）不拆事件——上游实现红灯；</li>
 *   <li>有头无尾的畸形粘贴在预算内放弃、不挂死输入线程——防「等宽了就永远等」的回归；</li>
 *   <li>非终止符的 ESC 序列（日志里的 ANSI 颜色码）原样保留——改等待逻辑不许碰内容。</li>
 * </ol>
 */
class EventParserPasteTest {

    /** 与 shadow 实现里的静默预算保持一致的数量级断言用；实现常量为 500ms。 */
    private static final long EXPECTED_QUIET_BUDGET_MS = 500;

    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";

    @Test
    void interChunkGapWithinQuietBudgetKeepsPasteAsSingleEvent() throws Exception {
        String head = "2026-09-22 ERROR first chunk\n";
        String tail = "2026-09-22 ERROR second chunk\nthe end";
        // 200ms 块间间隙：大于上游单次 read 的 50ms 超时、小于 500ms 静默预算
        try (GapBackend backend = new GapBackend(200, PASTE_START + head, tail + PASTE_END)) {
            long t0 = System.nanoTime();
            Event event = EventParser.readEvent(backend, 50, BindingSets.defaults());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertInstanceOf(PasteEvent.class, event, "间隙内不应拆事件，实际拿到 " + event);
            assertEquals(head + tail, ((PasteEvent) event).text(),
                    "两块内容必须完整进同一个 PasteEvent");
            assertTrue(elapsedMs >= 150,
                    "应当等过间隙（~200ms），实际 " + elapsedMs + "ms 就返回了");
        }
    }

    @Test
    void pasteWithoutTerminatorGivesUpWithinQuietBudgetInsteadOfHanging() throws Exception {
        // 只有起始标记 + 半截内容，永远等不来 ESC[201~：必须在 ~500ms 预算内返回部分内容
        try (GapBackend backend = new GapBackend(0, PASTE_START + "partial log", "")) {
            long t0 = System.nanoTime();
            Event event = EventParser.readEvent(backend, 50, BindingSets.defaults());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertInstanceOf(PasteEvent.class, event);
            assertEquals("partial log", ((PasteEvent) event).text());
            // 上界钉 2×预算：放宽等待后最怕的就是这里悄悄变成永久阻塞（输入线程卡死 = 全 TUI 假死）
            assertTrue(elapsedMs < EXPECTED_QUIET_BUDGET_MS * 2 + 500,
                    "畸形粘贴应在静默预算内放弃，实际等了 " + elapsedMs + "ms");
        }
    }

    @Test
    void embeddedEscapeSequencesStayLiteralInPasteText() throws Exception {
        // 日志里常带 ANSI 颜色码（ESC[31m 等）：不是终止符就必须原样进文本，不许被吞/被解析
        String content = "\u001b[31mRED\u001b[0m normal";
        try (GapBackend backend = new GapBackend(0, PASTE_START + content + PASTE_END, "")) {
            Event event = EventParser.readEvent(backend, 50, BindingSets.defaults());
            assertInstanceOf(PasteEvent.class, event);
            assertEquals(content, ((PasteEvent) event).text());
        }
    }

    /**
     * 可编程间隙的 Backend：先给出 {@code firstChunk}，{@code gapMs} 之后追加快照线程
     * 「送达」的 {@code lateChunk}（模拟终端暂停写入后恢复）。read/peek 语义与生产
     * Backend 契约一致：有数据立即返回；无数据阻塞至多 timeoutMs 后返回 -2（超时）、
     * 不消耗任何字节；EOF（-1）本桩不产生（pty 场景读侧不会 EOF）。
     */
    private static final class GapBackend implements Backend {
        private final StringBuilder pending = new StringBuilder();
        private final String lateChunk;
        private final long deliverAtNanos;
        private volatile boolean delivered;

        GapBackend(long gapMs, String firstChunk, String lateChunk) {
            pending.append(firstChunk);
            this.lateChunk = lateChunk;
            this.deliverAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(gapMs);
        }

        private void deliverIfDue() {
            if (!delivered && System.nanoTime() >= deliverAtNanos) {
                pending.append(lateChunk);
                delivered = true;
            }
        }

        /** read：有数据立即消费并返回首字符；无数据阻塞至多 timeoutMs，仍无则 -2（不消耗）。 */
        @Override public int read(int timeoutMs) throws IOException {
            deliverIfDue();
            if (pending.length() == 0) {
                sleepQuietly(timeoutMs);
                deliverIfDue();
                if (pending.length() == 0) return -2;
            }
            char c = pending.charAt(0);
            pending.deleteCharAt(0);
            return c;
        }

        /** peek：同 read 的等待语义，但不消费。 */
        @Override public int peek(int timeoutMs) throws IOException {
            deliverIfDue();
            if (pending.length() == 0) {
                sleepQuietly(timeoutMs);
                deliverIfDue();
                if (pending.length() == 0) return -2;
            }
            return pending.charAt(0) & 0xFFFF;
        }

        private static void sleepQuietly(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ── 其余 Backend 方法与输入解析无关，全部 no-op（同 InlineTuiRunnerEventDrivenTest.RecordingBackend）──
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
        @Override public void writeRaw(byte[] data) { }
        @Override public void onResize(Runnable handler) { }
        @Override public void close() { }
    }
}
