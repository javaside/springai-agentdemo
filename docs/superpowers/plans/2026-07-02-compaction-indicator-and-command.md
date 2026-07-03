# 压缩指示器 + 手动 `/compact` 命令 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让会话压缩对用户可见（自动压缩显示实时状态 + 完成结果），并新增手动 `/compact` 命令强制立即、激进地压缩会话历史。

**Architecture:** 一个 `NotifyingCompactionStrategy` 装饰器包住真实压缩策略，在 `compact()` 前后经既有的 `AgentListener` 接缝发出「开始/完成/失败」事件——自动与手动两条路径共用同一装饰器与同一 UI。手动路径经 `SessionService.compact(id, req->true, 激进策略)` 直接触发；并发闸门放在 `CodeTuiView`（复用既有的「忙时入队」机制），不污染 `CodingAgent`。

**Tech Stack:** Java 21、spring-ai-session 0.5.0（`org.springframework.ai.session.*`）、TamboUI（终端 UI）、JUnit 5、Maven。

**设计文档：** `docs/superpowers/specs/2026-07-02-compaction-indicator-and-command-design.md`

**已核实的库事实（javap）：**
- `SessionService.compact(String, CompactionTrigger, CompactionStrategy) -> CompactionResult`
- `CompactionTrigger` 是函数式接口 `boolean shouldCompact(CompactionRequest)` → 强制触发 = `req -> true`
- `CompactionStrategy` 是函数式接口 `CompactionResult compact(CompactionRequest)`
- `CompactionResult` record：`int eventsRemoved()`（= `archivedEvents().size()`）、`int tokensEstimatedSaved()`
- `RecursiveSummarizationCompactionStrategy.Builder`：`maxEventsToKeep(int)`、`overlapSize(int)`、`tokenCountEstimator(...)` 均存在

**相较设计文档的一处细化（计划期确认）：** 并发闸门从「`CodingAgent.compact()` 检查活跃回合」改为「`CodeTuiView` 在派发 `/compact` 前检查 `state.isIdle() && !state.isCompacting()`」。原因：`CodingAgent` 只持有 `AgentListener` 接缝、拿不到回合活跃状态，而 `CodeTuiView` 本就拥有「空闲才派发、忙则入队」的决策（见 `submitInput`）。闸门放这里可直接复用既有入队机制，`CodingAgent` 保持纯净。

---

## File Structure

| 文件 | 职责 | 改动 |
| --- | --- | --- |
| `agent/AgentListener.java` | CodingAgent→UI 唯一接缝 | +3 抽象方法 |
| `agent/NotifyingCompactionStrategy.java` | 压缩事件装饰器（自动/手动共用） | **新建** |
| `agent/AgentTools.java` | ChatClient 工厂 | 双策略 + 新增 `AgentRuntime` record，`build()` 改返回它 |
| `agent/CodingAgent.java` | 回合翻译 + 手动压缩入口 | 构造函数 +2 参、+`compact()`/`runCompaction()` |
| `agent/SubmitHandler.java` | 命令接缝 | +`compact()` 默认方法 |
| `ui/ConversationState.java` | 共享状态 + AgentListener 落地端 | 瞬态压缩状态 + 3 方法实现 |
| `ui/CodeTuiView.java` | TUI 渲染 + 命令派发 | `/compact` 注册/派发/闸门 + 状态行渲染 |
| `CodeTuiApplication.java` | 入口装配 | 适配 `AgentRuntime` + 6 参构造 |
| 测试若干 | | 见各 Task |

---

## Task 1: 压缩接缝 + ConversationState 瞬态状态

给 `AgentListener` 加 3 个抽象方法，并在唯一生产实现 `ConversationState` 里落地：置/清瞬态压缩状态 + 把完成/失败/无内容写进 scrollback。两个测试用的手写 `AgentListener` 实现补上 no-op（否则接口新增抽象方法会破坏编译）。

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java`
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java`（`Recorder` 补 no-op）
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/ToolEventCallbackTest.java`（`RecordingListener` 补 no-op）
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateTest.java`

- [ ] **Step 1: 写失败测试**（追加到 `ConversationStateTest.java` 末尾、类内）

