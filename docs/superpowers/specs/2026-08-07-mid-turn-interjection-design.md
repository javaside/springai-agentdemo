# 回合中插话 · 设计

**日期**：2026-08-07
**模块**：`springai-code-tui`
**状态**：已实现（`2443411`..`1e12c07`，14 个提交，分支 `feat/mid-turn-interjection`）；
反馈设计于 `bcee22e`..`5118dfd` 返工，见下节。

> **验收压在一条断言上**：`AgentTools.build` 里 `InterjectingChatModel.wrap()` 那行**没有任何单测覆盖**。
> 实测把它摘掉后，1322 个单测**一个都不红**，只有 pty 冒烟的 `check_delivered_to_model` 会红——
> 它断的不是屏幕，是桩模型**实际收到的请求体**（角色序列 `[system, user, assistant, tool, user]`，
> 末尾那条 user 就是插话，且紧跟 tool 结果）。
>
> 更要命的是：接线断掉时**插话面板和状态栏「插话 N 条」照样正常显示**——它们读的是 UI 自己的队列，
> 队列只是躺着不动。所以肉眼看界面完全正常，功能却已经死了。
>
> **删掉 `check_delivered_to_model` 等于删掉整个功能的验收。**

## 返工：反馈设计（2026-08-07 当日）

初版的界面反馈是**错的**，用起来才发现。原设计：输入那一刻往 scrollback 打一行
`› 原话`（`Kind.INFO`），送达时**什么都不做**，靠状态栏一个计数从 1 变 0。四个问题：

| # | 问题 |
|---|---|
| A1 | 送达时界面无任何信号——`InterjectingChatModel` 注入成功只打 `log.debug` |
| A2 | 状态栏计数在子 agent 场景**被挤出屏幕**（`Task` 的长入参吃满 80 列，实测 80 列终端上「插话 N 条 · Esc 取消」整段消失） |
| A3 | 回显位置≠真实位置——输入时就打，而它的真实位置在后面那条工具结果之后 |
| A4 | 回显是 `Kind.INFO` 不是 `Kind.USER`，样式上不像用户消息 |

叠加之后：注入指引明写「否则先把手头的做完」，即**模型正确消化了插话却不显式回应是常态**。
于是「送达并被采纳」「送达但被无视」「接线断了压根没送出去」三者在界面上无法区分。

**根因是我把「不能写会过期的状态」多推了一步成「不能写状态」。**「待送达」会过期，
「已送达」不会——所以正确做法是**在送达那一刻补打一行**，而不是什么都不打。

**修法是照抄仓库里已有的排队消息那套**（`queuedChildren`）：没走的钉在输入框上方的活面板里
（活面板可以消失，scrollback 不行），走了才以 `Kind.USER` 打进信息流。初版两头都没做。

改动：

1. `Interjections.pendingSnapshot()`（非破坏性）+ `onDelivered/fireDelivered` 送达回调
2. `CodingAgent` 构造时把回调接到 `listener.onUserMessage` 上——复用它而不是新开一路，
   插话本就是一条用户消息，还白送 turnId 迟到过滤
3. `CodeTuiView.interjectionChildren` 面板（`⤷` 前缀 + 暖橙，排在排队面板之上＝先走的在上），
   撤掉输入时的 `pushInfo`
4. `InterjectionText`（新）——把包裹与**拆包裹**放在一起
5. 状态行工具摘要按终端宽度让位给尾部

顺带修掉一个独立缺陷：`-c` 回放**没剥 `[interjection]` 包裹**，把给模型的行为指引当成用户原话
显示了出来。`<skill_instruction>` 和图片引用块都装了同类护栏，唯独这条漏了——所以把 `wrap`
和 `unwrap` 挪进同一个类，它们错开时不会报错，只会在回放里默默显示错东西。

结果：**屏幕顺序 = 发给模型的消息表顺序 = `-c` 回放顺序**，三者第一次一致。
pty 实机复核，插话那行落在 `⎿ Glob ✓` 与模型回复之间。

⚠ 这次也暴露了测试盲区：状态栏测试用 `"{}"`（两字符入参）+ `ViewScreen` 硬编码 120 列，
**两个条件各绕开一半**，故 A2 一直是绿的。`ViewScreen` 已开放宽度参数。

