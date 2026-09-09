# 子 agent TodoWrite 可见性与 -c 恢复面板重建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 子 agent 默认不再暴露 TodoWrite（其内容注定被丢弃），且 `-c` 恢复会话时从历史重建 todo 面板（而不是空白直到主 agent 恰好再调一次 TodoWrite）。

**Architecture:** 两处独立、互不依赖的改动。① 一行 frontmatter 配置改动（`general-purpose.md` 的 `disallowedTools`）。② `HistoryReplay`（`ui` 包）新增一个纯函数 `lastTodoSnapshot`，从已回放的历史消息里找最后一次 TodoWrite 调用、反序列化、复用 `AgentTools.toLines`（升 public）格式化成行；`ConversationState.replayHistory` 在原有恢复逻辑里追加调用它、直接写入 `todo` 字段——不复用带 `turnId` 门卫的实时事件路径，因为恢复重建是一次性初始化，不是实时事件。

**Tech Stack:** Java 17、JUnit 5、Spring AI `ChatModel`/`AssistantMessage` 消息模型、Jackson 3（`tools.jackson.databind.ObjectMapper`，本代码库既有约定，注意不是 `com.fasterxml.jackson`）。

**背景与根因**：见已批准的设计文档 `docs/superpowers/specs/2026-09-09-subagent-todowrite-visibility-design.md`（commit `77cb52e5`）。

---

### Task 1: `general-purpose` 默认拒绝 TodoWrite

