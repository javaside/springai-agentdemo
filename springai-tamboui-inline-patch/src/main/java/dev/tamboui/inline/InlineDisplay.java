/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.AnsiCellWriter;
import dev.tamboui.terminal.AnsiStringBuilder;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.text.Text;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * TamboUI 0.4.0 compatible inline display with differential frame submission.
 *
 * <p>The public surface remains compatible with 0.4.0. Unlike the upstream implementation, an
 * unchanged frame emits no terminal bytes, while changed frames are submitted as row-local cell
 * patches in one raw write and one flush.</p>
 */
public final class InlineDisplay implements AutoCloseable {
    private final int height;
    private final boolean autoWidth;
    private int width;
    private Buffer currentBuffer;
    private Buffer previousBuffer;
    private final PrintWriter out;
    private final Backend backend;
    private boolean initialized;
    private boolean released;
    private boolean shouldClearOnClose;
    private boolean previousFrameValid;
    private int lastCursorX;
    private int lastCursorY;
    private int currentHeight;

    InlineDisplay(int height, int width, Backend backend, PrintWriter out) {
        this(height, width, false, backend, out);
    }

    InlineDisplay(int height, int width, boolean autoWidth, Backend backend, PrintWriter out) {
        this.height = height;
        this.width = width;
        this.autoWidth = autoWidth;
        this.backend = backend;
        this.out = out;
        this.currentBuffer = Buffer.empty(Rect.of(width, height));
        this.previousBuffer = Buffer.empty(Rect.of(width, height));
    }

    public static InlineDisplay create(int height) throws IOException {
        return withBackend(height, BackendFactory.create());
    }

    public static InlineDisplay withBackend(int height, Backend backend) throws IOException {
        Size size = backend.size();
        return new InlineDisplay(height, size.width(), true, backend, createPrintWriter(backend));
    }

    public static InlineDisplay create(int height, int width) throws IOException {
        return withBackend(height, width, BackendFactory.create());
    }

    public static InlineDisplay withBackend(int height, int width, Backend backend) throws IOException {
        return new InlineDisplay(height, width, backend, createPrintWriter(backend));
    }

