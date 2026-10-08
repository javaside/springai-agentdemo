# 智谱联网搜索（ZhipuWebSearch）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 接第三家搜索后端——智谱 BigModel Web Search API，与博查/Brave 三家共存，按 `ZHIPU_API_KEY` 门控注册。

**Architecture:** 自研 `ZhipuWebSearchTool`（`RestClient` + JDK HttpClient，形状照 `BochaWebSearchTool` 先例）；`AgentTools.build` 门控注册进既有装饰链；系统提示指引段 `webSearchGuide` 从两布尔四态硬编码重构为三布尔条目拼装。

**Tech Stack:** Java 17（语言级锁 17，禁用更高版本语法）、Spring AI 2.0 `@Tool` 注解、JUnit 5 + JDK 内置 HttpServer 做 stub。

**Spec:** `docs/superpowers/specs/2026-10-08-zhipu-web-search-design.md`（含实测事实与已知取舍，执行者必读）。

## Global Constraints

- 测试命令必须模块作用域 + `-am`：`mvn -pl springai-code-tui -am test`；单类跑加 `-Dsurefire.failIfNoSpecifiedTests=false`。整仓 `mvn test` 禁用。
- 语言级 17（`maven.compiler.release=17`）。
- 注释写「为什么」不写「做了什么」；中文注释、中文提交正文。
- `webSearchGuide` 正文**不得含花括号**（StringTemplate param 值注入会炸）。
- 错误消息**不塌**：状态码 + 响应原文必须透传；**不重试**。
- 不在测试/文档里硬编码任何真实 API key。
- 提交信息 `type: 说明` 前缀，一次提交只做一件事，功能与测试同提交。
- 分支：`feature/zhipu-web-search`（已建好），不直接提交 main。

---

### Task 1: `ZhipuWebSearchTool` 工具类 + 离线单测

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchToolTest.java`

**Interfaces:**
- Consumes: 无（纯新增；参考同包 `BochaWebSearchTool.java` 的既有形状）。
- Produces: `public static ZhipuWebSearchTool.Builder builder(String apiKey)`；`Builder.baseUrl(String)/resultCount(int)/searchEngine(String)/build()`；`public String webSearch(String query, String freshness, List<String> include)`；`public static int resolveResultCount(String raw)`（默认 8，钳 [1,50]）；`public static String resolveSearchEngine(String raw)`（白名单四值，非法回退 `search_std`）；`static String joinInclude(List<String>)`（逗号连接）。注册名 `ZhipuWebSearch`。

- [ ] **Step 1: 写失败测试**

`ZhipuWebSearchToolTest.java` 全文（照 `BochaWebSearchToolTest` 的 StubServer 模式；stub 路径改为 `/api/paas/v4/web_search`；样例响应按官方文档的 `search_result[]` 形状造）：

```java
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
```

- [ ] **Step 2: 跑测试确认红（编译失败：类不存在）**

```bash
mvn -pl springai-code-tui -am test -Dtest='ZhipuWebSearchToolTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译错误 `cannot find symbol: class ZhipuWebSearchTool`。

- [ ] **Step 3: 写实现**

`ZhipuWebSearchTool.java` 全文：

