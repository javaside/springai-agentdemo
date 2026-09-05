package dev.anthropic.code.tui.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import dev.anthropic.code.tui.render.MarkdownTable.Alignment;

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
}
