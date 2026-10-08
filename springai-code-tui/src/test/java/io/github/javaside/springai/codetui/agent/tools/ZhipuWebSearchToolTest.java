package io.github.javaside.springai.codetui.agent.tools;

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

/** ZhipuWebSearchTool 离线单测：JDK 内置 HttpServer 起本地 stub，不发真实网络请求。 */
class ZhipuWebSearchToolTest {

    /** 两条结果的样例（按官方文档 search_result 形状）：第一条字段齐全；第二条缺 publish_date（供降级用例）。 */
    private static final String TWO_RESULTS = """
            {"created":1748261757,"id":"cs-abc","request_id":"req-1",
             "search_result":[
               {"title":"标题一","link":"https://a.com/1","content":"摘要一",
                "media":"来源一","icon":"https://a.com/icon.jpg","publish_date":"2026-01-30","refer":"ref_1"},
               {"title":"标题二","link":"https://b.com/2","content":"摘要二","media":"来源二"}
             ]}
            """;

    private static final String ZERO_RESULTS = """
            {"search_result":[]}
            """;

    /** 智谱真实错误形状（2026-10-08 实测：余额不足为 HTTP 429 + code 1113）。 */
    private static final String QUOTA_ERROR =
            "{\"error\":{\"code\":\"1113\",\"message\":\"余额不足或无可用资源包,请充值。\"}}";

    /** 本地 stub server：固定返回给定状态码与响应体，记录请求数与最后一次请求体/鉴权头。 */
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
            this.server.createContext("/api/paas/v4/web_search", exchange -> {
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
    void rendersTitleUrlContentMediaAndShortDate() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用", null, null);

            assertEquals(1, stub.requests.get(), "应恰好发一次请求");
            assertTrue(out.contains("找到 2 条结果"), "应报告结果条数，实际=" + out);
            assertTrue(out.contains("标题一"), "应含标题，实际=" + out);
            assertTrue(out.contains("https://a.com/1"), "应含 URL，实际=" + out);
            assertTrue(out.contains("摘要一"), "应含摘要，实际=" + out);
            // 断言整条 meta 行而非只 contains("来源一")：拼装错误（悬空分隔符）只有整行断言抓得住。
            assertTrue(out.contains("标题一 — 来源一 · 2026-01-30"),
                    "来源名与日期应拼成 meta 行，实际=" + out);
        }
    }

    @Test
    void omitsDateSegmentWhenDateMissing() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用", null, null);

