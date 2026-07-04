package com.example.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * OpenAI provider（Spring AI 2.0 spring-ai-openai）。key 缺失即 unavailable。
 *
 * <p>构建（已实测网络无关）：把 apiKey 设到 {@link OpenAiChatOptions} 上，{@code OpenAiChatModel.build()}
 * 自行派生底层 client——不用供应商 {@code OpenAIOkHttpClient}（不在 classpath）。
 */
public final class OpenAiProvider implements LlmProvider {

    private static final String DEFAULT_MODEL = "gpt-4o";
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("gpt-4o",      "gpt-4o",      "均衡 · 日常编码"),
            new ModelOption("gpt-4o-mini", "gpt-4o-mini", "快 · 便宜"));

    private final String apiKey;
    private volatile ChatModel chatModel;

    public OpenAiProvider(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
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
            m = OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().apiKey(apiKey).model(DEFAULT_MODEL).build())
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
