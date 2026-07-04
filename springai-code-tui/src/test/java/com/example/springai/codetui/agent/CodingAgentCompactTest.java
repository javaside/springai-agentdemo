package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CodingAgent.runCompaction 用「强制触发器 + 手动策略」调 SessionService.compact，并把结果计数经 listener 透出。 */
class CodingAgentCompactTest {

    // CompactionRequest.of 内部 Assert.notNull(session, ...)，故不能传 null——用一个占位 Session 满足契约。
    private static final Session DUMMY_SESSION = Session.builder().id("sess-1").userId("test-user").build();
    // CompactionResult.eventsRemoved() 派生自 archivedEvents.size()——传空表恒为 0，
    // 会走「无可压缩内容」分支而非「已压缩...约省 N tokens」分支，故需一个占位事件。
    private static final SessionEvent DUMMY_EVENT =
            SessionEvent.builder().sessionId("sess-1").message(new UserMessage("x")).build();

    /** 记录 compact(...) 入参、返回预置结果的假 SessionService；其余方法用不到，抛未支持。 */
    private static final class FakeSessionService implements SessionService {
        final AtomicReference<String> sessionId = new AtomicReference<>();
        final AtomicReference<CompactionTrigger> trigger = new AtomicReference<>();
        final AtomicReference<CompactionStrategy> strategy = new AtomicReference<>();
        RuntimeException failBeforeStrategy;   // 非空则模拟「进入策略前」抛错（会话不存在 / I/O）

        @Override
        public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            sessionId.set(id); trigger.set(t); strategy.set(s);
            if (failBeforeStrategy != null) throw failBeforeStrategy;   // 未进策略即失败：decorator 不会发 started
            // 模拟真实流程：判定 trigger 命中后才进策略。这里直接进策略以驱动通知装饰器。
            return s.compact(CompactionRequest.of(DUMMY_SESSION, List.of()));
        }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public void appendEvent(SessionEvent e) { throw new UnsupportedOperationException(); }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { throw new UnsupportedOperationException(); }
    }

    @Test
    void runCompaction_forcesCompaction_andReportsFinishedExactlyOnce() {
        OrderRecordingListener listener = new OrderRecordingListener();
        CompactionResult result = new CompactionResult(List.of(), List.of(DUMMY_EVENT), 999);
        FakeSessionService fake = new FakeSessionService();
        CompactionStrategy manual = req -> result;   // 裸策略：runCompaction 从返回值自行上报，不叠加装饰器

        CodingAgent agent = new CodingAgent(
                dummyChatClient(), listener, "sess-1", new AtomicLong(), fake, manual, null);

        agent.runCompaction();   // 同步

        assertNotNull(fake.trigger.get(), "应调用 compact 并传入触发器");
        assertTrue(fake.trigger.get().shouldCompact(CompactionRequest.of(DUMMY_SESSION, List.of())),
                "手动触发器必须恒为 true（强制压缩）");
        assertSame(manual, fake.strategy.get(), "应传入手动策略");
        assertEquals(List.of("started:manual", "finished:1:999"), listener.events,
                "成功路径应恰好 started 一次、finished 一次（计数来自返回的 CompactionResult）");
    }

    @Test
    void runCompaction_reportsFailedExactlyOnce_whenStrategyItselfThrows() {
        OrderRecordingListener listener = new OrderRecordingListener();
        FakeSessionService fake = new FakeSessionService();
        // 策略「内部」失败（模拟摘要 LLM 调用抛错）：手动策略未包装装饰器，故只应被 runCompaction 上报一次。
        CompactionStrategy manual = req -> { throw new RuntimeException("llm boom"); };

        CodingAgent agent = new CodingAgent(
                dummyChatClient(), listener, "sess-1", new AtomicLong(), fake, manual, null);

        agent.runCompaction();   // 同步

        assertEquals(List.of("started:manual", "failed:llm boom"), listener.events,
                "策略内部失败应恰好上报一次 failed（不因装饰器与 runCompaction 叠加而重复）");
    }

    @Test
    void runCompaction_firesStarted_beforeFailed_whenCompactThrowsPreStrategy() {
        OrderRecordingListener listener = new OrderRecordingListener();
        FakeSessionService fake = new FakeSessionService();
        fake.failBeforeStrategy = new RuntimeException("session not found");   // 进策略前就抛
        // 策略永不应被调用（compact 会先抛），但仍需传一个非 null 策略。
        CompactionStrategy manual = new NotifyingCompactionStrategy(req -> null, listener, "manual");

        CodingAgent agent = new CodingAgent(
                dummyChatClient(), listener, "sess-1", new AtomicLong(), fake, manual, null);

        agent.runCompaction();   // 同步

        // 即便「进入策略前」失败，也必须先发过 started，再发 failed——不能只有失败行、没有开始提示。
        assertEquals(List.of("started:manual", "failed:session not found"), listener.events,
                "pre-strategy 失败也应先 started 再 failed");
    }

    /** 按调用顺序记录压缩三事件的最小 listener（其余接缝方法空实现）。 */
    private static final class OrderRecordingListener implements AgentListener {
        final List<String> events = new ArrayList<>();
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { events.add("started:" + reason); }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) {
            events.add("finished:" + eventsRemoved + ":" + tokensSaved);
        }
        @Override public void onCompactionFailed(String message) { events.add("failed:" + message); }
    }

    /** 占位 ChatClient：runCompaction 不触发对话，不会真正用到它。 */
    private static org.springframework.ai.chat.client.ChatClient dummyChatClient() {
        return (org.springframework.ai.chat.client.ChatClient) java.lang.reflect.Proxy.newProxyInstance(
                org.springframework.ai.chat.client.ChatClient.class.getClassLoader(),
                new Class[]{org.springframework.ai.chat.client.ChatClient.class},
                (p, m, a) -> { throw new UnsupportedOperationException(String.valueOf(m.getName())); });
    }
}
