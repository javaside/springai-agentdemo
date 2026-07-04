# 子任务面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把本回合发起的所有子任务汇总成一个钉在输入框上方的固定面板，实时显示计数与运行中/完成/失败状态。

**Architecture:** 纯 UI 层新增，复用现有 `todo` 计划面板范式（原地替换、live 区渲染、不进 scrollback、回合起清空）。`ConversationState` 新增子任务状态列表并在既有 `onSubagentStarted/onToolStarted(taskId)/onSubagentFinished` 回调里维护它（**保留**现有 scrollback push，共存）；`SubagentRunner` 给 `onSubagentFinished` 补 `boolean ok` 维度作为"失败"状态数据源；`CodeTuiView` 加一个 `subtaskChildren` 面板并 `scope()` 到 render 树。

**Tech Stack:** Java 21、Spring AI（spring-ai-model **2.0.0**）、TamboUI（`dev.tamboui.*` 内联 TUI Toolkit）、JUnit 5、Maven（`./mvnw`）。

**关键约束（全程遵守）：**
- 中文回答；**不要 push**（本地仓库）。
- 校验命令排除联网易抖的 `CodingAgentSpikeTest`：`-Dtest='!CodingAgentSpikeTest'`。
- 提交信息结尾加：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。
- 内联 TUI 高亮/缩进**纯前景、禁用背景色条**（底色会串到下一项，已 pty 实机复现）。
- 每条 `OutputLine` = 一物理行（描述/工具入参先经 `summarize` 折叠空白）。
- 面板方法每帧被 `scope` eager 求值构造 → **首行必须判空**再解引用。
- 所有命令在模块目录 `springai-code-tui/` 下运行（下述命令均假设 cwd = 仓库根 `springai-agentdemo/`，用 `-pl springai-code-tui` 指定模块）。

**关键 API 事实（已用 javap 核实，勿再猜）：**
- `org.springframework.ai.chat.model.ChatModel` 抽象方法只有两个：`ChatResponse call(Prompt)` 与 `Flux<ChatResponse> stream(Prompt)`；`getDefaultOptions()` 是 default。
- `new ChatResponse(java.util.List<Generation>)`；`new Generation(new AssistantMessage(String))`。
- `ChatOptions.builder().build()` 返回的实例支持 `.mutate()`（2.0.0）。
- `SubmitHandler` 可用 lambda 桩：`(SubmitHandler) t -> null`。
- 工具注册名（`filterTools`/`allow`/`deny` 用的名字）是 `@Tool(name=...)` 注解值，非方法名。

---

## 文件结构

**修改：**
- `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java` — 加 `onSubagentFinished` 的 default 4 参（带 `ok`）重载。
- `springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubagentRunner.java` — 成功/异常分别用 `ok=true/false` 调 4 参。
- `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java` — 加 `SubtaskStatus` 枚举、`SubtaskView` 记录、内部可变 `Subtask` 持有者 + `subtasks` 列表、`subtaskSnapshot()`；在 `onSubagentStarted`/`onToolStarted(taskId)`/覆写 `onSubagentFinished(4参)`/`onTurnStarted` 里维护（保留现有 scrollback push）。
- `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java` — 加 `SUBTASK_CAP`、`subtaskHeaderText`/`subtaskRowText` 静态、`subtaskChildren`，并在 render 树 todo 面板之后 `scope()` 接入。

**新建测试：**
- `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentListenerSubagentTest.java`
- `springai-code-tui/src/test/java/com/example/springai/codetui/agent/SubagentRunnerOkTest.java`
- `springai-code-tui/src/test/java/com/example/springai/codetui/ui/SubtaskPanelTest.java`（面板文本静态方法）

**修改测试：**
- `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateTest.java` — 加子任务面板状态断言。

**不改：** `Theme.java`、`ScrollbackPrinter.java`（面板走 live 区，不经 `OutputLine`/`styleFor`）。

---

## Task 1: AgentListener 加 `onSubagentFinished` 成败维度（default 重载）

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentListenerSubagentTest.java`

- [ ] **Step 1: 写失败测试**

新建 `AgentListenerSubagentTest.java`：

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 新增的 4 参 onSubagentFinished（带 ok）默认委托回旧 3 参，保证既有实现零改动。 */
class AgentListenerSubagentTest {

    @Test
    void finishedFourArg_delegatesToThreeArg() {
        List<String> got = new ArrayList<>();
        AgentListener l = new StubListener() {
            @Override public void onSubagentFinished(long turnId, String taskId, String finalText) {
                got.add(finalText);
            }
        };
        l.onSubagentFinished(1L, "t1", "hello", true);    // 默认 4 参 → 3 参
        l.onSubagentFinished(1L, "t1", "world", false);
        assertEquals(List.of("hello", "world"), got, "4 参默认实现应把 finalText 转交 3 参");
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=AgentListenerSubagentTest`
Expected: 编译失败——`AgentListener` 无 4 参 `onSubagentFinished`（method not found）。

