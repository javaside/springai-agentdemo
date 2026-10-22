package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.openai.errors.BadRequestException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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

    // ── 图片被服务端 400 拒收后的降级重试 ───────────────────────

    /** 可编程失败序列的 delegate：第 i 次调用抛 failures[i]，之后成功。 */
    private static final class Scripted implements ChatModel {
        final List<Prompt> seen = new java.util.ArrayList<>();
        private final List<Throwable> failures;
        private final ChatOptions options;

        Scripted(ChatOptions options, Throwable... failures) {
            this.options = options;
            this.failures = List.of(failures);
        }

        private Prompt record(Prompt p) {
            seen.add(p);
            int i = seen.size() - 1;
            if (i < failures.size()) {
                throw (RuntimeException) failures.get(i);
            }
            return p;
        }

        @Override public ChatResponse call(Prompt p) {
            record(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override public Flux<ChatResponse> stream(Prompt p) {
            // 真实组件（OpenAiChatModel.stream）装配期不抛、订阅期才抛——必须 defer 模拟，
            // 否则异常在 onErrorResume 挂上之前同步穿出，测不到降级路径。
            return Flux.defer(() -> {
                record(p);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            });
        }

        @Override public ChatOptions getOptions() { return options; }
    }

    /** 智谱实录文案。BadRequestException 本身即类型化的 400（statusCode 由类型承载）。 */
    private static BadRequestException imageRejected400() {
        return BadRequestException.builder()
                .headers(com.openai.core.http.Headers.builder().build())
                .error(errorObject("1113", "图片输入格式/解析错误"))
                .build();
    }

    /** SDK 的 ErrorObject 四字段全必填（code/param/type/message），逐一补齐。 */
    private static com.openai.models.ErrorObject errorObject(String code, String message) {
        return com.openai.models.ErrorObject.builder()
                .code(code)
                .param(java.util.Optional.<String>empty())
                .type("invalid_request_error")
                .message(message)
                .build();
    }

    /** 工具图路径的当轮 prompt：引用在工具结果里，兑现后落在合成 user 消息。 */
    private ToolResponseMessage toolResultRef() {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read",
                        "[file reference]\nid: sha256:abc\nkind: image\nmime_type: image/png\n"
                                + "size_bytes: 10\nname: bug.png\npath: docs/bug.png\n"
                                + "delivery: not_in_view\nreason: x\n[/file reference]")))
                .build();
    }

    /**
     * ★ 降级主路径（stream，主 agent 走这条路）：图片被 400 拒 → 剥图重试一次。
     * 断言三点：只重试一次、第二次出站不含任何图、被拒引用的 prompt 与原始对象相同
     * （==，即「无可兑现」短路——引用文本在 ToolResponseMessage 里本就一字不改）。
     */
    @Test
    void streamImageRejected400DegradesToOneRetryWithoutMedia() throws Exception {
        png("docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看"), toolResultRef()),
                optionsFor("gpt-5.6-sol"));
        Scripted spy = new Scripted(optionsFor("gpt-5.6-sol"), imageRejected400());

        VisionMaterializingChatModel.wrap(spy, root).stream(p).blockLast();

        assertEquals(2, spy.seen.size(), "图片 400 应触发恰好一次降级重试");
        assertNotSame(p, spy.seen.get(0), "第一次应兑现（否则测不到降级）");
        assertSame(p, spy.seen.get(1), "第二次应无可兑现（被拒图被挡下）——引用文本一字不改");
        assertFalse(hasMedia(spy.seen.get(1)), "第二次出站不能再带被拒的图");
    }

    /** call 同享降级（子 agent 经 RetryingChatModel 桥接到 call）。 */
    @Test
    void callImageRejected400DegradesToOneRetryWithoutMedia() throws Exception {
        png("docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看"), toolResultRef()),
                optionsFor("gpt-5.6-sol"));
        Scripted spy = new Scripted(optionsFor("gpt-5.6-sol"), imageRejected400());

        VisionMaterializingChatModel.wrap(spy, root).call(p);

        assertEquals(2, spy.seen.size(), "call 路径同样该降级重试一次");
        assertFalse(hasMedia(spy.seen.get(1)));
    }

    /** 用户贴图被拒：第二次出站的引用块必须如实写 send_failed（锚点文本可改写）。 */
    @Test
    void userImageRejectionRewritesDeliveryToSendFailed() throws Exception {
        png("docs/bug.png");
        String refBlock = "[file reference]\nid: sha256:abc\nkind: image\nmime_type: image/png\n"
                + "size_bytes: 10\nname: bug.png\npath: docs/bug.png\n"
                + "delivery: not_in_view\nreason: x\n[/file reference]";
        Prompt p = new Prompt(List.of(new UserMessage("看\n" + refBlock)),
                optionsFor("gpt-5.6-sol"));
        Scripted spy = new Scripted(optionsFor("gpt-5.6-sol"), imageRejected400());

        VisionMaterializingChatModel.wrap(spy, root).call(p);

        assertFalse(hasMedia(spy.seen.get(1)), "被拒的图不该再发");
        assertTrue(spy.seen.get(1).getInstructions().get(0).getText()
                        .contains("delivery: send_failed"),
                "被拒图的 delivery 必须如实改写：\n"
                        + spy.seen.get(1).getInstructions().get(0).getText());
    }

    /** 非图片类 400（参数错等）：与图片无关，重试只会白烧一次——必须原样传播。 */
    @Test
    void nonImage400IsNotRetried() {
        Scripted spy = new Scripted(optionsFor("gpt-5.6-sol"),
                BadRequestException.builder()
                        .headers(com.openai.core.http.Headers.builder().build())
                        .error(errorObject("1214", "参数非法。请检查文档"))
                        .build());
        Prompt p = new Prompt(List.of(new UserMessage("hi")), optionsFor("gpt-5.6-sol"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> VisionMaterializingChatModel.wrap(spy, root).call(p));
        assertEquals(1, spy.seen.size(), "非图片 400 不得重试");
    }

    /** 没兑现过图（纯文本模型/无引用）就没有可剥的——图片 400 也直接传播。 */
    @Test
    void noMaterializedImagesNoRetry() throws Exception {
        png("docs/bug.png");
        // supportsImage=false：物化是恒等变换，没图可剥
        Scripted spy = new Scripted(optionsFor("deepseek-chat"), imageRejected400());
        Prompt p = new Prompt(List.of(new UserMessage("看\n[file reference] fake")),
                optionsFor("deepseek-chat"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> VisionMaterializingChatModel.wrap(spy, root).call(p));
        assertEquals(1, spy.seen.size(), "无可兑现的图时不得重试");
    }

    /** 重发后再次 400：第二次失败必须传播——降级恰一次，不无限循环。 */
    @Test
    void secondImageFailurePropagates() throws Exception {
        png("docs/bug.png");
        Prompt p = new Prompt(List.of(new UserMessage("看"), toolResultRef()),
                optionsFor("gpt-5.6-sol"));
        Scripted spy = new Scripted(optionsFor("gpt-5.6-sol"), imageRejected400(), imageRejected400());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> VisionMaterializingChatModel.wrap(spy, root).call(p));
        assertEquals(2, spy.seen.size(), "降级重试必须恰一次");
    }
}