```java
package io.github.javaside.springai.codetui.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智谱（BigModel）Web Search API 工具 —— 给模型提供联网搜索能力，第三家搜索后端（与博查/Brave 共存）。
 *
 * <p><b>为何不用官方 Java SDK（ai.z.openapi:zai-sdk）</b>：为一个 POST 引整包 SDK + 传递依赖，
 * 错误转译、超时、代理、离线 stub 测试全部失控。博查先例（手写 RestClient）已验证这条路可控。
 *
 * <p><b>200 响应形状未经真机实测</b>（2026-10-08 账户余额不足），解析按官方 API 参考实现；
 * 真机形状若有出入以冒烟为准回填 spec「已知取舍」。
 *
 * <p><b>超时不接 LlmTimeouts</b>：搜索是一次性 REST 调用，超 20s 就该失败（与博查同一决策）。
 */
public final class ZhipuWebSearchTool {

    /** 智谱开放平台默认端点；{@code baseUrl(..)} 仅供测试打本地 stub server，不暴露 env。 */
    static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn";

    private static final String SEARCH_PATH = "/api/paas/v4/web_search";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** 默认返回条数；智谱侧 count 允许 1–50。对齐博查默认：同为国内向长摘要源。 */
    static final int DEFAULT_COUNT = 8;
    static final int MAX_COUNT = 50;

    /**
     * 引擎白名单。REST 侧 search_engine 必填，且四档计费差 5 倍（std 0.01 → sogou/quark 0.05 元/次）——
     * 与 freshness「原样透传」哲学不同：freshness 写错只是搜索失败，引擎写错可能默默选错计费档，
     * 计费项必须本地拦。
     */
    private static final Set<String> SEARCH_ENGINES =
            Set.of("search_std", "search_pro", "search_pro_sogou", "search_pro_quark");
    static final String DEFAULT_SEARCH_ENGINE = "search_std";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final int resultCount;
    private final String searchEngine;

    private ZhipuWebSearchTool(String apiKey, String baseUrl, int resultCount, String searchEngine) {
        // JdkClientHttpRequestFactory + 显式 ProxySelector：与博查同款。HttpURLConnection 在
        // 「流式请求体 + 401」下会丢 error stream（401 = key 无效恰是最需要看到原文的一档）。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.resultCount = resultCount;
        this.searchEngine = searchEngine;
    }

    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    @Tool(name = "ZhipuWebSearch", description = """
            用智谱（BigModel）搜索引擎搜索互联网，返回网页标题、网址、摘要、来源名与发布时间。

            用法：
            - 主打中文内容与国内站点；英文技术文档、英文新闻也可用。
            - 它只返回摘要。需要网页原文细节时，把结果里的网址交给 webFetch 工具去抓取。
            - freshness 一般不要传：默认不限时间效果最好，硬指时间范围反而容易搜空。
              只有明确需要「最近一天/一周」的最新消息时才传。
            - include 与 freshness 不要同时传：智谱侧规定两者同时生效时结果条数限制会失效。
            - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
            """)
    public String webSearch(
            @ToolParam(description = "搜索词。用具体的关键词；搜索最新资料时可在词里带上年份。")
            String query,
            @ToolParam(required = false, description =
                    "时间范围，可选。默认 noLimit（不限，推荐）。可填 oneDay / oneWeek / oneMonth / oneYear。")
            String freshness,
            @ToolParam(required = false, description =
                    "只在这些域名内搜索，可选。例如 [\"docs.spring.io\", \"github.com\"]。" +
                    "注意：与 freshness 同时传时结果条数限制会失效，一般别同时传。")
            List<String> include) {

        if (query == null || query.isBlank()) {
            return "搜索词为空，请给出要搜索的内容。";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("search_engine", searchEngine);
        body.put("search_query", query.trim());
        body.put("count", resultCount);
        body.put("search_recency_filter",
                (freshness == null || freshness.isBlank()) ? "noLimit" : freshness.trim());

        String includeParam = joinInclude(include);
        if (!includeParam.isEmpty()) {
            body.put("search_domain_filter", includeParam);
        }

        Map<String, Object> response = execute(body);

        List<Map<String, Object>> values = extractResults(response);
        if (values.isEmpty()) {
            return "没搜到「" + query.trim() + "」的相关结果。建议换一组关键词或同义词，"
                    + "或去掉 freshness 时间限制再试一次。";
        }
        return render(query.trim(), values);
    }

    /**
     * 发请求并转译错误。<b>不重试</b>：失败绝大多数是 key / 余额 / 限流问题，重试只烧钱并拖长回合。
     * <b>不塌错误</b>：智谱把余额不足映射为 429（博查是 403），只认状态码必错，body 原文必须透传。
     */
    private Map<String, Object> execute(Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(SEARCH_PATH)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String detail = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                        throw new IllegalStateException("智谱搜索失败：HTTP "
                                + response.getStatusCode().value()
                                + (detail.isEmpty() ? "" : "，响应：" + preview(detail)));
                    })
                    .body(MAP_TYPE);
        } catch (IllegalStateException e) {
            throw e;                       // onStatus 里已转译过，原样抛出
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("智谱搜索连不上（网络不通或超时）：" + e.getMessage(), e);
        } catch (RestClientException e) {
            // 连接成功但响应无法解析：常见于代理 / 门户 / WAF 的 HTML 拦截页，不能并进「连不上」。
            throw new IllegalStateException("智谱搜索失败：响应无法解析（Content-Type 可能不是 JSON，"
                    + "疑似代理或门户拦截页）：" + preview(String.valueOf(e.getMessage())), e);
        }
    }

    /** 截断超长文本，避免把整页响应塞进错误消息。 */
    private static String preview(String raw) {
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResults(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("智谱搜索失败：响应为空");
        }
        Object results = response.get("search_result");
        if (!(results instanceof List<?> list)) {
            throw new IllegalStateException("智谱搜索失败：响应缺少 search_result 字段，响应片段："
                    + preview(String.valueOf(response)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> item) {
                out.add((Map<String, Object>) item);
            }
        }
        if (out.isEmpty() && !list.isEmpty()) {
            throw new IllegalStateException("智谱搜索失败：search_result 里没有可识别的结果对象，响应片段："
                    + preview(String.valueOf(response)));
        }
        return out;
    }

    private static String render(String query, List<Map<String, Object>> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(query).append("」找到 ").append(values.size()).append(" 条结果：\n");
        int index = 1;
        for (Map<String, Object> item : values) {
            String title = str(item.get("title"));
            String url = str(item.get("link"));
            String media = str(item.get("media"));
            String date = shortDate(str(item.get("publish_date")));
            String text = str(item.get("content"));

            sb.append('\n').append(index++).append(". ").append(title.isEmpty() ? url : title);
            String meta = media;
            if (!date.isEmpty()) {
                meta = meta.isEmpty() ? date : media + " · " + date;
            }
            if (!meta.isEmpty()) {
                sb.append(" — ").append(meta);
            }
            sb.append('\n').append("   ").append(url).append('\n');
            if (!text.isEmpty()) {
                sb.append("   ").append(text).append('\n');
            }
        }
        return sb.toString();
    }

    /** 日期截到天；文档形如 2025-05-23（10 位），ISO 长形（2025-05-23T…）也兼容。 */
    private static String shortDate(String raw) {
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 解析 {@code ZHIPU_SEARCH_COUNT}：缺失 / 非数字回退 {@link #DEFAULT_COUNT}，越界钳到 {@code [1, MAX_COUNT]}。
     * 形状照 {@code BochaWebSearchTool#resolveResultCount}。env 由 AgentTools 读取，这里只负责解析语义。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static int resolveResultCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COUNT;
        }
        try {
            return Math.min(MAX_COUNT, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }

    /**
     * 解析 {@code ZHIPU_SEARCH_ENGINE}：白名单四枚举值之外一律回退 {@link #DEFAULT_SEARCH_ENGINE}。
     * 引擎是计费档位（差 5 倍），非法值本地拦，不透传（理由见 {@link #SEARCH_ENGINES}）。
     */
    public static String resolveSearchEngine(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SEARCH_ENGINE;
        }
        String trimmed = raw.trim();
        return SEARCH_ENGINES.contains(trimmed) ? trimmed : DEFAULT_SEARCH_ENGINE;
    }

    /**
     * 域名列表拼成 {@code a.com,b.com}。分隔符依官方文档未写明（仅单域名示例），按 HTTP 常见约定
     * 取逗号——<b>未实测</b>（账户余额不足），真机冒烟双域名用例验证后回填 spec「已知取舍」。
     * 与博查不同，不设 100 个截断：智谱文档未给上限，不替它发明限制。
     */
    static String joinInclude(List<String> include) {
        if (include == null || include.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String domain : include) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(domain.trim());
        }
        return sb.toString();
    }

    /** 链式构造；{@code apiKey} 必填非空（是否创建工具由 AgentTools 按 env 决定）。 */
    public static final class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private int resultCount = DEFAULT_COUNT;
        private String searchEngine = DEFAULT_SEARCH_ENGINE;

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("智谱 API key 不能为空");
            }
            this.apiKey = apiKey.trim();
        }

        /** 仅供测试指向本地 stub server；生产走 {@link #DEFAULT_BASE_URL}。null / 空白则保持默认。 */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                this.baseUrl = baseUrl.trim();
            }
            return this;
        }

        /**
         * 结果条数，钳到 {@code [1, MAX_COUNT]}。与 {@link #resolveResultCount} 的钳制<b>不是</b>冗余：
         * 那里是 env 字符串的解析语义，这里是所有构造路径的不变量守卫。两处都别删。
         */
        public Builder resultCount(int resultCount) {
            this.resultCount = Math.min(MAX_COUNT, Math.max(1, resultCount));
            return this;
        }

        /** 引擎经 {@link #resolveSearchEngine} 白名单收口：builder 路径同样不许越出四枚举。 */
        public Builder searchEngine(String searchEngine) {
            this.searchEngine = resolveSearchEngine(searchEngine);
            return this;
        }

        public ZhipuWebSearchTool build() {
            return new ZhipuWebSearchTool(apiKey, baseUrl, resultCount, searchEngine);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='ZhipuWebSearchToolTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`ZhipuWebSearchToolTest` 全部 24 个用例 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchToolTest.java
git commit -m "feat: ZhipuWebSearchTool 智谱联网搜索工具（离线 stub 单测全覆盖）"
```

