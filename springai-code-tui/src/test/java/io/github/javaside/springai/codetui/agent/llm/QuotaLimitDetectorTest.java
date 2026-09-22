package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaLimitDetectorTest {

    // 1316 文档原文形态（反引号包裹北京时间）
    static final String M1316 = "429: 已达到 5 小时使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-22 15:30:00` 重置。";
    static final String M1317 = "429: 已达到 7 天使用上限。主账号余额不足，无法使用超额按量付费。"
            + "您的限额将在 `2026-09-23 08:00` 重置。";   // 无秒位

    @Test
    void detectQuotaCodeWithResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1316", M1316));
        assertTrue(q.isPresent());
        assertEquals("1316", q.get().code());
        // 北京时间 15:30 == UTC 07:30（解析按 Asia/Shanghai）
        assertEquals(Instant.parse("2026-09-22T07:30:00Z"), q.get().resetAt());
    }

    @Test
    void detectMinutePrecision() {
        assertEquals(Instant.parse("2026-09-23T00:00:00Z"),
                QuotaLimitDetector.detect(Quota429s.quota429("1317", M1317)).get().resetAt());
    }

    @Test
    void detectIsoOffset() {
        RateLimitException ex = Quota429s.quota429("1308",
                "429: 已达到 100 USD 5hour 使用上限，将在 2026-09-22T15:30:00+08:00 重置。");
        assertEquals(Instant.parse("2026-09-22T07:30:00Z"),
                QuotaLimitDetector.detect(ex).get().resetAt());
    }

    /**
     * 真机样本（2026-09-22 探针实测）：Coding Plan 专用端点
     * https://open.bigmodel.cn/api/coding/paas/v4 撞 5 小时限额的 429 body
     * {"error":{"code":"1308","message":"已达到 5 小时的使用上限。您的限额将在 2026-09-22 13:45:14 重置。"}}；
     * SDK 侧 getMessage() 即传入串（"429: " 前缀由 SDK 拼 statusCode 而来）。
     */
    @Test
    void realWorldSample1308CodingPlan5hLimit() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1308",
                "429: 已达到 5 小时的使用上限。您的限额将在 2026-09-22 13:45:14 重置。"));
        assertTrue(q.isPresent());
        assertEquals("1308", q.get().code());
        // 北京时间 13:45:14 == UTC 05:45:14（解析按 Asia/Shanghai）
        assertEquals(Instant.parse("2026-09-22T05:45:14Z"), q.get().resetAt());
    }

    /**
     * 真机反例（2026-09-22 探针实测）：同账号走按量付费端点 /api/paas/v4 的
     * 1113 欠费 body {"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}。
     * 1113 必须不被识别为限额（等待不自愈，spec §1.1）。
     */
    @Test
    void realWorldSample1113PayAsYouGoNotDetected() {
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1113",
                "429: 余额不足或无可用资源包,请充值。")).isEmpty());
    }

    @Test
    void quotaCodeWithoutTimeYieldsNullResetAt() {
        Optional<QuotaLimit> q = QuotaLimitDetector.detect(Quota429s.quota429("1310",
                "429: 已达到每周使用上限。"));
        assertTrue(q.isPresent());
        assertNull(q.get().resetAt());
    }

    @Test
    void keywordFallbackRequiresBothKeywords() {
        // code() 缺失（ErrorObject 不设 code）+ 双关键词 → 命中
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429(null, M1316)).isPresent());
        // 单关键词「使用上限」无「重置」→ 不命中
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429(null,
                "429: 已达到使用上限")).isEmpty());
    }

    @Test
    void plainRateLimit429NotDetected() {
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1302",
                "429: 您的访问频率过高")).isEmpty());
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1113",
                "429: 账户欠费")).isEmpty());
        assertTrue(QuotaLimitDetector.detect(Quota429s.quota429("1309",
                "429: 套餐已到期")).isEmpty());
    }

    @Test
    void webClient429NotDetected() {
        WebClientResponseException wcre =
                WebClientResponseException.create(429, "Too Many Requests", null, null, null);
        assertTrue(QuotaLimitDetector.detect(wcre).isEmpty());
    }

    @Test
    void traversesCauseChainAndSiiWrapper() {
        // Spring AI 包一层 RuntimeException 的真实传播形态
        assertTrue(QuotaLimitDetector.detect(new RuntimeException("wrap",
                Quota429s.quota429("1316", M1316))).isPresent());
        // SII 包装穿透：message 置空、cause 保留（L2 路径）
        assertTrue(QuotaLimitDetector.detect(new StreamInterruptedException(3,
                Quota429s.quota429("1316", M1316))).isPresent());
    }

    @Test
    void nonSdkThrowableNotDetected() {
        assertTrue(QuotaLimitDetector.detect(new RuntimeException("boom")).isEmpty());
    }

    @Test
    void parseResetAtNullSafe() {
        assertNull(QuotaLimitDetector.parseResetAt(null));
        assertNull(QuotaLimitDetector.parseResetAt("没有任何时间"));
    }

    @Test
    void pastTimestampStillParsed_fieldIsKeptDescriptive() {
        // detect 是纯描述：过去时刻不过滤（quotaWaitMs 兜底，spec §3.1）
        RateLimitException ex = Quota429s.quota429("1316", "429: 上限，将于 `2020-01-01 00:00:00` 重置。");
        assertEquals(Instant.parse("2019-12-31T16:00:00Z"),
                QuotaLimitDetector.detect(ex).get().resetAt());
    }
}
