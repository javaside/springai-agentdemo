# headless 非交互模式（-p）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `codetui -p "<提示词>"` 不进 TUI 跑一个回合，stdout 只出最终回复（或计划正文），退出码 0/1/2。

**Architecture:** 全部经既有 `AgentListener` 接缝——新增 `headless` 包两个类（事件监听器 + 跑法编排），main 抽公共装配为 `assemble(...)` 后在 `-p` 处分叉；TUI 路径行为零变化。权限 fail-closed 内建（接口默认 DENY），问询/计划取消走「应答 + dispose + try/finally 放行必达」。

**Tech Stack:** Java 17、JUnit 5、reactor `Disposable`、既有 Spring AI 2.0 装配链。

**Spec:** `docs/superpowers/specs/2026-10-25-headless-mode-design.md`（12 条前提事实与事件表是行为权威；执行者须先读 spec 再动手）。

## Global Constraints

- 语言级别锁 17；注释/提交正文中文，注释写**为什么**。
- 单类测试命令（缺一必挂）：`mvn -pl springai-code-tui -am test -Dtest='XxxTest' -Dsurefire.failIfNoSpecifiedTests=false`。
- 工作分支 `feature/headless-mode`（已存在，勿切）；提交 `type: 说明` 前缀中文正文；功能与测试同提交。
- stdout 在 headless 成功路径**只有最终回复一段**；一切进度/诊断走 stderr。
- 回调禁令（`AgentListener` javadoc）：覆写问询/计划/审批**不得调 super、不得阻塞**；取消处置必须 try/finally 保证 latch 放行必达。
- 真机用例一律 `@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")` 门控；不硬编码 key。
- 新代码在 `io.github.javaside.springai.codetui.headless` 包 + `CodeTuiApplication` 既有包。

---

### Task 1: `HeadlessAgentListener`

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/headless/HeadlessAgentListener.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessAgentListenerTest.java`

**Interfaces:**
- Consumes: `agent.seam.AgentListener`（含 default）、`AskRequest(long turnId, List<QuestionSpec>, AskResponder)`、`PlanRequest(long turnId, String plan, PlanResponder)`、`PermissionRequest(long turnId, String taskId, String toolName, String target, String rawInput, String reason, PermissionRule suggested, PermissionResponder responder)`（suggested 可 null、responder 构造期非空）、`reactor.core.Disposable`。
- Produces（Task 4 依赖）: `new HeadlessAgentListener(PrintStream err)`、`bindTurn(Disposable)`、`exitCode()`、`planText()`、`finalReply()`、`awaitDone(long seconds)`（超时返回 false）、`awaitMcpReady(long seconds)`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionResponder;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanResponder;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** headless 监听器：分段/取消处置/退出码/latch——纯 JVM，responder 全捕获型桩。 */
class HeadlessAgentListenerTest {

    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final HeadlessAgentListener listener =
            new HeadlessAgentListener(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
    private String err() { return errBuf.toString(StandardCharsets.UTF_8); }

    // ── 捕获型桩 ─────────────────────────────────────────────
    private static final class RecAsk implements AskResponder {
        final AtomicInteger cancels = new AtomicInteger();
        @Override public void answer(Map<String, String> a) { }
        @Override public void cancel() { cancels.incrementAndGet(); }
    }
    private record RecPlan(PlanOutcome outcome, String feedback) { }
    private static final class RecPlanResponder implements PlanResponder {
        RecPlan got;
        @Override public void respond(PlanOutcome o, String f) { got = new RecPlan(o, f); }
    }
    private static final class RecPerm implements PermissionResponder {
        final List<PermissionOutcome> got = new ArrayList<>();
        @Override public void respond(PermissionOutcome o) { got.add(o); }
    }
    private static Disposable recordingDisposable(AtomicInteger disposed) {
        return disposed::incrementAndGet;   // Disposable 是单方法接口，lambda 即桩
    }

    @Test
    void segments_sealOnToolStart_finalReplyTakesLastNonEmpty() {
        listener.onAssistantToken(1L, "第一段开头");
        listener.onAssistantToken(1L, "续写");
        listener.onToolStarted(1L, "Bash", "{\"command\":\"ls\"}");   // 封段
        listener.onAssistantToken(1L, "最终结论");
        listener.onTurnComplete(1L);

        assertEquals("最终结论", listener.finalReply());
        assertTrue(err().contains("第一段开头续写"), "中间段进 stderr：" + err());
        assertTrue(err().contains("⏺ Bash"), "工具行进 stderr：" + err());
    }

    @Test
    void finalReply_blankTail_fallsBackToLastMiddle_allBlankEmpty() {
        listener.onAssistantToken(1L, "仅此一段");
        listener.onToolStarted(1L, "Read", "{}");   // 封段后尾部为空
        listener.onTurnComplete(1L);
        assertEquals("仅此一段", listener.finalReply(), "尾部空回退最后非空中间段");

        assertEquals("", new HeadlessAgentListener(System.err).finalReply(), "全程无文本得空串");
    }

    @Test
    void permissionDenied_withNotice_turnContinues() throws Exception {
        RecPerm perm = new RecPerm();
        PermissionRequest req = new PermissionRequest(1L, null, "Bash", "rm -rf /tmp/x",
                "{}", "测试", null, perm);
        listener.onPermissionRequested(1L, req);

        assertEquals(List.of(PermissionOutcome.DENY), perm.got);
        assertTrue(err().contains("headless 拒绝 Bash"), "拒绝要说出来：" + err());
        assertFalse(listener.awaitDone(0), "DENY 不放行 latch——回合继续（0 秒 await 立即返回 false）");
    }

    @Test
    void questionCancelsTurn_exit2_latchAlwaysReleased() throws Exception {
        RecAsk ask = new RecAsk();
        AtomicInteger disposed = new AtomicInteger();
        listener.bindTurn(recordingDisposable(disposed));

        listener.onQuestionAsked(1L, new AskRequest(1L, List.of(), ask));

        assertEquals(1, ask.cancels.get(), "先解除工具线程 park");
        assertEquals(1, disposed.get(), "再 dispose 终止回合");
        assertEquals(2, listener.exitCode());
        assertTrue(listener.awaitDone(1), "latch 已放行");
        assertTrue(err().contains("问询"), err());

        // turn 未注入（bindTurn 理论微窗）也不许 NPE 挂死
        HeadlessAgentListener bare = new HeadlessAgentListener(System.err);
        RecAsk ask2 = new RecAsk();
        bare.onQuestionAsked(1L, new AskRequest(1L, List.of(), ask2));
        assertEquals(2, bare.exitCode());
        assertTrue(bare.awaitDone(1), "无 turn 也放行");
    }

    @Test
    void planCancels_recordsText_exit0() throws Exception {
        RecPlanResponder plan = new RecPlanResponder();
        AtomicInteger disposed = new AtomicInteger();
        listener.bindTurn(recordingDisposable(disposed));

        listener.onPlanSubmitted(1L, new PlanRequest(1L, "## 计划正文", plan));

        assertEquals(PlanOutcome.CANCEL, plan.got.outcome());
        assertEquals(1, disposed.get());
        assertEquals("## 计划正文", listener.planText());
        assertEquals(0, listener.exitCode());
        assertTrue(listener.awaitDone(1));
    }

    @Test
    void errorSetsExit1_completeKeeps0_mcpReadyLatch() throws Exception {
        HeadlessAgentListener l = new HeadlessAgentListener(System.err);
        l.onMcpReady(2, 7);
        assertTrue(l.awaitMcpReady(1), "onMcpReady 放行");
        l.onTurnComplete(1L);
        assertEquals(0, l.exitCode());
        assertTrue(l.awaitDone(1));

        l.onError(2L, new RuntimeException("boom"));
        assertEquals(1, l.exitCode());
        assertTrue(l.awaitDone(1), "错误路径也放行");
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessAgentListenerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: HeadlessAgentListener`。

