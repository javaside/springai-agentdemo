package io.github.javaside.springai.codetui.agent.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DeepSeekWebSearchTool 离线单测：JDK 内置 HttpServer 起本地 stub，不发真实网络请求。 */
class DeepSeekWebSearchToolTest {

    /**
     * 两条结果的样例（按官方文档响应结构表造）：第一条字段齐全且带 rerank_score / authority 等
     * 不渲染字段；第二条缺 date 且 summary 为空（供 snippet 回退与日期降级用例）。
     */
    private static final String TWO_RESULTS = """
            {"results":[
              {"rerank_score":2.4,"title":"标题一","url":"https://a.com/1",
               "snippet":"短摘要一","summary":"长摘要一","content":"",
               "site_name":"来源一","site_icon":"https://a.com/icon.png",
               "date":"2026-06-01T00:00:00+00:00","authority":3},
              {"rerank_score":1.8,"title":"标题二","url":"https://b.com/2",
               "snippet":"短摘要二","summary":"","content":"","site_name":"来源二",
               "site_icon":null,"date":null,"authority":-1}
            ]}
            """;

    private static final String ZERO_RESULTS = """
            {"results":[]}
            """;

    /** DeepSeek 错误为 OpenAI 兼容形状（文档：param 恒 null）；402 = 余额不足。 */
    private static final String INSUFFICIENT_BALANCE =
            "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"payment_required\","
                    + "\"param\":null,\"code\":\"insufficient_balance\"}}";

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
            this.server.createContext("/v1/tools/search", exchange -> {
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
    void rendersTitleUrlSiteSummaryAndShortDate() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用");

            assertEquals(1, stub.requests.get(), "应恰好发一次请求");
            assertTrue(out.contains("找到 2 条结果"), "应报告结果条数，实际=" + out);
            assertTrue(out.contains("标题一"), "应含标题，实际=" + out);
            assertTrue(out.contains("https://a.com/1"), "应含 URL，实际=" + out);
            assertTrue(out.contains("长摘要一"), "summary 存在时应渲染长摘要，实际=" + out);
            // 断言整条 meta 行而非只 contains("来源一")：拼装错误（悬空分隔符）只有整行断言抓得住。
            assertTrue(out.contains("标题一 — 来源一 · 2026-06-01"),
                    "站点名与日期应拼成 meta 行（RFC 3339 截到天），实际=" + out);
            assertFalse(out.contains("短摘要一"), "有 summary 时不应回退 snippet，实际=" + out);
            assertFalse(out.contains("rerank_score") && out.contains("2.4"),
                    "质量分是 token 噪音不应渲染，实际=" + out);
        }
    }

    @Test
    void fallsBackToSnippetWhenSummaryMissing() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用");

