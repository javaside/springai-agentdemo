/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui;

import java.io.IOException;
import java.util.function.Consumer;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.inline.InlineDisplay;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;

/**
 * Frame-compatible wrapper around {@link InlineDisplay}.
 *
 * <p>本类是 {@code dev.tamboui:tamboui-tui:0.4.0} 的<b>同包同名 shadow（覆盖）类</b>，与
 * {@link InlineTuiRunner} 同一机制（classpath 顺序优先加载）。与上游版本逐字一致，唯一差异：
 * 暴露 {@link #needsFollowUpFrame()}——{@link InlineTuiRunner} 在一次 draw 后据此按需补排
 * IME 光标带修复的后续帧（Task 8）。不暴露任何 renderer 所有权给上层应用（display 的
 * {@code beginPrintBatch}/{@code endPrintBatch} 等仍由应用经反射接缝使用，见 code-tui 的
 * {@code InlineRenderBatch}）。
 *
 * <p>为什么必须 shadow 而不是反射：上游 {@code InlineViewport} 是包私有 final 类，
 * {@code InlineTuiRunner}（同为 shadow）持有其字段并直接调用 {@code draw}。补排 follow-up
 * 帧需要 draw 之后立刻读到 display 的 {@code needsFollowUpFrame()}——经反射每帧一次
 * 既慢又脆（字段名漂移静默失效，IME 修复窗口被无声砍短）。同包 shadow 与既有
 * {@code InlineTuiRunner} 的治理方式一致。
 */
final class InlineViewport {

    private final InlineDisplay display;
    private Buffer buffer;
    private Rect area;
    private Frame frame;
    private int contentHeight;  // Current content height to allocate

    /**
     * Creates a new viewport wrapping the given display.
     *
     * @param display the inline display to wrap
     */
    InlineViewport(InlineDisplay display) {
        this.display = display;
        this.area = Rect.of(display.width(), display.height());
        this.buffer = Buffer.empty(area);
        this.frame = Frame.forTesting(buffer);
        this.contentHeight = display.height();  // Default to initial height
    }

    /**
     * Returns the width of the viewport.
     *
     * @return the width in characters
     */
    int width() {
        syncArea(contentHeight);
        return area.width();
    }

    /**
     * Returns the height of the viewport.
     *
     * @return the height in lines
     */
    int height() {
        return area.height();
    }

    /**
     * Returns the viewport area.
     *
     * @return the area rectangle
     */
    Rect area() {
        syncArea(contentHeight);
        return area;
    }

    /**
     * Sets the content height for the next draw.
     * <p>
     * This determines how many terminal lines will be allocated
     * for the inline display. The display will grow or shrink
     * accordingly on the next draw() call. If the requested height
     * exceeds the current buffer size, the buffer is resized.
     *
     * @param height the desired content height in lines
     */
    void setContentHeight(int height) {
        height = Math.max(0, height);
        this.contentHeight = height;
        syncArea(height);
    }

    /**
     * Draws the UI using the given renderer.
     * <p>
     * The buffer is cleared, the renderer is called, and then
     * the buffer content is pushed to the inline display.
     *
     * @param renderer the render function that populates the frame
     */
    void draw(Consumer<Frame> renderer) {
        syncArea(contentHeight);
        buffer.clear();
        frame.clearCursor();  // Reset cursor before render
        renderer.accept(frame);

        // Get cursor position from frame (if set by a text input)
        int cursorX = frame.cursorPosition().map(p -> p.x()).orElse(-1);
        int cursorY = frame.cursorPosition().map(p -> p.y()).orElse(-1);

        display.render((a, b) -> {
            // Copy our buffer to the display's buffer
            // Note: Only copy up to currentHeight, but the display handles resizing
            for (int y = 0; y < Math.min(contentHeight, area.height()); y++) {
                for (int x = 0; x < area.width(); x++) {
                    b.set(x, y, buffer.get(x, y));
                }
            }
        }, contentHeight, cursorX, cursorY);
    }

    /**
     * 刚完成的这次 draw 之后是否还需要再画一帧（Task 8 按需 follow-up 帧信号）。
     *
     * <p>直通 {@link InlineDisplay#needsFollowUpFrame()}——IME 光标带修复窗口仍武装时 true。
     * <b>只在 draw 之后（渲染线程）调用有意义</b>：窗口计数在 display.render 内逐帧消耗，
     * 读早了是上一帧的值。{@link InlineTuiRunner} 据此决定是否补排一次性 render。
     */
    boolean needsFollowUpFrame() {
        return display.needsFollowUpFrame();
    }

    private void syncArea(int minHeight) {
        int targetWidth = display.width();
        int targetHeight = Math.max(area.height(), minHeight);
        if (targetWidth == area.width() && targetHeight == area.height()) {
            return;
        }

        this.area = Rect.of(targetWidth, targetHeight);
        this.buffer = Buffer.empty(area);
        this.frame = Frame.forTesting(buffer);
    }

    /**
     * Prints a plain text message above the viewport.
     *
     * @param message the message to print
     */
    void println(String message) {
        display.println(message);
    }

    /**
     * Prints styled text above the viewport.
     *
     * @param text the styled text to print
     */
    void println(Text text) {
        display.println(text);
    }

    /**
     * Releases the display and moves the cursor below the viewport.
     */
    void release() {
        display.release();
    }

    /**
     * Closes the underlying display.
     *
     * @throws IOException if an I/O error occurs
     */
    void close() throws IOException {
        display.close();
    }
}
