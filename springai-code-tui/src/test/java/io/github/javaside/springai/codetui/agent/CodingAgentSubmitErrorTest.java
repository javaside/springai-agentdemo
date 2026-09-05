package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.StreamIdleTimeoutChatModel;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：CodingAgent.submit 在响应式装配/订阅抛异常时，必须经 onError 把状态复位到 IDLE，
 * 而不是让异常逃逸、把 UI 永远卡在 THINKING（无终态事件）。
 *
 * <p>⚠ 语义随 Task 6 调整：submit 的 prompt 组装已移入 {@code Flux.defer + subscribeOn}，
 * 装配异常变成 error 信号由 doOnError 统一处理（外层同步 catch 已删除——保留会与 doOnError
 * 双发 listener.onError，双 trim、双终态事件）。故「同步抛异常 → 返回已 dispose 句柄」的
 * 旧断言改为「submit 不逃逸 → 异步 error → IDLE」。
 */
class CodingAgentSubmitErrorTest {

    /** 用动态代理造一个 prompt() 同步抛异常的 ChatClient，免去实现整个接口。 */
    private static ChatClient throwingOnPrompt(String message) {
        return (ChatClient) Proxy.newProxyInstance(
                ChatClient.class.getClassLoader(),
                new Class[]{ChatClient.class},
                (proxy, method, args) -> {
                    if ("prompt".equals(method.getName())) {
                        throw new RuntimeException(message);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void syncAssemblyException_isReportedAndStateReset_notLeaked() throws Exception {
        ConversationState state = new ConversationState();   // implements AgentListener
        CodingAgent agent = new CodingAgent(throwingOnPrompt("assembly-boom"), state, "s", new AtomicLong(),
                null, null, null);   // 本测试不触发 /compact，压缩句柄用不到

        Disposable d = assertDoesNotThrow(() -> agent.submit("hi"),
                "装配异常不得同步逃逸出 submit：defer+subscribeOn 后变成异步 error 信号");

        // 异步到达：错误在 boundedElastic worker 上经 doOnError 处理，等它把状态复位到 IDLE。
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!state.isIdle() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(state.isIdle(), "onError 应把状态复位到 IDLE（不卡在 THINKING）");
        assertTrue(state.drainPending().stream().anyMatch(l -> l.text().contains("assembly-boom")),
                "错误信息应经 onError 落进 pending（滚入 scrollback）");
        d.dispose();
        assertTrue(d.isDisposed(), "返回句柄 dispose 后应 isDisposed");
    }

    @Test
    void asynchronousIdleTimeout_isReportedAndStateReset() throws Exception {
        ChatOptions options = OpenAiChatOptions.builder().model("gpt-5.6-sol").build();
        ChatModel hanging = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.never(); }
            @Override public ChatOptions getOptions() { return options; }
        };
        ChatClient client = ChatClient.builder(
                new StreamIdleTimeoutChatModel(hanging, Duration.ofMillis(100))).build();
        ConversationState state = new ConversationState();
        CodingAgent agent = new CodingAgent(client, state, "s", new AtomicLong(), null, null, null);

        Disposable disposable = agent.submit("hi");
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!state.isIdle() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertTrue(state.isIdle(), "异步超时必须经 onError 把状态复位到 IDLE");
        assertTrue(state.drainPending().stream().anyMatch(line ->
                line.text().contains("等待模型流数据超时")));
        disposable.dispose();
    }
}
