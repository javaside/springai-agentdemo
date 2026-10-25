# headless 非交互模式（-p）设计

> 对标 `claude -p` / `codex exec`：不进 TUI，一条提示词跑一个回合，stdout 只出最终回复。
> 架构已为此准备好——`AgentListener` 接缝 + 桥以 listener 构造 + 模态默认 fail-closed；
> 本设计新增 `headless` 包两个类 + main 装配分叉，TUI 路径行为零变化。

## 目标与范围

- **目标**：
  1. `codetui -p "<提示词>"`：headless 跑一个回合，stdout 打**最后一段 assistant 文本**，退出；
  2. `-p -` 从 stdin 读全部为提示词（长文本/管道）；`-c`/`--resume` 可组合（脚本多步链式）；
  3. `--output-last-message <path>`：最终回复并行写文件；
  4. 退出码语义：`0` 回合正常完成（含计划产出）；`1` 装配失败/回合错误/流超时；`2` 模型问询被取消。
- **非目标**：
  - `--output-format json|stream-json` 事件流——后续版本（用户已核准推迟）；
  - `exec` 子命令别名——纯 flag 风格已定，子命令解析层 YAGNI；
  - headless 下的 goal 自动轮（不接 `wireGoal`，无人值守自动轮危险）、`/斜杠命令`、插话、后台任务面板等 TUI 概念；
  - 交互式审批（headless 定义即无 UI：权限 ASK 一律 DENY fail-closed）；
  - 后台任务的等待/送达——**headless 回合结束即进程退出，在飞后台任务随之终止**（模型可用 `TaskOutput block=true` 自管；脚本拿到的是退出码而非任务结果）。

## 前提事实（2026-10-25 逐一核查源码）