- [ ] **Step 3: 最小实现**

```java
package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import reactor.core.Disposable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * headless 模式的 AgentListener：事件→stderr 单行、token 分段累积、模态 fail-closed 处置。
 *
 * <p><b>同步纪律</b>同 ConversationState：事件来自 reactive 与工具两路线程，全方法 synchronized。
 * stderr 是注入的（生产 System.err；测试捕获）——stdout 通道由 Runner 独占（spec：stdout 只有最终回复）。
 */
public final class HeadlessAgentListener implements AgentListener {

    private final PrintStream err;
    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch mcpReady = new CountDownLatch(1);
    private volatile int exitCode = 0;
    /** submit 后由 Runner 注入。理论微窗内可能为 null（spec 评审：判空 + try/finally，放行必达）。 */
    private volatile Disposable turn;
    private final StringBuilder currentSegment = new StringBuilder();
    private final List<String> middleSegments = new ArrayList<>();
    private String planText;

    public HeadlessAgentListener(PrintStream err) {
        this.err = err;
    }

    // ── Runner 消费口 ────────────────────────────────────────
    public void bindTurn(Disposable turn) { this.turn = turn; }
    public int exitCode() { return exitCode; }
    /** 超时返回 false（Runner 据此 dispose + 退出 1）。 */
    public boolean awaitDone(long seconds) throws InterruptedException {
        return done.await(seconds, TimeUnit.SECONDS);
    }
    public boolean awaitMcpReady(long seconds) throws InterruptedException {
        return mcpReady.await(seconds, TimeUnit.SECONDS);
    }
    public synchronized String planText() { return planText; }

    /** 最后一个非空段（尾部空则回退最后非空中间段；全程无文本返回空串）。 */
    public synchronized String finalReply() {
        if (!currentSegment.isBlank()) {
            return currentSegment.strip();
        }
        for (int i = middleSegments.size() - 1; i >= 0; i--) {
            if (!middleSegments.get(i).isBlank()) {
                return middleSegments.get(i).strip();
            }
        }
        return "";
    }

    // ── 事件（spec 事件表逐行落地）────────────────────────────
    @Override public synchronized void onTurnStarted(long turnId) { }
    @Override public synchronized void onUserMessage(long turnId, String text) { }

    @Override public synchronized void onAssistantToken(long turnId, String token) {
        if (token != null && !token.isEmpty()) {
            currentSegment.append(token);
        }
    }

    @Override public synchronized void onToolStarted(long turnId, String toolName, String input) {
        sealSegment();
        err.println("⏺ " + toolName + firstLine(input));
    }

    @Override public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        err.println("  ⎿ " + (ok ? "✓" : "✗") + firstLine(output));
    }

    @Override public synchronized void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        err.println("▶ 子 agent " + agentName + "：" + description);
    }

    @Override public synchronized void onSubagentFinished(long turnId, String taskId, String finalText) {
        err.println("◀ 子 agent 结束" + firstLine(finalText));
    }

    @Override public synchronized void onTodoUpdated(long turnId, List<String> todoLines) { }

    @Override public synchronized void onTurnComplete(long turnId) {
        done.countDown();
    }

    @Override public synchronized void onError(long turnId, Throwable error) {
        err.println("✗ 回合失败：" + error);
        exitCode = 1;
        done.countDown();
    }

    /** fail-closed 的带提示版：DENY 后回合继续（默认实现即 DENY，这里只为 stderr 可见性）。 */
    @Override public synchronized void onPermissionRequested(long turnId, PermissionRequest request) {
        err.println("⚠ headless 拒绝 " + request.toolName() + "（" + request.target()
                + "）：无 UI 不可审批；可用 --permission-mode / allow 规则放宽");
        request.responder().respond(PermissionOutcome.DENY);
    }

    /** 取消处置与 UI cancelTurnFor 同序（先应答解除 park 再 dispose）；try/finally 保证放行必达。 */
    @Override public synchronized void onQuestionAsked(long turnId, AskRequest request) {
        err.println("⚠ 模型发起问询，headless 无法交互——取消回合（退出码 2）");
        try {
            request.responder().cancel();
            Disposable d = turn;
            if (d != null) {
                d.dispose();
            }
        } finally {
            exitCode = 2;
            done.countDown();
        }
    }

    /** 计划不批准：CANCEL + dispose，正文留给 stdout（--permission-mode plan + -p 的脚本用法）。 */
    @Override public synchronized void onPlanSubmitted(long turnId, PlanRequest request) {
        err.println("⏸ 模型提交计划——headless 不批准，输出正文后退出");
        planText = request.plan();
        try {
            request.responder().respond(PlanOutcome.CANCEL, "");
            Disposable d = turn;
            if (d != null) {
                d.dispose();
            }
        } finally {
            exitCode = 0;
            done.countDown();
        }
    }

    @Override public synchronized void onRuleRecorded(long turnId, boolean ok, String message) {
        err.println((ok ? "✓ " : "✗ ") + message);
    }

    /** BYPASS 下无人看着，放行记录必须可见。 */
    @Override public synchronized void onGuardrailBypassed(long turnId, String what) {
        err.println("⚠ BYPASS 放行：" + what);
    }

    @Override public synchronized void onRetryScheduled(long turnId, int attempt, int maxAttempts,
                                                        long backoffMs, String reason) {
        err.println("↻ 流式重试 " + attempt + "/" + maxAttempts + "（" + backoffMs + "ms 后）：" + reason);
    }

    @Override public synchronized void onQuotaWaitScheduled(long turnId, long resetAtEpochMs, String reason) {
        err.println("⏳ 限额等待：" + reason);
    }

    @Override public synchronized void onBackgroundTaskStarted(String taskId, String agentName, String description) {
        err.println("▶ 后台任务 " + taskId + "（注意：headless 回合结束即进程退出，后台任务随之终止）");
    }

    @Override public synchronized void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
        err.println("◀ 后台任务 " + taskId + (ok ? " ✓" : " ✗"));
    }

    @Override public synchronized void onMcpReady(int serverCount, int toolCount) {
        err.println("（MCP：已连接 " + serverCount + " 个 server / " + toolCount + " 个工具。）");
        mcpReady.countDown();
    }

    @Override public synchronized void onCompactionStarted(String reason) {
        err.println("（会话压缩开始：" + reason + "）");
    }

    @Override public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        err.println("（会话压缩完成：移除 " + eventsRemoved + " 事件 / 约省 " + tokensSaved + " token。）");
    }

    @Override public synchronized void onCompactionFailed(String message) {
        err.println("（会话压缩失败：" + message + "）");
    }

    /** 工具开始=上一段收口；段文本先打 stderr（spec：中间段全部进 stderr），再打工具行。 */
    private void sealSegment() {
        String s = currentSegment.toString();
        currentSegment.setLength(0);
        if (!s.isBlank()) {
            middleSegments.add(s);
            err.println(s.strip());   // 中间段正文（模型边做边说的部分）
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String line = s.strip().split("\n", 2)[0];
        return " " + (line.length() > 80 ? line.substring(0, 80) + "…" : line);
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessAgentListenerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 6/6 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/headless/HeadlessAgentListener.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessAgentListenerTest.java
git commit -m "feat: HeadlessAgentListener（分段/fail-closed 模态处置/latch 放行必达）"
```

