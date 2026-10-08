# TodoWrite 过期清单重注入（todo-stale-reminder）设计

状态：已交付（2026-10-08，实施记录见 [plans/2026-10-07-todo-stale-reminder.md](../plans/2026-10-07-todo-stale-reminder.md)）。

## 背景与问题

真机会话取证（`/Users/zxh/IdeaProjects/xibaojun/.codetui/sessions/20261007T143538-009fe4.json`）：

- 201 个事件里 TodoWrite 仅 1 次（event#125：7 任务 + 终审，Task 1 in_progress）；
- 此后主控切子 agent 委派模式（18 次 `Task`），每个任务通过后精准追加了 8 次
  `cat >> progress.md` 台账（#135/#143/#151/#159/#167/#175/#183/#196），正文同步叙述
  「Task N 通过……记账并派 Task N+1」；
- 最终 8 个 commit、7 任务全部完成，而任务面板全程冻结在「Task 1: in_progress」。

定性（2026-10-08 v7，完整取证与可复跑脚本见 [2026-10-07-todowrite-not-updated-root-cause.md](2026-10-07-todowrite-not-updated-root-cause.md)）：
不是模型纪律差（台账 8 次全中）、不是工具描述差（业界全文）、不是 skill（同一份 skill 下
b65902 装载后更新 11 次、配对 0.57）、不是模型（均为 glm-5.3）。
跨语料 322 会话复核后，失败形态是**一个确定缺陷被若干统计性触发器反复触发**：

1. **确定缺陷（本设计的靶子）**：清单对模型单向、零反馈——面板在 UI 层模型看不见、
   TodoWrite 返回是一次性文案、harness 没有任何机制把过期清单送回上下文，
   于是**任何一次漏更新都不可逆**（漏一次就漏到底）。
2. **统计触发器**：「完成」时刻的记账槽位被更具体的动作占据——SDD 台账行、
   委派（完成发生在子 agent 内，控制器只看到 Task 返回）、长流程文本。
   度量：控制器做出完成标记时顺手更新清单的比例，非 SDD 会话 0.63、SDD 会话 0.37。
   SDD 不是必要条件：非 SDD 里冻结跨度 ≥50 且有完成标记的会话有 20 个（SDD 仅 3 个）。
3. **会话内随机性**：配对率 0.00~1.00 连续分布，切不出决定成败的会话级变量。

本设计的提醒器即**第 1 层（确定缺陷）**的修复（定期重掷，让漏更新不再永久化）；
第 2 层（触发器）的修法是把纪律绑到「提交/记账」事件，见根因文档。

配套动作（已撤销）：曾修订 `~/.codetui/skills/subagent-driven-development/SKILL.md`（流程图点名
TodoWrite 等），2026-10-08 应用户要求恢复原样——第三方 skill 文本不可控（上游重同步即覆盖），
且与工具无绑定关系、修订收益有限；过期清单的纠正**一律由本设计的 harness 兜底承担**，
不管哪条流程再犯，环境自己会把过期状态怼回模型眼前。

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
   瓶颈在**触发时机与漏后无反馈**，不在文本（「注意力稀释」说已按根因文档排查表 #3 淘汰）。
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

### 提醒文案（v1 形态，已由 v2 取代；保留作退化形态）

> v2 起默认注入可照抄的整份清单 JSON（见下节），只有快照 JSON 超 3000 字符时才退化为本形态。

```
[任务面板提醒] 距上次 TodoWrite 已连续 N 次工具调用，任务清单可能已过期（X/Y 项未完成）：
✓ 已完成：Z 项
▶ <in_progress 项 content，截 80 字符>
○ <pending 项 content，截 80 字符>（未完成项最多 10 行，超出折为「…等共 K 项」）
请按实际进度立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。
```

### v2 追加：事件对齐触发 + 可照抄入参（2026-10-08）

v1 只有「连续 N 次调用未更新」这一条触发路径，问题是它与「完成」这个时刻无关：SDD 场景
`every=8` 约等于 4 个任务之后才响，而失守恰恰发生在完成时刻（跨语料度量：控制器做完成标记时
顺手更新清单的比例，非 SDD 0.63 / SDD 0.37）。v2 补一条精准路径。

**新增状态**（与 v1 字段同样 volatile，语义都是控制器级）：

```java
volatile boolean armed;              // 刚发生过完成事件，等下一次「跳过更新」再响
volatile String armedBy = "";        // 武装它的完成事件名（提醒里指名道姓）
volatile boolean eventFiredThisTurn; // 本回合事件提醒已响过（每回合至多一次）
```

**触发规则**：

- 完成事件 = `Task`/`ParallelTasks` 返回，或 `Bash` 入参里 `git commit` 出现在行首/命令分隔符之后
  （与取证脚本 `todo_forensics` 的 `COMMIT` 同口径）。
  刻意**不认**「写了 ledger/progress 台账」——那是某个 skill 的约定，harness 不该内建；
  且 SDD 写完台账紧接着就派下一个 Task，由 `Task` 返回这条路径已覆盖。
