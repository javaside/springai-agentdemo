package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenUsageAccumulatorTest {

    private static DefaultUsage usage(int prompt, int completion, long cacheRead) {
        return new DefaultUsage(prompt, completion, prompt + completion, null, cacheRead, 0L);
    }

    @Test
    void record_accumulatesAcrossCalls() {
        var acc = new TokenUsageAccumulator();
        acc.record(usage(100, 50, 80));
        acc.record(usage(200, 20, 10));
        var s = acc.snapshot();
        assertEquals(300L, s.promptTokens());
        assertEquals(70L, s.completionTokens());
        assertEquals(90L, s.cacheReadTokens());
        assertEquals(0L, s.cacheWriteTokens());
    }

    @Test
    void record_nullIsNoop() {
        var acc = new TokenUsageAccumulator();
        acc.record(null);
        assertEquals(TokenUsageAccumulator.Snapshot.empty(), acc.snapshot());
    }

    @Test
    void cacheHitPercent_roundsAndNullsOnZeroDenominator() {
        var acc = new TokenUsageAccumulator();
        assertNull(acc.snapshot().cacheHitPercent(), "无计费输入 → null");

        acc.record(usage(100, 50, 80));   // 80/100 = 80%
        assertEquals(80, acc.snapshot().cacheHitPercent());

        acc.reset();
        acc.record(usage(100, 50, 33));   // 33% 精确
        assertEquals(33, acc.snapshot().cacheHitPercent());

        acc.reset();
        acc.record(usage(200, 50, 99));   // 49.5% → Math.round → 50%
        assertEquals(50, acc.snapshot().cacheHitPercent());
    }

    @Test
    void reset_zeroesAllBuckets() {
        var acc = new TokenUsageAccumulator();
        acc.record(usage(100, 50, 80));
        acc.reset();
        assertEquals(TokenUsageAccumulator.Snapshot.empty(), acc.snapshot());
    }

    @Test
    void snapshot_isImmutableAndBilledInputEqualsPrompt() {
        var acc = new TokenUsageAccumulator();
        acc.record(usage(100, 50, 80));
        var s = acc.snapshot();
        assertEquals(100L, s.billedInputTokens(), "billedInput = promptTokens（已含缓存）");
    }

    @Test
    void record_isThreadSafe() throws Exception {
        var acc = new TokenUsageAccumulator();
        int threads = 8;
        int perThread = 10_000;
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> { for (int j = 0; j < perThread; j++) acc.record(usage(10, 5, 4)); return null; });
            }
            pool.invokeAll(tasks);
        } finally {
            pool.shutdown();
        }
        var s = acc.snapshot();
        long total = (long) threads * perThread;
        assertEquals(total * 10L, s.promptTokens());
        assertEquals(total * 5L, s.completionTokens());
        assertEquals(total * 4L, s.cacheReadTokens());
    }
}
