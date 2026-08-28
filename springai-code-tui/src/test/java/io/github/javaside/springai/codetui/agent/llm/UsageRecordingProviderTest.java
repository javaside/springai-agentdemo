package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageRecordingProviderTest {

    private static final LlmProvider INNER = new LlmProvider() {
        @Override public String id() { return "test"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
        }; }
        @Override public ChatOptions options(String modelId) { return null; }
        @Override public List<ModelOption> models() { return List.of(); }
        @Override public String defaultModel() { return "m"; }
    };

    @Test
    void chatModel_isWrappedOnceAndCached() {
        var acc = new TokenUsageAccumulator();
        var provider = new UsageRecordingProvider(INNER, acc);

        ChatModel first = provider.chatModel();
        ChatModel second = provider.chatModel();

        assertTrue(first instanceof UsageRecordingChatModel, "chatModel() 被包上 UsageRecordingChatModel");
        assertSame(first, second, "chatModel() 幂等：同一次包装缓存复用");
    }

    @Test
    void everythingElse_delegates() {
        var acc = new TokenUsageAccumulator();
        var provider = new UsageRecordingProvider(INNER, acc);
        assertSame(INNER.id(), provider.id());
        assertSame(INNER.available(), provider.available());
        assertSame(INNER.defaultModel(), provider.defaultModel());
    }
}
