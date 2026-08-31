# Task 6 Report — Make context usage refresh demand-driven and single-flight

**Status: COMPLETE**
**Commit: `824b957` refactor(code-tui): refresh context usage on demand**
**Worktree:** `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui` (branch `refactor/event-driven-ui`)
**Spec:** §10.4 ContextUsage（`docs/superpowers/specs/2026-08-30-code-tui-event-driven-ui-design.md`）
**Brief:** `.superpowers/sdd/2026-08-30-code-tui-event-driven-ui/task-6-brief.md`

---

## 1. Files

| File | Change |
|---|---|
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java` | Modified — `refresh()` void→boolean；类/构造器/`refresh()` 升为 public（跨包消费） |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshController.java` | **Created** — 按需/防抖/单飞/追赶调度器 |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ContextUsageTest.java` | Modified — 新增 6 个 refresh 返回值语义测试（原 21 个断言全部保留、未改一字） |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshControllerTest.java` | **Created** — 11 个控制器契约测试 |

`CodeTuiView` 本任务**未改**（animTick % 30 周期触发暂留，`ctxUsage::refresh` 方法引用作为 Runnable 编译不受返回值变化影响——Task 7 删除）。

## 2. Implementation

### 2.1 `ContextUsage.refresh(): boolean`

```java
public boolean refresh() {
    try {
        ContextStats next = source.get();
        if (next == null) return false;          // 旧语义保留：null 不更新缓存
        ContextStats prev = cached;
        if (next.equals(prev)) return false;     // 可见口径等值 → 不重写 volatile、不触发 UI 重画
        cached = next;
        return true;
    } catch (RuntimeException ignore) {
        return false;                             // 异常：保留旧快照
    }
}
```

- **true 仅当缓存的可见数据真实变化**。「可见口径」= `suffix()` / `cacheHitSuffix()` / `report()` 全部输出的输入字段；`ContextStats` 是 record，其 equals 逐组件比较恰好覆盖该口径（events、各事件桶数、estimatedTokens、threshold、window、keep 数、vision、cache 计数、`cacheHitPercent`（Integer equals，无缓存 null==null）、`TokenBreakdown`（嵌套 record）、systemPromptTokens）——等值但身份不同的新快照返回 false，避免无意义 VIEW 发布。
- **null / 异常 / 等值三态都不动缓存**，与旧行为逐点一致（旧代码 `if (s != null) cached = s` 异常/null 均不写；新增的等值短路只是少写一次相同值的 volatile 写，任何并发读到的值集合不变）。
- 可见性升级说明：类、构造器、`refresh()` 变 public 是任务接口的必然结果——controller 在 `ui.update` 包且测试也要消费返回值；`report()/suffix()/cacheHitSuffix()` 仍为包内可见（javadoc 中说明了这一例外及原因）。

### 2.2 `ContextUsageRefreshController`（`ui.update`，`AutoCloseable`）

无锁状态机，全部 `AtomicBoolean`：`inFlight`（单飞权）、`armed`（防抖已武装）、`debt`（追赶欠账）、`stopped`。

```
IDLE ──markDirty──► ARMED（防抖 timer）──到期──► 提交 refresh，进入 REFRESHING
ARMED 期间再 markDirty：不重排 timer（首个到期为准 → 突发合并）
REFRESHING 期间 markDirty：只置 DEBT，不提交任务（单飞）
REFRESHING 完成：DEBT=true → 立即再提交恰好一个追赶（单飞权直接移交，任务间无无主窗口）；
                 DEBT=false → 归还单飞权回 IDLE
```

关键点（对应 brief Step 3 要求）：

