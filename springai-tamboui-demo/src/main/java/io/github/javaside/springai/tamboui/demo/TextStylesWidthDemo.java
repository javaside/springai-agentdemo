package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.AnsiStringBuilder;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 示例① 富文本与宽度口径 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>不进 TUI 模式，stdout 直出（普通 System.out 打 ANSI 色），三段式：
 * 带病演示 → 病因 → 药方。</p>
 *
 * <p>本步核心认知：终端对齐的唯一真相源是<b>渲染宽度</b>，不是字符个数——
 * 连库自己也会算错（上游 CharWidth 对 VS16/keycap/ZWJ/ZWNJ 的口径与终端不符），
 * code-tui 因此维护了 inline-patch（shadow CharWidth 并自建 TerminalWidth）。</p>
 */
public final class TextStylesWidthDemo implements TambouiDemo {

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String title() {
        return "富文本三粒度 + 宽度口径（上游带病演示）";
    }

    @Override
    public void run() {
        section1StyledText();
        section2WidthTable();
        section3AlignmentDemo();
    }

    /** 第 1 段：Span（带样式的片段）→ Line（一行）→ Text（多行段落），三种粒度逐级拼装。 */
    private void section1StyledText() {
        System.out.println("—— 1. 富文本三粒度：Span → Line → Text ——");
        System.out.println("Span = 一个带样式的字符串片段（样式与内容绑定）：");
        Span red = Span.styled("红色片段", Style.create().fg(Color.RED));
        Span boldCyan = Span.styled("青色加粗", Style.create().fg(Color.CYAN).bold());
        System.out.println("  片段1 content()=\"" + red.content() + "\" width()=" + red.width());
        System.out.println("  片段2 content()=\"" + boldCyan.content() + "\" width()=" + boldCyan.width());

        System.out.println("Line = 一行，由多个 Span 拼成（还有 Text = 多行段落，把 Line 组成块）：");
        Line line = Line.from(red, Span.raw(" + "), boldCyan);
        System.out.println("  Line.width() = " + line.width() + "（行宽 = 各 Span 宽度求和，宽度口径从这里就开始起作用）");
        System.out.print("  实际渲染（stdout 场景自己把 Style 转 ANSI；TUI 场景这活交给 Buffer/Frame，见示例②）：\n    ");
        printSpan(red);
        printSpan(Span.raw(" + "));
        printSpan(boldCyan);
        System.out.println();
        Text text = Text.from(line, Line.from(Span.raw("Text 把多行 Line 组成段落，这句是第二行。")));
        System.out.println("  Text.height() = " + text.height() + "（Text 0.4.0 没有公开的按行取值 API——"
                + "它存在的意义是作为整体喂给渲染层，stdout 演示打印我们手里已有的 Span 即可）\n");
    }

    /** 第 2 段：宽度对照表——上游算的宽度 vs 终端实际渲染宽度，四行带病。 */
    private void section2WidthTable() {
        System.out.println("—— 2. 宽度对照表：上游 CharWidth 算的 vs 终端实际渲染 ——");
        System.out.println("（盯着「实际」列：带病行的样本会占得比上游以为的更宽）\n");
        System.out.printf("  %-12s %-4s %-8s %-8s%n", "样本", "码点数", "上游算的", "终端实际");
        for (WidthSample row : WidthSamples.table()) {
            // 病行用红色标出；样本列本身就会跳格——printf 的 %-12s 按 Java char 数补空格，
            // 而样本实际渲染宽度与它不符，这正是本节要展示的现象（别修它）。
            String mark = row.sick()
                    ? AnsiStringBuilder.styleToAnsi(Style.create().fg(Color.RED)) + "  ← 病（不符）" + AnsiStringBuilder.RESET
                    : "";
            System.out.printf("  %-12s %-4d %-8d %-8d%s%n",
                    row.sample(), row.sample().length(), row.upstream(), row.actualTerminal(), mark);
        }
        System.out.println("""

                病因：上游按「逐码点求和 + 简单跳宽」计宽——
                  · VS16（U+FE0F）被记 0 列，但它把前面的符号顶成 emoji 显示（占 2 列）；
                  · keycap（数字+FE0F+20E3）逐码点算完只有 1 列，终端画成 2 列的按键帽；
                  · ZWJ 家族（👨+ZWJ+👩+ZWJ+👧）被「跳宽分支」整段记 2 列，多数终端按成员分开渲染；
                  · ZWNJ（U+200C）被记 0 列，部分终端实际占 1 列（2026-10 抖音粘贴事故的根源）。
                「终端实际」列取自 inline-patch 的实测记录；不同终端对 ZWJ 的合并渲染确有差异——
                这正说明：没有唯一真相时，工程只能钉一个主流实测口径。
                """);
    }

    /** 第 3 段：用上游口径做手动两栏对齐——健康行对齐、带病行 visibly 崩坏。 */
    private void section3AlignmentDemo() {
        System.out.println("—— 3. 手动两栏对齐：左列样本 + 右列说明（补空格按「上游算的宽度」） ——\n");
        for (WidthSample row : WidthSamples.table()) {
            // 把样本补到固定 10 列宽——补多少完全听上游 CharWidth 的，病行就会补少 → 右栏右移错位
            int computed = CharWidth.of(row.sample());
            int pad = Math.max(0, 10 - computed);
            System.out.println("  " + row.sample() + " ".repeat(pad) + "| " + row.label()
                    + (row.sick() ? "   ← 右栏被顶歪了：终端实际占 " + row.actualTerminal() + " 列，只补了 " + computed + " 列" : "   ✓ 对齐"));
        }
        System.out.println("""

                药方：code-tui 的 springai-tamboui-inline-patch 模块 shadow 了 CharWidth（组合序列
                按终端实际渲染宽度计）并自建 TerminalWidth 类——同包同名类靠 classpath 顺序优先加载，
                库本体零改动。见仓库 springai-tamboui-inline-patch 模块与
                docs/superpowers/specs/ 下宽度事故系列 spec。

                本步核心认知：终端对齐的唯一真相源是渲染宽度，不是字符个数；
                连库自己也会算错——所以 code-tui 连宽度层都自己钉了口径。
                """);
    }

    /** 打印单个 Span：Style → SGR 序列（上游 AnsiStringBuilder 提供），内容，复位。 */
    private static void printSpan(Span span) {
        System.out.print(AnsiStringBuilder.styleToAnsi(span.style()) + span.content() + AnsiStringBuilder.RESET);
    }
}
