package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.RetryPolicy;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 限额等待主链桥（spec §3.6）：L1QuotaBridge 两段式 + CodingAgent.onL1QuotaWait 转发。 */
class QuotaWiringTest {

    static final class RecordingHook implements RetryPolicy.QuotaWaitHook {
        final List<Long> waits = new CopyOnWriteArrayList<>();
        final List<Long> resets = new CopyOnWriteArrayList<>();
        @Override public void onQuotaWait(long waitMs, long resetAtEpochMs, String reason) {
            waits.add(waitMs);
            resets.add(resetAtEpochMs);
        }
    }

    @Test
    void l1QuotaBridgeNoOpBeforeBindAndForwardsAfter() {
        AgentTools.L1QuotaBridge bridge = new AgentTools.L1QuotaBridge();
        assertDoesNotThrow(() -> bridge.onQuotaWait(1, 2, "r"));   // 未 bind：null 守卫 no-op
        RecordingHook recorder = new RecordingHook();
        bridge.bind(recorder);
        bridge.onQuotaWait(90_000L, 123L, "r");
        assertEquals(List.of(90_000L), recorder.waits);
        assertEquals(List.of(123L), recorder.resets);
    }

    @Test
    void codingAgentOnL1QuotaWaitForwardsToSinkOrNullGuards() throws Exception {
        CodingAgent agent = minimalAgent();
        // 1) 未置 sink（新实例默认 null）→ onL1QuotaWait no-op 不抛
        assertDoesNotThrow(() -> agent.onL1QuotaWait(1, 2, "r"));
        // 2) 反射置 activeTurnQuotaSink = recorder → onL1QuotaWait 转发同参
        RecordingHook recorder = new RecordingHook();
        Field f = CodingAgent.class.getDeclaredField("activeTurnQuotaSink");
        f.setAccessible(true);
        f.set(agent, recorder);
        agent.onL1QuotaWait(90_000L, 123L, "r");
        assertEquals(List.of(90_000L), recorder.waits);
        assertEquals(List.of(123L), recorder.resets);
    }

    /**
     * 最小 CodingAgent（照抄 AgentToolsRetryWiringTest / CodingAgentTurnResumeTest 的最小构造段：
     * 桩 ChatClient + listener + sessionId + activeTurnId + sessionService + manualStrategy +
     * tokenCountEstimator）。本用例不 submit，后三件可为 null（构造只赋字段，7 参重载无 null 检查）。
     */
    private static CodingAgent minimalAgent() {
        ChatModel stub = new ChatModel() {
            @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                return Flux.error(new UnsupportedOperationException());
            }
        };
        ChatClient chat = ChatClient.builder(stub).build();
        return new CodingAgent(chat, new StubListener(), "quota-wiring", new AtomicLong(),
                null, null, null);
    }
}