```java
@Test
void compaction_started_setsFlagAndReason() {
    ConversationState s = new ConversationState();
    assertFalse(s.isCompacting(), "初始不在压缩");

    s.onCompactionStarted("manual");

    assertTrue(s.isCompacting(), "started 后应处于压缩中");
    assertEquals("manual", s.compactReason());
    assertTrue(s.compactElapsedNanos() >= 0, "经过时间应可读且非负");
}

@Test
void compaction_finished_clearsFlagAndPushesSummaryLine() {
    ConversationState s = new ConversationState();
    s.onCompactionStarted("auto");

    s.onCompactionFinished(7, 1234);

    assertFalse(s.isCompacting(), "finished 后应退出压缩中");
    assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("7") && l.text().contains("1234")),
            "完成行应含移除事件数与节省 token");
}

@Test
void compaction_finished_zeroRemoved_pushesNothingToCompactLine() {
    ConversationState s = new ConversationState();
    s.onCompactionStarted("manual");

    s.onCompactionFinished(0, 0);

    assertFalse(s.isCompacting());
    assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("无可压缩")),
            "0 移除应提示无可压缩");
}

@Test
void compaction_failed_clearsFlagAndPushesErrorLine() {
    ConversationState s = new ConversationState();
    s.onCompactionStarted("manual");

    s.onCompactionFailed("boom");

    assertFalse(s.isCompacting(), "failed 后应退出压缩中");
    assertTrue(s.drainPending().stream().anyMatch(l ->
                    l.text().contains("boom") && l.kind() == ConversationState.OutputLine.Kind.ERROR),
            "失败行应含原因且为 ERROR 类型");
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd springai-code-tui && mvn -q -Dtest=ConversationStateTest test`
Expected: 编译失败——`isCompacting()` / `compactReason()` / `compactElapsedNanos()` / `onCompactionStarted` 等方法不存在。

- [ ] **Step 3: 给 `AgentListener` 加 3 个抽象方法**

在 `AgentListener.java` 的 `onError` 行之后、接口右括号之前插入：

```java

    // ── 会话压缩（跨回合的横切信号；无 turnId） ──
    /** 压缩开始。reason: "auto"（阈值触发）| "manual"（/compact）。 */
    void onCompactionStarted(String reason);
    /** 压缩完成。eventsRemoved：被归档移除的事件数；tokensSaved：估算节省 token。 */
    void onCompactionFinished(int eventsRemoved, int tokensSaved);
    /** 压缩失败。message：失败原因（已做 null 安全处理的字符串）。 */
    void onCompactionFailed(String message);
```

- [ ] **Step 4: 给两个测试用手写实现补 no-op**

在 `CodingAgentSpikeTest.java` 的 `Recorder` 类里，`onError` 覆写之后加：

```java
        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }
```

在 `ToolEventCallbackTest.java` 的 `RecordingListener` 类里，同样在最后一个覆写方法之后加相同的三个 no-op 覆写（若 `RecordingListener` 未实现全部方法而是只实现部分，请仅补这三个新方法）。

- [ ] **Step 5: 在 `ConversationState` 里实现**

(a) 在字段区（`private volatile long acceptingTurnId = -1L;` 之后）加瞬态压缩状态：

```java
    // ── 会话压缩瞬态状态（供状态行显示；独立于 Status，自动/手动共用） ──
    private volatile boolean compacting = false;
    private volatile long compactStartNanos = 0L;
    private volatile String compactReason = "";
```

(b) 在「单飞 / 状态」区（`public long acceptingTurnId() { ... }` 之后）加读取方法：

```java
    // ── 压缩状态读取（渲染线程用） ──
    public boolean isCompacting() { return compacting; }
    public String compactReason() { return compactReason; }
    /** 距压缩开始的经过纳秒（用于状态行计时）。 */
    public long compactElapsedNanos() { return compacting ? System.nanoTime() - compactStartNanos : 0L; }
```

(c) 在「AgentListener 落地端」区（`onError` 实现之后）加三个实现：

```java
    @Override
    public synchronized void onCompactionStarted(String reason) {
        compacting = true;
        compactStartNanos = System.nanoTime();
        compactReason = reason == null ? "" : reason;
    }

    @Override
    public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        compacting = false;
        if (eventsRemoved <= 0) {
            pending.add(new OutputLine("• 无可压缩内容（历史尚短）", OutputLine.Kind.INFO));
        } else {
            pending.add(new OutputLine(
                    "✓ 已压缩会话：移除 " + eventsRemoved + " 个事件，约省 " + tokensSaved + " tokens",
                    OutputLine.Kind.INFO));
        }
    }

    @Override
    public synchronized void onCompactionFailed(String message) {
        compacting = false;
        pending.add(new OutputLine("✗ 压缩失败：" + (message == null ? "unknown" : message),
                OutputLine.Kind.ERROR));
    }
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd springai-code-tui && mvn -q -Dtest=ConversationStateTest test`
Expected: PASS（4 个新测试全绿）。

