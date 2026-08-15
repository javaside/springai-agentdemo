# Token 缓存命中率统计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统计当前会话累计的 token 缓存命中率，在 `/context` 报告与状态栏两处展示，覆盖全部 6 家 provider（DeepSeek / OpenAI / Anthropic / Qwen / Zhipu / OpenCode Go）。

**Architecture:** 用 Spring AI 2.0 已标准化的 `Usage.getCacheReadInputTokens()`/`getCacheWriteInputTokens()`（DeepSeek 经 `getNativeUsage()` 兜底）在 `LlmProvider.chatModel()` 外包一层 `UsageRecordingChatModel`，把每个模型调用的 token 用量原子累加进一个线程安全的会话级 `TokenUsageAccumulator`；`CodingAgent.contextStats()` 把快照填进扩展后的 `ContextStats`，`ContextUsage` 渲染到 `/context` 与状态栏。

**Tech Stack:** Java 17、Spring AI 2.0.0、Reactor（`Flux`）、JUnit 5、Maven。

## Global Constraints

- 只改 `springai-code-tui` 模块；测试命令恒为 `mvn -B -pl springai-code-tui -am test`（单类加 `-Dtest=...`）。
- 接缝纪律：`ContextStats` / `ContextUsage` / `AgentListener` 不 import 任何 Spring AI 类型；`TokenUsageAccumulator.Snapshot` 是纯 Java record。
- 命中率口径 = `cacheReadTokens / promptTokens × 100`（`promptTokens` 为计费输入、已含缓存），`Math.round` 取整；分母为 0 返回 `null`。
- 流式只记一次：`.stream()` 只在 `doFinally` 提交一次（按最后 chunk 的累计 usage）。
- 抽取/累加全程 null-safe、失败静默降级，绝不抛。
- 需真实 API key 的冒烟测试用 `@EnabledIfEnvironmentVariable` 门控（本计划全部为离线单测，不需要 key）。

---

### Task 1: `CacheUsageExtractor`（纯函数拆缓存桶）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CacheUsageExtractor.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CacheUsageExtractorTest.java`

**Interfaces:**
- Produces: `record CacheTokens(long cacheRead, long cacheWrite)`；`static CacheTokens extract(org.springframework.ai.chat.metadata.Usage usage)`。

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=CacheUsageExtractorTest`
Expected: FAIL（`CacheUsageExtractor` 不存在，编译失败）。

- [ ] **Step 3: Write the implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=CacheUsageExtractorTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CacheUsageExtractor.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CacheUsageExtractorTest.java
git commit -m "feat: add cache usage extractor"
```

---

### Task 2: `TokenUsageAccumulator`（会话级累加器 + 快照）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/TokenUsageAccumulator.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/TokenUsageAccumulatorTest.java`

**Interfaces:**
- Consumes: `CacheUsageExtractor.CacheTokens` + `CacheUsageExtractor.extract(Usage)`（Task 1）。
- Produces: `record Snapshot(long promptTokens, long completionTokens, long cacheReadTokens, long cacheWriteTokens)`（含 `static Snapshot empty()`、`long billedInputTokens()`、`Integer cacheHitPercent()`）；`void record(Usage)`、`Snapshot snapshot()`、`void reset()`。

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=TokenUsageAccumulatorTest`
Expected: FAIL（`TokenUsageAccumulator` 不存在）。

- [ ] **Step 3: Write the implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=TokenUsageAccumulatorTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/TokenUsageAccumulator.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/TokenUsageAccumulatorTest.java
git commit -m "feat: add thread-safe token usage accumulator"
```

---

### Task 3: `UsageRecordingChatModel`（ChatModel 装饰器）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/UsageRecordingChatModel.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/UsageRecordingChatModelTest.java`

**Interfaces:**
- Consumes: `TokenUsageAccumulator.record(Usage)` / `snapshot()`（Task 2）。
- Produces: `class UsageRecordingChatModel implements ChatModel`（构造 `(ChatModel delegate, TokenUsageAccumulator accumulator)`）。

