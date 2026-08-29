package io.github.javaside.springai.codetui;
import io.github.javaside.springai.codetui.agent.llm.UsageRecordingProvider;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiApplicationThinkingConfigTest {

    @Test
    void registryLoadsPersistedThinkingSettings(@TempDir Path root) throws Exception {
        Path file = ThinkingConfigStore.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"version":1,"providers":{"openai":{"gpt-5.6-sol":{"mode":"ENABLED","effort":"high"}}}}
                """);
        ProviderRegistry registry = CodeTuiApplication.createProviderRegistry(root,
                Map.of("OPENAI_API_KEY", "k"));
        assertEquals("high", registry.thinkingSettings("openai", "gpt-5.6-sol").config().effort());
    }

    @Test
    void startupWrapperTimesOutHungStreamsAndRecordsSeenUsage() {
        var usage = new DefaultUsage(100, 20, 120, null, 80L, 0L);
        var metadata = ChatResponseMetadata.builder().usage(usage).build();
        var first = new ChatResponse(
                List.of(new Generation(new AssistantMessage("first"))), metadata);
        ChatModel hanging = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return first; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.concat(Flux.just(first), Flux.never());
            }
        };
        LlmProvider provider = new TestProvider(hanging);
        TokenUsageAccumulator accumulator = new TokenUsageAccumulator();
        LlmProvider wrapped = CodeTuiApplication.wrap(
                provider, accumulator, Duration.ofMillis(100));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> wrapped.chatModel().stream(new Prompt("hi")).blockLast());

        assertTrue(error.getMessage().contains("等待模型流数据超时"));
        assertEquals(100L, accumulator.snapshot().promptTokens(),
                "UsageRecordingProvider 必须在最外层，超时前看到的 usage 仍应提交");
    }

    private record TestProvider(ChatModel chatModel) implements LlmProvider {
        @Override public String id() { return "test"; }
        @Override public boolean available() { return true; }
        @Override public ChatOptions options(String modelId) { return null; }
        @Override public List<ModelOption> models() {
            return List.of(new ModelOption("model", "Model", "test"));
        }
        @Override public String defaultModel() { return "model"; }
    }
}
