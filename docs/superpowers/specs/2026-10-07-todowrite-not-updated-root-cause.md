# TodoWrite「建了清单不更新」根因分析

状态：定稿（2026-10-08 复核收敛；调查会话 2026-10-07）。
关联实现：[2026-10-07-todo-stale-reminder-design.md](2026-10-07-todo-stale-reminder-design.md)（harness 兜底，已交付计数版）。

## TL;DR

**TodoWrite 的使用纪律（完成后立即标记）写在工具描述里，而工具描述走 API 请求的 `tools`
字段——模型把它当「接口文档」读，不当「行为指令」读。** 在没有更强指令竞争时这够用
（直接执行的会话更新得教科书级）；一旦场景里出现更具体、更近、带格式模板的记账流程
（SDD skill 的台账），动作选择时刻就被它抢走；而首次遗漏之后环境零反馈、模型自身历史
开始示范错误流程，遗漏永久化。

一句话因果链：**规则放在了函数目录里当文档（通道错位）→ 台账抢占动作槽（现场）→
零反馈 + 自我模式强化（永久化）。**

## 案发现场（实测取证）

会话：`/Users/zxh/IdeaProjects/xibaojun/.codetui/sessions/20261007T143538-009fe4.json`（201 事件）。

| 证据 | 数据 |
|---|---|
| TodoWrite 调用次数 | **1 次**（#125：8 项清单，Task 1 in_progress） |
| 台账追加次数 | **8 次，时机全对**（#135/#143/#151/#159/#167/#175/#183/#196，`cat >> progress.md`） |
| 台账行格式 | 逐字套用 skill §5 模板：`Task 1: complete (commits e23e4fd..a3f4283, review clean)` |
| 最终结果 | 8 个 commit、7 任务全部完成；面板全程冻结在「Task 1: in_progress」 |
| 压缩 | **无**（仅有的 2 处 "ompact" 字符串：`PngEncodeUtil#writeCompact`、skill 原文里的 "compaction" 一词） |
| 首次遗漏时刻清单的距离 | #126→#135 仅 **10KB / 9 个事件**（subagent 内部上下文隔离，Task 返回只是 1~2KB 最终报告，主 agent 上下文一直不大） |
| skill 正文 | 32922 字符；**`TodoWrite` 字样 0 次**；"mark the todo complete" 是唯一更新指令，无工具名绑定 |

## 排查过程：被淘汰的假设

| # | 假设 | 淘汰证据 |
|---|---|---|
| 1 | 模型记账纪律差 | 台账 8/8 全中、skill 格式模板逐字照抄 |
| 2 | 上下文压缩导致遗忘 | 全会话无 compaction 事件 |
| 3 | 清单太远、注意力稀释（首次遗漏时） | 实测 #126→#135 仅 10KB；"76 个事件"是遗漏持续一小时后的**后果**，不是原因 |
| 4 | subagent 委派是特殊场景，描述不适用 | subagent 只是一个工具：调 Task 和调 Bash 对控制器无结构差异，"After completing a task" 对 Task 返回同样成立 |
| 5 | skill 与 TodoWrite「双轨记账竞争」 | skill 与工具零绑定（名字 0 次），不存在竞争；是「skill 提供替代动作 + TodoWrite 指令真空」 |
| 6 | 工具描述写得差 | 描述是业界全文（4.7KB）；对照组（直接执行、无 skill）更新纪律完美 |

## 真正的根因（三层）

### 层 1：通道错位——使用纪律放在了「接口文档」通道

模型每回合收到的请求体里，工具与指令是两个平级通道：

```json
{ "messages": [ {"role":"system","content":"…工作方式…"}, … ],
  "tools":     [ {"function": {"name":"TodoWrite","description":"…4.7KB…","parameters":…}} ] }
```

- `messages`（含 system）：**指令**，持续生效的行为约束；
- `tools`：**函数目录**，模型在「选下一个动作用哪个工具」时查阅的参考资料。

