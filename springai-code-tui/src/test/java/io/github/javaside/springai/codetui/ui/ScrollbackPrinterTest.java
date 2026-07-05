package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ScrollbackPrinter：喂输入，断言下沉到 scrollback 的行（经 Sink 接缝捕获，不经 InlineDisplay 的 ANSI 裁剪）。 */
class ScrollbackPrinterTest {

    /** 记录型输出接缝：把每次 println 的内容收进 lines（Text 取 rawContent 纯文本）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    /** 造一个 printer：固定 80 列，不折行（wrap 返回原行），root=/work。 */
    private static ScrollbackPrinter printerOver(RecordingSink sink) {
        return new ScrollbackPrinter(sink, Path.of("/work"), () -> 80, (s, w) -> List.of(s));
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
}
