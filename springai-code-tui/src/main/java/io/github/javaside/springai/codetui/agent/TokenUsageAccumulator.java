package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.metadata.Usage;

import java.util.concurrent.atomic.LongAdder;

/**
 * 会话级 token 用量累加器（线程安全）。主 agent / 子 agent / 摘要三条路径共用一个实例。
 *
 * <p>{@link #snapshot()} 返回的 {@link Snapshot} 是纯 Java record（不泄漏 Spring AI 类型），
 * 供 {@code CodingAgent.contextStats()} 拷贝进 {@link ContextStats}。{@code promptTokens} 即计费输入
 * （各 provider 的 prompt_tokens / input_tokens 均已含缓存）。
 */
public final class TokenUsageAccumulator {

    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder cacheReadTokens = new LongAdder();
    private final LongAdder cacheWriteTokens = new LongAdder();

    public void record(Usage usage) {
        if (usage == null) {
            return;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        CacheUsageExtractor.CacheTokens cache = CacheUsageExtractor.extract(usage);
        promptTokens.add(prompt == null ? 0L : prompt);
        completionTokens.add(completion == null ? 0L : completion);
        cacheReadTokens.add(cache.cacheRead());
        cacheWriteTokens.add(cache.cacheWrite());
    }

    public Snapshot snapshot() {
        return new Snapshot(promptTokens.sum(), completionTokens.sum(),
                cacheReadTokens.sum(), cacheWriteTokens.sum());
    }

    public void reset() {
        promptTokens.reset();
        completionTokens.reset();
        cacheReadTokens.reset();
        cacheWriteTokens.reset();
    }

    /** 不可变快照（纯 Java）。 */
    public record Snapshot(long promptTokens, long completionTokens,
                           long cacheReadTokens, long cacheWriteTokens) {

        public static Snapshot empty() {
            return new Snapshot(0L, 0L, 0L, 0L);
        }

        /** 计费输入 token（= promptTokens，已含缓存）。 */
        public long billedInputTokens() {
            return promptTokens;
        }

        /** 缓存命中率（%），分母为 0 返回 null。 */
        public Integer cacheHitPercent() {
            if (promptTokens == 0L) {
                return null;
            }
            return (int) Math.round(cacheReadTokens * 100.0 / promptTokens);
        }
    }
}
