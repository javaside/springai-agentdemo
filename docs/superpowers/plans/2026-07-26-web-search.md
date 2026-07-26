# 网络搜索（WebSearch / 博查）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 加一个 `WebSearch` 工具（博查 Web Search API），配了 `BOCHA_API_KEY` 才注册，让模型能联网搜索并把 URL 交给已有的 `webFetch` 抓正文。

**Architecture:** 新增单个 `@Tool` 类 `BochaWebSearchTool`（HTTP → 解析 → Markdown，不碰 LLM 与文件系统），在 `AgentTools.build` 里按 env 门控接入既有装饰链（`MediaExternalizingCallback` + `ToolEventCallback`），主 agent 与 `general-purpose` 子 agent 自动获得。系统提示新增一个 param 注入的指引段，无 key 时为空串。

**Tech Stack:** Java 17、Spring Framework 7.0.8（`RestClient` + `JdkClientHttpRequestFactory`）、Spring AI 2.0（`@Tool` / `@ToolParam` / `ToolCallbacks`）、JUnit 5、JDK 内置 `com.sun.net.httpserver.HttpServer` 做测试 stub。

**Spec:** `docs/superpowers/specs/2026-07-26-web-search-design.md`

---

## File Structure

| 文件 | 职责 |
|---|---|
| **创建** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java` | 唯一新增生产类。持 `RestClient` 与结果条数，暴露一个 `@Tool(name="WebSearch")` 方法；负责请求体拼装、响应解析、Markdown 渲染、错误转译 |
| **修改** `.../agent/AgentTools.java` | ① 门控创建工具并追加进 `rawTools`；② `SYSTEM_TEMPLATE` 加 `{WEB_SEARCH_GUIDE}` 占位符并 param 注入；③ 更新两处失效的工具计数注释 |
| **创建** `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java` | 离线单测全集，自带 `StubServer` 内部类 |
| **创建** `.../agent/AgentToolsWebSearchWiringTest.java` | 门控接线 + 注册名 + 系统提示指引段 |
| **创建** `.../agent/BochaWebSearchSmokeTest.java` | 真实 API 冒烟，`@EnabledIfEnvironmentVariable` 门控 |
| **修改** `springai-code-tui/src/package/bin/config.env.example` | 新增两个 env |
| **修改** `springai-code-tui/README.md` | 工具清单 + 安全披露 |

**验证命令一律模块作用域**：`mvn -pl springai-code-tui test`。整仓 `mvn test` 会被几个空模块打挂，且不要用 `-DfailIfNoSpecifiedTests=false` 盖问题。

---

### Task 1: 工具骨架与正常结果渲染

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: 编译失败，`找不到符号: 类 BochaWebSearchTool`

- [ ] **Step 3: 写最小实现**

创建 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查（Bocha）Web Search API 工具 —— 给模型提供联网搜索能力。
 *
 * <p>只做「HTTP 调博查 → 解析 → 渲染 Markdown」，不碰 LLM、不碰文件系统。与已有的
 * {@code webFetch}（SmartWebFetchTool）分工：本工具负责<b>找到 URL 与摘要</b>，需要网页原文细节时
 * 由模型把 URL 交给 {@code webFetch} 抓取。
 *
 * <p><b>为何不用库里现成的 BraveWebSearchTool</b>：{@code api.search.brave.com} 国内直连大概率不通，
 * 且库里没有代理配置口子；其工具描述还写死了「Claude」与「US only」，两条都不适用。
 *
 * <p><b>超时不接 {@link LlmTimeouts}</b>：那套是 LLM 语义（read 默认 300s，等的是流式块间隔）；
 * 搜索是一次性 REST 调用，超 20s 就该失败，套用 LLM 超时等于挂死。
 */
public final class BochaWebSearchTool {

    /** 博查 API 默认端点；{@code baseUrl(..)} 仅供测试打本地 stub server，不暴露 env。 */
    static final String DEFAULT_BASE_URL = "https://api.bochaai.com";

    private static final String SEARCH_PATH = "/v1/web-search";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** 默认返回条数；博查侧 count 允许 1–50。 */
    static final int DEFAULT_COUNT = 8;
    static final int MAX_COUNT = 50;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final int resultCount;

    private BochaWebSearchTool(String apiKey, String baseUrl, int resultCount) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.resultCount = resultCount;
    }

    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    @Tool(name = "WebSearch", description = """
            搜索互联网，返回网页标题、网址、摘要、站点名与发布时间。

            用法：
            - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时用它；不要凭记忆臆断外部事实。
            - 它只返回摘要。需要网页原文细节时，把结果里的网址交给 webFetch 工具去抓取。
            - freshness 一般不要传：博查的算法会自动改写时间范围，硬指区间反而容易搜不到东西。
              只有明确需要「最近一天/一周」的最新消息时才传。
            - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
            """)
    public String webSearch(
            @ToolParam(description = "搜索词。用具体的关键词；搜索最新资料时可在词里带上年份。")
            String query,
            @ToolParam(required = false, description =
                    "时间范围，可选。默认 noLimit（不限，推荐）。可填 oneDay / oneWeek / oneMonth / oneYear。")
            String freshness,
            @ToolParam(required = false, description =
                    "只在这些域名内搜索，可选。例如 [\"docs.spring.io\", \"github.com\"]。")
            List<String> include) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.trim());
        body.put("freshness", (freshness == null || freshness.isBlank()) ? "noLimit" : freshness.trim());
        body.put("summary", true);
        body.put("count", resultCount);

        Map<String, Object> response = restClient.post()
                .uri(SEARCH_PATH)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);

        return render(query, extractValues(response));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractValues(Map<String, Object> response) {
        Object pages = response.get("webPages");
        if (!(pages instanceof Map<?, ?> pageMap)) {
            return List.of();
        }
        Object value = pageMap.get("value");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }

    private static String render(String query, List<Map<String, Object>> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(query).append("」找到 ").append(values.size()).append(" 条结果：\n");
        int index = 1;
        for (Map<String, Object> item : values) {
            String title = str(item.get("name"));
            String url = str(item.get("url"));
            String site = str(item.get("siteName"));
            String date = shortDate(str(item.get("datePublished")));
            String text = str(item.get("summary"));

            sb.append('\n').append(index++).append(". ").append(title.isEmpty() ? url : title);
            sb.append(" — ").append(site).append(" · ").append(date);
            sb.append('\n').append("   ").append(url).append('\n');
            sb.append("   ").append(text).append('\n');
        }
        return sb.toString();
    }

    /** ISO 8601（如 2026-01-30T07:19:14+08:00）截到天；短于 10 位则原样返回。 */
    private static String shortDate(String raw) {
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 链式构造；{@code apiKey} 必填非空（是否创建工具由 AgentTools 按 env 决定）。 */
    public static final class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private int resultCount = DEFAULT_COUNT;

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("博查 API key 不能为空");
            }
            this.apiKey = apiKey.trim();
        }

        /** 仅供测试指向本地 stub server；生产走 {@link #DEFAULT_BASE_URL}。 */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl != null && !baseUrl.isBlank()) {
                this.baseUrl = baseUrl.trim();
            }
            return this;
        }

        public Builder resultCount(int resultCount) {
            this.resultCount = Math.min(MAX_COUNT, Math.max(1, resultCount));
            return this;
        }

        public BochaWebSearchTool build() {
            return new BochaWebSearchTool(apiKey, baseUrl, resultCount);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java
git commit -m "feat: 博查 WebSearch 工具骨架与结果渲染"
```

