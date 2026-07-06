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

    /** 记录收到的模型 id、并返回一个可辨识的标签文本，用来断言「调到了哪家」。 */
    private static final class RecordingChatModel implements ChatModel {
        private final String tag;
        volatile String lastModel;

        RecordingChatModel(String tag) {
            this.tag = tag;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastModel = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(tag))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    /** 最小可用 provider：一个可辨识的 ChatModel + 一个模型 id。 */
    private static final class FakeProvider implements LlmProvider {
        private final String id;
        private final String modelId;
        private final RecordingChatModel model;

        FakeProvider(String id, String modelId, RecordingChatModel model) {
            this.id = id;
            this.modelId = modelId;
            this.model = model;
        }

        @Override public String id() { return id; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return model; }
        @Override public ChatOptions options(String modelId) { return ChatOptions.builder().model(modelId).build(); }
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
}
