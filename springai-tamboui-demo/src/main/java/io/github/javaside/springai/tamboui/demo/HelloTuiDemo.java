package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 做出第一个界面 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p><b>画界面只有一件事</b>：{@code renderWidget(组件, 区域)}——组件给内容，
 * 区域给位置。本课用它三次，拼出一个备忘录面板：标题、待办内容、底部提示，
 * 放进三个手写的区域（Rect = 起点 + 宽高）。</p>
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
        return "第一个界面：区域 + 组件（退出看背后机制）";
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

    /** 画一帧：一个界面 = 三块内容放进三个区域。包内可见供离屏测试。 */
    void paint(Frame frame) {
        // 区域（Rect）= 起点（第几列，第几行）+ 宽高（多少格 × 多少行）
        Rect titleArea = new Rect(4, 1, 30, 3);            // 标题区：第 1 行、第 4 列起，30×3
        Rect contentArea = new Rect(4, 5, 30, 8);          // 内容区：第 5 行起，30×8
        Rect footerArea = new Rect(4, frame.area().height() - 3, 50, 2);   // 底栏：贴着屏幕底部

        // 画界面 = renderWidget(组件, 区域)，要几块就调几次
        frame.renderWidget(Paragraph.from("我的备忘录"), titleArea);
        frame.renderWidget(Paragraph.from("买牛奶\n写周报\n复习「区域 + 组件」"), contentArea);
        frame.renderWidget(Paragraph.from("界面 = renderWidget(组件, 区域)；拖动窗口底栏仍贴底 · q 退出"),
                footerArea);

        lastFrame = frame.buffer().copy();
    }
}
