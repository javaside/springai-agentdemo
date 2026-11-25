package io.github.javaside.springai.agent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMemoryDemoTest {

    @Test
    void twoTurnsAppendFourEventsAndSecondPromptCarriesTheHistory() {
        ScriptedChatModel model = new ScriptedChatModel(
                p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));

        SessionMemoryDemo.TwoTurnRun run = SessionMemoryDemo.runTwoTurns(
                ChatClient.builder(model).build(), SessionDemoSupport.newSessions(), "user-1");

        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT),
                run.historyBeforeSecondTurn().stream().map(SessionEvent::getMessageType).toList());
        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT),
                run.log().stream().map(SessionEvent::getMessageType).toList());
        assertTrue(run.log().stream().allMatch(e -> e.getType().equals(EventType.MESSAGE)));
        assertEquals(List.of(1, 3), model.promptSizes());
    }

    @Test
    void describeEventShowsIndexRoleIdTimeAndText() {
        SessionEvent user = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:33.120Z"))
                .message(UserMessage.builder().text("我最喜欢的编程语言是 Java，请记住。").build()).build();
        SessionEvent boundary = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:40.000Z")).type(EventType.COMPACTION).payload(Map.of()).build();

        assertEquals("#1  user       3f2a9c41  10:12:33.120  我最喜欢的编程语言是 Java，请记住。",
                SessionMemoryDemo.describeEvent(1, user, ZoneId.of("Asia/Shanghai")));
        assertEquals("#9  COMPACTION  3f2a9c41  10:12:40.000",
                SessionMemoryDemo.describeEvent(9, boundary, ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void describeEventKeepsOneEventPerLine() {
        // 真机上模型的回答动辄几段、带空行；日志里一条事件必须仍然占一行
        SessionEvent multiLine = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:33.120Z"))
                .message(AssistantMessage.builder().content("好的，已记住。\n\n\n我会在本次对话中记得。").build()).build();
        SessionEvent longText = SessionEvent.builder().id("3f2a9c41").sessionId("user-1")
                .timestamp(Instant.parse("2026-11-25T02:12:33.120Z"))
                .message(UserMessage.builder().text("x".repeat(80)).build()).build();

        assertEquals("#1  assistant  3f2a9c41  10:12:33.120  好的，已记住。 我会在本次对话中记得。",
                SessionMemoryDemo.describeEvent(1, multiLine, ZoneId.of("Asia/Shanghai")));
        assertEquals("#2  user       3f2a9c41  10:12:33.120  " + "x".repeat(60) + "…",
                SessionMemoryDemo.describeEvent(2, longText, ZoneId.of("Asia/Shanghai")));
    }
}
