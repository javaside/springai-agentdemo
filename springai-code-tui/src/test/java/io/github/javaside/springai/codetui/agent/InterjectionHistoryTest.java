package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class InterjectionHistoryTest {

    /**
     * 造一条「anchor 不在末尾」的历史：末尾还有 assistant 收尾，故「插在 anchor 后」≠「追加末尾」。
     *
     * <p><b>这个形状是刻意的</b>：若 anchor 那条 tool 恰好在末尾，成功路径与兜底路径产出的历史
     * <b>逐字相同</b>——故意破坏 anchor 查找的变异根本杀不掉，三个测试会一起假绿。
     */
    private static List<SessionEvent> history(String sid) {
        List<SessionEvent> out = new ArrayList<>();
        out.add(ev(sid, new UserMessage("原始提问")));
        out.add(ev(sid, AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "someTool", "{}"))).build()));
        out.add(ev(sid, ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "someTool", "工具结果"))).build()));
        out.add(ev(sid, new AssistantMessage("收工")));
        return out;
    }

    private static SessionEvent ev(String sid, Message m) {
        return SessionEvent.builder().sessionId(sid).message(m).build();
    }

    @Test
    @DisplayName("插话插在 anchor 那条 tool 之后，不是追加末尾")
    void insertsAfterAnchorNotAtTail() {
        List<SessionEvent> events = history("s1");

        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                events, "s1", "call-1", "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(ToolResponseMessage.class, out.get(2).getMessage());
        assertInstanceOf(UserMessage.class, out.get(3).getMessage(), "插话应在 tool 之后");
        assertInstanceOf(AssistantMessage.class, out.get(4).getMessage(), "收尾 assistant 仍在最后");
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"),
                out.get(3).getMessage().getText(), "落库文本须与模型看到的逐字一致");
    }

    @Test
    @DisplayName("anchor 为 null 时追加到末尾")
    void nullAnchorAppendsToTail() {
        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                history("s1"), "s1", null, "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(UserMessage.class, out.get(4).getMessage());
    }

    @Test
    @DisplayName("anchor 找不到时追加到末尾（被 sanitize 裁掉的情形）")
    void unknownAnchorAppendsToTail() {
        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                history("s1"), "s1", "call-已被裁掉", "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(UserMessage.class, out.get(4).getMessage());
    }
}