---

### Task 2: `webSearchGuide` 重构为三布尔条目拼装

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`webSearchGuide` 方法，约 896 行起）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

**Interfaces:**
- Consumes: 无前置（本任务独立于 Task 1，可并行）。
- Produces: `static String webSearchGuide(boolean bocha, boolean zhipu, boolean brave)`（包级；调用点 `AgentTools.build` 暂以 `zhipu=false` 过渡，Task 3 接真值）。零家注册返回空串；正文无花括号。

- [ ] **Step 1: 改造测试（先红）**

`AgentToolsWebSearchWiringTest.java` 中删除/替换以下既有用例（`guideIsEmptyWhenNeitherToolRegistered`、`guideCoversBochaOnly`、`guideCoversBraveOnly`、`guideExplainsDivisionWhenBothRegistered`、`noGuideVariantContainsTemplateBraces`），换成：

```java
    @Test
    void guideIsEmptyWhenNoToolRegistered() {
        assertEquals("", AgentTools.webSearchGuide(false, false, false),
                "三家都没注册时，系统提示不应出现任何搜索相关指引");
    }

    @Test
    void guideCoversBochaOnly() {
        String guide = AgentTools.webSearchGuide(true, false, false);

        assertTrue(guide.contains("BochaWebSearch"), "应点名博查工具，实际=" + guide);
        assertFalse(guide.contains("ZhipuWebSearch"), "智谱没注册就不该提它，实际=" + guide);
        assertFalse(guide.contains("BraveWebSearch"), "Brave 没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    @Test
    void guideCoversZhipuOnly() {
        String guide = AgentTools.webSearchGuide(false, true, false);

        assertTrue(guide.contains("ZhipuWebSearch"), "应点名智谱工具，实际=" + guide);
        assertFalse(guide.contains("BochaWebSearch"), "博查没注册就不该提它，实际=" + guide);
        assertFalse(guide.contains("BraveWebSearch"), "Brave 没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "实际=" + guide);
        assertTrue(guide.contains("include 与 freshness 不要同时传"),
                "智谱的 include+freshness 同传坑应只在智谱注册时提示，实际=" + guide);
    }

    @Test
    void guideCoversBraveOnly() {
        String guide = AgentTools.webSearchGuide(false, false, true);

        assertTrue(guide.contains("BraveWebSearch"), "应点名 Brave 工具，实际=" + guide);
        assertFalse(guide.contains("BochaWebSearch"), "博查没注册就不该提它，实际=" + guide);
        assertFalse(guide.contains("ZhipuWebSearch"), "智谱没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "实际=" + guide);
    }

    @Test
    void guideExplainsBochaZhipuRedundancy() {
        String guide = AgentTools.webSearchGuide(true, true, false);

        assertTrue(guide.contains("BochaWebSearch") && guide.contains("ZhipuWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("互为冗余"), "两个国内源应说明互为冗余，实际=" + guide);
        assertFalse(guide.contains("BraveWebSearch"), "Brave 没注册就不该提它，实际=" + guide);
    }

    @Test
    void guideExplainsDivisionWhenAllRegistered() {
        String guide = AgentTools.webSearchGuide(true, true, true);

        assertTrue(guide.contains("BochaWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("ZhipuWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("BraveWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("中文"), "应讲清中文走哪家，实际=" + guide);
        assertTrue(guide.contains("英文"), "应讲清英文走哪家，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    /** 指引段作为 param 值注入，正文里的花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示。 */
    @Test
    void noGuideVariantContainsTemplateBraces() {
        for (boolean bocha : new boolean[]{false, true}) {
            for (boolean zhipu : new boolean[]{false, true}) {
                for (boolean brave : new boolean[]{false, true}) {
                    String guide = AgentTools.webSearchGuide(bocha, zhipu, brave);
                    assertTrue(!guide.contains("{") && !guide.contains("}"),
                            "指引正文不得含花括号（bocha=" + bocha + ", zhipu=" + zhipu
                                    + ", brave=" + brave + "），实际=" + guide);
                }
            }
        }
    }
```

