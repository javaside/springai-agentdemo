package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 三个核心类：Buffer、Cell、Frame —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>核心是这三个类的<b>意思和使用</b>；方法 = 画一个简单的备忘录界面，
 * 在画的过程中把三个类各用起来（对照见 README 的「意思 × 使用」表）。</p>
 *
 * <p>核心是这三个类的<b>意思和使用</b>；方法 = 画一个简单的备忘录界面，
 * 在画的过程中把三个类各用起来（对照见 README 的「使用 × 本例对照」表）。</p>
 *
 * <p>画面底下发生了什么（Buffer/Cell/Frame）见退出后打印的网格图与 README
 * 「背后机制」一节——它们是理解层，日常做界面不直接碰。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** 退出打印用的快照。 */
    private Buffer lastFrame;

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "Buffer / Cell / Frame——画界面的三个核心类";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            runner.run(this::onKey, this::paint);
        }
        System.out.println("""
                
                —— 背后机制：刚才那屏在 Buffer（字符表格）里的样子 ——
                每个字符占一格（中文一字占两格，· 是第二格）；
                组件画出来的每个字，最终都躺在这张表的格子里。
                分工：你（借组件之手）填 Buffer；渲染到终端的是 TuiRunner（框架），
                Frame 只是填表时的工具包——不是渲染器。
                """);
        if (lastFrame != null) {
            FrameDump.dump(lastFrame).forEach(l -> System.out.println("  " + l));
        }
    }

    /** 本课只处理一件事：退出。交互（按键改状态）是第 6 课的正题。 */
    boolean onKey(Event event, TuiRunner r) {
        if (event instanceof KeyEvent key && key.isQuit()) {
            r.quit();
        }
        return false;
    }

    /**
     * 画一帧：用三个核心概念画出一个真实的备忘录界面。
     * 包内可见供离屏测试。
     */
    void paint(Frame frame) {
        // ── 概念① Frame：渲染入口——area() 问尺寸；renderWidget(组件, 区域) 批量画 ──
        //    区域（Rect）= 起点（第几列，第几行）+ 宽高（多少格 × 多少行）
        Rect titleArea = new Rect(4, 1, 30, 2);          // 标题区：第 1 行、第 4 列起
        Rect listArea = new Rect(4, 4, 30, 6);           // 列表区：第 4 行起
        Rect footerArea = new Rect(4, frame.area().height() - 3, 60, 2);   // 底栏：贴屏幕底
        frame.renderWidget(Paragraph.from("我的备忘录"), titleArea);
        frame.renderWidget(Paragraph.from("买牛奶\n写周报\n复习 Buffer/Cell/Frame"), listArea);
        frame.renderWidget(Paragraph.from("q 退出（退出后打印这张表）"), footerArea);

        // ── 概念② Buffer：从 Frame 拿到表格本体——画面此刻的数据 ──
        //    它自己也能写：setString 从某格起写一串（这里画标题下的分隔线）
        Buffer grid = frame.buffer();
        grid.setString(4, 3, "────────────", Style.create());

        // ── 概念③ Cell：一格内容（字符+样式）——往第一条待办前写一个 ● 标记 ──
        grid.set(3, 4, new Cell("●", Style.create()));

        lastFrame = frame.buffer().copy();
    }
}
