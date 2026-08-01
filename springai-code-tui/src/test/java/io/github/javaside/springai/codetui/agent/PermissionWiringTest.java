package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三处装配点（{@code AgentTools.build} 装饰循环 / {@code buildMemoryTools} / {@code McpRegistry.decorate}）
 * 是否真把 {@link PermissionCallback} 包在了最外层、且共用同一个引擎。
 *
 * <h2>为什么有 turnId 那三个用例（它们盯的是一种<b>静默</b>失效）</h2>
 * {@link PermissionCallback} 的 turnId 取自 {@link ToolContext}，缺失时是 {@code -1}；
 * 而 {@code ConversationState.onPermissionRequested} 会把 {@code turnId != acceptingTurnId} 的请求
 * 就地 DENY。于是<b>任何丢掉 ToolContext 的装配点，它的每一次 ASK 都不会弹面板</b>——
 * 用户只看到工具莫名被拒，日志里什么也没有。故这里对三处装配点各跑一次真实的 ASK：
 * 用真 {@link ConversationState} 当出口，断言请求<b>进了模态队列</b>（而不是被迟到过滤掉）。
 * 回归时这三个用例会红，且不会挂死——turnId 错了就是「立刻被 DENY、工具线程立刻返回」。
 */
class PermissionWiringTest {

    private static final long TURN = 42L;

    // ── 桩 ──────────────────────────────────────────────────────────────

