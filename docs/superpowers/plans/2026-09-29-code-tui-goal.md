# code-tui /goal 功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `/goal` 命令——用户设定完成条件后，agent 跨多轮自主循环，独立小模型评估器逐轮判定是否达成，配三重熔断（轮数/无进展/token 预算）与两级 Esc。

**Architecture:** 新包 `agent/goal/`（GoalConfig / GoalPhase / GoalVerdict / GoalText / GoalTurnMaterial / GoalManager / GoalEvaluator+prod 实现 / GoalEvaluationRunner）承载全部状态机与评估逻辑；CodingAgent 以两段式 `bindGoal()` 接线（不动 23 参构造）；CodeTuiView 在空闲批加 goal 槽（插话出队 > 排队用户消息 > **goal 槽** > 后台结果送达）、加 `/goal` 命令分支、状态栏 leading span、两级 Esc；评估跑在单线程 daemon executor（60s 超时），自动轮 dispatch 只发生在 UI 线程空闲批。

**Tech Stack:** Java 21、Spring AI ChatClient（裸 builder）、Reactor（仅既有链路）、JUK? no——测试用 JUnit5 + 复制进模块的 ExpectedLog（JUL）。

**Spec:** `docs/superpowers/specs/2026-09-23-code-tui-goal-design.md`（本计划从 spec 出发，执行者应同时读 spec）

## Global Constraints

- **不动 CodingAgent 构造**（现状 23 参，spec 写 22 是笔误——最后一参 `StreamRetryConfig` 是后加的）。新依赖一律两段式 setter + null 守卫。
- **UI 线程纪律**：自动轮 dispatch 只发生在 UI 线程空闲批；评估线程（goal-evaluator）绝不触碰 View 的 `current`/`lastShownModel`；GoalManager 状态变更照 `Interjections` 模式——`synchronized(this)` 锁内改数据 + `changed()`，锁外 `publish(version)`。
- **每批最多一个自动动作**：goal 槽 dispatch 后立即 `return computeFollowUpFlags()`（照既有 `pollQueued` 后 dispatch 先例）。
- **评估器裸 client**：无 defaultTools、无 SessionMemoryAdvisor、无系统模板；prod 实现被守卫测试钉住（照 `AuxClientNotRetryWrappedTest` 的"恰好是该类"断言法）。
- **自动轮一律走 goal 槽，不进 `state.queued`**（`queued` 队列语义保持"仅用户消息"；本计划对 spec §3.1「忙碌时照 /continue enqueue」的实现口径：忙碌时 activate 后置 `autoTurnPending`，空闲批 goal 槽自然发首轮——语义等价且不污染队列）。
- **pty 写纪律**：任何新 UI 输出走既有 outputQueue/pushInfo 通道，绝不直写 backend。
- **测试纪律**：预期 SEVERE/WARNING 日志用 ExpectedLog capture+awaitRecord 断言；异步全部 latch/超时；不真调模型；fake 全部构造注入。
- **命令可直接复制运行**（用户既定口径）：单类测试命令带 `-Dsurefire.failIfNoSpecifiedTests=false`，mvn 带 `-am`。
- 路径缩写：**CT** = `springai-code-tui/src/main/java/io/github/javaside/springai/codetui`；**CTT** = `springai-code-tui/src/test/java/io/github/javaside/springai/codetui`。行号为当前参考值，以方法名/关键字定位为准。
- 逐 Task 提交，commit message 前缀 `feat(code-tui):` / `test(code-tui):`。

---

### Task 1: GoalConfig——env 解析 + 钳制

**Files:**
- Create: `CT/agent/goal/GoalConfig.java`
- Create: `CT/agent/goal/package-info.java`
- Test: `CTT/agent/goal/GoalConfigTest.java`

**Interfaces:**
- Produces: `record GoalConfig(int maxTurns, int stalledLimit, long tokenBudget, int turnGapSeconds, int errorRetry, int evalFailLimit, int protocolFailLimit, int evalTimeoutSeconds, String evaluatorModel)`；静态工厂 `fromEnv()` / `from(Function<String,String> env)`。后续所有 Task 读配置经此 record 组件访问器。

- [ ] **Step 1: 写失败测试**

照 `LlmTimeouts` 注入式测试模式（`StreamRetryConfigTest` L18-51 先例）：