- [ ] **Step 3: 加 default 4 参重载**

在 `AgentListener.java` 的 3 参 `onSubagentFinished` 声明（第 20 行 `void onSubagentFinished(long turnId, String taskId, String finalText);`）**之后**插入：

```java
    /**
     * 子 agent 结束（带成败维度）。ok=true 正常返回、false 执行抛错。
     * 默认委托回 3 参版本，只有需要区分成败的实现（ConversationState 面板）覆写本方法。
     */
    default void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        onSubagentFinished(turnId, taskId, finalText);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=AgentListenerSubagentTest`
Expected: PASS（1 test）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentListenerSubagentTest.java
git commit -m "feat: AgentListener 加 onSubagentFinished 成败维度（default 委托）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: ConversationState 子任务面板状态模型

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateTest.java`

- [ ] **Step 1: 写失败测试**

在 `ConversationStateTest.java` 末尾（最后一个 `}` 之前）追加：

```java
    // ── 子任务面板（本回合汇总，独立于 scrollback） ──

    @Test
    void subtaskPanel_started_addsRunningEntry() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证模块");
        List<ConversationState.SubtaskView> snap = s.subtaskSnapshot();
        assertEquals(1, snap.size());
        assertEquals("explore", snap.get(0).agentName());
        assertEquals("分析认证模块", snap.get(0).description());
        assertEquals(ConversationState.SubtaskStatus.RUNNING, snap.get(0).status());
    }

    @Test
    void subtaskPanel_finishedOk_marksDone() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "d");
        s.onSubagentFinished(1L, "t1", "结论文本", true);
        assertEquals(ConversationState.SubtaskStatus.DONE, s.subtaskSnapshot().get(0).status());
    }

    @Test
    void subtaskPanel_finishedFail_marksFailed() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "d");
        s.onSubagentFinished(1L, "t1", "子 agent 执行失败：boom", false);
        assertEquals(ConversationState.SubtaskStatus.FAILED, s.subtaskSnapshot().get(0).status());
    }

    @Test
    void subtaskPanel_toolStart_updatesCurrentTool() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "d");
        s.onToolStarted(1L, "t1", "Grep", "{\"pattern\":\"auth\"}");
        assertEquals("Grep", s.subtaskSnapshot().get(0).currentTool());
    }

    @Test
    void subtaskPanel_turnStart_clears() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "d");
        s.onTurnStarted(2L);
        assertTrue(s.subtaskSnapshot().isEmpty(), "新回合应清空子任务面板");
    }

    @Test
    void subtaskPanel_unknownTaskId_noCrashNoEntry() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onToolStarted(1L, "ghost", "Grep", "{}");         // 无对应子任务
        s.onSubagentFinished(1L, "ghost", "x", true);       // 无对应子任务
        assertTrue(s.subtaskSnapshot().isEmpty(), "未知 taskId 不产生面板条、不抛异常");
    }

    @Test
    void subtaskPanel_coexistsWithScrollbackLines() {
        // 回归：面板不取代 scrollback 内联详情行（共存形态）
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证");
        s.onToolStarted(1L, "t1", "Grep", "{\"pattern\":\"auth\"}");
        s.onSubagentFinished(1L, "t1", "认证走 JWT", true);
        List<ConversationState.OutputLine> out = s.drainPending();
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_START));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_TOOL));
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_END));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=ConversationStateTest`
Expected: 编译失败——`SubtaskView`/`SubtaskStatus`/`subtaskSnapshot` 未定义。

- [ ] **Step 3: 实现面板状态模型**

在 `ConversationState.java` 中做四处改动。

**(a)** 在 `OutputLine` 记录定义之后（第 44 行 `}` 之后）新增枚举、只读视图记录与内部可变持有者：

```java
    /** 子任务状态（面板显示）。 */
    public enum SubtaskStatus { RUNNING, DONE, FAILED }

    /** 子任务只读快照（供渲染线程读，与内部可变状态解耦）。 */
    public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool) {}

    /** 内部可变持有者：status/currentTool 就地更新。仅本类访问。 */
    private static final class Subtask {
        final String taskId;
        final String agentName;
        final String description;
        SubtaskStatus status = SubtaskStatus.RUNNING;
        String currentTool = "";
        Subtask(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }
```

**(b)** 在字段区（第 53 行 `todo` 列表附近）新增：

```java
    private final List<Subtask> subtasks = new ArrayList<>();   // 本回合子任务（固定面板显示，不进 scrollback）
```

**(c)** 在 `onTurnStarted`（第 156 行 `todo.clear();` 之后）新增一行：

```java
        subtasks.clear();                 // 新回合清空子任务面板，与 todo 同生命周期
```

**(d)** 维护三个回调 + 加快照方法。

在 `onSubagentStarted` 方法体末尾（第 201 行 `OutputLine.Kind.SUBAGENT_START));` 之后、方法闭合 `}` 之前）追加：

```java
        subtasks.add(new Subtask(taskId, agentName, d));   // d 已 summarize（一物理行）
