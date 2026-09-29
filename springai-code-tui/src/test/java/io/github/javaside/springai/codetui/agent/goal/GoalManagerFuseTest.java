package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.llm.EmptyStreamException;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4 熔断矩阵与预算钉子：轮数 / 停滞 / 预算 / onError 家族独立计数、
 * 回调即终点（epoch + dispatchSerial 双校验）、takeAutoTurn 决策点优先级。
 * 语义红线逐条对号：每条注释组 = 一组真断言。
 */
class GoalManagerFuseTest {

    /** Usage 桩：Spring AI 2.x 接口只有 getPromptTokens/getCompletionTokens/getNativeUsage 是抽象方法。 */
    record FakeUsage(long prompt, long completion) implements Usage {
        @Override
        public Integer getPromptTokens() {
            return (int) prompt;
        }

        @Override
        public Integer getCompletionTokens() {
            return (int) completion;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }

    /** maxTurns, stalledLimit, tokenBudget, turnGap=0, errorRetry, evalFailLimit, protocolFailLimit */
    private static GoalConfig cfg(int maxTurns, int stalledLimit, long budget,
                                  int errorRetry, int evalFailLimit, int protocolFailLimit) {
        return new GoalConfig(maxTurns, stalledLimit, budget, 0,
                errorRetry, evalFailLimit, protocolFailLimit, 60, "");
    }

    /** brief 推荐的小值基线：maxTurns=2, stalledLimit=2, budget=1000, gap=0, errorRetry=1, eval=2, protocol=3。 */
    private static GoalConfig fuseCfg() {
        return cfg(2, 2, 1000L, 1, 2, 3);
    }

    private static GoalVerdict unsat(boolean stalled) {
        return new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "继续推进", stalled, null, "raw");
    }

    /** 消费挂起自动轮并就地发起评估（takeAutoTurn → beginEvaluation，均须成功）。 */
    private static long takeThenBegin(GoalManager gm) {
        String prompt = gm.takeAutoTurn();
        assertNotNull(prompt, "应有挂起自动轮可取");
        long epoch = gm.beginEvaluation();
        assertNotEquals(-1L, epoch, "评估应可发起");
        return epoch;
    }

    @Test
    void maxTurnsOnlyCountsAutoTurns() {
        GoalManager gm = new GoalManager(fuseCfg(), null);      // maxTurns=2
        gm.activate("g");
        assertEquals(0, gm.snapshot().turnsUsed());

        // 用户派发轮不经 takeAutoTurn → 不烧配额：插话清 pending，但轮次为 0、无终态
        gm.onUserDispatch();
        assertEquals(0, gm.snapshot().turnsUsed());
        assertFalse(gm.hasAutoTurnPending());
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertNull(gm.takeAutoTurn());                          // 无 pending 可取，且不误判轮数耗尽
        assertEquals(GoalPhase.RUNNING, gm.phase());

        // takeAutoTurn 两次成功返回 prompt（配额只按自动轮烧）
        long e0 = gm.beginEvaluation();                         // 先评插话轮（无 pending 阻塞）
        assertNotEquals(-1L, e0);
        gm.onVerdict(e0, unsat(false));                         // advancing → pending 回置
        assertNotNull(gm.takeAutoTurn());
        assertEquals(1, gm.snapshot().turnsUsed());
        long e1 = gm.beginEvaluation();
        assertNotEquals(-1L, e1);
        gm.onVerdict(e1, unsat(false));
        assertNotNull(gm.takeAutoTurn());
        assertEquals(2, gm.snapshot().turnsUsed());

        // 第三次：返回 null 且 phase=MAX_TURNS
        long e2 = gm.beginEvaluation();
        assertNotEquals(-1L, e2);
        gm.onVerdict(e2, unsat(false));
        assertNull(gm.takeAutoTurn());
        assertEquals(GoalPhase.MAX_TURNS, gm.phase());
    }

