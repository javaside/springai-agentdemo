package io.github.javaside.springai.codetui.agent.mcp;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.tools.PermissionCallback;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    /** 只记录工具事件的 listener（其余方法空实现）。 */
    private static final class RecordingListener implements AgentListener {
        final List<String> events = new ArrayList<>();
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) {
            events.add("start:" + toolName);
        }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {
            events.add("finish:" + toolName + ":" + ok);
        }
        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }
    }

    /**
     * 装饰链自外向内：{@code PermissionCallback → ToolEventCallback → 媒体外置 → 真实工具}。
     *
     * <p>MCP 工具在登记表里是 UNKNOWN（兜底 ASK），故这里显式给一条 allow 规则——
     * 否则本用例测的就变成「被拒时不发工具事件」了（那是 PermissionCallback 自己的用例）。
     */
    @Test
    void decorateWrapsWithPermissionCallbackAndFiresListenerEvents(@TempDir Path root) {
        RecordingListener listener = new RecordingListener();
        PermissionEngine engine = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT,
                        List.of(new PermissionRule("mcp__s1__ping", null,
                                PermissionBehavior.ALLOW, RuleScope.SESSION))),
                PermissionMode.DEFAULT);
        McpRegistry reg = McpRegistry.initForTest(root, listener, List.of(), engine);
        try {
            ToolCallback decorated = reg.decorate(fakeTool("mcp__s1__ping"));
            assertInstanceOf(PermissionCallback.class, decorated,
                    "最外层须是 PermissionCallback（被拒的调用不该先在 TUI 显示成「工具开始运行」）");
            assertEquals("ok", decorated.call("{}"), "调用应穿透装饰链回到原工具");
            assertEquals(List.of("start:mcp__s1__ping", "finish:mcp__s1__ping:true"), listener.events,
                    "装饰后调用应发工具开始/结束事件");
        } finally {
            reg.close();
        }
    }

    @Test
    void shortNameStripsOnlyOwnServerPrefix(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            reg.addConnectedForTest("s1", List.of(fakeTool("mcp__s1__do__thing"), fakeTool("unprefixed")));
            assertEquals(List.of("do__thing", "unprefixed"), reg.servers().get(0).toolNames(),
                    "含 __ 的工具名不被过度剥离；无前缀原样");
        } finally {
            reg.close();
        }
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
