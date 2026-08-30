package io.github.javaside.springai.codetui.agent.background;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackgroundTaskRegistry} 的变化通知契约（事件驱动 UI 的 Task 4）：
 *
 * <ul>
 *   <li>register → VIEW；</li>
 *   <li>complete 真实 RUNNING→DONE/FAILED → VIEW|CONTROL（删 drain 轮询后，后台结果的自动送达全靠它唤醒 UI）；</li>
 *   <li>kill / killAll / markConsumed 真实状态变化 → VIEW|CONTROL；</li>
 *   <li>容量淘汰已完成任务 → VIEW；</li>
 *   <li>unknown id、重复 complete / kill / consume → 不通知、不推进版本；</li>
 *   <li>通知在注册表监视器<b>外</b>；listener 异常隔离。</li>
 * </ul>
 */
class BackgroundTaskRegistryNotificationTest {

    private static final int VIEW = UiDirty.VIEW;
    private static final int VIEW_CONTROL = UiDirty.VIEW | UiDirty.CONTROL;

    private BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(64);
    }

    @Test
    @DisplayName("register → 一次 VIEW、版本 +1")
    void registerPublishesView() {
        BackgroundTaskRegistry r = registry();
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        String id = r.register("explore", "d");

        assertEquals(List.of(VIEW), bits);
        assertEquals(before + 1, r.uiVersion());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(id).status());
    }

    @Test
    @DisplayName("complete 真实 RUNNING→DONE → VIEW|CONTROL（必须唤醒自动送达）")
    void completePublishesViewControl() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        r.complete(id, "结论", true);

        assertEquals(List.of(VIEW_CONTROL), bits, "完成必须含 CONTROL，否则删 drain 后结果不会自动送达");
        assertEquals(before + 1, r.uiVersion());
    }

    @Test
    @DisplayName("complete 真实 RUNNING→FAILED → 同样 VIEW|CONTROL")
    void completeFailurePublishesViewControl() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("bash", "d");
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);

        r.complete(id, "连接被重置", false);

        assertEquals(List.of(VIEW_CONTROL), bits);
    }

    @Test
    @DisplayName("重复 complete / 未知 id complete：不通知、不推进版本")
    void duplicateOrUnknownCompleteStaysSilent() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "res", true);
        AtomicInteger calls = new AtomicInteger();
        r.setUiChangeListener(b -> calls.incrementAndGet());
        long before = r.uiVersion();

        r.complete(id, "again", true);          // 已 DONE：静默
        r.complete("task_nope", "x", true);     // 未知 id：静默

        assertEquals(0, calls.get());
        assertEquals(before, r.uiVersion());
        assertEquals(BackgroundTask.Status.DONE, r.find(id).status());
        assertNull(r.find("task_nope"));
    }

    @Test
    @DisplayName("kill 真实 RUNNING→KILLED → VIEW|CONTROL；重复 kill / 已完成 kill 静默")
    void killPublishesOnlyOnRealTransition() {
        BackgroundTaskRegistry r = registry();
        String running = r.register("bash", "a");
        String done = r.register("plan", "b");
        r.complete(done, "res", true);
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        assertTrue(r.kill(running));
        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, r.uiVersion());

        bits.clear();
        assertFalseSilent(() -> r.kill(running), "已 KILLED 再 kill 返回 false");
        assertFalseSilent(() -> r.kill(done), "已 DONE 杀不动");
        assertFalseSilent(() -> r.kill("task_nope"), "未知 id");
        assertEquals(List.of(), bits, "no-op kill 不得通知");
        assertEquals(before + 1, r.uiVersion());
    }

    private static void assertFalseSilent(java.util.function.BooleanSupplier op, String what) {
        assertEquals(false, op.getAsBoolean(), what);
    }

    @Test
    @DisplayName("killAll 有运行中任务 → 一次 VIEW|CONTROL；无运行中 → 静默")
    void killAllPublishesOnlyWhenSomethingWasRunning() {
        BackgroundTaskRegistry r = registry();
        String a = r.register("a", "1");
        r.register("b", "2");
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        r.killAll();
        assertEquals(List.of(VIEW_CONTROL), bits, "两个 RUNNING 合并成一次通知");
        assertEquals(before + 1, r.uiVersion());
        assertEquals(0, r.runningCount());

        bits.clear();
        r.killAll();                              // 全已 KILLED：no-op
        assertEquals(List.of(), bits);
        assertEquals(before + 1, r.uiVersion());
    }

    @Test
    @DisplayName("markConsumed 真实未消费→已消费 → VIEW|CONTROL；重复 / RUNNING 上消费静默")
    void markConsumedPublishesOnlyOnRealTransition() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "res", true);
        String running = r.register("bash", "r");
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        assertTrue(r.markConsumed(id));
        assertEquals(List.of(VIEW_CONTROL), bits);
        assertEquals(before + 1, r.uiVersion());

        bits.clear();
        assertEquals(false, r.markConsumed(id), "第二次消费：false");
        assertEquals(false, r.markConsumed(running), "RUNNING 上消费：false");
        assertEquals(false, r.markConsumed("task_nope"), "未知 id：false");
        assertEquals(List.of(), bits);
        assertEquals(before + 1, r.uiVersion());
    }

    @Test
    @DisplayName("容量淘汰已完成任务 → 一次 VIEW（与随后的登记合并成一次通知）")
    void evictionPublishesView() {
        BackgroundTaskRegistry r = new BackgroundTaskRegistry(3);
        String finished1 = r.register("a", "1");
        String running = r.register("b", "2");
        String finished2 = r.register("c", "3");
        r.complete(finished1, "r1", true);
        r.complete(finished2, "r2", true);
        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        long before = r.uiVersion();

        r.register("d", "4");                      // 触发淘汰 finished1 + 新登记（同一锁窗口）

        assertNull(r.find(finished1), "最旧已完成被淘汰");
        assertEquals(List.of(VIEW), bits,
                "淘汰 + 登记都是「面板列表形状变了」，合并成一次 VIEW（锁内生成、锁外发布）");
        assertEquals(before + 1, r.uiVersion());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(running).status());
    }

    // ── 锁外通知 / 异常隔离 ──

    @Test
    @DisplayName("通知发生在注册表监视器外：listener 内起线程读 synchronized 快照必须能完成")
    void notificationRunsOutsideRegistryMonitor() throws Exception {
        BackgroundTaskRegistry r = registry();
        CountDownLatch snapshotCompleted = new CountDownLatch(1);
        r.setUiChangeListener(bits -> {
            Thread reader = new Thread(() -> {
                r.all();                            // synchronized 读：monitor 被占就永远进不来
                snapshotCompleted.countDown();
            }, "registry-lock-probe");
            reader.setDaemon(true);
            reader.start();
            try {
                assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS),
                        "listener 在注册表监视器内执行——UI 回读面板会死锁");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        r.register("explore", "d");

        assertEquals(1, r.all().size());
    }

    @Test
    @DisplayName("listener 抛异常被隔离：版本照常推进，后续 mutation 照常通知")
    void throwingListenerIsIsolatedAndLaterMutationsStillPublish() {
        BackgroundTaskRegistry r = registry();
        r.setUiChangeListener(bits -> { throw new IllegalStateException("boom"); });

        assertDoesNotThrow(() -> r.register("a", "1"));
        assertEquals(1, r.uiVersion(), "listener 炸了也必须已记账");

        List<Integer> bits = new ArrayList<>();
        r.setUiChangeListener(bits::add);
        String id = r.all().get(0).taskId();
        assertDoesNotThrow(() -> r.complete(id, "res", true));
        assertEquals(2, r.uiVersion());
        assertEquals(List.of(VIEW_CONTROL), bits);
    }

    @Test
    @DisplayName("null listener 归一成 no-op：不抛异常")
    void nullListenerIsNormalizedToNoop() {
        BackgroundTaskRegistry r = registry();
        r.setUiChangeListener(null);
        assertDoesNotThrow(() -> r.register("a", "1"));
        assertEquals(1, r.uiVersion());
        assertEquals(1, r.all().size());
    }
}
