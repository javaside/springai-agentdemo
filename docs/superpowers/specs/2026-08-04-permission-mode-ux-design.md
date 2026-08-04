# 权限模式交互改造设计（BYPASS 运行期解锁 + 状态栏 notice 降级）

**日期**：2026-08-04
**模块**：`springai-code-tui`
**分支**：`feat/permission-mode-ux`

---

## 1. 背景

用户在实际使用中提出两点：

1. **BYPASS 为什么必须启动时指定，而不能进去之后自由切换？**
   今天 `PermissionEngine` 有一个 `final boolean bypassAllowed`，构造时由
   `--dangerously-skip-permissions` 定死；`setMode(BYPASS)` 与 `cycleMode()` 在它为 false
   时一律拒绝。于是要用 BYPASS 只能退出进程、改命令行、重启。

2. **回合正在跑的时候切权限模式，状态栏看不出还在跑了，很懵。**
   `CodeTuiView.statusLine()` 里
   ```java
   String notice = state.notice();
   if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
   ```
   这行**排在** `state.status()` 的 switch 之前。Shift+Tab 会 `setNotice("权限模式：X")`，
   于是 notice 整条盖掉「● 思考中…」/「⏺ 运行 X…」的波光行，而且是 sticky 的——
   不按下一个键就不还回来。

## 2. 一个必须先纠正的事实：BYPASS 下内置底线是被跳过的

`PermissionMode` 的 javadoc 现在写着：

> 全放行——但 deny 规则与内置危险检查**仍然生效**（它们排在 mode 默认之前）。

**这句话是错的。** `PermissionEngine.doDecide` 的实际顺序是：

```java
// 1. deny 规则——最高，BYPASS 下也生效
if (deny != null) return PermissionDecision.deny(...);

// ★ BYPASS：到此为止。
if (mode == PermissionMode.BYPASS) {
    return PermissionDecision.allowBypassingGuardrail(
            "BYPASS 模式已跳过权限检查（deny 规则仍然生效）",
            builtinDanger(entry, path, target));   // ← 只是把理由串捎出去，不拦
}

// 2. 内置危险检查——BYPASS 根本走不到这里
```

内置底线（改 `~/.ssh`、`rm -rf`、凭证文件等）在 BYPASS 下**被跳过**，只由
`PermissionCallback.onGuardrailBypassed` 在 scrollback 留一行 `⚠ BYPASS 放行：…` 事后记录。

这条陈旧 javadoc 直接误导了本次设计的第一版确认框文案。确认框是整个改动里**唯一**
一处向用户讲清后果的地方，文案错了门槛就白设了。因此：

- **本次一并修正 `PermissionMode` 的 javadoc**，使其与 `doDecide` 一致；
- 确认框文案按真实行为写（见 §4.2）。

---

## 3. 目标与非目标

### 目标

- **G1**：运行期可经 Shift+Tab 进入 BYPASS，无需重启进程。
- **G2**：进入 BYPASS 前有一次讲清后果的确认，手抖按不进去。
- **G3**：任何 notice（不只是切模式）都不再遮住状态栏的运行指示。
- **G4**：修正 `PermissionMode` 关于内置底线的错误 javadoc。

### 非目标

- **N1**：**不**放开配置文件声明 `defaultMode: "BYPASS"`。
- **N2**：**不**放开 `--permission-mode bypass`。
- **N3**：解锁状态**不落盘**，重启回到未解锁。
- **N4**：不改 `doDecide` 的任何判定语义——BYPASS 该放行什么、该拦什么，一个字不动。

N1/N2 与 G1 不矛盾。今天 `bypassAllowed` 一个字段同时把守三条路，而它们的威胁模型不同：

| 路径 | 威胁 | 本次 |
| --- | --- | --- |
| `permissions.json` 里 `defaultMode:"BYPASS"` | clone 别人的仓库就把你放成裸奔，用户全程无感 | 继续禁 |
| `--permission-mode bypass` | 给那道显眼开关配一个不显眼的同义词 | 继续禁 |
| 运行期用户当面按 Shift+Tab | 用户在场、有意图、看得见后果 | **解开** |

本次改动就是把这三条被合并成一条的禁令拆开，只解开第三条。

---

## 4. 议题一：BYPASS 运行期解锁

### 4.1 引擎侧：`bypassAllowed` → `bypassUnlocked`