---

### Task 2: `-p` 参数解析 + help 文本

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（`hasPrintFlag`/`headlessPrompt`/`outputLastMessagePath` 纯函数，放 `resumeFlagValue` 旁；`usageText()` 补两行）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/HeadlessStartupTest.java`；Modify `StartupHelpTest`（钉守新 flag）

**Interfaces:**
- Consumes: 既有 `resumeFlagValue` 解析纪律（`log.warn` 缺值先例）。
- Produces（Task 4 依赖；`headlessPrompt`/`outputLastMessagePath` **直接定义 public**——`headless` 包生产消费，javadoc 注明「生产跨包消费，非为测试放宽」；`hasPrintFlag` 包级，main 同包）: `static boolean hasPrintFlag(String[] args)`；`public static String headlessPrompt(String[] args)`（null=未给；`"-"`=stdin 哨兵；非法/空白抛 `IllegalArgumentException` 中文消息）；`public static String outputLastMessagePath(String[] args)`（null=未给/缺值）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** -p 与 --output-last-message 解析（纯函数；stdin 读取在 Runner，不属解析）。 */
class HeadlessStartupTest {

    @Test
    void headlessPrompt_value_stdinSentinel_missingOrFlagOrBlankRejected() {
        assertEquals("帮我修 NPE", CodeTuiApplication.headlessPrompt(new String[]{"-p", "帮我修 NPE"}));
        assertEquals("-", CodeTuiApplication.headlessPrompt(new String[]{"-p", "-"}), "stdin 哨兵");
        assertNull(CodeTuiApplication.headlessPrompt(new String[]{"-c"}), "未给 -p");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p"}), "尾部缺值");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p", "--permission-mode"}),
                "下一参是选项（注意：- 本身除外，它是 stdin 哨兵——与 resumeFlagValue 的差异）");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p", "  "}), "空白提示词");
    }

    @Test
    void hasPrintFlag_and_outputLastMessagePath() {
        assertTrue(CodeTuiApplication.hasPrintFlag(new String[]{"-c", "-p", "x"}));
        assertFalse(CodeTuiApplication.hasPrintFlag(new String[]{"-c"}));
        assertEquals("/tmp/out.txt",
                CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message", "/tmp/out.txt"}));
        assertEquals("/tmp/out.txt",
                CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message=/tmp/out.txt"}));
        assertNull(CodeTuiApplication.outputLastMessagePath(new String[]{"-p", "x"}));
        assertNull(CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message"}),
                "尾部缺值按未给处理（headless 下 stdout 仍有回复，不算失败）");
    }
}
```

