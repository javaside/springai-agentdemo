package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 1 课 Hello TUI——渲染模型 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>全屏模式（{@link TuiRunner} + 备用屏幕）：应用运行时接管整个终端，退出后终端复原。
 * 一个最小的 TUI 应用只有两个函数：</p>
 * <ul>
 *   <li><b>渲染函数</b>（renderer）：把「这一帧应该长什么样」整帧画进 {@code Frame}——
 *       框架拿它与上一帧的 Buffer 做 diff，只把变化的 Cell 发给终端；</li>
 *   <li><b>事件处理</b>（handler）：收到按键等事件，改状态或退出。</li>
 * </ul>
 *
 * <p>退出后本课还把最后一帧的 Buffer 网格打印出来（{@link FrameDump}）——亲眼看到
 * 「屏幕 = Cell 网格」，diff 有了直观对象。</p>
 *
 * <p><b>本课核心认知</b>：UI 是应用状态的纯函数——每帧从零全量重画，框架负责算最小差量。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** 退出后供 FrameDump 展示的最后一帧快照（渲染线程写、run() 返回后主线程读，先后有序）。 */
    private dev.tamboui.buffer.Buffer lastFrame;

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String title() {
        return "Hello TUI——渲染模型（全屏 + Buffer 网格亲眼看）";
    }

    @Override
    public void run() throws Exception {
        // TuiRunner.create()：全屏应用的标配——进备用屏幕 + 原始模式 + 默认键位（q/Q/Ctrl+C 退出）。
        try (TuiRunner runner = TuiRunner.create()) {
            // 渲染函数：每个需要重绘的时刻被调用一次。只管描述整帧，不关心上一帧。
            dev.tamboui.tui.Renderer renderer = this::paint;
            // 事件处理：返回值 = 是否需要重绘。本课没有可变状态，只处理退出，恒不需重绘。
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                }
                return false;
            };
            runner.run(handler, renderer);
        }

        // 已退出全屏（备用屏幕关闭、终端复原）。现在把「刚才那一帧」的网格打印给学习者看。
        System.out.println("\n—— 刚才那一帧，在 Buffer（Cell 网格）里的样子 ——");
        System.out.println("（每个 Cell 存一个符号；屏幕上中文实际渲染占 2 列，宽度是第 2 课的话题）");
        if (lastFrame != null) {
            for (String line : FrameDump.dump(lastFrame)) {
                System.out.println("  " + line);
            }
        }
        System.out.println("\n本课核心认知：UI 是应用状态的纯函数——每帧从零全量重画，");
        System.out.println("框架拿本帧与上一帧的 Buffer 做 diff，只把变化的 Cell 发给终端。");
    }

    /** 画一帧：居中的欢迎语 + 提示行。Frame 是 Buffer 的封装入口，renderWidget 把组件画进区域。 */
    private void paint(Frame frame) {
        Text text = Text.from(
                Line.from(Span.styled("你好，TamboUI！", Style.create().fg(Color.CYAN).bold())).centered(),
                Line.from(Span.raw("")).centered(),
                Line.from(Span.styled("第 1 课：这一屏就是一个 Cell 网格，你按任意退出键后我会把它打印出来",
                        Style.create().fg(Color.GRAY))).centered(),
                Line.from(Span.styled("按 q / Esc / Ctrl+C 退出", Style.create().fg(Color.YELLOW))).centered()
        );
        frame.renderWidget(Paragraph.builder().text(text).build(), frame.area());
        // 留一份快照：退出后打印网格，让「Buffer」从概念变成实物
        lastFrame = frame.buffer().copy();
    }
}
