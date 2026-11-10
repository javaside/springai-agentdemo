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
 * 第 3 课 文本与样式 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>全屏展示：富文本三粒度（Span→Line→Text）、Style 不可变方法链、颜色三种来源
 * （命名色 / 256 色 indexed / RGB）、行对齐、Paragraph 自动折行（WRAP_WORD），
 * 以及宽度口径的基础事实（ASCII 1 列、CJK 2 列）。</p>
 *
 * <p><b>本课核心认知</b>：终端富文本的一切都从「带样式的字符串片段（Span）」开始；
 * 宽度是渲染口径——中文占 2 列，第 2 课 Buffer 网格里的「主格+续格(·)」就是它的形态。</p>
 */
public final class TextStyleDemo implements TambouiDemo {

    private static final String WRAP_SENTENCE =
            "折行演示：设 overflow(WRAP_WORD) 才按词折行——这段长文本不需要手工断行；"
                    + "注意 Paragraph 默认是 CLIP 截断，长文本会被静默裁掉，别漏设。";

    @Override
    public int order() {
        return 3;
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
        // Text 是不可变值对象：append(Line) / push(Span) 都返回<b>新实例</b>（0.4.0 实测公开 API），
        // 课程用它把「演示文本 + 折行段 + 宽度说明」逐段接上——这是 Text 的标准组装方式。
        Text full = TextStyleShowcase.demoText()
                .append(Line.from(Span.raw("")))
                .append(Line.from(Span.styled(WRAP_SENTENCE, Style.create().fg(Color.GRAY))))
                .append(Line.from(Span.raw("")))
                .append(Line.from(Span.styled(widthNote.toString(), Style.create().fg(Color.YELLOW))))
                .append(Line.from(Span.styled("（第 2 课网格里的 主格+续格· 就是 2 列宽字符的形态）",
                        Style.create().fg(Color.YELLOW))))
                .append(Line.from(Span.styled("按 q / Ctrl+C 退出", Style.create().fg(Color.GRAY))).centered());

        frame.renderWidget(
                Paragraph.builder().text(full).overflow(Overflow.WRAP_WORD).build(),
                frame.area());
    }
}
