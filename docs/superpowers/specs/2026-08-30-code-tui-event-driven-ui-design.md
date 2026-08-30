# Code TUI 事件驱动 UI 重构设计

## 1. 背景

当前 `CodeTuiView` 通过两个永久周期推进界面：

- TamboUI `tickRate(100ms)` 周期性调用 `render()`，即使状态没有变化也会重建整棵 UI 树；
- `scheduleRepeating(...drain..., 66ms)` 周期性调用 `drain()`，轮询并消费输出、流式缓冲、模态请求、排队消息、后台结果、resize 和注意状态。

终端输入读取虽然在独立线程进行，但按键处理、`drain()`、scrollback `println` 和 `render()` 最终都在同一个 TamboUI 事件线程串行执行。一次 `drain()` 做大量 Markdown、高亮、折行、diff 展开或 PTY 写入时，按键只能等待。当前物理行限流、预览节流和 tick 降频降低了风险，但没有改变“永久轮询 + 单线程不可抢占重任务”的结构。

本次重构的目标不是删减功能，而是只改变 UI 与 Agent 之间的驱动和调度机制：从周期轮询改为状态变化通知驱动，同时完整保留现有命令、面板、输出、取消、排队、插话、后台任务、resize、IME 和终端保护语义。

## 2. 已确认目标

1. 删除常驻的 100ms 全局重绘 tick 和 66ms drain 轮询。
2. Agent、工具、MCP、后台任务和外部状态变化后主动唤醒 UI。
3. 所有终端读写、UI 状态机、自动出队和 render 继续由唯一 UI 线程串行执行。
4. 高频流式 token 通知必须合并，不能把轮询风暴替换成事件风暴。
5. 通知只负责唤醒，业务数据继续持久保存在状态或队列中，避免通知合并或重复导致数据丢失。
6. 空闲时不再有周期 wakeup 或 ANSI 输出。
7. 节流、防抖、输出限速和动画继续保留，但改为事件触发的按需一次性调度，状态消失后立即停止。
8. 所有现有功能和用户可见语义保持不变。
9. 验收包括完整 Maven 回归、现有 PTY 冒烟、新增输出与输入并发压测，以及真实 Terminal.app 人工验收步骤。

## 3. 非目标

本次不做以下改变：

- 不删除或简化任何斜杠命令、选择器、模态面板、状态栏、动画或任务面板；
- 不改变 Agent prompt、模型调用、会话记忆、工具执行或权限判定；
- 不把 `ConversationState` 改造成只转发事件、没有存量状态的总线；
- 不引入 Reactor Flux 作为第二套 UI 事件循环；
- 不让 Agent/Reactor/工具线程直接 render、`println`、自动 dispatch 或操作输入框；
- 不把自动 PTY 测试结果等同于真实 Terminal.app + IME 验收结果。

## 4. 现有真实流程与根因

### 4.1 线程与队列

`InlineTuiRunner` 维护一个 `LinkedBlockingQueue<Event>`。输入读取线程、resize callback、tick scheduler 和 `runOnRenderThread` 都向该队列投递事件。运行 `InlineTuiRunner.run()` 的线程是渲染线程：它顺序处理事件并调用 handler 或 renderer。

因此：

- 输入读取线程只负责读取和入队；
- `CodeTuiView.onInputKey()` 在渲染线程执行；
- 66ms scheduler 线程只把 `drain()` 包成 `UiRunnable` 入队；
- `drain()`、`render()` 和 `runner.println()` 在同一渲染线程执行；
- 渲染线程正在执行重 drain 时，输入无法抢占。

### 4.2 两个周期

`tickRate(100ms)` 产生 `TickEvent`，Toolkit handler 请求重绘，随后完整执行 `CodeTuiView.render()`。底层 `InlineDisplay` 会做 Buffer 差分，因此未必每次都写完整 ANSI，但 Java 侧仍会读取快照、构建 Element 树和渲染 Buffer。

66ms `drain()` 每次固定检查或处理：

1. 上下文用量刷新触发；
2. resize 宽度兜底与 settle；
3. pending 定稿行；
4. streaming 完整行；
5. modal 队首；
6. attention 边沿；
7. 未送达插话、queued 消息和后台结果自动送达。

两者均不关心状态是否真正发生变化。

### 4.3 已有修复为何不能替代架构重构

