package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模态请求类型的接缝测试。
 *
 * <p><b>活性优先</b>：本任务最危险的失效模式不是「结果算错」，而是「工具线程永久 park」。
 * 故除了类型层断言外，下半部分用<b>真实线程 + 超时</b>验证：任何唤醒路径（cancel / listener 默认实现）
 * 都必须在有限时间内把阻塞线程放出来。所有等待都带超时——退化时测试是 FAIL，不是挂死。
 */
class ModalRequestTest {

    /** 唤醒断言的统一上限：正常路径远小于此值，超过即视为「没醒」。 */
    private static final long WAKE_TIMEOUT_MS = 5_000;

    // ── 类型层：sealed 家族与统一取消入口 ──

    @Test
    @DisplayName("AskRequest 与 PermissionRequest 都是 ModalRequest，可放进同一个队列")
    void bothAreModalRequests() {
        AskRequest ask = new AskRequest(7L, List.of(), noopAskResponder());
        PermissionRequest perm = permissionRequest(7L, o -> { });

        assertInstanceOf(ModalRequest.class, ask);
        assertInstanceOf(ModalRequest.class, perm);

        // 真正的用途：Task 10 要把两者塞进同一条模态请求队列
        Deque<ModalRequest> queue = new ArrayDeque<>();
        queue.add(ask);
        queue.add(perm);
        assertEquals(7L, queue.poll().turnId());
        assertEquals(7L, queue.poll().turnId());
    }

    @Test
    @DisplayName("统一取消入口：PermissionRequest.cancel() 投 CANCEL")
    void cancelWakesBlockedThread() {
        AtomicReference<PermissionOutcome> got = new AtomicReference<>();
        ModalRequest perm = permissionRequest(1L, got::set);

        perm.cancel();

        assertEquals(PermissionOutcome.CANCEL, got.get(),
                "cancel() 必须给工具线程投 CANCEL，否则它永久 park");
    }

    @Test
    @DisplayName("统一取消入口：AskRequest.cancel() 转成问询自己的取消语义")
    void askCancelDelegatesToResponder() {
        AtomicReference<String> got = new AtomicReference<>();
        ModalRequest ask = new AskRequest(1L, List.of(), new AskResponder() {
            @Override public void answer(Map<String, String> answers) { got.set("answer"); }
            @Override public void cancel() { got.set("cancel"); }
        });

        ask.cancel();

        assertEquals("cancel", got.get(), "ModalRequest.cancel() 必须落到 AskResponder.cancel()");
    }

    @Test
    @DisplayName("sealed 生效：家族恰好是 AskRequest | PermissionRequest，外部无法再加实现")
    void sealedFamilyIsClosed() {
        // 计划原文用 `switch (r) { case AskRequest a -> …; case PermissionRequest p -> … }` 断言穷尽性，
        // 但按类型模式 switch 是 JEP 441（Java 21），本模块编译在 release=17，编译不过。
        // sealed 本身在 17 上完全生效，故改为直接对着 permitted 名单断言——
        // 这比原写法更强：原写法只证「这两个分支能编译」，本断言还钉住「不会有第三个」。
        Class<?>[] permitted = ModalRequest.class.getPermittedSubclasses();
        assertTrue(ModalRequest.class.isSealed(), "ModalRequest 必须是 sealed，否则外部可绕开模态队列纪律");
        assertEquals(2, permitted.length, "模态家族只应有问询与审批两员；新增成员须巡查所有 instanceof 分发点");
        assertEquals(Set.of(AskRequest.class, PermissionRequest.class), Set.of(permitted));
    }

    @Test
    @DisplayName("消费方按 instanceof 分发两类模态（release=17 下穷尽性由人保证）")
    void instanceofDispatchCoversBothKinds() {
        assertEquals("ask", kindOf(new AskRequest(1L, List.of(), noopAskResponder())));
        assertEquals("permission", kindOf(permissionRequest(1L, o -> { })));
    }

    /** Task 10/15 里 UI drain 的分发形状：sealed 保证这条链已覆盖全部成员。 */
    private static String kindOf(ModalRequest r) {
        if (r instanceof AskRequest) {
            return "ask";
        } else if (r instanceof PermissionRequest) {
            return "permission";
        }
        throw new AssertionError("sealed 家族出现了未处理的成员: " + r.getClass());
    }

