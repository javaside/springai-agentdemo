package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionBudgetTest {

    @Test
    void userImagesAreCappedAtThree() {
        assertEquals(3, VisionBudget.MAX_USER_IMAGES);
        assertEquals(1, VisionBudget.MAX_TOOL_IMAGES);
    }

    @Test
    void tokenCapStopsAdmittingFurtherImages() {
        VisionBudget b = new VisionBudget();
        VisionBudget.Session s = b.open("turn-1");
        assertTrue(s.admit(5_000));
        // 超出上限的那张被拒，但不应把额度扣掉——否则一张大图会连带废掉后面所有小图
        assertFalse(s.admit(2_000));
        assertTrue(s.admit(500));
    }

    @Test
    void turnBudgetIsExhaustedAfterTwelveDeliveries() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot(), "第 " + (i + 1) + " 次");
        }
        assertFalse(b.open("turn-1").tryConsumeTurnSlot());
    }

    /** 不同回合互不影响——并发子 agent 共用同一个装饰器实例，不隔离会互相冲掉计数。 */
    @Test
    void turnsAreIsolatedFromEachOther() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) b.open("turn-1").tryConsumeTurnSlot();
        assertFalse(b.open("turn-1").tryConsumeTurnSlot());
        assertTrue(b.open("turn-2").tryConsumeTurnSlot());
    }

    /** 计数表必须有界，否则长会话里它自己会变成泄漏。 */
    @Test
    void counterTableIsBounded() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 50; i++) b.open("turn-" + i).tryConsumeTurnSlot();
        assertTrue(b.trackedTurns() <= VisionBudget.MAX_TRACKED_TURNS,
                "跟踪的回合数 " + b.trackedTurns() + " 超过上限 " + VisionBudget.MAX_TRACKED_TURNS);
    }
}