- [ ] **Step 7: 编译全模块，确认无破坏**

Run: `cd springai-code-tui && mvn -q -o test-compile`
Expected: BUILD SUCCESS（两个手写 listener 已补 no-op）。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/ToolEventCallbackTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateTest.java
git commit -m "feat(code-tui): add compaction events to AgentListener + ConversationState transient state"
```

---

## Task 2: `NotifyingCompactionStrategy` 装饰器

包住任意 `CompactionStrategy`，在 `compact()` 前后经 `AgentListener` 发「开始/完成/失败」。自动与手动路径各包一份（reason 分别 `"auto"`/`"manual"`），一处代码驱动两条路径的指示器。

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/NotifyingCompactionStrategy.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/NotifyingCompactionStrategyTest.java`

- [ ] **Step 1: 写失败测试**

创建 `NotifyingCompactionStrategyTest.java`：

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NotifyingCompactionStrategy：成功路径发 started→finished（转发 result 的计数），失败路径发 started→failed 并重抛。 */
class NotifyingCompactionStrategyTest {

    /** 只记录压缩三事件的最小 listener，其余接缝方法留空。 */
    private static final class CapturingListener implements AgentListener {
        String startedReason;
        int finishedRemoved = -1, finishedSaved = -1;
        String failedMessage;
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onCompactionStarted(String reason) { startedReason = reason; }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) {
            finishedRemoved = eventsRemoved; finishedSaved = tokensSaved;
        }
        @Override public void onCompactionFailed(String message) { failedMessage = message; }
    }

    @Test
    void success_firesStartedThenFinished_withResultCounts() {
        CapturingListener listener = new CapturingListener();
        // archivedEvents 为空 → eventsRemoved()==0；tokensEstimatedSaved==1234
        CompactionResult result = new CompactionResult(List.of(), List.of(), 1234);
        CompactionStrategy delegate = req -> result;
        AtomicReference<CompactionRequest> seen = new AtomicReference<>();
        CompactionStrategy spyDelegate = req -> { seen.set(req); return result; };

        var notifying = new NotifyingCompactionStrategy(spyDelegate, listener, "manual");
        CompactionResult out = notifying.compact(null);   // req->result 忽略入参，可传 null

        assertEquals(result, out, "应透传 delegate 的结果");
        assertEquals("manual", listener.startedReason);
        assertEquals(result.eventsRemoved(), listener.finishedRemoved, "转发 eventsRemoved");
        assertEquals(1234, listener.finishedSaved, "转发 tokensEstimatedSaved");
    }

    @Test
    void failure_firesStartedThenFailed_andRethrows() {
        CapturingListener listener = new CapturingListener();
        CompactionStrategy delegate = req -> { throw new RuntimeException("boom"); };

        var notifying = new NotifyingCompactionStrategy(delegate, listener, "auto");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> notifying.compact(null));
        assertEquals("boom", ex.getMessage(), "异常应重抛");
        assertEquals("auto", listener.startedReason, "失败前应已发 started");
        assertTrue(listener.failedMessage.contains("boom"), "应发 failed 且含原因");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd springai-code-tui && mvn -q -Dtest=NotifyingCompactionStrategyTest test`
Expected: 编译失败——`NotifyingCompactionStrategy` 不存在。

- [ ] **Step 3: 实现装饰器**

创建 `NotifyingCompactionStrategy.java`：

```java
package com.example.springai.codetui.agent;

import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

/**
 * 装饰任意 {@link CompactionStrategy}，在 {@code compact()} 前后经 {@link AgentListener} 发压缩事件，
 * 让原本静默的压缩对 UI 可见。自动（阈值触发）与手动（/compact）两条路径各包一份，reason 区分。
 *
 * <p>started 只在真正进入策略时发（trigger 已判定要压缩），故不会误报；finished 转发
 * {@link CompactionResult} 的 {@code eventsRemoved()} / {@code tokensEstimatedSaved()}；
 * 失败时发 failed 并<b>重抛</b>（不吞异常，让上层 SessionService 的事务/版本语义照常）。
 */
public final class NotifyingCompactionStrategy implements CompactionStrategy {

