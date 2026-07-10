package io.github.javaside.springai.codetui.agent;

import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * 为 OpenAI-SDK 家族（OpenAI / 智谱，共用 spring-ai-openai）构造超时 customizer。
 *
 * <p>核心修复：把 SDK Timeout 的 {@code request} 段设为 {@link java.time.Duration#ZERO} —— spring-ai 会把它映射到
 * OkHttp {@code callTimeout}，ZERO=禁用「整个调用总时长超时」（流式响应不能被总时长砍断）。read=配置值
 * （取代默认 60s 过短）、connect=固定 30s。write 复用 read（写请求体一般很快，给足即可）。
 */
final class OpenAiTimeoutCustomizer {

    private OpenAiTimeoutCustomizer() {}

    static OpenAiHttpClientBuilderCustomizer of(LlmTimeouts timeouts) {
        Timeout t = Timeout.builder()
                .connect(timeouts.connectTimeout())
                .read(timeouts.readTimeout())
                .write(timeouts.readTimeout())
                .request(java.time.Duration.ZERO)   // 禁用 callTimeout
                .build();
        return builder -> builder.timeout(t);
    }
}