| # | 事实 | 证据 |
|---|---|---|
| 1 | `AgentListener.onPermissionRequested` **默认实现即 DENY**（javadoc：「没有接管审批 UI 的落地端应拒绝并让回合继续」）——headless 覆写只为加 stderr 提示，fail-closed 已内建 | `agent/seam/AgentListener.java:137-139` |
| 2 | `UserQuestionBridge`/`PlanApprovalBridge` 均以 `AgentListener` 构造（`AgentTools.build` 内 L469/L637）——换 listener 实现，模态体系整体自动降级 | `AgentTools.java:469,637`、`seam/UserQuestionBridge.java:42` |
| 3 | **取消不会自动终止回合**：`responder.cancel()` → `handoff.offer(CANCEL)` → 工具线程抛 `QuestionCancelledException`/`PermissionCancelledException`；若回合未 dispose，异常被 `ResilientToolExecutionExceptionProcessor` 转错误文本回给模型**继续跑**（全仓无这两个异常的 catch/特殊通道，评审已 grep 证实）；计划侧则是模型留在 plan 模式反复提交的死循环风险。UI 流程 `cancelTurnFor` 内部是**先 `req.cancel()` 再 `current.dispose()`**——headless 同序 | `UserQuestionBridge.java:24-25,53-65`、`PlanApprovalBridge.java:56-58,80-82`、`CodeTuiView.java:3638-3641` |
| 4 | **回合 dispose 会中断 park 中的工具线程**（interrupt → 抛 cancelled 异常，随已取消的流丢弃）；且取消路径（`doOnCancel`）**不发任何 listener 事件**（无 onTurnComplete/onError）——headless 终止回合后 latch 必须自查放行 | `UserQuestionBridge.java:31-32`、implementation-map §3 回合表 doOnCancel 行 |
| 5 | `PlanOutcome` 四值：`APPROVE_ACCEPT_EDITS / APPROVE_DEFAULT / KEEP_PLANNING / CANCEL`；`respond(CANCEL)` 只是解除工具线程阻塞 | `ui/CodeTuiView.java:3858-3860`、`seam/PlanApprovalBridge.java:80-82` |
| 6 | **零 enabled MCP server 时 `onMcpReady` 不发**：`connectInBackground` 对空集直接 return，`connecting` 从未递增——headless 的 MCP 等待不能只等 latch，须先查 `connectingCount()`（javadoc：「全连完/未配置 MCP 时为 0」；init 返回时计数已置位，读取准确） | `agent/mcp/McpRegistry.java:261-264,285-288` |
| 7 | `AgentListener` 的回调禁令：覆写审批/计划/问询的实现**不得调 super、不得在回调内阻塞**（ConversationState 的 synchronized 锁纪律）——headless 的「立即应答 + dispose + latch」形状合规 | `AgentListener.java:121-131,150-152` |
| 8 | main 的 `ConversationState` 调用点共 **14 处**（评审逐行核对无遗漏）：构造 L86、`restoreLastModel(registry, root, state)`（签名收 ConversationState，内部 pushInfo）L87、pushInfo ×5（L139/142/146/150/157：bypass 警告/忽略 cliMode/PLAN 提示/overBroadRoot/诊断开关）、`McpRegistry.init` L165、`AgentTools.build` L185、CodingAgent 构造 L186、replayHistory+pushInfo ×2（L206/208/210）、`CodeTuiView(state,…)` L213 | `CodeTuiApplication.java:86-213` |
| 9 | 流式空闲超时（`CODETUI_LLM_READ_TIMEOUT_SECONDS` 默认 300s 钳 [10,3600]，相邻 chunk 语义）+ 子 agent 重试兜底已内建——但**它只杀静默流**：模型持续产出或工具快返循环（`TurnToolLimitWiring` 默认不限）时空闲超时永不触发。故 headless 另设总时长硬顶 `CODETUI_HEADLESS_MAX_SECONDS`（默认 3600，钳 [60,86400]），到顶 dispose 回合、stderr 报告、退出码 1（评审修订：原「不设总超时」的依据不完整） | implementation-map §2.4、`CodeTuiApplication.java:241-255` |
| 10 | 退出纪律：OkHttp Dispatcher 线程 keep-alive 60s，必须 `System.exit`（TUI 同款）；`-h` 是 main 第一件事（早于一切） | `CodeTuiApplication.java:56-63` 及 §1 装配第 10 步 |
| 11 | 会话恢复无需回放：历史经 `SessionMemoryAdvisor` 按 sessionId 自动进上下文（`replayHistory` 只是 TUI 可视化）；`-c`/`--resume` 解析与冲突检查已在 main 公共段（L94-121） | implementation-map §7 |
| 12 | 工具异常永不 rethrow（转文本 + 栈前 6 帧），`ToolEventCallback` 把 turnId/taskId 压 ThreadLocal——headless listener 的事件与 TUI 完全同源 | implementation-map §2.4/§8.2 |

## 用户已核准的口径（2026-10-24/25）

1. **入口 `-p` flag**（非 exec 子命令）；`-p -` 读 stdin；
2. **输出 v1 文本**：stdout 仅最终回复；进度/诊断全走 stderr；json 事件流后续版本；
3. **权限 fail-closed + 可配**：默认 ASK→DENY；`--permission-mode`/`--dangerously-skip-permissions` 复用既有解析；
4. **会话持久化 + 可恢复**：默认新会话落盘；`-c`/`--resume` 链式；stderr 报会话 id。
5. 设计五节（入口参数/Listener/Runner/退出码/测试）已逐节确认（2026-10-25）。

## 设计定案

### 组件总览

| 组件 | 位置 | 职责 |
|---|---|---|
| `HeadlessAgentListener` | `headless/`（新包） | 事件→stderr、token 分段累积、问询/计划取消处置、latch 与退出码 |
| `HeadlessRunner` | `headless/`（新包） | headless 跑法：公共装配特化（不接 goal/todo 提醒线）、MCP 等待、submit→await→输出→exit |
| main 分叉 | `CodeTuiApplication` | 抽公共装配为包级方法（listener 与通告器作参数），`-p` 早分叉进 `HeadlessRunner.run`，TUI 路径零变化 |
| 参数解析 | `CodeTuiApplication` | `headlessPrompt(String[] args)`（含 `-p -` 哨兵）与 `outputLastMessagePath(args)` 纯函数，对齐 `--resume` 解析纪律 |

