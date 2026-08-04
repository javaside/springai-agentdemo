# 权限模式交互改造设计（四档平权 + 状态栏 notice 降级）

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

这条陈旧 javadoc 已经误导过一次：本设计第一版据它写的确认框文案，把「内置危险检查仍然生效」
当成事实告诉用户——而那正好是 BYPASS 下**不**成立的那一半。确认框后来被去掉了（见 §3 决策变更），
但这条错误 javadoc 还在，下一个读它的人会犯同样的错。

因此本次一并修正 `PermissionMode` 的 javadoc，使其与 `doDecide` 一致：
BYPASS 下只有 **deny 规则**还拦得住，内置底线被跳过、只留痕。
（README 对应段落本来就是对的——见「BYPASS 放行 … 下面几步全部跳过」那一行，
只有枚举的 javadoc 与它相左。）

---

## 3. 目标与非目标

### 目标

- **G1**：Shift+Tab **四档平权自由循环**，BYPASS 与其余三档一样，无需重启进程、无需任何解锁动作。
- **G2**：任何 notice（不只是切模式）都不再遮住状态栏的运行指示。
- **G3**：修正 `PermissionMode` 关于内置底线的错误 javadoc。

### 非目标

- **N1**：**不**放开配置文件声明 `defaultMode: "BYPASS"`。
- **N2**：**不**放开 `--permission-mode bypass`。
- **N3**：不改 `doDecide` 的任何判定语义——BYPASS 该放行什么、该拦什么，一个字不动。
- **N4**：不移除 `--dangerously-skip-permissions`——它继续表示「启动即进 BYPASS」，
  只是不再是**唯一**入口。无人值守 / 容器里的既有脚本不受影响。

### 为什么 N1/N2 与 G1 不矛盾

今天 `bypassAllowed` 一个字段同时把守三条路，而它们的威胁模型不同：

| 路径 | 威胁 | 本次 |
| --- | --- | --- |
| `permissions.json` 里 `defaultMode:"BYPASS"` | clone 别人的仓库就把你放成裸奔，用户全程无感 | 继续禁 |
| `--permission-mode bypass` | 给那道显眼开关配一个不显眼的等价路径 | 继续禁 |
| 运行期用户当面按 Shift+Tab | 用户在场、有意图、当前档位红色常驻可见 | **解开** |

本次改动就是把这三条被合并成一条的禁令拆开，只解开第三条。

### 决策变更记录：为什么没有确认模态

本设计的第一版在进入 BYPASS 前插了一个确认模态（「y 确认 · 其它键取消」）。
写实施计划时发现它有一个硬缺陷：

```
默认 → ⏵⏵ → ⏸ 计划模式 → [确认框] ──取消──→ 回到计划模式
                                └── y ──→ ⚠ BYPASS → 默认
```

取消不推进循环，于是**离开计划模式的唯一出路是先进 BYPASS**——最安全的一档成了陷阱，
出口是最危险的一档。对照今天的行为可知这是新造的问题而非继承的：

| | 从 PLAN 出去 |
| --- | --- |
| 今天 · 带 `--dangerously-skip-permissions` | 按两下：PLAN → ⚠ BYPASS → 默认。能出去 |
| 今天 · 不带 flag | 按一下：PLAN → 默认。能出去 |
| 确认模态版（取消 = 留在原档） | 永远出不去 |

补救方案有两个：让取消继续走到 DEFAULT（循环一圈就弹一次框，而 PLAN⇄默认是最高频用法），
或把解锁挪进 `/permissions` 面板（多一步、且形态上等于换了个显式入口）。

最终采用**第三条路：不要门槛**。理由是用户的原话——
「现在不就是想取消 `--dangerously-skip-permissions` 这个配置，嫌弃太麻烦了吗」。
给它配个确认框，等于把甩掉的麻烦换个地方装回来。BYPASS 的持续可见性由既有的红色常驻
`modeTag`（`⚠ 跳过权限检查`）承担，那本来就是为此加的。

---

## 4. 议题一：四档平权

改动的形状是**删掉一道门禁**，不是加一个新单元。

### 4.1 `PermissionMode.next()` 去掉参数

```java
/**
 * 循环到下一个模式（UI 的 Shift+Tab）。四档平权，BYPASS 排在 PLAN 之后。
 *
 * <p><b>为什么不带门禁参数</b>：BYPASS 是用户当面按键才进得去的一档，
 * 且进入后状态栏行首常驻红色 {@code ⚠ 跳过权限检查}。
 * 启动时能不能进 BYPASS 是<b>启动参数</b>的事（{@code --dangerously-skip-permissions}），
 * 与运行期循环无关；把两件事塞进同一个布尔，结果是运行期切档也被启动开关卡住。
 */
public PermissionMode next() {
    return switch (this) {
        case DEFAULT -> ACCEPT_EDITS;
        case ACCEPT_EDITS -> PLAN;
        case PLAN -> BYPASS;
        case BYPASS -> DEFAULT;
    };
}
```