`PermissionEngine`：

```java
// 旧：private final boolean bypassAllowed;
private volatile boolean bypassUnlocked;
```

**必须 volatile**：UI 线程写（确认框按 y）、工具线程读（`doDecide` 经 `cycleMode`/`setMode`
的结果间接读，以及 `cycleMode` 自身）。旁边的 `mode` 字段已经是 `volatile`，同一纪律。

新增：

```java
/**
 * 解锁 BYPASS 档（UI 的确认模态按 y）。
 *
 * <p><b>只有这一个运行期入口</b>。配置文件的 defaultMode 与 --permission-mode
 * 仍然一律拒绝 BYPASS——那两条路上用户可能全程无感（见 PermissionConfigLoader
 * 类注释「为什么配置文件不能声明 BYPASS」），而这里用户当面看过后果说明才走得到。
 *
 * <p>不落盘、不可撤销（本进程内）：重启即回到未解锁。
 */
public void unlockBypass() {
    this.bypassUnlocked = true;
}
```

保留读方法，改名以对齐语义：

```java
public boolean bypassUnlocked() { return bypassUnlocked; }
```

（`bypassAllowed()` 这个 getter 今天**一个调用方都没有**——全仓 `grep '\.bypassAllowed()'` 为空。
改名不会波及任何现有代码；它反而是在本次才第一次真正被用上，由 `CodingAgent.bypassUnlocked()` 转发给 UI。）

`setMode` 与 `cycleMode` 的**逻辑一个字不改**，只是字段名换掉：

```java
public PermissionMode setMode(PermissionMode m) {
    if (m == null) return mode;
    if (m == PermissionMode.BYPASS && !bypassUnlocked) {
        log.warn("拒绝切到 BYPASS：本会话尚未解锁（需经确认模态或 --dangerously-skip-permissions）。");
        return mode;
    }
    this.mode = m;
    return m;
}

public PermissionMode cycleMode() {
    PermissionMode next = mode.next(bypassUnlocked);
    this.mode = next;
    return next;
}
```

构造器参数 `bypassAllowed` 更名为 `bypassUnlocked`，语义不变：
`--dangerously-skip-permissions` 启动 = 出生即解锁，永不弹框。
构造器里「启动模式 BYPASS 未获授权则降级 DEFAULT」的逻辑保持。

`PermissionMode.next(boolean)` 的参数与 javadoc 同步更名（`bypassAllowed` → `bypassUnlocked`），
`next` 的分支逻辑不变。

### 4.2 UI 侧：确认模态

`CodeTuiView` 新增一个字段与一组方法，与既有的 `pickingPerms` / `pickingTasks` 同构：

```java
private boolean confirmingBypass;   // BYPASS 解锁确认模态是否在前台
```

**面板内容**（`bypassConfirmChildren()`，首行判空——`scope` 每帧 eager 求值）：

```
┌─ ⚠ 切到「跳过权限检查」 ──────────────────────┐
│                                              │
│  此后除你自己写的 deny 规则外，全部工具调用  │
│  不再询问——写文件、执行命令、删除都直接跑。 │
│                                              │
│  内置危险检查（~/.ssh、rm -rf、凭证文件…）   │
│  也会被跳过，只在事后留一行记录。            │
│                                              │
│  当前目录：<项目根绝对路径>                  │
│                                              │
│  y 确认进入 · 其它任意键取消（留在<当前档>） │
└──────────────────────────────────────────────┘
```

「当前目录」不是装饰：BYPASS 的杀伤范围由工作目录界定，这是用户唯一能当场核对
「我是不是在错的仓库里裸奔」的信息。取自 `PermissionEngine.root()`，经 `SubmitHandler` 暴露。

**注册进 render()**，位置在 `activePlan` 面板之后、斜杠菜单之前：

```java
scope(confirmingBypass, bypassConfirmChildren()),
```

### 4.3 按键路由

`onInputKey` 里，确认模态的分派**必须排在 Shift+Tab 分支之前**——否则确认框开着时
按 Shift+Tab 会漏到下面的循环分支去，一边问着「要不要进」一边把模式切走了。

```java
if (!state.notice().isEmpty()) state.setNotice("");     // 既有：任意键清 notice
if (confirmingBypass) return onBypassConfirmKey(k);     // 新增：早于 Shift+Tab
if (k.code() == KeyCode.TAB && k.hasShift()) { ... }    // 既有：模式循环
```

