package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * 通义千问 Qwen provider（阿里云百炼 DashScope，复用 spring-ai-openai）。key 缺失即 unavailable。
 *
 * <p><b>为何走 OpenAI 通路而非 DashScope SDK</b>：百炼提供 OpenAI Chat Completions <b>兼容模式</b>
 * ——请求/响应体一致、支持 SSE 流式、{@code tools}/{@code tool_calls} 走 OpenAI 标准格式，
 * 与 {@link ZhipuProvider} 同理，复用已验证的 {@link OpenAiChatModel} 通路即可，无需桥接 DashScope SDK。
 *
 * <p><b>baseUrl</b>：兼容模式路径是 {@code /compatible-mode/v1/chat/completions}。OpenAI Java SDK 覆盖
 * baseUrl 后仅追加 {@code chat/completions}（不强制拼 /v1，见 ZhipuProvider 注释），故 baseUrl 须写到
 * {@code .../compatible-mode/v1}。新加坡/国际站或 Coding Plan（{@code https://coding.dashscope.aliyuncs.com/v1}，
 * sk-sp- 专属 key）可经 DASHSCOPE_BASE_URL 覆盖。
 */
public final class QwenProvider implements LlmProvider {

    // 国内百炼兼容模式端点；国际站/新加坡为 https://dashscope-intl.aliyuncs.com/compatible-mode/v1。
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen3.7-max";
    // 2026-07 百炼在售 Qwen3.7 系：qwen3.7-max 旗舰 / qwen3.7-plus 均衡 / qwen3.6-flash 快档；
    // qwen3-coder-next 为现役编码专项模型。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("qwen3.7-max",      "qwen3.7-max",      "旗舰 · 复杂推理/编码"),
            new ModelOption("qwen3.7-plus",     "qwen3.7-plus",     "均衡 · 中档"),
            new ModelOption("qwen3.6-flash",    "qwen3.6-flash",    "快 · 便宜"),
            new ModelOption("qwen3-coder-next", "qwen3-coder-next", "编码专项 · Agentic"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→百炼内置默认；配了→覆盖（走国际站/Coding Plan/代理）
    private volatile ChatModel chatModel;   // 懒建，单例

    public QwenProvider(String apiKey) {
        this(apiKey, null);
    }

    public QwenProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
    }

    @Override public String id() { return "qwen"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("千问 不可用：未配置 DASHSCOPE_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // 与智谱不同：千问不能直接用 OpenAIOkHttpClient.builder()——须在 HTTP 层插一个 SSE 归一化装饰器
            // 修流式 tool_calls 空串 id 分片（见 QwenSseNormalizingHttpClient javadoc），而该 builder 不暴露
            // httpClient 钩子，故走 ClientOptions + OpenAIClient(Async)Impl 手工装配（SDK 公开 API）。
            // 超时同样两处都设：OkHttpClient（真正生效的 socket 超时）与 ClientOptions（SDK 记账）。
            com.openai.core.Timeout timeout = OpenAiTimeouts.of(TIMEOUTS);
            com.openai.core.http.HttpClient http = new QwenSseNormalizingHttpClient(
                    com.openai.client.okhttp.OkHttpClient.builder().timeout(timeout).build());
            com.openai.core.ClientOptions options = com.openai.core.ClientOptions.builder()
                    .httpClient(http)
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .timeout(timeout)
                    .build();
            m = OpenAiChatModel.builder()
                    .openAiClient(new com.openai.client.OpenAIClientImpl(options))
                    .openAiClientAsync(new com.openai.client.OpenAIClientAsyncImpl(options))
                    .options(OpenAiChatOptions.builder().model(DEFAULT_MODEL).build())
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return OpenAiChatOptions.builder().model(modelId).build();
    }

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