```

覆写 4 参 `onSubagentFinished`，并把原 3 参改为委托到 4 参（`ok=true`）。将现有 3 参方法（第 205-208 行）**整体替换**为：

```java
    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText) {
        onSubagentFinished(turnId, taskId, finalText, true);
    }

    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        if (turnId != acceptingTurnId) return;
        pending.add(new OutputLine("  ⎿ " + firstLine(finalText), OutputLine.Kind.SUBAGENT_END));   // 保留 scrollback 结论行
        Subtask st = findSubtask(taskId);
        if (st != null) {
            st.status = ok ? SubtaskStatus.DONE : SubtaskStatus.FAILED;
            st.currentTool = "";
        }
    }
```

在 4 参 `onToolStarted`（第 212-218 行）方法体末尾、闭合 `}` 之前追加（保留其原有 scrollback push 不动）：

```java
        Subtask st = findSubtask(taskId);
        if (st != null) st.currentTool = toolName;
```

在 `todoSnapshot()`（第 237 行）之后新增快照方法与查找辅助：

```java
    /** 当前子任务快照（供固定面板显示）。返回不可变值副本，与内部可变状态解耦。 */
    public synchronized List<SubtaskView> subtaskSnapshot() {
        List<SubtaskView> out = new ArrayList<>(subtasks.size());
        for (Subtask s : subtasks) out.add(new SubtaskView(s.agentName, s.description, s.status, s.currentTool));
        return out;
    }

    /** 按 taskId 定位子任务；找不到（迟到/已清）返回 null，调用方静默忽略。 */
    private Subtask findSubtask(String taskId) {
        for (Subtask s : subtasks) if (s.taskId.equals(taskId)) return s;
        return null;
    }
```

> 注意：`onToolStarted(4 参)` 方法里局部变量名不要与已有的 `s`（`summarize(input)` 的结果）冲突——已有代码里是 `String s = summarize(input);`。这里新增的持有者用 `st`，无冲突。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=ConversationStateTest`
Expected: PASS（含新增 7 个子任务面板用例 + 既有 `subagentEvents_produceNestedOutputLines` 仍绿）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateTest.java
git commit -m "feat: ConversationState 维护本回合子任务状态（面板数据源）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: SubagentRunner 上报成败维度

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubagentRunner.java:41-67`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/SubagentRunnerOkTest.java`

- [ ] **Step 1: 写失败测试**

新建 `SubagentRunnerOkTest.java`（用假 ChatModel + 假 LlmProvider，不联网）：

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SubagentRunner.run 成功传 ok=true、异常传 ok=false 并抛出。用假 ChatModel/Provider，不联网。 */
class SubagentRunnerOkTest {

