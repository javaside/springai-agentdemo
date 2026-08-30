package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubagentRunner} 的变化通知契约（事件驱动 UI 的 Task 4）：
 *
 * <ul>
 *   <li>前台 {@code inFlight} 计数每次增 / 减 → 恰好一次 VIEW|CONTROL（busy 闸门要重估）；</li>
 *   <li>成功 / 失败 / onSubagentStarted 抛出三条路径都各自收尾递减并通知；</li>
 *   <li>后台 {@code backgroundInFlight} 只发 VIEW（<b>绝不</b>进 busy 闸门）；</li>
 *   <li>listener 在原子计数变化之后调用，异常被隔离。</li>
 * </ul>
 */
class SubagentRunnerNotificationTest {

    private static final int VIEW_CONTROL = UiDirty.VIEW | UiDirty.CONTROL;

    private static ChatModel chatModel(String text) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    private static ChatModel failingModel() {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new RuntimeException("boom"));
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

    /** 计数记录器：每条通知的 bits 追加进列表。 */
    private static final class BitsRecorder {
        final List<Integer> bits = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    @Test
    @DisplayName("前台成功路径：进场 +1 与收尾 -1 各发一次 VIEW|CONTROL，版本各 +1")
    void foregroundSuccessNotifiesIncrementAndDecrement() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("done"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        BitsRecorder rec = new BitsRecorder();
        runner.setUiChangeListener(rec.bits::add);
        long before = runner.uiVersion();

        runner.run(spec(), "hi", "desc", 1L);

        assertEquals(List.of(VIEW_CONTROL, VIEW_CONTROL), rec.bits, "进场一次 + 收尾一次");
        assertEquals(before + 2, runner.uiVersion());
        assertEquals(0, runner.inFlightCount(), "正常完成后计数归零");
    }

