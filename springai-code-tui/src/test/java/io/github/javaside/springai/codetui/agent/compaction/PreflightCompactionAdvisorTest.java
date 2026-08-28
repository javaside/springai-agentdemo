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
        sessions.create(CreateSessionRequest.builder().id("s").userId("u").build());
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
            List<SessionEvent> archived = List.copyOf(request.events().subList(0, 6));
            List<SessionEvent> compacted = new ArrayList<>();
            compacted.add(SessionEvent.builder().sessionId("s")
                    .message(new AssistantMessage("summary")).build());
            compacted.addAll(request.events().subList(6, request.events().size()));
            return new CompactionResult(compacted, archived, 500);
        };
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(
                        new PreflightCompactionAdvisor(sessions, 400, deterministic),
                        SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build())
                .build();

        client.prompt().user("new").advisors(a -> a.param(
                SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "s"))
                .stream().content().collectList().block();

        assertEquals(5, sessions.getMessages("s").size(),
                "模型调用前应先替换为 summary + 最近两条，再追加本轮 user/assistant");
        assertTrue(modelPromptSizes.get(0) < 400,
                "模型第一次收到的就必须是压缩后历史，而不是先发送超限历史再 after 压缩");
    }
}