1. **markDirty 防抖合并**：CAS 武装 `armed`，窗口内重复标脏直接返回；防抖到期（scheduler 线程）只向 executor 提交任务，绝不触碰 View（§6.2 timer 纪律）。
2. **单飞**：refresh 任务体先 CAS `inFlight` false→true；在飞期间 markDirty 只 `debt.set(true)`，**不武装防抖**（防抖的意义是合并启动前的突发；执行不并发由单飞保证，欠账由完成路径立即消化，无需再等窗口）。
3. **版本追赶、至多一次**：完成复查 `debt.getAndSet(false)` —— 连续标脏合并为恰好一个追赶 refresh；追赶任务由完成者把单飞权**直接移交**（`permitTransferred=true` 跳过 CAS），不产生「归还→再抢」的无主窗口。「追版本但不追平」：`refresh()` 读实时会话源，欠账只需表达「又有变化」。
4. **回调条件**：仅 `changed && !stopped` 才回调 `onRefreshed`（生产接线中它发布 VIEW，即设计 §10.4「refresh 完成发布 VIEW」）；数据没变不打扰 UI。回调在 executor 线程执行且吞掉一切 Throwable。
5. **异常安全**：`refresh()` 自身吞 source 异常（保留旧缓存返回 false）；任务体再兜一层 `Throwable`（挡 Error 级），单飞权在任何路径（含追赶提交被 executor 拒绝）都不泄漏——拒绝时归还并改走 `markDirty()` 防抖重试。
6. **stop**：置 `stopped`、取消已武装防抖 timer、清 armed/debt；之后 markDirty / 迟到的防抖到期 / 完成复查 / 回调全部 no-op；在飞中的 refresh 允许自然跑完但静默（不回调、不追赶；接管到的单飞权归还）。幂等；`close()` ≡ `stop()`。
7. **丢唤醒窗口闭合**（与 coordinator runBatch 同款复查）：归还单飞权后若发现 `debt` 又被置位（markDirty 恰在「消费欠账→归还」之间看到在飞），补武装一个防抖窗口。
8. 生产参数：executor = CodeTuiView 现有 `context-usage-refresh` 单线程池（token 估算数百 ms 绝不占 UI 线程——类注释保留该理由）、scheduler = coordinator 共享 scheduler、debounce 由 Task 7 定。

### 2.3 明确不做（按 brief/约束）

- 不接入 `CodeTuiView`：`animTick % 30` 周期触发、`ctxUsageForTest()` 测试驱动口全部原样保留（Task 7 换成 controller 接线并删周期触发）。
- 不改 `/context` 报告、suffix、cache-hit 任何文案路径。

## 3. Tests

### 3.1 TDD 过程（先失败后实现）

Step 2 失败证据（实现前）：
```
[ERROR] ContextUsageRefreshControllerTest.java 找不到符号: 类 ContextUsageRefreshController   （多处）
[ERROR] ContextUsageRefreshControllerTest.java:[29,46] io.github.javaside.springai.codetui.ui.ContextUsage在io.github.javaside.springai.codetui.ui中不是公共的; 无法从外部程序包中对其进行访问
[ERROR] ContextUsageTest.java:[321,30] 此处不允许使用 '空' 类型    ← assertTrue(cu.refresh()) 对 void 的断言（多处）
BUILD FAILURE
```
与 brief「Expected: controller absent and refresh() return assertions fail to compile」一致。

### 3.2 命令与精确结果

Brief Step 4 命令：
```bash
mvn -pl springai-code-tui -am \
  -Dtest=ContextUsageTest,ContextUsageRefreshControllerTest,CacheHitStatusBarWidthTest,BusyCacheHitStatusTest,CacheHitPercentHighlightTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
结果：
```
Tests run:  6 ... in io.github.javaside.springai.codetui.ui.CacheHitStatusBarWidthTest
Tests run: 11 ... in io.github.javaside.springai.codetui.ui.update.ContextUsageRefreshControllerTest
Tests run:  3 ... in io.github.javaside.springai.codetui.ui.BusyCacheHitStatusTest
Tests run:  3 ... in io.github.javaside.springai.codetui.ui.CacheHitPercentHighlightTest
Tests run: 26 ... in io.github.javaside.springai.codetui.ui.ContextUsageTest
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整回归（设计 §15.6）：
```bash
mvn -pl springai-code-tui -am test
```
结果：
```
Tests run: 1784, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS（01:08 min）
```
基线对照（`git stash -u` 后同命令）：`Tests run: 1768, Failures: 0, Errors: 0, Skipped: 10` —— 净增 16 个测试（ContextUsageTest +5、ControllerTest +11），10 个 skipped 全部为既有网络 smoke 测试（BraveWebSearch/Bocha/DeepSeekVision/Qwen/McpStreamableHttp/CodingAgentSpike），与本次改动无关。

### 3.3 新增测试覆盖（brief Step 1 逐项）

ContextUsageTest（6）：
- 等值新快照（身份不同）→ false；两次各现算一次；
- empty→empty false、empty→有数据 true（events 可见）；
- 窗口/token 变化 → true；
- 异常 → false + 保留旧缓存；
- null → false + 已建缓存不重置回空（防止状态栏闪没）。

