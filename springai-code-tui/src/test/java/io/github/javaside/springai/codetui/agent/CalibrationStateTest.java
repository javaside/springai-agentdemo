package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationStateTest {

    private static final String KEY = "prov:model";

    @Test
    void initialIntervalIsConservativeAndUnproven() {
        CalibrationState state = new CalibrationState();
        CalibrationState.Interval interval = state.get(KEY);
        assertEquals(CalibrationState.SAFE_FALLBACK_BUDGET, interval.knownGood());
        assertFalse(interval.goodProven(), "初始 knownGood 是假设值,不是已验证值——数字解析的下限钳制不能拿它当证据");
        assertNull(interval.knownBad());
        assertEquals(1L, interval.provenFloor(), "未证明时下限钳制只到 1,不到假设的 32k");
    }

    @Test
    void updatesAreMonotonic() {
        CalibrationState state = new CalibrationState();
        state.recordGood(KEY, 40_000L);
        state.recordGood(KEY, 20_000L);   // 更小的成功值不回退
        state.recordBad(KEY, 100_000L);
        state.recordBad(KEY, 200_000L);   // 更大的失败值不回退
        CalibrationState.Interval interval = state.get(KEY);
        assertEquals(40_000L, interval.knownGood());
        assertTrue(interval.goodProven());
        assertEquals(100_000L, interval.knownBad());
        assertEquals(40_000L, interval.provenFloor());
    }

    @Test
    void goodAtOrAboveBadClearsBad() {
        // 曾观察到 60k 失败,后来 80k 成功(窗口变大/网关换了后端):以最新观察为准,knownBad 作废
        CalibrationState state = new CalibrationState();
        state.recordBad(KEY, 60_000L);
        state.recordGood(KEY, 80_000L);
        CalibrationState.Interval interval = state.get(KEY);
        assertEquals(80_000L, interval.knownGood());
        assertNull(interval.knownBad(), "成功量 ≥ 已知失败量:旧 knownBad 不再可信,须清空");
    }

    @Test
    void badAtOrBelowGoodShrinksGoodAndDropsProof() {
        // 曾验证 50k 成功,后来 30k 都失败(窗口变小):以最新失败为准收缩下界
        CalibrationState state = new CalibrationState();
        state.recordGood(KEY, 50_000L);
        state.recordBad(KEY, 30_000L);
        CalibrationState.Interval interval = state.get(KEY);
        assertEquals(30_000L, interval.knownBad());
        assertEquals(29_999L, interval.knownGood(), "读不到 knownGood ≥ knownBad 的撕裂区间");
        assertFalse(interval.goodProven(), "收缩出来的 knownGood 是推断值,不是验证值");
    }

    @Test
    void keysAreIndependentAndResetRestoresInitial() {
        CalibrationState state = new CalibrationState();
        state.recordGood(KEY, 90_000L);
        assertEquals(CalibrationState.Interval.INITIAL, state.get("other:model"));
        state.reset();
        assertEquals(CalibrationState.Interval.INITIAL, state.get(KEY));
    }

    @Test
    void concurrentUpdatesNeverProduceTornInterval() throws Exception {
        CalibrationState state = new CalibrationState();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    for (int i = 0; i < 1_000; i++) {
                        if ((i + seed) % 2 == 0) state.recordGood(KEY, 10_000L + (i * 37L) % 90_000L);
                        else state.recordBad(KEY, 20_000L + (i * 53L) % 90_000L);
                        CalibrationState.Interval seen = state.get(KEY);
                        if (seen.knownBad() != null && seen.knownGood() >= seen.knownBad()) {
                            throw new AssertionError("撕裂区间: " + seen);
                        }
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        CalibrationState.Interval last = state.get(KEY);
        assertTrue(last.knownBad() == null || last.knownGood() < last.knownBad());
    }
}
