# 上下文压缩「乐观全量摘要」实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `BoundedSummarizationCompactionStrategy` 的悲观预分片(~8 次串行大请求)替换为「乐观全量 + 区间学习」:窗口配置正确时 1 次调用完成压缩,配置虚高时靠真实失败快速校准,全场景 ≤ 20 次调用硬上限。

**Architecture:** 新增 `CalibrationState`(按 provider:model 记 knownGood/knownBad 区间,进程内共享)与 `SummarizerOverflow`(超限分类 + 窗口数字锚定解析)两个小类;策略新增「校准构造器」走新算法,旧构造器保留为无校准降级模式(现有测试不动);`DynamicAuxChatModel` 改一次快照 + maxTokens 合并;`AgentTools` 装配共享校准状态并给摘要路径显式 `maxTokens=8192`。

**Tech Stack:** Java 21+ / Spring AI 2.0.0(正式版,已实测 classpath)/ JUnit 6 / Maven 多模块。

**设计来源:** `docs/superpowers/specs/2026-08-17-compaction-optimistic-full-summarization-design.md`(v2,已吸收评审)。

---

## 前置须知(执行者必读)

### A. 基线用独立 worktree,不在共用树上干活

共用树里有别人在途的未跟踪文件 `springai-code-tui/src/main/java/.../agent/UsageDomain.java`(引用尚不存在的 `TokenUsageAccumulator.Domain`,**当前打挂整仓编译**)。这不是你的任务范围,绝不删改。从 main HEAD 开 worktree,未跟踪文件不会带过去,基线干净。全程只追加提交,**禁止 amend / stash**。

### B. 验证命令必须模块作用域

整仓 `mvn test -Dtest=...` 会被空模块打挂。先装父 POM 与兄弟模块(Task 1),之后一律:

```bash
mvn -pl springai-code-tui test -Dtest=<TestClass>
```

不要用 `-DfailIfNoSpecifiedTests=false` 掩盖问题。

### C. Spring AI 2.0 API 实测结论(写代码前别再猜)

已用编译探针对着本项目解析出的 classpath(`spring-ai-model-2.0.0.jar` 等正式版)核实:

- `ChatOptions` **没有** `copy()`;复制修改走 `mutate()`,各家 native options 的 `mutate()` 协变返回自家 Builder(状态全保留),`maxTokens(Integer)` 是通用 `ChatOptions$Builder` 方法。
- native options **没有** `setMaxTokens` setter(不可变 + Builder 风格)。
- `ChatClient` 请求级 options 收的是 **Builder**:`client.prompt().options(ChatOptions.builder().maxTokens(8192))`,不是 build 后的成品。
- `Prompt.getOptions()` 存在,可为 null。

### D. 与设计文档的两处显式偏差(均为落实设计自身的硬要求)

1. **构造器形状**:设计写的 7 参构造器里 `Supplier<RequestSelection> selection` 与 `LongSupplier configuredWindow` 是两个独立 supplier——两次独立 `.get()` 恰恰违反同一设计的「校准 key 与窗口预算必须出自同一次快照」。故融合为单一 `Supplier<ModelSnapshot>`(record:`calibrationKey + fullInputBudget`),在装配处由**一次** `activeRequestSelection()` 派生两个字段。同时策略不再需要 `chunkTokens` 参数(新算法的切块预算全部由校准/探测决定、上限即 fullInputBudget,与设计伪代码一致)。
2. **`MAX_SUMMARY_CHUNK_TOKENS`(64k)直接删除**:设计说其语义改为「未知窗口的保守兜底」,但该角色已由 `SAFE_FALLBACK_BUDGET=32k`(knownGood 初始值)+ 未知模型 128k 保守窗口覆盖,留一个无引用常量只会误导。

### E. 补齐设计未明说的三个语义(实现即按此,测试也按此断言)

1. **切块中途超限也学习**:某块超限 → `recordBad(该块 estimate)` 后整体落 localDigest(设计测试 9 只说了 knownGood 按成功块更新;不记 knownBad 则下次原样再撞)。网络类错误不学习、直接兜底。
2. **区间矛盾消解**:`recordBad(v)` 遇 `v ≤ knownGood`(模型窗口中途变小)时,以最新失败为准收缩 `knownGood = v − 1`,保住「读不到 knownGood ≥ knownBad」不变量;其余情况维持单调。
3. **E > fullBudget**(归档比配置窗口还大):跳过全量,直接按 `budget = fullInputBudget` 切块(配置诚实则必成;虚高则首块超限 → 学到 knownBad → 本轮 localDigest、下轮走短路)。

---

### Task 1: 工作树与干净基线

**Files:** 无代码改动;只建环境。

- [ ] **Step 1: 从 main 开独立 worktree**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git worktree add .claude/worktrees/compaction-optimistic -b feat/compaction-optimistic main
cd .claude/worktrees/compaction-optimistic
```

预期:worktree 内**没有** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/UsageDomain.java`(它是共用树里别人的在途未跟踪文件)。用 `ls` 确认不存在。

- [ ] **Step 2: 安装父 POM 与兄弟模块(一次性,解掉模块内构建的依赖)**

```bash
mvn -q -N install
mvn -q -pl springai-tamboui-inline-patch install -DskipTests
```

预期:两条均 BUILD SUCCESS(静默模式下无 ERROR 输出即成功)。

- [ ] **Step 3: 跑基线测试确认绿**