`StartupHelpTest` 追加断言（放 `usageCoversAllFlags` 既有断言旁——该测试用局部变量 `String usage = CodeTuiApplication.usageText();`，追加写法对齐）：

```java
        assertTrue(usage.contains("-p <提示词>"), "help 必须覆盖 -p");
        assertTrue(usage.contains("--output-last-message"), "help 必须覆盖 --output-last-message");
```

（取舍注明：spec 测试策略列的「-h 优先」由 main 结构保证——help 判定是 main 第一件事、`-p` 分叉在其后，无独立可单测的纯函数，不加用例。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessStartupTest,StartupHelpTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（三符号缺失）——编译错误会中止整个 test 阶段，`StartupHelpTest` 的断言红此刻观察不到（其验证在 Task 2 完成后的 Step 4 一起跑绿）。

- [ ] **Step 3: 最小实现**

`usageText()` 的 `-c` 行后追加：

```text
   -p <提示词>                     非交互模式：跑一个回合，stdout 只输出最终回复（-p - 从 stdin 读）
   --output-last-message <文件>    （-p 模式）最终回复同时写入该文件
```

`resumeFlagValue` 旁新增（注释纪律对齐）：

```java
    /** -p 是否出现（main 在会话选择后据此分叉进 headless）。 */
    static boolean hasPrintFlag(String[] args) {
        return java.util.Arrays.asList(args).contains("-p");
    }

    /**
     * 提取 {@code -p} 的提示词：null=未给；{@code "-"}=stdin 哨兵（由 Runner 读全）；否则原文。
     * 非法（尾部缺值 / 下一参以 - 开头且非 - 本身 / 空白）抛 IllegalArgumentException——main 捕获后
     * stderr + 退出 1。与 {@link #resumeFlagValue} 的差异：{@code -} 本身在此是合法值（stdin），
     * 不能照抄「一律当选项」的判定。public：headless 包生产消费（非为测试放宽）。
     */
    public static String headlessPrompt(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!"-p".equals(args[i])) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("-p 缺少提示词参数（长文本可用 -p - 从 stdin 读取）。");
            }
            String next = args[i + 1];
            if (!"-".equals(next) && next.startsWith("-")) {
                throw new IllegalArgumentException(
                        "-p 后面跟的是另一个选项（" + next + "）；长提示词可用 -p - 从 stdin 读取。");
            }
            if (!"-".equals(next) && next.isBlank()) {
                throw new IllegalArgumentException("-p 提示词为空。");
            }
            return next;
        }
        return null;
    }

    /** 提取 {@code --output-last-message <路径>} / {@code =路径}；未给或缺值返回 null（缺值 WARN 不算失败）。public：headless 包生产消费。 */
    public static String outputLastMessagePath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--output-last-message")) {
                if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    log.warn("--output-last-message 缺少文件路径参数，忽略。");
                    return null;
                }
                return args[i + 1];
            }
            if (a.startsWith("--output-last-message=")) {
                String v = a.substring("--output-last-message=".length());
                return v.isBlank() ? null : v;
            }
        }
        return null;
    }
```

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessStartupTest,StartupHelpTest,ResumeStartupTest,PermissionStartupTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（解析区与既有互不干扰）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/HeadlessStartupTest.java
# StartupHelpTest 若同仓修改一并 add
git commit -m "feat: -p 与 --output-last-message 参数解析（stdin 哨兵 + help 两行）"
```

---

### Task 3: main 公共装配抽取（TUI 零行为变化）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（main L76-201 段抽入 `assemble(...)`；`restoreLastModel` 加通告器重载；新增包级 `Assembled` record）

**Interfaces:**
- Consumes: main 现有装配链（spec 事实 #8 的 14 处调用点清单）；`AgentTools.build(registry, root, listener, mcpRegistry, permissionEngine, cfg, goalManager)`、`AgentTools.wireL1/wireGoal/wireTodoReminder`、CodingAgent 23 参构造器（实参序照 main L186-192 原样）。
- Produces（Task 4 依赖；**可见性定案**：被 `headless` 包生产消费的四件升 public——javadoc 注明「生产跨包消费，非为测试放宽」，与 StartupHelpTest 的包级口径不冲突；`hasPrintFlag` 仍包级，main 同包）:
  - `public record Assembled(ProviderRegistry registry, TokenUsageAccumulator usage, McpRegistry mcpRegistry, GoalEvaluationRunner goalRunner, AgentTools.AgentRuntime runtime, CodingAgent agent, boolean wantContinue, boolean latestPresent)`
  - `public static Assembled assemble(String[] args, Path root, AgentListener listener, java.util.function.Consumer<String> notice, boolean tuiMode, String sessionId)`
  - `static void restoreLastModel(ProviderRegistry, Path, java.util.function.Consumer<String> notice)`（ConversationState 版一行委托保留）
  - `headlessPrompt` / `outputLastMessagePath` 已在 Task 2 定义为 public（headless 包生产消费）；`hasPrintFlag` 保持包级，main 同包。

- [ ] **Step 1: 重构（纯搬家，无新测试——守护是全量回归）**

要点（顺序与现 main 严格一致，只是搬进方法）：

1. main 保留：`-h`、`applyRenderDiagFlags`、JUL 桥、root、**会话选择段（L94-121，不依赖 state，两模式共用）**、`-p` 分叉位（本任务只留注释占位，Task 4 接线）。
2. `assemble(...)` 体 = 现 L76-93 的 usageAccumulator/registry 创建（**key 缺失 catch 分支双模式统一 `return null`**：tuiMode 走原 `System.out.println(原中文文案)` 后 return null，main TUI 调用处 `if (a == null) return;`；tuiMode=false 走 `System.err.println(同文案)` 后 return null，headless 由 Runner 判 null 返回 1、main `System.exit(1)`——**退出决定权归调用方，assemble 绝不 System.exit**（否则杀死同 JVM 直调 run() 的真机冒烟，spec 定案的 stderr+退出 1 语义不变））→ restoreLastModel（重载版）→ 权限引擎与 5 处 notice（`notice.accept(...)`）→ `McpRegistry.init(root, listener, permissionEngine)` → goal 三件构造 → try 段内 build/CodingAgent 构造/wireL1 + `if (tuiMode) { wireGoal; wireTodoReminder; }`。
3. `Assembled.wantContinue/latestPresent` 供 TUI 开场提示块（L205-211）用——该块与 replayHistory、`CodeTuiView` 构造、`view.run()`、catch/finally（`mcpRegistry.close(); goalRunner.close(); System.exit`）**留在 main TUI 路径**。
4. `activeTurnId` 移入 assemble（仅 CodingAgent 消费）；`ConversationState state` 与 `state::pushInfo` 由 main TUI 路径构造后传入。
5. 原 L139-157 的 `state.pushInfo(...)` 全部改 `notice.accept(...)`；restoreLastModel 重载把 `state.pushInfo` 换 `notice.accept`，原三参版本一行委托 `restoreLastModel(registry, root, state::pushInfo)`。
6. **边缘差异注记**（重构后与现码不完全同序）：key 检测从「会话选择之前」后移到「会话选择之后」（assemble 内）——无 key 且 resume 冲突的机器上报错先后翻转、无 key 机器也会先扫目录。无害（两者都退出），实现时在 assemble 的 key 缺失分支注释里记一句。

- [ ] **Step 2: 全量回归（重构的「绿」）**

Run: `mvn -pl springai-code-tui -am test`
Expected: 全 PASS、0 新失败（TUI 路径行为零变化的证据；20 skipped 为 env 门控属正常）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "refactor: main 公共装配抽为 assemble（listener/通告器参数化，为 headless 分叉铺路）"
```