同时 `AgentTools.build` 里现有调用点 `webSearchGuide(webSearch != null, braveWebSearch != null)` 改为 `webSearchGuide(webSearch != null, false, braveWebSearch != null)`（过渡；Task 3 换真值）。

- [ ] **Step 2: 跑测试确认红**

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsWebSearchWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译错误（三参 `webSearchGuide` 不存在）。

- [ ] **Step 3: 重写 `webSearchGuide`（条目拼装）**

替换 `AgentTools.java` 中现有 `webSearchGuide` 方法（含 javadoc）为：

```java
    /**
     * 搜索指引段：按实际注册了哪些搜索工具动态拼装——都没注册就返回空串，模型看不到指引，
     * 也就不会去调不存在的工具。三家已是 2^3 = 8 态，if 分支硬编码不可维护，改为
     * 「总起 + 分工句 + 公共尾 + 各家专属坑」条目拼装，第四家只需在 {@link #divisionLine} 与
     * 专属坑段各加一个条目。
     *
     * <p><b>跨工具分工只写在这里</b>（按注册状态渲染，绝不提不存在的工具）；工具自身的
     * @Tool 描述只写自家能力与用法。
     *
     * <p>正文<b>不得含花括号</b>：它作为 param 值注入（与 AUTO_MEMORY / PROJECT_INSTRUCTIONS 同法），
     * 花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示渲染。
     */
    static String webSearchGuide(boolean bocha, boolean zhipu, boolean brave) {
        if (!bocha && !zhipu && !brave) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时先搜索，别凭记忆臆断外部事实。\n");
        sb.append("  ").append(divisionLine(bocha, zhipu, brave)).append('\n');
        sb.append("- 拿到网址后，需要网页原文细节就把该网址交给 webFetch 抓取。\n");
        if (bocha || zhipu) {
            sb.append("  国内源（").append(domesticNames(bocha, zhipu))
              .append("）的 freshness 一般不要传：默认不限时间效果最好，硬指时间范围反而容易搜空。\n");
        }
        if (zhipu) {
            sb.append("  ZhipuWebSearch 的 include 与 freshness 不要同时传：两者同时生效时结果条数限制会失效。\n");
        }
        if (brave) {
            sb.append("  BraveWebSearch 不要用 allowedDomains 限定域名（客户端过滤，白烧配额），改把 site:xxx 写进搜索词。\n");
        }
        sb.append("- 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。");
        return sb.toString();
    }

    /** 分工句：国内源（博查/智谱）与 Brave 的组合各有措辞；只描述已注册的工具。 */
    private static String divisionLine(boolean bocha, boolean zhipu, boolean brave) {
        String domestic = domesticNames(bocha, zhipu);
        if (!brave) {
            if (bocha && zhipu) {
                return "搜索工具：" + domestic + "，两家互为冗余：一家搜不到或额度耗尽时换另一家再试。";
            }
            return "搜索工具：" + domestic + "。";
        }
        if (domestic.isEmpty()) {
            return "搜索工具：BraveWebSearch（主打英文技术文档、GitHub issue、英文新闻）。";
        }
        return "多个搜索工具按内容分工：中文内容、国内站点、中文技术社区用 " + domestic
                + "（国内向，返回摘要）；英文技术文档、GitHub issue、英文新闻用 BraveWebSearch。";
    }

    /** 已注册的国内源工具名，两家时用斜杠连接；无国内源返回空串。 */
    private static String domesticNames(boolean bocha, boolean zhipu) {
        if (bocha && zhipu) {
            return "BochaWebSearch / ZhipuWebSearch";
        }
        if (bocha) {
            return "BochaWebSearch";
        }
        if (zhipu) {
            return "ZhipuWebSearch";
        }
        return "";
    }
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsWebSearchWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：该类全部用例 PASS（含未动的博查/Brave 门控用例）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "refactor: webSearchGuide 四态硬编码改为三家条目拼装

为第三家搜索源铺路：2^3=8 态若走 if 分支将不可维护。
分工句与各家专属坑提示按注册状态渲染，绝不提不存在的工具。"
```