现有实现已包括：

- tick 从约 40ms 降为 100ms；
- drain 从约 33ms 降为 66ms；
- 流式残行预览约 150ms 节流；
- 每 drain 约 300 个物理行的 burst cap；
- 上下文 token 估算移出 UI 线程；
- InlineDisplay 差分渲染与 print batch。

这些措施降低了 ANSI 频率和单次阻塞，但仍有结构性问题：

- 周期任务无变化时仍执行；
- 一个不可切分的大 `OutputLine` 或流式逻辑行仍可突破软上限；
- drain 执行期间输入无法抢占；
- 当前测试不能证明 Terminal.app 的 GCD/IME 崩溃已消失。

## 5. 方案比较

### 5.1 采用方案：状态真相源 + 合并唤醒

`ConversationState` 继续保存全部状态和队列。变化通知不携带业务数据，只表示“某类状态可能已变化”。UI 醒来后重新读取状态并执行既有处理顺序。

优点：

- 对现有功能侵入最小；
- 通知即使重复、合并或在 UI 启动前发生，数据仍留在状态中；
- modal、pending、streaming、queued 等存量不会因一次通知丢失而永久沉睡；
- 可渐进替换 `drain()`，保持 turnId/taskId 过滤和活性契约。

### 5.2 不采用：强类型业务事件总线

让 token、工具、modal 等事件本身承载唯一数据，会形成事件流与状态镜像两份真相，并扩大订阅前丢失、取消迟到、恰好一次响应和回放问题。功能回归风险过高。

### 5.3 不采用：Reactor Flux UI 流

TamboUI 是阻塞事件循环。再引入 Reactor scheduler 作为 UI 调度层会形成两套线程模型，增加取消和排查复杂度，不符合本次最小化机制重构的目标。

## 6. 总体架构

### 6.1 `ConversationState`：业务状态真相源

保留现有字段、队列、快照方法和同步边界，并增加轻量变化发布能力：

- 每次有效状态变更递增单调版本；
- 将变化归类为 dirty bits；
- 状态锁内只修改数据、版本和待发布位；
- 释放锁后调用通知器；
- 被 turnId/taskId 迟到过滤的写入既不改状态，也不发通知；
- 通知器异常必须被隔离，不能回传到 Agent/Reactor/工具线程。

通知不可直接同步调用 UI。通知器只进入 coordinator。

### 6.2 `UiUpdateCoordinator`：合并和调度

Coordinator 负责把任意并发生产者的变化合并为有限数量的 UI 任务。它不保存 token、modal 或业务快照。

核心状态：

- 原子 dirty bits；
- 原子 `scheduled` 标志；
- 已观察的状态版本；
- 生命周期状态：未启动、运行中、停止中、已停止；
- 各按需一次性任务的 generation/cancellation token。

生产者流程：

1. 原子 OR 新 dirty bits；
2. 若 `scheduled` 从 false 变 true，则向 TamboUI 事件队列投一个 UI update；
3. 已有 update 待处理时只合并位，不再堆事件。

消费者流程：

1. 在 UI 线程获取并清除本轮 dirty bits；
2. 执行一批 `processUpdates()`；
3. 更新已观察版本；
4. 清除 `scheduled`；
5. 重新检查 dirty bits 和版本；
6. 若期间出现新变化，再次取得调度权并入队下一批。

最后一步用于关闭“消费者清 scheduled 与生产者发通知交错”的丢唤醒窗口。

### 6.3 `InlineTuiRunner`：唯一 UI 执行器

在项目维护的 `springai-tamboui-inline-patch` 中增加明确的 UI 工作/重绘接口，语义至少包括：

- 从任意线程线程安全地唤醒事件循环；
- 在 UI 线程运行 action；
- action 完成后按请求执行一次 renderer；
- 多个 render 请求可合并；
- ticks disabled 时仍能由 Agent 变化触发绘制；
- 异常继续由现有 `Throwable` 防护记录并保持事件循环存活。

不使用 `InlineTuiRunner.draw(renderer)` 让业务层自己持有 renderer，也不依赖 `runOnRenderThread()` 后等待下一次 tick，因为后者当前执行 `UiRunnable` 后直接 continue，不会自动重绘。

## 7. 变化分类

使用可组合 dirty bits，而不是为每个 token 建事件对象：

