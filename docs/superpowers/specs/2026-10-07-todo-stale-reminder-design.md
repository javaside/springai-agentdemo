# TodoWrite 过期清单重注入（todo-stale-reminder）设计

状态：已核准（2026-10-07，与 SKILL.md 文本修订一并拍板「两个修法并行」）。

## 背景与问题

真机会话取证（`/Users/zxh/IdeaProjects/xibaojun/.codetui/sessions/20261007T143538-009fe4.json`）：

- 201 个事件里 TodoWrite 仅 1 次（event#125：7 任务 + 终审，Task 1 in_progress）；
- 此后主控切子 agent 委派模式（18 次 `Task`），每个任务通过后精准追加了 8 次
  `cat >> progress.md` 台账（#135/#143/#151/#159/#167/#175/#183/#196），正文同步叙述
  「Task N 通过……记账并派 Task N+1」；
- 最终 8 个 commit、7 任务全部完成，而任务面板全程冻结在「Task 1: in_progress」。

定性：**不是模型记账纪律差**（台账 8 次全中），是 **subagent-driven-development skill
的台账指令与 TodoWrite 双轨记账竞争**（skill 正文 "a ledger file, not only in todos"
重笔墨、流程图 "mark todo complete" 只是脚注），叠加 **harness 没有任何机制把过期清单
重新喂给模型**——一次遗漏永久持续。

配套动作（已另行交付）：`~/.codetui/skills/subagent-driven-development/SKILL.md` 已修订
（流程图节点点名 TodoWrite、正文明确「台账与 TodoWrite 同一步双更新、面板只认 TodoWrite」）。
本设计是 harness 侧的结构性兜底：不管哪条流程再犯，环境自己会把过期状态怼回模型眼前。

## 目标

1. 控制器建过 TodoWrite 清单后，若**连续 N 次控制器级工具调用**未再调 TodoWrite 且清单
   仍有未完成项，把当前清单快照 + 更新指令**追加到第 N 次工具调用的返回文本尾部**，
   模型看到过期状态后自发纠正。
2. 阈值可配：`CODETUI_TODO_REMIND_EVERY`，默认 8，≤0 关闭，非法值回退默认。
3. `/clear` 换会话时清空快照与计数（旧会话的清单不该吓唬新会话）。

## 非目标

- 不改 TodoWrite 工具描述（已是业界全文，证据见下）。
- 不覆盖子 agent 内部 todo（`taskId != null`）：不计数、不提醒（沿用 2026-09-09
  subagent-todowrite-visibility 的层级语义）。
- MCP 工具（`McpRegistry` 自行装饰那条链）不参与计数与提醒——它们的装饰循环不在
  `AgentTools`，V1 不动。
- `-c` 恢复不为快照播种：新进程无快照，直到模型再调 TodoWrite。
- 不做回合开始（submit 时）注入。
- 不在 UI 显示提醒行：追加文本只给模型，scrollback 仍显示原始工具输出。

## 已核准的事实依据

1. **注入点**：工具调用循环在 Spring AI 内部，项目唯一逐调用拦截层是
   `ToolEventCallback`（`AgentTools` 7 处 + `McpRegistry` 1 处全工具装饰）；它已从
   ToolContext 取 turnId/taskId；装饰顺序 `PermissionCallback → ToolEventCallback →
   媒体外置 → 真实工具`（AgentTools.java 装饰循环注释）。
2. **控制器/子 agent 分流**：`TodoWriteTool.todoEventHandler` 在工具 call 内同线程触发，
   `ToolEventCallback.currentTaskId()` 可区分层级（AgentTools.build 现有 lambda 即如此用）。
3. **/clear 汇聚点**：`CodingAgent.clearContext()` 已集中做会话级重置
   （usageAccumulator.reset / permissionEngine.clearSessionRules / goalManager.clear）；
   构造器是长 telescoping 链，跨包接线用 **wireL1/wireGoal 两段式 bind 先例**
   （volatile null 守卫 no-op）。
4. **TodoWrite 权限类别 INTERNAL**（ToolRegistry 登记表）→ 装饰链在测试里可直调；
   测试基建 `RuntimeToolSet.toolsOf(runtime)` 取真实装配产物（离线假 key build）。
5. **工具描述已是完整文本**：spring-ai-agent-utils-0.10.0.jar 字节码
   RuntimeVisibleAnnotations 提取，约 4.7KB（When to Use 7 条 / When NOT 4 条 /
   4 正例 4 反例 / Task Management）；且 springai-agentdemo 侧 94 个大会话抽验，
   凡建了清单的会话状态推进教科书级（`[5,1,0]→…→[0,0,6]`）——「怎么更新」在触发时有效，
   瓶颈在触发后的**注意力维持**，不在文本。
