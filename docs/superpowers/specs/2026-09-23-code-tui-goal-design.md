# code-tui /goal 功能设计（MVP）

- 日期：2026-09-23
- 状态：已批准·已实现（计划：docs/superpowers/plans/2026-09-29-code-tui-goal.md）
- 参考：Claude Code `/goal`（v2.1.139+）、OpenAI Codex `/goal`（v0.128+）
- 前置评审：三路 subagent 审核（架构代码验证 / 产品对标 / 熔断矩阵与测试），修订已并入本文

## 1. 背景与目标

AI 编码助手常见"干一轮就停"：大任务需要用户反复手动"继续"。goal 功能 = 用户设定一个**完成条件**，agent 跨多个 turn 自主循环：每轮结束后由**独立小模型评估器**判定条件是否达成——未满足则把原因注入下一轮继续；满足/不可能满足则停止；配轮数、无进展、token 预算三重熔断。

用户已拍板的口径：

| 决策点 | 结论 |
|---|---|
| 评估器 | 独立小模型（env 配置，未配置回退跟随当前模型） |
| 熔断 | 最大轮数 + 无进展刹车 + token 预算，三者全要 |
| 持久化 | 会话内存态，`/clear` 清、退出即失，不落盘 |
| 范围 | 最小闭环（交互式 TUI only） |
| Esc 语义 | 两级：第一次暂停（发消息即续），第二次终止；stalled 熔断同口径（暂停） |
| 轮间节奏 | 3 秒可视倒计时（env 可配 0 关闭） |

## 2. 范围

**做**：`/goal <条件>` / `/goal`（面板）/ `/goal clear`（别名 `stop`）；评估器循环；状态栏指示；两级 Esc；3 秒倒计时；三重熔断（部分为暂停语义，见 §7）；终态总结与事后可查。

**不做（明确出栈）**：`pause`/`resume` 显式命令（两级 Esc + 暂停态即穷人版）、headless `-p` 模式、独立 goal.json、check-in 空闲唤醒、权限行为改变、`/goal amend`（用户插话已进评估器输入，见 §6.2）。

## 3. 用户可见行为

### 3.1 命令面

- `/goal <条件>`：设定（替换旧 goal，旧 goal 的在途评估按 epoch 丢弃）。**立即发起首轮**。忙碌时照 `/continue` 先例 enqueue。条件上限 4000 字符，空/纯空白拒绝且无状态变更。当前权限档位为 DEFAULT 时提示"建议 Shift+Tab 切到 ACCEPT_EDITS/BYPASS，否则循环会停下等批准"（行为不变，仅提示）。条件含 `or stop after N turns` 类子句不做硬解析，由评估器语义判断（脚手架会告知当前 N/M）。
- `/goal`：面板——条件、状态（RUNNING/PAUSED(原因)/终态）、N/maxTurns、token used/budget、stalled streak、最近 8 条评估结论轨迹、运行时长。**终态后面板保留该次 goal 报告**，直到下一个 `/goal` 命令。
- `/goal clear`（别名 `stop`）：任何时刻可用，清除并回显被清条件。为避免与 `/clear` 误触混淆，文档主推 `stop`。

### 3.2 状态栏（leading span，与 modeTag 拼接为一个 Span）

| 状态 | 显示 |
|---|---|
| RUNNING（等 turn 结束） | `◎ goal N/M` |
| EVALUATING | `◎ goal 评估中` |
| 倒计时 | `◎ goal ⏳3s`（数字随刷新动，照限额倒计时先例） |
| PAUSED | `◎ goal 已暂停` |

### 3.3 Esc 两级语义

- 自动轮进行中按 Esc：取消当前轮，goal → `PAUSED(ESC)`，提示"已暂停，发送任意消息继续；再按 Esc 或 /goal stop 终止"。
- EVALUATING 中按 Esc（此时已 IDLE）：goal → `PAUSED(ESC)`；在途评估不硬中断，迟到 verdict 按 epoch 丢弃。
- 倒计时中按 Esc（同样 IDLE）：goal → `PAUSED(ESC)`，取消待发轮。
- PAUSED 中再按 Esc：`CANCELLED` 终态。
- 两条取消路径（输入框 Esc 与 `cancelTurnFor`：问询 Esc / 畸形问询 / 审批"中断本回合" / 计划 Esc）统一走同一私有方法。

### 3.4 终态与暂停的通知

- 每次进入终态/暂停：一行式总结（终态 + 原因 + N/M + token 用量）。
- 每次评估结论（verdict + reason）写入会话 transcript（带合成标记，HistoryReplay 渲染为系统块、不重放为用户消息）——这是评估轨迹的权威来源。
- 会话恢复（`-c`）时若 transcript 检测到 `[goal 继续]` 痕迹：打印一行"上次会话有未完成 goal，已失效"。

