package io.github.javaside.springai.agent.demo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.ChatModelSummarizer;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README「延伸」小节两段代码的依据：带摘要的压缩、lastN 窗口。
 *
 * <p>这两条钉的是 Spring AI 自身的行为，本仓库没有对应的实现；断言是否有效靠变异确认
 * （改掉配置里的一个数字，断言必须变红）。
 */
class SessionMemoryExtrasTest {

    static final String SID = "extras";

    @Test
    void summaryCompactionKeepsSyntheticSummaryTurnInView() {
        ScriptedChatModel summarizer = new ScriptedChatModel(p -> ScriptedChatModel.text("【摘要】前两轮。"));
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(2), WindowCompactionStrategy.builder().lastTurns(1)
                        .summarizer(ChatModelSummarizer.builder(summarizer).build()).build())
                .build();
        ScriptedChatModel model = new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
        ChatClient client = ChatClient.builder(model).build();
        for (int i = 1; i <= 3; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }

        List<SessionEvent> log = sessions.getEvents(SID, EventFilter.all());
        assertEquals(9, log.size());
        assertEquals(EventType.COMPACTION, log.get(6).getType());
        assertTrue(log.get(7).isSynthetic() && log.get(8).isSynthetic());
        assertTrue(log.get(7).isTurnStart());
        assertEquals(MessageType.USER, log.get(7).getMessageType());
        assertEquals(MessageType.ASSISTANT, log.get(8).getMessageType());
        assertEquals("【摘要】前两轮。", log.get(8).getMessage().getText());
        assertEquals(List.of(log.get(7).getId(), log.get(8).getId(), log.get(4).getId(), log.get(5).getId()),
                ids(sessions.getEvents(SID, EventFilter.active())));   // 摘要回合在前，保留回合在后
        assertEquals(List.of(1, 3, 5), model.promptSizes());
        assertEquals(List.of(2), summarizer.promptSizes());
    }

    @Test
    void lastNWindowBoundsThePromptWhileTheLogKeepsEverything() {
        SessionService sessions = SessionDemoSupport.newSessions();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .memory(memory -> memory.eventFilter(EventFilter.active().toBuilder().lastN(4).build()))
                .build();
        ScriptedChatModel model = new ScriptedChatModel(p -> ScriptedChatModel.text("回声：" + ScriptedChatModel.lastUserText(p)));
        ChatClient client = ChatClient.builder(model).build();
        for (int i = 1; i <= 5; i++) {
            SessionDemoSupport.ask(client, advisors, SID, "第" + i + "句");
        }

        List<SessionEvent> log = sessions.getEvents(SID, EventFilter.all());
        assertEquals(10, log.size());
        assertTrue(log.stream().noneMatch(e -> e.getType().equals(EventType.COMPACTION)));
        assertEquals(List.of(1, 3, 5, 5, 5), model.promptSizes());
        List<String> last3 = ids(sessions.getEvents(SID, EventFilter.active().toBuilder().lastN(3).build()));
        assertEquals(ids(sessions.getEvents(SID, EventFilter.active().toBuilder().lastN(4).build())), last3);
        assertEquals(ids(log.subList(6, 10)), last3);   // 落在回合中间时向前扩到回合起点
    }

    static List<String> ids(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::getId).toList();
    }
}