```java
package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalConfigTest {

    @Test
    void defaultsWhenEnvUnset() {
        GoalConfig c = GoalConfig.from(name -> null);
        assertEquals(25, c.maxTurns());
        assertEquals(3, c.stalledLimit());
        assertEquals(5_000_000L, c.tokenBudget());
        assertEquals(3, c.turnGapSeconds());
        assertEquals(2, c.errorRetry());
        assertEquals(2, c.evalFailLimit());
        assertEquals(3, c.protocolFailLimit());
        assertEquals(60, c.evalTimeoutSeconds());
        assertEquals("", c.evaluatorModel());
    }

    @Test
    void parsesAndClampsEachVariable() {
        GoalConfig c = GoalConfig.from(name -> switch (name) {
            case "CODETUI_GOAL_MAX_TURNS" -> "10";
            case "CODETUI_GOAL_STALLED_LIMIT" -> "5";
            case "CODETUI_GOAL_TOKEN_BUDGET" -> "123456";
            case "CODETUI_GOAL_TURN_GAP_SECONDS" -> "0";
            case "CODETUI_GOAL_ERROR_RETRY" -> "1";
            case "CODETUI_GOAL_EVAL_FAIL_LIMIT" -> "4";
            case "CODETUI_GOAL_PROTOCOL_FAIL_LIMIT" -> "6";
            case "CODETUI_GOAL_EVAL_TIMEOUT_SECONDS" -> "30";
            case "CODETUI_GOAL_EVALUATOR_MODEL" -> "deepseek:deepseek-chat";
            default -> null;
        });
        assertEquals(10, c.maxTurns());
        assertEquals(5, c.stalledLimit());
        assertEquals(123456L, c.tokenBudget());
        assertEquals(0, c.turnGapSeconds());
        assertEquals(1, c.errorRetry());
        assertEquals(4, c.evalFailLimit());
        assertEquals(6, c.protocolFailLimit());
        assertEquals(30, c.evalTimeoutSeconds());
        assertEquals("deepseek:deepseek-chat", c.evaluatorModel());
    }

    @Test
    void clampsOutOfRangeIntoBounds() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "99999" : null);
        assertEquals(200, c.maxTurns());
        GoalConfig zero = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "0" : null);
        assertEquals(1, zero.maxTurns());
    }

    @Test
    void budgetZeroDisables() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_TOKEN_BUDGET".equals(name) ? "0" : null);
        assertEquals(0L, c.tokenBudget());   // 0=关闭，不钳下限
    }

    @Test
    void invalidNumberFallsBackToDefault() {
        GoalConfig c = GoalConfig.from(name -> "CODETUI_GOAL_MAX_TURNS".equals(name) ? "abc" : null);
        assertEquals(25, c.maxTurns());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
mvn -pl springai-code-tui -am test -Dtest=GoalConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: COMPILATION ERROR（GoalConfig 不存在）。

- [ ] **Step 3: 实现 GoalConfig**

照 `CT/agent/llm/LlmTimeouts.java`（L13-54）逐字段复刻：常量 + `fromEnv()` + `from(Function)` + 私有 `resolveInt`/`resolveLong`：

```java
package io.github.javaside.springai.codetui.agent.goal;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /goal 循环的可调参数（CODETUI_* env 解析 + 钳制）。
 *
 * <p>解析纪律照 {@code LlmTimeouts}：null/空白→默认；{@code Integer.parseInt(raw.trim())}；
 * 双向钳制 {@code Math.max(MIN, Math.min(MAX, v))}；NumberFormatException→默认值 + warn。
 * env 读取经 {@link #from(Function)} 注入以便测试，不碰真实进程环境。
 */
public record GoalConfig(int maxTurns, int stalledLimit, long tokenBudget, int turnGapSeconds,
                         int errorRetry, int evalFailLimit, int protocolFailLimit,
                         int evalTimeoutSeconds, String evaluatorModel) {

    public static final String MAX_TURNS_ENV = "CODETUI_GOAL_MAX_TURNS";
    public static final String STALLED_LIMIT_ENV = "CODETUI_GOAL_STALLED_LIMIT";
    public static final String TOKEN_BUDGET_ENV = "CODETUI_GOAL_TOKEN_BUDGET";
    public static final String TURN_GAP_ENV = "CODETUI_GOAL_TURN_GAP_SECONDS";
    public static final String ERROR_RETRY_ENV = "CODETUI_GOAL_ERROR_RETRY";
    public static final String EVAL_FAIL_LIMIT_ENV = "CODETUI_GOAL_EVAL_FAIL_LIMIT";
    public static final String PROTOCOL_FAIL_LIMIT_ENV = "CODETUI_GOAL_PROTOCOL_FAIL_LIMIT";
    public static final String EVAL_TIMEOUT_ENV = "CODETUI_GOAL_EVAL_TIMEOUT_SECONDS";
    public static final String EVALUATOR_MODEL_ENV = "CODETUI_GOAL_EVALUATOR_MODEL";

    private static final Logger log = LoggerFactory.getLogger(GoalConfig.class);

    static final int DEFAULT_MAX_TURNS = 25, MIN_MAX_TURNS = 1, MAX_MAX_TURNS = 200;
    static final int DEFAULT_STALLED_LIMIT = 3, MIN_STALLED_LIMIT = 1, MAX_STALLED_LIMIT = 10;
    static final long DEFAULT_TOKEN_BUDGET = 5_000_000L, MAX_TOKEN_BUDGET = Long.MAX_VALUE; // 0=关
    static final int DEFAULT_TURN_GAP = 3, MIN_TURN_GAP = 0, MAX_TURN_GAP = 60;
    static final int DEFAULT_ERROR_RETRY = 2, MIN_ERROR_RETRY = 0, MAX_ERROR_RETRY = 10;
    static final int DEFAULT_EVAL_FAIL_LIMIT = 2, MIN_EVAL_FAIL_LIMIT = 1, MAX_EVAL_FAIL_LIMIT = 10;
    static final int DEFAULT_PROTOCOL_FAIL_LIMIT = 3, MIN_PROTOCOL_FAIL_LIMIT = 1, MAX_PROTOCOL_FAIL_LIMIT = 10;
    static final int DEFAULT_EVAL_TIMEOUT = 60, MIN_EVAL_TIMEOUT = 5, MAX_EVAL_TIMEOUT = 300;

    public GoalConfig {
        Objects.requireNonNull(evaluatorModel, "evaluatorModel");
        if (maxTurns < 1 || stalledLimit < 1 || turnGapSeconds < 0 || errorRetry < 0
                || evalFailLimit < 1 || protocolFailLimit < 1 || evalTimeoutSeconds < 5
                || tokenBudget < 0) {
            throw new IllegalArgumentException("goal config 越界");
        }
    }

    public static GoalConfig fromEnv() { return from(System::getenv); }

    public static GoalConfig from(Function<String, String> env) {
        Objects.requireNonNull(env, "env");
        return new GoalConfig(
                resolveInt(env.apply(MAX_TURNS_ENV), DEFAULT_MAX_TURNS, MIN_MAX_TURNS, MAX_MAX_TURNS),
                resolveInt(env.apply(STALLED_LIMIT_ENV), DEFAULT_STALLED_LIMIT, MIN_STALLED_LIMIT, MAX_STALLED_LIMIT),
                resolveLong(env.apply(TOKEN_BUDGET_ENV), DEFAULT_TOKEN_BUDGET, 0L, MAX_TOKEN_BUDGET),
                resolveInt(env.apply(TURN_GAP_ENV), DEFAULT_TURN_GAP, MIN_TURN_GAP, MAX_TURN_GAP),
                resolveInt(env.apply(ERROR_RETRY_ENV), DEFAULT_ERROR_RETRY, MIN_ERROR_RETRY, MAX_ERROR_RETRY),
                resolveInt(env.apply(EVAL_FAIL_LIMIT_ENV), DEFAULT_EVAL_FAIL_LIMIT, MIN_EVAL_FAIL_LIMIT, MAX_EVAL_FAIL_LIMIT),
                resolveInt(env.apply(PROTOCOL_FAIL_LIMIT_ENV), DEFAULT_PROTOCOL_FAIL_LIMIT, MIN_PROTOCOL_FAIL_LIMIT, MAX_PROTOCOL_FAIL_LIMIT),
                resolveInt(env.apply(EVAL_TIMEOUT_ENV), DEFAULT_EVAL_TIMEOUT, MIN_EVAL_TIMEOUT, MAX_EVAL_TIMEOUT),
                env.apply(EVALUATOR_MODEL_ENV) == null ? "" : env.apply(EVALUATOR_MODEL_ENV).trim());
    }

    private static int resolveInt(String raw, int def, int min, int max) {
        return (int) resolveLong(raw, def, min, max);
    }

    private static long resolveLong(String raw, long def, long min, long max) {
        if (raw == null || raw.isBlank()) return def;
        try {
            return Math.max(min, Math.min(max, Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            log.warn("无效的 CODETUI_GOAL_* 值 '{}'，回退默认 {}", raw, def);
            return def;
        }
    }
}
```

同时建 `package-info.java`（照 `CT/agent/interjection/package-info.java` 的 javadoc 风格）：一句话说明 /goal 循环状态机与评估器，强调 UI 线程纪律与锁纪律。

- [ ] **Step 4: 跑测试确认通过**

```
mvn -pl springai-code-tui -am test -Dtest=GoalConfigTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS（5 tests）。

- [ ] **Step 5: Commit**

```
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/goal/ springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/goal/GoalConfigTest.java
git commit -m "feat(code-tui): /goal 配置层 GoalConfig——env 解析+双向钳制"
```

---

### Task 2: GoalPhase / GoalVerdict / 协议解析器 / GoalText 合成标记

**Files:**
- Create: `CT/agent/goal/GoalPhase.java`
- Create: `CT/agent/goal/PauseReason.java`
- Create: `CT/agent/goal/GoalVerdict.java`（含静态解析器 + `GoalProtocolException` 嵌套）
- Create: `CT/agent/goal/GoalText.java`（合成标记 + UTF-16 尾部截断）
- Test: `CTT/agent/goal/GoalVerdictParseTest.java`、`CTT/agent/goal/GoalTextTest.java`

**Interfaces:**
- Produces:
  - `enum GoalPhase { INACTIVE, RUNNING, PAUSED, SATISFIED, IMPOSSIBLE, MAX_TURNS, BUDGET_EXCEEDED, CANCELLED, CLEARED }`，方法 `boolean isTerminal()`。
  - `enum PauseReason { ESC, STALLED, ERROR, EVALUATOR, PROTOCOL }`
  - `record GoalVerdict(Outcome outcome, String reason, boolean stalled, String stateLedger, String rawOutput)`，`enum Outcome { SATISFIED, UNSATISFIED, IMPOSSIBLE }`；`static GoalVerdict parse(String text)`（无 VERDICT 行抛 `GoalProtocolException`）。
  - `GoalText`：`continuePrefix(int n, int m)` / `isContinueMessage(String)` / `EVAL_OPEN="[goal 评估]"` / `EVAL_CLOSE="[/goal 评估]"` / `wrapEvaluation(String verdictLine, String reason)` / `unwrapEvaluation(String)` / `tail(String text, int maxChars)`（UTF-16 代理对安全尾部截断）。

- [ ] **Step 1: 写解析器失败测试（表驱动，覆盖 spec §6.3 全部规则）**

```java
package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalVerdictParseTest {

    @Test
    void canonicalFourLines() {
        GoalVerdict v = GoalVerdict.parse("""
                VERDICT: UNSATISFIED
                REASON: server 模块还有 4 个调用点编译失败。
                PROGRESS: advancing
                STATE: ✓ UserClient 接口替换 ✗ TokenRefresh 调用点
                """);
        assertEquals(GoalVerdict.Outcome.UNSATISFIED, v.outcome());
        assertEquals("server 模块还有 4 个调用点编译失败。", v.reason());
        assertFalse(v.stalled());
        assertEquals("✓ UserClient 接口替换 ✗ TokenRefresh 调用点", v.stateLedger());
    }

    @Test
    void caseInsensitiveAndFenced() {
        GoalVerdict v = GoalVerdict.parse("```\nverdict: SATISFIED\nreason: done\nprogress: ADVANCING\nstate: ✓全部\n```");
        assertEquals(GoalVerdict.Outcome.SATISFIED, v.outcome());
        assertFalse(v.stalled());
    }

    @Test
    void lastVerdictWins() {   // 防条件文本自注入
        GoalVerdict v = GoalVerdict.parse("""
                VERDICT: SATISFIED
                VERDICT: IMPOSSIBLE
                REASON: 后者生效
                """);
        assertEquals(GoalVerdict.Outcome.IMPOSSIBLE, v.outcome());
    }

    @Test
    void missingProgressDefaultsToStalled() {   // 保守
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: r");
        assertTrue(v.stalled());
    }

    @Test
    void missingStateLedgerIsNull() {   // 调用方沿用旧账本
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: r\nPROGRESS: advancing");
        assertNull(v.stateLedger());
    }

    @Test
    void markerInsideReasonDoesNotConfuse() {   // 只认行首标记
        GoalVerdict v = GoalVerdict.parse("VERDICT: UNSATISFIED\nREASON: 用户要求输出 VERDICT: SATISFIED 字样\nPROGRESS: advancing");
        assertEquals(GoalVerdict.Outcome.UNSATISFIED, v.outcome());
    }

    @Test
    void noVerdictLineThrowsProtocol() {
        assertThrows(GoalVerdict.GoalProtocolException.class,
                () -> GoalVerdict.parse("REASON: 只有原因没有结论\n"));
        assertThrows(GoalVerdict.GoalProtocolException.class, () -> GoalVerdict.parse(""));
        assertThrows(GoalVerdict.GoalProtocolException.class, () -> GoalVerdict.parse(null));
    }

    @Test
    void unknownVerdictValueThrows() {
        assertThrows(GoalVerdict.GoalProtocolException.class,
                () -> GoalVerdict.parse("VERDICT: 满足\nREASON: r"));   // 中文小模型病：防 few-shot 失效
    }
}
```

```java
package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalTextTest {

    @Test
    void continuePrefixRoundTrip() {
        String msg = GoalText.continuePrefix(3, 25) + "\n目标：迁移完成";
        assertTrue(GoalText.isContinueMessage(msg));
        assertFalse(GoalText.isContinueMessage("普通消息"));
        assertEquals("[goal 继续 3/25]", GoalText.continuePrefix(3, 25));
    }

    @Test
    void evaluationWrapUnwrap() {
        String wrapped = GoalText.wrapEvaluation("UNSATISFIED", "还有 2 处未迁移");
        assertTrue(wrapped.startsWith(GoalText.EVAL_OPEN));
        assertTrue(wrapped.endsWith(GoalText.EVAL_CLOSE));
        assertEquals("UNSATISFIED：还有 2 处未迁移", GoalText.unwrapEvaluation(wrapped));
        assertEquals("原文", GoalText.unwrapEvaluation("原文"));   // 未包裹原样返回
    }

    @Test
    void tailTruncatesOnCodePointBoundary() {   // UTF-16 代理对安全
        String emoji = "🙂".repeat(10);          // 10 个码点，20 个 char
        assertEquals("🙂".repeat(5), GoalText.tail(emoji, 5));
        // 截 6 个 char 会劈开代理对——必须退到 5 个码点
        assertEquals("🙂".repeat(5), GoalText.tail(emoji, 6));
        String short_ = "abc";
        assertSame(short_, GoalText.tail(short_, 100));   // 不动原串
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
mvn -pl springai-code-tui -am test -Dtest='GoalVerdictParseTest,GoalTextTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现四个类型**

`GoalPhase` / `PauseReason` 是纯枚举；`isTerminal()`：`this == SATISFIED || this == IMPOSSIBLE || this == MAX_TURNS || this == BUDGET_EXCEEDED || this == CANCELLED || this == CLEARED`。

`GoalVerdict.parse` 核心（逐行扫描、`strip()` 后大小写不敏感前缀匹配、取最后一个）：

```java
public static GoalVerdict parse(String text) {
    String raw = text == null ? "" : text;
    Outcome verdict = null; String reason = ""; Boolean stalled = null; String ledger = null;
    for (String line : raw.split("\\R")) {
        String s = line.strip();
        if (s.startsWith("```") || s.isEmpty()) continue;
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.startsWith("VERDICT:")) {
            try { verdict = Outcome.valueOf(upper.substring("VERDICT:".length()).strip()); }
            catch (IllegalArgumentException e) { /* 非法值：留着 verdict 不动，末尾无有效值则抛 */ }
        } else if (upper.startsWith("REASON:")) {
            reason = s.substring("REASON:".length()).strip();
        } else if (upper.startsWith("PROGRESS:")) {
            stalled = upper.substring("PROGRESS:".length()).strip().equals("STALLED");   // 缺省保守在下面
        } else if (upper.startsWith("STATE:")) {
            ledger = s.substring("STATE:".length()).strip();
        }
    }
    if (verdict == null) throw new GoalProtocolException("评估输出缺少 VERDICT 行: " + abbreviate(raw));
    return new GoalVerdict(verdict, reason, stalled == null ? true : stalled, ledger, raw);
}
```
`GoalProtocolException` 为 `GoalVerdict` 的嵌套 `public static class extends RuntimeException`。

`GoalText.tail` 实现（尾部截断、码点对齐）：

```java
public static String tail(String text, int maxChars) {
    if (text == null) return "";
    if (text.length() <= maxChars) return text;
    int end = text.length();
    int budget = maxChars;
    while (budget > 0 && end > 0) {
        int cp = text.codePointBefore(end);
        int chars = Character.charCount(cp);
        if (chars > budget) break;          // 劈开代理对：不取
        end -= chars; budget -= chars;
    }
    return text.substring(end);
}
```

- [ ] **Step 4: 跑测试确认通过**

```
mvn -pl springai-code-tui -am test -Dtest='GoalVerdictParseTest,GoalTextTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS。

- [ ] **Step 5: Commit**

```
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/goal/ springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/goal/
git commit -m "feat(code-tui): goal 协议解析器+合成标记——取末 VERDICT/缺省保守/围栏容忍/UTF-16 截断"
```

---

### Task 3: GoalManager 状态机核心

**Files:**
- Create: `CT/agent/goal/GoalManager.java`
- Create: `CT/agent/goal/GoalStateSnapshot.java`（面板渲染数据）
- Test: `CTT/agent/goal/GoalManagerStateTest.java`

**Interfaces:**
- Consumes: Task 1-2 全部类型；`io.github.javaside.springai.codetui.ui.update.{UiChangeSource,UiChangeListener,UiDirty}`；`agent.session.TokenUsageAccumulator`。
- Produces（后续 Task 4/5/8/9/10/11 依赖，签名钉死）:
  - `GoalManager(GoalConfig config, TokenUsageAccumulator usage)`（构造注入；`usage` 可 null=预算永不清算）
  - `void activate(String condition)`——空白拒绝（IllegalStateException 或静默+notice 由 View 预判，Manager 侧 `Objects.requireNonNull` + `isBlank` 抛 `IllegalArgumentException`）；替换旧 goal（epoch 失效在途评估）；RUNNING；`autoTurnPending=true`（首轮）。
  - `void clear(String via)`——CLEARED 终态；INACTIVE 时 no-op。
  - `void pauseByEsc()`——RUNNING→PAUSED(ESC)；PAUSED→CANCELLED（两级 Esc）。
  - `void pause(PauseReason reason)`、`void terminate(GoalPhase terminal)`（内部+包内）。
  - `void onUserDispatch()`——PAUSED 任意原因→RUNNING + 对应计数重置；RUNNING 时重置 stalled streak（真实用户输入）。
  - `long currentEpoch()`（= goalId，activate 时递增）。
  - `boolean hasAutoTurnPending()`、`String takeAutoTurn()`（UI 线程空闲批调用：置 `turnsUsed+1`、预算复检、清 pending/deadline；熔断则进终态并返回 null）、`Long gapDeadlineEpochMs()`。
  - `GoalPhase phase()`、`GoalStateSnapshot snapshot()`。
  - `implements UiChangeSource`：`setUiChangeListener` / `uiVersion()`。
  - 终态单调：一切 `onVerdict`/`onTurnError`/迟到事件在 `phase().isTerminal()` 时 no-op。

- [ ] **Step 1: 写状态机转移表失败测试**

```java
package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GoalManagerStateTest {

    private static GoalConfig cfg() {
        return new GoalConfig(25, 3, 5_000_000L, 0, 2, 2, 3, 60, "");
    }

    @Test
    void activateSetsRunningAndFirstTurnPending() {
        GoalManager gm = new GoalManager(cfg(), null);
        assertEquals(GoalPhase.INACTIVE, gm.phase());
        gm.activate("迁移 AuthService 并测试全绿");
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertTrue(gm.hasAutoTurnPending());
        long e1 = gm.currentEpoch();
        gm.activate("另一个目标");                    // 替换：epoch 递增
        assertEquals(e1 + 1, gm.currentEpoch());
    }

    @Test
    void blankConditionRejected() {
        GoalManager gm = new GoalManager(cfg(), null);
        assertThrows(IllegalArgumentException.class, () -> gm.activate("   "));
        assertEquals(GoalPhase.INACTIVE, gm.phase());   // 无状态变更
    }

    @Test
    void twoLevelEsc() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.pauseByEsc();                               // 第一级
        assertEquals(GoalPhase.PAUSED, gm.phase());
        assertEquals(PauseReason.ESC, gm.snapshot().pauseReason());
        gm.pauseByEsc();                               // 第二级
        assertEquals(GoalPhase.CANCELLED, gm.phase());
    }

    @Test
    void pausedResumesOnUserDispatch() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.activate("g");
        gm.pause(PauseReason.STALLED);
        gm.onUserDispatch();
        assertEquals(GoalPhase.RUNNING, gm.phase());
        assertTrue(gm.hasAutoTurnPending());           // 用户消息触发的轮结束后由 goal 槽接续
    }

    @Test
    void terminalStatesAreMonotonic() {
        for (GoalPhase t : List.of(GoalPhase.SATISFIED, GoalPhase.IMPOSSIBLE,
                GoalPhase.MAX_TURNS, GoalPhase.BUDGET_EXCEEDED, GoalPhase.CANCELLED, GoalPhase.CLEARED)) {
            GoalManager gm = new GoalManager(cfg(), null);
            gm.activate("g");
            gm.terminate(t);
            gm.pauseByEsc();                            // 一切迟到事件 no-op
            gm.onUserDispatch();
            gm.onVerdict(gm.currentEpoch(), new GoalVerdict(
                    GoalVerdict.Outcome.UNSATISFIED, "r", false, null, "raw"));
            assertEquals(t, gm.phase());
        }
    }

    @Test
    void uiChangePublishedOutsideLockExactlyOnce() {   // 照 InterjectionsNotificationTest 模式
        GoalManager gm = new GoalManager(cfg(), null);
        List<Integer> bits = new CopyOnWriteArrayList<>();
        AtomicLong versions = new AtomicLong();
        gm.setUiChangeListener(b -> { bits.add(b); });   // listener 内读锁内快照探针可后续加
        long before = gm.uiVersion();
        gm.activate("g");
        assertEquals(before + 1, gm.uiVersion());       // 恰 +1
        gm.activate("g2");                              // no-op 版本纪律：activate 总是有效变化
        assertEquals(before + 2, gm.uiVersion());
        assertFalse(bits.isEmpty());
    }

    @Test
    void clearFromAnyPhase() {
        GoalManager gm = new GoalManager(cfg(), null);
        gm.clear("stop");                               // INACTIVE no-op
        assertEquals(GoalPhase.INACTIVE, gm.phase());
        gm.activate("g");
        gm.clear("stop");
        assertEquals(GoalPhase.CLEARED, gm.phase());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
mvn -pl springai-code-tui -am test -Dtest=GoalManagerStateTest -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 实现 GoalManager 骨架（本 Task 只做状态机，熔断计数留 Task 4 钩位）**

照 `Interjections`（L59-110）锁纪律。字段与骨架：

```java
public final class GoalManager implements UiChangeSource {
    private static final Logger log = LoggerFactory.getLogger(GoalManager.class);

    private final GoalConfig config;
    private final TokenUsageAccumulator usage;   // 可 null

    private volatile UiChangeListener uiChangeListener = UiChangeListener.noop();
    private final AtomicLong uiVersion = new AtomicLong();
    private final AtomicLong goalIds = new AtomicLong();

    // 以下全部在 synchronized(this) 内读写
    private GoalPhase phase = GoalPhase.INACTIVE;
    private PauseReason pauseReason;
    private String condition;
    private long epoch;                       // 当前 goal 的代
    private int turnsUsed;
    private long basePromptTokens, baseCompletionTokens;   // activate 快照
    private int stalledStreak, errorStreak, evalFailures, protocolFailures;
    private boolean evalInFlight;
    private boolean autoTurnPending;
    private Long gapDeadlineEpochMs;
    private Instant activatedAt;
    private final Deque<GoalTurnRecord> history = new ArrayDeque<>();   // ≤8，Task 5 用
    private String stateLedger = "";

    public void activate(String condition) {
        if (condition == null || condition.isBlank()) throw new IllegalArgumentException("goal 条件为空");
        long version;
        synchronized (this) {
            if (condition.length() > 4000) condition = condition.substring(0, 4000);
            this.condition = condition;
            this.epoch = goalIds.incrementAndGet();
            this.phase = GoalPhase.RUNNING;
            this.pauseReason = null;
            this.turnsUsed = 0;
            this.stalledStreak = this.errorStreak = this.evalFailures = this.protocolFailures = 0;
            var snap = usage == null ? null : usage.snapshot();
            this.basePromptTokens = snap == null ? 0 : snap.promptTokens();
            this.baseCompletionTokens = snap == null ? 0 : snap.completionTokens();
            this.autoTurnPending = true;
            this.gapDeadlineEpochMs = null;
            this.evalInFlight = false;        // 旧 goal 在途评估按 epoch 丢弃
            this.activatedAt = Instant.now();
            this.history.clear();
            this.stateLedger = "";
            version = changed();
        }
        publish(version);
        log.info("goal 已激活（epoch={}，maxTurns={}，预算={}）：{}", epoch, config.maxTurns(), config.tokenBudget(), condition);
    }
    // pauseByEsc / pause / terminate / clear / onUserDispatch / hasAutoTurnPending /
    // takeAutoTurn / gapDeadlineEpochMs / currentEpoch / phase / snapshot 同锁纪律展开
    // takeAutoTurn 内：synchronized 判 phase==RUNNING && autoTurnPending；
    //   预算决策点复检 budgetExceeded() → terminate(BUDGET_EXCEEDED) 返回 null；
    //   turnsUsed+1 > maxTurns → terminate(MAX_TURNS) 返回 null；
    //   否则清 pending/deadline，返回 buildAutoTurnPromptLocked()。
}
```

`GoalStateSnapshot` record：`phase, pauseReason, condition, turnsUsed, maxTurns, tokenSpent, tokenBudget, stalledStreak, activatedAt, List<GoalEvalTrace> recentTraces, String lastSummary`（面板/状态栏共用一次快照，避免两次锁读撕裂）。

- [ ] **Step 4: 跑测试确认通过**（同 Step 2 命令）

- [ ] **Step 5: Commit**

```
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/goal/ springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/goal/GoalManagerStateTest.java
git commit -m "feat(code-tui): GoalManager 状态机——epoch 防陈旧/终态单调/两级 Esc/锁外 publish"
```

---

### Task 4: GoalManager 熔断矩阵与预算

**Files:**
- Modify: `CT/agent/goal/GoalManager.java`
- Test: `CTT/agent/goal/GoalManagerFuseTest.java`

**Interfaces:**
- Consumes: `TokenUsageAccumulator.snapshot()`（执行前先读 `CT/agent/session/TokenUsageAccumulator.java` L34-71 确认 `Snapshot` 访问器名，本计划按 `promptTokens()/completionTokens()` 书写）。
- Produces（View/Runner 依赖）:
  - `long beginEvaluation()`——CAS 置在飞：锁内 `phase==RUNNING && !evalInFlight` 时置位返回 epoch，否则 -1。
  - `void endEvaluation(long epoch)`——finally 复位（epoch 不符也复位自己那代）。
  - `void onVerdict(long epoch, GoalVerdict v)`——epoch 不符丢弃；SATISFIED/IMPOSSIBLE→终态；UNSATISFIED→记录+置 gapDeadline（turnGapSeconds>0）或 autoTurnPending；stalled streak 更新。
  - `void onEvaluationFailure(long epoch, Throwable t)`——EVALUATOR 计数（超时同归此）。
  - `void onProtocolFailure(long epoch, String rawOutput)`——PROTOCOL 计数（先按 UNSATISFIED+stalled 记一轮）。
  - `void onTurnError(Throwable rootCause)`——`EmptyStreamException` 根因不计（交评估器判 stalled）；CancellationException 不计（Esc 取消）；其余 errorStreak+1，达 `errorRetry+1` 连续 → PAUSED(ERROR)。
  - `void onTurnCompleted()`——errorStreak 清零（成功重置）。
  - `boolean budgetExceeded()`。

- [ ] **Step 1: 写熔断失败测试（矩阵穷举）**

测试要点（每个用例独立 `GoalManager`，config 用 `new GoalConfig(2, 2, 1000L, 0, 1, 2, 3, 60, "")` 之类小值；`TokenUsageAccumulator` 用真实例 + `record(Usage)` 喂数——`Usage` 是 Spring AI 接口，写 4 字段 record 桩实现）：

```java
class GoalManagerFuseTest {
    // Usage 桩：
    record FakeUsage(long prompt, long completion) implements Usage {}

    @Test void maxTurnsOnlyCountsAutoTurns() {
        // cfg.maxTurns=2：takeAutoTurn() 两次成功返回 prompt；第三次返回 null 且 phase=MAX_TURNS
        // onUserDispatch() 触发的轮（View 侧 dispatch 前调）不调 takeAutoTurn → 不烧配额（口径由集成测试④钉，单测钉 takeAutoTurn 语义）
    }
    @Test void stalledStreakFromVerdictAndReset() {
        // onVerdict UNSATISFIED+stalled=true ×stalledLimit → PAUSED(STALLED)
        // advancing 重置；onUserDispatch 重置
    }
    @Test void budgetDeltaAndSoftExceeded() {
        // activate 前喂 usage 5000；activate 后再喂 6000 → budgetExceeded()（5000+6000-基线5000=6000≥1000? 配 budget=6000）
        // budget=0 → 永不 exceeded
    }
    @Test void errorStreakAndEmptyStreamExempt() {
        // onTurnError(new RuntimeException(...)) errorRetry+1 次 → PAUSED(ERROR)
        // 根因 EmptyStreamException 不计数；CancellationException 不计数；onTurnCompleted 清零
    }
    @Test void evalAndProtocolFailuresIndependent() {
        // onEvaluationFailure ×evalFailLimit → PAUSED(EVALUATOR)，protocolFailures 不受影响
        // onProtocolFailure 单次 → 相当于 UNSATISFIED+stalled 入账；连续 protocolFailLimit → PAUSED(PROTOCOL)
    }
    @Test void priorityWhenMultipleTrue() {
        // 同时预算超限+轮数耗尽：决策点先判预算 → BUDGET_EXCEEDED（spec §7 优先级 CANCEL/CLEAR > BUDGET > MAX_TURNS > …）
        // terminate(CANCELLED) 优先于一切迟到判定
    }
    @Test void beginEndEvaluationCasPairing() {
        // beginEvaluation 两次：第二次 -1（在飞）；endEvaluation 后可再 begin
        // epoch 变更后 begin 返回新 epoch
    }
}
```
（执行时把注释展开成真实断言——每条注释即一个断言组，禁止留 TODO。）

- [ ] **Step 2: 跑测试确认失败**

```
mvn -pl springai-code-tui -am test -Dtest=GoalManagerFuseTest -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 实现熔断逻辑**

要点（全部在既有锁内改、锁外 publish 的方法体内展开）：
- `onVerdict`：`synchronized` 内先 `if (epoch != this.epoch || phase.isTerminal()) return;`；SATISFIED/IMPOSSIBLE → `terminateLocked(...)` + `lastSummary = verdict.reason()`；UNSATISFIED → `stalledStreak = v.stalled() ? +1 : 0`、达 limit → `pauseLocked(STALLED)`；否则 `gapDeadlineEpochMs = turnGapSeconds>0 ? now+gap*1000 : null`、`autoTurnPending = turnGapSeconds==0`。
- `onTurnError`：终态/`epoch 过期`不动作；`unwrapRoot(err) instanceof EmptyStreamException` 或 `CancellationException` → return；`errorStreak++ >= errorRetry+1` → PAUSED(ERROR)。
- `budgetExceeded()`：`config.tokenBudget()==0 || usage==null` → false；`spent = (p-baseP)+(c-baseC) >= budget`。
- `takeAutoTurn` 决策点顺序：`phase!=RUNNING → null`；`budgetExceeded() → terminate(BUDGET_EXCEEDED)`；`turnsUsed+1 > maxTurns → terminate(MAX_TURNS)`；其余放行（**软超限口径**：在飞轮放行到轮末，判定只在决策点）。
- `onProtocolFailure`：`protocolFailures++`；同时按 UNSATISFIED+stalled 入滚动记录（不额外烧轮）；达 limit → PAUSED(PROTOCOL)，`lastSummary` 附原始输出摘要。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**（`feat(code-tui): goal 三重熔断+评估 CAS——轮数/停滞/预算/onError 家族独立计数`）

---

### Task 5: 滚动记录与两份 prompt 文案

**Files:**
- Create: `CT/agent/goal/GoalTurnRecord.java`、`CT/agent/goal/GoalTurnMaterial.java`、`CT/agent/goal/EvaluationInput.java`
- Modify: `CT/agent/goal/GoalManager.java`
- Test: `CTT/agent/goal/GoalManagerPromptTest.java`

**Interfaces:**
- Produces:
  - `record GoalTurnRecord(int turn, String evalReason, String assistantTail, int toolCallCount, String userInterjection)``
  - `record GoalTurnMaterial(String assistantTail, int toolCallCount, String userInterjection)`（CodingAgent 收集，Task 9）
  - `record EvaluationInput(String condition, int turn, int maxTurns, int stalledStreak, String stateLedger, List<GoalTurnRecord> recentTurns)`
  - `GoalManager.recordTurnMaterial(GoalTurnMaterial m)`——评估启动前调用（View 空闲批）；滚动 append ≤8。
  - `GoalManager.buildEvaluationInput()` → `EvaluationInput`（锁内快照）。
  - `GoalManager.buildAutoTurnPrompt()`——spec §4.1 文案；语言跟随条件（含 CJK → 中文模板，否则英文模板）。

- [ ] **Step 1: 写失败测试**

```java
class GoalManagerPromptTest {
    @Test void autoTurnPromptChineseCondition() {
        GoalManager gm = new GoalManager(new GoalConfig(25, 3, 0, 0, 2, 2, 3, 60, ""), null);
        gm.activate("迁移 AuthService，mvn -pl server test 退出码 0");
        String p = gm.takeAutoTurn();        // turnsUsed=1
        assertTrue(p.startsWith("[goal 继续 1/25]"));
        assertTrue(p.contains("目标：迁移 AuthService，mvn -pl server test 退出码 0"));
        assertTrue(p.contains("累积进度："));
        assertTrue(p.contains("不要重复已完成的工作"));
        assertTrue(GoalText.isContinueMessage(p));
    }
    @Test void autoTurnPromptEnglishConditionUsesEnglishScaffold() {
        gm.activate("migrate AuthService, tests green");
        // 英文模板：p.contains("Goal:") && p.contains("Do not repeat completed work")
    }
    @Test void promptCarriesLastVerdictAndLedger() {
        // onVerdict(UNSATISFIED, reason="还有 4 个调用点", ledger="✓A ✗B") → 下一 prompt 含
        // "评估器结论（上一轮）：还有 4 个调用点" 与 "累积进度：✓A ✗B"
    }
    @Test void rollingHistoryCapsAt8AndCarriesInterjection() {
        // 9 次 recordTurnMaterial → buildEvaluationInput().recentTurns().size()==8，最旧被挤掉
        // 记录含 userInterjection 原文（用户纠偏对评估器可见）
    }
    @Test void assistantTailTruncatedAt2000() { /* 3000 字符素材 → 记录内 tail ≤2000 且代理对安全 */ }
}
```

- [ ] **Step 2: 跑测试确认失败**（`-Dtest=GoalManagerPromptTest`）

- [ ] **Step 3: 实现**

`buildAutoTurnPromptLocked()` 中文模板（英文版对照翻译，`condition` 含 `[\u4e00-\u9fff]` 判 CJK）：

```
[goal 继续 N/M]
目标：<condition>
评估器结论（上一轮）：<上一轮 reason，首轮为"（首轮）">
累积进度：<stateLedger，空则为"（尚无）">

请继续推进。本轮结束时必须给出可复验的审计证据：执行过的命令与退出码、变更的文件列表；
若认为目标已达成，请附上验证命令的原始输出。不要重复已完成的工作。
```
`recordTurnMaterial`：锁内 `history.addLast(new GoalTurnRecord(turnsUsed, lastEvalReason, GoalText.tail(m.assistantTail(), 2000), m.toolCallCount(), m.userInterjection()))`，超 8 `pollFirst`。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**（`feat(code-tui): goal 滚动记录+自动轮/评估双 prompt 文案——语言跟随条件`）

---

### Task 6: GoalEvaluator + 裸 client 实现 + 守卫测试

**Files:**
- Create: `CT/agent/goal/GoalEvaluator.java`（接口）、`CT/agent/goal/ChatClientGoalEvaluator.java`
- Test: `CTT/agent/goal/ChatClientGoalEvaluatorTest.java`、`CTT/agent/goal/GoalEvaluatorGuardTest.java`

**Interfaces:**
- Consumes: `ProviderRegistry.requestSelection(p, m)`（`CT/agent/llm/ProviderRegistry.java` L104-110）、`DynamicAuxChatModel`、`ChatClient.builder(model).build()`（AgentTools L395 裸先例）、`GoalVerdict.parse`。
- Produces:
  - `interface GoalEvaluator { GoalVerdict evaluate(EvaluationInput input) throws Exception; }`
  - `static GoalEvaluator create(ProviderRegistry registry, GoalConfig config)`（工厂；`evaluatorModel` 空→aux 回退；`IllegalArgumentException`→aux 回退 + 显式 warn 日志）。
  - 调用形态：`.prompt().system(SYSTEM).user(rendered).options(ChatOptions.builder().maxTokens(512).build()).call().content()` → `GoalVerdict.parse(content)`。

- [ ] **Step 1: 写失败测试（用 RecordingChatModel 桩照 DynamicAuxChatModelTest L23-75）**

```java
class GoalEvaluatorGuardTest {
    static final class RecordingChatModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatResponse next = response("VERDICT: SATISFIED\nREASON: done\nPROGRESS: advancing\nSTATE: ✓all");
        @Override public ChatResponse call(Prompt p) { prompts.add(p); return next; }
        @Override public Flux<ChatResponse> stream(Prompt p) { throw new UnsupportedOperationException(); }
    }

    @Test void requestHasNoToolsNoMemorySingleTurn() {
        RecordingChatModel rec = new RecordingChatModel();
        // registry 桩：requestSelection 返回 () -> rec 的 provider（FakeProvider 照 DynamicAuxChatModelTest）
        GoalEvaluator ev = new ChatClientGoalEvaluator(ChatClient.builder(rec).build(), systemPrompt());
        GoalVerdict v = ev.evaluate(sampleInput());
        assertEquals(GoalVerdict.Outcome.SATISFIED, v.outcome());
        Prompt sent = rec.prompts.get(0);
        assertEquals(2, sent.getInstructions().size());           // 恰 system+user 两条，无 advisor 注入
        assertNull(sent.getToolCallbacks());                       // 或按 Spring AI 版本断言无 toolCallbacks
        assertTrue(sent.getInstructions().get(1).getText().contains("目标条件："));
    }
    @Test void fallbackToAuxWhenModelUnknown() {
        // registry.requestSelection("x","y") 抛 IllegalArgumentException → create 返回 aux 底座
        // 断言实现类底层 ChatModel 恰是 DynamicAuxChatModel（照 AuxClientNotRetryWrappedTest「恰好是该类」法）
    }
    @Test void parseFailureSurfacesAsProtocolException() {
        rec.next = response("模型语无伦次没有标记行");
        assertThrows(GoalVerdict.GoalProtocolException.class, () -> ev.evaluate(sampleInput()));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

`SYSTEM_PROMPT`（常量，few-shot 防中文小模型输出"满足/未满足"）：

```
你是目标验收评估器。只依据给定的滚动记录判断目标是否达成；不假设、不要求执行任何命令。
严格按以下协议输出四个标记行（标记为行首顶格的大写英文）：
VERDICT: SATISFIED | UNSATISFIED | IMPOSSIBLE
REASON: <一句话结论，语言与目标条件一致>
PROGRESS: advancing | stalled
STATE: <压缩的累积进度账本，checklist 风格，单行>
输出示例：
VERDICT: UNSATISFIED
REASON: server 模块还有 4 个调用点未迁移。
PROGRESS: advancing
STATE: ✓UserClient 接口替换 ✗TokenRefresh 调用点 ✗mvn test 全绿
除协议行外不要输出任何内容。
```

`renderUser(EvaluationInput in)`：`目标条件：…\n轮次：N/M\n连续停滞轮数：…\n上一轮 STATE 账本：…\n最近 K 轮记录：\n--- 轮 X ---\n评估结论：…\nagent 末文本（尾部 ≤2000）：…\n本轮工具调用数：…\n用户插话：…（无则"（无）"）`。

- [ ] **Step 4: 跑测试确认通过**（`-Dtest='ChatClientGoalEvaluatorTest,GoalEvaluatorGuardTest'`）

- [ ] **Step 5: Commit**（`feat(code-tui): goal 评估器——裸 client/aux 回退/协议解析直通`）

---

### Task 7: GoalEvaluationRunner——daemon 线程 + 60s 超时

**Files:**
- Create: `CT/agent/goal/GoalEvaluationRunner.java`
- Test: `CTT/agent/goal/GoalEvaluationRunnerTest.java`

**Interfaces:**
- Produces:
  - `GoalEvaluationRunner(GoalConfig config)`；`AutoCloseable`（shutdown executor）。
  - `void submit(long epoch, GoalEvaluator evaluator, EvaluationInput input, Consumer<GoalVerdict> onSuccess, Consumer<Throwable> onFailure)`——回调在 executor 线程触发（线程安全，GoalManager 回调自身线程安全；UI 侧经 UiChangeSource publish，不直接碰 View）。
  - 线程命名 `goal-evaluator`、daemon、单线程池。

- [ ] **Step 1: 写失败测试**

```java
class GoalEvaluationRunnerTest {
    @Test void successDeliversVerdict() throws Exception {
        try (GoalEvaluationRunner r = new GoalEvaluationRunner(testCfg(1))) {
            CountDownLatch ok = new CountDownLatch(1);
            AtomicReference<GoalVerdict> got = new AtomicReference<>();
            r.submit(1, in -> new GoalVerdict(SATISFIED, "r", false, null, "raw"), sampleInput(), v -> { got.set(v); ok.countDown(); }, x -> {});
            assertTrue(ok.await(2, TimeUnit.SECONDS));
            assertEquals(SATISFIED, got.get().outcome());
        }
    }
    @Test void timeoutCountsAsCallFailure() throws Exception {
        // cfg.evalTimeoutSeconds=5 下限钳制，测试用 1？——钳制 MIN=5 会挡：改用可注入 timeoutMs 的包私有构造
        // GoalEvaluationRunner(GoalConfig cfg, long timeoutMsForTest)
        // evaluator 内 sleep 5000，timeoutMs=50 → onFailure 收到 TimeoutException
    }
    @Test void evaluatorExceptionDelivered() { /* 立抛 → onFailure 原样 */ }
    @Test void threadIsDaemonAndNamed() { /* 捕获线程名断言 "goal-evaluator" 前缀 + isDaemon */ }
}
```
注意钳制冲突：`GoalConfig` MIN_EVAL_TIMEOUT=5s，测试超时注入走包私有第二构造（`timeoutMs` 直通），生产构造从 config 换算毫秒。

- [ ] **Step 2-4: 失败→实现→通过**

实现核心：`CompletableFuture.supplyAsync(() -> evaluator.evaluate(input), exec).orTimeout(timeoutMs, MS).whenComplete((v, ex) -> { if (ex != null) onFailure.accept(unwrap(ex)); else onSuccess.accept(v); })`。

- [ ] **Step 5: Commit**（`feat(code-tui): goal 评估调度——单线程 daemon+orTimeout 超时归类调用异常`）

---

### Task 8: ExpectedLog 入模块 + 全链装配

**Files:**
- Create: `CTT/testutil/ExpectedLog.java`（从 `springai-tamboui-inline-patch/src/test/java/dev/tamboui/testutil/ExpectedLog.java` 原样复制，包名改 `io.github.javaside.springai.codetui.testutil`）
- Modify: `CT/agent/AgentTools.java`（`AgentRuntime` record 加 `GoalManager goalManager` 组件 + `build` 加第 7 参 + 构建尾部装配）
- Modify: `CT/CodeTuiApplication.java`（构造 GoalManager/Runner/Evaluator + 传递 + 关闭钩子）
- Test: `CTT/agent/AgentRuntimeTest.java`（扩展）、`CTT/agent/goal/GoalWiringTest.java`

**Interfaces:**
- Consumes: Task 3-7 全部；`AgentRuntime`（AgentTools L966-988，19 分量 → 22 分量）；`build`（L368-370 6 参 → 7 参，默认重载 L352-355 补 `GoalConfig.fromEnv()` 派生）。
- Produces: `AgentRuntime.goalManager()`；CodeTuiApplication 持有 `GoalEvaluationRunner`（app 生命周期 close）。

- [ ] **Step 1: 复制 ExpectedLog 并写装配失败测试**

`GoalWiringTest`（照 `AgentRuntimeTest` L22-33 离线装配模式）：

```java
class GoalWiringTest {
    @Test void buildAssemblesGoalComponentsOffline() throws Exception {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        GoalManager gm = new GoalManager(GoalConfig.from(name -> null), null);
        AgentTools.AgentRuntime rt = AgentTools.build(registry, tmp, listener, mcpRegistry, permissionEngine,
                StreamRetryConfig.fromEnv(), gm);
        assertNotNull(rt.goalManager());
        assertSame(gm, rt.goalManager());
    }
    @Test void auxFallbackLoggedExplicitly() {
        // evaluatorModel="ghost:ghost"（registry 无此模型）→ ExpectedLog.capture(ChatClientGoalEvaluator.class)
        // awaitRecord(Level.WARN, "回退 aux", 1, SECONDS)
        // 产物 GoalEvaluator 底层恰为 DynamicAuxChatModel
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现装配**

- `AgentTools.build` 尾部（L722 `return new AgentRuntime(...)` 前）无新构建（GoalManager 由外部传入）；record 加组件 `GoalManager goalManager`（javadoc 更新 L938-965 区段，注明"goal 循环状态机，与 View goal 槽/CodingAgent.bindGoal 共享同一实例，另建一个等于 UI 永远看不到真相"——照 interjections 字段先例措辞）。
- `CodeTuiApplication`：`TokenUsageAccumulator usageAccumulator`（L67 已存在）之后：

```java
GoalConfig goalConfig = GoalConfig.fromEnv();
GoalManager goalManager = new GoalManager(goalConfig, usageAccumulator);
GoalEvaluationRunner goalRunner = new GoalEvaluationRunner(goalConfig);
```
`build(..., goalManager)` 传入；`view` 构造后（见 Task 10 改造后的签名）接线；app 退出路径 `goalRunner.close()`。

- [ ] **Step 4: 跑 `GoalWiringTest` + 既有 `AgentRuntimeTest` 全绿**（record 加组件会迫使全参构造调用点更新——只有 build 一处，兼容重载不受影响）

- [ ] **Step 5: Commit**（`feat(code-tui): goal 装配进 AgentRuntime——ExpectedLog 入模块+aux 回退日志断言`）

---

### Task 9: CodingAgent 接线——两段式 bindGoal

**Files:**
- Modify: `CT/agent/CodingAgent.java`（加字段 + `bindGoal` + `handleError` 通知 + `clearContext` 清 goal + `collectGoalMaterial`）
- Modify: `CT/agent/seam/SubmitHandler.java`（default 方法）
- Test: `CTT/agent/CodingAgentGoalTest.java`

**Interfaces:**
- Consumes: `GoalManager`（Task 3-5）；`sessionService.getEvents(sid)`（oldest-first）；`InterjectionText.OPEN` 前缀过滤（HistoryReplay L118-126 先例）；`EmptyStreamException`（`CT/agent/llm/`，RetryingStreamChatModel 抛出）。
- Produces:
  - `CodingAgent.bindGoal(GoalManager goalManager, GoalEvaluationRunner runner, GoalEvaluator evaluator)`——包内两段式（照 `AgentTools.wireL1` L1000-1003 先例），内部 `goalManager.bindSession(sessionService, () -> this.sessionId)`（lambda 在 agent 包内可访问包私有 `sessionId()` L1217）。
  - `SubmitHandler` 加 default：`default GoalTurnMaterial collectGoalMaterial() { return null; }` + `default GoalManager goal() { return null; }`（View 经 `onSubmit.goal()` 拿 manager——CodeTuiView 不新增构造参数，测试桩零改动）。
  - `handleError`（L1338-1343）加 `goalManager.onTurnError(rootCauseOf(err))`；`handleComplete`（L1345-1352）加 `goalManager.onTurnCompleted()`（均 null 守卫）。
  - `clearContext`（L1124-1138）加 `if (goalManager != null) goalManager.clear("clear-context");`。

- [ ] **Step 1: 写失败测试**

```java
class CodingAgentGoalTest {
    @Test void clearContextClearsGoal() { /* bind fake goal → clearContext() → phase=CLEARED */ }
    @Test void handleErrorNotifiesGoalAndEmptyStreamExempt() {
        // 直接调包内 handleError(err, turnId)（测试同包可及）：
        // RuntimeException → fake 收到；EmptyStreamException → 不计（fake 记录计数差 0）
        // CancellationException 包装 → 不计
    }
    @Test void collectGoalMaterialScansSessionTail() {
        // 造 session：UserMessage(正常) → AssistantMessage(toolCalls×2) → ToolResponse×2
        //   → AssistantMessage("最终回答文本...") → UserMessage(InterjectionText.wrap("快点收尾"))
        // collectGoalMaterial() → assistantTail 尾部文本、toolCallCount=2、userInterjection="快点收尾"
    }
    @Test void collectGoalMaterialSkipsTrailingInterjectionsOnly() {
        // 尾部无插话 → userInterjection null；插话后无 AssistantMessage → assistantTail 取再前一条
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

`collectGoalMaterial()`（照 `lastUserHasResumeNotice` L713-722 倒扫形状）：

```java
@Override public GoalTurnMaterial collectGoalMaterial() {
    List<SessionEvent> events = sessionService.getEvents(sessionId);
    String assistantTail = null; int tools = 0; String interjection = null;
    for (int i = events.size() - 1; i >= 0; i--) {
        Message m = events.get(i).getMessage();
        if (m instanceof UserMessage um) {
            String t = um.getText() == null ? "" : um.getText();
            if (t.startsWith(InterjectionText.OPEN)) {
                if (interjection == null) interjection = InterjectionText.unwrap(t);
                continue;                                   // 跳过尾部插话
            }
            break;                                          // 最近一条真实用户消息 = 本轮边界
        }
        if (m instanceof ToolResponseMessage trm) { tools += trm.getResponses().size(); continue; }
        if (m instanceof AssistantMessage am && assistantTail == null) {
            assistantTail = am.getText() == null ? "" : am.getText();
        }
    }
    return new GoalTurnMaterial(assistantTail == null ? "" : assistantTail, tools, interjection);
}
```

- [ ] **Step 4: 跑测试 + 既有 CodingAgent 全量测试确认无回归**

```
mvn -pl springai-code-tui -am test -Dtest='CodingAgent*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: Commit**（`feat(code-tui): CodingAgent 接线 goal——两段式 bind/onError 通知/素材倒扫/clear 联动`）

---

### Task 10: CodeTuiView 命令面 + 状态栏 + 面板

**Files:**
- Modify: `CT/ui/CodeTuiView.java`
- Test: `CTT/ui/CodeTuiViewGoalCommandTest.java`

**Interfaces:**
- Consumes: `onSubmit.goal()`（Task 9）；View 既有：`COMMANDS`（L357-375）、`submitInput` if 链（L2379-2585，`/continue` 分支 L2485-2503 同构）、`state.pushInfo`、`withLeading`/`modeTag`（L4335-4350）、`state.status()` 状态栏（L4157-4232）、`permissionMode`。
- Produces: `handleGoalCommand(String rest)` 私有方法、`goalLeadingSpan()` 私有方法（状态栏各态拼 leading）、`printGoalPanel(GoalStateSnapshot s)` 私有方法。

- [ ] **Step 1: 写失败测试**

```java
class CodeTuiViewGoalCommandTest {
    // View 测试构造照既有四参版（+testSink）；onSubmit 用桩实现 SubmitHandler 并覆写 goal()
    @Test void goalWithConditionActivates() {
        // submitInput("/goal 迁移完成且测试全绿") → fake GoalManager.phase()==RUNNING、activate 被调
        // 输出含"◎ 已设定目标"提示；DEFAULT 权限档 → 输出含"建议 Shift+Tab"
    }
    @Test void blankConditionRejectedWithNotice() { /* "/goal    " → notice 提示 + phase 不变 */ }
    @Test void goalAlonePrintsPanel() {
        /* activate + 喂 snapshot（RUNNING, N=3/M=25, token…, 轨迹 2 条）→ "/goal" →
           pushInfo 行含：条件原文、"RUNNING"、"3/25"、stalled、轨迹行"UNSATISFIED — reason" */
    }
    @Test void goalStopClearsAndEchoes() { /* "/goal stop" 与 "/goal clear" 都 → CLEARED + 回显被清条件 */ }
    @Test void terminalReportSurvivesUntilNextGoal() { /* 终态后 "/goal" 面板仍显示该次报告 */ }
    @Test void statusLineLeadingSpan() {
        /* snapshot 分别 RUNNING(2/25)/评估中/倒计时/PAUSED → goalLeadingSpan() 文本
           "◎ goal 2/25" / "◎ goal 评估中" / "◎ goal ⏳3s"（deadline 现算）/ "◎ goal 已暂停"；INACTIVE → null */
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

- `COMMANDS` 加 `new SlashCommand("/goal", "设定/查看目标循环（/goal stop 清除）")`。
- `submitInput` 加分支（放 `/continue` 分支旁）：

```java
if (cmd.equals("/goal")) {
    clearInput();
    handleGoalCommand(rawInputText().substring(cmd.length()).strip());   // 余文
    return;
}
```
`handleGoalCommand`：
```java
private void handleGoalCommand(String rest) {
    GoalManager gm = onSubmit.goal();
    if (gm == null) { state.setNotice("goal 未启用"); return; }
    if (rest.isEmpty()) { printGoalPanel(gm.snapshot()); return; }
    if (rest.equals("clear") || rest.equals("stop")) {
        GoalStateSnapshot before = gm.snapshot();
        if (before.phase() == GoalPhase.INACTIVE) { state.setNotice("没有进行中的 goal"); return; }
        gm.clear("stop");
        state.pushInfo("◎ goal 已清除：" + before.condition());
        return;
    }
    if (state.permissionMode() == PermissionMode.DEFAULT) {
        state.pushInfo("（提示：当前权限档位为 DEFAULT，循环会停下等批准；建议 Shift+Tab 切到 ACCEPT_EDITS/BYPASS。）");
    }
    gm.activate(rest);                     // 状态立即立起；首轮由空闲批 goal 槽发出（忙碌时同样成立）
    state.pushInfo("◎ goal 已设定（" + gm.snapshot().turnsUsed() + "/" + gm.snapshot().maxTurns() + "）：" + rest);
}
```
- `statusLine()`：在既有 modeTag 拼接点前取 `goalLeadingSpan()`（`snapshot()` 一次读齐），照 `withLeading(leading, ...)` 先例插入；倒计时态文本 `state.formatQuotaRemaining(deadline - now)`（State L1390-400 既有静态，复用）。
- `printGoalPanel`：`state.pushInfo` 逐行——条件 / 状态（RUNNING|PAUSED(原因)|终态+N/M） / token used/budget / stalled streak / 运行时长（`Duration.between(activatedAt, now)` 人读化）/ 最近 8 条轨迹（`recentTraces` 每行 `N. VERDICT — reason`）。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: Commit**（`feat(code-tui): /goal 命令面+面板+状态栏 leading 指示`）

---

### Task 11: 空闲批 goal 槽 + 评估调度 + 倒计时 + 两级 Esc

**Files:**
- Modify: `CT/ui/CodeTuiView.java`
- Test: `CTT/ui/CodeTuiViewGoalSlotTest.java`

**Interfaces:**
- Consumes: `processUpdatesInsideBatch`（L901-1003，goal 槽插在 `state.pollQueued()` 出队之后、`deliverBackgroundResults()` 之前）；`busy()`（L2601）；输入框 Esc 分支（L2078-2106）与 `cancelTurnFor`（L3256-3262）；`animationDemandActive()`（L1120-1124）；`onSubmit.collectGoalMaterial()`（Task 9）；Runner/Evaluator（经 `onSubmit` 桩或测试直接注入 View 的包内字段）。
- Produces: 私有 `boolean goalSlotTick()`（本批 dispatch 了自动轮则 true）；私有 `void goalOnEsc()`（统一两级 Esc 挂点，输入框 Esc 与 `cancelTurnFor` 都调它）。

- [ ] **Step 1: 写失败测试（用可放行 fake evaluator：CompletableFuture 手动 complete）**

```java
class CodeTuiViewGoalSlotTest {
    // 装置：fake GoalManager（真 GoalManager 也行）+ fake evaluator 持 CompletableFuture<GoalVerdict>
    //       View 测试态：pendingUiUpdatesForTest 队列手动 drain 模拟空闲批（既有测试同法）
    @Test void idleBatchDispatchesPendingAutoTurn() {
        // gm.activate("...")（gap=0）→ drain 一批 → onSubmit.submit() 收到以 "[goal 继续 1/25]" 开头的文本
        // 且本批无第二个 dispatch（后台结果让路）
    }
    @Test void queuedUserInputBeatsGoalSlot() {
        // state.enqueue("用户先说") + autoTurnPending → drain 一批 → 只 submit 用户文本；下一批才轮到 goal
    }
    @Test void evaluationStartedAfterTurnEndAndVerdictSchedulesNextTurn() {
        // 模拟 onTurnComplete（state.onTurnComplete(turnId)）→ drain 一批 → evaluator 收到 EvaluationInput
        //   （含素材：assistantTail/toolCallCount/插话）→ 手动 complete(UNSATISFIED, stalled=false)
        //   → publish 到 UI → drain 一批 → 自动轮 2 dispatch（prompt 含评估器结论）
    }
    @Test void staleVerdictDroppedOnNewGoal() {
        // 评估在飞时 activate 新条件 → 旧 future complete(SATISFIED) → 新 goal 仍 RUNNING（epoch 丢弃）
    }
    @Test void gapCountdownDelaysDispatch() {
        // gap=2s 配置：verdict 后 autoTurn 不立即发；deadline 现算文本 "◎ goal ⏳…"；
        // 虚拟推进（测试注入 clock 或等待真实 2s？——GoalManager gapDeadline 用包内可注入 Clock，测试拨表）
        // 到期后 drain 一批 → dispatch
    }
    @Test void twoLevelEscPausesThenCancels() {
        // 自动轮在飞（state 置 THINKING）→ Esc 键 → gm PAUSED(ESC)、notice "已暂停，发送任意消息继续"
        // 再 Esc → CANCELLED 终态
        // cancelTurnFor 路径（审批"中断本回合"）→ 同样 PAUSED(ESC)
    }
    @Test void pausedResumesOnUserMessage() {
        // PAUSED(STALLED) → dispatch 用户消息 → gm RUNNING；该轮结束 → goal 槽接续评估
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

goal 槽（`processUpdatesInsideBatch` 的 `!busy()` 段，插在 `pollQueued` dispatch-return 之后）：

```java
ConversationState.Queued next = state.pollQueued();
if (next != null) { releaseBrake(); dispatch(next.text(), next.skill()); return computeFollowUpFlags(); }
if (goalSlotTick()) return computeFollowUpFlags();   // ← goal 槽：评估启动或自动轮 dispatch
deliverBackgroundResults();
```

```java
private boolean goalSlotTick() {
    GoalManager gm = onSubmit.goal();
    if (gm == null) return false;
    if (gm.hasAutoTurnPending()) {
        Long deadline = gm.gapDeadlineEpochMs();
        if (deadline != null && System.currentTimeMillis() < deadline) return false;  // 倒计时中，动画帧再看
        String prompt = gm.takeAutoTurn();
        if (prompt == null) return false;             // 决策点熔断（终态已进），notice 由 manager publish 驱动
        dispatch(prompt, null);                       // UI 线程空闲批 dispatch——红线
        return true;
    }
    if (gm.phase() == GoalPhase.RUNNING && !gm.evaluationInFlight()) {
        long epoch = gm.beginEvaluation();
        if (epoch < 0) return false;
        try {
            GoalTurnMaterial m = onSubmit.collectGoalMaterial();
            gm.recordTurnMaterial(m);
            goalRunner.submit(epoch, goalEvaluator, gm.buildEvaluationInput(),
                    v -> gm.onVerdict(epoch, v),                      // executor 线程→锁内改锁外 publish
                    ex -> {
                        if (ex instanceof GoalVerdict.GoalProtocolException pe) gm.onProtocolFailure(epoch, pe.getMessage());
                        else gm.onEvaluationFailure(epoch, ex);
                    });
        } finally { /* begin/end 配对：submit 已异步化，endEvaluation 不在此处 */ }
        // 评估在飞不算"本批一个动作"（无 dispatch），可继续 deliverBackgroundResults
    }
    return false;
}
```
倒计时动画：`animationDemandActive()` 加 `|| goalCountdownActive()`（`gapDeadlineEpochMs()!=null && 未到期`），66ms 帧自然驱动 `statusLine` 现算。

两级 Esc：输入框 Esc 分支（L2078）现有取消逻辑前后插 `goalOnEsc()`；`cancelTurnFor`（L3256）尾部同调：

```java
private void goalOnEsc() {
    GoalManager gm = onSubmit.goal();
    if (gm == null) return;
    GoalPhase p = gm.phase();
    if (p == GoalPhase.RUNNING) gm.pauseByEsc();          // 第一级：含取消在飞轮语义（轮本身由既有路径取消）
    else if (p == GoalPhase.PAUSED && gm.snapshot().pauseReason() == PauseReason.ESC) gm.pauseByEsc();  // 第二级
}
```
（PAUSED(STALLED/ERROR/…) 中 Esc 不升级取消——spec §3.3 第二级仅针对 ESC 暂停；其余暂停态 Esc 保持既有输入框行为。）

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

```
mvn -pl springai-code-tui -am test
```

- [ ] **Step 5: Commit**（`feat(code-tui): goal 空闲槽+评估调度+倒计时+两级 Esc——UI 线程 dispatch 红线`）

---

### Task 12: 集成场景 ①–⑥

**Files:**
- Create: `CTT/ui/GoalIntegrationScenariosTest.java`（场景 ①–⑥；照 `CodeTuiViewEventWiringTest` 的 View 测试装置）

**Interfaces:**
- Consumes: Task 11 的全部装置（fake evaluator 手动放行 + `pendingUiUpdatesForTest` drain + state 事件驱动）。

- [ ] **Step 1: 写六个场景（spec §11 清单 ①–⑥）**

每个场景 = 一个 @Test，共用装置（`GoalIntegrationHarness` 静态内部类：建 View+fake agent+真 GoalManager+可控 evaluator+可拨 Clock）。断言要点：

1. `unsatTwiceThenSatisfiedLoop`：fake 依次放行 UNSATISFIED→UNSATISFIED→SATISFIED；drain 循环驱动；断言 submit 调用序列 3 轮、第 2/3 轮 prompt 含上轮评估结论、终态 SATISFIED + 一行式总结（notice/pushInfo 断言）。
2. `stalledBrakeAndResetWithInterleaving`：stalled,advancing,stalled,stalled（limit=3）→ 未暂停；第 4 个 stalled → PAUSED(STALLED)；交错序列中 advancing 重置；用户消息恢复后计数清零。
3. `firstTurnImpossible`：首个 verdict IMPOSSIBLE → 终态、无第 2 轮 submit。
4. `maxTurnsBoundaryAndUserTurnsFree`：maxTurns=2；UNSATISFIED×2 后第 3 次 takeAutoTurn null → MAX_TURNS；中间插入用户消息轮（submit 直接来自用户 enqueue）→ turnsUsed 不涨。
5. `budgetFirstSoftExceededAndZeroDisables`：小预算（真 TokenUsageAccumulator 喂 Usage 桩）；决策点判定 BUDGET_EXCEEDED；在飞轮放行到轮末（软超限：轮已 dispatch 后喂超限 usage，该轮 verdict 仍处理）；budget=0 永不触发；评估器调用计入同链（accumulator 喂数模拟评估器耗量）。
6. `onErrorFamily`：`handleError` 直调——普通异常 ×(errorRetry+1) → PAUSED(ERROR)；第 errorRetry 次后成功 `onTurnCompleted` 重置；Esc 取消（CancellationException）不计。

- [ ] **Step 2: 跑测试确认失败（先装置后逐场景）**

- [ ] **Step 3: 补齐实现缺口直到 6 场景全绿**（预期 Task 3-11 已覆盖逻辑，此处主要钉接线；发现缺口回补对应类并补单测）*

- [ ] **Step 4: 全模块回归** `mvn -pl springai-code-tui -am test`

- [ ] **Step 5: Commit**（`test(code-tui): goal 集成场景①-⑥——闭环/刹车/IMPOSSIBLE/轮数/预算/onError`）

---

### Task 13: 集成场景 ⑦–⑭ + HistoryReplay + 收尾

**Files:**
- Create: `CTT/ui/GoalConcurrencyScenariosTest.java`（⑦–⑭）
- Modify: `CT/ui/HistoryReplay.java`（goal 痕迹提示）
- Modify: `CT/agent/goal/GoalManager.java`（评估结论落库：`bindSession` 后 `appendEvent`）
- Modify: `docs/superpowers/specs/2026-09-23-code-tui-goal-design.md`（状态：待评审 → 已批准·实现中/已实现）
- Modify: `springai-code-tui/README.md` 或 docs 下命令文档（/goal 用法一节）

**Interfaces:**
- Consumes: `SessionRepository.appendEvent(SessionEvent)`（FileSessionRepository L106）；`GoalText.EVAL_OPEN/CLOSE`；HistoryReplay 的 `userTurns` 过滤形状（L118-126）。

- [ ] **Step 1: 并发场景 ⑦–⑭**（装置同 Task 12，重点 CompletableFuture 悬停）

7. `slowEvalUserInterjectsFirst`：评估挂起 → 用户 enqueue 消息 → drain（用户先走）→ 旧 verdict complete → 断言重评（evaluator 收到第 2 次输入）而非按旧 verdict 发轮（挂起 verdict 单槽作废）。
8. `slowEvalEscDropsStaleVerdict`：评估挂起 → Esc（PAUSED）→ verdict complete(SATISFIED) → 仍 PAUSED（epoch/状态校验丢弃）、无自动轮。
9. `slowEvalClearFullCleanup`：评估挂起 → `/clear` → CLEARED + 素材全清 + 挂起 verdict complete 后 transcript 无新增 goal 事件。
10. `slowEvalReplaceGoal`：评估挂起 → `/goal 新条件` → 旧 verdict complete(SATISFIED) → 新 goal RUNNING 不受影响。
11. `evaluatorThrowsRepeatedly`：evaluator 连抛 ×evalFailLimit → PAUSED(EVALUATOR)；且 begin/endEvaluation CAS 配对（PAUSED 后空闲批再 drain 不再发评估——无死锁： latch 断言无新 submit）。
12. `zombieTerminalGuardAndPriority`：多熔断同真（预算+轮数同批为真）→ 原因唯一 BUDGET_EXCEEDED；终态后 drain 任意批无任何 submit/eval（僵尸防护）。
13. `protocolFailureStorm`：evaluator 返回协议外文本 ×protocolFailLimit → PAUSED(PROTOCOL)；每次单失败按 UNSATISFIED+stalled 入账但不 dispatch 新轮（不烧预算——usage 断言不涨）。
14. `compactionSurvivalAndGuards`：触发既有压缩路径（照压缩测试装置）→ goal 滚动记录内存态不丢（评估输入仍含 8 轮）；守卫复断言：评估不写对话上下文（评估前后 session events 数不变——评估结论只走独立 appendEvent 标记事件）、自动轮 dispatch 全部落 UI 线程（测试态即断言 submit 全在 drain 调用线程）。

评估结论落库（`onVerdict` 锁外、publish 前后皆可，包内）：

```java
private void appendEvaluationEvent(String verdictLine, String reason) {
    SessionService ss; String sid;
    synchronized (this) { ss = this.sessionService; sid = this.sessionIdSupplier == null ? null : this.sessionIdSupplier.get(); }
    if (ss == null || sid == null) return;
    ss.appendEvent(SessionEvent.builder().sessionId(sid)
            .message(new UserMessage(GoalText.wrapEvaluation(verdictLine, reason))).build());
}
```
（`SessionService` 若无 appendEvent 门面则经 `sessionRepository.appendEvent`——执行时看 `DefaultSessionService` 暴露面，二选一，测试钉住落库形状。）

HistoryReplay：`userTurns` 过滤加 `!safe(m.getText()).startsWith(GoalText.CONTINUE_PREFIX_RAW)`（"[goal 继续"）；`toReplayLines` 的 USER 分支前加：`startsWith(GoalText.EVAL_OPEN)` → 渲一行 `Kind.INFO`（"◎ goal 评估：<内容>"）不重放正文；`replayHistory` 头部检测任一 goal 痕迹 → 追加一行 `pushInfo("上次会话有未完成 goal，已失效")`。

- [ ] **Step 2: 跑 ⑦–⑭ + 全模块回归** `mvn -pl springai-code-tui -am test`

- [ ] **Step 3: 文档收尾**——spec 状态行改「已批准·已实现（计划：docs/superpowers/plans/2026-09-29-code-tui-goal.md）」；命令文档补 `/goal`、`/goal stop`、9 个 env 变量表（照 README 现有命令节格式）。

- [ ] **Step 4: PTY 冒烟（真机评估器）**

```
# 前置：全项目 mvn -DskipTests install（build-classpath 不走 reactor）
source ~/.secrets   # ZHIPU_API_KEY（不回显、不落盘）
CODETUI_GOAL_EVALUATOR_MODEL=<智谱providerId>:<modelId> java -cp "$(cat target/cp.txt)" <MainClass>
# 交互：/goal 在 README.md 末尾追加一行 DONE 标记并验证 —— 观察自动轮 → 评估中 → UNSAT → 第2轮 → SATISFIED 终态
```
（MainClass/classpath 生成照既有 PTY 冒烟脚本先例；预期日志断言可选。）

- [ ] **Step 5: Commit**（`test(code-tui): goal 并发场景⑦-⑭+恢复提示——慢评估家族/僵尸防护/压缩存活`）

---

## Self-Review 记录

- **Spec 覆盖**：§3.1-3.4 命令面/状态栏/Esc/通知 → Task 10-13；§4 两份 prompt → Task 5/6；§5 架构与状态机 → Task 3-8；§6 评估器 → Task 6-7；§7 熔断矩阵+优先级 → Task 4（单测）+12/13（集成）；§8 权限 → Task 10（DEFAULT 档提示）；§9 配置 → Task 1；§10 涉及文件 → 全部映射（`CodingAgent` 门面简化为"GoalManager 直接持有 accumulator"，spec 原文"暴露 usage 完整 snapshot 门面"由构造注入达成——Snapshot 本就含 completionTokens）；§11 测试策略 → Task 1-13 逐层；§12 实现顺序 → Task 顺序一致。
- **与 spec 的三处偏差（已论证）**：① 构造实为 23 参非 22（红线不变）；② 忙碌时首轮不 enqueue 而置 `autoTurnPending` 走 goal 槽（队列语义干净，行为等价）；③ 空回复（EmptyStreamException）现状走 onError，由 `onTurnError` 根因豁免实现"不算 onError"口径。
- **类型一致性**：`GoalManager` 方法名在 Task 3/4/9/10/11 间已对齐（activate/clear/pauseByEsc/onUserDispatch/beginEvaluation/endEvaluation/onVerdict/onEvaluationFailure/onProtocolFailure/onTurnError/onTurnCompleted/collectGoalMaterial(SubmitHandler)/hasAutoTurnPending/takeAutoTurn/gapDeadlineEpochMs/currentEpoch/phase/snapshot/goal()）。
- **已知执行期核对点**（非占位符，执行时以代码为准）：TokenUsageAccumulator.Snapshot 访问器名（Task 4）；SessionService 是否暴露 appendEvent（Task 13）；Prompt.getToolCallbacks 断言形态随 Spring AI 版本（Task 6）。
