package io.github.javaside.springai.codetui.agent;

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
    void buildAcceptsMcpToolsWithoutThrowing(@TempDir Path root) {
        assertDoesNotThrow(() -> AgentTools.build(
                McpWiringTestSupport.dummyRegistry(), root, new ConversationState(),
                List.of(fakeMcpTool())));
    }

    @Test
    void threeArgOverloadStillBuildsForBackwardCompat(@TempDir Path root) {
        assertDoesNotThrow(() -> AgentTools.build(
                McpWiringTestSupport.dummyRegistry(), root, new ConversationState()));
    }
}