循环顺序**不变**（BYPASS 仍排在 PLAN 之后，与今天带 flag 启动时一致），只是不再有三档形态。

### 4.2 `PermissionEngine` 去掉 `bypassAllowed`

删除字段、删除构造器第 4 个参数、删除两处拒绝分支：

```java
// 删除：private final boolean bypassAllowed;
// 删除：public boolean bypassAllowed() { ... }

public PermissionEngine(Path root, PermissionConfig config, PermissionMode startupMode) {
    // ...
    // 删除：if (start == BYPASS && !bypassAllowed) { 降级 DEFAULT }
    this.mode = startupMode != null ? startupMode : config.defaultMode();
}

public PermissionMode setMode(PermissionMode m) {
    if (m == null) return mode;
    // 删除：if (m == BYPASS && !bypassAllowed) { WARN; return mode; }
    this.mode = m;
    return m;
}

public PermissionMode cycleMode() {
    PermissionMode next = mode.next();
    this.mode = next;
    return next;
}
```

**构造器降级分支为什么能删**：它唯一的作用是「未授权时把 BYPASS 启动模式降成 DEFAULT」。
启动模式来自 `CodeTuiApplication.startMode`，而那里只有 `--dangerously-skip-permissions`
这一条路能产出 BYPASS（配置层与 `--permission-mode` 都仍然拒绝，见 §4.3）。
于是这个分支在本次改动后**永远不会命中**——留着就是一段假装还在守什么的死代码。

**并发**：`mode` 已是 `volatile`，不变。删掉的是一个 final 字段，不引入新的可变共享状态。

### 4.3 两条禁令原样保留

以下代码**一个字不动**，并各配一条回归测试钉住：

- `PermissionConfigLoader` 丢弃两层配置里的 `defaultMode: "BYPASS"` 并记 WARN；
- `CodeTuiApplication.startupMode` 对 `--permission-mode bypass` 返回 `null` 并记 WARN。

它们守的是「用户全程无感就被放成裸奔」，与运行期当面按键是两个威胁模型（见 §3 表）。
拆禁令时最容易顺手拆过头的就是这两处，所以钉子必须先于删改写好。

### 4.4 `--dangerously-skip-permissions` 保留

语义从「唯一能进 BYPASS 的入口」变成「启动即进 BYPASS」。启动横幅照打。
`CodeTuiApplication` 里 `skipPermissions` 变量只剩这一个用途：决定 `startMode`。
它不再需要传给 `PermissionEngine`。

### 4.5 `SubmitHandler` / `CodingAgent` 不变

不新增任何接口方法。UI 侧 Shift+Tab 分支也**不改**——它本来就是
`state.setNotice("…" + onSubmit.cyclePermissionMode().label())`，四档循环是引擎侧的事。
（唯一的改动是 notice 文案，属议题二，见 §5.3。）

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

### 生产代码

| 文件 | 改动 |
| --- | --- |
| `agent/permission/PermissionMode.java` | 修正 BYPASS 的错误 javadoc（§2）；`next(boolean)` → `next()`，四档恒定 |
| `agent/permission/PermissionEngine.java` | 删 `bypassAllowed` 字段、构造器第 4 参、`bypassAllowed()` getter、构造器降级分支、`setMode` 的拒绝分支 |
| `agent/AgentTools.java:567` | 隔离引擎构造少传一个 `false` |
| `CodeTuiApplication.java:83` | 构造少传 `skipPermissions`；`skipPermissions` 只剩决定 `startMode` 一个用途 |
| `ui/CodeTuiView.java` | `statusLine()` 的 notice 降级（§5.2）；Shift+Tab 分支的 notice 文案（§5.3） |
| `springai-code-tui/README.md` | 权限章节（§10） |

**无新增文件、无新增接口方法。** 净删除多于净新增。

### 编译期必然暴露的连带更新

这两处改签名，漏改就是编译错误——不存在静默漏网：

| 签名 | 调用点 |
| --- | --- |
| `PermissionMode.next(boolean)` → `next()` | 1 处生产（`PermissionEngine.cycleMode`）+ 10 处测试 |
| `PermissionEngine` 构造器去掉第 4 参 | 2 处生产 + 18 处测试 |

测试侧多数只是删一个 `true` / `false` 实参。三处需要**改断言**：

