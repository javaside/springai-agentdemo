package io.github.javaside.springai.codetui.agent;

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
}
