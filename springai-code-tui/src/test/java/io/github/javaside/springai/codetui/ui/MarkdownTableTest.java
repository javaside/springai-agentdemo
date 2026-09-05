package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import io.github.javaside.springai.codetui.ui.MarkdownTable.Alignment;
import dev.tamboui.text.Span;
import dev.tamboui.style.Style;
import dev.tamboui.style.Modifier;

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

        // 需削减 9 列，按"每次削当前最宽列"规则：
        // 20→15（削5次索引1），15并列时轮流削减（索引1和2各削2次）
        assertArrayEquals(new int[]{10, 13, 13}, reduced);
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
        int targetTotalWidth = 28; // 当前总宽 = 10+10+5+2×2 = 29，需削减 1

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

    @Test
    void wrapCellContent_breaksAtSpaces() {
        List<String> lines = MarkdownTable.wrapCellContent("hello world foo", 8);

        // 优先在空格处断："hello" (5) + " world" (6，超8) → 断在 "hello" 后
        assertEquals(List.of("hello", "world", "foo"), lines);
    }

    @Test
    void wrapCellContent_hardBreaksLongWords() {
        // 单词超列宽，按显示宽度硬切
        List<String> lines = MarkdownTable.wrapCellContent("verylongword", 5);

        assertEquals(List.of("veryl", "ongwo", "rd"), lines);
    }

    @Test
    void wrapCellContent_handlesCJKWithoutSpaces() {
        // CJK 无空格，硬切（不切半个宽字符）
        List<String> lines = MarkdownTable.wrapCellContent("你好世界", 4); // 每行最多 4 列 = 2 个 CJK 字符

        assertEquals(List.of("你好", "世界"), lines);
    }

    @Test
    void wrapCellContent_doesNotSplitWideChar() {
        // 硬切时不切半个宽字符
        List<String> lines = MarkdownTable.wrapCellContent("a你b", 2); // 2 列容不下 "你" (2列)

        // "a" (1列) + "你" (2列，超) → "a" 单独一行
        // "你" (2列) + "b" (1列) → "你" 单独一行，"b" 下一行
        assertEquals(List.of("a", "你", "b"), lines);
    }

    @Test
    void render_fallsBackOnNarrowTerminal() {
        List<String> block = List.of(
            "| A | B |",
            "|---|---|",
            "| 1 | 2 |"
        );

        // inner < 6 直接退回原样（走 renderInline）
        List<List<Span>> result = MarkdownTable.render(block, 5);

        // 期望 3 行原样输出（带内联样式）
        assertEquals(3, result.size());
        // 第一行应该包含原始内容（简化验证：检查行数）
    }

    @Test
    void render_fallsBackWhenTooManyColumns() {
        // 列数太多，连最小宽度都装不下
        List<String> block = List.of(
            "| A | B | C | D | E | F | G | H |",
            "|---|---|---|---|---|---|---|---|"
        );

        int inner = 20; // 8列 × 4 + 7×2 = 46 > 20
        List<List<Span>> result = MarkdownTable.render(block, inner);

        // 退回原样
        assertEquals(2, result.size());
    }

    @Test
    void render_handlesNullInput() {
        assertDoesNotThrow(() -> MarkdownTable.render(null, 80));
        assertEquals(List.of(), MarkdownTable.render(null, 80));
    }

    @Test
    void render_handlesEmptyBlock() {
        assertEquals(List.of(), MarkdownTable.render(List.of(), 80));
    }

    @Test
    void render_parsesHeaderAndDataRows() {
        List<String> block = List.of(
            "| A | B |",
            "|---|---|",
            "| 1 | 2 |",
            "| 3 | 4 |"
        );

        List<List<Span>> result = MarkdownTable.render(block, 80);

        // 表头 + 分隔线 + 2 行数据 = 4 行
        assertEquals(4, result.size());

        // 验证表头加粗（第一行应包含 BOLD 样式）
        assertTrue(result.get(0).stream().anyMatch(s ->
            s.style().effectiveModifiers().contains(Modifier.BOLD)));
    }

    @Test
    void render_adjustsCellCountToHeader() {
        List<String> block = List.of(
            "| A | B | C |",
            "|---|---|---|",
            "| 1 | 2 |",           // 少一列
            "| 3 | 4 | 5 | 6 |"   // 多一列
        );

        List<List<Span>> result = MarkdownTable.render(block, 80);

        // 应该成功排版，不抛异常
        assertEquals(4, result.size());
    }

    @Test
    void render_reducesWidthWhenExceedsInner() {
        List<String> block = List.of(
            "| VeryLongHeaderAAAAAAAA | VeryLongHeaderBBBBBBBB | VeryLongHeaderCCCCCCCC |",
            "|------------------------|------------------------|------------------------|",
            "| Data1 | Data2 | Data3 |"
        );

        int inner = 40; // 远小于表格自然宽度
        List<List<Span>> result = MarkdownTable.render(block, inner);

        // 应该削列后输出，不是退回原样
        assertTrue(result.size() >= 3);

        // 验证每行不超宽
        for (List<Span> line : result) {
            int width = MarkdownTable.spansDisplayWidth(line);
            assertTrue(width <= inner, "Line width " + width + " exceeds inner " + inner);
        }
    }

    @Test
    void render_wrapsLongCellContent() {
        List<String> block = List.of(
            "| Short | Long |",
            "|-------|------|",
            "| A | This is a very long content that should be wrapped |"
        );

        int inner = 20;
        List<List<Span>> result = MarkdownTable.render(block, inner);

        // 格内折行后应该多于 3 行
        assertTrue(result.size() > 3);
    }

    @Test
    void render_fallsBackWhenOutputExceeds600Lines() {
        List<String> block = new java.util.ArrayList<>();
        block.add("| A |");
        block.add("|---|");

        // 构造能产生 >600 行的输入
        StringBuilder longCell = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longCell.append("word ");
        }
        block.add("| " + longCell.toString() + " |");

        List<List<Span>> result = MarkdownTable.render(block, 4); // 最小宽度，最大折行

        // 应该退回原样（3 行），不是 >600 行
        assertEquals(3, result.size());
    }
}