- [ ] **Step 1: Write the failing test**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageRecordingChatModelTest {

    private static ChatResponse response(int prompt, int completion, long cacheRead) {
        var usage = new DefaultUsage(prompt, completion, prompt + completion, null, cacheRead, 0L);
        var meta = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("x"))), meta);
    }

    /** 可控 delegate：call 返回 callResponse，stream 返回给定响应序列。 */
    private static ChatModel model(ChatResponse callResponse, List<ChatResponse> streamResponses) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return callResponse; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.fromIterable(streamResponses); }
        };
    }

    @Test
    void call_recordsOnce() {
        var acc = new TokenUsageAccumulator();
        var model = new UsageRecordingChatModel(model(response(100, 50, 80), List.of()), acc);

        model.call(new Prompt("hi"));

        assertEquals(100L, acc.snapshot().promptTokens());
        assertEquals(50L, acc.snapshot().completionTokens());
        assertEquals(80L, acc.snapshot().cacheReadTokens());
    }

    @Test
    void stream_recordsOnlyLastChunk() {
        var acc = new TokenUsageAccumulator();
        // 流式每个 chunk 都是累计 usage；最后一个才是完整值
        List<ChatResponse> chunks = List.of(
                response(100, 10, 10),
                response(100, 30, 40),
                response(100, 50, 80));
        var model = new UsageRecordingChatModel(model(null, chunks), acc);

        model.stream(new Prompt("hi")).blockLast();

        var s = acc.snapshot();
        assertEquals(100L, s.promptTokens(), "只记最后 chunk，不重复累加 prompt");
        assertEquals(50L, s.completionTokens(), "只记最后 chunk，不重复累加 completion");
        assertEquals(80L, s.cacheReadTokens());
    }

    @Test
    void stream_error_stillRecordsSeenUsage() {
        var acc = new TokenUsageAccumulator();
        Flux<ChatResponse> erroring = Flux.concat(
                Flux.just(response(100, 50, 80)),
                Flux.error(new RuntimeException("boom")));
        ChatModel delegate = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return erroring; }
        };
        var model = new UsageRecordingChatModel(delegate, acc);

        try {
            model.stream(new Prompt("hi")).blockLast();
        } catch (RuntimeException expected) {
            // doFinally 在 onError 后仍提交
        }

        assertEquals(80L, acc.snapshot().cacheReadTokens(), "报错也应提交已看到的 usage");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=UsageRecordingChatModelTest`
Expected: FAIL（`UsageRecordingChatModel` 不存在）。

- [ ] **Step 3: Write the implementation**

```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录 token 用量的 {@link ChatModel} 装饰器。包在每家 provider 的 chatModel 外，主 agent / 子 agent /
 * 摘要三条路径都经它，把每次模型调用的 usage 原子累加进共享的 {@link TokenUsageAccumulator}。
 *
 * <p><b>流式只记一次</b>：Spring AI 流式的每个 chunk 都带<b>累计</b> usage，最后一个 chunk 即完整值，
 * 故用 {@code doOnNext} 记最新、{@code doFinally} 提交一次（成功/报错/取消统一收口），杜绝按 chunk 重复计数。
 */