## 问题

回合跑起来之后，用户输入的消息要等**整个回合**结束才被处理。

现状路径：`CodeTuiView.onEnter` 见 `busy()` 为真 → `state.enqueue(text, skill)` →
回合末在出队钩子里 `state.pollQueued()` → `dispatch`。
一个回合可能有十几轮工具调用，几分钟。

而用户在工具循环中途开口，十有八九是看它跑偏了想纠正。等事都做完再听，这句话就从
「纠偏」降级成了「善后」——它想阻止的事已经全做完了。

现有的两个出口都不好：等到底，或者按 Esc 砍掉整个回合。后者会丢掉已经跑对的部分。

## 决定

加第三条路：**不打断回合，把消息插进下一次模型调用**。

合法的插入位置只有一个——「所有工具结果之后」：

```
system
user       原始提问
assistant  tool_calls
tool       工具结果
user       插话        ← 只有这里合法
```

落在 `assistant(tool_calls)` 与 `tool` 之间就是悬空 tool_calls，下一次请求直接 400
（这个项目在「Esc 中断留下悬空 tool_calls」上已经吃过一次）。

而**会话存储层看不到这个位置**：工具结果落库与「构建下一次 prompt」是同一步，
从存储层追加必然错位。只有 ChatModel 层拿到的 `Prompt` 是已经配平的完整消息表。
所以注入挂在 ChatModel 装饰器（`InterjectingChatModel`），与
`VisionMaterializingChatModel` / `RetryingChatModel` 同层，是既有先例。

等待时间从「剩余整个回合」缩短到「当前这一个工具」。

**刻意不做**：不中断正在跑的工具、不给未开始的工具写合成的「已取消」结果。那是抢占，
能把延迟再压到「一次推理」，但要处理工具跑到一半的副作用不可回滚，且改变回合语义。
插话只是多一条 user 消息，回合语义不变。真想停的人有 Esc。

## 已有代码

工作树里有三个未提交文件，本设计以它们为基础：

| 文件 | 行数 | 状态 |
|---|---|---|
| `agent/Interjections.java` | 103 | 队列 + `delivered`/`anchorToolCallId` 记账，逻辑完整 |
| `agent/InterjectingChatModel.java` | 105 | ChatModel 层注入装饰器，已转发 `getOptions()` |
| `test/…/MidTurnInjectionProbeTest.java` | 294 | 三个探针，实测比较了三种注入点 |

全仓没有任何生产代码引用前两个类——`CodingAgent`、`SubmitHandler`、`CodeTuiView`
都没有。**功能当前不生效。**

## 判定：什么算插话

`Enter` 默认插话，`/queue <消息>` 显式排到下回合。

**不用 Enter 的修饰键变体做区分**。`CodeTuiView.java:838-841` 里 `Shift/Alt+Enter`
已经是输入框换行，且那行注释自己写着「Apple Terminal 等区分不了」——在区分不了修饰键的
终端上，`Alt+Enter` 到达时**就是裸 Enter**，用户以为排了队实际走了插话，静默错路由。
这类问题只有 pty 实机测得出，单测原理上抓不到。斜杠命令是纯文本判定，与终端无关。

**回落到老队列的三条路径**（都不进 `Interjections`）：

| 条件 | 为什么 |
|---|---|
| `state.isIdle()` 但 `busy()` 为真 | 压缩中 / 仅子 agent 在飞：**不会再有模型调用**，插话进去石沉大海 |
| 带技能挂载 | 插话是纯 `UserMessage`，带不了 `submit(text, skillName)` 的第二参数，走插话会静默丢技能 |
| `/queue` 显式指定 | 用户明确要求 |

第一条依赖把 `busy()` 拆开：

```java
busy()          = state.isBusy() || onSubmit.hasInFlightSubagents()
state.isBusy()  = !isIdle() || compacting || hasModal()
```

只有 `!state.isIdle()` 代表「回合在飞、还会有下一次模型调用」。判据用它，不用 `busy()`。

## 组件与改动

