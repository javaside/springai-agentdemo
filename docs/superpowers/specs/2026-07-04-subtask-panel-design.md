# 子任务面板设计（Subtask Panel · v1）

日期：2026-07-04
模块：`springai-code-tui`
状态：已批准，待写实现计划
关联：[子 agent 与多 provider 设计](2026-07-04-subagent-and-multi-provider-design.md)（本特性建立在其子 agent/Task 功能之上）

## 1. 背景与问题

子 agent（Task）功能已上线：主 agent 可把子任务委派给专门子 agent，运行时子任务事件以内联行进入 scrollback（`▸ Task(explore) 描述` / `    ⎿ Grep` / `  ⎿ 结论`）。

问题：这些行**会随滚动消失**，没有一个整体视图。用户无法一眼看清本回合**发起了几个子任务、完成了几个、谁在跑、谁失败**。

## 2. 目标与范围

**目标**：把本回合发起的所有子任务，汇总成一个**钉在输入框上方的固定面板**，实时显示计数与每条状态（运行中 / 完成 / 失败）。

**核心约束**：这是**纯可视化**特性。执行模型不变——子 agent 仍串行前台阻塞执行（`SubagentRunner.run` 内 `.call().content()`），任一时刻最多 1 个子任务在跑。面板只是把"逐个累积起来的子任务"汇总呈现。

**范围内**：
- 一个新的固定面板，复用现有 `todo` 计划面板的范式（原地替换、钉在输入框上方、不进 scrollback、回合起清空）。
- 保留现有 scrollback 内联子任务行不动（详情可上滚回看）。
- 补上 `onSubagentFinished` 的成败维度——它是面板"失败"状态的数据来源。

**范围外（v2）**：
- 并行/异步子 agent 执行。
- 跨回合任务中心（本设计仅本回合累积，回合起清空）。
- 面板内展开/折叠、点击跳转 scrollback。
- 运行计时（v1 运行行显示当前子工具而非计时）。

## 3. 形态：共存

详情留 scrollback（保持现状）+ 新增实时汇总面板。这是纯新增改动，不动已建好并经 pty 验证的内联渲染，且与现有 `todo` 面板"汇总钉住 + 详情滚动"范式一致。

```
─── scrollback（详情，可上滚回看，保持现状不动）───
  ▸ Task(explore) 探索认证模块
      ⎿ Grep JWT
    ⎿ 认证走 JWT，配置在 SecurityConfig

─── 钉在输入框上方（实时汇总，新增）───
  ⟐ 子任务  ✓1 完成 · ▶1 运行
    ✓ explore  探索认证模块
    ▶ plan     设计缓存方案 · Grep        ← 运行行尾附当前子工具
  ╭──────────────────────────────
  │ 输入消息…
```

### 视觉规则

- **纯前景无底色**（内联 TUI 铁律：带底色的高亮条会串到下一项，已 pty 实机复现）。
- 配色：`✓`=绿（`OK`）、`▶`=亮黄加粗（`TODO_RUN`）、`✗`=红（`ERROR`）、待定=暗（`DIM`）、标题=`TODO_TITLE`。
- 标题行：`⟐ 子任务  ✓N 完成 · ▶M 运行`（失败数 >0 时追加 `· ✗K 失败`）。
- 每行：`<状态图标> <agent类型>  <描述>`；运行中行尾附 `· <当前子工具>`（无当前工具则省略）。
- 面板走 live 区渲染（同 `todoChildren`），**不经** `OutputLine` / `Theme.styleFor` / `ScrollbackPrinter`。

## 4. 组件与数据流

### 4.1 `ConversationState`（核心状态）

新增：

```java
public enum SubtaskStatus { RUNNING, DONE, FAILED }

// 可变条目（同步访问）：description/status/currentTool 会就地更新
public static final class Subtask {
    final String taskId;
    final String agentName;
    final String description;
    SubtaskStatus status;
    String currentTool;   // 运行中的当前子工具，可空
}
```

