package dev.anthropic.code.tui.render;

import io.github.javaside.springai.codetui.ui.MarkdownRenderer;
import io.github.javaside.springai.codetui.ui.ScrollbackPrinter;
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
}
