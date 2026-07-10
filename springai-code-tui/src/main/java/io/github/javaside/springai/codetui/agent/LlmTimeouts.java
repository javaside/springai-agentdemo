package io.github.javaside.springai.codetui.agent;

import java.time.Duration;
import java.util.function.Function;

/**
 * LLM 请求超时配置（适配流式）。单一职责：读环境变量 {@code CODETUI_LLM_READ_TIMEOUT_SECONDS}，
 * 暴露 read/connect 两个 {@link Duration}。callTimeout（总时长）由各 provider 的接线固定禁用（request=0），
 * 不在此暴露——见设计文档「根因」。
 *
 * <p>read 默认 300s（取代 SDK 默认 60s 过短之祸）；钳制到 [10, 3600]。connect 固定 30s。
 */
public final class LlmTimeouts {

    /** 环境变量名：模型两个数据块之间的最大间隔（秒）。 */
    public static final String READ_TIMEOUT_ENV = "CODETUI_LLM_READ_TIMEOUT_SECONDS";

    private static final int DEFAULT_READ_SECONDS = 300;
    private static final int MIN_READ_SECONDS = 10;
    private static final int MAX_READ_SECONDS = 3600;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Duration readTimeout;

    private LlmTimeouts(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /** 从真实环境变量构建。 */
    public static LlmTimeouts fromEnv() {
        return from(System::getenv);
    }

    /** 注入取值函数构建（便于单测，不依赖真实环境）。 */
    public static LlmTimeouts from(Function<String, String> env) {
        return new LlmTimeouts(Duration.ofSeconds(resolveReadSeconds(env.apply(READ_TIMEOUT_ENV))));
    }

    private static int resolveReadSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_READ_SECONDS;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return Math.max(MIN_READ_SECONDS, Math.min(MAX_READ_SECONDS, v));
        } catch (NumberFormatException e) {
            return DEFAULT_READ_SECONDS;
        }
    }

    public Duration readTimeout() { return readTimeout; }

    public Duration connectTimeout() { return CONNECT_TIMEOUT; }
}