```bash
mvn -pl springai-code-tui test -Dtest='ContextStatsTest,AgentToolsCompactionWiringTest,AuxClientNotVisionWrappedTest'
```

预期:全 PASS。这三个类此后是「不许变红」的守卫基线。

---

### Task 2: CalibrationState(区间存储 + 原子更新)

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CalibrationState.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CalibrationStateTest.java`

- [ ] **Step 1: 写失败测试**

```java
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
```

- [ ] **Step 2: 跑测试确认编译失败(类不存在)**

```bash
mvn -pl springai-code-tui test -Dtest=CalibrationStateTest
```

预期:COMPILATION ERROR,找不到 CalibrationState。

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 {@code provider:model} 记录「摘要输入量」的已验证区间,供压缩策略跨次学习模型的真实容量。
 * 进程内共享(auto/manual 两条策略同用一个实例——校准是模型的属性,不是策略实例的属性),不持久化。
 *
 * <p>记录口径:值是<b>用户文本估计量</b>(estimate(text) 或切块 budget),不含 system prompt 与消息封装
 * ——与策略比对时的 E 同口径,自洽。
 */
final class CalibrationState {

    /** 保守起点:任何现代模型窗口都装得下,且高于策略的安全阀下限 16k。<b>假设值</b>,非验证值。 */
    static final long SAFE_FALLBACK_BUDGET = 32_000L;

    /**
     * 不可变区间。{@code knownGood}=已成功的最大输入量;{@code goodProven}=knownGood 是否经过真实成功验证
     * (初始 32k 是假设,数字解析的下限钳制不能拿假设当证据);{@code knownBad}=已失败的最小输入量(null=+∞)。
     */
    record Interval(long knownGood, boolean goodProven, Long knownBad) {
        static final Interval INITIAL = new Interval(SAFE_FALLBACK_BUDGET, false, null);

        /** 数字解析/探测减半的下限:只钳到「已证明」的安全水平;未证明时到 1(即不钳)。 */
        long provenFloor() { return goodProven ? knownGood : 1L; }
    }

    private final ConcurrentHashMap<String, Interval> byModel = new ConcurrentHashMap<>();

    Interval get(String key) {
        return byModel.getOrDefault(key, Interval.INITIAL);
    }

    /** 单调:knownGood 只涨。成功量 ≥ knownBad 说明旧失败观察过期(窗口变大),knownBad 清空。 */
    void recordGood(String key, long value) {
        byModel.compute(key, (k, old) -> {
            Interval base = old == null ? Interval.INITIAL : old;
            long good = Math.max(base.knownGood(), value);
            Long bad = base.knownBad() != null && base.knownBad() <= good ? null : base.knownBad();
            return new Interval(good, true, bad);
        });
    }

    /** 单调:knownBad 只降。失败量 ≤ knownGood 说明窗口中途变小,以最新失败为准收缩下界并撤销证明。 */
    void recordBad(String key, long value) {
        byModel.compute(key, (k, old) -> {
            Interval base = old == null ? Interval.INITIAL : old;
            long bad = base.knownBad() == null ? value : Math.min(base.knownBad(), value);
            if (base.knownGood() >= bad) {
                return new Interval(Math.max(1L, bad - 1), false, bad);
            }
            return new Interval(base.knownGood(), base.goodProven(), bad);
        });
    }

    /** 测试隔离用。 */
    void reset() {
        byModel.clear();
    }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -pl springai-code-tui test -Dtest=CalibrationStateTest
```

预期:6 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CalibrationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CalibrationStateTest.java
git commit -m "feat(tui): 压缩校准区间存储 CalibrationState"
```
### Task 3: SummarizerOverflow(超限分类 + 窗口数字锚定解析)

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SummarizerOverflow.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SummarizerOverflowTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizerOverflowTest {

    // ---- 分类:是不是「超限」 ----

    @Test
    void recognizesCommonOverflowVariantsCaseInsensitively() {
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "400 - {\"error\":{\"code\":\"context_length_exceeded\"}}")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "Prompt is too long: 195300 tokens > 190000 maximum")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "This model's MAXIMUM CONTEXT LENGTH is 65536 tokens.")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException("input too long for model")));
    }

    @Test
    void walksCauseChainForWrappedServerErrors() {
        // Spring AI 常把服务端 4xx 包在通用 RuntimeException 里
        RuntimeException wrapped = new RuntimeException("call failed",
                new IllegalStateException(new RuntimeException("maximum context length is 163857 tokens")));
        assertTrue(SummarizerOverflow.isOverflow(wrapped));
    }

    @Test
    void networkAndAuthErrorsAreNotOverflow() {
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException("connection reset by peer")));
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException("401 invalid api key")));
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException((String) null)));
        assertFalse(SummarizerOverflow.isOverflow(cyclicCause()));
    }

    private static RuntimeException cyclicCause() {
        RuntimeException a = new RuntimeException("network glitch");
        RuntimeException b = new RuntimeException("retry", a);
        a.initCause(b);   // 环:遍历必须有深度上限,不能死循环
        return a;
    }

    // ---- 数字解析:锚定窗口值,不是「找第一个数字」 ----

    @Test
    void anchorsOpenAiStyleAfterMaximumContextLength() {
        assertEquals(163_857L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "This model's maximum context length is 163857 tokens. However, you requested 250000 tokens.")));
    }

    @Test
    void anchorsAnthropicStyleBetweenGtAndMaximum() {
        // 两个数字:195300 是请求量,200000 才是窗口——必须取后者
        assertEquals(200_000L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "prompt is too long: 195300 tokens > 200000 maximum")));
    }

    @Test
    void anchorsChineseVariants() {
        assertEquals(131_072L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "输入过长,该模型最大上下文长度为 131072 tokens")));
    }

    @Test
    void parsesNumbersWithThousandsSeparators() {
        assertEquals(163_857L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "maximum context length is 163,857 tokens")));
    }

    @Test
    void parsesFromCauseChain() {
        RuntimeException wrapped = new RuntimeException("outer",
                new RuntimeException("Maximum context length is 65536"));
        assertEquals(65_536L, SummarizerOverflow.parseWindowTokens(wrapped));
    }

    @Test
    void returnsNullWhenNoAnchoredNumber() {
        assertNull(SummarizerOverflow.parseWindowTokens(new RuntimeException("prompt is too long")));
        // 有数字但没锚:不瞎猜
        assertNull(SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "input too long, request id 8845123")));
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
mvn -pl springai-code-tui test -Dtest=SummarizerOverflowTest
```

