package io.github.javaside.springai.codetui.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static final String ZERO_RESULTS = """
            {"_type":"SearchResponse","webPages":{"totalEstimatedMatches":0,"value":[]}}
            """;

    /** 只有标题与 URL 的极简结果：siteName 与 datePublished 都缺，用于覆盖 meta 整段省略的分支。 */
    private static final String BARE_RESULT = """
            {"_type":"SearchResponse","webPages":{"value":[
              {"name":"极简标题","url":"https://bare.com/x","snippet":"极简片段"}
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

    @Test
    void fallsBackToSnippetWhenSummaryMissing() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用", null, null);

            assertTrue(out.contains("短片段二"),
                    "第二条无 summary，应退回 snippet，实际=" + out);
        }
    }

    @Test
    void omitsDateSegmentWhenDateMissing() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用", null, null);

            assertTrue(out.contains("标题二 — b.com\n"),
                    "第二条无日期，站点名后不应留下悬空的 ' · '，实际=" + out);
        }
    }

    @Test
    void resolveResultCountFallsBackAndClamps() {
        assertEquals(8, BochaWebSearchTool.resolveResultCount(null), "缺失应回退 8");
        assertEquals(8, BochaWebSearchTool.resolveResultCount("  "), "空白应回退 8");
        assertEquals(8, BochaWebSearchTool.resolveResultCount("abc"), "非数字应回退 8");
        assertEquals(1, BochaWebSearchTool.resolveResultCount("0"), "低于下界应钳到 1");
        assertEquals(50, BochaWebSearchTool.resolveResultCount("999"), "高于上界应钳到 50");
        assertEquals(20, BochaWebSearchTool.resolveResultCount(" 20 "), "合法值应生效（允许两侧空白）");
    }

    @Test
    void resultCountReachesRequestBody() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(20).build();

            tool.webSearch("q", null, null);

            assertTrue(stub.lastBody.contains("\"count\":20"), "实际=" + stub.lastBody);
        }
    }

    @Test
    void joinsIncludeDomainsWithPipe() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, List.of("docs.spring.io", " github.com "));

            assertTrue(stub.lastBody.contains("\"include\":\"docs.spring.io|github.com\""),
                    "域名应用 | 连接并去掉两侧空白，实际=" + stub.lastBody);
        }
    }

    @Test
    void omitsIncludeWhenEmpty() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, List.of());

            assertFalse(stub.lastBody.contains("include"), "空域名列表不应带 include 字段，实际=" + stub.lastBody);
        }
    }

    @Test
    void truncatesIncludeDomainsAt100() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add("d" + i + ".com");
        }

        String joined = BochaWebSearchTool.joinInclude(many);

        assertEquals(100, joined.split("\\|").length, "超过 100 个域名应截断取前 100");
        assertTrue(joined.startsWith("d0.com|"), "应保留前 100 个，实际开头=" + joined.substring(0, 20));
        assertFalse(joined.contains("d100.com"), "第 101 个及之后应被丢弃");
    }

    @Test
    void blankQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("   ", null, null);

            assertEquals(0, stub.requests.get(), "空搜索词不应发出请求（省额度）");
            assertTrue(out.contains("搜索词为空"), "应返回可读提示，实际=" + out);
        }
    }

    @Test
    void nullQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch(null, null, null);

            assertEquals(0, stub.requests.get(), "null 搜索词不应发出请求，也不应 NPE");
            assertTrue(out.contains("搜索词为空"), "实际=" + out);
        }
    }

    @Test
    void zeroResultsReturnsActionableTextWithoutThrowing() throws Exception {
        try (StubServer stub = new StubServer(200, ZERO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("一个不存在的东西", null, null);

            assertTrue(out.contains("没搜到"), "零结果应是正常返回而非异常，实际=" + out);
            assertTrue(out.contains("freshness"), "应提示可去掉时间限制重试，实际=" + out);
        }
    }

    @Test
    void omitsWholeMetaSegmentWhenSiteAndDateMissing() throws Exception {
        try (StubServer stub = new StubServer(200, BARE_RESULT)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("q", null, null);

            assertTrue(out.contains("1. 极简标题\n"),
                    "站点与日期都缺时，标题后不应出现悬空的 ' — '，实际=" + out);
            assertTrue(out.contains("https://bare.com/x"), "URL 仍应输出，实际=" + out);
            assertTrue(out.contains("极简片段"), "snippet 仍应作为正文输出，实际=" + out);
        }
    }
}
