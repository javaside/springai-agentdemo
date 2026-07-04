# 计划 / 任务双面板设计（Plan & Task Panels · v2）

日期：2026-07-04（v2 定型：todo=主 agent 计划、任务=子 agent 状态）
模块：`springai-code-tui`
状态：已实现（bcc7329）
关联：[子 agent 与多 provider 设计](2026-07-04-subagent-and-multi-provider-design.md)（本特性建立在其子 agent/Task 功能之上）

> **v1→v2 演进说明**：初版把面板定位成「子任务汇总面板」（标题「⟐ 子任务」），并一度让 todo 面板显示"当前子 agent 内部的 todo"。经与用户澄清本体论后定型为 v2：
> - **todo 就是一个 agent 自己的清单**。主 agent（控制器）的 todo 本身就是本回合的**开发计划**，因此它进 **📋 计划** 面板。
> - **task 是一个带状态的子 agent 作业**。每个子 agent = 一条任务，进 **⟐ 任务** 面板（▶/✓/✗ + 当前工具）。
> - **子 agent 内部 todo（`taskId != null`）一律丢弃**，不进任何面板（其工具活动仍在 scrollback 可见）。
> 下文描述的是 v2 定型形态。

## 1. 背景与问题

子 agent（Task）功能已上线：主 agent 可把子任务委派给专门子 agent，运行时子任务事件以内联行进入 scrollback（`▸ Task(explore) 描述` / `    ⎿ Grep` / `  ⎿ 结论`）。同时主 agent 用 `TodoWrite` 维护本回合的开发计划。

问题：

1. 计划与子任务这些信息**会随滚动消失**，没有整体视图。用户无法一眼看清本回合的**计划步骤**、**派了几个子 agent、谁在跑、谁完成、谁失败**。
2. **一个隐蔽 bug**：`AgentTools` 的 `todoEventHandler` 只带了 `currentTurnId()`、漏了 `currentTaskId()`，导致主 agent 的计划 todo 与子 agent 的内部 todo 落进**同一个列表互相覆盖**。

## 2. 目标与范围

**目标**：把本回合的信息拆成两个**钉在输入框上方的固定面板**：

- **📋 计划**：主 agent（控制器）自己的 todo，即本回合开发计划的步骤与进度（○待办 / ▶进行中 / ✓完成）。
- **⟐ 任务**：本回合派出的每个子 agent 一行，实时显示状态（▶运行 / ✓完成 / ✗失败）与当前子工具。

**核心约束**：这是**纯可视化**特性。执行模型不变——子 agent 仍串行前台阻塞执行（`SubagentRunner.run` 内 `.call().content()`），任一时刻最多 1 个子任务在跑。面板只是把"逐个累积起来的任务"汇总呈现。任务面板是**动态**的（执行一个列一个），因为完整计划总览已由 📋 计划 面板承担。

**本体论（关键区分，勿再混淆）**：
- **todo = 一个 agent 自己的检查清单**。主 agent 的 todo → 📋 计划面板。
- **task = 一个带状态的子 agent 作业**。每个子 agent → ⟐ 任务面板一行。
- **子 agent 内部的 todo 被丢弃**，不上任何面板——scrollback 里能看到它的工具活动就够了。

**范围内**：
- 两个固定面板，复用现有 live 区渲染范式（原地替换、钉在输入框上方、不进 scrollback、回合起清空）。
- 保留现有 scrollback 内联子任务行不动（详情可上滚回看）。
- 修 `todoEventHandler` 漏传 `taskId` 的 bug（按 taskId 路由：null=控制器计划、非空=子 agent 内部）。
- 补 `onSubagentFinished` 的成败维度——它是任务面板"失败"状态的数据来源。

**范围外（v3）**：
- 并行/异步子 agent 执行。
- 跨回合任务中心（本设计仅本回合累积，回合起清空）。
- 子 agent 内部 todo 的可视化（当前一律丢弃）。
- 面板内展开/折叠、点击跳转 scrollback。
- 运行计时（运行行显示当前子工具而非计时）。

## 3. 形态：双面板共存

