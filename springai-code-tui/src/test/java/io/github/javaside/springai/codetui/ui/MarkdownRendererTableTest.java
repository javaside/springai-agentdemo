package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTableTest {
    private MarkdownRenderer md;

    @BeforeEach
    void setUp() {
        md = new MarkdownRenderer();
    }

    @Test
    void feed_entersCandidateStateOnTableRow() {
        List<List<Span>> result = md.feed("| A | B |", 80);

        // 候选态，不输出
        assertEquals(0, result.size());
        assertTrue(md.hasBuffered());
    }

    @Test
    void feed_outputsNonTableRowImmediately() {
        List<List<Span>> result = md.feed("normal text", 80);

        // 非表格行直接输出
        assertEquals(1, result.size());
        assertFalse(md.hasBuffered());
    }
}
