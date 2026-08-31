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
    /**
     * 编辑活动后连续重申光标带的帧数，覆盖 IME 预编辑异步、可能滞后的清理。
     *
     * <p>换算成时长要乘调用方的 tickRate，<b>不是固定毫秒数</b>：code-tui 现在跑 100ms/帧
     * （见其 {@code configure} 的降频注释），窗口即 ~800ms。任何按写死毫秒等窗口耗尽的验证
     * （如 pty 冒烟的 pump 时长）都必须按「帧数 × 当前帧长」重算，否则会随帧率调整静默错位。
     */
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
        if (desiredHeight <= 0) {
            resizeDisplay(0, batch, null);
            currentBuffer = Buffer.empty(Rect.of(width, 0));
            submit(batch);
            return;
        }

        // ⚠ 顺序：先把新帧渲染进内存，再动终端。高度变化时 resizeDisplay 要拿新帧和上一帧比对，
        // 才知道增减的行在 live 区<b>顶部</b>还是底部——选错方向会逼出一次整区逐行重画，
        // 而那正是「输入框上下闪出两根线」的来源（见 resizeDisplay 注释）。
        if (currentBuffer.width() != width || currentBuffer.height() != desiredHeight) {
            currentBuffer = Buffer.empty(Rect.of(width, desiredHeight));
        }
        currentBuffer.clear();
        renderer.accept(currentBuffer.area(), currentBuffer);
        resizeDisplay(desiredHeight, batch, currentBuffer);

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
                appendLiveRestore(printBatch, previousBuffer, -1, -1);
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

    /**
     * 本帧之后是否还需要再画一帧（Task 8 按需 follow-up 帧的唯一信号源）。
     *
     * <p>返回 {@code cursorBandRepairFramesLeft > 0}：与 IME 光标带修复窗口<b>同源同寿命</b>。
     * 事件驱动 UI 关闭了全局 tick，光标带重申窗口（见 {@link #cursorBandRepairFramesLeft}
     * 的 8 帧计数）内的帧<b>只能</b>由调用方按需补排——本方法就是「补排判断」。
     *
     * <p>语义约束（有 {@code InlineDisplayDiffTest.needsFollowUpFrameTracksCursorBandRepairWindow} 钉）：
     * <ul>
     *   <li>触及光标带的变更武装窗口 → true；</li>
     *   <li>窗口逐帧消耗（每帧 render 自减一次）→ 剩余 &gt; 0 期间持续 true；</li>
     *   <li>计数耗尽且无新触发 → false（静止零输出，调用方停止补排）。</li>
     * </ul>
     *
     * <p>线程纪律：与 {@link #render} 同线程调用（渲染线程）；只读一个 int 字段。
     */
    public boolean needsFollowUpFrame() {
        return cursorBandRepairFramesLeft > 0;
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

    /**
     * 调整 live 区高度。
     *
     * <p><b>行加在顶部还是底部，决定了下一帧要不要整区重画</b>。本 TUI 的 live 区贴屏幕底部，
     * 内容也贴底（输入框与状态行永远是最后几行），高度变化几乎总是「顶部多/少一行」——流式预览行、
     * 计划/排队等面板都长在输入框上方。若照旧在<b>底部</b>加减行，输入框相对终端就整体挪了一行，
     * 逐行差分于是把每一行都判成变化；重画进行到一半时屏上同时存在新旧两条边框，用户看到的就是
     * 「输入框上下各多一根线，一闪又没，反复出现」（模型输出期间预览行不停增删，故一直在闪）。
     *
     * <p>改法：拿新帧与上一帧比一次行对齐方式；若「整体平移」比「顶部对齐」对上更多行，就用终端的
     * IL/DL 在 live 区<b>顶部</b>插/删行——终端一次原子上/下移，输入框与状态行相对屏幕纹丝不动，
     * 帧差随之缩到真正变化的那几行（常见情形是零行，一个字节都不必发）。平移没有更优时（例如新行
     * 确实加在底部）保持原有的底部增减，故不会比修复前更差。
     *
     * @param nextFrame 已渲染好的新帧；{@code null}（高度归零）时按底部增减处理
     */
    private void resizeDisplay(int newHeight, StringBuilder batch, Buffer nextFrame) {
        if (newHeight == currentHeight) return;
        int oldHeight = currentHeight;
        int delta = newHeight - oldHeight;
        boolean shiftRows = oldHeight > 0 && newHeight > 0 && previousFrameValid && nextFrame != null
                && shiftAlignsBetter(previousBuffer, nextFrame, delta);
        appendHome(batch);
        if (delta > 0) {
            if (oldHeight > 0) down(batch, oldHeight - 1);
            for (int i = 0; i < delta; i++) batch.append("\r\n");
            // 从 0 高度生长时已向下走了 newHeight 行，必须退回 newHeight 行（而不是 newHeight-1），
            // 否则整个 live 区比预期低 1 行——「启动画面整体下移一行」的根源。
            up(batch, newHeight - (oldHeight > 0 ? 1 : 0));
            batch.append('\r');
            // 上面的换行只把 live 区向下撑开（贴底时即滚屏腾行）；这一步再把空行挪到顶部。
            if (shiftRows) batch.append("\u001b[").append(delta).append('L');
        } else if (shiftRows) {
            batch.append("\u001b[").append(-delta).append('M');   // 光标已在 live 区顶部，直接删顶部若干行
        } else {
            down(batch, newHeight);
            batch.append('\r').append("\u001b[").append(-delta).append('M');
            up(batch, newHeight);
            batch.append('\r');
        }
        previousBuffer = previousFrameValid
                ? InlinePatch.realign(previousBuffer, width, newHeight, shiftRows ? delta : 0)
                : Buffer.empty(Rect.of(width, newHeight));
        currentHeight = newHeight;
        lastCursorX = 0;
        lastCursorY = 0;
    }

    /**
     * 「内容整体平移 {@code delta} 行」是否比「顶部对齐」对上更多行。相等时返回 {@code false}：
     * 平移要多发一条 IL/DL，没有收益就不发。
     */
    private boolean shiftAlignsBetter(Buffer previous, Buffer next, int delta) {
        int shifted = 0;
        int aligned = 0;
        for (int row = 0; row < next.height(); row++) {
            if (rowsEqual(previous, row - delta, next, row)) shifted++;
            if (rowsEqual(previous, row, next, row)) aligned++;
        }
        return shifted > aligned;
    }

    private boolean rowsEqual(Buffer previous, int previousRow, Buffer next, int nextRow) {
        if (previousRow < 0 || previousRow >= previous.height()) return false;
        int cols = Math.min(previous.width(), next.width());
        for (int col = 0; col < cols; col++) {
            if (!previous.get(col, previousRow).equals(next.get(col, nextRow))) return false;
        }
        return true;
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
     * then park the cursor. Used only for the first frame and after snapshot invalidation, when
     * stale terminal content must be removed.
     */
    private void appendFullRedraw(StringBuilder batch, Buffer source, int cursorX, int cursorY) {
        appendLiveArea(batch, source, cursorX, cursorY, true);
    }

    /**
     * 批末把 live 区恢复到屏幕底部，<b>不逐行 EL</b>。
     *
     * <p>可以不擦是因为每一行落点上的内容本来就等于要写的内容：println 的 ESC[1L 只是把 live 区
     * 整体下推（内容不变），被挤出屏幕的行则由本方法行间的 LF 滚屏重新腾出——那是<b>全新的空行</b>。
     * 先擦后写反而会让不支持同步输出的终端（如 Apple Terminal）看到「边框先没后有」的中间帧，
     * 模型输出期间每批一次，看起来就是输入框在闪。
     */
    private void appendLiveRestore(StringBuilder batch, Buffer source, int cursorX, int cursorY) {
        appendLiveArea(batch, source, cursorX, cursorY, false);
    }

    private void appendLiveArea(StringBuilder batch, Buffer source, int cursorX, int cursorY, boolean eraseRowTails) {
        if (currentHeight <= 0) return;
        appendHome(batch);
        try (AnsiCellWriter cells = new AnsiCellWriter(batch::append)) {
            for (int row = 0; row < currentHeight; row++) {
                // ⚠ 行间必须走 LF，不能换成 CUD（ESC[1B）：<b>底行的那次滚屏是刚需</b>。
                // println 用 ESC[1L 在 live 区顶部插行，把 live 区整体下推、底行被挤出屏幕；本次恢复
                // 写到最后一行时正落在屏幕底行，LF 触发滚屏才腾回那一行。改成 CUD 后光标在底行原地
                // 不动，状态行直接盖在输入框底边框上（用户实报「输入框和状态栏重叠」「输入框下面多
                // 一条边框线」），且此后 live 区顶部记账整体偏移一行，后续帧会把输入框画到 scrollback
                // 上、边打边吃掉已经输出的正文。
                if (row > 0) batch.append("\r\n");
                batch.append('\r');
                if (eraseRowTails) batch.append("\u001b[K");
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