TodoWrite 描述里真正值钱的行为规则——"Update task status in real-time as you work"、
"Mark tasks complete IMMEDIATELY after finishing"——被放在了后者。**这个「描述即纪律」
的约定只对按 Claude Code 轨迹训练过的 Claude 系模型生效**；我们接的 GLM/GPT/DeepSeek
没有这个训练，`tools` 字段对它们是 API 文档。（挂载点：`AgentTools.java` 的
`.defaultTools(toolsWithTask)`。）

对照组证明这条是「弱」而非「无」：直接执行的会话里，没有更强的记账指令竞争，
「记录进度」状态出现时 TodoWrite 是唯一选项，一行系统提示词 + 接口文档就足以赢得
动作选择——所以那批会话更新得教科书级（`[5,1,0]→…→[0,0,6]`）。

### 层 2：动作槽被占——SDD 场景里唯一的强指令指向另一个动作

首次遗漏时刻（#135）模型的三条指令通道状态：

| 通道 | 关于「更新 TodoWrite」的内容 | 效力 |
|---|---|---|
| 系统提示词 | 一行创建导向指引（「先调用 TodoWrite 把工作拆成清单……完成后立刻标 completed」），更新是从句 | 弱 |
| `tools` 字段 | 完整 Task Management 规则 | 接口文档级（层 1） |
| skill 正文（33KB，13 个事件前加载） | 台账流程，精确到格式模板；"mark the todo complete" 无工具名绑定，且被它自己规定的台账行（`Task N: complete…`）**语义吸收** | 强，但指向台账 |

模型在「现在记一下进度」的状态下选了最具体的指令执行——台账赢了动作槽。
它极可能认为写完 `Task 1: complete` 这行，"mark the todo complete" 已一并满足。

### 层 3：零反馈 + 自我模式强化——单次遗漏永久化

- 面板是 UI 层，模型看不见；工具返回是一次性静态文案（"Todos have been modified
  successfully…"，#126 之后永不再现）；
- harness 无任何机制把过期清单重新送进模型上下文；
- 写不写 TodoWrite 对任务推进、审查结果、台账内容**没有任何可观察差异**；
- 第一轮过后，模型自己的历史示范了「审查通过 → cat >> 台账 → 派下一个」完整流程，
  LLM 对自身已建立模式的延续倾向使每一轮都在复制它。

→ 漏一次 = 漏到底。

## 证据等级说明

- 实测：案发现场全部数据、请求结构、skill 文本计数、对照组会话统计（94 个大会话）。
- 推断（与全部事实吻合、无反证，但未做对照实验）：层 1 的「非 Claude 模型把 `tools`
  描述当文档而非纪律」；层 2 的「台账行语义吸收了 mark-complete」。可证伪方式：
  把纪律写进系统提示词后重跑同型 SDD 会话，观察更新行为是否恢复。

## 修复方案（对应三层）

| 层 | 修法 | 状态 |
|---|---|---|
| 层 1 通道错位 | 系统提示词（`AgentTools` 工作方式段，我们代码里的字符串）增加任务清单纪律段，明确覆盖委派语义：**subagent 返回且通过审查 = 任务完成，必须先调 TodoWrite 更新状态再继续** | 待做 |
| 层 2 动作槽 | `TodoStaleReminder` 加事件对齐触发：控制器级 Task/ParallelTasks 返回且清单有未完成项 → 当场在返回尾部点名，不数满阈值 | 待做 |
| 层 3 零反馈 | 同上（运行时注入即反馈回路）；计数版兜底（默认 8 次控制器调用触发） | **已交付**（main，见关联 spec） |

第三方 skill（subagent-driven-development）**一字不动**：不可控（上游重同步即覆盖）、
与工具无绑定关系、修订收益有限；曾有本地修订，2026-10-08 已恢复原样。

## 附：工具信息的放置原则

| 信息性质 | 例子 | 放哪 |
|---|---|---|
| 接口文档：是什么、怎么调、参数/返回约束 | 入参 schema、"Pass the FULL list every call" | `tools` 字段（随 schema 走） |
| 行为纪律：什么事件必须用、用完的义务 | 「每任务完成先更新再继续」 | 系统提示词 |
| 现场强制：此刻必须做 | 「subagent 刚返回、清单未动 → 现在更新」 | 运行时注入（工具结果尾部） |
