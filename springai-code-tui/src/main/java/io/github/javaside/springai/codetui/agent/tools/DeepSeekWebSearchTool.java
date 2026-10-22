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

/**
 * DeepSeek Web Search API 工具 —— 给模型提供联网搜索能力，第四家搜索后端（与博查/Brave/智谱共存）。
 *
 * <p><b>为何工具只留一个 query 参数</b>：DeepSeek 的时间/域名/语言过滤没有独立请求参数，
 * 全靠搜索语法（{@code site:} / {@code after:} 等）直接写进 query——这是 API 的设计而非缺失。
 * 在工具层加 freshness/include 参数就得本地翻译成语法（如 oneDay → after:&lt;今天-1天&gt;），
 * 枚举到日期串的映射有时区臆测成分，猜错还没人发现；不如把语法教给模型（写在 @Tool 描述里），
 * 与 API 形状保持一对一。
 *
 * <p><b>为何不渲染 rerank_score / authority</b>：质量信号模型可从 URL 与来源自行推断，
 * 渲染纯属 token 噪音（博查/智谱先例也不渲染质量分）。
 *
 * <p><b>计费</b>：每 1,000 次成功请求 5 元，零结果与失败请求不计费——按次而非按 token，
 * 与 LLM 余额同一个账户（key 复用 {@code DEEPSEEK_API_KEY}，注册门控见 AgentTools.deepSeekSearchWanted）。
 *
 * <p><b>超时不接 LlmTimeouts</b>：搜索是一次性 REST 调用，超 20s 就该失败（与前三家同一决策）。
 */
public final class DeepSeekWebSearchTool {

    /** DeepSeek API 默认端点；{@code baseUrl(..)} 仅供测试打本地 stub server，不暴露 env。 */
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String SEARCH_PATH = "/v1/tools/search";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** 默认返回条数；DeepSeek 侧 count 允许 1–50。对齐博查/智谱默认：同为国内向长摘要源。 */
    static final int DEFAULT_COUNT = 8;
    static final int MAX_COUNT = 50;

    /**
     * 单条摘要渲染上限。文档宣称 summary 600-800 字符，但 2026-10-22 真机实测单条可达 2500+
     * （内嵌 markdown 标题）——默认 8 条若全文渲染，一次搜索可吃掉上万 token 上下文。
     * 摘要的职责是让模型挑 URL，不是搬运正文（正文走 webFetch），超线截断。
     */
    private static final int MAX_SUMMARY_CHARS = 800;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final int resultCount;

    private DeepSeekWebSearchTool(String apiKey, String baseUrl, int resultCount) {
        // JdkClientHttpRequestFactory + 显式 ProxySelector：与博查/智谱同款。HttpURLConnection 在
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
    }

    public static Builder builder(String apiKey) {
        return new Builder(apiKey);
    }

    @Tool(name = "DeepSeekWebSearch", description = """
            用 DeepSeek 搜索引擎搜索互联网，返回网页标题、网址、摘要（约 600-800 字符）、站点名与发布时间。

            用法：
            - 中英文内容都可以搜；需要项目之外最新信息（库的用法、报错含义、版本变更、新闻）时用它。
            - 时间、域名、语言过滤没有独立参数，用搜索语法直接写进搜索词，可组合多个：
              site:github.com（限定域名）、-site:zhihu.com（排除域名）、"精确短语"、-排除词、
              lang:zh,en（限定语言）、after:2025-01-01 / before:2026（时间范围）。
            - 时间过滤一般别加：默认不限时间效果最好，硬指区间反而容易搜空。
            - 它只返回摘要。需要网页原文细节时，把结果里的网址交给 webFetch 工具去抓取。
            - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
            """)
    public String webSearch(
            @ToolParam(description = "搜索词，支持组合搜索语法：site:github.com、-site:zhihu.com、" +
                    "\"精确短语\"、-排除词、lang:zh,en、after:2025-01-01、before:2026。搜最新资料时可在词里带上年份。")
            String query) {

        if (query == null || query.isBlank()) {
            return "搜索词为空，请给出要搜索的内容。";
        }

        // 恒只发 query + count：need_content 不发（默认 false，summary 已是给模型读的长摘要，
        // 原文走 webFetch）；ori_question/localization 不发（query 本身已含意图，多一个字段多一处猜测）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.trim());
        body.put("count", resultCount);

        Map<String, Object> response = execute(body);

        List<Map<String, Object>> values = extractResults(response);
        if (values.isEmpty()) {
            return "没搜到「" + query.trim() + "」的相关结果。建议换一组关键词或同义词，"
                    + "或去掉搜索词里的 site: / after: 等限定语法再试一次。";
        }
        return render(query.trim(), values);
    }

