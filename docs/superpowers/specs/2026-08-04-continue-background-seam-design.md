# `/continue` 与后台子 agent 的接缝设计

**日期**：2026-08-04
**模块**：`springai-code-tui`

---

## 1. `/continue` 的本意

**把 todo 里没做完的接着做完。**

不管上一次是怎么停的——`Esc` 中断、模型 API 超时、额度用尽、网关坏窗口、进程退出后
`-c` 恢复——用户敲下 `/continue` 就是要它继续往下执行。

这条本意有一个直接推论，写在最前面免得后面的设计跑偏：

> **`/continue` 不判断「该不该重试」。** 用户选了继续，那就是继续。
> 本设计里任何「先看看失败原因再决定」的分支都是越权，一律不要。

现有提示词（`CodeTuiView.java` 的 `/continue` 分支）：

```java
String prompt = "继续执行上一批未完成的计划。请先回顾你的 todo 列表，从第一个尚未完成的任务开始委派子 agent 继续："
        + "相互独立、无共享状态的子任务用 ParallelTasks 并行委派，有依赖或需共享上下文的用 Task 串行委派"
        + "（与你先前采用的方式保持一致）；已完成的任务不要重做。若没有未完成的计划，直接说明即可。";
```

它写于后台子 agent 存在之前，因此对后台任务一无所知。

---

## 2. 事实核查：后台任务在各场景下还在不在

以下三条都读过代码确认，不是推断：

**① `Esc` 完全不碰后台任务。** `CodeTuiView` 的 `k.isCancel()` 分支只做三件事：
`current.dispose()`、`state.cancelCurrent()`、`state.clearQueued()`。没有任何
`killBackgroundTask` / `killAllBackgroundTasks` 调用。这是既有设计决策（后台任务跨回合存活）。

**② 后台任务纯内存，进程一死全没。** `BackgroundTaskRegistry` 只有一个
`LinkedHashMap` 字段，零 IO；`FileSessionRepository` 完全不认识后台任务。
`TaskOutput` 报错文案里那句「后台任务不跨进程保存」属实。

**③ API 失败 ⇒ FAILED，且结果留在注册表里。** `SubagentRunner.runBackgroundBody`
的 `catch (RuntimeException)` 与 `catch (Throwable)` 都调
`backgroundRegistry.complete(taskId, detail, false)`，`detail` 就是错误原文
（超时 / 额度 / 网关坏窗口）。任务变 FAILED，不消失。
另有一条：线程池拒绝（队列满 / 池已关）时走 `registry.kill(taskId)` ⇒ KILLED。

### 三种用法下的可达性

| 什么时候敲 `/continue` | 后台任务还在吗 | 有没有重复劳动风险 |
| --- | --- | --- |
| 退出后重启（不带 `-c`） | 全没了 | 新会话没有 todo，`/continue` 本就无事可做 |
| **`-c` 恢复上次会话** | **全没了**（随上个进程死亡） | **没有**——重新派发正是对的 |
| **当前会话 `Esc` / 报错之后** | **还在跑（或已完成待取回）** | **有** |

---

## 3. 缺陷清单

### D1：重复派发正在跑的后台任务

**仅在「当前会话」这一列成立。** `Esc` 掐掉前台回合，后台那批照跑；todo 上它们仍是
「进行中」；`/continue` 让模型「从第一个尚未完成的任务开始委派」——于是同一批活跑两份，
可能同时写同一个文件。提示词里那句「已完成的任务不要重做」拦不住：它们确实没完成。

### D2：重做已经完成、但结果没送出去的任务

**最阴的一格。** 后台任务成功跑完，结果因为回合被 `Esc` 掐掉 / 报错结束而没走到
「空闲自动送达」，于是停在注册表里 DONE-未消费；todo 上还写着「进行中」。
`/continue` 一来，模型把**已经做完的活重做一遍**——而 todo 根本不知道它完成了。

### D3：`-c` 恢复后模型以为旧任务还在跑

会话历史里留着 `已在后台启动：task_xxx`，而注册表是空的。模型读历史看到它们，可能：

- 调 `TaskOutput(task_xxx)` ⇒ 现有报错文案已说清「可能来自已结束的进程……请重新派发」，
  **这条路是通的**；
- **不调，直接认为它们还在跑，于是干等或跳过** ⇒ 那个 todo 永远不会被继续做，
  而用户敲 `/continue` 恰恰是要它继续。

### D4：模型看不见自己派出去的后台任务

