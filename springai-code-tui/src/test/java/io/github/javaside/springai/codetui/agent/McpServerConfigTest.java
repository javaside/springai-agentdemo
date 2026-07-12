package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConfigTest {

    @Test
    void stdioConfigHoldsFieldsAndExposesCommonAccessors() {
        McpServerConfig.StdioServerConfig cfg = new McpServerConfig.StdioServerConfig(
                "fs", true, Duration.ofSeconds(20), "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                Map.of("FOO", "bar"));

        McpServerConfig base = cfg;
        assertEquals("fs", base.name());
        assertTrue(base.enabled());
        assertEquals(Duration.ofSeconds(20), base.timeoutMs());

        assertEquals("npx", cfg.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), cfg.args());
        assertEquals(Map.of("FOO", "bar"), cfg.env());
    }
}
