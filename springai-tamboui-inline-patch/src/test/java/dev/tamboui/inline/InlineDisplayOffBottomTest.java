package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「live 区不贴屏幕底」时 println 与 live 增长交织的画面守卫（2026-11-01 用户实报）。
 *
 * <p><b>实报症状</b>：⏱ 后台任务面板出现后，每个任务短行行尾多一根横线（旧输入框边框 ─…╮ 的
 * 残影），任务行/正文还会被撕裂。实机复现见 background_smoke.py 场景 2（24 行小窗口，状态栏
 * 错位断言 FAIL）；pyte 逐步重放定位的根因链：
 *
 * <ol>
 *   <li>println（纯滚动模式）的序列假设「live 底 = 屏幕底行：CRLF 触发滚屏，up(ch) 落在新腾出的
 *       正文行」。live 不贴底时（首帧锚定离屏幕底还有空行，v1.26.4 起首帧不钉底即可能出现），
 *       CRLF 不滚屏，up(ch) 落回 live <b>顶</b>本身；</li>
 *   <li>随后的 EL 把 live 顶行擦掉、正文写在 live 顶上；appendLiveRestore 又从错位一格的位置
 *       整块重画 live，且 eraseRowTails=false 不擦行尾——短行（面板行）覆写满宽行（旧边框）
 *       就把 ─…╮ 残留在行尾，还会覆盖/撕裂刚写的正文。</li>
 * </ol>
 *
 * <p>纯字节断言测不出「最终画面」，这里用 {@link MiniVt} 把字节重放成屏幕再断言
 * （同族教训：InlineDisplayDiffTest 只能钉字节形状，画面级回归全靠 pyte 冒烟，本测试补上离屏缺口）。
 * 场景：live 从 4 行（输入框+状态）经 3 次「println + 顶部 +1 行」长到 8 行，期间任务行耗时在变
 * （同高 diff 帧），最后再 println 一行正文——正是后台任务面板出现时的实机时序。
 */
class InlineDisplayOffBottomTest {

    private CaptureBackend backend;
    private String previousHardwareCursorMode;

    @BeforeEach
    void setUp() {
        previousHardwareCursorMode = System.getProperty("codetui.hardwareCursor");
        System.setProperty("codetui.hardwareCursor", "never");
        backend = new CaptureBackend(40, 24);
    }

    @AfterEach
    void restoreTerminalModes() {
        if (previousHardwareCursorMode == null) System.clearProperty("codetui.hardwareCursor");
        else System.setProperty("codetui.hardwareCursor", previousHardwareCursorMode);
    }

    @Test
    void printlnThenGrowThenPrintlnKeepsPictureIntact() {
        InlineDisplay display = new InlineDisplay(8, 40, backend, backend.writer(),
                SynchronizedOutput.from(java.util.Map.of(), "never"));
        renderHeight(display, 0, "0m00s");                 // 帧 0：live 4 行（边框+状态），面板未出现

        for (int task = 1; task <= 3; task++) {            // 3 轮：println 通知 + 面板多一行 + 耗时跳一秒
            display.println("BG started task_" + task);
            renderHeight(display, task, "0m0" + task + "s");
            renderHeight(display, task, "0m0" + (task + 1) + "s");
        }
        display.println("plain reply");                    // 最后一行正文

        MiniVt vt = new MiniVt(40, 24);
        vt.feed(backend.outputUtf8());
        String[] rows = vt.display();

        // 1) 四条正文各自完整独占一行：撕裂（如 "…re job1 m 0m04s"）或被 live 覆盖都会让 equals 失败
        assertFullRow(rows, "BG started task_1");
        assertFullRow(rows, "BG started task_2");
        assertFullRow(rows, "BG started task_3");
        assertFullRow(rows, "plain reply");

        // 2) live 8 行连续完整：标题、3 条任务行、边框 3 行、状态行按顺序紧挨着，
        //    中间不得穿插正文/空白/残影
        int top = indexOfRowStarting(rows, "BG (3 run · 0 done)");
        assertTrue(top >= 0, "面板标题行不在屏幕上：" + joinRows(rows));
        assertEquals("task_11111112 explore job1 m 0m04s", rows[top + 1].stripTrailing(),
                "任务行 1 须紧跟标题行且完整：" + joinRows(rows));
        assertEquals("task_11111113 explore job2 m 0m04s", rows[top + 2].stripTrailing(),
                "任务行 2 须紧跟任务行 1 且完整：" + joinRows(rows));
        assertEquals("task_11111114 explore job3 m 0m04s", rows[top + 3].stripTrailing(),
                "任务行 3 须紧跟任务行 2 且完整：" + joinRows(rows));
        assertEquals("╭──────────────────────────────────────╮", rows[top + 4].stripTrailing(),
                "输入框上边框须紧跟任务行：" + joinRows(rows));
        assertEquals("│input                                 │", rows[top + 5].stripTrailing(),
                "输入行：" + joinRows(rows));
        assertEquals("╰──────────────────────────────────────╯", rows[top + 6].stripTrailing(),
                "输入框下边框：" + joinRows(rows));
        assertEquals("Enter send · model m1 · ctx 0% · BG 3", rows[top + 7].stripTrailing(),
                "状态行须是 live 区最后一行：" + joinRows(rows));

        // 3) 残影专项：面板短行（标题/任务行）行尾不得残留旧边框横线（用户实报的那根线）
        for (int r = top; r <= top + 3; r++) {
            assertNull(containsAny(rows[r], '─', '╮', '╯'),
                    "面板短行行尾残留边框残影：" + rows[r].stripTrailing());
        }
    }

