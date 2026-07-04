package com.example.springai.codetui.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Model;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * Anthropic provider（Spring AI 2.0 spring-ai-anthropic）。key 缺失即 unavailable。
 *
 * <p>注意：Anthropic 的 {@code model()} 收 typed 枚举 {@link Model}，用静态 {@code Model.of(String)}；
 * 且 {@code max_tokens} 为必填，故默认 options 与每请求 options 都显式带 {@link #MAX_TOKENS}。
 * client 用 Spring AI 自带的 {@code AnthropicSetup.setupSyncClient(...)} 装配，避免额外的
 * {@code anthropic-java-client-okhttp} 依赖（该 artifact 不在本模块 classpath 内）。
 */
public final class AnthropicProvider implements LlmProvider {

    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";
    private static final int MAX_TOKENS = 8192;   // Anthropic 必填；可调
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("claude-sonnet-4-5", "claude-sonnet-4-5", "均衡 · 日常编码"),
            new ModelOption("claude-opus-4-5",   "claude-opus-4-5",   "最强推理 · 更贵"),
            new ModelOption("claude-haiku-4-5",  "claude-haiku-4-5",  "快 · 便宜"));

    private final String apiKey;
    private volatile ChatModel chatModel;

    public AnthropicProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
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
            // 用 Spring AI 自带的 SpringAiAnthropicHttpClient 工厂建 client（在本模块 classpath 内，
            // 无需额外的 anthropic-java-client-okhttp 依赖），仅装配、不发网络请求。
            // 参数序：(baseUrl, apiKey, timeout, maxRetries, proxy, headers)，null 走默认/环境变量。
            AnthropicClient client = AnthropicSetup.setupSyncClient(null, apiKey, null, null, null, null);
            m = AnthropicChatModel.builder()
                    .anthropicClient(client)
                    .options(AnthropicChatOptions.builder()
                            .model(Model.of(DEFAULT_MODEL))
                            .maxTokens(MAX_TOKENS)
                            .build())
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
