package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * OpenCode Go（OpenCode Zen 的 Go 订阅）provider。key 缺失即 unavailable。
 *
 * <p>这是一家<b>聚合网关</b>：一个 key 同时提供 MiniMax / Kimi / GLM / DeepSeek / 通义 / MiMo /
 * 混元 / OpenAI / xAI 等多家模型，模型清单见 {@code https://opencode.ai/zen/go/v1/models}。
 * 其 {@code /chat/completions} 与 {@code /models} 是 OpenAI 兼容端点（Bearer 认证），
 * 因此复用 {@link OpenAiChatModel} 通路即可，与 {@link ZhipuProvider} 同理。
 *
 * <p><b>baseUrl</b>：默认 {@code https://opencode.ai/zen/go/v1}。OpenAI Java SDK 覆盖 baseUrl 后
 * 仅追加 {@code chat/completions}（不强制拼 /v1，见 {@link ZhipuProvider} 注释），故 baseUrl 必须写到
 * {@code /v1}，最终精确打 {@code .../zen/go/v1/chat/completions}。海外网关或自建代理可经
 * OPENCODE_GO_BASE_URL 覆盖。
 *
 * <p><b>qwen3.7-max 走不了 /chat/completions</b>：OpenCode Zen 把少数模型（已知 qwen3.7-max）仅暴露在
 * Anthropic 原生 {@code /messages} 端点上，{@code /chat/completions} 会拒绝（"not supported for
 * format oa-compat"）。本 provider 只走 OpenAI 兼容通路，故默认清单<b>不含</b> qwen3.7-max；
 * 需要通义旗舰时可用 qwen3.8-max。等上游暴露按模型路由的元数据后，再考虑接入 /messages 分支。
 *
 * <p><b>思考强度走 reasoning_effort</b>：网关统一校验并翻译 OpenAI 兼容的 {@code reasoning_effort}
 * （合法值 none / minimal / low / medium / high / xhigh / max）。实测（2026-08-14，真实 key）各家上游
 * 并非全盘接受完整档位——mimo 只认 low/medium/high（minimal/xhigh 被上游 400 拒），故只暴露
 * <b>low / medium / high</b> 三档，关闭思考映射为 {@code none}（各家实测均能关掉）。
 * <p>图片输入仍保守：网关是否透传图片未经验证，视觉能力沿用默认 TEXT_ONLY（未知即不支持，见
 * {@code media.VisionModels} 注释）。要用视觉请直接走对应的原生 provider。
 */
public final class OpencodeGoProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1";
    // 首项即默认模型（OPENCODE_GO_MODELS 未配置时的回退清单，约定第一项为默认）。
    // 只列各家的旗舰/编码/快档代表，完整清单见 /models；qwen3.7-max 因仅走 /messages 端点而不在此列。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 复杂编码"),
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("glm-5.2",           "glm-5.2",           "Agentic 编码 · 长上下文"),
            new ModelOption("glm-5.1",           "glm-5.1",           "长任务 · 自规划"),
            new ModelOption("kimi-k2.7-code",    "kimi-k2.7-code",    "Kimi 编码专项"),
            new ModelOption("kimi-k3",           "kimi-k3",           "Kimi 旗舰"),
            new ModelOption("qwen3.8-max",       "qwen3.8-max",       "通义旗舰 · 复杂推理"),
            new ModelOption("qwen3.7-plus",      "qwen3.7-plus",      "通义均衡 · 中档"),
            new ModelOption("minimax-m3",        "minimax-m3",        "MiniMax 旗舰"),
            new ModelOption("mimo-v2.5-pro",     "mimo-v2.5-pro",     "小米 MiMo 旗舰"),
            new ModelOption("mimo-v2-omni",      "mimo-v2-omni",      "小米 MiMo 多模态"),
            new ModelOption("hy3",               "hy3",               "腾讯混元 · 推理/Agent"),
            new ModelOption("hy3-preview",       "hy3-preview",       "腾讯混元 · 预览"),
            new ModelOption("gpt-5.6-luna",      "gpt-5.6-luna",      "OpenAI 快 · 便宜"),
            new ModelOption("grok-4.5",          "grok-4.5",          "xAI 旗舰"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    private final String apiKey;
    private final String baseUrl;            // 空→内置默认；配了→覆盖
    private final List<ModelOption> models;   // OPENCODE_GO_MODELS 解析结果；未配置=内置 MODELS
    private volatile ChatModel chatModel;    // 懒建，单例

    public OpencodeGoProvider(String apiKey) {
        this(apiKey, null);
    }

    public OpencodeGoProvider(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    public OpencodeGoProvider(String apiKey, String baseUrl, String modelsEnv) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        this.models = ModelListEnv.parse(modelsEnv, MODELS);
    }

    @Override public String id() { return "opencode-go"; }

    @Override public boolean available() { return !apiKey.isBlank(); }

    @Override
    public ChatModel chatModel() {
        if (!available()) {
            throw new IllegalStateException("OpenCode Go 不可用：未配置 OPENCODE_GO_API_KEY");
        }
        ChatModel m = chatModel;
        if (m == null) {
            // 超时设在 SDK client 的 ClientOptions（见 OpenAiTimeouts）；baseUrl 恒非空（/zen/go/v1）。
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
        // 网关合法的 reasoning_effort 全集为 none/minimal/low/medium/high/xhigh/max，
        // 但实测 mimo 上游只认 low/medium/high（见类注释），只暴露三档全上游通用值。
        return ThinkingCapabilities.effort(true, List.of("low", "medium", "high"));
    }

    @Override
    public ChatOptions options(String modelId, ThinkingConfig config) {
        thinkingCapabilities(modelId).validate(config);
        if (config.mode() == ThinkingMode.DEFAULT) {
            return options(modelId);
        }
        String effort = config.mode() == ThinkingMode.DISABLED ? "none" : config.effort();
        return OpenAiChatOptions.builder().model(modelId).reasoningEffort(effort).build();
    }

    @Override public List<ModelOption> models() { return models; }

    @Override public String defaultModel() { return models.get(0).id(); }
}