Shift+Tab 分支改为：

```java
if (k.code() == KeyCode.TAB && k.hasShift()) {
    // 问枚举「下一档是不是 BYPASS」，不在 UI 里写死循环顺序——
    // 顺序改了这里不用跟着改，改漏了才是静默错档。
    boolean wantsBypass = onSubmit.permissionMode().next(true) == PermissionMode.BYPASS;
    if (wantsBypass && !onSubmit.bypassUnlocked()) {
        if (modalActive()) {
            // 模态在前台：不开确认框，模式不动，只解释。
            state.setNotice("先处理完当前审批，再切「跳过权限检查」");
        } else {
            confirmingBypass = true;    // 模式不动，等确认
        }
        return EventResult.HANDLED;
    }
    state.setNotice("已切到 " + onSubmit.cyclePermissionMode().label());
    return EventResult.HANDLED;
}
```

其中 `modalActive()` 为 `activePermission != null || activePlan != null || activeAsk != null`
（与 `permsPanelChildren` / `tasksPanelChildren` 里既有的同一组判空口径一致，抽成方法复用）。

**为什么模态在前台时拒绝而不是排队**：那些模态背后 park 着工具线程，`y` 在权限审批面板里
本身就是有效键；更要命的是语义——在「正等你批准这次调用」的时刻问「要不要从此不再问」，
用户没法确定当前这次算不算被一并放行了。

### 4.4 确认模态的按键

```java
private EventResult onBypassConfirmKey(KeyEvent k) {
    confirmingBypass = false;
    if (k.isChar('y') || k.isChar('Y')) {
        onSubmit.unlockBypass();
        state.setNotice("已切到 " + onSubmit.cyclePermissionMode().label());
    } else {
        // 取消 = 留在原档，不动模式。
        state.setNotice("已取消，仍在 " + onSubmit.permissionMode().label());
    }
    return EventResult.HANDLED;
}
```

**取消为什么留在原档而不是走到 DEFAULT**：未解锁时今天的循环是 `PLAN → DEFAULT`，
沿用它意味着「用户看完危险后果说算了，系统回他一个**更宽松**的档」。取消一个危险动作
不该让权限放宽。代价是未解锁时想从 PLAN 出去得先看一次框再取消——多一次按键，
换掉一次静默放宽。

### 4.5 `SubmitHandler` 接口增量

```java
/** BYPASS 档本会话是否已解锁（未解锁时 Shift+Tab 循环不含它，UI 据此决定弹不弹确认）。 */
default boolean bypassUnlocked() { return false; }

/** 解锁 BYPASS 档（确认模态按 y）。默认空实现，便于回显桩/测试桩省略。 */
default void unlockBypass() { }

/** 权限判定的工作目录（确认模态展示，让用户当场核对裸奔范围）。 */
default String permissionRoot() { return ""; }
```

`CodingAgent` 三个方法均委托 `permissionEngine`，`permissionEngine == null` 时分别退回
`false` / 空操作 / `""`——与既有 `permissionMode()` 的降级纪律一致（桩缺引擎不该让 UI 崩，
且退回值都是最严的那一侧）。

---

## 5. 议题二：状态栏 notice 降级为后缀

### 5.1 问题不是 Shift+Tab 特有的

`statusLine()` 里 notice 分支排在 `state.status()` 开关之前，意味着**任何**忙时 notice
都会吃掉转轮。今天只有 Shift+Tab 会在忙时设 notice，所以只表现为这一个症状；
但结构在那儿，以后再加任何忙时提示都会重蹈覆辙。因此按结构修，不按症状修。

### 5.2 改法

```java
String notice = state.notice();
// 独占只留给空闲态：那时本来也没有动态信息要保。
// draining 那行也是动态信息（提示子 agent 在收尾），一并排除。
String draining = drainingSubagentsHint(state.isIdle(), onSubmit.hasInFlightSubagents());
if (!notice.isEmpty() && state.isIdle() && draining == null) {
    return text(notice + " · Ctrl+C 退出").style(THINK);
}
// 忙时进后缀：排在「已排队 N 条」之后、按键提示之前。
String ns = notice.isEmpty() ? "" : " · " + notice;
```

三条常态行的 suffix 相应变成 `qs + ns + …`：

