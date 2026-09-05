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
}
