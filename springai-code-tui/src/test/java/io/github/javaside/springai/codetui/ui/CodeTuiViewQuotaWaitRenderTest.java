package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 限额倒计时渲染（spec §3.5）：RETRYING 态每帧从 deadline 现算剩余时间（无 ticker）。 */
class CodeTuiViewQuotaWaitRenderTest {

    @Test
    void quotaDeadlineRendersCountdownInsteadOfStaticBackoff() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        long now = System.currentTimeMillis();
        // deadline 存在：现算剩余（2h13m 前推的 deadline，now 对齐）
        s.onQuotaWaitScheduled(1L, now + 2 * 3600_000L + 13 * 60_000L, "429: 上限");
        // 1s 后剩 2h12m59s，formatQuotaRemaining 按整分截断 → "2h12m"
        assertEquals("2h12m", CodeTuiView.quotaBackoffText(s, now + 1000));
        // deadline 为 null：回落静态 retryBackoffText（普通重试路径不受影响）
        s.onRetryScheduled(1L, 2, 7, 30_000, "传输");
        assertEquals("30.0s", CodeTuiView.quotaBackoffText(s, now + 1000));
    }
}