预期:COMPILATION ERROR,找不到 SummarizerOverflow。

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摘要请求失败的「超限」判定与真实窗口数字解析。
 *
 * <p>分类遍历异常 cause chain(Spring AI 通常把服务端 4xx 包在通用 RuntimeException 里),任一层消息
 * 命中超限特征串才算超限;其余(网络/限流/鉴权)一律不算——<b>绝不拿网络错误调预算</b>。
 *
 * <p>数字解析<b>锚定窗口值</b>而非「找第一个数字」:Anthropic 的 {@code 195300 tokens > 200000 maximum}
 * 里 195300 是请求量,取错就把预算钉在还是会失败的水平上。
 */
final class SummarizerOverflow {

    /** cause chain 遍历深度上限(防环)。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    private static final String[] OVERFLOW_MARKERS = {
            "context_length_exceeded", "prompt is too long", "maximum context length",
            "input too long", "context window", "最大上下文", "上下文长度", "输入过长",
    };

    /** 锚定窗口数字的模式,按序尝试;第 1 捕获组即窗口值(允许千分位逗号)。 */
    private static final Pattern[] WINDOW_PATTERNS = {
            // OpenAI / DeepSeek: "maximum context length is 163857 tokens"
            Pattern.compile("maximum context length is\\s*([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE),
            // Anthropic: "195300 tokens > 200000 maximum"
            Pattern.compile(">\\s*([0-9][0-9,]*)\\s*maximum", Pattern.CASE_INSENSITIVE),
            // 中文变体: "最大上下文长度为 131072" / "最长 131072"
            Pattern.compile("(?:最大上下文(?:长度)?为?|最长)\\s*([0-9][0-9,]*)"),
    };

    private SummarizerOverflow() { }

    static boolean isOverflow(Throwable failure) {
        return firstMessageMatching(failure) != null;
    }

    /** 从 cause chain 里解析锚定的窗口 token 数;解析不到返回 null(调用方走减半探测)。 */
    static Long parseWindowTokens(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            for (Pattern pattern : WINDOW_PATTERNS) {
                Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    try {
                        return Long.parseLong(matcher.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        // 长到溢出的数字视为没解析到,继续找
                    }
                }
            }
        }
        return null;
    }