    private static void assertFullRow(String[] rows, String expected) {
        String actual = null;
        for (String row : rows) {
            if (row.startsWith(expected) || row.contains(expected)) {
                actual = row.stripTrailing();
                break;
            }
        }
        assertNotNull(actual, "正文行不在屏幕上：" + expected);
        assertEquals(expected, actual, "正文行被撕裂或覆盖：");
    }

    private static int indexOfRowStarting(String[] rows, String prefix) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].startsWith(prefix)) return i;
        }
        return -1;
    }

    private static String containsAny(String row, char... chars) {
        for (char c : chars) {
            if (row.indexOf(c) >= 0) return String.valueOf(c);
        }
        return null;
    }

    private static String joinRows(String[] rows) {
        return String.join("\n", rows);
    }

    /** live 高度：tasks=0 → 4 行（边框 3 + 状态）；tasks&gt;0 → 标题 + tasks 行 + 边框 3 + 状态。 */
    private static void renderHeight(InlineDisplay display, int tasks, String elapsed) {
        int h = tasks == 0 ? 4 : 5 + tasks;
        display.render((area, buffer) -> {
            if (tasks > 0) buffer.setString(0, 0, "BG (" + tasks + " run · 0 done)", Style.EMPTY);
            for (int i = 1; i <= tasks; i++) {
                buffer.setString(0, i, "task_" + (11111111 + i) + " explore job" + i + " m " + elapsed, Style.EMPTY);
            }
            boxRowsAt(buffer, tasks == 0 ? 0 : tasks + 1);
            buffer.setString(0, h - 1, "Enter send · model m1 · ctx 0%" + (tasks > 0 ? " · BG " + tasks : ""), Style.EMPTY);
        }, h, 1, tasks == 0 ? 1 : 1);
    }

    private static void boxRowsAt(Buffer buffer, int top) {
        buffer.set(0, top, Cell.EMPTY.symbol("╭"));
        for (int x = 1; x < 39; x++) buffer.set(x, top, Cell.EMPTY.symbol("─"));
        buffer.set(39, top, Cell.EMPTY.symbol("╮"));
        buffer.set(0, top + 1, Cell.EMPTY.symbol("│"));
        buffer.setString(1, top + 1, "input", Style.EMPTY);
        buffer.set(39, top + 1, Cell.EMPTY.symbol("│"));
        buffer.set(0, top + 2, Cell.EMPTY.symbol("╰"));
        for (int x = 1; x < 39; x++) buffer.set(x, top + 2, Cell.EMPTY.symbol("─"));
        buffer.set(39, top + 2, Cell.EMPTY.symbol("╯"));
    }

    /** 纯捕获 Backend：记录全部写出字节（帧间不 reset，重放需要完整连续流）。 */
    static final class CaptureBackend implements Backend {
        private final int width;
        private final int height;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        CaptureBackend(int width, int height) {
            this.width = width;
            this.height = height;
        }

        PrintWriter writer() {
            return new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
        }

        String outputUtf8() {
            return output.toString(StandardCharsets.UTF_8);
        }

        @Override public void draw(dev.tamboui.buffer.DiffResult diff) { }
        @Override public void flush() { }
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
        @Override public void writeRaw(byte[] data) throws IOException { output.write(data); }
        @Override public void onResize(Runnable handler) { }
        @Override public int read(int timeoutMs) { return -2; }
        @Override public int peek(int timeoutMs) { return -2; }
        @Override public void close() { }
    }
}