### `HeadlessAgentListener`

状态与线程模型：事件来自 reactive 线程与工具线程，**全部方法 synchronized**（同 `ConversationState` 纪律）；持有 `CountDownLatch done`、`volatile int exitCode`、分段缓冲、`Disposable turn`（`bindTurn(Disposable)` 由 Runner 在 submit 后注入——问询最早也发生在 submit 之后，无竞态窗口）。

| 事件 | headless 行为 |
|---| --- |
| `onAssistantToken` | 追加进 `currentSegment` |
| `onToolStarted` | **封段**：`currentSegment` 非空则推入 `middleSegments` 并清空；stderr 打 `⏺ <工具名> <入参首行摘要>` |
| `onToolFinished` | stderr 打 `⎿ ✓/✗ <输出首行摘要>` |
| `onSubagentStarted/Finished`、`onTodoUpdated`、后台任务 | stderr 单行 / 忽略 |
| `onRetryScheduled`/`onQuotaWaitScheduled` | stderr 单行（脚本排障必需） |
| `onGuardrailBypassed` | stderr 单行——**BYPASS 下 headless 尤其必须可见**（无人看着） |
| `onCompactionStarted/Finished/Failed` | stderr 单行 |
| `onRuleRecorded` | stderr 单行（含落点） |
| `onMcpReady` | 放行 `mcpReady` latch |
| `onPermissionRequested` | stderr「⚠ headless 拒绝 <工具>（无 UI 不可审批；可用 --permission-mode / allow 规则放宽）」+ `respond(DENY)`——**不 dispose**，回合继续（fail-closed 的带提示版） |
| `onQuestionAsked` | stderr 提示 + **try { `responder.cancel()`（解除工具线程 park）；`if (turn != null) turn.dispose()` } finally { `exitCode=2`；`done.countDown()` }**——放行必达：bindTurn 注入有理论微窗，dispose 也可能异常，任一前置失败都不许变成永久挂死（评审修订） |
| `onPlanSubmitted` | 记录计划正文 + 同款 **try/finally**（`respond(CANCEL, "")` + `turn` 判空 dispose；finally `exitCode=0` + `done.countDown()`）；**stdout 出计划正文**（`--permission-mode plan` + `-p` = 生成计划的脚本用法）。不用默认 KEEP_PLANNING——模型会反复提交死循环。非 plan 模式下模型自发调 ExitPlanMode 走同一出口（stdout 计划、exit 0），行为一致 |
| `onTurnComplete` | `done.countDown()`（exitCode 保持 0） |
| `onError` | stderr 打错误 + `exitCode=1` + `done.countDown()` |

**最终回复** = 最后一个非空段（`currentSegment` 空则取 `middleSegments` 末项）；中间段全部进 stderr。对齐 `claude -p`/`codex exec` 的 final message 语义。

**淘汰备选**：问询不 dispose、让异常转文本回给模型继续——模型行为不可控（重试问询/自答），脚本无法确定性分支；dispose + 退出码 2 才是可编程语义。

### `HeadlessRunner`