**`Interjections`（改 1 处）**
`drainForRefill()` 现在把 `delivered` 置 null 却不返回。按「Esc 要回填」的决定，它必须
连已送达的一起交还——否则模型看过、历史没有、用户也拿不回来，那句话凭空消失。
另加 `takePendingOnly()`（只取未送达，供 UI 兜底用，理由见「竞态」一节）。

**`InterjectingChatModel`（改 1 处）**
`new UserMessage(text.get())` 改成包裹后的文本：

```
[interjection]
用户在任务执行中插话，未完成的工作仍在进行中。
若与当前方向冲突就调整，否则先把手头的做完。
---
<用户原话>
[/interjection]
```

沿用 `FileReference` 的方括号成对标签约定（`[file reference]` / `[/file reference]`）。
指引与原话之间要有分隔，否则模型会把指引当成用户说的。

不包的话，模型在 tool 结果之后突然看到一条裸 user 消息，很可能判定「上一轮结束了，
这是新任务」，于是丢下没做完的工具循环去做新事，或者提前收尾。

**`CodingAgent`**
- 持有 `Interjections`，build ChatModel 时 `InterjectingChatModel.wrap()` 包上，**只包主 agent**
- `handleComplete(turnId)` 里补历史（时序约束见下节）
- 实现 `SubmitHandler` 的三个新方法

**`SubmitHandler`（加 4 个 default 方法）**

| 方法 | 委托到 | 用途 |
|---|---|---|
| `interject(String)` | `Interjections.offer` | 忙时提交插话 |
| `pendingInterjections()` | `pendingCount` | 状态栏计数 |
| `takePendingInterjections()` | `takePendingOnly` | 回合末兜底出队 |
| `takeBackInterjections()` | `drainForRefill` | Esc 回填输入框 |

后两个**必须是两个方法、不能合并**：兜底出队只能取未送达的，Esc 才能通吃已送达的。
合成一个的话就是竞态①里那个「同一句话发两遍」的 bug。都给空实现，桩不受影响。

选这条通道而不是新 Spring bean 或挂 `ConversationState`：`CodeTuiView` 只通过
`SubmitHandler` 跟 agent 说话（构造参数就 `state` / `onSubmit` / `root` 三个），
而这个接口本来就有近 20 个 default 方法在管后台任务、MCP、技能、权限。加三个同构方法
不引入新概念。挂 `ConversationState` 则方向是反的——注入在 ChatModel 层、补历史要碰会话
存储，都在 agent 层，让 agent 去读 UI 层状态对象是依赖倒置；且 `cancelCurrent()` 已经在
清 `queued`，混进去会和「Esc 要回填而不是丢弃」的新语义打架。

**`CodeTuiView`**
- `onEnter` 的 busy 分支按上表路由
- 新增 `/queue <消息>`
- Esc 分支 `takeBackInterjections()` 回填输入框
- 输入即回显原话 + 状态栏「插话 N 条」

回显行**不写送达状态**。内联 TUI 已经打印进 scrollback 的行改不了（一个 OutputLine =
一个物理行），写了「待送达」就永远是「待送达」，scrollback 会变成错的。送达与否全交给
状态栏的实时计数。

> **⚠ 本小节已被返工推翻**（见文首「返工：反馈设计」）。上面那句推理只对了一半：
> 「待送达」会过期没错，但由此推出「输入时打一行、送达时什么都不打」是多推了一步——
> **「已送达」不会过期**。现行做法是：输入时**不打**（改钉在输入框上方的活面板里），
> 送达时才以 `Kind.USER` 打进信息流。状态栏计数保留为第二条反馈。

## 数据流

```
UI 线程    输入 → !state.isIdle() → onSubmit.interject(text)
                 → Interjections.offer()  → 面板出现 ⤷ 原话 + 状态栏「插话 1 条」
                                            （不写 scrollback：位置还不对）

模型线程  工具执行完 → ChatClient 组装下一次 Prompt（tool 结果此刻才配平）
                 → InterjectingChatModel.inject()
                 → drainForInjection(lastToolCallId)
                    ├ 多条合并成一条 → 包裹 [interjection] → 追加为 UserMessage
                    ├ delivered = 包裹后文本
                    └ anchorToolCallId = 末尾 tool 消息 id
                 → fireDelivered(原话)（锁外）
                    → listener.onUserMessage(turnId, 原话)
                    → scrollback 打出 › 原话，位置正在 tool 结果之后
                                            面板清空、状态栏计数归零
                 → delegate.stream(prompt)

回合末    handleComplete(turnId) → takeForHistory()
                 → 读 events，定位 anchor 那条 tool 事件
                 → 在其后插入 UserMessage → replaceEvents 覆盖写回
```

