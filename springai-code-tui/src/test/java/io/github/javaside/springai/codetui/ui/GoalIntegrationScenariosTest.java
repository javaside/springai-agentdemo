package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.goal.EvaluationInput;
import io.github.javaside.springai.codetui.agent.goal.GoalConfig;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluationRunner;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluator;
import io.github.javaside.springai.codetui.agent.goal.GoalManager;
import io.github.javaside.springai.codetui.agent.goal.GoalPhase;
import io.github.javaside.springai.codetui.agent.goal.GoalTurnMaterial;
import io.github.javaside.springai.codetui.agent.goal.GoalVerdict;
import io.github.javaside.springai.codetui.agent.goal.PauseReason;
import io.github.javaside.springai.codetui.agent.llm.EmptyStreamException;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 主链路六场景端到端集成（Task 12）——前 11 个 Task 的组件全部接线后的行为钉：
 * <ol>
 *   <li>① unsatTwiceThenSatisfiedLoop：UNSAT→UNSAT→SAT 闭环，prompt 携带上轮结论与 [goal 继续 N/M]，终态一行式总结；</li>
 *   <li>② stalledBrakeAndResetWithInterleaving：交错序列不清旧账误伤、达限刹车、用户消息恢复清零可再跑；</li>
 *   <li>③ firstTurnImpossible：首轮 IMPOSSIBLE 即终态、无第 2 次派发；</li>
 *   <li>④ maxTurnsBoundaryAndUserTurnsFree：恰 N 轮后 MAX_TURNS，用户消息轮不烧配额；</li>
 *   <li>⑤ budgetFirstSoftExceededAndZeroDisables：决策点熔断 / 软超限不夺走在飞轮 verdict / budget=0 关闭；</li>
 *   <li>⑥ onErrorFamily：豁免族不计、errorRetry+1 刹停 + 一行式总结、onTurnCompleted 重置。</li>
 * </ol>
 *
 * <p>装置照 {@code CodeTuiViewGoalSlotTest} 形态（Task 11 先例）：真 {@link GoalManager} +
 * 真 {@link io.github.javaside.springai.codetui.agent.goal.GoalEvaluationRunner} + <b>可放行</b>评估器
 * （{@code evaluate} 阻塞在门上等测试手动放行——既走生产 executor 线程与回调链，又完全确定）；
 * 批驱动用 {@code tickForTest()}，publish 驱动的批用 {@code runPendingUiUpdatesForTest()} 受控执行。
 * 全部等待有界（5s 超时 fail），断言全部真断言。
 */
class GoalIntegrationScenariosTest {

    // ── 装置（GoalIntegrationHarness，照 CodeTuiViewGoalSlotTest） ─────────

    /** Spring AI 2.x Usage 桩（GoalManagerFuseTest 同款）：只有三个抽象方法。 */
    private record FakeUsage(long prompt, long completion) implements Usage {
        @Override public Integer getPromptTokens() { return (int) prompt; }
        @Override public Integer getCompletionTokens() { return (int) completion; }
        @Override public Object getNativeUsage() { return null; }
    }

    /**
     * 脚本化门控评估器：每次 {@code evaluate} 从门队列取一块（无门则挂起等测试补挂——
     * 超脚本轮次的评估不致跑飞），测试手动 complete 放行 verdict。
     */
    private static final class ScriptedEvaluator implements GoalEvaluator {
        private final LinkedBlockingDeque<CompletableFuture<GoalVerdict>> gates = new LinkedBlockingDeque<>();
        final List<EvaluationInput> inputs = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        /** 挂一块放行门（按调用顺序消费），返回门柄——可先挂门后补 verdict。 */
        CompletableFuture<GoalVerdict> gate() {
            CompletableFuture<GoalVerdict> g = new CompletableFuture<>();
            gates.addLast(g);
            return g;
        }

        /** 便捷：挂门并立即放行（评估线程到点即取，先后到达两个方向都安全）。 */
        void enqueue(GoalVerdict v) { gate().complete(v); }