    /** 记录 4 参 onSubagentFinished 的 ok。 */
    private static final class RecordingListener extends StubListener {
        Boolean ok;
        String finalText;
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
            this.ok = ok;
            this.finalText = finalText;
        }
    }

    /** 假 ChatModel：success 返回固定文本；否则 call 抛异常。ChatModel 抽象方法只有 call/stream。 */
    private static ChatModel chatModel(boolean success) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (!success) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    /** 假 LlmProvider：可用、返回上面的假 ChatModel、options 用空 ChatOptions（支持 mutate）。 */
    private static LlmProvider provider(ChatModel model) {
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    @Test
    void success_emitsOkTrue_andReturnsContent() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel(true))));
        RecordingListener lis = new RecordingListener();
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis);
        String out = runner.run(spec(), "hi", "desc", 1L);
        assertEquals("done", out);
        assertEquals(Boolean.TRUE, lis.ok);
    }

    @Test
    void failure_emitsOkFalse_andRethrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel(false))));
        RecordingListener lis = new RecordingListener();
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis);
        assertThrows(RuntimeException.class, () -> runner.run(spec(), "hi", "desc", 1L));
        assertEquals(Boolean.FALSE, lis.ok);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=SubagentRunnerOkTest`
Expected: FAIL——`failure_emitsOkFalse_andRethrows` 里 `lis.ok` 仍为 null（现 catch 走的是 3 参 `onSubagentFinished`，RecordingListener 只覆写了 4 参）；`success_emitsOkTrue` 同理 `lis.ok==null`。

- [ ] **Step 3: 改 run() 走 4 参**

在 `SubagentRunner.java` 的 `run` 方法里，把成功路径与 catch 路径的 `onSubagentFinished` 调用都改成 4 参。

成功路径（第 60-62 行）：

```java
            String finalText = result == null ? "" : result;
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
```

catch 路径（第 63-66 行）：

```java
        } catch (RuntimeException ex) {
            listener.onSubagentFinished(parentTurnId, taskId, "子 agent 执行失败：" + ex.getMessage(), false);
            throw ex;
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=SubagentRunnerOkTest,SubagentRunnerTest`
Expected: PASS（新 2 个 + 既有 filterTools 2 个）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubagentRunner.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/SubagentRunnerOkTest.java
git commit -m "feat: SubagentRunner 上报子 agent 成败（ok=true/false）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: CodeTuiView 子任务面板渲染

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/SubtaskPanelTest.java`

- [ ] **Step 1: 写失败测试**

新建 `SubtaskPanelTest.java`（断言面板文本静态方法，确定性、无需起 TUI）：

```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.SubtaskStatus;
import com.example.springai.codetui.ui.ConversationState.SubtaskView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子任务面板文本组装（计数标题 / 行文本 / 运行行附当前工具）。 */
class SubtaskPanelTest {

    private static SubtaskView v(String agent, String desc, SubtaskStatus st, String tool) {
        return new SubtaskView(agent, desc, st, tool);
    }

    @Test
    void header_countsDoneRunningAndFailed() {
        List<SubtaskView> subs = List.of(
                v("explore", "a", SubtaskStatus.DONE, ""),
                v("plan", "b", SubtaskStatus.RUNNING, "Grep"),
                v("bash", "c", SubtaskStatus.FAILED, ""));
        String h = CodeTuiView.subtaskHeaderText(subs);
        assertTrue(h.contains("✓1"), "1 完成");
        assertTrue(h.contains("▶1"), "1 运行");
        assertTrue(h.contains("✗1"), "1 失败");
    }

    @Test
    void header_noFailedSegmentWhenZero() {
        List<SubtaskView> subs = List.of(v("explore", "a", SubtaskStatus.DONE, ""));
        assertTrue(!CodeTuiView.subtaskHeaderText(subs).contains("失败"), "无失败时不显示失败段");
    }

    @Test
    void row_doneShowsCheckAndAgentAndDesc() {
        String r = CodeTuiView.subtaskRowText(v("explore", "分析认证", SubtaskStatus.DONE, "Grep"));
        assertTrue(r.contains("✓"), "完成图标");
        assertTrue(r.contains("explore"), "agent 类型");
        assertTrue(r.contains("分析认证"), "描述");
        assertTrue(!r.contains("Grep"), "非运行态不附当前工具");
    }

    @Test
    void row_runningAppendsCurrentTool() {
        String r = CodeTuiView.subtaskRowText(v("plan", "设计缓存", SubtaskStatus.RUNNING, "Grep"));
        assertTrue(r.contains("▶"), "运行图标");
        assertTrue(r.contains("· Grep"), "运行行尾附当前工具");
    }

    @Test
    void row_runningNoToolOmitsTail() {
        String r = CodeTuiView.subtaskRowText(v("plan", "设计缓存", SubtaskStatus.RUNNING, ""));
        assertTrue(!r.contains("·"), "无当前工具时不附尾巴");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=SubtaskPanelTest`
Expected: 编译失败——`CodeTuiView.subtaskHeaderText`/`subtaskRowText` 未定义。

- [ ] **Step 3: 实现面板渲染**

在 `CodeTuiView.java` 做三处改动。

**(a)** 常量区（第 73 行 `TODO_CAP` 附近）新增：

```java
    private static final int SUBTASK_CAP = 6;    // 子任务面板最多显示几条
```

**(b)** `render()` 方法（第 140-155 行）：顶部取快照 + 在 todo 面板 `scope` 之后插入子任务面板 `scope`。

把方法体开头改为：

```java
        List<String> todos = state.todoSnapshot();
        List<ConversationState.SubtaskView> subs = state.subtaskSnapshot();
        List<String> queued = state.queuedSnapshot();
```

并在 `scope(!todos.isEmpty(), todoChildren(todos)),` 之后新增一行：

```java
                scope(!subs.isEmpty(), subtaskChildren(subs)),
```

**(c)** 在计划面板方法区（`todoChildren` 第 863 行附近）新增三个方法：

```java
    /** 子任务面板：计数标题 + 每条一行（✓/▶/✗ 分色，纯前景无底色），SUBTASK_CAP 溢出显示"还有 N 项"。 */
    private Element[] subtaskChildren(List<ConversationState.SubtaskView> subs) {
        if (subs == null || subs.isEmpty()) return new Element[0];   // scope eager 求值：首行判空
        List<Element> els = new ArrayList<>();
        els.add(text(subtaskHeaderText(subs)).style(TODO_TITLE));
        int shown = Math.min(subs.size(), SUBTASK_CAP);
        for (int i = 0; i < shown; i++) els.add(subtaskRow(subs.get(i)));
        if (subs.size() > SUBTASK_CAP) {
            els.add(text("  … 还有 " + (subs.size() - SUBTASK_CAP) + " 项").style(DIM));
        }
        return els.toArray(new Element[0]);
    }

    /** 一条子任务：✓完成=绿 / ▶运行=亮黄加粗 / ✗失败=红（纯前景）。 */
    private static Element subtaskRow(ConversationState.SubtaskView s) {
        Style st = switch (s.status()) {
            case DONE -> OK;
            case FAILED -> ERROR;
            case RUNNING -> TODO_RUN;
        };
        return text(subtaskRowText(s)).style(st);
    }

    /** 面板标题文本："⟐ 子任务  ✓N 完成 · ▶M 运行[ · ✗K 失败]"。 */
    static String subtaskHeaderText(List<ConversationState.SubtaskView> subs) {
        long done = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.DONE).count();
        long running = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.RUNNING).count();
        long failed = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.FAILED).count();
        StringBuilder h = new StringBuilder("⟐ 子任务  ✓" + done + " 完成 · ▶" + running + " 运行");
        if (failed > 0) h.append(" · ✗").append(failed).append(" 失败");
        return h.toString();
    }

    /** 一条子任务的行文本："  <图标> <agent>  <描述>[ · <当前工具>]"（运行态且有当前工具才附尾巴）。 */
    static String subtaskRowText(ConversationState.SubtaskView s) {
        String icon = switch (s.status()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            case RUNNING -> "▶";
        };
        String tail = (s.status() == ConversationState.SubtaskStatus.RUNNING
                && s.currentTool() != null && !s.currentTool().isEmpty())
                ? " · " + s.currentTool() : "";
        return "  " + icon + " " + s.agentName() + "  " + s.description() + tail;
    }
```

> `OK`/`ERROR`/`TODO_RUN`/`DIM`/`TODO_TITLE` 均已由类顶部 `import static ...Theme.*` 引入（见第 42 行）。全为纯前景样式，无底色。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl springai-code-tui test -Dtest=SubtaskPanelTest`
Expected: PASS（5 tests）。

- [ ] **Step 5: 面板渲染冒烟（不崩 + scope 空安全）**

在 `CodeTuiViewAskTest.java` 之外，快速用既有 `renderForTest()` 保护"有子任务时构造 render 树不崩"。在 `SubtaskPanelTest.java` 追加：

```java
    @Test
    void renderTree_withSubtasks_doesNotThrow() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "分析认证");
        CodeTuiView v = new CodeTuiView(s, (com.example.springai.codetui.agent.SubmitHandler) t -> null,
                java.nio.file.Path.of("."));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(v::renderForTest);
    }
