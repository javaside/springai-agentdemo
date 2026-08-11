package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
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

    @BeforeEach
    void setUp() {
        backend = new RecordingBackend(40, 24);
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
    void oneCellChangeDoesNotEraseOrRewriteOtherRows() {
        InlineDisplay display = display(2);
        render(display, "input", "thinking", 5, 0);
        backend.resetCounts();

        render(display, "input!", "thinking", 6, 0);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("\u001b[K"), raw);
        assertFalse(raw.contains("thinking"), raw);
        assertEquals(1, backend.writeCalls());
        assertEquals(1, backend.flushCalls());
    }

    @Test
    void shimmerStyleChangeDoesNotRewriteInputRow() {
        InlineDisplay display = display(2);
        renderStyled(display, false);
        backend.resetCounts();

        renderStyled(display, true);

        String raw = backend.outputUtf8();
        assertFalse(raw.contains("input-border"), raw);
        assertFalse(raw.contains("\u001b[K"), raw);
        assertTrue(raw.contains("thinking"), raw);
    }

    @Test
    void growingOnlyDrawsNewAndActuallyChangedRows() {
        InlineDisplay display = display(2);
        render(display, "stable", null, 0, 0);
        backend.resetCounts();

        render(display, "stable", "new", 0, 0);

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
    void printBatchDoesNotRedrawLiveAreaPerLine() {
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
        assertEquals(0, occurrences(raw, "LIVE"));
        assertEquals(1, backend.writeCalls());
        assertEquals(1, backend.flushCalls());
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
            buffer.setString(0, 1, "thinking", style);
        }, 2, 0, 0);
    }

    private static final class RecordingBackend implements Backend {
        private final int width;
        private final int height;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int writeCalls;
        private int flushCalls;

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

        void resetCounts() {
            output.reset();
            writeCalls = 0;
            flushCalls = 0;
        }

        @Override public void draw(DiffResult diff) { }
        @Override public void flush() { flushCalls++; }
        @Override public void clear() { }
        @Override public Size size() { return new Size(width, height); }
        @Override public void showCursor() { }
        @Override public void hideCursor() { }
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
