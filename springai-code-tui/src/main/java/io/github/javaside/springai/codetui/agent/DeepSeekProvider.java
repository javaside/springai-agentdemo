package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionModels;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
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
    // 首项即默认模型（*_MODELS 未配置时的回退清单，约定第一项为默认）。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"),
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-flash-vision-exp", "deepseek-v4-flash-vision-exp",
                    "视觉 · 实验 · 快（图最多 384 token/张）"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→内置默认；配了→覆盖
    private final List<ModelOption> models;   // DEEPSEEK_MODELS 解析结果；未配置=内置 MODELS
    private volatile ChatModel chatModel;   // 懒建，单例
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry =
            new io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry();

    public DeepSeekProvider(String apiKey) {
        this(apiKey, null);
    }

    public DeepSeekProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public DeepSeekProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }

    @Override public String id() { return "deepseek"; }

    /** 视觉传输通道：inline=base64 内联（默认），files=Files API file_id。 */
    enum VisionTransport { INLINE, FILES }

    /** 严格等于 files（忽略大小写、不去空格）才走 Files API；其余一律内联。纯函数供单测。 */
    static VisionTransport visionTransportFor(String envValue) {
        return envValue != null && envValue.equalsIgnoreCase("files")
                ? VisionTransport.FILES : VisionTransport.INLINE;
    }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("DeepSeek 不可用：未配置 DEEPSEEK_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // DeepSeek 走 Spring RestClient(阻塞/子 agent) + WebClient(流式/主 agent)、默认无超时=可能永久挂死。
            // 关键：必须沿用「Spring 检测出的默认同款」HTTP 栈只加超时，换栈会破坏真实 SSE（换 Simple/reactor-netty 都实测挂死）。
            //   阻塞：默认检测到 httpclient5 → HttpComponentsClientHttpRequestFactory（本类同款）+ setReadTimeout；
            //   流式：无 reactor-netty/jetty/httpcore5-reactive → 默认 JdkClientHttpConnector（本类同款）+ setReadTimeout。
            Duration read = TIMEOUTS.readTimeout();
            Duration connect = TIMEOUTS.connectTimeout();

            org.springframework.http.client.HttpComponentsClientHttpRequestFactory rf =
                    new org.springframework.http.client.HttpComponentsClientHttpRequestFactory();
            rf.setReadTimeout(read);
            rf.setConnectionRequestTimeout(connect);
            var restBuilder = org.springframework.web.client.RestClient.builder().requestFactory(rf);

            java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder().connectTimeout(connect).build();
            var connector = new org.springframework.http.client.reactive.JdkClientHttpConnector(jdk);
            connector.setReadTimeout(read);
            var webBuilder = org.springframework.web.reactive.function.client.WebClient.builder().clientConnector(connector);

            // 视觉传输通道：files 走 Files API（sha 幂等上传 → file_id），失败自动降级内联；默认内联。
            java.util.function.BiFunction<byte[], String, java.util.Optional<String>> fileUploader = null;
            if (visionTransportFor(System.getenv("DEEPSEEK_VISION_TRANSPORT")) == VisionTransport.FILES) {
                io.github.javaside.springai.codetui.agent.media.DeepSeekFileStore store =
                        new io.github.javaside.springai.codetui.agent.media.DeepSeekFileStore(form -> {
                            org.springframework.web.client.RestClient rc = org.springframework.web.client.RestClient.builder()
                                    .baseUrl(baseUrl)
                                    .defaultHeader("Authorization", "Bearer " + apiKey)
                                    .requestFactory(rf)
                                    .build();
                            return rc.post().uri("/files")
                                    .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                                    .body(form)
                                    .retrieve()
                                    .body(String.class);
                        });
                fileUploader = (bytes, filename) -> store.fileIdFor(bytes, filename);
            }

            ChatModel defaultDelegate = buildDelegate(restBuilder, webBuilder, ThinkingConfig.defaults());
            m = new DeepSeekThinkingChatModel(defaultDelegate,
                    config -> buildDelegate(restBuilder, webBuilder, config), visionRegistry, fileUploader);
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return DeepSeekChatOptions.builder().model(modelId).build();
    }

    @Override
    public ThinkingCapabilities thinkingCapabilities(String modelId) {
        return ThinkingCapabilities.effort(true, List.of("low", "high", "max"));
    }

    @Override
    public ChatOptions options(String modelId, ThinkingConfig config) {
        thinkingCapabilities(modelId).validate(config);
        if (config.mode() == ThinkingMode.DEFAULT) {
            return options(modelId);
        }
        return new DeepSeekThinkingChatOptions(
                DeepSeekChatOptions.builder().model(modelId).build(), config);
    }

    private ChatModel buildDelegate(org.springframework.web.client.RestClient.Builder baseRest,
                                    org.springframework.web.reactive.function.client.WebClient.Builder baseWeb,
                                    ThinkingConfig config) {
        var rest = baseRest.clone();
        var web = baseWeb.clone();
        if (config.mode() != ThinkingMode.DEFAULT) {
            rest.requestInterceptor((request, body, execution) ->
                    execution.execute(request, DeepSeekThinkingBodyCodec.decorate(body, config, visionRegistry)));
        }
        // 流式请求一律注入 stream_options.include_usage=true：DeepSeek 流式默认不带 usage（与 OpenAI 不同，
        // spring-ai-deepseek 的 ChatCompletionRequest 又没有 stream_options 字段、也不会自动加），不注入则 token
        // 采集器永远拿不到计费输入 → 缓存命中率恒为空。思考配置在 decorateStreaming 里叠加（DEFAULT 时仅注入
        // stream_options）。The provider's WebClient builder already owns the working JDK connector; mutating its
        // codecs cannot see the selected request config, so install a fixed connector below.
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                .connectTimeout(TIMEOUTS.connectTimeout()).build();
        var nativeConnector = new org.springframework.http.client.reactive.JdkClientHttpConnector(jdk);
        nativeConnector.setReadTimeout(TIMEOUTS.readTimeout());
        web.clientConnector(new DeepSeekThinkingClientHttpConnector(nativeConnector, config, visionRegistry));
        DeepSeekApi api = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(rest)
                .webClientBuilder(web)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model(defaultModel()).build())
                .build();
    }

    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }

    /**
     * DeepSeek 视觉能力按模型名单判定（见 {@link VisionModels}）：目前仅 deepseek-v4-flash-vision-exp
     * 支持图片输入。这里显式覆写让判定清晰化，将来名单变化只需动 VisionModels。
     */
    @Override
    public ModelCapabilities capabilities(String modelId) {
        return new ModelCapabilities(VisionModels.supportsImage(modelId), false);
    }
}