6. **持久化影响**：工具返回文本会落 session 事件 toolResponses——提醒文本将随历史持久化
   并参与 `-c` 回放。可接受（与 Claude Code system-reminder 同 trade-off），写入本节供知情。

## 设计

### 组件：`TodoStaleReminder`（`agent.tools` 包，public——跨包装配同先例）

状态机（全部字段线程安全：工具线程并发调用是常态）：

```java
int every;                                  // 0=停用；CODETUI_TODO_REMIND_EVERY
volatile long turnId = -1;                  // 当前计数归属回合
volatile List<TodoItem> todos = List.of();  // 控制器清单快照（含 status）
AtomicInteger stale;                        // 距上次 TodoWrite/触发提醒的工具调用数
```

- `onControllerTodoWritten(items)`：todos=copy、stale=0（todoEventHandler 控制器分支调用）。
- `reset()`：全清（/clear 用）。
- `reminderOrNull(turnId, toolName)`（ToolEventCallback 成功返回后调用，返回 null=不提醒）：
  - `every<=0` 或 `toolName=="TodoWrite"` → null（不计数）；
  - turnId 变化 → 记新回合、stale=0；
  - todos 空、或全部 completed → null（清单没有「过期可言」）；
  - `++stale < every` → null；到阈值 → stale=0 并返回提醒文本（之后每 every 次再提醒一轮）。

### 提醒文案（前缀与关键内容钉死，测试断言锚点）

```
[任务面板提醒] 距上次 TodoWrite 已连续 N 次工具调用，任务清单可能已过期（X/Y 项未完成）：
✓ 已完成：Z 项
▶ <in_progress 项 content，截 80 字符>
○ <pending 项 content，截 80 字符>（未完成项最多 10 行，超出折为「…等共 K 项」）
请按实际进度立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。
```

### `ToolEventCallback` 改动

新增可选第三构造参 `TodoStaleReminder`（旧构造委托 null=停用；`McpRegistry` 与既有
调用点零改动）。`call()` 成功路径在 `onToolFinished`（携带**原始** out，UI 不见提醒）
之后、return 之前：`reminder != null && taskId == null` 时取 note，非空则
`out + "\n\n" + note`。异常路径不提醒。

### `AgentTools` 装配

- build 顶部：`TodoStaleReminder todoReminder = TodoStaleReminder.fromEnv();`
- `todoEventHandler` lambda 扩展：`currentTaskId() == null` 时同步
  `todoReminder.onControllerTodoWritten(todos.todos())`。
- `AgentTools` 内**全部 7 处** `ToolEventCallback` 构造点传入 reminder
  （主装饰循环 / Task / ParallelTasks / TaskOutput / ListTasks / ExitPlanMode /
  buildMemoryTools——最后一个加参数）。McpRegistry 不动（非目标）。
- `AgentRuntime` 增组件 `todoReminder`；新增静态 `wireTodoReminder(runtime, agent)`：
  调 `agent.bindTodoReminder(...)`（包私有、volatile null 守卫）。
- `CodeTuiApplication` 在 `wireGoal` 之后调用 `wireTodoReminder`。
- `CodingAgent.clearContext()`：`todoReminder != null → reset()`。

### 配置与文档

- `CODETUI_TODO_REMIND_EVERY`：默认 8；`Integer.parseInt` 失败回退默认；≤0 停用。
- 文档落点：`springai-code-tui/README.md` 环境变量表（CODETUI_GOAL_* 表下方同款式）、
  `springai-code-tui/src/package/bin/config.env.example`。

## 被淘汰的备选及理由

- **A. 只改 SKILL.md 不动 harness**——文本约束兜不住所有漂移路径（无 skill 的长回合、
  其它自带记账的流程同样冻结），需结构性兜底；两者互补而非二选一。
- **B. 回合开始注入（submit 组 prompt 时）**——目标场景是**单个超长回合**，回合开始时
  清单尚未过期，注入无的放矢。
- **C. Advisor 层注入**——工具循环在 Spring AI 内部，Advisor 只能按模型调用粒度介入，
  做不到「每 K 次工具调用」节流，粒度上等价于 B。
- **D. Task 工具结果固定携带提醒**——只覆盖子 agent 委派路径，纯 Bash/Edit 长回合不覆盖，
  且无法感知清单是否真的过期。
- **E. harness 代写 TodoWrite（子任务完成自动标完成）**——破坏「清单=模型状态」契约：
  状态错误时模型无从分辨自己是否写过；且与 subagent todo 层级语义冲突。
- **F. 继续加长工具描述**——描述已 4.7KB 业界全文，实测更新纪律在触发时良好，
  瓶颈在注意力位置而非文本缺失（事实依据 §5）。