ContextUsageRefreshControllerTest（11）：
- 50 次 markDirty 突发 → 1 个防抖任务、1 次 refresh、恰好 1 次回调；
- 滑动窗口内重复标脏不叠加第二个任务、未执行前不触碰 source；
- 在飞期间 3 次标脏 → 0 个新任务、完成复查 → 恰好 1 个追赶、总 refresh=2；
- 在飞无新标脏 → 完成后不排任何任务；
- 缓存可见未变 → 不回调（首刷变化回调 1 次，等值追刷不再增）；
- 每次真实变化各恰回调一次（含变回去）；
- 异常 → 不回调、不卡死，后续标脏照常刷新；
- stop 后 markDirty 不排任务、已武装防抖到期 no-op、从未触碰 source；
- stop 于在飞中：第一刷允许跑完但不回调、不追赶、迟到窗口滑过仍无任务；
- close() ≡ stop()；
- 8 线程×200 并发标脏压测：source 绝不并发进入（maxInside==1，单飞+单线程 executor 双保险）、refresh 次数 ≪ 1600、排空后无残留单飞权。

测试接缝：`ManualExecutor`（入队不执行，受控 FIFO drive）隔离「防抖到期」与「refresh 执行」两阶段；防抖到期用真实 scheduler 异步发生再轮询收敛（10s 上限），不 sleep-猜时序。

## 4. Commit

```
824b957 refactor(code-tui): refresh context usage on demand
 4 files changed, 818 insertions(+), 8 deletions(-)
```
与 brief Step 5 的文件清单、message 完全一致。

## 5. 自审（Self-review）

1. **null 语义纠偏**：初版实现把 null 归一为 `empty()` 并写缓存——与旧代码「null 不写」不一致（非空缓存遇 null 会被重置回空，状态栏「 · 上下文 N%」闪没）。已在实现前发现并改为沿旧语义，测试 `refresh_nullSource_returnsFalseAndKeepsCache` 钉死。
2. **追赶路径单飞权**：若追赶任务重新 CAS 获取单飞权会因「权仍被前一个完成者持有」而自拒。改为完成者直接**移交**（`permitTransferred` 跳过 CAS），且所有提前退出路径（stop、提交被拒）都归还权限。8 线程压测的 `maxInside==1` 断言为该不变量提供并发证据。
3. **回调时机**：追赶续启（submitted）时先回调再 return，顺序为「通知旧变化 → 新 refresh 执行」，无回调丢失；`notify` 在 stop 后重新求值（`changed && !stopped`），stop 后零回调。
4. **`ctxUsage::refresh` 兼容性**：`CodeTuiView` line 570 的方法引用（Runnable 目标）不消费返回值，编译与行为均不受影响——完整回归 1784 通过即证据。
5. **不接入 View 遵守**：`git diff` 确认 `CodeTuiView.java` 零改动。
6. **现有断言保留**：`ContextUsageTest` 原 21 个测试方法体未动（仅追加）；`CacheHit*`/`BusyCacheHit*` 原样通过。
7. **等值短路的多线程效果**：少一次 volatile 写不会让任何读者观察到旧代码观察不到的值（新值与旧值 equals 相等）。

## 6. Concerns

1. **（Task 7 必办）接线清单**：① View 构造 controller（executor=现有 `contextUsageExecutor`、scheduler=coordinator 的、debounce 建议沿用 ~1s 量级或更短、onRefreshed=`coordinator.onUiChanged(UiDirty.VIEW)`）；② 事件源挂 `markDirty()`（turn 完成、压缩、`/clear`、工具/消息落盘等影响统计的 mutation）；③ 删除 `animTick % 30` 周期触发；④ `onStop()`/close 顺序并入设计 §13.2。controller 类注释已留此说明。
2. **首次进会话不标脏则无首刷**：按需模型下 UI 依赖首个可见变化事件出现；Task 7 启动全量同步（§13.1 第 5 步）时应记得显式 `markDirty()` 一次，避免空会话→首条消息之间状态栏一直无上下文百分比。
3. **`refreshInFlight()` 不反映 stop**：stop 后在飞的 refresh 仍显示 true 直至自然跑完（已在 javadoc 注明；与「允许跑完但不产生副作用」语义一致）。
4. **追赶到防抖的降级重试**：追赶提交被 executor 拒绝时归还权限并走 `markDirty()` 防抖重试——若拒绝源于 executor 关闭（生产 stop 场景），重试也会静默失败；这是有意保守路径，但意味着「executor 半死（拒绝但未关闭）」时刷新延迟一个防抖窗口。生产为 daemon 单线程池 + shutdownNow，不构成实际风险。
5. **公共 API 扩大**：`ContextUsage` 类/构造器/`refresh()` 变 public 是任务接口要求，但把一个原本包私有的类暴露给了整个模块；Task 7 接线后若发现可以收窄（例如 controller 移回 `ui` 包），可考虑收回——当前按 brief 指定包路径（`ui/update`）实现。

---

# Fix Round Report — 审查发现 I-1 + M-1…M-5

