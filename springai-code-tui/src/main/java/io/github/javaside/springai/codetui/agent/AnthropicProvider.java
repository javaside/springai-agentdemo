package io.github.javaside.springai.codetui.agent;

import com.anthropic.models.messages.Model;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.util.List;

/**
 * Anthropic provider（Spring AI 2.0 spring-ai-anthropic）。key 缺失即 unavailable。
 *
 * <p>注意：Anthropic 的 {@code model()} 收 typed 枚举 {@link Model}，用静态 {@code Model.of(String)}；
 * 且 {@code max_tokens} 为必填，故默认 options 与每请求 options 都显式带 {@link #MAX_TOKENS}。
 * key/base-url 直接设在 {@link AnthropicChatOptions} builder 上：baseUrl 为空时不设，用框架内置默认；配了则覆盖。
 */
public final class AnthropicProvider implements LlmProvider {

    private static final int MAX_TOKENS = 8192;   // Anthropic 必填；可调
    // 2026-07 在售编号：fable-5 / sonnet-5 / opus-4-8 / haiku-4-5（旧 sonnet-4-5、opus-4-5 是不存在的跳号）。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("claude-opus-4-8",  "claude-opus-4-8",  "最强推理 · 默认"),
            new ModelOption("claude-fable-5",   "claude-fable-5",   "最新旗舰"),
            new ModelOption("claude-sonnet-5",  "claude-sonnet-5",  "均衡 · 日常编码"),
            new ModelOption("claude-haiku-4-5", "claude-haiku-4-5", "快 · 便宜"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→框架内置默认；配了→覆盖
    private final List<ModelOption> models;   // ANTHROPIC_MODELS 解析结果；未配置=内置 MODELS
    private volatile ChatModel chatModel;

    public AnthropicProvider(String apiKey) {
        this(apiKey, null);
    }

    public AnthropicProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public AnthropicProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }

    @Override public String id() { return "anthropic"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("Anthropic 不可用：未配置 ANTHROPIC_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // 超时必须设在 SDK client 的 ClientOptions（同 OpenAI 家族：http-client customizer 会被每请求覆盖）。
            // request=ZERO 禁 callTimeout、read 可配。async client 供主 agent 流式、sync 供子 agent 阻塞。
            com.anthropic.core.Timeout timeout = com.anthropic.core.Timeout.builder()
                    .connect(TIMEOUTS.connectTimeout())
                    .read(TIMEOUTS.readTimeout())
                    .write(TIMEOUTS.readTimeout())
                    .request(Duration.ZERO)
                    .build();
            var syncB = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout);
            var asyncB = com.anthropic.client.okhttp.AnthropicOkHttpClientAsync.builder().apiKey(apiKey).timeout(timeout);
            if (!baseUrl.isEmpty()) {   // 空→SDK 内置默认 https://api.anthropic.com
                syncB.baseUrl(baseUrl);
                asyncB.baseUrl(baseUrl);
            }
            AnthropicChatOptions opts = AnthropicChatOptions.builder()
                    .model(Model.of(defaultModel()))
                    .maxTokens(MAX_TOKENS)
                    .build();
            m = AnthropicChatModel.builder()
                    .anthropicClient(syncB.build())
                    .anthropicClientAsync(asyncB.build())
                    .options(opts)
                    .build();
            chatModel = m;
        }
        return m;
    }

    @Override
    public ChatOptions options(String modelId) {
        return AnthropicChatOptions.builder()
                .model(Model.of(modelId))
                .maxTokens(MAX_TOKENS)
                .build();
    }

    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }
}
