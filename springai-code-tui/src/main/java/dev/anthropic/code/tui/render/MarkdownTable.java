package dev.anthropic.code.tui.render;

import io.github.javaside.springai.codetui.ui.MarkdownRenderer;
import io.github.javaside.springai.codetui.ui.ScrollbackPrinter;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.List;

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
}