            assertTrue(out.contains("标题二 — 来源二\n"),
                    "第二条无日期，来源名后不应留悬空的 ' · '，实际=" + out);
        }
    }

    @Test
    void sendsEngineQueryCountAndDefaultFreshness() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("Spring AI 工具调用", null, null);

            assertTrue(stub.lastBody.contains("\"search_engine\":\"search_std\""),
                    "引擎默认应为 search_std（REST 必填），实际=" + stub.lastBody);
            assertTrue(stub.lastBody.contains("\"count\":8"), "默认条数应为 8，实际=" + stub.lastBody);
            assertTrue(stub.lastBody.contains("\"search_recency_filter\":\"noLimit\""),
                    "freshness 缺省应为 noLimit，实际=" + stub.lastBody);
        }
    }

    @Test
    void passesThroughExplicitFreshness() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("最新消息", "oneDay", List.of());

            assertTrue(stub.lastBody.contains("\"search_recency_filter\":\"oneDay\""),
                    "显式 freshness 应原样透传（不做本地白名单），实际=" + stub.lastBody);
        }
    }

    @Test
    void resultCountReachesRequestBody() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(20).build();

            tool.webSearch("q", null, null);

            assertTrue(stub.lastBody.contains("\"count\":20"), "实际=" + stub.lastBody);
        }
    }

    @Test
    void builderClampsResultCount() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(999).build();

            tool.webSearch("q", null, null);

            assertTrue(stub.lastBody.contains("\"count\":50"),
                    "Builder 应把越界条数钳到上界 50，实际=" + stub.lastBody);
        }
    }

    @Test
    void builderFallsBackIllegalEngine() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).searchEngine("search_ultra").build();

            tool.webSearch("q", null, null);

            assertTrue(stub.lastBody.contains("\"search_engine\":\"search_std\""),
                    "引擎是计费档位，白名单外的值必须回退 search_std，实际=" + stub.lastBody);
        }
    }

    @Test
    void sendsBearerAuthorizationHeader() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, null);

            assertEquals("Bearer fake-key", stub.lastAuth, "必须带 Bearer 鉴权头");
        }
    }

    @Test
    void joinsIncludeDomainsWithComma() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, List.of("docs.spring.io", " github.com "));

            assertTrue(stub.lastBody.contains("\"search_domain_filter\":\"docs.spring.io,github.com\""),
                    "域名应用逗号连接并去掉两侧空白，实际=" + stub.lastBody);
        }
    }

    @Test
    void omitsIncludeWhenEmpty() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q", null, List.of());

            assertFalse(stub.lastBody.contains("search_domain_filter"),
                    "空域名列表不应带 search_domain_filter 字段，实际=" + stub.lastBody);
        }
    }

    @Test
    void blankQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("   ", null, null);

            assertEquals(0, stub.requests.get(), "空搜索词不应发出请求（省额度）");
            assertTrue(out.contains("搜索词为空"), "应返回可读提示，实际=" + out);
        }
    }

    @Test
    void nullQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch(null, null, null);

            assertEquals(0, stub.requests.get(), "null 搜索词不应发出请求，也不应 NPE");
            assertTrue(out.contains("搜索词为空"), "实际=" + out);
        }
    }

    @Test
    void zeroResultsReturnsActionableTextWithoutThrowing() throws Exception {
        try (StubServer stub = new StubServer(200, ZERO_RESULTS)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("一个不存在的东西", null, null);

            assertTrue(out.contains("没搜到"), "零结果应是正常返回而非异常，实际=" + out);
            assertTrue(out.contains("freshness"), "应提示可去掉时间限制重试，实际=" + out);
        }
    }

    @Test
    void quotaErrorCarriesStatusCodeAndBody() throws Exception {
        try (StubServer stub = new StubServer(429, QUOTA_ERROR)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("429"), "错误消息须含状态码，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("余额不足"),
                    "须透传智谱原文（429 可能是限流也可能是余额，不透传分不清），实际=" + ex.getMessage());
        }
    }

    @Test
    void serverErrorCarriesStatusCode() throws Exception {
        try (StubServer stub = new StubServer(503, "{\"error\":{\"code\":\"1302\",\"message\":\"server busy\"}}")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("智谱搜索失败：HTTP 503"),
                    "5xx 应走自定义转译（带前缀），实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "5xx 是服务端错误，不能报成连不上，实际=" + ex.getMessage());
        }
    }

    @Test
    void truncatesOverlongErrorBody() throws Exception {
        String longMsg = "x".repeat(500);
        try (StubServer stub = new StubServer(500, "{\"error\":{\"message\":\"" + longMsg + "\"}}")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("…"), "超长响应体应截断加省略号，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().length() < 400,
                    "截断后错误消息不应超过 400 字符，实际长度=" + ex.getMessage().length());
        }
    }

    @Test
    void unreachableHostReportsConnectionFailure() {
        ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                .baseUrl("http://127.0.0.1:1").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tool.webSearch("q", null, null));

        assertTrue(ex.getMessage().contains("连不上"),
                "连接失败与服务端错误应措辞可区分，实际=" + ex.getMessage());
    }

    @Test
    void nonJsonResponseIsNotReportedAsConnectionFailure() throws Exception {
        try (StubServer stub = new StubServer(200, "<html><body>portal login</body></html>", "text/html")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("无法解析"),
                    "连接成功但响应非 JSON 应报解析失败，实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "连接明明成功了，不能报成连不上，实际=" + ex.getMessage());
        }
    }

    @Test
    void malformedResponseThrowsWithPreview() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"unexpected\":\"shape\"}")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("search_result"),
                    "响应形状不对时应点明缺什么字段，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("unexpected"),
                    "错误消息应带响应片段便于排查，实际=" + ex.getMessage());
        }
    }

    @Test
    void nonObjectResultElementsThrowReadableError() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"search_result\":[\"a\",\"b\"]}")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("search_result"),
                    "应点明是结果数组的形状不对，实际=" + ex.getMessage());
        }
    }

    @Test
    void keepsUsableResultsWhenArrayHasJunkElements() throws Exception {
        String mixed = """
                {"search_result":[
                  {"title":"合法标题","link":"https://ok.com/1","content":"合法片段","media":"ok.com"},
                  "junk"
                ]}
                """;
        try (StubServer stub = new StubServer(200, mixed)) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("q", null, null);

            assertTrue(out.contains("合法标题"), "有可识别结果时不应因个别脏元素整体失败，实际=" + out);
            assertTrue(out.contains("找到 1 条结果"), "脏元素应被丢弃且不计入条数，实际=" + out);
        }
    }

    @Test
    void emptyBodyThrowsInsteadOfNpe() throws Exception {
        try (StubServer stub = new StubServer(204, "")) {
            ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q", null, null));

            assertTrue(ex.getMessage().contains("响应为空"),
                    "空响应体应抛可读异常而非 NPE，实际=" + ex.getMessage());
        }
    }

    @Test
    void resolveResultCountFallsBackAndClamps() {
        assertEquals(8, ZhipuWebSearchTool.resolveResultCount(null), "缺失应回退 8");
        assertEquals(8, ZhipuWebSearchTool.resolveResultCount("  "), "空白应回退 8");
        assertEquals(8, ZhipuWebSearchTool.resolveResultCount("abc"), "非数字应回退 8");
        assertEquals(1, ZhipuWebSearchTool.resolveResultCount("0"), "低于下界应钳到 1");
        assertEquals(50, ZhipuWebSearchTool.resolveResultCount("999"), "高于上界应钳到 50");
        assertEquals(20, ZhipuWebSearchTool.resolveResultCount(" 20 "), "合法值应生效（允许两侧空白）");
    }

    @Test
    void resolveSearchEngineWhitelist() {
        assertEquals("search_std", ZhipuWebSearchTool.resolveSearchEngine(null), "缺失应回退 std");
        assertEquals("search_std", ZhipuWebSearchTool.resolveSearchEngine(" "), "空白应回退 std");
        assertEquals("search_std", ZhipuWebSearchTool.resolveSearchEngine("bogus"), "白名单外应回退 std");
        assertEquals("search_pro", ZhipuWebSearchTool.resolveSearchEngine(" search_pro "),
                "四枚举值应原样生效（允许两侧空白）");
        assertEquals("search_pro_sogou", ZhipuWebSearchTool.resolveSearchEngine("search_pro_sogou"));
        assertEquals("search_pro_quark", ZhipuWebSearchTool.resolveSearchEngine("search_pro_quark"));
    }
}
