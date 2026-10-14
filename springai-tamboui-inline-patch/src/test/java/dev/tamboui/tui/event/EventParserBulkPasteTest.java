/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.jline.utils.NonBlockingReader;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.bindings.BindingSets;

/**
 * 粘贴体<b>批量读取快路径</b>（反射穿透到 pty 原始 InputStream 整块 read）的行为钉。
 *
 * <p><b>为什么需要快路径</b>：逐字符 {@code backend.read(50ms)} 走 JLine 非阻塞层，
 * 每字符 ~6µs——实测 4MB 日志摄取要 25s+（pty 复现：~150KB/s）。期间输入线程泡在
 * readPasteContent 里，用户按键全部排队在粘贴字节后面、屏幕无任何反馈——真机实报
 * 「贴大日志卡死」。JLine 连 {@code readBuffered} 底层也是逐字节循环（NonBlockingInputStream
 * 基类实现即 {@code while(read())}），必须穿透到原始流一次 read(2) 拿整块。
 *
 * <p>本类钉三件事：
 * <ol>
 *   <li>{@code PasteBodyScanner} 终止符状态机：跨块边界匹配、失败回滚为字面量；</li>
 *   <li>快路径接线：backend 暴露 {@code reader→input→in} 反射链时整块读原始流，
 *       块间间隙（available 探测超时）在静默预算内不拆事件，UTF-8 跨块残尾由
 *       CharsetDecoder carry 保住；</li>
 *   <li>反射链断裂的 backend（测试桩/其他实现/库升级改名）自动退回逐字符慢路径，
 *       行为与既有 EventParserPasteTest 钉的契约一致。</li>
 * </ol>
 */
class EventParserBulkPasteTest {

    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";
    private static final String ESC = "\u001b";

    // ── PasteBodyScanner 状态机 ──────────────────────────────

    /** 终止符整体到达 → 返回 true 且终止符不进正文。 */
    @Test
    void scannerEndsOnTerminator() {
        StringBuilder out = new StringBuilder();
        EventParser.PasteBodyScanner sc = new EventParser.PasteBodyScanner();
        for (char c : "abc".toCharArray()) assertFalse(sc.accept(c, out));
        assertEquals("abc", out.toString());
        for (char c : PASTE_END.toCharArray()) {
            // 最后一个 ~ 之前都还没结束（返回值只在终止符完整匹配那一刻为 true）
        }
        boolean ended = false;
        for (char c : PASTE_END.toCharArray()) {
            ended = sc.accept(c, out);
        }
        assertTrue(ended);
        assertEquals("abc", out.toString(), "终止符必须整体吞掉、不进正文");
    }

    /** 终止符被拆在两次批量块之间：前半截先进状态机，回滚后正文一字不差。 */
    @Test
    void scannerHandlesTerminatorSplitAcrossChunks() {
        StringBuilder out = new StringBuilder();
        EventParser.PasteBodyScanner sc = new EventParser.PasteBodyScanner();
        for (char c : ("log" + ESC + "[20").toCharArray()) sc.accept(c, out);
        assertEquals("log", out.toString(), "未完结的终止符前缀暂不进正文");
        boolean ended = false;
        for (char c : "1~".toCharArray()) ended = sc.accept(c, out);
        assertTrue(ended);
        assertEquals("log", out.toString());
    }

    /** 非终止符的 ESC 序列（ANSI 颜色码）整体按字面量保留。 */
    @Test
    void scannerRollsBackNonTerminatorEscapes() {
        StringBuilder out = new StringBuilder();
        EventParser.PasteBodyScanner sc = new EventParser.PasteBodyScanner();
        boolean ended = false;
        String content = ESC + "[31mRED" + ESC + "[0m ok" + ESC + ESC + "x";
        for (char c : content.toCharArray()) ended |= sc.accept(c, out);
        assertFalse(ended);
        assertEquals(content, out.toString(), "非终止符 ESC 序列必须原样保留");
    }