**Status: COMPLETE**
**Commit: `e217dea` fix(code-tui): tighten Task 6 review findings (I-1, M-1..M-5)**
**Worktree:** 同上（branch `refactor/event-driven-ui`）

## F1. 修复清单（逐项）

### I-1（必改）：`refresh_percentInputsChange_isVisibleChange` 未测试其声称场景

**问题确认**：原第二段构造的是全新 `ContextUsage`（初始缓存 `empty()`），`assertTrue(windowChanged.refresh())` 由 empty→有数据恒真，与「窗口变化检测」无关——窗口字段变化只是陪衬，断言区分不了任何实现。

**修复**：改为**同一实例**两段式：
1. `src = statsWithEstimate(30_000L)`（window=100_000）→ 首刷 `true`，并新增断言 `suffix()==" · 上下文 30%"` 钉住缓存内容；
2. 同一实例换 source 为 window=50_000 的快照 → `refresh()` 断言 `true`（百分比输入变化），并断言 `suffix()==" · 上下文 60%"`（30000/50000，证明后缀确实随窗口变化——比原断言更强，多了可见效果验证）。

**补充用例** `refresh_cacheHitPercentChange_isVisibleChange`（同实例）：`withCache(0,0,null)` 建缓存（后缀无缓存命中）→ 换 `withCache(80,100,80)` → `true`，后缀追加「 · 缓存命中 80%」。首刷在 `null` cacheHitPercent 下返回 true 也顺带钉住「`Integer` 组件 null≠80 走 equals，无 NPE、null==null 恒等」的边界。

### M-1：报告 §1/§3.3「新增 6 个」计数更正

**更正（不重写历史段落）**：§1 表格与 §3.3 所载「ContextUsageTest 新增 6 个 refresh 返回值语义测试」计数有误——实际为 **5 个**（`refresh_visibleDataUnchanged_returnsFalseEvenForNewRecord`、`refresh_eventCountChange_isVisibleChange`、`refresh_percentInputsChange_isVisibleChange`、`refresh_sourceThrows_returnsFalseAndKeepsCache`、`refresh_nullSource_returnsFalseAndKeepsCache`；ContextUsageTest 总数 26 = 原 21 + 新 5，§3.2 的「净增 16 = +5 + 11」数字本身是对的，仅 §1/§3.3 的「6」为笔误）。本轮后新增数变为 **6 个**（+`refresh_cacheHitPercentChange_isVisibleChange`），ContextUsageTest 总数 27。

### M-2：`ContextUsage.java` :129 javadoc 口径修正

原「record 自带的 equals 语义与此恰好一致」不成立（`autoKeepEvents` 无任何渲染输出）。改为如实表述：**equals 是可见口径的超集，方向安全（多通知、不漏通知）**——可见数据变化 ⇒ 必有渲染组件变化 ⇒ equals 不等 ⇒ true；反向只可能「多通知」，且未渲染组件只有 `autoKeepEvents`（`manualKeepEvents` 有「手动 /compact」行），生产取值为编译期常量（`AgentTools.MAX_EVENTS_TO_KEEP=120`，static final int），不产生实际噪音。

### M-3：`debounceWindowRepeatedMarksStayOneTask` 时序假红

原实现在 80ms 窗口内 `sleep(30)×2`——慢机器上调度延迟可能把第三次标脏推到窗口之后，重复标脏被误判为「叠加了第二个任务」。改为**可注入的假时钟** `ManualScheduler`（测试内 `ScheduledExecutorService` 桩：防抖任务入队不执行，`fireNext()` 显式模拟到期）：首标脏武装 1 个 timer → 窗口内再标 2 次脏断言 `pendingCount()==1`（不重排/不叠加）→ `fireNext()` 后断言 executor 恰好 1 个任务、source 零触碰。**判别力增强**（原版只看最终队列大小，新版直接断言窗口内 timer 计数恒 1 + 到期恰好一个提交），全程零真实 sleep。

### M-4：拒绝降级两路径补测（ToggleExecutor，一次性拒绝）

新增 `ToggleExecutor`：`rejectNextSubmit()` 后**恰好一次** `execute` 抛 `RejectedExecutionException`，随后自动恢复——「拒哪一次提交」由测试精确指定，无时序竞态。