---

### Task 3: `AgentTools` 接线（门控注册 + env 解析进 build）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`build` 注册段约 480–500 行、`createWebSearchTool` 附近、类 javadoc 约 93–95 行、行内注释约 469 行）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`（追加智谱接线用例）

**Interfaces:**
- Consumes: Task 1 的 `ZhipuWebSearchTool.builder/resolveResultCount/resolveSearchEngine`；Task 2 的三布尔 `webSearchGuide`。
- Produces: `public static ZhipuWebSearchTool createZhipuWebSearchTool(String apiKey, String countEnv, String engineEnv)`（key 空返回 null）；`build` 内按 `ZHIPU_API_KEY` / `ZHIPU_SEARCH_COUNT` / `ZHIPU_SEARCH_ENGINE` 门控注册。

- [ ] **Step 1: 追加失败测试（`AgentToolsWebSearchWiringTest` 末尾）**

```java
    @Test
    void noZhipuKey_noZhipuTool() {
        assertNull(AgentTools.createZhipuWebSearchTool(null, null, null), "未配 ZHIPU_API_KEY 时不应创建");
        assertNull(AgentTools.createZhipuWebSearchTool("   ", null, null), "空白 key 时不应创建");
    }

    /** 注册名取 @Tool 注解而非方法名；子 agent 的 allow/deny 按注册名精确匹配，写错会静默失效。 */
    @Test
    void zhipuToolRegisteredNameIsZhipuWebSearch() {
        ZhipuWebSearchTool tool = AgentTools.createZhipuWebSearchTool("fake-key", null, null);

        assertNotNull(tool, "配了 key 就应创建");
        List<String> names = Arrays.stream(ToolCallbacks.from(tool))
                .map(c -> c.getToolDefinition().name()).toList();
        assertEquals(List.of("ZhipuWebSearch"), names, "实际=" + names);
    }

    @Test
    void zhipuCountAndEngineFromEnvReachTool() {
        // 工厂把 env 解析结果交给 builder 的路径：非默认值能建出来不抛即可，解析语义已由工具单测钉住。
        assertNotNull(AgentTools.createZhipuWebSearchTool("fake-key", "20", "search_pro"));
    }

    /** 完整装饰链之后注册名仍须保持——中间任何一层丢了名字，工具分发就会撞上别家。 */
    @Test
    void zhipuKeepsNameThroughFullDecorationChain() {
        ZhipuWebSearchTool tool = AgentTools.createZhipuWebSearchTool("fake-key", null, null);

        ToolCallback decorated = new ToolEventCallback(
                ToolCallbacks.from(tool)[0], new ConversationState());

        assertEquals("ZhipuWebSearch", decorated.getToolDefinition().name(),
                "装饰链末端的注册名，实际=" + decorated.getToolDefinition().name());
    }
```

