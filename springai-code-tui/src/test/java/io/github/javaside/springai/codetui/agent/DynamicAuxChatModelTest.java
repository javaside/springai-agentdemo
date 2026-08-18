package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DynamicAuxChatModel} 必须在<b>每次调用时</b>解析当前激活 provider——而非在装配期一次性绑定，
 * 从而让复用它的两处内部 LLM 调用（SmartWebFetch 抽取、会话滚动摘要）跟随 {@code /model} 切换。
 */
class DynamicAuxChatModelTest {

    /** 记录收到的模型 id 与 maxTokens、并返回一个可辨识的标签文本，用来断言「调到了哪家、带什么输出上限」。 */
    private static final class RecordingChatModel implements ChatModel {
        private final String tag;
        volatile String lastModel;
        volatile Integer lastMaxTokens;

        RecordingChatModel(String tag) {
            this.tag = tag;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastModel = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
            this.lastMaxTokens = prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(tag))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    /** 最小可用 provider：一个可辨识的 ChatModel + 一个模型 id + 可选的每请求基础 maxTokens。 */
    private static final class FakeProvider implements LlmProvider {
        private final String id;
        private final String modelId;
        private final RecordingChatModel model;
        private final Integer baseMaxTokens;

        FakeProvider(String id, String modelId, RecordingChatModel model) {
            this(id, modelId, model, null);
        }

        FakeProvider(String id, String modelId, RecordingChatModel model, Integer baseMaxTokens) {
            this.id = id;
            this.modelId = modelId;
            this.model = model;
            this.baseMaxTokens = baseMaxTokens;
        }

        @Override public String id() { return id; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return model; }
        @Override public ChatOptions options(String modelId) {
            ChatOptions.Builder b = ChatOptions.builder().model(modelId);
            if (baseMaxTokens != null) {
                b.maxTokens(baseMaxTokens);
            }
            return b.build();
        }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "")); }
        @Override public String defaultModel() { return modelId; }
    }

    @Test
    void callDelegatesToActiveProviderAndFollowsSwitch() {
        RecordingChatModel a = new RecordingChatModel("A");
        RecordingChatModel b = new RecordingChatModel("B");
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new FakeProvider("pa", "model-a", a),
                new FakeProvider("pb", "model-b", b)));
        DynamicAuxChatModel aux = new DynamicAuxChatModel(reg);

        // 初始激活 = 第一个 provider（pa）
        assertEquals("A", aux.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals("model-a", a.lastModel, "应把激活模型 id 注入 prompt options");

        // /model 切到第二家后，aux 调用必须跟着切
        reg.select("model-b");
        assertEquals("B", aux.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals("model-b", b.lastModel, "切换后应打到新家、并注入新模型 id");
    }

    @Test
    void auxAlwaysUsesDefaultConfig() {
        RecordingChatModel a = new RecordingChatModel("A");
        FakeProvider provider = new FakeProvider("pa", "model-a", a);
        io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore store =
                io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore.inMemory();
        store.put("pa", "model-a", io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig.enabledEffort("high"));
        ProviderRegistry reg = new ProviderRegistry(List.of(provider), store);
        DynamicAuxChatModel aux = new DynamicAuxChatModel(reg);
        aux.call(new Prompt("hi"));
        // Auxiliary path must use the compatibility options(String) entry, which carries DEFAULT.
        assertEquals("model-a", a.lastModel);
    }

    @Test
    void streamAlsoFollowsActiveProvider() {
        RecordingChatModel a = new RecordingChatModel("A");
        RecordingChatModel b = new RecordingChatModel("B");
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new FakeProvider("pa", "model-a", a),
                new FakeProvider("pb", "model-b", b)));
        DynamicAuxChatModel aux = new DynamicAuxChatModel(reg);

        reg.select("model-b");
        ChatResponse resp = aux.stream(new Prompt("hi")).blockLast();
        assertEquals("B", resp.getResult().getOutput().getText());
        assertEquals("model-b", b.lastModel);
    }

    /**
     * prompt 自带 maxTokens（摘要路径的 8192 输出上限）必须<b>覆盖</b> provider 基础 options 的同名值，
     * 而不是被整体替换丢掉——否则压缩摘要一旦切到带基础 maxTokens 的家（如 Anthropic 的必填项），
     * 输出上限就静默漂移。
     */
    @Test
    void promptMaxTokensOverridesProviderBase() {
        RecordingChatModel a = new RecordingChatModel("A");
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new FakeProvider("pa", "model-a", a, 4096)));
        DynamicAuxChatModel aux = new DynamicAuxChatModel(reg);

        aux.call(new Prompt("hi", ChatOptions.builder().maxTokens(8192).build()));

        assertEquals("model-a", a.lastModel, "模型 id 仍须注入");
        assertEquals(8192, a.lastMaxTokens, "prompt 的 maxTokens 应覆盖 provider 基础值");
    }

    /** 对照：prompt 不带 maxTokens 时，provider 基础 options（如 Anthropic 必填项）必须原样保留。 */
    @Test
    void promptWithoutMaxTokensKeepsProviderBase() {
        RecordingChatModel a = new RecordingChatModel("A");
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new FakeProvider("pa", "model-a", a, 4096)));
        DynamicAuxChatModel aux = new DynamicAuxChatModel(reg);

        aux.call(new Prompt("hi"));

        assertEquals("model-a", a.lastModel);
        assertEquals(4096, a.lastMaxTokens, "无 prompt 覆盖时应保留 provider 基础 maxTokens");
    }
}
