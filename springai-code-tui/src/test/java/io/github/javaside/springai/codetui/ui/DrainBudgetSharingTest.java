package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>fix round（审查 I-3）</b>：单个 drain tick 的时间预算必须<b>全 tick 共享一个 deadline</b>，
 * 且 pending 入队必须有界分批。
 *
 * <p>严格分批的第一版在两个地方各调了一次 {@code drainQueuedOutput}（输出段 + 计划模态侦测段），
 * 每次各自 {@code System.nanoTime()+12ms} 起新 deadline——单 tick 最坏 2×12ms，且 pending 全量
 * 一次建完 20 000 个 entry lambda、diff 工厂成本完全在预算外。本测试钉三件事：
 * <ol>
 *   <li>一次 tick 内两次 drain 段共享同一 deadline（第二段拿到的截止时刻与第一段相同）；</li>
 *   <li>pending 入队每 tick 有界（一个 tick 转入的 entry 数 ≤ 上限，剩余留待后续 tick，顺序不乱、不丢）；</li>
 *   <li>计划正文段与前段共用同一预算（不再重开 12ms 窗口）。</li>
 * </ol>
 */
class DrainBudgetSharingTest {

    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static CodeTuiView view(ConversationState state, Path root, RecordingSink sink) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null;
            }
        }, root, sink);
    }

    @Test
    @DisplayName("一次 tick 的两次输出段共享同一个 deadline（不各起新 12ms 窗口）")
    void bothDrainSegmentsShareOneDeadlinePerTick(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        // 场景：队首一个计划模态（触发第二输出段）+ 一批 pending INFO（撑住第一段）
        List<String> answers = new CopyOnWriteArrayList<>();
        state.onTurnStarted(1L);
        for (int i = 0; i < 10; i++) state.pushInfo("pre " + i);
        state.onPlanSubmitted(1L, new PlanRequest(1L, "# p\nbody1\nbody2",
                (o, f) -> answers.add(o + "/" + f)));

        v.tickForTest();

        // 记录本 tick 内观察到的所有 deadline：必须全部相同（同一绝对时刻）
        List<Long> deadlines = v.drainDeadlinesObservedForTest();
        assertTrue(deadlines.size() >= 2,
                "本 tick 应发生 ≥2 次输出段（输出段 + 计划正文段），实际 " + deadlines.size());
        for (int i = 1; i < deadlines.size(); i++) {
            assertEquals(deadlines.get(0), deadlines.get(i),
                    "第 " + i + " 段的 deadline 与第一段不同：单 tick 时间预算必须共享，"
                            + "否则最坏 2×12ms（" + deadlines.get(0) + " vs " + deadlines.get(i) + "）");
        }
    }

    @Test
    @DisplayName("无计划模态的普通 tick：输出段也记录 deadline（基线，恒一只段）")
    void plainTickRecordsItsDeadline(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        for (int i = 0; i < 5; i++) state.pushInfo("x " + i);
        v.tickForTest();

        assertEquals(1, v.drainDeadlinesObservedForTest().size(), "普通 tick 只有一次输出段");
    }

    @Test
    @DisplayName("pending 入队有界分批：一个 tick 最多转入 N 条 entry，其余留在 state.pending 等后续 tick")
    void pendingIntakeIsBoundedPerTick(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        int total = 20_000;
        for (int i = 0; i < total; i++) state.pushInfo("info " + i);
        state.onTurnComplete(1L);

        v.tickForTest();                       // 第一个 tick

        // 入队上限可在测试里读取（与生产常量一致）
        int cap = v.pendingIntakeCapForTest();
        assertTrue(cap >= 100 && cap <= 5_000,
                "入队上限应在合理量级（100..5000），实际 " + cap);
        assertTrue(v.pendingIntakeCountForTest() <= cap,
                "单个 tick 转入的 entry 数（" + v.pendingIntakeCountForTest() + "）不得超过上限 " + cap
                        + "——全量一次建完 20 000 个 lambda 正是审查指出的突刺");
        assertTrue(state.hasPendingOutput(),
                "剩余 pending 应留在 state 里等后续 tick（内容不丢，只是分批转入）");

        // 后续 tick 逐步转入 + 消费：最终一行不丢、顺序不乱
        int written;
        int guard = 0;
        do {
            int before = sink.lines.size();
            v.tickForTest();
            written = sink.lines.size() - before;
            assertTrue(v.pendingIntakeCountForTest() <= cap, "每个 tick 的转入都必须 ≤ 上限");
            guard++;
        } while ((written > 0 || state.hasPendingOutput()) && guard < 5_000);

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("info ")).toList();
        assertEquals(total, body.size(), "分批转入最终必须一行不丢，实际 " + body.size());
        assertEquals("info 0", body.get(0), "顺序不能乱：首条");
        assertEquals("info " + (total - 1), body.get(total - 1), "顺序不能乱：末条");
        for (int i = 0; i < body.size(); i++) {
            assertEquals("info " + i, body.get(i), "顺序不能乱：第 " + i + " 条实际 " + body.get(i));
        }
    }

    @Test
    @DisplayName("入队有界不阻碍正常体量：一批 pending（≤ 上限）一个 tick 内全部入队并被打出")
    void boundedIntakeDoesNotThrottleNormalVolume(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        for (int i = 0; i < 50; i++) state.pushInfo("short " + i);

        v.tickForTest();

        long got = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("short ")).count();
        assertEquals(50, got, "50 条短行远低于入队上限，应当一个 tick 打完（不被节流）");
        assertFalse(state.hasPendingOutput(), "应全部转出");
    }
}
