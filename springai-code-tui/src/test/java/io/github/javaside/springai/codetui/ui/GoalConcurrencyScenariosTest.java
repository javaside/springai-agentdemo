package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.goal.EvaluationInput;
import io.github.javaside.springai.codetui.agent.goal.GoalConfig;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluationRunner;
import io.github.javaside.springai.codetui.agent.goal.GoalEvaluator;
import io.github.javaside.springai.codetui.agent.goal.GoalManager;
import io.github.javaside.springai.codetui.agent.goal.GoalPhase;
import io.github.javaside.springai.codetui.agent.goal.GoalText;
import io.github.javaside.springai.codetui.agent.goal.GoalTurnMaterial;
import io.github.javaside.springai.codetui.agent.goal.GoalVerdict;
import io.github.javaside.springai.codetui.agent.goal.PauseReason;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 并发场景 ⑦–⑭（Task 13 D）——「慢评估」家族 + 僵尸防护 + 压缩存活，全部围绕同一条时序：
 * 评估在 runner 线程<b>挂起</b>（门未放行）时 UI 侧发生了一次打扰（插话 / Esc / /clear / /goal 换代），
 * 迟到的 verdict 必须按门（epoch / phase / dispatchSerial）正确作废，不复活、不改判、不烧预算。
 *
 * <p>装置照 {@code GoalIntegrationScenariosTest}（Task 12 Harness）扩展三点：
 * <ul>
 *   <li>门可<b>异常放行</b>（evaluator 连抛 / 协议失败风暴——原始异常剥 ExecutionException 直捅
 *       Runner，同生产 {@code ChatClientGoalEvaluator} 的异常形态）；</li>
 *   <li>SubmitHandler 桩带记录型 {@link SessionService}（评估结论落库形状断言），
 *       {@code clearContext} 镜像 {@code CodingAgent}（clear goal + 换 sessionId）、
 *       {@code submit} 记录调用线程与模拟耗量（自动轮全落 UI 线程 / 预算不涨断言）；</li>
 *   <li>全部等待有界（5s 超时 fail），断言全部真断言。</li>
 * </ul>
 */
class GoalConcurrencyScenariosTest {

    // ── 装置（Task 12 Harness 扩展） ────────────────────────────────────

    /** Spring AI 2.x Usage 桩（GoalManagerFuseTest 同款）：只有三个抽象方法。 */
    private record FakeUsage(long prompt, long completion) implements Usage {
        @Override public Integer getPromptTokens() { return (int) prompt; }
        @Override public Integer getCompletionTokens() { return (int) completion; }
        @Override public Object getNativeUsage() { return null; }
    }

    /**
     * 脚本化门控评估器：每次 {@code evaluate} 从门队列取一块（无门则挂起等测试补挂），
     * 测试手动放行 verdict 或<b>异常</b>（异常剥 {@link ExecutionException} 后原样抛——Runner 只
     * 剥一层 CompletionException，再包一层会让协议失败静默降级成调用失败）。
     */
    private static final class ScriptedEvaluator implements GoalEvaluator {
        private final LinkedBlockingDeque<CompletableFuture<GoalVerdict>> gates = new LinkedBlockingDeque<>();
        final List<EvaluationInput> inputs = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        /** 挂一块放行门（按调用顺序消费），返回门柄——可先挂门后补 verdict/异常。 */
        CompletableFuture<GoalVerdict> gate() {
            CompletableFuture<GoalVerdict> g = new CompletableFuture<>();
            gates.addLast(g);
            return g;
        }

        void enqueue(GoalVerdict v) { gate().complete(v); }

        void enqueueFailure(RuntimeException e) { gate().completeExceptionally(e); }

        void enqueueProtocolFailure(String rawOutput) {
            enqueueFailure(new GoalVerdict.GoalProtocolException(rawOutput));
        }

        @Override public GoalVerdict evaluate(EvaluationInput input) throws Exception {
            calls.incrementAndGet();
            inputs.add(input);
            try {
                return gates.takeFirst().get();      // 无门则挂起；verdict 经真 Runner 回调链进 GoalManager
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw e;
            }
        }
    }

