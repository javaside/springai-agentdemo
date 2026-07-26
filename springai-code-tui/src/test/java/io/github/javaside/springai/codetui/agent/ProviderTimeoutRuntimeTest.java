package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四家 provider 超时的<b>真实运行时</b>验证：本地开「接受连接但永不回包」的 socket，把各家底层 HTTP client
 * 指过去、read 超时设 2s、真打一次请求，量失败耗时。~2s 触发=超时真落到网络层；≥8s=未生效
 * （okhttp 默认 10s / SDK 默认 60s / 或无超时永久挂）。
 *
 * <ul>
 *   <li>OpenAI / 智谱：{@code OpenAIOkHttpClient} + {@code com.openai.core.Timeout}（两家同机制，测一条即代表）；
 *   <li>Anthropic：{@code AnthropicOkHttpClient} + {@code com.anthropic.core.Timeout}；
 *   <li>DeepSeek 阻塞：{@code HttpComponentsClientHttpRequestFactory}（Spring 检测默认同款）+ setReadTimeout；
 *   <li>DeepSeek 流式：{@code JdkClientHttpConnector}（Spring 检测默认同款）+ setReadTimeout。
 * </ul>
 * read 取 2s（内联，绕过 {@link LlmTimeouts} 的 10s 下限——helper 取值/钳制由 {@link OpenAiTimeoutsTest} 覆盖）。
 * DeepSeek 两条用 Spring 检测出的默认 HTTP 栈同款、只加超时不换栈（换 Simple/reactor-netty 会破坏真实 SSE，已实测）。
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
                    .model("claude-opus-5").maxTokens(16).addUserMessage("hi").build();
            assertTimesOutAround2s("Anthropic(AnthropicOkHttpClient)", () -> client.messages().create(params));
        });
    }

    // DeepSeek 用 Spring 检测出的默认同款栈 + 超时（阻塞=HttpComponents、流式=JDK connector），与生产 DeepSeekProvider 一致。
    @Test
    void deepSeekBlocking_httpComponentsReadTimeout() throws Exception {
        withHungServer(port -> {
            var rf = new org.springframework.http.client.HttpComponentsClientHttpRequestFactory();
            rf.setReadTimeout(Duration.ofSeconds(2));
            rf.setConnectionRequestTimeout(Duration.ofSeconds(30));
            var rc = org.springframework.web.client.RestClient.builder().requestFactory(rf).build();
            assertTimesOutAround2s("DeepSeek 阻塞(HttpComponents)",
                    () -> rc.post().uri("http://127.0.0.1:" + port).body("{}").retrieve().toBodilessEntity());
        });
    }

    @Test
    void deepSeekStreaming_jdkConnectorReadTimeout() throws Exception {
        withHungServer(port -> {
            java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30)).build();
            var connector = new org.springframework.http.client.reactive.JdkClientHttpConnector(jdk);
            connector.setReadTimeout(Duration.ofSeconds(2));
            var wc = org.springframework.web.reactive.function.client.WebClient.builder()
                    .clientConnector(connector).build();
            assertTimesOutAround2s("DeepSeek 流式(JdkClientHttpConnector)",
                    () -> wc.post().uri("http://127.0.0.1:" + port).bodyValue("{}")
                            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30)));
        });
    }
}