    private final CompactionStrategy delegate;
    private final AgentListener listener;
    private final String reason;   // "auto" | "manual"

    public NotifyingCompactionStrategy(CompactionStrategy delegate, AgentListener listener, String reason) {
        this.delegate = delegate;
        this.listener = listener;
        this.reason = reason;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        listener.onCompactionStarted(reason);
        try {
            CompactionResult result = delegate.compact(request);
            listener.onCompactionFinished(result.eventsRemoved(), result.tokensEstimatedSaved());
            return result;
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
            throw e;
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd springai-code-tui && mvn -q -Dtest=NotifyingCompactionStrategyTest test`
Expected: PASS（2 个测试全绿）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/NotifyingCompactionStrategy.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/NotifyingCompactionStrategyTest.java
git commit -m "feat(code-tui): add NotifyingCompactionStrategy decorator for compaction events"
```

---

## Task 3: AgentTools 双策略 + `AgentRuntime`

自动策略包成 reason=`"auto"` 交给 advisor；新增激进的手动策略（keep=20/overlap=3）包成 reason=`"manual"`。`build()` 不再只返回 `ChatClient`，改返回 `AgentRuntime(client, sessionService, manualStrategy)`，把手动压缩所需的句柄暴露出来。

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java`（适配返回类型）
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java`（5 处 `build()` 调用改取 `.client()`）
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试**（离线装配测试，无需真实 API key）

创建 `AgentRuntimeTest.java`：

```java
package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** AgentTools.build 只做装配、不发网络请求：用假 key 也能造出完整 AgentRuntime。 */
class AgentRuntimeTest {

    private static DeepSeekChatModel dummyModel() {
        DeepSeekApi api = DeepSeekApi.builder().apiKey("test-key")
                .baseUrl("https://api.deepseek.com").build();
        return DeepSeekChatModel.builder().deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build()).build();
    }

    @Test
    void build_returnsRuntime_withAllHandles(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyModel(), root, new ConversationState());
        assertNotNull(rt.client(), "ChatClient 必须装配出来");
        assertNotNull(rt.sessionService(), "SessionService 必须暴露（供手动 /compact）");
        assertNotNull(rt.manualStrategy(), "手动压缩策略必须暴露");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd springai-code-tui && mvn -q -Dtest=AgentRuntimeTest test`
Expected: 编译失败——`AgentTools.AgentRuntime` 不存在、`build` 仍返回 `ChatClient`。

- [ ] **Step 3: 改 AgentTools —— 常量、import、双策略、返回 record**

(a) 在 import 区加：

```java
import org.springframework.ai.session.compaction.CompactionStrategy;
```

(b) 在 `MAX_EVENTS_TO_KEEP` 常量之后加手动策略常量：

```java
    /** 手动 /compact 的保留窗口：比自动的 120 激进得多，让「按需缩小上下文」真正生效。可调。 */
    private static final int MANUAL_MAX_EVENTS_TO_KEEP = 20;

    /** 手动策略的重叠窗口：须 >=0 且 < MANUAL_MAX_EVENTS_TO_KEEP，给摘要与保留段留一点上下文衔接。 */
    private static final int MANUAL_OVERLAP_SIZE = 3;
```

(c) 把 `build` 的返回类型与 advisor 装配段改成如下。定位 `SessionMemoryAdvisor memoryAdvisor = ...` 到 `return ChatClient.builder(model)...build();` 这一整段，替换为：

```java
        // 自动压缩策略（保留 120）→ 包一层通知装饰器（reason="auto"），让阈值触发的压缩对 UI 可见。
        CompactionStrategy autoStrategy = new NotifyingCompactionStrategy(
                RecursiveSummarizationCompactionStrategy.builder(auxClient)
                        .maxEventsToKeep(MAX_EVENTS_TO_KEEP)
                        .tokenCountEstimator(tokenCountEstimator).build(),
                listener, "auto");

        // 手动压缩策略（保留 20，更激进）→ 包一层通知装饰器（reason="manual"），供 /compact 直接调用。
        CompactionStrategy manualStrategy = new NotifyingCompactionStrategy(
                RecursiveSummarizationCompactionStrategy.builder(auxClient)
                        .maxEventsToKeep(MANUAL_MAX_EVENTS_TO_KEEP)
                        .overlapSize(MANUAL_OVERLAP_SIZE)
                        .tokenCountEstimator(tokenCountEstimator).build(),
                listener, "manual");

        SessionMemoryAdvisor memoryAdvisor = SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId(DEFAULT_USER_ID)
                .compactionTrigger(TokenCountTrigger.builder()
                        .threshold(COMPACTION_TOKEN_THRESHOLD)
                        .tokenCountEstimator(tokenCountEstimator).build())
                .compactionStrategy(autoStrategy)
                .build();

        ChatClient client = ChatClient.builder(model)
                .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                        .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                        .param(AgentEnvironment.AGENT_MODEL_KEY, MODEL_NAME))
                .defaultTools((Object[]) decorated)
                .defaultAdvisors(memoryAdvisor)
                .build();

        return new AgentRuntime(client, sessionService, manualStrategy);
```

(d) 把 `build` 方法签名的返回类型从 `ChatClient` 改为 `AgentRuntime`：

```java
    public static AgentRuntime build(DeepSeekChatModel model, Path root, AgentListener listener) {
```

(e) 在类内（`toLines` 方法之前）加 record 定义：

```java
    /**
     * build 的产物：装好的 ChatClient + 手动 /compact 所需的会话句柄。
     *
     * @param client         注册好工具与会话记忆 advisor 的 ChatClient
     * @param sessionService 会话服务（手动压缩经它 {@code compact(id, trigger, strategy)}）
     * @param manualStrategy 激进的手动压缩策略（已包通知装饰器，reason="manual"）
     */
    public record AgentRuntime(ChatClient client,
                               SessionService sessionService,
                               CompactionStrategy manualStrategy) {}
```

- [ ] **Step 4: 适配 CodeTuiApplication**

把 `CodeTuiApplication.java` 第 47 行 `ChatClient client = AgentTools.build(model, root, state);` 改为：

```java
        AgentTools.AgentRuntime runtime = AgentTools.build(model, root, state);
        ChatClient client = runtime.client();
```

（`new CodingAgent(...)` 那行本 Task 暂不动——仍是 4 参，Task 4 再改。此处保持编译通过。）

- [ ] **Step 5: 适配 CodingAgentSpikeTest 的 5 处 build 调用**

`CodingAgentSpikeTest.java` 内 5 处 `ChatClient client = AgentTools.build(buildModel(), tempRoot, rec);` 全部替换为：

```java
        ChatClient client = AgentTools.build(buildModel(), tempRoot, rec).client();
```

（`new CodingAgent(client, rec, "...", active)` 仍是 4 参，Task 4 再改。）

- [ ] **Step 6: 运行装配测试 + 全模块编译**

Run: `cd springai-code-tui && mvn -q -Dtest=AgentRuntimeTest test && mvn -q -o test-compile`
Expected: `AgentRuntimeTest` PASS；`test-compile` BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java
git commit -m "feat(code-tui): dual compaction strategies + AgentRuntime handle from AgentTools.build"
```

---

## Task 4: CodingAgent 手动压缩入口 + SubmitHandler.compact

`CodingAgent` 构造函数 +2 参（`SessionService` / 手动 `CompactionStrategy`），新增 `compact()`（后台线程）与包级 `runCompaction()`（同步、供测试直调）。`SubmitHandler` 加 `compact()` 默认方法。

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubmitHandler.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java`（6 参构造）
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSubmitErrorTest.java`（6 参构造，多传 `null, null`）
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java`（5 处 6 参构造）
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentCompactTest.java`

- [ ] **Step 1: 写失败测试**

创建 `CodingAgentCompactTest.java`：

```java
package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CodingAgent.runCompaction 用「强制触发器 + 手动策略」调 SessionService.compact，并把结果计数经 listener 透出。 */
class CodingAgentCompactTest {

    /** 记录 compact(...) 入参、返回预置结果的假 SessionService；其余方法用不到，抛未支持。 */
    private static final class FakeSessionService implements SessionService {
        final AtomicReference<String> sessionId = new AtomicReference<>();
        final AtomicReference<CompactionTrigger> trigger = new AtomicReference<>();
        final AtomicReference<CompactionStrategy> strategy = new AtomicReference<>();
        final CompactionResult result;
        FakeSessionService(CompactionResult result) { this.result = result; }

        @Override
        public CompactionResult compact(String id, CompactionTrigger t, CompactionStrategy s) {
            sessionId.set(id); trigger.set(t); strategy.set(s);
            // 模拟真实流程：SessionService 判定 trigger 命中后才进策略。这里直接进策略以驱动通知。
            return s.compact(CompactionRequest.of(null, List.of()));
        }
        @Override public Session create(CreateSessionRequest r) { throw new UnsupportedOperationException(); }
        @Override public Session findById(String id) { throw new UnsupportedOperationException(); }
        @Override public List<Session> findByUserId(String u) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }
        @Override public int deleteExpiredSessions(Instant i) { throw new UnsupportedOperationException(); }
        @Override public void appendEvent(SessionEvent e) { throw new UnsupportedOperationException(); }
        @Override public List<SessionEvent> getEvents(String id, EventFilter f) { throw new UnsupportedOperationException(); }
    }

    @Test
    void runCompaction_callsCompact_withForcingTrigger_andManualStrategy() {
        ConversationState state = new ConversationState();
        CompactionResult result = new CompactionResult(List.of(), List.of(), 999);
        FakeSessionService fake = new FakeSessionService(result);
        // 手动策略：包通知装饰器，驱动 state 的 started/finished（复用真实装饰器）。
        CompactionStrategy manual = new NotifyingCompactionStrategy(req -> result, state, "manual");

        CodingAgent agent = new CodingAgent(
                dummyChatClient(), state, "sess-1", new AtomicLong(), fake, manual);

        agent.runCompaction();   // 同步

        assertNotNull(fake.trigger.get(), "应调用 compact 并传入触发器");
        assertTrue(fake.trigger.get().shouldCompact(CompactionRequest.of(null, List.of())),
                "手动触发器必须恒为 true（强制压缩）");
        assertSame(manual, fake.strategy.get(), "应传入手动策略");
        assertTrue(state.drainPending().stream().anyMatch(l -> l.text().contains("999")),
                "结果计数应经 listener 落进 pending");
    }

    /** 占位 ChatClient：runCompaction 不触发对话，不会真正用到它。 */
    private static org.springframework.ai.chat.client.ChatClient dummyChatClient() {
        return (org.springframework.ai.chat.client.ChatClient) java.lang.reflect.Proxy.newProxyInstance(
                org.springframework.ai.chat.client.ChatClient.class.getClassLoader(),
                new Class[]{org.springframework.ai.chat.client.ChatClient.class},
                (p, m, a) -> { throw new UnsupportedOperationException(m.getName()); });
    }
}
```

> 注：`SessionService` 接口的抽象方法以 javap 为准（`create/findById/findByUserId/delete/deleteExpiredSessions/appendEvent/getEvents(id,filter)/compact`）。若某方法签名与此处不符导致编译报错，按编译器提示补齐/调整假实现即可——它们都用不到、抛 `UnsupportedOperationException` 即可。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd springai-code-tui && mvn -q -Dtest=CodingAgentCompactTest test`
Expected: 编译失败——`CodingAgent` 无 6 参构造、无 `runCompaction()`。

- [ ] **Step 3: SubmitHandler 加默认方法**

在 `SubmitHandler.java` 的 `selectModel` 之后加：

```java

    /** 手动压缩会话历史（/compact）。默认空实现，便于回显桩/测试桩省略。 */
    default void compact() { }
```

- [ ] **Step 4: 改 CodingAgent —— import、字段、构造、compact/runCompaction**

(a) import 区加：

```java
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
```

(b) 字段区（`private final AtomicLong activeTurnId;` 之后）加：

```java
    private final SessionService sessionService;
    private final CompactionStrategy manualStrategy;
```

(c) 构造函数替换为 6 参：

```java
    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy) {
        this.chatClient = chatClient;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
    }
```

(d) 在 `selectModel` 之后加手动压缩方法：

```java
    /**
     * 手动压缩：在后台线程强制立即压缩本会话。总结 LLM 调用可能耗时数分钟，绝不阻塞调用线程（UI）。
     * 并发闸门在 {@code CodeTuiView}（仅空闲且非压缩中才调用本方法），此处不再判活跃回合。
     */
    @Override
    public void compact() {
        Thread t = new Thread(this::runCompaction, "manual-compact");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次手动压缩（供 {@link #compact()} 的后台线程调用；包级可见便于测试直调）。
     * 用恒真触发器绕过 token 阈值，配合激进的手动策略。started/finished/failed 由
     * {@link NotifyingCompactionStrategy} 在策略内部发出；这里只兜底「进入策略前」抛出的异常。
     */
    void runCompaction() {
        try {
            sessionService.compact(sessionId, req -> true, manualStrategy);
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
        }
    }
```

- [ ] **Step 5: 更新 CodingAgent 的三处构造调用**

(a) `CodeTuiApplication.java`：把 `new CodingAgent(client, state, sessionId, activeTurnId);` 改为：

```java
        CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId,
                runtime.sessionService(), runtime.manualStrategy());
```

(b) `CodingAgentSubmitErrorTest.java` 第 38 行改为（该测试只验同步装配异常，不触发压缩，句柄传 null）：

```java
        CodingAgent agent = new CodingAgent(throwingOnPrompt("assembly-boom"), state, "s", new AtomicLong(),
                null, null);   // 本测试不触发 /compact，压缩句柄用不到
```

(c) `CodingAgentSpikeTest.java` 5 处：先把 `AgentTools.build(...).client()` 拆出 runtime 以便取句柄。将每处的两行

```java
        ChatClient client = AgentTools.build(buildModel(), tempRoot, rec).client();
        CodingAgent agent = new CodingAgent(client, rec, "spike-session-XXX", active);
```

替换为（`XXX` 保持各处原有后缀不变：text/tool/memory/cancel/todo）：

```java
        AgentTools.AgentRuntime rt = AgentTools.build(buildModel(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-XXX", active,
                rt.sessionService(), rt.manualStrategy());
```

- [ ] **Step 6: 运行测试 + 全模块编译**

Run: `cd springai-code-tui && mvn -q -Dtest=CodingAgentCompactTest test && mvn -q -o test-compile`
Expected: `CodingAgentCompactTest` PASS；`test-compile` BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSubmitErrorTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSpikeTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentCompactTest.java
git commit -m "feat(code-tui): CodingAgent.compact() manual compaction + SubmitHandler.compact seam"
```

---

## Task 5: CodeTuiView —— `/compact` 命令 + 状态行指示器

注册 `/compact`；派发时做并发闸门（空闲且非压缩中才触发，否则提示）；忙/压缩中提交走入队；drain 在压缩中不出队；状态行在压缩中显示「⟳ 正在压缩会话历史…（计时）+ 不确定型动画条」。

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`

> 本 Task 改的是终端渲染/命令派发（重 UI，无纯逻辑单测钩子），用「编译 + 手动冒烟」验证。每步给出精确锚点与代码。

- [ ] **Step 1: 注册 `/compact` 命令**

在 `COMMANDS` 列表里、`/model` 与 `/help` 之间插入一行（顺序影响斜杠菜单与 /help 展示）：

```java
            new SlashCommand("/compact", "压缩会话历史（手动）"),
```

改后应为：

```java
    private static final List<SlashCommand> COMMANDS = List.of(
            new SlashCommand("/model",   "切换 AI 模型"),
            new SlashCommand("/compact", "压缩会话历史（手动）"),
            new SlashCommand("/help",    "显示可用命令与快捷键"),
            new SlashCommand("/exit",    "退出"));
```

- [ ] **Step 2: 派发 `/compact` + 并发闸门**

在 `submitInput()` 里、`/help` 分支之前插入：

```java
        if (cmd.equals("/compact")) {
            inputState.clear();
            if (!state.isIdle() || state.isCompacting()) {
                state.setNotice("忙碌中，无法压缩");   // 有回合或已在压缩：拒绝并提示
            } else {
                onSubmit.compact();
            }
            return;
        }
```

- [ ] **Step 3: 压缩中提交走入队（不打断/不并发）**

把 `submitInput()` 末尾的忙判断

```java
        if (!state.isIdle()) {                       // 忙：排队，不打断当前回合（仿 Claude Code）
```

改为（压缩中也视作忙，新消息入队，压缩结束后由 drain 自动出队）：

```java
        if (!state.isIdle() || state.isCompacting()) {   // 忙或压缩中：排队，不打断/不并发
```

- [ ] **Step 4: drain 在压缩中不自动出队**

把 `drain()` 里的

```java
        if (state.isIdle()) {
            String next = state.pollQueued();
            if (next != null) dispatch(next);
        }
```

改为：

```java
        if (state.isIdle() && !state.isCompacting()) {   // 压缩中不出队，避免与手动压缩并发触发版本冲突
            String next = state.pollQueued();
            if (next != null) dispatch(next);
        }
```

- [ ] **Step 5: 状态行显示压缩指示器**

在 `statusLine()` 里、`slashMenuActive()` 分支之后、`int q = state.queuedCount();` 之前插入：

```java
        if (state.isCompacting()) return compactingStatus();   // 压缩指示器优先于普通思考/工具状态
```

- [ ] **Step 6: 实现 `compactingStatus()` 助手**

在 `shimmerSpans(...)` 方法之后加：

```java
    /**
     * 压缩状态行：「⟳ 正在压缩会话历史…（计时）」+ 一段左右往返的<b>不确定型</b>动画条。
     * 库不暴露压缩进度，故只做真实经过时间 + 往返光块（不伪造百分比）。动画由 drain 的 animTick 驱动。
     */
    private Element compactingStatus() {
        long sec = state.compactElapsedNanos() / 1_000_000_000L;
        String elapsed = sec >= 60 ? (sec / 60) + "m " + (sec % 60) + "s" : sec + "s";
        String label = "⟳ 正在压缩会话历史… (" + elapsed + ")  ";

        int width = 24;                                   // 进度条格数
        int period = width * 2;                           // 往返一轮
        int pos = (int) ((animTick / 2) % period);        // 每 2 帧前进一格
        int center = pos < width ? pos : period - pos;    // 三角波：来回移动

        List<Span> spans = new ArrayList<>(width + 1);
        spans.add(Span.styled(label, THINK));
        for (int i = 0; i < width; i++) {
            boolean lit = Math.abs(i - center) <= 1;
            spans.add(Span.styled(lit ? "▰" : "▱", lit ? SHIMMER_HI : THINK));
        }
        return richText(Text.from(Line.from(spans)));
    }
```

> `Span` / `Line` / `Text` / `richText` / `Element` / `THINK` / `SHIMMER_HI` / `animTick` 均已在本文件使用（见 `shimmerStatus`/`shimmerSpans`），无需新增 import。

- [ ] **Step 7: 编译**

Run: `cd springai-code-tui && mvn -q -o test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java
git commit -m "feat(code-tui): /compact command + live compaction status indicator in TUI"
```

---

## Task 6: 全量校验 + 手动冒烟

- [ ] **Step 1: 全量测试**

Run: `cd springai-code-tui && mvn -q test`
Expected: BUILD SUCCESS，所有单测通过（集成 spike 测试若无 `DEEPSEEK_API_KEY` 则跳过）。

- [ ] **Step 2: 手动冒烟（需 `DEEPSEEK_API_KEY`）**

Run:
```bash
export DEEPSEEK_API_KEY=<your-key>
cd springai-code-tui && mvn -q -o exec:java -Dexec.mainClass=com.example.springai.codetui.CodeTuiApplication
```

在 TUI 内验证：
1. 输入 `/` → 斜杠菜单出现 `/compact 压缩会话历史（手动）`。
2. 先随便对话一两轮，再输入 `/compact` 回车。
3. 观察状态行出现 `⟳ 正在压缩会话历史… (Ns)` + 左右往返的动画条；计时随秒递增。
4. 完成后状态行恢复，scrollback 出现灰色信息行 `✓ 已压缩会话：移除 N 个事件，约省 T tokens`；历史很短时应是 `• 无可压缩内容（历史尚短）`。
5. 压缩进行中再输入消息回车 → 显示「已排队 N 条」，压缩结束后自动提交。
6. 回合进行中输入 `/compact` → 状态行提示「忙碌中，无法压缩」，不触发压缩。

- [ ] **Step 3: 最终提交（若冒烟中有微调）**

```bash
git add -A && git commit -m "chore(code-tui): finalize compaction indicator + /compact after smoke test"
```

---

## 自检清单（写计划后复核）

- **Spec 覆盖**：指示器（自动+手动）→ Task 1/2/3/5；手动 /compact 语义（激进、恒真触发）→ Task 3/4；并发闸门 → Task 4 注 + Task 5 Step 2-4；不确定型动画条 + 真实计时 → Task 5 Step 6；完成/失败/无内容落 transcript → Task 1 Step 5；影响文件清单 9 个文件全部有对应 Task。✅
- **占位符**：无 TBD/TODO，每个代码步给出完整代码。✅
- **类型一致**：`onCompactionStarted/Finished/Failed`、`isCompacting()/compactReason()/compactElapsedNanos()`、`AgentRuntime(client/sessionService/manualStrategy)`、`runCompaction()`、`compactingStatus()` 全程一致；`CompactionResult.eventsRemoved()/tokensEstimatedSaved()` 与 javap 一致。✅
- **范围**：单一特性、单一实现计划，无需拆分。✅
