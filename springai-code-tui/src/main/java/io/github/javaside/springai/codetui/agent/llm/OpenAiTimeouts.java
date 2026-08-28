package io.github.javaside.springai.codetui.agent.llm;

import com.openai.core.Timeout;

import java.time.Duration;

/**
 * 构造 OpenAI-SDK 家族（OpenAI / 智谱，共用官方 openai-java SDK）的完整 {@link Timeout}。
 *
 * <p><b>为何必须是完整 Timeout、且设在 client 而非 http-client customizer</b>：spring-ai 的
 * {@code SpringAiOpenAiHttpClient.newCall()} <b>每次请求</b>都从 SDK {@code RequestOptions.getTimeout()}
 * （源自 {@code ClientOptions.timeout}）重建 OkHttp 的 connect/read/write/<b>callTimeout</b>——会覆盖掉
 * 任何设在「基础 OkHttpClient」上的超时。故超时必须设在 SDK {@code ClientOptions.timeout} 上
 * （经 {@code OpenAIOkHttpClient.builder().timeout(Timeout)}），才能真正作用于每一次请求。
 *
 * <p>{@code request=}{@link Duration#ZERO} → OkHttp {@code callTimeout(0)} = 禁用「整个调用总时长超时」
 * （流式响应不能被总时长砍断）。read=配置值（取代 SDK 默认 60s 过短之祸）、connect=固定 30s、write 复用 read。
 */
final class OpenAiTimeouts {

    private OpenAiTimeouts() {}

    static Timeout of(LlmTimeouts timeouts) {
        return Timeout.builder()
                .connect(timeouts.connectTimeout())
                .read(timeouts.readTimeout())
                .write(timeouts.readTimeout())
                .request(Duration.ZERO)   // 禁用 callTimeout
                .build();
    }
}