    /**
     * 发请求并转译错误。<b>不重试</b>：失败绝大多数是 key / 余额 / 限流问题，重试只烧钱并拖长回合。
     * <b>不塌错误</b>：DeepSeek 错误为 OpenAI 兼容形状（error.message 可读），状态码与原文必须透传，
     * 402（余额不足）/429（限速）等语义留给读消息的人判断，不做 code 分支。
     */
    private Map<String, Object> execute(Map<String, Object> body) {
        try {
            return restClient.post()
                    .uri(SEARCH_PATH)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String detail = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                        throw new IllegalStateException("DeepSeek 搜索失败：HTTP "
                                + response.getStatusCode().value()
                                + (detail.isEmpty() ? "" : "，响应：" + preview(detail)));
                    })
                    .body(MAP_TYPE);
        } catch (IllegalStateException e) {
            throw e;                       // onStatus 里已转译过，原样抛出
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("DeepSeek 搜索连不上（网络不通或超时）：" + e.getMessage(), e);
        } catch (RestClientException e) {
            // 连接成功但响应无法解析：常见于代理 / 门户 / WAF 的 HTML 拦截页，不能并进「连不上」。
            throw new IllegalStateException("DeepSeek 搜索失败：响应无法解析（Content-Type 可能不是 JSON，"
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
            throw new IllegalStateException("DeepSeek 搜索失败：响应为空");
        }
        Object results = response.get("results");
        if (!(results instanceof List<?> list)) {
            throw new IllegalStateException("DeepSeek 搜索失败：响应缺少 results 字段，响应片段："
                    + preview(String.valueOf(response)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> item) {
                out.add((Map<String, Object>) item);
            }
        }
        if (out.isEmpty() && !list.isEmpty()) {
            throw new IllegalStateException("DeepSeek 搜索失败：results 里没有可识别的结果对象，响应片段："
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
            String url = str(item.get("url"));
            String site = str(item.get("site_name"));
            String date = shortDate(str(item.get("date")));
            // summary（600-800 字符，为模型阅读而生）优先；snippet（100-200 字符）作兜底。
            String text = str(item.get("summary"));
            if (text.isEmpty()) {
                text = str(item.get("snippet"));
            }

            sb.append('\n').append(index++).append(". ").append(title.isEmpty() ? url : title);
            String meta = site;
            if (!date.isEmpty()) {
                meta = meta.isEmpty() ? date : site + " · " + date;
            }
            if (!meta.isEmpty()) {
                sb.append(" — ").append(meta);
            }
            sb.append('\n').append("   ").append(url).append('\n');
            if (!text.isEmpty()) {
                sb.append("   ").append(clip(text, MAX_SUMMARY_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 摘要渲染截断（理由见 {@link #MAX_SUMMARY_CHARS}）；与错误预览 {@link #preview} 上限不同，不共用。 */
    private static String clip(String raw, int limit) {
        return raw.length() <= limit ? raw : raw.substring(0, limit) + "…";
    }

    /** RFC 3339（如 2026-06-01T00:00:00+00:00）截到天；短于 10 位则原样返回。 */
    private static String shortDate(String raw) {
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 解析 {@code DEEPSEEK_SEARCH_COUNT}：缺失 / 非数字回退 {@link #DEFAULT_COUNT}，越界钳到 {@code [1, MAX_COUNT]}。
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

    /** 链式构造；{@code apiKey} 必填非空（是否创建工具由 AgentTools 按 env 决定）。 */
    public static final class Builder {
        private final String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private int resultCount = DEFAULT_COUNT;

        private Builder(String apiKey) {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("DeepSeek API key 不能为空");
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

        public DeepSeekWebSearchTool build() {
            return new DeepSeekWebSearchTool(apiKey, baseUrl, resultCount);
        }
    }
}
