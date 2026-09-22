package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 限额等待态（spec §3.5）：进入/清除纪律 + -1 丢弃 + 剩余时间格式化。进入接受态用既有 API onTurnStarted(long)。 */
class ConversationStateQuotaWaitTest {

    @Test
    void quotaWaitEntersRetryingWithDeadline() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        long reset = System.currentTimeMillis() + 90_000;
        s.onQuotaWaitScheduled(1L, reset, "429: 已达到 5 小时使用上限");
        assertEquals(ConversationState.Status.RETRYING, s.status());
        assertEquals("⏳ 限额等待", s.retryLabel());
        assertEquals(reset, s.quotaWaitDeadline());
        assertNotNull(s.quotaWaitReason());
        assertTrue(s.quotaWaitReason().contains("使用上限"));
    }

    @Test
    void staleTurnIdDropped() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(999, System.currentTimeMillis() + 90_000, "r");
        assertNull(s.quotaWaitDeadline());
    }

    @Test
    void backgroundMinusOneDropped() {
        // 后台子 agent turnId=-1：空闲态 acceptingTurnId==-1 会穿透过滤（spec §3.4.4）——必须丢弃
        ConversationState s = new ConversationState();
        s.onQuotaWaitScheduled(-1, System.currentTimeMillis() + 90_000, "r");
        assertNull(s.quotaWaitDeadline());
    }

    @Test
    void leavingEventsClearDeadline() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(1L, System.currentTimeMillis() + 90_000, "r");
        assertNotNull(s.quotaWaitDeadline());
        s.onTurnComplete(1L);                       // 任一离开事件
        assertNull(s.quotaWaitDeadline());          // clearRetryState 并入清除
        assertNull(s.retryLabel());
    }

    @Test
    void retryScheduledAlsoClearsDeadline() {
        // spec §3.5 清除纪律：onRetryScheduled 本身不清 retryLabel（它设置新值），必须显式清 deadline——
        // 否则限额等待→到点→普通瞬态失败时，状态栏用过期 deadline 现算「即将重试」盖掉新退避
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onQuotaWaitScheduled(1L, System.currentTimeMillis() + 90_000, "r");
        s.onRetryScheduled(1L, 2, 7, 1000, "传输");
        assertNull(s.quotaWaitDeadline());
        assertEquals("↻ 重试中 2/7·传输", s.retryLabel());   // 普通重试态正常进入（现行 retryLabel 带 tag 后缀）
    }

    @Test
    void formatQuotaRemainingMatrix() {
        assertEquals("即将重试", ConversationState.formatQuotaRemaining(-1));
        assertEquals("45s", ConversationState.formatQuotaRemaining(45_000));
        assertEquals("2m30s", ConversationState.formatQuotaRemaining(150_000));
        assertEquals("3h5m", ConversationState.formatQuotaRemaining(3 * 3600_000L + 5 * 60_000L));
        assertEquals("6d23h", ConversationState.formatQuotaRemaining(6L * 24 * 3600_000 + 23 * 3600_000L));
    }
}