**合并而不是逐条追加**：多条连续同角色消息在部分 provider 上会被折叠或报错，这个项目
为此已经有 `SessionEvents.collapseConsecutiveSameRole`。

**入历史的是包裹后的文本**，与模型实际看到的一致；屏幕上给用户看的是原话。刻意不同。

### 为什么要补历史

不补的话插话只活在那一次 `Prompt` 里。落盘后 `-c` 恢复、以及压缩之后，历史上就会出现
一次「无来由的转向」——模型照着一句不存在的话改了方向，后来的人（和后来的模型）都看不懂。

### anchor 定位失败的回落

anchor 为 null（插话时末尾还不是 tool 消息，比如模型还在第一次思考），或 anchor 在
events 里找不到（被 `sanitize` 裁掉了）→ 追加到历史末尾。

这时历史以 `UserMessage` 结尾，看着危险，但下个回合 `foldTrailingUserIntoOutbound`
（`CodingAgent.java:440`）正好接住这个形状、把它折进出站文本，不会变成两条连续 user
被 DeepSeek 400 拒收。**不需要为此新写保护**——既有兜底刚好覆盖。

### 兜底：回合结束时 pending 仍有货

真实会发生：用户在收尾流期间插话，最后一次模型调用已经发出去了，不会再有 `inject()`。
不处理的话这句话卡在队列里，直到用户下次发消息才被捎走，那时 anchor 语义早就不对了。

处理放在 UI 侧、复用**已有的**出队钩子（`CodeTuiView.java:362-371`，渲染线程）：

```java
if (!busy()) {
    List<String> leftover = onSubmit.takePendingInterjections();   // 只取未送达；先于 pollQueued
    if (!leftover.isEmpty()) { releaseBrake(); dispatch(String.join("\n", leftover), null); return; }
    ConversationState.Queued next = state.pollQueued();
    ...
}
```

不放 `handleComplete`：那里在 reactive 线程，从那起新回合要跟 UI 线程的出队逻辑抢。
而这个钩子本来就是「回合结束后起下一条」的唯一入口，单线程，且已有防重复提交的保证
（`submit()` 同步置 THINKING，本 tick 只出一条）。插话残余排在 `pollQueued()` 之前，
因为它在时序上更早。

## 边界与失败模式

### ① 竞态：`delivered` 有两个潜在消费者

回合正常结束时，`handleComplete` 要拿 `delivered` 补历史，UI 的兜底出队也想拿它重发。
谁先跑到谁拿走——若 UI 先拿走，那条已送达的插话既没进历史，又被当成新回合**再发一遍**，
模型会看到同一句话两次。

解法不是加锁排序，是让三个消费者各取互不重叠的部分：

| 消费者 | 触发 | 取什么 |
|---|---|---|
| `takeForHistory()` | 回合正常结束（`handleComplete`） | 只取 `delivered` |
| `takePendingOnly()` | UI 兜底出队 | 只取 `pending` |
| `drainForRefill()` | Esc 取消 | 两者都取 |

Esc 路径不会和补历史撞车：取消走 `doOnCancel`，`handleComplete` 根本不跑，
`delivered` 没人跟它抢。所以 `drainForRefill` 通吃是安全的。

### ② 顺序：补历史必须在通知 listener 之前

UI 的出队钩子靠 `!busy()` 放行，而 `state` 回 IDLE 是 `handleComplete → listener`
事件驱动的。补历史写在通知 listener **之前**，UI 就不可能在历史补完之前起新回合。
反过来的话，新回合的 `SessionMemoryAdvisor.before()` 会先写入新 user，`handleComplete`
随后再按 anchor 插入，位置错乱。