```

Run: `./mvnw -q -pl springai-code-tui test -Dtest=SubtaskPanelTest`
Expected: PASS（6 tests）。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/SubtaskPanelTest.java
git commit -m "feat: CodeTuiView 子任务汇总面板（纯前景 ✓/▶/✗，钉输入框上方）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 全量回归 + pty 实机冒烟 + 重打包

**Files:**
- Create（临时，不提交）: `/private/tmp/claude-501/.../scratchpad/subtask-panel-smoke/`（pty 冒烟脚本）
- 无源码改动（仅验证）。

- [ ] **Step 1: 全量测试（排除联网 spike）**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && ./mvnw -pl springai-code-tui test -Dtest='!CodingAgentSpikeTest'`
Expected: `BUILD SUCCESS`，Failures: 0 / Errors: 0。若有失败先修再继续。

- [ ] **Step 2: 重打包**

Run: `./mvnw -q -pl springai-code-tui package -DskipTests`
Expected: `BUILD SUCCESS`，生成可运行 jar（`springai-code-tui/target/*.jar`）。

- [ ] **Step 3: pty+pyte 实机冒烟**

参照记忆 `pty-smoke-inline-tui-needs-winsize-and-term`：用 `pty.fork()` + `ioctl TIOCSWINSZ`（如 40×120）+ `TERM=xterm-256color` 起一个最小 harness，从守护线程按序触发 `onTurnStarted → onSubagentStarted(t1,explore,...) → onToolStarted(t1,Grep,...) → onSubagentStarted(t2,plan,...) → onSubagentFinished(t1,...,true)`，用 pyte 抓屏断言：

