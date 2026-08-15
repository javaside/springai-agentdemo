package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.deepseek.api.DeepSeekApi;

/**
 * 从 Spring AI {@link Usage} 拆出 (cacheRead, cacheWrite) 两个桶（缺省为 0）。
 *
 * <p>OpenAI / Qwen / Zhipu / OpenCode Go（{@code OpenAiChatModel}）与 Anthropic 已把缓存字段填进
 * {@link Usage#getCacheReadInputTokens()} / {@link Usage#getCacheWriteInputTokens()}，直接读即可；
 * DeepSeek 的 {@code DeepSeekChatModel} 未填缓存字段，但原生 usage 里带着
 * {@code prompt_tokens_details.cached_tokens}，故经 {@link Usage#getNativeUsage()} 兜底。
 * 全程 null-safe，绝不抛。
 */
public final class CacheUsageExtractor {

    private CacheUsageExtractor() {
    }

    /** 一次模型调用的缓存读写 token。 */
    public record CacheTokens(long cacheRead, long cacheWrite) {
    }

    public static CacheTokens extract(Usage usage) {
        if (usage == null) {
            return new CacheTokens(0L, 0L);
        }
        Long read = usage.getCacheReadInputTokens();
        Long write = usage.getCacheWriteInputTokens();
        if (read != null || write != null) {
            return new CacheTokens(read == null ? 0L : read, write == null ? 0L : write);
        }
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof DeepSeekApi.Usage ds) {
            var details = ds.promptTokensDetails();
            Integer cached = details == null ? null : details.cachedTokens();
            return new CacheTokens(cached == null ? 0L : cached, 0L);
        }
        return new CacheTokens(0L, 0L);
    }
}
