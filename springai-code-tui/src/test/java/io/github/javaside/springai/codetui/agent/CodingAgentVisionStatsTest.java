package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.agent.media.VisionSnapshot;
import io.github.javaside.springai.codetui.agent.session.ContextStats;
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
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉占用是否<b>真的从装饰器流进了</b> {@code /context}。
 *
 * <p>只断言 {@link ContextStats} 的字段存取是「不会失败的测试」——{@code contextStats()} 恒返回 0
 * 它照样绿。这里把真实的 {@link VisionMaterializingChatModel} 喂进去兑现出快照，再从
 * {@code CodingAgent.contextStats()} 那一头读回来，中间任何一段断掉都会红。
 *
 * <p><b>map 里刻意放两家、且激活的不是第一家</b>：只有一家时「按激活取」和「随便取一个」结果相同，
 * 测试就成了摆设——而取错家报的是别家的陈旧数字，看起来完全合理，没有任何迹象提示账不对。
 */
class CodingAgentVisionStatsTest {

    @TempDir Path root;

    // ── 桩 ────────────────────────────────────────────────────

    /** 只接住请求、不关心内容的 delegate。 */
    private static final class NoopModel implements ChatModel {
        @Override public ChatResponse call(Prompt p) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
    }

    /** 可用的假 provider：只需 id/available/models/defaultModel，registry 的选家逻辑就够跑。 */
    private record FakeProvider(String id, String model) implements LlmProvider {
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { throw new UnsupportedOperationException(); }
        @Override public ChatOptions options(String modelId) {
            return ToolCallingChatOptions.builder().model(modelId).build();
        }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(model, model, "")); }
        @Override public String defaultModel() { return model; }
        @Override public ModelCapabilities capabilities(String modelId) { return ModelCapabilities.TEXT_ONLY; }
    }

    /** contextStats 只读事件与消息；这里给空会话即可，视觉那两列与会话内容无关。 */
    private static final class EmptySessionService implements SessionService {
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { return List.of(); }
        @Override public List<Message> getMessages(String id) { return List.of(); }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public void appendEvent(SessionEvent e) { throw new UnsupportedOperationException(); }
        @Override public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            throw new UnsupportedOperationException();
        }
    }

    private static final TokenCountEstimator LEN_ESTIMATOR = new TokenCountEstimator() {
        @Override public int estimate(String text) { return text.length(); }
        @Override public int estimate(MediaContent c) { return 0; }
        @Override public int estimate(Iterable<MediaContent> c) { return 0; }
    };

    // ── 辅助 ──────────────────────────────────────────────────

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
     * 让一个装饰器真的兑现 {@code count} 张用户贴图，从而在它内部留下非空快照。
     * 走用户贴图而非工具产图：工具图每请求只兑现最新一张（{@code MAX_TOOL_IMAGES=1}），
     * 张数拉不开差距，两家就区分不出来了。
     */
    private void materializeUserImages(VisionMaterializingChatModel model, String prefix, int count)
            throws Exception {
        StringBuilder text = new StringBuilder("看这些图");
        for (int i = 0; i < count; i++) {
            String rel = prefix + i + ".png";
            png(rel);
            text.append('\n').append(ref(rel, rel));
        }
        ChatOptions visionOptions = ToolCallingChatOptions.builder().model("gpt-5.6-sol").build();
        model.call(new Prompt(List.of(new UserMessage(text.toString())), visionOptions));
    }

    /** 两家 provider：alpha 在前、bravo 在后；构造后激活的是 alpha。 */
    private ProviderRegistry twoProviders() {
        return new ProviderRegistry(List.of(
                new FakeProvider("alpha", "alpha-model"),
                new FakeProvider("bravo", "bravo-model")));
    }

    private CodingAgent agentOver(ProviderRegistry registry,
                                  Map<String, VisionMaterializingChatModel> visionModels) {
        return new CodingAgent(registry, Map.of(), null, "s", new AtomicLong(),
                new EmptySessionService(), null, LEN_ESTIMATOR, List.of(),
                null, null, null, null, null, null, null, visionModels);
    }

    // ── 测试 ──────────────────────────────────────────────────

    /**
     * ★ 端到端：装饰器兑现出的张数与 token 必须出现在 {@code contextStats()} 里，
     * 且取的是<b>激活那一家</b>的账——map 有两条、激活的是第二条。
     */
    @Test
    void contextStatsReadsActiveProvidersDecorator() throws Exception {
        VisionMaterializingChatModel alpha = VisionMaterializingChatModel.wrap(new NoopModel(), root);
        VisionMaterializingChatModel bravo = VisionMaterializingChatModel.wrap(new NoopModel(), root);
        materializeUserImages(alpha, "a", 1);   // 第一家：1 张
        materializeUserImages(bravo, "b", 2);   // 第二家：2 张 —— 与第一家不同，才能验出取错家
        assertEquals(1, alpha.lastSnapshot().images(), "前置条件：alpha 应已兑现 1 张");
        assertEquals(2, bravo.lastSnapshot().images(), "前置条件：bravo 应已兑现 2 张");

        ProviderRegistry registry = twoProviders();
        registry.select("bravo-model");   // 激活第二家（/model 跨家切换）
        assertEquals("bravo", registry.active().id(), "前置条件：激活的必须不是第一家");

        // 刻意用 LinkedHashMap 且 alpha 排在最前：Map.of 的迭代序每次 JVM 启动都不同，
        // 「误按第一个条目取」这类缺陷就只有一半概率被抓到，是道抓不稳的网。
        Map<String, VisionMaterializingChatModel> visionModels = new LinkedHashMap<>();
        visionModels.put("alpha", alpha);
        visionModels.put("bravo", bravo);

        ContextStats s = agentOver(registry, visionModels).contextStats();

        assertEquals(2, s.visionImages(), "取到的不是激活 provider（bravo）的快照");
        assertEquals(bravo.lastSnapshot().tokens(), s.visionTokens(), "视觉 token 没从装饰器流过来");
        assertTrue(s.visionTokens() > 0, "兑现了图却报 0 token");
        assertEquals(0L, s.estimatedTokens(), "空会话的文本估算必须仍是 0：视觉 token 不许混进这一笔");
    }

    /** 无 registry / 无 map / 该家没装饰器 → 空快照，不抛。 */
    @Test
    void missingWiringDegradesToEmptySnapshot() {
        ProviderRegistry registry = twoProviders();
        assertSame(VisionSnapshot.EMPTY, CodingAgent.snapshotOf(null, registry));
        assertSame(VisionSnapshot.EMPTY, CodingAgent.snapshotOf(Map.of(), null));
        assertSame(VisionSnapshot.EMPTY, CodingAgent.snapshotOf(Map.of(), registry));
        assertEquals(0, agentOver(registry, null).contextStats().visionImages());
    }
}