---

### Task 4: `HeadlessRunner` + main `-p` 分叉

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/headless/HeadlessRunner.java`
- Modify: `CodeTuiApplication.java`（main 会话选择后接分叉）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessRunnerTest.java`（env 解析与 stdin 读取的纯函数部分）

**Interfaces:**
- Consumes: Task 1 `HeadlessAgentListener` 全部产出口；Task 2 三解析函数；Task 3 `assemble`/`Assembled`。
- Produces: `public static int HeadlessRunner.run(String[] args, Path root, String sessionId, boolean resumed)`（main 跨包调用；返回退出码，System.exit 留给 main）；`static long mcpWaitSeconds()` / `static long maxSeconds()` / `static String prompt(String[] args)`（包级，可单测）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.headless;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runner 的可单测纯函数：env 超时值解析与 stdin 提示词。run() 全链路属真机冒烟（Task 5，env 门控）。 */
class HeadlessRunnerTest {

    @Test
    void envTimeouts_defaultAndClamp() {
        assertEquals(10L, HeadlessRunner.mcpWaitSeconds(), "默认 10（env 未设走默认）");
        assertEquals(3600L, HeadlessRunner.maxSeconds(), "默认 3600");
        // 钳制边界：env 由 System.getenv 读——本用例只钉默认值与返回类型契约；
        // 钳制逻辑经私有 clamp 实现，真机/环境类测试不在此展开（CI 无 env 干预）。
    }