### 7.1 `OUTPUT`

表示 scrollback 或流式输出可能有新存量：

- pending 信息、错误、用户行、工具行、子任务行；
- streaming token、完整行和最终 flush；
- MCP ready、规则记录、BYPASS、压缩结果等下沉行。

### 7.2 `VIEW`

表示 live 区快照可能变化：

- status、notice、active tool、compacting；
- todo、subtask、background task；
- queued、interjection；
- modal UI 本地状态；
- context usage；
- 输入框外部变更和动画帧。

### 7.3 `CONTROL`

表示需在 UI 线程重新评估控制流：

- turn started/completed/error/cancel；
- modal 入队、取消或队首变化；
- in-flight subagent 数量变化；
- interjection 送达或留下；
- background result 可自动送达；
- compaction 状态变化。

一次 mutation 可以同时发布多类，例如 `onToolStarted` 同时是 `OUTPUT | VIEW | CONTROL`。

## 8. UI 批处理顺序

一次 UI update 保持现有 `drainInsideBatch()` 的关键顺序：

1. 在物理行和时间预算内消费 pending 与 streaming 完整行；
2. 同步 modal 队首与当前 active modal；
3. 对畸形问询保持现有“移除请求并取消整个回合”的降级；
4. 推进 attention 边沿；
5. 重新计算 `busy()`；
6. 空闲时依次处理：
   - 未送达插话；
   - queued 用户消息；
   - 后台任务结果自动送达；
7. 根据最新快照执行一次差分 render；
8. 若处理期间出现新变化，合并到后续批次；
9. 若只是输出预算未清空，安排 continuation，不等待新 Agent 事件。

自动出队和 `dispatch()` 只允许在 UI 线程执行，并在执行点重新读取 `state.isBusy()`、modal、compacting 和 `hasInFlightSubagents()`。收到 `TURN_COMPLETE` 通知本身不等价于可以直接提交。

## 9. 输出调度与输入公平性

### 9.1 严格批次

现有 `rowsThisFrame` 是软上限，因为一条 `OutputLine` 在展开后无法中途停止。重构后先把逻辑输出渲染为可续消费的物理行块，再按批次提交，使单批满足：

- 严格物理行预算；
- 可选的最大 UI 线程执行时间预算；
- 批次结束后立即返回事件循环。

超大 Markdown、diff 或无换行长行不能独占 UI 线程。尚未提交的物理行留在 UI 输出缓冲，顺序不变。

### 9.2 continuation

输出存量未清空时，不在同一个 `UiRunnable` 中循环到空。Coordinator 安排下一次一次性 continuation：

- 每次只存在一个 continuation；
- continuation 再次进入事件队列；
- 批次之间允许键盘、粘贴和 resize 被处理；
- 无新 Agent 写入也必须最终排空输出；
- stop 时取消 continuation。

### 9.3 token 合并

`onAssistantToken` 只追加 streaming 并置 dirty bit。即使瞬间到达数千 token，事件队列中最多保留一个已调度 UI update 和一个必要的 continuation，不允许一 token 一 `UiRunnable`。

## 10. 按需时间任务

事件驱动不保留永久固定周期，但以下功能需要由事件启动的一次性任务：

### 10.1 流式残行预览

- 首个新残行立即或按现有视觉语义请求预览；
- 150ms 窗口内新 token 只更新状态；
- 窗口到期只安排一次 VIEW update；
- 残行清空时立即更新，不等待 throttle；
- 回合结束后不再续排。

### 10.2 resize settle

- `ResizeEvent` 立即更新宽度、光标停放状态并 render；
- 每个新 resize 替换前一个 settle generation；
- 静默窗口到期后，在 UI 线程执行现有 `ScreenCleaner.clear()` 和 `scrollTail` 重放；
- 完成后恢复硬件光标锚点并 render。

### 10.3 状态动画和耗时

状态栏动画、压缩计时和运行中后台任务耗时保持可见，但只在确有动态状态时续排下一帧：

- THINKING、RUNNING_TOOL、compacting 或运行中任务存在时启动；
- 每帧到期发 VIEW update，并在 render 后判断是否需要下一帧；
- 状态消失即停止；
- 空闲静态界面没有动画 timer。

### 10.4 ContextUsage

不再按 drain 次数触发：

