package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionMaterializingChatModelTest {

    @TempDir Path root;

    /** 记录 delegate 实际收到的 Prompt——兑现有没有发生在出站那一刻，只能这么看。 */
    private static final class Spy implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        private final ChatOptions options;
        Spy(ChatOptions options) { this.options = options; }
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() { return options; }
    }

    private ChatOptions optionsFor(String model) {
        return ToolCallingChatOptions.builder().model(model).build();
    }

    private void png(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    /** 造一个能通过 FileReferenceParser 严格校验的引用块（照抄 VisionMaterializerTest 的写法）。 */
    private String ref(String name, String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: " + name + "\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    /**
     * ★ 漏转发 getOptions 会让 ChatClient 拿到裸 DefaultChatOptions → 不是
     * ToolCallingChatOptions → ToolCallingAdvisor 整个跳过 → 子 agent 静默丢掉全部工具。
     * 本项目在 RetryingChatModel 上栽过一模一样的坑。
     */
    @Test
    void forwardsOptionsSoToolCallingStaysEnabled() {
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        ChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        assertSame(spy.getOptions(), m.getOptions());
        assertInstanceOf(ToolCallingChatOptions.class, m.getOptions());
    }

    /** 纯文本模型：Prompt 必须原样透传（同一个对象），零行为变化。 */
    @Test
    void textOnlyModelPromptIsUntouched() {
        Spy spy = new Spy(optionsFor("deepseek-chat"));
        Prompt p = new Prompt(List.of(new UserMessage("hi")), optionsFor("deepseek-chat"));
        VisionMaterializingChatModel.wrap(spy, root).call(p);
        assertSame(p, spy.seen.get());
    }

    /** 模型 id 取自出站 Prompt 的 options，不是 delegate 的默认值——子 agent 换家也判得对。 */
    @Test
    void modelIdComesFromOutboundPromptNotFromDelegateDefaults() {
        Spy spy = new Spy(optionsFor("deepseek-chat"));            // delegate 默认是纯文本模型
        Prompt p = new Prompt(List.of(new UserMessage("hi")), optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel.wrap(spy, root).call(p);
        assertEquals("gpt-5.6-sol", spy.seen.get().getOptions().getModel());
    }

    /** call 与 stream 都必须走兑现——主 agent 走 stream，子 agent 走 call。 */
    @Test
    void bothCallAndStreamReachDelegate() {
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        ChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        Prompt p = new Prompt(List.of(new UserMessage("hi")), optionsFor("gpt-5.6-sol"));
        m.call(p);
        assertNotNull(spy.seen.get(), "call 没到达 delegate");
        spy.seen.set(null);
        m.stream(p).blockLast();
        assertNotNull(spy.seen.get(), "stream 没到达 delegate");
    }

    /**
     * 端到端：视觉模型 + 含合法引用块的用户消息 → delegate 收到的必须是<b>另一个</b> Prompt，
     * 且那条 user 消息真的挂上了 Media。
     *
     * <p>只测「透传」证明不了兑现接上了——透传在兑现器整个没接线时同样成立。这条走 {@code call}。
     */
    @Test
    void callMaterializesUserReferenceIntoRealMedia() throws Exception {
        png("docs/bug.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        Prompt p = new Prompt(
                List.of(new UserMessage("看这个\n" + ref("bug.png", "docs/bug.png"))),
                optionsFor("gpt-5.6-sol"));

        VisionMaterializingChatModel.wrap(spy, root).call(p);

        Prompt out = spy.seen.get();
        assertNotSame(p, out, "Prompt 没被兑现器改过——装饰器没接上兑现");
        assertTrue(hasMedia(out), "出站消息里没有任何 Media——引用没被兑现成真图");
    }

    /**
     * 同一条端到端断言走 {@code stream}——主 agent 走的是这条路，
     * 只在 {@code call} 上兑现等于主 agent 没有视觉。
     */
    @Test
    void streamMaterializesUserReferenceIntoRealMedia() throws Exception {
        png("docs/bug.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        Prompt p = new Prompt(
                List.of(new UserMessage("看这个\n" + ref("bug.png", "docs/bug.png"))),
                optionsFor("gpt-5.6-sol"));

        VisionMaterializingChatModel.wrap(spy, root).stream(p).blockLast();

        Prompt out = spy.seen.get();
        assertNotSame(p, out, "stream 路径没走兑现——主 agent 会看不见图");
        assertTrue(hasMedia(out), "stream 出站消息里没有任何 Media");
    }

    /** 兑现后 lastSnapshot 必须记上账（{@code /context} 的视觉占用一列全靠它）。 */
    @Test
    void snapshotRecordsDeliveredImages() throws Exception {
        png("docs/bug.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        assertEquals(0, m.lastSnapshot().images());

        m.call(new Prompt(List.of(new UserMessage("看这个\n" + ref("bug.png", "docs/bug.png"))),
                optionsFor("gpt-5.6-sol")));

        assertEquals(1, m.lastSnapshot().images());
        assertTrue(m.lastSnapshot().tokens() > 0, "兑现了图却没记 token");
    }

    /** provider 专属能力判定优先于全局模型名单，防止兼容网关的同名模型串用能力。 */
    @Test
    void providerCapabilityCanRejectGloballyKnownVisionModel() throws Exception {
        png("docs/bug.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        Prompt p = new Prompt(
                List.of(new UserMessage("看这个\n" + ref("bug.png", "docs/bug.png"))),
                optionsFor("gpt-5.6-sol"));

        VisionMaterializingChatModel.wrap(spy, root, ignored -> false).call(p);

        assertSame(p, spy.seen.get(), "provider 已判为纯文本，却仍按全局模型名单兑现了图片");
        assertFalse(hasMedia(spy.seen.get()));
    }

    /** 纯文本模型下引用块一个字都不该动——图不能兑现给一个收不了图的模型。 */
    @Test
    void textOnlyModelDoesNotMaterializeEvenWithValidReference() throws Exception {
        png("docs/bug.png");
        Spy spy = new Spy(optionsFor("deepseek-chat"));
        Prompt p = new Prompt(
                List.of(new UserMessage("看这个\n" + ref("bug.png", "docs/bug.png"))),
                optionsFor("deepseek-chat"));

        VisionMaterializingChatModel.wrap(spy, root).call(p);

        assertSame(p, spy.seen.get());
        assertFalse(hasMedia(spy.seen.get()));
    }

    private boolean hasMedia(Prompt prompt) {
        for (Message m : prompt.getInstructions()) {
            if (m instanceof MediaContent && !((MediaContent) m).getMedia().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
