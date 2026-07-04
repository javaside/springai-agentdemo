package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 取消回合回滚会话（修复：中途取消工具回合会在会话里留下悬空 assistant(tool_calls)，
 * 下一次请求把坏历史发给 DeepSeek 会 400）。
 *
 * <p>这里直接验证 {@link CodingAgent#snapshotSession()} / {@link CodingAgent#rollbackTo} 这对方法
 * （即 submit 的 {@code doOnCancel} 回调所做的事）在真实 {@link DefaultSessionService} +
 * {@link InMemorySessionRepository} 上把会话回滚到回合前、删掉半截历史。不联网、确定性。
 */
class CodingAgentRollbackTest {

    private static CodingAgent agent(SessionService svc, SessionRepository repo, String sid) {
        return new CodingAgent(null, new StubListener(), sid, new AtomicLong(),
                svc, null, null, List.of(), null, repo);
    }

    /** 建会话（真实流程里由 SessionMemoryAdvisor.before 创建；测试需手动建，否则 appendMessage 报 Session not found）。 */
    private static SessionService session(SessionRepository repo, String sid) {
        SessionService svc = DefaultSessionService.builder().sessionRepository(repo).build();
        svc.create(CreateSessionRequest.builder().id(sid).userId("u").build());
        return svc;
    }

    @Test
    void rollback_restoresSessionToMark_droppingPartialTurn() {
        String sid = "roll-1";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        // 一个已完成的回合：user + assistant
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, new AssistantMessage("a1"));

        CodingAgent agent = agent(svc, repo, sid);
        List<SessionEvent> mark = agent.snapshotSession();     // 回合前快照
        assertEquals(2, mark.size(), "快照应含前一回合的 2 条事件");

        // 模拟一个「被取消的工具回合」写入的半截历史：user + 悬空 assistant（真实里带 tool_calls）
        svc.appendMessage(sid, new UserMessage("q2"));
        svc.appendMessage(sid, new AssistantMessage("(悬空的 tool_calls 回合)"));
        assertEquals(4, svc.getEvents(sid).size(), "半截回合写入后应有 4 条");

        agent.rollbackTo(mark);   // = submit 的 doOnCancel 回调

        assertEquals(2, svc.getEvents(sid).size(), "回滚后应只剩回合前的 2 条");
        assertEquals(List.of("q1", "a1"),
                svc.getMessages(sid).stream().map(Message::getText).toList(),
                "回滚后历史应逐字等于回合前");
    }

    @Test
    void rollback_firstTurn_restoresToEmpty() {
        String sid = "roll-2";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);

        CodingAgent agent = agent(svc, repo, sid);
        List<SessionEvent> mark = agent.snapshotSession();     // 首个回合：会话尚空
        assertEquals(0, mark.size());

        svc.appendMessage(sid, new UserMessage("q"));
        svc.appendMessage(sid, new AssistantMessage("(悬空)"));
        assertEquals(2, svc.getEvents(sid).size());

        agent.rollbackTo(mark);
        assertEquals(0, svc.getEvents(sid).size(), "首个回合被取消后会话应回到空");
    }

    @Test
    void rollback_nullRepository_isNoOpAndDoesNotCrash() {
        String sid = "roll-3";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q1"));

        // 仓库为 null（测试桩装配）：rollbackTo 应静默跳过、不崩、不改会话
        CodingAgent agent = agent(svc, null, sid);
        List<SessionEvent> mark = agent.snapshotSession();
        svc.appendMessage(sid, new UserMessage("q2"));

        assertDoesNotThrow(() -> agent.rollbackTo(mark));
        assertEquals(2, svc.getEvents(sid).size(), "无仓库时不回滚，会话保持不变");
    }
}
