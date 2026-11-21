package io.github.javaside.springai.codetui.agent.compaction;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreflightCompactionAdvisorTest {

    @Test
    void compactsPersistedHistoryBeforeModelReceivesNextRequest() {
        SessionService sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build();
        sessions.create(CreateSessionRequest.builder().id("s").build());
        for (int i = 0; i < 8; i++) {
            sessions.appendEvent(SessionEvent.builder().sessionId("s")
                    .message(new UserMessage("x".repeat(100))).build());
        }
        ArrayList<Integer> modelPromptSizes = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return answer(prompt); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(answer(prompt)); }
            private ChatResponse answer(Prompt prompt) {
                modelPromptSizes.add(prompt.getInstructions().stream()
                        .mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
        };
        CompactionStrategy deterministic = request -> {
            // 官方 2.1.0 六参：旧三参 (compacted, archived, tokens) → covered←archived、preserved←保留尾部
            // （旧 compacted 除合成摘要外的部分）、summary←合成摘要消息、tokens 透传。
            List<SessionEvent> archived = List.copyOf(request.events().subList(0, 6));
            List<SessionEvent> kept = List.copyOf(request.events().subList(6, request.events().size()));
            return new CompactionResult(archived, List.of(), kept,
                    List.of(new AssistantMessage("summary")), 500, "test");
        };
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(
                        new PreflightCompactionAdvisor(sessions, 400, deterministic),
                        SessionMemoryAdvisor.builder(sessions).build())   // 官方 2.1.0 Builder 无 defaultUserId
                .build();

        client.prompt().user("new").advisors(a -> a.param(
                SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "s"))
                .stream().content().collectList().block();

        // 官方 2.1.0 append-only：被压缩覆盖的事件仍在日志里（全量 11 条），活跃视图才是发给模型的历史。
        assertEquals(5, sessions.getEvents("s", org.springframework.ai.session.EventFilter.active()).size(),
                "模型调用前活跃视图应为 summary + 最近两条，再追加本轮 user/assistant");
        assertTrue(modelPromptSizes.get(0) < 400,
                "模型第一次收到的就必须是压缩后历史，而不是先发送超限历史再 after 压缩");
    }
}
