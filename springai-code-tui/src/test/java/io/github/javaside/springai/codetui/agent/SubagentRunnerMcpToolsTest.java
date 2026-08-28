package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.mcp.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.mcp.McpServerConfig;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具的子 agent 动态注入：effectiveTools = 内置工具 + registry 实时 activeTools，
 * 再按 spec allow/deny 以注册名过滤；disable 后下一次委派即不含。
 */
class SubagentRunnerMcpToolsTest {

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    private static SubagentSpec spec(List<String> deny) {
        return new SubagentSpec("t", "d", "x", List.of(), deny, null, List.of());
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

    @Test
    void mcpToolsFlowIntoSubagentAndRespectDeny(@TempDir Path root) {
        McpRegistry reg = registryWithTool(root, "mcp__s1__ping");
        SubagentRunner runner = new SubagentRunner(McpWiringTestSupport.dummyRegistry(),
                List.of(fakeTool("Grep")), new ConversationState(), "", 4, reg);

        List<String> names = runner.effectiveTools(spec(List.of())).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertTrue(names.contains("mcp__s1__ping"), "MCP 工具应进入子 agent 工具集");
        assertTrue(names.contains("Grep"));

        List<String> filtered = runner.effectiveTools(spec(List.of("mcp__s1__ping"))).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertFalse(filtered.contains("mcp__s1__ping"), "deny 按注册名过滤 MCP 工具");
    }

    @Test
    void disableRemovesFromSubagentToolset(@TempDir Path root) {
        McpRegistry reg = registryWithTool(root, "mcp__s1__ping");
        SubagentRunner runner = new SubagentRunner(McpWiringTestSupport.dummyRegistry(),
                List.of(), new ConversationState(), "", 4, reg);
        reg.disable("s1");
        assertTrue(runner.effectiveTools(spec(List.of())).isEmpty());
    }
}
