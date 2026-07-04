package com.example.springai.codetui.agent;

import com.anthropic.models.messages.Model;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * Anthropic provider（Spring AI 2.0 spring-ai-anthropic）。key 缺失即 unavailable。
 *
 * <p>注意：Anthropic 的 {@code model()} 收 typed 枚举 {@link Model}，用静态 {@code Model.of(String)}；
 * 且 {@code max_tokens} 为必填，故默认 options 与每请求 options 都显式带 {@link #MAX_TOKENS}。
 * key/base-url 直接设在 {@link AnthropicChatOptions} builder 上：baseUrl 为空时不设，用框架内置默认；配了则覆盖。
 */
public final class AnthropicProvider implements LlmProvider {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";
    private static final int MAX_TOKENS = 8192;   // Anthropic 必填；可调
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("claude-sonnet-4-5", "claude-sonnet-4-5", "均衡 · 日常编码"),
            new ModelOption("claude-opus-4-5",   "claude-opus-4-5",   "最强推理 · 更贵"),
            new ModelOption("claude-haiku-4-5",  "claude-haiku-4-5",  "快 · 便宜"));

    private final String apiKey;
    private final String baseUrl;            // 空→框架内置默认；配了→覆盖
    private volatile ChatModel chatModel;

    public AnthropicProvider(String apiKey) {
        this(apiKey, null);
    }

    public AnthropicProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.trim();
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
            AnthropicChatOptions.Builder opts = AnthropicChatOptions.builder()
                    .apiKey(apiKey)
                    .model(Model.of(DEFAULT_MODEL))
                    .maxTokens(MAX_TOKENS);
            if (!baseUrl.isEmpty()) {
                opts.baseUrl(baseUrl);
            }
            m = AnthropicChatModel.builder()
                    .options(opts.build())
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

    @Override public List<ModelOption> models() { return MODELS; }

    @Override public String defaultModel() { return DEFAULT_MODEL; }
}