`TaskOutput` 的 `task_id` 是必填，模型必须先知道 id；id 只存在于会话历史里那条
`Task` 的返回值中。`/compact` 之后 id 被压掉，任务还在跑，模型再也查不了。
`registry.all()` 这个能力**早就存在且是 public 的**，只是没有任何工具把它暴露出去。

这是个**能力不对称**：模型能派后台任务，却看不见自己派了什么。它与 D1–D3 无关，
是它自己的毛病。

### 刻意不当作缺陷的

**FAILED / KILLED 不需要任何特殊处理。** 它们就是普通的「没做完」，模型看 todo 自然会重派——
而重派正是 `/continue` 的定义。提示词里连提都不用提（见 §1 的推论）。

---

## 4. 目标与非目标

### 目标

- **G1**：`/continue` 不再重复派发正在跑的后台任务（D1）。
- **G2**：`/continue` 不再重做已完成、结果未送出的任务（D2）。
- **G3**：`-c` 恢复后模型不会干等已随进程死亡的旧任务（D3）。
- **G4**：模型能随时列出当前后台任务（D4）。
- **G5**：四条路径上模型看到的后台任务措辞**完全一致**——只有一个措辞出处。

### 非目标

- **N1**：不改 `/continue` 的本意。它继续是「接着做完」，不引入任何「该不该重试」的判断。
- **N2**：不给 FAILED / KILLED 加特殊处理（见 §3 末）。
- **N3**：不让后台任务跨进程持久化。那是另一个量级的设计（要处理孤儿进程、结果落盘、
  跨进程 id 冲突），且与本次要解决的四个缺陷都无关。
- **N4**：不改 `busy()` 闸门。它只看前台在飞子 agent 是**对的**——后台任务按设计跨回合存活，
  拿它挡新回合等于把整个后台功能关掉。
- **N5**：不做「每回合自动注入后台任务清单」。那要接在 `submit` 而非 advisor 上
  （工具循环每迭代都会重进 advisor，接错位置会每轮工具调用重注一遍），
  且没用后台功能的人也要为它付每回合的判断成本。`ListTasks` 让模型按需自取即可。

---

## 5. 架构：一个措辞出处，四个调用方

### 5.1 新单元 `BackgroundDigest`

`agent/background/BackgroundDigest.java`，**纯函数、无状态、不认识 UI 也不认识锁**——
只接收一份快照：

```java
/**
 * 把后台任务快照渲染成<b>给模型看</b>的文本。
 *
 * <p><b>唯一的措辞出处</b>：/continue 注入、ListTasks 工具、TaskOutput 查无此 id
 * 三条路都走它，于是模型无论从哪儿看到后台任务，读到的都是同一套说法。
 * 三处各写一遍的话，改一处忘两处，模型就会在同一件事上收到互相矛盾的描述。
 *
 * <p>接快照而不接注册表：注册表的方法都是 synchronized，调用方取一次
 * {@code all()} 就够；本类若持有注册表就会诱使实现里取两次，而两次之间状态会变。
 */
public final class BackgroundDigest {

    /** 给 /continue 用：只列需要「别重复劳动」的两格，都没有则返回空串。 */
    public static String forContinue(List<BackgroundTask> snapshot);

    /** 给 ListTasks 用：列全部四种状态。 */
    public static String full(List<BackgroundTask> snapshot);
}
```

**为什么只需要一份快照**：`BackgroundTask.consumed()` 是 public 的，
`forContinue` 自己从 `all()` 里筛 RUNNING 与 `DONE && !consumed()`，
不必再调一次 `completedUnconsumed()`。

### 5.2 `forContinue` 的三种产出

**(a) 有 RUNNING 或 DONE-未消费**（同一会话 `Esc` / 报错之后）：

```
当前进程仍有后台任务：
  task_a1  explore  扫描鉴权相关代码   运行中 → 正在做，不要重复委派
  task_a2  general  写单测            已完成待取回 → 先用 TaskOutput(task_a2) 取回结果，
                                       它对应的任务可能已经做完了
```

**(b) 注册表为空**（`-c` 恢复之后，或本来就没用过后台）——一句**自条件**的提醒：

```
若你在历史里看到过「已在后台启动：task_xxx」，注意后台任务不跨进程保存——
恢复会话后它们一律已经结束，需要重新派发，不要等它们。
```

**自条件**是刻意的：UI 无法便宜地知道「会话历史里提没提过后台任务」，
但**模型正在读那段历史**，它自己判断得了。代价是一句话，收益是不必把
「本进程是否恢复自旧会话」这个启动期事实一路穿到视图层。