## 4. 消息形态示例（模型实际收到的内容）

### 4.1 自动轮的 user message（干活 agent）

```
[goal 继续 3/25]
目标：把 AuthService 从旧版 UserClient 迁移到新版 API，mvn -pl server test 退出码 0，不许改 pom.xml。
评估器结论（上一轮）：server 模块还有 4 个调用点编译失败，集中在 TokenRefresh 流程。
累积进度：✓ UserClient 接口替换 ✗ TokenRefresh 调用点 ✗ mvn test 全绿

请继续推进。本轮结束时必须给出可复验的审计证据：执行过的命令与退出码、变更的文件列表；
若认为目标已达成，请附上验证命令的原始输出。不要重复已完成的工作。
```

脚手架语言跟随条件语言（中文条件 → 中文脚手架），前缀 `[goal 继续 N/M]` 为英文数字形式仅作标记。

### 4.2 评估器 prompt（独立小模型，裸 client）

system（要点）：你是目标验收评估器。只依据给定的滚动记录判断，不假设、不要求执行任何命令。严格按协议输出四个标记行。附 few-shot：英文标记 + 中文 REASON 示例（防中文小模型输出"满足/未满足"）。

user（要点）：条件原文、N/M、stalled streak、上一轮 STATE 账本、最近 8 轮记录（每轮：轮号、评估 reason、agent 末文本**尾部**截断 ≤2000 字符、本轮工具调用数、该轮用户插话原文若有）。maxTokens=512。

## 5. 架构

新包 `agent/goal/`：

| 类 | 职责 |
|---|---|
| `GoalConfig` | env 解析 + 钳制（照 `StreamRetryConfig` 模式） |
| `GoalManager` | 状态机 + 滚动记录 + epoch；`implements UiChangeSource`（照 `Interjections` 锁内改、锁外 publish） |
| `GoalEvaluator` | 接口（构造注入 fake 供测试）；prod 实现组装 record → 裸 client 阻塞 `.call()` → 解析 verdict |
| `GoalVerdict` | record：verdict / reason / progress / state 账本 / 原始输出（诊断用） |
| `GoalPhase` | 状态枚举（见 §7） |

装配：`AgentTools.build` 构建并经 `AgentRuntime` 传递（**不**拉长 CodingAgent 22 参构造）。

### 5.1 状态机

```
INACTIVE
  └─ /goal <条件> → RUNNING
RUNNING（含监控/评估中/倒计时/挂起，由标志位区分）
  ├─ verdict SATISFIED / IMPOSSIBLE        → 终态
  ├─ 轮数达 maxTurns / 预算超限（决策点）    → 终态 MAX_TURNS / BUDGET_EXCEEDED
  ├─ Esc（第一级）                          → PAUSED(ESC)
  ├─ 连续 stalledLimit 轮 stalled           → PAUSED(STALLED)
  ├─ onError 连续 errorRetryLimit+1 次      → PAUSED(ERROR)
  ├─ 评估器连续 evalFailLimit 次调用异常     → PAUSED(EVALUATOR)
  ├─ 协议解析连续 protocolFailLimit 次失败   → PAUSED(PROTOCOL)
  └─ /goal stop / /clear / 二次 Esc         → CANCELLED / CLEARED
PAUSED(任一原因)
  ├─ 任意用户消息 dispatch                  → RUNNING（对应计数器重置）
  └─ Esc / /goal stop / /clear             → CANCELLED / CLEARED
终态（SATISFIED / IMPOSSIBLE / MAX_TURNS / BUDGET_EXCEEDED / CANCELLED / CLEARED）
  └─ 一切迟到事件（verdict / turn_end / 重试到期）no-op（终态单调性）
```

### 5.2 驱动流程（挂在 `CodeTuiView.processUpdates` 空闲批）

槽位顺序：插话出队 > 排队用户消息 > 后台结果送达 > **goal 槽**。每批最多一个自动动作，动作后提前 return。

