package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionModels;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * 智谱 GLM provider（Spring AI 2.0 已不再内置智谱，故复用 spring-ai-openai）。key 缺失即 unavailable。
 *
 * <p><b>为何走 OpenAI 通路而非自研 ChatModel</b>：智谱 GLM 的 API 与 OpenAI Chat Completions <b>完全兼容</b>
 * ——请求/响应体一致、支持 SSE 流式、{@code tools}/{@code tool_calls} 走 OpenAI 标准格式。CodingAgent 依赖
 * 流式 + Spring AI tool-calling，复用已验证的 {@link OpenAiChatModel} 通路即可白嫖，无需桥接智谱 SDK。
 *
 * <p><b>baseUrl 的坑</b>：智谱路径是 {@code /api/paas/v4/chat/completions}（v4，非 v1）。实测 OpenAI Java SDK
 * <b>不会</b>强制拼 {@code /v1}（v1 只在 SDK 默认 base 常量里），覆盖 baseUrl 后仅追加 {@code chat/completions}，
 * 故 baseUrl 必须写到 {@code .../api/paas/v4}（不带尾斜杠也不带 /v1），最终精确打 {@code .../v4/chat/completions}。
 * 模型名须全小写（{@code glm-5.2}），大写会 404。
 */
public final class ZhipuProvider implements LlmProvider {

    // 国内官方端点；海外/Coding Plan 可经 ZHIPU_BASE_URL 覆盖为 https://api.z.ai/api/paas/v4。
    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    // 2026 在售 GLM-5 系：glm-5.2 旗舰（Agentic Coding，对标 Opus 4.8）/ glm-5.1 长任务 / glm-5-turbo 快档。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("glm-5.2",     "glm-5.2",     "旗舰 · Agentic 编码/长上下文"),
            new ModelOption("glm-5.1",     "glm-5.1",     "长任务 · 自规划"),
            new ModelOption("glm-5-turbo", "glm-5-turbo", "快 · 便宜"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→智谱内置默认；配了→覆盖（走 z.ai / 代理）
    private final List<ModelOption> models;   // ZHIPU_MODELS 解析结果；未配置=内置 MODELS
    private volatile ChatModel chatModel;   // 懒建，单例

    public ZhipuProvider(String apiKey) {
        this(apiKey, null);
    }

    public ZhipuProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public ZhipuProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }

    @Override public String id() { return "zhipu"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("智谱 不可用：未配置 ZHIPU_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // 超时设在 SDK client 的 ClientOptions（见 OpenAiTimeouts）；智谱 baseUrl 恒非空（/api/paas/v4）。
            // async client 供主 agent 流式、sync client 供子 agent 阻塞——两个都带超时。
            com.openai.core.Timeout timeout = OpenAiTimeouts.of(TIMEOUTS);
            var syncClient = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                    .apiKey(apiKey).baseUrl(baseUrl).timeout(timeout).build();
            var asyncClient = com.openai.client.okhttp.OpenAIOkHttpClientAsync.builder()
                    .apiKey(apiKey).baseUrl(baseUrl).timeout(timeout).build();
            m = OpenAiChatModel.builder()
                    .openAiClient(syncClient)
                    .openAiClientAsync(asyncClient)
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

    @Override
    public ThinkingCapabilities thinkingCapabilities(String modelId) {
        if ("glm-5.2".equals(modelId)) {
            return ThinkingCapabilities.effort(true, List.of("high", "max"));
        }
        return ThinkingCapabilities.toggle(true);
    }

    @Override
    public ChatOptions options(String modelId, ThinkingConfig config) {
        thinkingCapabilities(modelId).validate(config);
        if (config.mode() == ThinkingMode.DEFAULT) {
            return options(modelId);
        }
        java.util.Map<String, Object> thinking = java.util.Map.of(
                "type", config.mode() == ThinkingMode.ENABLED ? "enabled" : "disabled");
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(modelId)
                .extraBody(java.util.Map.of("thinking", thinking));
        if (config.effort() != null) {
            builder.reasoningEffort(config.effort());
        }
        return builder.build();
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