    @Test
    void prompt_stdinSentinelReadsAll() {
        InputStream old = System.in;
        System.setIn(new ByteArrayInputStream("第一行\n第二行".getBytes(StandardCharsets.UTF_8)));
        try {
            assertEquals("第一行\n第二行", HeadlessRunner.prompt(new String[]{"-p", "-"}), "stdin 全读含换行");
            assertEquals("直接文本", HeadlessRunner.prompt(new String[]{"-p", "直接文本"}));
        } finally {
            System.setIn(old);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: HeadlessRunner`。

- [ ] **Step 3: 最小实现 + main 分叉**

`HeadlessRunner`：

```java
package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.CodeTuiApplication;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * headless 跑法：公共装配（headless 特化：不接 goal/todo 提醒线）→ MCP 等待 → submit →
 * await（总硬顶）→ stdout 最终回复 → 收尾报告。返回退出码；System.exit 留给 main（与 TUI 同纪律）。
 */
public final class HeadlessRunner {

    private HeadlessRunner() {}

    public static int run(String[] args, Path root, String sessionId, boolean resumed) {
        String promptSpec;
        try {
            promptSpec = CodeTuiApplication.headlessPrompt(args);   // 非法/空白在此抛
        } catch (IllegalArgumentException e) {
            System.err.println("错误：" + e.getMessage());
            return 1;
        }
        HeadlessAgentListener listener = new HeadlessAgentListener(System.err);
        CodeTuiApplication.Assembled a;
        try {
            a = CodeTuiApplication.assemble(args, root, listener, System.err::println, false, sessionId);
        } catch (Throwable t) {   // init 之后装配步骤抛异常会裸栈逃出（现 main 同窗口）；headless 卖点是脚本友好，stderr + 退出 1
            System.err.println("✗ 装配失败：" + t);
            return 1;
        }
        if (a == null) {
            // key 缺失：assemble 的 headless 分支已 System.err 打过中文文案（双模式统一 return null，退出权在调用方）
            return 1;
        }
        try {
            if (resumed) {
                System.err.println("（已恢复会话 " + sessionId + "，历史进入上下文。）");
            }
            // 零 enabled server 时 onMcpReady 不发（spec 事实 #6）——先查 connectingCount 再等
            if (a.mcpRegistry().connectingCount() > 0 && !listener.awaitMcpReady(mcpWaitSeconds())) {
                System.err.println("⚠ MCP 连接等待超时（" + mcpWaitSeconds() + "s），工具可能缺席，继续执行。");
            }
            Disposable turn;
            try {
                turn = a.agent().submit(prompt(args));
            } catch (RuntimeException e) {   // submit 同步段会抛且无外层 catch（spec 事实/评审修订）
                System.err.println("✗ 提交失败：" + e);
                return 1;
            }
            listener.bindTurn(turn);
            if (!listener.awaitDone(maxSeconds())) {   // 总硬顶：空闲超时只杀静默流（spec 事实 #9）
                turn.dispose();
                System.err.println("✗ headless 回合超时（CODETUI_HEADLESS_MAX_SECONDS=" + maxSeconds() + "s）。");
                return 1;
            }
            String reply = listener.planText() != null ? listener.planText() : listener.finalReply();
            if (!reply.isBlank()) {
                System.out.println(reply);   // 空则不打（stdout 只有最终回复——exit-2 等空回复场景不出空行）
            }
            writeLastMessage(args, reply);
            var snap = a.usage().snapshot();
            System.err.println("（会话 id：" + sessionId + "；--resume " + sessionId + " 可续跑。）");
            System.err.println("（token：" + snap.promptTokens() + " in / " + snap.completionTokens() + " out）");
            return listener.exitCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("✗ headless 被中断。");
            return 1;
        } finally {
            a.mcpRegistry().close();   // 与 main 同序：先 MCP 后 goal 池
            a.goalRunner().close();
        }
    }

    static String prompt(String[] args) {
        String spec = CodeTuiApplication.headlessPrompt(args);
        if (!"-".equals(spec)) {
            return spec;
        }
        try (var in = System.in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 stdin 失败", e);
        }
    }

    static long mcpWaitSeconds() {
        return clamp(System.getenv("CODETUI_HEADLESS_MCP_WAIT_SECONDS"), 10L, 1L, 300L);
    }

    static long maxSeconds() {
        return clamp(System.getenv("CODETUI_HEADLESS_MAX_SECONDS"), 3600L, 60L, 86400L);
    }

    private static long clamp(String raw, long def, long min, long max) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(raw.strip())));
        } catch (NumberFormatException e) {
            return def;   // 非法回退默认，与 LlmTimeouts 同口径
        }
    }

    private static void writeLastMessage(String[] args, String reply) {
        String path = CodeTuiApplication.outputLastMessagePath(args);
        if (path == null) {
            return;
        }
        try {
            Files.writeString(Path.of(path), reply + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {   // 写失败只警告不改退出码（spec 定案）
            System.err.println("⚠ --output-last-message 写入失败（" + path + "）：" + e);
        }
    }
}
```

`CodeTuiApplication.main` 会话选择段之后（Task 3 留的分叉位）：

```java
        // headless 分叉：会话选择之后（两模式共用 -c/--resume 语义）、TUI 装配之前。
        // System.exit 在此——Runner 只返回退出码（测试可直调 run 不退出）。
        if (hasPrintFlag(args)) {
            System.exit(HeadlessRunner.run(args, root, sessionId, resumed));
        }
```

注意：`assemble` 双模式统一「key 缺失 → 各自通道打印中文文案 + `return null`」（Task 3 要点 2 定案），退出决定权归调用方——TUI 的 main 判 null 后 return（原「打印+return」语义不变），headless 的 Runner 判 null 返回 1、main `System.exit(1)`（spec「stderr + 退出 1」达成）。

- [ ] **Step 4: 跑测试确认绿 + 回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='HeadlessRunnerTest,HeadlessStartupTest,HeadlessAgentListenerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/headless/HeadlessRunner.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessRunnerTest.java
git commit -m "feat: HeadlessRunner 与 main -p 分叉（MCP 等待/总硬顶/退出码 0-1-2）"
```

---

### Task 5: 真机冒烟 + 文档 + 全量回归

**Files:**
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessSmokeTest.java`（env 门控）
- Modify: `springai-code-tui/docs/implementation-map.md`（速查表行 + §1 补「headless 模式」小节 + 关键环境变量表两行 + **顺手修 §3 回合表「步骤 l 同步段 catch」的过时描述**——评审发现现码无外层 catch）；`springai-code-tui/README.md` 与 `springai-code-tui/docs/guide/reference.md`（启动参数节补 `-p` / `--output-last-message`）

**Interfaces:** Consumes: Task 1-4 全部成品；`SessionCatalog.scan`（冒烟取会话 id）。

- [ ] **Step 1: 写冒烟测试（先红——门控环境下必跑；无 key 环境自动跳过不算失败）**

```java
package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.agent.session.SessionCatalog;
import io.github.javaside.springai.codetui.agent.session.SessionIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * headless 全链路真机冒烟（DEEPSEEK_API_KEY 门控，同 CodingAgentSpikeTest 纪律）。
 * 直调 HeadlessRunner.run——run 只返回退出码不 System.exit（exit 在 main），同 JVM 可测；
 * stdout 经 System.setOut 捕获（run 内唯一 stdout 写点）。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class HeadlessSmokeTest {

    @Test
    @Timeout(180)
    void oneShot_replyOnStdout_sessionPersisted(@TempDir Path root) throws Exception {
        PrintStream oldOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = HeadlessRunner.run(new String[]{"-p", "只回复四个字：收到请讲"}, root, SessionIds.newId(), false);
        } finally {
            System.setOut(oldOut);
        }

        assertEquals(0, code, "正常回合退出码 0");
        String out = buf.toString(StandardCharsets.UTF_8).strip();
        assertFalse(out.isBlank(), "stdout 有最终回复");
        assertTrue(out.contains("收到"), "回复内容含约定暗号，实际：" + out);
        List<SessionCatalog.SessionSummary> sessions =
                SessionCatalog.scan(root.resolve(".codetui").resolve("sessions"));
        assertEquals(1, sessions.size(), "会话文件已落盘（spec：持久化+可恢复）");
    }

    @Test
    @Timeout(240)
    void resumeChainedSecondStep_carriesContext(@TempDir Path root) throws Exception {
        String firstId = SessionIds.newId();
        assertEquals(0, HeadlessRunner.run(new String[]{"-p", "请记住暗号：蓝鲸。只回复：好的"},
                root, firstId, false));
        String sessionId = SessionCatalog.scan(root.resolve(".codetui").resolve("sessions"))
                .get(0).id();

        PrintStream oldOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = HeadlessRunner.run(new String[]{"-p", "我们约定的暗号是什么？只回复暗号本身"},
                    root, sessionId, true);
        } finally {
            System.setOut(oldOut);
        }

        assertEquals(0, code);
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("蓝鲸"),
                "第二步带上第一步上下文，实际：" + buf);
    }
}
```

- [ ] **Step 2: 门控环境跑冒烟（功能验证；无 key 环境自动跳过不算失败）**

Run: `DEEPSEEK_API_KEY 已配置的终端执行 mvn -pl springai-code-tui -am test -Dtest='HeadlessSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false`；无 key 环境该类自动跳过。
Expected: 2/2 PASS（或 SKIPPED）。

