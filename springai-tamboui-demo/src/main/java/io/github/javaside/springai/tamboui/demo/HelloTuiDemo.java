package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 三个概念，三条带 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>屏幕自上而下三条带，每条带标注「是谁写的」——三个核心概念的用法
 * 一屏对齐：</p>
 * <ul>
 *   <li><b>顶部两行</b>：Frame 的 {@code renderWidget} 画的（含 {@code area()} 实时尺寸）；</li>
 *   <li><b>中间一行</b>：Buffer 的 {@code setString} 直接写的（没经过组件）；</li>
 *   <li><b>★</b>：Cell 用 {@code set} 写的一格（h/j/k/l 移动）。</li>
 * </ul>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** ★ 的位置（列 x、行 y）。 */
    int starX = 10;
    int starY = 5;

    /** 退出打印用的快照与最近画布尺寸（onKey 钳边界用）。 */
    private Buffer lastFrame;
    private int lastRows = 24;

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "三个概念，三条带——Buffer / Cell / Frame";
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

    /** 按键：h/j/k/l 移动 ★（钳在顶部文字与中间行之间的空带内）；q 退出。 */
    boolean onKey(Event event, TuiRunner r) {
        if (event instanceof KeyEvent key && key.isQuit()) {
            r.quit();
            return false;
        }
        if (event instanceof KeyEvent key) {
            if (key.isChar('h')) { starX = Math.max(1, starX - 2); return true; }
            if (key.isChar('l')) { starX = Math.min(lastFrame == null ? 80 : lastFrame.area().width() - 2, starX + 2); return true; }
            if (key.isChar('k')) { starY = Math.max(3, starY - 1); return true; }
            if (key.isChar('j')) { starY = Math.min(lastRows / 2 - 1, starY + 1); return true; }
        }
        return false;
    }

    /** 画一帧：三条带各出自谁，一眼可辨。包内可见供离屏测试。 */
    void paint(Frame frame) {
        int rows = frame.area().height();
        lastRows = rows;

        // ── 带一（第 0、1 行）：Frame——area() 问尺寸 + renderWidget 批量画 ──
        frame.renderWidget(Paragraph.from(
                "↑ 顶部：Frame 的 renderWidget 画的（尺寸 " + frame.area().width()
                        + " 列 × " + rows + " 行，拖窗口会变）\n"
                        + "h←  j↓  k↑  l→ 移动 ★ · q 退出（退出后打印这张表）"),
                frame.area());

        Buffer grid = frame.buffer();

        // ── 带二（屏幕中间一行）：Buffer——setString 直接写，不经过组件 ──
        grid.setString(1, rows / 2, "← 中间这行：Buffer 的 setString 直接写的（没经过组件）",
                Style.create());

        // ── 带三（★）：Cell——set 写一格 ──
        grid.set(starX, starY, new Cell("★", Style.create()));

        lastFrame = grid.copy();
    }
}