    /**
     * 记录型 {@link SessionService}（读端）：getEvents 回放一份共享事件表。评估结论标记（C1 中段插入）
     * 与压缩重写（⑭）的唯一观测面。注意 {@code SessionService.findById} 返回 {@code Session}、
     * {@code SessionRepository.findById} 返回 {@code Optional}——两接口同名不同型，无法由同一个类实现，
     * 故仓库视图（{@link #repoView}）挂在同一份事件表上。
     */
    private static class RecordingSessionService implements SessionService {
        final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        /** 同一份事件表的仓库视图（C1 中段插入的写回端）。 */
        final SessionRepository repoView = new SessionRepository() {
            @Override public void replaceEvents(String sessionId, List<SessionEvent> evts) {
                events.clear();
                events.addAll(evts);
            }
            @Override public boolean replaceEvents(String sessionId, List<SessionEvent> evts, long expectedVersion) {
                replaceEvents(sessionId, evts);
                return true;
            }
            @Override public void appendEvent(SessionEvent e) { events.add(e); }
            @Override public Session save(Session s) { throw new UnsupportedOperationException(); }
            @Override public java.util.Optional<Session> findById(String id) { throw new UnsupportedOperationException(); }
            @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
            @Override public List<String> findExpiredSessionIds(Instant before) { throw new UnsupportedOperationException(); }
            @Override public void delete(String id) { throw new UnsupportedOperationException(); }
            @Override public long getEventVersion(String id) { return events.size(); }
            @Override public List<SessionEvent> findEvents(String id, EventFilter f) { return List.copyOf(events); }
        };