| 状态 | 改后 suffix |
| --- | --- |
| draining | `qs + ns + " · Ctrl+C 退出"` |
| THINKING | `qs + ns + " · Esc 取消 · Ctrl+C 退出"` |
| RUNNING_TOOL | `qs + ns + " · Esc 取消"` |

IDLE 分支不动（notice 非空且 idle 时已在上面独占返回；idle 且 notice 为空时 `ns` 为空串）。

**`state.isCompacting()` 分支保持在 notice 之前**：压缩条本身就是动画状态指示，
既有注释已说明「已在压缩：动画条已表明状态，不再叠加 notice」。

**面板/模态分支保持在最前**：`permStatusText()` 等分支自己回显 notice（既有设计，
见那几处「必须自己回显 notice」的注释），不受本次改动影响。

### 5.3 notice 文案

`权限模式：X` → `已切到 X`。

后缀位置读起来才通顺；且「已切到」点明这是**刚发生的事件**，而常驻状态由行首
`modeTag` 负责——两者分工明确后就不再是同一件事说两遍。

### 5.4 改后的屏幕

```
切换前（运行中）：
  ⏸ 计划模式 · ⏺ 运行 Bash: npm test…                        · Esc 取消

按 Shift+Tab 后：
  ⏵⏵ 自动接受编辑 · ⏺ 运行 Bash: npm test…   · 已切到 自动接受编辑 · Esc 取消
  └──────┬───────┘   └────────┬──────────┘     └────────┬─────────┘
      标识变了              转轮还在跑              切换反馈进后缀
```

---

## 6. 改动清单

| 文件 | 改动 |
| --- | --- |
| `agent/permission/PermissionMode.java` | 修正 BYPASS 的错误 javadoc（§2）；`next` 参数更名 `bypassAllowed` → `bypassUnlocked` |
| `agent/permission/PermissionEngine.java` | `final boolean bypassAllowed` → `volatile boolean bypassUnlocked`；新增 `unlockBypass()`；`bypassAllowed()` → `bypassUnlocked()`；`setMode`/`cycleMode` 逻辑不变 |
| `agent/SubmitHandler.java` | 新增 `bypassUnlocked()` / `unlockBypass()` / `permissionRoot()` 三个 default 方法 |
| `agent/CodingAgent.java` | 三个新方法委托引擎，`null` 引擎降级 |
| `ui/CodeTuiView.java` | `confirmingBypass` 字段 + `bypassConfirmChildren()` + `onBypassConfirmKey()` + `modalActive()`；Shift+Tab 分支改写；`drain` 里模态上前台时清确认框；`statusLine()` notice 降级 |
| `CodeTuiApplication.java` | 只跟 `PermissionEngine` 构造参数更名，行为不变 |
| `springai-code-tui/README.md` | 权限章节：BYPASS 的进入方式与内置底线的真实行为 |

无新增生产文件。改动集中在既有单元内部，接口只增不改。

**现有测试的连带更名**（都是局部名，不是行为改动）：

- `PermissionStartupTest` 的辅助方法参数 `bypassAllowed`
- `CodeTuiViewModeIndicatorTest` 里模拟启动开关的桩字段 `bypassAllowed`
- `PermissionTestSupport` 里解释「为什么传 true」的那段注释

这些改名不该让任何断言变绿或变红——若某条测试因更名而行为改变，说明它依赖的是名字之外的东西，
停下来查清楚再动。

---

## 7. 边界情形

| 情形 | 行为 | 理由 |
| --- | --- | --- |
| 已解锁后再按 Shift+Tab | 四档正常循环，不再弹框 | 解锁是一次性的 |
| `--dangerously-skip-permissions` 启动 | 出生即解锁，从 PLAN 一按直接进 BYPASS，全程无框 | 命令行本身已是显式意图 |
| 确认框开着时到达一个工具审批请求 | **清掉确认框**（`confirmingBypass = false`），模式不动，审批面板接管 | 审批背后 park 着工具线程，优先级更高；两个面板并存会让 `y` 键归属不明。在 `drain` 里模态上前台的那一处清 |
| 有审批/计划/作答模态在前台时按 Shift+Tab 想进 BYPASS | 不开框，模式不动，notice 解释 | §4.3 |
| 有模态在前台时按 Shift+Tab 切**其它**档 | 照常切（既有行为，不变） | 模式只影响后续判定，不动 pending 请求 |
| 确认框开着时按 Ctrl+C | 照常退出（`isCtrlC()` 在 `onInputKey` 最顶） | 退出永远最优先 |
| 解锁后执行 `/clear` | **仍然解锁**，模式也不动 | 解锁是进程级；`/clear` 换的是会话不是权限态 |
| 确认框开着时想敲 `/clear` | 敲不出来——第一个字符被确认框吃掉并当作「取消」 | 模态的定义就是接管全部按键；取消后再敲即可 |
| 运行期切到 BYPASS 时有后台子 agent 在飞 | 立刻对它们生效（后续工具调用不再被 ASK→DENY 挡） | 模式是进程全局的；这是预期而非缺陷 |
| notice 为空 | `ns` 为空串，不产生悬空的 ` · ` | 见 §5.2 |
| `unlockBypass()` 重复调用 | 幂等 | 单向布尔 |