        @Override public GoalVerdict evaluate(EvaluationInput input) throws Exception {
            calls.incrementAndGet();
            inputs.add(input);
            return gates.takeFirst().get();      // 无门则挂起；verdict 经真 Runner 回调链进 GoalManager
        }
    }

    /** goal 桩：真 GoalManager + 真 GoalEvaluationRunner + 门控评估器；submit 记录、素材走桩。 */
    private static final class GoalHandler implements SubmitHandler {
        final GoalManager gm;
        final GoalEvaluationRunner runner;
        final ScriptedEvaluator evaluator = new ScriptedEvaluator();
        final List<String> submitted = new ArrayList<>();
        /** 真 accumulator：env 配了预算键就注入（含 0——钉「0=关闭」而非「未注入=关闭」）。 */
        final TokenUsageAccumulator usage;

        GoalHandler(Map<String, String> env) {
            GoalConfig config = GoalConfig.from(env::get);
            this.usage = env.containsKey(GoalConfig.TOKEN_BUDGET_ENV) ? new TokenUsageAccumulator() : null;
            this.gm = new GoalManager(config, usage);
            this.runner = new GoalEvaluationRunner(config);
        }

        @Override public Disposable submit(String text) { submitted.add(text); return () -> { }; }
        @Override public GoalManager goal() { return gm; }
        @Override public GoalEvaluationRunner goalRunner() { return runner; }
        @Override public GoalEvaluator goalEvaluator() { return evaluator; }
        @Override public GoalTurnMaterial collectGoalMaterial() { return new GoalTurnMaterial("末文本", 2, null); }
    }

    /** 记录型 scrollback 接缝（CodeTuiViewGoalSlotTest 先例）：断言一行式总结用。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(dev.tamboui.text.Text line) { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    /** 一套场景装置：state + handler + View + scrollback 记录（startForTest 后初始批已排空）。 */
    private record Rig(ConversationState s, GoalHandler h, CodeTuiView v, RecordingSink sink) { }

    private static Rig rig(GoalHandler h, Path root) {
        ConversationState s = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = new CodeTuiView(s, h, root, sink);
        v.startForTest();
        v.runPendingUiUpdatesForTest();          // 排空启动初始批，后续断言只看场景驱动的批
        return new Rig(s, h, v, sink);
    }

