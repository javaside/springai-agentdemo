package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodingAgentThinkingTest {

    @Test
    void submitUsesActiveSelectionSnapshot() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt.getOptions());
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
            }
            @Override public ChatOptions getOptions() {
                return OpenAiChatOptions.builder().model("gpt-5.6-sol").build();
            }
        };
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-5.6-sol", ThinkingConfig.enabledEffort("high"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new OpenAiProvider("k")), store);
        ChatClient client = ChatClient.builder(model).build();
        CodingAgent agent = new CodingAgent(registry, Map.of("openai", client), new StubListener(),
                "s", new AtomicLong(), null, null, null, List.of(), null, null, null);
        agent.submit("hi");
        assertEquals("high", ((OpenAiChatOptions) captured.get()).getReasoningEffort());
    }
}