同时该测试文件顶部补 import：`import io.github.javaside.springai.codetui.agent.tools.ZhipuWebSearchTool;`

- [ ] **Step 2: 跑测试确认红**

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsWebSearchWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译错误（`createZhipuWebSearchTool` 不存在）。

- [ ] **Step 3: 实现（`AgentTools.java` 三处）**

3a. `build` 方法内，Brave 创建语句之后追加：

```java
        // 智谱搜索（第三家，国内向）：ZHIPU_API_KEY 配了才注册。与博查/Brave 共存，
        // 分工与冗余关系写在系统提示指引段（webSearchGuide）。
        ZhipuWebSearchTool zhipuWebSearch = createZhipuWebSearchTool(
                System.getenv("ZHIPU_API_KEY"),
                System.getenv("ZHIPU_SEARCH_COUNT"),
                System.getenv("ZHIPU_SEARCH_ENGINE"));
```

`if (webSearch != null) { rawTools.add(webSearch); }` 之后追加：

```java
        if (zhipuWebSearch != null) {
            rawTools.add(zhipuWebSearch);   // @Tool 对象，与博查同路（统一走装饰链）
        }
```

Task 2 留下的过渡调用 `webSearchGuide(webSearch != null, false, braveWebSearch != null)` 改为：

```java
        String webSearchGuide = webSearchGuide(webSearch != null, zhipuWebSearch != null, braveWebSearch != null);
```

3b. `createWebSearchTool` 方法之后新增工厂与解析（`resolveBraveResultCount` 附近）：

```java
    /**
     * 按 env 决定是否创建智谱搜索工具：{@code apiKey} 空即返回 null（不注册）。
     * env 的<b>读取</b>在这里，<b>解析语义</b>（回退/钳制/白名单）在
     * {@link ZhipuWebSearchTool#resolveResultCount} / {@link ZhipuWebSearchTool#resolveSearchEngine}。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ZhipuWebSearchTool createZhipuWebSearchTool(String apiKey, String countEnv, String engineEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return ZhipuWebSearchTool.builder(apiKey)
                .resultCount(ZhipuWebSearchTool.resolveResultCount(countEnv))
                .searchEngine(ZhipuWebSearchTool.resolveSearchEngine(engineEnv))
                .build();
    }
```