    @Test
    @DisplayName("PermissionRequest 如实带上面板所需的全部字段")
    void permissionRequestCarriesPanelFields() {
        PermissionRequest r = permissionRequest(3L, o -> { });

        assertEquals(3L, r.turnId());
        assertNull(r.taskId(), "主 agent 的请求 taskId 为 null");
        assertEquals("Bash", r.toolName());
        assertEquals("git push origin main", r.target());
        assertEquals("未授权的操作", r.reason());
        assertNotNull(r.suggested(), "建议规则供面板生成「本会话不再问」/「永久允许」");
        assertEquals("Bash(git push:*)", r.suggested().toDsl());
    }

    // ── 活性：真实线程 ──

    @Test
    @DisplayName("活性：cancel() 唤醒真实阻塞的工具线程")
    void cancelReleasesRealBlockedThread() throws Exception {
        Handoff handoff = new Handoff();
        ModalRequest request = permissionRequest(1L, handoff);
        ToolThread tool = ToolThread.blockOn(handoff);

        request.cancel();

        assertEquals(PermissionOutcome.CANCEL, tool.awaitOutcome(),
                "cancel() 后工具线程必须在超时内醒来");
        tool.assertTerminated();
    }

    @Test
    @DisplayName("活性：两个并发请求各自独立唤醒，取消其一不影响另一个")
    void concurrentRequestsWakeIndependently() throws Exception {
        Handoff first = new Handoff();
        Handoff second = new Handoff();
        ModalRequest r1 = permissionRequest(1L, first);
        ModalRequest r2 = permissionRequest(1L, second);
        ToolThread t1 = ToolThread.blockOn(first);
        ToolThread t2 = ToolThread.blockOn(second);

        r1.cancel();

        assertEquals(PermissionOutcome.CANCEL, t1.awaitOutcome());
        t1.assertTerminated();
        assertFalse(t2.isDone(), "只取消了 r1，t2 不该被顺带唤醒（应答口必须一请求一个）");

        second.respond(PermissionOutcome.ALLOW_ONCE);

        assertEquals(PermissionOutcome.ALLOW_ONCE, t2.awaitOutcome());
        t2.assertTerminated();
    }

    @Test
    @DisplayName("活性：重复 cancel / 取消与应答竞争都不抛异常，首个信号胜出")
    void repeatedSignalsAreSafe() throws Exception {
        Handoff handoff = new Handoff();
        ModalRequest request = permissionRequest(1L, handoff);
        ToolThread tool = ToolThread.blockOn(handoff);

        handoff.respond(PermissionOutcome.ALLOW_ONCE);
        request.cancel();                                // 迟到的取消：既不能抛，也不能改结论
        request.cancel();

        assertEquals(PermissionOutcome.ALLOW_ONCE, tool.awaitOutcome(),
                "首个信号胜出；迟到信号被安全丢弃");
        tool.assertTerminated();
    }

    // ── listener 接缝 ──

    @Test
    @DisplayName("AgentListener 的默认实现直接 DENY —— 未实现该方法的落地端不会让工具线程永久 park")
    void listenerDefaultDenies() {
        AtomicReference<PermissionOutcome> got = new AtomicReference<>();
        AgentListener bare = new BareListener();

        bare.onPermissionRequested(1L, permissionRequest(1L, got::set));

        assertEquals(PermissionOutcome.DENY, got.get(),
                "默认实现必须应答（DENY），不能是空实现");
    }

    @Test
    @DisplayName("活性：默认 listener 实现放得开真实阻塞的工具线程")
    void listenerDefaultReleasesRealBlockedThread() throws Exception {
        Handoff handoff = new Handoff();
        PermissionRequest request = permissionRequest(1L, handoff);
        ToolThread tool = ToolThread.blockOn(handoff);

        new BareListener().onPermissionRequested(1L, request);

        assertEquals(PermissionOutcome.DENY, tool.awaitOutcome(),
                "默认实现若是空实现，本断言会在超时后 FAIL（而不是让测试挂死）");
        tool.assertTerminated();
    }

    // ── 排空健壮性（Task 9R）──

