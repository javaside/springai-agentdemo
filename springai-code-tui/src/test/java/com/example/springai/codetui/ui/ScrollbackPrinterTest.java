package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
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
        printerOver(sink).welcome("deepseek-v4-flash");

        // 顶/底边框各 1 + 6 行内容 + 末尾 1 空行 = 9 行
        assertEquals(9, sink.lines.size(), "欢迎横幅应输出 9 行");
        assertTrue(sink.lines.get(0).startsWith("╭"), "首行应为圆角上边框");
        assertTrue(sink.lines.get(7).startsWith("╰"), "倒数第二行应为圆角下边框");
        assertEquals("", sink.lines.get(8), "末行应为留白空行");
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
}
