package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.list.ListState;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 4 课 组件画廊 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>认识积木：四个常用组件各占一块演示区——Paragraph（多行文本）、Block（边框容器，
 * 官方称「最基础的容器」）、ListWidget+ListState（列表，有状态组件典范）、Gauge（进度条）。
 * 绘制逻辑在 {@link GalleryPainter}（离屏可单测），每块区域的代码对照见画面注释与 README。</p>
 *
 * <p>四块区域是<b>手写的 Rect</b>——下一课（布局）用 Layout/Constraint 把「手摆」升级成
 * 「约束切分」，正好形成前后对照。TamboUI 全部 26 个组件（8 大类：容器/文本/选择/
 * 数据可视化/动画/输入/工具/Markdown）见官方文档，课程演示最常用的四个。</p>
 *
 * <p><b>本课核心认知</b>：组件分无状态（{@code renderWidget(组件, 区域)} 直接画）与
 * 有状态（{@code renderStatefulWidget(组件, 区域, 状态)}，状态外部持有）两种——
 * 状态外置正是「UI = 状态的纯函数」能成立的原因。</p>
 */
public final class WidgetsGalleryDemo implements TambouiDemo {

    private final ListState listState = new ListState();

    @Override
    public int order() {
        return 4;
    }

    @Override
    public String title() {
        return "组件画廊——Paragraph / Block / List / Gauge 逐个亮相";
    }

    @Override
    public void run() throws Exception {
        listState.select(1);
        try (TuiRunner runner = TuiRunner.create()) {
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                }
                return false;
            };
            runner.run(handler, frame -> GalleryPainter.paint(frame, listState));
        }
    }
}