1. 输入框上方出现面板标题行含 `⟐ 子任务` 且计数正确（如 `✓1 完成 · ▶1 运行`）。
2. 出现 `▶ plan` 行、`✓ explore` 行。
3. 面板所有单元格 `cell.bg` 均为默认（无底色串行）——纯前景铁律。
4. 与 `todo` 面板并存时不错位（可选：同时触发一次 `onTodoUpdated` 验证两面板叠放）。

harness 与脚本放 scratchpad，打印 `ALL_PASS` / `FAIL: <原因>`。
Expected: `ALL_PASS`。

- [ ] **Step 4: 提交（若冒烟脚本需留档则单独提交，否则跳过）**

pty 脚本为临时验证物，默认不入库。若本任务无源码改动，则无需提交；确认工作区干净：

Run: `git status --porcelain`
Expected: 无未提交的 `src/` 改动（前 4 个 Task 已各自提交）。

---

## Self-Review

**Spec 覆盖核对（逐条对照 `2026-07-04-subtask-panel-design.md`）：**
- §2 纯可视化、串行不变 → 未改 `SubagentRunner` 执行模型，仅加 `ok` 上报（Task 3）。✓
- §3 共存形态（详情留 scrollback + 汇总面板）→ Task 2 保留所有 scrollback push，新增独立 `subtasks` 列表；`subtaskPanel_coexistsWithScrollbackLines` 断言。✓
- §3 视觉：纯前景无底色、✓/▶/✗ 配色、标题计数、运行行附当前工具 → Task 4 `subtaskRow`/`subtaskHeaderText`/`subtaskRowText` + Task 5 pty 断言 `cell.bg` 默认。✓
- §4.1 ConversationState 数据流（started/toolStarted/finished(ok)/turnStarted 清空/snapshot）→ Task 2 全覆盖。✓
- §4.2 AgentListener default 4 参重载 → Task 1。✓
- §4.3 SubagentRunner 成功 true / catch false → Task 3。✓
- §4.4 render 树 todo 之后 scope 接入、SUBTASK_CAP、首行判空、不改 Theme/ScrollbackPrinter → Task 4。✓
- §5 边界：taskId 未命中静默、scope 空安全、cap → Task 2/4 用例覆盖。✓
- §6 测试：ConversationState/SubagentRunner/CodeTuiView + pty → Task 2/3/4/5。✓
- §7 验收 1-7 → 分散在 Task 2/4/5，pty 覆盖 6，全量 + package 覆盖 7。✓

**占位符扫描：** 无 TBD/TODO/"类似 Task N"；每个代码步骤均含完整代码。✓

**类型一致性：** `SubtaskStatus{RUNNING,DONE,FAILED}`、`SubtaskView(agentName,description,status,currentTool)`、`subtaskSnapshot()`、`subtaskHeaderText`/`subtaskRowText`/`subtaskChildren`/`subtaskRow`、`SUBTASK_CAP` 在 Task 2/4 定义与引用一致；`onSubagentFinished(long,String,String,boolean)` 签名在 Task 1/2/3 一致。✓