详情留 scrollback（保持现状）+ 新增两个实时汇总面板（计划在上、任务在下）。纯新增改动，与现有 live 区渲染范式一致。

```
─── scrollback（详情，可上滚回看，保持现状不动）───
  ▸ Task(explore) 探索认证模块
      ⎿ Grep JWT
    ⎿ 认证走 JWT，配置在 SecurityConfig

─── 钉在输入框上方（实时汇总，新增）───
  📋 计划
    ✓ 摸清认证模块
    ▶ 设计缓存方案
    ○ 补测试
  ⟐ 任务  ✓1 完成 · ▶1 运行
    ✓ explore  探索认证模块
    ▶ plan     设计缓存方案 · Grep        ← 运行行尾附当前子工具
  ╭──────────────────────────────
  │ 输入消息…
```

### 视觉规则

- **纯前景无底色**（内联 TUI 铁律：带底色的高亮条会串到下一项，已 pty 实机复现）。
- 计划面板：标题 `📋 计划`（`TODO_TITLE`）；每行 `✓`=绿（`OK`）/`▶`=亮黄加粗（`TODO_RUN`）/`○`=暗（`DIM`）。
- 任务面板：标题 `⟐ 任务  ✓N 完成 · ▶M 运行`（失败数 >0 时追加 `· ✗K 失败`）；每行 `✓`=绿 / `▶`=亮黄加粗 / `✗`=红（`ERROR`）。运行中行尾附 `· <当前子工具>`（无当前工具则省略）。
- 两面板均走 live 区渲染（同 `todoChildren`），**不经** `OutputLine` / `Theme.styleFor` / `ScrollbackPrinter`。

## 4. 组件与数据流

### 4.1 `ConversationState`（核心状态）

两条独立数据：

```java
private final List<String> todo;            // 主 agent 计划（📋 计划面板）
private final List<Subtask> subtasks;       // 子 agent 状态（⟐ 任务面板）

public enum SubtaskStatus { RUNNING, DONE, FAILED }

// 只读快照（供渲染线程读）
public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool) {}

// 内部可变持有者：status/currentTool 就地更新
private static final class Subtask {
    final String taskId, agentName, description;
    SubtaskStatus status = SubtaskStatus.RUNNING;
    String currentTool = "";
}
```

**计划面板（todo）**：
- `onTodoUpdated(turnId, todoLines)`（旧 3 参）委托到 `onTodoUpdated(turnId, null, todoLines)`。
- `onTodoUpdated(turnId, taskId, todoLines)`（新 4 参，覆写）：
  - `turnId != acceptingTurnId` → 丢弃（迟到过滤）。
  - **`taskId != null` → 直接 return**（子 agent 内部 todo 不上面板，这是 v2 的关键裁决）。
  - 否则 `todo.clear(); todo.addAll(todoLines)`（原地替换）。

**任务面板（subtasks）**：
- `onSubagentStarted(turnId, taskId, agentName, description)`：迟到过滤后**追加** RUNNING 条（**保留**现有 scrollback push）。
- `onToolStarted(turnId, taskId, tool, input)`（taskId 非空分支）：`findSubtask(taskId).currentTool = tool`（**保留**现有缩进行 push）。
- `onSubagentFinished(turnId, taskId, finalText, ok)`：按 taskId 定位，置 `DONE`（ok）或 `FAILED`（!ok），清 `currentTool`（**保留**现有结论行 push）。
- `subtaskSnapshot()`：返回不可变 `List<SubtaskView>` 副本。

**共同生命周期** `onTurnStarted`：`todo.clear(); subtasks.clear();`（回合起清空、回合内累积、回合后驻留至下次发送）。taskId 在 `subtasks` 中找不到匹配（迟到/已清）→ 静默忽略，不抛异常。

### 4.2 `AgentTools`（bug 修复：todo 路由）

`todoEventHandler` 补上 `currentTaskId()`，让 `ConversationState` 能按 taskId 区分控制器计划与子 agent 内部 todo：