---

## 8. 错误处理

这个改动没有 IO、没有网络、没有并发任务，错误面很窄：

- **`permissionEngine == null`（测试桩路径）**：`bypassUnlocked()` 退 `false`、
  `unlockBypass()` 空操作、`permissionRoot()` 退空串。三个退回值都倒向「更严」那一侧，
  且都不让 UI 渲染崩——与既有 `permissionMode()` 退 `DEFAULT` 同纪律。
- **`permissionRoot()` 为空串时的确认框**：省掉「当前目录」那一行，其余照渲染。
  一个测试桩缺目录不该让确认框整个消失（那会让最危险的一档反而没了门槛）。
- **未解锁时被绕过的尝试**：`setMode(BYPASS)` 与 `cycleMode()` 各自记 WARN 后返回原档，
  不抛异常——它们是 UI 反复调用的路径，抛异常等于让一次误按崩掉整个 TUI。

---

## 9. 测试策略

命令一律带模块作用域：`mvn test -pl springai-code-tui -Dtest='...'`。
（整仓 `-Dtest` 会被三个空模块打挂。）

### 9.1 引擎层（`PermissionStartupTest` 或新建同级测试）

| 断言 | 要杀掉的变异 |
| --- | --- |
| 未解锁时 `setMode(BYPASS)` 返回原档、`mode()` 未变 | 把解锁默认改成 `true` |
| 未解锁时 `cycleMode()` 从 PLAN 走到 DEFAULT（不含 BYPASS） | `next(true)` 写死 |
| `unlockBypass()` 后 `setMode(BYPASS)` 生效 | 解锁不落到字段 |
| `unlockBypass()` 后 `cycleMode()` 从 PLAN 走到 BYPASS | `cycleMode` 仍读死值 |
| 构造时传 `bypassUnlocked=true` 即可直接进 | 构造参数没接上 |

### 9.2 禁令回归钉（防止拆禁令时拆过头）

| 断言 | 位置 |
| --- | --- |
| `PermissionConfigLoader` 仍丢弃 `defaultMode:"BYPASS"`（两层皆然） | 既有测试若已覆盖则确认其仍绿，未覆盖则补 |
| `CodeTuiApplication.startupMode("--permission-mode bypass")` 仍返回 `null` | 既有测试同上 |

这两条不是新功能的测试，是**护栏**：它们钉住「解开的只有运行期这一条路」。

### 9.3 UI 层（`CodeTuiViewModeIndicatorTest` 及新建测试）

| 断言 | 要杀掉的变异 |
| --- | --- |
| PLAN + 未解锁 + Shift+Tab → `confirmingBypass == true` **且模式仍是 PLAN** | 弹框的同时已经切过去了 |
| 确认框中按 `y` → 解锁被调用、模式变为 BYPASS、`confirmingBypass == false` | 只解锁不切档 |
| 确认框中按任意其它键 → 模式**仍是 PLAN**、`confirmingBypass == false` | 取消后退回 DEFAULT（放宽） |
| 确认框开着时按 Shift+Tab → 被确认框当作取消，**没有**发生模式循环 | 路由顺序写反 |
| `activePermission != null` 时 Shift+Tab 想进 BYPASS → 不开框、模式不动 | 模态叠模态 |
| `activePermission != null` 时 Shift+Tab 从 DEFAULT 切 ACCEPT_EDITS → 照常切 | 把拒绝写宽了，连普通切档也拦 |
| 已解锁后 Shift+Tab 到 BYPASS → 不开框，直接切 | 每次都弹（用户选的是「记住」） |
| 确认框开着时模态请求上前台 → `confirmingBypass` 被清 | 两个面板并存 |
| `permissionRoot()` 返回空串 → 确认框仍渲染出来（少一行） | 空目录让框整个消失 |