- **完成事件当刻不提醒**，只置 `armed=true`。此刻清单必然还没机会更新，当场响等于每次委派
  都刷一遍，模型很快学会忽略。
- **下一个控制器调用若不是 TodoWrite**（说明模型确实跳过了更新）→ 当场提醒，点名 `armedBy`，
  并清 `armed`；同回合不再响。
- TodoWrite、快照全完成、`/clear`、回合切换都会清 `armed` 与 `eventFiredThisTurn`。
- 阈值计数路径（v1）保持不变，两条路径共用同一份快照。

**提醒文本改为带可照抄的入参**：TodoWrite 是整表替换（每项都要 content/activeForm/status），
而竞争动作（写一行台账）成本极低——只喊「请更新」等于把重建整份清单的重活丢回给模型。

```
[任务面板提醒] <触发者> 已返回，一个工作单元完成，但任务清单未更新（2/7 项未完成）。
请立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。
可直接照抄下面这行入参、只改 status（已完成项改 completed、下一项改 in_progress）：
{"todos":[{"content":"…","activeForm":"…","status":"in_progress"}, …]}
```

- **必须给整份清单**（含已完成项）：部分清单被照抄会静默丢项。
- 条目不截断（截断会让照抄有损）；JSON 超过 3000 字符时退化为 v1 的紧凑列表形态，
  避免每 N 次调用注入一份巨型文本。
- 清单状态照抄当前快照，harness 不猜哪项完成了——守住「清单=模型状态」契约。
- 正确性由机器证明：用例把提醒里的 JSON 原样喂给**生产装配路径**
  （`TodoWriteToolAdapter` 经 `ToolCallbacks.from`），必须返回 `modified successfully`。
  注意不能拿库工具 `TodoWriteTool` 去测——它的入参是双层 `todos` 嵌套（见适配器类注释），
  用它做回归会永远红在「形状不对」上。

### 真机测量与两个由用例抓出的真实缺陷（2026-10-08 补）

1. **commit 识别口径错了**（装配级用例暴露）：入参是 Bash 的 `arguments` JSON，命令行开头的
   `git` 前面是引号；原正则按 shell 语义锚定行首/分隔符，于是 `git commit -m x` 永不匹配、
   只有 `cd X && git commit` 命中。改用词边界 `(?<![\w-])git`，并允许 git 与子命令之间夹
   全局选项（`-C <dir>` / `-c k=v` / `--no-pager`）。全语料有 30 次提交属于被漏计形态。
   **教训**：这类失败只是「不响」，没有报错——只有跨组件的装配级用例才炸得出来。
2. **真机测量**（`TodoReminderLiveSpikeTest`）：5 次真实回合里模型自行维护清单良好
   （每回合 3~6 次更新），其中 1 次提交后跳过更新 → 提醒当场注入 → 模型随即更新。
   提醒的「注入→响应」在真机闭环过一次；失败形态在短脚本回合里本身少见（与跨语料
   非 SDD 配对率 0.63 一致），长会话里的效果需发布后由响应率指标持续观测。

**真机用例的安全护栏**：Bash 的工作目录是<b>进程 CWD（宿主仓库）</b>，不是 `build(...)` 传的
`root`（那个只给 Grep/Glob/会话）；第一次真机跑真把 `hello.txt`/`world.txt` 提交进了本仓库
（已回退）。现在用例跑前记宿主仓库 HEAD 与 `git status --porcelain`、跑后逐字比对，
不一致即失败；提示词也把工作目录钉在临时目录并明令不得改动宿主仓库。

### 系统提示词纪律段（`AgentTools.TODO_DISCIPLINE`）

绑定到具体事件而非笼统要求：每完成一个任务、每次 git 提交、每写完一条台账/进度记录之后，
先调 TodoWrite 把已完成项改 `completed`、下一项改 `in_progress`，再继续；并声明
「委派的子 agent 返回并通过审查 = 一个任务完成」。该段作为 param `{TODO_DISCIPLINE}` 注入，
不得含花括号（否则 StringTemplate 渲染抛）；由 `AgentToolsSystemPromptTest` 真正渲染一次
系统提示词来钉守——**「构建 runtime」不等于「渲染提示词」**（删掉 `.param` 时装配类测试全绿，
只有渲染用例会红）。

### `ToolEventCallback` 改动

新增可选第三构造参 `TodoStaleReminder`（旧构造委托 null=停用；`McpRegistry` 与既有
调用点零改动）。`call()` 成功路径在 `onToolFinished`（携带**原始** out，UI 不见提醒）
之后、return 之前：`reminder != null && taskId == null` 时取 note，非空则
`out + "\n\n" + note`。异常路径不提醒。

v2 起传的是三参 `reminderOrNull(turnId, name, toolInput)`——提醒器要靠 `toolInput`
识别「这次调用是不是 `git commit`」这类完成事件（两参重载保留，等价于无 toolInput，
只剩阈值计数路径）。

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
