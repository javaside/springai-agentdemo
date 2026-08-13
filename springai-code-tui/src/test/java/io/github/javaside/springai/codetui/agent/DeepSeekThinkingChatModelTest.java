package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekThinkingChatModelTest {

    @Test
    void routesConfiguredOptionsAndReusesDelegate() {
        AtomicInteger created = new AtomicInteger();
        ChatModel defaultDelegate = model();
        DeepSeekThinkingChatModel router = new DeepSeekThinkingChatModel(defaultDelegate, config -> {
            created.incrementAndGet();
            return model();
        });
        Prompt prompt = prompt(new DeepSeekThinkingChatOptions(
                org.springframework.ai.deepseek.DeepSeekChatOptions.builder().model("deepseek-v4-pro").build(),
                ThinkingConfig.enabledEffort("max")));
        router.call(prompt);
        router.stream(prompt).blockLast();
        assertEquals(1, created.get());
        assertEquals(1, router.delegateCount());
    }

    @Test
    void plainNativeOptionsRemainDefaultDelegate() {
        AtomicInteger created = new AtomicInteger();
        DeepSeekThinkingChatModel router = new DeepSeekThinkingChatModel(model(), config -> {
            created.incrementAndGet();
            return model();
        });
        router.call(prompt(org.springframework.ai.deepseek.DeepSeekChatOptions.builder().build()));
        assertEquals(0, created.get());
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("hello")), options);
    }

    private static ChatModel model() {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }
}