这条得在代码里写死并留注释——后人重排两行就会悄悄坏掉，且不会有任何报错。

### ③ 装饰顺序：`InterjectingChatModel` 放最外层

在 `RetryingChatModel` 内层的话，重试时队列已被第一次尝试排空，插话会在一次网络抖动后
**静默消失**。当前主 agent 不用 Retrying（只有子 agent 用），所以现在不相邻，但接线一变
就相关。`getOptions()` 转发已经做了，别动——忘了转发会让 `toolCallbacks` 被静默丢弃，
模型看不到任何工具，这个项目踩过。

### ④ 只包主 agent

子 agent 不参与插话：它们有独立 ChatModel 且用了 `RetryingChatModel`。

### ⑤ `/clear` 交错

补历史时必须快照 `sessionId`（`volatile`，`/clear` 在 UI 线程换掉它），纪律同
`trimDanglingToolCalls`。另外 `/clear` 要一并清空 `Interjections`，否则 `delivered` /
`anchor` 指向的是已经不存在的旧会话。

### ⑥ 已知不精确：同回合二次插话的位置

现有实现把两次插话合并、anchor 取**最新**那次。于是补历史时，第一条插话会被记在比它
实际送达位置更靠后的地方。都在同一回合内，偏差有限，**选择接受**而不是引入
`List<Delivered>`。

写在这里是因为这是「明知有偏差而不修」，不是漏掉的 bug——以后有人发现了不用重查一遍。

### ⑧ 阻塞型工具会让「下一次模型调用」永远不来（上线后才发现）

**本设计通篇的隐含前提是「模型调用来得很快」**——插话的送达点是下一次模型调用，而一次
工具调用通常只有几百毫秒到几秒。这个前提有一个反例，写这份设计时没想到：
`TaskOutput(block=true)` 是**故意**等下去的，默认上限 300 秒，且这一等发生在主 agent 的
工具线程上。

它的症状极具迷惑性：回合在跑、`⤷` 面板照常显示、状态栏计数也对——**反馈这一层全是好的**，
只有那句话迟迟不被听见。用户报的是「后台任务派出去了，为什么插话还是要等」，而后台派发
本身完全无辜（`runInBackground` 立刻返回）。真正的墙在**回收**那一侧。

修法是让那个工具的轮询顺路查一次插话队列（每 200ms 醒一次，几乎不要钱），有就提前返回
「仍在运行」。**这条纪律要跟着任何新的「会长时间阻塞的工具」走**：判据不是「这个工具是不是
后台相关」，而是「它会不会让主 agent 在一次工具调用里停留到人能察觉」。

装配层为此单独钉了一条测试——工具自己的单测只能证明「给它一个返回 true 的 supplier 它会
让路」，生产那头填成恒 `false` 的话一条都不会红。

### ⑦ 补历史失败不能带崩回合

`replaceEvents` 抛异常时静默跳过（同 `trimDanglingToolCalls` 里 `service == null` 的
纪律）。回合已经成功完成，不该因为一句插话没归档就变成错误。

## 动手第一步：消除唯一的未知数

`handleComplete`（`doOnComplete`）与 `SessionMemoryAdvisor` 写 assistant 回复，谁先？

补历史挂在 `handleComplete` 的前提是 advisor 已经落库。如果反了，插进去的位置会被随后的
写入覆盖，「数据流」一节的挂载点就得换（`doFinally`，或改成 advisor 之后的钩子）。

**静态读代码定不了，先写探针实测。在它出结果之前，后面的测试都不用写。**

### 实测结论（2026-08-07）

一次性探针 `PersistOrderProbeTest`：装一个真实 `CodingAgent`（假 `ChatModel` 先回一个
tool_call、再回纯文本收尾；真实 `SessionMemoryAdvisor` + `DefaultSessionService` +
`InMemorySessionRepository`），在 `AgentListener.onTurnComplete(turnId)` 回调里**立刻**
读 `sessionService.getMessages(sid)`。

`onTurnComplete` 那一刻会话存储里的消息序：

```
user | 原始提问
assistant tool_calls=1 |
tool |
assistant | 收工
```

回合彻底结束再等 500ms 复读，序列一字不变（4 条 → 4 条），说明 `onComplete` 之后
advisor 没有补写。

