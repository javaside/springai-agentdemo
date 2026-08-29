package io.github.javaside.springai.codetui.agent.llm;

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
import java.util.Map;

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
 * <p><b>坏模型不下发</b>：网关 {@code /models} 列出的模型里，少数当前上游不可用（实测 2026-08-15）——
 * {@code mimo-v2-pro} / {@code mimo-v2-omni} 返回「Unsupported model」、{@code hy3-preview} 返回
 * 「Model is unavailable」、{@code grok-4.5} 返回「Endpoint is unavailable」（503）。这些不放进清单，
 * 避免用户选中即报错。上游恢复后再加回。
 *
 * <p><b>思考强度走 reasoning_effort</b>：网关统一校验并翻译 OpenAI 兼容的 {@code reasoning_effort}
 * （合法值 none / minimal / low / medium / high / xhigh / max）。各家上游对档位的接受度不一致，
 * 故按 modelId 返回各自真实支持的档位（见 {@link #EFFORT_CAPS}），未收录的模型回退到保守的
 * low / medium / high 三档；关闭思考映射为 {@code none}（仅 supportsDisable=true 的模型）。
 * <p><b>图片输入只开放官方声明的模型</b>：OpenCode Go 文档目前仅确认
 * {@code deepseek-v4-flash-vision-exp} 支持图片，因此只为该模型开放视觉兑现；其他内置模型和
 * {@code OPENCODE_GO_MODELS} 自定义模型仍保持 TEXT_ONLY，避免把全局视觉前缀名单误套到未经 Go 网关
 * 验证的上游。图片沿用 {@link OpenAiChatModel} 的 OpenAI 兼容 {@code image_url} 通路。
 */
public final class OpencodeGoProvider implements LlmProvider {

    private static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/go/v1";
    // 首项即默认模型（OPENCODE_GO_MODELS 未配置时的回退清单，约定第一项为默认）。
    // 收录网关 /models 中当前可用的模型；坏模型（mimo-v2-pro/omni、hy3-preview、grok-4.5）不下发，见类注释。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-pro",              "deepseek-v4-pro",              "强推理 · 复杂编码"),
            new ModelOption("deepseek-v4-flash",            "deepseek-v4-flash",            "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-flash-vision-exp", "deepseek-v4-flash-vision-exp", "视觉实验 · 图片理解"),
            new ModelOption("glm-5.2",                      "glm-5.2",                      "Agentic 编码 · 长上下文"),
            new ModelOption("glm-5.3",           "glm-5.3",           "GLM 新旗舰 · 低/高/最大档"),
            new ModelOption("glm-5.1",           "glm-5.1",           "长任务 · 自规划"),
            new ModelOption("glm-5",             "glm-5",             "GLM 上代"),
            new ModelOption("kimi-k3",           "kimi-k3",           "Kimi 旗舰"),
            new ModelOption("kimi-k2.7-code",    "kimi-k2.7-code",    "Kimi 编码专项"),
            new ModelOption("kimi-k2.6",         "kimi-k2.6",         "Kimi 均衡"),
            new ModelOption("kimi-k2.5",         "kimi-k2.5",         "Kimi 上代"),
            new ModelOption("qwen3.8-max",       "qwen3.8-max",       "通义旗舰 · 复杂推理"),
            new ModelOption("qwen3.7-max",       "qwen3.7-max",       "通义旗舰 · 上代"),
            new ModelOption("qwen3.7-plus",      "qwen3.7-plus",      "通义均衡 · 中档"),
            new ModelOption("qwen3.6-plus",      "qwen3.6-plus",      "通义均衡 · 上代"),
            new ModelOption("qwen3.5-plus",      "qwen3.5-plus",      "通义 · 上代"),
            new ModelOption("minimax-m3",        "minimax-m3",        "MiniMax 旗舰"),
            new ModelOption("minimax-m2.7",      "minimax-m2.7",      "MiniMax 均衡"),
            new ModelOption("minimax-m2.5",      "minimax-m2.5",      "MiniMax 上代"),
            new ModelOption("mimo-v2.5-pro",     "mimo-v2.5-pro",     "小米 MiMo 旗舰"),
            new ModelOption("mimo-v2.5",         "mimo-v2.5",         "小米 MiMo 均衡"),
            new ModelOption("hy3",               "hy3",               "腾讯混元 · 推理/Agent"),
            new ModelOption("gpt-5.6-luna",      "gpt-5.6-luna",      "OpenAI 快 · 便宜"));

    private static final LlmTimeouts TIMEOUTS = LlmTimeouts.fromEnv();

    // 各模型真实支持的 reasoning_effort 档位（2026-08-15 真实 key 逐个实测）。
    // 网关全集为 none/minimal/low/medium/high/xhigh/max，但每家上游只认其中一部分；
    // 这里按 modelId 收录实测结果，未收录的模型（含 OPENCODE_GO_MODELS 自定义）回退 CONSERVATIVE。
    // 约定不暴露 minimal（本项目其它 provider 一致，且 minimal 会变成「切到开启」时的默认档）。
    private static final List<String> EFFORT_FULL = List.of("low", "medium", "high", "xhigh", "max");
    private static final List<String> EFFORT_NO_MAX = List.of("low", "medium", "high", "xhigh");
    private static final List<String> EFFORT_LOW_MED_HIGH = List.of("low", "medium", "high");
    private static final List<String> EFFORT_GLM53 = List.of("low", "high", "max");   // glm-5.3 上游只认这三档
    private static final Map<String, ThinkingCapabilities> EFFORT_CAPS = Map.ofEntries(
            Map.entry("deepseek-v4-pro",   ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("deepseek-v4-flash", ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("glm-5.2",           ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("glm-5.3",           ThinkingCapabilities.effort(false, EFFORT_GLM53)),
            Map.entry("glm-5.1",           ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("glm-5",             ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("kimi-k3",           ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("kimi-k2.7-code",    ThinkingCapabilities.effort(false, EFFORT_FULL)),
            Map.entry("kimi-k2.6",         ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("kimi-k2.5",         ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("qwen3.8-max",       ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("qwen3.7-max",       ThinkingCapabilities.effort(true,  EFFORT_NO_MAX)),
            Map.entry("qwen3.7-plus",      ThinkingCapabilities.effort(true,  EFFORT_NO_MAX)),
            Map.entry("qwen3.6-plus",      ThinkingCapabilities.effort(true,  EFFORT_NO_MAX)),
            Map.entry("qwen3.5-plus",      ThinkingCapabilities.effort(true,  EFFORT_NO_MAX)),
            Map.entry("minimax-m3",        ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("minimax-m2.7",      ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            Map.entry("minimax-m2.5",      ThinkingCapabilities.effort(false, EFFORT_FULL)),
            Map.entry("mimo-v2.5-pro",     ThinkingCapabilities.effort(true,  EFFORT_LOW_MED_HIGH)),
            Map.entry("mimo-v2.5",         ThinkingCapabilities.effort(false, EFFORT_LOW_MED_HIGH)),
            Map.entry("hy3",               ThinkingCapabilities.effort(true,  EFFORT_FULL)),
            // gpt-5.6-luna 实测时被 Cloudflare 限流（403），档位未测得，暂按保守三档回退，待补测。
            Map.entry("gpt-5.6-luna",      ThinkingCapabilities.effort(true,  EFFORT_LOW_MED_HIGH)));
    private static final ThinkingCapabilities CONSERVATIVE = ThinkingCapabilities.effort(true, EFFORT_LOW_MED_HIGH);

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
        return EFFORT_CAPS.getOrDefault(modelId, CONSERVATIVE);
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

    @Override
    public ModelCapabilities capabilities(String modelId) {
        boolean officialVisionModel = "deepseek-v4-flash-vision-exp".equalsIgnoreCase(
                modelId == null ? "" : modelId.trim());
        return new ModelCapabilities(officialVisionModel && VisionModels.enabled(), false);
    }
}
