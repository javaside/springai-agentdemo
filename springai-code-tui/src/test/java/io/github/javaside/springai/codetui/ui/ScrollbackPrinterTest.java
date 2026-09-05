package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 造一个指定终端宽的 printer（表格排版对宽度敏感，120 / 80 两档都要验）。 */
    private static ScrollbackPrinter printerOver(RecordingSink sink, int width) {
        return new ScrollbackPrinter(sink, Path.of("/work"), () -> width);
    }

    /** 把游标抽干，返回每个物理段的纯文本。 */
    private static List<String> drain(io.github.javaside.springai.codetui.ui.output.OutputCursor cursor) {
        List<String> out = new ArrayList<>();
        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine line;
        while ((line = cursor.next()) != null) {
            out.add(line.styled().rawContent());
        }
        return out;
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
        // 段宽上限 = 终端宽 80（缩进 2 + 内容 ≤ inner 78），不是 inner 78——fix round 2 / 复审 N-1：
        // 旧「先缩进后折行」把整行（含缩进）按 inner 折，段宽上限被压到 inner 且续段从第 0 列起；
        // wrap-then-indent 后缩进在折行预算之外，每段 = 缩进 2 + ≤78 列内容。
        assertTrue(dev.tamboui.text.CharWidth.of(s1.styled().rawContent()) <= 80,
                "段宽上限应为终端宽 80（缩进 2 + 内容 ≤78），实际 "
                        + dev.tamboui.text.CharWidth.of(s1.styled().rawContent()));
        assertNotNull(s1.raw(), "留底原文（Text）必须挂上（I-2）");
        // 原文是折行前的整行（120 列，含缩进 122 列）：宽度信息无损，重放才能按新宽度回流
        assertEquals(122, dev.tamboui.text.CharWidth.of(
                ((dev.tamboui.text.Text) s1.raw()).rawContent()), "raw 宽度 = 整行渲染宽（缩进+120）");

        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine s2 = c.next();
        assertTrue(!c.hasNext(), "120 列在内宽 78 下应折成 2 段");
        assertTrue(s2.styled().rawContent().startsWith("  "),
                "折行续段也必须带悬挂缩进（复审 N-1 的核心缺陷面），实际：" + s2.styled().rawContent());
        assertTrue(dev.tamboui.text.CharWidth.of(s2.styled().rawContent()) <= 80, "续段宽度 ≤ 终端宽");
        assertSame(s1.raw(), s2.raw(), "两段共享同一 raw 引用");
        // 拼回校验：逐段剥掉缩进前缀再拼——缩进是排版前缀不是内容；wrap-then-indent 后拆分点在
        // 内容流上（18y / 42y），直接拼物理段会把第二段缩进夹进 y 流（contains 必假）。精确相等
        // 比此前的 contains 断言更强（一字不差 vs 子串）。
        assertEquals(longBody,
                deindent(s1.styled().rawContent()) + deindent(s2.styled().rawContent()),
                "两段去缩进后拼回必须一字不差");
    }

    // ── fix round 2（复审 N-1）：折行续段的悬挂缩进 ──────────────────────

    /** 剥掉物理段的悬挂缩进前缀（INDENT=2 空格；测试正文本身不含连续空格，安全）。 */
    private static String deindent(String physicalSegment) {
        return physicalSegment.startsWith("  ") ? physicalSegment.substring(2) : physicalSegment;
    }

    /**
     * 跑完一个 cursor，收集每段纯文本（消费循环与 {@code PhysicalOutputQueue.drain} 一致；
     * next 返回 null 视为结束——游标契约里 hasNext 假阳性由 drain 兜底）。
     */
    private static List<String> drainCursorTexts(
            io.github.javaside.springai.codetui.ui.output.OutputCursor c) {
        List<String> out = new ArrayList<>();
        while (c.hasNext()) {
            io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine l = c.next();
            if (l == null) break;
            out.add(l.styled() != null ? l.styled().rawContent() : l.plain());
        }
        return out;
    }

    @Test
    void assistantCursor_everyWrappedSegmentCarriesHangingIndent_withinTerminalWidth() {
        // 80 列终端 → 内宽 78。中文占 2 列：这段 ~108 列显示宽的正文折出 2 段——中文正文在 80 列
        // 终端几乎必然折行（复审 N-1 的生产主路径）。折行源必须是<b>未缩进</b>的渲染结果按内宽折、
        // 每段前置缩进；旧实现把「已缩进整行」当折行源：首段吃 2 列预算、续段从第 0 列起。
        ScrollbackPrinter p = printerOver(new RecordingSink());
        String body = "模型回复的中文正文在八十列终端几乎必然折行，续段必须保持悬挂缩进对齐首段内容，不能从第零列开始。";
        List<String> segs = drainCursorTexts(p.assistantCursor(body));

        assertTrue(segs.size() >= 2, "前置：正文在内宽 78 下应折出 ≥2 段，实际 " + segs.size() + "：" + segs);
        for (int i = 0; i < segs.size(); i++) {
            assertTrue(segs.get(i).startsWith("  "),
                    "第 " + i + " 段（含折行续段）必须带悬挂缩进，实际：" + segs.get(i));
            assertTrue(dev.tamboui.text.CharWidth.of(segs.get(i)) <= 80,
                    "第 " + i + " 段显示宽度必须 ≤ 终端宽 80（缩进 2 + 内容 ≤78），实际 "
                            + dev.tamboui.text.CharWidth.of(segs.get(i)) + "：" + segs.get(i));
        }
        // 段间拼回（去缩进）必须一字不差：缩进是排版前缀，不是内容
        assertEquals(body, String.join("", segs).replace("  ", ""),
                "全部段拼回（去缩进）必须等于原文");
    }

    @Test
    void assistantCursor_manySegmentChineseBody_everySegmentIndented() {
        // 更长的中文正文 → 更多折行段（≥5），把「每一段」的断言从 2 段扩到多段；
        // 中文无空格断点，整段按宽度硬切——正是 80 列终端上的真实形态。
        // 长度标定：整句 64 个 CJK 字符（含标点）= 128 显示列，×3 = 384 列，内宽 78 下折 5 段。
        // ⚠ repeat 必须括在整句上：`"A" + "B".repeat(3)` 只重复 B 句（~220 列，折 3 段），
        //   `>= 5` 前置会假红——上一轮残留草稿正是这个错。
        ScrollbackPrinter p = printerOver(new RecordingSink());
        String body = ("这是一段很长很长的中文正文，用于制造五个以上的折行段，验证每一个续段都带悬挂缩进、"
                + "每段宽度都不超过终端宽度上限，且内容无损拼回。").repeat(3);
        List<String> segs = drainCursorTexts(p.assistantCursor(body));

        assertTrue(segs.size() >= 5, "前置：长中文正文应折出 ≥5 段，实际 " + segs.size());
        for (int i = 0; i < segs.size(); i++) {
            assertTrue(segs.get(i).startsWith("  "), "第 " + i + " 段必须带悬挂缩进：" + segs.get(i));
            assertTrue(dev.tamboui.text.CharWidth.of(segs.get(i)) <= 80, "第 " + i + " 段超宽");
        }
        assertEquals(body, String.join("", segs).replace("  ", ""), "拼回一字不差");
    }

    @Test
    void assistantCursor_mixedWidthAndStreamingSegmentsKeepHangingIndent() {
        ScrollbackPrinter p = printerOver(new RecordingSink());
        // 中英混合 + 跨样式拆分（**bold** 拆出多 span，样式跨拆分点保留）+ 窄终端主路径。
        // 长度标定（显示宽）：10(宽字)+8(mixed宽)+3×2+3×2+80+4+100 = ~208 列 → 内宽 78 下 ≥3 段。
        String logical = "中英mixed宽**加粗段**" + "字".repeat(40) + "tail" + "z".repeat(100);

        List<String> segs = drainCursorTexts(p.assistantCursor(logical));
        assertTrue(segs.size() >= 3, "前置：混合宽字符长行应折出 ≥3 段，实际 " + segs.size());
        for (int i = 0; i < segs.size(); i++) {
            assertTrue(segs.get(i).startsWith("  "), "第 " + i + " 段必须带悬挂缩进：" + segs.get(i));
            assertTrue(dev.tamboui.text.CharWidth.of(segs.get(i)) <= 80, "第 " + i + " 段超宽");
        }
        assertEquals(logical.replace("**", ""),
                String.join("", segs).replace("  ", "").replace("**", ""),
                "拼回不丢内容（markdown 标记剥掉后比对）");

        // 流式完整行与 assistant 同一条 MdLineCursor：同语义钉一份
        List<String> streamingSegs = drainCursorTexts(
                p.streamingLinesCursor(List.of("s".repeat(200))));
        assertEquals(3, streamingSegs.size(), "200 列在内宽 78 下应折成 3 段");
        for (int i = 0; i < streamingSegs.size(); i++) {
            assertTrue(streamingSegs.get(i).startsWith("  "),
                    "流式第 " + i + " 段必须带悬挂缩进：" + streamingSegs.get(i));
            assertTrue(dev.tamboui.text.CharWidth.of(streamingSegs.get(i)) <= 80);
        }
    }

    @Test
    void assistantCursor_wrapThenIndent_matchesOneShotPrintWrappedExactly() {
        // 「与一次性方法逐字同源」的钉子：同一输入、同一 md 状态链（两个 printer 各自从 reset 态起），
        // cursor 产出的物理行序列必须与一次性 assistant()（= printWrapped，wrap-then-indent）完全相等。
        String[] bodies = {
                "短行",
                "字".repeat(30) + "y".repeat(60),
                "模型回复的中文正文在八十列终端几乎必然折行。" + "续段要对齐。" + "q".repeat(100),
                "" };
        for (String body : bodies) {
            RecordingSink oneShotSink = new RecordingSink();
            printerOver(oneShotSink).assistant(body);          // 一次性路径（printWrapped）
            List<String> cursorSegs = drainCursorTexts(
                    printerOver(new RecordingSink()).assistantCursor(body));
            assertEquals(oneShotSink.lines, cursorSegs,
                    "cursor 与一次性 printWrapped 分家（body 前缀="
                            + body.substring(0, Math.min(12, body.length())) + "…）——续段缩进/宽度语义必须一致");
        }
    }

    @Test
    void assistantCursor_outputsAlignedTable() {
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink);

        String input = "| Name | Age |\n|------|-----|\n| Alice | 30 |\n";
        List<String> output = drain(p.assistantCursor(input));

        // 表头 + 分隔线 + 数据 + 末尾空行
        assertEquals(4, output.size(), "表格应输出 3 行 + 收尾空行");
        assertTrue(output.stream().anyMatch(l -> l.contains("Name")), "应包含表头");
        assertTrue(output.stream().anyMatch(l -> l.contains("Alice")), "应包含数据行");
    }

    @Test
    void mdCursor_emitsOneSegmentPerTableRow_noSecondaryWrapping() {
        // §3.1 硬不变量：排出的行必须 ≤ inner，否则被 SegmentedWrap 撕成两段、续段再加一层缩进。
        // 只断言「每行 ≤ 终端宽」会假绿（宽 inner+1 的行照样 ≤ 终端宽），必须断言段数 == 表格行数。
        List<String> block = List.of(
                "| 参数 | 类型 | 默认值 | 说明 |",
                "|------|------|--------|------|",
                "| codetui.syncOutput | String | auto | 控制是否使用终端同步输出扩展，取值 never/auto。|",
                "| codetui.hardwareCursor | String | auto | 控制硬件光标可见性，IME 路径需要 always。|");

        for (int width : new int[]{120, 80}) {
            RecordingSink sink = new RecordingSink();
            ScrollbackPrinter p = printerOver(sink, width);
            List<String> out = drain(p.streamingLinesCursor(concat(block, "")));

            int expected = MarkdownTable.render(block, width - 2).size() + 1;   // +1 = 收尾空行
            assertEquals(expected, out.size(),
                    "%d 列：物理段数必须等于表格行数（二次折行必须是 no-op），实际 %s".formatted(width, out));
            for (String l : out) {
                assertTrue(dev.tamboui.text.CharWidth.of(l) <= width,
                        "%d 列下行宽 %d 超限：%s".formatted(width, dev.tamboui.text.CharWidth.of(l), l));
            }
        }
    }

    @Test
    void mdCursor_keepsColumnStartsAligned() {
        List<String> block = List.of(
                "| 参数 | 类型 |",
                "|------|------|",
                "| a | String |",
                "| 中文键 | int |");

        RecordingSink sink = new RecordingSink();
        List<String> out = drain(printerOver(sink, 80).streamingLinesCursor(concat(block, "")));

        // 第 2 列的起点显示偏移在表头与所有数据行上必须一致
        int headerStart = secondColumnStart(out.get(0));
        assertTrue(headerStart > 0, "应能定位表头第 2 列：" + out.get(0));
        for (int i = 2; i < out.size() - 1; i++) {
            assertEquals(headerStart, secondColumnStart(out.get(i)),
                    "第 2 列起点必须与表头一致：" + out.get(i));
        }
    }

    @Test
    void mdCursor_wrappedLineKeepsAllSegmentsWhenMoreLogicalLinesFollow() {
        // 一批流式行里，前一条逻辑行的续段必须先吐完再推进到下一条逻辑行。
        // 推进抢在续段之前 = 长行只剩第一段，其余静默消失。
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink, 80);

        List<String> out = drain(p.streamingLinesCursor(List.of("y".repeat(200), "第二行", "第三行")));

        String joined = String.join("", out);
        assertEquals(200, joined.chars().filter(c -> c == 'y').count(),
                "长行折出的每一段都要落地，一个字不丢：" + out);
        assertTrue(joined.contains("第二行") && joined.contains("第三行"), "后续逻辑行也不能丢");
    }

    @Test
    void mdCursor_200RowBatchStartingWithPipeLosesNothing() {
        // §3.6 的雷：feed 返回空列表 ≠ 游标耗尽。next() 不内部循环时，首行是 `|` 的一批
        // 会让 next() 立刻返回 null → 整个游标被丢弃 → 后面最多 299 条逻辑行永久消失。
        List<String> rows = new ArrayList<>();
        rows.add("| k | v |");
        rows.add("|---|---|");
        for (int i = 0; i < 197; i++) {
            rows.add("| k" + i + " | v" + i + " |");
        }
        rows.add("");   // 非表格行触发整块输出（199 行缓冲，未越 200 上限）

        RecordingSink sink = new RecordingSink();
        List<String> out = drain(printerOver(sink, 80).streamingLinesCursor(rows));

        String joined = String.join("\n", out);
        for (int i = 0; i < 197; i++) {
            assertTrue(joined.contains("k" + i), "第 " + i + " 条数据行丢了");
        }
    }

    @Test
    void tableFlushCursor_emitsBufferedBlockAndIsIdempotent() {
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink, 80);

        // 只喂表头 + 分隔行 + 数据行，不喂结束行 → 整块压在缓冲里
        List<String> streamed = drain(p.streamingLinesCursor(List.of("| k | v |", "|---|---|", "| a | b |")));
        assertEquals(List.of(), streamed, "块未结束时游标不应吐出任何东西");
        assertTrue(p.hasBufferedTable(), "整块应压在缓冲里");

        List<String> flushed = drain(p.tableFlushCursor());
        assertEquals(3, flushed.size(), "flush 应排出表头 + 分隔线 + 数据行");
        assertFalse(p.hasBufferedTable());

        assertEquals(List.of(), drain(p.tableFlushCursor()), "空缓冲 flush 必须幂等");
    }

    @Test
    void tableFlushCursor_sharesOneMultiLineRawForResizeReplay() {
        // record() 按引用身份去重、且只跟上一条比：N 行共享同一个<b>单行</b> raw 会让
        // resize 重放后历史表格只剩 1 行。整块一个多行 Text 才对（只占 1 条 SCROLL_TAIL_CAP 配额）。
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = printerOver(sink, 80);
        drain(p.streamingLinesCursor(List.of("| k | v |", "|---|---|", "| a | b |")));

        io.github.javaside.springai.codetui.ui.output.OutputCursor cursor = p.tableFlushCursor();
        List<Object> raws = new ArrayList<>();
        io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine line;
        while ((line = cursor.next()) != null) {
            raws.add(line.raw());
        }

        assertEquals(3, raws.size());
        for (Object raw : raws) {
            assertSame(raws.get(0), raw, "整块必须共享同一个 raw 实例（去重后只记 1 条）");
        }
        assertEquals(3, ((Text) raws.get(0)).lines().size(),
                "共享的 raw 必须是多行 Text，重放才能还原 3 行");
    }

    private static List<String> concat(List<String> rows, String extra) {
        List<String> all = new ArrayList<>(rows);
        all.add(extra);
        return all;
    }

    /** 第 2 列起点的显示偏移：跳过缩进与第 1 列，找到「2 空格 + 非空格」的位置。 */
    private static int secondColumnStart(String line) {
        String body = line.substring(2);   // 去掉行首缩进
        int idx = body.indexOf("  ");
        if (idx < 0) return -1;
        int j = idx;
        while (j < body.length() && body.charAt(j) == ' ') j++;
        return dev.tamboui.text.CharWidth.of(body.substring(0, j));
    }
}
