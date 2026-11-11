package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 放大镜下的表格 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>回答三个问题，全在画面上：</p>
 * <ul>
 *   <li><b>画面是 Frame 还是 Buffer？</b>——画面 = <b>Buffer</b> 这张表的内容照着显示；
 *       <b>Frame 不是画面</b>，是填表时手里的工具（底部结论行写明）。</li>
 *   <li><b>「每格一字符」什么意思？</b>——屏幕中央的「放大镜」把这张表的前 10 格 × 5 行
 *       <b>每格用 [ ] 框出</b>：一格一字符肉眼可数，中文一字占两格（[你][·]），
 *       空白也是格；★ 就在放大镜里移动（h/j/k/l）。</li>
 *   <li><b>想画出画面怎么操作？</b>——三招：{@code set} 写一格、{@code setString} 写一串、
 *       {@code renderWidget} 画组件（本类 paint 各用了一次，底部结论行同款提示）。</li>
 * </ul>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** 放大镜参数：显示表格前 10 格（列）× 5 行。 */
    static final int MAG_COLS = 10;
    static final int MAG_ROWS = 5;

    /** ★ 在表格中的位置（列 x、行 y）——被钳在放大镜范围内，始终可见。 */
    int starX = 3;
    int starY = 2;

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "放大镜下的表格——Buffer / Cell / Frame";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            Renderer renderer = this::paint;
            runner.run(this::onKey, renderer);
        }

        System.out.println("\n—— 刚才那屏，在 Buffer（字符表格）里的样子 ——");
        System.out.println("（第 y 行 = 屏幕第 y 行，一格 = 一个字符；· 是宽字符占的第二格）");
        if (lastFrame != null) {
            FrameDump.dump(lastFrame).forEach(l -> System.out.println("  " + l));
        }
        System.out.println("""

                一句话收束三概念：
                · 画面 = Buffer 的内容（屏幕显示的就是这张表照着画）
                · Frame 不是画面，是填表工具（area 问尺寸 / renderWidget 批量画 / buffer 把表递给你）
                · Cell 是一格的内容（一个字符 + 样式）
                操作三招：set 写一格 · setString 写一串 · renderWidget 画组件
                """);
    }

    /** 按键：h/j/k/l 移动 ★（钳在放大镜 10×5 范围内，始终看得见）；q 退出。 */
    boolean onKey(Event event, TuiRunner r) {
        if (event instanceof KeyEvent key && key.isQuit()) {
            r.quit();
            return false;
        }
        if (event instanceof KeyEvent key) {
            if (key.isChar('h')) { starX = Math.max(0, starX - 1); return true; }
            if (key.isChar('l')) { starX = Math.min(MAG_COLS - 1, starX + 1); return true; }
            if (key.isChar('k')) { starY = Math.max(0, starY - 1); return true; }
            if (key.isChar('j')) { starY = Math.min(MAG_ROWS - 1, starY + 1); return true; }
        }
        return false;
    }

    /** 退出打印用的最后一屏快照。 */
    private Buffer lastFrame;

    /** 画一帧。包内可见供离屏测试。 */
    void paint(Frame frame) {
        int cols = frame.area().width();
        int rows = frame.area().height();

        // ── Frame 的动作：area() 问尺寸 + renderWidget 画顶部两行说明（落在第 0、1 行）──
        frame.renderWidget(Paragraph.from(
                "表格尺寸：" + cols + " 列 × " + rows + " 行（拖动窗口，数字会变）\n"
                        + "h←  j↓  k↑  l→ 移动 ★（在放大镜里） · q 退出"),
                frame.area());

        // ── Buffer / Cell 的动作：拿到表格本体，把一格内容（Cell）写进 (starX, starY) ──
        Buffer grid = frame.buffer();
        Cell star = new Cell("★", Style.create());
        grid.set(starX, starY, star);

        // ── 放大镜：把这张表前 10 格 × 5 行，每格用 [ ] 框出来投在屏幕中央 ──
        //    一格一字符肉眼可数；中文一字占两格（主格[你]+续格[·]）；空白也是格。
        //    注意读写分区：读的是第 0-4 行，写的是第 5 行以下——放大镜自己的文字
        //    不会盖住它要展示的内容（set/setString 都是硬写，位置要自己负责）。
        int magY = 6;   // 放大镜投影像的起始行（标题在第 5 行）
        grid.setString(1, magY - 1, "放大镜：Buffer 的前 " + MAG_COLS + " 格 × " + MAG_ROWS
                + " 行（每格 [ ] 框出；· 是宽字符的第二格）", Style.create());
        for (int y = 0; y < MAG_ROWS; y++) {
            StringBuilder sb = new StringBuilder("第" + y + "行 ");
            for (int x = 0; x < MAG_COLS; x++) {
                Cell c = grid.get(x, y);
                String ch = c.isContinuation() ? "·"
                        : (c.symbol() == null || c.symbol().isEmpty() ? " " : c.symbol());
                sb.append("[").append(ch).append("]");
            }
            grid.setString(1, magY + y, sb.toString(), Style.create());
        }

        // ── 底部结论：回答「画面是谁」和「怎么操作」──
        grid.setString(1, rows - 3, "看到的画面 = Buffer 这张表；Frame 不是画面，"
                + "是填表工具（area 问尺寸 · renderWidget 批量画 · buffer 把表递给你）", Style.create());
        grid.setString(1, rows - 2, "操作三招：set 写一格 · setString 写一串 · renderWidget 画组件"
                + "（本程序三种各用了一次）", Style.create());

        lastFrame = grid.copy();
    }
}
