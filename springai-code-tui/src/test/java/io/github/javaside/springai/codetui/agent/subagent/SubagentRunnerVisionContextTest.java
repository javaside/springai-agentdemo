package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结进 ToolContext 的两样东西：能力快照 + 该派发自带的预算。 */
class SubagentRunnerVisionContextTest {

    /**
     * 消息里还没有工具返回 → 发一次工具调用；有 → 收尾成文本。
     *
     * <p>按消息内容判状态而不是按调用计数：同一个 runner 连续 run 两次时，计数会在第二次
     * 直接收尾、假工具根本不再执行（那样「每次派发各带预算」就测不到了）。
     * 也必须能收敛——永远返回工具调用会让 ToolCallingAdvisor 无限循环（首版就这么挂死过）。
     */
    private static ChatModel toolCallingModel(String modelId) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt p) {
                boolean toolAlreadyRan = p.getInstructions().stream()
                        .anyMatch(m -> m instanceof ToolResponseMessage);
                AssistantMessage msg = toolAlreadyRan
                        ? new AssistantMessage("done")
                        : AssistantMessage.builder()
                                .content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                                        "c1", "function", "capture", "{}"))).build();
                return new ChatResponse(List.of(new Generation(msg)));
            }
            @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
            @Override public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().model(modelId).build();
            }
        };
    }

    private static LlmProvider provider(String id, String modelId, boolean vision) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return toolCallingModel(modelId); }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
            @Override public String defaultModel() { return modelId; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(vision, false); }
        };
    }

    private static ToolCallback captureTool(AtomicReference<ToolContext> seen) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("capture").description("c").inputSchema("{}").build();
            }
            @Override public String call(String i) { return "ok"; }
            @Override public String call(String i, ToolContext ctx) { seen.set(ctx); return "ok"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner wired(LlmProvider p, ToolCallback tool) {
        ProviderRegistry registry = new ProviderRegistry(List.of(p));
        return new SubagentRunner(registry, List.of(tool), new StubListener(), "",
                4, null, () -> null, (base, sel, budget) -> base);
    }

    @Test
    void visionDispatchFreezesCapabilitiesAndItsOwnBudget() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        wired(provider("glm", "glm-5.3-flash", true), captureTool(seen)).run(spec(), "hi", "d", 1L);

        assertNotNull(seen.get(), "假工具必须真的被调用过一次");
        ModelCapabilities caps = (ModelCapabilities) seen.get().getContext()
                .get(MediaExternalizingCallback.CAPABILITIES_KEY);
        assertNotNull(caps, "视觉派发必须冻结能力快照，否则引用块永远写 reference_only");
        assertTrue(caps.supportsImageInput());
        assertNotNull(seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY),
                "每派发必须自带 VisionBudget 并下发给工具侧");
    }

    @Test
    void textOnlyDispatchFreezesUnsupportedCapabilities() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        wired(provider("openai", "gpt-5.6-sol", false), captureTool(seen)).run(spec(), "hi", "d", 1L);

        ModelCapabilities caps = (ModelCapabilities) seen.get().getContext()
                .get(MediaExternalizingCallback.CAPABILITIES_KEY);
        assertNotNull(caps);
        assertFalse(caps.supportsImageInput());
    }

    @Test
    void notWiredMeansNoVisualKeysAtAll() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("glm", "glm-5.3-flash", true)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(captureTool(seen)), new StubListener(), "");

        runner.run(spec(), "hi", "d", 1L);

        assertNotNull(seen.get());
        assertNull(seen.get().getContext().get(MediaExternalizingCallback.CAPABILITIES_KEY),
                "未接线不得半开：只冻结 caps 不接出站会造出「说 Read 可见却永远看不见」的假话");
        assertNull(seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY));
    }

    @Test
    void eachDispatchGetsADistinctBudget() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        SubagentRunner runner = wired(provider("glm", "glm-5.3-flash", true), captureTool(seen));

        runner.run(spec(), "hi", "d", 1L);
        VisionBudget first = (VisionBudget) seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY);
        runner.run(spec(), "hi", "d", 1L);
        VisionBudget second = (VisionBudget) seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY);

        assertNotSame(first, second,
                "同一 prompt 的两次派发必须各带预算——否则额度跨派发累计且永不回收");
    }

    @Test
    void backgroundDispatchKeepsBackgroundTaskId() throws Exception {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        SubagentRunner runner = wired(provider("glm", "glm-5.3-flash", true), captureTool(seen));
        runner.enableBackground(new BackgroundTaskRegistry(4), 1);

        runner.runInBackground(spec(), "hi", "d");
        for (int i = 0; i < 200 && seen.get() == null; i++) {
            Thread.sleep(20);
        }

        assertNotNull(seen.get(), "后台派发必须真的跑起来");
        assertNotNull(seen.get().getContext().get(ToolEventCallback.BACKGROUND_TASK_ID_KEY),
                "整表复制：漏掉 backgroundTaskId 会让后台审批静默变成阻塞询问");
        assertEquals(String.valueOf(seen.get().getContext().get(ToolEventCallback.TASK_ID_KEY)),
                String.valueOf(seen.get().getContext().get(ToolEventCallback.BACKGROUND_TASK_ID_KEY)),
                "后台路径下两个 taskId 键应指向同一个值");
    }
}
