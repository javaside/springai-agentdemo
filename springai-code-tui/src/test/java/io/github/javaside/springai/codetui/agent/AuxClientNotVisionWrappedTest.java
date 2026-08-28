package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;

import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：辅助 client（SmartWebFetch 抽取 + <b>会话滚动摘要</b>）的底层 ChatModel
 * <b>绝不能</b>被视觉兑现装饰器包上。
 *
 * <p><b>包上会发生什么</b>：摘要请求里的消息含引用块——一旦被包，每次压缩都会把历史图兑现成真
 * 字节发给摘要模型，<b>一次纯文本摘要静默变成视觉请求</b>。而压缩是<b>自动触发</b>的、全程无提示，
 * 你不会注意到，直到看账单。这正是那种「不写下来三个月后一定有人顺手包上」的地方。
 *
 * <p><b>为什么这条测试能真的挡事</b>：{@link AgentTools#auxChatModel} 是「auxClient 用哪个
 * ChatModel」的<b>唯一决策点</b>（从一长串构造里抽出来就是为了能被盯住），这里断言它的返回值是
 * <b>恰好</b> {@link DynamicAuxChatModel} 而非任何子类/装饰——不止拦视觉装饰器，套任何一层都会红。
 */
class AuxClientNotVisionWrappedTest {

    private ProviderRegistry registry() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
    }

    /** ★ 核心守卫：aux 的 ChatModel 不许是视觉装饰器。 */
    @Test
    void auxChatModelIsNotVisionWrapped() {
        ChatModel m = AgentTools.auxChatModel(registry());
        assertFalse(m instanceof VisionMaterializingChatModel,
                "auxClient 被视觉装饰器包上了：每次自动压缩都会把历史图兑现成真字节发给摘要模型，"
                        + "纯文本摘要静默变成视觉请求，且无任何提示");
    }

    /**
     * 更紧的一道：必须<b>恰好</b>是 DynamicAuxChatModel。
     *
     * <p>只断言「不是视觉装饰器」挡不住「先包一层别的、别的里面再包视觉」。这条把 aux 钉死成裸的，
     * 任何装饰都得先在这里被看见一眼。
     */
    @Test
    void auxChatModelIsExactlyTheDynamicOne() {
        assertEquals(DynamicAuxChatModel.class, AgentTools.auxChatModel(registry()).getClass(),
                "aux 的 ChatModel 被套了一层——先想清楚那一层会不会跟着自动压缩一起触发");
    }

    /**
     * 对照：{@link AgentTools.AgentRuntime#visionModels()} 的键必须与 {@code clients} 一一对应
     * ——{@code /context} 按激活 provider 取快照，缺一个就取到 null。
     */
    @Test
    void visionModelsKeysMatchClients(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(registry(), root, new ConversationState());
        assertNotNull(rt.visionModels());
        assertEquals(rt.clients().keySet(), rt.visionModels().keySet(),
                "clients 与 visionModels 的键必须一一对应，否则 /context 会按激活 provider 取到 null");
    }

    /**
     * 对照（真接线）：per-provider 的 ChatClient <b>发出去的那个 Prompt</b> 必须已被兑现。
     *
     * <p>没有这条，「aux 不许包」可以靠<b>谁都不包</b>来满足——功能整个没接上，守卫却全绿。
     * 也不能只断言 {@code visionModels} 这张表非空：表填了、而 {@code ChatClient.builder} 仍传
     * {@code provider.chatModel()}（少改一处）时，表照样对，图却一张都发不出去。
     * 故这里从 {@code rt.client()} 真发一次，看落到最底层 ChatModel 的 Prompt 上有没有 Media。
     */
    @Test
    void activeClientActuallyMaterializesOutboundPrompt(@TempDir Path root) throws Exception {
        Path img = root.resolve("bug.png");
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", img.toFile());

        SpyProvider provider = new SpyProvider();
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(provider)), root, new ConversationState());

        // 会话记忆 advisor 是每个 client 的默认 advisor，缺 session id 会直接抛（照 CodingAgent.submit 传）。
        rt.client().prompt()
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "t8-guard"))
                .user("看这个\n" + reference("bug.png"))
                .call().content();

        Prompt outbound = provider.spy.seen.get();
        assertNotNull(outbound, "请求没到达底层 ChatModel");
        assertTrue(hasMedia(outbound),
                "ChatClient 出站的 Prompt 里没有 Media——per-provider 的 ChatModel 没被视觉装饰器包上");
    }

    /** 合法引用块（照抄 VisionMaterializerTest 的构造）。 */
    private String reference(String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: " + rel + "\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private boolean hasMedia(Prompt prompt) {
        for (Message m : prompt.getInstructions()) {
            if (m instanceof MediaContent && !((MediaContent) m).getMedia().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 记录最底层 ChatModel 实际收到的 Prompt。 */
    private static final class SpyChatModel implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        /** 必须是带模型 id 的 ToolCallingChatOptions：兑现判定读的正是出站 Prompt 的 model。 */
        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model(VISION_MODEL).build();
        }
    }

    /** 支持视觉的模型 id（在 VisionModels 名单内），否则装饰器会正确地什么都不做。 */
    private static final String VISION_MODEL = "gpt-5.6-sol";

    private static final class SpyProvider implements LlmProvider {
        final SpyChatModel spy = new SpyChatModel();
        @Override public String id() { return "spy"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return spy; }
        @Override public ChatOptions options(String modelId) {
            return ToolCallingChatOptions.builder().model(modelId).build();
        }
        @Override public List<ModelOption> models() {
            return List.of(new ModelOption(VISION_MODEL, VISION_MODEL, "test"));
        }
        @Override public String defaultModel() { return VISION_MODEL; }
        @Override public io.github.javaside.springai.codetui.agent.media.ModelCapabilities capabilities(String modelId) {
            return new io.github.javaside.springai.codetui.agent.media.ModelCapabilities(true, false);
        }
    }
}