    /** 终止符前缀后紧跟别的 ESC 序列：回滚后重新从起点判定。 */
    @Test
    void scannerRestartsAfterPartialMatch() {
        StringBuilder out = new StringBuilder();
        EventParser.PasteBodyScanner sc = new EventParser.PasteBodyScanner();
        String content = ESC + "[2" + ESC + "[201~";
        boolean ended = false;
        for (char c : content.toCharArray()) ended |= sc.accept(c, out);
        assertTrue(ended, "末尾真正的终止符必须被识别");
        assertEquals(ESC + "[2", out.toString(), "前半截 ESC[2 应回滚为字面量");
    }

    // ── 快路径接线（反射链 reader → input → in → 原始流批量读）──────────

    /**
     * 假 NonBlockingInputStream：承载 {@code in} 字段（原始流桩，延迟投放字节块）。
     * 非阻塞读请求本身按 READ_EXPIRED 处理——快路径只用它的 {@code in}。
     */
    private static final class FakeNBInputStream extends org.jline.utils.NonBlockingInputStream {
        final DelayedRawStream in;

        FakeNBInputStream(DelayedRawStream in) {
            this.in = in;
        }

        @Override public int read(long timeout, boolean isPeek) {
            return READ_EXPIRED;
        }

        @Override public void shutdown() {
        }
    }

    /**
     * 原始流桩：两段字节块，第二段在 {@code gapMs} 后才「到达」（available 变 >0）。
     * available()==0 表示未到——快路径靠它探测静默，绝不阻塞。
     */
    private static final class DelayedRawStream extends java.io.ByteArrayInputStream {
        private final byte[] late;
        private final long deliverAtNanos;
        private boolean delivered;

        DelayedRawStream(byte[] first, byte[] late, long gapMs) {
            super(first);
            this.late = late;
            this.deliverAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(gapMs);
        }

        DelayedRawStream(String firstUtf8, String lateUtf8, long gapMs) {
            this(firstUtf8.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    lateUtf8.getBytes(java.nio.charset.StandardCharsets.UTF_8), gapMs);
        }

        private void deliverIfDue() {
            if (!delivered && System.nanoTime() >= deliverAtNanos) {
                delivered = true;
                // ByteArrayInputStream 不能追加——用 buf 重建
                byte[] merged = new byte[buf.length - pos + late.length];
                System.arraycopy(buf, pos, merged, 0, merged.length - late.length);
                System.arraycopy(late, 0, merged, merged.length - late.length, late.length);
                buf = merged;
                pos = 0;
                count = merged.length;
                mark = 0;
            }
        }

        @Override public synchronized int available() {
            deliverIfDue();
            return count - pos;
        }
    }

    /**
     * 假 NonBlockingReader：只服务<b>起始标记 + 粘贴体第一个字节</b>（EventParser 经
     * backend.read/peek 走这条），并持有 {@code input} 字段完成反射链。
     */
    private static final class FakeNBReader extends NonBlockingReader {
        final FakeNBInputStream input;
        private final java.util.Deque<Character> header = new java.util.ArrayDeque<>();

        FakeNBReader(String headerChars, FakeNBInputStream input) {
            headerChars.chars().forEach(c -> header.addLast((char) c));
            this.input = input;
        }

        @Override public int read(long timeout, boolean isPeek) {
            if (header.isEmpty()) return READ_EXPIRED;
            char c = header.peekFirst();
            if (!isPeek) header.pollFirst();
            return c;
        }

        @Override public int readBuffered(char[] b, int off, int len, long timeout) {
            return READ_EXPIRED;
        }

        @Override public void close() {
        }
    }

    /** 暴露 {@code reader} 字段的 Backend 桩——EventParser 靠反射链发现快路径。 */
    private static final class BulkBackend extends StubBackend {
        final NonBlockingReader reader;

        BulkBackend(NonBlockingReader reader) {
            this.reader = reader;
        }

        @Override public int read(int timeoutMs) throws IOException { return reader.read(timeoutMs); }

        @Override public int peek(int timeoutMs) throws IOException { return reader.peek(timeoutMs); }
    }

