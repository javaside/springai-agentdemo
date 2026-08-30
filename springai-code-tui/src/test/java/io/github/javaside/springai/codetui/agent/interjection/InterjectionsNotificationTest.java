package io.github.javaside.springai.codetui.agent.interjection;

import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Interjections} 的变化通知契约（事件驱动 UI 的 Task 4）：
 *
 * <ul>
 *   <li>offer 真实入队、drainForInjection 真实 pending→delivered、
 *       takePendingOnly / drainForRefill / takeForHistory 真实移除 → 各恰好一次 VIEW|CONTROL、版本恰好 +1；</li>
 *   <li>空操作（空白 offer、空队列 drain、空 delivered 取历史）不通知、不推进版本；</li>
 *   <li>通知发生在 Interjections 监视器<b>外</b>（UI 醒来要回读本队列的 synchronized 快照）；</li>
 *   <li>{@link Interjections#fireDelivered} 是业务回调、不改状态：<b>不</b>发 UI 通知
 *       （业务回调与 UI wake 不合并，见 fireDelivered 的锁外纪律）；</li>
 *   <li>listener 抛异常被隔离，状态照常推进、后续照常通知。</li>
 * </ul>
 */
class InterjectionsNotificationTest {

    private static final int VIEW_CONTROL = UiDirty.VIEW | UiDirty.CONTROL;

    // ── offer ──

    @Test
    @DisplayName("offer 真实入队：恰好一次 VIEW|CONTROL、版本 +1")
    void offerRealEnqueuePublishesViewControlOnce() {
        Interjections q = new Interjections();
        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        long before = q.uiVersion();

        q.offer("改用方案 B");

        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, q.uiVersion());
        assertEquals(1, q.pendingCount());
    }

    @Test
    @DisplayName("空白 / null offer 是 no-op：不通知、不推进版本")
    void blankOfferStaysSilent() {
        Interjections q = new Interjections();
        AtomicInteger calls = new AtomicInteger();
        q.setUiChangeListener(b -> calls.incrementAndGet());
        long before = q.uiVersion();

        q.offer("");
        q.offer("   ");
        q.offer(null);

        assertEquals(0, calls.get());
        assertEquals(before, q.uiVersion());
        assertEquals(0, q.pendingCount());
    }

    // ── drainForInjection ──

    @Test
    @DisplayName("drainForInjection 真实 pending→delivered：一次 VIEW|CONTROL；空队列再次 drain 静默")
    void drainForInjectionPublishesOnlyOnRealTransition() {
        Interjections q = new Interjections();
        q.offer("第一句");
        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        long before = q.uiVersion();

        assertTrue(q.drainForInjection("call-1").isPresent(), "前置：确实送达了一条");

        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, q.uiVersion());

        bits.clear();
        assertTrue(q.drainForInjection("call-2").isEmpty(), "队列已空");
        assertEquals(List.of(), bits, "空操作不得通知");
        assertEquals(before + 1, q.uiVersion(), "空操作不得推进版本");
    }

    // ── takePendingOnly / drainForRefill / takeForHistory ──

    @Test
    @DisplayName("takePendingOnly 真实移除：一次 VIEW|CONTROL；空队列 no-op")
    void takePendingOnlyPublishesOnlyOnRealRemoval() {
        Interjections q = new Interjections();
        q.offer("第一句");
        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        long before = q.uiVersion();

        assertEquals(List.of("第一句"), q.takePendingOnly());
        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, q.uiVersion());

        bits.clear();
        assertEquals(List.of(), q.takePendingOnly());
        assertEquals(List.of(), bits, "空队列取走是 no-op");
        assertEquals(before + 1, q.uiVersion());
    }

    @Test
    @DisplayName("drainForRefill 有内容：一次 VIEW|CONTROL；两边都空时 no-op")
    void drainForRefillPublishesOnlyWithContent() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");
        q.offer("后说的");
        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        long before = q.uiVersion();

        assertEquals(List.of("先说的", "后说的"), q.drainForRefill());
        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, q.uiVersion());

        bits.clear();
        assertEquals(List.of(), q.drainForRefill(), "已清空后再取：无内容");
        assertEquals(List.of(), bits);
        assertEquals(before + 1, q.uiVersion());
    }

    @Test
    @DisplayName("takeForHistory 真实取走：一次 VIEW|CONTROL；无 delivered 时 no-op")
    void takeForHistoryPublishesOnlyOnRealTake() {
        Interjections q = new Interjections();
        q.offer("要补历史的");
        q.drainForInjection("call-1");
        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        long before = q.uiVersion();

        assertTrue(q.takeForHistory().isPresent());
        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, q.uiVersion());

        bits.clear();
        assertTrue(q.takeForHistory().isEmpty());
        assertEquals(List.of(), bits);
        assertEquals(before + 1, q.uiVersion());
    }

    // ── 锁外通知 ──

    @Test
    @DisplayName("通知发生在 Interjections 监视器外：listener 内起线程读 synchronized 快照必须能完成")
    void notificationRunsOutsideInterjectionsMonitor() throws Exception {
        Interjections q = new Interjections();
        q.offer("seed");                      // 挂监听之前先备一条
        CountDownLatch snapshotCompleted = new CountDownLatch(1);
        q.setUiChangeListener(bits -> {
            Thread reader = new Thread(() -> {
                q.pendingSnapshot();          // synchronized 读：monitor 被占就永远进不来
                snapshotCompleted.countDown();
            }, "interjections-lock-probe");
            reader.setDaemon(true);
            reader.start();
            try {
                assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS),
                        "listener 在 Interjections 监视器内执行——UI 回读队列会死锁");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        q.offer("wake");

        assertEquals(2, q.pendingCount());
    }

    // ── 业务回调与 UI wake 不合并 ──

    @Test
    @DisplayName("fireDelivered 走业务回调、不发 UI 通知（两路不合并）")
    void fireDeliveredDoesNotPublishUiChange() {
        Interjections q = new Interjections();
        List<String> delivered = new ArrayList<>();
        List<Integer> uiBits = new ArrayList<>();
        q.onDelivered(delivered::add);
        q.setUiChangeListener(uiBits::add);

        q.fireDelivered("改用方案 B");

        assertEquals(List.of("改用方案 B"), delivered, "业务回调照常收到原文");
        assertTrue(uiBits.isEmpty(), "fireDelivered 本身不改状态，不得发 UI 通知");
    }

    // ── 异常隔离 / null 归一 ──

    @Test
    @DisplayName("listener 抛异常被隔离：版本照常推进，后续 mutation 照常通知")
    void throwingListenerIsIsolatedAndLaterMutationsStillPublish() {
        Interjections q = new Interjections();
        q.setUiChangeListener(bits -> { throw new IllegalStateException("boom"); });

        assertDoesNotThrow(() -> q.offer("第一句"));
        assertEquals(1, q.uiVersion(), "listener 炸了也必须已记账");

        List<Integer> bits = new ArrayList<>();
        q.setUiChangeListener(bits::add);
        assertDoesNotThrow(() -> q.offer("第二句"));
        assertEquals(2, q.uiVersion());
        assertEquals(List.of(VIEW_CONTROL), bits);
    }

    @Test
    @DisplayName("null listener 归一成 no-op：不抛异常、不通知")
    void nullListenerIsNormalizedToNoop() {
        Interjections q = new Interjections();
        q.setUiChangeListener(null);
        assertDoesNotThrow(() -> q.offer("still works"));
        assertEquals(1, q.uiVersion());
        assertEquals(1, q.pendingCount());
    }
}
