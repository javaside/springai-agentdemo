package dev.anthropic.code.tui.render;

import io.github.javaside.springai.codetui.ui.MarkdownRenderer;
import io.github.javaside.springai.codetui.ui.ScrollbackPrinter;

public final class MarkdownTable {
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
}
