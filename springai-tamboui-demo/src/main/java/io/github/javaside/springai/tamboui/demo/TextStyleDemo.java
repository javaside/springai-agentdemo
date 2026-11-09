package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
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
 * 第 2 课 文本与样式 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>全屏展示：富文本三粒度（Span→Line→Text）、Style 不可变方法链、颜色三种来源
 * （命名色 / 256 色 indexed / RGB）、行对齐、Paragraph 自动折行（WRAP_WORD），
 * 以及宽度口径的基础事实（ASCII 1 列、CJK 2 列）。</p>
 *
 * <p><b>本课核心认知</b>：终端富文本的一切都从「带样式的字符串片段（Span）」开始；
 * 宽度是渲染口径——中文占 2 列，第 1 课 Buffer 网格里的「主格+续格(·)」就是它的形态。</p>
 */
public final class TextStyleDemo implements TambouiDemo {

    private static final String WRAP_SENTENCE =
            "折行演示：Paragraph 默认按词折行（WRAP_WORD）——这段长文本不需要你手工断行，"
                    + "放到窄区域里它自己换行；超出区域的处理方式由 overflow 决定（截断/省略号/折行）。";

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "文本与样式（三粒度 / 颜色 / 对齐 / 折行 / 宽度）";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                }
                return false;
            };
            runner.run(handler, this::paint);
        }
    }

    private void paint(Frame frame) {
        StringBuilder widthNote = new StringBuilder("宽度口径：");
        for (TextStyleShowcase.WidthExample row : TextStyleShowcase.widthExamples()) {
            widthNote.append('[').append(row.sample()).append(" → ").append(row.columns()).append("列] ");
        }
        // Text（0.4.0）没有公开的行级读取/追加——要加行就一次性把所有 Line 构造进 from(...)。
        // 这本身就是一课：Text 是「整体喂给渲染层」的值对象，不是可变列表。
        Text full = Text.from(
                Line.from(Span.styled("第 2 课 · 文本与样式", Style.create().fg(Color.CYAN).bold())),
                Line.from(Span.raw("")),
                Line.from(Span.styled("三粒度：", Style.create().bold()),
                        Span.styled("Span(片段)", Style.create().fg(Color.YELLOW)),
                        Span.raw(" → "),
                        Span.styled("Line(一行)", Style.create().fg(Color.GREEN)),
                        Span.raw(" → "),
                        Span.styled("Text(段落)", Style.create().fg(Color.MAGENTA))),
                Line.from(Span.styled("颜色三种来源：", Style.create().bold()),
                        Span.styled("命名色 ", Style.create().fg(Color.RED)),
                        Span.styled("256色 ", Style.create().fg(Color.indexed(208))),
                        Span.styled("RGB", Style.create().fg(Color.rgb(70, 130, 180)))),
                Line.from(Span.styled("样式可链式叠加：", Style.create().bold()),
                        Span.styled("加粗+斜体+下划线", Style.create().bold().italic().underlined())),
                Line.from(Span.raw("")),
                Line.from(Span.raw("对齐（同一屏宽下对比）：")),
                Line.from(Span.styled("← 左对齐（默认）", Style.create().fg(Color.GRAY))).left(),
                Line.from(Span.styled("居中对齐", Style.create().fg(Color.GRAY))).centered(),
                Line.from(Span.styled("右对齐 →", Style.create().fg(Color.GRAY))).right(),
                Line.from(Span.raw("")),
                Line.from(Span.styled(WRAP_SENTENCE, Style.create().fg(Color.GRAY))),
                Line.from(Span.raw("")),
                Line.from(Span.styled(widthNote.toString(), Style.create().fg(Color.YELLOW))),
                Line.from(Span.styled("（第 1 课网格里的 主格+续格· 就是 2 列宽字符的形态）",
                        Style.create().fg(Color.YELLOW))),
                Line.from(Span.styled("按 q / Esc / Ctrl+C 退出", Style.create().fg(Color.GRAY))).centered());

        frame.renderWidget(
                Paragraph.builder().text(full).overflow(Overflow.WRAP_WORD).build(),
                frame.area());
    }
}