    @Test
    void maxTurnsZeroMeansUnlimitedAutoTurns() {
        GoalManager gm = new GoalManager(cfg(0, 2, 0L, 1, 2, 3), null);   // maxTurns=0=无上限、budget=0=关
        gm.activate("g");                         // activate 置首轮 pending
        // 跑过旧默认 25 的量级（此处 30 轮）也不得触发 MAX_TURNS 终态
        for (int i = 1; i <= 30; i++) {
            assertNotNull("第 " + i + " 轮应可派发（无上限）", gm.takeAutoTurn());
            long e = gm.beginEvaluation();
            assertNotEquals(-1L, e);
            gm.onVerdict(e, unsat(false));        // advancing → 置下一轮 pending
            assertEquals(GoalPhase.RUNNING, gm.phase());
        }
        assertEquals(30, gm.snapshot().turnsUsed());
    }

    @Test
    void stalledStreakFromVerdictAndReset() {
        GoalConfig c = cfg(10, 2, 0L, 1, 2, 3);                 // stalledLimit=2
        GoalManager gm = new GoalManager(c, null);

        // advancing（stalled=false）重置连击
        gm.activate("g");
        long e1 = takeThenBegin(gm);
        gm.onVerdict(e1, unsat(true));
        assertEquals(1, gm.snapshot().stalledStreak());
        assertEquals(GoalPhase.RUNNING, gm.phase());
        long e2 = takeThenBegin(gm);
        gm.onVerdict(e2, unsat(false));                          // advancing → 清零
        assertEquals(0, gm.snapshot().stalledStreak());
        long e3 = takeThenBegin(gm);
        gm.onVerdict(e3, unsat(true));
        assertEquals(1, gm.snapshot().stalledStreak());          // 未因旧连击误暂停

        // onUserDispatch 重置（RUNNING 中途插话也打断连击）
        gm.onUserDispatch();
        assertEquals(0, gm.snapshot().stalledStreak());

        // 达 stalledLimit → PAUSED(STALLED)，且此时不置 pending、无倒计时
        GoalManager gm2 = new GoalManager(c, null);
        gm2.activate("g");
        long b1 = takeThenBegin(gm2);
        gm2.onVerdict(b1, unsat(true));                          // streak=1 < 2 → pending 照常
        assertTrue(gm2.hasAutoTurnPending());
        long b2 = takeThenBegin(gm2);
        gm2.onVerdict(b2, unsat(true));                          // streak=2 ≥ 2 → 熔断
        assertEquals(GoalPhase.PAUSED, gm2.phase());
        assertEquals(PauseReason.STALLED, gm2.snapshot().pauseReason());
        assertFalse(gm2.hasAutoTurnPending());                   // 熔断即断流：不置 pending
        assertNull(gm2.gapDeadlineEpochMs());

        // PAUSED(任意) → 用户派发恢复且计数清零
        gm2.onUserDispatch();
        assertEquals(GoalPhase.RUNNING, gm2.phase());
        assertEquals(0, gm2.snapshot().stalledStreak());
    }

    // ── I1：stalled 熔断的「本轮零工具调用」机器信号（spec §7） ──────────

