package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Streamable HTTP 端到端冒烟：走完整生产路径（transport → McpSyncClient → initialize → tools/list）。
 *
 * <p>门控 {@code CODETUI_MCP_SMOKE_URL}：设了才跑并连该地址，不设则跳过。用专门的开关而非绑某个
 * API key，是因为验证目标（Context7）不需要鉴权，没有天然可绑的变量；这样也便于指向别的 server。
 * 若同时设了 {@code CONTEXT7_API_KEY}，会带上 Authorization 头，顺带走一遍鉴权头路径。
 *
 * <p><b>本测试不验证鉴权。</b>实测 Context7 在 initialize 阶段不校验 API key（故意传错误 key 仍回
 * 200 与完整 serverInfo），所以 headers 全部丢失它照样绿。鉴权头是否真的发出去，由
 * {@code McpTransportFactoryTest.headerCustomizerPutsConfiguredHeadersOnRequest} 负责。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_MCP_SMOKE_URL", matches = ".+")
class McpStreamableHttpSmokeTest {

    @Test
    void connectsHandshakesAndListsTools() {
        Map<String, String> headers = new LinkedHashMap<>();
        String key = System.getenv("CONTEXT7_API_KEY");
        if (key != null && !key.isBlank()) {
            headers.put("Authorization", "Bearer " + key);
        }
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "smoke", true, Duration.ofSeconds(30),
                System.getenv("CODETUI_MCP_SMOKE_URL"), Map.copyOf(headers));

        McpClientManager manager = McpClientManager.connectAll(List.of(cfg));
        try {
            List<ToolCallback> tools = manager.toolCallbacks();
            System.out.println("[smoke] 拉到 " + tools.size() + " 个工具："
                    + tools.stream().map(t -> t.getToolDefinition().name()).toList());
            assertFalse(tools.isEmpty(),
                    "应能通过 Streamable HTTP 完成握手并拉到工具列表；空列表说明连接或发现失败");
        } finally {
            manager.close();
        }
    }
}
