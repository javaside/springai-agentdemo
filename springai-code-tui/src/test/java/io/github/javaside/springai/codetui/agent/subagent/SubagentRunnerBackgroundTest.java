package io.github.javaside.springai.codetui.agent.subagent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.javaside.springai.codetui.agent.AgentListener;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.StubListener;
import io.github.javaside.springai.codetui.agent.background.BackgroundTask;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** runInBackground：立刻返回、结果回填注册表、前台闸门不被拉高。用假 ChatModel，不联网。 */
class SubagentRunnerBackgroundTest {

    private static ChatModel chatModel(String text, CountDownLatch gate) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("subagent path bridges to stream()");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (gate != null) {
                    try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.error(new RuntimeException("interrupted"));
                    }
                }
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
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

    private static SubagentRunner runner(ChatModel model, BackgroundTaskRegistry reg, AgentListener lis) {
        SubagentRunner r = new SubagentRunner(new ProviderRegistry(List.of(provider(model))),
                List.of(), lis, "");
        r.enableBackground(reg, 2);
        return r;
    }

    private static void awaitDone(BackgroundTaskRegistry reg, String id) throws InterruptedException {
        // 500 轮 ×20ms = 10s：失败路径会走满 5 次重试 ×指数退避（500+1000+2000+4000 = 7.5s），
        // 旧 2s 上限在退避升级后必然超时。10s 仍留 ~2.5s 余量给调度抖动。
        for (int i = 0; i < 500; i++) {
            if (reg.find(id) != null && reg.find(id).finished()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("后台任务在 10s 内未结束");
    }

    @Test
    void returnsImmediatelyWithTaskIdAndDoesNotBlock() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", gate), reg, new StubListener());

        long t0 = System.nanoTime();
        String out = r.runInBackground(spec(), "hi", "调查登录失败");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 1000, "派发必须立刻返回，实测 " + elapsedMs + "ms");
        assertTrue(out.contains("task_"), "返回文本要含 taskId：" + out);
        assertEquals(1, reg.runningCount());

        gate.countDown();
        awaitDone(reg, reg.all().get(0).taskId());
    }

    @Test
    void resultIsWrittenBackToRegistryOnSuccess() throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("调查结论", null), reg, new StubListener());
        r.runInBackground(spec(), "hi", "调查");
        String id = reg.all().get(0).taskId();
        awaitDone(reg, id);
        assertEquals(BackgroundTask.Status.DONE, reg.find(id).status());
        assertEquals("调查结论", reg.find(id).result());
    }

    @Test
    void successfulLifecycleIsLoggedAtInfoWithoutPollutingReturnedResult() throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("只返回最终结论", null), reg, new StubListener());
        Logger logger = (Logger) LoggerFactory.getLogger(SubagentRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String dispatchResult = r.runInBackground(spec(), "hi", "调查日志策略");
            String id = reg.all().get(0).taskId();
            awaitDone(reg, id);

            List<ILoggingEvent> lifecycle = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains(id))
                    .toList();
            assertTrue(lifecycle.stream().anyMatch(e -> e.getLevel() == Level.INFO
                            && e.getFormattedMessage().contains("已提交")),
                    "后台任务提交应写 INFO 生命周期日志：" + lifecycle);
            assertTrue(lifecycle.stream().anyMatch(e -> e.getLevel() == Level.INFO
                            && e.getFormattedMessage().contains("开始执行")),
                    "后台任务开始应写 INFO 生命周期日志：" + lifecycle);
            assertTrue(lifecycle.stream().anyMatch(e -> e.getLevel() == Level.INFO
                            && e.getFormattedMessage().contains("执行完成")),
                    "后台任务完成应写 INFO 生命周期日志：" + lifecycle);
            assertTrue(lifecycle.stream().noneMatch(e -> e.getLevel() == Level.DEBUG),
                    "后台任务生命周期不得使用 DEBUG：" + lifecycle);
            assertEquals("只返回最终结论", reg.find(id).result(),
                    "主会话可取回的任务结果只能是 subagent 最终结论，不能夹带生命周期日志");
            assertTrue(!dispatchResult.contains("已提交") && !dispatchResult.contains("开始执行")
                            && !dispatchResult.contains("执行完成"),
                    "Task 调用的立即返回文本不得夹带生产日志：" + dispatchResult);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            r.shutdownBackground();
        }
    }

    @Test
    void failureIsRecordedAsFailedNotThrownToCaller() throws Exception {
        ChatModel boom = new ChatModel() {
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                return Flux.error(new RuntimeException("Error reading response",
                        new java.io.IOException("No content to map due to end-of-input")));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(boom, reg, new StubListener());

        r.runInBackground(spec(), "hi", "调查");        // 不得抛：后台任务失败不该炸掉主 agent 的回合

        String id = reg.all().get(0).taskId();
        awaitDone(reg, id);
        assertEquals(BackgroundTask.Status.FAILED, reg.find(id).status());
        assertTrue(reg.find(id).result().contains("end-of-input"), "失败文本应摊平 cause 链");
    }

    @Test
    void backgroundTasksDoNotRaiseForegroundInFlightGate() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", gate), reg, new StubListener());

        r.runInBackground(spec(), "hi", "调查");
        // 关键断言：此刻只有后台任务在飞，前台计数必须是 0，否则 busy 闸门会挡住新回合 = 后台化失效
        assertEquals(0, r.inFlightCount(), "后台任务绝不能计入前台在飞计数");
        assertEquals(1, r.backgroundInFlightCount());

        gate.countDown();
        awaitDone(reg, reg.all().get(0).taskId());
    }

    @Test
    void emitsBackgroundListenerEventsNotTurnScopedOnes() throws Exception {
        AtomicReference<String> started = new AtomicReference<>();
        AtomicReference<String> finished = new AtomicReference<>();
        AtomicReference<Boolean> sawTurnScoped = new AtomicReference<>(false);
        AgentListener lis = new StubListener() {
            @Override public void onBackgroundTaskStarted(String taskId, String agentName, String description) {
                started.set(taskId + "|" + agentName + "|" + description);
            }
            @Override public void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
                finished.set(taskId + "|" + finalText + "|" + ok);
            }
            @Override public void onSubagentStarted(long turnId, String taskId, String a, String d) {
                sawTurnScoped.set(true);
            }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", null), reg, lis);

        r.runInBackground(spec(), "hi", "调查登录失败");
        awaitDone(reg, reg.all().get(0).taskId());
        for (int i = 0; i < 50 && finished.get() == null; i++) Thread.sleep(20);

        assertNotNull(started.get());
        assertTrue(started.get().contains("explore"));
        assertTrue(started.get().contains("调查登录失败"));
        assertNotNull(finished.get());
        assertTrue(finished.get().endsWith("|true"));
        assertEquals(Boolean.FALSE, sawTurnScoped.get(),
                "后台任务不得发回合级事件——那些会被迟到过滤丢弃，且会污染 ⟐ 面板");
    }

    @Test
    void queueFullIsRejectedWithClearTextAndTaskIsNotDeliverable() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = new SubagentRunner(
                new ProviderRegistry(List.of(provider(chatModel("结论", gate)))), List.of(),
                new StubListener(), "");
        r.enableBackground(reg, 1, 1);          // 并发 1、队列 1

        r.runInBackground(spec(), "hi", "a");   // 占住唯一线程
        r.runInBackground(spec(), "hi", "b");   // 占住唯一队列位
        String third = r.runInBackground(spec(), "hi", "c");

        assertTrue(third.contains("队列已满"), "拒绝要说清楚，别静默排队：" + third);
        assertTrue(reg.completedUnconsumed().isEmpty(),
                "被拒的任务不得成为可送达结果——否则会被自动送给模型，读起来像它真跑过");

        gate.countDown();
    }

    /**
     * ★ 派发被拒时必须补一次结束事件，否则 ⏱ 面板上留下一条<b>永远在转的幽灵任务</b>。
     *
     * <p>顺序是 register → onBackgroundTaskStarted → execute，rejected 分支原来只做了
     * {@code registry.kill}——注册表干净了，但 UI 镜像那条 BackgroundEntry 永远停在 RUNNING。
     *
     * <p><b>断言打在 {@link ConversationState#backgroundRunningCount()} 上而不是「listener 被调过」</b>：
     * 卡在 RUNNING 才是用户看得见的症状（状态栏永久挂「⏱ N 个后台任务」、耗时涨到天荒地老），
     * 而他按 k 想终止它时，注册表里那条已是 KILLED → 回「终止失败」，那行却还在转。
     */
    @Test
    void rejectedDispatchDoesNotLeaveGhostRunningTask() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        ConversationState state = new ConversationState();   // 真 UI 镜像，不是计数桩
        SubagentRunner r = new SubagentRunner(
                new ProviderRegistry(List.of(provider(chatModel("结论", gate)))), List.of(),
                state, "");
        r.enableBackground(reg, 1, 1);          // 并发 1、队列 1 → 第 3 次必被拒

        r.runInBackground(spec(), "hi", "a");   // 占住唯一线程（卡在 gate 上）
        r.runInBackground(spec(), "hi", "b");   // 占住唯一队列位
        r.runInBackground(spec(), "hi", "c");   // 被拒

        assertEquals(2, state.backgroundRunningCount(),
                "只有 a、b 真的在跑；被拒的 c 必须立刻结束，否则 ⏱ 面板永远转下去。实际面板="
                        + state.backgroundTasks());

        gate.countDown();
    }

    @Test
    void backgroundToolContextCarriesBackgroundMarkerAndNoRealTurnId() throws Exception {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                captured.set(p);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(capturing, reg, new StubListener());
        r.runInBackground(spec(), "hi", "调查");
        awaitDone(reg, reg.all().get(0).taskId());
        assertNotNull(captured.get(), "假模型应当真的被调用过");
    }
}