```
run(args) -> int 退出码：
1. 公共装配（与 TUI 共享同一静态方法，listener=HeadlessAgentListener，通告器=System.err）：
   provider/usageAccumulator → restoreLastModel（新增 Consumer<String> 通告器重载）→
   会话选择（-c/--resume 解析复用 main 既有段，冲突/零命中报错路径相同）→ 权限引擎（同一优先级链）→
   McpRegistry.init → goal 三件构造（便宜，但不接 wireGoal/wireTodoReminder——无人值守自动轮危险、UI 提醒无意义）→
   AgentTools.build → CodingAgent 构造 → wireL1（流重试必须，两模式同需）
2. MCP 等待：init 返回后查 connectingCount()，>0 则等 mcpReady latch，超时
   CODETUI_HEADLESS_MCP_WAIT_SECONDS（默认 10，钳 [1,300]）后继续（工具缺席照跑）；
   ==0（含零 server，事实 #6）跳过等待
3. agent.submit 包 try/catch（**submit 同步段会抛**：client==null 抛 IllegalStateException、能力快照失败——现码无外层 catch，评审证实）→ 捕获即 stderr + 退出 1；
   成功则 bindTurn(disposable) → done.await(**总硬顶** `CODETUI_HEADLESS_MAX_SECONDS` 默认 3600 钳 [60,86400]，事实 #9——
   到顶 dispose 回合、stderr 报告超时、退出 1)
4. 输出：stdout 最终回复（或计划正文）；--output-last-message 写文件（失败 stderr 警告，不改退出码）
5. stderr 收尾：会话 id 一行（--resume 提示）+ token 用量一行（usageAccumulator.snapshot）
6. finally 关 goalRunner/MCP → System.exit(exitCode)（事实 #10）
```

**恢复语义**：`-c`/`--resume` 时**不回放**（无屏；历史经 SessionMemoryAdvisor 照常进上下文，事实 #11），stderr 报「已恢复会话 xxx」。

### main 分叉（公共装配抽取）

- 抽包级静态方法承载 L86-201 的公共段（参数：`AgentListener listener`、`Consumer<String> notice`、`boolean tuiOnly`——后者控制 wireGoal/wireTodoReminder 与 replayHistory 段留在 TUI 路径）；`restoreLastModel` 加 `Consumer<String>` 重载，`ConversationState` 版一行委托。
- `-p` 解析钉位：**`-h` 之后、JUL 桥安装之后（headless 也要桥，否则 SDK 的 SEVERE 裸写 stderr 撕坏单行进度）、key 检测之前**；`-p` 存在即进 headless 路径。`headlessPrompt` 缺值/下一参以 `-` 开头且非 `-` 本身/`-p ""` 空白 → 报错退出 1（`-` 判定与 `resumeFlagValue` 的差异——`-` 本身是 stdin 哨兵——在 javadoc 写明）。
- **key 缺失路径（评审修订）**：`-p` 下「未检测到任何可用大模型 key」改走 **stderr + `System.exit(1)`**（TUI 路径维持 System.out+return 不变）——否则脚本会把提示文本当最终回复管道走、且以退出码 0 误判成功。
- help 文本补 `-p` 与 `--output-last-message` 两行（`StartupHelpTest` 钉守，必须同步）。

## 测试策略（先红后绿）

| 层 | 用例要点 |
|---|---|
| `HeadlessAgentListenerTest` | 分段（工具事件封段、最后非空段取法）；权限 DENY 文案与不 dispose；问询 cancel+dispose+latch+exit2；计划 CANCEL+正文+exit0+latch；onTurnComplete/onError 两路 latch；中间段进 stderr（PrintStream 注入捕获） |
| 参数解析单测 | `-p` 值 / `-p -` 哨兵 / 缺值 / `-p`+`-c` 共存合法 / `--output-last-message` 两种写法与缺值 / `-h` 优先 |
| `HeadlessRunner` 组装级 | 经真实 `FileSessionRepository` 断言会话落盘与 `--resume` 链式（无模型依赖的部分）；全链路真机冒烟 `@EnabledIfEnvironmentVariable(DEEPSEEK_API_KEY)`：只读一句 → stdout 非空/退出码 0/会话文件出现；`--resume` 第二步带上文 |
| TUI 零行为 | main 重构后既有全量测试不动全过（含 StartupHelpTest 更新后的新断言） |

## 影响面

- 新增：`headless/HeadlessAgentListener`、`headless/HeadlessRunner`；改动：`CodeTuiApplication`（分叉+装配抽取+解析+help）、`restoreLastModel` 重载。
- 不动：`AgentListener` 接口、桥、权限层、MCP、会话层——headless 完全经既有接缝消费。
- 文档：implementation-map 速查表+§1 补 headless 小节；README/reference.md 启动参数补 `-p`。
- 发布：v1.29.0。