    private static GoalVerdict unsat(String reason, boolean stalled, String ledger) {
        return new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, reason, stalled, ledger, "raw");
    }

    /** 轮询等待（评估在 runner 线程异步起跑/回调落定），5s 超时 fail。 */
    private static void await(BooleanSupplier cond, String message) {
        long end = System.currentTimeMillis() + 5_000;
        try {
            while (System.currentTimeMillis() < end) {
                if (cond.getAsBoolean()) return;
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError(message);
    }

    /** publish 驱动的批受控执行：反复排空测试队列直到第 n 个 submit 到位（调度晚到也兜住）。 */
    private static void drainUntilSubmitted(CodeTuiView v, GoalHandler h, int n) {
        long end = System.currentTimeMillis() + 5_000;
        try {
            while (System.currentTimeMillis() < end && h.submitted.size() < n) {
                v.runPendingUiUpdatesForTest();
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        v.runPendingUiUpdatesForTest();
        assertEquals(n, h.submitted.size(), "publish 驱动的空闲批应派发第 " + n + " 个回合：" + h.submitted);
    }

    /**
     * 有界排空直到 scrollback 出现目标行。决策点熔断（MAX_TURNS/BUDGET_EXCEEDED）的总结在
     * 批<b>中</b>发生：批顶的 goalNoticeIfTransitioned 先于槽跑、pushInfo 又晚于本批输出段——
     * 要到 takeAutoTurn 自身 publish 唤醒的下一批才下沉进 scrollback，故这里多排空几批。
     */
    private static void drainUntilLine(CodeTuiView v, RecordingSink sink, String needle) {
        long end = System.currentTimeMillis() + 5_000;
        try {
            while (System.currentTimeMillis() < end && countLinesContaining(sink.lines, needle) == 0) {
                v.runPendingUiUpdatesForTest();
                v.tickForTest();
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(countLinesContaining(sink.lines, needle) >= 1,
                "应出现一行式总结「" + needle + "」，实际：" + sink.lines);
    }

    private static int countLinesContaining(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    // ── 场景①：UNSAT→UNSAT→SAT 闭环 ────────────────────────────────────

    @Test
    @DisplayName("场景① UNSAT→UNSAT→SAT：3 轮 submit、第 2/3 轮 prompt 含上轮结论与 [goal 继续 N/M]、终态 SATISFIED+一行式总结")
    void unsatTwiceThenSatisfiedLoop(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");

        rig.v().tickForTest();                    // 自动轮 1
        assertEquals(1, rig.h().submitted.size());
        assertTrue(rig.h().submitted.get(0).startsWith("[goal 继续 1/∞]"),
                "首轮 prompt 以 [goal 继续 1/∞] 开头（默认无上限），实际：" + rig.h().submitted.get(0));
        assertTrue(rig.h().submitted.get(0).contains("（首轮）"), "首轮无上轮结论");

        rig.v().tickForTest();                    // goal 槽发起评估 1
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应在空闲批发起");
        rig.h().evaluator.enqueue(unsat("还差登录页", false, null));
        await(() -> !gm.evaluationInFlight(), "verdict 1 应经 executor 回调落账");
        drainUntilSubmitted(rig.v(), rig.h(), 2);

        assertTrue(rig.h().submitted.get(1).startsWith("[goal 继续 2/∞]"),
                "自动轮 2 前缀，实际：" + rig.h().submitted.get(1));
        assertTrue(rig.h().submitted.get(1).contains("还差登录页"),
                "第 2 轮 prompt 携带第 1 轮评估结论，实际：" + rig.h().submitted.get(1));

        rig.v().tickForTest();                    // 评估 2
        await(() -> rig.h().evaluator.calls.get() >= 2, "评估 2 应在下一空闲批发起");
        rig.h().evaluator.enqueue(unsat("登录页已好，还差设置页", false, "已完成：DB 迁移"));
        await(() -> !gm.evaluationInFlight(), "verdict 2 应落账");
        drainUntilSubmitted(rig.v(), rig.h(), 3);

        assertTrue(rig.h().submitted.get(2).startsWith("[goal 继续 3/∞]"));
        assertTrue(rig.h().submitted.get(2).contains("还差设置页"), "第 3 轮 prompt 携带第 2 轮结论");
        assertTrue(rig.h().submitted.get(2).contains("已完成：DB 迁移"),
                "STATE 账本随 verdict 透传进累积进度行，实际：" + rig.h().submitted.get(2));

        rig.v().tickForTest();                    // 评估 3
        await(() -> rig.h().evaluator.calls.get() >= 3, "评估 3 应发起");
        rig.h().evaluator.enqueue(new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部测试通过", false, null, "raw"));
        await(() -> !gm.evaluationInFlight(), "终局 verdict 应落账");

        rig.v().tickForTest();                    // verdict publish → 批 → 一行式总结

        assertEquals(GoalPhase.SATISFIED, gm.phase());
        assertEquals(3, gm.snapshot().turnsUsed(), "恰 3 轮自动轮");
        assertEquals(3, rig.h().submitted.size(), "SATISFIED 终态不再派发");
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 终态：SATISFIED"),
                "终态一行式总结恰一条，实际：" + rig.sink().lines);
        assertTrue(rig.sink().lines.stream().anyMatch(l -> l.contains("全部测试通过")
                        && l.contains("3/∞") && l.contains("token")),
                "总结含 reason/N/M/token，实际：" + rig.sink().lines);
    }

    // ── 场景②：stalled 刹车与插话重置 ──────────────────────────────────

    @Test
    @DisplayName("场景② stalled 刹车（limit=3）：stalled,adv,stalled,stalled 不停；第 4 个 stalled→PAUSED(STALLED)；用户消息恢复清零可再跑")
    void stalledBrakeAndResetWithInterleaving(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.STALLED_LIMIT_ENV, "3")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("零进展风险目标");

        // 判定序列 stalled(1) → advancing(0) → stalled(1) → stalled(2)：交错不清旧账误伤，均未达限
        rig.v().tickForTest();                    // 轮 1
        assertEquals(1, rig.h().submitted.size());
        for (int round = 1; round <= 4; round++) {
            final int n = round;                  // lambda 捕获需 effectively final
            boolean stalled = round != 2;         // 第 2 个判定 advancing
            String reason = stalled ? "第" + round + "次判定：原地打转" : "第2次判定：有实质进展";
            rig.v().tickForTest();                // 评估 round
            await(() -> rig.h().evaluator.calls.get() >= n, "评估 " + n + " 应发起");
            rig.h().evaluator.enqueue(unsat(reason, stalled, null));
            await(() -> !gm.evaluationInFlight(), "verdict " + round + " 应落账");
            drainUntilSubmitted(rig.v(), rig.h(), round + 1);
            assertEquals(GoalPhase.RUNNING, gm.phase(), "连击未达 3 不得刹车（第 " + round + " 判定后）");
        }
        assertEquals(2, gm.snapshot().stalledStreak(), "末两个 stalled 计连击 2（advancing 已清过零）");

        rig.v().tickForTest();                    // 评估 5：第 4 个 stalled → 连击 3
        await(() -> rig.h().evaluator.calls.get() >= 5, "评估 5 应发起");
        rig.h().evaluator.enqueue(unsat("第5次判定：彻底停滞", true, null));
        await(() -> !gm.evaluationInFlight(), "verdict 5 应落账");

        assertEquals(GoalPhase.PAUSED, gm.phase(), "第 4 个 stalled 达限 3 → 刹车");
        assertEquals(PauseReason.STALLED, gm.snapshot().pauseReason());
        assertEquals(5, rig.h().submitted.size(), "刹车即断流：不派发第 6 轮");
        rig.v().tickForTest();
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 已暂停（STALLED）"),
                "暂停一行式总结恰一条，实际：" + rig.sink().lines);

        // 用户消息恢复：RUNNING + 熔断计数清零
        rig.v().setInputForTest("人来了，换思路");
        rig.v().feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(GoalPhase.RUNNING, gm.phase(), "用户消息恢复 RUNNING（onUserDispatch）");
        assertEquals(6, rig.h().submitted.size());
        assertEquals("人来了，换思路", rig.h().submitted.get(5));
        assertEquals(0, gm.snapshot().stalledStreak(), "恢复即清零");

        // 恢复后可再跑：单次 stalled 不再立即刹车，下一自动轮照常派发
        rig.v().tickForTest();                    // 该轮已结束（桩不置 busy）：goal 槽接续评估
        await(() -> rig.h().evaluator.calls.get() >= 6, "恢复后应重新评估");
        rig.h().evaluator.enqueue(unsat("恢复后单次停滞", true, null));
        await(() -> !gm.evaluationInFlight(), "恢复后的 verdict 应落账");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "清零后单次 stalled 不刹车");
        assertEquals(1, gm.snapshot().stalledStreak());
        drainUntilSubmitted(rig.v(), rig.h(), 7);
        assertTrue(rig.h().submitted.get(6).startsWith("[goal 继续 6/∞]"),
                "用户轮不烧配额：恢复后自动轮次续到 6，实际：" + rig.h().submitted.get(6));
    }

    // ── 场景③：首轮 IMPOSSIBLE ─────────────────────────────────────────

    @Test
    @DisplayName("场景③ 首轮 verdict IMPOSSIBLE：终态即停、无第 2 次 submit、一行式总结")
    void firstTurnImpossible(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");

        rig.v().tickForTest();                    // 自动轮 1
        assertEquals(1, rig.h().submitted.size());
        rig.v().tickForTest();                    // 评估 1
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        rig.h().evaluator.enqueue(new GoalVerdict(GoalVerdict.Outcome.IMPOSSIBLE,
                "依赖的旧接口已下线", true, null, "raw"));
        await(() -> !gm.evaluationInFlight(), "verdict 应落账");

        rig.v().tickForTest();

        assertEquals(GoalPhase.IMPOSSIBLE, gm.phase());
        assertEquals(1, rig.h().submitted.size(), "IMPOSSIBLE 终态：无第 2 次派发");
        assertFalse(gm.hasAutoTurnPending());
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 终态：IMPOSSIBLE"),
                "实际：" + rig.sink().lines);
        assertTrue(rig.sink().lines.stream().anyMatch(l -> l.contains("依赖的旧接口已下线")),
                "总结携带 reason，实际：" + rig.sink().lines);
    }

    // ── 场景④：maxTurns 边界与用户轮免费 ───────────────────────────────

    @Test
    @DisplayName("场景④ maxTurns=2：恰 2 轮自动 submit 后第 3 次决策点 MAX_TURNS；中间用户消息轮不烧配额")
    void maxTurnsBoundaryAndUserTurnsFree(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.MAX_TURNS_ENV, "2")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");

        rig.v().tickForTest();                    // 轮 1（turnsUsed=1）
        assertEquals(1, rig.h().submitted.size());
        assertTrue(rig.h().submitted.get(0).startsWith("[goal 继续 1/2]"));
        rig.v().tickForTest();                    // 评估 1
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        rig.h().evaluator.enqueue(unsat("还差一点", false, null));
        await(() -> !gm.evaluationInFlight(), "verdict 1 应落账");
        drainUntilSubmitted(rig.v(), rig.h(), 2); // 轮 2（turnsUsed=2）
        assertTrue(rig.h().submitted.get(1).startsWith("[goal 继续 2/2]"));
        assertEquals(2, gm.snapshot().turnsUsed());

        // 中间插入用户消息轮（排队出队路径）：不烧自动轮配额。
        // 轮 2 派发（takeAutoTurn 自身 publish）的后续批已在飞评估 2——先钉这个链条形态。
        assertTrue(gm.evaluationInFlight(), "轮 2 派发后空闲链应在飞评估 2");
        await(() -> rig.h().evaluator.calls.get() >= 2, "评估 2 应已在飞");
        rig.s().enqueue("用户补充信息", null);
        rig.v().tickForTest();

        assertEquals(3, rig.h().submitted.size());
        assertEquals("用户补充信息", rig.h().submitted.get(2));
        assertEquals(2, gm.snapshot().turnsUsed(), "用户消息轮不计入 maxTurns 配额");
        assertEquals(GoalPhase.RUNNING, gm.phase());

        // 插话推进对话边界 → 在飞评估 2 的判定过期（serial 不符，spec §5.2「挂起 verdict 单槽」）：
        // 判定丢弃、只清在飞标志，恢复后的下一空闲批重评
        rig.h().evaluator.enqueue(unsat("过期的判定", false, null));
        await(() -> !gm.evaluationInFlight(), "verdict 2 应到账（哪怕被丢）");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "过期判定不得改判/置 pending");
        assertFalse(gm.hasAutoTurnPending(), "过期判定不得留待发轮");
        assertEquals(3, rig.h().submitted.size(), "过期判定不得触发派发");

        // 用户轮结束 → goal 槽重新评估（新 serial 锁存）→ UNSAT 置 pending → 第 3 次决策点 MAX_TURNS
        rig.v().tickForTest();
        await(() -> rig.h().evaluator.calls.get() >= 3, "用户轮后应重新评估");
        rig.h().evaluator.enqueue(unsat("仍未完成", false, null));
        await(() -> !gm.evaluationInFlight(), "verdict 3 应落账");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "轮数是软超限：verdict 照常放行置 pending");

        rig.v().tickForTest();                    // 决策点：takeAutoTurn → null + MAX_TURNS

        assertEquals(GoalPhase.MAX_TURNS, gm.phase());
        assertEquals(3, rig.h().submitted.size(), "第 3 次自动派发被熔断");
        assertFalse(gm.hasAutoTurnPending());
        assertEquals(2, gm.snapshot().turnsUsed(), "第 3 轮未发生：配额停在 2/2");
        drainUntilLine(rig.v(), rig.sink(), "◎ goal 终态：MAX_TURNS");
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 终态：MAX_TURNS"),
                "快照差分：重复 drain 不重复打印，实际：" + rig.sink().lines);
        assertTrue(rig.sink().lines.stream().anyMatch(l -> l.contains("2/2")),
                "总结轮次 2/2，实际：" + rig.sink().lines);
    }

    // ── 场景⑤：预算三态 ────────────────────────────────────────────────

    @Test
    @DisplayName("场景⑤ 预算：决策点 BUDGET_EXCEEDED；软超限不夺走在飞轮 verdict；budget=0 永不触发（评估耗量同链计账）")
    void budgetFirstSoftExceededAndZeroDisables(@TempDir Path root) {
        // (a) 决策点熔断：评估在飞时喂 1500（评估器耗量计入同一 accumulator 链）→ verdict 仍放行，
        //     pending 置位后的下一次 takeAutoTurn → BUDGET_EXCEEDED，不派发第 2 轮
        Rig a = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.TOKEN_BUDGET_ENV, "1000")), root);
        GoalManager gma = a.h().gm;
        assertNotNull(a.h().usage, "配了预算就应注入真 accumulator");
        gma.activate("迁移完成且测试全绿");
        a.v().tickForTest();
        assertEquals(1, a.h().submitted.size());
        a.v().tickForTest();
        await(() -> a.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        a.h().usage.record(new FakeUsage(1500, 0));          // 评估调用耗量同链计账
        a.h().evaluator.enqueue(unsat("未达成", false, null));
        await(() -> !gma.evaluationInFlight(), "verdict 1 应落账");
        assertEquals(GoalPhase.RUNNING, gma.phase(), "预算只在决策点清算，不拦截 verdict 放行");

        a.v().tickForTest();                                 // 决策点

        assertEquals(GoalPhase.BUDGET_EXCEEDED, gma.phase());
        assertEquals(1, a.h().submitted.size(), "预算超限不再派发第 2 轮");
        drainUntilLine(a.v(), a.sink(), "◎ goal 终态：BUDGET_EXCEEDED");
        assertEquals(1, countLinesContaining(a.sink().lines, "◎ goal 终态：BUDGET_EXCEEDED"),
                "快照差分：重复 drain 不重复打印，实际：" + a.sink().lines);
        assertTrue(a.sink().lines.stream().anyMatch(l -> l.contains("token 1500/1000")),
                "总结含超限用量与预算，实际：" + a.sink().lines);

        // (b) 软超限：轮 2 已 dispatch 之后才喂超量 → 该轮 verdict（SATISFIED）仍处理完
        Rig b = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.TOKEN_BUDGET_ENV, "1000")), root);
        GoalManager gmb = b.h().gm;
        gmb.activate("迁移完成且测试全绿");
        b.v().tickForTest();                                 // 轮 1
        b.v().tickForTest();                                 // 评估 1
        await(() -> b.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        b.h().usage.record(new FakeUsage(600, 0));           // 决策点前 600 < 1000：轮 2 放行
        b.h().evaluator.enqueue(unsat("推进中", false, null));
        await(() -> !gmb.evaluationInFlight(), "verdict 1 应落账");
        drainUntilSubmitted(b.v(), b.h(), 2);
        b.v().tickForTest();                                 // 评估 2（轮 2 在飞）
        await(() -> b.h().evaluator.calls.get() >= 2, "评估 2 应发起");
        b.h().usage.record(new FakeUsage(500, 0));           // 轮 2 在飞期间超量（1100 ≥ 1000）
        assertTrue(gmb.budgetExceeded(), "软超限应成立");
        b.h().evaluator.enqueue(new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "提前完成", false, null, "raw"));
        await(() -> !gmb.evaluationInFlight(), "verdict 2 应落账");

        b.v().tickForTest();

        assertEquals(GoalPhase.SATISFIED, gmb.phase(),
                "软超限不夺走在飞轮的 verdict：判 SATISFIED 而非 BUDGET_EXCEEDED");
        assertTrue(b.sink().lines.stream().anyMatch(l ->
                        l.contains("◎ goal 终态：SATISFIED") && l.contains("token 1100/1000")),
                "总结按超限后的真实用量记账，实际：" + b.sink().lines);

        // (c) budget=0：永不触发——accumulator 已注入且巨量超耗，自动轮照常派发
        Rig c = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.TOKEN_BUDGET_ENV, "0")), root);
        GoalManager gmc = c.h().gm;
        assertNotNull(c.h().usage, "budget=0 也注入 accumulator：钉「0=关闭」而非「未注入=关闭」");
        gmc.activate("迁移完成且测试全绿");
        c.v().tickForTest();
        assertEquals(1, c.h().submitted.size());
        c.v().tickForTest();
        await(() -> c.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        c.h().usage.record(new FakeUsage(10_000_000, 10_000_000));
        c.h().evaluator.enqueue(unsat("预算关闭照跑", false, null));
        await(() -> !gmc.evaluationInFlight(), "verdict 应落账");
        drainUntilSubmitted(c.v(), c.h(), 2);

        assertEquals(GoalPhase.RUNNING, gmc.phase(), "budget=0 永不熔断");
        assertFalse(gmc.budgetExceeded());
    }

    // ── 场景⑥：onError 家族 ────────────────────────────────────────────

    @Test
    @DisplayName("场景⑥ onError 家族：豁免族（取消/空流）不计；普通异常连续 errorRetry+1 → PAUSED(ERROR)+总结；onTurnCompleted 重置")
    void onErrorFamily(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of()), root);   // env 空：errorRetry 默认 2
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");
        rig.v().tickForTest();
        assertEquals(1, rig.h().submitted.size());

        // 豁免族：Esc 取消与空流（瞬态）不进错误连击
        for (int i = 0; i < 3; i++) gm.onTurnError(new CancellationException());
        for (int i = 0; i < 3; i++) gm.onTurnError(new EmptyStreamException("网关空流"));
        assertEquals(GoalPhase.RUNNING, gm.phase(), "豁免族不计连击、不刹车");

        // 普通异常：连续 2 次（恰 errorRetry）未达 errorRetry+1
        gm.onTurnError(new RuntimeException("网络 500"));
        gm.onTurnError(new RuntimeException("读超时"));
        assertEquals(GoalPhase.RUNNING, gm.phase(), "未达 errorRetry+1 不刹车");

        // 成功轮重置连击：若未重置，下一条错误累计 3 就该刹
        gm.onTurnCompleted();
        gm.onTurnError(new RuntimeException("重置后首错"));
        assertEquals(GoalPhase.RUNNING, gm.phase(), "onTurnCompleted 应重置错误连击");

        // 重置后再连 3 次 → PAUSED(ERROR)
        gm.onTurnError(new RuntimeException("再错其一"));
        gm.onTurnError(new RuntimeException("再错其二"));
        assertEquals(GoalPhase.PAUSED, gm.phase(), "连续 errorRetry+1 次 → 刹车");
        assertEquals(PauseReason.ERROR, gm.snapshot().pauseReason());
        assertEquals(1, rig.h().submitted.size(), "ERROR 熔断不再派发自动轮");

        rig.v().tickForTest();
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 已暂停（ERROR）"),
                "暂停一行式总结恰一条，实际：" + rig.sink().lines);
    }
}
