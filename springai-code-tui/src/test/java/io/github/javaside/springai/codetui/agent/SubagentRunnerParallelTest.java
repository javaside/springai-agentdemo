package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SubagentRunner.runAll 并发执行：顺序、失败隔离、turnId 传播、并发上限。全部用假 ChatModel，不联网。 */
class SubagentRunnerParallelTest {

    private static ChatModel chatModel(String replyText, boolean shouldThrow) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (shouldThrow) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage(replyText))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

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

    @Test
    void runAll_returnsResultsInInputOrder() {
        // 回显 prompt 的假模型：结果可区分，才能真正验证「按入参顺序」
        ChatModel echo = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(prompt.getContents()))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(echo)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "PROMPT_ONE", "d1"),
                new SubagentRunner.Dispatch(spec(), "PROMPT_TWO", "d2"),
                new SubagentRunner.Dispatch(spec(), "PROMPT_THREE", "d3"));
        List<String> out = runner.runAll(ds, 1L);
        assertEquals(3, out.size());
        assertTrue(out.get(0).contains("PROMPT_ONE"), out.get(0));
        assertTrue(out.get(1).contains("PROMPT_TWO"), out.get(1));
        assertTrue(out.get(2).contains("PROMPT_THREE"), out.get(2));
    }

    @Test
    void runAll_isolatesFailures_othersStillSucceed() {
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (prompt.getContents().contains("fail")) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(model)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "please succeed", "d1"),
                new SubagentRunner.Dispatch(spec(), "please fail here", "d2"),
                new SubagentRunner.Dispatch(spec(), "succeed again", "d3"));
        List<String> out = runner.runAll(ds, 1L);
        assertEquals(3, out.size());
        assertEquals("ok", out.get(0));
        assertTrue(out.get(1).startsWith("失败："), "失败条应以「失败：」开头，实际=" + out.get(1));
        assertEquals("ok", out.get(2));
    }

    @Test
    void runAll_propagatesParentTurnIdToListener_offMainThread() {
        Set<Long> seenTurnIds = ConcurrentHashMap.newKeySet();
        StubListener lis = new StubListener() {
            @Override public void onSubagentStarted(long turnId, String taskId, String name, String desc) {
                seenTurnIds.add(turnId);
            }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis, "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"));
        runner.runAll(ds, 77L);
        assertEquals(Set.of(77L), seenTurnIds, "所有子 agent 事件应带传入的 parentTurnId=77，不得为 -1（ThreadLocal 丢失）");
    }

    @Test
    void runAll_respectsConcurrencyLimit() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                int now = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(now, Math::max);
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                inFlight.decrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(model)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 2);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"),
                new SubagentRunner.Dispatch(spec(), "p3", "d3"),
                new SubagentRunner.Dispatch(spec(), "p4", "d4"));
        Thread t = new Thread(() -> runner.runAll(ds, 1L));
        t.start();
        try {
            Thread.sleep(300);
            assertTrue(maxObserved.get() <= 2, "同时在飞不得超过并发上限 2，实际峰值=" + maxObserved.get());
            assertTrue(maxObserved.get() >= 2, "应真正并行到上限 2，实际峰值=" + maxObserved.get());
        } finally {
            release.countDown();
            t.join(3000);
        }
    }

    @Test
    void runAll_whenCallingThreadInterrupted_throwsWrappedInterrupt() throws InterruptedException {
        // 假模型：call 阻塞一会儿，确保主调线程停在 invokeAll 上，可被中断
        ChatModel blocking = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(blocking)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { runner.runAll(ds, 1L); }
            catch (Throwable t) { thrown.set(t); }
        });
        caller.start();
        Thread.sleep(200);      // 让 runAll 进入 invokeAll 阻塞
        caller.interrupt();     // 中断主调线程
        caller.join(3000);

        assertNotNull(thrown.get(), "被中断的 runAll 应抛异常");
        assertTrue(thrown.get() instanceof RuntimeException, "应为 RuntimeException，实际=" + thrown.get());
        assertTrue(thrown.get().getMessage().contains("并行子任务被中断"),
                "异常消息应含「并行子任务被中断」，实际=" + thrown.get().getMessage());
    }
}
