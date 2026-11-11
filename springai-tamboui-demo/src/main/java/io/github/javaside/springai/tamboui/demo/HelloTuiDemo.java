package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Cell;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 三个对象的用法演示 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>本课不做功能，只教三样东西各怎么用一下、各看到一次效果——paint 里
 * <b>每个概念恰好一行</b>：</p>
 *
 * <ul>
 *   <li><b>Frame.area()</b>：问「这张表多大」。屏幕上显示实时尺寸——拖动窗口，数字跟着变；</li>
 *   <li><b>Frame.renderWidget(...)</b>：往表里批量画一段文字（第 1 课已见过，框架替你填格）；</li>
 *   <li><b>frame.buffer().set(x, y, new Cell("★"))</b>：拿到表格本体，往某一格填一个 ★。
 *       按 h/j/k/l 移动 ★——每按一次，就是「往另一个格子写一个 Cell」。</li>
 * </ul>
 *
 * <p>退出后把最后一屏的表格原样打印出来（FrameDump）——亲眼确认
 * 「看到的画面 = 这张字符表格」。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** ★ 在表格中的位置（列 x、行 y）。这就是本课的全部状态。 */
    int starX = 10;
    int starY = 5;

    /** 最近一帧的画布尺寸（paint 里更新，onKey 用它把 ★ 挡在表格边界内）。 */
    private int lastWidth = 80;
    private int lastHeight = 24;

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "Buffer / Cell / Frame 的用法——移动 ★";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            Renderer renderer = this::paint;
            runner.run(this::onKey, renderer);
        }

        // 退出后：把最后一屏的表格打印出来（保存快照见 paint 末尾）
        System.out.println("\n—— 刚才那屏，在 Buffer（字符表格）里的样子 ——");
        System.out.println("（第 y 行 = 屏幕第 y 行，一格 = 一个字符；· 是宽字符占的第二格）");
        if (lastFrame != null) {
            FrameDump.dump(lastFrame).forEach(l -> System.out.println("  " + l));
        }
        System.out.println("""
                
                本课三行代码，三个对象：
                · frame.area()                → 表多大（拖窗口，屏幕上数字会变）
                · frame.renderWidget(组件,区域) → 批量往表里画（框架替你填格）
                · frame.buffer().set(x,y,Cell) → 拿到表本体，往一格填一个字符（★ 就是那格）
                """);
    }

    /** 按键：h/j/k/l 移动 ★（往新格子写）；q 退出。返回值 = 是否需要重绘。 */
    boolean onKey(Event event, TuiRunner r) {
        if (event instanceof KeyEvent key && key.isQuit()) {
            r.quit();
            return false;
        }
        if (event instanceof KeyEvent key) {
            // 边界用最近一帧的画布尺寸（TuiRunner 无公开尺寸查询，paint 时记下即可）。
            // starY 下限取 2 是「人肉防撞」：上面的文字占第 0、1 行，★ 最低压到第 2 行——
            // set 是硬写一格，若让 ★ 走进文字区，字会被顶掉（位置自己负责）。
            // 「位置自动排、互不打架」要等第 5 课的布局（区域切分）。
            if (key.isChar('h')) { starX = Math.max(1, starX - 2); return true; }
            if (key.isChar('l')) { starX = Math.min(lastWidth - 2, starX + 2); return true; }
            if (key.isChar('k')) { starY = Math.max(2, starY - 1); return true; }
            if (key.isChar('j')) { starY = Math.min(lastHeight - 2, starY + 1); return true; }
        }
        return false;
    }

    /** 退出打印用的最后一屏快照。 */
    private dev.tamboui.buffer.Buffer lastFrame;

    /** 画一帧：每个概念一行。包内可见供离屏测试。 */
    void paint(Frame frame) {
        // ① Frame.area()：问这张表多大——下面的文字因此永远显示实时尺寸
        lastWidth = frame.area().width();
        lastHeight = frame.area().height();
        String size = lastWidth + " 列 × " + lastHeight + " 行";

        // ② Frame.renderWidget：往表里批量画文字——从区域左上角 (0,0) 起逐行铺，
        //    本例两行文字落在第 0、1 行（框架内部就是替你逐字符填格，坐标它算）
        frame.renderWidget(Paragraph.from(
                "表格尺寸：" + size + "（拖动窗口，数字会变）\n"
                        + "h←  j↓  k↑  l→ 移动 ★   ·   q 退出（退出后打印这张表）"),
                frame.area());

        // ③ frame.buffer().set(列, 行, Cell)：拿到表本体，往一格填一个字符
        frame.buffer().set(starX, starY, new Cell("★", Style.create()));

        lastFrame = frame.buffer().copy();
    }
}
