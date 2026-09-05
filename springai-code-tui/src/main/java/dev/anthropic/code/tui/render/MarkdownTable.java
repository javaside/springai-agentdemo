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
}
