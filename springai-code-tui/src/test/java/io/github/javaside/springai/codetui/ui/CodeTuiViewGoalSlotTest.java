package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.Text;
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
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * goal 空闲批槽 + 评估调度 + 倒计时 + 两级 Esc（Task 11）——UI 线程纪律红线的行为钉：
 * 自动轮 dispatch 只发生在空闲批、每批最多一个自动动作、槽位顺序（插话 &gt; 排队用户消息 &gt;
 * goal 槽 &gt; 后台结果）、评估回调只经 GoalManager 回 UI。
 *
 * <p>桩策略：真 {@link GoalManager} + 真 {@link GoalEvaluationRunner} + <b>可放行</b>评估器
 * （{@code evaluate} 阻塞在 {@link CompletableFuture} 上，测试手动 complete）——既走生产的
 * executor 线程与回调链，又完全确定。批驱动用 {@code tickForTest()}（既有 View 测试同法）；
 * publish 驱动的批用 {@code runPendingUiUpdatesForTest()} 受控执行。
 */
class CodeTuiViewGoalSlotTest {

    // ── 装置 ────────────────────────────────────────────────────────────

    /** 可放行评估器：首次评估阻塞在 gate 上等测试手动放行；后续评估永久挂起（桩的回合零耗时，
     *  不挂起的话「评估→放行→派发→再评估」会在测试里连锁跑飞到 maxTurns——真实世界回合耗分钟）。 */
    private static final class GatedEvaluator implements GoalEvaluator {
        final CompletableFuture<GoalVerdict> gate = new CompletableFuture<>();
        final CompletableFuture<GoalVerdict> tail = new CompletableFuture<>();
        volatile boolean firstUsed;
        volatile EvaluationInput lastInput;
        volatile int calls;

        @Override public GoalVerdict evaluate(EvaluationInput input) throws Exception {
            calls++;
            lastInput = input;
            if (firstUsed) return tail.get();    // 后续评估挂起（仍可经 calls/lastInput 观察到已起评）
            firstUsed = true;
            return gate.get();
        }
    }

    /** goal 桩：真 GoalManager + 真 GoalEvaluationRunner + 门控评估器；其余走接口默认。 */
    private static final class GoalHandler implements SubmitHandler {
        final GoalManager gm;
        final GoalEvaluationRunner runner;
        final GatedEvaluator evaluator = new GatedEvaluator();
        final List<String> submitted = new ArrayList<>();
        final List<BackgroundResult> backgrounds = new ArrayList<>();

        GoalHandler() { this("0", null); }   // 默认 gap=0（背靠背）、stalledLimit 默认 3

        GoalHandler(String turnGap, String stalledLimit) {
            GoalConfig config = GoalConfig.from(k -> {
                if (GoalConfig.TURN_GAP_ENV.equals(k)) return turnGap;
                if (GoalConfig.STALLED_LIMIT_ENV.equals(k)) return stalledLimit;
                return null;
            });
            this.gm = new GoalManager(config, null);
            this.runner = new GoalEvaluationRunner(config);
        }

        @Override public Disposable submit(String text) { submitted.add(text); return () -> { }; }
        @Override public GoalManager goal() { return gm; }
        @Override public GoalEvaluationRunner goalRunner() { return runner; }
        @Override public GoalEvaluator goalEvaluator() { return evaluator; }
        @Override public GoalTurnMaterial collectGoalMaterial() { return new GoalTurnMaterial("末文本", 2, null); }
        @Override public List<BackgroundResult> completedBackgroundTasks() { return List.copyOf(backgrounds); }
        @Override public boolean markBackgroundConsumed(String taskId) {
            return backgrounds.removeIf(r -> r.taskId().equals(taskId));
        }

        /** 手动放行评估：verdict 经真 Runner 的 executor 线程进 GoalManager 回调。 */
        void release(GoalVerdict v) { evaluator.gate.complete(v); }
    }

