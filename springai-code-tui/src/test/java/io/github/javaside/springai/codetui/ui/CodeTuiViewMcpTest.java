package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.McpRegistry;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /mcp 管理面板：忙时拒绝；无 server 提示；Enter 对已连接项同步禁用、对禁用项后台线程启用；Esc 关闭。
 */
class CodeTuiViewMcpTest {

    private static McpRegistry.ServerView view(String name, McpRegistry.Status st) {
        return new McpRegistry.ServerView(name, McpConfigLoader.ConfigSource.PROJECT, st,
                st == McpRegistry.Status.CONNECTED ? 2 : 0,
                st == McpRegistry.Status.CONNECTED ? List.of("ping", "pong") : List.of(),
                st == McpRegistry.Status.FAILED ? "timeout" : null);
    }

    /** 可编程桩：mcpServers 固定返回、enable/disable 记录调用。 */
    private static final class McpStub implements SubmitHandler {
        volatile List<McpRegistry.ServerView> servers = List.of();
        final AtomicInteger enables = new AtomicInteger();
        final AtomicInteger disables = new AtomicInteger();
        final CountDownLatch enableCalled = new CountDownLatch(1);
        @Override public Disposable submit(String text) { return null; }
        @Override public List<McpRegistry.ServerView> mcpServers() { return servers; }
        @Override public McpRegistry.ToggleResult enableMcp(String name) {
            enables.incrementAndGet(); enableCalled.countDown();
            return new McpRegistry.ToggleResult(true, true, null);
        }
        @Override public McpRegistry.ToggleResult disableMcp(String name) {
            disables.incrementAndGet();
            return new McpRegistry.ToggleResult(true, true, null);
        }
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
    }

    private static void submitMcp(CodeTuiView v) {
        type(v, "/mcp");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    void mcpWhenBusyIsRejected() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertFalse(v.pickingMcpForTest());
        assertEquals("忙碌中，无法管理 MCP", s.notice());
    }

    @Test
    void mcpWithNoServersShowsNotice() {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, new McpStub(), Path.of("."));
        submitMcp(v);
        assertFalse(v.pickingMcpForTest());
        assertEquals("未配置 MCP server（.codetui/mcp.json）", s.notice());
    }

    @Test
    void enterOnConnectedRowDisablesSynchronously() {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertTrue(v.pickingMcpForTest());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(1, h.disables.get());
        assertEquals(0, h.enables.get());
    }

    @Test
    void enterOnDisabledRowEnablesOnBackgroundThread() throws Exception {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.DISABLED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertTrue(v.pickingMcpForTest());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(h.enableCalled.await(3, TimeUnit.SECONDS), "enable 应在后台线程被调用");
    }

    @Test
    void escClosesPanel() {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingMcpForTest());
    }
}