    /** 名字可指定的假工具；记录是否被真正执行（被拒时必须没跑）。 */
    private static final class NoopTool implements ToolCallback {
        private final String name;
        volatile boolean called;
        NoopTool(String name) { this.name = name; }
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        }
        @Override public String call(String toolInput) { called = true; return "ok"; }
        @Override public String call(String toolInput, ToolContext ctx) { return call(toolInput); }
    }

    /** 记录每次到达模型的 Prompt（生产侧 toolContext 的唯一可观测点）。 */
    private static final class CapturingModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        @Override public ChatResponse call(Prompt p) { prompts.add(p); return ok(); }
        @Override public Flux<ChatResponse> stream(Prompt p) { prompts.add(p); return Flux.just(ok()); }
        private ChatResponse ok() {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        Prompt await(int index) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (prompts.size() <= index && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(prompts.size() > index, "等待第 " + (index + 1) + " 条 Prompt 超时");
            return prompts.get(index);
        }
    }

    private static ProviderRegistry providers() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
    }

    /** 从装配产物 ChatClient 上取回它真实注册的 defaultTools（同 {@code RuntimeToolSet} 的取法）。 */
    private static List<ToolCallback> assembledTools(AgentTools.AgentRuntime rt) {
        ChatClient.ChatClientRequestSpec spec = rt.client().prompt();
        assertInstanceOf(DefaultChatClient.DefaultChatClientRequestSpec.class, spec,
                "取不到装配期工具列表：ChatClient 实现已不是 DefaultChatClient");
        return ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getToolCallbacks();
    }

    private static ToolCallback byName(List<ToolCallback> tools, String name) {
        for (ToolCallback t : tools) {
            if (name.equals(t.getToolDefinition().name())) {
                return t;
            }
        }
        throw new AssertionError("装配产物里没有工具 " + name + "，实际："
                + tools.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    private static McpRegistry mcpRegistry(Path root, AgentListener listener, PermissionEngine engine) {
        McpConfigLoader.LoadedServer l = new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig("s1", false, Duration.ofSeconds(2),
                        "echo", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
        return McpRegistry.initForTest(root, listener, List.of(l), engine);
    }

    /**
     * 在后台线程上跑一次工具调用（ASK 会阻塞工具线程），等模态请求进队列，断言它带着 {@code TURN}。
     * <b>不会挂死</b>：turnId 若没传到，请求会被就地 DENY、工具线程立刻返回，这里等到超时即失败。
     */
    private static void assertAskReachesQueue(ToolCallback tool, String toolInput,
                                              ConversationState state, String where) throws Exception {
        Thread worker = new Thread(() -> tool.call(toolInput, new ToolContext(Map.of("turnId", TURN))),
                "permission-wiring-" + where);
        worker.setDaemon(true);
        worker.start();
        try {
            ModalRequest modal = awaitModal(state);
            assertNotNull(modal, where + "：审批请求没能进入模态队列——"
                    + "多半是 ToolContext 没传到，turnId 取成了 -1，请求被 acceptingTurnId 过滤后静默 DENY");
            PermissionRequest req = assertInstanceOf(PermissionRequest.class, modal);
            assertEquals(TURN, req.turnId(), where + "：请求带的 turnId 不是发起回合的");
            req.responder().respond(PermissionOutcome.DENY);   // 放工具线程走，别留 park 的线程
        } finally {
            worker.join(5000);
            assertFalse(worker.isAlive(), where + "：工具线程没能醒来");
        }
    }

    private static ModalRequest awaitModal(ConversationState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ModalRequest r = state.peekModal();
            if (r != null) {
                return r;
            }
            Thread.sleep(10);
        }
        return null;
    }

    // ── 装饰链最外层 ────────────────────────────────────────────────────

    @Test
    @DisplayName("记忆工具的装饰链最外层是 PermissionCallback")
    void memoryToolsAreWrapped(@TempDir Path root) {
        ToolCallback[] tools = AgentTools.buildMemoryTools(root, new ConversationState());

        assertNotNull(tools);
        assertTrue(tools.length > 0, "记忆工具不该为空");
        for (ToolCallback t : tools) {
            assertInstanceOf(PermissionCallback.class, t,
                    "记忆工具装饰链最外层必须是 PermissionCallback，实际：" + t.getClass());
        }
    }

    @Test
    @DisplayName("主 agent 的每一个工具（含 Task / ParallelTasks / 记忆）最外层都是 PermissionCallback")
    void allAssembledToolsAreWrapped(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(providers(), root, new ConversationState());
        List<ToolCallback> tools = assembledTools(rt);

        assertFalse(tools.isEmpty(), "装配产物不该没有工具");
        for (ToolCallback t : tools) {
            assertInstanceOf(PermissionCallback.class, t,
                    "工具 " + t.getToolDefinition().name() + " 没被权限层包住，实际：" + t.getClass());
        }
        assertInstanceOf(PermissionCallback.class, rt.skillTool(),
                "手动 /skill 复用的那个实例也必须走权限层");
    }

    @Test
    @DisplayName("MCP 工具装饰链最外层是 PermissionCallback")
    void mcpToolsAreWrapped(@TempDir Path root) {
        PermissionEngine engine = AgentTools.testEngine(root);
        McpRegistry registry = mcpRegistry(root, new ConversationState(), engine);

        ToolCallback decorated = registry.decorate(new NoopTool("mcp__s1__ping"));

        assertInstanceOf(PermissionCallback.class, decorated);
    }

    // ── 同一个引擎 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("三处装配点共用同一个 PermissionEngine 实例（模式切换必须全局一致）")
    void singleEngineInstance(@TempDir Path root) {
        ConversationState state = new ConversationState();
        PermissionEngine engine = AgentTools.testEngine(root);
        McpRegistry mcp = mcpRegistry(root, state, engine);
        AgentTools.AgentRuntime rt = AgentTools.build(providers(), root, state, mcp, engine);

        assertSame(engine, rt.permissionEngine(), "AgentRuntime 暴露的必须是传进去的那一个");
        for (ToolCallback t : assembledTools(rt)) {
            assertSame(engine, ((PermissionCallback) t).engine(),
                    "工具 " + t.getToolDefinition().name() + " 挂在了另一个引擎上：Shift+Tab 切模式对它无效");
        }
        assertSame(engine, ((PermissionCallback) mcp.decorate(new NoopTool("mcp__s1__ping"))).engine(),
                "MCP 工具挂在了另一个引擎上");
    }

    @Test
    @DisplayName("子 agent 拿到的是同一批实例、同一个引擎（不能各自新建一个 = 绕过用户已做的授权）")
    void subagentsShareTheSameEngine(@TempDir Path root) {
        ConversationState state = new ConversationState();
        PermissionEngine engine = AgentTools.testEngine(root);
        McpRegistry mcp = mcpRegistry(root, state, engine);
        mcp.addConnectedForTest("s1", List.of(mcp.decorate(new NoopTool("mcp__s1__ping"))));
        AgentTools.AgentRuntime rt = AgentTools.build(providers(), root, state, mcp, engine);

        SubagentSpec spec = new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
        List<ToolCallback> subagentTools = rt.subagentRunner().effectiveTools(spec);

        assertFalse(subagentTools.isEmpty(), "子 agent 工具集不该为空");
        assertTrue(subagentTools.stream().anyMatch(t -> t.getToolDefinition().name().startsWith("mcp__")),
                "子 agent 也该看得到 MCP 工具（它们同样要过权限层）");
        for (ToolCallback t : subagentTools) {
            PermissionCallback pc = assertInstanceOf(PermissionCallback.class, t,
                    "子 agent 的工具 " + t.getToolDefinition().name() + " 没走权限层");
            assertSame(engine, pc.engine(), "子 agent 的 " + t.getToolDefinition().name()
                    + " 挂在了另一个引擎上：用户在主 agent 里授过的权对它无效");
        }
    }

    // ── turnId 传播（三处装配点各一次真实 ASK）────────────────────────────

    @Test
    @DisplayName("装饰循环：ASK 带着发起回合的 turnId 进模态队列，不被静默 DENY")
    void turnIdReachesQueue_assembledTools(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        state.onTurnStarted(TURN);
        AgentTools.AgentRuntime rt = AgentTools.build(providers(), root, state);

        // DEFAULT 模式下写工作区文件 → ASK（见 PermissionEngine.fileWriteByMode）
        assertAskReachesQueue(byName(assembledTools(rt), "Write"),
                "{\"filePath\":\"" + json(root.resolve("a.txt")) + "\",\"content\":\"x\"}",
                state, "装饰循环");
    }

    @Test
    @DisplayName("buildMemoryTools：ASK 带着发起回合的 turnId 进模态队列")
    void turnIdReachesQueue_memoryTools(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        state.onTurnStarted(TURN);
        // 记忆工具是 INTERNAL（恒放行），用一条 ask 规则逼出一次真实审批
        PermissionEngine engine = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT,
                        List.of(new PermissionRule("MemoryView", null,
                                PermissionBehavior.ASK, RuleScope.SESSION))),
                PermissionMode.DEFAULT, false);
        ToolCallback[] tools = AgentTools.buildMemoryTools(root, state, engine);

        assertAskReachesQueue(byName(List.of(tools), "MemoryView"), "{\"path\":\"/\"}",
                state, "buildMemoryTools");
    }

    @Test
    @DisplayName("McpRegistry.decorate：ASK 带着发起回合的 turnId 进模态队列")
    void turnIdReachesQueue_mcpTools(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        state.onTurnStarted(TURN);
        McpRegistry mcp = mcpRegistry(root, state, AgentTools.testEngine(root));

        // MCP 工具未登记 → 兜底 ASK
        NoopTool raw = new NoopTool("mcp__s1__ping");
        assertAskReachesQueue(mcp.decorate(raw), "{\"q\":1}", state, "McpRegistry");
        assertFalse(raw.called, "被拒的调用不该真的执行");
    }

    // ── 生产侧：turnId 是谁放进 ToolContext 的 ────────────────────────────

    @Test
    @DisplayName("CodingAgent.submit 把 turnId 放进真实 Prompt 的 toolContext（消费侧的前提）")
    void submitCarriesTurnIdInToolContext() throws Exception {
        CapturingModel model = new CapturingModel();
        ChatClient client = ChatClient.builder(model).build();
        CodingAgent agent = new CodingAgent(providers(), Map.of("deepseek", client),
                new StubListener(), "sid", new AtomicLong(),
                null, null, null, List.of(), null, null, null, null, null, null);

        agent.submit("hi");

        assertEquals(1L, toolContextTurnId(model.await(0)),
                "submit 没把 turnId 放进 toolContext：所有 ASK 都会被 ConversationState 当迟到请求 DENY");
    }

    @Test
    @DisplayName("SubagentRunner 把 parentTurnId 放进真实 Prompt 的 toolContext（子 agent 的 ASK 也要弹面板）")
    void subagentCarriesTurnIdInToolContext() throws Exception {
        CapturingModel model = new CapturingModel();
        ProviderRegistry reg = new ProviderRegistry(List.of(new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            // 必须是 ToolCallingChatOptions：否则 toolContext 会被静默丢弃（真实 DeepSeekChatOptions 是）
            @Override public ChatOptions options(String modelId) {
                return ToolCallingChatOptions.builder().build();
            }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("m", "M", "d")); }
            @Override public String defaultModel() { return "m"; }
        }));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");

        runner.run(new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()),
                "hi", "desc", 7L);

        assertEquals(7L, toolContextTurnId(model.await(0)),
                "子 agent 没把 parentTurnId 放进 toolContext：子 agent 里的 ASK 会被静默 DENY");
    }

    private static long toolContextTurnId(Prompt prompt) {
        assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions(),
                "Prompt options 不是 ToolCallingChatOptions，toolContext 根本不会被带上");
        Map<String, Object> ctx = ((ToolCallingChatOptions) prompt.getOptions()).getToolContext();
        Object v = ctx.get("turnId");
        assertInstanceOf(Long.class, v, "toolContext 里没有 Long 型 turnId，实际：" + ctx);
        return (Long) v;
    }

    /** 路径进 JSON 字符串（Windows 反斜杠会被当转义符）。 */
    private static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }
}