**(c) 注册表非空、但只有 FAILED / KILLED**：返回**空串**。
它们是普通的「没做完」，模型看 todo 自然会重派（§3 末）。

### 5.3 四个调用方

```
/continue 提示词注入 ──┐
ListTasks 工具 ────────┼──→ BackgroundDigest
TaskOutput 查无此 id ──┘
```

| 调用方 | 用哪个方法 | 变化 |
| --- | --- | --- |
| `CodeTuiView` 的 `/continue` 分支 | `forContinue` | digest 非空则拼进提示词；空则提示词**逐字不变** |
| 新工具 `ListTasks` | `full` | 新增 |
| `BackgroundTaskTool.unknownTask` | `full` | 在现有解释后附上当前实际存在的清单 |

第三条是顺带的，但它命中的是最有价值的时刻：**模型拿着一个不存在的 id 来问**，
正是它最需要看清单的那一刻，而现在只回一句「未知任务」。

### 5.4 接线：UI 不直接碰注册表

`SubmitHandler` 加一个 default 方法：

```java
/** /continue 用的后台任务摘要；无需提醒时返回空串。 */
default String backgroundDigestForContinue() { return ""; }
```

`CodingAgent` 实现它：从注册表取一次 `all()` 快照，交给 `BackgroundDigest.forContinue`。

**为什么不让 UI 读自己的镜像**：`ConversationState` 里那份 `BackgroundView` 是**显示镜像**，
注册表才是「唯一并发真相源」（其类注释如此声明）。用镜像去构造喂给模型的指令，
等于让一个为渲染而生的副本承担正确性责任。

---

## 6. `ListTasks` 工具

新文件 `agent/background/BackgroundTaskListTool.java`，与 `BackgroundTaskTool`
（单数，管一个任务）分开——两者受众不同、参数不同，塞一个文件里只会让那个类名说谎。

```java
FunctionToolCallback.builder("ListTasks", (NoArgs a) -> digest())
```

**无参**。不加 status 过滤：四种状态一共不会超过注册表上限 64 条，
而真实使用中同时存在的远少于此（并发 4 + 队列 16）。加过滤是替一个不存在的问题写代码。

工具描述要说清它与 `TaskOutput` 的分工，否则模型会拿 `ListTasks` 去取结果：

```
List all background subagent tasks started with Task(run_in_background=true) in this
process, with their status. Use TaskOutput(task_id) to retrieve a finished task's result.
Background tasks do not survive a process restart.
```

最后一句是给 D3 兜底的：即使模型没走 `/continue`，只要它调过一次 `ListTasks`
看到空清单，也能明白历史里那些 id 已经没了。

---

## 7. 边界情形

| 情形 | 行为 | 理由 |
| --- | --- | --- |
| 从没用过后台功能，敲 `/continue` | 提示词加一句 §5.2(b) 的自条件提醒 | 那句话对「历史里没提过后台任务」的模型是空操作，代价一句话 |
| 只有 FAILED / KILLED | `forContinue` 返回空串，提示词逐字不变 | 它们是普通的「没做完」（§3 末） |
| 一个 RUNNING 都没有，只有 DONE-未消费 | 照常注入 DONE 那一组 | D2 与 D1 独立成立 |
| DONE-未消费的结果很长 | 清单里**只列 id 与描述，不含结果正文** | 结果由 `TaskOutput` 取；塞进提示词会把 `/continue` 撑爆，且绕过了 `markConsumed` 互斥 |
| 注册表里有 64 条（满） | 照常渲染全部 | 上限本就是为此设的；`full` 不再另设截断 |
| 单条 FAILED 的错误原文极长 | `full` 里按单条截断（沿用 `TaskResultStore` 的 4000 字符纪律，不另立规矩） | 一条堆栈不该淹掉其余 63 条 |
| `/continue` 时后台任务正好在状态切换 | 快照取一次，渲染的是那一瞬的状态 | 注册表方法都是 `synchronized`；一帧的滞后对「别重复劳动」这个用途无害 |
| `ListTasks` 时注册表为空 | 返回一句「当前没有后台任务」，不是空串 | 工具返回空串会让模型以为调用失败 |
| 子 agent 调 `ListTasks` | **调不到——它不进 `decoratedList`，仅主 agent 持有** | 照抄 `TaskOutput` 的既有做法（`AgentTools` 那里的注释：「子 agent 拿不到 `Task`，自然也没有属于自己的后台任务；给了它只会让它去捞别人的结果」）。同一条理由逐字适用于列举：子 agent 列出来的只会是主 agent 的任务 |

