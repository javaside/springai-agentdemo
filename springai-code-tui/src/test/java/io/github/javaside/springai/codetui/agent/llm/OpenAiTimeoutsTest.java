package io.github.javaside.springai.codetui.agent.llm;

import com.openai.core.Timeout;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiTimeouts 产出的 {@link Timeout}——这正是喂给 SDK client 的 {@code ClientOptions.timeout}（每请求生效的那一层，
 * 非「基础 OkHttpClient」）。核心：{@code request}（→callTimeout）必须为 0（禁用），read/connect 随配置。
 */
class OpenAiTimeoutsTest {

    @Test
    void callTimeoutDisabled_readAndConnectFromConfig() {
        Timeout t = OpenAiTimeouts.of(LlmTimeouts.from(n -> "120"));
        assertTrue(t.request().isZero(), "request(→callTimeout) 必须为 0（禁用总时长超时），实际=" + t.request());
        assertEquals(Duration.ofSeconds(120), t.read());
        assertEquals(Duration.ofSeconds(120), t.write());
        assertEquals(Duration.ofSeconds(30), t.connect());
    }

    @Test
    void defaultRead_is300s_andCallTimeoutStillDisabled() {
        Timeout t = OpenAiTimeouts.of(LlmTimeouts.from(n -> null));   // 环境变量缺失→默认 300s
        assertTrue(t.request().isZero());
        assertEquals(Duration.ofSeconds(300), t.read());
    }
}