- 会影响上下文统计的事件将 usage 标脏；
- 使用按需延迟/合并任务启动后台 `refresh()`；
- 同时最多一个 refresh 在飞；
- refresh 期间再次标脏只记录版本，完成后必要时再执行一次；
- refresh 完成发布 VIEW；
- `onStop()` 后不再接受新任务。

## 11. 外部变化源

并非所有 UI 相关状态都在 `ConversationState` 内，以下组件需提供同样的轻量变化通知：

- `Interjections`：offer、真实送达、取回、历史持久化导致 pending/delivered 变化；通知必须继续在其锁外调用；
- `SubagentRunner`/`CodingAgent`：in-flight subagent 数量增减；
- 后台任务注册表：完成结果可送达或消费状态变化；
- ContextUsage：后台刷新完成；
- MCP 手工 enable 等异步 UI 状态。

这些通知统一进入 coordinator，不允许恢复局部轮询。

## 12. 锁与活性纪律

### 12.1 锁外通知

严禁以下形状：

```java
synchronized void mutate() {
    // 修改状态
    uiListener.run();
}
```

正确流程是锁内 mutation + version/dirty 记录，锁外通知。原因包括：

- UI 醒来会回读 state；
- Interjections 与 ConversationState 已存在双锁访问顺序；
- scheduler 或 runner 不应阻塞 Agent 回调；
- listener 异常不能破坏状态写入。

### 12.2 modal

modal 继续先持久进入 `modals` 队列，再通知 UI。通知不是请求本体，因而合并不会丢请求。

保持以下不变量：

- 迟到或队满必须立即应答，不能静默丢弃；
- cancel、reset、shutdown 必须唤醒所有已入队请求；
- 正常处理先按对象身份 `removeModal(req)`，再调用 responder；
- 不按 record `equals` 移除；
- 新增 ModalRequest 子类型必须有 UI 分支和测试；
- 事件重复不得导致 responder 重复调用。

### 12.3 turnId/taskId

所有现有迟到过滤继续在状态写入前执行：

- 普通前台事件按 acceptingTurnId 过滤；
- 后台事件按 taskId/background 镜像识别，不错误套用 `turnId=-1`；
- BYPASS、规则写盘结果和后台任务等现有特殊例外保持不变；
- 被过滤事件不发 UI 通知。

## 13. 启动与停止

### 13.1 启动

MCP 和恢复历史可能在 View 运行前写入状态。启动顺序必须是：

1. 构造 state 和生产者；
2. 构造 coordinator；
3. 将 state、Interjections、in-flight 等变化源绑定到 coordinator；
4. 启动 TamboUI runner；
5. 在 UI 线程执行一次初始全量同步：欢迎横幅、已有 pending、已有面板状态和首次 render；
6. 之后完全由事件驱动。

即使绑定前已有状态，初始全量同步也能接住。

### 13.2 停止

1. coordinator 进入 stopping，不再接受外部调度；
2. 解除或关闭变化通知；
3. 取消 preview、resize、animation、context refresh 和 output continuation；
4. 保持现有 modal/background/MCP/terminal 清理；
5. 关闭 runner；
6. 后续迟到通知成为 no-op。

## 14. 功能保留清单

以下行为必须保持，现有测试不得删除或降低断言：

- 所有斜杠命令及其 busy 闸门和提示；
- 输入框编辑、历史回溯、斜杠补全、附件识别与兑现；
- 权限模式切换和模式标签；
- model/skill/MCP/permissions/tasks 选择器；
- Ask、Permission、Plan 三类 modal；
- scrollback 与 live 区分离；
- Markdown、代码高亮、工具 diff、用户块；
- 流式完整行下沉和残行预览；
- todo、前台子任务、后台任务、排队和插话面板；
- 插话只在真实送达后进入 scrollback；
- Esc 取消、插话回填、queued 清理和 cancel notice；
- turnId/taskId 迟到过滤；
- 子 agent 收尾期间阻止新回合；
- queued 自动出队优先级；
- 后台结果自动送达和刹车；
- attention 标题和 BEL 边沿；
- `/clear` 清理顺序、scrollTail 和历史重放；
- resize settle、清屏重放和硬件光标停放；
- InlineDisplay diff、IME 光标带修复和 print batch；
- context usage、缓存命中显示和状态栏动画；
- 退出时后台任务与 MCP 清理。