- [ ] **Step 3: 文档更新**

- implementation-map 速查表加行：`| headless -p | CodeTuiApplication 分叉 → HeadlessRunner | HeadlessAgentListener、assemble | [1](#1-启动与装配) |`；§1 末尾补 6-10 行「headless 模式」小节（分叉点/事件表要点/退出码/env 两项/MCP 等待策略）；「关键环境变量」表补 `CODETUI_HEADLESS_MCP_WAIT_SECONDS`（10，钳 [1,300]）与 `CODETUI_HEADLESS_MAX_SECONDS`（3600，钳 [60,86400]）；**顺手修 §3 步 l 过时句**（同步段 catch 已不存在，改为「同步抛由调用方兜：TUI 视图装配后 catch；headless 由 Runner catch」）。
- README 快速上手/常用操作附近补一节 3-5 行「非交互（脚本）用法」：`codetui -p "..."`、`-p -`、`--output-last-message`、退出码 0/1/2、`--resume` 链式示例。
- reference.md「启动参数」表补 `-p` 与 `--output-last-message` 两行。

- [ ] **Step 4: 全量回归**

Run: `mvn -pl springai-code-tui -am test`
Expected: 全 PASS（20 skipped 为 env 门控；已知 flaky CodingAgentSpikeTest.todoTurnIdBinding 若红按 CONTRIBUTING 单跑复核）。

