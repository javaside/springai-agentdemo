package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SegmentedWrap}（fix round I-1 的核心）：可续折行的两个推进器。
 *
 * <p>契约：①每次 {@code nextSegment()} 只折<b>一段</b>（增量，不建整表——这正是消除「整行段一次
 * 物化」突刺的机制）；②产出序列与既有一次性实现<b>逐一相等</b>——{@code Styled} 对齐
 * {@link TextWrap#wrap}、{@code Plain} 对齐 {@code CodeTuiView.wrapSegments}，两处分家会让
 * 「打出去的行」与「留底重放的行」对不上；③内容无损（段拼回原文一字不差）。
 */
class SegmentedWrapTest {

    private static final Style A = Style.create().bold();
    private static final Style B = Style.create().italic();

    /** 逐段取完 Styled 的所有段（纯文本）。 */
    private static List<String> drainStyled(List<Span> spans, int width) {
        List<String> out = new ArrayList<>();
        SegmentedWrap.Styled s = SegmentedWrap.styled(spans, width);
        while (s.hasNextSegment()) out.add(Text.from(Line.from(s.nextSegment())).rawContent());
        return out;
    }

    /** TextWrap 参考序列（纯文本）。 */
    private static List<String> textWrapRef(List<Span> spans, int width) {
        List<String> out = new ArrayList<>();
        for (Text t : TextWrap.wrap(Text.from(Line.from(spans)), width)) out.add(t.rawContent());
        return out;
    }

    /** 逐段取完 Plain 的所有段。 */
    private static List<String> drainPlain(String source, int width) {
        List<String> out = new ArrayList<>();
        SegmentedWrap.Plain p = SegmentedWrap.plain(source, width);
        while (p.hasNextSegment()) out.add(p.nextSegment());
        return out;
    }

    /** CodeTuiView.wrapSegments 参考序列（同包静态，直接调）。 */
    private static List<String> wrapSegmentsRef(String source, int width) {
        return CodeTuiView.wrapSegmentsForTest(source, width);
    }

    @Test
    @DisplayName("Plain：产出序列与 CodeTuiView.wrapSegments 逐一相等（含空行/宽字符/超长行）")
    void plainMatchesWrapSegments() {
        List<String> cases = List.of(
                "", " ", "abc", "abcdefghij",
                "字".repeat(30) + "y".repeat(60),          // 120 列中英混合
                "w".repeat(60_000),                          // 审查 I-1 的那条 60k 无换行长行
                "a\nb", "  x  ");
        for (String s : cases) {
            for (int w : new int[]{1, 2, 5, 10, 78, 80, 5000}) {
                assertEquals(wrapSegmentsRef(s, w), drainPlain(s, w),
                        "Plain 与 wrapSegments 分家（width=" + w + "）：" + s.substring(0, Math.min(20, s.length())));
            }
        }
    }

    @Test
    @DisplayName("Styled：产出序列与 TextWrap.wrap 逐一相等（含跨 span 拆分/样式保留）")
    void styledMatchesTextWrap() {
        List<List<Span>> cases = List.of(
                List.of(),
                List.of(Span.styled("", A)),
                List.of(Span.styled("abc", A)),
                List.of(Span.styled("abcdefghij", A)),
                List.of(Span.styled("ab", A), Span.styled("cdef", B)),
                List.of(Span.styled("你好世界", A), Span.styled("tail", B)),
                List.of(Span.styled("字".repeat(40), A), Span.styled("z".repeat(90), B)));
        for (List<Span> spans : cases) {
            for (int w : new int[]{1, 3, 5, 10, 78, 80, 5000}) {
                assertEquals(textWrapRef(spans, w), drainStyled(spans, w),
                        "Styled 与 TextWrap 分家（width=" + w + "）");
            }
        }
    }

    @Test
    @DisplayName("内容无损：60k 长行逐段取完拼回原文一字不差（Plain 与 Styled）")
    void longLineLosesNothing() {
        String s = "tail " + "t".repeat(60_000);
        assertEquals(s, String.join("", drainPlain(s, 80)), "Plain 拼回必须一字不差");
        assertEquals(s, String.join("", drainStyled(List.of(Span.styled(s, A)), 80)),
                "Styled 拼回必须一字不差");
    }

    @Test
    @DisplayName("每段宽度 ≤ 上限（宽字符不切半；唯一例外是上限 1 放不下宽字符时硬吃 1 个）")
    void everySegmentWithinWidth() {
        String s = "模型回复 abc 中英夹杂，URL https://api.deepseek.com/chat/completions 长串。" + "字".repeat(50);
        for (String seg : drainPlain(s, 20)) {
            assertTrue(CharWidth.of(seg) <= 20, "Plain 段宽超限：" + seg);
        }
        for (String seg : drainStyled(List.of(Span.styled(s, A)), 20)) {
            assertTrue(CharWidth.of(seg) <= 20, "Styled 段宽超限：" + seg);
        }
    }

    @Test
    @DisplayName("耗尽语义：取完后再取返回 null、hasNext 为 false；空输入产出单个空段")
    void exhaustionSemantics() {
        SegmentedWrap.Plain p = SegmentedWrap.plain("abcd", 3);
        assertTrue(p.hasNextSegment());
        assertEquals("abc", p.nextSegment());
        assertEquals("d", p.nextSegment());
        assertTrue(!p.hasNextSegment());
        assertNull(p.nextSegment(), "耗尽后返回 null（游标按此识别结束）");

        SegmentedWrap.Plain empty = SegmentedWrap.plain("", 80);
        assertEquals(List.of(""), drainPlain("", 80), "空行 → 单个空段（保持行数语义）");

        assertEquals(List.of(""), drainStyled(List.of(), 80), "空 span 列表 → 单个空段（对齐 TextWrap）");
    }

    @Test
    @DisplayName("样式跨拆分点保留：被拆 span 的两侧同一样式（对齐 TextWrapTest.styleSurvivesTheSplitPoint）")
    void styleSurvivesSplitPoint() {
        List<Span> spans = List.of(Span.styled("ab", A), Span.styled("cdef", B));
        SegmentedWrap.Styled s = SegmentedWrap.styled(spans, 3);
        List<List<Span>> segs = new ArrayList<>();
        while (s.hasNextSegment()) segs.add(s.nextSegment());

        assertEquals(2, segs.size(), "abcd 宽 3 折成 2 段");
        assertEquals("abc", Text.from(Line.from(segs.get(0))).rawContent());
        assertEquals("def", Text.from(Line.from(segs.get(1))).rawContent());
        assertEquals(A, segs.get(0).get(0).style(), "首段第一 span 样式不变");
        assertEquals(B, segs.get(0).get(1).style(), "同行第二 span 样式不变");
        assertEquals(B, segs.get(1).get(0).style(), "续段继承被拆 span 的样式");
    }

    @Test
    @DisplayName("增量性（I-1 的机制本身）：取一段不触发后续段的物化——耗尽前 hasNext 恒可用，段数与一次性实现一致")
    void incrementalProduction() {
        // 60k 行按 80 列折出 750 段；机制保证一次只折一段。可观测面：逐段产出总数与一次性实现一致、
        // 且中途任何时刻 hasNext 都能立即回答（不消费后续内容也可判定——见 hasNextSegment 的 O(1) 实现）。
        String s = "t".repeat(60_000);
        SegmentedWrap.Plain p = SegmentedWrap.plain(s, 80);
        int count = 0;
        while (p.hasNextSegment()) {
            p.nextSegment();
            count++;
        }
        assertEquals(wrapSegmentsRef(s, 80).size(), count, "段数与一次性实现一致（80 列下 60k 行 = 750 段）");
        assertEquals(750, count);
    }
}
