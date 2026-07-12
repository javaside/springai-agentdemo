package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientManagerTest {

    @Test
    void prefixedNameNamespacesAndSanitizes() {
        assertEquals("mcp__chrome_devtools__take_screenshot",
                McpClientManager.prefixedName("chrome-devtools", "take-screenshot"));
    }

    @Test
    void prefixedNameStripsIllegalChars() {
        // 空格/点等非法字符被 strip，'-' 归一为 '_'
        assertEquals("mcp__abc__do_it", McpClientManager.prefixedName("a b.c", "do-it"));
    }

    @Test
    void bogusServerDegradesToEmptyNeverThrows() {
        // command 指向不存在的可执行文件：连接/初始化必失败，但 connectAll 不抛、降级为 0 工具。
        McpServerConfig.StdioServerConfig bogus = new McpServerConfig.StdioServerConfig(
                "bogus", true, Duration.ofSeconds(2),
                "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of());

        McpClientManager mgr = McpClientManager.connectAll(List.of(bogus));
        try {
            assertTrue(mgr.toolCallbacks().isEmpty());
        } finally {
            mgr.close();
        }
    }

    @Test
    void emptyConfigYieldsEmptyManager() {
        McpClientManager mgr = McpClientManager.connectAll(List.of());
        try {
            assertTrue(mgr.toolCallbacks().isEmpty());
        } finally {
            mgr.close();
        }
    }
}
