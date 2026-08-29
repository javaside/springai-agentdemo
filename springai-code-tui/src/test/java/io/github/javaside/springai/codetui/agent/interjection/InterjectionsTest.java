package io.github.javaside.springai.codetui.agent.interjection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterjectionsTest {

    /** 兜底出队只能取未送达的：动了 delivered 就会和补历史抢，同一句话发两遍。 */
    @Test
    @DisplayName("takePendingOnly 取走未送达，且不动 delivered")
    void takePendingOnlyLeavesDelivered() {
        Interjections q = new Interjections();
        q.offer("第一句");
        q.drainForInjection("call-1");     // 第一句送达，进 delivered
        q.offer("第二句");                  // 未送达

        assertEquals(List.of("第二句"), q.takePendingOnly());

        // ⚠ takeForHistory() 会清空 delivered，只能调一次；用返回值做全部断言。
        Interjections.Delivered d = q.takeForHistory().orElse(null);
        assertNotNull(d, "delivered 被 takePendingOnly 顺手清掉了");
        assertEquals("第一句", d.text());
        assertEquals("call-1", d.anchorToolCallId());
    }

    /** Esc 要通吃：模型看过、历史没有、用户也拿不回来的话，那句话就凭空消失了。 */
    @Test
    @DisplayName("drainForRefill 同时交还已送达与未送达，已送达在前")
    void drainForRefillReturnsDeliveredToo() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");     // 送达
        q.offer("后说的");

        assertEquals(List.of("先说的", "后说的"), q.drainForRefill());
        assertTrue(q.takeForHistory().isEmpty(), "drainForRefill 之后 delivered 应已清空");
        assertEquals(0, q.pendingCount());
    }

    /** 回填给用户的是原话，不是 [interjection] 包裹后的文本。 */
    @Test
    @DisplayName("drainForRefill 交还的是原文")
    void refillIsRawText() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        q.drainForInjection("call-1");

        assertEquals(List.of("改用方案 B"), q.drainForRefill());
    }

    // ── 面板快照 ──

    /** 面板每帧都要读它，读完队列还得在——这是它与 takePendingOnly 的全部区别。 */
    @Test
    @DisplayName("pendingSnapshot 不消费队列，可反复读")
    void snapshotIsNonDestructive() {
        Interjections q = new Interjections();
        q.offer("第一句");
        q.offer("第二句");

        assertEquals(List.of("第一句", "第二句"), q.pendingSnapshot());
        assertEquals(List.of("第一句", "第二句"), q.pendingSnapshot(), "读一次就没了说明它在消费队列");
        assertEquals(2, q.pendingCount());
    }

    /**
     * 快照只列<b>未送达</b>的。已送达的那条此刻正躺在 scrollback 的信息流里
     * （送达回调打的那一行），面板再列一遍就是同一句话在屏幕上出现两次。
     */
    @Test
    @DisplayName("pendingSnapshot 不含已送达的")
    void snapshotExcludesDelivered() {
        Interjections q = new Interjections();
        q.offer("已送达的");
        q.drainForInjection("call-1");
        q.offer("还没送的");

        assertEquals(List.of("还没送的"), q.pendingSnapshot());
    }

    // ── 送达回调 ──

    /** 送达是 UI 唯一能知道「这句话进模型了」的时刻——不通知，界面就只能靠一个数字变化去猜。 */
    @Test
    @DisplayName("fireDelivered 把原文交给回调")
    void deliveryCallbackGetsRawText() {
        Interjections q = new Interjections();
        List<String> seen = new java.util.ArrayList<>();
        q.onDelivered(seen::add);

        q.fireDelivered("改用方案 B");

        assertEquals(List.of("改用方案 B"), seen);
    }

    /** 没接回调的落地端（测试桩、子 agent 那套）不能因此 NPE。 */
    @Test
    @DisplayName("未设回调 / 设 null 时 fireDelivered 不炸")
    void deliveryCallbackTolerAtesAbsence() {
        Interjections q = new Interjections();
        q.fireDelivered("没人听");
        q.onDelivered(null);
        q.fireDelivered("还是没人听");
    }
}