```
turn 结束（onTurnComplete，此时已置 IDLE）
→ 空闲批：有排队用户消息/插话/后台结果？→ 是：先出队它们，本批结束（goal 顺延）
→ 否：goal RUNNING 且未在评估？
   → 取本轮素材：读 session 落库事件倒扫最后一条 AssistantMessage（跳过尾部插话 UserMessage）、
     统计本轮工具调用数（机器信号，非 agent 自报）
   → CAS 置"评估在飞"，后台 daemon 线程（命名 goal-evaluator）跑评估器（带 goalId+epoch，60s 超时）
   → verdict 返回（任意线程）：
       · 校验 epoch，不符丢弃
       · SATISFIED/IMPOSSIBLE → 终态 + 总结（回 UI 线程 publish）
       · UNSATISFIED → 记录滚动记录 + 若倒计时开启则置 deadline，否则置"待发轮"标志
       · 调用异常/解析失败 → 对应独立计数（§7）
→ 后续空闲批：倒计时到期（或无倒计时）且仍无用户输入 → UI 线程 dispatch 自动轮（§4.1 文案，
   dispatch 时 turnsUsed+1、token 预算决策点复检）
```

关键纪律（来自架构评审）：

- **自动轮 dispatch 只发生在 UI 线程空闲批**（评估线程绝不触碰 View 的 `current`/`lastShownModel`）；评估完成仅 publish。
- **评估在飞 CAS**：`finally` 复位，防异常静默死锁。
- **挂起 verdict 单槽**：倒计时/挂起期间用户消息到达 → 旧 verdict 作废重评（插话改变上下文，旧判断过期）。
- 激活即发首轮，不存在"空闲态等待 turn_end"死状态。
- 忙碌中 `/goal <条件>` 照 `/continue` enqueue。

## 6. 评估器设计

### 6.1 模型选择

`CODETUI_GOAL_EVALUATOR_MODEL`（格式 `providerId:modelId`，`indexOf(':')` 切分）→ `ProviderRegistry.requestSelection(p, m).provider().chatModel()` 上自建**裸** `ChatClient`（无 defaultTools、无 SessionMemoryAdvisor、无系统模板——守卫测试钉住）。未配置或 `IllegalArgumentException` → 回退 aux 通路（`DynamicAuxChatModel`）。回退在**激活时定死**（不中途换脑）并显式日志。评估器调用 60s 超时（超时归类为调用异常，走 EVALUATOR 计数）；aux 链无重试装饰器（既有守卫），瞬时异常同走 EVALUATOR 计数。

### 6.2 输入（GoalManager 滚动记录，内存态、不进对话历史、压缩不影响）

条件原文（≤4000 字符）、N/M、stalled streak、STATE 累积账本（评估器每轮输出、滚动传递，解决 8 轮窗口遗忘）、最近 K=8 轮（每轮：评估 reason、agent 末文本尾部截断 ≤2000 字符 UTF-16 代理对安全、本轮工具调用数、**该轮用户插话原文**——用户纠偏对评估器可见，防 goal 与用户对抗）。

### 6.3 输出协议与解析

```
VERDICT: SATISFIED | UNSATISFIED | IMPOSSIBLE
REASON: <一句话，语言跟随条件>
PROGRESS: advancing | stalled
STATE: <压缩的累积进度账本（checklist 风格）>
```

解析：逐行扫描、大小写不敏感、容忍围栏代码块包裹、**取最后一个 VERDICT**（防条件文本自注入）。缺 PROGRESS 默认 `stalled`（保守）；缺 STATE 沿用旧账本；无 VERDICT 行 = 解析失败（独立计数）。REASON 中的标记字样不干扰（只认行首标记）。

## 7. 熔断矩阵

| 熔断 | 默认 | 口径 | 结果 |
|---|---|---|---|
| maxTurns | 25 | **只计自动轮**（含 onError 续跑轮），dispatch 时 +1；用户插话轮不烧配额 | 终态 MAX_TURNS |
| stalled | 连续 3 轮 | streak 来源：PROGRESS=stalled **或** 本轮零工具调用（机器信号）；advancing 或真实用户输入重置 | PAUSED(STALLED) |
| token 预算 | 5,000,000 | activate 时快照做差；主 agent + 评估器同一条 usage 采集链（天然聚合，测试钉住）；决策点（评估启动前 + dispatch 前）判定；**软超限**（在飞轮放行到轮末）；0=关 | 终态 BUDGET_EXCEEDED（总结用 STATE 账本生成，不额外烧一轮） |
| onError | 连续 3 次尝试（2 次续跑） | 信号源：`CodingAgent.handleError` 通知；连续计数、成功重置；**Esc 取消不计**；空回复/拒答不算（交评估器判 stalled）；续跑轮计入 maxTurns 与预算 | PAUSED(ERROR) |
| 评估器调用异常 | 连续 2 次 | 与解析失败分开计数 | PAUSED(EVALUATOR) |
| 协议解析失败 | 连续 3 次 | 单次失败按 UNSATISFIED + PROGRESS=stalled 计 | PAUSED(PROTOCOL)，展示原始输出助诊断 |

