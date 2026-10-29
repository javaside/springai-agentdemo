package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.ImageProfile;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
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
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 agent 的出站兑现：视觉模型必须把引用物化成真 Media，文本模型必须一张都不发。
 *
 * <p><b>文本用例为什么用 gpt-5.6-sol</b>：它在全局白名单（VisionModels）里，而 stub provider 的
 * capabilities 返回 false。若把能力谓词错写成全局 VisionModels::supportsImage，本用例会变红——
 * 这正是 provider 注入的意义（兼容网关同名模型、OpencodeGo 自定义模型都靠它）。
 */
class SubagentRunnerVisionMaterializeTest {

    @TempDir Path root;

    /** 记录 delegate 真正收到的 Prompt。 */
    private static final class SpyModel implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        private final String model;
        SpyModel(String model) { this.model = model; }
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() {
            // 必须是 ToolCallingChatOptions：否则 ChatClient 拿到的不是它 → ToolCallingAdvisor 整个跳过
            return ToolCallingChatOptions.builder().model(model).build();
        }
    }

    private LlmProvider provider(String id, String modelId, boolean vision, SpyModel spy) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return spy; }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
            @Override public String defaultModel() { return modelId; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(vision, false); }
        };
    }

    /** 与 AgentTools 里那份实现同构（本任务就是验证这份链序）。 */
    private static SubagentVision vision(Path root) {
        return (base, sel, budget) -> VisionMaterializingChatModel.wrap(
                base, root,
                id -> sel.provider().capabilities(id).supportsImageInput(),
                budget,
                ImageProfile.forProvider(sel.provider().id()));
    }

    private void png(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    /** 能通过 FileReferenceParser 严格校验的引用块。 */
    private String ref(String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: a.png\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private static boolean hasMedia(Prompt p) {
        return p.getInstructions().stream()
                .filter(Message.class::isInstance)
                .anyMatch(m -> m instanceof MediaContent mc && !mc.getMedia().isEmpty());
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    @Test
    void visionModelGetsRealMediaInOutboundPrompt() throws Exception {
        png("a.png");
        SpyModel spy = new SpyModel("glm-5.3-flash");
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("glm", "glm-5.3-flash", true, spy)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "",
                4, null, () -> null, vision(root));

        runner.run(spec(), "看这个\n" + ref("a.png"), "d", 1L);

        assertTrue(hasMedia(spy.seen.get()),
                "视觉模型的子 agent 出站必须带真 Media；否则引用块只是文本，模型永远看不见图");
    }

    @Test
    void textModelGetsNoMediaEvenIfModelIdIsGloballyVisionCapable() throws Exception {
        png("a.png");
        SpyModel spy = new SpyModel("gpt-5.6-sol");
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("openai", "gpt-5.6-sol", false, spy)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "",
                4, null, () -> null, vision(root));

        runner.run(spec(), "看这个\n" + ref("a.png"), "d", 1L);

        assertFalse(hasMedia(spy.seen.get()),
                "provider 判不支持时一张都不许发——错写全局 VisionModels::supportsImage 会让本用例变红");
        // VisionMaterializer 在 visionCapable=false 时【原样返回同一个 Prompt】，不做任何改写：
        // 故这里断言的是「一行都没被改成 delivered」，而不是 reference_only（那是【入站】写的）。
        assertTrue(spy.seen.get().getInstructions().stream()
                        .noneMatch(m -> m.getText() != null && m.getText().contains("delivery: delivered")),
                "不支持的模型必须一张都不兑现——任何 delivery: delivered 都意味着真把图发出去了");
    }
}
