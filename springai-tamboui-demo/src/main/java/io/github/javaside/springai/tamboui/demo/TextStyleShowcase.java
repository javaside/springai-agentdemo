package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.List;

/**
 * 第 3 课的数据源（纯逻辑、可单测）：演示文本与宽度基础示例。
 *
 * <p>富文本三粒度：{@link Span}（带样式的字符串片段）→ {@link Line}（一行，多个 Span 拼接）
 * → {@link Text}（多行段落）。样式是<b>不可变</b>的——每个方法返回新实例，链式调用即组合。</p>
 */
public final class TextStyleShowcase {

    /** 宽度基础示例：样本 → 渲染列数（ASCII 1 列、CJK 2 列、混合求和）。 */
    public record WidthExample(String sample, int columns) {
    }

    private TextStyleShowcase() {
    }

    /** 四个中文组成的行（宽度 8 列）。 */
    public static Line cjkLine() {
        return Line.from(Span.styled("终端富文本", Style.create().fg(Color.GREEN)));
    }

    /** 混合行：ASCII + CJK + ASCII（宽度 1+2+1... 由各 Span 求和）。 */
    public static Line mixedLine() {
        return Line.from(
                Span.styled("第", Style.create().fg(Color.RED)),
                Span.raw("2"),
                Span.styled("课", Style.create().fg(Color.BLUE).bold()));
    }

    /** 课程主演示文本（10 行）。 */
    public static Text demoText() {
        return Text.from(
                Line.from(Span.styled("第 3 课 · 文本与样式", Style.create().fg(Color.CYAN).bold())),
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
                Line.from(Span.raw("对齐（同一屏宽下对比）：")).left(),
                Line.from(Span.styled("← 左对齐（默认）", Style.create().fg(Color.GRAY))).left(),
                Line.from(Span.styled("居中对齐", Style.create().fg(Color.GRAY))).centered(),
                Line.from(Span.styled("右对齐 →", Style.create().fg(Color.GRAY))).right());
    }

    /** 宽度基础示例行（与 CharWidth 现场一致；续格概念见第 2 课 FrameDump 的 ·）。 */
    public static List<WidthExample> widthExamples() {
        return List.of(
                new WidthExample("A", CharWidth.of("A")),
                new WidthExample("中", CharWidth.of("中")),
                new WidthExample("中文A", CharWidth.of("中文A")));
    }
}
