package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Modifier;
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

    // ---- 围栏守卫 ----

    @Test
    void feed_doesNotTreatFencedPipeLinesAsTable() {
        // 围栏内的 `|` 行不是表格——状态机放在 MarkdownRenderer 里就是因为只有它知道围栏开合。
        // 被当成表格排版会丢掉代码块的左边栏、还会把示例表格的原文重排掉。
        List<List<Span>> open = md.feed("```markdown", 78);
        assertEquals(1, open.size());

        List<List<Span>> row = md.feed("| A | B |", 78);
        assertEquals(1, row.size(), "围栏内的 | 行必须立即输出，不能进缓冲");
        assertFalse(md.hasBuffered(), "围栏内不进候选态");
        assertTrue(text(row.get(0)).contains("| A | B |"), "围栏内应保留原文：" + text(row.get(0)));

        List<List<Span>> sep = md.feed("|---|---|", 78);
        assertEquals(1, sep.size());
        assertTrue(text(sep.get(0)).contains("|---|---|"));
        assertFalse(md.hasBuffered());
    }

    @Test
    void feed_recognizesTableRightAfterFenceCloses() {
        md.feed("```", 78);
        md.feed("| in | fence |", 78);
        md.feed("```", 78);          // 围栏关闭

        md.feed("| A | B |", 78);
        assertTrue(md.hasBuffered(), "围栏关闭后应恢复识别表格");
    }

    // ---- 重投喂 ----

    @Test
    void feed_reFeedsCurrentLine_soTableAfterPipeProseIsRecognized() {
        // 少了「当前行重新投喂」，第 2 行（真表头）会被连带原样吐出、第 3 行（分隔行）
        // 又被当成新候选，整张表拆成原样输出。
        List<List<Span>> out1 = md.feed("| 开头的一句正文", 78);
        assertEquals(0, out1.size());

        List<List<Span>> out2 = md.feed("| 参数 | 说明 |", 78);
        assertEquals(1, out2.size(), "只吐上一条候选行");
        assertTrue(text(out2.get(0)).contains("开头的一句正文"));
        assertTrue(md.hasBuffered(), "当前行必须被重新投喂、成为新候选");

        assertEquals(0, md.feed("|------|------|", 78).size());
        List<List<Span>> out4 = md.feed("", 78);

        // 表头 + 分隔线 + 空行 = 3 行，且表头已重排（不含原文竖线）
        assertEquals(3, out4.size());
        assertFalse(text(out4.get(0)).contains("|"), "表格应被识别并重排：" + text(out4.get(0)));
    }

    // ---- 缓冲上限与降级态 ----

    @Test
    void feed_degradesWhenBufferExceedsRowLimit() {
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);
        for (int i = 0; i < 198; i++) {
            assertEquals(0, md.feed("| " + i + " | x |", 78).size(), "第 " + i + " 行应仍在缓冲");
        }

        // 第 199 条数据行使缓冲到 201 行 > 200 上限 → 已攒行原样吐出、转降级
        List<List<Span>> out = md.feed("| 198 | x |", 78);
        assertEquals(201, out.size(), "越上限时已攒的 201 行必须原样吐出，一行不丢");
        assertTrue(text(out.get(0)).contains("| A | B |"), "原样 = 保留竖线，不重排列");
        assertFalse(md.hasBuffered(), "降级态缓冲已空");

        // 降级态：剩余 | 行原样逐行输出
        List<List<Span>> more = md.feed("| 199 | x |", 78);
        assertEquals(1, more.size());
        assertTrue(text(more.get(0)).contains("| 199 | x |"));
    }

    @Test
    void feed_degradesWhenBufferExceedsCharLimit() {
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);

        // 单行超 64 K 原文：按原文字符数判上限
        List<List<Span>> out = md.feed("| " + "x".repeat(70_000) + " |", 78);

        assertEquals(3, out.size(), "越字符上限时已攒的 3 行原样吐出");
        assertFalse(md.hasBuffered());
    }

    @Test
    void feed_degradedRowsKeepInlineStyle() {
        // 「原样」= 走 renderFinalized，只是不重排列——降级行照旧要有内联样式，
        // 否则就是把今天已有的行为改坏了（回归）。
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);
        for (int i = 0; i < 199; i++) {
            md.feed("| " + i + " | x |", 78);
        }
        assertFalse(md.hasBuffered(), "已转降级态");

        List<List<Span>> out = md.feed("| **粗** | `代码` |", 78);
        assertEquals(1, out.size());
        assertTrue(out.get(0).stream().anyMatch(s -> s.style().effectiveModifiers().contains(Modifier.BOLD)),
                "降级行的 **粗** 仍须加粗");
        assertFalse(text(out.get(0)).contains("**"), "内联标记应被渲染掉：" + text(out.get(0)));
    }

    @Test
    void feed_degradedStateReturnsToIdleOnNonTableRow() {
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);
        for (int i = 0; i < 199; i++) {
            md.feed("| " + i + " | x |", 78);
        }

        assertEquals(1, md.feed("普通正文", 78).size(), "降级态遇非 | 行：回空闲并渲染该行");

        // 回空闲后新表格能重新被识别
        md.feed("| C | D |", 78);
        assertTrue(md.hasBuffered());
    }

    @Test
    void reset_clearsDegradedState() {
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);
        for (int i = 0; i < 199; i++) {
            md.feed("| " + i + " | x |", 78);
        }
        assertFalse(md.hasBuffered(), "降级态 hasBuffered 为 false——第 4 条触发点不会碰它，"
                + "只丢缓冲不复位状态就会让降级态活过回合边界");

        md.reset();

        md.feed("| C | D |", 78);
        assertTrue(md.hasBuffered(), "reset 后必须重新识别表格");
    }

    @Test
    void flush_inDegradedStateReturnsEmptyAndGoesIdle() {
        md.feed("| A | B |", 78);
        md.feed("|---|---|", 78);
        for (int i = 0; i < 199; i++) {
            md.feed("| " + i + " | x |", 78);
        }

        assertEquals(0, md.flush(78).size(), "降级态 flush 返回空");

        md.feed("| C | D |", 78);
        assertTrue(md.hasBuffered(), "flush 后应已回空闲");
    }

    private static String text(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        spans.forEach(s -> sb.append(s.content()));
        return sb.toString();
    }
}
