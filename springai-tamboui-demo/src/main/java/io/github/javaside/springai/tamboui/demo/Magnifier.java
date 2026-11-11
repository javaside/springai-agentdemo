package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;

/**
 * 第 2 课的教具：把当前帧左上 cols×rows 格「每格用 [ ] 框出」投到屏幕中部——
 * 让「一格一字符、中文一字占两格（主格+续格[·]）」肉眼可数。
 *
 * <p><b>这是教学道具，不是第 2 课要学的用法</b>：它内部只是循环 {@code grid.get}
 * 拼字符串再 {@code setString}（本身就是一次 Buffer 读写的练习）。读不读随缘，
 * 课程正文只有一行调用。</p>
 */
public final class Magnifier {

    /** 教具固定取景（第 2 课示例引用）。 */
    public static final int COLS = 10, ROWS = 5;

    private Magnifier() {
    }

    /**
     * 投影：读当前帧左上 cols 列 × rows 行，逐格 [字符] 写到 targetY 起的屏幕区域。
     * 读写分区：读第 0..rows-1 行，写第 targetY-1（标题）与 targetY.. 行——互不覆盖。
     */
    public static void project(Frame frame, int cols, int rows, int targetY) {
        for (int y = 0; y < rows; y++) {
            StringBuilder sb = new StringBuilder("第" + y + "行 ");
            for (int x = 0; x < cols; x++) {
                Cell c = frame.buffer().get(x, y);
                String ch = c.isContinuation() ? "·"
                        : (c.symbol() == null || c.symbol().isEmpty() ? " " : c.symbol());
                sb.append("[").append(ch).append("]");
            }
            frame.buffer().setString(1, targetY + y, sb.toString(), Style.create());
        }
        frame.buffer().setString(1, targetY - 1,
                "放大镜：Buffer 的前 " + cols + " 格 × " + rows + " 行（· 是宽字符的第二格）",
                Style.create());
    }
}
