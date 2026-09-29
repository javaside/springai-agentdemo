package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /goal 评估结论的两条出账（Task 13 A+B；C1 后落库改为<b>中段插入</b>；R2 后写回改为 <b>CAS 重试</b>）：
 *
 * <ol>
 *   <li><b>落库（A，spec §3.4）</b>：onVerdict <b>放行</b>的 verdict 经
 *       {@link GoalText#wrapEvaluation} 合成块插到会话<b>最后一条 AssistantMessage 之前</b>——
 *       绝不追加尾部（C1：{@code CodingAgent.submit} 每回合 {@code foldTrailingUserIntoOutbound}
 *       会把尾部 UserMessage 折进出站并删除，追加尾部 = 轨迹被毁 + 用户消息被混入标记）；无
 *       AssistantMessage 则跳过。写回走 {@code replaceEvents(sid, events, expectedVersion)} CAS
 *       变体（R2：落库在评估器线程，与 UI 线程 fold RMW 交错，盲写会覆盖窗口内他人写入的
 *       用户事件）——版本不符重读重试 ≤3 次，耗尽放弃（标记丢失良性）；被丢弃的 verdict
 *       （Esc 暂停迟到 / serial 过期 / 终态 no-op）<b>不落库</b>；未接线静默跳过；落库失败
 *       log.warn 不抛。</li>
 *   <li><b>recentTraces 投影（B）</b>：snapshot() 把 onVerdict 放行时入账的
 *       {@link GoalStateSnapshot.GoalEvalTrace}（turn / verdict / reason）≤8 条 FIFO 投给
 *       goal 面板「最近轨迹」。</li>
 * </ol>
 */
class GoalManagerEvalStoreTest {

    private static GoalConfig cfg() {
        return new GoalConfig(25, 3, 5_000_000L, 0, 2, 2, 3, 60, "");
    }

    // ── 装置 ────────────────────────────────────────────────────────────

    /**
     * 记录型 {@link SessionService}（读端）：getEvents 回放一份共享事件表。
     * 注意 {@code SessionService.findById} 返回 {@code Session}、{@code SessionRepository.findById}
     * 返回 {@code Optional<Session>}——两接口同名不同型，<b>无法由同一个类实现</b>，故写成
     * 「一份事件表 + service/repo 两个视图」（C1 中段插入的读写两端）。
     */
    private static class RecordingStore implements SessionService {
        final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        @Override public void appendEvent(SessionEvent e) { events.add(e); }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return List.copyOf(events); }
        @Override public List<Message> getMessages(String id) { return events.stream().map(SessionEvent::getMessage).toList(); }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { return null; }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }

        /** 评估结论标记条数（唯一观测面：fold 之后标记还活着几条）。 */
        long markerCount() {
            return events.stream().filter(e -> e.getMessage() instanceof UserMessage um
                    && um.getText() != null && um.getText().startsWith(GoalText.EVAL_OPEN)).count();
        }

        /**
         * 镜像 {@code CodingAgent.foldTrailingUserIntoOutbound}（C1 事故现场）：会话尾部若残留
         * UserMessage，折进出站文本并<b>从会话删除</b>；返回合并后的出站文本。
         */
        String foldTrailingUserIntoOutbound() {
            List<SessionEvent> evs = List.copyOf(events);
            if (evs.isEmpty() || !(evs.get(evs.size() - 1).getMessage() instanceof UserMessage prev)) {
                return "";
            }
            String prevText = prev.getText();
            replaceAll(evs.subList(0, evs.size() - 1));
            return prevText == null || prevText.isBlank() ? "" : prevText;
        }

        /** 共享事件表的整表替换（fold 与仓库视图共用一个写法）。 */
        private void replaceAll(List<SessionEvent> evts) {
            events.clear();
            events.addAll(evts);
        }

        /**
         * 落一条带 tool 调用的完整轮形状（SessionMemoryAdvisor 的落库次序）：
         * user → assistant(tool_calls) → tool → assistant(收尾)。
         */
        void appendToolTurn(String userText) {
            events.add(SessionEvent.builder().sessionId("s").message(new UserMessage(userText)).build());
            events.add(SessionEvent.builder().sessionId("s").message(asstWithCalls("call-1")).build());
            events.add(SessionEvent.builder().sessionId("s").message(toolResult("call-1")).build());
            events.add(SessionEvent.builder().sessionId("s").message(new AssistantMessage("本轮收尾")).build());
        }
    }

    /**
     * 同一份事件表的 {@link SessionRepository} 视图（C1 写回端；R2 后 GoalManager 只走 3 参 CAS
     * 变体）。本桩以 {@code events.size()} 充当版本号——直接改表（fold 桩/竞态注入）即自然 +1，
     * 与 {@code FileSessionRepository}「version 随每次成功写回 +1」的语义对齐。
     */
    private static class RecordingRepo implements SessionRepository {
        final List<SessionEvent> events;
        /** 3 参 CAS 变体被调次数：断言写回确实走 CAS 路径、且重试次数符合预期。 */
        int casCalls;
        /** 每次 CAS 收到的 expectedVersion（GoalManager 侧应来自 getEventVersion 读取门面）。 */
        final List<Long> expectedVersions = new ArrayList<>();

        RecordingRepo(List<SessionEvent> events) { this.events = events; }

        @Override public void appendEvent(SessionEvent e) { events.add(e); }
        @Override public void replaceEvents(String sessionId, List<SessionEvent> evts) {
            events.clear();
            events.addAll(evts);
        }
        /**
         * CAS 变体，镜像 {@code FileSessionRepository} 语义：版本不符 → 表不变、返回 {@code false}
         * （R2 失败信号就是布尔返回值，不抛异常）；命中 → 提交、返回 {@code true}。
         */
        @Override public boolean replaceEvents(String sessionId, List<SessionEvent> evts, long expectedVersion) {
            casCalls++;
            expectedVersions.add(expectedVersion);
            if (expectedVersion != events.size()) return false;   // CAS 未命中：不变、false
            replaceEvents(sessionId, evts);
            return true;
        }
        @Override public Session save(Session s) { throw new UnsupportedOperationException(); }
        @Override public Optional<Session> findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public List<String> findExpiredSessionIds(Instant before) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public long getEventVersion(String id) { return events.size(); }
        @Override public List<SessionEvent> findEvents(String id, EventFilter f) { return List.copyOf(events); }
    }

    /** 落库必炸的仓库（C1 的写回端）：断言失败被吞成日志、评估主流程不受影响。 */
    private static final class FailingRepo extends RecordingRepo {
        FailingRepo(List<SessionEvent> events) { super(events); }

        @Override public void replaceEvents(String sessionId, List<SessionEvent> evts) {
            throw new IllegalStateException("磁盘炸了");
        }
    }

    /**
     * 竞态桩（R2）：第一次 CAS 前模拟「他人抢先写入」——往共享表追加一条 user 事件（版本随之 +1，
     * 恰使首次 CAS 以旧版本号未命中、返回 {@code false}，即 {@code FileSessionRepository} 的失败
     * 信号）；第二次放行提交。另记录每次提交的整表，供断言「重试时重读了含他人写入的新状态」。
     */
    private static final class RacyRepo extends RecordingRepo {
        final List<List<SessionEvent>> submittedLists = new ArrayList<>();

        RacyRepo(List<SessionEvent> events) { super(events); }

        @Override public boolean replaceEvents(String sessionId, List<SessionEvent> evts, long expectedVersion) {
            if (casCalls == 0) {
                // 竞态窗口：GoalManager 已读完版本/事件，他人（UI 线程）此刻抢先落库
                events.add(SessionEvent.builder().sessionId("s").message(new UserMessage("用户插话抢先")).build());
            }
            submittedLists.add(List.copyOf(evts));
            return super.replaceEvents(sessionId, evts, expectedVersion);
        }
    }

    /** 恒冲突桩（R2 放弃路径）：每次 CAS 都未命中，断言重试 ≤3 次后放弃且绝不盲写覆盖。 */
    private static final class AlwaysConflictingRepo extends RecordingRepo {
        AlwaysConflictingRepo(List<SessionEvent> events) { super(events); }

        @Override public boolean replaceEvents(String sessionId, List<SessionEvent> evts, long expectedVersion) {
            casCalls++;
            expectedVersions.add(expectedVersion);
            return false;
        }
    }

    private static AssistantMessage asstWithCalls(String callId) {
        return AssistantMessage.builder().content("(调工具)")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "bash", "{}"))).build();
    }

    private static ToolResponseMessage toolResult(String callId) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "bash", "exit 0"))).build();
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

    /** 序列形态断言：任何相邻两事件都不得同为 UserMessage（无连续双 user）。 */
    private static void assertNoConsecutiveUsers(List<SessionEvent> events) {
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i - 1).getMessage() instanceof UserMessage
                            && events.get(i).getMessage() instanceof UserMessage,
                    "相邻双 user 事件（下标 " + (i - 1) + "/" + i + "）会被出站 sanitize 折叠，"
                            + "实际序列：" + describe(events));
        }
    }

    private static List<String> describe(List<SessionEvent> events) {
        List<String> out = new ArrayList<>();
        for (SessionEvent e : events) {
            String t = e.getMessage().getText();
            out.add(e.getMessage().getClass().getSimpleName() + "(" + (t == null ? "" : t.replaceAll("\\R+", " ")) + ")");
        }
        return out;
    }

    // ── A：评估结论落库（C1 中段插入） ─────────────────────────────────

    @Test
    @DisplayName("放行 verdict 的标记插到最后一条 AssistantMessage 之前：走 CAS 变体一次命中、恒非尾事件、形态与序列守卫")
    void appliedVerdictInsertsMarkerBeforeLastAssistant() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingStore store = new RecordingStore();
        RecordingRepo repo = new RecordingRepo(store.events);
        gm.bindSession(store, repo, () -> "s-42");
        gm.activate("迁移完成且测试全绿");
        store.appendToolTurn("[goal 继续 1/25]…");     // CodingAgent 侧已完成的第一轮（4 事件）

        runTurn(gm, unsat("还差登录页"));

        // R2 CAS 路径：写回恰走一次 3 参 CAS 变体（正常路径无竞态、一次命中），版本号取自 getEventVersion
        assertEquals(1, repo.casCalls, "写回走 CAS 变体且恰一次（盲写 2 参 replaceEvents 已废除）");
        assertEquals(List.of(4L), repo.expectedVersions, "expectedVersion=读时快照（4 事件 → 版本 4）");
        assertEquals(1, store.markerCount(), "放行 verdict 恰落一条标记事件");
        assertEquals(5, store.events.size(), "中段插入：原 4 事件一条不少");
        SessionEvent marker = store.events.get(3);
        assertInstanceOf(UserMessage.class, marker.getMessage(), "落库形态是合成 UserMessage");
        assertEquals("s-42", marker.getSessionId(), "落库取 sessionIdSupplier 的当前值");
        assertEquals(GoalText.wrapEvaluation("UNSATISFIED", "还差登录页"), marker.getMessage().getText(),
                "文本为 wrapEvaluation 合成块");
        assertTrue(marker.getMessage().getText().startsWith(GoalText.EVAL_OPEN), "行首判定标记（HistoryReplay 靠它识别）");
        // 插入点：最后一条 AssistantMessage 之前 → 标记后面必跟 assistant（恒非尾事件，fold 够不到）
        assertInstanceOf(AssistantMessage.class, store.events.get(4).getMessage(),
                "标记插在最后一条 assistant 之前：其后必是 assistant（标记不在尾部）");
        assertInstanceOf(ToolResponseMessage.class, store.events.get(2).getMessage(),
                "标记之前是本轮 tool 结果（真实轮形状的中段）");
        assertNoConsecutiveUsers(store.events);
    }

    @Test
    @DisplayName("CAS 竞态重试：首次未命中（他人抢先写入）→ 重读重试第二次命中，标记与他人的写入都存活")
    void casConflictRetriesAndPreservesConcurrentWrite() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingStore store = new RecordingStore();
        RacyRepo repo = new RacyRepo(store.events);
        gm.bindSession(store, repo, () -> "s");
        gm.activate("g");
        store.appendToolTurn("[goal 继续 1/25]…");     // 4 事件 → 版本 4

        runTurn(gm, unsat("竞态窗口"));

        assertEquals(2, repo.casCalls, "首次 CAS 未命中后恰好重试一次命中（不第三读、不放弃）");
        assertEquals(List.of(4L, 5L), repo.expectedVersions,
                "重试前重读版本：他人抢先写入已使版本 4→5（旧号 4 撞不上，重读拿新号）");
        assertTrue(repo.submittedLists.get(1).stream().anyMatch(e -> e.getMessage() instanceof UserMessage um
                        && "用户插话抢先".equals(um.getText())),
                "重试提交的整表须含他人抢先写入的事件（重读拿新状态拼标记，绝不覆盖他人写入）");
        assertEquals(1, store.markerCount(), "重试命中后标记存活");
        assertTrue(store.events.stream().anyMatch(e -> e.getMessage() instanceof UserMessage um
                        && "用户插话抢先".equals(um.getText())),
                "他人抢先写入的事件在最终会话中存活（旧盲写实现会把它整个抹掉——本测试的存在理由）");
        assertEquals(6, store.events.size(), "4 轮事件 + 抢先 user + 标记，一条不少");
        assertNoConsecutiveUsers(store.events);
    }

    @Test
    @DisplayName("CAS 重试耗尽（3 次均未命中）→ 放弃落库：不抛、不盲写覆盖，评估主流程照常推进")
    void casExhaustedGivesUpWithoutOverwriting() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingStore store = new RecordingStore();
        AlwaysConflictingRepo repo = new AlwaysConflictingRepo(store.events);
        gm.bindSession(store, repo, () -> "s");
        gm.activate("g");
        store.appendToolTurn("[goal 继续 1/25]…");

        runTurn(gm, unsat("一直冲突"));

        assertEquals(3, repo.casCalls, "重试上限恰 3 次（放弃后不再碰仓库）");
        assertEquals(List.of(4L, 4L, 4L), repo.expectedVersions, "每次重试都重读版本（无变化恒 4）");
        assertEquals(0, store.markerCount(), "重试耗尽：标记本轮丢失（良性，宁丢勿覆盖）");
        assertEquals(4, store.events.size(), "绝不盲写：会话保持原状，他人窗口内的写入不被旧表覆盖");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "落库放弃不影响评估主流程");
        assertTrue(gm.hasAutoTurnPending(), "UNSAT 照常置下一轮 pending");
    }

    @Test
    @DisplayName("C1 集成（fold 语义）：submit 桩折走尾部 user 后标记存活；用户消息与自动轮 prompt 不含被折入的标记")
    void markerSurvivesFoldSemanticsIntegration() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingStore store = new RecordingStore();
        gm.bindSession(store, new RecordingRepo(store.events), () -> "s");
        gm.activate("迁移完成且测试全绿");

        // 第一轮（自动轮）：prompt 派发（桩记录出站）→ 轮完成（user/tool/assistant 落库）→ 评估落库标记
        String prompt1 = gm.takeAutoTurn();
        assertNotNull(prompt1);
        assertFalse(prompt1.contains(GoalText.EVAL_OPEN), "首轮自动轮 prompt 不含评估标记（尚无结论）");
        store.appendToolTurn(prompt1);
        long epoch1 = gm.beginEvaluation();                    // 轮已派发：无 pending 阻塞
        assertTrue(epoch1 > 0, "前置：评估可发起");
        gm.onVerdict(epoch1, unsat("还差登录页"));
        assertEquals(1, store.markerCount(), "前置：第一轮结论标记已落库");

        // 用户插话（spec §5.2 对话语义：清挂起轮、推对话边界），随后 submit：
        // 装置镜像 CodingAgent.submit 先 foldTrailingUserIntoOutbound——尾部是 assistant
        // （标记在 assistant 前的中段）→ 折不到标记，出站只有用户原文。
        gm.onUserDispatch();
        String foldedFromTail = store.foldTrailingUserIntoOutbound();
        assertTrue(foldedFromTail.isEmpty(), "尾部是 assistant：fold 空手而归（折不到中段标记）");
        String userOutbound = "改用另一个方案";
        assertFalse(userOutbound.contains(GoalText.EVAL_OPEN),
                "用户消息出站不得混入被折的评估标记（C1 旧症状：[goal 评估]…\\n\\n<用户文本>）");
        store.appendToolTurn(userOutbound);
        assertEquals(1, store.markerCount(), "标记在 fold 之后存活（没被折走删除）");
        // 标记仍非尾事件（其后还有本轮 assistant）
        assertFalse(store.events.get(store.events.size() - 1).getMessage() instanceof UserMessage um
                && um.getText().startsWith(GoalText.EVAL_OPEN), "标记不得成为尾事件");

        // 用户轮结束 → 第二次评估（插话已清 pending，评估输入含该轮）→ 第二条标记
        long epoch2 = gm.beginEvaluation();
        assertTrue(epoch2 > 0, "用户轮后应可发起评估");
        gm.onVerdict(epoch2, unsat("第二处还没改"));
        assertEquals(2, store.markerCount(), "第二条结论标记落库");
        // 下一自动轮 prompt 同样不含标记（评估结论只进内存/prompt 行，不折入出站）
        String prompt2 = gm.takeAutoTurn();
        assertNotNull(prompt2);
        assertFalse(prompt2.contains(GoalText.EVAL_OPEN), "自动轮 prompt 不含被折入的标记（评估输入走内存滚动记录）");
        store.appendToolTurn(prompt2);

        // 序列形态：三轮 + 两条标记后，任何位置都不出现连续双 user（中段插入的天然保证）
        assertNoConsecutiveUsers(store.events);
        // 且两条标记各插在自己那轮最后一条 assistant 之前（审计顺序与轮次对齐）
        int firstMarker = indexOfMarker(store, 0);
        int secondMarker = indexOfMarker(store, firstMarker + 1);
        assertInstanceOf(AssistantMessage.class, store.events.get(firstMarker + 1).getMessage(),
                "标记 1 后跟 assistant");
        assertInstanceOf(AssistantMessage.class, store.events.get(secondMarker + 1).getMessage(),
                "标记 2 后跟 assistant");
        assertTrue(firstMarker < secondMarker, "标记按评估顺序排列");
    }

    /** 找第 n 条评估标记事件的下标。 */
    private static int indexOfMarker(RecordingStore store, int from) {
        for (int i = from; i < store.events.size(); i++) {
            if (store.events.get(i).getMessage() instanceof UserMessage um
                    && um.getText() != null && um.getText().startsWith(GoalText.EVAL_OPEN)) {
                return i;
            }
        }
        throw new AssertionError("找不到评估标记（from=" + from + "）：" + describe(store.events));
    }

    @Test
    @DisplayName("无 AssistantMessage 则跳过落库（空会话/纯 user 壳不硬插——宁缺勿尾部）")
    void noAssistantMessageSkipsPersistence() {
        GoalManager gm = new GoalManager(cfg(), null);
        RecordingStore store = new RecordingStore();
        gm.bindSession(store, new RecordingRepo(store.events), () -> "s");
        gm.activate("g");
        // 会话空壳：只有一条 user（如上个回合 after() 未落 assistant 的残留形状）
        store.events.add(SessionEvent.builder().sessionId("s").message(new UserMessage("残留 user")).build());

        runTurn(gm, unsat("无锚点"));

        assertEquals(0, store.markerCount(), "无 AssistantMessage 锚点：跳过落库（追加尾部只会重演 fold 事故）");
        assertEquals(1, store.events.size(), "会话零改写");
        assertEquals(GoalPhase.RUNNING, gm.phase(), "跳过落库不影响评估主流程");
    }

    @Test
    @DisplayName("丢弃的 verdict 不落库：Esc 暂停迟到 / serial 过期 / 终态 no-op 三门各钉一条")
    void droppedVerdictsAreNotPersisted() {
        // (a) Esc 暂停后的迟到 SATISFIED：判定丢弃（spec §3.3），不得落库
        GoalManager a = new GoalManager(cfg(), null);
        RecordingStore sa = new RecordingStore();
        a.bindSession(sa, new RecordingRepo(sa.events), () -> "s1");
        a.activate("g");
        storeTurn(sa, a.takeAutoTurn());
        long ea = a.beginEvaluation();
        a.pauseByEsc();
        a.onVerdict(ea, new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "迟到", false, null, "raw"));
        assertEquals(0, sa.markerCount(), "PAUSED 丢弃的 verdict 不得落库");

        // (b) 评估在飞期间插话（dispatchSerial 过期，spec §5.2 单槽作废）：不落库
        GoalManager b = new GoalManager(cfg(), null);
        RecordingStore sb = new RecordingStore();
        b.bindSession(sb, new RecordingRepo(sb.events), () -> "s2");
        b.activate("g");
        storeTurn(sb, b.takeAutoTurn());
        long eb = b.beginEvaluation();
        b.onUserDispatch();                          // 插话：锁存 serial 过期
        b.onVerdict(eb, unsat("过期判定"));
        assertEquals(0, sb.markerCount(), "serial 过期的 verdict 不得落库");

        // (c) /clear（终态）后的迟到 verdict：完全 no-op，不落库（场景⑨守卫源头）
        GoalManager c = new GoalManager(cfg(), null);
        RecordingStore sc = new RecordingStore();
        c.bindSession(sc, new RecordingRepo(sc.events), () -> "s3");
        c.activate("g");
        storeTurn(sc, c.takeAutoTurn());
        long ec = c.beginEvaluation();
        c.clear("clear-context");
        c.onVerdict(ec, unsat("清空后迟到"));
        assertEquals(0, sc.markerCount(), "终态 no-op 的 verdict 不得落库");
    }

    /** 桩侧轮形状：user prompt → assistant 收尾（丢弃路径的落库与否不受形状影响，最简两事件即可）。 */
    private static void storeTurn(RecordingStore store, String prompt) {
        store.events.add(SessionEvent.builder().sessionId("s").message(new UserMessage(prompt)).build());
        store.events.add(SessionEvent.builder().sessionId("s").message(new AssistantMessage("收尾")).build());
    }

    @Test
    @DisplayName("未 bindSession 静默跳过；replaceEvents 抛异常只记日志、评估主流程照常推进")
    void noBindingOrFailingStoreDoesNotBreakEvaluation() {
        // 未接线：不落库也不抛
        GoalManager plain = new GoalManager(cfg(), null);
        plain.activate("g");
        runTurn(plain, unsat("无会话"));
        assertEquals(GoalPhase.RUNNING, plain.phase(), "未接线不影响状态机");

        // 只接 service 不接 repository（C1 后中段插入无从写回）：静默跳过
        GoalManager noRepo = new GoalManager(cfg(), null);
        RecordingStore storeOnly = new RecordingStore();
        noRepo.bindSession(storeOnly, null, () -> "s");
        noRepo.activate("g");
        storeOnly.appendToolTurn("[goal 继续 1/25]…");
        runTurn(noRepo, unsat("无仓库"));
        assertEquals(0, storeOnly.markerCount(), "缺 repository：中段插入无写回端，静默跳过");
        assertEquals(GoalPhase.RUNNING, noRepo.phase());

        // 接线但 replaceEvents 抛：log.warn 不抛出（此处 runTurn 不炸即为断言），轮次照常入账
        GoalManager failing = new GoalManager(cfg(), null);
        RecordingStore failingStore = new RecordingStore();
        failing.bindSession(failingStore, new FailingRepo(failingStore.events), () -> "s");
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

    // ── 自检：装置形状（防桩自身漂移） ─────────────────────────────────

    @Test
    @DisplayName("装置自检：fold 桩真能折走尾部 user（旧 C1 事故的复现前提）")
    void fixtureFoldActuallyFoldsTrailingUser() {
        RecordingStore store = new RecordingStore();
        store.events.add(SessionEvent.builder().sessionId("s").message(new UserMessage("尾巴 user")).build());
        String folded = store.foldTrailingUserIntoOutbound();
        assertEquals("尾巴 user", folded, "折走尾部 user 的文本");
        assertTrue(store.events.isEmpty(), "尾部 user 已从会话删除（fold 语义）");
    }
}
