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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentToolsMcpWiringTest {

    private static ToolCallback fakeMcpTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("mcp__fake__ping")
                        .description("fake mcp tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }
            @Override public String call(String toolInput) { return "pong"; }
        };
    }

    @Test
    void buildAcceptsMcpRegistryWithoutThrowing(@TempDir Path root) {
        McpConfigLoader.LoadedServer l = new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig("fake", false, java.time.Duration.ofSeconds(2),
                        "echo", List.of(), java.util.Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(l));
        reg.addConnectedForTest("fake", List.of(fakeMcpTool()));
        assertDoesNotThrow(() -> AgentTools.build(
                McpWiringTestSupport.dummyRegistry(), root, new ConversationState(), reg));
    }

    @Test
    void threeArgOverloadStillBuildsForBackwardCompat(@TempDir Path root) {
        assertDoesNotThrow(() -> AgentTools.build(
                McpWiringTestSupport.dummyRegistry(), root, new ConversationState()));
    }
}
