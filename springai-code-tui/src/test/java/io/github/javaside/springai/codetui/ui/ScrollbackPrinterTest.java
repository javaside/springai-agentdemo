package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScrollbackPrinter：喂输入，断言下沉到 scrollback 的行（经 Sink 接缝捕获，不经 InlineDisplay 的 ANSI 裁剪）。 */
class ScrollbackPrinterTest {

    /** 记录型输出接缝：把每次 println 的内容收进 lines（Text 取 rawContent 纯文本）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    /** 造一个 printer：固定 80 列，root=/work（折行由 printer 内部完成，无需注入）。 */
    private static ScrollbackPrinter printerOver(RecordingSink sink) {
        return new ScrollbackPrinter(sink, Path.of("/work"), () -> 80);
    }

    @Test
    void welcome_printsRoundedBannerWithModelAndCwd() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).welcome("deepseek-v4-flash", "v9.9.9");

        // 顶边框(标题+版本嵌入) 1 + 内容 7 + 底边框 1 + 末尾空行 1 = 11 行
        assertEquals(11, sink.lines.size(), "欢迎横幅应输出 11 行");
        assertTrue(sink.lines.get(0).startsWith("╭"), "首行应为圆角上边框");
        assertTrue(sink.lines.get(0).contains("Spring AI Code TUI"), "标题应嵌入顶部边框");
        assertTrue(sink.lines.get(0).contains("v9.9.9"), "版本号应嵌入顶部边框");
        assertTrue(sink.lines.get(9).startsWith("╰"), "倒数第二行应为圆角下边框");
        assertEquals("", sink.lines.get(10), "末行应为留白空行");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("deepseek-v4-flash")), "应含所选模型名");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("/work")), "应含 cwd 路径");
    }

    @Test
    void assistant_wrapsLongLineToWidth_losesNothing() {
        RecordingSink sink = new RecordingSink();
        // 80 列终端，正文一行 120 个字符：InlineDisplay 的 println 是定宽截断，printer 必须先折行
        // ——否则右边 40+ 个字符直接消失（用户实报「模型回复文字没显示全」）。
        String body = "字".repeat(30) + "y".repeat(60);   // 30×2 + 60 = 120 列
        printerOver(sink).assistant(body);

        assertTrue(sink.lines.size() >= 2, "超宽正文应折成多行，实际只有 " + sink.lines.size() + " 行");
        for (String l : sink.lines) {
            assertTrue(dev.tamboui.text.CharWidth.of(l) <= 80,
                    "折行后每行显示宽度必须 ≤ 终端宽 80，实际 %d：%s".formatted(dev.tamboui.text.CharWidth.of(l), l));
            assertTrue(l.startsWith("  "), "续行也应带缩进（悬挂缩进），实际：" + l);
        }
        String joined = String.join("", sink.lines).replace(" ", "");
        assertTrue(joined.contains("字".repeat(30)) && joined.contains("y".repeat(60)),
                "拼回去必须一个字不丢，实际：" + joined);
    }

    @Test
    void streamingLine_wrapsSameAsAssistant() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).streamingLine("z".repeat(100));
        assertTrue(sink.lines.size() >= 2, "流式整行同样要折行");
        for (String l : sink.lines) {
            assertTrue(dev.tamboui.text.CharWidth.of(l) <= 80);
        }
    }

    @Test
    void userBlock_emitsOneLinePerLogicalLineWithIndentPrefix() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).userBlock("第一行\n第二行");

        assertEquals(2, sink.lines.size(), "两条逻辑行 → 两行输出");
        assertTrue(sink.lines.get(0).startsWith("  "), "用户块每行以 INDENT 起");
        assertTrue(sink.lines.get(0).contains("第一行"));
        assertTrue(sink.lines.get(1).contains("第二行"));
    }

    @Test
    void toolStart_nonFileWrite_fallsBackToSingleSummaryLine() {
        RecordingSink sink = new RecordingSink();
        // 2 参构造 → toolName/raw 均为 null → 非文件写入，走单行摘要回退
        printerOver(sink).toolStart(new OutputLine("Bash(ls)", OutputLine.Kind.TOOL_START));

        assertEquals(1, sink.lines.size(), "非文件写入 → 单行摘要");
        assertTrue(sink.lines.get(0).contains("Bash(ls)"));
    }

    @Test
    void line_infoKind_printsTextContent() {
        RecordingSink sink = new RecordingSink();
        printerOver(sink).line(new OutputLine("📊 上下文用量", OutputLine.Kind.INFO));

        assertEquals(1, sink.lines.size());
        assertTrue(sink.lines.get(0).contains("上下文用量"));
    }

    @Test
    void line_subagentKinds_printTextContent() {
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink);
        p.line(new OutputLine("▸ Task(explore) 分析认证模块", OutputLine.Kind.SUBAGENT_START));
        p.line(new OutputLine("    ⎿ Grep \"authenticate\"", OutputLine.Kind.SUBAGENT_TOOL));
        p.line(new OutputLine("  ⎿ 认证走 JWT", OutputLine.Kind.SUBAGENT_END));
        assertEquals(3, sink.lines.size());
        assertTrue(sink.lines.get(0).contains("Task(explore)"));
        assertTrue(sink.lines.get(1).contains("⎿ Grep"));
        assertTrue(sink.lines.get(2).contains("认证走 JWT"));
    }

    // ── fix round I-1 / I-2：cursor 的段级推进与留底原文 ──────────────────

    @Test
    void lineCursor_overlongLine_producesOneSegmentPerNext_andCarriesRawLine() {
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink);
        String longInfo = "info " + "x".repeat(60_000);          // 80 列下 ~750 段

        io.github.javaside.springai.codetui.ui.output.OutputCursor c =
                p.lineCursor(new OutputLine(longInfo, OutputLine.Kind.INFO));

        // 每次 next() 只折一段：第一次调用立即返回第一段（不先把 750 段全建出来），
        // hasNext 无需消费后续内容即可回答。这是「可续折行」的可观测行为面。
        // INFO 走带样式路径（styleFor(INFO)=INFO_LINE），纯文本从 styled().rawContent() 取。
        assertTrue(c.hasNext());
        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine first = c.next();
        assertNotNull(first.styled(), "INFO 有样式（styleFor 非 null），走 styled 路径");
        assertTrue(dev.tamboui.text.CharWidth.of(first.styled().rawContent()) <= 80, "首段宽度 ≤ 终端宽");
        assertTrue(first.styled().rawContent().startsWith("info "));
        assertTrue(c.hasNext(), "还有 ~749 段");

        // 留底原文（I-2）：每个物理段携带其所属逻辑行的原文（带样式的整行 Text），且各段共享同一引用
        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine second = c.next();
        assertTrue(first.raw() instanceof dev.tamboui.text.Text,
                "INFO 的留底原文应为带样式整行 Text（重放时样式不丢），实际 " + first.raw().getClass());
        assertEquals(longInfo, ((dev.tamboui.text.Text) first.raw()).rawContent(),
                "raw 应为折行前的逻辑行原文");
        assertSame(first.raw(), second.raw(), "同一逻辑行的各段共享同一 raw 引用（留底据此去重）");

        // 跑完全部段：内容无损 + 每段 ≤ 80 + raw 恒为原文
        StringBuilder joined = new StringBuilder(first.styled().rawContent()).append(second.styled().rawContent());
        int segments = 2;
        while (c.hasNext()) {
            io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine l = c.next();
            assertNotNull(l, "hasNext 为 true 时 next 不得返回 null");
            joined.append(l.styled().rawContent());
            assertTrue(dev.tamboui.text.CharWidth.of(l.styled().rawContent()) <= 80, "每段宽度 ≤ 终端宽");
            assertSame(first.raw(), l.raw(), "所有段共享同一 raw 引用");
            segments++;
        }
        assertEquals(longInfo, joined.toString(), "全部段拼回必须一字不差（内容无损）");
        assertEquals(751, segments, "80 列下 60k+5 列应折出 751 段（(60005+79)/80）");
    }

    @Test
    void assistantCursor_overlongLine_producesSegmentsWithPreWrapRawText() {
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink);
        String longBody = "字".repeat(30) + "y".repeat(60);      // 120 列 → 内宽 78 下 2 段

        io.github.javaside.springai.codetui.ui.output.OutputCursor c = p.assistantCursor(longBody);

        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine s1 = c.next();
        assertNotNull(s1.styled(), "assistant 走带样式路径");
        assertTrue(s1.styled().rawContent().startsWith("  "), "悬挂缩进");
        assertTrue(dev.tamboui.text.CharWidth.of(s1.styled().rawContent()) <= 78);
        assertNotNull(s1.raw(), "留底原文（Text）必须挂上（I-2）");
        // 原文是折行前的整行（120 列，含缩进 122 列）：宽度信息无损，重放才能按新宽度回流
        assertEquals(122, dev.tamboui.text.CharWidth.of(
                ((dev.tamboui.text.Text) s1.raw()).rawContent()), "raw 宽度 = 整行渲染宽（缩进+120）");

        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine s2 = c.next();
        assertTrue(!c.hasNext(), "120 列在内宽 78 下应折成 2 段");
        assertSame(s1.raw(), s2.raw(), "两段共享同一 raw 引用");
        String joined = s1.styled().rawContent() + s2.styled().rawContent();
        assertTrue(joined.contains("字".repeat(30)) && joined.contains("y".repeat(60)), "拼回不丢内容");
    }
}
