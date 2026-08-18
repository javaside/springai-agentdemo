package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校准模式(新构造器)的主算法行为:乐观全量、区间学习、knownBad 短路、非超限兜底。 */
class BoundedSummarizationCalibrationTest {

    private static final String KEY = "prov:model";
    private static final long WINDOW = 200_000L;
    private static final long RESERVE = 12_000L;   // 4k prompt + 8k output
    private static final long TARGET = 40_000L;

    private static List<SessionEvent> events(int count, int sizeEach) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> SessionEvent.builder().sessionId("s")
                        .message(new UserMessage("x".repeat(sizeEach))).build())
                .toList();
    }

    private static CompactionRequest request(List<SessionEvent> events) {
        return CompactionRequest.of(Session.builder().id("s").userId("u").build(), events);
    }

    private static BoundedSummarizationCompactionStrategy strategy(
            CalibrationState calibration, Function<String, String> summarizer) {
        return new BoundedSummarizationCompactionStrategy(() -> TARGET, String::length, summarizer,
                () -> new BoundedSummarizationCompactionStrategy.ModelSnapshot(KEY, WINDOW, RESERVE),
                calibration);
    }

    /** 摘要产物文本(compacted[0] 是 "[Earlier conversation summary]" 标记,[1] 是摘要本体)。 */
    private static String summaryText(CompactionResult result) {
        return result.compactedEvents().get(1).getMessage().getText();
    }

    // 场景 1:窗口装得下 → 一次全量调用,knownGood 学到 E
    @Test
    void fullSummarizationSucceedsInSingleCall() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            return "summary";
        }).compact(request(events(12, 10_000)));

        assertEquals(1, inputs.size(), "窗口配置正确时必须一次全量,不许切块");
        assertEquals(inputs.get(0).length(), calibration.get(KEY).knownGood(),
                "成功后 knownGood 应更新为本次输入量 E");
        assertTrue(calibration.get(KEY).goodProven());
        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= TARGET);
    }

    // 场景 2:首次失败(超限、带数字)→ 按数字锚定预算切块,knownGood/knownBad 都学到
    @Test
    void overflowWithNumberFallsBackToAnchoredChunking() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            if (inputs.size() == 1) throw new RuntimeException(
                    "prompt is too long: 120000 tokens > 60000 maximum");
            return "summary";
        }).compact(request(events(12, 10_000)));

        long budget = 60_000L - RESERVE;   // 48_000:数字锚定的是窗口值 60000,不是请求量 120000
        assertEquals(4, inputs.size(), "1 次失败的全量 + 3 块切块");
        assertTrue(inputs.subList(1, 4).stream().allMatch(in -> in.length() <= budget),
                "每块输入不得超过数字推出的预算");
        assertEquals(inputs.get(0).length(), (long) calibration.get(KEY).knownBad(),
                "失败的全量输入量 E 应记为 knownBad");
        assertEquals(inputs.subList(1, 4).stream().mapToInt(String::length).max().orElseThrow(),
                calibration.get(KEY).knownGood(), "knownGood 按成功块的最大 estimate 更新");
        assertFalse(summaryText(result).contains("compacted locally"), "切块成功不该落本地兜底");
    }

    // 场景 3:首次失败(超限、无数字)→ 减半探测;探测结果只学容量、不复用进摘要
    @Test
    void overflowWithoutNumberProbesThenChunks() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            if (inputs.size() == 1) throw new RuntimeException("input too long");
            if (inputs.size() == 2) return "PROBE-ONLY";
            return "summary";
        }).compact(request(events(12, 10_000)));

        long e = inputs.get(0).length();
        assertEquals(4, inputs.size(), "全量失败 + 1 次探测 + 2 块切块");
        assertTrue(inputs.get(1).length() <= e / 2, "探测输入取减半预算");
        assertEquals(inputs.get(0).substring(0, inputs.get(1).length()), inputs.get(1),
                "探测输入必须是全文前缀,不是重新切块");
        assertFalse(summaryText(result).contains("PROBE-ONLY"), "探测结果只学容量,不得混进摘要");
        assertEquals(e / 2, calibration.get(KEY).knownGood(), "探测成功即证明该预算安全");
        assertEquals(e, (long) calibration.get(KEY).knownBad());
    }

    // 场景 4:knownBad 短路——已知会失败的量,绝不再发全量
    @Test
    void knownBadShortCircuitsStraightToChunking() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        calibration.recordBad(KEY, 50_000L);
        strategy(calibration, input -> {
            inputs.add(input);
            return "summary";
        }).compact(request(events(12, 10_000)));

        assertEquals(4, inputs.size(), "E ≥ knownBad:直接按安全预算切块(32k → 4 块)");
        assertTrue(inputs.stream().allMatch(in -> in.length() < 50_000),
                "任何一次请求都不得达到已知失败量");
    }

    // 场景 5:区间中间态(knownGood < E < knownBad=∞)→ 发全量;失败收紧 knownBad
    @Test
    void middleZoneAttemptsFullAndTightensKnownBadOnFailure() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        calibration.recordGood(KEY, 40_000L);
        strategy(calibration, input -> {
            inputs.add(input);
            if (inputs.size() == 1) throw new RuntimeException("input too long");
            return "summary";
        }).compact(request(events(12, 10_000)));

        assertTrue(inputs.get(0).length() > 40_000, "中间态必须先乐观发全量");
        assertEquals(inputs.get(0).length(), (long) calibration.get(KEY).knownBad(),
                "全量失败应收紧 knownBad 到 E");
    }

    // 场景 6:非超限异常(网络等)→ 不调预算、不学习,直接本地兜底
    @Test
    void nonOverflowFailureFallsBackLocallyWithoutCalibrating() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            throw new RuntimeException("connection reset by peer");
        }).compact(request(events(12, 10_000)));

        assertEquals(1, inputs.size(), "网络错误不该触发探测/切块的重试风暴");
        assertEquals(CalibrationState.Interval.INITIAL, calibration.get(KEY),
                "绝不拿网络错误调预算");
        assertTrue(summaryText(result).contains("compacted locally"));
        assertNull(result.compactedEvents().get(1).getMessage().getText().isEmpty() ? "x" : null,
                "兜底摘要不能为空");
        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= TARGET);
    }

    // 场景 7:安全阀——数字锚定出的预算跌破 16k 下限 → 不切块,直接本地兜底
    @Test
    void safetyValveBudgetBelowFloorFallsBackLocally() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            throw new RuntimeException("prompt is too long: 120000 tokens > 20000 maximum");
        }).compact(request(events(12, 10_000)));

        // 20000 - 12000(预留) = 8000 < 16000:切块只会制造一串必败请求
        assertEquals(1, inputs.size(), "预算跌破下限时不得再发任何切块请求");
        assertTrue(summaryText(result).contains("compacted locally"));
        assertEquals(inputs.get(0).length(), (long) calibration.get(KEY).knownBad(),
                "失败的全量输入量仍应记入 knownBad");
    }

    // 场景 8:安全阀——预算合法但块数超 8 上限 → 全量失败 1 次后直接兜底,无切块调用
    @Test
    void safetyValveChunkCountOverEightFallsBackLocally() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            throw new RuntimeException("prompt is too long: 160000 tokens > 30000 maximum");
        }).compact(request(events(16, 10_000)));

        // 30000 - 12000 = 18000 ≥ 16000 合法,但 archived≈150k / 18k → 9 块 > 8 上限
        assertEquals(1, inputs.size(), "块数超上限时不得发出任何切块请求");
        assertTrue(summaryText(result).contains("compacted locally"));
    }

    // 场景 9:全局调用硬上限 20——回显型摘要(每轮只缩 10%,永远压不到目标)必须在恰 20 次调用时被掐断
    @Test
    void globalCallBudgetCapsAtTwenty() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            if (inputs.isEmpty() || inputs.size() == 1) {
                throw new RuntimeException("prompt is too long: 160000 tokens > 32000 maximum");
            }
            // 恒收缩 10%:切块与再压缩循环永不收敛到 40k 目标,直到预算耗尽
            return input.substring(0, (int) (input.length() * 0.9));
        }).compact(request(events(15, 10_000)));

        // 32000 - 12000 = 20000,E≈150k → 8 块(恰在上限内);随后再压缩循环内耗尽:1+8+8+3 = 20
        assertEquals(20, inputs.size(), "全局调用上限必须恰好在第 20 次掐断(不多发一次,也不提前放弃)");
        assertTrue(summaryText(result).contains("compacted locally"), "预算耗尽必须落本地兜底,不得抛出");
    }

    // 场景 10:切块中途失败 → 整体兜底,但失败块的容量学习保留(防下次原样再撞)
    @Test
    void partialChunkFailureFallsBackButKeepsLearnedIntervals() {
        List<String> inputs = new ArrayList<>();
        CalibrationState calibration = new CalibrationState();
        CompactionResult result = strategy(calibration, input -> {
            inputs.add(input);
            if (inputs.size() == 1) {
                throw new RuntimeException("prompt is too long: 120000 tokens > 60000 maximum");
            }
            if (inputs.size() == 3) {
                throw new RuntimeException("maximum context length exceeded");   // 第 2 块超限
            }
            return "summary";
        }).compact(request(events(12, 10_000)));

        long budget = 60_000L - RESERVE;   // 48_000
        assertEquals(3, inputs.size(), "全量失败 + 第 1 块成功 + 第 2 块失败即止");
        assertEquals(budget, inputs.get(1).length(), "第 1 块按锚定预算切块");
        assertTrue(summaryText(result).contains("compacted locally"), "中途失败不复用部分摘要,整体兜底");

        // 同一量级(48k)先成功后失败 = 窗口中途收缩:以最新失败为准——knownBad=48k,
        // knownGood 收缩到 47_999 并撤销证明(诚实口径:不复用已失效的成功证据)
        assertEquals(budget, (long) calibration.get(KEY).knownBad(), "失败块的输入量应记为 knownBad");
        assertEquals(budget - 1, calibration.get(KEY).knownGood(), "knownGood 须收缩到 knownBad-1");
        assertFalse(calibration.get(KEY).goodProven(), "矛盾证据下必须撤销 goodProven");
    }

    // 场景 11:共享校准——A 策略学到的 knownBad,B 策略(另一实例)首次压缩直接切块、零试探
    @Test
    void sharedCalibrationGivesSecondStrategyZeroTrial() {
        CalibrationState calibration = new CalibrationState();
        List<String> firstInputs = new ArrayList<>();
        strategy(calibration, input -> {
            firstInputs.add(input);
            if (firstInputs.size() == 1) {
                throw new RuntimeException("prompt is too long: 120000 tokens > 60000 maximum");
            }
            return "summary";
        }).compact(request(events(12, 10_000)));
        long e = firstInputs.get(0).length();
        assertEquals(e, (long) calibration.get(KEY).knownBad(), "前置:A 应学到 knownBad=E");

        List<String> secondInputs = new ArrayList<>();
        strategy(calibration, input -> {
            secondInputs.add(input);
            return "summary";
        }).compact(request(events(12, 10_000)));

        assertTrue(secondInputs.get(0).length() < e,
                "B 的首次请求不得是注定失败的全量(knownBad 短路必须跨策略实例生效)");
        assertTrue(secondInputs.stream().allMatch(in -> in.length() < e),
                "B 的所有请求都必须低于已知失败量");
    }
}