- [ ] **Step 5: 提交（两笔：测试与文档各一件事，仓库无复合前缀先例）**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/headless/HeadlessSmokeTest.java
git commit -m "test: headless 真机冒烟（一次性回复/会话落盘/--resume 链式）"
git add springai-code-tui/docs/implementation-map.md springai-code-tui/README.md \
        springai-code-tui/docs/guide/reference.md
git commit -m "docs: headless -p 用法（退出码/env 表/§1 小节/§3 过时句修正）"
```

---

## Plan Self-Review（已执行）

1. **Spec 覆盖**：spec 六个设计节全部有任务承接——Listener 事件表（Task 1 逐行）、参数解析与 help（Task 2）、装配分叉（Task 3）、Runner 全流程含 MCP 等待/总硬顶/key 缺失/submit 同步抛（Task 4）、退出码三值与 stdout 纪律（Task 1/4）、真机冒烟与文档（Task 5）。spec 评审修订四条必改（try/finally、key 缺失、硬顶、submit catch）全部落码。
2. **占位符扫描**：无 TBD/TODO/「落地时再改」类注记（写作过程中的笔误已当场清干净）。
3. **类型一致性**：`HeadlessAgentListener` 产出口（bindTurn/exitCode/planText/finalReply/awaitDone/awaitMcpReady）在 Task 4 逐一消费且签名一致；`assemble`/`Assembled` 字段在 Task 3 定义、Task 4 消费（registry/usage/mcpRegistry/goalRunner/runtime/agent 均用到）；`hasPrintFlag/headlessPrompt/outputLastMessagePath` Task 2 定义、Task 4 消费。
4. **已知执行期对齐点**（注记在任务内）：Task 3 纯重构以全量回归为绿（无先红）；Task 5 冒烟直调 `run()` 依赖「System.exit 只在 main」这一 Task 4 定案。
5. **评审修订记录（subagent 评审 2026-10-25，已全部落实）**：跨包可见性定案（`Assembled`/`assemble`/`headlessPrompt`/`outputLastMessagePath`/`run` 升 public，javadoc 注明生产消费）；Task 1 三个测试方法补 `throws Exception`；key 缺失双模式统一 `return null`（assemble 绝不 System.exit——保真机冒烟可直调 run）；Runner 的 assemble 调用包 `try/catch(Throwable)`；空回复不打 stdout 空行；stderr 收尾拆两行；finally 关闭顺序对齐 main；`assemble` 去掉无消费的 `resumed` 参数；`StartupHelpTest` 追加片段对齐局部变量 `usage`；Task 2 Step 2 预期改为纯编译失败；Task 5 提交拆 `test:`/`docs:` 两笔；Task 3 补 key 检测次序后移的边缘差异注记；spec 测试策略两项缺口（-h 优先/无模型依赖落盘用例）注明取舍。