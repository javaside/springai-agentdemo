# 并行子 agent —— 批量 Task 工具（fan-out 并发执行）

> 让 code-tui 支持「一个回合内并发执行多个相互独立的子 agent」，使
> `dispatching-parallel-agents` skill 在 code-tui 里真正生效。

## 背景与目标

`dispatching-parallel-agents` skill 是一份**给主 LLM 的决策指令**：当模型面对「2 个及以上、相互独立、
无共享状态」的子任务时，在一条回复里同时发出多个子 agent 调用以并发处理。它假设底层运行环境具备
「一条回复里的多个工具调用会被并发执行」的能力。

**code-tui 现状不具备这条能力**：即便主 LLM 照 skill 在一条回复里发出多个 `Task` 调用，Spring AI 2.0 的
`ToolCallingManager` 也会在同一线程**逐个串行**执行这些 tool_call，每个 `Task` 内部又是阻塞的
`.call()`——一个子 agent 跑完才轮到下一个。结果是 skill 在 code-tui 里「空转」：模型以为在并行，实际排队。

**目标**：提供一个「并行底座」，让上述 skill 生效。经与用户确认，采用**新增批量工具**的方式（而非改造
框架逐个执行 tool_call 的行为），现有单任务 `Task` 零改动、零回归。

## 决策总览（已与用户逐项确认）

| 决策点 | 结论 |
|---|---|
| 执行模型 | 批量 fan-out + join 全部（非后台任务+轮询；后者推到 v2） |
| 工具形态 | **新增**批量工具 `ParallelTasks`，保留单任务 `Task` 不变 |
| 并发度 | 默认 4，可经环境变量 `CODETUI_SUBAGENT_CONCURRENCY` 覆盖 |
| 失败语义 | **部分失败隔离**：单个子 agent 失败不影响其他，如实标注成败 |
| 结果格式 | **结构化分块**：按入参顺序，每段带序号 + subagent_type + ✓/✗，失败带原因 |
| 取消（Esc） | **尽力全部中断** + 立即停止回灌、UI 立即回 IDLE（见「错误处理」的诚实边界） |
| 并发原语 | JDK 原生 `ExecutorService`（项目编译目标 Java 17，虚拟线程不可用），回合级局部线程池 |

## 关键技术发现（决定设计形态）

1. **串行根因三层**：① `SubagentRunner.run()` 阻塞 `.call()`；② Spring AI 逐个串行执行同回合的
   tool_call（框架行为，无法靠「发多个 Task」绕过）；③ 回合跑在单条 Reactor 线程。
   → 结论：必须在**单次工具调用内部自己 fan-out**（自控线程池），不能指望框架并行多个 tool_call。

2. **turnId/taskId 走 ThreadLocal（头号陷阱）**：`parentTurnId` 现经
   `ToolEventCallback.currentTurnId()`（ThreadLocal）取得，只在执行工具 call 的那个线程有效。
   一旦子 agent 移到线程池的别的线程，子线程 ThreadLocal 为空（-1L）→ turnId 丢失 →
   所有 UI 事件被 `ConversationState` 的 `if (turnId != acceptingTurnId) return` 迟到过滤器丢弃 →
   子 agent 活动在界面上完全不显示。
   → 纪律：**fan-out 前在工具线程里显式读一次 turnId，作为参数传进每个 Callable 闭包；子线程内绝不新读 ThreadLocal。**

3. **UI 层已就绪并发**（所以是改造非重写）：`ConversationState` 全 `synchronized`、线程安全；子任务/工具
   事件**已全部按 `taskId` keyed**（`List<Subtask>` + `findSubtask`），天生支持多个并发子 agent 同屏渲染，
   事件交错到达是预期且已支持。

4. **子 agent 已天然隔离**：`SubagentRunner.run()` 内每个子 agent **各自** `ChatClient.builder(model).build()`，
   底层 OpenAI/Anthropic SDK client 无状态、可并发。子 agent 内部工具事件经 `.toolContext(...)` **显式**携带
   turnId/taskId，故子线程里 `ToolEventCallback` 从 toolContext 取值仍正确。

## 架构与数据流

```
主 LLM 一条回复里调用 ParallelTasks({tasks:[t1,t2,t3]})
    │  （工具 call 在执行 tool 的线程上）
    ├─ 读 parentTurnId = ToolEventCallback.currentTurnId()   ← 必须在此线程、fan-out 之前
    ├─ 路由每个 t.subagent_type → SubagentSpec（未知→该条降级为失败，不抛）
    └─ SubagentRunner.runAll(dispatches, parentTurnId)
           │
           ├─ pool = newFixedThreadPool(min(N, maxConcurrency))
           ├─ 每个 dispatch 包成 Callable：run(spec, prompt, desc, parentTurnId)  ← turnId 显式入闭包
           │      └─ 各自 ChatClient + 各自 taskId；子 agent 内部工具事件经 toolContext 上报（带 turnId/taskId）
           ├─ invokeAll → 按入参顺序收集 String 结果（成功=最终文本；失败=「失败：原因」）
           └─ finally { pool.shutdownNow() }   ← 回合级池，跑完即销毁
    │
    └─ 结构化汇总成单个字符串，作为本次 tool_call 结果回灌主 agent
```

**并发下的事件流**：多个子 agent 的 `onSubagentStarted` / 内部工具事件交错到达 `ConversationState`，
各自按 `taskId` 落到对应 `Subtask`（任务面板）与 scrollback（缩进块）。无需改渲染。

## 组件改动清单（聚焦、无牵连重构）

