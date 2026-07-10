package io.github.javaside.springai.codetui.agent;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** OpenAiTimeoutCustomizer 产出的 OkHttp 超时值。核心：callTimeout 必须为 0（禁用），否则流式会被总时长砍断（Stream failed）。 */
class OpenAiTimeoutCustomizerTest {

    private static OkHttpClient built(String readSecondsEnv) {
        OpenAiHttpClientBuilderCustomizer c =
                OpenAiTimeoutCustomizer.of(LlmTimeouts.from(n -> readSecondsEnv));
        SpringAiOpenAiHttpClient.Builder b = SpringAiOpenAiHttpClient.builder();
        c.customize(b);
        return b.build().getOkHttpClient();
    }

    @Test
    void callTimeoutIsDisabled_andReadConnectSet() {
        OkHttpClient client = built("120");
        assertEquals(0, client.callTimeoutMillis(), "callTimeout 必须为 0（禁用），这是修复的核心");
        assertEquals(120_000, client.readTimeoutMillis());
        assertEquals(120_000, client.writeTimeoutMillis());
        assertEquals(30_000, client.connectTimeoutMillis());
    }

    @Test
    void defaultRead_is300s_andCallTimeoutStillDisabled() {
        OkHttpClient client = built(null);   // 环境变量缺失→默认 300s
        assertEquals(0, client.callTimeoutMillis());
        assertEquals(300_000, client.readTimeoutMillis());
    }
}
