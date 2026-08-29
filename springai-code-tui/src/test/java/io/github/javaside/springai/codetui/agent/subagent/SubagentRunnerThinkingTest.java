package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubagentRunnerThinkingTest {

    private static final class CapturingProvider implements LlmProvider {
        private final AtomicReference<ChatOptions> captured;
        CapturingProvider(AtomicReference<ChatOptions> captured) { this.captured = captured; }
        @Override public String id() { return "openai"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    captured.set(prompt.getOptions());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
                @Override public ChatOptions getOptions() {
                    return OpenAiChatOptions.builder().model("gpt-5.6-sol").build();
                }
            };
        }
        @Override public ChatOptions options(String modelId) {
            return OpenAiChatOptions.builder().model(modelId).build();
        }
        @Override public ChatOptions options(String modelId, ThinkingConfig config) {
            thinkingCapabilities(modelId).validate(config);
            if (config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.DEFAULT) {
                return options(modelId);
            }
            String effort = config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.DISABLED
                    ? "none" : config.effort();
            return OpenAiChatOptions.builder().model(modelId).reasoningEffort(effort).build();
        }
        @Override public io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities thinkingCapabilities(String modelId) {
            return io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities.effort(true, List.of("low", "high"));
        }
        @Override public List<ModelOption> models() {
            return List.of(new ModelOption("gpt-5.6-sol", "Sol", "d"),
                    new ModelOption("gpt-5.6-terra", "Terra", "d"));
        }
        @Override public String defaultModel() { return "gpt-5.6-sol"; }
    }

    private static SubagentSpec spec(String model) {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), model, List.of());
    }

    @Test
    void defaultModelUsesActiveConfig() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-5.6-sol", ThinkingConfig.enabledEffort("high"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)), store);
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");
        runner.run(spec(null), "hi", "desc", 1L);
        assertEquals("high", ((OpenAiChatOptions) captured.get()).getReasoningEffort());
    }

    @Test
    void explicitModelUsesItsConfig() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt-5.6-terra", ThinkingConfig.enabledEffort("low"));
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)), store);
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");
        runner.run(spec("gpt-5.6-terra"), "hi", "desc", 1L);
        assertEquals("low", ((OpenAiChatOptions) captured.get()).getReasoningEffort());
    }
}
