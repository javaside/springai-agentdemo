package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Brave API 冒烟。绑 {@code BRAVE_API_KEY}：有 key 则 {@code mvn test} 自动跑，无 key 优雅跳过
 * （门控模式同 {@link CodingAgentSpikeTest}）。
 *
 * <p><b>这条测试比一般冒烟更重要</b>：库版 {@code BraveWebSearchTool} 的 baseUrl 是硬编码常量，
 * 无法指向本地 stub，所以它的响应解析与错误处理<b>没有任何离线用例</b>——正确性全靠这条。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")   // 默认不跑：联网、花钱、且墙钟断言天生不稳
@EnabledIfEnvironmentVariable(named = "BRAVE_API_KEY", matches = ".+")
class BraveWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool(System.getenv("BRAVE_API_KEY"), "3");
        assertNotNull(brave, "配了 key 就应创建成功");
        assertEquals("BraveWebSearch", brave.getToolDefinition().name());

        String out = brave.call("{\"query\":\"Spring AI framework documentation 2026\"}");

        System.out.println("[smoke] Brave 搜索返回：\n" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }
}