---

### Task 2: 字段缺失降级（summary 退回 snippet、日期缺失不留空段）

Task 1 的 `render` 直接读 `summary` 和 `datePublished`，博查真实响应里这两个字段经常缺失（`snippet` 才是恒有的）。本任务补上降级。

**Files:**
- Modify: `.../agent/BochaWebSearchTool.java`（`render` 方法）
- Test: `.../agent/BochaWebSearchToolTest.java`（新增两个用例）

- [ ] **Step 1: 写失败的测试**

在 `BochaWebSearchToolTest` 里追加：

```java
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
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `fallsBackToSnippetWhenSummaryMissing` 与 `omitsDateSegmentWhenDateMissing` 两条 FAIL（第二条渲染出 `标题二 — b.com · ` 且正文为空行）

- [ ] **Step 3: 改实现**

把 `BochaWebSearchTool.render` 整个方法替换为：

```java
    private static String render(String query, List<Map<String, Object>> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(query).append("」找到 ").append(values.size()).append(" 条结果：\n");
        int index = 1;
        for (Map<String, Object> item : values) {
            String title = str(item.get("name"));
            String url = str(item.get("url"));
            String site = str(item.get("siteName"));
            String date = shortDate(str(item.get("datePublished")));
            // summary 仅在请求 summary:true 且博查生成成功时返回；snippet 恒有，作为兜底。
            String text = str(item.get("summary"));
            if (text.isEmpty()) {
                text = str(item.get("snippet"));
            }

            sb.append('\n').append(index++).append(". ").append(title.isEmpty() ? url : title);
            String meta = site;
            if (!date.isEmpty()) {
                meta = meta.isEmpty() ? date : meta + " · " + date;
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java
git commit -m "fix: 搜索结果 summary 缺失退回 snippet、日期缺失不留悬空分隔符"
```

---

### Task 3: count 钳制与 include 域名拼接（含 100 个截断）

**Files:**
- Modify: `.../agent/BochaWebSearchTool.java`（新增 `resolveResultCount` 与 `joinInclude`，`webSearch` 加 include）
- Test: `.../agent/BochaWebSearchToolTest.java`

- [ ] **Step 1: 写失败的测试**

追加（并在文件顶部补 `import java.util.ArrayList;` 与 `import static org.junit.jupiter.api.Assertions.assertFalse;`）：

```java
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
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: 编译失败，`找不到符号: 方法 resolveResultCount(java.lang.String)` 与 `joinInclude(java.util.List)`

- [ ] **Step 3: 写实现**

在 `BochaWebSearchTool` 里，常量区补一行：

```java
    /** 博查侧 include 域名上限；超出部分截断（截断比让整次搜索失败合理）。 */
    private static final int MAX_INCLUDE_DOMAINS = 100;
```

在 `webSearch` 方法里，`body.put("count", resultCount);` 之后插入：

```java
        String includeParam = joinInclude(include);
        if (!includeParam.isEmpty()) {
            body.put("include", includeParam);
        }
```

并在 `str(..)` 方法之后追加两个包级静态方法：

```java
    /**
     * 解析 {@code BOCHA_SEARCH_COUNT}：缺失 / 非数字回退 {@link #DEFAULT_COUNT}，越界钳到 {@code [1, MAX_COUNT]}。
     * 形状照 {@code AgentTools.resolveSubagentConcurrency}。env 由 AgentTools 读取，这里只负责解析语义。
     */
    static int resolveResultCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_COUNT;
        }
        try {
            return Math.min(MAX_COUNT, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }

    /** 域名列表拼成博查要的 {@code a.com|b.com}；跳过空白项，超过 {@link #MAX_INCLUDE_DOMAINS} 个则截断。 */
    static String joinInclude(List<String> include) {
        if (include == null || include.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String domain : include) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            if (kept > 0) {
                sb.append('|');
            }
            sb.append(domain.trim());
            if (++kept == MAX_INCLUDE_DOMAINS) {
                break;
            }
        }
        return sb.toString();
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java
git commit -m "feat: 搜索条数钳制与 include 域名拼接（超 100 截断）"
```

---

### Task 4: 空 query 短路与零结果文案

两者都是「正常返回文本、不抛异常」的路径：空 query 连请求都不发（省搜索额度），零结果给模型一句可操作的提示而不是炸掉整个回合。

**Files:**
- Modify: `.../agent/BochaWebSearchTool.java`（`webSearch` 开头 + `render` 前的分支）
- Test: `.../agent/BochaWebSearchToolTest.java`

- [ ] **Step 1: 写失败的测试**

追加：

```java
    private static final String ZERO_RESULTS = """
            {"_type":"SearchResponse","webPages":{"totalEstimatedMatches":0,"value":[]}}
            """;

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
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: 3 条 FAIL —— 空/null query 用例因 `query.trim()` 抛 NPE 或照发请求；零结果用例输出的是「找到 0 条结果：」

- [ ] **Step 3: 写实现**

在 `webSearch` 方法体最开头（`Map<String, Object> body = ...` 之前）插入：

```java
        if (query == null || query.isBlank()) {
            return "搜索词为空，请给出要搜索的内容。";
        }
```

把方法末尾的 `return render(query, extractValues(response));` 替换为：

```java
        List<Map<String, Object>> values = extractValues(response);
        if (values.isEmpty()) {
            return "没搜到「" + query.trim() + "」的相关结果。建议换一组关键词或同义词，"
                    + "或去掉 freshness 时间限制再试一次。";
        }
        return render(query.trim(), values);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `Tests run: 13, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java
git commit -m "feat: 空搜索词短路不发请求、零结果返回可操作提示"
```

---

### Task 5: 错误透传与响应包装兼容

> **实施期偏离记录（已批准，`374b4c8`）**：Task 1 里选的 `SimpleClientHttpRequestFactory` 是**计划的错误**。
> 实测它把请求体流式发出，而 `HttpURLConnection` 在「流式请求体 + 401」下会把 error stream 丢成 null
> （403/429/503 都正常，唯独 401 空）。401 = key 无效正是本任务最要透传原文的一档，与「不塌错误」直接冲突。
> 故改用 `JdkClientHttpRequestFactory`（connect 挂 `HttpClient.Builder`、read 挂 factory），并显式补
> `.proxy(ProxySelector.getDefault())`——`java.net.http` 默认不认 `http.proxyHost` 系统属性，
> 漏掉即**静默**失去代理。另补一条 204/空体用例覆盖 `extractValues` 的 null 守卫（此前只有代码没有测试）。
> 下面各步骤的代码块保留原样作为历史记录；实际落地以本说明与提交为准。

三件事：① HTTP 错误必须带**状态码和博查返回的原文**（不能塌成「搜索失败」，否则分不清 key 无效 / 余额不足 / 限流）；② 连不上与服务端错误分开措辞；③ 兼容博查可能的 `{"code":200,"data":{...}}` 外层包装。

**Files:**
- Modify: `.../agent/BochaWebSearchTool.java`
- Test: `.../agent/BochaWebSearchToolTest.java`

- [ ] **Step 1: 写失败的测试**

在测试文件顶部补 `import static org.junit.jupiter.api.Assertions.assertThrows;`，然后追加：

```java
    /** 博查在部分版本里把 SearchResponse 包在 data 字段下，解析须兼容两种形状。 */
    private static final String WRAPPED_IN_DATA = """
            {"code":200,"log_id":"abc","msg":null,
             "data":{"_type":"SearchResponse","webPages":{"value":[
               {"name":"包装标题","url":"https://c.com/3","snippet":"包装片段","siteName":"c.com"}
             ]}}}
            """;

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

            assertTrue(ex.getMessage().contains("503"), "实际=" + ex.getMessage());
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
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: 5 条 FAIL —— 前三条抛的是 `RestClientException` 子类而非 `IllegalStateException`；`parsesResponseWrappedInDataField` 输出「没搜到」；`malformedResponseThrowsWithPreview` 不抛异常（Task 1 的 `extractValues` 遇到未知形状是静默返回空列表）

- [ ] **Step 3: 写实现**

在 `BochaWebSearchTool` 顶部补两个 import：

```java
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
```

把 `webSearch` 里发请求那段（`Map<String, Object> response = restClient.post()...body(MAP_TYPE);`）替换为：

```java
        Map<String, Object> response = execute(body);
```

在 `extractValues` 之前插入 `execute` 方法：

```java
    /**
     * 发请求并转译错误。<b>不重试</b>：失败绝大多数是 key / 额度 / 限流问题，重试只烧额度并拖长回合，
     * 模型自己会换词再试。<b>不塌错误</b>：状态码与博查返回的原文必须透传，否则无法定位是哪一类失败。
     */
    private Map<String, Object> execute(Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(SEARCH_PATH)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String detail = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                        throw new IllegalStateException("博查搜索失败：HTTP "
                                + response.getStatusCode().value()
                                + (detail.isEmpty() ? "" : "，响应：" + preview(detail)));
                    })
                    .body(MAP_TYPE);
        } catch (IllegalStateException e) {
            throw e;                       // onStatus 里已转译过，原样抛出
        } catch (RestClientException e) {
            throw new IllegalStateException("博查搜索连不上（网络不通或超时）：" + e.getMessage(), e);
        }
    }

    /** 截断超长文本，避免把整页响应塞进错误消息。 */
    private static String preview(String raw) {
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }
```

补 import：

```java
import java.nio.charset.StandardCharsets;
```

把 `extractValues` 替换为兼容 `data` 包装的版本：

```java
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractValues(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("博查搜索失败：响应为空");
        }
        // 博查部分版本把 SearchResponse 包在 data 字段下，两种形状都要认。
        Map<String, Object> payload = response;
        if (response.get("data") instanceof Map<?, ?> data) {
            payload = (Map<String, Object>) data;
        }
        Object pages = payload.get("webPages");
        if (!(pages instanceof Map<?, ?> pageMap)) {
            throw new IllegalStateException("博查搜索失败：响应缺少 webPages 字段，响应片段："
                    + preview(String.valueOf(response)));
        }
        Object value = pageMap.get("value");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return (List<Map<String, Object>>) list;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchToolTest'`
