package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionModels;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * OpenAI provider（Spring AI 2.0 spring-ai-openai）。key 缺失即 unavailable。
 *
 * <p>构建（已实测网络无关）：key/base-url 直接设在 {@link OpenAiChatOptions} builder 上，baseUrl 为空时
 * 用框架内置默认（{@code https://api.openai.com}），配了则覆盖；{@code OpenAiChatModel.build()} 自派生 client。
 */
public final class OpenAiProvider implements LlmProvider {

    // 2026-07-09 发布 GPT-5.6 家族（新命名：数字=代，Sol/Terra/Luna=能力档）。
    // 裸 gpt-5.6 别名路由到 Sol；要确定性路由用带后缀的显式 id。旧 gpt-5.5/5.4 保留作回退。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("gpt-5.6-sol",   "gpt-5.6-sol",   "旗舰 · 复杂推理/编码/安全"),
            new ModelOption("gpt-5.6-terra", "gpt-5.6-terra", "均衡 · 中档"),
            new ModelOption("gpt-5.6-luna",  "gpt-5.6-luna",  "快 · 便宜"),
            new ModelOption("gpt-5.5", "gpt-5.5", "上一代旗舰"),
            new ModelOption("gpt-5.4", "gpt-5.4", "上一代 · 快"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→框架内置默认；配了→覆盖
    private final List<ModelOption> models;   // OPENAI_MODELS 解析结果；未配置=内置 MODELS
    private volatile ChatModel chatModel;

    public OpenAiProvider(String apiKey) {
        this(apiKey, null);
    }

    public OpenAiProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public OpenAiProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }

    @Override public String id() { return "openai"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("OpenAI 不可用：未配置 OPENAI_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // 超时必须设在 SDK client 的 ClientOptions 上（见 OpenAiTimeouts）；apiKey/baseUrl 也随 client。
            // 流式走 async client、阻塞（子 agent）走 sync client——两个都要带超时，否则流式仍吃 SDK 默认 60s/10min。
            com.openai.core.Timeout timeout = OpenAiTimeouts.of(TIMEOUTS);
            var syncB = com.openai.client.okhttp.OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(timeout);
            var asyncB = com.openai.client.okhttp.OpenAIOkHttpClientAsync.builder().apiKey(apiKey).timeout(timeout);
            if (!baseUrl.isEmpty()) {   // 空→SDK 内置默认 https://api.openai.com/v1
                syncB.baseUrl(baseUrl);
                asyncB.baseUrl(baseUrl);
            }
            m = OpenAiChatModel.builder()
                    .openAiClient(syncB.build())
                    .openAiClientAsync(asyncB.build())
                    .options(OpenAiChatOptions.builder().model(defaultModel()).build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return OpenAiChatOptions.builder().model(modelId).build();
    }

    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }

    /**
     * 视觉能力按<b>模型</b>判定，不按 provider——同一家既有视觉模型也有纯文本模型。
     * 名单与「未知即不支持」的理由见 {@link VisionModels}。
     */
    @Override
    public ModelCapabilities capabilities(String modelId) {
        return new ModelCapabilities(VisionModels.supportsImage(modelId), false);
    }
}
