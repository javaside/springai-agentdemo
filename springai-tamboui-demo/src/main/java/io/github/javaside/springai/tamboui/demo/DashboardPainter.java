package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.List;

/**
 * 第 3 课的整帧绘制（离屏可单测）：按 {@link DashboardLayout} 切分区域，
 * 各区域放一个组件——头（Paragraph + Block 标题）、左（ListWidget，StatefulWidget）、
 * 右（Gauge）、尾（提示行）。
 *
 * <p>组件分两种：{@code Widget}（无状态，render(Rect, Buffer) 自己就能画，如 Paragraph/Gauge/Block）
 * 与 {@code StatefulWidget}（状态在<b>外部</b>持有，渲染时传入——如 ListWidget 配 {@link ListState}
 * 管选中与滚动）。状态外置正是「UI = 状态的纯函数」能成立的原因。</p>
 */
public final class DashboardPainter {

    private static final List<String> LESSONS = List.of(
            "第 1 课 渲染模型", "第 2 课 文本与样式", "第 3 课 组件与布局（List 在此）",
            "第 4 课 事件与交互", "第 5 课 Toolkit DSL", "第 6 课 行内模式");

    private DashboardPainter() {
    }

    private static final String DEFAULT_FOOTER =
            "布局语言：length(n) 固定 · percentage(n) 比例 · fill() 吃剩余——切分可嵌套 | 按 q / Ctrl+C 退出";

    /** 画一帧完整课程面板。listState 由调用方持有（第 3 课静态展示用）。 */
    public static void paint(Frame frame, ListState listState) {
        paint(frame, listState, 62, DEFAULT_FOOTER);
    }

    /** 动态版（第 4 课交互用）：Gauge 比例与尾行文本随状态变化——同一棵布局，不同的状态。 */
    public static void paint(Frame frame, ListState listState, int gaugePercent, String footerText) {
        var parts = DashboardLayout.split(frame.area());
        var header = parts.get(0);
        var middle = parts.get(1);
        var footer = parts.get(2);

        // 头：带圆角边框与标题的 Paragraph——Block 就是「边框 + 标题」组件
        frame.renderWidget(
                Paragraph.builder()
                        .text(dev.tamboui.text.Text.from(
                                dev.tamboui.text.Line.from(dev.tamboui.text.Span.styled(
                                        "第 3 课 · 组件与布局",
                                        dev.tamboui.style.Style.create()
                                                .fg(dev.tamboui.style.Color.CYAN).bold()))))
                        .block(Block.builder().borders(Borders.BOTTOM_ONLY).build())
                        .build(),
                header);

        // 中段左右切分：List 占 60%，Gauge 吃剩余
        var columns = DashboardLayout.splitMiddle(middle);
        frame.renderStatefulWidget(
                ListWidget.builder()
                        .items(LESSONS.toArray(new String[0]))
                        .highlightSymbol("▶ ")
                        .block(rounded("课程目录"))
                        .build(),
                columns.get(0), listState);

        frame.renderWidget(
                Gauge.builder().ratio(gaugePercent / 100.0)
                        .label(dev.tamboui.text.Span.styled("课程进度 " + gaugePercent + "%",
                                dev.tamboui.style.Style.create().fg(dev.tamboui.style.Color.GREEN)))
                        .block(rounded("Gauge"))
                        .build(),
                columns.get(1));

        // 尾：操作提示/状态行（内容由调用方决定——静态课是提示语，交互课是最近按键）
        frame.renderWidget(
                Paragraph.builder().text(dev.tamboui.text.Text.from(
                        dev.tamboui.text.Line.from(dev.tamboui.text.Span.styled(
                                footerText, dev.tamboui.style.Style.create().fg(dev.tamboui.style.Color.GRAY)))))
                        .build(),
                footer);
    }

    private static Block rounded(String title) {
        return Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).title(title).build();
    }
}