```java
.todoEventHandler(todos ->
        listener.onTodoUpdated(ToolEventCallback.currentTurnId(),
                ToolEventCallback.currentTaskId(),   // ← 修复前漏传，致两类 todo 互相覆盖
                toLines(todos)))
```

`ToolEventCallback.currentTaskId()` 走 ThreadLocal：主 agent 调 `TodoWrite` 时为 null，子 agent（`SubagentRunner` 在 toolContext 里设 `TASK_ID_KEY`）调时非空。

### 4.3 `AgentListener`（接口）

两处 default 重载，均沿用"新参默认委派回旧签名"模式（只有 `ConversationState` 覆写，其余测试监听器零改动）：

```java
default void onTodoUpdated(long turnId, String taskId, List<String> todoLines) {
    onTodoUpdated(turnId, todoLines);
}
default void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
    onSubagentFinished(turnId, taskId, finalText);
}
```

### 4.4 `SubagentRunner`（执行器）

- 成功路径：`listener.onSubagentFinished(parentTurnId, taskId, finalText, true)`。
- `catch` 路径：`listener.onSubagentFinished(parentTurnId, taskId, "子 agent 执行失败：" + msg, false)` 后 rethrow。

### 4.5 `CodeTuiView`（渲染）

- `render()` 中先取两快照 `todoSnapshot()` / `subtaskSnapshot()`，`scope(!todos.isEmpty(), todoChildren(todos))` **之后**插入 `scope(!subs.isEmpty(), subtaskChildren(subs))`（计划在上、任务在下）。
- `todoChildren`：标题 `📋 计划` + 每条一行，`TODO_CAP = 10`。
- `subtaskChildren`：标题（计数）+ 可见子任务各一行，`SUBTASK_CAP = 6`，**取末尾窗口**（串行下"运行中"总是最后一条，取末尾保证它恒可见）。
- 每帧构造，**首行判空**（守 `scope` eager 求值坑）。
- 无需改动 `Theme` / `ScrollbackPrinter`（面板不走 scrollback 路径）。

## 5. 错误处理与边界

- `todoChildren` / `subtaskChildren` 首行判空，空/null 安全。
- todo `taskId != null` → 直接丢弃；子任务 taskId 未命中 → 静默忽略。
- 面板高度受 `TODO_CAP`（10）/ `SUBTASK_CAP`（6）限制；两面板 + 输入框的 live 区总高由 `InlineApp` 每帧 `setContentHeight` 动态跟随，不漂移/撑爆。
- 并发：`todo` / `subtasks` 读写均在 `ConversationState` 内 `synchronized`，快照返回不可变副本。

## 6. 测试

- **`ConversationStateTest`**：子任务累积 / 回合起清空 / DONE 落位 / FAILED 落位 / `currentTool` 更新 / taskId 未命中不抛；**主 agent todo 进计划面板**、**子 agent 内部 todo（taskId!=null）被丢弃**。
- **`SubagentRunnerOkTest`**：成功传 `ok=true`、异常传 `ok=false` 并 rethrow（假 ChatModel/Provider，不联网）。
- **`SubtaskPanelTest`**：任务面板标题计数 / 行文本 / 运行行附当前工具 / 非运行态不附；render 树有子任务不崩。
- **pty + pyte 实机**：两面板钉住渲染、纯前景无底色串行、计划与任务叠放不错位；改完重新 package。

## 7. 验收标准

1. 主 agent 写 todo 后，输入框上方出现 **📋 计划** 面板，逐条显示计划步骤与 ○/▶/✓ 状态。
2. 发起子任务后出现 **⟐ 任务** 面板，标题显示正确的完成/运行计数。
3. 子任务完成后其行变 `✓`、失败变 `✗`，计数同步更新；运行中行尾显示当前子工具名。
4. **子 agent 内部的 todo 不出现在任何面板**（仅 scrollback 有其工具活动）。
5. 新回合开始时两面板均清空。
6. scrollback 内联详情行保持不变（回归无损）。
7. 面板高亮纯前景、无底色串行（pty 断言 `cell.bg` 全默认）。
8. 全量测试绿（`-pl springai-code-tui -Dtest='!CodingAgentSpikeTest'`），jar 重新 package。