### 9.4 状态栏（离屏 Buffer 渲染）

用 `Frame.forTesting` + 反射 `markAsRenderThread` 渲染进离屏 Buffer 后**读回屏幕文本**，
不是断言 `statusLine()` 的返回对象——后者测不到 shimmer 有没有把 suffix 真的拼进去。

| 断言 | 要杀掉的变异 |
| --- | --- |
| THINKING + 非空 notice → 屏幕文本**同时**含「思考中」和 notice 文字 | 恢复 notice 独占 |
| RUNNING_TOOL + 非空 notice → 同时含工具名和 notice 文字 | 只改了 THINKING 分支 |
| IDLE + 非空 notice → 独占整行，**不**含模型名 | 把独占也一并删了 |
| draining + 非空 notice → 同时含收尾提示和 notice | 漏掉 draining 分支 |
| 空 notice → 屏幕上没有悬空的 ` · ·` | `ns` 拼接没判空 |

### 9.5 pty 冒烟（`src/test/resources/scripts/`）

必须实机跑，两幕：

1. **解锁流程**：连按 Shift+Tab，第三下屏幕上出现确认框（读到「跳过权限检查」与
   「y 确认进入」字样）；按 `y` 后行首出现 `⚠`；再按 Shift+Tab 一圈能回到 `⚠`。
2. **运行中切档**：起一个会跑一会儿的回合，运行中按 Shift+Tab，读**当前帧**，
   断言同一屏里既有转轮标签（`思考中` 或 `运行 `）又有新模式标识。

**第二幕的断言必须读当前帧，不能用 `wait_for` 子串**——历史 scrollback 里会有旧的
模式行，子串匹配会假绿（见 `pty wait_for 会命中陈旧 scrollback` 的既有教训）。
窗口须先 `ioctl TIOCSWINSZ` 且 `TERM=xterm-256color`，否则渲染全空白。

---

## 10. 文档更新

`springai-code-tui/README.md` 的权限章节今天有两处会因本次改动而失真：

1. 「仅 `--dangerously-skip-permissions` 启动可进 BYPASS」——改为「Shift+Tab 循环到该档时
   经一次确认即可进入；`--dangerously-skip-permissions` 启动则免确认。解锁不跨进程。」
2. 若 README 沿用了 `PermissionMode` 那句错的「内置危险检查仍然生效」，一并按 §2 改对，
   并说明 BYPASS 下内置底线只留痕不拦截。

`--dangerously-skip-permissions` 与 `--permission-mode` 的说明保持——两个启动参数都还在，
语义也没变。

---

## 11. 验收标准

1. 不带任何权限参数启动，连按 Shift+Tab 能走到确认框，按 `y` 后状态栏行首出现红色 `⚠ 跳过权限检查`。
2. 同一进程内此后 Shift+Tab 四档自由循环，不再弹框。
3. 退出重启后，Shift+Tab 又会在 BYPASS 位弹框。
4. 确认框按非 `y` 键取消后，模式**仍是计划模式**（不是默认）。
5. 有工具审批面板在前台时按 Shift+Tab 想进 BYPASS，不弹框、模式不动、有一句解释。
6. `permissions.json` 写 `defaultMode: "BYPASS"` 仍被忽略并记 WARN。
7. `--permission-mode bypass` 仍被拒绝并记 WARN。
8. 回合运行中按 Shift+Tab，波光转轮**不消失**，切换反馈出现在右侧后缀。
9. `mvn test -pl springai-code-tui` 全绿。
10. 两幕 pty 冒烟通过。

**不在验收范围内**：

- BYPASS 判定语义本身（§3 N4），本次一行没动。
- **空闲态 notice 仍会独占整行**，从而暂时盖住「⏱ N 个后台任务 · /tasks」与「有结果待处理」
  那两个后缀。这是同一类缺陷，但空闲态下「是不是还在跑」不存在歧义（用户面前就是输入框），
  且 notice 在下一次按键即被清掉。刻意留着，不在本次扩大范围——记在这里是为了它别成为静默的漏。