    private static PrintWriter createPrintWriter(Backend backend) {
        try {
            backend.writeRaw("");
            return new PrintWriter(new Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) throws IOException {
                    backend.writeRaw(new String(cbuf, off, len));
                }

                @Override
                public void flush() throws IOException {
                    backend.flush();
                }

                @Override
                public void close() throws IOException {
                    flush();
                }
            }, true);
        } catch (UnsupportedOperationException | IOException e) {
            return new PrintWriter(System.out, true);
        }
    }

    public InlineDisplay clearOnClose() {
        shouldClearOnClose = true;
        return this;
    }

    public void render(BiConsumer<Rect, Buffer> renderer) {
        render(renderer, height, -1, -1);
    }

    public void render(BiConsumer<Rect, Buffer> renderer, int contentHeight, int cursorX, int cursorY) {
        ensureInitialized();
        syncWidth();
        int desiredHeight = Math.max(0, contentHeight);
        StringBuilder batch = new StringBuilder();
        resizeDisplay(desiredHeight, batch);
        if (currentHeight <= 0) {
            submit(batch);
            return;
        }

        currentBuffer.clear();
        renderer.accept(currentBuffer.area(), currentBuffer);
        int targetX = cursorX >= 0 && cursorY >= 0 ? cursorX : findLastContentPosition(currentBuffer, 0);
        int targetY = cursorX >= 0 && cursorY >= 0 ? cursorY : 0;
        targetX = Math.max(0, Math.min(targetX, Math.max(0, width - 1)));
        targetY = Math.max(0, Math.min(targetY, currentHeight - 1));

        Buffer comparison = previousFrameValid
                ? previousBuffer
                : Buffer.empty(currentBuffer.area());
        List<InlinePatch.PatchRun> runs = InlinePatch.runs(comparison, currentBuffer);
        if (!runs.isEmpty() || lastCursorX != targetX || lastCursorY != targetY) {
            appendFramePatch(batch, runs, targetX, targetY);
        }
        submit(batch);

        Buffer swap = previousBuffer;
        previousBuffer = currentBuffer;
        currentBuffer = swap;
        previousFrameValid = true;
        lastCursorX = targetX;
        lastCursorY = targetY;
    }

    public void setLine(int line, String content) {
        if (line < 0 || line >= height) return;
        render((area, buffer) -> {
            if (previousFrameValid) copy(previousBuffer, buffer);
            buffer.setString(0, line, content, Style.EMPTY);
        }, height, -1, -1);
    }

    public void setLine(int line, Text text) {
        if (line < 0 || line >= height) return;
        render((area, buffer) -> {
            if (previousFrameValid) copy(previousBuffer, buffer);
            for (int x = 0; x < width; x++) buffer.set(x, line, Cell.EMPTY);
            if (!text.lines().isEmpty()) buffer.setLine(0, line, text.lines().get(0));
        }, height, -1, -1);
    }

    public void println(String message) {
        ensureInitialized();
        syncWidth();
        if (currentHeight == 0) {
            out.println(message);
            return;
        }
        StringBuilder batch = new StringBuilder();
        appendHome(batch);
        batch.append("\u001b[1L").append(message).append("\u001b[K\n\r");
        submit(batch);
        lastCursorX = 0;
        lastCursorY = 0;
    }

    public void println(Text text) {
        if (text.lines().isEmpty()) {
            println("");
            return;
        }
        syncWidth();
        Buffer temp = Buffer.empty(Rect.of(width, 1));
        temp.setLine(0, 0, text.lines().get(0));
        println(temp.toAnsiStringTrimmed());
    }

    /** Invalidates only the remembered live frame; the next render rebuilds that area once. */
    public void invalidateFrame() {
        previousFrameValid = false;
    }

    public void release() {
        if (released) return;
        StringBuilder batch = new StringBuilder();
        if (shouldClearOnClose) appendClearDisplayArea(batch);
        batch.append('\r');
        int toBottom = currentHeight - 1 - lastCursorY;
        down(batch, toBottom);
        batch.append(AnsiStringBuilder.RESET).append("\u001b[?25h\u001b[0 q\r\n");
        submit(batch);
        released = true;
    }

    public int height() {
        return height;
    }

    public int width() {
        syncWidth();
        return width;
    }

    @Override
    public void close() throws IOException {
        if (!released) release();
        backend.close();
    }

    Buffer previousFrameForTest() {
        return previousBuffer.copy();
    }

    private void ensureInitialized() {
        if (initialized) return;
        try {
            backend.hideCursor();
            backend.flush();
        } catch (IOException ignored) {
        }
        initialized = true;
    }

    private void syncWidth() {
        if (!autoWidth) return;
        try {
            int newWidth = backend.size().width();
            if (newWidth <= 0 || newWidth == width) return;
            width = newWidth;
            currentBuffer = Buffer.empty(Rect.of(width, currentHeight));
            previousBuffer = Buffer.empty(Rect.of(width, currentHeight));
            previousFrameValid = false;
            lastCursorX = 0;
            lastCursorY = 0;
        } catch (IOException ignored) {
        }
    }

    private void resizeDisplay(int newHeight, StringBuilder batch) {
        if (newHeight == currentHeight) return;
        int oldHeight = currentHeight;
        appendHome(batch);
        int delta = newHeight - oldHeight;
        if (delta > 0) {
            if (oldHeight > 0) down(batch, oldHeight - 1);
            for (int i = 0; i < delta; i++) batch.append("\r\n");
            up(batch, newHeight - 1);
            batch.append('\r');
        } else {
            down(batch, newHeight);
            batch.append('\r').append("\u001b[").append(-delta).append('M');
            up(batch, newHeight);
            batch.append('\r');
        }
        previousBuffer = previousFrameValid
                ? InlinePatch.preserveOverlap(previousBuffer, width, newHeight)
                : Buffer.empty(Rect.of(width, newHeight));
        currentBuffer = Buffer.empty(Rect.of(width, newHeight));
        currentHeight = newHeight;
        lastCursorX = 0;
        lastCursorY = 0;
    }

    private void appendFramePatch(StringBuilder batch, List<InlinePatch.PatchRun> runs,
                                  int targetX, int targetY) {
        appendHome(batch);
        int row = 0;
        try (AnsiCellWriter cells = new AnsiCellWriter(batch::append)) {
            for (InlinePatch.PatchRun run : runs) {
                if (run.row() > row) down(batch, run.row() - row);
                else if (run.row() < row) up(batch, row - run.row());
                batch.append('\r');
                right(batch, run.startCol());
                for (int col = run.startCol(); col < run.endColExclusive(); col++) {
                    Cell cell = currentBuffer.get(col, run.row());
                    if (!cell.isContinuation()) cells.writeCell(cell);
                }
                row = run.row();
            }
        }
        batch.append('\r');
        up(batch, row);
        down(batch, targetY);
        right(batch, targetX);
    }

    private void appendHome(StringBuilder batch) {
        batch.append('\r');
        up(batch, lastCursorY);
    }

    private void appendClearDisplayArea(StringBuilder batch) {
        appendHome(batch);
        for (int row = 0; row < currentHeight; row++) {
            if (row > 0) batch.append("\r\n");
            batch.append("\r\u001b[K");
        }
        up(batch, Math.max(0, currentHeight - 1));
        batch.append('\r');
    }

    private void submit(StringBuilder batch) {
        if (batch.isEmpty()) return;
        try {
            backend.writeRaw(batch.toString());
            backend.flush();
        } catch (IOException ignored) {
            previousFrameValid = false;
        }
    }

    private int findLastContentPosition(Buffer buffer, int line) {
        for (int x = width - 1; x >= 0; x--) {
            Cell cell = buffer.get(x, line);
            if (cell.isContinuation()) continue;
            if (!cell.isEmpty()) return Math.min(width - 1, x + dev.tamboui.text.CharWidth.of(cell.symbol()));
        }
        return 0;
    }

    private static void copy(Buffer source, Buffer target) {
        int height = Math.min(source.height(), target.height());
        int width = Math.min(source.width(), target.width());
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) target.set(col, row, source.get(col, row));
        }
    }

    private static void up(StringBuilder out, int n) {
        if (n > 0) out.append("\u001b[").append(n).append('A');
    }

    private static void down(StringBuilder out, int n) {
        if (n > 0) out.append("\u001b[").append(n).append('B');
    }

    private static void right(StringBuilder out, int n) {
        if (n > 0) out.append("\u001b[").append(n).append('C');
    }
}