Expected: `Tests run: 18, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchToolTest.java
git commit -m "feat: 搜索错误透传状态码与原文，兼容 data 包装响应"
```

---

### Task 6: AgentTools 门控接线与注册名

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:216-219`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 写失败的测试**

创建 `AgentToolsWebSearchWiringTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebSearch 的门控接线：配了 BOCHA_API_KEY 才有这个工具。 */
class AgentToolsWebSearchWiringTest {

    @Test
    void noKey_noTool() {
        assertNull(AgentTools.createWebSearchTool(null, null), "未配 key 时不应创建工具");
        assertNull(AgentTools.createWebSearchTool("   ", null), "空白 key 时不应创建工具");
    }

    @Test
    void withKey_toolCreatedAndCountFromEnv() {
        assertNotNull(AgentTools.createWebSearchTool("fake-key", null), "配了 key 就应创建工具");
        assertNotNull(AgentTools.createWebSearchTool("fake-key", "20"), "带条数配置也应创建成功");
    }

    /** 注册名取 @Tool 注解而非方法名；子 agent 的 allow/deny 按注册名精确匹配，写错会静默失效。 */
    @Test
    void registeredToolNameIsWebSearch() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);

        List<String> names = Arrays.stream(ToolCallbacks.from(tool))
                .map(c -> c.getToolDefinition().name()).toList();

        assertEquals(List.of("WebSearch"), names,
                "注册名必须恰好是 WebSearch（方法名是 webSearch，两者不同），实际=" + names);
    }

    @Test
    void build_withoutBochaKey_stillAssemblesOffline(@TempDir Path root) {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));

        AgentTools.AgentRuntime rt = AgentTools.build(reg, root, new ConversationState());

        assertNotNull(rt.client(), "无搜索 key 时装配仍须成功");
    }

    @Test
    void decoratedToolCallbackKeepsName() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);
        ToolCallback raw = ToolCallbacks.from(tool)[0];

        ToolCallback decorated = new ToolEventCallback(raw, new ConversationState());

        assertTrue("WebSearch".equals(decorated.getToolDefinition().name()),
                "装饰后注册名不能变，实际=" + decorated.getToolDefinition().name());
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: 编译失败，`找不到符号: 方法 createWebSearchTool(java.lang.String,java.lang.String)`

