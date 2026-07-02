package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

        @Override
        public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            sessionId.set(id); trigger.set(t); strategy.set(s);
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
    void runCompaction_callsCompact_withForcingTrigger_andManualStrategy() {
        ConversationState state = new ConversationState();
        CompactionResult result = new CompactionResult(List.of(), List.of(DUMMY_EVENT), 999);
        FakeSessionService fake = new FakeSessionService();
        // 手动策略：包通知装饰器，驱动 state 的 started/finished（复用真实装饰器）。
        CompactionStrategy manual = new NotifyingCompactionStrategy(req -> result, state, "manual");

        CodingAgent agent = new CodingAgent(
                dummyChatClient(), state, "sess-1", new AtomicLong(), fake, manual);

        agent.runCompaction();   // 同步

        assertNotNull(fake.trigger.get(), "应调用 compact 并传入触发器");
        assertTrue(fake.trigger.get().shouldCompact(CompactionRequest.of(DUMMY_SESSION, List.of())),
                "手动触发器必须恒为 true（强制压缩）");
        assertSame(manual, fake.strategy.get(), "应传入手动策略");
        assertTrue(state.drainPending().stream().anyMatch(l -> l.text().contains("999")),
                "结果计数应经 listener 落进 pending");
    }

    /** 占位 ChatClient：runCompaction 不触发对话，不会真正用到它。 */
    private static org.springframework.ai.chat.client.ChatClient dummyChatClient() {
        return (org.springframework.ai.chat.client.ChatClient) java.lang.reflect.Proxy.newProxyInstance(
                org.springframework.ai.chat.client.ChatClient.class.getClassLoader(),
                new Class[]{org.springframework.ai.chat.client.ChatClient.class},
                (p, m, a) -> { throw new UnsupportedOperationException(String.valueOf(m.getName())); });
    }
}
