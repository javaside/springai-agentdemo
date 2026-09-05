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

        // 全空行：外侧两个空串丢掉后剩中间那一个空格子（`||` = 一个空列）
        cells = MarkdownTable.parseCells("||");
        assertEquals(List.of(""), cells);
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
    void displayWidth_agreesWithCharWidthOracle() {
        // 排出来的行随后要过 SegmentedWrap（用 CharWidth 测量）。两套口径不一致时，
        // 本类算 ≤ inner 的行会被 SegmentedWrap 判超宽撕成两段——§3.1 硬不变量的失效形态。
        for (String s : List.of(
                "控制是否使用同步输出。",   // 全角句号 U+3002，CJK Symbols and Punctuation
                "、《》「」【】",           // 同区间的其它标点
                "한국어",                   // Hangul Syllables
                "ｱｲｳ",                     // 半角片假名（CharWidth 算 1，别算成 2）
                "ㄅㄆㄇ",                   // 注音符号
                "é",                 // 组合字符（CharWidth 算 0 宽）
                "hello",
                "你好abc")) {
            assertEquals(dev.tamboui.text.CharWidth.of(s), MarkdownTable.displayWidth(s),
                    "displayWidth 必须与 CharWidth 逐字一致，否则行宽算错：" + s);
        }
    }

    @Test
    void wrapCellContent_matchesSegmentedWrapWhenNoSpaces() {
        // §5.1 交叉单测：无空格可断时，格内折行必须与 SegmentedWrap 逐字相等
        // （仓里第三套折行语义，分家就是「打出去的行 ≠ 留底重放的行」）。
        for (String content : List.of("你好世界再见天地", "abcdefghijklmnop", "a你b好c世d界", "混排abc中文def",
                                     "一句话。又一句话。", "한국어테스트")) {
            for (int width : new int[]{3, 4, 5, 8}) {
                List<String> mine = MarkdownTable.wrapCellContent(content, width);
                List<String> theirs = new java.util.ArrayList<>();
                SegmentedWrap.Plain p = SegmentedWrap.plain(content, width);
                while (p.hasNextSegment()) {
                    theirs.add(p.nextSegment());
                }
                assertEquals(theirs, mine,
                        "无空格内容在宽度 " + width + " 下必须与 SegmentedWrap 一致：" + content);
            }
        }
    }

    @Test
    void parseCells_keepsInteriorEmptyCells() {
        // 只丢首尾的空单元格；中间的空格子是真实列，丢了会让后面的内容整体左移一列
        assertEquals(List.of("a", "", "c"), MarkdownTable.parseCells("| a |  | c |"));
        assertEquals(List.of("", "b"), MarkdownTable.parseCells("|  | b |"));
        assertEquals(List.of("a", ""), MarkdownTable.parseCells("| a |  |"));
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
    void render_everyRowFitsInner_withCjkPunctuation() {
        // §3.1 硬不变量：排出来的每一行（未加缩进）显示宽度 ≤ inner。
        // 全角标点是最容易破这条的输入——列宽算窄 1 列，行就会被 SegmentedWrap 撕开。
        List<String> block = List.of(
                "| 参数 | 说明 |",
                "|------|------|",
                "| a | " + "中".repeat(20) + "。".repeat(8) + " |",
                "| bb | 取值 never/auto，默认 auto。|");

        int inner = 78;
        List<List<Span>> result = MarkdownTable.render(block, inner);

        for (List<Span> row : result) {
            StringBuilder sb = new StringBuilder();
            row.forEach(s -> sb.append(s.content()));
            assertTrue(dev.tamboui.text.CharWidth.of(sb.toString()) <= inner,
                    "行宽 %d 超过 inner %d：%s".formatted(
                            dev.tamboui.text.CharWidth.of(sb.toString()), inner, sb));
        }
    }

    @Test
    void render_separatorLineSpansExactTableWidth() {
        // 分隔线长度 = 表格总宽（Σ列宽 + 2×(列数−1)），不是 inner、也不是表头行的实际宽度
        List<String> block = List.of(
                "| 参数 | 类型 |",
                "|------|------|",
                "| abc | String |");

        List<List<Span>> result = MarkdownTable.render(block, 78);

        // 列宽：col0 = max("参数"=4, "abc"=3) = 4；col1 = max("类型"=4, "String"=6) = 6
        // 总宽 = 4 + 6 + 2 = 12
        String sep = result.get(1).stream().map(Span::content).reduce("", String::concat);
        assertEquals("─".repeat(12), sep, "分隔线必须与表格总宽等长");
    }

    @Test
    void render_inlineMarkupNotCountedInWidth() {
        // 列宽按 spans 内容拼接后测量：`**粗**` 的星号不算宽度
        List<String> block = List.of(
                "| A | B |",
                "|---|---|",
                "| **bold** | x |");

        List<List<Span>> result = MarkdownTable.render(block, 78);

        String row = result.get(2).stream().map(Span::content).reduce("", String::concat);
        assertFalse(row.contains("**"), "内联标记应被渲染掉，不应出现在输出里：" + row);
        // col0 宽 = max("A"=1, "bold"=4) = 4；col1 宽 = 1 → 数据行 = "bold" + 2 空格 + "x"
        assertEquals("bold  x", row);
    }

    @Test
    void render_appliesAlignment() {
        List<String> block = List.of(
                "| L | C | R |",
                "| :--- | :--: | ---: |",
                "| a | b | c |");

        List<List<Span>> result = MarkdownTable.render(block, 78);
        String row = result.get(2).stream().map(Span::content).reduce("", String::concat);

        // 每列宽 1（表头与数据都是 1 列宽），无补白空间 → 三列各 1 字符、列间 2 空格
        assertEquals("a  b  c", row);

        // 换一个有补白空间的：右对齐靠前置补白
        block = List.of(
                "| head | tail |",
                "| ---: | ---: |",
                "| a | b |");
        result = MarkdownTable.render(block, 78);
        row = result.get(2).stream().map(Span::content).reduce("", String::concat);
        assertEquals("   a     b", row, "右对齐前置补白；最后一列尾部不补白");
    }

    @Test
    void render_singleColumnTable() {
        List<String> block = List.of("| 参数 |", "|------|", "| abc |");

        List<List<Span>> result = MarkdownTable.render(block, 78);

        assertEquals(3, result.size());
        String sep = result.get(1).stream().map(Span::content).reduce("", String::concat);
        // 单列：列间宽退化为 0，总宽 = 列宽 = max("参数"=4, "abc"=3) = 4
        assertEquals("─".repeat(4), sep);
    }

    @Test
    void render_fallsBackWhenSeparatorHasFewerColumnsThanHeader() {
        // GFM 识别规则：分隔行列数 < 表头列数 → 不是表格
        List<String> block = List.of(
                "| A | B | C |",
                "|---|---|",
                "| 1 | 2 | 3 |");

        List<List<Span>> result = MarkdownTable.render(block, 78);

        assertEquals(3, result.size());
        String head = result.get(0).stream().map(Span::content).reduce("", String::concat);
        assertEquals("| A | B | C |", head, "退回原样必须保留原文（只是不重排列）");
    }

    @Test
    void render_preservesContentWhenRowHasExtraCells() {
        // 多于表头的单元格并入最后一列，一个字不丢（典型触发：格子里有带管道的行内代码）
        List<String> block = List.of(
                "| cmd | desc |",
                "|-----|------|",
                "| ps | ps aux | grep java |");

        List<List<Span>> result = MarkdownTable.render(block, 78);
        String row = result.get(2).stream().map(Span::content).reduce("", String::concat);

        assertTrue(row.contains("ps aux | grep java"), "多出的单元格应拼回最后一列：" + row);
    }

    @Test
    void render_doesNotThrowOnMalformedInput() {
        assertDoesNotThrow(() -> MarkdownTable.render(List.of("|---|"), 78));            // 只有分隔行
        assertDoesNotThrow(() -> MarkdownTable.render(List.of("|", "|", "|"), 78));      // 全是 |
        assertDoesNotThrow(() -> MarkdownTable.render(List.of("| a |", "|---|"), -5));   // 负宽度
        assertDoesNotThrow(() -> MarkdownTable.render(java.util.Arrays.asList("| a |", null), 78));
        assertDoesNotThrow(() -> MarkdownTable.render(List.of("| a |", "|---|", ""), 78));
    }

    /**
     * 产出侧 600 行上限：超限退回原样。
     *
     * <p>⚠ 两个坑，都会让这条断言为<b>错误的理由</b>变绿（把 {@code MAX_OUTPUT_LINES} 改成
     * {@code Integer.MAX_VALUE} 也照样通过）：
     * <ul>
     *   <li>{@code inner < 6} 与「削列迭代超 10000 次」都会<b>先</b>退回原样。想靠「一个超长格子
     *       折出几百行」触发上限是做不到的：单元格越长，削列迭代数就越大，先撞的永远是削列上限。
     *       所以要靠<b>行数</b>而不是靠格内折行。</li>
     *   <li>退回原样返回的行数 = 块行数，与「上限没了、正常排版」的行数<b>恰好相同</b>
     *       （每行输入 → 每行输出）。只断言 size 的话两条路径撞在同一个数上，变异杀不掉。
     *       必须断言<b>内容</b>：退回原样保留原文竖线，正常排版不含竖线。</li>
     * </ul>
     */
    @Test
    void render_fallsBackWhenOutputExceeds600Lines() {
        List<String> block = new java.util.ArrayList<>();
        block.add("| 参数 |");
        block.add("|------|");
        for (int i = 0; i < 599; i++) {   // 1 表头 + 1 分隔线 + 599 → 第 599 条数据行处 size 已达 600
            block.add("| 值 |");
        }

        List<List<Span>> result = MarkdownTable.render(block, 78);

        assertEquals(block.size(), result.size(), "退回原样：每行输入一行输出");
        assertTrue(join(result.get(0)).contains("|"),
                "必须是<b>退回原样</b>（保留原文竖线），不是照常排版：" + join(result.get(0)));
    }

    @Test
    void render_staysAlignedJustUnderTheOutputCap() {
        List<String> block = new java.util.ArrayList<>();
        block.add("| 参数 |");
        block.add("|------|");
        for (int i = 0; i < 598; i++) {   // 合计正好 600 行，不该触发上限
            block.add("| 值 |");
        }

        List<List<Span>> result = MarkdownTable.render(block, 78);

        assertEquals(600, result.size());
        assertFalse(join(result.get(0)).contains("|"),
                "边界内必须照常排版（不含原文竖线）：" + join(result.get(0)));
    }

    private static String join(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        spans.forEach(s -> sb.append(s.content()));
        return sb.toString();
    }
}
