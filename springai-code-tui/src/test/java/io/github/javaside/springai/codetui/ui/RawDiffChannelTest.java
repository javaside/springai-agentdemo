package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pushRawLines + Kind.DIFF：UI 主动输出通道（不受 turnId 门控、不走 markdown）。 */
class RawDiffChannelTest {

    /** 照抄 ScrollbackPrinterTest 的记录型 Sink（两方法接口——不是函数式接口，不能 lambda）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line) { lines.add(line.rawContent()); }
        @Override public void println(String s) { lines.add(s); }
    }

    @Test
    void pushRawLines_appendsAllLinesInOrder_regardlessOfAcceptingTurnId() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(7L);   // 有活跃回合——门控管的是模型流，UI 主动输出不受控，此处钉住该契约

        s.pushRawLines(List.of("+新增", "-删除", " context"), OutputLine.Kind.DIFF);

        List<OutputLine> lines = s.drainPending();
        assertEquals(3, lines.size(), "三行全进 pending");
        assertEquals("+新增", lines.get(0).text());
        assertEquals(OutputLine.Kind.DIFF, lines.get(1).kind());
        assertEquals(" context", lines.get(2).text(), "逐字不改写（前导空格保留）");
    }

    @Test
    void emptyListIsNoop_publishStillSafe() {
        ConversationState s = new ConversationState();
        s.pushRawLines(List.of(), OutputLine.Kind.DIFF);
        assertEquals(0, s.drainPending().size());
    }

    @Test
    void diffKindRendersVerbatim_notMarkdown() {
        // 正向断言（禁恒真写法）：DIFF 行经 printer.line() 落到 Sink 的文本逐字保留——
        // `#` 不被吃、`- xxx` 不变列表项、`|` 不触发表格攒块。若 DIFF 被误路由到 markdown
        // 游标，`-` 行会被改写成 "• "、`|` 行进表格缓冲不输出，断言必红。
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = new ScrollbackPrinter(sink, Path.of("/work"), () -> 80);   // 三参构造（同 ScrollbackPrinterTest）
        p.line(new OutputLine("- 删掉的行 #不是标题", OutputLine.Kind.DIFF));
        p.line(new OutputLine("| a | b |", OutputLine.Kind.DIFF));
        p.line(new OutputLine("+新增行", OutputLine.Kind.DIFF));

        String all = String.join("\n", sink.lines);
        assertTrue(all.contains("- 删掉的行 #不是标题"), "`-` 与 `#` 原样存活，实际: " + all);
        assertTrue(all.contains("| a | b |"), "`|` 不触发表格，实际: " + all);
        assertTrue(all.contains("+新增行"), "`+` 原样存活，实际: " + all);
    }
}