- 同步字段 `List<Subtask> subtasks`。
- `onSubagentStarted(turnId, taskId, agentName, description)`：迟到过滤后**追加** RUNNING 条（**保留**现有 scrollback push）。
- `onToolStarted(turnId, taskId, tool, input)`（taskId 非空分支）：更新对应条 `currentTool = tool`（**保留**现有缩进行 push）。
- `onSubagentFinished(turnId, taskId, finalText, ok)`：按 taskId 定位，置 `DONE`（ok）或 `FAILED`（!ok），清 `currentTool`（**保留**现有结论行 push）。
- `onTurnStarted`：`subtasks.clear()`（与 `todo.clear()` 同处、同生命周期：回合起清空、回合内累积、回合后驻留至下次发送）。
- 新增只读快照 `List<Subtask> subtaskSnapshot()`（供渲染线程读）。

taskId 在 `subtasks` 中找不到匹配（迟到/已清）→ 静默忽略，不抛异常。

### 4.2 `AgentListener`（接口）

给 `onSubagentFinished` 加一个 **default 4 参重载**，带 `boolean ok`，默认委派回旧 3 参：

```java
default void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
    onSubagentFinished(turnId, taskId, finalText);
}
```

沿用上次加 taskId 维度时已验证的默认重载模式——只有 `ConversationState` 覆盖 4 参版，其余测试监听器无需改动。

### 4.3 `SubagentRunner`（执行器）

- 成功路径：`listener.onSubagentFinished(parentTurnId, taskId, finalText, true)`。
- `catch` 路径：`listener.onSubagentFinished(parentTurnId, taskId, "子 agent 执行失败：" + msg, false)`。

### 4.4 `CodeTuiView`（渲染）

- `render()` 中在 `scope(!todos.isEmpty(), todoChildren(todos))` **之后**插入 `scope(!subtasks.isEmpty(), subtaskChildren(subtasks))`（计划在上、子任务执行在下）。
- 新增 `subtaskChildren(List<Subtask>)`：仿 `todoChildren`——标题行 + 每条一行，`SUBTASK_CAP = 6`，超出显示 "… 还有 N 项"。
- 每帧构造，**首行判空**（守 `scope` eager 求值坑：面板方法每帧被调，须先判空再解引用）。
- 无需改动 `Theme` / `ScrollbackPrinter`（面板不走 scrollback 路径）。

## 5. 错误处理与边界

- `subtaskChildren` 首行判空，`subtasks` 为空/null 安全。
- taskId 未命中 → 静默忽略。
- 面板高度受 `SUBTASK_CAP` 限制；与现有 `todo`（`TODO_CAP=10`）并存时 live 区总高由 `InlineApp` 每帧 `setContentHeight` 动态跟随，不会漂移/撑爆。
- 并发：所有 `subtasks` 读写在 `ConversationState` 内 `synchronized`，快照返回不可变副本。

## 6. 测试

- **`ConversationStateTest`**：累积多条 / 回合起清空 / DONE 落位 / FAILED 落位 / `currentTool` 更新 / taskId 未命中不抛。
- **`SubagentRunnerTest`**：成功传 `ok=true`、异常传 `ok=false`（用捕获监听器断言）。
- **`CodeTuiViewTest`**（`renderForTest`）：有子任务时面板出现、空时收起、`SUBTASK_CAP` 溢出显示 "还有 N 项"。
- **pty + pyte 实机**：面板钉住渲染、纯前景无底色串行、与 `todo` 面板并存不错位；改完重新 package。

## 7. 验收标准

1. 发起 ≥2 个子任务时，输入框上方出现汇总面板，标题显示正确的完成/运行计数。
2. 子任务完成后其行变 `✓`，失败变 `✗`，计数同步更新。
3. 运行中子任务行尾显示其当前子工具名。
4. 新回合开始时面板清空。
5. scrollback 内联详情行保持不变（回归无损）。
6. 面板高亮纯前景、无底色串行（pty 断言 `cell.bg` 全默认）。
7. 全量测试绿（`-Dtest='!CodingAgentSpikeTest'`），jar 重新 package。
