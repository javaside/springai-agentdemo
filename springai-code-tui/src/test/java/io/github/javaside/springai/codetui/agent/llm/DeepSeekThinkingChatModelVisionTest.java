package io.github.javaside.springai.codetui.agent.llm;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** 消费 key(0,0) 记录 entry：模型 call 的 finally 会清理注册表，断言必须在 delegate.call 内完成（否则取到 null）。 */
    static final class EntryCapturingDelegate implements ChatModel {
        private final DeepSeekVisionMediaRegistry registry;
        DeepSeekVisionMediaRegistry.Entry entry;

        EntryCapturingDelegate(DeepSeekVisionMediaRegistry registry) {
            this.registry = registry;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            entry = registry.take(DeepSeekVisionMediaRegistry.key(0, 0));
            return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("ok")))).build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
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
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry, null);

        model.call(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertTrue(d.sawImage, "delegate.call 执行时注册表应已含 key(0,0)（注册必须先于序列化）");
        assertTrue(registry.isEmpty(), "请求结束后注册表应被清理");
    }

    @Test
    void stream_registersMedia_andCleansOnTermination() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        StubDelegate d = new StubDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry, null);

        model.stream(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()))
                .blockLast();

        assertTrue(d.sawImage, "delegate.stream 执行时注册表应已含 key(0,0)");
        assertTrue(registry.isEmpty(), "流式终止后注册表应被清理（doFinally）");
    }

    @Test
    void textOnlyPrompt_registersNothing() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        StubDelegate d = new StubDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry, null);

        model.call(new Prompt("纯文本", DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertFalse(d.sawImage, "纯文本请求不得注册任何图片");
        assertTrue(registry.isEmpty());
    }

    @Test
    void filesUploaderSuccess_writesFileEntry() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        EntryCapturingDelegate d = new EntryCapturingDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(
                d, c -> d, registry, (bytes, filename) -> java.util.Optional.of("file-api-x"));

        model.call(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertNotNull(d.entry, "上传成功：delegate.call 时注册表应含 key(0,0)（FILES）");
        assertEquals(DeepSeekVisionMediaRegistry.Transport.FILES, d.entry.transport(), "上传成功必须注册 FILES entry");
        assertEquals("file-api-x", d.entry.fileId(), "file_id 必须来自上传器返回值");
        assertNull(d.entry.bytes(), "FILES entry 不携带 base64 字节");
        assertTrue(registry.isEmpty(), "请求结束后注册表应被清理");
    }

    @Test
    void filesUploaderFailure_degradesToInline() {
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        EntryCapturingDelegate d = new EntryCapturingDelegate(registry);
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(
                d, c -> d, registry, (bytes, filename) -> java.util.Optional.empty());

        model.call(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertNotNull(d.entry, "上传失败降级：delegate.call 时注册表应含 key(0,0)（INLINE）");
        assertEquals(DeepSeekVisionMediaRegistry.Transport.INLINE, d.entry.transport(), "上传失败必须降级 INLINE");
        assertNotNull(d.entry.bytes(), "INLINE entry 必须携带字节");
        assertTrue(d.entry.bytes().length > 0, "INLINE entry 字节非空");
        assertNull(d.entry.fileId(), "降级后不得残留 file_id");
        assertTrue(registry.isEmpty(), "请求结束后注册表应被清理");
    }
}
