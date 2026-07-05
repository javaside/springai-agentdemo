package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话净化：既裁尾部悬空 tool_calls，也丢孤儿 tool 结果——后者是恢复会话（-c）首个请求 400 的根因。
 * 复现真实坏会话里观测到的模式：AskUserQuestion 的 tool 结果残留、却没有对应的 assistant tool_call。
 */
class SessionEventsTest {

    private static SessionEvent ev(Message m) {
        return SessionEvent.builder().sessionId("s").message(m).build();
    }

    private static AssistantMessage asstCalls(String... ids) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (String id : ids) calls.add(new AssistantMessage.ToolCall(id, "function", "T", "{}"));
        return AssistantMessage.builder().content("").toolCalls(calls).build();
    }

    private static ToolResponseMessage toolResults(String... ids) {
        List<ToolResponseMessage.ToolResponse> rs = new ArrayList<>();
        for (String id : ids) rs.add(new ToolResponseMessage.ToolResponse(id, "T", "ok-" + id));
        return ToolResponseMessage.builder().responses(rs).build();
    }

    /** 断言不变量：每条 tool 结果的 id 都能在其之前的 assistant tool_calls 里找到（无孤儿）。 */
    private static void assertNoOrphans(List<SessionEvent> events) {
        Set<String> open = new HashSet<>();
        for (SessionEvent e : events) {
            Message m = e.getMessage();
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                am.getToolCalls().forEach(tc -> open.add(tc.id()));
            } else if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    assertTrue(open.remove(tr.id()), "孤儿 tool 结果未被清除：" + tr.id());
                }
            }
        }
        assertTrue(open.isEmpty(), "残留悬空 tool_calls：" + open);
    }

    @Test
    void dropsOrphanToolResponseInMiddle() {
        // 真实坏会话模式：asst(a,b) → tool(a,b) → tool(orphan) → user。orphan 无对应 assistant tool_call。
        List<SessionEvent> in = List.of(
                ev(new UserMessage("q")),
                ev(asstCalls("a", "b")),
                ev(toolResults("a", "b")),
                ev(toolResults("orphan")),        // ← 孤儿：id 从未出现在任何 assistant tool_calls
                ev(new UserMessage("再问")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(4, out.size(), "孤儿 tool 消息应被丢弃");
        assertNoOrphans(out);
        // 保留的 tool 结果仍是 a,b；末尾仍是用户消息
        assertTrue(out.get(2).getMessage() instanceof ToolResponseMessage);
        assertEquals("再问", out.get(3).getMessage().getText());
    }

    @Test
    void rebuildsPartiallyOrphanToolMessage() {
        // 一条 tool 消息里既有命中(a)又有孤儿(z) → 重建为只含 a。
        List<SessionEvent> in = List.of(
                ev(asstCalls("a")),
                ev(toolResults("a", "z")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(2, out.size());
        ToolResponseMessage trm = (ToolResponseMessage) out.get(1).getMessage();
        assertEquals(List.of("a"), trm.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList());
        assertNoOrphans(out);
    }

    @Test
    void trimsTrailingDanglingToolCalls() {
        List<SessionEvent> in = List.of(
                ev(new UserMessage("q")),
                ev(asstCalls("a")),
                ev(toolResults("a")),
                ev(asstCalls("dangling")));       // ← 尾部悬空：有调用没结果
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(3, out.size(), "尾部悬空 assistant(tool_calls) 应被裁掉");
        assertEquals("q", out.get(0).getMessage().getText());
        assertNoOrphans(out);   // 末尾 open 必须为空 = 无残留悬空 tool_calls
    }

    @Test
    void balancedHistoryReturnedUnchanged() {
        List<SessionEvent> in = List.of(
                ev(new UserMessage("q")),
                ev(asstCalls("a", "b")),
                ev(toolResults("a", "b")),
                ev(new AssistantMessage("终答")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertSame(in, out, "合法历史零改动应返回同一引用");
    }
}
