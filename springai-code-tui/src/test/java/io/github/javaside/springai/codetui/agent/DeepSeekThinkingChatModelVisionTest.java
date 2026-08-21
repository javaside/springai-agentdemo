package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekThinkingChatModelVisionTest {

    /** 桩 delegate：构造注入与 ChatModel 同一个 registry；call/stream 时<b>检查但不消费</b>（`!registry.isEmpty()`），记录是否命中。注册表非空只有模型注册能达到；清理只能由模型的 finally/doFinally 完成。 */
    static final class StubDelegate implements ChatModel {
        private final DeepSeekVisionMediaRegistry registry;
        boolean sawImage;

        StubDelegate(DeepSeekVisionMediaRegistry registry) {
            this.registry = registry;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            sawImage = !registry.isEmpty();
            return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("ok")))).build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            sawImage = !registry.isEmpty();
            return Flux.empty();
        }

        @Override
        public ChatOptions getOptions() { return null; }

        @Override
        public ChatOptions getDefaultOptions() { return null; }
    }

    private static UserMessage imageMessage() {
        return UserMessage.builder()
                .text("看这张图")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(new byte[]{1, 2, 3}).build()))
                .build();
    }

    @Test
    void call_registersMedia_beforeDelegate_andCleansAfter() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        StubDelegate d = new StubDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.call(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertTrue(d.sawImage, "delegate.call 执行时注册表应已含 key(0,0)（注册必须先于序列化）");
        assertTrue(registry.isEmpty(), "请求结束后注册表应被清理");
    }

    @Test
    void stream_registersMedia_andCleansOnTermination() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        StubDelegate d = new StubDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.stream(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()))
                .blockLast();

        assertTrue(d.sawImage, "delegate.stream 执行时注册表应已含 key(0,0)");
        assertTrue(registry.isEmpty(), "流式终止后注册表应被清理（doFinally）");
    }

    @Test
    void textOnlyPrompt_registersNothing() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        StubDelegate d = new StubDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.call(new Prompt("纯文本", DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertFalse(d.sawImage, "纯文本请求不得注册任何图片");
        assertTrue(registry.isEmpty());
    }
}
