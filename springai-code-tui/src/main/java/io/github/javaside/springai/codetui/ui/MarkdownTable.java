package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;
import dev.tamboui.style.Style;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 表格块的解析 + 排版：<b>纯函数、无状态</b>（只有 static 方法，不持有协作者、不碰 IO），
 * 因此可独立单测。轻量表输出规格见设计 §3.1：表头加粗 + 一条 {@code ─} 分隔线 + 空格对齐，
 * 不画竖线、行尾不补白。
 *
 * <p>宽度一律走 {@link #displayWidth}（委托 {@link CharWidth}），与后续 {@code SegmentedWrap}
 * 的折行口径必须同源，否则排好的行会被二次折行撕开。
 *
 * <p><b>对任意输入不抛异常</b>（含 {@code null}）：调用方 {@code MdLineCursor.next()} 的 catch
 * 返回 null，而 null 在队列语义里是「游标耗尽」，一次异常会丢掉整块 + 同游标里剩余的逻辑行。
 */
public final class MarkdownTable {

    enum Alignment {
        LEFT, CENTER, RIGHT
    }

    private MarkdownTable() {
    }

    static boolean looksLikeRow(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        return line.trim().startsWith("|");
    }

    static boolean isSeparator(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String stripped = line.strip();
        if (!stripped.startsWith("|")) {
            return false;
        }
        // 分隔行只能包含: | - : 和空格
        boolean hasDash = false;
        for (char c : stripped.toCharArray()) {
            if (c == '-') {
                hasDash = true;
            } else if (c != '|' && c != ':' && c != ' ') {
                return false;
            }
        }
        return hasDash;
    }

    static List<Alignment> alignments(String separatorLine) {
        if (separatorLine == null || !isSeparator(separatorLine)) {
            return List.of();
        }

        String[] cells = separatorLine.split("\\|", -1);
        List<Alignment> result = new ArrayList<>();

        for (String cell : cells) {
            String trimmed = cell.trim();
            if (trimmed.isEmpty()) {
                continue; // 跳过首尾空单元格
            }

            boolean leftColon = trimmed.startsWith(":");
            boolean rightColon = trimmed.endsWith(":");

            if (leftColon && rightColon) {
                result.add(Alignment.CENTER);
            } else if (rightColon) {
                result.add(Alignment.RIGHT);
            } else {
                result.add(Alignment.LEFT); // 默认左对齐
            }
        }

        return result;
    }

    /**
     * 解析单元格，按未转义的 | 切分。
     * 首尾 trim 后为空的单元格丢弃。
     */
    static List<String> parseCells(String line) {
        if (line == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;

        for (char c : line.toCharArray()) {
            if (escaped) {
                if (c == '|' || c == '\\') {
                    current.append(c);
                } else {
                    current.append('\\').append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        // 行末单 \ 保留
        if (escaped) {
            current.append('\\');
        }
        result.add(current.toString());

        // 只丢<b>首尾</b>的空单元格（GFM 标准写法 `| a | b |` 产生的外侧空串，不是真的空列）。
        // 中间的空格子是真实列，丢了会让它后面的内容整体左移一列（adjustCellCount 在末尾补空）。
        List<String> cells = new ArrayList<>(result.size());
        for (String cell : result) {
            cells.add(cell.trim());
        }
        int from = 0;
        int to = cells.size();
        if (from < to && cells.get(from).isEmpty()) {
            from++;
        }
        if (to > from && cells.get(to - 1).isEmpty()) {
            to--;
        }

        return new ArrayList<>(cells.subList(from, to));
    }

    /**
     * 调整单元格数量：不足补空串，过多并入最后列。
     */
    static List<String> adjustCellCount(List<String> cells, int targetCount) {
        if (cells.size() == targetCount) {
            return cells;
        }

        List<String> result = new ArrayList<>(cells);

        // 补空串
        while (result.size() < targetCount) {
            result.add("");
        }

        // 并入最后一列
        if (result.size() > targetCount) {
            StringBuilder lastCell = new StringBuilder(result.get(targetCount - 1));
            for (int i = targetCount; i < result.size(); i++) {
                lastCell.append(" | ").append(result.get(i));
            }
            // 创建新 list 而非使用 subList（subList 返回的是不可变视图）
            List<String> adjusted = new ArrayList<>(result.subList(0, targetCount));
            adjusted.set(targetCount - 1, lastCell.toString());
            return adjusted;
        }

        return result;
    }

    /**
     * 显示宽度：<b>必须</b>与 {@link CharWidth} 同口径。
     *
     * <p>排出来的每一行随后都要过 {@code SegmentedWrap.styled(line, innerWidth())}
     * （用 {@code CharWidth} 测量）。这里若自成一套 CJK 区间表，本类算出「总宽 ≤ inner」的行
     * 会被 SegmentedWrap 判超宽撕成两段、续段再加一层缩进——「大部分行齐、个别行裂开」
     * 是最难看的形态（设计 §3.1 硬不变量）。所以直接委托，不要重新实现。
     */
    static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return CharWidth.of(s);
    }

    /**
     * 测量 spans 拼接后的显示宽度。
     * 必须拼接后测量，因为 ZWJ/组合字符按整簇算。
     */
    static int spansDisplayWidth(List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }

        StringBuilder joined = new StringBuilder();
        for (Span span : spans) {
            joined.append(span.content());
        }

        return displayWidth(joined.toString());
    }

    /**
     * 计算每列的最大宽度（头行+体行取最大值）。
     */
    static int[] calculateColumnWidths(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }

        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columnCount];

        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                int cellWidth = displayWidth(row.get(i));
                widths[i] = Math.max(widths[i], cellWidth);
            }
        }

        // 空列最小宽度 4
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] == 0) {
                widths[i] = 4;
            }
        }

        return widths;
    }

    /**
     * 削列算法：每次削减最宽列 1 个字符宽，直到适配目标宽度或所有列达最小宽 4。
     * 超过 10000 次迭代返回 null（触发 fallback）。
     */
    static int[] reduceColumnWidths(int[] widths, int targetTotalWidth) {
        int[] result = widths.clone();
        int iterations = 0;
        final int MAX_ITERATIONS = 10000;

        while (iterations < MAX_ITERATIONS) {
            // 计算列间宽度
            int separatorWidth = Math.max(0, 2 * (result.length - 1));
            int currentTotalWidth = separatorWidth;
            for (int w : result) {
                currentTotalWidth += w;
            }

            if (currentTotalWidth <= targetTotalWidth) {
                break;
            }

            // 找最宽列（并列时取索引小的）
            int widestIdx = 0;
            int maxWidth = result[0];
            for (int i = 1; i < result.length; i++) {
                if (result[i] > maxWidth) {
                    maxWidth = result[i];
                    widestIdx = i;
                }
            }

            // 已达最小宽度，无法继续削减
            if (maxWidth <= 4) {
                break;
            }

            result[widestIdx]--;
            iterations++;
        }

        // 超限返回 null（触发 fallback）
        if (iterations >= MAX_ITERATIONS) {
            return null;
        }

        return result;
    }

    /**
     * 格内折行：优先在空格处断，无空格则硬切（不切半个宽字符）。
     *
     * <p>硬切走 {@link CharWidth#substringByWidth}——与 {@code SegmentedWrap} /
     * {@code TextWrap} 同一原语。这是本仓第三套折行语义，唯一的区别只能是「空格感知」，
     * 无空格可断时必须与 {@code SegmentedWrap.plain} 逐字相等（有交叉单测钉住）。
     */
    static List<String> wrapCellContent(String content, int columnWidth) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }

        int width = Math.max(1, columnWidth);
        List<String> result = new ArrayList<>();
        String remaining = content;

        while (!remaining.isEmpty()) {
            // 整段适配，直接收尾
            if (displayWidth(remaining) <= width) {
                result.add(remaining);
                break;
            }

            // 本段能容纳的最长前缀（宽字符不切半）
            String window = CharWidth.substringByWidth(remaining, width);
            if (window.isEmpty()) {
                window = remaining.substring(0, 1);   // 窄到放不下 1 个宽字符：硬吃 1 个防死循环
            }

            int lastSpace = window.lastIndexOf(' ');
            if (lastSpace > 0) {
                // 在空格处断，空格本身丢弃、下一段去掉开头空白
                result.add(remaining.substring(0, lastSpace));
                remaining = remaining.substring(lastSpace + 1).stripLeading();
            } else {
                result.add(window);
                remaining = remaining.substring(window.length());
            }
        }

        return result;
    }

    /**
     * 表格块渲染主入口。
     *
     * @param block 表格块（包含头行、分隔符、体行）
     * @param inner 终端内宽（列）
     * @return 渲染后的行（每行是 Span 列表），退回原样时走 renderInline
     */
    static List<List<Span>> render(List<String> block, int inner) {
        if (inner < 6 || block == null || block.size() < 2) {
            return fallbackToRaw(block);
        }

        // 解析表头和分隔行
        String headerLine = block.get(0);
        if (!looksLikeRow(headerLine)) {
            return fallbackToRaw(block);
        }

        String separatorLine = block.get(1);
        if (!isSeparator(separatorLine)) {
            return fallbackToRaw(block);
        }

        List<String> headerCells = parseCells(headerLine);
        List<Alignment> aligns = alignments(separatorLine);

        // 对齐信息数量必须 ≥ 表头列数
        if (aligns.size() < headerCells.size()) {
            return fallbackToRaw(block);
        }

        // 截取对齐信息到表头列数
        aligns = aligns.subList(0, headerCells.size());
        int columnCount = headerCells.size();

        // 解析数据行
        List<List<String>> dataRows = new ArrayList<>();
        for (int i = 2; i < block.size(); i++) {
            String line = block.get(i);
            if (!looksLikeRow(line)) {
                continue; // 跳过非表格行
            }
            List<String> cells = parseCells(line);
            cells = adjustCellCount(cells, columnCount);
            dataRows.add(cells);
        }

        // 收集所有行（用于计算列宽）
        List<List<String>> allRows = new ArrayList<>();
        allRows.add(headerCells);
        allRows.addAll(dataRows);

        // 计算列宽
        int[] columnWidths = calculateColumnWidths(allRows);

        // 计算总宽度
        int separatorWidth = Math.max(0, 2 * (columnCount - 1));
        int totalWidth = separatorWidth;
        for (int w : columnWidths) {
            totalWidth += w;
        }

        // 超宽时削列
        if (totalWidth > inner) {
            int[] reduced = reduceColumnWidths(columnWidths, inner);
            if (reduced == null) {
                // 削列超限，退回原样
                return fallbackToRaw(block);
            }
            columnWidths = reduced;

            // 重新计算总宽度
            totalWidth = separatorWidth;
            for (int w : columnWidths) {
                totalWidth += w;
            }
        }

        // 连最小宽度都装不下
        int minTotalWidth = 4 * columnCount + separatorWidth;
        if (minTotalWidth > inner) {
            return fallbackToRaw(block);
        }

        // 开始排版输出
        List<List<Span>> output = new ArrayList<>();
        final int MAX_OUTPUT_LINES = 600;

        // 排版表头
        List<List<List<Span>>> wrappedHeader = wrapRow(headerCells, columnWidths, aligns, true);
        for (List<List<Span>> physicalLine : wrappedHeader) {
            if (output.size() >= MAX_OUTPUT_LINES) {
                return fallbackToRaw(block);
            }
            output.add(flattenPhysicalLine(physicalLine));
        }

        // 输出分隔线
        if (output.size() >= MAX_OUTPUT_LINES) {
            return fallbackToRaw(block);
        }
        output.add(List.of(Span.styled(repeat('─', totalWidth), Style.create().dim())));

        // 排版数据行
        for (List<String> row : dataRows) {
            List<List<List<Span>>> wrappedRow = wrapRow(row, columnWidths, aligns, false);
            for (List<List<Span>> physicalLine : wrappedRow) {
                if (output.size() >= MAX_OUTPUT_LINES) {
                    return fallbackToRaw(block);
                }
                output.add(flattenPhysicalLine(physicalLine));
            }
        }

        return output;
    }

    /**
     * 将一行数据按列宽折行，返回物理行列表。
     * 每个物理行是 List<List<Span>>，外层按列、内层是该列该段的 spans。
     */
    private static List<List<List<Span>>> wrapRow(List<String> cells, int[] columnWidths,
                                                    List<Alignment> aligns, boolean bold) {
        int columnCount = cells.size();

        // 对每个格子格内折行
        List<List<String>> wrappedCells = new ArrayList<>();
        int maxLines = 1;

        for (int col = 0; col < columnCount; col++) {
            List<String> lines = wrapCellContent(cells.get(col), columnWidths[col]);
            wrappedCells.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }

        // 按物理行组装
        List<List<List<Span>>> result = new ArrayList<>();
        for (int lineIdx = 0; lineIdx < maxLines; lineIdx++) {
            List<List<Span>> physicalLine = new ArrayList<>();

            for (int col = 0; col < columnCount; col++) {
                List<String> cellLines = wrappedCells.get(col);
                String content = lineIdx < cellLines.size() ? cellLines.get(lineIdx) : "";

                // 对齐处理
                String aligned = alignContent(content, columnWidths[col], aligns.get(col), col == columnCount - 1);

                // 渲染内联样式
                List<Span> spans = MarkdownRenderer.renderInline(aligned);

                // 表头加粗
                if (bold) {
                    List<Span> boldSpans = new ArrayList<>();
                    for (Span s : spans) {
                        Style newStyle = s.style().bold();
                        boldSpans.add(Span.styled(s.content(), newStyle));
                    }
                    spans = boldSpans;
                }

                physicalLine.add(spans);
            }

            result.add(physicalLine);
        }

        return result;
    }

    /**
     * 将物理行（多列 spans）拼接成单行 spans，列间加 2 空格。
     */
    private static List<Span> flattenPhysicalLine(List<List<Span>> columns) {
        List<Span> result = new ArrayList<>();

        for (int i = 0; i < columns.size(); i++) {
            result.addAll(columns.get(i));

            // 列间 2 空格（最后一列不加）
            if (i < columns.size() - 1) {
                result.add(Span.raw("  "));
            }
        }

        return result;
    }

    /**
     * 对齐内容：左/中/右对齐，最后一列去尾部补白。
     */
    private static String alignContent(String content, int columnWidth, Alignment align, boolean isLastColumn) {
        int contentWidth = displayWidth(content);
        int padding = columnWidth - contentWidth;

        if (padding <= 0) {
            return content;
        }

        String padStr = repeat(' ', padding);

        switch (align) {
            case LEFT:
                return isLastColumn ? content : content + padStr;
            case RIGHT:
                return padStr + content;
            case CENTER:
                int leftPad = padding / 2;
                int rightPad = padding - leftPad;
                if (isLastColumn) {
                    rightPad = 0; // 最后一列去尾部补白
                }
                return repeat(' ', leftPad) + content + repeat(' ', rightPad);
            default:
                return content;
        }
    }

    /**
     * 重复字符 n 次。
     */
    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 退回原样：每行走 MarkdownRenderer.renderInline。
     */
    private static List<List<Span>> fallbackToRaw(List<String> block) {
        if (block == null || block.isEmpty()) {
            return List.of();
        }

        List<List<Span>> result = new ArrayList<>();
        for (String line : block) {
            result.add(MarkdownRenderer.renderInline(line));
        }
        return result;
    }
}