**结论：advisor 先。** `doOnComplete` 触发时，本回合的 assistant 收尾消息**已经**在
会话里了。

**挂载点：`handleComplete` 开头**（补历史 → 再 `listener.onTurnComplete`）。不需要改挂
`doFinally`。这也正好让「顺序②」那条测试成立：listener 桩在 `onComplete` 里读到的历史
必须已含插话。

注意探针的边界：它只覆盖 `registry == null` 的单-client 路径，且流是 `Flux.just(...)`
单元素同步流。多 provider 路径与真实网络流的 advisor 时序未直接观测——但 advisor 的
写入发生在 `doOnComplete` 的**上游**，这个拓扑关系与流的来源无关。

**独立复核（同日，另一条路径）**：上面那个探针走的是 `.call()`，而生产代码
`CodingAgent.submit` 走的是 `.stream().chatClientResponse()`，advisor 的 `adviseStream`
与 `adviseCall` 是两段不同的代码。故用流式路径另写了一个一次性复核，同样在
`doOnComplete` 回调里读会话：

```
USER|原始提问
ASSISTANT|            ← tool_calls
TOOL|
ASSISTANT|收工        ← doOnComplete 触发时已经在了
```

两条路径结论一致，挂载点定死在 `handleComplete`。复核测试已删（同为一次性工具）。

## 测试

### anchor 定位：让成功路径和兜底路径结构上可区分

成功路径是「插在 anchor 那条 tool 之后」，兜底是「追加到末尾」。若测试构造的会话里
anchor tool 消息**就在末尾**，两条路径产出完全相同的历史——故意破坏 anchor 查找的变异
根本杀不掉，测试全绿。

会话必须构造成 `… → tool(anchor) → assistant(收尾)`，让「插在中间」和「追加末尾」
是两个不同结果。

### 回落判定：反向断言要为正确的理由失败

测「压缩中不走插话」时，输入必须满足除目标外的全部前置条件：`state` 为 IDLE **且**
`compacting == true`。图省事构造纯 IDLE 的 state 的话，`busy()` 也是 false，走的是
`dispatch` 而不是 `enqueue`，测试会因为一个完全无关的原因变绿。

变异判据 `!state.isIdle()` → `busy()`，必须挂。

### 竞态①：单测测不了并发，但能测契约

断言 `takePendingOnly()` 不动 `delivered`、`takeForHistory()` 不动 `pending`——这正是
解法的实质。变异「让 `takePendingOnly` 顺手清掉 delivered」必须挂。

### 顺序②：可测，别当成只能靠注释

用 listener 桩，在 `onComplete` 回调里读会话 events，断言插话此刻**已经**在历史里。
把补历史和通知 listener 两行调换 → 桩读到的历史里没有插话 → 挂。

### 注入位置：断言索引，不是「包含」

只断言消息列表里含插话文本的话，`merged.add(0, ...)` 这种变异照样过。

### 技能挂载回落

断言「`submit(text, skill)` 被调用且 skill 非 null」，不是「没调 interject」。
变异「删掉 skill != null 判断」必须挂。

### pty 实机

状态栏计数、插话面板、送达进信息流、`/queue` 补全。设 `TIOCSWINSZ` + `TERM=xterm-256color`，
否则渲染全空白；`wait_for` 断言「当前状态」不能只靠子串匹配，会命中陈旧 scrollback；
改完记得重新 package。

> **窄终端也要测**：状态行的内容按 `terminalWidth()` 拼，宽终端下什么都放得下，
> 「重要的那段被不重要的挤出屏幕」永远测不出来。`ViewScreen.of(view, 80)` 是为此开的。

### 现有探针测试的去留

`MidTurnInjectionProbeTest` 294 行里三个探针是「验证假设」用的一次性工具，不是回归测试。
结论已经写进本文档，代码里**只保留真正断言契约的部分并改名**。

留着的话，以后改动逻辑会被一堆探测性断言挡住，却分不清哪些是真契约。

### 验证命令

```
mvn test -pl springai-code-tui -Dtest=…
```

模块作用域。整仓跑会被空模块打挂。
