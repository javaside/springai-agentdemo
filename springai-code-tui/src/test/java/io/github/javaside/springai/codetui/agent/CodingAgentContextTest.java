package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodingAgent.contextStats：读会话事件（按消息类型分桶、计数）+ 拼接消息文本估算 token，
 * 组成纯 Java 的 {@link ContextStats}。策略数（阈值/窗口/保留窗口）取自 {@link AgentTools} 同包常量。
 * 不涉及 ChatClient / listener / 压缩策略——故这几项传 null。
 */
class CodingAgentContextTest {

    /** 只回放预置事件/消息的假 SessionService；其余方法用不到，抛未支持。 */
    private static final class StubSessionService implements SessionService {
        final List<SessionEvent> events;
        final List<Message> messages;
        StubSessionService(List<SessionEvent> events, List<Message> messages) {
            this.events = events; this.messages = messages;
        }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return events; }
        @Override public List<Message> getMessages(String id) { return messages; }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public void appendEvent(SessionEvent e) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }
    }

    /** 确定性估算器：token 数 = 文本字符数（便于精确断言）。 */
    private static final TokenCountEstimator LEN_ESTIMATOR = new TokenCountEstimator() {
        @Override public int estimate(String text) { return text.length(); }
        @Override public int estimate(MediaContent c) { return 0; }
        @Override public int estimate(Iterable<MediaContent> c) { return 0; }
    };

    private static SessionEvent event(Message m) {
        return SessionEvent.builder().sessionId("s").message(m).build();
    }

    private static CodingAgent agentOver(SessionService svc, TokenCountEstimator est) {
        // contextStats 只依赖 sessionService + estimator；chatClient/listener/manualStrategy 用不到。
        return new CodingAgent(null, null, "s", new AtomicLong(), svc, null, est);
    }

    @Test
    void contextStats_countsEventsByType_andEstimatesTokensFromMessageText() {
        List<SessionEvent> events = List.of(
                event(new UserMessage("hello")),
                event(new AssistantMessage("world!!")),
                event(new AssistantMessage("again")));
        // 拼接文本 = "hello\n" + "world!!\n"（各 +1 换行）；LEN_ESTIMATOR 直接返回长度。
        List<Message> messages = List.of(new UserMessage("hello"), new AssistantMessage("world!!"));
        CodingAgent agent = agentOver(new StubSessionService(events, messages), LEN_ESTIMATOR);

        ContextStats s = agent.contextStats();

        assertEquals(3, s.events(), "事件总数");
        assertEquals(1, s.userEvents(), "用户事件数");
        assertEquals(2, s.assistantEvents(), "助手事件数");
        assertEquals(0, s.toolEvents(), "工具事件数");
        assertEquals(0, s.otherEvents(), "其他事件数");
        assertEquals(("hello\n" + "world!!\n").length(), s.estimatedTokens(), "估算 token = 拼接文本长度");
        // 策略数直接映射自 AgentTools 常量（同包可见），保证 /context 展示与实际装配一致。
        assertEquals(AgentTools.COMPACTION_TOKEN_THRESHOLD, s.tokenThreshold());
        assertEquals(AgentTools.CONTEXT_WINDOW_TOKENS, s.contextWindow());
        assertEquals(AgentTools.MAX_EVENTS_TO_KEEP, s.autoKeepEvents());
        assertEquals(AgentTools.MANUAL_MAX_EVENTS_TO_KEEP, s.manualKeepEvents());
    }

    @Test
    void contextStats_emptySession_isAllZeroCountsAndZeroTokens() {
        CodingAgent agent = agentOver(new StubSessionService(List.of(), List.of()), LEN_ESTIMATOR);

        ContextStats s = agent.contextStats();

        assertEquals(0, s.events(), "空会话事件数为 0");
        assertEquals(0, s.estimatedTokens(), "空会话不调用估算器，token 为 0");
    }
}
