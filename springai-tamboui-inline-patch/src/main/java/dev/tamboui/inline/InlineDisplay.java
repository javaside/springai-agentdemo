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
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Text;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.function.BiConsumer;

/**
 * TamboUI 0.4.0 compatible inline display.
 *
 * <p>This source starts from the upstream MIT implementation so code-tui can carry a reproducible
 * compatibility patch until an upstream release contains differential inline rendering.</p>
 */
public final class InlineDisplay implements AutoCloseable {
    private final int height;
    private final boolean autoWidth;
    private int width;
    private Buffer buffer;
    private final PrintWriter out;
    private final Backend backend;
    private boolean initialized;
    private boolean released;
    private boolean shouldClearOnClose;
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
        this.buffer = Buffer.empty(Rect.of(width, height));
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
        resizeDisplay(contentHeight);
        if (currentHeight > 0) {
            buffer.clear();
            renderer.accept(buffer.area(), buffer);
            redrawDisplayArea(cursorX, cursorY);
        }
    }

    public void setLine(int line, String content) {
        if (line < 0 || line >= height) return;
        ensureInitialized();
        syncWidth();
        if (currentHeight != height) resizeDisplay(height);
        for (int x = 0; x < width; x++) buffer.set(x, line, Cell.EMPTY);
        buffer.setString(0, line, content, Style.EMPTY);
        redrawDisplayArea(-1, -1);
    }

    public void setLine(int line, Text text) {
        if (line < 0 || line >= height) return;
        ensureInitialized();
        syncWidth();
        if (currentHeight != height) resizeDisplay(height);
        for (int x = 0; x < width; x++) buffer.set(x, line, Cell.EMPTY);
        if (!text.lines().isEmpty()) buffer.setLine(0, line, text.lines().get(0));
        redrawDisplayArea(-1, -1);
    }

    public void println(String message) {
        ensureInitialized();
        syncWidth();
        if (currentHeight == 0) {
            out.println(message);
            return;
        }
        try {
            backend.carriageReturn();
            if (lastCursorY > 0) backend.moveCursorUp(lastCursorY);
            backend.insertLines(1);
            out.print(message);
            backend.eraseToEndOfLine();
            out.print("\n");
            backend.carriageReturn();
            lastCursorY = 0;
            out.flush();
        } catch (IOException ignored) {
        }
        redrawDisplayArea(-1, -1);
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

    public void release() {
        if (released) return;
        if (shouldClearOnClose) clearDisplayArea();
        try {
            backend.carriageReturn();
            int toBottom = currentHeight - 1 - lastCursorY;
            if (toBottom > 0) backend.moveCursorDown(toBottom);
            out.print(AnsiStringBuilder.RESET);
            backend.showCursor();
            out.print("\u001b[0 q");
            out.println();
            out.flush();
        } catch (IOException ignored) {
        }
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

    private void ensureInitialized() {
        if (initialized) return;
        try {
            backend.hideCursor();
            out.flush();
        } catch (IOException ignored) {
        }
        initialized = true;
    }

    private void syncWidth() {
        if (!autoWidth) return;
        try {
            int newWidth = backend.size().width();
            if (newWidth <= 0 || newWidth == width) return;
            int h = buffer.area().height();
            Buffer resized = Buffer.empty(Rect.of(newWidth, h));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < Math.min(width, newWidth); x++) resized.set(x, y, buffer.get(x, y));
            }
            width = newWidth;
            buffer = resized;
        } catch (IOException ignored) {
        }
    }

    private void redrawDisplayArea(int cursorX, int cursorY) {
        if (currentHeight == 0) return;
        try {
            backend.carriageReturn();
            if (lastCursorY > 0) backend.moveCursorUp(lastCursorY);
            try (AnsiCellWriter cells = new AnsiCellWriter(out::print)) {
                for (int y = 0; y < currentHeight; y++) {
                    if (y > 0) out.print("\n");
                    backend.carriageReturn();
                    backend.eraseToEndOfLine();
                    int end = findLastContentPosition(y);
                    for (int x = 0; x < end; x++) cells.writeCell(buffer.get(x, y));
                }
            }
            backend.carriageReturn();
            if (currentHeight > 1) backend.moveCursorUp(currentHeight - 1);
            if (cursorX >= 0 && cursorY >= 0) {
                if (cursorY > 0) backend.moveCursorDown(cursorY);
                if (cursorX > 0) backend.moveCursorRight(cursorX);
                lastCursorY = cursorY;
            } else {
                int end = findLastContentPosition(0);
                if (end > 0) backend.moveCursorRight(end);
                lastCursorY = 0;
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private int findLastContentPosition(int line) {
        for (int x = width - 1; x >= 0; x--) {
            Cell cell = buffer.get(x, line);
            if (cell.isContinuation()) continue;
            String symbol = cell.symbol();
            if (!symbol.isEmpty() && !symbol.equals(" ")) return x + CharWidth.of(symbol);
        }
        return 0;
    }

    private void clearDisplayArea() {
        try {
            backend.carriageReturn();
            for (int y = 0; y < currentHeight; y++) {
                if (y > 0) {
                    out.print("\n");
                    backend.carriageReturn();
                }
                backend.eraseToEndOfLine();
            }
            if (currentHeight > 1) backend.moveCursorUp(currentHeight - 1);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void resizeDisplay(int newHeight) {
        newHeight = Math.max(0, newHeight);
        if (newHeight == currentHeight) return;
        try {
            int delta = newHeight - currentHeight;
            if (delta > 0) {
                if (currentHeight > 0) {
                    int toBottom = currentHeight - 1 - lastCursorY;
                    if (toBottom > 0) backend.moveCursorDown(toBottom);
                    backend.carriageReturn();
                }
                for (int i = 0; i < delta; i++) out.print("\n");
                int cursorLineFromTop = currentHeight > 0 ? newHeight - 1 : delta;
                if (cursorLineFromTop > 0) backend.moveCursorUp(cursorLineFromTop);
                backend.carriageReturn();
            } else {
                int toTarget = newHeight - lastCursorY;
                if (toTarget > 0) backend.moveCursorDown(toTarget);
                else if (toTarget < 0) backend.moveCursorUp(-toTarget);
                backend.carriageReturn();
                backend.deleteLines(-delta);
                if (newHeight > 0) backend.moveCursorUp(newHeight);
                backend.carriageReturn();
            }
            lastCursorY = 0;
            out.flush();
        } catch (IOException ignored) {
        }
        currentHeight = newHeight;
        buffer = Buffer.empty(Rect.of(width, newHeight));
    }
}
