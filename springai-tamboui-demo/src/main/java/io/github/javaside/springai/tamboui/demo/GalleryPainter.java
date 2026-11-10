package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.list.ListState;
import dev.tamboui.widgets.list.ListWidget;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * 第 4 课「组件画廊」的整帧绘制（离屏可单测）：四个常用组件各占一块演示区——
 * Paragraph（多行文本）、Block（边框容器）、ListWidget+ListState（列表）、Gauge（进度条）。
 *
 * <p>四块区域目前是<b>手写的 Rect</b>（第 5 课才系统讲 Layout 切分——画廊先「用手摆」，
 * 下一课把手摆升级成约束切分，正好形成对照）。</p>
 */
public final class GalleryPainter {

    /** 演示区划分（手写 Rect：第 5 课将用 Layout/Constraint 重写同样的分区）。 */
    static Rect paragraphArea(Rect screen) {
        return new Rect(1, 2, screen.width() / 2 - 2, screen.height() / 2 - 2);
    }

    static Rect blockArea(Rect screen) {
        int half = screen.width() / 2;
        return new Rect(half + 1, 2, screen.width() - half - 2, screen.height() / 2 - 2);
    }

    static Rect listArea(Rect screen) {
        return new Rect(1, screen.height() / 2 + 1, screen.width() / 2 - 2, screen.height() / 2 - 3);
    }

    static Rect gaugeArea(Rect screen) {
        int half = screen.width() / 2;
        return new Rect(half + 1, screen.height() / 2 + 1, screen.width() - half - 2, screen.height() / 2 - 3);
    }

    private GalleryPainter() {
    }

    /** 画一帧画廊。listState 由调用方持有（有状态组件的惯例：状态外置）。 */
    public static void paint(Frame frame, ListState listState) {
        Rect screen = frame.area();

        // ① Paragraph：多行文本（对齐见第 3 课；这里演示它在组件层的用法）
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(
                        Line.from(Span.styled("① Paragraph", Style.create().fg(Color.CYAN).bold())),
                        Line.from(Span.styled("多行文本组件。text(Text/String) 装内容，", Style.create().fg(Color.GRAY))),
                        Line.from(Span.styled("可选 block(...) 挂边框、centered() 居中、", Style.create().fg(Color.GRAY))),
                        Line.from(Span.styled("overflow(...) 定超宽行为（默认 CLIP 截断）", Style.create().fg(Color.GRAY)))))
                .block(Block.builder().borders(Borders.TOP_ONLY).build())
                .build(),
                paragraphArea(screen));

        // ② Block：最基础的容器——边框 + 标题（常挂在别的组件的 .block(...) 上）
        Block block = Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .title("② Block")
                .build();
        Buffer buf = frame.buffer();
        Rect area = blockArea(screen);
        block.render(area, buf);
        buf.setString(area.x() + 2, area.y() + 1, "边框容器：borders(哪些边) + borderType(样式) + title(标题)",
                Style.create().fg(Color.GRAY));

        // ③ ListWidget + ListState：列表（有状态组件——状态外部持有，渲染时传入）
        frame.renderStatefulWidget(
                ListWidget.builder()
                        .items("ListWidget.builder().items(...)", "选中行由 ListState 决定", "高亮符号 highlightSymbol")
                        .highlightSymbol("▶ ")
                        .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).title("③ List").build())
                        .build(),
                listArea(screen), listState);

        // ④ Gauge：进度条
        frame.renderWidget(
                Gauge.builder().ratio(0.62)
                        .label(Span.styled("课程进度 62%", Style.create().fg(Color.GREEN)))
                        .block(Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).title("④ Gauge").build())
                        .build(),
                gaugeArea(screen));
    }
}
