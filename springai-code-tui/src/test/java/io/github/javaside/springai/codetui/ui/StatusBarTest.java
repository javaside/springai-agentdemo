package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.javaside.springai.codetui.ui.Theme.THINK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatusBar 纯函数：喂 (文本, tick) 断言渲染出的 {@link Text} —— 波光高亮带位置、压缩条往返 + 计时格式。
 * 遍历 {@code text.lines().get(0).spans()} 读回 span 文本 / 样式（同 MarkdownRendererTest 手法）。
 */
class StatusBarTest {

    private final StatusBar bar = new StatusBar();

    /** 取单行结果的 span 列表。 */
    private static List<Span> spansOf(Text t) {
        return t.lines().get(0).spans();
    }

    /** SHIMMER_HI = 亮白加粗。 */
    private static boolean isHighlight(Span s) {
        return s.style().effectiveModifiers().contains(Modifier.BOLD)
                && s.style().fg().map(c -> c.equals(Color.BRIGHT_WHITE)).orElse(false);
    }

    @Test
    void cacheHitSpansHighlightsOnlyPercentage() {
        Style base = Theme.HINT;
        List<Span> spans = StatusBar.cacheHitSpans(
                "deepseek-chat · 上下文 50% · 缓存命中 78%", base);

        assertEquals("deepseek-chat · 上下文 50% · 缓存命中 78%",
                spans.stream().map(Span::content).collect(java.util.stream.Collectors.joining()));
        Span value = spans.stream().filter(s -> s.content().equals("78%")).findFirst().orElseThrow();
        assertEquals(Color.indexed(115), value.style().fg().orElseThrow());
        assertTrue(value.style().effectiveModifiers().contains(Modifier.BOLD));
        assertTrue(spans.stream()
                .filter(s -> !s.content().equals("78%"))
                .allMatch(s -> s.style().equals(base)));
    }

    @Test
    void cacheHitSpansLeavesOtherPercentagesAndMissingCacheTextAlone() {
        Style base = Theme.DIM;
        List<Span> plain = StatusBar.cacheHitSpans(" · 上下文 50% · Esc 取消", base);

        assertEquals(1, plain.size());
        assertEquals(" · 上下文 50% · Esc 取消", plain.get(0).content());
        assertEquals(base, plain.get(0).style());
        assertFalse(plain.get(0).style().effectiveModifiers().contains(Modifier.BOLD));
    }

    @Test
    void shimmer_atTickZero_highlightsLeadingBand_andAppendsDimSuffix() {
        Text t = bar.shimmer("abcdef", " · x", THINK, 0);   // n=6 → period 12；tick0 → center=0
        assertEquals("abcdef · x", t.rawContent(), "整行文本 = label + suffix");
        List<Span> spans = spansOf(t);
        assertEquals(7, spans.size(), "6 个逐字 label span + 1 个 suffix span");
        assertTrue(isHighlight(spans.get(0)), "|0-0|≤1 高亮");
        assertTrue(isHighlight(spans.get(1)), "|1-0|≤1 高亮");
        assertFalse(isHighlight(spans.get(3)), "|3-0|>1 不高亮");
        assertEquals(" · x", spans.get(6).content(), "尾 span 为 suffix");
        assertFalse(isHighlight(spans.get(6)), "suffix 为 DIM，非高亮");
    }

    @Test
    void shimmer_emptySuffix_hasNoSuffixSpan() {
        Text t = bar.shimmer("ab", "", THINK, 0);
        assertEquals("ab", t.rawContent());
        assertEquals(2, spansOf(t).size(), "空 suffix → 无尾 span");
    }

    @Test
    void shimmer_bandMovesWithTick() {
        Text t = bar.shimmer("abcdef", "", THINK, 8);   // center = (8/2) % 12 = 4
        List<Span> spans = spansOf(t);
        assertTrue(isHighlight(spans.get(3)), "|3-4|≤1 高亮");
        assertTrue(isHighlight(spans.get(4)), "|4-4|≤1 高亮");
        assertTrue(isHighlight(spans.get(5)), "|5-4|≤1 高亮");
        assertFalse(isHighlight(spans.get(0)), "|0-4|>1 不高亮");
    }

    @Test
    void compacting_containsLabel_reason_andBounceBar() {
        Text t = bar.compacting(0L, 0);
        String raw = t.rawContent();
        assertTrue(raw.contains("正在压缩会话历史"), "含压缩 label");
        assertTrue(raw.contains("不可中断"), "含不可中断提示");
        assertTrue(raw.contains("▰") || raw.contains("▱"), "含进度条格");
    }

    @Test
    void compacting_formatsElapsedOverMinute() {
        Text t = bar.compacting(75_000_000_000L, 0);   // 75s → "1m 15s"
        assertTrue(t.rawContent().contains("1m 15s"), "跨分钟计时格式");
    }

    @Test
    void compacting_formatsElapsedUnderMinute() {
        Text t = bar.compacting(5_000_000_000L, 0);    // 5s → "(5s)"
        assertTrue(t.rawContent().contains("(5s)"), "秒级计时格式");
    }

    /** 进度条亮格位置跟随三角波：升段（pos<width，center=pos）与折返段（pos≥width，center=period-pos）各验一次。 */
    @Test
    void compacting_bounceBarLitBandTracksTriangleWave() {
        // width=24, period=48。tick=20 → pos=(20/2)%48=10（<24）→ center=10 → 亮格 {9,10,11}
        assertEquals("▰▰▰", litRunAround(cellsOf(bar.compacting(0L, 20)), 9, 11), "升段 center=10");
        assertEquals("▱", cellCharAt(cellsOf(bar.compacting(0L, 20)), 0), "远处暗格");
        // tick=60 → pos=(60/2)%48=30（≥24）→ center=48-30=18 → 亮格 {17,18,19}（折返段）
        assertEquals("▰▰▰", litRunAround(cellsOf(bar.compacting(0L, 60)), 17, 19), "折返段 center=18");
    }

    /** 取压缩条的 24 个进度格（span[0] 是 label，其后为逐格 ▰/▱）。 */
    private static String cellsOf(Text t) {
        List<Span> spans = spansOf(t);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < spans.size(); i++) sb.append(spans.get(i).content());
        return sb.toString();
    }

    /** 第 i 个进度格字符（▰/▱），以 String 返回便于与字面量比较。 */
    private static String cellCharAt(String cells, int i) {
        return String.valueOf(cells.charAt(i));
    }

    /** 进度格 [from, to] 闭区间子串（to 含内，与 substring 的开区间不同）。 */
    private static String litRunAround(String cells, int from, int to) {
        return cells.substring(from, to + 1);
    }
}