    private static String firstMessageMatching(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            for (String marker : OVERFLOW_MARKERS) {
                if (lower.contains(marker)) return message;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -pl springai-code-tui test -Dtest=SummarizerOverflowTest
```

预期:10 tests PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SummarizerOverflow.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SummarizerOverflowTest.java
git commit -m "feat(tui): 摘要超限分类器与窗口数字锚定解析"
```
### Task 4: 策略核心——校准构造器 + 乐观全量算法(场景 1~6)

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCompactionStrategy.java`(全文件替换,旧构造器行为不变)
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCalibrationTest.java`

**测试断言技巧(全文件贯穿):** estimator 用 `String::length`,于是 `E == 首次全量调用输入的 length`——所有校准值断言都相对 `inputs.get(0).length()` 表达,不预测 kept/archived 的精确切分(SessionTokenEstimator 可能有小额封装开销,kept 数量会浮动,但断言对 kept∈{0,1,2} 全部成立,已逐一推演)。

- [ ] **Step 1: 写失败测试(场景 1~6)**

```java
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
}
```

- [ ] **Step 2: 跑测试确认编译失败(新构造器/ModelSnapshot 不存在)**

```bash
mvn -pl springai-code-tui test -Dtest=BoundedSummarizationCalibrationTest
```

预期:COMPILATION ERROR。

- [ ] **Step 3: 全文件替换实现**

用下面内容整体替换 `BoundedSummarizationCompactionStrategy.java`。旧构造器走 `compactPessimistic`(与现状逐行一致,仅 `chunks`/`textChunks` 参数化);新构造器走 `compactCalibrated`:

```java
package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Compacts by token budget. Two modes:
 *
 * <p><b>校准模式</b>(新构造器,生产装配用):乐观全量摘要 + 区间学习。窗口配置正确时归档一次全量
 * 送摘要模型(1 次调用);超限失败时按错误里的窗口数字(或减半探测)自我校准,校准区间按
 * {@code provider:model} 记在共享 {@link CalibrationState} 里,跨压缩记忆。三重安全阀 + 全局
 * {@value #MAX_TOTAL_CALLS} 次调用硬上限,任一触发即落本地纯文本兜底(localDigest)。
 *
 * <p><b>悲观模式</b>(旧构造器,保留给测试与无校准场景):固定 chunk 预算预分片,行为与历史版本
 * 逐行一致。
 *
 * <p>The newest complete suffix is kept verbatim; older events are summarized.
 */
final class BoundedSummarizationCompactionStrategy implements CompactionStrategy {

    private static final Logger log = LoggerFactory.getLogger(BoundedSummarizationCompactionStrategy.class);

    private static final String SUMMARY_SOURCE = "bounded-recursive-summarization";

    // —— 校准模式常量(设计 v2:安全阀 + 全局上限;边界统一为「触线前允许最后一次尝试」)——
    /** 安全阀 1:切块/探测预算<b>严格小于</b>此值即本地兜底;恰为 16k 允许最后一次尝试。 */
    static final long MIN_SUMMARY_BUDGET = 16_000L;
    /** 安全阀 2:按预算切出的块数<b>超过</b>此值即本地兜底(等效回到旧算法的慢,不如不发)。 */
    static final int MAX_CHUNKS = 8;
    /** 安全阀 3:单次压缩内探测减半深度上限(第 4 次减半允许尝试,仍失败才兜底)。 */
    static final int MAX_HALVING_DEPTH = 4;
    /** 全局硬上限:单次压缩内所有模型调用(全量+探测+切块+再压缩)共享一个递减计数,归零即兜底。 */
    static final int MAX_TOTAL_CALLS = 20;

    /**
     * 一次快照派生的模型身份与窗口:校准 key 与预算必须<b>同源</b>——由装配方在单次
     * {@code ProviderRegistry.activeRequestSelection()} 里派生,防 /model 并发切换在两次读取间交错。
     */
    record ModelSnapshot(String calibrationKey, long windowTokens, long inputReserve) {
        long fullInputBudget() { return Math.max(1L, windowTokens - inputReserve); }
    }

    /** 全局调用预算耗尽的内部信号:调用方一律转本地兜底。必须先于 RuntimeException 被 catch。 */
    private static final class CallBudgetExhausted extends RuntimeException {
        CallBudgetExhausted() { super(null, null, false, false); }
    }

    private final LongSupplier targetTokens;
    private final LongSupplier chunkTokens;          // 悲观模式专用;校准模式下为 null
    private final ToIntFunction<String> estimator;
    private final Function<String, String> summarizer;
    private final Supplier<ModelSnapshot> snapshot;  // 校准模式专用;悲观模式下为 null
    private final CalibrationState calibration;      // 非 null 即校准模式

    /** 悲观模式(测试兼容)。 */
    BoundedSummarizationCompactionStrategy(long targetTokens, long chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this(() -> targetTokens, () -> chunkTokens, estimator, summarizer);
    }

    /** 悲观模式(测试兼容):固定 chunk 预算预分片,无校准能力。 */
    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this(targetTokens, chunkTokens, estimator, summarizer, null, null);
    }

    /** 校准模式(生产装配):乐观全量 + 区间学习。auto/manual 两条策略须共享同一个 calibration。 */
    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer,
                                           Supplier<ModelSnapshot> snapshot,
                                           CalibrationState calibration) {
        this(targetTokens, null, estimator, summarizer,
                Objects.requireNonNull(snapshot, "snapshot"),
                Objects.requireNonNull(calibration, "calibration"));
    }

    private BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                                   ToIntFunction<String> estimator,
                                                   Function<String, String> summarizer,
                                                   Supplier<ModelSnapshot> snapshot,
                                                   CalibrationState calibration) {
        this.targetTokens = targetTokens;
        this.chunkTokens = chunkTokens;
        this.estimator = estimator;
        this.summarizer = summarizer;
        this.snapshot = snapshot;
        this.calibration = calibration;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        List<SessionEvent> events = request.events();
        long targetBudget = targetTokens.getAsLong();
        if (targetBudget <= 0) throw new IllegalStateException("token budgets must be positive");
        if (calibration == null && chunkTokens.getAsLong() <= 0) {
            throw new IllegalStateException("token budgets must be positive");
        }
        long total = SessionTokenEstimator.estimateEvents(events, estimator);
        if (total <= targetBudget) {
            return new CompactionResult(events, List.of(), 0);
        }

        long keepBudget = Math.max(1L, targetBudget / 2L);
        int split = newestSuffixStart(events, keepBudget);
        if (split <= 0) {
            split = events.size() == 1 ? 1 : Math.max(1, events.size() - 1);
        }
        List<SessionEvent> archived = List.copyOf(events.subList(0, split));
        List<SessionEvent> kept = List.copyOf(events.subList(split, events.size()));
        if (SessionTokenEstimator.estimateEvents(kept, estimator) > keepBudget) {
            archived = events;
            kept = List.of();
        }

        return calibration == null
                ? compactPessimistic(request, archived, kept, targetBudget, total)
                : compactCalibrated(request, archived, kept, targetBudget, total);
    }

    // ==================== 校准模式 ====================

    private CompactionResult compactCalibrated(CompactionRequest request, List<SessionEvent> archived,
                                               List<SessionEvent> kept, long targetBudget, long total) {
        long summaryBudget = Math.max(1L, targetBudget
                - SessionTokenEstimator.estimateEvents(kept, estimator)
                - estimate("[Earlier conversation summary]\n") - 8L);
        String text = format(archived);
        long textEstimate = estimate(text);
        ModelSnapshot snap = snapshot.get();   // 一次快照:key 与窗口同源
        int[] callsLeft = { MAX_TOTAL_CALLS };

        String merged = summarizeCalibrated(text, textEstimate, snap, callsLeft, summaryBudget);
        if (merged == null || estimate(merged) > summaryBudget) {
            merged = localDigest(archived, summaryBudget);
        }
        log.debug("压缩完成: E={}, 调用数={}, 兜底={}", textEstimate,
                MAX_TOTAL_CALLS - callsLeft[0], merged.startsWith("Earlier history was too large"));

        List<SessionEvent> compacted = new ArrayList<>(kept.size() + 2);
        compacted.add(synthetic(request, new UserMessage("[Earlier conversation summary]")));
        compacted.add(synthetic(request, new AssistantMessage(merged)));
        compacted.addAll(kept);
        int saved = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                total - SessionTokenEstimator.estimateEvents(compacted, estimator)));
        return new CompactionResult(compacted, archived, saved);
    }

    /** 主算法:乐观全量 → 区间学习 → 切块;返回 null 即「走本地兜底」。 */
    private String summarizeCalibrated(String text, long textEstimate, ModelSnapshot snap,
                                       int[] callsLeft, long summaryBudget) {
        String key = snap.calibrationKey();
        CalibrationState.Interval interval = calibration.get(key);
        long fullBudget = snap.fullInputBudget();

        boolean provenSafe = interval.goodProven() && textEstimate <= interval.knownGood();
        boolean middleZone = textEstimate <= fullBudget
                && (interval.knownBad() == null || textEstimate < interval.knownBad());
        long chunkBudget;
        if (provenSafe || middleZone) {
            // 1+3. 已证明安全,或区间中间态/首次:乐观全量(失败便宜且能学到容量)
            try {
                String summary = call(text, callsLeft);
                calibration.recordGood(key, textEstimate);
                log.debug("压缩全量摘要成功: E={}", textEstimate);
                return recompress(summary, fullBudget, callsLeft, summaryBudget);
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (!SummarizerOverflow.isOverflow(failure)) {
                    log.debug("压缩全量摘要非超限失败,本地兜底: {}", failure.toString());
                    return null;
                }
                calibration.recordBad(key, textEstimate);
                interval = calibration.get(key);
                Long parsed = SummarizerOverflow.parseWindowTokens(failure);
                if (parsed != null && parsed - snap.inputReserve() > 0) {
                    // 带数字:官方容量,一步到位,不需要探测
                    chunkBudget = clamp(parsed - snap.inputReserve(), interval.provenFloor(), fullBudget);
                    log.debug("压缩全量超限,错误带窗口数字 {} → 切块预算 {}", parsed, chunkBudget);
                } else {
                    Long probed = probeForBudget(text, textEstimate, key, fullBudget, callsLeft);
                    if (probed == null) return null;
                    chunkBudget = probed;
                }
            }
        } else if (interval.knownBad() != null && textEstimate >= interval.knownBad()) {
            // 2. knownBad 短路:绝不重发注定失败的全量
            chunkBudget = Math.max(interval.knownGood(),
                    Math.min(CalibrationState.SAFE_FALLBACK_BUDGET, interval.knownBad() - 1));
            log.debug("压缩 knownBad 短路: E={} ≥ knownBad={} → 切块预算 {}",
                    textEstimate, interval.knownBad(), chunkBudget);
        } else {
            // E > fullBudget:归档比配置窗口还大,按窗口预算直接切块(配置诚实则必成,虚高则学到后短路)
            chunkBudget = interval.knownBad() != null
                    ? Math.max(1L, Math.min(fullBudget, interval.knownBad() - 1))
                    : fullBudget;
        }
        return summarizeChunked(text, textEstimate, chunkBudget, key, callsLeft, summaryBudget);
    }

    /**
     * 无数字路径的减半探测:单次请求、输入取全文前 budget tokens 的前缀。
     * 探测成功只学容量(结果不复用),返回该预算;失败继续减半,深度 ≤ {@value #MAX_HALVING_DEPTH}。
     */
    private Long probeForBudget(String text, long textEstimate, String key,
                                long fullBudget, int[] callsLeft) {
        long budget = textEstimate;
        for (int depth = 1; depth <= MAX_HALVING_DEPTH; depth++) {
            CalibrationState.Interval interval = calibration.get(key);
            long badCap = interval.knownBad() == null ? Long.MAX_VALUE : interval.knownBad() - 1;
            budget = clamp(Math.min(budget / 2, badCap), interval.provenFloor(), fullBudget);
            if (budget < MIN_SUMMARY_BUDGET) {
                log.debug("压缩探测预算 {} 跌破下限 {},本地兜底", budget, MIN_SUMMARY_BUDGET);
                return null;
            }
            String probe = text.substring(0, prefixEnd(text, 0, budget));
            try {
                call(probe, callsLeft);   // 结果只学容量,不作为摘要复用
                calibration.recordGood(key, budget);
                log.debug("压缩探测成功: budget={}, depth={}", budget, depth);
                return budget;
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (!SummarizerOverflow.isOverflow(failure)) {
                    log.debug("压缩探测非超限失败,本地兜底: {}", failure.toString());
                    return null;
                }
                calibration.recordBad(key, budget);
            }
        }
        log.debug("压缩探测减半深度耗尽({}),本地兜底", MAX_HALVING_DEPTH);
        return null;
    }

    /** 按校准预算正式切块;任一块失败即整体兜底(不复用部分摘要,超限块仍记入 knownBad)。 */
    private String summarizeChunked(String text, long textEstimate, long chunkBudget, String key,
                                    int[] callsLeft, long summaryBudget) {
        if (chunkBudget < MIN_SUMMARY_BUDGET) {
            log.debug("压缩切块预算 {} 跌破下限 {},本地兜底", chunkBudget, MIN_SUMMARY_BUDGET);
            return null;
        }
        long chunkCount = (textEstimate + chunkBudget - 1) / chunkBudget;
        if (chunkCount > MAX_CHUNKS) {
            log.debug("压缩切块数 {} 超上限 {},本地兜底", chunkCount, MAX_CHUNKS);
            return null;
        }
        List<String> summaries = new ArrayList<>();
        for (String chunk : textChunks(text, chunkBudget)) {
            String summary;
            try {
                summary = call(chunk, callsLeft);
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (SummarizerOverflow.isOverflow(failure)) {
                    // 中途某块超限:记下失败量防下次原样再撞;本轮不复用部分摘要、不重试单块
                    calibration.recordBad(key, estimate(chunk));
                }
                log.debug("压缩切块失败,本地兜底: {}", failure.toString());
                return null;
            }
            if (summary != null && !summary.isBlank()) summaries.add(summary.strip());
            calibration.recordGood(key, estimate(chunk));   // 成功块逐个入账(诚实口径:按实际输入量)
        }
        if (summaries.isEmpty()) return null;
        return recompress(String.join("\n\n", summaries), chunkBudget, callsLeft, summaryBudget);
    }

    /** 合并摘要超预算时的再压缩循环(≤4 轮,调用数计入全局上限);返回 null 即「走本地兜底」。 */
    private String recompress(String merged, long regroupBudget, int[] callsLeft, long summaryBudget) {
        try {
            for (int round = 0; estimate(merged) > summaryBudget && round < 4; round++) {
                List<String> next = new ArrayList<>();
                for (String group : textChunks(merged, regroupBudget)) {
                    String summary = call(group, callsLeft);
                    if (summary != null && !summary.isBlank()) next.add(summary.strip());
                }
                String candidate = String.join("\n\n", next);
                if (candidate.isEmpty() || estimate(candidate) >= estimate(merged)) break;
                merged = candidate;
            }
        } catch (CallBudgetExhausted exhausted) {
            return null;
        } catch (RuntimeException failure) {
            log.debug("压缩再压缩失败,本地兜底: {}", failure.toString());
            return null;
        }
        return merged;
    }

    /** 全局调用上限的唯一扣减点:所有摘要模型调用必须走这里。 */
    private String call(String input, int[] callsLeft) {
        if (callsLeft[0] <= 0) {
            log.debug("压缩调用预算耗尽({} 次),本地兜底", MAX_TOTAL_CALLS);
            throw new CallBudgetExhausted();
        }
        callsLeft[0]--;
        return summarizer.apply(input);
    }

    private static long clamp(long value, long lower, long upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    // ==================== 悲观模式(与历史行为逐行一致) ====================

    private CompactionResult compactPessimistic(CompactionRequest request, List<SessionEvent> archived,
                                                List<SessionEvent> kept, long targetBudget, long total) {
        long chunkBudget = chunkTokens.getAsLong();
        List<String> summaries = new ArrayList<>();
        boolean summarizationFailed = false;
        try {
            for (List<SessionEvent> chunk : chunks(archived, chunkBudget)) {
                for (String bounded : textChunks(format(chunk), chunkBudget)) {
                    String summary = summarizer.apply(bounded);
                    if (summary != null && !summary.isBlank()) summaries.add(summary.strip());
                }
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }

        String merged = summarizationFailed || summaries.isEmpty()
                ? localDigest(archived, Math.max(1L, targetBudget / 2L))
                : String.join("\n\n", summaries);
        try {
            for (int round = 0; !summarizationFailed && estimate(merged) > chunkBudget && round < 4; round++) {
                List<String> next = new ArrayList<>();
                for (String group : textChunks(merged, chunkBudget)) {
                    String summary = summarizer.apply(group);
                    if (summary != null && !summary.isBlank()) next.add(summary.strip());
                }
                String candidate = String.join("\n\n", next);
                if (candidate.isEmpty() || estimate(candidate) >= estimate(merged)) break;
                merged = candidate;
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }
        long summaryBudget = Math.max(1L, targetBudget
                - SessionTokenEstimator.estimateEvents(kept, estimator)
                - estimate("[Earlier conversation summary]\n") - 8L);
        if (estimate(merged) > summaryBudget) {
            merged = localDigest(archived, summaryBudget);
        }

        List<SessionEvent> compacted = new ArrayList<>(kept.size() + 2);
        compacted.add(synthetic(request, new UserMessage("[Earlier conversation summary]")));
        compacted.add(synthetic(request, new AssistantMessage(merged)));
        compacted.addAll(kept);
        int saved = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                total - SessionTokenEstimator.estimateEvents(compacted, estimator)));
        return new CompactionResult(compacted, archived, saved);
    }

    // ==================== 共用工具 ====================

    private int newestSuffixStart(List<SessionEvent> events, long budget) {
        long used = 0L;
        int start = events.size();
        for (int i = events.size() - 1; i >= 0; i--) {
            long eventTokens = SessionTokenEstimator.estimateEvents(List.of(events.get(i)), estimator);
            if (start < events.size() && used + eventTokens > budget) break;
            used += eventTokens;
            start = i;
        }
        while (start < events.size() && start > 0 && !events.get(start).isRootEvent()) start--;
        return start;
    }

    private List<List<SessionEvent>> chunks(List<SessionEvent> events, long chunkBudget) {
        List<List<SessionEvent>> out = new ArrayList<>();
        List<SessionEvent> current = new ArrayList<>();
        long used = 0L;
        for (SessionEvent event : events) {
            long tokens = SessionTokenEstimator.estimateEvents(List.of(event), estimator);
            if (!current.isEmpty() && used + tokens > chunkBudget) {
                out.add(List.copyOf(current));
                current.clear();
                used = 0L;
            }
            if (tokens > chunkBudget) {
                for (String part : textChunks(format(List.of(event)), chunkBudget)) {
                    out.add(List.of(syntheticEvent(event.getSessionId(), new UserMessage(part))));
                }
            } else {
                current.add(event);
                used += tokens;
            }
        }
        if (!current.isEmpty()) out.add(List.copyOf(current));
        return out;
    }

    /** 从 from 起、estimate ≤ budget 的最长前缀终点(二分)。 */
    private int prefixEnd(String text, int from, long budget) {
        int low = from + 1, high = text.length(), best = from + 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (estimate(text.substring(from, mid)) <= budget) {
                best = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        return best;
    }

    private List<String> textChunks(String text, long budget) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int end = prefixEnd(text, from, budget);
            out.add(text.substring(from, end));
            from = end;
        }
        return out;
    }

    private long estimate(String text) {
        return text == null || text.isEmpty() ? 0L : estimator.applyAsInt(text);
    }

    private String localDigest(List<SessionEvent> archived, long budget) {
        String prefix = "Earlier history was too large for a safe model-generated summary. "
                + archived.size() + " events were compacted locally. Recent context follows.";
        if (estimate(prefix) <= budget) return prefix;
        int low = 0, high = prefix.length(), best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (estimate(prefix.substring(0, mid)) <= budget) {
                best = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        return prefix.substring(0, best);
    }

    private String format(List<SessionEvent> events) {
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : events) {
            Message message = event.getMessage();
            out.append('[').append(message.getMessageType()).append("] ");
            if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.append(response.name()).append(": ").append(response.responseData()).append('\n');
                }
            } else {
                out.append(message.getText()).append('\n');
                if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        out.append("tool_call ").append(call.name()).append(' ').append(call.arguments()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private SessionEvent synthetic(CompactionRequest request, Message message) {
        return syntheticEvent(request.session().id(), message);
    }

    private static SessionEvent syntheticEvent(String sessionId, Message message) {
        return SessionEvent.builder().sessionId(sessionId).message(message)
                .metadata(Map.of(SessionEvent.METADATA_SYNTHETIC, true,
                        SessionEvent.METADATA_COMPACTION_SOURCE, SUMMARY_SOURCE))
                .build();
    }
}
```

实现要点(照抄时自查):

- `CalibrationState.Interval` 需要实现 `equals`(record 自带)——场景 6 用 `assertEquals(INITIAL, ...)`。
- `CallBudgetExhausted` 的 catch 必须排在 `RuntimeException` 之前(它是其子类)。
- 成功块 `recordGood(estimate(chunk))` 用<b>实际输入量</b>而非 budget(设计说 recordGood(budget),但单块可能远小于 budget,按 budget 记会把未验证量标成已验证——诚实口径,且直接满足设计测试 9 的「按已成功块的最大 estimate 更新」)。
- 探测的下限钳制用 `provenFloor()` 而非裸 knownGood:初始 32k 是假设,拿假设当下限会在真实窗口 < 32k 时永远探不下去。

- [ ] **Step 4: 跑新测试 + 三个守卫基线**

```bash
mvn -pl springai-code-tui test -Dtest='BoundedSummarizationCalibrationTest,ContextStatsTest,AgentToolsCompactionWiringTest,CalibrationStateTest,SummarizerOverflowTest'
```

预期:全 PASS(旧构造器路径逐行未动,ContextStatsTest 不许红)。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCompactionStrategy.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCalibrationTest.java
git commit -m "feat(tui): 压缩策略乐观全量摘要 + 区间校准(新构造器)"
```
### Task 5: DynamicAuxChatModel——一次快照 + maxTokens 合并

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DynamicAuxChatModel.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DynamicAuxChatModelTest.java`

**前置事实（已核实）：**
- aux 路径的 options 契约是 **DEFAULT 思考配置**（`auxAlwaysUsesDefaultConfig` 守卫），而 `activeRequestSelection().options()` 是思考配置感知的——所以快照只取**身份**（provider+modelId），options 仍走 `sel.provider().options(sel.modelId())` 一参 DEFAULT 入口，行为不变。
- 现状 `registry.active()` + `registry.activeModelId()` 两次读取的交错窗口即设计「快照一致性」要修的 bug。
- `ChatOptions.mutate().maxTokens(Integer).build()`：native options 协变 Builder、状态全保留（前置须知 C）。
- stream() 的 `Flux.defer`（延迟订阅）属 usage-domain 评审 P2，**不在本设计范围**，不做。

- [x] **Step 1: 写失败测试**（DynamicAuxChatModelTest 补 2 例，RecordingChatModel 增记 `lastMaxTokens`，FakeProvider 支持基础 maxTokens）

`promptMaxTokensOverridesProviderBase`：prompt 带 maxTokens=8192 → delegate 收到 model=model-a 且 maxTokens=8192。
`promptWithoutMaxTokensKeepsProviderBase`：provider 基础 maxTokens=4096、prompt 不带 → delegate 仍见 4096。

- [x] **Step 2: 红** `mvn -pl springai-code-tui test -Dtest=DynamicAuxChatModelTest`
- [x] **Step 3: 实现**——call/stream 各取一次 `activeRequestSelection()`；`withActiveOptions` 改合并式：

```java
private Prompt withActiveOptions(Prompt prompt, ProviderRegistry.RequestSelection sel) {
    ChatOptions base = sel.provider().options(sel.modelId());   // DEFAULT 思考配置（aux 契约）
    ChatOptions override = prompt.getOptions();
    ChatOptions merged = override == null || override.getMaxTokens() == null
            ? base
            : base.mutate().maxTokens(override.getMaxTokens()).build();
    return new Prompt(prompt.getInstructions(), merged);
}
```

- [x] **Step 4: 绿**（含原有 3 例不回归）
- [x] **Step 5: 提交** `feat(tui): 辅助模型一次快照与 maxTokens 合并`

### Task 6: AgentTools 装配——共享 CalibrationState + 校准构造器 + 摘要 maxTokens

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`

- [x] **Step 1: 实现**（装配级改动，行为由 Task 7/8 的策略级与守卫测试覆盖）：
  1. 新增 `SUMMARY_OUTPUT_RESERVE = 8_000L`；`modelSnapshot(registry)` 静态方法：一次 `activeRequestSelection()` 派生 `ModelSnapshot(key=provider.id()+":"+modelId, window=MODEL_CONTEXT_WINDOWS.resolve(...), inputReserve=SUMMARY_PROMPT_TOKEN_RESERVE+SUMMARY_OUTPUT_RESERVE)`（registry null 走 `CONTEXT_WINDOW_TOKENS` 旧兜底）。
  2. `contextWindow(registry)` 的两次读取改为同款单快照派生（设计「快照一致性」，行为等价）。
  3. `build()` 创建**一个** `CalibrationState`；auto/manual 两条策略改走 5 参校准构造器，共享之；`chunkBudget` supplier 与 `MAX_SUMMARY_CHUNK_TOKENS` 常量删除（前置须知 D.2）。
  4. 摘要 lambda 抽成共用方法：system prompt 追加「Keep the summary under 8000 tokens.」，并 `.options(ChatOptions.builder().maxTokens(8192))`（收 Builder，前置须知 C）。

- [x] **Step 2: 编译 + 守卫** `mvn -pl springai-code-tui test -Dtest='AgentToolsCompactionWiringTest,AuxClientNotVisionWrappedTest,ContextStatsTest'`（须全绿）
- [x] **Step 3: 提交** `feat(tui): 压缩策略接入校准装配与摘要输出上限`

### Task 7: 策略级剩余行为测试（安全阀/全局上限/局部失败/共享校准）

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BoundedSummarizationCalibrationTest.java`

- [x] **Step 1: 补 4 例（先跑确认红或行为不符，再对照实现）：**
  1. `safetyValveBudgetBelowFloorFallsBackLocally`：数字锚定出 < 16k 的预算（如窗口 20k）→ 只 1 次失败调用即 localDigest，不切块。
  2. `safetyValveChunkCountOverEightFallsBackLocally`：预算 ≥16k 但 ⌈E/budget⌉ > 8 → 全量失败 1 次后直接兜底，无切块调用。
  3. `globalCallBudgetCapsAtTwenty`：summarizer 恒回显输入（非空、体积不减）→ 恰 20 次调用后 localDigest（1 全量 + 1 探测 + 4 切块 + 14 再压缩内耗尽）。
  4. `partialChunkFailureFallsBackButKeepsLearnedIntervals`：全量失败（无数字）→ 探测成功 → 首块成功、次块超限 → 整体兜底；knownGood=max(探测, 首块)，knownBad=次块 estimate。
  5. `sharedCalibrationGivesSecondStrategyZeroTrial`：两个策略实例共享同一 CalibrationState，A 学到 knownBad 后 B 的首次压缩直接切块（不发全量）。

- [x] **Step 2: 绿** `mvn -pl springai-code-tui test -Dtest=BoundedSummarizationCalibrationTest`
- [x] **Step 3: 提交** `test(tui): 压缩校准安全阀/上限/共享状态行为测试`

### Task 8: 全量回归 + 收尾

- [x] **Step 1: 模块全测** `mvn -pl springai-code-tui test`（全绿，0 失败）
- [x] **Step 2: 勾选本计划全部 checkbox，提交** `docs: 压缩乐观全量摘要计划执行完毕`
- [x] **Step 3: 汇报**：不合并回 main（共用树有并行在途改动，由用户决定合并时机）。