- [ ] **Step 3: 写实现**

在 `AgentTools.java` 中，把现有的（`AgentTools.java:216-219`）：

```java
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(fs, sh, grep, glob, webFetch, askTool)));
        all.add(todoCallback);      // 薄适配器版 TodoWrite（名仍为 "TodoWrite"）
        all.add(reloadableSkill);   // 始终注册可重载 Skill 代理（支持运行期 /reload 从零热加载）
```

替换为：

```java
        // 网络搜索（博查）：BOCHA_API_KEY 配了才注册。没配则工具根本不存在——模型看不到、
        // 也不会去调一个不存在的工具（系统提示的搜索指引段同步为空串，见 webSearchGuide）。
        BochaWebSearchTool webSearch =
                createWebSearchTool(System.getenv("BOCHA_API_KEY"), System.getenv("BOCHA_SEARCH_COUNT"));

        List<Object> rawTools = new ArrayList<>(List.of(fs, sh, grep, glob, webFetch, askTool));
        if (webSearch != null) {
            rawTools.add(webSearch);
        }
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(rawTools.toArray())));
        all.add(todoCallback);      // 薄适配器版 TodoWrite（名仍为 "TodoWrite"）
        all.add(reloadableSkill);   // 始终注册可重载 Skill 代理（支持运行期 /reload 从零热加载）
```