**Files:**
- Modify: `springai-code-tui/src/main/resources/agents/general-purpose.md`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentDefinitionsTest.java`

**背景**：4 个内置子 agent 里，只有 `general-purpose` 用黑名单式 `disallowedTools`（当前只禁 `AskUserQuestionTool`，默认继承全部工具，因此当前能拿到 TodoWrite）；`explore`/`plan`/`bash` 三个都用白名单式 `tools:`，TodoWrite 从未在列，不受本任务影响、不用动。子 agent 调 TodoWrite 的完整内容会被 `ConversationState.onTodoUpdated`（`taskId != null` 分支）直接丢弃，只在 scrollback 留一行面包屑——见设计文档根因 1。

- [ ] **Step 1: 写失败测试**

在 `SubagentDefinitionsTest.java` 里，紧跟在已有的 `generalPurposeDeniesAskTool` 方法后面添加：

```java
    /** TodoWrite 的完整内容对子 agent 注定被丢弃（见 ConversationState.onTodoUpdated 的 taskId!=null 分支），
     *  默认放行只是让子 agent 白白多花一次工具往返。 */
    @Test
    void generalPurposeDeniesTodoWrite() {
        SubagentSpec gp = SubagentLoader.loadBuiltins().get("general-purpose");
        assertTrue(gp.denyTools().contains("TodoWrite"),
                "general-purpose 应 deny TodoWrite（内容会被丢弃，见 ConversationState.onTodoUpdated）");
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentDefinitionsTest`
Expected: `generalPurposeDeniesTodoWrite` FAIL（`denyTools()` 当前不含 `"TodoWrite"`），其余既有测试仍 PASS。

- [ ] **Step 3: 修改 general-purpose.md frontmatter**

当前文件（`springai-code-tui/src/main/resources/agents/general-purpose.md`）开头：

```markdown
---
name: general-purpose
description: General-purpose agent for researching complex questions and executing multi-step tasks. Use when a task needs several rounds of searching, reading, and editing. Runs headless — has access to all tools except user-question prompts.
disallowedTools: AskUserQuestionTool
---
```

改成：

```markdown
---
name: general-purpose
description: General-purpose agent for researching complex questions and executing multi-step tasks. Use when a task needs several rounds of searching, reading, and editing. Runs headless — has access to all tools except user-question prompts and TodoWrite.
disallowedTools: AskUserQuestionTool, TodoWrite
---
```

（`description` 一并更新，避免"except user-question prompts"这句话在加了新的 deny 项之后变得不准确；`SubagentLoader.java:78` 已按逗号 split+trim 解析 `disallowedTools`，两项写法不需要额外代码改动。）

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentDefinitionsTest`
Expected: 全部 4 个测试方法（`allFourLoadAndAreIdentityNeutral` / `toolsFieldsUseRealNames` / `disallowedToolsUseRealNames` / `generalPurposeDeniesAskTool` / `generalPurposeDeniesTodoWrite`）PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/resources/agents/general-purpose.md \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentDefinitionsTest.java
git commit -m "fix(code-tui): general-purpose 子 agent 默认拒绝 TodoWrite（内容注定被丢弃）"
```

### Task 2: `-c` 恢复时重建 todo 面板

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:1088`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java:298-316`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/HistoryReplayTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateTest.java`

**背景**：`ConversationState.replayHistory` 目前只把历史转成 scrollback 文本（`HistoryReplay.toReplayLines`），从未碰 `todo` 字段——恢复那一刻面板必然是空的，直到主 agent 在新会话里再调一次 TodoWrite。见设计文档根因 2、3。

- [ ] **Step 1: 把 `AgentTools.toLines` 升为 public（前置、机械改动，无需单独测试驱动——由 Step 3 的新测试间接覆盖其可达性）**

当前（`AgentTools.java:1087-1088`）：

```java
    /** 把 {@link Todos} 转成可显示的行：状态标记 + 内容。 */
    static List<String> toLines(Todos todos) {
```

改成：

```java
    /** 把 {@link Todos} 转成可显示的行：状态标记 + 内容。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。 */
    public static List<String> toLines(Todos todos) {
```

（方法体不变；这行是本代码库既有的"升 public 仅为跨包装配"惯用注释，`SessionEvents.java`/`ToolEventCallback.java`/`RetryPolicy.java` 等多处已用同一句原文。）

- [ ] **Step 2: 写失败测试——`HistoryReplay.lastTodoSnapshot`**

在 `HistoryReplayTest.java` 里，紧跟在已有的 `fullTurnOrderingAndUserTurnCount` 方法（文件末尾那个测试）后面、类的闭合大括号之前添加：

```java

    // ── -c 恢复时重建 todo 面板 ──

    @Test
    void lastTodoSnapshotReturnsLatestOfMultipleCalls() {
        AssistantMessage first = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "TodoWrite",
                        "{\"todos\":[{\"content\":\"步骤一\",\"activeForm\":\"正在步骤一\",\"status\":\"in_progress\"}]}")))
                .build();
        AssistantMessage second = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("c2", "function", "TodoWrite",
                        "{\"todos\":[{\"content\":\"步骤一\",\"activeForm\":\"正在步骤一\",\"status\":\"completed\"},"
                        + "{\"content\":\"步骤二\",\"activeForm\":\"正在步骤二\",\"status\":\"in_progress\"}]}")))
                .build();
        List<String> lines = HistoryReplay.lastTodoSnapshot(List.of(first, second));
        assertEquals(List.of("✓ 步骤一", "▶ 步骤二"), lines, "应取最后一次调用，而非第一次");
    }

    @Test
    void lastTodoSnapshotEmptyWhenNoTodoWriteCall() {
        AssistantMessage am = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "Grep", "{\"pattern\":\"foo\"}")))
                .build();
        assertTrue(HistoryReplay.lastTodoSnapshot(List.of(am)).isEmpty());
        assertTrue(HistoryReplay.lastTodoSnapshot(null).isEmpty());
        assertTrue(HistoryReplay.lastTodoSnapshot(List.of()).isEmpty());
    }

    @Test
    void lastTodoSnapshotToleratesMalformedArguments() {
        AssistantMessage am = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "TodoWrite", "not valid json")))
                .build();
        assertTrue(HistoryReplay.lastTodoSnapshot(List.of(am)).isEmpty(), "格式异常应静默返回空，不抛异常");
    }
```

不需要新增 import——`AssistantMessage`/`List`/`assertEquals`/`assertTrue` 在该文件里已经导入。

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=HistoryReplayTest`
Expected: 编译失败（`lastTodoSnapshot` 方法不存在）——这是本步预期的"失败"形式。

- [ ] **Step 4: 实现 `HistoryReplay.lastTodoSnapshot`**

`HistoryReplay.java` 顶部 import 区（第 1-13 行）当前：

```java
import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import dev.tamboui.text.CharWidth;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
```

改成（新增 3 行：`AgentTools`、`Todos`、`ObjectMapper`）：

```java
import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import dev.tamboui.text.CharWidth;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
```

紧接着，构造函数与 `toReplayLines` 方法之间当前是：

```java
    private HistoryReplay() {}

    /** 历史消息 → 回放行（不含头尾提示，由调用方补）。null/空返回空列表。 */
    static List<OutputLine> toReplayLines(List<Message> messages) {
```

改成（插入新字段）：

```java
    private HistoryReplay() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 历史消息 → 回放行（不含头尾提示，由调用方补）。null/空返回空列表。 */
    static List<OutputLine> toReplayLines(List<Message> messages) {
```

再往下，`toReplayLines` 方法结束处、`userTurns` 方法开始处当前是：

```java
                default -> { /* SYSTEM：grounding 提示，跳过不回放 */ }
            }
        }
        return out;
    }

    /**
     * 历史里的用户轮数（供头部提示，比原始事件数直观）。
```

改成（插入新方法）：

```java
                default -> { /* SYSTEM：grounding 提示，跳过不回放 */ }
            }
        }
        return out;
    }

    /** 恢复会话时重建 todo 面板：找历史里最后一次 TodoWrite 调用，取其参数还原成显示行（格式与
     *  {@link AgentTools#toLines} 完全一致——直接复用，不新写一份格式化逻辑）。找不到、或参数反序列化
     *  失败（容忍老版本残留数据）都返回空列表，不让恢复流程崩掉。 */
    static List<String> lastTodoSnapshot(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        String lastArgs = null;
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    if ("TodoWrite".equals(tc.name())) {
                        lastArgs = tc.arguments();
                    }
                }
            }
        }
        if (lastArgs == null) {
            return List.of();
        }
        try {
            return AgentTools.toLines(MAPPER.readValue(lastArgs, Todos.class));
        } catch (Exception ignore) {
            return List.of();
        }
    }

    /**
     * 历史里的用户轮数（供头部提示，比原始事件数直观）。
```

（`catch (Exception ignore)` 是本代码库同包 `DiffRenderer.java` 已有的防御性 JSON 解析惯例，风格保持一致。）

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=HistoryReplayTest`
Expected: 全部方法 PASS（含新增的 3 个 + 原有全部）。

- [ ] **Step 6: 写失败测试——`ConversationState.replayHistory` 重建面板**

在 `ConversationStateTest.java` 顶部 import 区（第 3-9 行，`io.github.javaside...OutputLine` 之后、`org.junit.jupiter.api.Test` 之前或之后均可）追加：

```java
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
```

在文件末尾、最后一个已有测试方法 `taskPanel_modelLabelWithEmbeddedNewline_foldedToSingleLine` 的闭合 `}` 之后、类的闭合 `}` 之前添加：

```java

    // ── -c 恢复时重建 todo 面板 ──

    @Test
    void replayHistorySeedsTodoPanelFromLastTodoWriteCall() {
        ConversationState state = new ConversationState();
        List<Message> history = List.of(
                new UserMessage("开始任务"),
                AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "TodoWrite",
                                "{\"todos\":[{\"content\":\"步骤一\",\"activeForm\":\"正在步骤一\",\"status\":\"completed\"},"
                                + "{\"content\":\"步骤二\",\"activeForm\":\"正在步骤二\",\"status\":\"in_progress\"}]}")))
                        .build());
        state.replayHistory(history);
        assertEquals(List.of("✓ 步骤一", "▶ 步骤二"), state.todoSnapshot(), "恢复应从历史重建 todo 面板");
    }

    @Test
    void replayHistoryLeavesTodoPanelEmptyWhenHistoryHasNoTodoWrite() {
        ConversationState state = new ConversationState();
        state.replayHistory(List.of(new UserMessage("问题"), new AssistantMessage("回答")));
        assertTrue(state.todoSnapshot().isEmpty(), "历史无 TodoWrite 时面板应保持空");
    }
```

- [ ] **Step 7: 运行测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStateTest`
Expected: `replayHistorySeedsTodoPanelFromLastTodoWriteCall` FAIL（`todoSnapshot()` 返回空，因为 `replayHistory` 还没有重建面板的逻辑）；`replayHistoryLeavesTodoPanelEmptyWhenHistoryHasNoTodoWrite` 可能已经 PASS（面板本来就是空的）——这没关系，下一步实现后两条都应保持 PASS。

- [ ] **Step 8: 实现 `ConversationState.replayHistory` 的面板重建**

当前（`ConversationState.java:298-316`）：

```java
    /**
     * -c 恢复启动：把历史消息回放进 scrollback（仿 Claude Code --continue），直观重现上次对话，
     * 而非只提示「已恢复 N 条」。转换出的定稿行走正常输出队列通道下沉，故排在欢迎横幅之后、首条新输入之前。
     * 空历史则什么都不做。
     */
    public void replayHistory(List<Message> messages) {
        List<OutputLine> body = HistoryReplay.toReplayLines(messages);
        if (body.isEmpty()) return;
        Change change;
        synchronized (this) {
            pending.add(new OutputLine("↺ 已恢复上次会话（" + HistoryReplay.userTurns(messages) + " 轮对话）",
                    OutputLine.Kind.INFO));
            pending.addAll(body);
            pending.add(new OutputLine("──── 以上为历史 · 可继续对话，或 /continue 续跑未完成的计划 ────",
                    OutputLine.Kind.INFO));
            change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
        }
        publish(change);
    }
