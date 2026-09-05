package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.Span;
import dev.tamboui.style.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class MarkdownTable {

    enum Alignment {
        LEFT, CENTER, RIGHT
    }

    private final MarkdownRenderer markdownRenderer;
    private final ScrollbackPrinter scrollbackPrinter;

    public MarkdownTable(MarkdownRenderer markdownRenderer, ScrollbackPrinter scrollbackPrinter) {
        this.markdownRenderer = markdownRenderer;
        this.scrollbackPrinter = scrollbackPrinter;
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

        // 去除首尾空单元格
        List<String> trimmed = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            String cell = result.get(i).trim();
            if (cell.isEmpty() && (i == 0 || i == result.size() - 1)) {
                continue;
            }
            if (!cell.isEmpty()) {
                trimmed.add(cell);
            }
        }

        return trimmed;
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
     * 计算字符串的显示宽度（CJK 字符算 2 列）。
     */
    static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }

        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            int codePoint = s.codePointAt(i);
            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++; // 跳过低代理项
            }

            // CJK 统一表意文字、全角字符等占 2 列
            if ((codePoint >= 0x4E00 && codePoint <= 0x9FFF) ||   // CJK 统一表意文字
                (codePoint >= 0x3400 && codePoint <= 0x4DBF) ||   // CJK 扩展 A
                (codePoint >= 0x20000 && codePoint <= 0x2A6DF) || // CJK 扩展 B
                (codePoint >= 0x2A700 && codePoint <= 0x2B73F) || // CJK 扩展 C
                (codePoint >= 0x2B740 && codePoint <= 0x2B81F) || // CJK 扩展 D
                (codePoint >= 0x2B820 && codePoint <= 0x2CEAF) || // CJK 扩展 E
                (codePoint >= 0xF900 && codePoint <= 0xFAFF) ||   // CJK 兼容表意文字
                (codePoint >= 0x2F800 && codePoint <= 0x2FA1F) || // CJK 兼容表意文字补充
                (codePoint >= 0x3040 && codePoint <= 0x309F) ||   // 平假名
                (codePoint >= 0x30A0 && codePoint <= 0x30FF) ||   // 片假名
                (codePoint >= 0xFF00 && codePoint <= 0xFFEF)) {   // 全角字符
                width += 2;
            } else {
                width += 1;
            }
        }

        return width;
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
     */
    static List<String> wrapCellContent(String content, int columnWidth) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }

        List<String> result = new ArrayList<>();
        String remaining = content;

        while (!remaining.isEmpty()) {
            // 整行适配，直接返回
            if (displayWidth(remaining) <= columnWidth) {
                result.add(remaining);
                break;
            }

            // 尝试在空格处断
            int lastSpaceIdx = -1;
            int widthUpToSpace = 0;

            for (int i = 0; i < remaining.length(); i++) {
                char c = remaining.charAt(i);
                int charWidth = displayWidth(String.valueOf(c));

                if (widthUpToSpace + charWidth > columnWidth) {
                    break;
                }

                widthUpToSpace += charWidth;

                if (c == ' ') {
                    lastSpaceIdx = i;
                }
            }

            if (lastSpaceIdx > 0) {
                // 在空格处断，空格丢弃
                result.add(remaining.substring(0, lastSpaceIdx));
                remaining = remaining.substring(lastSpaceIdx + 1).trim();
            } else {
                // 硬切（不切半个宽字符）
                int cutIdx = 0;
                int accumulatedWidth = 0;

                for (int i = 0; i < remaining.length(); i++) {
                    String charStr = String.valueOf(remaining.charAt(i));
                    int charWidth = displayWidth(charStr);

                    if (accumulatedWidth + charWidth > columnWidth) {
                        break;
                    }

                    accumulatedWidth += charWidth;
                    cutIdx = i + 1;
                }

                if (cutIdx == 0) {
                    // 单个字符就超宽，强制取 1 个字符
                    cutIdx = 1;
                }

                result.add(remaining.substring(0, cutIdx));
                remaining = remaining.substring(cutIdx);
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