- `PermissionModeTest.cyclesThreeWithoutBypass` —— 三档循环的断言不再成立，改写为四档；
- `CodeTuiViewPermissionModeTest.shiftTabThriceReturnsToDefault` —— 连按三次到的是 BYPASS 不是 DEFAULT，
  改为连按四次回默认；
- `CodeTuiViewModeIndicatorTest` —— 桩字段 `bypassAllowed` 删除；
  断言里的 `"权限模式：自动接受编辑"` 随 §5.3 改为 `"已切到 自动接受编辑"`。

---

## 7. 边界情形

| 情形 | 行为 | 理由 |
| --- | --- | --- |
| 从 PLAN 按一下 Shift+Tab | 进 ⚠ BYPASS（不再是 DEFAULT） | 四档平权；与今天带 flag 启动时完全一致 |
| 从 PLAN 回到默认 | 按两下（PLAN → ⚠ → 默认） | 同上。**这是本次唯一的行为回退点**：不带 flag 时今天只需一下 |
| 有审批/计划/作答模态在前台时按 Shift+Tab | 照常切档（既有行为，不变） | 模式只影响后续判定，不动任何 pending 请求 |
| 运行期切到 BYPASS 时有后台子 agent 在飞 | 立刻对它们生效（后续调用不再被 ASK→DENY 挡） | 模式是进程全局的；预期行为 |
| 运行期切到 BYPASS 时有审批面板正等着 | 那个 pending 请求**照旧等人应答**，不被追认放行 | 请求早已决策完毕并 park 在队列上，模式只管下一次判定 |
| `--dangerously-skip-permissions` 启动 | 起始档 BYPASS，横幅照打 | §4.4 |
| `permissions.json` 声明 `defaultMode:"BYPASS"` | 丢弃 + WARN（不变） | §4.3 |
| `--permission-mode bypass` | 拒绝 + WARN（不变） | §4.3 |
| notice 为空 | `ns` 为空串，不产生悬空的 ` · ` | §5.2 |

「从 PLAN 回到默认要按两下」是这次唯一会让老用户手感变化的地方。它是四档平权的直接推论，
且与带 flag 启动时的既有手感一致，故接受。

---

## 8. 错误处理

本改动没有 IO、没有网络、没有新增并发状态，错误面只有一处：

- **`setMode(null)`**：保持既有的「返回当前档、不改状态」，不抛。它是 UI 反复调用的路径，
  抛异常等于让一次误按崩掉整个 TUI。

删掉的两处 `log.warn`（拒绝进 BYPASS）随分支一起消失——它们描述的情况不再存在，
留着会在日志里制造「系统还在拦什么」的错觉。

---

## 9. 测试策略

命令一律带模块作用域：`mvn test -pl springai-code-tui -Dtest='...'`。
（整仓 `-Dtest` 会被三个空模块打挂。）

### 9.1 四档循环

| 断言 | 要杀掉的变异 |
| --- | --- |
| `DEFAULT.next()` == `ACCEPT_EDITS`、`ACCEPT_EDITS.next()` == `PLAN`、`PLAN.next()` == `BYPASS`、`BYPASS.next()` == `DEFAULT` | 顺序写错、某档自环 |
| 从 DEFAULT 连调四次 `next()` 回到 DEFAULT，且四次结果互不相同 | 漏掉一档导致三档循环 |
| `engine.cycleMode()` 连调四次走遍四档 | 引擎没接上新的 `next()` |
| `engine.setMode(BYPASS)` 直接生效，`mode()` == `BYPASS` | 拒绝分支没删干净 |
| 构造 `new PermissionEngine(root, empty, BYPASS)` 后 `mode()` == `BYPASS`（不降级） | 构造器降级分支没删 |

### 9.2 禁令回归钉（防止拆过头）

**这两条必须先写、先跑绿，再动 §4.2 的删除**——它们是本次唯一还在守东西的地方：

| 断言 | 要杀掉的变异 |
| --- | --- |
| 用户层与项目层配置里的 `defaultMode:"BYPASS"` 均被丢弃，引擎起始档不是 BYPASS | 拆禁令时把 `PermissionConfigLoader` 的检查一起删了 |
| `CodeTuiApplication.startupMode(new String[]{"--permission-mode","bypass"})` 返回 `null` | 同上，删了 CLI 侧的检查 |
| `startupMode(new String[]{"--permission-mode","plan"})` 仍返回 `PLAN` | 上一条写成「一律返回 null」的假绿 |

最后一条是**反向断言的配套**：只断言「bypass 被拒」，一个把整个方法改成 `return null`
的变异也能让它通过。必须有一条正向用例证明这个方法还在正常工作。

