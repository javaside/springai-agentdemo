package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.Quota429s;
import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 串行子 agent 可取消 + 限额 UI 桥（spec §3.4.3/§3.4.4）。 */
class SubagentRunnerSerialInterruptTest {

    private static String quotaMessage(long deltaSeconds) {
        String at = java.time.Instant.now().plusSeconds(deltaSeconds)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "429: 已达到 5 小时使用上限。您的限额将在 `" + at + "` 重置。";
    }

    /** 脚本桩：第 n 次（1 基）订阅返回 script 的 Flux。 */
    private static ChatModel scripted(java.util.function.IntFunction<Flux<ChatResponse>> script,
                                      AtomicInteger calls) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> script.apply(calls.incrementAndGet()));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 假 LlmProvider（照抄 SubagentRunnerOkTest.provider）。 */
    private static LlmProvider provider(ChatModel model) {
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    /** 收限额事件的 listener（extends StubListener，照抄 OkTest.RecordingListener 模式）。 */
    private static final class QuotaRecordingListener extends StubListener {
        final List<Long> quotaTurnIds = new CopyOnWriteArrayList<>();
        final List<String> quotaReasons = new CopyOnWriteArrayList<>();
        @Override public void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) {
            quotaTurnIds.add(turnId);
            quotaReasons.add(reason);
        }
    }

    @Test
    void serialQuotaWaitInterruptedByCancelTurn() throws Exception {
        CountDownLatch firstCall = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = scripted(n -> {
            firstCall.countDown();
            return Flux.error(new java.util.concurrent.CompletionException(
                    Quota429s.quota429("1316", quotaMessage(3600))));   // 1h 等待：生产 sleeper 真睡
        }, calls);
        QuotaRecordingListener lis = new QuotaRecordingListener();
        SubagentRunner runner = new SubagentRunner(
                new ProviderRegistry(List.of(provider(delegate))), List.of(), lis, "");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                runner.run(spec(), "p", "d", 77L);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();
        assertTrue(firstCall.await(5, TimeUnit.SECONDS));   // 已进入限额等待（Thread.sleep 真睡）
        runner.cancelTurn(77L);                              // interrupt 串行线程 → sleeper 抛 RuntimeException
        worker.join(5000);
        assertFalse(worker.isAlive());                       // 不再挂死（未实现 interrupt 时此断言红：worker 仍在睡）
        assertNotNull(failure.get());                        // run 以异常收场
    }

    @Test
    void quotaHookBridgesToListenerForForeground() {
        RetryPolicy.setDelayScaleForTest(ms -> Math.min(ms, 1));   // 压缩等待，run 可跑完
        try {
            AtomicInteger calls = new AtomicInteger();
            ChatModel delegate = scripted(n -> n == 1
                    ? Flux.error(new java.util.concurrent.CompletionException(
                            Quota429s.quota429("1316", quotaMessage(3600))))
                    : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))), calls);
            QuotaRecordingListener lis = new QuotaRecordingListener();
            SubagentRunner runner = new SubagentRunner(
                    new ProviderRegistry(List.of(provider(delegate))), List.of(), lis, "");
            assertEquals("done", runner.run(spec(), "p", "d", 77L));
            assertEquals(List.of(77L), lis.quotaTurnIds);   // 前台桥通
            assertTrue(lis.quotaReasons.get(0).startsWith("子任务"), "reason 应带「子任务」前缀");
        } finally {
            RetryPolicy.resetDelayScaleForTest();
        }
    }
}