    @Test
    void zeroToolCallTurnCountsAsStalledEvenWhenVerdictAdvancing() {
        GoalConfig c = cfg(10, 2, 0L, 1, 2, 3);                 // stalledLimit=2
        GoalManager gm = new GoalManager(c, null);
        gm.activate("g");

        // 评估器说 advancing，但本轮一个工具都没调用（机器信号）→ streak+1
        long e1 = takeThenBegin(gm);
        gm.recordTurnMaterial(new GoalTurnMaterial("嘴上说在推进", 0, null));
        gm.onVerdict(e1, unsat(false));                          // stalled=false 也不行：零工具即停滞
        assertEquals(1, gm.snapshot().stalledStreak(), "零工具轮按停滞记账（spec §7 机器信号）");

        // 第二轮同形 → 达 stalledLimit=2 → PAUSED(STALLED)
        long e2 = takeThenBegin(gm);
        gm.recordTurnMaterial(new GoalTurnMaterial("还是没动工具", 0, null));
        gm.onVerdict(e2, unsat(false));
        assertEquals(GoalPhase.PAUSED, gm.phase(), "零工具连击同样触发 STALLED 熔断");
        assertEquals(PauseReason.STALLED, gm.snapshot().pauseReason());

        // 有工具调用且 advancing → 重置（对照）
        GoalManager gm2 = new GoalManager(c, null);
        gm2.activate("g");
        long f1 = takeThenBegin(gm2);
        gm2.recordTurnMaterial(new GoalTurnMaterial("真干活", 3, null));
        gm2.onVerdict(f1, unsat(true));                          // stalled=true 但有工具：仍按 verdict 计 1
        assertEquals(1, gm2.snapshot().stalledStreak());
        long f2 = takeThenBegin(gm2);
        gm2.recordTurnMaterial(new GoalTurnMaterial("真干活第二轮", 2, null));
        gm2.onVerdict(f2, unsat(false));                         // advancing + 工具>0 → 清零
        assertEquals(0, gm2.snapshot().stalledStreak(), "advancing 且有工具调用：连击重置");
    }

    @Test
    void noRecordedMaterialMeansNoMachineSignal() {
        // 从未 recordTurnMaterial（单测直调链/素材缺失）：机器信号缺位，只按 verdict.stalled() 计账——
        // 「没记录」不得误判成「零工具」（stalledStreakFromVerdictAndReset 全链就是这个前提，此处钉死）
        GoalManager gm = new GoalManager(cfg(10, 2, 0L, 1, 2, 3), null);
        gm.activate("g");
        long e = takeThenBegin(gm);
        gm.onVerdict(e, unsat(false));                           // 无素材 + advancing → 重置
        assertEquals(0, gm.snapshot().stalledStreak(), "无素材=无机器信号，不误伤 advancing 重置");
    }

    @Test
    void verdictAlwaysSetsPendingAndGapDeadline() {
        // gap>0：pending 恒真 + gapDeadlineEpochMs = now + gap*1000（deadline 只负责延迟）
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        GoalManager gm = new GoalManager(new GoalConfig(10, 2, 1000L, 5, 1, 2, 3, 60, ""), null, clock);
        gm.activate("g");
        long e = takeThenBegin(gm);
        gm.onVerdict(e, unsat(false));
        assertTrue(gm.hasAutoTurnPending());                     // 决不允许 pending=false 等 deadline 的断流形态
        assertEquals(1_000_000L + 5 * 1000L, gm.gapDeadlineEpochMs());

        // gap=0：pending 恒真 + deadline 为 null
        GoalManager gm2 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm2.activate("g");
        long e2 = takeThenBegin(gm2);
        gm2.onVerdict(e2, unsat(false));
        assertTrue(gm2.hasAutoTurnPending());
        assertNull(gm2.gapDeadlineEpochMs());
    }

