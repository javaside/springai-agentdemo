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

    @Test
    void feed_enteresBlockStateOnSeparator() {
        md.feed("| A | B |", 80);
        List<List<Span>> result = md.feed("|---|---|", 80);

        // 进块内态，仍不输出
        assertEquals(0, result.size());
        assertTrue(md.hasBuffered());
    }

    @Test
    void feed_collectsRowsInBlock() {
        md.feed("| A | B |", 80);
        md.feed("|---|---|", 80);
        List<List<Span>> result = md.feed("| 1 | 2 |", 80);

        // 块内继续收集
        assertEquals(0, result.size());
        assertTrue(md.hasBuffered());
    }

    @Test
    void feed_flushesBlockOnNonTableRow() {
        md.feed("| A | B |", 80);
        md.feed("|---|---|", 80);
        md.feed("| 1 | 2 |", 80);

        // 非表格行触发整块输出
        List<List<Span>> result = md.feed("normal text", 80);

        // 表格块（头行 + 分隔线 + 数据行 = 3 行）+ 当前行 = 4 行
        assertTrue(result.size() >= 4, "Expected >= 4 lines, got " + result.size());
        assertFalse(md.hasBuffered());
    }

    @Test
    void feed_flushesCandidate_whenNotFollowedBySeparator() {
        md.feed("| A | B |", 80);

        // 下一行不是分隔符，吐候选行 + 当前行
        List<List<Span>> result = md.feed("normal text", 80);

        assertEquals(2, result.size());
        assertFalse(md.hasBuffered());
    }

    @Test
    void flush_outputsCandidateAsNormalLine() {
        md.feed("| not a table", 80); // 候选态

        List<List<Span>> result = md.flush(80);

        // 候选行按普通行输出
        assertEquals(1, result.size());
        assertFalse(md.hasBuffered());
    }

    @Test
    void flush_outputsAlignedBlock() {
        md.feed("| A | B |", 80);
        md.feed("|---|---|", 80);
        md.feed("| 1 | 2 |", 80);

        List<List<Span>> result = md.flush(80);

        // 对齐输出
        assertTrue(result.size() >= 3);
        assertFalse(md.hasBuffered());
    }

    @Test
    void flush_idempotent() {
        md.feed("| A | B |", 80);
        md.flush(80);

        List<List<Span>> result = md.flush(80);

        // 第二次 flush 返回空
        assertEquals(0, result.size());
        assertFalse(md.hasBuffered());
    }

    @Test
    void reset_clearsBufferAndState() {
        md.feed("| A | B |", 80);
        md.feed("|---|---|", 80);

        assertTrue(md.hasBuffered());

        md.reset();

        // 缓冲清空，状态回 IDLE
        assertFalse(md.hasBuffered());

        // 下一张表能正常识别
        md.feed("| C | D |", 80);
        assertTrue(md.hasBuffered());
    }

    @Test
    void feed_handlesNull() {
        assertDoesNotThrow(() -> md.feed(null, 80));
        assertEquals(List.of(), md.feed(null, 80));
    }
}
