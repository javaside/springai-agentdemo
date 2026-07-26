package io.github.javaside.springai.codetui.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BochaWebSearchTool 离线单测：用 JDK 内置 HttpServer 起本地 stub，不发真实网络请求。 */
class BochaWebSearchToolTest {

    /** 两条结果的样例响应：第一条字段齐全；第二条缺 summary 与 datePublished（供降级用例复用）。 */
    private static final String TWO_RESULTS = """
            {"_type":"SearchResponse",
             "queryContext":{"originalQuery":"Spring AI 工具调用"},
             "webPages":{"totalEstimatedMatches":2,"value":[
               {"name":"标题一","url":"https://a.com/1","snippet":"短片段一","summary":"长摘要一",
                "siteName":"a.com","datePublished":"2026-01-30T07:19:14+08:00"},
               {"name":"标题二","url":"https://b.com/2","snippet":"短片段二","siteName":"b.com"}
             ]}}
            """;

    /** 本地 stub server：固定返回给定状态码与响应体，并记录收到的请求数与最后一次请求体。 */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        volatile String lastBody = "";

        StubServer(int status, String responseJson) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/web-search", exchange -> {
                requests.incrementAndGet();
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            this.server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    @Test
    void rendersTitleUrlSummarySiteAndShortDate() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用", null, null);

            assertEquals(1, stub.requests.get(), "应恰好发一次请求");
            assertTrue(out.contains("找到 2 条结果"), "应报告结果条数，实际=" + out);
            assertTrue(out.contains("标题一"), "应含标题，实际=" + out);
            assertTrue(out.contains("https://a.com/1"), "应含 URL，实际=" + out);
            assertTrue(out.contains("长摘要一"), "应含长摘要，实际=" + out);
            // 断言整条 meta 行，而不是只 contains("a.com")——后者会被 URL 里的 a.com 蒙混过关。
            assertTrue(out.contains("标题一 — a.com · 2026-01-30"),
                    "站点名与截到天的日期应拼成 meta 行，实际=" + out);
            assertTrue(!out.contains("07:19:14"), "日期不应保留时分秒，实际=" + out);
        }
    }

    @Test
    void sendsSummaryTrueAndDefaultFreshnessAndCount() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("Spring AI 工具调用", null, null);

            assertTrue(stub.lastBody.contains("\"summary\":true"), "summary 应恒为 true，实际=" + stub.lastBody);
            assertTrue(stub.lastBody.contains("\"freshness\":\"noLimit\""),
                    "freshness 缺省应为 noLimit，实际=" + stub.lastBody);
            assertTrue(stub.lastBody.contains("\"count\":8"), "默认条数应为 8，实际=" + stub.lastBody);
        }
    }

    @Test
    void passesThroughExplicitFreshness() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("最新消息", "oneDay", List.of());

            assertTrue(stub.lastBody.contains("\"freshness\":\"oneDay\""),
                    "显式 freshness 应原样透传（不做本地白名单校验），实际=" + stub.lastBody);
        }
    }
}