    @Test
    @DisplayName("前台失败路径：递减照发 VIEW|CONTROL（异常向上传播不受影响）")
    void foregroundFailureStillNotifiesDecrement() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(failingModel())));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        BitsRecorder rec = new BitsRecorder();
        runner.setUiChangeListener(rec.bits::add);
        long before = runner.uiVersion();

        assertDoesNotThrow(() -> {
            try {
                runner.run(spec(), "hi", "desc", 1L);
            } catch (RuntimeException expected) {
                // run() 把失败包成 SubagentFailedException 抛出——原语义
            }
        });

        assertEquals(List.of(VIEW_CONTROL, VIEW_CONTROL), rec.bits, "失败也要通知递减（闸门才能解除）");
        assertEquals(before + 2, runner.uiVersion());
        assertEquals(0, runner.inFlightCount());
    }

    @Test
    @DisplayName("onSubagentStarted 抛出：计数未进场（increment 紧贴 try 之后），零通知、计数归零")
    void throwingListenerDoesNotLeakCount() {
        StubListener throwing = new StubListener() {
            @Override public void onSubagentStarted(long turnId, String taskId, String name, String desc) {
                throw new RuntimeException("listener boom");
            }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), throwing, "");
        BitsRecorder rec = new BitsRecorder();
        runner.setUiChangeListener(rec.bits::add);
        long before = runner.uiVersion();

        try {
            runner.run(spec(), "p", "d", 1L);
        } catch (RuntimeException expected) {
            // onSubagentStarted 异常向上传播——原语义
        }

        assertEquals(0, runner.inFlightCount(), "计数不得泄漏（否则 busy 闸门永久卡死）");
        assertTrue(rec.bits.isEmpty(), "计数从未变过：零通知");
        assertEquals(before, runner.uiVersion());
    }

    @Test
    @DisplayName("runAll 并行路径：N 个子 agent 各自进场/收尾，通知数 == 2N")
    void runAllNotifiesPerSubagent() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        BitsRecorder rec = new BitsRecorder();
        runner.setUiChangeListener(rec.bits::add);

        runner.runAll(List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"),
                new SubagentRunner.Dispatch(spec(), "p3", "d3")), 1L);

        assertEquals(6, rec.bits.size(), "3 个子 agent × (进场 + 收尾)");
        assertTrue(rec.bits.stream().allMatch(b -> b == VIEW_CONTROL));
        assertEquals(0, runner.inFlightCount());
    }

    @Test
    @DisplayName("后台派发与完成：backgroundInFlight 只发 VIEW，绝不含 CONTROL")
    void backgroundCountEmitsViewOnly() throws Exception {
        BackgroundTaskRegistry taskReg = new BackgroundTaskRegistry(64);
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("结论"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        runner.enableBackground(taskReg, 2);
        try {
            BitsRecorder rec = new BitsRecorder();
            runner.setUiChangeListener(rec.bits::add);
            long before = runner.uiVersion();

            runner.runInBackground(spec(), "hi", "调查");
            String id = taskReg.all().get(0).taskId();
            for (int i = 0; i < 500; i++) {
                if (taskReg.find(id) != null && taskReg.find(id).finished()) break;
                Thread.sleep(20);
            }
            // 等后台计数归零（runBackgroundBody 的 finally）
            for (int i = 0; i < 250 && runner.backgroundInFlightCount() > 0; i++) {
                Thread.sleep(20);
            }

            assertEquals(0, runner.backgroundInFlightCount(), "后台任务已收尾");
            assertEquals(0, runner.inFlightCount(), "后台任务绝不进前台 busy 闸门");
            assertTrue(!rec.bits.isEmpty(), "后台计数变化必须通知（⏱ 面板要刷新）");
            assertTrue(rec.bits.stream().allMatch(b -> b == UiDirty.VIEW),
                    "后台计数只发 VIEW，不得含 CONTROL：" + rec.bits);
            assertEquals(before + rec.bits.size(), runner.uiVersion());
        } finally {
            runner.shutdownBackground();
        }
    }

    @Test
    @DisplayName("listener 抛异常被隔离：计数照常推进，后续通知照常")
    void throwingUiListenerIsIsolated() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("done"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        runner.setUiChangeListener(bits -> { throw new IllegalStateException("boom"); });

        assertDoesNotThrow(() -> runner.run(spec(), "hi", "desc", 1L));
        assertEquals(2, runner.uiVersion(), "listener 炸了也必须已记账（进/出各一）");

        AtomicInteger calls = new AtomicInteger();
        runner.setUiChangeListener(bits -> calls.incrementAndGet());
        assertDoesNotThrow(() -> runner.run(spec(), "again", "d2", 2L));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("null listener 归一成 no-op：不抛异常")
    void nullListenerIsNormalizedToNoop() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("done"))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        runner.setUiChangeListener(null);
        assertDoesNotThrow(() -> runner.run(spec(), "hi", "desc", 1L));
        assertEquals(0, runner.inFlightCount());
    }

    /** 通知在计数变化之后：listener 里读到的 inFlightCount 已是新值（VERSION 与计数同序）。 */
    @Test
    @DisplayName("通知在原子计数变化之后：listener 内读 inFlightCount 已是新值")
    void notificationSeesUpdatedCount() throws Exception {
        CountDownLatch firstSeen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel gated = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(gated)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "");
        java.util.List<Integer> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        runner.setUiChangeListener(bits -> {
            observed.add(runner.inFlightCount());   // 监听触发瞬间读到的计数
            if (observed.size() == 1) firstSeen.countDown();
        });

        Thread t = new Thread(() -> runner.run(spec(), "p", "d", 1L));
        t.setDaemon(true);
        t.start();
        assertTrue(firstSeen.await(2, TimeUnit.SECONDS), "进场通知未到达");
        assertEquals(List.of(1), observed, "进场通知读到的必须已是新值 1（通知在计数变化之后）");

        release.countDown();
        t.join(3000);
        assertEquals(2, observed.size());
        assertEquals(0, observed.get(1), "收尾通知读到的必须已是 0");
    }
}
