package io.github.javaside.springai.codetui.ui;

import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.buffer.DiffResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResizeSweeper} 的两层保护：
 * <ol>
 *   <li><b>结构断言</b>——生产入口反射的私有字段还在库里。库升级改名时这里先红灯，
 *       线上则安全降级为「维持今天的行为」（resize 残影回来，但不会引入新的坏状态）；</li>
 *   <li><b>核心序列</b>——清扫必须是「CR、上移到显示区顶行、ESC[J、下移还原」一次原子写出，
 *       且守卫失败时<b>一个字节都不写</b>。上移多一行就是擦掉真实历史，下移漏一行就是
 *       记账与光标脱节（下一帧画进历史区），故序列逐字节钉死。</li>
 * </ol>
 *
 * <p>清扫的<b>真实屏幕效果</b>（旧帧残迹消失、新帧原地盖回）依赖终端重排，pyte 不重排、
 * 单测更到不了——{@code resize_smoke.py} 能抓「清扫越界吃掉历史」这半边（擦除不需要重排），
 * 「残迹清干净」那半边只能在会重排的终端（tmux/iTerm2）实机确认。
 */
class ResizeSweeperTest {

    // ── 结构断言：反射依赖的库内部还长这样 ─────────────────────────────

    @Test
    void inlineTuiRunner_hasBackendAndViewportFields() throws Exception {
        Class<?> c = Class.forName("dev.tamboui.tui.InlineTuiRunner");
        assertNotNull(c.getDeclaredField("backend"));
        assertNotNull(c.getDeclaredField("viewport"));
    }

    @Test
    void inlineViewport_hasDisplayField() throws Exception {
        Class<?> c = Class.forName("dev.tamboui.tui.InlineViewport");
        Field display = c.getDeclaredField("display");
        assertEquals("dev.tamboui.inline.InlineDisplay", display.getType().getName());
    }

    @Test
    void inlineDisplay_hasCursorAccountingFields() throws Exception {
        Class<?> c = Class.forName("dev.tamboui.inline.InlineDisplay");
        assertEquals(int.class, c.getDeclaredField("lastCursorY").getType());
        assertEquals(int.class, c.getDeclaredField("currentHeight").getType());
    }

    // ── 核心序列 ─────────────────────────────────────────────────────

    @Test
    void sweep_cursorMidDisplay_emitsUpEraseDownAsOneWrite() {
        RecordingBackend backend = new RecordingBackend();
        assertTrue(ResizeSweeper.sweep(backend, accounting(4, 1)));
        assertEquals(List.of("\r\u001b[1A\u001b[J\u001b[1B"), backend.writes);
        assertTrue(backend.flushed);
    }

    @Test
    void sweep_cursorOnTopRow_skipsCursorMoves() {
        RecordingBackend backend = new RecordingBackend();
        assertTrue(ResizeSweeper.sweep(backend, accounting(4, 0)));
        assertEquals(List.of("\r\u001b[J"), backend.writes);
    }

    @Test
    void sweep_deepCursor_movesExactRowCount() {
        RecordingBackend backend = new RecordingBackend();
        assertTrue(ResizeSweeper.sweep(backend, accounting(10, 9)));
        assertEquals(List.of("\r\u001b[9A\u001b[J\u001b[9B"), backend.writes);
    }

    // ── 守卫：失败时一个字节都不写 ───────────────────────────────────

    @Test
    void sweep_noDisplayAllocatedYet_writesNothing() {
        RecordingBackend backend = new RecordingBackend();
        assertFalse(ResizeSweeper.sweep(backend, accounting(0, 0)));
        assertTrue(backend.writes.isEmpty());
    }

    @Test
    void sweep_inconsistentAccounting_writesNothing() {
        RecordingBackend backend = new RecordingBackend();
        assertFalse(ResizeSweeper.sweep(backend, accounting(4, -1)));   // 读 lastCursorY 失败的哨兵值
        assertFalse(ResizeSweeper.sweep(backend, accounting(4, 4)));    // 光标行越过显示区高度
        assertTrue(backend.writes.isEmpty());
    }

    @Test
    void sweep_nullArguments_returnFalse() {
        assertFalse(ResizeSweeper.sweep((Backend) null, accounting(4, 1)));
        assertFalse(ResizeSweeper.sweep(new RecordingBackend(), null));
    }

    @Test
    void sweep_writeFails_returnsFalseAndNeverThrows() {
        RecordingBackend backend = new RecordingBackend() {
            @Override public void writeRaw(String data) throws IOException {
                throw new IOException("terminal gone");
            }
        };
        assertFalse(ResizeSweeper.sweep(backend, accounting(4, 1)));
    }

    @Test
    void sweep_nullRunner_returnsFalse() {
        assertFalse(ResizeSweeper.sweep((dev.tamboui.toolkit.app.InlineToolkitRunner) null));
    }

    // ── 工装 ─────────────────────────────────────────────────────────

    private static ResizeSweeper.Accounting accounting(int height, int cursorRow) {
        return new ResizeSweeper.Accounting() {
            @Override public int contentHeight() { return height; }
            @Override public int cursorRow() { return cursorRow; }
        };
    }

    /** 只记录 writeRaw/flush 的假终端，其余操作一律拒绝——sweep 若碰了别的方法，测试立刻炸。 */
    private static class RecordingBackend implements Backend {
        final List<String> writes = new ArrayList<>();
        boolean flushed;

        @Override public void writeRaw(String data) throws IOException { writes.add(data); }
        @Override public void flush() { flushed = true; }

        @Override public void draw(DiffResult diff) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public Size size() { throw new UnsupportedOperationException(); }
        @Override public void showCursor() { throw new UnsupportedOperationException(); }
        @Override public void hideCursor() { throw new UnsupportedOperationException(); }
        @Override public Position getCursorPosition() { throw new UnsupportedOperationException(); }
        @Override public void setCursorPosition(Position position) { throw new UnsupportedOperationException(); }
        @Override public void enterAlternateScreen() { throw new UnsupportedOperationException(); }
        @Override public void leaveAlternateScreen() { throw new UnsupportedOperationException(); }
        @Override public void enableRawMode() { throw new UnsupportedOperationException(); }
        @Override public void disableRawMode() { throw new UnsupportedOperationException(); }
        @Override public void onResize(Runnable handler) { throw new UnsupportedOperationException(); }
        @Override public int read(int timeoutMs) { throw new UnsupportedOperationException(); }
        @Override public int peek(int timeoutMs) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
