package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportFactoryTest {

    @Test
    void stdioConfigProducesTransport() {
        McpServerConfig.StdioServerConfig cfg = new McpServerConfig.StdioServerConfig(
                "fs", true, Duration.ofSeconds(20), "echo", List.of("hi"), Map.of());

        Optional<McpClientTransport> t = McpTransportFactory.create(cfg);

        assertTrue(t.isPresent());   // 仅构造 transport 对象，不启动进程
    }
}
