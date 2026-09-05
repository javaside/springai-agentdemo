package dev.anthropic.code.tui.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import dev.anthropic.code.tui.render.MarkdownTable.Alignment;
import dev.tamboui.text.Span;
import dev.tamboui.style.Style;

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

    @Test
    void displayWidth_calculatesCJKCorrectly() {
        // ASCII 字符宽度为 1
        assertEquals(5, MarkdownTable.displayWidth("hello"));

        // CJK 字符宽度为 2
        assertEquals(4, MarkdownTable.displayWidth("你好")); // 2个字符 × 2

        // 混合
        assertEquals(7, MarkdownTable.displayWidth("你好abc")); // 2×2 + 3×1 = 7

        // 空串
        assertEquals(0, MarkdownTable.displayWidth(""));
        assertEquals(0, MarkdownTable.displayWidth(null));
    }

    @Test
    void spansDisplayWidth_measuresAfterJoining() {
        // ASCII spans
        List<Span> spans = List.of(
            Span.raw("hello"),
            Span.styled("world", Style.create().bold())
        );

        assertEquals(10, MarkdownTable.spansDisplayWidth(spans)); // "helloworld"

        // 含 CJK
        spans = List.of(
            Span.raw("你好"),
            Span.raw("abc")
        );
        assertEquals(7, MarkdownTable.spansDisplayWidth(spans)); // 2×2 + 3 = 7
    }

    @Test
    void calculateColumnWidths_findsMaxWidthPerColumn() {
        List<List<String>> rows = List.of(
            List.of("A", "BB", "CCC"),
            List.of("1", "22222", "3")
        );

        int[] widths = MarkdownTable.calculateColumnWidths(rows);
        assertArrayEquals(new int[]{1, 5, 3}, widths);

        // 含 CJK
        rows = List.of(
            List.of("参数", "类型"),
            List.of("codetui.syncOutput", "String")
        );
        widths = MarkdownTable.calculateColumnWidths(rows);
        assertArrayEquals(new int[]{18, 6}, widths); // "codetui.syncOutput"=18, "参数"=4, "类型"=4, "String"=6
    }

    @Test
    void calculateColumnWidths_usesMinWidth4ForEmptyColumn() {
        List<List<String>> rows = List.of(
            List.of("A", "", "C"),
            List.of("1", "", "3")
        );

        int[] widths = MarkdownTable.calculateColumnWidths(rows);
        assertArrayEquals(new int[]{1, 4, 1}, widths); // 空列最小宽度 4
    }

    @Test
    void reduceColumnWidths_reducesWidestColumn() {
        int[] widths = {10, 20, 15};
        int targetTotalWidth = 40; // 当前总宽 = 10+20+15+2×2 = 49

        int[] reduced = MarkdownTable.reduceColumnWidths(widths, targetTotalWidth);

        // 需削减 9 列，每次削最宽列：20→11 (9次)
        assertArrayEquals(new int[]{10, 11, 15}, reduced);
    }

    @Test
    void reduceColumnWidths_respectsMinWidth4() {
        int[] widths = {5, 5, 5};
        int targetTotalWidth = 10; // 当前总宽 = 5+5+5+2×2 = 19

        int[] reduced = MarkdownTable.reduceColumnWidths(widths, targetTotalWidth);

        // 最多削到每列 4：4+4+4+2×2 = 16，无法达到 10
        assertArrayEquals(new int[]{4, 4, 4}, reduced);
    }

    @Test
    void reduceColumnWidths_prefersLowerIndexWhenTied() {
        int[] widths = {10, 10, 5};
        int targetTotalWidth = 20; // 需削减 1 列

        int[] reduced = MarkdownTable.reduceColumnWidths(widths, targetTotalWidth);

        // 并列最宽时削索引小的：索引 0 的列被削
        assertArrayEquals(new int[]{9, 10, 5}, reduced);
    }

    @Test
    void reduceColumnWidths_stopsAtIterationLimit() {
        // 模拟极端输入：单列超宽
        int[] widths = {65000}; // 需削减约 64996 次才到最小宽 4
        int targetTotalWidth = 4;

        int[] reduced = MarkdownTable.reduceColumnWidths(widths, targetTotalWidth);

        // 超过 10000 次迭代，返回 null（触发 fallback）
        assertNull(reduced);
    }
}
