package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextStatsTest {

    /**
     * 视觉 token 与文本估算是两笔账：图片从不进会话存储，故不该混进 estimatedTokens。
     * 合并会让「为什么请求比面板显示的大」和「压缩阈值为什么没触发」都变得无法解释。
     */
    @Test
    void visionTokensAreReportedSeparatelyFromTextEstimate() {
        ContextStats s = new ContextStats(10, 3, 4, 3, 0, 5_000L,
                100_000L, 128_000L, 120, 20, 2, 3_600L);
        assertEquals(5_000L, s.estimatedTokens());
        assertEquals(3_600L, s.visionTokens());
        assertEquals(2, s.visionImages());
    }

    @Test
    void emptySnapshotHasNoVisionUsage() {
        assertEquals(0, ContextStats.empty().visionImages());
        assertEquals(0L, ContextStats.empty().visionTokens());
    }
}