在 `resolveSubagentConcurrency()` 方法之后追加：

```java
    /**
     * 按 env 决定是否创建博查搜索工具：{@code apiKey} 空即返回 null（不注册）。
     * env 的<b>读取</b>在这里，<b>解析语义</b>（回退/钳制）在 {@link BochaWebSearchTool#resolveResultCount}——
     * 与 {@code ModelListEnv.parse} 一样把 env 值作为参数传入，测试才能不依赖真实环境变量。
     */
    static BochaWebSearchTool createWebSearchTool(String apiKey, String countEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return BochaWebSearchTool.builder(apiKey)
                .resultCount(BochaWebSearchTool.resolveResultCount(countEnv))
                .build();
    }
```

同时更新两处失效的计数注释：把类 javadoc（`AgentTools.java:41`）的

```
 * <p>把 6 个社区工具（FileSystem/Shell/Grep/Glob/TodoWrite/SmartWebFetch）用 {@link ToolEventCallback} 装饰后
```

改为：

```
 * <p>把社区工具（FileSystem/Shell/Grep/Glob/TodoWrite/SmartWebFetch）与自写的 WebSearch（博查，
 * 仅在配了 BOCHA_API_KEY 时注册）用 {@link ToolEventCallback} 装饰后
```

把行内注释（原 `AgentTools.java:206`）的

