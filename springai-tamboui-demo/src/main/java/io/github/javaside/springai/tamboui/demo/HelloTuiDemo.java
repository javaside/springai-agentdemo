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
 * <p>正文只有 {@link #paint} 的几行概念用法：Frame 画说明、Buffer 拿表本体、
 * Cell 写一格（★，h/j/k/l 移动）。屏幕中央的「放大镜」是教学道具
 * （{@link Magnifier}，一格一字符肉眼可数），一行调用，不必读其实现。</p>
 *
 * <p>做界面的正道见 README 第 2 课「定心丸」：万能公式 = renderWidget(组件, 区域)。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** ★ 在表格中的位置（列 x、行 y）——被钳在放大镜范围内，始终可见。 */
    int starX = 3;
    int starY = 2;

    /** 退出打印用的最后一屏快照。 */
    private Buffer lastFrame;

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
            runner.run(this::onKey, this::paint);
        }
        System.out.println("\n—— 刚才那屏在 Buffer 里的样子（一格一字符；· 是宽字符第二格）——");
        if (lastFrame != null) {
            FrameDump.dump(lastFrame).forEach(l -> System.out.println("  " + l));
        }
    }

    /** 按键：h/j/k/l 移动 ★（钳在放大镜 10×5 内）；q 退出。 */
    boolean onKey(Event event, TuiRunner r) {
        if (event instanceof KeyEvent key && key.isQuit()) {
            r.quit();
            return false;
        }
        if (event instanceof KeyEvent key) {
            if (key.isChar('h')) { starX = Math.max(0, starX - 1); return true; }
            if (key.isChar('l')) { starX = Math.min(Magnifier.COLS - 1, starX + 1); return true; }
            if (key.isChar('k')) { starY = Math.max(0, starY - 1); return true; }
            if (key.isChar('j')) { starY = Math.min(Magnifier.ROWS - 1, starY + 1); return true; }
        }
        return false;
    }

    /** 画一帧：三概念各一行 + 教具一行。包内可见供离屏测试。 */
    void paint(Frame frame) {
        // ① Frame：area() 问尺寸；renderWidget 画顶部说明（落在第 0、1 行）
        frame.renderWidget(Paragraph.from(
                "表格尺寸：" + frame.area().width() + " 列 × " + frame.area().height()
                        + " 行（拖动窗口，数字会变）\n"
                        + "h←  j↓  k↑  l→ 移动 ★（在放大镜里） · q 退出"),
                frame.area());

        // ② Buffer：拿到表格本体；③ Cell：一格内容——写进 (starX, starY)，★ 随按键移动
        Buffer grid = frame.buffer();
        grid.set(starX, starY, new Cell("★", Style.create()));

        // 教具（不必读实现）：把左上 10 格 × 5 行每格 [ ] 框出投到屏幕中央
        Magnifier.project(frame, Magnifier.COLS, Magnifier.ROWS, 6);

        // 底部结论：画面 = Buffer；Frame 是工具；操作三招
        int rows = frame.area().height();
        grid.setString(1, rows - 3, "看到的画面 = Buffer 这张表；Frame 是填表工具（area/renderWidget/buffer）",
                Style.create());
        grid.setString(1, rows - 2, "操作三招：set 写一格 · setString 写一串 · renderWidget 画组件",
                Style.create());

        lastFrame = grid.copy();
    }
}
