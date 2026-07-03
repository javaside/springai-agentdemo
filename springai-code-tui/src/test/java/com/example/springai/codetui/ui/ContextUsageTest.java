package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.ContextStats;
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
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10);
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

        // 标题 + 事件桶 + token/占窗口 + 自动压缩 + 手动 = 5 行
        assertEquals(5, sink.lines.size(), "满快照 → 5 行报告");
        assertTrue(sink.lines.get(0).contains("上下文用量"));
        String bucket = sink.lines.get(1);
        assertTrue(bucket.contains("用户 40") && bucket.contains("助手 50") && bucket.contains("工具 8"), "事件分桶");
        assertTrue(bucket.contains("其他 2"), "otherEvents>0 才带其他");
        assertTrue(sink.lines.get(2).contains("占窗口 30%"), "30000/100000 = 30%");
        assertTrue(sink.lines.get(3).contains("当前 50%"), "自动压缩当前 30000/60000 = 50%");
        assertTrue(sink.lines.get(3).contains("保留最近 20 条"));
        assertTrue(sink.lines.get(4).contains("保留最近 10 条"), "手动行");
    }

    @Test
    void report_noWindow_omitsWindowPercentLine() {
        RecordingSink sink = new RecordingSink();
        // window=0, threshold=0, manualKeep=0 → 只标题 + 事件桶 + 无占窗口的 token 行
        ContextStats s = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0);
        new ContextUsage(() -> s, sink).report();

        assertEquals(3, sink.lines.size(), "无窗口/阈值/手动 → 3 行");
        assertFalse(sink.lines.get(1).contains("其他"), "otherEvents=0 不带其他");
        assertTrue(sink.lines.get(2).contains("估算 token") && !sink.lines.get(2).contains("占窗口"),
                "窗口=0 → 只打估算 token，无占窗口");
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

    @Test
    void suffix_windowZero_isEmpty() {
        ContextStats noWindow = new ContextStats(5, 3, 2, 0, 0, 1_234L, 0L, 0L, 0, 0);
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
        ContextStats halfUp = new ContextStats(10, 5, 5, 0, 0, 49_500L, 0L, 100_000L, 0, 0);
        ContextUsage cu = new ContextUsage(() -> halfUp, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 50%", cu.suffix(), "49.5% 四舍五入到 50%（非截断的 49%）");
    }

    @Test
    void pct_roundsDownBelowHalf_notCeil() {
        // 49400/100000 = 49.4% → Math.round → 49%（排除向上取整 ceil 的 50%）
        ContextStats belowHalf = new ContextStats(10, 5, 5, 0, 0, 49_400L, 0L, 100_000L, 0, 0);
        ContextUsage cu = new ContextUsage(() -> belowHalf, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 49%", cu.suffix(), "49.4% 取整到 49%（排除 ceil）");
    }
}