1. **追赶提交被拒**（`rejectedCatchUpSubmitDegradesToDebouncedRetry`，对应原 §6.4 Concerns）：首刷入队执行（latch 阻塞在 source）→ 在飞期间武装拒绝 + 标脏 → 放行 → 完成复查的追赶提交被拒：断言单飞权归还（`refreshInFlight()==false`）、无任务残留、**重新武装恰好 1 个防抖 timer**、首刷变化照常回调一次（降级只影响追赶不吞首刷通知）→ 假时钟到期 → 恰好 1 个重试任务 → 执行后 source 共读 2 次、STEP→STEP_MORE 再回调一次、单飞权归还。恢复路径完整。
2. **防抖到期提交被拒**（`rejectedDebounceSubmitAllowsRearm`）：到期 → execute 抛 REE → 无任务、从未触碰 source、无自动残留 timer → 新 markDirty **可重新武装**（armed 已清）→ 到期重试提交成功 → refresh 执行并回调。一次拒绝不构成永久失能。

### M-5：注释与 javadoc 准确性

- `refresh_visibleDataUnchanged_returnsFalseEvenForNewRecord` 的「破除 Integer 缓存：数值故意用 != -128..127 的 12345」理由不成立（record equals 对 Integer/Long 组件用 equals 而非 ==，且注释里写的 12345 与实际用的 30_000 不符）→ 改为准确表述：「实例身份必然不同，record equals 逐组件相等（Integer/Long 组件按 equals 比较，与 == 语义无关）」。
- `refreshInFlight()` 方法级 javadoc 补「**不反映 stop**：stop 后在飞的 refresh 仍报 true 直至自然跑完（在飞任务无法安全中断，完成路径静默）」。

## F2. 断言未弱化声明

- ContextUsageTest：原 26 个用例全部保留；被改写的 1 个（I-1）断言从 1 条恒真断言升级为 2×`assertTrue(refresh)` + 2×`assertEquals(suffix)`（4 条有效断言）；其余仅注释/javadoc 修正。
- ControllerTest：原 11 个用例全部保留；被改写的 1 个（M-3）判别力增强（窗口内 timer 计数断言为新增，原「不叠加任务/source 零触碰」两条保留）；新增 2 个（M-4）。生产代码仅 javadoc 改动，零行为变化（`ContextUsage.java` +8/-2、`ContextUsageRefreshController.java` +6/-1 全为注释）。

## F3. 测试命令与精确结果

聚焦（brief Step 4 命令）：
```bash
mvn -pl springai-code-tui -am \
  -Dtest=ContextUsageTest,ContextUsageRefreshControllerTest,CacheHitStatusBarWidthTest,BusyCacheHitStatusTest,CacheHitPercentHighlightTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
```
Tests run:  6 ... in io.github.javaside.springai.codetui.ui.CacheHitStatusBarWidthTest
Tests run: 13 ... in io.github.javaside.springai.codetui.ui.update.ContextUsageRefreshControllerTest
Tests run:  3 ... in io.github.javaside.springai.codetui.ui.BusyCacheHitStatusTest
Tests run:  3 ... in io.github.javaside.springai.codetui.ui.CacheHitPercentHighlightTest
Tests run: 27 ... in io.github.javaside.springai.codetui.ui.ContextUsageTest
Tests run: 52, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
（对照上轮 49：ContextUsageTest 26→27、ControllerTest 11→13。）

全模块回归（设计 §15.6）：`mvn -pl springai-code-tui -am test`
```
Tests run: 1787, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS（01:07 min）
```
（上轮 1784 → 1787，净增 3：ContextUsageTest +1、ControllerTest +2。10 skipped 仍为既有网络 smoke 测试。）

稳定性复跑（本轮改动的时序相关用例）：聚焦两测试类连跑 3 次全绿（`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0` ×3）；M-3/M-4 三个新/改用例运行在假时钟上，无 wall-clock 依赖。

## F4. Commit

```
e217dea fix(code-tui): tighten Task 6 review findings (I-1, M-1..M-5)
 4 files changed, 346 insertions(+), 20 deletions(-)
```
生产代码改动均为 javadoc/注释（ContextUsage.java、ContextUsageRefreshController.java），行为零变化；测试为 1 个改写增强 + 3 个新增（I-1 补充用例、M-4 两路径）+ 接缝（ManualScheduler 假时钟、ToggleExecutor 一次性拒绝）。

## F5. 本轮 Concerns

1. `ManualScheduler.fireNext()` 在无任务可触发时抛 `IllegalStateException`（fail-fast，防测试序列写错时静默吞掉武装）——若未来 controller 改为可取消后不补排，需同步调整该桩语义。
2. `ToggleExecutor` 语义是「一次性拒绝」而非真实关闭态；真实 executor 关闭后的持续拒绝场景（原 §6.4 的保守路径：重试也静默失败）仍无测试——那是终态场景，断言只能验证「不炸、不泄漏」，本轮以一次性拒绝覆盖降级与恢复，属有意取舍。
