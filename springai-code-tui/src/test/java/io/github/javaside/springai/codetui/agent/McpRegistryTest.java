package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpConfigLoader.LoadedServer bogus(String name, boolean enabled, Path file) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, enabled, Duration.ofSeconds(2),
                        "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, file);
    }

    private static ToolCallback fakeTool(String registeredName) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(registeredName).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    /** 写一个含单条目的 mcp.json，返回文件路径（供回写断言）。 */
    private static Path writeCfg(Path dir, String name, boolean enabled) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, "{\"mcpServers\":{\"" + name + "\":{\"command\":\"echo\",\"enabled\":" + enabled + "}}}");
        return f;
    }

    @Test
    void disabledEntryIsListedButNotConnected(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", false);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            List<McpRegistry.ServerView> views = reg.servers();
            assertEquals(1, views.size());
            assertEquals(McpRegistry.Status.DISABLED, views.get(0).status());
            assertTrue(reg.activeTools().isEmpty());
        } finally {
            reg.close();
        }
    }

    @Test
    void enableOnBrokenServerRecordsErrorAndStillPersistsIntent(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", false);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            McpRegistry.ToggleResult r = reg.enable("s1");
            assertFalse(r.applied(), "连接必失败");
            assertTrue(r.persisted(), "enabled:true 仍应回写（用户意图）");
            assertNotNull(r.error());
            assertEquals(McpRegistry.Status.FAILED, reg.servers().get(0).status());
            assertTrue(MAPPER.readTree(Files.readString(f))
                    .get("mcpServers").get("s1").get("enabled").asBoolean());
        } finally {
            reg.close();
        }
    }

    @Test
    void disableRemovesToolsAndPersists(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            reg.addConnectedForTest("s1", List.of(fakeTool("mcp__s1__ping")));
            assertEquals(1, reg.activeTools().size());
            assertEquals(McpRegistry.Status.CONNECTED, reg.servers().get(0).status());

            McpRegistry.ToggleResult r = reg.disable("s1");
            assertTrue(r.applied());
            assertTrue(r.persisted());
            assertTrue(reg.activeTools().isEmpty());
            assertEquals(McpRegistry.Status.DISABLED, reg.servers().get(0).status());
            assertFalse(MAPPER.readTree(Files.readString(f))
                    .get("mcpServers").get("s1").get("enabled").asBoolean());
        } finally {
            reg.close();
        }
    }

    @Test
    void unknownNameDegrades() {
        McpRegistry reg = McpRegistry.initForTest(Path.of("."), new ConversationState(), List.of());
        assertFalse(reg.enable("nope").applied());
        assertFalse(reg.disable("nope").applied());
    }

    @Test
    void viewCarriesToolCountAndShortNames(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            reg.addConnectedForTest("s1", List.of(fakeTool("mcp__s1__ping"), fakeTool("mcp__s1__pong")));
            McpRegistry.ServerView v = reg.servers().get(0);
            assertEquals(2, v.toolCount());
            assertEquals(List.of("ping", "pong"), v.toolNames(), "面板列 mcp__ 前缀后的短名");
        } finally {
            reg.close();
        }
    }
}
