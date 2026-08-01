package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionCallback} 的行为与<b>活性</b>测试。
 *
 * <p><b>纪律</b>：凡涉及阻塞的用例一律用真实线程 + 带超时的 {@code await} / {@code join}，
 * 断言「线程醒了」而不是「代码看起来会醒」。回归时测试必须<b>失败</b>而不是永久挂住——
 * 挂死的测试什么也证明不了。
 */
class PermissionCallbackTest {

    /** 记录是否被真正调用的假工具。 */
    private static final class FakeTool implements ToolCallback {
        final AtomicBoolean called = new AtomicBoolean();
        private final String name;
        FakeTool(String name) { this.name = name; }
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        }
        @Override public String call(String input) { called.set(true); return "OK"; }
        @Override public String call(String input, ToolContext ctx) { return call(input); }
    }

    private static PermissionEngine engine(Path root, PermissionMode mode) {
        return new PermissionEngine(root, new PermissionConfig(mode, List.of()), mode, true);
    }

    private static ToolContext ctx(long turnId) {
        return new ToolContext(Map.of("turnId", turnId));
    }

    /** 轮询等到列表至少有 n 个元素；超时即失败（绝不无限等）。 */
    private static void awaitSize(List<?> list, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (list.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(list.size() >= n, "3 秒内只收到 " + list.size() + " 个审批请求，期望 " + n);
    }

    private static Thread start(Runnable body) {
        Thread t = new Thread(body);
        t.setDaemon(true);      // 万一真挂住，别拖着 JVM 不退出
        t.start();
        return t;
    }

    // ── 三条基本路径 ──────────────────────────────────────────────────

    @Test
    @DisplayName("ALLOW：透传给真实工具，返回原样")
    void allowPassesThrough(@TempDir Path root) {
        FakeTool tool = new FakeTool("Read");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { throw new AssertionError("ALLOW 不该弹审批"); });

        assertEquals("OK", cb.call("{\"filePath\":\"" + root.resolve("a.txt") + "\"}", ctx(1L)));
        assertTrue(tool.called.get());
    }

    @Test
    @DisplayName("DENY：返回拒绝字符串，不抛异常，不调用真实工具（回合要继续）")
    void denyReturnsStringNotException(@TempDir Path root) {
        FakeTool tool = new FakeTool("Bash");
        // 审批面板选「拒绝」→ 同样走返回串路径
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> r.responder().respond(PermissionOutcome.DENY));

        String out = cb.call("{\"command\":\"curl http://x\"}", ctx(1L));

        assertTrue(out.startsWith("Permission denied:"), "实际返回：" + out);
        assertFalse(tool.called.get(), "被拒绝的工具绝不能真的执行");
    }

    @Test
    @DisplayName("引擎判 DENY（deny 规则）：同样返回拒绝串，且根本不发审批请求")
    void engineDenyDoesNotAsk(@TempDir Path root) {
        FakeTool tool = new FakeTool("Bash");
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT,
                        List.of(io.github.javaside.springai.codetui.agent.permission.PermissionRule.parse(
                                "Bash(curl:*)",
                                io.github.javaside.springai.codetui.agent.permission.PermissionBehavior.DENY,
                                io.github.javaside.springai.codetui.agent.permission.RuleScope.PROJECT))),
                PermissionMode.DEFAULT, true);
        PermissionCallback cb = new PermissionCallback(tool, e,
                (t, r) -> { throw new AssertionError("deny 规则不该弹审批"); });

        String out = cb.call("{\"command\":\"curl http://x\"}", ctx(1L));

        assertTrue(out.startsWith("Permission denied:"), "实际返回：" + out);
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("ASK：阻塞工具线程，直到 UI 喂回结果")
    void askBlocksUntilAnswered(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        AtomicReference<PermissionRequest> captured = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { captured.set(r); asked.countDown(); });

        AtomicReference<String> result = new AtomicReference<>();
        Thread worker = start(() -> result.set(cb.call("{\"command\":\"npm test\"}", ctx(1L))));

        assertTrue(asked.await(3, TimeUnit.SECONDS), "应在 3 秒内发出审批请求");
        assertFalse(tool.called.get(), "喂回结果前工具不该执行");

        captured.get().responder().respond(PermissionOutcome.ALLOW_ONCE);
        worker.join(3000);

        assertFalse(worker.isAlive(), "喂回结果后工具线程必须解除阻塞");
        assertEquals("OK", result.get());
        assertTrue(tool.called.get());
    }

    @Test
    @DisplayName("审批请求带齐面板要用的字段（turnId / taskId / 目标 / 原始入参 / 原因）")
    void requestCarriesPanelFields(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        AtomicReference<PermissionRequest> captured = new AtomicReference<>();
        AtomicReference<Long> askedTurn = new AtomicReference<>();
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { askedTurn.set(t); captured.set(r); r.responder().respond(PermissionOutcome.DENY); });

        cb.call("{\"command\":\"npm test\"}", new ToolContext(Map.of("turnId", 42L, "taskId", "task_7")));

        PermissionRequest r = captured.get();
        assertEquals(42L, r.turnId());
        assertEquals(42L, askedTurn.get(), "asker 的 turnId 必须与请求一致");
        assertEquals("task_7", r.taskId(), "子 agent 的 taskId 要透出去，面板才能标来源");
        assertEquals("Bash", r.toolName());
        assertEquals("npm test", r.target(), "COMMAND 的判定目标是命令本身");
        assertEquals("{\"command\":\"npm test\"}", r.rawInput());
        assertTrue(r.reason() != null && !r.reason().isBlank(), "面板要显示原因");
    }

    // ── 允许并记规则 ──────────────────────────────────────────────────

    @Test
    @DisplayName("ALLOW_SESSION：加会话规则，同类调用下次直接放行不再问")
    void allowSessionAddsRule(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        PermissionCallback cb = new PermissionCallback(tool, e, (t, r) -> {
            asks.add(r);
            r.responder().respond(PermissionOutcome.ALLOW_SESSION);
        });

        cb.call("{\"command\":\"npm test\"}", ctx(1L));
        cb.call("{\"command\":\"npm test -- --watch\"}", ctx(1L));

        assertEquals(1, asks.size(), "第二次同前缀调用应被会话规则放行，不再弹审批");
    }

    @Test
    @DisplayName("ALLOW_ALWAYS：规则落盘到项目层 permissions.json")
    void allowAlwaysPersists(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        PermissionCallback cb = new PermissionCallback(tool, e,
                (t, r) -> r.responder().respond(PermissionOutcome.ALLOW_ALWAYS));

        assertEquals("OK", cb.call("{\"command\":\"npm test\"}", ctx(1L)));

        Path file = root.resolve(".codetui").resolve("permissions.json");
        assertTrue(java.nio.file.Files.exists(file), "「永久允许」必须写项目层 permissions.json：" + file);
        assertTrue(java.nio.file.Files.readString(file).contains("npm test"),
                "落盘内容：" + java.nio.file.Files.readString(file));
    }

    @Test
    @DisplayName("建议规则为 null（内置危险检查引发的 ASK）时选「本会话」也不会炸，只是不记规则")
    void allowSessionWithoutSuggestionStillRuns(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Read");
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        PermissionCallback cb = new PermissionCallback(tool, e, (t, r) -> {
            asks.add(r);
            r.responder().respond(PermissionOutcome.ALLOW_SESSION);
        });

        // 读 ~/.ssh/id_rsa：内置危险检查命中 → askOnly（suggested == null）
        String secret = Path.of(System.getProperty("user.home"), ".ssh", "id_rsa").toString();
        assertEquals("OK", cb.call("{\"filePath\":\"" + secret.replace("\\", "\\\\") + "\"}", ctx(1L)));
        assertEquals(1, asks.size());
        assertNull(asks.get(0).suggested(), "内置危险检查引发的 ASK 不该给建议规则");
    }

    // ── 取消与中断 ────────────────────────────────────────────────────

    @Test
    @DisplayName("CANCEL：抛 PermissionCancelledException，不调用真实工具")
    void cancelThrows(@TempDir Path root) {
        FakeTool tool = new FakeTool("Bash");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> r.responder().respond(PermissionOutcome.CANCEL));

        assertThrows(PermissionCancelledException.class,
                () -> cb.call("{\"command\":\"npm test\"}", ctx(1L)));
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("取消期排空循环调 ModalRequest.cancel() 也能唤醒阻塞的工具线程")
    void modalCancelWakesBlockedThread(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asks.add(r));

        start(() -> {
            try {
                cb.call("{\"command\":\"npm test\"}", ctx(1L));
            } catch (Throwable e) {
                thrown.set(e);
            } finally {
                done.countDown();
            }
        });

        awaitSize(asks, 1);
        asks.get(0).cancel();                       // ConversationState.clearModals() 的动作

        assertTrue(done.await(3, TimeUnit.SECONDS), "cancel() 后工具线程必须醒");
        assertTrue(thrown.get() instanceof PermissionCancelledException, "实际：" + thrown.get());
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("活性：中断阻塞中的工具线程不会永久 park")
    void interruptDoesNotPark(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asked.countDown());   // 故意不应答

        Thread worker = start(() -> {
            try {
                cb.call("{\"command\":\"npm test\"}", ctx(1L));
            } catch (RuntimeException expected) {
                // PermissionCancelledException
            } finally {
                done.countDown();
            }
        });
        assertTrue(asked.await(3, TimeUnit.SECONDS));
        worker.interrupt();

        assertTrue(done.await(3, TimeUnit.SECONDS), "被中断的工具线程必须退出，不能永久 park");
    }

    /**
     * 硬约束 2：{@code take()} 抛 InterruptedException 时会<b>清掉</b>中断标志，必须重新置位。
     * 漏了这一句，上层（回合 dispose / Reactor 的取消传播）就看不到「本线程已被取消」。
     *
     * <p>本用例会<b>咬</b>：把实现里的 {@code Thread.currentThread().interrupt()} 删掉，断言立刻红。
     */
    @Test
    @DisplayName("硬约束 2：中断被 take() 吞掉后必须重新置位中断标志")
    void interruptFlagIsReasserted(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asked.countDown());   // 故意不应答

        Thread worker = start(() -> {
            try {
                cb.call("{\"command\":\"npm test\"}", ctx(1L));
            } catch (Throwable e) {
                thrown.set(e);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                done.countDown();
            }
        });
        assertTrue(asked.await(3, TimeUnit.SECONDS));
        worker.interrupt();

        assertTrue(done.await(3, TimeUnit.SECONDS), "被中断的工具线程必须退出");
        assertTrue(thrown.get() instanceof PermissionCancelledException, "实际：" + thrown.get());
        assertTrue(stillInterrupted.get(),
                "take() 清掉了中断标志，实现必须 Thread.currentThread().interrupt() 重新置位");
    }

    // ── 硬约束 1：一次调用一个 handoff ─────────────────────────────────

    /**
     * 硬约束 1 的<b>行为</b>钉子：handoff 若被提升成字段（跨调用复用），上一次调用<b>被消费之后</b>
     * 迟到的 {@code CANCEL} 会留在队列里，被<b>下一次</b>调用读走——杀掉用户刚刚批准的回合。
     *
     * <p>这条是确定性的：不依赖线程调度，把 {@code new ArrayBlockingQueue<>(1)} 挪成字段即红。
     */
    @Test
    @DisplayName("硬约束 1：每次调用新建 handoff——上一次的迟到 CANCEL 不得杀掉下一次调用")
    void staleCancelDoesNotLeakIntoNextCall(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asks.add(r));      // 不同步应答，时序由主线程掌控

        // 第 1 次：批准 → 工具线程消费掉 ALLOW_ONCE 后正常返回（此时 handoff 已空）
        AtomicReference<String> first = new AtomicReference<>();
        Thread w1 = start(() -> first.set(cb.call("{\"command\":\"npm test\"}", ctx(1L))));
        awaitSize(asks, 1);
        asks.get(0).responder().respond(PermissionOutcome.ALLOW_ONCE);
        w1.join(3000);
        assertFalse(w1.isAlive());
        assertEquals("OK", first.get());

        // 迟到的 CANCEL：真实来源是取消期排空循环 / 面板重复按键，契约明文允许它到来
        asks.get(0).cancel();

        // 第 2 次：必须读到自己的 ALLOW_ONCE，而不是上一次遗留的 CANCEL
        AtomicReference<String> second = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            try {
                second.set(cb.call("{\"command\":\"npm run build\"}", ctx(1L)));
            } catch (Throwable e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        });
        awaitSize(asks, 2);
        asks.get(1).responder().respond(PermissionOutcome.ALLOW_ONCE);

        assertTrue(done.await(3, TimeUnit.SECONDS), "第 2 次调用必须醒");
        assertNull(err.get(), "复用 handoff 会让第 2 次调用读到陈旧 CANCEL：" + err.get());
        assertEquals("OK", second.get());
    }

    /** 硬约束 1 的<b>结构</b>钉子：两个并发请求不得共用同一个应答口。 */
    @Test
    @DisplayName("硬约束 1：并发的两个请求各自持有独立的 responder")
    void eachCallGetsItsOwnResponder(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asks.add(r));

        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            int n = i;
            start(() -> {
                try {
                    if ("OK".equals(cb.call("{\"command\":\"job" + n + " run\"}", ctx(1L)))) {
                        allowed.incrementAndGet();
                    }
                } catch (RuntimeException ignored) {
                    // CANCEL 分支
                } finally {
                    done.countDown();
                }
            });
        }
        awaitSize(asks, 2);
        PermissionRequest a = asks.get(0);
        PermissionRequest b = asks.get(1);
        assertFalse(a.responder() == b.responder(), "两个请求共用应答口 → 响应会串到别人身上");

        // 一个 DENY 一个 ALLOW：共用 handoff 时结论会串（实测过），这里要求恰好一个被放行
        a.responder().respond(PermissionOutcome.DENY);
        b.responder().respond(PermissionOutcome.ALLOW_ONCE);
        assertTrue(done.await(3, TimeUnit.SECONDS), "两个线程都必须醒");
        assertEquals(1, allowed.get(), "恰好一个调用被放行");
    }

    @Test
    @DisplayName("并发：多个线程各自阻塞在自己的队列上，各自独立唤醒（ParallelTasks 场景）")
    void concurrentRequestsAreIndependent(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        CountDownLatch allAsked = new CountDownLatch(3);
        CountDownLatch allDone = new CountDownLatch(3);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { asks.add(r); allAsked.countDown(); });

        for (int i = 0; i < 3; i++) {
            int n = i;
            start(() -> {
                try {
                    cb.call("{\"command\":\"job" + n + " run\"}", ctx(1L));
                } catch (RuntimeException ignored) {
                    // CANCEL 分支
                } finally {
                    allDone.countDown();
                }
            });
        }

        assertTrue(allAsked.await(3, TimeUnit.SECONDS), "3 个线程都应各自发出审批请求");
        for (PermissionRequest r : asks) {
            r.responder().respond(PermissionOutcome.DENY);
        }
        assertTrue(allDone.await(3, TimeUnit.SECONDS),
                "每个线程必须被自己的应答唤醒——共用一个队列会漏唤醒");
    }

    // ── 落地端与异常路径 ──────────────────────────────────────────────

    /**
     * 没有接管审批 UI 的落地端（回显桩 / 测试桩）走 {@link AgentListener} 的默认实现——
     * 它<b>立即</b> DENY。这条也覆盖 {@code ConversationState} 的「迟到 / 队满」路径：
     * 二者同款，都是在 {@code onPermissionRequested} 里同步应答 DENY。
     */
    @Test
    @DisplayName("落地端未接管审批（listener 默认 DENY）：线程立刻醒，返回拒绝串")
    void listenerDefaultDenyWakesThread(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        AgentListener bare = new NoopListener();       // 不覆写 onPermissionRequested
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT), bare);

        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            try {
                out.set(cb.call("{\"command\":\"npm test\"}", ctx(1L)));
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(3, TimeUnit.SECONDS), "默认 DENY 必须立刻唤醒工具线程");
        assertTrue(out.get().startsWith("Permission denied:"), "实际：" + out.get());
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("asker 自身抛异常：失败关闭成 DENY，线程不 park，回合不炸")
    void askerThrowingFailsClosed(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { throw new IllegalStateException("UI 挂了"); });

        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        start(() -> {
            try {
                out.set(cb.call("{\"command\":\"npm test\"}", ctx(1L)));
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(3, TimeUnit.SECONDS), "asker 抛异常后工具线程不能 park");
        assertTrue(out.get() != null && out.get().startsWith("Permission denied:"), "实际：" + out.get());
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("真实工具抛异常：原样上抛（权限层不吞工具错误），线程不 park")
    void delegateExceptionPropagates(@TempDir Path root) {
        ToolCallback boom = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("Read").description("d").inputSchema("{}").build();
            }
            @Override public String call(String input) { throw new IllegalStateException("工具炸了"); }
            @Override public String call(String input, ToolContext c) { return call(input); }
        };
        PermissionCallback cb = new PermissionCallback(boom, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { throw new AssertionError("只读工具不该弹审批"); });

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> cb.call("{\"filePath\":\"" + root.resolve("a.txt") + "\"}", ctx(1L)));
        assertEquals("工具炸了", ex.getMessage());
    }

    @Test
    @DisplayName("透传：getToolDefinition 与无 ToolContext 的 call 重载都不改变语义")
    void passesThroughDefinitionAndSingleArgCall(@TempDir Path root) {
        FakeTool tool = new FakeTool("Read");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> r.responder().respond(PermissionOutcome.DENY));

        assertEquals("Read", cb.getToolDefinition().name());
        assertSame(tool.getToolDefinition().name(), cb.getToolDefinition().name());
        assertEquals("OK", cb.call("{\"filePath\":\"" + root.resolve("a.txt") + "\"}"));
        assertTrue(tool.called.get());
    }

    /** 只实现必需方法的空 listener：用来验证 {@code onPermissionRequested} 的<b>默认</b>实现。 */
    private static class NoopListener implements AgentListener {
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }
    }
}
