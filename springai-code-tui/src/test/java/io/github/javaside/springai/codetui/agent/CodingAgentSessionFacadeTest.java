package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.session.FileSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodingAgent 四个会话门面落地实现（sessionExists / forkSession / deleteSession / sessionMessages）的
 * 真实装配测试：注入 {@link FileSessionRepository} + {@link DefaultSessionService}（生产装配同款，
 * 见 {@code AgentTools.build}），而不是 UI 测试的记录型桩——桩只验证「门面被调到」，这里验证「门面做对了」。
 * 这是对既有代码补覆盖（无先红阶段），有效性靠变异自检：任一断言对应的实现被改坏必须变红（见发版记录）。
 */
class CodingAgentSessionFacadeTest {

    private static final String SID = "code-tui-session";

    private static SessionEvent ev(String sid, Message m) {
        return SessionEvent.builder().sessionId(sid).message(m).build();
    }

    private static Session session(String sid) {
        return Session.builder().id(sid).userId("u").createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).metadata(Map.of()).build();
    }

    /**
     * 23 参全参构造器注入真仓库与真服务（参数序以 CodingAgent 全参构造器签名为准）。
     * skills 必须传 List.of()——null 会在构造器 List.copyOf 处 NPE；其余依赖本测试不触及，传 null
     * （先例：{@code CodingAgentSwitchContextTest.agentWithAccumulator} 的注释与坑）。
     */
    private static CodingAgent agentWith(SessionRepository repo, SessionService service) {
        return new CodingAgent(null, null, null, "live-session", new AtomicLong(), service, null, null,
                List.of(), null, repo, null, null, null, null, null, null, null, null, null, null,
                0L, null);
    }

    @Test
    void sessionExists_reflectsRepository_nullGuarded(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        CodingAgent agent = agentWith(repo, DefaultSessionService.builder().sessionRepository(repo).build());
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("有事件才算数")));

        assertTrue(agent.sessionExists(SID), "预写会话存在");
        assertFalse(agent.sessionExists("no-such-session"), "不存在的 id 返回 false 而非抛");
        assertFalse(agent.sessionExists(null),
                "null 入参走守卫返回 false——去掉守卫 findById(null) 会在 ConcurrentHashMap 处 NPE");
    }

    @Test
    void forkSession_copiesEventsToNewId_sourceUntouched(@TempDir Path dir) throws Exception {
        FileSessionRepository repo = new FileSessionRepository(dir);
        CodingAgent agent = agentWith(repo, DefaultSessionService.builder().sessionRepository(repo).build());
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("原会话问题")));
        repo.appendEvent(ev(SID, new AssistantMessage("原会话回答")));
        String sourceBytes = Files.readString(dir.resolve(SID + ".json"));

        Optional<String> forked = agent.forkSession(SID);

        assertTrue(forked.isPresent(), "有事件的会话可分叉——门面若恒返回 empty（instanceof 转型失效）此处红");
        String newId = forked.get();
        assertNotEquals(SID, newId, "分叉得到新 id，不是源 id");
        List<SessionEvent> events = repo.findEvents(newId, EventFilter.all());
        assertEquals(2, events.size(), "事件逐条复制到新会话");
        assertEquals("原会话问题", events.get(0).getMessage().getText());
        assertEquals("原会话回答", events.get(1).getMessage().getText());
        assertTrue(events.stream().allMatch(e -> newId.equals(e.getSessionId())),
                "事件的 sessionId 必须重写为新 id——直拷文件或漏掉 retarget，事件仍自报源会话 id");
        assertEquals(sourceBytes, Files.readString(dir.resolve(SID + ".json")), "源会话文件字节不动");
    }

    @Test
    void forkSession_emptySourceReturnsEmpty(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        CodingAgent agent = agentWith(repo, DefaultSessionService.builder().sessionRepository(repo).build());
        repo.save(session(SID));   // 已保存但无事件

        assertEquals(Optional.empty(), agent.forkSession(SID), "空会话分叉无意义，返回 empty");
    }

    @Test
    void deleteSession_removesFile_nullGuarded(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        CodingAgent agent = agentWith(repo, DefaultSessionService.builder().sessionRepository(repo).build());
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("q")));
        assertTrue(Files.exists(dir.resolve(SID + ".json")), "前置：会话文件在盘上");

        agent.deleteSession(SID);

        assertFalse(Files.exists(dir.resolve(SID + ".json")), "真删——文件不在了（门面若 no-op 此处红）");
        assertTrue(repo.findById(SID).isEmpty(), "仓库侧也查不到该会话");
        assertFalse(agent.sessionExists(SID), "sessionExists 同步反映删除");
        assertDoesNotThrow(() -> agent.deleteSession(null),
                "null 入参走守卫不抛——去掉守卫 delete(null) 会在 ConcurrentHashMap 处 NPE");
    }

    @Test
    void sessionMessages_readsThroughSessionService_nullGuarded(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        CodingAgent agent = agentWith(repo, DefaultSessionService.builder().sessionRepository(repo).build());
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("用户问题")));
        repo.appendEvent(ev(SID, new AssistantMessage("模型回答")));

        List<Message> msgs = agent.sessionMessages(SID);

        assertEquals(2, msgs.size(), "走 DefaultSessionService.getMessages 读出全部消息（恒空表此处红）");
        assertEquals("用户问题", msgs.get(0).getText());
        assertEquals("模型回答", msgs.get(1).getText());
        assertTrue(agent.sessionMessages(null).isEmpty(),
                "null 入参返回空表不抛——去掉守卫 getMessages(null) 会在 ConcurrentHashMap 处 NPE");
    }
}
