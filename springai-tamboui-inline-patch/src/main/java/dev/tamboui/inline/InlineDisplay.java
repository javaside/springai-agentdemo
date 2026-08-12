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
    private final SynchronizedOutput synchronizedOutput;
    private final boolean hardwareCursorVisible;
    private boolean initialized;
    private boolean released;
    private boolean shouldClearOnClose;
    private boolean previousFrameValid;
    private int lastCursorX;
    private int lastCursorY;
    private int currentHeight;
    private int printBatchDepth;
    private StringBuilder printBatch;
    /**
     * 光标带修复的剩余帧数。macOS 终端把 IME 预编辑串画在硬件光标处，其清理是<b>异步</b>的，
     * 且实测会越界擦坏<b>相邻行的右缘</b>（右竖线、顶边框尾段与圆角）；应用对预编辑全程收不到
     * 任何事件（取消时更是全无痕迹），差分渲染又认为这些格子未变化不会重画，损坏就此永驻。
     * 对策：任何触及光标行 ±1 的变更（编辑、删除、上屏、光标块移动）都武装本窗口，
     * 窗口内每帧把光标行 ±1 整行无擦除重写——覆写相同字形不可见，带外行仍严格差分。
     */
    private int cursorBandRepairFramesLeft;
    /** 编辑活动后连续重申光标带的帧数（约 8 × 40ms），覆盖 IME 预编辑异步、可能滞后的清理。 */
    private static final int CURSOR_BAND_REPAIR_FRAMES = 8;
    /** 光标带半径：光标行上下各一行（顶边框/底边框正好落在此带内）。 */
    private static final int CURSOR_BAND_RADIUS = 1;

    InlineDisplay(int height, int width, Backend backend, PrintWriter out) {
        this(height, width, false, backend, out, SynchronizedOutput.systemDefault());
    }

    InlineDisplay(int height, int width, Backend backend, PrintWriter out, SynchronizedOutput synchronizedOutput) {
        this(height, width, false, backend, out, synchronizedOutput);
    }

    InlineDisplay(int height, int width, boolean autoWidth, Backend backend, PrintWriter out) {
        this(height, width, autoWidth, backend, out, SynchronizedOutput.systemDefault());
    }

    private InlineDisplay(int height, int width, boolean autoWidth, Backend backend, PrintWriter out,
                          SynchronizedOutput synchronizedOutput) {
        this.height = height;
        this.width = width;
        this.autoWidth = autoWidth;
        this.backend = backend;
        this.out = out;
        this.synchronizedOutput = synchronizedOutput;
        this.hardwareCursorVisible = hardwareCursorVisible();
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

        if (previousFrameValid) {
            List<InlinePatch.PatchRun> runs = InlinePatch.runs(previousBuffer, currentBuffer);
            boolean bandTail = cursorBandRepairFramesLeft > 0;
            if (bandTail) cursorBandRepairFramesLeft--;
            if (!runs.isEmpty() || bandTail || lastCursorX != targetX || lastCursorY != targetY) {
                appendFramePatch(batch, runs, bandTail, targetX, targetY);
            }
        } else {
            // 首帧、宽度变化或快照失效后无法可靠差分：完整重画 live 区一次（每行先 EL）。
            appendFullRedraw(batch, currentBuffer, targetX, targetY);
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

    /** Starts a nestable scrollback print batch. */
    public void beginPrintBatch() {
        ensureInitialized();
        if (printBatchDepth++ == 0) printBatch = new StringBuilder();
    }

    /** Ends a print batch, submits all accumulated lines once, then redraws the live area once. */
    public void endPrintBatch() {
        if (printBatchDepth <= 0) return;
        if (--printBatchDepth == 0) {
            // 批处理只插行、不逐行重画 live 区；当 live 区已被推到屏幕底部时，插行会把它的
            // 底行挤出屏幕。此处必须完整重画一次把 live 区拉回屏幕底部——官方版是每行 println
            // 后立即重画，这里合并为每批一次（仍满足「一批至多一次 live 提交」）。
            // 注意：render() 结束时会交换 current/previous，已显示内容此刻在 previousBuffer。
            if (printBatch != null && !printBatch.isEmpty() && currentHeight > 0 && previousFrameValid) {
                appendFullRedraw(printBatch, previousBuffer, -1, -1);
            }
            submit(printBatch);
            printBatch = null;
        }
    }

    public void println(String message) {
        ensureInitialized();
        syncWidth();
        boolean ownBatch = printBatchDepth == 0;
        if (ownBatch) beginPrintBatch();
        try {
            if (currentHeight == 0) {
                printBatch.append(message).append("\r\n");
                return;
            }
            appendHome(printBatch);
            printBatch.append("\u001b[1L").append(message).append("\u001b[K\n\r");
            lastCursorX = 0;
            lastCursorY = 0;
        } finally {
            if (ownBatch) endPrintBatch();
        }
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
            if (hardwareCursorVisible) backend.showCursor();
            else backend.hideCursor();
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
            // 从 0 高度生长时已向下走了 newHeight 行，必须退回 newHeight 行（而不是 newHeight-1），
            // 否则整个 live 区比预期低 1 行——「启动画面整体下移一行」的根源。
            up(batch, newHeight - (oldHeight > 0 ? 1 : 0));
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

    private void appendFramePatch(StringBuilder batch, List<InlinePatch.PatchRun> runs, boolean bandTail,
                                  int targetX, int targetY) {
        appendHome(batch);
        int row = 0;
        boolean bandTouched = false;
        try (AnsiCellWriter cells = new AnsiCellWriter(batch::append)) {
            for (InlinePatch.PatchRun run : runs) {
                if (run.row() > row) down(batch, run.row() - row);
                else if (run.row() < row) up(batch, row - run.row());
                batch.append('\r');
                right(batch, run.startCol());
                // 显式追踪视觉列：AnsiCellWriter 对 continuation 直接跳过且游标不推进，
                // 若 run 内 CJK 后跟 continuation 链，后续字符（光标反显、右竖线）会被写到
                // 错误列。每写一个非 continuation 字符前，若其列大于当前游标视觉列，先 right()。
                int cursor = run.startCol();
                for (int col = run.startCol(); col < run.endColExclusive(); col++) {
                    Cell cell = currentBuffer.get(col, run.row());
                    if (cell.isContinuation()) continue;
                    if (col > cursor) {
                        right(batch, col - cursor);
                        cursor = col;
                    }
                    cells.writeCell(cell);
                    cursor += dev.tamboui.text.CharWidth.of(cell.symbol());
                }
                bandTouched |= Math.abs(run.row() - targetY) <= CURSOR_BAND_RADIUS;
                row = run.row();
            }
            // 光标带修复（见 cursorBandRepairFramesLeft 注释）：本帧有触及光标带的变更 → 立即重申
            // 并武装后续窗口；处于已武装窗口内 → 继续重申。整行覆写相同字形在终端上不可见，
            // 不用 EL（先擦后写才会闪）；窗口耗尽且无新触发时恢复静止零输出。
            if (bandTouched) cursorBandRepairFramesLeft = CURSOR_BAND_REPAIR_FRAMES;
            if (bandTouched || bandTail) {
                int from = Math.max(0, targetY - CURSOR_BAND_RADIUS);
                int to = Math.min(currentHeight - 1, targetY + CURSOR_BAND_RADIUS);
                for (int bandRow = from; bandRow <= to; bandRow++) {
                    if (bandRow > row) down(batch, bandRow - row);
                    else if (bandRow < row) up(batch, row - bandRow);
                    batch.append('\r');
                    appendRowOverwrite(batch, cells, currentBuffer, bandRow);
                    row = bandRow;
                }
            }
        }
        batch.append('\r');
        up(batch, row);
        down(batch, targetY);
        right(batch, targetX);
    }

    /**
     * 把 {@code source} 的一整行（0..最后内容格）覆写到终端当前行，不带 EL。
     * 调用前游标须已在该行行首；与 {@link #appendFramePatch} 相同地跳过 continuation 并显式定位。
     */
    private void appendRowOverwrite(StringBuilder batch, AnsiCellWriter cells, Buffer source, int row) {
        int lineEnd = findLastContentPosition(source, row);
        int cursor = 0;
        for (int col = 0; col < lineEnd; col++) {
            Cell cell = source.get(col, row);
            if (cell.isContinuation()) continue;
            if (col > cursor) {
                right(batch, col - cursor);
                cursor = col;
            }
            cells.writeCell(cell);
            cursor += dev.tamboui.text.CharWidth.of(cell.symbol());
        }
    }

    /**
     * Full rewrite of the live area: move to its top, erase each row tail, write the row content,
     * then park the cursor. Used for the first frame, after snapshot invalidation, and at the end
     * of a scrollback print batch (where inserted lines may have pushed the bottom of the live
     * area off the screen).
     *
     * @param source the buffer whose content should be drawn (current frame inside render(), the
     *               already-displayed frame at the end of a print batch)
     */
    private void appendFullRedraw(StringBuilder batch, Buffer source, int cursorX, int cursorY) {
        if (currentHeight <= 0) return;
        appendHome(batch);
        try (AnsiCellWriter cells = new AnsiCellWriter(batch::append)) {
            for (int row = 0; row < currentHeight; row++) {
                if (row > 0) batch.append("\r\n");
                batch.append('\r').append("\u001b[K");
                appendRowOverwrite(batch, cells, source, row);
            }
        }
        batch.append('\r');
        up(batch, currentHeight - 1);
        int tx = cursorX >= 0 ? cursorX : findLastContentPosition(source, 0);
        int ty = cursorY >= 0 ? cursorY : 0;
        down(batch, ty);
        right(batch, tx);
        lastCursorX = tx;
        lastCursorY = ty;
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
            backend.writeRaw(synchronizedOutput.wrap(batch.toString()));
            backend.flush();
        } catch (IOException ignored) {
            previousFrameValid = false;
        }
    }

    private int findLastContentPosition(Buffer buffer, int line) {
        int column = findLastContentCellColumn(buffer, line);
        if (column < 0) return 0;
        return Math.min(width, column + dev.tamboui.text.CharWidth.of(buffer.get(column, line).symbol()));
    }

    private int findLastContentCellColumn(Buffer buffer, int line) {
        for (int x = width - 1; x >= 0; x--) {
            Cell cell = buffer.get(x, line);
            if (!cell.isContinuation() && !cell.isEmpty()) return x;
        }
        return -1;
    }

    private static boolean hardwareCursorVisible() {
        String mode = System.getProperty("codetui.hardwareCursor", "auto").strip();
        if (mode.equalsIgnoreCase("always")) return true;
        if (mode.equalsIgnoreCase("never")) return false;
        return "Apple_Terminal".equalsIgnoreCase(System.getenv("TERM_PROGRAM"));
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