---

## 8. 错误处理

面很窄——没有 IO、没有网络、没有新增可变状态：

- `BackgroundDigest` 的入参为 `null` 或空列表：按「空注册表」处理，返回 §5.2(b) 那句
  （`forContinue`）或「当前没有后台任务」（`full`）。**不抛**——它跑在提示词构造路径上，
  抛异常等于让一次 `/continue` 崩掉整个 TUI。
- `BackgroundTask` 的 `description()` / `agentName()` 为空串：照常渲染，
  该列留空。渲染层不做业务校验。
- `permissionEngine` 式的降级：`CodingAgent.backgroundDigestForContinue()` 在注册表为
  `null`（测试桩路径）时返回空串，与 `SubmitHandler` 的 default 一致。

---

## 9. 测试策略

命令一律带模块作用域：`mvn test -pl springai-code-tui -Dtest='...'`。

### 9.1 `BackgroundDigest`（纯函数，主战场）

| 断言 | 要杀掉的变异 |
| --- | --- |
| RUNNING 一条 ⇒ `forContinue` 含 id、含「不要重复委派」 | D1 没修 |
| DONE 且未消费 ⇒ 含 id、含 `TaskOutput`、含「可能已经做完」 | D2 没修 |
| DONE 但**已消费** ⇒ **不**出现在 `forContinue` 里 | 漏判 `consumed()`，把已送达的又提一遍 |
| **只有 FAILED / KILLED ⇒ `forContinue` 返回空串** | 给 FAILED 加了特殊处理（违反 §1 推论与 N2）——**本组最要紧的一条** |
| 空快照 ⇒ `forContinue` 含「不跨进程保存」 | D3 没修 |
| 四种状态齐全 ⇒ `full` 四条都在 | `full` 漏了某一格 |
| `forContinue` 不含结果正文 | 把结果塞进提示词（撑爆 + 绕过 markConsumed） |

### 9.2 `/continue` 注入

| 断言 | 要杀掉的变异 |
| --- | --- |
| digest 为空串时，送出的提示词与改动前**逐字相同** | 总是拼一段（空清单也拼），给没用后台的人平白加噪声 |
| digest 非空时，送出的提示词**同时**含原文与 digest | 拼接把原文覆盖掉了 |
| `busy()` 为真时走 `enqueue` 且入队的是**拼好的**那份 | 排队路径用了未拼接的旧提示词 |

最后一条是接缝：`/continue` 有 `enqueue` 与 `dispatch` 两条出口，只测一条会漏。

### 9.3 工具层

- `ListTasks` 四种状态齐全时都列出；空注册表时返回「当前没有后台任务」而非空串。
- `TaskOutput` 未知 id ⇒ 返回值**同时**含原有解释与当前清单。

### 9.4 pty 冒烟（`background_smoke.py` 增一幕）

派一个后台任务 → `Esc` 中断当前回合 → `/continue` → 断言**发给桩模型的请求体**里
含那个 task id 与「不要重复委派」。

**断言落在请求体上而不是屏幕上**：这条要证明的是「模型收到了什么」，
而提示词是发出去的、不回显在屏幕上。`background_smoke.py` 的桩已经记录
`StubModel.sent`，直接查那里。

---

## 10. 验收标准

1. 同一会话里派一个后台任务、`Esc` 中断、`/continue` ⇒ 模型收到的提示词里点名了那个任务、
   并说明不要重复委派。
2. 后台任务已完成但未送达时 `/continue` ⇒ 提示词让模型先 `TaskOutput` 取回。
3. 注册表里只有 FAILED / KILLED 时 `/continue` ⇒ 提示词**逐字等于**改动前。
4. 从没用过后台功能时 `/continue` ⇒ 提示词只多那句自条件提醒。
5. `-c` 恢复会话后 `/continue` ⇒ 提示词说明旧任务不跨进程保存、需要重新派发。
6. 模型可调 `ListTasks` 列出全部后台任务及状态。
7. `TaskOutput` 传一个不存在的 id ⇒ 返回值里带上当前清单。
8. `mvn test -pl springai-code-tui` 全绿；`background_smoke.py` 通过。

**不在验收范围内**：后台任务跨进程持久化（N3）、`busy()` 闸门（N4）、
每回合自动注入（N5）、FAILED/KILLED 的特殊处理（N2）。
