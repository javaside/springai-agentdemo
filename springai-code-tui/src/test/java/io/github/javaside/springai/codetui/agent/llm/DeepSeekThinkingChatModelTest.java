package io.github.javaside.springai.codetui.agent.llm;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * 回归：ChatClient 合并链（{@code getOptions().mutate() → combineWith(每回合 options) → build()}）
     * 必须保留思考配置、并把最终 Prompt 的 options 还原成<b>原生</b> DeepSeekChatOptions。
     * 否则 DeepSeekChatModel.createRequest 里 {@code (DeepSeekChatOptions) prompt.getOptions()} 会
     * ClassCastException（裸 DefaultChatOptions），且思考配置/模型都会被静默丢掉。
     */
    @Test
    void chatClientMergePreservesThinkingConfigAndNativeOptions() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        AtomicInteger thinkingCreated = new AtomicInteger();
        DeepSeekThinkingChatModel router = new DeepSeekThinkingChatModel(
                capturingModel(captured), config -> {
                    thinkingCreated.incrementAndGet();
                    return capturingModel(captured);
                });
        ChatClient client = ChatClient.builder(router).build();
        ChatOptions thinking = new DeepSeekThinkingChatOptions(
                org.springframework.ai.deepseek.DeepSeekChatOptions.builder().model("deepseek-v4-flash").build(),
                ThinkingConfig.enabledEffort("low"));
        client.prompt().user("hi").options(thinking.mutate()).call().chatResponse();
        assertTrue(captured.get() instanceof org.springframework.ai.deepseek.DeepSeekChatOptions,
                "合并链应产出 native DeepSeekChatOptions，实为 " + captured.get().getClass().getName());
        assertEquals("deepseek-v4-flash", captured.get().getModel());
        assertEquals(1, thinkingCreated.get(), "思考配置应路由到 thinking delegate");
    }

    private static Prompt prompt(ChatOptions options) {
        return new Prompt(List.of(new UserMessage("hello")), options);
    }

    private static ChatModel model() {
        return capturingModel(new AtomicReference<>());
    }

    private static ChatModel capturingModel(AtomicReference<ChatOptions> captured) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                captured.set(prompt.getOptions());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                captured.set(prompt.getOptions());
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            }
            @Override public ChatOptions getOptions() {
                return org.springframework.ai.deepseek.DeepSeekChatOptions.builder().model("deepseek-v4-pro").build();
            }
        };
    }
}
