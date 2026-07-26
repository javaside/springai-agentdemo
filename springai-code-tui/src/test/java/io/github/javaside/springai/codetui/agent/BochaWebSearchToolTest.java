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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 博查在部分版本里把 SearchResponse 包在 data 字段下，解析须兼容两种形状。 */
    private static final String WRAPPED_IN_DATA = """
            {"code":200,"log_id":"abc","msg":null,
             "data":{"_type":"SearchResponse","webPages":{"value":[
               {"name":"包装标题","url":"https://c.com/3","snippet":"包装片段","siteName":"c.com"}
             ]}}}
            """;

    /** 本地 stub server：固定返回给定状态码与响应体，并记录收到的请求数与最后一次请求体。 */
    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        volatile String lastBody = "";
        volatile String lastAuth = "";

        StubServer(int status, String responseJson) throws IOException {
            this(status, responseJson, "application/json");
        }

        StubServer(int status, String responseBody, String contentType) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/v1/web-search", exchange -> {
                requests.incrementAndGet();
                lastAuth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
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
            assertFalse(out.contains("短片段一"),
                    "第一条有 summary，不应同时输出它的 snippet（fallback 必须是排他的），实际=" + out);
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
    void builderClampsResultCount() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(999).build();

            tool.webSearch("q", null, null);

            assertTrue(stub.lastBody.contains("\"count\":50"),
                    "Builder 应把越界条数钳到上界 50，实际=" + stub.lastBody);
        }
    }

    @Test
    void sendsBearerAuthorizationHeader() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, null);

            assertEquals("Bearer fake-key", stub.lastAuth, "必须带 Bearer 鉴权头，否则博查一律 401");
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

    @Test
    void clientErrorCarriesStatusCodeAndBody() throws Exception {
        try (StubServer stub = new StubServer(401, "{\"code\":401,\"msg\":\"invalid api key\"}")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("bad-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("401"), "错误消息须含状态码，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("invalid api key"),
                    "错误消息须透传博查原文（否则分不清 key 无效/余额不足/限流），实际=" + ex.getMessage());
        }
    }

    @Test
    void serverErrorCarriesStatusCode() throws Exception {
        try (StubServer stub = new StubServer(503, "{\"msg\":\"service unavailable\"}")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("博查搜索失败：HTTP 503"),
                    "5xx 应走自定义转译（带前缀），实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "5xx 是服务端错误，不能报成连不上，实际=" + ex.getMessage());
        }
    }

    @Test
    void truncatesOverlongErrorBody() throws Exception {
        String longMsg = "x".repeat(500);
        try (StubServer stub = new StubServer(500, "{\"msg\":\"" + longMsg + "\"}")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("…"), "超长响应体应被截断并加省略号，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().length() < 400,
                    "截断后错误消息不应超过 400 字符，实际长度=" + ex.getMessage().length());
        }
    }

    @Test
    void unreachableHostReportsConnectionFailure() {
        // 127.0.0.1:1 上没有监听者，连接立即被拒。
        BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                .baseUrl("http://127.0.0.1:1").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tool.webSearch("q", null, null));

        assertTrue(ex.getMessage().contains("连不上"),
                "连接失败与服务端错误应措辞可区分，实际=" + ex.getMessage());
    }

    @Test
    void nonJsonResponseIsNotReportedAsConnectionFailure() throws Exception {
        try (StubServer stub = new StubServer(200, "<html><body>portal login</body></html>", "text/html")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("无法解析"),
                    "连接成功但响应非 JSON，应报解析失败，实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "连接明明成功了，不能报成连不上（会把排查方向带偏），实际=" + ex.getMessage());
        }
    }

    @Test
    void parsesResponseWrappedInDataField() throws Exception {
        try (StubServer stub = new StubServer(200, WRAPPED_IN_DATA)) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("q", null, null);

            assertTrue(out.contains("包装标题"), "应兼容 data 包装的响应形状，实际=" + out);
            assertTrue(out.contains("https://c.com/3"), "实际=" + out);
        }
    }

    @Test
    void malformedResponseThrowsWithPreview() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"unexpected\":\"shape\"}")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("webPages"),
                    "响应形状不对时应点明缺什么字段，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("unexpected"),
                    "错误消息应带响应片段便于排查，实际=" + ex.getMessage());
        }
    }

    @Test
    void nonObjectResultElementsThrowReadableError() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"webPages\":{\"value\":[\"a\",\"b\"]}}")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("webPages.value"),
                    "应点明是结果数组的形状不对，实际=" + ex.getMessage());
        }
    }

    @Test
    void emptyBodyThrowsInsteadOfNpe() throws Exception {
        try (StubServer stub = new StubServer(204, "")) {
            BochaWebSearchTool tool = BochaWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("响应为空"),
                    "空响应体应抛可读异常而非 NPE，实际=" + ex.getMessage());
        }
    }
}
