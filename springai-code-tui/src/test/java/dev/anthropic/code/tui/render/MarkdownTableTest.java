package dev.anthropic.code.tui.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import dev.anthropic.code.tui.render.MarkdownTable.Alignment;

public class MarkdownTableTest {

    @Test
    void looksLikeRow_recognizesTableRow() {
        assertTrue(MarkdownTable.looksLikeRow("| a | b |"));
        assertTrue(MarkdownTable.looksLikeRow("  | a | b |")); // 前导空格
        assertFalse(MarkdownTable.looksLikeRow("a | b"));      // 无前导竖线
        assertFalse(MarkdownTable.looksLikeRow(""));
        assertFalse(MarkdownTable.looksLikeRow(null));         // null 守卫
    }

    @Test
    void isSeparator_recognizesSeparatorRow() {
        assertTrue(MarkdownTable.isSeparator("|------|------|"));
        assertTrue(MarkdownTable.isSeparator("| :--- | ---: |"));   // 对齐冒号
        assertTrue(MarkdownTable.isSeparator("| :--: | ---- |"));   // 居中
        assertTrue(MarkdownTable.isSeparator("|  -  |  --  |"));   // 空格
        assertFalse(MarkdownTable.isSeparator("| a | b |"));        // 含非法字符
        assertFalse(MarkdownTable.isSeparator("|     |     |"));    // 无破折号
        assertFalse(MarkdownTable.isSeparator(null));
    }

    @Test
    void alignments_parsesAlignmentFromSeparator() {
        assertEquals(List.of(Alignment.LEFT, Alignment.LEFT),
                     MarkdownTable.alignments("|------|------|"));
        assertEquals(List.of(Alignment.LEFT, Alignment.RIGHT),
                     MarkdownTable.alignments("| :--- | ---: |"));
        assertEquals(List.of(Alignment.CENTER, Alignment.LEFT),
                     MarkdownTable.alignments("| :--: | ---- |"));
        assertEquals(List.of(), MarkdownTable.alignments(null));
    }

    @Test
    void parseCells_splitsAndTrimsCorrectly() {
        List<String> cells = MarkdownTable.parseCells("| a | b | c |");
        assertEquals(List.of("a", "b", "c"), cells);

        // 首尾空单元格丢弃
        cells = MarkdownTable.parseCells("| a | b |");
        assertEquals(List.of("a", "b"), cells);

        // 空表格行
        cells = MarkdownTable.parseCells("||");
        assertEquals(List.of(), cells);
    }

    @Test
    void parseCells_handlesEscaping() {
        // \| 转义为字面 |
        List<String> cells = MarkdownTable.parseCells("| a\\|b | c |");
        assertEquals(List.of("a|b", "c"), cells);

        // \\| 是字面 \ + 分隔符
        cells = MarkdownTable.parseCells("| a\\\\| b |");
        assertEquals(List.of("a\\", "b"), cells);

        // \\\| 是字面 \|
        cells = MarkdownTable.parseCells("| a\\\\\\| |");
        assertEquals(List.of("a\\|"), cells);

        // 行末单 \ 视为字面
        cells = MarkdownTable.parseCells("| a\\ |");
        assertEquals(List.of("a\\"), cells);
    }

    @Test
    void adjustCellCount_handlesFewerCells() {
        List<String> header = List.of("A", "B", "C");
        List<String> row = List.of("1", "2");

        List<String> adjusted = MarkdownTable.adjustCellCount(row, header.size());
        assertEquals(List.of("1", "2", ""), adjusted);
    }

    @Test
    void adjustCellCount_handlesMoreCells() {
        List<String> header = List.of("A", "B");
        List<String> row = List.of("1", "2", "3", "4");

        // 多出来的并入最后一列（用 " | " 拼接）
        List<String> adjusted = MarkdownTable.adjustCellCount(row, header.size());
        assertEquals(List.of("1", "2 | 3 | 4"), adjusted);
    }
}
