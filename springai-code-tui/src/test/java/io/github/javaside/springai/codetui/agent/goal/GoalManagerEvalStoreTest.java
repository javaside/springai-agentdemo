package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /goal 评估结论的两条出账（Task 13 A+B）：
 *
 * <ol>
 *   <li><b>落库（A，spec §3.4）</b>：onVerdict <b>放行</b>的 verdict 经
 *       {@link GoalText#wrapEvaluation} 合成块独立 appendEvent 一条 UserMessage——
 *       不进对话上下文（评估器输入靠内存滚动记录）；被丢弃的 verdict（Esc 暂停迟到 / serial 过期 /
 *       终态 no-op）<b>不落库</b>——落一条从未生效的结论等于污染 transcript（集成场景⑨「/clear 后
 *       transcript 不复活」的守卫源头在这里）；未 bindSession 静默跳过；落库失败 log.warn 不抛，
 *       评估主流程不受影响。</li>
 *   <li><b>recentTraces 投影（B）</b>：snapshot() 把 onVerdict 放行时入账的
 *       {@link GoalStateSnapshot.GoalEvalTrace}（turn / verdict / reason）≤8 条 FIFO 投给
 *       goal 面板「最近轨迹」——轨迹按<b>评估发生的轮次</b>对齐（轮 N 的评估记 N），终局 verdict
 *       （SATISFIED/IMPOSSIBLE）也在账上，恢复与事后可查都靠它。</li>
 * </ol>
 */
class GoalManagerEvalStoreTest {

    private static GoalConfig cfg() {
        return new GoalConfig(25, 3, 5_000_000L, 0, 2, 2, 3, 60, "");
    }

    // ── 装置 ────────────────────────────────────────────────────────────

    /** 记录型 SessionService：只记 appendEvent，getEvents/getMessages 回放（同 CodingAgentGoalTest 桩形）。 */
    private static class RecordingSessionService implements SessionService {
        final List<SessionEvent> appended = new CopyOnWriteArrayList<>();

        @Override public void appendEvent(SessionEvent e) { appended.add(e); }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return List.copyOf(appended); }
        @Override public List<Message> getMessages(String id) { return appended.stream().map(SessionEvent::getMessage).toList(); }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }
    }

    /** 落库必炸的 SessionService：断言失败被吞成日志、评估主流程不受影响。 */
    private static final class FailingSessionService extends RecordingSessionService {
        @Override public void appendEvent(SessionEvent e) { throw new IllegalStateException("磁盘炸了"); }
    }

    private static GoalVerdict unsat(String reason) {
        return new GoalVerdict(GoalVerdict.Outcome.UNSATISFIED, reason, false, null, "raw");
    }

    /** 推进一轮的标准链（turnGap=0）：takeAutoTurn → beginEvaluation → onVerdict。 */
    private static void runTurn(GoalManager gm, GoalVerdict verdict) {
        assertNotNull(gm.takeAutoTurn(), "前置：应有待发自动轮");
        long epoch = gm.beginEvaluation();
        assertTrue(epoch > 0, "前置：CAS 置在飞应成功");
        gm.onVerdict(epoch, verdict);
    }

    // ── A：评估结论落库 ─────────────────────────────────────────────────

    @Test
    @DisplayName("放行 verdict 恰落一条 wrapEvaluation 合成块 UserMessage（sessionId 取 supplier）")
    void appliedVerdictAppendsWrappedMarkerEvent() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingSessionService sessions = new RecordingSessionService();
        gm.bindSession(sessions, () -> "s-42");
        gm.activate("迁移完成且测试全绿");

        runTurn(gm, unsat("还差登录页"));

        assertEquals(1, sessions.appended.size(), "放行 verdict 恰落一条事件");
        SessionEvent e = sessions.appended.get(0);
        assertEquals("s-42", e.getSessionId(), "落库取 sessionIdSupplier 的当前值");
        assertInstanceOf(UserMessage.class, e.getMessage(), "落库形态是合成 UserMessage");
        assertEquals(GoalText.wrapEvaluation("UNSATISFIED", "还差登录页"), e.getMessage().getText(),
                "文本为 wrapEvaluation 合成块");
        assertTrue(e.getMessage().getText().startsWith(GoalText.EVAL_OPEN), "行首判定标记（HistoryReplay 靠它识别）");
    }

    @Test
    @DisplayName("丢弃的 verdict 不落库：Esc 暂停迟到 / serial 过期 / 终态 no-op 三门各钉一条")
    void droppedVerdictsAreNotPersisted() {
        // (a) Esc 暂停后的迟到 SATISFIED：判定丢弃（spec §3.3），不得落库
        GoalManager a = new GoalManager(cfg(), null);
        RecordingSessionService sa = new RecordingSessionService();
        a.bindSession(sa, () -> "s1");
        a.activate("g");
        a.takeAutoTurn();
        long ea = a.beginEvaluation();
        a.pauseByEsc();
        a.onVerdict(ea, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "迟到", false, null, "raw"));
        assertTrue(sa.appended.isEmpty(), "PAUSED 丢弃的 verdict 不得落库");

        // (b) 评估在飞期间插话（dispatchSerial 过期，spec §5.2 单槽作废）：不落库
        GoalManager b = new GoalManager(cfg(), null);
        RecordingSessionService sb = new RecordingSessionService();
        b.bindSession(sb, () -> "s2");
        b.activate("g");
        b.takeAutoTurn();
        long eb = b.beginEvaluation();
        b.onUserDispatch();                          // 插话：锁存 serial 过期
        b.onVerdict(eb, unsat("过期判定"));
        assertTrue(sb.appended.isEmpty(), "serial 过期的 verdict 不得落库");

        // (c) /clear（终态）后的迟到 verdict：完全 no-op，不落库（场景⑨守卫源头）
        GoalManager c = new GoalManager(cfg(), null);
        RecordingSessionService sc = new RecordingSessionService();
        c.bindSession(sc, () -> "s3");
        c.activate("g");
        c.takeAutoTurn();
        long ec = c.beginEvaluation();
        c.clear("clear-context");
        c.onVerdict(ec, unsat("清空后迟到"));
        assertTrue(sc.appended.isEmpty(), "终态 no-op 的 verdict 不得落库");
    }

    @Test
    @DisplayName("未 bindSession 静默跳过；appendEvent 抛异常只记日志、评估主流程照常推进")
    void noBindingOrFailingStoreDoesNotBreakEvaluation() {
        // 未接线：不落库也不抛
        GoalManager plain = new GoalManager(cfg(), null);
        plain.activate("g");
        runTurn(plain, unsat("无会话"));
        assertEquals(GoalPhase.RUNNING, plain.phase(), "未接线不影响状态机");

        // 接线但 appendEvent 抛：log.warn 不抛出（此处 runTurn 不炸即为断言），轮次照常入账
        GoalManager failing = new GoalManager(cfg(), null);
        failing.bindSession(new FailingSessionService(), () -> "s");
        failing.activate("g");
        runTurn(failing, unsat("落库失败"));
        assertEquals(1, failing.snapshot().turnsUsed(), "落库失败不影响评估主流程记账");
        assertEquals(GoalPhase.RUNNING, failing.phase());
        assertTrue(failing.hasAutoTurnPending(), "UNSAT 照常置下一轮 pending");
    }

    // ── B：recentTraces 投影 ────────────────────────────────────────────

    @Test
    @DisplayName("snapshot.recentTraces 按 onVerdict 放行入账：turn 对齐评估发生的轮次，终局 verdict 也在账")
    void recentTracesProjectAppliedVerdicts() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        assertTrue(gm.snapshot().recentTraces().isEmpty(), "未评估无轨迹");

        runTurn(gm, unsat("还差登录页"));
        runTurn(gm, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "全部完成", false, null, "raw"));

        List<GoalStateSnapshot.GoalEvalTrace> traces = gm.snapshot().recentTraces();
        assertEquals(2, traces.size());
        assertEquals(new GoalStateSnapshot.GoalEvalTrace(1, "UNSATISFIED", "还差登录页"), traces.get(0));
        assertEquals(new GoalStateSnapshot.GoalEvalTrace(2, "SATISFIED", "全部完成"), traces.get(1),
                "终局 verdict 也入轨迹（事后可查）");
    }

    @Test
    @DisplayName("轨迹 ≤8 条 FIFO：第 11 次评估后恰 8 条、最旧被挤掉")
    void tracesCappedAtEightOldestEvictedFirst() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        for (int i = 1; i <= 10; i++) {
            runTurn(gm, unsat("第" + i + "轮结论"));
        }
        List<GoalStateSnapshot.GoalEvalTrace> traces = gm.snapshot().recentTraces();
        assertEquals(8, traces.size(), "轨迹上限 8");
        assertEquals(3, traces.get(0).turn(), "最旧两条（轮 1/2）被挤掉");
        assertEquals("UNSATISFIED", traces.get(0).verdict());
        assertEquals(10, traces.get(7).turn(), "最新一条是第 10 轮");
        assertEquals("第10轮结论", traces.get(7).reason());
    }

    @Test
    @DisplayName("丢弃的 verdict 不入轨迹；activate 换代清空轨迹")
    void droppedVerdictsLeaveNoTraceAndActivateResets() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        runTurn(gm, unsat("第1轮结论"));
        assertEquals(1, gm.snapshot().recentTraces().size());

        // 丢弃路径（Esc 暂停后迟到）不入账
        gm.takeAutoTurn();
        long epoch = gm.beginEvaluation();
        gm.pauseByEsc();
        gm.onVerdict(epoch, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "迟到", false, null, "raw"));
        assertEquals(1, gm.snapshot().recentTraces().size(), "丢弃的 verdict 不入轨迹");

        // 换代：新 goal 不背旧账
        gm.activate("新目标");
        assertTrue(gm.snapshot().recentTraces().isEmpty(), "activate 换代清空轨迹");
    }
}
