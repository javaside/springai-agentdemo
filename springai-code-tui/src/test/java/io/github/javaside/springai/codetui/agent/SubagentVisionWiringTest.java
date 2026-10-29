package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
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
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产装配守卫：装配传参 + 执行器建链两处都改了才算真的接上。
 *
 * <p>单测里 new 一个 SubagentRunner 全绿、而 AgentTools 少传一个参数，是这类改动的经典漏法
 * （同 AuxClientNotVisionWrappedTest 存在的理由）。本用例走真实 build()。
 */
class SubagentVisionWiringTest {

    @TempDir Path root;

    private static final class SpyModel implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("glm-5.3-flash").build();
        }
    }

    @Test
    void productionWiringMaterializesSubagentImages() throws Exception {
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png",
                root.resolve("a.png").toFile());

        SpyModel spy = new SpyModel();
        LlmProvider provider = new LlmProvider() {
            @Override public String id() { return "glm"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return spy; }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() {
                return List.of(new ModelOption("glm-5.3-flash", "flash", "d"));
            }
            @Override public String defaultModel() { return "glm-5.3-flash"; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(true, false); }
        };
        ProviderRegistry registry = new ProviderRegistry(List.of(provider));
        AgentTools.AgentRuntime rt = AgentTools.build(registry, root, new StubListener());

        String ref = "[file reference]\n"
                + "id: sha256:abcd\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: a.png\n"
                + "path: a.png\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
        rt.subagentRunner().run(
                new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()),
                "看这个\n" + ref, "d", 1L);

        assertNotNull(spy.seen.get(), "子 agent 的请求必须真的发到 provider");
        assertTrue(spy.seen.get().getInstructions().stream()
                        .filter(Message.class::isInstance)
                        .anyMatch(m -> m instanceof MediaContent mc && !mc.getMedia().isEmpty()),
                "AgentTools 必须把视觉接缝传给 SubagentRunner，否则子 agent 永远看不见图");
    }
}