    /** 记录型 scrollback 接缝（CodeTuiViewPlanTest 先例）：断言 goal 一行式总结用。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    /** 轮询等待（评估在 runner 线程异步起跑/回调落定），5s 超时 fail。 */
    private static void await(BooleanSupplier cond) { await(cond, "等待条件 5s 未满足"); }

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
        fail(message);
    }

    /** publish 驱动的批受控执行：反复排空测试队列直到提交数到位（调度晚到也兜住）。 */
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
        assertEquals(n, h.submitted.size(), "publish 驱动的空闲批应派发第 " + n + " 个回合");
    }

    private static int countLinesContaining(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    // ── 空闲批槽 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("空闲批派发挂起的自动轮；本批无第二个 dispatch（后台结果让路）")
    void idleBatchDispatchesPendingAutoTurn(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.backgrounds.add(new SubmitHandler.BackgroundResult("ab12", "explore", "调查", "结论", true));
        h.gm.activate("迁移完成且测试全绿");

        v.tickForTest();

        assertEquals(1, h.submitted.size(), "空闲批应恰好派发一个自动轮：" + h.submitted);
        assertTrue(h.submitted.get(0).startsWith("[goal 继续 1/25]"),
                "自动轮 prompt 以 [goal 继续 1/25] 开头，实际：" + h.submitted.get(0));
        assertEquals(1, h.backgrounds.size(), "后台结果让路：本批不得送达");

        v.tickForTest();

        assertEquals(0, h.backgrounds.size(), "下一批才轮到后台结果");
        assertEquals(2, h.submitted.size());
        assertTrue(h.submitted.get(1).contains("ab12"), "第二批送达的是后台摘要");
    }

    @Test
    @DisplayName("排队用户消息优先于 goal 槽：本批只发用户文本；用户消息作废挂起轮，下一批 goal 槽起评估")
    void queuedUserInputBeatsGoalSlot(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("迁移完成且测试全绿");
        s.enqueue("用户先说", null);

        v.tickForTest();

        assertEquals(List.of("用户先说"), h.submitted, "排队用户消息先走：" + h.submitted);
        assertEquals(0, s.queuedCount());
        assertFalse(h.gm.hasAutoTurnPending(),
                "spec §5.2：用户消息作废挂起的自动轮（onUserDispatch），不得跟在用户文本后再发");

        v.tickForTest();                          // goal 槽没有断流：接手发起评估

        await(() -> h.evaluator.lastInput != null, "goal 槽应在下一批接手（起评估，而非发旧自动轮）");
        assertEquals("迁移完成且测试全绿", h.evaluator.lastInput.condition());
    }

    // ── 评估调度 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("回合结束后的空闲批发起评估（素材齐全），verdict 放行后下一空闲批派发自动轮 2")
    void evaluationStartedAfterTurnEndAndVerdictSchedulesNextTurn(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        v.startForTest();
        v.runPendingUiUpdatesForTest();          // 排空启动初始批，后续断言只看 verdict 触发的批
        h.gm.activate("迁移完成且测试全绿");

        v.tickForTest();                          // 自动轮 1
        assertEquals(1, h.submitted.size());
        assertEquals(0, h.evaluator.calls, "有待发轮时不评估");

        v.tickForTest();                          // 回合已结束（桩不置 busy）：goal 槽发起评估

        await(() -> h.evaluator.lastInput != null);
        EvaluationInput in = h.evaluator.lastInput;
        assertEquals("迁移完成且测试全绿", in.condition());
        assertEquals(1, in.turn(), "评估输入见已用轮次");
        assertEquals(1, in.recentTurns().size(), "本轮素材入滚动记录");
        assertEquals("末文本", in.recentTurns().get(0).assistantTail(), "素材含 agent 末文本");
        assertEquals(2, in.recentTurns().get(0).toolCallCount(), "素材含工具调用数");

        h.release(new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "还差登录页", false, null, ""));
        await(() -> !h.gm.evaluationInFlight());
        drainUntilSubmitted(v, h, 2);             // verdict publish → UI 批 → 自动轮 2

        assertTrue(h.submitted.get(1).startsWith("[goal 继续 2/25]"),
                "自动轮 2，实际：" + h.submitted.get(1));
        assertTrue(h.submitted.get(1).contains("还差登录页"), "prompt 携带评估器结论");
    }

    @Test
    @DisplayName("评估在飞时 activate 新条件：旧 verdict 按 epoch 丢弃，新 goal 仍 RUNNING")
    void staleVerdictDroppedOnNewGoal(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("条件 A");

        v.tickForTest();                          // 自动轮 1
        v.tickForTest();                          // 评估在飞（gate 未放行）
        await(() -> h.evaluator.lastInput != null);

        h.gm.activate("条件 B");                   // 换代：epoch+1、在飞标志复位
        assertEquals(GoalPhase.RUNNING, h.gm.phase());
        assertTrue(h.gm.hasAutoTurnPending(), "新 goal 置首轮 pending");

        h.release(new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "早就完成了", false, null, ""));
        await(() -> h.evaluator.gate.isDone());

        v.tickForTest();
        assertEquals(GoalPhase.RUNNING, h.gm.phase(), "旧代 verdict 丢弃：不得把新 goal 判成 SATISFIED");
        assertFalse(h.gm.snapshot().condition().equals("条件 A"));
    }

    @Test
    @DisplayName("gap 倒计时未到期不派发（⏳ 现算），到期后的空闲批派发")
    void gapCountdownDelaysDispatch(@TempDir Path root) throws Exception {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler("2", null);   // gap=2s（env 钳制范围内）
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("推进迁移");

        v.tickForTest();                          // 首轮（无倒计时）
        assertEquals(1, h.submitted.size());
        v.tickForTest();                          // 评估
        await(() -> h.evaluator.lastInput != null);
        h.release(new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "推进中", false, null, ""));
        await(() -> !h.gm.evaluationInFlight());

        v.tickForTest();
        assertEquals(1, h.submitted.size(), "倒计时未到：不派发");
        assertTrue(h.gm.hasAutoTurnPending());
        assertTrue(v.goalLeadingSpan().content().matches("◎ goal ⏳\\d+s · "),
                "倒计时 deadline 现算，实际：" + v.goalLeadingSpan().content());

        Thread.sleep(2100);                       // 等真实 2s 过去（Manager clock 包内注入，ui 测试拨不了表）
        v.tickForTest();
        assertEquals(2, h.submitted.size(), "到期后空闲批派发自动轮");
    }

    // ── 两级 Esc ────────────────────────────────────────────────────────

    @Test
    @DisplayName("两级 Esc：在飞轮 Esc→PAUSED(ESC)+提示；再 Esc→CANCELLED")
    void twoLevelEscPausesThenCancels(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("迁移完成且测试全绿");
        v.tickForTest();                          // 自动轮 1 已 dispatch
        s.onTurnStarted(1);                       // 在飞（THINKING）

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(GoalPhase.PAUSED, h.gm.phase(), "第一级 Esc：RUNNING→PAUSED(ESC)");
        assertEquals(io.github.javaside.springai.codetui.agent.goal.PauseReason.ESC,
                h.gm.snapshot().pauseReason());
        assertTrue(s.notice().contains("已暂停"),
                "要给「发送任意消息继续」的可见提示，实际：" + s.notice());

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(GoalPhase.CANCELLED, h.gm.phase(), "第二级 Esc：PAUSED(ESC)→CANCELLED");
    }

    @Test
    @DisplayName("cancelTurnFor 路径（畸形问询中断回合）同样 PAUSED(ESC)")
    void cancelTurnForPathAlsoPausesGoal(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("迁移完成且测试全绿");
        v.tickForTest();
        s.onTurnStarted(1);
        QuestionSpec noOptions = new QuestionSpec("选哪个?", "选择", List.of(), false);
        s.onQuestionAsked(1, new AskRequest(1, List.of(noOptions), new AskResponder() {
            @Override public void answer(java.util.Map<String, String> a) { }
            @Override public void cancel() { }
        }));

        v.tickForTest();                          // drain 侦测畸形问询 → cancelTurnFor

        assertEquals(GoalPhase.PAUSED, h.gm.phase(), "回合取消路径必须同走 goal 两级 Esc 语义");
        assertEquals(io.github.javaside.springai.codetui.agent.goal.PauseReason.ESC,
                h.gm.snapshot().pauseReason());
    }

    @Test
    @DisplayName("评估在飞（IDLE 态）Esc 同走一级暂停；暂停后迟到 verdict 不改判")
    void escDuringEvaluationPausesGoal(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("迁移完成且测试全绿");
        v.tickForTest();
        v.tickForTest();
        await(() -> h.evaluator.lastInput != null);   // 评估在飞，IDLE 态

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(GoalPhase.PAUSED, h.gm.phase(), "EVALUATING 中 Esc（现有取消逻辑空转）→ PAUSED(ESC)");
        assertEquals(io.github.javaside.springai.codetui.agent.goal.PauseReason.ESC,
                h.gm.snapshot().pauseReason());

        h.release(new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "迟到的达成", false, null, ""));
        await(() -> h.evaluator.gate.isDone());
        assertEquals(GoalPhase.PAUSED, h.gm.phase(), "暂停后的迟到 verdict 不得改判（spec §3.3）");
    }

    // ── 一行式总结（spec §3.4 快照差分） ────────────────────────────────

    @Test
    @DisplayName("终态与暂停各打一行式总结；重复 drain 不重复打印")
    void terminalAndPauseTransitionsPrintOneLineSummary(@TempDir Path root) {
        RecordingSink sink = new RecordingSink();
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler();
        CodeTuiView v = new CodeTuiView(s, h, root, sink);
        h.gm.activate("迁移完成且测试全绿");
        v.tickForTest();
        v.tickForTest();
        await(() -> h.evaluator.lastInput != null);
        h.release(new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部测试通过", false, null, ""));
        await(() -> !h.gm.evaluationInFlight());

        v.tickForTest();

        assertEquals(1, countLinesContaining(sink.lines, "◎ goal 终态：SATISFIED"),
                "终态一行式总结恰一条，实际：" + sink.lines);
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("全部测试通过")
                        && l.contains("1/25") && l.contains("token")),
                "总结含 reason/N/M/token，实际：" + sink.lines);

        v.tickForTest();
        assertEquals(1, countLinesContaining(sink.lines, "◎ goal 终态：SATISFIED"),
                "快照差分：重复 drain 不重复打印");

        // STALLED 刹车（独立装置：stalledLimit=1 让单次 stalled 即刹）
        RecordingSink sink2 = new RecordingSink();
        ConversationState s2 = new ConversationState();
        GoalHandler h2 = new GoalHandler("0", "1");
        CodeTuiView v2 = new CodeTuiView(s2, h2, root, sink2);
        h2.gm.activate("零进展目标");
        v2.tickForTest();
        v2.tickForTest();
        await(() -> h2.evaluator.lastInput != null);
        h2.release(new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "毫无进展", true, null, ""));
        await(() -> !h2.gm.evaluationInFlight());

        v2.tickForTest();

        assertEquals(GoalPhase.PAUSED, h2.gm.phase());
        assertEquals(1, countLinesContaining(sink2.lines, "◎ goal 已暂停（STALLED）"),
                "暂停一行式总结，实际：" + sink2.lines);
    }

    @Test
    @DisplayName("PAUSED(STALLED) 后用户消息恢复 RUNNING；该轮结束 goal 槽接续评估")
    void pausedResumesOnUserMessage(@TempDir Path root) {
        ConversationState s = new ConversationState();
        GoalHandler h = new GoalHandler("0", "1");
        CodeTuiView v = new CodeTuiView(s, h, root, new RecordingSink());
        h.gm.activate("零进展目标");
        v.tickForTest();
        v.tickForTest();
        await(() -> h.evaluator.lastInput != null);
        h.release(new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, "毫无进展", true, null, ""));
        await(() -> !h.gm.evaluationInFlight());
        assertEquals(GoalPhase.PAUSED, h.gm.phase(), "前置：已刹停");
        int submittedAfterGoalTurn = h.submitted.size();

        v.setInputForTest("人来了，先改方向");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(GoalPhase.RUNNING, h.gm.phase(), "用户消息恢复 RUNNING（onUserDispatch）");
        assertEquals(submittedAfterGoalTurn + 1, h.submitted.size(), "用户消息照常 dispatch");
        assertEquals("人来了，先改方向", h.submitted.get(h.submitted.size() - 1));

        int callsAfterResume = h.evaluator.calls;
        v.tickForTest();                          // 该轮已结束（桩不置 busy）：goal 槽接续评估

        await(() -> h.evaluator.calls > callsAfterResume,
                "暂停恢复后的轮次要重新评估（起评在 runner 线程，轮询等它记上账）");
        assertEquals(GoalPhase.RUNNING, h.gm.phase(), "评估重开不改相位");
    }
}
