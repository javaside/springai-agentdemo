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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** 只关心「谁真正接到了请求」，不涉及思考配置——用于证明覆盖值真的跨 provider 路由，而不是丢前缀落到激活 provider。 */
    private static final class IdOnlyProvider implements LlmProvider {
        private final String providerId;
        private final String modelId;
        private final AtomicBoolean called;
        IdOnlyProvider(String providerId, String modelId, AtomicBoolean called) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.called = called;
        }
        @Override public String id() { return providerId; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    called.set(true);
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
                @Override public ChatOptions getOptions() { return OpenAiChatOptions.builder().model(modelId).build(); }
            };
        }
        @Override public ChatOptions options(String mid) { return OpenAiChatOptions.builder().model(mid).build(); }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
        @Override public String defaultModel() { return modelId; }
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

    @Test
    void crossProviderModelOverrideRoutesToNamedProvider() {
        AtomicBoolean primaryCalled = new AtomicBoolean();
        AtomicBoolean secondaryCalled = new AtomicBoolean();
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new IdOnlyProvider("openai", "shared-model", primaryCalled),
                new IdOnlyProvider("deepseek", "shared-model", secondaryCalled)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");

        runner.run(spec("deepseek:shared-model"), "hi", "desc", 1L);

        assertTrue(secondaryCalled.get(), "跨 provider 覆盖必须真正按 provider 精确路由到 deepseek，"
                + "而不是丢前缀后按裸 modelId 无范围搜索、落到列表里排前面的 openai（两家都持有同名 shared-model）");
        assertFalse(primaryCalled.get());
    }

    @Test
    void requestedModelLabelUsesActiveWhenSpecModelBlank() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        AtomicReference<String> label = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        var listener = new StubListener() {
            @Override
            public void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                          String modelLabel) {
                label.set(modelLabel);
            }
        };
        SubagentRunner runner = new SubagentRunner(registry, List.of(), listener, "");

        runner.run(spec(null), "hi", "desc", 1L);

        assertEquals("openai:gpt-5.6-sol", label.get());
    }

    @Test
    void requestedModelLabelShowsRawOverrideBeforeResolution() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        AtomicReference<String> label = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        var listener = new StubListener() {
            @Override
            public void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                          String modelLabel) {
                label.set(modelLabel);
            }
        };
        SubagentRunner runner = new SubagentRunner(registry, List.of(), listener, "");

        runner.run(spec("gpt-5.6-terra"), "hi", "desc", 1L);

        assertEquals("gpt-5.6-terra", label.get());
    }

    /**
     * 回归本任务要修的具体 bug：{@code gpt-5.6-sol} 是真实模型，但挂在不存在的 provider 前缀下。
     * 旧实现会丢弃 "no-such-provider:" 前缀、按裸 modelId 命中唯一配置的 openai，静默"成功"——
     * 这正是设计文档背景里说的那个缺陷。修复后必须真按 providerId 校验，找不到就清楚报错。
     */
    @Test
    void unknownProviderInOverrideSurfacesClearFailureMessage() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runner.run(spec("no-such-provider:gpt-5.6-sol"), "hi", "desc", 1L));

        assertTrue(ex.getMessage().contains("未知或不可用模型"), ex.getMessage());
    }
}