    @Test
    void budgetDeltaAndSoftExceeded() {
        // activate 前喂 5000 → 基线；activate 后再喂 6000 → 增量 6000 ≥ budget 6000
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        acc.record(new FakeUsage(5000, 0));
        GoalManager gm = new GoalManager(cfg(10, 2, 6000L, 1, 2, 3), acc);
        gm.activate("g");
        assertFalse(gm.budgetExceeded());                        // 增量 0
        acc.record(new FakeUsage(3000, 3000));
        assertTrue(gm.budgetExceeded());                         // (8000-5000)+(3000-0)=6000 ≥ 6000
        assertEquals(GoalPhase.RUNNING, gm.phase());             // 软超限：决策点之前不熔断

        // 边界：spent == budget 即超（>= 口径）
        TokenUsageAccumulator acc2 = new TokenUsageAccumulator();
        GoalManager gm2 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), acc2);
        gm2.activate("g");
        acc2.record(new FakeUsage(600, 399));
        assertFalse(gm2.budgetExceeded());                       // 999 < 1000
        acc2.record(new FakeUsage(0, 1));
        assertTrue(gm2.budgetExceeded());                        // 1000 ≥ 1000

        // budget=0 → 预算关闭，永不超限
        TokenUsageAccumulator acc3 = new TokenUsageAccumulator();
        GoalManager gm3 = new GoalManager(cfg(10, 2, 0L, 1, 2, 3), acc3);
        gm3.activate("g");
        acc3.record(new FakeUsage(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4));
        assertFalse(gm3.budgetExceeded());

        // usage==null → 永不清算
        assertFalse(new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null).budgetExceeded());
    }

    // ── M7：评估启动前的预算决策点（spec §7「评估启动前」） ─────────────

    @Test
    void beginEvaluationClearsBudgetBeforeStartingEvaluation() {
        // 轮 1 发走后耗量超限（600+900 ≥ 1000）→ 下一批先撞 takeAutoTurn 那侧的 pending；
        // 这里构造「无 pending、纯空闲」的形态：插话轮烧掉 pending 后，评估启动前清算预算。
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        GoalManager gm = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), acc);
        gm.activate("g");
        assertNotNull(gm.takeAutoTurn());                     // 轮 1：pending 清、turnsUsed=1
        acc.record(new FakeUsage(600, 0));
        acc.record(new FakeUsage(0, 900));                    // 增量 1500 ≥ 1000
        assertTrue(gm.budgetExceeded(), "前置：预算软超限");

        assertEquals(-1L, gm.beginEvaluation(), "评估启动前预算超限 → -1（不再烧一次评估调用）");
        assertEquals(GoalPhase.BUDGET_EXCEEDED, gm.phase(), "评估启动前清算 → BUDGET_EXCEEDED 终态");
        assertFalse(gm.evaluationInFlight(), "未置在飞标志");

        // 未超限对照：同样无 pending 的空闲形态 → 正常置位评估
        TokenUsageAccumulator acc2 = new TokenUsageAccumulator();
        GoalManager gm2 = new GoalManager(cfg(10, 2, 100_000L, 1, 2, 3), acc2);
        gm2.activate("g");
        gm2.takeAutoTurn();
        acc2.record(new FakeUsage(100, 0));
        assertTrue(gm2.beginEvaluation() > 0, "未超限：评估照常可发起");
    }

    @Test
    void beginEvaluationBudgetTakesPriorityOverMaxTurns() {
        // 预算与轮数同真：beginEvaluation 决策点预算优先（BUDGET > MAX_TURNS，与 takeAutoTurn 同序）
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        GoalManager gm = new GoalManager(cfg(1, 2, 1000L, 1, 2, 3), acc);   // maxTurns=1
        gm.activate("g");
        gm.takeAutoTurn();                                    // 唯一轮已用：轮数同样耗尽
        acc.record(new FakeUsage(2000, 0));
        assertEquals(-1L, gm.beginEvaluation());
        assertEquals(GoalPhase.BUDGET_EXCEEDED, gm.phase(),
                "spec §7 优先级：预算先于轮数清算——原因唯一 BUDGET_EXCEEDED 而非 MAX_TURNS");
    }

    @Test
    void errorStreakAndEmptyStreamExempt() {
        // errorRetry=1 → 连续 2 次普通错误 → PAUSED(ERROR)
        GoalManager gm = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm.activate("g");
        gm.onTurnError(new RuntimeException("boom1"));
        assertEquals(GoalPhase.RUNNING, gm.phase());             // 1 < errorRetry+1
        gm.onTurnError(new RuntimeException("boom2"));
        assertEquals(GoalPhase.PAUSED, gm.phase());
        assertEquals(PauseReason.ERROR, gm.snapshot().pauseReason());

        // 根因 EmptyStreamException / CancellationException（含包裹根因）不计数
        GoalManager gm2 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm2.activate("g");
        gm2.onTurnError(new EmptyStreamException("empty"));
        gm2.onTurnError(new RuntimeException("wrap", new EmptyStreamException("empty")));
        gm2.onTurnError(new CancellationException("esc"));
        gm2.onTurnError(new RuntimeException("wrap", new CancellationException("esc")));
        assertEquals(GoalPhase.RUNNING, gm2.phase());            // 四连豁免后仍未熔断

        // onTurnCompleted 清零：1 错 → 成功 → 1 错仍未停（未清零则第 2 错即停）
        gm2.onTurnError(new RuntimeException("e1"));
        assertEquals(GoalPhase.RUNNING, gm2.phase());
        gm2.onTurnCompleted();
        gm2.onTurnError(new RuntimeException("e2"));
        assertEquals(GoalPhase.RUNNING, gm2.phase());
        gm2.onTurnError(new RuntimeException("e3"));             // 清零后的第 2 连击 → 停
        assertEquals(GoalPhase.PAUSED, gm2.phase());
        assertEquals(PauseReason.ERROR, gm2.snapshot().pauseReason());

        // INACTIVE / 终态 / PAUSED：迟到错误一概不动（不改判既有暂停原因）
        GoalManager gm3 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm3.onTurnError(new RuntimeException("late"));
        assertEquals(GoalPhase.INACTIVE, gm3.phase());
        gm3.activate("g");
        gm3.terminate(GoalPhase.MAX_TURNS);
        gm3.onTurnError(new RuntimeException("late"));
        assertEquals(GoalPhase.MAX_TURNS, gm3.phase());
        GoalManager gm4 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm4.activate("g");
        gm4.pauseByEsc();
        gm4.onTurnError(new RuntimeException("late"));
        assertEquals(GoalPhase.PAUSED, gm4.phase());
        assertEquals(PauseReason.ESC, gm4.snapshot().pauseReason());
    }

    @Test
    void evalAndProtocolFailuresIndependent() {
        // EVALUATOR 家族：×evalFailLimit(2) → PAUSED(EVALUATOR)；不碰停滞计数
        GoalManager gm = new GoalManager(cfg(10, 10, 1000L, 1, 2, 3), null);
        gm.activate("g");
        long e1 = takeThenBegin(gm);
        gm.onEvaluationFailure(e1, new RuntimeException("timeout"));
        assertEquals(GoalPhase.RUNNING, gm.phase());             // 1 < 2
        assertEquals(0, gm.snapshot().stalledStreak());          // EVALUATOR 不入停滞账
        long e2 = gm.beginEvaluation();                          // 回调即终点：在飞已自清，可直接重评
        assertNotEquals(-1L, e2);
        gm.onEvaluationFailure(e2, new RuntimeException("timeout2"));
        assertEquals(GoalPhase.PAUSED, gm.phase());
        assertEquals(PauseReason.EVALUATOR, gm.snapshot().pauseReason());

        // PROTOCOL 单次：按 UNSATISFIED+stalled 记账（streak+1）但不派发新轮（不置 pending/deadline）
        GoalManager gm2 = new GoalManager(cfg(10, 10, 1000L, 1, 2, 3), null);
        gm2.activate("g");
        long p1 = takeThenBegin(gm2);
        gm2.onProtocolFailure(p1, "抱歉，我无法按格式输出");
        assertEquals(GoalPhase.RUNNING, gm2.phase());            // 1 < protocolFailLimit(3)
        assertFalse(gm2.hasAutoTurnPending());                   // 不 dispatch 新轮
        assertNull(gm2.gapDeadlineEpochMs());
        assertEquals(1, gm2.snapshot().stalledStreak());         // 停滞账照记
        long p2 = gm2.beginEvaluation();
        assertNotEquals(-1L, p2);
        gm2.onProtocolFailure(p2, "still no verdict");
        assertEquals(GoalPhase.RUNNING, gm2.phase());
        assertEquals(2, gm2.snapshot().stalledStreak());
        // 连续 protocolFailLimit(3) 次 → PAUSED(PROTOCOL)，lastSummary 附原始输出摘要
        long p3 = gm2.beginEvaluation();
        assertNotEquals(-1L, p3);
        gm2.onProtocolFailure(p3, "VERDICT missing entirely, model apologized");
        assertEquals(GoalPhase.PAUSED, gm2.phase());
        assertEquals(PauseReason.PROTOCOL, gm2.snapshot().pauseReason());
        assertTrue(gm2.snapshot().lastSummary().contains("VERDICT missing"));

        // 两族互不泄漏：evalFailLimit=2 / protocolFailLimit=2
        GoalManager gm3 = new GoalManager(cfg(10, 10, 1000L, 1, 2, 2), null);
        gm3.activate("g");
        long a = takeThenBegin(gm3);
        gm3.onEvaluationFailure(a, new RuntimeException("t"));   // eval 1/2
        assertEquals(GoalPhase.RUNNING, gm3.phase());
        long b = gm3.beginEvaluation();
        assertNotEquals(-1L, b);
        gm3.onProtocolFailure(b, "bad");                         // 若 eval 泄入 protocol（1+1=2）此处应已停
        assertEquals(GoalPhase.RUNNING, gm3.phase());
        long c = gm3.beginEvaluation();
        assertNotEquals(-1L, c);
        gm3.onProtocolFailure(c, "bad2");                        // protocol 2/2 → 仅 protocol 家族触发
        assertEquals(GoalPhase.PAUSED, gm3.phase());
        assertEquals(PauseReason.PROTOCOL, gm3.snapshot().pauseReason());

        GoalManager gm4 = new GoalManager(cfg(10, 10, 1000L, 1, 2, 2), null);
        gm4.activate("g");
        long d = takeThenBegin(gm4);
        gm4.onProtocolFailure(d, "bad");                         // protocol 1/2
        long f = gm4.beginEvaluation();
        gm4.onEvaluationFailure(f, new RuntimeException("t"));   // 若 protocol 泄入 eval（1+1=2）此处应已停
        assertEquals(GoalPhase.RUNNING, gm4.phase());
        long g = gm4.beginEvaluation();
        gm4.onEvaluationFailure(g, new RuntimeException("t2"));  // eval 2/2 → 仅 eval 家族触发
        assertEquals(GoalPhase.PAUSED, gm4.phase());
        assertEquals(PauseReason.EVALUATOR, gm4.snapshot().pauseReason());
    }

    @Test
    void priorityWhenMultipleTrue() {
        // 预算超限 + 轮数耗尽同时为真：决策点先判预算 → BUDGET_EXCEEDED（spec §7）
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        GoalManager gm = new GoalManager(cfg(1, 2, 1000L, 1, 2, 3), acc);   // maxTurns=1
        gm.activate("g");
        assertNotNull(gm.takeAutoTurn());                        // 唯一自动轮：maxTurns 即刻耗尽
        assertEquals(1, gm.snapshot().turnsUsed());
        long e = gm.beginEvaluation();
        assertNotEquals(-1L, e);
        gm.onVerdict(e, unsat(false));                           // pending 回置
        acc.record(new FakeUsage(2000, 0));                      // 预算同时超限
        assertEquals(GoalPhase.RUNNING, gm.phase());             // 软超限在决策点前不动
        assertNull(gm.takeAutoTurn());
        assertEquals(GoalPhase.BUDGET_EXCEEDED, gm.phase());     // 不是 MAX_TURNS
        // 终态单调：BUDGET_EXCEEDED 后一切迟到判定 no-op
        gm.onVerdict(gm.currentEpoch(), unsat(false));
        gm.onTurnError(new RuntimeException("late"));
        gm.onTurnCompleted();
        assertEquals(GoalPhase.BUDGET_EXCEEDED, gm.phase());

        // terminate(CANCELLED) 优先于一切迟到判定
        GoalManager gm2 = new GoalManager(cfg(1, 2, 1000L, 1, 2, 3), acc);
        gm2.activate("g");
        gm2.terminate(GoalPhase.CANCELLED);
        assertEquals(-1L, gm2.beginEvaluation());                // 终态不评估
        gm2.onVerdict(gm2.currentEpoch(), unsat(true));
        gm2.onProtocolFailure(gm2.currentEpoch(), "x");
        gm2.onEvaluationFailure(gm2.currentEpoch(), new RuntimeException("x"));
        gm2.onTurnError(new RuntimeException("x"));
        assertEquals(GoalPhase.CANCELLED, gm2.phase());
        assertNull(gm2.snapshot().pauseReason());
    }

    @Test
    void beginEvaluationCasAndCallbacksSelfClear() {
        GoalManager gm = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm.activate("g");
        long epoch1 = gm.currentEpoch();

        // 首轮 pending 在 → beginEvaluation -1（先发轮、不评估）
        assertEquals(-1L, gm.beginEvaluation());
        long e = takeThenBegin(gm);
        assertEquals(epoch1, e);
        // CAS：在飞评估 → 第二次 begin -1
        assertEquals(-1L, gm.beginEvaluation());
        assertTrue(gm.evaluationInFlight());
        // onVerdict 回调后：正常处理 + evalInFlight 复原
        gm.onVerdict(e, unsat(false));
        assertTrue(gm.hasAutoTurnPending());
        assertFalse(gm.evaluationInFlight());

        // activate 换代：evalInFlight 复位，begin 返回新 epoch；旧代回调不清新代标志
        gm.activate("g2");
        long epoch2 = gm.currentEpoch();
        assertNotEquals(epoch1, epoch2);
        assertEquals(-1L, gm.beginEvaluation());                 // 新代首轮 pending
        long e2 = takeThenBegin(gm);
        assertEquals(epoch2, e2);
        gm.onVerdict(epoch1, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "done", false, null, "raw"));
        assertEquals(GoalPhase.RUNNING, gm.phase());             // 旧代判定丢弃：不终态
        assertTrue(gm.evaluationInFlight());                     // 新代在飞标志未被旧代清掉
        gm.onVerdict(epoch2, unsat(false));
        assertFalse(gm.evaluationInFlight());                    // 同代回调自清
        assertTrue(gm.hasAutoTurnPending());

        // SATISFIED → 终态 + lastSummary + 在飞清零
        String prompt = gm.takeAutoTurn();
        assertNotNull(prompt);
        long e3 = gm.beginEvaluation();
        assertNotEquals(-1L, e3);
        gm.onVerdict(e3, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部完成", false, null, "raw"));
        assertEquals(GoalPhase.SATISFIED, gm.phase());
        assertEquals("全部完成", gm.snapshot().lastSummary());
        assertFalse(gm.evaluationInFlight());
    }

    @Test
    void interjectionDuringEvalInvalidatesVerdictBySerial() {
        // a) verdict：serial 锁存后插话（serial+1）→ 判定丢弃（无 pending、无记账），但 epoch 未变、在飞已清可重评
        GoalManager gm = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm.activate("g");
        long e = takeThenBegin(gm);                              // 锁存当前 dispatchSerial
        gm.onUserDispatch();                                     // 插话：serial+1（epoch 不变）
        gm.onVerdict(e, unsat(true));                            // serial 不符 → 纯 serial 失效
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertFalse(gm.hasAutoTurnPending());                    // 判定未生效：无 pending
        assertNull(gm.gapDeadlineEpochMs());
        assertEquals(0, gm.snapshot().stalledStreak());          // 记账同样被丢弃
        assertFalse(gm.evaluationInFlight());                    // 自己的在飞标志已清
        assertNotEquals(-1L, gm.beginEvaluation());              // 可立即重评（重评输入含插话原文）

        // b) onEvaluationFailure 同受 serial 门：evalFailLimit=1 下若计入必停 → 仍 RUNNING 即丢弃
        GoalManager gm2 = new GoalManager(cfg(10, 2, 1000L, 1, 1, 3), null);
        gm2.activate("g");
        long f = takeThenBegin(gm2);
        gm2.onUserDispatch();
        gm2.onEvaluationFailure(f, new RuntimeException("late"));
        assertEquals(GoalPhase.RUNNING, gm2.phase());
        assertFalse(gm2.evaluationInFlight());

        // c) onProtocolFailure 同受 serial 门：停滞记账被丢弃
        GoalManager gm3 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm3.activate("g");
        long p = takeThenBegin(gm3);
        gm3.onUserDispatch();
        gm3.onProtocolFailure(p, "garbage");
        assertEquals(GoalPhase.RUNNING, gm3.phase());
        assertEquals(0, gm3.snapshot().stalledStreak());
        assertFalse(gm3.evaluationInFlight());
    }

    @Test
    void pausedDiscardsLateVerdictButClearsInFlight() {
        // spec §3.3「在途评估不硬中断」：Esc 暂停（评估在飞）后迟到的判定只清自己的在飞标志，
        // 不得 terminate/pause 改判；恢复 RUNNING 后由下一空闲批重评。
        // a) verdict：PAUSED(ESC) 下迟到的 SATISFIED 不终态、不写摘要
        GoalManager gm = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm.activate("g");
        long e = takeThenBegin(gm);
        assertTrue(gm.evaluationInFlight());
        gm.pauseByEsc();                                          // 评估在飞期间第一级 Esc
        assertEquals(GoalPhase.PAUSED, gm.phase());
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason());
        gm.onVerdict(e, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "迟到的完成判定", false, null, "raw"));
        assertEquals(GoalPhase.PAUSED, gm.phase());               // 不被迟到 SATISFIED 硬中断成终态
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason());
        assertFalse(gm.evaluationInFlight());                     // 但自己的在飞标志自清（wasInFlight 才推版本）
        assertEquals("", gm.snapshot().lastSummary());            // 判定被丢弃：摘要不写入

        // 恢复 RUNNING 后在飞已清、无 pending 阻塞 → 下一空闲批可立即重评
        gm.onUserDispatch();
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertNotEquals(-1L, gm.beginEvaluation());

        // b) onEvaluationFailure 同构：PAUSED(ESC) 下迟到失败不记账（evalFailLimit=1，计入必停）
        GoalManager gm2 = new GoalManager(cfg(10, 2, 1000L, 1, 1, 3), null);
        gm2.activate("g");
        long f = takeThenBegin(gm2);
        gm2.pauseByEsc();
        gm2.onEvaluationFailure(f, new RuntimeException("late"));
        assertEquals(GoalPhase.PAUSED, gm2.phase());
        assertEquals(PauseReason.ESC, gm2.snapshot().pauseReason());   // 不改判为 EVALUATOR
        assertFalse(gm2.evaluationInFlight());

        // c) onProtocolFailure 同构：PAUSED(ESC) 下迟到协议失败不记停滞账
        GoalManager gm3 = new GoalManager(cfg(10, 2, 1000L, 1, 2, 3), null);
        gm3.activate("g");
        long q = takeThenBegin(gm3);
        gm3.pauseByEsc();
        gm3.onProtocolFailure(q, "late garbage");
        assertEquals(GoalPhase.PAUSED, gm3.phase());
        assertEquals(PauseReason.ESC, gm3.snapshot().pauseReason());
        assertEquals(0, gm3.snapshot().stalledStreak());
        assertFalse(gm3.evaluationInFlight());
    }
}
