package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件版会话仓库：往返序列化、version/CAS 语义、缺失会话语义、findEvents 过滤、加载裁悬空、坏文件不崩，
 * 以及「重开」= 新建仓库指向同目录后历史仍在（跨重启恢复的核心保证）。
 */
class FileSessionRepositoryTest {

    private static final String SID = "code-tui-session";

    private static SessionEvent ev(String sid, Message m) {
        return SessionEvent.builder().sessionId(sid).message(m).build();
    }

    private static Session session(String sid) {
        return Session.builder().id(sid).userId("u").createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).metadata(Map.of()).build();
    }

    private static AssistantMessage asstCalls(String... ids) {
        List<AssistantMessage.ToolCall> calls = java.util.Arrays.stream(ids)
                .map(id -> new AssistantMessage.ToolCall(id, "function", "Task", "{\"x\":1}")).toList();
        return AssistantMessage.builder().content("(委派)").toolCalls(calls).build();
    }

    private static ToolResponseMessage toolResults(String... ids) {
        List<ToolResponseMessage.ToolResponse> rs = java.util.Arrays.stream(ids)
                .map(id -> new ToolResponseMessage.ToolResponse(id, "Task", "done-" + id)).toList();
        return ToolResponseMessage.builder().responses(rs).build();
    }

    @Test
    void roundTripsAllMessageTypesAcrossReopen(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("用户问题")));
        repo.appendEvent(ev(SID, new SystemMessage("系统提示")));
        repo.appendEvent(ev(SID, new AssistantMessage("纯文本回复")));
        repo.appendEvent(ev(SID, asstCalls("call_a", "call_b")));   // 一次两个 tool_call
        repo.appendEvent(ev(SID, toolResults("call_a", "call_b")));

        // 「重开」：新建一个仓库指向同目录，模拟进程重启
        FileSessionRepository reopened = new FileSessionRepository(dir);
        List<Message> msgs = reopened.findEvents(SID, EventFilter.all()).stream()
                .map(SessionEvent::getMessage).toList();

        assertEquals(5, msgs.size(), "全部事件都应被持久化并加载回来");
        assertEquals(MessageType.USER, msgs.get(0).getMessageType());
        assertEquals("用户问题", msgs.get(0).getText());
        assertEquals(MessageType.SYSTEM, msgs.get(1).getMessageType());
        assertEquals("纯文本回复", msgs.get(2).getText());
        // assistant + tool_calls 逐字还原
        AssistantMessage am = (AssistantMessage) msgs.get(3);
        assertTrue(am.hasToolCalls());
        assertEquals(List.of("call_a", "call_b"), am.getToolCalls().stream().map(AssistantMessage.ToolCall::id).toList());
        assertEquals("{\"x\":1}", am.getToolCalls().get(0).arguments(), "tool_call 参数逐字还原");
        // tool 结果逐字还原
        ToolResponseMessage trm = (ToolResponseMessage) msgs.get(4);
        assertEquals(List.of("call_a", "call_b"), trm.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList());
        assertEquals("done-call_a", trm.getResponses().get(0).responseData());
    }

    @Test
    void versionIncrementsOnAppendAndReplace(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        assertEquals(0L, repo.getEventVersion(SID), "新会话 version=0");
        repo.appendEvent(ev(SID, new UserMessage("a")));
        assertEquals(1L, repo.getEventVersion(SID));
        repo.appendEvent(ev(SID, new AssistantMessage("b")));
        assertEquals(2L, repo.getEventVersion(SID));
        repo.replaceEvents(SID, List.of(ev(SID, new UserMessage("only"))));
        assertEquals(3L, repo.getEventVersion(SID), "replace 也 +1");
        // save 已存在会话保留事件与 version
        repo.save(session(SID));
        assertEquals(3L, repo.getEventVersion(SID), "save 保留 version");
        assertEquals(1, repo.findEvents(SID, EventFilter.all()).size(), "save 保留事件");
    }

    @Test
    void casReplaceRespectsExpectedVersion(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("a")));   // version=1
        assertFalse(repo.replaceEvents(SID, List.of(), 0L), "版本不匹配返回 false 且不改");
        assertEquals(1, repo.findEvents(SID, EventFilter.all()).size(), "CAS 未命中不改事件");
        assertTrue(repo.replaceEvents(SID, List.of(), 1L), "版本匹配返回 true");
        assertEquals(0, repo.findEvents(SID, EventFilter.all()).size(), "CAS 命中换成空");
    }

    @Test
    void missingSessionSemantics(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        assertEquals(0L, repo.getEventVersion("nope"), "缺失会话 version=0（不抛）");
        assertTrue(repo.findEvents("nope", EventFilter.all()).isEmpty(), "缺失会话 findEvents 返空（不抛）");
        assertThrows(IllegalArgumentException.class, () -> repo.appendEvent(ev("nope", new UserMessage("x"))));
        assertThrows(IllegalArgumentException.class, () -> repo.replaceEvents("nope", List.of()));
    }

    @Test
    void findEventsLastNReturnsTail(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        for (int i = 0; i < 5; i++) repo.appendEvent(ev(SID, new UserMessage("m" + i)));
        List<SessionEvent> tail = repo.findEvents(SID, EventFilter.lastN(2));
        assertEquals(List.of("m3", "m4"), tail.stream().map(e -> e.getMessage().getText()).toList());
    }

    @Test
    void loadTrimsDanglingToolCalls(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("q")));
        repo.appendEvent(ev(SID, asstCalls("plan")));
        repo.appendEvent(ev(SID, toolResults("plan")));
        repo.appendEvent(ev(SID, asstCalls("dangling")));   // 末尾悬空，无 tool 结果

        FileSessionRepository reopened = new FileSessionRepository(dir);   // 加载即裁
        List<SessionEvent> events = reopened.findEvents(SID, EventFilter.all());
        assertEquals(3, events.size(), "加载时裁掉末尾悬空 assistant(tool_calls)");
        assertFalse(events.get(events.size() - 1).hasToolCalls(), "末尾不再是悬空 tool_calls");
    }

    @Test
    void corruptFileIsSkippedNotFatal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("garbage.json"), "{ not valid json ]");
        FileSessionRepository repo = new FileSessionRepository(dir);
        // 惰性加载坏文件对应的 id：不抛、当作不存在（返回空 / 0），不崩
        assertTrue(repo.findEvents("garbage", EventFilter.all()).isEmpty(), "坏文件当作不存在");
        assertEquals(0L, repo.getEventVersion("garbage"));
        // 仓库仍可正常使用
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("ok")));
        assertEquals(1, repo.findEvents(SID, EventFilter.all()).size());
    }

    @Test
    void constructorDoesNotEagerLoad(@TempDir Path dir) {
        // 先写一个会话到磁盘
        FileSessionRepository writer = new FileSessionRepository(dir);
        writer.save(session(SID));
        writer.appendEvent(ev(SID, new UserMessage("x")));
        // 新仓库指向同目录：构造不应预加载任何会话（惰性）——findByUserId 只见已加载的（空）
        FileSessionRepository lazy = new FileSessionRepository(dir);
        assertTrue(lazy.findByUserId("u").isEmpty(), "构造不 eager load，未访问前 map 为空");
        // 一旦按 id 访问，才惰性载入
        assertEquals(1, lazy.findEvents(SID, EventFilter.all()).size(), "访问该 id 时才载入");
        assertFalse(lazy.findByUserId("u").isEmpty(), "载入后可见");
    }

    @Test
    void latestSessionIdPicksNewestByMtime(@TempDir Path dir) throws Exception {
        assertTrue(FileSessionRepository.latestSessionId(dir).isEmpty(), "空目录无最近会话");
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(Session.builder().id("s-old").userId("u").createdAt(Instant.now()).metadata(Map.of()).build());
        repo.appendEvent(ev("s-old", new UserMessage("旧")));
        repo.save(Session.builder().id("s-new").userId("u").createdAt(Instant.now()).metadata(Map.of()).build());
        repo.appendEvent(ev("s-new", new UserMessage("新")));
        // 显式设置 mtime，避免同毫秒下不确定
        Files.setLastModifiedTime(dir.resolve("s-old.json"), FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(dir.resolve("s-new.json"), FileTime.fromMillis(2_000_000L));
        assertEquals(Optional.of("s-new"), FileSessionRepository.latestSessionId(dir), "选最后修改最新的会话");
    }
}