```

改成：

```java
    /**
     * -c 恢复启动：把历史消息回放进 scrollback（仿 Claude Code --continue），直观重现上次对话，
     * 而非只提示「已恢复 N 条」。转换出的定稿行走正常输出队列通道下沉，故排在欢迎横幅之后、首条新输入之前。
     * 同时从历史里最后一次 TodoWrite 调用重建 todo 面板——面板状态不落盘，仅靠回放重建，
     * 见 {@link HistoryReplay#lastTodoSnapshot}。空历史则什么都不做。
     */
    public void replayHistory(List<Message> messages) {
        List<OutputLine> body = HistoryReplay.toReplayLines(messages);
        if (body.isEmpty()) return;
        List<String> lastTodo = HistoryReplay.lastTodoSnapshot(messages);
        Change change;
        synchronized (this) {
            pending.add(new OutputLine("↺ 已恢复上次会话（" + HistoryReplay.userTurns(messages) + " 轮对话）",
                    OutputLine.Kind.INFO));
            pending.addAll(body);
            pending.add(new OutputLine("──── 以上为历史 · 可继续对话，或 /continue 续跑未完成的计划 ────",
                    OutputLine.Kind.INFO));
            todo.clear();
            todo.addAll(lastTodo);
            change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
        }
        publish(change);
    }
```

- [ ] **Step 9: 运行测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStateTest`
Expected: 全部方法 PASS（含新增的 2 个 + 原有全部，特别是既有的 `todo 面板只显主 agent todo，不被子 agent 覆盖` 与「新回合清空 todo 面板」两条不应回归）。

- [ ] **Step 10: 跑受影响的完整测试类，确认无回归**

Run: `mvn test -pl springai-code-tui -Dtest=HistoryReplayTest,ConversationStateTest,ConversationStateNotificationTest,SubagentDefinitionsTest`
Expected: 全部 PASS（`ConversationStateNotificationTest` 里 `replayHistory` 相关的既有 mutation-notification 断言不应因新增的面板写入而回归——它们断言的是"某次调用触发了变化通知"，本变更仍在同一 `synchronized` 块内产生同一次 `changed(...)`，不引入新的通知次数）。

- [ ] **Step 11: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/HistoryReplayTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateTest.java
git commit -m "fix(code-tui): -c 恢复时从历史重建 todo 面板，不再显示为空白"
```
