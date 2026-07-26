package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
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

    @Test
    void httpConfigProducesTransport() {
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "ctx7", true, Duration.ofSeconds(30), "https://mcp.context7.com/mcp", Map.of());

        Optional<McpClientTransport> t = McpTransportFactory.create(cfg);

        assertTrue(t.isPresent(), "仅构造 transport 对象，不发网络");
    }

    @Test
    void httpConfigWithHeadersProducesTransport() {
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "ctx7", true, Duration.ofSeconds(30), "https://h/mcp",
                Map.of("Authorization", "Bearer tok"));

        assertTrue(McpTransportFactory.create(cfg).isPresent());
    }

    /**
     * 鉴权头是否真的落到出站请求上——<b>这条必须离线测</b>：真机冒烟证明不了它，
     * 实测 Context7 在 initialize 阶段不校验 key，headers 全丢也照样回 200。
     */
    @Test
    void headerCustomizerPutsConfiguredHeadersOnRequest() {
        var customizer = McpTransportFactory.headerCustomizer(
                Map.of("Authorization", "Bearer tok", "X-Trace", "abc"));
        URI uri = URI.create("https://example.com/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        customizer.customize(builder, "POST", uri, null, McpTransportContext.EMPTY);

        HttpRequest request = builder.build();
        assertEquals(Optional.of("Bearer tok"), request.headers().firstValue("Authorization"));
        assertEquals(Optional.of("abc"), request.headers().firstValue("X-Trace"));
    }

    @Test
    void headerCustomizerWithEmptyMapAddsNothing() {
        var customizer = McpTransportFactory.headerCustomizer(Map.of());
        URI uri = URI.create("https://example.com/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        customizer.customize(builder, "POST", uri, null, McpTransportContext.EMPTY);

        assertEquals(0, builder.build().headers().map().size(), "空 headers 不应凭空加头");
    }
}
