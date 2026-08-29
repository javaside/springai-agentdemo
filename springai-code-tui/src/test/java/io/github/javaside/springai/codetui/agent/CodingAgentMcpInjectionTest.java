package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;

import io.github.javaside.springai.codetui.agent.mcp.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.mcp.McpServerConfig;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/**
 * 主 agent 每回合 MCP 工具快照注入（{@code submit} 的 {@code .tools(activeTools)}）：
 * 断言的是<b>真正传给 ChatModel 的 Prompt</b> 里的 toolCallbacks——即模型实际可见的工具集。
 * disable 后下一回合不再含该 server 的工具，内置 defaultTools 不受影响。
 *
 * <p><b>桩契约</b>：{@code getOptions()} 必须返回 {@link ToolCallingChatOptions}——
 * DefaultChatClientUtils 用 {@code chatModel.getOptions().mutate()} 起 runtime options builder，
 * 非 ToolCallingChatOptions.Builder 时 toolCallbacks 会被<b>静默丢弃</b>（真实 DeepSeekChatModel
 * 返回 DeepSeekChatOptions 故生产不受影响；漏实现会让本测试假阴性地看到空工具集）。
 */
class CodingAgentMcpInjectionTest {

    /** 记录每次到达模型的 Prompt；流式返回单块 "ok"（无 tool_calls，回合即完）。 */
    private static final class CapturingModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public ChatResponse call(Prompt p) { prompts.add(p); return ok(); }
        @Override public Flux<ChatResponse> stream(Prompt p) { prompts.add(p); return Flux.just(ok()); }
        private ChatResponse ok() {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        /** 流式链可能在后台调度器上订阅（ToolCallingAdvisor stream 路径），须等第 n 条 Prompt 到达。 */
        List<String> toolNamesOfPrompt(int index) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (prompts.size() <= index && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(prompts.size() > index, "等待第 " + (index + 1) + " 条 Prompt 超时");
            if (prompts.get(index).getOptions() instanceof ToolCallingChatOptions tc) {
                return tc.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).toList();
            }
            return List.of();
        }
    }

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
            @Override public String call(String toolInput, ToolContext ctx) { return "ok"; }
        };
    }

    private static McpRegistry registryWithTool(Path root, String toolName) {
        McpConfigLoader.LoadedServer l = new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig("s1", false, Duration.ofSeconds(2),
                        "echo", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(l));
        reg.addConnectedForTest("s1", List.of(fakeTool(toolName)));
        return reg;
    }

    private record Fixture(CodingAgent agent, CapturingModel model, McpRegistry reg) { }

    private static Fixture fixture(Path root) {
        McpRegistry reg = registryWithTool(root, "mcp__s1__ping");
        CapturingModel model = new CapturingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultTools((Object) fakeTool("Grep"))
                .build();
        ProviderRegistry providers = new ProviderRegistry(List.of(new DeepSeekProvider("k")));
        CodingAgent agent = new CodingAgent(providers, Map.of("deepseek", client),
                new StubListener(), "sid", new AtomicLong(),
                null, null, null, List.of(), null, null, null, null, null, reg);
        return new Fixture(agent, model, reg);
    }

    @Test
    void mcpToolsInjectedAlongsideDefaultTools(@TempDir Path root) throws InterruptedException {
        Fixture f = fixture(root);
        f.agent().submit("第一回合");
        List<String> names = f.model().toolNamesOfPrompt(0);
        assertTrue(names.contains("mcp__s1__ping"), "MCP 工具应随回合快照注入，实际: " + names);
        assertTrue(names.contains("Grep"), "内置 defaultTools 应同在，实际: " + names);
    }

    @Test
    void disableRemovesMcpToolsFromNextTurn(@TempDir Path root) throws InterruptedException {
        Fixture f = fixture(root);
        f.agent().submit("第一回合");
        f.model().toolNamesOfPrompt(0);   // 先等第一回合真正抵达模型，再切换，保证回合边界干净
        f.reg().disable("s1");
        f.agent().submit("第二回合");
        List<String> names = f.model().toolNamesOfPrompt(1);
        assertFalse(names.contains("mcp__s1__ping"), "disable 后下一回合不应再暴露 MCP 工具，实际: " + names);
        assertTrue(names.contains("Grep"), "内置工具不受 MCP 启停影响，实际: " + names);
    }
}