    @Test
    @DisplayName("null responder 在构造期就被拒绝，而不是留到排空期炸")
    void nullResponderRejectedAtConstruction() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> new PermissionRequest(1L, null, "Bash", "cmd", "{}", "why", null, null),
                "record 静默接受 null responder 的话，NPE 会推迟到 cancel() 时才炸");
        assertTrue(e.getMessage() != null && e.getMessage().contains("responder"),
                "报错须指名 responder，别让排查者去猜哪个字段是 null");
    }

    @Test
    @DisplayName("排空循环：一个坏元素不该让它后面的工具线程永久 park")
    void oneBadRequestDoesNotStrandTheRest() throws Exception {
        // 复刻 cancelCurrent() 的排空循环：坏元素若能进队列，循环会在它那里中断，
        // 队列里其后的请求永不被唤醒。构造期拦截使这种队列根本无法被组装出来。
        Handoff survivor = new Handoff();
        ToolThread tool = ToolThread.blockOn(survivor);
        Deque<ModalRequest> queue = new ArrayDeque<>();

        assertThrows(NullPointerException.class,
                () -> queue.add(new PermissionRequest(1L, null, "Bash", "bad", "{}", "why", null, null)));
        queue.add(permissionRequest(1L, survivor));

        for (ModalRequest r : queue) {                    // 无 try/catch：契约要求 cancel() 不抛
            r.cancel();
        }

        assertEquals(PermissionOutcome.CANCEL, tool.awaitOutcome(),
                "坏元素没能入队，故排空循环走完全程，后面的工具线程被唤醒");
        tool.assertTerminated();
    }

    // ── 测试替身 ──

    private static PermissionRequest permissionRequest(long turnId, PermissionResponder responder) {
        return new PermissionRequest(turnId, null, "Bash", "git push origin main",
                "{\"command\":\"git push origin main\"}", "未授权的操作",
                PermissionRule.parse("Bash(git push:*)", PermissionBehavior.ALLOW, RuleScope.SESSION),
                responder);
    }

    private static AskResponder noopAskResponder() {
        return new AskResponder() {
            @Override public void answer(Map<String, String> answers) { }
            @Override public void cancel() { }
        };
    }

    /**
     * 一次性应答口的最小复刻：容量 1 的队列 + 非阻塞 {@code offer}
     * （与 {@code UserQuestionBridge} / 未来的 {@code PermissionCallback} 同款）。
     * 「非阻塞」很关键：{@code cancel()} 由 UI 线程调用，若它会阻塞，卡住的就是整个界面。
     */
    private static final class Handoff implements PermissionResponder {
        private final ArrayBlockingQueue<PermissionOutcome> q = new ArrayBlockingQueue<>(1);

        @Override
        public void respond(PermissionOutcome outcome) {
            q.offer(outcome);                            // 满了就丢：首个信号胜出
        }

        PermissionOutcome take() throws InterruptedException {
            return q.poll(WAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);   // 带超时：回归时 FAIL 而非挂死
        }
    }

    /** 模拟阻塞在应答口上的工具线程。 */
    private static final class ToolThread {
        private final Thread thread;
        private final AtomicReference<PermissionOutcome> outcome = new AtomicReference<>();

        private ToolThread(Handoff handoff) {
            this.thread = new Thread(() -> {
                try {
                    outcome.set(handoff.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "fake-tool-thread");
        }

        static ToolThread blockOn(Handoff handoff) {
            ToolThread t = new ToolThread(handoff);
            t.thread.start();
            return t;
        }

        /** 等线程醒来并返回它收到的结论；超时即 null（断言随后 FAIL）。 */
        PermissionOutcome awaitOutcome() throws InterruptedException {
            thread.join(WAKE_TIMEOUT_MS);
            return outcome.get();
        }

        boolean isDone() {
            return !thread.isAlive();
        }

        void assertTerminated() {
            assertFalse(thread.isAlive(), "工具线程必须已退出，不能留在 park 状态");
        }
    }

    /** 只实现必需方法的落地端：用来验证 onPermissionRequested 的默认实现是安全的。 */
    private static final class BareListener implements AgentListener {
        @Override public void onTurnStarted(long t) { }
        @Override public void onUserMessage(long t, String x) { }
        @Override public void onAssistantToken(long t, String x) { }
        @Override public void onToolStarted(long t, String n, String i) { }
        @Override public void onToolFinished(long t, String n, String o, boolean ok) { }
        @Override public void onSubagentStarted(long t, String id, String a, String d) { }
        @Override public void onSubagentFinished(long t, String id, String f) { }
        @Override public void onTodoUpdated(long t, List<String> l) { }
        @Override public void onTurnComplete(long t) { }
        @Override public void onError(long t, Throwable e) { }
        @Override public void onQuestionAsked(long t, AskRequest r) { }
        @Override public void onCompactionStarted(String r) { }
        @Override public void onCompactionFinished(int a, int b) { }
        @Override public void onCompactionFailed(String m) { }
    }
}
