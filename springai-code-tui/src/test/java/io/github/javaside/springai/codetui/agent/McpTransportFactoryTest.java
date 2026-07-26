package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportFactoryTest {

    @Test
    void stdioConfigProducesTransport() {
        McpServerConfig.StdioServerConfig cfg = new McpServerConfig.StdioServerConfig(
                "fs", true, Duration.ofSeconds(20), "echo", List.of("hi"), Map.of());

        Optional<McpClientTransport> t = McpTransportFactory.create(cfg);

        assertTrue(t.isPresent());   // 仅构造 transport 对象，不启动进程
    }

    @Test
    void splitsBaseUriAndEndpoint() {
        URI uri = URI.create("https://mcp.context7.com/mcp");

        assertEquals("https://mcp.context7.com", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp", McpTransportFactory.endpointOf(uri));
    }

    @Test
    void keepsMultiSegmentPathAsEndpoint() {
        URI uri = URI.create("https://h/api/v1/mcp");

        assertEquals("https://h", McpTransportFactory.baseUriOf(uri));
        assertEquals("/api/v1/mcp", McpTransportFactory.endpointOf(uri));
    }

    /** 无路径 / 只有 "/" 时返回 null，表示交给 SDK 的默认 endpoint（/mcp）。 */
    @Test
    void emptyPathYieldsNullEndpoint() {
        assertNull(McpTransportFactory.endpointOf(URI.create("https://h")));
        assertNull(McpTransportFactory.endpointOf(URI.create("https://h/")));
    }

    @Test
    void keepsQueryStringInEndpoint() {
        URI uri = URI.create("https://h/mcp?tenant=acme");

        assertEquals("https://h", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp?tenant=acme", McpTransportFactory.endpointOf(uri));
    }

    @Test
    void keepsPortInBaseUri() {
        URI uri = URI.create("http://127.0.0.1:8080/mcp");

        assertEquals("http://127.0.0.1:8080", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp", McpTransportFactory.endpointOf(uri));
    }
}