            assertTrue(out.contains("短摘要二"),
                    "summary 为空串时应回退 snippet（约 100-200 字符的短摘要），实际=" + out);
        }
    }

    @Test
    void omitsMetaWhenDateMissing() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("Spring AI 工具调用");

            assertTrue(out.contains("标题二 — 来源二\n"),
                    "date 为 null 时不应留悬空的 ' · '，实际=" + out);
        }
    }

    /**
     * 实测（2026-10-22 真机）summary 远超文档宣称的 600-800 字符（单条可达 2500+，
     * 且内嵌 markdown 标题）：默认 8 条若不截断，一次搜索可吃掉上万 token 上下文。
     * 摘要的用途是让模型挑 URL，不是搬运正文——正文走 webFetch。
     */
    @Test
    void truncatesOverlongSummaryInRender() throws Exception {
        String longSummary = "字".repeat(1200);
        String response = """
                {"results":[{"title":"长摘要条目","url":"https://a.com/long",
                 "summary":"%s","snippet":"短","site_name":"来源","date":null}]}
                """.formatted(longSummary);
        try (StubServer stub = new StubServer(200, response)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("q");

            assertTrue(out.contains("…"), "超长 summary 应截断加省略号，实际长度=" + out.length());
            assertFalse(out.contains(longSummary), "1200 字符的 summary 不该全文渲染");
            assertTrue(out.length() < 1500,
                    "单条截断后整段输出应远小于原文，实际=" + out.length());
        }
    }

    @Test
    void sendsOnlyQueryAndCount() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("Spring AI 工具调用");

            assertTrue(stub.lastBody.contains("\"query\":\"Spring AI 工具调用\""),
                    "实际=" + stub.lastBody);
            assertTrue(stub.lastBody.contains("\"count\":8"), "默认条数应为 8，实际=" + stub.lastBody);
            assertFalse(stub.lastBody.contains("need_content"),
                    "正文走 webFetch，不该请求网页原文，实际=" + stub.lastBody);
            assertFalse(stub.lastBody.contains("ori_question"), "实际=" + stub.lastBody);
            assertFalse(stub.lastBody.contains("localization"), "实际=" + stub.lastBody);
        }
    }

    @Test
    void resultCountReachesRequestBody() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(20).build();

            tool.webSearch("q");

            assertTrue(stub.lastBody.contains("\"count\":20"), "实际=" + stub.lastBody);
        }
    }

    @Test
    void builderClampsResultCount() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).resultCount(999).build();

            tool.webSearch("q");

            assertTrue(stub.lastBody.contains("\"count\":50"),
                    "Builder 应把越界条数钳到上界 50，实际=" + stub.lastBody);
        }
    }

    @Test
    void sendsBearerAuthorizationHeader() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            tool.webSearch("q");

            assertEquals("Bearer fake-key", stub.lastAuth, "必须带 Bearer 鉴权头");
        }
    }

    @Test
    void blankQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("   ");

            assertEquals(0, stub.requests.get(), "空搜索词不应发出请求");
            assertTrue(out.contains("搜索词为空"), "应返回可读提示，实际=" + out);
        }
    }

    @Test
    void nullQueryShortCircuitsWithoutRequest() throws Exception {
        try (StubServer stub = new StubServer(200, TWO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch(null);

            assertEquals(0, stub.requests.get(), "null 搜索词不应发出请求，也不应 NPE");
            assertTrue(out.contains("搜索词为空"), "实际=" + out);
        }
    }

    @Test
    void zeroResultsReturnsActionableTextWithoutThrowing() throws Exception {
        try (StubServer stub = new StubServer(200, ZERO_RESULTS)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("一个不存在的东西");

            assertTrue(out.contains("没搜到"), "零结果应是正常返回而非异常（且不计费），实际=" + out);
            assertTrue(out.contains("site:"), "应提示可去掉搜索语法限定再试，实际=" + out);
        }
    }

    @Test
    void paymentRequiredErrorCarriesStatusCodeAndBody() throws Exception {
        try (StubServer stub = new StubServer(402, INSUFFICIENT_BALANCE)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("402"), "错误消息须含状态码，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("Insufficient Balance"),
                    "须透传 DeepSeek 原文（OpenAI 兼容错误形状），实际=" + ex.getMessage());
        }
    }

    @Test
    void serverErrorCarriesStatusCode() throws Exception {
        try (StubServer stub = new StubServer(503,
                "{\"error\":{\"message\":\"server busy\",\"type\":\"server_error\",\"param\":null}}")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("DeepSeek 搜索失败：HTTP 503"),
                    "5xx 应走自定义转译（带前缀），实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "5xx 是服务端错误，不能报成连不上，实际=" + ex.getMessage());
        }
    }

    @Test
    void truncatesOverlongErrorBody() throws Exception {
        String longMsg = "x".repeat(500);
        try (StubServer stub = new StubServer(500,
                "{\"error\":{\"message\":\"" + longMsg + "\"}}")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("…"), "超长响应体应截断加省略号，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().length() < 400,
                    "截断后错误消息不应超过 400 字符，实际长度=" + ex.getMessage().length());
        }
    }

    @Test
    void unreachableHostReportsConnectionFailure() {
        DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                .baseUrl("http://127.0.0.1:1").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tool.webSearch("q"));

        assertTrue(ex.getMessage().contains("连不上"),
                "连接失败与服务端错误应措辞可区分，实际=" + ex.getMessage());
    }

    @Test
    void nonJsonResponseIsNotReportedAsConnectionFailure() throws Exception {
        try (StubServer stub = new StubServer(200, "<html><body>portal login</body></html>", "text/html")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("无法解析"),
                    "连接成功但响应非 JSON 应报解析失败，实际=" + ex.getMessage());
            assertFalse(ex.getMessage().contains("连不上"),
                    "连接明明成功了，不能报成连不上，实际=" + ex.getMessage());
        }
    }

    @Test
    void malformedResponseThrowsWithPreview() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"unexpected\":\"shape\"}")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("results"),
                    "响应形状不对时应点明缺什么字段，实际=" + ex.getMessage());
            assertTrue(ex.getMessage().contains("unexpected"),
                    "错误消息应带响应片段便于排查，实际=" + ex.getMessage());
        }
    }

    @Test
    void nonObjectResultElementsThrowReadableError() throws Exception {
        try (StubServer stub = new StubServer(200, "{\"results\":[\"a\",\"b\"]}")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("results"),
                    "应点明是结果数组的形状不对，实际=" + ex.getMessage());
        }
    }

    @Test
    void keepsUsableResultsWhenArrayHasJunkElements() throws Exception {
        String mixed = """
                {"results":[
                  {"title":"合法标题","url":"https://ok.com/1","snippet":"合法片段","site_name":"ok.com"},
                  "junk"
                ]}
                """;
        try (StubServer stub = new StubServer(200, mixed)) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            String out = tool.webSearch("q");

            assertTrue(out.contains("合法标题"), "有可识别结果时不应因个别脏元素整体失败，实际=" + out);
            assertTrue(out.contains("找到 1 条结果"), "脏元素应被丢弃且不计入条数，实际=" + out);
        }
    }

    @Test
    void emptyBodyThrowsInsteadOfNpe() throws Exception {
        try (StubServer stub = new StubServer(204, "")) {
            DeepSeekWebSearchTool tool = DeepSeekWebSearchTool.builder("fake-key")
                    .baseUrl(stub.baseUrl()).build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> tool.webSearch("q"));

            assertTrue(ex.getMessage().contains("响应为空"),
                    "空响应体应抛可读异常而非 NPE，实际=" + ex.getMessage());
        }
    }

    @Test
    void resolveResultCountFallsBackAndClamps() {
        assertEquals(8, DeepSeekWebSearchTool.resolveResultCount(null), "缺失应回退 8");
        assertEquals(8, DeepSeekWebSearchTool.resolveResultCount("  "), "空白应回退 8");
        assertEquals(8, DeepSeekWebSearchTool.resolveResultCount("abc"), "非数字应回退 8");
        assertEquals(1, DeepSeekWebSearchTool.resolveResultCount("0"), "低于下界应钳到 1");
        assertEquals(50, DeepSeekWebSearchTool.resolveResultCount("999"), "高于上界应钳到 50");
        assertEquals(20, DeepSeekWebSearchTool.resolveResultCount(" 20 "), "合法值应生效（允许两侧空白）");
    }
}