3c. 类 javadoc（约 93–95 行）与 rawTools 行内注释（约 469 行）的「两家/BOCHA_API_KEY」措辞扩为三家（`ZHIPU_API_KEY` 同样条件注册）。

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsWebSearchWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: AgentTools 按 ZHIPU_API_KEY 门控注册智谱搜索工具"
```

---

### Task 4: 权限登记 + 运行时工具集补齐

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java:53`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RuntimeToolSet.java:57-65`

**Interfaces:**
- Consumes: Task 3 的 `AgentTools.createZhipuWebSearchTool`。
- Produces: `ToolRegistry.lookup("ZhipuWebSearch")` 返回 `NETWORK_READ / "query"`；`RuntimeToolSet.byRegisteredName` 无 key 机器上也含 `ZhipuWebSearch`。

- [ ] **Step 1: 先跑完整性测试看它红**

```bash
mvn -pl springai-code-tui -am test -Dtest='ToolRegistryCompletenessTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：FAIL——Task 3 之后 `RuntimeToolSet` 尚未补智谱，而 build 无 key 时不会注册它；此刻若 CI 机器无 `ZHIPU_API_KEY`，工具不在枚举里、登记表也没有条目，测试可能仍绿。**若绿：先做 Step 2 的 RuntimeToolSet 补齐，再单独跑本步，必红（枚举里有工具、登记表没条目）——这才是本任务要钉住的红。**

- [ ] **Step 2: 登记与补齐**

`ToolRegistry.java` 只读网络区块（`BraveWebSearch` 行后）加：

```java
        put("ZhipuWebSearch",  ToolCategory.NETWORK_READ, "query", false);
```

`RuntimeToolSet.byRegisteredName` 中 Brave 补齐段之后追加（javadoc「两个 env 门控」措辞同步改「三个」）：

```java
        ZhipuWebSearchTool zhipu = AgentTools.createZhipuWebSearchTool("fake-key", null, null);
        if (zhipu != null) {
            all.add(ToolCallbacks.from(zhipu)[0]);
        }
```

顶部补 import：`import io.github.javaside.springai.codetui.agent.tools.ZhipuWebSearchTool;`

- [ ] **Step 3: 跑完整性测试确认绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='ToolRegistryCompletenessTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS（`ZhipuWebSearch` 在登记表与运行时枚举两侧对上）。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RuntimeToolSet.java
git commit -m "feat: ZhipuWebSearch 登记权限表并补齐运行时工具集枚举"
```

---

### Task 5: 真机冒烟测试

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchSmokeTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ZhipuWebSearchTool.builder().webSearch(...)`。
- Produces: 无（终端测试类；双门控 `CODETUI_LIVE_TESTS=1` + `ZHIPU_API_KEY`，默认跳过）。

- [ ] **Step 1: 写测试类**

```java
package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实智谱 Web Search API 冒烟。双门控：{@code CODETUI_LIVE_TESTS=1} 显式开（联网、花钱、墙钟不稳，
 * 默认不跑——同 {@link BochaWebSearchSmokeTest}）+ {@code ZHIPU_API_KEY}。会消耗一次搜索额度（0.01 元起）。
 *
 * <p>余额不足（实测 429 + code 1113）显式 assume 跳过：环境态不是代码错，红了只会淹没在误报里。
 * 充值后本类即为 spec「已知取舍」里 200 响应形状与多域名分隔符的回填验证口。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "ZHIPU_API_KEY", matches = ".+")
class ZhipuWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder(System.getenv("ZHIPU_API_KEY"))
                .resultCount(3)
                .build();

        String out;
        try {
            out = tool.webSearch("Spring AI 框架", null, null);
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(
                    !(e.getMessage().contains("429") && e.getMessage().contains("1113")),
                    "智谱账户余额不足，跳过冒烟：" + e.getMessage());
            throw e;
        }

        System.out.println("[smoke] 智谱搜索返回：\n" + out);
        assertTrue(out.contains("找到"), "应返回结果列表而非零结果提示，实际=" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }

    /**
     * 双域名 include：验证 joinInclude 的逗号分隔符在真实 API 上被接受（spec「已知取舍」回填口）。
     * 不断言结果全在白名单内——分隔符若不被识别可能被当整串忽略，脆断言只会制造误报；
     * 调用成功 + 打印结果供人工核对即可。
     */
    @Test
    void dualDomainIncludeAcceptedByRealApi() {
        ZhipuWebSearchTool tool = ZhipuWebSearchTool.builder(System.getenv("ZHIPU_API_KEY"))
                .resultCount(3)
                .build();

        String out;
        try {
            out = tool.webSearch("Spring AI reference", null, List.of("docs.spring.io", "github.com"));
        } catch (IllegalStateException e) {
            Assumptions.assumeTrue(
                    !(e.getMessage().contains("429") && e.getMessage().contains("1113")),
                    "智谱账户余额不足，跳过冒烟：" + e.getMessage());
            throw e;
        }

        System.out.println("[smoke] 双域名 include 返回：\n" + out);
        assertTrue(out.contains("找到") || out.contains("没搜到"),
                "调用本身应成功（接受逗号格式），实际=" + out);
    }
}
```