        @Override public void appendEvent(SessionEvent e) { events.add(e); }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return List.copyOf(events); }
        @Override public List<Message> getMessages(String id) { return events.stream().map(SessionEvent::getMessage).toList(); }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }

        /** 模拟压缩（NotifyingCompactionStrategy 的 replaceEvents 语义）：全部事件换成一条摘要 user。 */
        void simulateCompaction(String summary) {
            events.clear();
            events.add(SessionEvent.builder().sessionId("s").message(new UserMessage(summary)).build());
        }

        /** 评估结论标记条数（C1 后的落库观测面：中段插入，标记不占尾部）。 */
        long markerCount() {
            return events.stream().filter(e -> e.getMessage() instanceof UserMessage um
                    && um.getText() != null && um.getText().startsWith(GoalText.EVAL_OPEN)).count();
        }
    }

    /** goal 桩：真 GoalManager + 真 Runner + 门控评估器 + 记录型会话；submit 记线程与模拟耗量。 */
    private static final class GoalHandler implements SubmitHandler {
        final GoalManager gm;
        final GoalEvaluationRunner runner;
        final ScriptedEvaluator evaluator = new ScriptedEvaluator();
        final RecordingSessionService sessions = new RecordingSessionService();
        final List<String> submitted = new CopyOnWriteArrayList<>();
        final List<Thread> submitThreads = new CopyOnWriteArrayList<>();
        /** 真 accumulator：env 配了预算键就注入（含 0——钉「0=关闭」而非「未注入=关闭」）。 */
        final TokenUsageAccumulator usage;
        /** 当前会话 id（/clear 换新——镜像 CodingAgent.clearContext 的 volatile 语义）。 */
        volatile String sessionId = "s";

        GoalHandler(Map<String, String> env) {
            GoalConfig config = GoalConfig.from(env::get);
            this.usage = env.containsKey(GoalConfig.TOKEN_BUDGET_ENV) ? new TokenUsageAccumulator() : null;
            this.gm = new GoalManager(config, usage);
            this.runner = new GoalEvaluationRunner(config);
            // 评估结论落库接线（Task 9 两段式的桩侧等价；C1 后 service+repository 双参——中段插入的两端）
            this.gm.bindSession(sessions, sessions.repoView, () -> sessionId);
        }

        @Override public Disposable submit(String text) {
            submitted.add(text);
            submitThreads.add(Thread.currentThread());
            // 镜像 CodingAgent 的会话落库形状（C1 后评估结论落库需要 AssistantMessage 锚点）：
            // 镜像 foldTrailingUserIntoOutbound（尾部 user 折走删除）+ 一轮完整对话
            // user → assistant(tool_calls) → tool → assistant(收尾)——评估标记插在收尾 assistant
            // 之前、恒非尾事件，且不与任何 user 相邻成双。
            List<SessionEvent> evs = List.copyOf(sessions.events);
            if (!evs.isEmpty() && evs.get(evs.size() - 1).getMessage() instanceof UserMessage) {
                sessions.repoView.replaceEvents(sessionId, evs.subList(0, evs.size() - 1));
            }
            String callId = "call-" + submitted.size();
            sessions.appendEvent(SessionEvent.builder().sessionId(sessionId)
                    .message(new UserMessage(text)).build());
            sessions.appendEvent(SessionEvent.builder().sessionId(sessionId)
                    .message(AssistantMessage.builder().content("(委派)")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "bash", "{}")))
                            .build()).build());
            sessions.appendEvent(SessionEvent.builder().sessionId(sessionId)
                    .message(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "bash", "exit 0")))
                            .build()).build());
            sessions.appendEvent(SessionEvent.builder().sessionId(sessionId)
                    .message(new AssistantMessage("（轮完成）")).build());
            if (usage != null) usage.record(new FakeUsage(600, 0));   // 模拟一轮对话耗量（预算断言用）
            return () -> { };
        }

        /** 镜像 CodingAgent.clearContext 的 goal 侧效果：clear + 换会话 id（场景⑨走真 /clear 命令路径）。 */
        @Override public void clearContext() {
            gm.clear("clear-context");
            this.sessionId = "s-new";
        }

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

    private static GoalVerdict satisfied(String reason) {
        return new GoalVerdict(GoalVerdict.Outcome.SATISFIED, reason, false, null, "raw");
    }

    /** 输入一行并回车（命令与普通消息同一条真实按键路径）。 */
    private static void type(CodeTuiView v, String line) {
        v.setInputForTest(line);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
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

    /** 受控 drain：反复排空测试批（publish/动画双源）直到条件满足——评估起评类条件用这个。 */
    private static void drainUntil(CodeTuiView v, BooleanSupplier cond, String message) {
        long end = System.currentTimeMillis() + 5_000;
        try {
            while (System.currentTimeMillis() < end && !cond.getAsBoolean()) {
                v.runPendingUiUpdatesForTest();
                v.tickForTest();
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        v.runPendingUiUpdatesForTest();
        v.tickForTest();
        assertTrue(cond.getAsBoolean(), message);
    }

    /** publish 驱动的批受控执行：排空直到第 n 个 submit 到位（调度晚到也兜住）。 */
    private static void drainUntilSubmitted(CodeTuiView v, GoalHandler h, int n) {
        drainUntil(v, () -> h.submitted.size() >= n,
                "publish 驱动的空闲批应派发第 " + n + " 个回合：" + h.submitted);
        assertEquals(n, h.submitted.size(), "恰第 " + n + " 个：" + h.submitted);
    }

    /** 有界排空直到 scrollback 出现目标行（决策点熔断总结要跨批下沉，见 Task 12 drainUntilLine 注释）。 */
    private static void drainUntilLine(CodeTuiView v, RecordingSink sink, String needle) {
        drainUntil(v, () -> countLinesContaining(sink.lines, needle) > 0,
                "应出现一行「" + needle + "」，实际：" + sink.lines);
    }

    private static int countLinesContaining(List<String> lines, String needle) {
        return (int) lines.stream().filter(l -> l.contains(needle)).count();
    }

    // ── 场景⑦：慢评估中插话——用户先走，旧 verdict 单槽作废重评 ──────────

    @Test
    @DisplayName("场景⑦ 慢评估中插话：排队用户消息先走；旧 verdict 按 serial 作废（不改判不派发），用户轮后重评（第 2 次输入）")
    void slowEvalUserInterjectsFirst(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");

        rig.v().tickForTest();                    // 自动轮 1
        assertEquals(1, rig.h().submitted.size());
        rig.v().tickForTest();                    // goal 槽发起评估 1（门上挂起）
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应在空闲批发起");
        assertTrue(gm.evaluationInFlight(), "前置：评估挂起");

        rig.s().enqueue("用户插话：方向不对，先改登录页", null);   // 排队用户消息
        rig.v().tickForTest();                    // 槽位顺序：排队用户消息 > goal 槽——用户先走

        assertEquals(2, rig.h().submitted.size());
        assertEquals("用户插话：方向不对，先改登录页", rig.h().submitted.get(1));
        assertTrue(gm.evaluationInFlight(), "评估仍在飞（未放行）");

        // 旧 verdict 到账：插话推进了 dispatchSerial → 锁存过期（spec §5.2 单槽）→ 丢弃
        rig.h().evaluator.enqueue(satisfied("旧判定：其实早就完成了"));
        await(() -> !gm.evaluationInFlight(), "旧 verdict 应经回调链到账并自清在飞标志");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "serial 过期的 verdict 丢弃：不得按旧判定 SATISFIED");
        assertFalse(gm.hasAutoTurnPending(), "过期 verdict 不得置待发轮");
        assertEquals(2, rig.h().submitted.size(), "过期 verdict 不得触发自动轮派发");

        // 用户轮结束 → 下一空闲批重评：evaluator 收到第 2 次输入（而非按旧 verdict 发轮）
        drainUntil(rig.v(), () -> rig.h().evaluator.calls.get() >= 2, "插话轮结束后应重新评估");
        assertEquals(2, rig.h().evaluator.inputs.size());
        assertEquals(1, rig.h().evaluator.inputs.get(1).turn(), "用户消息轮不烧自动轮配额：重评仍见第 1 轮");
        assertEquals(2, rig.h().submitted.size(), "重评发起本身不得派发");

        // 重评 verdict 放行 → 自动轮 2 携带重评结论
        rig.h().evaluator.enqueue(unsat("重评：还差登录页", false, null));
        await(() -> !gm.evaluationInFlight(), "重评 verdict 应落账");
        drainUntilSubmitted(rig.v(), rig.h(), 3);
        assertTrue(rig.h().submitted.get(2).startsWith("[goal 继续 2/25]"),
                "自动轮 2 前缀，实际：" + rig.h().submitted.get(2));
        assertTrue(rig.h().submitted.get(2).contains("重评：还差登录页"),
                "第 2 轮 prompt 携带重评结论（旧结论已被作废），实际：" + rig.h().submitted.get(2));
    }

    // ── 场景⑧：慢评估中 Esc——迟到 verdict 丢弃，仍 PAUSED ─────────────

    @Test
    @DisplayName("场景⑧ 慢评估中 Esc：PAUSED(ESC)；迟到的 SATISFIED 丢弃不改判、无自动轮；再 drain 无评估")
    void slowEvalEscDropsStaleVerdict(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");
        rig.v().tickForTest();                    // 自动轮 1
        rig.v().tickForTest();                    // 评估 1 挂起（IDLE 态）
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");

        rig.v().feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));   // IDLE+评估中：现有取消逻辑空转 → goal 一级 Esc

        assertEquals(GoalPhase.PAUSED, gm.phase(), "评估中 Esc 应暂停");
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason());
        assertTrue(gm.evaluationInFlight(), "在途评估不硬中断（spec §3.3）：门仍挂起");

        rig.h().evaluator.enqueue(satisfied("迟到的达成"));
        await(() -> !gm.evaluationInFlight(), "迟到 verdict 应到账并自清在飞标志");
        assertEquals(GoalPhase.PAUSED, gm.phase(), "迟到 verdict 丢弃：不得 terminate");
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason(), "不得改判暂停原因");
        assertFalse(gm.hasAutoTurnPending(), "迟到 verdict 不得置待发轮");
        assertEquals(1, rig.h().submitted.size(), "无自动轮");

        for (int i = 0; i < 3; i++) {             // PAUSED 后 drain 任意批：僵尸防护（无新 submit/eval）
            rig.v().tickForTest();
            rig.v().runPendingUiUpdatesForTest();
        }
        assertEquals(1, rig.h().evaluator.calls.get(), "PAUSED 不再发评估");
        assertEquals(1, rig.h().submitted.size(), "PAUSED 不再派发");
        assertFalse(gm.evaluationInFlight());
    }

    // ── 场景⑨：慢评估中 /clear——全量清理，transcript 不复活 ────────────

    @Test
    @DisplayName("场景⑨ 慢评估中 /clear：CLEARED+队列清空+总结一行；挂起 verdict 到账后 transcript 无新增 goal 事件")
    void slowEvalClearFullCleanup(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");
        rig.v().tickForTest();                    // 自动轮 1
        rig.v().tickForTest();                    // 评估 1 挂起
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        int eventsBefore = rig.h().sessions.events.size();

        type(rig.v(), "/clear");                  // 真命令路径：killAllBackgroundTasks→clearContext→面板复位

        assertEquals(GoalPhase.CLEARED, gm.phase(), "/clear 换新会话必须连带清掉活动 goal");
        assertEquals(0, rig.s().queuedCount(), "排队消息随新会话丢弃");
        assertFalse(gm.hasAutoTurnPending());

        // 挂起 verdict 到账：终态单调 → 完全 no-op——不落库、不复活、不改判。
        // await 等「门被评估线程消费」（gates 在挂起时为空、enqueue 后非空、取走后复空）——
        // 不能等 inFlight/终态：/clear 时两者已满足，等不到 verdict 真正走完回调链。
        rig.h().evaluator.enqueue(satisfied("清空后才回来"));
        await(() -> rig.h().evaluator.gates.isEmpty(), "迟到 verdict 应被评估线程消费（回调链走完）");
        assertEquals(GoalPhase.CLEARED, gm.phase(), "终态单调：迟到 SATISFIED 完全 no-op");
        assertEquals(eventsBefore, rig.h().sessions.events.size(),
                "transcript 不复活：CLEARED 后不得再落任何 goal 事件（含评估结论标记）");
        for (int i = 0; i < 3; i++) {             // drain 任意批也不复活
            rig.v().tickForTest();
            rig.v().runPendingUiUpdatesForTest();
        }
        assertEquals(eventsBefore, rig.h().sessions.events.size(), "drain 后仍无新增");
        assertEquals(1, rig.h().submitted.size(), "无新轮");
        assertEquals(1, rig.h().evaluator.calls.get(), "无新评估");
        assertEquals(1, countLinesContaining(rig.sink().lines, "◎ goal 终态"),
                "goal 总结恰一行（CLEARED 那次），实际：" + rig.sink().lines);
        assertFalse(rig.sink().lines.stream().anyMatch(l -> l.contains("SATISFIED")),
                "迟到的 SATISFIED 不得出现在任何提示行，实际：" + rig.sink().lines);
    }

    // ── 场景⑩：慢评估中替换新条件——旧 verdict 不作用新 goal ────────────

    @Test
    @DisplayName("场景⑩ 慢评估中 /goal 新条件：旧 verdict 按 epoch 丢弃；新 goal RUNNING、轮次从 1 重新计数")
    void slowEvalReplaceGoal(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("旧条件：迁移模块 A");
        rig.v().tickForTest();                    // 旧 goal 自动轮 1
        rig.v().tickForTest();                    // 评估 1 挂起
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");

        type(rig.v(), "/goal 新条件：测试全绿");   // /goal 无忙碌闸门：换代（epoch+1）、首轮 pending

        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertEquals("新条件：测试全绿", gm.snapshot().condition());
        assertTrue(gm.hasAutoTurnPending(), "新 goal 置首轮 pending");

        rig.h().evaluator.enqueue(satisfied("旧 goal 早就完成了"));
        await(() -> rig.h().evaluator.gates.isEmpty(), "旧 verdict 应被评估线程消费");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "旧代 verdict 按 epoch 丢弃：不得把新 goal 判成 SATISFIED");
        assertEquals("新条件：测试全绿", gm.snapshot().condition());
        assertTrue(gm.hasAutoTurnPending(), "旧 verdict 不得动新 goal 的待发轮");

        drainUntilSubmitted(rig.v(), rig.h(), 2); // 新 goal 首轮
        assertTrue(rig.h().submitted.get(1).startsWith("[goal 继续 1/25]"),
                "新 goal 轮次从 1 重新计数，实际：" + rig.h().submitted.get(1));
    }

    // ── 场景⑪：评估器连抛——EVALUATOR 熔断、CAS 无死锁 ─────────────────

    @Test
    @DisplayName("场景⑪ 评估器连抛 ×evalFailLimit(2)：PAUSED(EVALUATOR)；之后 drain 不再发评估（CAS 配对无死锁）")
    void evaluatorThrowsRepeatedly(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.EVAL_FAIL_LIMIT_ENV, "2")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");
        rig.v().tickForTest();                    // 自动轮 1
        assertEquals(1, rig.h().submitted.size());

        rig.v().tickForTest();                    // 评估 1
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        rig.h().evaluator.enqueueFailure(new RuntimeException("评估器 500"));
        await(() -> !gm.evaluationInFlight(), "失败 1 应经回调链落账");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "未达 evalFailLimit 不熔断");

        drainUntil(rig.v(), () -> rig.h().evaluator.calls.get() >= 2, "失败后下一空闲批应再次发起评估");
        rig.h().evaluator.enqueueFailure(new RuntimeException("评估器又 500"));
        await(() -> !gm.evaluationInFlight(), "失败 2 应落账");

        assertEquals(GoalPhase.PAUSED, gm.phase(), "连抛达 evalFailLimit → 熔断");
        assertEquals(PauseReason.EVALUATOR, gm.snapshot().pauseReason());
        drainUntilLine(rig.v(), rig.sink(), "◎ goal 已暂停（EVALUATOR）");
        assertEquals(1, rig.h().submitted.size(), "评估失败不派发自动轮");

        int callsAtPause = rig.h().evaluator.calls.get();
        for (int i = 0; i < 3; i++) {             // PAUSED 后空闲批再 drain：begin/end CAS 配对、无死锁
            rig.v().tickForTest();
            rig.v().runPendingUiUpdatesForTest();
        }
        assertEquals(callsAtPause, rig.h().evaluator.calls.get(), "PAUSED 后不得再发评估（无新 submit 到 runner）");
        assertFalse(gm.evaluationInFlight(), "在飞标志保持清除：下个 goal/恢复不会被死锁卡住");
        assertEquals(1, rig.h().submitted.size());
    }

    // ── 场景⑫：终态僵尸防护 + 多熔断唯一原因 ───────────────────────────

    @Test
    @DisplayName("场景⑫ 预算+轮数同批为真：评估启动前预算决策点（M7）先行清算，原因唯一 BUDGET_EXCEEDED；终态后 drain 无任何 submit/eval")
    void zombieTerminalGuardAndPriority(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.MAX_TURNS_ENV, "1", GoalConfig.TOKEN_BUDGET_ENV, "500")), root);
        GoalManager gm = rig.h().gm;
        assertNotNull(rig.h().usage, "配了预算就应注入真 accumulator");
        gm.activate("迁移完成且测试全绿");

        rig.v().tickForTest();                    // 自动轮 1（submit 记 600）
        assertEquals(1, rig.h().submitted.size());

        // 决策点（M7 后前移到评估启动前）：轮 1 之后耗量 600 ≥ 500 已超限——空闲批先撞
        // beginEvaluation 的预算检查：置在飞<b>之前</b>清算，评估调用一次都不发（不再白烧一次评估）。
        rig.v().tickForTest();                    // goal 槽：评估启动前预算熔断

        assertEquals(GoalPhase.BUDGET_EXCEEDED, gm.phase(),
                "spec §7 优先级：预算先于轮数清算——原因唯一 BUDGET_EXCEEDED 而非 MAX_TURNS");
        assertEquals(1, gm.snapshot().turnsUsed(), "第 2 轮未发生");
        assertEquals(0, rig.h().evaluator.calls.get(),
                "预算超限在评估启动前已清算：评估器一次都不该被调（M7 决策点）");
        assertFalse(gm.evaluationInFlight());
        drainUntilLine(rig.v(), rig.sink(), "◎ goal 终态：BUDGET_EXCEEDED");

        int submittedAtTerminal = rig.h().submitted.size();
        int callsAtTerminal = rig.h().evaluator.calls.get();
        for (int i = 0; i < 3; i++) {             // 僵尸防护：终态后 drain 任意批，无任何 submit/eval
            rig.v().tickForTest();
            rig.v().runPendingUiUpdatesForTest();
        }
        assertEquals(submittedAtTerminal, rig.h().submitted.size(), "终态后不得再有 submit");
        assertEquals(callsAtTerminal, rig.h().evaluator.calls.get(), "终态后不得再有评估");
        assertFalse(gm.evaluationInFlight());
    }

    // ── 场景⑬：解析失败风暴——PROTOCOL 熔断，不烧预算 ───────────────────

    @Test
    @DisplayName("场景⑬ 协议外输出 ×protocolFailLimit(2)：PAUSED(PROTOCOL)；单失败按 UNSATISFIED+stalled 入账不派发（usage 不涨）")
    void protocolFailureStorm(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0",
                GoalConfig.PROTOCOL_FAIL_LIMIT_ENV, "2", GoalConfig.TOKEN_BUDGET_ENV, "1000")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");
        rig.v().tickForTest();                    // 自动轮 1（600 < 1000）
        assertEquals(1, rig.h().submitted.size());
        long spentAfterTurn1 = gm.snapshot().tokenSpent();

        rig.v().tickForTest();                    // 评估 1
        await(() -> rig.h().evaluator.calls.get() >= 1, "评估 1 应发起");
        rig.h().evaluator.enqueueProtocolFailure("模型闲聊输出，无 VERDICT 行");
        await(() -> !gm.evaluationInFlight(), "协议失败 1 应落账");

        assertEquals(GoalPhase.RUNNING, gm.phase(), "未达限不熔断");
        assertEquals(1, gm.snapshot().stalledStreak(), "协议失败按 UNSATISFIED+stalled 同账入账");
        assertFalse(gm.hasAutoTurnPending(), "协议失败不派发新轮");
        assertEquals(1, rig.h().submitted.size(), "不烧轮次");
        assertEquals(spentAfterTurn1, gm.snapshot().tokenSpent(), "不烧预算：无新自动轮即无新耗量");

        drainUntil(rig.v(), () -> rig.h().evaluator.calls.get() >= 2, "风暴中下一批应再次评估");
        rig.h().evaluator.enqueueProtocolFailure("还是没有 VERDICT 行");
        await(() -> !gm.evaluationInFlight(), "协议失败 2 应落账");

        assertEquals(GoalPhase.PAUSED, gm.phase(), "protocolFailLimit=2 先于 stalledLimit=3 达到 → PROTOCOL");
        assertEquals(PauseReason.PROTOCOL, gm.snapshot().pauseReason());
        drainUntilLine(rig.v(), rig.sink(), "◎ goal 已暂停（PROTOCOL）");
        assertEquals(1, rig.h().submitted.size(), "全程无新轮");
        assertEquals(spentAfterTurn1, gm.snapshot().tokenSpent(), "风暴全程预算分文未动");
    }

    // ── 场景⑭：压缩中途存活 + 两条守卫 ─────────────────────────────────

    @Test
    @DisplayName("场景⑭ 压缩重写会话事件：goal 滚动记录内存态不丢（评估输入仍 8 轮）；评估只落标记事件、自动轮全落 UI 线程")
    void compactionSurvivalAndGuards(@TempDir Path root) {
        Rig rig = rig(new GoalHandler(Map.of(GoalConfig.TURN_GAP_ENV, "0")), root);
        GoalManager gm = rig.h().gm;
        gm.activate("迁移完成且测试全绿");

        // 8 轮完整循环：滚动记录恰满 8 条（每次评估起评前落一条素材）。
        // await 同时等「在飞标志清 + 标记事件到账」——onVerdict 锁内清标志、锁外才做中段插入落库，
        // 只等标志会与落库竞态（真跑抓到过：expected 1 but was 0）。
        // 事件计数口径（C1 后）：submit 桩每轮落 4 条轮事件、评估标记中段插入 1 条——标记数才是落库观测量。
        for (int i = 1; i <= 8; i++) {
            final int n = i;
            rig.v().tickForTest();                // 自动轮 i
            drainUntil(rig.v(), () -> rig.h().evaluator.calls.get() >= n, "评估 " + n + " 应起评");
            rig.h().evaluator.enqueue(unsat("轮" + n + "未完", false, "已完成：" + n + "/8"));
            final int expectedMarkers = n;
            await(() -> !gm.evaluationInFlight() && rig.h().sessions.markerCount() == expectedMarkers,
                    "verdict " + n + " 应落账且结论标记到账");
            if (n < 8) {
                drainUntilSubmitted(rig.v(), rig.h(), n + 1);
            }
        }
        assertEquals(8, gm.buildEvaluationInput().recentTurns().size(), "前置：滚动记录恰 8 条");
        assertEquals(8, rig.h().sessions.markerCount(), "前置：8 条评估结论标记已独立落库（中段插入）");
        assertEquals(8 * 5, rig.h().sessions.events.size(), "前置：8 轮 ×（4 轮事件 + 1 标记）");

        // 触发压缩（replaceEvents 语义）：全部会话事件换成一条摘要——goal 滚动记录是内存态，不受影响
        rig.h().sessions.simulateCompaction("（历史已压缩：前 8 轮完成迁移主体）");
        assertEquals(1, rig.h().sessions.events.size(), "前置：会话事件已被压缩重写");
        assertEquals(8, gm.buildEvaluationInput().recentTurns().size(),
                "压缩中途存活：滚动记录是 Manager 内存态，不随会话事件重写丢失");

        // 压缩后的评估：输入仍含 8 轮（第 9 条素材入账、FIFO 挤掉最旧）
        rig.v().tickForTest();                    // 自动轮 9（第 8 个 verdict 置的 pending；
        //   submit 桩按 fold 语义折走压缩摘要那条尾部 user 再落轮 9 的 4 条事件）
        assertEquals(9, rig.h().submitted.size());
        drainUntil(rig.v(), () -> rig.h().evaluator.calls.get() >= 9, "压缩后评估 9 应起评");
        assertEquals(8, rig.h().evaluator.inputs.get(8).recentTurns().size(),
                "评估输入仍含 8 轮滚动记录（压缩不缩水评估视野）");

        // 守卫①：评估不写对话上下文——本轮 submit 落完轮事件之后，评估全程新增事件恰一条（结论标记，
        // C1 中段插入：标记插在收尾 assistant 之前、不在序列末尾——差分按「移除标记后与原序列逐位相等」计）
        List<SessionEvent> eventsBeforeEval = List.copyOf(rig.h().sessions.events);
        rig.h().evaluator.enqueue(satisfied("全部完成"));
        await(() -> !gm.evaluationInFlight() && rig.h().sessions.events.size() == eventsBeforeEval.size() + 1,
                "终局 verdict 应落账且结论标记到账");
        assertEquals(GoalPhase.SATISFIED, gm.phase());
        List<SessionEvent> after = List.copyOf(rig.h().sessions.events);
        int markerIdx = -1;
        for (int i = 0; i < after.size(); i++) {
            String t = after.get(i).getMessage().getText();
            if (t != null && t.startsWith(GoalText.EVAL_OPEN)) {
                assertEquals(-1, markerIdx, "压缩重写后只应有一条标记");
                markerIdx = i;
            }
        }
        assertTrue(markerIdx >= 0, "应存在一条评估标记，实际：" + after);
        String markerText = after.get(markerIdx).getMessage().getText();
        assertTrue(markerText.startsWith(GoalText.EVAL_OPEN) && markerText.endsWith(GoalText.EVAL_CLOSE),
                "评估只允许落 wrapEvaluation 标记事件，不得塞 assistant/user 轮，实际：" + markerText);
        assertTrue(markerText.contains("SATISFIED"), "终局结论在标记里，实际：" + markerText);
        List<SessionEvent> withoutMarker = new ArrayList<>(after);
        withoutMarker.remove(markerIdx);
        assertEquals(eventsBeforeEval, withoutMarker, "除标记外事件零改动（评估不写对话上下文）");
        // C1 序列形态：标记插在最后一条 assistant 之前（非尾事件），全程无相邻双 user
        assertTrue(markerIdx + 1 < after.size()
                        && after.get(markerIdx + 1).getMessage() instanceof AssistantMessage,
                "标记后必跟 assistant（恒非尾事件，fold 够不到）");
        for (int i = 1; i < after.size(); i++) {
            assertFalse(after.get(i - 1).getMessage() instanceof UserMessage
                            && after.get(i).getMessage() instanceof UserMessage,
                    "相邻双 user（下标 " + (i - 1) + "/" + i + "）会触发折叠/400 形状");
        }

        // 守卫②：自动轮 dispatch 全落 UI 线程（测试态即断言 submit 全在 drain 调用线程）
        assertEquals(9, rig.h().submitThreads.size());
        for (Thread t : rig.h().submitThreads) {
            assertSame(Thread.currentThread(), t, "自动轮 dispatch 红线：必须全部发生在驱动批的线程上");
        }
    }
}
