package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.time.Duration;
import java.util.List;

/**
 * DeepSeek provider（现役、默认激活）。key 缺失即 unavailable。
 * 默认模型 deepseek-v4-pro（强推理；另有 deepseek-v4-flash 非思考款，旧 deepseek-chat/reasoner 2026-07-24 停用）。
 */
public final class DeepSeekProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→内置默认；配了→覆盖
    private volatile ChatModel chatModel;   // 懒建，单例

    public DeepSeekProvider(String apiKey) {
        this(apiKey, null);
    }

    public DeepSeekProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    @Override public String id() { return "deepseek"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("DeepSeek 不可用：未配置 DEEPSEEK_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // DeepSeek 走 Spring RestClient(阻塞/子 agent) + WebClient(流式/主 agent)，无 OkHttp callTimeout；
            // 默认无任何超时=可能永久挂死。给两条路径都配 connect/read（统一 CODETUI_LLM_READ_TIMEOUT_SECONDS）。
            Duration read = TIMEOUTS.readTimeout();
            Duration connect = TIMEOUTS.connectTimeout();

            // 阻塞：SimpleClientHttpRequestFactory 的 connect/read（read=整体响应读取上限，阻塞调用足够）。
            org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(connect);
            rf.setReadTimeout(read);
            org.springframework.web.client.RestClient.Builder restBuilder =
                    org.springframework.web.client.RestClient.builder().requestFactory(rf);

            // 流式：reactor-netty responseTimeout=两次网络读之间的最大间隔（等价 read 语义，不会砍正常流）。
            reactor.netty.http.client.HttpClient nettyHttp =
                    reactor.netty.http.client.HttpClient.create().responseTimeout(read);
            org.springframework.web.reactive.function.client.WebClient.Builder webBuilder =
                    org.springframework.web.reactive.function.client.WebClient.builder()
                            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyHttp));

            DeepSeekApi api = DeepSeekApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .restClientBuilder(restBuilder)
                    .webClientBuilder(webBuilder)
                    .build();
            m = DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .options(DeepSeekChatOptions.builder().model(DEFAULT_MODEL).build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return DeepSeekChatOptions.builder().model(modelId).build();
    }

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
