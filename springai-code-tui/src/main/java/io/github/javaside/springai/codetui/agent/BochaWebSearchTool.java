package io.github.javaside.springai.codetui.agent;

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

    /** 博查侧 include 域名上限；超出部分截断（截断比让整次搜索失败合理）。 */
    private static final int MAX_INCLUDE_DOMAINS = 100;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final int resultCount;

    private BochaWebSearchTool(String apiKey, String baseUrl, int resultCount) {
        // 不用 SimpleClientHttpRequestFactory：它把请求体流式发出，而 HttpURLConnection 在「流式请求体 + 401」
        // 这一组合下会把 error stream 丢成 null（请求体已流出去、没法重放做认证握手，JDK 索性弃掉响应体）。
        // 实测 403/429/503 都能拿到 body，唯独 401 空——而 401 = key 无效恰是最需要看到博查原文的一档，
        // 塌成光秃秃的 "HTTP 401" 就没了排查价值。JdkClientHttpRequestFactory 各档都能拿到 body；
        // 代价是 java.net.http 默认不认 http.proxyHost 等系统代理属性，故显式补 ProxySelector 保持行为等价。
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

        if (query == null || query.isBlank()) {
            return "搜索词为空，请给出要搜索的内容。";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.trim());
        body.put("freshness", (freshness == null || freshness.isBlank()) ? "noLimit" : freshness.trim());
        body.put("summary", true);
        body.put("count", resultCount);

        String includeParam = joinInclude(include);
        if (!includeParam.isEmpty()) {
            body.put("include", includeParam);
        }

        Map<String, Object> response = execute(body);

        List<Map<String, Object>> values = extractValues(response);
        if (values.isEmpty()) {
            return "没搜到「" + query.trim() + "」的相关结果。建议换一组关键词或同义词，"
                    + "或去掉 freshness 时间限制再试一次。";
        }
        return render(query.trim(), values);
    }

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
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("博查搜索连不上（网络不通或超时）：" + e.getMessage(), e);
        } catch (RestClientException e) {
            // 连接成功但响应无法解析：常见于代理 / 门户 / WAF 返回的 HTML 拦截页（Content-Type 非 JSON）。
            // 绝不能并进「连不上」——连接其实是通的，那句话会把排查方向带偏。
            throw new IllegalStateException("博查搜索失败：响应无法解析（Content-Type 可能不是 JSON，"
                    + "疑似代理或门户拦截页）：" + preview(String.valueOf(e.getMessage())), e);
        }
    }

    /** 截断超长文本，避免把整页响应塞进错误消息。 */
    private static String preview(String raw) {
        return raw.length() <= 300 ? raw : raw.substring(0, 300) + "…";
    }

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
        // value 缺失 / 不是数组时按「零结果」降级而非抛异常：真实 API 在无结果时是否总会带上 value 字段
        // 没有把握，抛异常有误伤风险。代价是结构畸形会被当成正常零结果告诉模型——已知取舍，非疏漏。
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> item) {
                results.add((Map<String, Object>) item);
            }
        }
        if (results.isEmpty() && !list.isEmpty()) {
            throw new IllegalStateException("博查搜索失败：webPages.value 里没有可识别的结果对象，响应片段："
                    + preview(String.valueOf(response)));
        }
        return results;
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

    /** ISO 8601（如 2026-01-30T07:19:14+08:00）截到天；短于 10 位则原样返回。 */
    private static String shortDate(String raw) {
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

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
