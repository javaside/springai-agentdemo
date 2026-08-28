package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.ModalRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.PermissionResponder;

class ConversationStateModalQueueTest {

    private static ConversationState started(long turnId) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(turnId);
        return s;
    }

    private static PermissionRequest perm(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Bash", "cmd", "{}", "why", null, sink::add);
    }

    @Test
    @DisplayName("FIFO：先进先出，peek 不出队")
    void fifoOrder() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        PermissionRequest a = perm(1L, sink);
        PermissionRequest b = perm(1L, sink);

        s.onPermissionRequested(1L, a);
        s.onPermissionRequested(1L, b);

        assertSame(a, s.peekModal());
        assertSame(a, s.peekModal(), "peek 不应出队");
        s.removeModal(a);
        assertSame(b, s.peekModal());
    }

    @Test
    @DisplayName("迟到请求（turnId 不匹配）不入队，直接 DENY —— 不是 CANCEL")
    void lateRequestIsDenied() {
        ConversationState s = started(5L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();

        s.onPermissionRequested(4L, perm(4L, sink));

        assertNull(s.peekModal(), "迟到请求不该进队列");
        assertEquals(List.of(PermissionOutcome.DENY), sink,
                "迟到应答必须是 DENY（回合继续），不是 CANCEL");
    }

    @Test
    @DisplayName("队列上限 8：第 9 个直接 DENY，防失控回合塞爆队列")
    void queueCapAtEight() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        for (int i = 0; i < ConversationState.MODAL_QUEUE_CAP; i++) {
            s.onPermissionRequested(1L, perm(1L, sink));
        }
        assertTrue(sink.isEmpty(), "前 8 个应全部入队、无人被应答");

        assertEquals(8, ConversationState.MODAL_QUEUE_CAP, "上限就是 8（改了这个数就得改本测试的语义）");
        s.onPermissionRequested(1L, perm(1L, sink));
        assertEquals(List.of(PermissionOutcome.DENY), sink, "第 9 个应被直接拒绝");
    }

    @Test
    @DisplayName("取消回合必须唤醒队列里每一个 pending 请求（漏了就是永久 park）")
    void cancelWakesEveryPendingRequest() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1L, perm(1L, sink));
        s.onPermissionRequested(1L, perm(1L, sink));

        List<String> askCancels = new ArrayList<>();
        s.onQuestionAsked(1L, new AskRequest(1L, List.of(), new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { askCancels.add("cancelled"); }
        }));

        s.cancelCurrent();

        assertEquals(List.of(PermissionOutcome.CANCEL, PermissionOutcome.CANCEL), sink,
                "两个审批请求都必须收到 CANCEL");
        assertEquals(List.of("cancelled"), askCancels, "问询也必须被取消");
        assertNull(s.peekModal(), "取消后队列应清空");
    }

    @Test
    @DisplayName("问询与审批共用同一个队列（不会互相覆盖）")
    void asksAndPermissionsShareQueue() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        AskRequest ask = new AskRequest(1L, List.of(), new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { }
        });
        PermissionRequest p = perm(1L, sink);

        s.onQuestionAsked(1L, ask);
        s.onPermissionRequested(1L, p);

        ModalRequest first = s.peekModal();
        assertSame(ask, first, "先到的问询先弹");
        s.removeModal(ask);
        assertSame(p, s.peekModal(), "问询处理完，审批接着弹（不会被覆盖）");
    }

    @Test
    @DisplayName("有 pending 模态时 isBusy()=true —— 否则排队消息会在审批期间被错误出队")
    void busyWhileModalPending() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1L, perm(1L, sink));
        s.onTurnComplete(1L);           // 即使回合被标记结束

        assertTrue(s.isBusy(), "还有待审批的模态就不算空闲");
        s.removeModal(s.peekModal());
        assertFalse(s.isBusy());
    }

    // ── 真实线程活性：上面的 sink 断言只能证明「respond 被调过」，证不了阻塞线程真的醒了 ──

    /**
     * 三个真阻塞的工具线程（含第 4 个溢出者），{@code cancelCurrent()} 后必须<b>全部</b>在超时内醒。
     * 用 {@code ArrayBlockingQueue(1)} 复刻 {@code PermissionCallback} 的握手（同 {@code UserQuestionBridge}）：
     * 队列漏一个就是永久 park，而 park 的线程持着回合 —— 整个 agent 静默挂死。
     * <b>必须带超时</b>：回归时要变红，不能挂死测试进程。
     */
    @Test
    @DisplayName("活性：真实阻塞的工具线程在取消后全部醒（含被溢出拒绝者）")
    void realThreadsAllWakeUp() throws Exception {
        ConversationState s = started(1L);
        int total = 4;                                  // 3 个入队 + 1 个溢出（cap 收窄测不到，故用小样本 + 手工溢出）
        CountDownLatch woke = new CountDownLatch(total);
        List<PermissionOutcome> got = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            ArrayBlockingQueue<PermissionOutcome> handoff = new ArrayBlockingQueue<>(1);
            AtomicReference<PermissionRequest> ref = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    got.add(handoff.take());            // 复刻工具线程：阻塞直到 UI 应答
                    woke.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "fake-tool-" + i);
            ref.set(new PermissionRequest(1L, null, "Bash", "cmd" + i, "{}", "why", null, handoff::offer));
            threads.add(t);
            t.start();
            s.onPermissionRequested(1L, ref.get());
        }

        s.cancelCurrent();

        assertTrue(woke.await(5, TimeUnit.SECONDS),
                "取消后仍有工具线程 park（醒了 " + (total - woke.getCount()) + "/" + total + "）");
        assertEquals(total, got.size());
        for (Thread t : threads) t.join(1000);
        assertNull(s.peekModal(), "取消后队列应清空");
    }

    /**
     * 溢出者（第 9 个）也必须真的醒 —— 它同样有个线程 park 在后面，
     * 静默丢弃就是永久挂死，且此时队列里前 8 个还占着位、UI 根本不会去管它。
     */
    @Test
    @DisplayName("活性：第 9 个（溢出）请求的线程也必须醒，收到 DENY")
    void overflowThreadWakesWithDeny() throws Exception {
        ConversationState s = started(1L);
        List<PermissionOutcome> filler = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) s.onPermissionRequested(1L, perm(1L, filler));

        ArrayBlockingQueue<PermissionOutcome> handoff = new ArrayBlockingQueue<>(1);
        AtomicReference<PermissionOutcome> seen = new AtomicReference<>();
        CountDownLatch woke = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                seen.set(handoff.take());
                woke.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "fake-tool-overflow");
        t.start();

        s.onPermissionRequested(1L, new PermissionRequest(1L, null, "Bash", "boom", "{}", "why", null, handoff::offer));

        assertTrue(woke.await(5, TimeUnit.SECONDS), "溢出请求的线程被静默丢弃 → 永久 park");
        assertEquals(PermissionOutcome.DENY, seen.get(), "溢出应是 DENY（回合继续），不是 CANCEL");
        t.join(1000);
    }

    /**
     * {@code resetForNewSession()}（{@code /clear}）也必须唤醒 —— 别把 park 的线程留给新会话。
     * 现实里 {@code /clear} 被 {@code isBusy()} 挡着走不到这儿，这条是纵深防御的回归钉。
     */
    @Test
    @DisplayName("活性：resetForNewSession 也唤醒全部 pending 模态")
    void resetWakesPendingModals() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1L, perm(1L, sink));
        s.onPermissionRequested(1L, perm(1L, sink));

        s.resetForNewSession();

        assertEquals(List.of(PermissionOutcome.CANCEL, PermissionOutcome.CANCEL), sink,
                "/clear 不得把 pending 模态留在队列里 park");
        assertNull(s.peekModal());
        // 注意：不断言 isBusy()==false —— resetForNewSession 刻意不动 status（回合状态归 cancelCurrent 管），
        // 这里只钉「模态被唤醒且清空」这一条活性契约。
    }

    /**
     * 队列 {@code [A, B]}，A 的 responder 违约抛异常：B <b>仍必须</b>被唤醒，
     * 且异常不得沿 {@code synchronized} 的 {@code cancelCurrent()} 传到 UI 线程的 Esc 处理器。
     * 一个坏元素不得殃及它后面的每一个——这正是本类要防的那种挂死。
     */
    @Test
    @DisplayName("排空循环容忍违约的 cancel()：坏元素不得殃及它后面的请求")
    void throwingCancelDoesNotStrandTheRest() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        PermissionRequest bad = new PermissionRequest(1L, null, "Bash", "boom", "{}", "why", null,
                o -> { throw new IllegalStateException("responder 违约抛异常"); });
        PermissionRequest good = perm(1L, sink);

        s.onPermissionRequested(1L, bad);
        s.onPermissionRequested(1L, good);

        assertDoesNotThrow(s::cancelCurrent, "异常不得传到 UI 线程的 Esc 处理器");

        assertEquals(List.of(PermissionOutcome.CANCEL), sink,
                "排在坏元素后面的请求必须照样被唤醒（漏了就是永久 park）");
        assertNull(s.peekModal(), "队列仍应被清空");
    }

    /**
     * {@code removeModal} 必须按<b>身份</b>移除，不能按 {@code equals}。
     * {@code PermissionRequest}/{@code AskRequest} 都是 record（逐分量 equals），
     * 若两个请求分量全同（现实里 responder 每次新建故不会，但这只是巧合），
     * {@code Deque.remove(Object)} 会摘掉<b>另一个</b>请求 —— 被误摘的那个线程永久 park。
     */
    @Test
    @DisplayName("removeModal 按身份而非 equals 移除（record 分量相同也不能误摘）")
    void removeModalIsIdentityBased() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        // 共用同一个 responder 实例 → 两个 record 分量完全相同 → a.equals(b) 为真
        io.github.javaside.springai.codetui.agent.seam.PermissionResponder shared = sink::add;
        PermissionRequest a = new PermissionRequest(1L, null, "Bash", "same", "{}", "why", null, shared);
        PermissionRequest b = new PermissionRequest(1L, null, "Bash", "same", "{}", "why", null, shared);
        assertEquals(a, b, "前置：两者 equals 相等（record 逐分量）");

        s.onPermissionRequested(1L, a);
        s.onPermissionRequested(1L, b);
        s.removeModal(b);                       // 只该摘掉 b

        assertSame(a, s.peekModal(), "按 equals 移除会摘掉队首的 a，留下 b —— a 的线程永久 park");
        s.removeModal(a);
        assertNull(s.peekModal());
    }
}