### 9.3 状态栏（离屏 Buffer 渲染）

用 `ViewScreen.of(view)` 渲染进离屏 Buffer 后**读回屏幕文本**，不是断言 `statusLine()`
的返回对象——后者测不到 shimmer 有没有把 suffix 真的拼进去。

| 断言 | 要杀掉的变异 |
| --- | --- |
| THINKING + 非空 notice → 屏幕**同时**含「思考中」与 notice 文字 | 恢复 notice 独占 |
| RUNNING_TOOL + 非空 notice → 同时含工具名与 notice 文字 | 只改了 THINKING 分支 |
| IDLE + 非空 notice → 独占整行，**不**含当前模型名 | 把独占也一并删了 |
| 空 notice + THINKING → 屏幕上没有 ` ·  · ` 这样的空段 | `ns` 拼接没判空 |

驱动状态用 `state.onTurnStarted(1L)`（→ THINKING）与
`state.onToolStarted(1L, "Bash", "{\"command\":\"npm test\"}")`（→ RUNNING_TOOL）。

### 9.4 pty 冒烟

`src/test/resources/scripts/permission_smoke.py` 需**更新**（不是新增）：

1. `MODE_DEFAULT` / `MODE_ACCEPT` / `MODE_PLAN` 三个常量的文案随 §5.3 改为「已切到 …」，
   并新增 `MODE_BYPASS = "已切到 跳过权限检查"`。
2. 全部 `PLAN → DEFAULT` 的一键跳转（约 10 处）改为两键，中间经过 BYPASS。
3. 新增一幕：**运行中切档**——起一个会跑一会儿的回合，运行中按 Shift+Tab，
   读**当前帧**，断言同一屏里既有转轮标签（`思考中` 或 `运行 `）又有新模式标识。

第 3 幕的断言**必须读当前帧，不能用 `wait_for` 子串**：历史 scrollback 里会有旧的模式行，
子串匹配会假绿。窗口须先 `ioctl TIOCSWINSZ` 且 `TERM=xterm-256color`，否则渲染全空白。

---

## 10. 文档更新

`README.md` 需改的位置及改法：

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| 特性列表 | 「`Shift+Tab` 在「默认 / 自动接受编辑 / 计划模式」三档间循环」 | 四档，含「跳过权限检查」 |
| 权限模式表下方 | 「**BYPASS 只能由 `--dangerously-skip-permissions` 启动进入**，键盘和配置文件都进不去」 | 「`Shift+Tab` 四档循环，BYPASS 也在其中；**配置文件仍进不去**（clone 的仓库不该让你启动即裸奔）。`--dangerously-skip-permissions` 表示启动即进该档」 |
| `--permission-mode` 段 | 「全放行只能由 `--dangerously-skip-permissions` 进，否则那道启动开关就等于有了第二个入口」 | 「不接受 `bypass`——`--dangerously-skip-permissions` 已经是启动进该档的写法，不再设第二条等价路径」 |
| 快捷键表 | 「循环权限模式（默认 → 自动接受编辑 → 计划模式 → 默认）」 | 四档 |

`--dangerously-skip-permissions` 关于「真的跳过全部检查」的那几段**不改**——那些描述的是
BYPASS 档的判定语义，本次一个字没动（§3 N3）。

---

## 11. 验收标准

1. 不带任何权限参数启动，连按 Shift+Tab 三下进入 BYPASS，状态栏行首出现红色 `⚠ 跳过权限检查`。
2. 第四下回到默认，标识消失。
3. `permissions.json` 写 `defaultMode: "BYPASS"` 仍被忽略并记 WARN。
4. `--permission-mode bypass` 仍被拒绝并记 WARN；`--permission-mode plan` 仍正常生效。
5. `--dangerously-skip-permissions` 启动仍直接进 BYPASS 并打横幅。
6. 回合运行中按 Shift+Tab，波光转轮**不消失**，切换反馈出现在右侧后缀。
7. `PermissionMode` 的 javadoc 不再宣称 BYPASS 下内置危险检查仍然生效。
8. `mvn test -pl springai-code-tui` 全绿。
9. `permission_smoke.py` 通过（含新增的「运行中切档」一幕）。

**不在验收范围内**：

- BYPASS 的判定语义本身（§3 N3），本次一行没动。
- **空闲态 notice 仍会独占整行**，从而暂时盖住「⏱ N 个后台任务 · /tasks」与「有结果待处理」
  两个后缀。同一类缺陷，但空闲态下「是不是还在跑」不存在歧义（用户面前就是输入框），
  且 notice 在下一次按键即被清掉。刻意留着，记在这里是为了它别成为静默的漏。
