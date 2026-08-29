package io.github.javaside.springai.codetui.agent.session;

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

    // ── 连续同角色折叠（DeepSeek 400 的根因：gpt 回合 after() 抛错致 assistant 不落盘，
    //    before() 已落盘的 user 于是堆成连续 USER，DeepSeek 拒收严格交替失败的历史）──

    @Test
    void collapsesConsecutiveUsersByMergingText() {
        List<SessionEvent> in = List.of(
                ev(new UserMessage("a")),
                ev(new UserMessage("b")),
                ev(new UserMessage("c")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(1, out.size(), "连续 user 应折叠为一条");
        assertTrue(out.get(0).getMessage() instanceof UserMessage);
        assertEquals("a\n\nb\n\nc", out.get(0).getMessage().getText(), "文本按 \\n\\n 拼接、不丢内容");
    }

    @Test
    void collapsePreservesToolPairing() {
        // 连续 user 夹着一段完整的 tool 回合：user 两端各被折叠，tool 配对不动。
        List<SessionEvent> in = List.of(
                ev(new UserMessage("x")),
                ev(new UserMessage("y")),
                ev(asstCalls("t")),
                ev(toolResults("t")),
                ev(new UserMessage("p")),
                ev(new UserMessage("q")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(4, out.size());
        assertEquals("x\n\ny", out.get(0).getMessage().getText());
        assertTrue(out.get(1).getMessage() instanceof AssistantMessage am && am.hasToolCalls(), "tool_call assistant 保留");
        assertTrue(out.get(2).getMessage() instanceof ToolResponseMessage trm
                && trm.getResponses().get(0).id().equals("t"), "tool 结果 id 保留");
        assertEquals("p\n\nq", out.get(3).getMessage().getText());
        assertNoOrphans(out);
    }

    @Test
    void collapseAfterOrphanRemovalExposesConsecutiveUsers() {
        // 孤儿 TOOL 夹在两个 user 之间：先删孤儿，再暴露出的连续 user 才被折叠（验证两遍的先后次序）。
        List<SessionEvent> in = List.of(
                ev(new UserMessage("a")),
                ev(toolResults("orphan")),
                ev(new UserMessage("b")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertEquals(1, out.size(), "删孤儿后连续 user 应折叠为一条");
        assertEquals("a\n\nb", out.get(0).getMessage().getText());
    }

    @Test
    void collapseMergesPlainAssistantsButNeverToolCallAssistant() {
        // 直接测折叠：连续普通 assistant 合并；带 tool_calls 的 assistant 绝不参与合并。
        List<SessionEvent> plain = List.of(
                ev(new AssistantMessage("x")),
                ev(new AssistantMessage("y")));
        List<SessionEvent> mergedPlain = SessionEvents.collapseConsecutiveSameRole(plain);
        assertEquals(1, mergedPlain.size());
        assertEquals("x\n\ny", mergedPlain.get(0).getMessage().getText());

        List<SessionEvent> withCall = List.of(
                ev(asstCalls("t")),               // 带 tool_calls
                ev(new AssistantMessage("z")));   // 普通
        List<SessionEvent> notMerged = SessionEvents.collapseConsecutiveSameRole(withCall);
        assertSame(withCall, notMerged, "tool_call assistant 与普通 assistant 相邻也不合并（返回同一引用）");
    }

    @Test
    void alreadyAlternatingReturnedUnchanged() {
        List<SessionEvent> in = List.of(
                ev(new UserMessage("a")),
                ev(new AssistantMessage("b")),
                ev(new UserMessage("c")),
                ev(new AssistantMessage("d")));
        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertSame(in, out, "已严格交替：零改动应返回同一引用");
    }

    @Test
    void healsRealCorruptedShapeToValidAlternation() {
        // 复刻磁盘上真实坏会话形状：USER×5 → [A(tc),TOOL]×3 → A(终答) → USER → [A(tc),TOOL] → USER×4。
        List<SessionEvent> in = new ArrayList<>();
        for (int i = 0; i < 5; i++) in.add(ev(new UserMessage("u" + i)));
        in.add(ev(asstCalls("c1", "c2"))); in.add(ev(toolResults("c1", "c2")));
        in.add(ev(asstCalls("c3")));       in.add(ev(toolResults("c3")));
        in.add(ev(asstCalls("c4")));       in.add(ev(toolResults("c4")));
        in.add(ev(new AssistantMessage("终答")));
        in.add(ev(new UserMessage("mid")));
        in.add(ev(asstCalls("c5")));       in.add(ev(toolResults("c5")));
        for (int i = 0; i < 4; i++) in.add(ev(new UserMessage("t" + i)));

        List<SessionEvent> out = SessionEvents.sanitize(in);

        assertNoOrphans(out);
        // 无两个相邻的同为 USER 或同为「普通 ASSISTANT」的消息
        for (int i = 1; i < out.size(); i++) {
            Message prev = out.get(i - 1).getMessage(), cur = out.get(i).getMessage();
            boolean twoUsers = prev instanceof UserMessage && cur instanceof UserMessage;
            boolean twoPlainAsst = prev instanceof AssistantMessage pa && !pa.hasToolCalls()
                    && cur instanceof AssistantMessage ca && !ca.hasToolCalls();
            assertFalse(twoUsers || twoPlainAsst, "第 " + i + " 处仍有连续同角色");
        }
        assertTrue(out.get(out.size() - 1).getMessage() instanceof UserMessage, "尾部 user 折叠为一条");
        assertEquals("t0\n\nt1\n\nt2\n\nt3", out.get(out.size() - 1).getMessage().getText());
    }
}