```java
        // org.springframework.ai.support.ToolCallbacks（spring-ai-model）：7 个 @Tool 对象转 ToolCallback。
```

改为：

```java
        // org.springframework.ai.support.ToolCallbacks（spring-ai-model）：@Tool 对象转 ToolCallback。
        // 数量不固定：WebSearch 是条件注册（BOCHA_API_KEY 配了才有），故用 rawTools 列表而非定长参数。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: WebSearch 工具按 BOCHA_API_KEY 门控接入工具链"
```

---

### Task 7: 系统提示注入搜索指引

**Files:**
- Modify: `.../agent/AgentTools.java`（`SYSTEM_TEMPLATE` + 新常量 + `webSearchGuide` + `.param(...)`）
- Modify: `.../agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 写失败的测试**

在 `AgentToolsWebSearchWiringTest` 追加：

```java
    @Test
    void guideIsEmptyWhenNoTool() {
        assertEquals("", AgentTools.webSearchGuide(false),
                "未注册搜索工具时，系统提示不应出现任何搜索相关指引");
    }

    @Test
    void guideMentionsWebSearchAndFetchHandoff() {
        String guide = AgentTools.webSearchGuide(true);

        assertTrue(guide.contains("WebSearch"), "应点名工具，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
        assertTrue(guide.contains("freshness"), "应提醒 freshness 一般别传，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    /** 指引段作为 param 值注入，正文里的花括号不能进模板（会炸 StringTemplate 渲染）。 */
    @Test
    void guideContainsNoTemplateBraces() {
        String guide = AgentTools.webSearchGuide(true);

        assertTrue(!guide.contains("{") && !guide.contains("}"),
                "指引正文不得含花括号，实际=" + guide);
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: 编译失败，`找不到符号: 方法 webSearchGuide(boolean)`

- [ ] **Step 3: 写实现**

在 `AgentTools` 的 `PROJECT_INSTRUCTIONS_KEY` 常量之后追加：

```java
    /** 搜索指引注入的 param 键；与 SYSTEM_TEMPLATE 里的 {WEB_SEARCH_GUIDE} 占位符对应。 */
    private static final String WEB_SEARCH_GUIDE_KEY = "WEB_SEARCH_GUIDE";
```

在 `SYSTEM_TEMPLATE` 里，把这一行：

```
            - 回答简洁，聚焦用户的目标本身。
```

改成：

```
            - 回答简洁，聚焦用户的目标本身。
            {WEB_SEARCH_GUIDE}
```

在 `createWebSearchTool` 方法之后追加：

```java
    /**
     * 搜索指引段：仅在 WebSearch 工具真被注册时才有内容，否则空串——模型看不到指引，
     * 也就不会去调一个不存在的工具。
     *
     * <p>正文<b>不得含花括号</b>：它作为 param 值注入（与 AUTO_MEMORY / PROJECT_INSTRUCTIONS 同法），
     * 花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示渲染。
     */
    static String webSearchGuide(boolean enabled) {
        if (!enabled) {
            return "";
        }
        return """
                - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时，先用 WebSearch 搜索，
                  拿到标题、网址和摘要；需要网页原文细节时，再把该网址交给 webFetch 抓取。
                - WebSearch 的 freshness 参数一般不要传（默认不限时间效果最好），
                  只有明确需要「最近一天 / 最近一周」的最新消息时才用。
                - 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。""";
    }
```

在 `build` 方法里，找到渲染系统提示的那段（`String autoMemoryPrompt = MemoryPrompt.render(...)` 附近），在其后追加：

```java
        // 搜索指引：与工具注册状态严格同步——工具没注册就不给模型任何搜索提示。
        String webSearchGuide = webSearchGuide(webSearch != null);
```

在每个 provider 的 `ChatClient.builder(...)` 的 `defaultSystem` 链里，`.param(PROJECT_INSTRUCTIONS_KEY, projectInstructions)` 之后追加一行：

```java
                            .param(WEB_SEARCH_GUIDE_KEY, webSearchGuide)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 5: 跑全模块回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`。若 `CodingAgentSpikeTest` 里某条真实网络用例超时失败，单独重跑该条确认是偶发（它绑 `DEEPSEEK_API_KEY`、单回合 60s 上限，与本改动无关）。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: 系统提示按搜索工具注册状态注入使用指引"
```

---

### Task 8: 真实 API 冒烟（env 门控）

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchSmokeTest.java`

- [ ] **Step 1: 写测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实博查 API 冒烟。绑 {@code BOCHA_API_KEY}：有 key 则 {@code mvn test} 自动跑，无 key 优雅跳过
 * （门控模式同 {@link CodingAgentSpikeTest}）。需要网络与有效 key，会消耗一次搜索额度。
 */
@EnabledIfEnvironmentVariable(named = "BOCHA_API_KEY", matches = ".+")
class BochaWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        BochaWebSearchTool tool = BochaWebSearchTool.builder(System.getenv("BOCHA_API_KEY"))
                .resultCount(3)
                .build();

        String out = tool.webSearch("Spring AI 框架", null, null);

        System.out.println("[smoke] 博查搜索返回：\n" + out);
        assertTrue(out.contains("找到"), "应返回结果列表而非零结果提示，实际=" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn -pl springai-code-tui test -Dtest='BochaWebSearchSmokeTest'`
Expected：
- 没配 `BOCHA_API_KEY` → `Tests run: 0, Skipped: 1`（正常，不是失败）
- 配了 key → `Tests run: 1, Failures: 0`，并在输出里打印真实搜索结果

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BochaWebSearchSmokeTest.java
git commit -m "test: 博查搜索真实 API 冒烟（BOCHA_API_KEY 门控）"
```

---

### Task 9: 文档同步

**Files:**
- Modify: `springai-code-tui/src/package/bin/config.env.example`
- Modify: `springai-code-tui/README.md:11`（工具清单）
- Modify: `springai-code-tui/README.md:30`（安全披露）

- [ ] **Step 1: 改 config.env.example**

在「可选：自定义模型清单」区块之前插入一个新区块：

```bash
# ────────────── 可选：网络搜索（博查 Bocha） ──────────────
# 配了 key 才启用 WebSearch 工具；不配则智能体没有联网搜索能力（webFetch 抓取不受影响）。
# key 申请：https://open.bochaai.com/
#BOCHA_API_KEY=你的key
# 单次搜索返回条数，范围 [1, 50]，默认 8。不暴露给模型（避免乱开条数烧搜索额度）。
#BOCHA_SEARCH_COUNT=8
```

- [ ] **Step 2: 改 README 工具清单**

把 `README.md:11` 里的 `` `SmartWebFetchTool`（联网抓取）、`` 替换为：

```
`SmartWebFetchTool`（联网抓取网页正文）、`WebSearch`（联网搜索，博查 Bocha API，需配 `BOCHA_API_KEY`，不配则该工具不注册）、
```

- [ ] **Step 3: 改 README 安全披露**

把 `README.md:30` 整行：

```
- **联网出口无过滤**：`SmartWebFetchTool` 可发起对外 HTTP 请求，无域名白名单/出网限制——被提示注入时可能外泄本地读到的内容。
```

替换为：

```
- **联网出口无过滤**：`SmartWebFetchTool`（抓取任意网址）与 `WebSearch`（把搜索词发给第三方搜索服务博查）都可发起对外 HTTP 请求，无域名白名单/出网限制——被提示注入时可能外泄本地读到的内容。`WebSearch` 额外意味着智能体构造的查询词会离开本机。
```

- [ ] **Step 4: 人工核对**

Run: `git diff --stat`
Expected: 三个文件有改动，且 `grep -n "BOCHA_API_KEY" springai-code-tui/README.md springai-code-tui/src/package/bin/config.env.example` 两个文件都有命中。

- [ ] **Step 5: 最终全量回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/README.md springai-code-tui/src/package/bin/config.env.example
git commit -m "docs: 补充 WebSearch 配置说明与联网出口安全披露"
```

---

## 完成标准

- `mvn -pl springai-code-tui test` 通过（`BochaWebSearchSmokeTest` 在无 key 时显示为 skipped 属正常）
- 未配 `BOCHA_API_KEY` 时：工具不注册、系统提示无搜索指引、装配与既有行为完全不变
- 配了 `BOCHA_API_KEY` 时：TUI 里能看到 `WebSearch` 工具活动行，模型能搜索并把 URL 交给 `webFetch`

## 本计划范围外（spec 已记为已知取舍）

- 不改 `src/main/resources/agents/*.md`：`WebSearch` 只会落到 `general-purpose` 子 agent，`explore` / `plan` / `bash` 的 allow 列表不含它。要给 `plan` 开搜索得连 `webFetch` 一起开，属独立的子 agent 能力调整。
- 不做 `/search` 斜杠命令、不做运行期开关、不做多搜索后端抽象。
- **不给搜索结果加任何外置逻辑**（这是一条「不做」，故无对应任务）。搜索结果是纯文本 tool 结果，
  按现行策略（文本正文永不外置，只清媒体/二进制字节）留在会话里，由 400k 阈值的滚动摘要正常吸收。
  它看起来像「大块外部文本」，很容易被后来者误加外置——明确记在这里。