## 15. 测试设计

### 15.1 ConversationState 通知测试

- 每类有效 mutation 发布正确 dirty bits；
- 一次 mutation 可组合多个 bits；
- 迟到事件不改状态也不通知；
- 通知发生在 state 锁外，可在 listener 中回读 state 而不阻塞；
- listener 抛异常不影响 Agent 回调和后续通知；
- modal 队满/迟到仍恰好应答一次。

### 15.2 UiUpdateCoordinator 测试

- 数千并发 token 合并成有界 UI 任务；
- dirty bits 不丢、可组合；
- scheduled 清理窗口内新通知不会丢；
- update 执行期间的新变化会安排后续批次；
- 输出预算耗尽后无新事件也能 continuation 到空；
- 未启动、运行、停止中、已停止生命周期正确；
- stop 取消所有一次性任务。

### 15.3 InlineTuiRunner 测试

- ticks disabled 时，主动请求可唤醒阻塞 loop 并 render；
- 多个并发 render 请求合并；
- UI action 与 render 的顺序明确；
- action 异常被记录后 loop 继续；
- input、resize 和主动 UI update 均可正确绘制；
- 空闲无事件时不产生 frame 或 ANSI 输出。

### 15.4 CodeTuiView 接线测试

覆盖所有变化源到 UI：

- pending、streaming、turn start/complete/error；
- tool、todo、subtask、background；
- modal 入队/移除/取消；
- queued、interjection、in-flight；
- notice、context usage、attention；
- MCP 和异步 enable；
- resize、preview、animation。

建立一张接线参数化测试，防止以后新增 mutation 忘记通知。

### 15.5 严格限流与公平性测试

- 5000 行 streaming 严格受单批物理行预算；
- 单个超大正文严格受限；
- 单个超大 diff 严格受限；
- 单个超长无换行逻辑行不会独占 UI；
- 多批后内容完整、顺序不乱；
- 输出批次之间插入按键，输入在全部输出结束前得到处理；
- 高频 token 不导致 UI 事件队列线性增长。

### 15.6 完整回归

运行 `springai-code-tui -am` 全部 Maven 测试，并保持所有现有 `CodeTuiView*Test`、`DrainBurstCapTest`、InlineDisplay 测试和 Agent 测试通过。

## 16. PTY 与 Terminal.app 验收

### 16.1 自动 PTY

运行现有无网络脚本，包括 render diff、stream box、resize、permission、interjection、attachment、clear 等。新增：

1. 桩模型持续输出数千行；
2. 输出期间持续输入 ASCII、退格和左右移动；
3. 记录输入到画面出现的延迟；
4. 断言无丢键、乱序和长时间冻结；
5. 等待输出全部完成并校验首尾与数量；
6. 静止界面持续观测，断言无周期 ANSI 写入；
7. 可行时双 PTY 并行施压。

自动 PTY 只证明事件调度、ANSI 坐标和输入公平性，不证明 Cocoa IME 或 Terminal.app 内部稳定性。

### 16.2 真实 Terminal.app 人工验收

- 同时打开两个 Code TUI 窗口并持续大输出；
- 输出期间连续英文输入、退格、移动光标；
- 切换中文输入法，持续预编辑、候选切换、取消和上屏；
- 检查输入延迟、光标/边框错位、画面撕裂和 Terminal.app 崩溃；
- 若崩溃，收集 crash report 并检查 GCD kevent、`setMarkedText:`、`selectedRange`、NSTextInputContext/IMKInputSession 路径；
- 报告运行时长、窗口数、输出规模和结果。

## 17. 完成标准

同时满足以下条件才可声称代码层重构完成：

1. 常驻 100ms tick 与 66ms drain 已删除；
2. 空闲时没有周期 render、drain 或 ANSI 输出；
3. UI 与 Agent 变化全部通过合并通知接线；
4. 所有终端操作和 UI 控制流仍在唯一 UI 线程；
5. 高频 token 不形成事件风暴；
6. 输出严格分批，批间输入可被处理；
7. 所有现有功能回归测试通过；
8. 新增 coordinator、通知、限流和并发输入测试通过；
9. 现有及新增自动 PTY 测试通过；
10. 真实 Terminal.app 验收结果被单独、如实报告，未执行时不得表述为已验证终端崩溃根治。
