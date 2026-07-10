package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI-SDK 家族（OpenAI / 智谱 / Anthropic）超时的<b>真实运行时</b>验证：本地开「接受连接但永不回包」的 socket，
 * 把各家底层 HTTP client 指过去、read 超时设 2s、真打一次请求，量失败耗时。~2s 触发=超时真落到 OkHttp；
 * ≥8s=未生效（退到 okhttp 内置默认 10s / SDK 默认 60s）。
 *
 * <ul>
 *   <li>OpenAI / 智谱：{@code OpenAIOkHttpClient} + {@code com.openai.core.Timeout}（两家同机制，测一条即代表）；
 *   <li>Anthropic：{@code AnthropicOkHttpClient} + {@code com.anthropic.core.Timeout}。
 * </ul>
 * read 取 2s（内联，绕过 {@link LlmTimeouts} 的 10s 下限——helper 取值/钳制由 {@link OpenAiTimeoutsTest} 覆盖）。
 * DeepSeek 走 Spring RestClient/WebClient、无此 bug，保持默认（不在本测试覆盖）。
 */
class ProviderTimeoutRuntimeTest {

    /** 起一个接受连接后不回包的 server，把 port 交给 body 执行，测完关闭。 */
    private void withHungServer(Consumer<Integer> body) throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                while (!stop.get()) {
                    try { Socket s = server.accept(); /* 挂住不回 */ }
                    catch (Exception e) { return; }
                }
            });
            accepter.setDaemon(true);
            accepter.start();
            try {
                body.accept(server.getLocalPort());
            } finally {
                stop.set(true);
            }
        }
    }

    /** 量 action 抛异常的耗时，断言在 [1s, 8s)（~2s read 超时生效）。 */
    private void assertTimesOutAround2s(String who, Runnable action) {
        long start = System.nanoTime();
        boolean threw = false;
        try { action.run(); } catch (RuntimeException e) { threw = true; }
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.println("[ProviderTimeoutRuntimeTest] " + who + " 失败耗时 = " + ms + " ms");
        assertTrue(threw, who + "：指向挂死服务端的请求应超时失败");
        assertTrue(ms >= 1_000, who + "：不应瞬间失败（应是 ~2s 超时），实际=" + ms + "ms");
        assertTrue(ms < 8_000, who + "：超时应 ~2s 触发；≥8s 说明未落到 OkHttp（默认 10s/60s）。实际=" + ms + "ms");
    }

    @Test
    void openAiFamily_readTimeoutReachesOkHttp() throws Exception {
        withHungServer(port -> {
            com.openai.core.Timeout t = com.openai.core.Timeout.builder()
                    .connect(Duration.ofSeconds(30)).read(Duration.ofSeconds(2))
                    .write(Duration.ofSeconds(2)).request(Duration.ZERO).build();
            var client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                    .apiKey("fake").baseUrl("http://127.0.0.1:" + port).timeout(t).maxRetries(0).build();
            var params = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                    .model("gpt-5.6-sol").addUserMessage("hi").build();
            assertTimesOutAround2s("OpenAI/智谱(OpenAIOkHttpClient)", () -> client.chat().completions().create(params));
        });
    }

    @Test
    void anthropic_readTimeoutReachesOkHttp() throws Exception {
        withHungServer(port -> {
            com.anthropic.core.Timeout t = com.anthropic.core.Timeout.builder()
                    .connect(Duration.ofSeconds(30)).read(Duration.ofSeconds(2))
                    .write(Duration.ofSeconds(2)).request(Duration.ZERO).build();
            var client = com.anthropic.client.okhttp.AnthropicOkHttpClient.builder()
                    .apiKey("fake").baseUrl("http://127.0.0.1:" + port).timeout(t).maxRetries(0).build();
            var params = com.anthropic.models.messages.MessageCreateParams.builder()
                    .model("claude-opus-4-8").maxTokens(16).addUserMessage("hi").build();
            assertTimesOutAround2s("Anthropic(AnthropicOkHttpClient)", () -> client.messages().create(params));
        });
    }
}