public final class UsageRecordingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageAccumulator accumulator;

    public UsageRecordingChatModel(ChatModel delegate, TokenUsageAccumulator accumulator) {
        this.delegate = delegate;
        this.accumulator = accumulator;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        record(usageOf(response));
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AtomicReference<Usage> last = new AtomicReference<>();
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (usage != null) {
                        last.set(usage);
                    }
                })
                .doFinally(signal -> record(last.get()));
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    private void record(Usage usage) {
        if (usage != null) {
            accumulator.record(usage);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=UsageRecordingChatModelTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/UsageRecordingChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/UsageRecordingChatModelTest.java
git commit -m "feat: add usage-recording chat model decorator"
```

---

### Task 4: `UsageRecordingProvider`（LlmProvider 装饰器）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/UsageRecordingProvider.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/UsageRecordingProviderTest.java`

**Interfaces:**
- Consumes: `UsageRecordingChatModel`（Task 3）、`LlmProvider`（既有）。
- Produces: `class UsageRecordingProvider implements LlmProvider`（构造 `(LlmProvider inner, TokenUsageAccumulator accumulator)`）。

- [ ] **Step 1: Write the failing test**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageRecordingProviderTest {

    private static final LlmProvider INNER = new LlmProvider() {
        @Override public String id() { return "test"; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return null; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
        }; }
        @Override public ChatOptions options(String modelId) { return null; }
        @Override public List<ModelOption> models() { return List.of(); }
        @Override public String defaultModel() { return "m"; }
    };

    @Test
    void chatModel_isWrappedOnceAndCached() {
        var acc = new TokenUsageAccumulator();
        var provider = new UsageRecordingProvider(INNER, acc);

        ChatModel first = provider.chatModel();
        ChatModel second = provider.chatModel();

        assertTrue(first instanceof UsageRecordingChatModel, "chatModel() 被包上 UsageRecordingChatModel");
        assertSame(first, second, "chatModel() 幂等：同一次包装缓存复用");
    }

    @Test
    void everythingElse_delegates() {
        var acc = new TokenUsageAccumulator();
        var provider = new UsageRecordingProvider(INNER, acc);
        assertSame(INNER.id(), provider.id());
        assertSame(INNER.available(), provider.available());
        assertSame(INNER.defaultModel(), provider.defaultModel());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=UsageRecordingProviderTest`
Expected: FAIL（`UsageRecordingProvider` 不存在）。

- [ ] **Step 3: Write the implementation**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * {@link LlmProvider} 装饰器：把 {@link #chatModel()} 包上 {@link UsageRecordingChatModel}，使主 agent /
 * 子 agent / 摘要三条路径（都经 {@code registry.*().chatModel()}）统一采集 token 用量。
 * 在 {@code ProviderRegistry} 构造前对每家 provider 各包一次。
 */
public final class UsageRecordingProvider implements LlmProvider {

    private final LlmProvider inner;
    private final TokenUsageAccumulator accumulator;
    private volatile ChatModel recorded;   // 幂等缓存（chatModel() 契约是单例）

    public UsageRecordingProvider(LlmProvider inner, TokenUsageAccumulator accumulator) {
        this.inner = inner;
        this.accumulator = accumulator;
    }

    @Override public String id() { return inner.id(); }
    @Override public boolean available() { return inner.available(); }

    @Override
    public ChatModel chatModel() {
        ChatModel m = recorded;
        if (m == null) {
            m = new UsageRecordingChatModel(inner.chatModel(), accumulator);
            recorded = m;
        }
        return m;
    }

    @Override public ChatOptions options(String modelId) { return inner.options(modelId); }
    @Override public ThinkingCapabilities thinkingCapabilities(String modelId) { return inner.thinkingCapabilities(modelId); }
    @Override public ChatOptions options(String modelId, ThinkingConfig config) { return inner.options(modelId, config); }
    @Override public List<ModelOption> models() { return inner.models(); }
    @Override public String defaultModel() { return inner.defaultModel(); }
    @Override public ModelCapabilities capabilities(String modelId) { return inner.capabilities(modelId); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=UsageRecordingProviderTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/UsageRecordingProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/UsageRecordingProviderTest.java
git commit -m "feat: add usage-recording provider decorator"
```

---

### Task 5: 扩展 `ContextStats`（+3 字段 + 12 参便捷构造）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ContextStats.java`

**Interfaces:**
- Produces: `ContextStats` 新增访问器 `long cacheReadTokens()`、`long billedInputTokens()`、`Integer cacheHitPercent()`；保留 12 参便捷构造（老调用点缓存字段默认 `0/0/null`）。

- [ ] **Step 1: 改 record 声明与便捷构造**

把 `ContextStats.java` 整体替换为（在既有 12 个字段后追加 3 个，并加一个 12 参便捷构造使老调用点不改也能编译）：

```java
package io.github.javaside.springai.codetui.agent;

/**
 * 会话上下文用量快照（{@code /context} 命令用）——<b>纯 Java</b>，不泄漏任何 Spring AI 类型，
 * 与 {@link AgentListener} 同属 CodingAgent→UI 的接缝纪律。
 *
 * <p>由 {@link SubmitHandler#contextStats()} 现算返回：读一遍当前会话事件与消息，统计条数与估算 token。
 * 全部为「只读快照」，读期间若有回合在写事件，拿到的是尽力而为的一致视图，不加锁。
 *
 * @param events          会话事件总数
 * @param userEvents      其中用户消息事件数
 * @param assistantEvents 其中助手消息事件数
 * @param toolEvents      其中工具消息事件数（tool_call/tool_result）
 * @param otherEvents     其余事件数（系统/摘要等）
 * @param estimatedTokens 全部消息文本的估算 token（同压缩用的 JTokkit 估算器）
 * @param tokenThreshold  自动压缩的累计 token 阈值（超过即对更早历史滚动摘要）
 * @param contextWindow   当前模型上下文窗口（token）
 * @param autoKeepEvents  自动压缩保留的最近事件数
 * @param manualKeepEvents 手动 {@code /compact} 保留的最近事件数（更激进）
 * @param visionImages    <b>本回合</b>累计兑现的图片张数
 * @param visionTokens    本回合累计兑现图片的估算视觉 token
 * @param cacheReadTokens 本会话累计的缓存读 token（provider 计费）
 * @param billedInputTokens 本会话累计的计费输入 token（= promptTokens，已含缓存）
 * @param cacheHitPercent 本会话缓存命中率（%），无计费输入时为 null
 */
public record ContextStats(int events,
                           int userEvents,
                           int assistantEvents,
                           int toolEvents,
                           int otherEvents,
                           long estimatedTokens,
                           long tokenThreshold,
                           long contextWindow,
                           int autoKeepEvents,
                           int manualKeepEvents,
                           int visionImages,
                           long visionTokens,
                           long cacheReadTokens,
                           long billedInputTokens,
                           Integer cacheHitPercent) {

    /** 12 参便捷构造：老调用点不填缓存字段（等价无缓存命中数据）。 */
    public ContextStats(int events, int userEvents, int assistantEvents, int toolEvents, int otherEvents,
                        long estimatedTokens, long tokenThreshold, long contextWindow,
                        int autoKeepEvents, int manualKeepEvents, int visionImages, long visionTokens) {
        this(events, userEvents, assistantEvents, toolEvents, otherEvents, estimatedTokens,
                tokenThreshold, contextWindow, autoKeepEvents, manualKeepEvents, visionImages, visionTokens,
                0L, 0L, null);
    }

    /** 空快照（会话尚无事件 / 回显桩用）。 */
    public static ContextStats empty() {
        return new ContextStats(0, 0, 0, 0, 0, 0L,
                0L, 0L, 0, 0, 0, 0L, 0L, 0L, null);
    }
}
```

- [ ] **Step 2: 编译并跑既有测试（回归）**

Run: `mvn -B -pl springai-code-tui -am test`
Expected: PASS——既有 `ContextStatsTest` / `ContextUsageTest` 的 12 参构造经便捷构造编译通过、语义不变。

- [ ] **Step 3: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ContextStats.java
git commit -m "feat: extend ContextStats with cache-hit fields"
```

---

### Task 6: `CodingAgent` 接累加器（字段 + 构造 + contextStats + clearContext）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`

**Interfaces:**
- Consumes: `TokenUsageAccumulator`（Task 2）、`ContextStats` 15 参构造（Task 5）。
- Produces: 全参构造新增末位参数 `TokenUsageAccumulator usageAccumulator`（可空）；旧全参构造保留（委托传 `null`）。

- [ ] **Step 1: 加字段**

在 `CodingAgent` 字段区（`private final Interjections interjections;` 之后，`private volatile String model` 之前）加一行：

```java
    /** 可空（测试桩）：会话级 token 用量累加器；contextStats() 读快照、clearContext() 清零。 */
    private final TokenUsageAccumulator usageAccumulator;
```

- [ ] **Step 2: 终态单-client 构造补一行**

只有<b>直接给字段赋值</b>的那个终态单-client 构造（以 `this.interjections = null;` 结尾、`registry = null` 那一个）需要补赋值——`usageAccumulator` 是 `final`，其余三个单-client 构造经 `this(...)` 委托到它、无需改动。在 `this.interjections = null;` 之后加：

```java
        this.usageAccumulator = null;      // 单-client 桩路径：无采集（缓存列恒 0/null）
```

（共 1 处；`grep "this.interjections = null" CodingAgent.java` 只有一处。）

- [ ] **Step 3: 把现全参构造改为委托、新增 21 参构造**

把现有 20 参全参构造（以 `Interjections interjections)` 结尾、含字段赋值主体的那个）的**主体**改为一行委托：

```java
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels, backgroundRegistry,
                backgroundResults, interjections, null);
```

然后在其后新增 21 参构造（参数尾部加 `TokenUsageAccumulator usageAccumulator`，主体即原来的赋值主体，另加一行赋值）：

```java
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry backgroundRegistry,
                       io.github.javaside.springai.codetui.agent.background.TaskResultStore backgroundResults,
                       Interjections interjections,
                       TokenUsageAccumulator usageAccumulator) {
        this.chatClient = null;
        this.registry = registry;
        this.clientsByProvider = clientsByProvider;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
        this.reloadableSkill = reloadableSkill;
        this.sessionRepository = sessionRepository;
        this.subagentRunner = subagentRunner;
        this.fileExternalizer = fileExternalizer;
        this.mcpRegistry = mcpRegistry;
        this.permissionEngine = permissionEngine;
        this.visionModels = visionModels;
        this.backgroundRegistry = backgroundRegistry;
        this.backgroundResults = backgroundResults;
        this.interjections = interjections;
        this.usageAccumulator = usageAccumulator;
        if (interjections != null) {
            interjections.onDelivered(text -> listener.onUserMessage(activeTurnId.get(), text));
        }
    }
```

（原有的「向后兼容重载」链不动的关键：它们仍委托到 20 参构造，而 20 参构造现在只多转一手 `null`。）

- [ ] **Step 4: `contextStats()` 填缓存字段**

把 `contextStats()` 里 `return new ContextStats(...)` 替换为（在取 `vision` 之后加一行快照、构造用 15 参）：

```java
        long tokens = SessionTokenEstimator.estimateMessages(sessionService.getMessages(sid), tokenCountEstimator);
        VisionSnapshot vision = snapshotOf(visionModels, registry);
        TokenUsageAccumulator.Snapshot usage = usageAccumulator == null
                ? TokenUsageAccumulator.Snapshot.empty()
                : usageAccumulator.snapshot();
        return new ContextStats(events.size(), user, assistant, tool, other, tokens,
                AgentTools.autoCompactionThreshold(registry), AgentTools.contextWindow(registry),
                AgentTools.MAX_EVENTS_TO_KEEP, AgentTools.MANUAL_MAX_EVENTS_TO_KEEP,
                vision.images(), vision.tokens(),
                usage.cacheReadTokens(), usage.billedInputTokens(), usage.cacheHitPercent());
```

- [ ] **Step 5: `clearContext()` 清零累加器**

在 `clearContext()` 方法体（`this.sessionId = SessionIds.newId();` 之后、`if (permissionEngine != null)` 之前）加：

```java
        if (usageAccumulator != null) {
            usageAccumulator.reset();   // /clear 换新会话，缓存命中率从 0 重新累计
        }
```

- [ ] **Step 6: 编译并跑既有测试**

Run: `mvn -B -pl springai-code-tui -am test`
Expected: PASS（单-client 测试桩走 `usageAccumulator == null` 分支，缓存列恒 0/null）。

- [ ] **Step 7: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java
git commit -m "feat: wire usage accumulator into CodingAgent"
```

---

### Task 7: `ContextUsage` 渲染命中率行与后缀

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ContextUsageTest.java`（扩展）

**Interfaces:**
- Consumes: `ContextStats.cacheReadTokens()/billedInputTokens()/cacheHitPercent()`（Task 5）。

- [ ] **Step 1: Write the failing test**

在 `ContextUsageTest` 末尾（`pct_roundsDownBelowHalf_notCeil` 之后、类结束括号之前）加三个测试：

```java
    private static ContextStats withCache(long cacheRead, long billedInput, Integer hitPercent) {
        return new ContextStats(100, 40, 50, 8, 2, 30_000L, 60_000L, 100_000L, 20, 10, 0, 0L,
                cacheRead, billedInput, hitPercent);
    }

    @Test
    void report_cacheHit_printsLineAfterTokenEstimate() {
        RecordingSink sink = new RecordingSink();
        ContextStats s = withCache(80L, 100L, 80);
        new ContextUsage(() -> s, sink).report();

        int tokenIdx = indexOfContaining(sink.lines, "估算 token");
        int cacheIdx = indexOfContaining(sink.lines, "缓存命中率");
        assertTrue(cacheIdx > tokenIdx, "命中行在估算 token 行之后");
        String line = sink.lines.get(cacheIdx);
        assertTrue(line.contains("缓存命中率：80%"), "命中率文案：" + line);
        assertTrue(line.contains("命中 80"), "命中数：" + line);
        assertTrue(line.contains("计费输入 100"), "计费输入数：" + line);
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return -1;
    }

    @Test
    void report_noCacheData_omitsCacheLine() {
        RecordingSink sink = new RecordingSink();
        new ContextUsage(ContextUsageTest::full, sink).report();   // full() 缓存字段为 0/0/null

        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("缓存命中率")), "无缓存数据不打印命中行");
    }

    @Test
    void suffix_cacheHit_appendsAfterContext() {
        ContextStats s = withCache(80L, 100L, 80);
        ContextUsage cu = new ContextUsage(() -> s, new RecordingSink());
        cu.refresh();
        assertEquals(" · 上下文 30% · 缓存命中 80%", cu.suffix());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=ContextUsageTest`
Expected: FAIL（`withCache` 的 15 参构造可编译，但 `report`/`suffix` 尚无缓存行/后缀，断言不通过）。

- [ ] **Step 3: 改 `report()` 追加缓存命中行**

在 `report()` 里「估算 token」行之后、视觉行之前（即 `if (s.contextWindow() > 0) {...} else {...}` 结束之后）加：

```java
        // 缓存命中率：有计费输入才打印。命中/计费输入用 %,d 原值（小会话不足千也不显示成 0）。
        if (s.cacheHitPercent() != null) {
            sink.accept(String.format("  缓存命中率：%d%%（命中 %,d / 计费输入 %,d token）",
                    s.cacheHitPercent(), s.cacheReadTokens(), s.billedInputTokens()));
        }
```

- [ ] **Step 4: 改 `suffix()` 追加缓存命中后缀**

把 `suffix()` 方法体替换为（窗口未知但缓存有数据时，仍可单独显示命中率）：

```java
    /** 状态栏上下文用量后缀（如 {@code " · 上下文 3% · 缓存命中 80%"}）；尚无对话时返回空串。 */
    String suffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0) return "";
        StringBuilder sb = new StringBuilder();
        if (s.contextWindow() > 0) {
            sb.append(" · 上下文 ").append(pct(s.estimatedTokens(), s.contextWindow()));
        }
        if (s.cacheHitPercent() != null) {
            sb.append(" · 缓存命中 ").append(s.cacheHitPercent()).append("%");
        }
        return sb.toString();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -B -pl springai-code-tui -am test -Dtest=ContextUsageTest`
Expected: PASS（新旧测试全绿——旧 `suffix_windowZero_isEmpty` 用 12 参构造、缓存为 null，后缀仍为空串）。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ContextUsageTest.java
git commit -m "feat: render cache hit rate in /context and status bar"
```

---

### Task 8: `CodeTuiApplication` 接线（包装 provider + 创建/传入累加器）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`

**Interfaces:**
- Consumes: `UsageRecordingProvider`（Task 4）、`TokenUsageAccumulator`（Task 2）、`CodingAgent` 21 参构造（Task 6）。

- [ ] **Step 1: 新增 3 参 `createProviderRegistry` + 包装助手，保留 2 参重载**

把 `createProviderRegistry(Path, Map)` 改为委托 3 参版本，并新增 3 参版本 + 私有 `wrap` 助手：

```java
    /** 按环境变量构建全部 provider 并加载工作区思考配置。供 main 与测试共用。 */
    static ProviderRegistry createProviderRegistry(Path root, java.util.Map<String, String> env) {
        return createProviderRegistry(root, env, new TokenUsageAccumulator());
    }

    /** 同 {@link #createProviderRegistry(Path, Map)}，额外注入会话级 token 累加器，并给每家 provider 包采集装饰器。 */
    static ProviderRegistry createProviderRegistry(Path root, java.util.Map<String, String> env,
                                                   TokenUsageAccumulator usageAccumulator) {
        return new ProviderRegistry(java.util.List.of(
                wrap(new DeepSeekProvider(env.get("DEEPSEEK_API_KEY"), env.get("DEEPSEEK_BASE_URL"), env.get("DEEPSEEK_MODELS")), usageAccumulator),
                wrap(new ZhipuProvider(env.get("ZHIPU_API_KEY"), env.get("ZHIPU_BASE_URL"), env.get("ZHIPU_MODELS")), usageAccumulator),
                wrap(new QwenProvider(env.get("DASHSCOPE_API_KEY"), env.get("DASHSCOPE_BASE_URL"), env.get("DASHSCOPE_MODELS")), usageAccumulator),
                wrap(new AnthropicProvider(env.get("ANTHROPIC_API_KEY"), env.get("ANTHROPIC_BASE_URL"), env.get("ANTHROPIC_MODELS")), usageAccumulator),
                wrap(new OpenAiProvider(env.get("OPENAI_API_KEY"), env.get("OPENAI_BASE_URL"), env.get("OPENAI_MODELS")), usageAccumulator),
                wrap(new OpencodeGoProvider(env.get("OPENCODE_GO_API_KEY"), env.get("OPENCODE_GO_BASE_URL"), env.get("OPENCODE_GO_MODELS")), usageAccumulator)),
                io.github.javaside.springai.codetui.agent.thinking.ThinkingConfigStore.load(root));
    }

    /** 给一家 provider 包上 token 采集装饰器。必须在 ProviderRegistry 构造前完成（registry 构造后不可变）。 */
    private static LlmProvider wrap(LlmProvider provider, TokenUsageAccumulator accumulator) {
        return new UsageRecordingProvider(provider, accumulator);
    }
```

（`TokenUsageAccumulator` / `LlmProvider` 已在 `io.github.javaside.springai.codetui.agent` 包，`CodeTuiApplication` 若未 import 则补：`import io.github.javaside.springai.codetui.agent.LlmProvider;` 与 `import io.github.javaside.springai.codetui.agent.TokenUsageAccumulator;`。）

- [ ] **Step 2: `main()` 创建累加器并传给 registry 与 CodingAgent**

在 `main()` 的 `ProviderRegistry registry;` 之前加：

```java
        TokenUsageAccumulator usageAccumulator = new TokenUsageAccumulator();   // 会话级 token 采集（主/子/摘要共享）
```

把 `registry = createProviderRegistry(root, System.getenv());` 改为：

```java
            registry = createProviderRegistry(root, System.getenv(), usageAccumulator);
```

把 `new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId, ...)` 的末参数 `runtime.interjections()` 之后追加 `, usageAccumulator`：

```java
            CodingAgent agent = new CodingAgent(registry, runtime.clients(), state, sessionId, activeTurnId,
                    runtime.sessionService(), runtime.manualStrategy(), runtime.tokenCountEstimator(),
                    runtime.skills(), runtime.skillTool(), runtime.sessionRepository(),
                    runtime.reloadableSkill(), runtime.subagentRunner(), runtime.fileExternalizer(),
                    mcpRegistry, runtime.permissionEngine(), runtime.visionModels(),
                    runtime.backgroundRegistry(), runtime.backgroundResults(), runtime.interjections(),
                    usageAccumulator);
```

（`main()` 若未 import `TokenUsageAccumulator`，补 `import io.github.javaside.springai.codetui.agent.TokenUsageAccumulator;`。）

- [ ] **Step 3: 全量编译 + 测试**

Run: `mvn -B -pl springai-code-tui -am test`
Expected: PASS（含 `CodeTuiApplicationThinkingConfigTest`——它走 2 参 `createProviderRegistry`，仍编译通过）。

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "feat: wire usage recording into provider registry and agent"
```

---

## Self-Review 记录

- **Spec 覆盖**：命中率口径（Task 2 `cacheHitPercent`）、全 6 家 provider + DeepSeek 兜底（Task 1/8）、会话累计（Task 2）、`/clear` 清零（Task 6）、两处展示（Task 7）、流式只记一次（Task 3）、失败静默降级（Task 1/3 的 null-safe）。全部有对应任务。
- **占位符**：无 TBD/TODO。
- **类型一致性**：`CacheTokens(cacheRead, cacheWrite)`、`Snapshot(promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens)`、`ContextStats` 15 参顺序（`..., visionImages, visionTokens, cacheReadTokens, billedInputTokens, cacheHitPercent`）在 Task 2/5/6/7 间一致；`CodingAgent` 全参构造末位 `usageAccumulator` 在 Task 6/8 间一致。
