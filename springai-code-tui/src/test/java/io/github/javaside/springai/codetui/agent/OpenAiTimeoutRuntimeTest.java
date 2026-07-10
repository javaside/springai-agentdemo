package io.github.javaside.springai.codetui.agent;

import com.openai.core.Timeout;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实运行时验证「com.openai.core.Timeout 的 read 会落到每请求的 OkHttp readTimeout、且 request=ZERO 禁用 callTimeout
 * 不影响 read 独立生效」——这是整个超时修复赖以成立的机制（单纯断言 Timeout 对象或「装配不抛异常」证明不了这一层）。
 *
 * <p>做法：本地开「接受连接但永不回包」的 socket，指过去打一次真实请求（maxRetries=0，绕过 spring-ai 重试）。
 * 用 read=2s（内联，绕过 {@link LlmTimeouts} 的 10s 下限——生产 helper 的取值/钳制由 {@link OpenAiTimeoutsTest} 覆盖）。
 * 断言 ~2s 触发：证明 read 真落到 OkHttp；若未生效会退到 okhttp 内置默认 10s（≥8s 即判失败）。
 */
class OpenAiTimeoutRuntimeTest {

    @Test
    void readTimeoutReachesOkHttp_andCallTimeoutZeroDoesNotBreakIt() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread accepter = new Thread(() -> {
                while (!stop.get()) {
                    try { Socket s = server.accept(); /* 接受后不回包，挂住 */ }
                    catch (Exception e) { return; }
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            // read=2s + request=ZERO（禁 callTimeout）——与生产 OpenAiTimeouts.of 同构，仅 read 取 2s 便于快速判别。
            Timeout timeout = Timeout.builder()
                    .connect(Duration.ofSeconds(30))
                    .read(Duration.ofSeconds(2))
                    .write(Duration.ofSeconds(2))
                    .request(Duration.ZERO)
                    .build();
            var client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                    .apiKey("fake-key").baseUrl("http://127.0.0.1:" + port).timeout(timeout).maxRetries(0).build();
            var params = com.openai.models.chat.completions.ChatCompletionCreateParams.builder()
                    .model("gpt-5.6-sol").addUserMessage("hi").build();

            long start = System.nanoTime();
            boolean threw = false;
            try {
                client.chat().completions().create(params);
            } catch (RuntimeException expected) {
                threw = true;
            } finally {
                stop.set(true);
                server.close();
            }
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            System.out.println("[OpenAiTimeoutRuntimeTest] read=2s 单次请求失败耗时 = " + ms + " ms");

            assertTrue(threw, "指向挂死服务端的请求应超时失败");
            assertTrue(ms >= 1_000, "不应瞬间失败（应是 read 超时 ~2s），实际=" + ms + "ms");
            assertTrue(ms < 8_000,
                    "read 超时应在 ~2s 触发；≥8s 说明 read 未落到 OkHttp（退到 okhttp 默认 10s / SDK 默认 60s）。实际=" + ms + "ms");
        }
    }
}
