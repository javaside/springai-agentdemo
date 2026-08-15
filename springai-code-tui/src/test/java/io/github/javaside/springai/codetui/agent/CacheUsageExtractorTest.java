package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheUsageExtractorTest {

    @Test
    void nullUsage_isZero() {
        assertEquals(new CacheUsageExtractor.CacheTokens(0L, 0L), CacheUsageExtractor.extract(null));
    }

    @Test
    void emptyUsage_isZero() {
        assertEquals(new CacheUsageExtractor.CacheTokens(0L, 0L), CacheUsageExtractor.extract(new EmptyUsage()));
    }

    @Test
    void populatedCacheFields_areReadDirectly() {
        // OpenAI/Anthropic 已填 cacheRead/cacheWrite（6 参构造）
        var usage = new DefaultUsage(100, 50, 150, null, 80L, 20L);
        assertEquals(new CacheUsageExtractor.CacheTokens(80L, 20L), CacheUsageExtractor.extract(usage));
    }

    @Test
    void deepSeek_nativeUsageFallback_readsCachedTokens() {
        // DeepSeekChatModel 只给 4 参构造（cache 字段为 null），但 nativeUsage 里带着 cached_tokens
        var nativeUsage = new DeepSeekApi.Usage(100, 50, 150, new DeepSeekApi.Usage.PromptTokensDetails(80));
        var usage = new DefaultUsage(100, 50, 150, nativeUsage);
        assertEquals(new CacheUsageExtractor.CacheTokens(80L, 0L), CacheUsageExtractor.extract(usage));
    }

    @Test
    void nonDeepSeekNativeUsage_isZero() {
        var usage = new DefaultUsage(100, 50, 150, "not-a-deepseek-usage");
        assertEquals(new CacheUsageExtractor.CacheTokens(0L, 0L), CacheUsageExtractor.extract(usage));
    }
}