- [ ] **Step 2: 默认环境跑一遍确认优雅跳过**

```bash
mvn -pl springai-code-tui -am test -Dtest='ZhipuWebSearchSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：两个用例均 SKIP（未设 `CODETUI_LIVE_TESTS`）。

- [ ] **Step 3:（有余额时才做）真机跑并回填 spec**

```bash
CODETUI_LIVE_TESTS=1 mvn -pl springai-code-tui -am test -Dtest='ZhipuWebSearchSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：PASS；把 200 响应的真实字段形状、双域名是否生效回填 spec「已知取舍」两节（当前账户 1113 余额不足，此步只能 assume 跳过——在提交信息里注明即可）。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ZhipuWebSearchSmokeTest.java
git commit -m "test: 智谱搜索真机冒烟（双门控，余额不足显式跳过）"
```

---

### Task 6: 文档同步

**Files:**
- Modify: `springai-code-tui/src/package/bin/config.env.example:32-47`（搜索段落）
- Modify: `springai-code-tui/README.md`（工具清单、安全披露两处）
- Modify: `springai-code-tui/docs/implementation-map.md`（搜索工具一节）
- Modify: `springai-code-tui/docs/guide/permissions.md`、`springai-code-tui/docs/guide/security.md`（工具清单/出网通道）

**Interfaces:** 无代码接口；纯文档。改动前先 grep 各文件中 `BochaWebSearch` / `BraveWebSearch` 定位全部提及点，逐处补智谱。

- [ ] **Step 1: config.env.example 搜索段落更新**

「可选：网络搜索（两家可共存）」标题改「三家可共存」，博查/Brave 块后追加：

```
#
# 智谱 BigModel（国内向，返回摘要）。与博查互为冗余；按次计费（0.01–0.05 元/次，无免费档）。
# 注意：key 复用 ZHIPU_API_KEY（上面大模型段落那把）——配了它搜索工具即启用。
#ZHIPU_SEARCH_COUNT=8
# 搜索引擎，可选。默认 search_std（0.01 元/次）；可选 search_pro（0.03）/ search_pro_sogou /
# search_pro_quark（均 0.05）。白名单外的值静默回退 search_std。
#ZHIPU_SEARCH_ENGINE=search_std
```

末尾「两家都配则模型按内容语言自选」句改「三家按注册状态与内容语言自选；都不配则没有联网搜索能力（webFetch 不受影响）」。

- [ ] **Step 2: README 工具清单 + 安全披露**

工具清单加一行：`ZhipuWebSearch`（智谱联网搜索，需 `ZHIPU_API_KEY`，按次计费）。安全披露段落补：第三条对外出网通道；查询词发给智谱（国内服务，不出境，与博查同性质、与 Brave 不同）；**按次计费**是与博查/Brave（免费/包月档）不同的新维度。

- [ ] **Step 3: implementation-map / permissions / security**

`implementation-map.md` 搜索工具条目扩为三家（一句话差异：自研 REST、离线单测全覆盖）。`permissions.md` 工具类别表与 `security.md` 出网通道清单补 `ZhipuWebSearch`。

- [ ] **Step 4: 校验文档命令可复制运行**

按 `docs/guide/` 惯例核对示例命令带 `-am` 与 `-Dsurefire.failIfNoSpecifiedTests=false`（本任务不新增命令则跳过）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/package/bin/config.env.example springai-code-tui/README.md \
        springai-code-tui/docs/implementation-map.md springai-code-tui/docs/guide/permissions.md \
        springai-code-tui/docs/guide/security.md
git commit -m "docs: 智谱联网搜索接入文档（env/工具清单/安全披露/权限表）"
```

---

### Task 7: 全量验证 + 合并

- [ ] **Step 1: 全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：全绿（含既有全部测试；`CodingAgentSpikeTest.todoTurnIdBinding` 为已知 flaky，红了先按 CONTRIBUTING.md 单跑复核再判断）。

- [ ] **Step 2: 编译对齐 CI**

```bash
mvn -DskipTests package
```

- [ ] **Step 3: 变异测试抽查（验证测试真的钉住了行为）**

临时把 `ZhipuWebSearchTool` 的 `render` 里 `media` 字段改读 `"mediaName"`（不存在的字段名）→ 跑 `ZhipuWebSearchToolTest` 应红 → 改回 → 复跑全绿 → `git diff` 确认无残留。

- [ ] **Step 4: 合并（问过用户后执行）**

```bash
git checkout main && git merge --no-ff feature/zhipu-web-search
```

提交信息正文概述三家共存格局与已知取舍（200 形状待充值后冒烟回填）。