决策点原因优先级（多个条件同时为真时终态/暂停原因唯一确定，日志可断言）：
`CANCEL/CLEAR > BUDGET > MAX_TURNS > STALLED > ERROR > EVALUATOR > PROTOCOL`

## 8. 权限

行为完全不变（与 Claude Code 一致：Manual 档照样弹批准）。DEFAULT 档激活时提示切档（§3.1）。不引入后台任务式 ASK 自动拒绝。

## 9. 配置项（`CODETUI_*` env + 钳制）

| 变量 | 默认 | 说明 |
|---|---|---|
| `CODETUI_GOAL_EVALUATOR_MODEL` | 空 | 空=跟随当前模型（aux 回退） |
| `CODETUI_GOAL_MAX_TURNS` | 25 | 自动轮上限 |
| `CODETUI_GOAL_STALLED_LIMIT` | 3 | 连续 stalled 暂停阈值 |
| `CODETUI_GOAL_TOKEN_BUDGET` | 5000000 | 0=关闭 |
| `CODETUI_GOAL_TURN_GAP_SECONDS` | 3 | 轮间倒计时，0=背靠背 |
| `CODETUI_GOAL_ERROR_RETRY` | 2 | onError 自动续跑次数 |
| `CODETUI_GOAL_EVAL_FAIL_LIMIT` | 2 | 评估器调用异常暂停阈值 |
| `CODETUI_GOAL_PROTOCOL_FAIL_LIMIT` | 3 | 解析失败暂停阈值 |
| `CODETUI_GOAL_EVAL_TIMEOUT_SECONDS` | 60 | 评估调用超时 |

## 10. 涉及文件

| 文件 | 改动 |
|---|---|
| `agent/goal/*`（新增 5 类） | 本体 |
| `agent/CodingAgent.java` | handleError 加 goal 通知；clearContext 清 goal；暴露 usage 完整 snapshot 门面（现 contextStats 不含 completionTokens） |
| `ui/CodeTuiView.java` | COMMANDS + `/goal` 分支；空闲批 goal 槽（deliverBackgroundResults 之后）；statusBar 指示；`/goal` 面板；两条取消路径统一挂两级 Esc |
| `agent/AgentTools.java`（build） | 装配 GoalManager/GoalEvaluator 进 AgentRuntime |
| `ui/HistoryReplay.java` | 恢复时 goal 痕迹提示（轻量） |

## 11. 测试策略

纪律：预期日志 ExpectedLog 断言式接管；异步全部 latch/虚拟时间带超时；测试不真调模型；fake 全部构造注入（GoalEvaluator、usage、可控 executor、可暂停 evaluator 用 CompletableFuture 手动放行）。

**单测**：状态机转移表穷举（含终态单调、暂停恢复、二次 Esc）；解析器表驱动（缺字段/双标记/大小写/围栏包裹/空输出/自注入/截断）；熔断优先级；口径（用户轮不烧 maxTurns、续跑轮烧）；stalled 重置矩阵；预算做差与软超限；UTF-16 截断。

**守卫测试**：评估器请求无工具/无会话记忆/单轮无状态；评估不写 transcript（前后 hash）；自动轮 dispatch 落 UI 线程；回退 aux 显式日志。

**集成最小集（14 场景）**：①UNSAT×2→SAT 闭环 ②stalled 刹车与 streak 重置（含交错序列）③首轮 IMPOSSIBLE ④maxTurns 边界+插话不烧配额 ⑤预算先撞+软超限+评估器计入+0=关 ⑥onError 家族（重试成功/耗尽/Esc 不计）⑦慢评估中插话（用户先走、旧 verdict 作废重评）⑧慢评估中 Esc（epoch 丢弃迟到 verdict）⑨慢评估中 clear/`/clear`（全量清理、transcript 不复活）⑩慢评估中替换新条件（旧 verdict 不作用新 goal）⑪评估器连抛异常（CAS 复位无死锁、EVALUATOR 暂停）⑫终态僵尸防护+多熔断同真唯一原因 ⑬解析失败风暴（PROTOCOL 暂停不烧预算）⑭压缩中途存活+守卫断言。

## 12. 实现顺序建议

1. `agent/goal` 纯逻辑层 + 单测（状态机/解析/熔断，全 fake）
2. 守卫测试 + 装配（AgentTools.build / AgentRuntime）
3. View 接线（命令/空闲批槽/状态栏/面板/两级 Esc/倒计时）+ 集成场景 ①–⑥
4. 并发家族集成场景 ⑦–⑭ + PTY 冒烟（真机评估器用智谱 key 跑一轮 UNSAT→SAT）
