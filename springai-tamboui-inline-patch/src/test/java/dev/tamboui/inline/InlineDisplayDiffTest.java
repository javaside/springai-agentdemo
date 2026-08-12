package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineDisplayDiffTest {

    private RecordingBackend backend;
    private String previousHardwareCursorMode;

    @BeforeEach
    void setUp() {
        previousHardwareCursorMode = System.getProperty("codetui.hardwareCursor");
        System.setProperty("codetui.hardwareCursor", "never");
        backend = new RecordingBackend(40, 24);
    }

    @AfterEach
    void restoreTerminalModes() {
        restoreProperty("codetui.hardwareCursor", previousHardwareCursorMode);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    @Test
    void visibleHardwareCursorModeShowsCursorForIme() {
        System.setProperty("codetui.hardwareCursor", "always");
        InlineDisplay display = display(1);

        render(display, "input", null, 5, 0);

        assertEquals(1, backend.showCursorCalls());
        assertEquals(0, backend.hideCursorCalls());
    }

    @Test
    void identicalSecondFrameWritesAndFlushesNothing() {
        InlineDisplay display = display(2);
        render(display, "hello", "", 1, 0);
        backend.resetCounts();

        render(display, "hello", "", 1, 0);

        assertEquals(0, backend.writeCalls());
        assertEquals(0, backend.flushCalls());
        assertArrayEquals(new byte[0], backend.output());
    }

    @Test
    void oneCellChangeDoesNotEraseOrRewriteRowsOutsideCursorBand() {
        // 光标带（光标行 ±1）内的行会因 IME 修复被整行重申，「不重写无关行」的
        // 最小差分契约只对带外行成立：把 thinking 放到光标行 +3 处验证。
        InlineDisplay display = display(4);
        renderRows(display, new String[]{"input", null, null, "thinking"}, 5, 0);
        backend.resetCounts();

        renderRows(display, new String[]{"input!", null, null, "thinking"}, 6, 0);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("\u001b[K"), raw);
        assertFalse(raw.contains("thinking"), raw);
        assertEquals(1, backend.writeCalls());
        assertEquals(1, backend.flushCalls());
    }

    @Test
    void shimmerStyleChangeOutsideCursorBandDoesNotRewriteInputRow() {
        InlineDisplay display = display(4);
        renderStyled(display, false);
        backend.resetCounts();

        renderStyled(display, true);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("input-border"), raw);
        assertFalse(raw.contains("\u001b[K"), raw);
        assertTrue(raw.contains("thinking"), raw);
    }

    @Test
    void growingOnlyDrawsNewAndActuallyChangedRowsOutsideCursorBand() {
        InlineDisplay display = display(4);
        renderRows(display, new String[]{"stable", null, null}, 0, 0);
        backend.resetCounts();

        renderRows(display, new String[]{"stable", null, null, "new"}, 0, 0);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("stable"), raw);
        assertTrue(raw.contains("new"), raw);
        assertFalse(raw.contains("\u001b[K"), raw);
    }

    @Test
    void shrinkingDoesNotRewriteSurvivingRows() {
        InlineDisplay display = display(2);
        render(display, "surviving", "removed", 0, 0);
        backend.resetCounts();

        render(display, "surviving", null, 0, 0);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("surviving"), raw);
        assertFalse(raw.contains("removed"), raw);
        assertTrue(raw.contains("\u001b[1M"), raw);
    }

    @Test
    void invalidatedFrameRebuildsLiveAreaOnce() {
        InlineDisplay display = display(1);
        render(display, "stable", null, 0, 0);
        display.invalidateFrame();
        backend.resetCounts();

        render(display, "stable", null, 0, 0);
        assertTrue(backend.outputUtf8().contains("stable"));
        backend.resetCounts();
        render(display, "stable", null, 0, 0);
        assertEquals(0, backend.writeCalls());
    }

    @Test
    void cursorOnlyChangeDoesNotRewriteCells() {
        InlineDisplay display = display(1);
        render(display, "abc", null, 0, 0);
        backend.resetCounts();

        render(display, "abc", null, 2, 0);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("abc"), raw);
        assertFalse(raw.contains("\u001b[K"), raw);
        assertEquals(1, backend.writeCalls());
        assertEquals(1, backend.flushCalls());
    }

    @Test
    void printBatchRedrawsLiveAreaOnceAtTheEnd() {
        InlineDisplay display = display(1);
        render(display, "LIVE", null, 0, 0);
        backend.resetCounts();

        display.beginPrintBatch();
        display.println("one");
        display.println("two");
        display.println("three");
        display.endPrintBatch();

        String raw = backend.outputUtf8();
        assertEquals(1, occurrences(raw, "one"));
        assertEquals(1, occurrences(raw, "two"));
        assertEquals(1, occurrences(raw, "three"));
        // 批末必须把 live 区完整重画一次（内容出现恰一次）：插行把 live 区推离屏幕底部后，
        // 靠这次重画把它拉回。若被跳过（0 次），live 区被挤出后永不恢复。
        assertEquals(1, occurrences(raw, "LIVE"));
        assertEquals(1, backend.writeCalls());
        assertEquals(1, backend.flushCalls());
    }

    @Test
    void growingFromZeroMovesCursorUpByFullHeight() {
        InlineDisplay display = display(4);
        display.render((area, buffer) -> buffer.setString(0, 0, "a", Style.EMPTY), 4, 0, 0);

        String raw = backend.outputUtf8();
        // 首帧生长序列（第一个 EL 之前）：4 个换行把光标下移 4 行后必须退回 4 行，
        // 退回 3 行会让整个 live 区比预期低 1 行（启动画面整体下移的根源）。
        String growth = raw.substring(0, raw.indexOf("\u001b[K"));
        assertTrue(growth.contains("\u001b[4A"), growth);
        assertFalse(growth.contains("\u001b[3A"), growth);
    }

    @Test
    void firstFrameAndInvalidatedFrameEraseEachRowTail() {
        InlineDisplay display = display(2);
        render(display, "one", "two", 0, 0);
        // 首帧：每行先 EL 再写，清掉终端上可能残留的旧行尾。
        assertEquals(2, occurrences(backend.outputUtf8(), "\u001b[K"));

        display.invalidateFrame();
        backend.resetCounts();
        render(display, "one", "two", 0, 0);
        assertEquals(2, occurrences(backend.outputUtf8(), "\u001b[K"));
        backend.resetCounts();
        render(display, "one", "two", 0, 0);
        assertEquals(0, backend.writeCalls());
    }

    @Test
    void oneHundredIdenticalFramesStaySilent() {
        InlineDisplay display = display(1);
        render(display, "steady", null, 2, 0);
        backend.resetCounts();

        for (int i = 0; i < 100; i++) render(display, "steady", null, 2, 0);

        assertEquals(0, backend.writeCalls());
        assertEquals(0, backend.flushCalls());
    }

    @Test
    void enabledSynchronizedOutputWrapsOneNonEmptyFrameOnce() {
        InlineDisplay display = new InlineDisplay(1, 40, backend, backend.writer(),
                SynchronizedOutput.from(java.util.Map.of(), "always"));
        render(display, "first", null, 0, 0);
        String raw = backend.outputUtf8();
        assertEquals(1, occurrences(raw, "\u001b[?2026h"));
        assertEquals(1, occurrences(raw, "\u001b[?2026l"));
    }

    @Test
    void cjkReplacementWritesWholeGlyphWithoutLineErase() {
        InlineDisplay display = display(1);
        render(display, "中", null, 2, 0);
        backend.resetCounts();

        render(display, "文", null, 2, 0);

        String raw = backend.outputUtf8();
        assertTrue(raw.contains("文"), raw);
        assertFalse(raw.contains("\u001b[K"), raw);
        assertEquals(1, backend.writeCalls());
    }

    @Test
    void wideContinuationRunRepositionsAndArmsCursorBandRepair() {
        InlineDisplay display = display(1);
        // before: col0='│', col1='a', col3=CONTINUATION, col4='A', col39='│'
        renderCells(display, "a", "A");
        backend.resetCounts();
        // after: 输入「中」后 col1='中'(col2=cont)，col3 仍是 continuation（光标区链），col4='B'
        renderCells(display, "中", "B");
        String raw = backend.outputUtf8();
        // 中（col1，宽2）后 continuation 链停在 col3，B 在 col4：必须显式 right(1) 再写 B。
        assertTrue(raw.contains("\u001b[1CB"), raw);
        // 同帧即须整行重申（行尾右竖线在内），抵御 IME 预编辑清理同步擦除。
        assertTrue(raw.contains("│"), raw);

        // macOS IME 清理预编辑串是异步的，可能晚于编辑帧若干 Tick 并越界擦坏相邻行右缘。
        // 触及光标带的变更须武装连续 8 帧的整行重申窗口；窗口结束才恢复静止零输出。
        for (int frame = 1; frame <= 8; frame++) {
            backend.resetCounts();
            renderCells(display, "中", "B");
            raw = backend.outputUtf8();
            assertTrue(raw.contains("│"), "第 " + frame + " 帧重申窗口内必须补画行尾竖线：" + raw);
        }

        backend.resetCounts();
        renderCells(display, "中", "B");
        assertEquals(0, backend.writeCalls(), "重申窗口结束后，相同帧必须恢复静默");
    }

    @Test
    void asciiEditOnCursorRowAlsoArmsCursorBandRepair() {
        // 拼音被取消时应用收不到任何事件、也没有宽字符上屏；损坏只能靠下一次任意
        // 编辑活动修复。故普通 ASCII 编辑同样要武装光标带窗口。
        InlineDisplay display = display(1);
        renderCells(display, "a", "A");
        backend.resetCounts();

        renderCells(display, "b", "A");
        assertTrue(backend.outputUtf8().contains("│"), backend.outputUtf8());

        backend.resetCounts();
        renderCells(display, "b", "A");
        assertTrue(backend.outputUtf8().contains("│"), "窗口首帧必须重申光标行");
    }

    @Test
    void cursorBandRepairCoversRowsAboveAndBelowCursor() {
        // 实测 IME 清理会擦坏光标行**上方**顶边框的尾段与圆角（对话框右侧缺角），
        // 修复带必须覆盖光标行 ±1（输入框场景正好是顶边框与底边框）。
        InlineDisplay display = display(3);
        renderBox(display, "a");
        backend.resetCounts();

        renderBox(display, "中");

        String raw = backend.outputUtf8();
        assertTrue(raw.contains("╮"), "必须重申光标行上方（顶边框圆角）：" + raw);
        assertTrue(raw.contains("╯"), "必须重申光标行下方（底边框圆角）：" + raw);
    }

    @Test
    void runOutsideCursorBandDoesNotArmRepair() {
        InlineDisplay display = display(4);
        renderRows(display, new String[]{"input", null, null, "status"}, 0, 0);
        backend.resetCounts();

        renderRows(display, new String[]{"input", null, null, "status!"}, 0, 0);
        backend.resetCounts();

        renderRows(display, new String[]{"input", null, null, "status!"}, 0, 0);
        assertEquals(0, backend.writeCalls(), "带外变更不得武装修复窗口");
    }

    /** 3 行圆角框：row0 顶边框（含 ╮），row1 = │ + 文本 + │（光标行），row2 底边框（含 ╯）。 */
    private void renderBox(InlineDisplay display, String text) {
        display.render((area, buffer) -> {
            buffer.set(0, 0, Cell.EMPTY.symbol("╭"));
            for (int x = 1; x < 39; x++) buffer.set(x, 0, Cell.EMPTY.symbol("─"));
            buffer.set(39, 0, Cell.EMPTY.symbol("╮"));
            buffer.set(0, 1, Cell.EMPTY.symbol("│"));
            buffer.setString(1, 1, text, Style.EMPTY);
            buffer.set(39, 1, Cell.EMPTY.symbol("│"));
            buffer.set(0, 2, Cell.EMPTY.symbol("╰"));
            for (int x = 1; x < 39; x++) buffer.set(x, 2, Cell.EMPTY.symbol("─"));
            buffer.set(39, 2, Cell.EMPTY.symbol("╯"));
        }, 3, 1, 1);
    }

    private void renderCells(InlineDisplay display, String cell1, String cell4) {
        display.render((area, buffer) -> {
            buffer.set(0, 0, Cell.EMPTY.symbol("\u2502"));
            buffer.setString(1, 0, cell1, Style.EMPTY);
            buffer.set(3, 0, Cell.CONTINUATION);
            buffer.set(4, 0, Cell.EMPTY.symbol(cell4));
            buffer.set(39, 0, Cell.EMPTY.symbol("\u2502"));
        }, 1, 0, 0);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int index = 0; (index = haystack.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    private InlineDisplay display(int height) {
        return new InlineDisplay(height, 40, backend, backend.writer(),
                SynchronizedOutput.from(java.util.Map.of(), "never"));
    }

    private static void render(InlineDisplay display, String first, String second, int cx, int cy) {
        int height = second == null ? 1 : 2;
        display.render((area, buffer) -> {
            buffer.setString(0, 0, first, Style.EMPTY);
            if (second != null) buffer.setString(0, 1, second, Style.EMPTY);
        }, height, cx, cy);
    }

    private static void renderStyled(InlineDisplay display, boolean highlighted) {
        display.render((area, buffer) -> {
            buffer.setString(0, 0, "input-border", Style.EMPTY);
            Style style = highlighted ? Style.EMPTY.fg(Color.YELLOW) : Style.EMPTY.fg(Color.BLUE);
            buffer.setString(0, 3, "thinking", style);
        }, 4, 0, 0);
    }

    /** 按行数组渲染（null 行留空），光标 (cx, cy)；行数 = 数组长度。 */
    private static void renderRows(InlineDisplay display, String[] rows, int cx, int cy) {
        display.render((area, buffer) -> {
            for (int i = 0; i < rows.length; i++) {
                if (rows[i] != null) buffer.setString(0, i, rows[i], Style.EMPTY);
            }
        }, rows.length, cx, cy);
    }

    private static final class RecordingBackend implements Backend {
        private final int width;
        private final int height;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int writeCalls;
        private int flushCalls;
        private int showCursorCalls;
        private int hideCursorCalls;

        private RecordingBackend(int width, int height) {
            this.width = width;
            this.height = height;
        }

        java.io.PrintWriter writer() {
            return new java.io.PrintWriter(new java.io.OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        }

        byte[] output() {
            return output.toByteArray();
        }

        String outputUtf8() {
            return output.toString(StandardCharsets.UTF_8);
        }

        int writeCalls() {
            return writeCalls;
        }

        int flushCalls() {
            return flushCalls;
        }

        int showCursorCalls() {
            return showCursorCalls;
        }

        int hideCursorCalls() {
            return hideCursorCalls;
        }

        void resetCounts() {
            output.reset();
            writeCalls = 0;
            flushCalls = 0;
        }

        @Override public void draw(DiffResult diff) { }
        @Override public void flush() { flushCalls++; }
        @Override public void clear() { }
        @Override public Size size() { return new Size(width, height); }
        @Override public void showCursor() { showCursorCalls++; }
        @Override public void hideCursor() { hideCursorCalls++; }
        @Override public Position getCursorPosition() { return new Position(0, 0); }
        @Override public void setCursorPosition(Position position) { }
        @Override public void enterAlternateScreen() { }
        @Override public void leaveAlternateScreen() { }
        @Override public void enableRawMode() { }
        @Override public void disableRawMode() { }
        @Override public void writeRaw(byte[] data) throws IOException {
            writeCalls++;
            output.write(data);
        }
        @Override public void onResize(Runnable handler) { }
        @Override public int read(int timeoutMs) { return -2; }
        @Override public int peek(int timeoutMs) { return -2; }
        @Override public void close() { }
    }
}