    /**
     * 组装一次快路径粘贴：header 阶段（经非阻塞层）供起始标记 + 正文首字符；
     * 原始流供正文其余字节 + 终止符（可分两块、带间隙）。
     */
    private static BulkBackend bulkBackend(String body, long gapMs, boolean terminate)
            throws IOException {
        String header = PASTE_START + body.charAt(0);
        String rawRest = body.substring(1) + (terminate ? PASTE_END : "");
        int split = Math.max(0, rawRest.length() / 2);
        FakeNBInputStream nbIn = new FakeNBInputStream(
                new DelayedRawStream(rawRest.substring(0, split), rawRest.substring(split), gapMs));
        return new BulkBackend(new FakeNBReader(header, nbIn));
    }

    /** 无 reader 字段的最小 Backend 桩（走慢路径）。 */
    private static class StubBackend implements Backend {
        @Override public int read(int timeoutMs) throws IOException { return -2; }
        @Override public int peek(int timeoutMs) throws IOException { return -2; }
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

    /** 喂起始标记 + 内容 + 终止符，断言产出完整的单个 PasteEvent。 */
    private static Event readPaste(Backend backend) throws IOException {
        return EventParser.readEvent(backend, 50, BindingSets.defaults());
    }

    @Test
    void bulkPathReadsWholePasteWithGapsAsOneEvent() throws Exception {
        String body = "chunk-one\n" + "x".repeat(8192) + "\nchunk-two";
        Event event = readPaste(bulkBackend(body, 150, true));
        assertInstanceOf(PasteEvent.class, event);
        assertEquals(body, ((PasteEvent) event).text(), "跨块间隙（150ms>单次50ms超时）不得拆事件");
    }

    /** 快路径也必须遵守静默预算：有头无尾 ≤~500ms 放弃，不永久等。 */
    @Test
    void bulkPathGivesUpWithinQuietBudget() throws Exception {
        String body = "partial";
        long t0 = System.nanoTime();
        Event event = readPaste(bulkBackend(body, 0, false));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertInstanceOf(PasteEvent.class, event);
        assertEquals(body, ((PasteEvent) event).text());
        assertTrue(ms < 2000, "畸形粘贴应在静默预算内放弃，实际 " + ms + "ms");
    }

    /** 内容里的 ESC 序列经快路径仍按字面量保留（原始流只搬运字节、不解析）。 */
    @Test
    void bulkPathKeepsEmbeddedEscapesLiteral() throws Exception {
        String body = ESC + "[31mRED" + ESC + "[0m";
        Event event = readPaste(bulkBackend(body, 0, true));
        assertInstanceOf(PasteEvent.class, event);
        assertEquals(body, ((PasteEvent) event).text());
    }

    /** 块边界正好落在终止符中间：状态机跨块匹配，不多吞不少吞。 */
    @Test
    void bulkPathTerminatorStraddlingChunks() throws Exception {
        String body = "L1\nL2\nL3";
        Event event = readPaste(bulkBackend(body, 20, true));
        assertInstanceOf(PasteEvent.class, event);
        assertEquals(body, ((PasteEvent) event).text());
    }