1. **`SubagentRunner`（改动，新增方法）**
   - 新增 `List<String> runAll(List<Dispatch> dispatches, long parentTurnId)`。
   - 新增内嵌 record `Dispatch(SubagentSpec spec, String prompt, String description)`。
   - 构造函数新增 `int maxConcurrency`（保留现有构造，重载或加参）。
   - 复用现有 `run()` 作为单个执行单元；`run()` 本身**不改**。
   - 局部固定线程池、`invokeAll`、`finally shutdownNow`。单条失败在 `run()` 内已 emit
     `onSubagentFinished(ok=false)` 并抛出，`runAll` 捕获成「失败：<msg>」文本、不外抛（失败隔离）。

2. **批量工具 `ParallelTasks`（新增）**
   - 放在 `SubagentTool`（新增静态工厂方法）或新类；入参 `record ParallelCall(List<SubagentCall> tasks)`，
     元素复用现有 `SubagentCall(description, prompt, subagent_type)`。
   - 路由：逐个 `subagent_type → spec`；未知类型该条降级为失败（**与单任务 `Task` 抛异常的行为有意不同**，
     服从失败隔离语义——文档标注，避免误判为 bug）。
   - 结果汇总（按入参顺序，空行分隔）：
     ```
     [1] general-purpose ✓
     <子 agent 1 最终文本>

     [2] explore ✗ 失败：<原因>

     [3] general-purpose ✓
     <子 agent 3 最终文本>
     ```
   - 工具描述明确：用于 2+ **相互独立、无共享状态**的子任务；有依赖/需共享上下文的用单个 `Task`。

3. **`AgentTools.build`（改动）**
   - 读 `CODETUI_SUBAGENT_CONCURRENCY`（非法/缺失回退 4）注入 `SubagentRunner`。
   - 构造 `ParallelTasks` 工具、用 `ToolEventCallback` 装饰（与 `Task` 一致，委派本身在主流显示为一行）。
   - 加入主 agent 工具集 `toolsWithTask`。**不给子 agent**（子 agent 不应再派并行子 agent，避免递归 fan-out）。

**不改动**：`ConversationState`、UI 渲染、`AgentListener` 接口、单任务 `Task`、`SubagentTool` 现有路由、
`SubagentSpec`。**无新增依赖**（仅 JDK `java.util.concurrent`）。

## 错误处理与边界

- **部分失败**：单个子 agent 抛错 → `runAll` 捕获为该条「✗ 失败：msg」，其余照常。批量工具整体**成功返回**
  （汇总含成败），主 agent 据此决定下一步。
- **未知 subagent_type**：批量内降级为该条失败（不抛），不炸整批。
- **取消（Esc）—— 诚实边界**：
  - 目标是「尽力全部中断」。`invokeAll` 被中断 → `shutdownNow()` 中断在飞线程。
  - **不保证硬杀**：子 agent 阻塞在 HTTP 网络读上，`Thread.interrupt()` 不一定能打断底层 socket read。
    实际效果 = **立即停止回灌 + UI 立即回 IDLE**；个别在飞子 agent 可能继续跑到网络返回才真正结束。
    这与现有单个 `Task` 被 Esc 的软取消行为一致，不夸大为「强杀」。
  - 批量工具是**单个 tool_call**，取消后不产生「带 tool_calls 无结果」的悬空；现有
    `trimDanglingToolCalls` 仍兜底。
- **turnId 迟到过滤**：并发子 agent 的所有事件都携带 fan-out 前捕获的 `parentTurnId`，与
  `acceptingTurnId` 一致，不会被误过滤。

## 开放风险（实现阶段必须实测确认）

1. **取消可靠性**：Reactor 流 dispose 是否能中断到阻塞在 `invokeAll` 的工具线程，取决于 Spring AI 2.0
   工具执行是否可中断——**未 100% 确认**。
   **退路（已想好，一并落地）**：批量工具内部检查一个「回合已取消」的 volatile 标志（由 CodingAgent 的
   `doOnCancel` 置位），在收集/提交循环里主动短路，不依赖 interrupt 送达。
2. **ChatClient/ChatModel 并发安全**：设计上每子 agent 独立 builder，理应安全；实现阶段先写**并发冒烟测试**
   （多线程并发跑假 ChatModel）确认无共享可变状态问题。若有，退路是已隔离的 builder，不影响设计。

## 测试策略（TDD，仿现有 `SubagentRunnerOkTest` 无网络套路）

- `runAll` 全部完成：N 个假 ChatModel，断言结果数量、**按入参顺序**。
- **失败隔离**：混入一个抛异常的子 agent，断言其余成功、该条标失败、整体不抛。
- **turnId 传播（锁死头号陷阱）**：在非主线程执行，断言 listener 收到的 turnId == 传入的 parentTurnId
  （防 ThreadLocal 陷阱回归）。
- **并发上限**：注入 `maxConcurrency=2`，用 `CountDownLatch` 验证同时在飞 ≤ 2。
- **并发安全冒烟**：多线程并发跑，无异常、无串扰。
- **`ParallelTasks` 工具**：schema（含 `tasks` 列表）、路由、未知类型降级为失败（仿 `SubagentToolTest`）。

## 验证命令基线

`mvn -pl springai-code-tui test`（模块作用域；并发测试须跑真实多线程，不用 `-Dtest` 单点以免漏跑）。

## YAGNI / 明确不做

- **不做**后台任务 + 轮询回收（`run_in_background` / `TaskOutput`）——推到 v2。
- **不改**单任务 `Task` 的入参与行为。
- **不给**子 agent 分发 `ParallelTasks`（禁止递归 fan-out）。
- **不引入**共享的应用级线程池（回合级局部池足够，避免共享可变状态与生命周期管理）。
- **不做**跨 provider 并行路由——子 agent 仍走激活 provider（与现状一致）。
- **不做**响应式化 `SubagentRunner`（阻塞 join 对有界 fan-out 足够；响应式得不偿失）。
