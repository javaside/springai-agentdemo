package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.session.ContextStats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextUsage 纯 Java 单测：喂 {@link ContextStats}，断言 /context 报告每行文案分支、状态栏后缀、
 * 缓存刷新容错。全程无 tamboui 类型（本类是零 UI 依赖的接缝）。
 */
class ContextUsageTest {

    /** 收集 sink 输出的每一行。 */
    private static final class RecordingSink implements Consumer<String> {
        final List<String> lines = new ArrayList<>();
        @Override public void accept(String line) { lines.add(line); }
    }

    /** 造一份满快照：events=100（用户40/助手50/工具8/其他2）、est=30000、window=100000、
     *  threshold=60000、autoKeep=20、manualKeep=10。 */
    private static ContextStats full() {
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10, 0, 0L);
    }

    @Test
    void report_emptySnapshot_printsTitleAndNoHistory() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextStats::empty, sink).report();

        assertEquals(2, sink.lines.size(), "空快照 → 标题 + 尚无对话历史 两行");
        assertTrue(sink.lines.get(0).contains("上下文用量"));
        assertTrue(sink.lines.get(1).contains("尚无对话历史"));
    }

    @Test
    void report_nullSource_treatedAsEmpty() {
        RecordingSink sink = new RecordingSink();
        Supplier<ContextStats> nullSource = () -> null;
        new ContextUsage(nullSource, sink).report();

        assertEquals(2, sink.lines.size(), "source 返回 null 当空快照处理");
        assertTrue(sink.lines.get(1).contains("尚无对话历史"));
    }

    @Test
    void report_fullSnapshot_printsAllBranchesWithRoundedPercents() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();

        // 标题 + 事件桶 + 上下文占用/占窗口 + 自动压缩 + 手动 = 5 行
        assertEquals(5, sink.lines.size(), "满快照 → 5 行报告");
        assertTrue(sink.lines.get(0).contains("上下文用量"));
        String bucket = sink.lines.get(1);
        assertTrue(bucket.contains("用户 40") && bucket.contains("助手 50") && bucket.contains("工具 8"), "事件分桶");
        assertTrue(bucket.contains("其他 2"), "otherEvents>0 才带其他");
        assertTrue(sink.lines.get(2).contains("上下文占用"), "总数行行名");
        assertTrue(sink.lines.get(2).contains("占窗口 30%"), "30000/100000 = 30%");
        assertTrue(sink.lines.get(3).contains("30,000 · 50%"), "自动压缩写原值分子：30000/60000 = 50%");
        assertTrue(sink.lines.get(3).contains("按 token 保留近期完整回合"));
        assertTrue(sink.lines.get(4).contains("按 token 更激进压缩"), "手动行");
    }

    /**
     * 视觉占用单列一行，且必须紧跟总数行——图片从不进会话存储，上面那笔估算看不见它们。
     * 顺带钉住「不许把视觉 token 加进上下文占用」：那行仍应是 30,000。
     */
    @Test
    void report_visionUsage_printsOwnLineRightAfterTextEstimate() {
        RecordingSink sink = new RecordingSink();
        ContextStats s = new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10, 3, 4_800L);
        new ContextUsage(() -> s, sink).report();

        assertEquals(6, sink.lines.size(), "满快照 + 视觉行 → 6 行报告");
        assertTrue(sink.lines.get(2).contains("30,000"), "总数行不许把视觉 token 加进去");
        String vision = sink.lines.get(3);
        assertTrue(vision.contains("3 张"), "图片张数：" + vision);
        assertTrue(vision.contains("4,800"), "视觉 token 原值，不做 k 舍入（小图会显示成 0k）：" + vision);
        assertTrue(vision.contains("不计入上方合计"), "必须说明这是另一笔账：" + vision);
        // 口径必须写明是「本回合累计」：按「上次请求」记的话，一个回合几十次工具迭代下来，
        // 用户按 /context 那一刻读到的几乎必然是 0，这一行等于没写。
        assertTrue(vision.contains("本回合"), "没写明统计口径是本回合累计：" + vision);
        assertFalse(vision.contains("上次请求"), "口径已改按回合累计，文案还停在「上次请求」：" + vision);
    }

    /** 没兑现过图就不占一行——常态是纯文本会话，多一行恒 0 的噪音会稀释真正要看的数字。 */
    @Test
    void report_noVisionUsage_omitsVisionLine() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();

        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("视觉图片")), "零视觉时不该有视觉行");
    }

    @Test
    void report_noWindow_omitsWindowPercentLine() {
        RecordingSink sink = new RecordingSink();
        // window=0, threshold=0, manualKeep=0 → 只标题 + 事件桶 + 无占窗口的 token 行
        ContextStats s = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0, 0, 0L);
        new ContextUsage(() -> s, sink).report();

        assertEquals(3, sink.lines.size(), "无窗口/阈值/手动 → 3 行");
        assertFalse(sink.lines.get(1).contains("其他"), "otherEvents=0 不带其他");
        assertTrue(sink.lines.get(2).contains("上下文占用") && !sink.lines.get(2).contains("占窗口"),
                "窗口=0 → 只打上下文占用总数，无占窗口");
    }

    @Test
    void suffix_beforeRefresh_isEmpty() {
        ContextUsage cu = new ContextUsage(ContextUsageTest::full, new RecordingSink());
        assertEquals("", cu.suffix(), "未 refresh，cached 为 empty → 空后缀");
    }

    @Test
    void suffix_afterRefresh_showsContextPercent() {
        ContextUsage cu = new ContextUsage(ContextUsageTest::full, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 30%", cu.suffix(), "refresh 后读缓存：30000/100000 = 30%");
    }

    /** 状态栏与 /context 报告同一分母（上下文占用，含系统提示词），两处百分比不许互相矛盾。 */
    @Test
    void suffix_withSystemPrompt_countsItInContextPercent() {
        ContextUsage cu = new ContextUsage(() -> withBreakdown(), new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 33%", cu.suffix(), "32,500/100,000 = 33%（含系统提示词 2,500）");
    }

    @Test
    void suffix_windowZero_isEmpty() {
        ContextStats noWindow = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0, 0, 0L);
        ContextUsage cu = new ContextUsage(() -> noWindow, new RecordingSink());
        cu.refresh();
        assertEquals("", cu.suffix(), "窗口=0 → 空后缀");
    }

    @Test
    void refresh_sourceThrows_keepsPreviousCache() {
        AtomicReference<Supplier<ContextStats>> ref = new AtomicReference<>(ContextUsageTest::full);
        ContextUsage cu = new ContextUsage(() -> ref.get().get(), new RecordingSink());
        cu.refresh();                                   // 缓存 = full → 后缀 30%
        assertEquals(" · 上下文 30%", cu.suffix());

        ref.set(() -> { throw new RuntimeException("boom"); });
        cu.refresh();                                   // 抛异常，静默保留旧值
        assertEquals(" · 上下文 30%", cu.suffix(), "source 抛异常 → 保留上次缓存");
    }

    @Test
    void pct_roundsHalfUp_notTruncated() {
        // 49500/100000 = 49.5% → Math.round → 50%（截断/整除会得 49%）
        ContextStats halfUp = new ContextStats(10, 5, 5, 0, 0, 49_500L, 0L, 100_000L, 0, 0, 0, 0L);
        ContextUsage cu = new ContextUsage(() -> halfUp, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 50%", cu.suffix(), "49.5% 四舍五入到 50%（非截断的 49%）");
    }

    @Test
    void pct_roundsDownBelowHalf_notCeil() {
        // 49400/100000 = 49.4% → Math.round → 49%（排除向上取整 ceil 的 50%）
        ContextStats belowHalf = new ContextStats(10, 5, 5, 0, 0, 49_400L, 0L, 100_000L, 0, 0, 0, 0L);
        ContextUsage cu = new ContextUsage(() -> belowHalf, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 49%", cu.suffix(), "49.4% 取整到 49%（排除 ceil）");
    }

    private static ContextStats withCache(long cacheRead, long billedInput, Integer hitPercent) {
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10, 0, 0L,
                cacheRead, billedInput, hitPercent);
    }

    /** 带 token 分桶与系统提示词的快照：对话消息 30,000（用户 12,000 / 助手 10,000 / 工具 8,000），系统提示词 2,500。 */
    private static ContextStats withBreakdown() {
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10, 0, 0L,
                0L, 0L, null,
                new ContextStats.TokenBreakdown(0L, 12_000L, 10_000L, 8_000L), 2_500L);
    }

    @Test
    void report_breakdown_printsOneLinePerCategory() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(() -> withBreakdown(), sink).report();

        int turnIdx = indexOfContaining(sink.lines, "上下文占用");
        int compIdx = indexOfContaining(sink.lines, "构成");
        assertTrue(compIdx > turnIdx, "构成行应在上下文占用行之后：行序=" + sink.lines);
        // 上下文占用 = 系统提示词 2,500 + 消息 30,000 = 32,500
        assertTrue(sink.lines.get(turnIdx).contains("32,500 / 100,000"),
                "上下文占用须含系统提示词：实际=" + sink.lines.get(turnIdx));
        // 构成逐行：系统提示词第一项，消息三项各占一行（不再挤成一长串）
        String sp = sink.lines.get(indexOfContaining(sink.lines, "系统提示词"));
        assertTrue(sp.contains("2,500 · 8%"), "系统提示词占比（7.7% 补余到 8%）：实际=" + sp);
        assertTrue(sp.contains("每回合固定重发"), "系统提示词须注明每回合固定重发：实际=" + sp);
        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "用户消息")).contains("12,000 · 37%"),
                "用户占比（36.9% 补余到 37%）");
        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "助手消息")).contains("10,000 · 31%"),
                "助手占比（30.8% 补余到 31%）");
        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "工具结果")).contains("8,000 · 24%"),
                "工具占比（24.6% 截断到 24%）");
        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("系统/摘要")), "系统/摘要桶为 0 时省略");
    }

    /** 占比经 largest remainder 分配：三桶各 1/3 时总和仍须是 100%，不许 33+33+33=99。 */
    @Test
    void report_tokenBreakdown_percentsAlwaysSumTo100() {
        ContextStats s = new ContextStats(3, 1, 1, 1, 0, 3_000L, 0L, 0L, 0, 0, 0, 0L,
                0L, 0L, null,
                new ContextStats.TokenBreakdown(0L, 1_000L, 1_000L, 1_000L), 0L);
        RecordingSink sink = new RecordingSink();
        new ContextUsage(() -> s, sink).report();

        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "用户消息")).contains("1,000 · 34%"),
                "余量补给小数部分最大者");
        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "助手消息")).contains("1,000 · 33%"));
        assertTrue(sink.lines.get(indexOfContaining(sink.lines, "工具结果")).contains("1,000 · 33%"));
    }

    /** 唯一的 token 总数行是「上下文占用」——不许再出现第二个「会话估算」行制造两套对不上的数字。 */
    @Test
    void report_onlyOneTokenTotalLine() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(() -> withBreakdown(), sink).report();

        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("估算 token")), "不许出现第二个总数行");
        assertEquals(1, sink.lines.stream().filter(l -> l.contains("上下文占用")).count(),
                "上下文占用行只允许一行");
    }

    @Test
    void report_systemBucketShown_whenSystemMessagesExist() {
        ContextStats s = new ContextStats(5, 2, 1, 1, 1, 5_000L, 0L, 0L, 0, 0, 0, 0L,
                0L, 0L, null,
                new ContextStats.TokenBreakdown(500L, 2_000L, 1_500L, 1_000L), 0L);
        RecordingSink sink = new RecordingSink();
        new ContextUsage(() -> s, sink).report();

        String cls = sink.lines.get(indexOfContaining(sink.lines, "系统/摘要"));
        assertTrue(cls.contains("500 · 10%"), "系统桶非零应显示且带占比：实际=" + cls);
    }

    @Test
    void report_noBreakdownData_omitsCompositionLines() {
        // 12 参便捷构造器：tokens 为空桶、systemPromptTokens 为 0 → 不打印构成与系统提示词行；
        // 但「上下文占用」总数行无论有无分桶都打印（它是唯一总数）。
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();

        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("构成")), "零分桶数据不打印构成行");
        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("系统提示词")), "零系统提示词不打印该行");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("上下文占用")), "总数行必须打印");
    }

    @Test
    void report_cacheHit_printsLineAfterTokenEstimate() {
        RecordingSink sink = new RecordingSink();
        ContextStats s = withCache(80L, 100L, 80);
        new ContextUsage(() -> s, sink).report();

        int tokenIdx = indexOfContaining(sink.lines, "上下文占用");
        int cacheIdx = indexOfContaining(sink.lines, "缓存命中率");
        assertTrue(cacheIdx > tokenIdx, "命中行在上下文占用行之后");
        String line = sink.lines.get(cacheIdx);
        assertTrue(line.contains("缓存命中率：80%"), "命中率文案：" + line);
        assertTrue(line.contains("命中 80"), "命中数：" + line);
        assertTrue(line.contains("计费输入 100"), "计费输入数：" + line);
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return -1;
    }

    @Test
    void report_noCacheData_omitsCacheLine() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();   // full() 缓存字段为 0/0/null

        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("缓存命中率")), "无缓存数据不打印命中行");
    }

    @Test
    void suffix_cacheHit_appendsAfterContext() {
        ContextStats s = withCache(80L, 100L, 80);
        ContextUsage cu = new ContextUsage(() -> s, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 30% · 缓存命中 80%", cu.suffix());
    }
}
