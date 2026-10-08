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