    /**
     * UTF-8 多字节字符被字节级拆在两块之间：CharsetDecoder 的 carry 必须保住残尾，
     * 不得把它替换成 �——中文日志粘贴的常态路径。
     */
    @Test
    void bulkPathDecodesUtf8StraddlingChunks() throws Exception {
        // header 供起始标记 + 首字符「日」；原始流只供「志」：第一块 [E5]（首字节），
        // 第二块 [BF 97]（其余字节）+ 终止符——多字节序列横跨三次读取
        byte[] zhi = "志".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] endMark = PASTE_END.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] late = new byte[zhi.length - 1 + endMark.length];
        System.arraycopy(zhi, 1, late, 0, zhi.length - 1);
        System.arraycopy(endMark, 0, late, zhi.length - 1, endMark.length);
        FakeNBInputStream nb = new FakeNBInputStream(new DelayedRawStream(
                new byte[]{zhi[0]}, late, 10));
        BulkBackend backend = new BulkBackend(new FakeNBReader(PASTE_START + "日", nb));
        Event event = readPaste(backend);
        assertInstanceOf(PasteEvent.class, event);
        assertEquals("日志", ((PasteEvent) event).text(), "跨块多字节序列被截断/替换："
                + ((PasteEvent) event).text());
    }

    /** 无 reader 字段的 backend：供完起始标记即 EOF，静默退化到逐字符慢路径。 */
    @Test
    void backendWithoutReaderFallsBackGracefully() throws Exception {
        StringBuilder queue = new StringBuilder(PASTE_START);
        StubBackend consuming = new StubBackend() {
            @Override public int read(int timeoutMs) {
                if (queue.length() == 0) return -1;   // 供完即 EOF
                char c = queue.charAt(0);
                queue.deleteCharAt(0);
                return c;
            }

            @Override public int peek(int timeoutMs) {
                return queue.length() > 0 ? queue.charAt(0) : -1;
            }
        };
        Event event = readPaste(consuming);
        // EOF 立即收尾：空粘贴体（行为同慢路径契约）
        assertInstanceOf(PasteEvent.class, event);
        assertEquals("", ((PasteEvent) event).text());
    }

    // ── 背靠背括号粘贴（多文件拖拽）─────────────────────────

    /**
     * Terminal.app 多文件拖拽实测（2026-10-14 od 取证）：每文件一条<b>独立</b>括号粘贴
     * 背靠背 —— {@code ESC[200~p1 ESC[201~ESC[200~p2 ESC[201~...}。批量快路径按块整读、
     * 扫到第一条终止符就收手：同一块里排在后面的整条粘贴曾被静默丢弃（实报症状：拖 5 张
     * 只显示 2 张，幸存哪几张看块边界运气）。背靠背的每一条都必须产出独立 PasteEvent。
     */
    @Test
    void adjacentBracketedPastesInOneChunkYieldSequentialEvents() throws Exception {
        String stream = PASTE_START + "p1 " + PASTE_END
                + PASTE_START + "p2 " + PASTE_END
                + PASTE_START + "p3" + PASTE_END;
        String header = PASTE_START + "p";                  // 非阻塞层：起始标记 + 正文首字符
        String raw = stream.substring(header.length());     // 原始流一次全给（含后两条完整粘贴）
        FakeNBInputStream nb = new FakeNBInputStream(new DelayedRawStream(raw, "", 0L));
        BulkBackend backend = new BulkBackend(new FakeNBReader(header, nb));

        Event first = readPaste(backend);
        assertInstanceOf(PasteEvent.class, first);
        assertEquals("p1 ", ((PasteEvent) first).text(), "第一条粘贴正文不全");

        Event second = readPaste(backend);
        assertInstanceOf(PasteEvent.class, second);
        assertEquals("p2 ", ((PasteEvent) second).text(),
                "同块里的第二条粘贴被终止符后的批量丢弃吞掉了");

        Event third = readPaste(backend);
        assertInstanceOf(PasteEvent.class, third);
        assertEquals("p3", ((PasteEvent) third).text(), "第三条粘贴丢失");
    }

    /** 粘贴边界落在 UTF-8 多字节字符中间：残尾字节必须原样留给下一条粘贴，不得变 �。 */
    @Test
    void overshootPreservesMultibyteBytesAcrossPasteBoundary() throws Exception {
        // 第二条粘贴正文以中文开头：把「日」的首字节切进本块末尾（overshoot 区），
        // 残尾两个字节必须以原始字节留给下一次解析，拼回「日志」。
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        raw.writeBytes("1 ".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        raw.writeBytes(PASTE_END.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        raw.writeBytes(PASTE_START.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ri = "日".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        raw.write(ri[0]);                                 // 「日」的首字节切进本块末尾
        raw.write(ri, 1, ri.length - 1);                  // 残尾
        raw.writeBytes("志".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        raw.writeBytes(PASTE_END.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String header = PASTE_START + "p";
        FakeNBInputStream nb = new FakeNBInputStream(new DelayedRawStream(
                raw.toByteArray(), new byte[0], 0L));
        BulkBackend backend = new BulkBackend(new FakeNBReader(header, nb));

        Event first = readPaste(backend);
        assertInstanceOf(PasteEvent.class, first);
        assertEquals("p1 ", ((PasteEvent) first).text());

        Event second = readPaste(backend);
        assertInstanceOf(PasteEvent.class, second);
        assertEquals("日志", ((PasteEvent) second).text(),
                "跨粘贴边界的多字节字符被截断/替换：" + ((PasteEvent) second).text());
    }

}
