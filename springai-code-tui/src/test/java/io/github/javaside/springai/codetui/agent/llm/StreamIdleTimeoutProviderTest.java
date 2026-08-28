package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamIdleTimeoutProviderTest {

    private static final ChatModel MODEL = new ChatModel() {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    };
    private static final ChatOptions OPTIONS = OpenAiChatOptions.builder().model("model").build();
    private static final List<ModelOption> MODELS = List.of(new ModelOption("model", "Model", "test"));
    private static final ThinkingCapabilities THINKING = ThinkingCapabilities.unsupported();
    private static final ModelCapabilities CAPABILITIES = ModelCapabilities.TEXT_ONLY;
    private static final LlmProvider INNER = new LlmProvider() {
        @Override public String id() { return "test"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return MODEL; }
        @Override public ChatOptions options(String modelId) { return OPTIONS; }
        @Override public ThinkingCapabilities thinkingCapabilities(String modelId) { return THINKING; }
        @Override public ChatOptions options(String modelId, ThinkingConfig config) { return OPTIONS; }
        @Override public List<ModelOption> models() { return MODELS; }
        @Override public String defaultModel() { return "model"; }
        @Override public ModelCapabilities capabilities(String modelId) { return CAPABILITIES; }
    };

    @Test
    void chatModelIsTimeoutWrappedOnceAndCached() {
        var provider = new StreamIdleTimeoutProvider(INNER, Duration.ofMillis(50));

        ChatModel first = provider.chatModel();
        ChatModel second = provider.chatModel();

        assertInstanceOf(StreamIdleTimeoutChatModel.class, first);
        assertSame(first, second);
    }

    @Test
    void providerMetadataAndOptionsDelegateByIdentity() {
        var provider = new StreamIdleTimeoutProvider(INNER, Duration.ofMillis(50));
        ThinkingConfig config = ThinkingConfig.defaults();

        assertEquals(INNER.id(), provider.id());
        assertTrue(provider.available());
        assertSame(OPTIONS, provider.options("model"));
        assertSame(THINKING, provider.thinkingCapabilities("model"));
        assertSame(OPTIONS, provider.options("model", config));
        assertSame(MODELS, provider.models());
        assertEquals("model", provider.defaultModel());
        assertSame(CAPABILITIES, provider.capabilities("model"));
    }
}
