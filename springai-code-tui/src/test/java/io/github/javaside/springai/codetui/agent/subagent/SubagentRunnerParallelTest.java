package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
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
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (shouldThrow) return Flux.error(new RuntimeException("boom"));
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(replyText)))));
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
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(prompt.getContents())))));
            }
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
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (prompt.getContents().contains("fail")) return Flux.error(new RuntimeException("boom"));
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
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
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    int now = inFlight.incrementAndGet();
                    maxObserved.accumulateAndGet(now, Math::max);
                    try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    inFlight.decrementAndGet();
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
                });
            }
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
    void inFlightCount_isZeroAfterNormalCompletion() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        assertEquals(0, runner.inFlightCount(), "起始在飞计数为 0");
        runner.runAll(List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2")), 1L);
        assertEquals(0, runner.inFlightCount(), "正常完成后在飞计数归零（每个 run 的 finally 递减）");
    }

    @Test
    void cancelTurn_shutsDownInFlightSubagents_andDrainsInFlightCount() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        // 阻塞到被中断的假模型：中断时以 RuntimeException 传播，使 run() 正常 unwind、finally 递减在飞计数。
        ChatModel blocking = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    started.countDown();
                    try { Thread.sleep(5000); }
                    catch (InterruptedException e) { return Flux.error(new RuntimeException("interrupted", e)); }
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
                });
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(blocking)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"));
        Thread t = new Thread(() -> runner.runAll(ds, 99L));
        t.setDaemon(true);
        t.start();
        assertTrue(started.await(2, TimeUnit.SECONDS), "子 agent 应已开跑");
        Thread.sleep(100);   // 让两个子任务都进场
        assertTrue(runner.inFlightCount() > 0, "取消前应有在飞子 agent，实际=" + runner.inFlightCount());

        runner.cancelTurn(99L);   // 对该 turn 名下的池 shutdownNow（立即返回、不 await）

        long deadline = System.currentTimeMillis() + 3000;
        while (runner.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, runner.inFlightCount(), "cancelTurn 后在飞计数应随中断传播归零");
        t.join(3000);
    }

    @Test
    void inFlight_decrementsEvenIfOnSubagentStartedThrows() {
        // 若 onSubagentStarted 抛出，run() 仍须退出在飞——否则计数永久泄漏、busy 闸门永久卡死、UI 再也无法提交。
        StubListener throwing = new StubListener() {
            @Override public void onSubagentStarted(long turnId, String taskId, String name, String desc) {
                throw new RuntimeException("listener boom");
            }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), throwing, "", 4);
        try {
            runner.run(spec(), "p", "d", 1L);
        } catch (RuntimeException expected) {
            // onSubagentStarted 的异常向上传播——符合原设计（start 事件失败不吞）
        }
        assertEquals(0, runner.inFlightCount(), "onSubagentStarted 抛出也必须退出在飞（否则 busy 闸门永久卡死）");
    }

    @Test
    void cancelTurn_unknownTurn_isNoOp() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        runner.cancelTurn(12345L);   // 无该 turn 的池：静默无操作、不抛
        assertEquals(0, runner.inFlightCount());
    }

    @Test
    void runAll_whenCallingThreadInterrupted_throwsWrappedInterrupt() throws InterruptedException {
        // 假模型：流阻塞一会儿，确保主调线程停在 invokeAll 上，可被中断
        ChatModel blocking = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
                });
            }
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
