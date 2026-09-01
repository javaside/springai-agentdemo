# Task 8 Report: Convert preview, resize, animation and IME follow-up frames to demand-driven tasks

**状态**: 完成
**Commit**: `9317a29` — `refactor(code-tui): schedule visual updates only on demand`
**Worktree**: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`（branch `refactor/event-driven-ui`）

---

## 1. 文件

| 文件 | 变更 |
|---|---|
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java` | 修改（preview 节流调度 + 动画 demand 接线 + 注释/test seam） |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinator.java` | 修改（新增 `hasPendingPreview()` / `hasPendingAnimation()` 诊断口，重构 timer 在飞判定为共用 `hasPendingTimer`） |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewEventWiringTest.java` | 修改（+9 用例：preview×4 / animation×2 / idle×1 / resize×2） |
| `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java` | 修改（新增 `needsFollowUpFrame()`） |
| `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineViewport.java` | **新增**（同包同名 shadow，转发 `needsFollowUpFrame()`） |
| `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineTuiRunner.java` | 修改（`drawAndMaybeScheduleImeFollowUp`：draw 后按需一次性补帧） |
| `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java` | 修改（+1 用例：窗口同源同寿命） |
| `springai-tamboui-inline-patch/src/test/java/dev/tamboui/tui/InlineTuiRunnerEventDrivenTest.java` | 修改（+2 用例：按需补帧后静止 / 静止不补帧；fixture 增 `renderBody` 可编程帧内容） |

`ResizeSettle.java`：**无需删除**（Task 7 已整体删除，本次只补验证）。

---

## 2. 实现

### 2.1 preview 节流（§10.1，设计「150ms 窗口内新 token 只更新状态；窗口到期只安排一次 VIEW update」）

接线点：`computeFollowUpFlags()`（每批尾执行）。

```java
String curTail = lastLine(state.streaming());
boolean previewPending = !curTail.isEmpty();
...
if (previewPending) {
    coordinator.schedulePreview(previewRemainingDelay());
}
```

- **`previewRemainingDelay()`**：把「距上次采纳（render 内 `lastPreviewAtNanos`）」换算成剩余窗口；
  已过窗口（含首段）返回 `ZERO`。
- **唤醒与采纳分离**：coordinator 的 preview 到期只 publish 一次 `VIEW`；是否真的换预览内容由
  `render()` 里<b>保留不动</b>的 150ms 判定决定（brief 明言「render 里的 150ms 判定逻辑保留为最终采纳点」）。
- **首段立即可见**：首 token 时 `lastPreviewedTail=""` 且窗口早已过期 → render 当帧采纳（有测试钉）。
- **残行清空立即 VIEW 不等节流**：`render()` 的 `curTail.isEmpty()` 分支当帧清空（既有逻辑，未改，有测试钉）。
- **回合结束不续排**：`previewPending` 只有残行非空才置真；flush 后 streaming 清空 → 不再调度
  （有测试钉：`preview_noTaskAfterTurnCompletion`）。
- **窗口内多 token 合一**：coordinator `scheduleOneShot` 的「每类至多一个在飞 generation」语义
  天然合并（Task 3 已备）；重复调用保持首个到期。

### 2.2 动画帧（§10.3「THINKING、RUNNING_TOOL、compacting 或运行中任务存在时启动；状态消失即停止」）

接线点：`computeFollowUpFlags()` 批尾（Task 7 fix round M-5 预留点的落地）：

```java
boolean animationActive = animationDemandActive();
coordinator.updateAnimationDemand(animationActive, ANIMATION_FRAME_DELAY);
```

- `animationDemandActive()`（Task 7 已备的判定，本次接线）：`!state.isIdle() || state.isCompacting()
  || state.backgroundRunningCount() > 0 || onSubmit.hasInFlightSubagents()`。
  `!isIdle()` 覆盖 THINKING 与 RUNNING_TOOL（Status 仅三值）。刻意**不含**模态：模态期状态栏显示
  模态提示行而非波光，动画 timer 在那里只烧周期不产画面。
- **续排/停止**：active 时 coordinator 保持至多一个在飞 66ms 帧任务（到期 publish VIEW → 下一批
  `processUpdates` 再判 demand 续排）；`updateAnimationDemand(false)` 立即 cancel 在飞帧
  ——状态消失即停，空闲零 timer。
- **`ANIMATION_FRAME_DELAY=66ms` 与旧 drain 周期同值**：波光/压缩条的视觉节奏不变
  （`StatusBar.shimmer` 的 `animTick/2` 帧数换算保持原样）。
- **animTick 语义**：每批自增一次（既有行为）；忙态下批由动画帧 timer 驱动 → 波光恢复动态；
  空闲无批 → 计数静止。不改变任何文案/样式计算。

### 2.3 IME 光标带修复的按需后续帧（§10.4 / brief Step 4）

三层接线（brief Interfaces 要求 `InlineDisplay.needsFollowUpFrame()`，runner 内部调度、不向
CodeTuiView 暴露 renderer 所有权）：

1. **`InlineDisplay.needsFollowUpFrame()`**：`return cursorBandRepairFramesLeft > 0;` ——与光标带
   修复窗口**同源同寿命**（窗口计数在 `render()` 内逐帧自减，`appendFramePatch` 内带内变更重新武装）。
2. **`InlineViewport`（新 shadow，同包同名覆盖类）**：上游 0.4.0 逐字拷贝 + 一个
   `needsFollowUpFrame()` 转发方法。shadow 机制与既有 `InlineTuiRunner` 完全一致
   （patch 模块 jar 经 Class-Path 顺序优先加载）。为什么 shadow 而不是反射：上游类是包私有
   final，补帧判断在**每次 draw 之后**执行，反射逐帧取字段既慢又脆（字段名漂移 = IME 修复窗口
   被无声砍短）。
3. **`InlineTuiRunner.drawAndMaybeScheduleImeFollowUp(Consumer<Frame>)`**：替换全部 4 个
   `viewport.draw(...)` 调用点（run() 初始帧 / ResizeEvent 后 / 事件重绘 / processUiWake 合并重绘）：

```java
viewport.draw(renderFunction);
if (!viewport.needsFollowUpFrame()) return;            // 绝大多数帧走这里：零开销
if (imeFollowUpScheduled.compareAndSet(false, true)) { // CAS：至多一个在飞
    scheduler.schedule(() -> {
        imeFollowUpScheduled.set(false);
        if (running.get()) requestRender();            // 合并请求，渲染仍在渲染线程
    }, IME_FOLLOW_UP_DELAY_MS /*100*/, MILLISECONDS);
}
```

- **一次性**（`scheduler.schedule`，非 `scheduleAtFixedRate`）：每次到期后由下一次 draw 的
  窗口状态决定是否再排——demand 驱动，窗口归零即停止。
- **~100ms 节拍**：沿用原视觉帧长（code-tui 旧 tickRate=100ms，见 `InlineDisplay`
  `CURSOR_BAND_REPAIR_FRAMES=8` 的注释「窗口时长 = 帧数 × 当前帧长」）；8 帧 ≈ 800ms 与重构前同量级。
- **不改变 diff/IL-DL/无 EL/CJK 行为**：只新增「什么时候再画一帧」，帧内容渲染路径零改动
  （既有 `InlineDisplayDiffTest` 全部原样通过）。
- **不暴露 renderer 所有权**：follow-up 走既有 `requestRender()` 合并口，View/应用层无感知。
- 公开的 `draw(Consumer<Frame>)` API（外部直画口）未接 follow-up——生产 code-tui 不经此口
  （全部经 requestUiUpdate/requestRender），保持外部 API 语义最小改动。

### 2.4 resize settle（Task 7 已完成，本任务验证）

- 132ms generation 替换已由 Task 7 落地（`onWidthChanged` → `coordinator.scheduleResizeSettle`），
  本次零改动；coordinator 侧「替换后旧 action 不执行」已有
  `UiUpdateCoordinatorTest.replacingResizeSettleSuppressesStaleAction` 钉。
- 新增 View 侧链路测试：`resize_settleGenerationReplacementAndCursorParkReset` ——立即钉顶、
  静默窗口内后续变化保持钉顶、最新一代到期执行，且**测试态 runner()==null 时 `replayAfterResize`
  直接降级返回（即「settle 失败」路径），`finally` 仍把 `parkCursorAtTop` 复位**（IME 光标锚点
  不永久钉边框）。
- 新增 `onWidthChangedForTest()` seam（包私有）：生产由 ResizeEvent global handler 触发，
  测试态无事件循环，直调同一方法体。

### 2.5 coordinator 诊断口

`hasPendingPreview()` / `hasPendingAnimation()`（与既有 `hasPendingContinuation()` 同构，
共用 `hasPendingTimer` 私有助手）；View 侧透传 seam
`hasPendingPreviewScheduledForTest()` / `hasPendingAnimationFrameForTest()` / `animTickForTest()`。

---

## 3. 测试

### 3.1 TDD 过程

**Step 1–2（红）**：先写全部新用例，对 HEAD（Task 7 完成态）跑：

```
mvn -pl springai-tamboui-inline-patch \
  -Dtest=InlineDisplayDiffTest,InlineDisplayBaselineTest,InlineTuiRunnerEventDrivenTest test
→ COMPILATION ERROR：找不到符号 方法 needsFollowUpFrame()（×4 处）

mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiViewEventWiringTest,CodeTuiViewCursorParkTest,CodeTuiViewThinkingSettingsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ 同上编译失败（经 -am 连带 patch 模块先红）；缺 hasPendingPreviewScheduledForTest/
   hasPendingAnimationFrameForTest/animTickForTest/onWidthChangedForTest 等 seam
```

红点符合预期：Task 7 完成态「animationActive 透传 false / preview 无主动到期调度 / runner 无补帧」，
新测试所依赖的行为与 seam 均不存在。

**Step 3–5（绿）**：实现后逐步转绿。中途两处测试自身缺陷的修正（不是放松断言）：

1. `InlineTuiRunnerEventDrivenTest` 初版用「每帧切换内容」的渲染器——那会**每帧重新武装**窗口
   （真实 diff 触及光标带），永远不静止。改为「一次编辑帧武装 → 立即冻结内容」，后续帧只剩
   窗口内的 band 重申，耗尽即静止（`awaitQuietFrames` 等帧流稳定 + 显式静止窗口断言）。
2. `preview_tokensInsideThrottleWindowProduceSinglePendingWake` 初版（a）不跑 render——生产中
   批后必然一次合并绘制（`lastPreviewAtNanos` 在 render 内前进），不跑 render 窗口永远 ZERO，
   不是生产形态：改为每消费步后 `ViewScreen.of(v)`（等价批后 render）；（b）批次上界初版取 5，
   忽略了**回合仍在 THINKING 时 §10.3 的 66ms 动画帧是合法批次**（200ms 窗口 ≈3 帧），
   放宽到 12（一 token 一批 = 50+，自驱动循环 = 40，均远超此界，回归仍会红）。

### 3.2 命令与精确结果

brief Step 2（红，已在上面记录）。

brief Step 5（绿）：

```
mvn -pl springai-tamboui-inline-patch test
→ Tests run: 45, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,ContextUsage*Test,DrainBurstCapTest,StrictOutputFairnessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 273, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

brief Step 2 命令集（含 CursorPark / ThinkingSettings）：

```
mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiViewEventWiringTest,CodeTuiViewCursorParkTest,CodeTuiViewThinkingSettingsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 40, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
（分解：EventWiring 29 / CursorPark 4 / ThinkingSettings 9 内含于 40 总数，含 patch 模块 45 经 -am）
```

补充 UiUpdateCoordinatorTest（coordinator 侧回归）：

```
mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,ContextUsage*Test,DrainBurstCapTest,StrictOutputFairnessTest,UiUpdateCoordinatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 290, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS（×3 连跑全绿）
```

**完整回归**（设计 §15.6）：

```
mvn -pl springai-code-tui -am test
→ Tests run: 1809, Failures: 0, Errors: 0, Skipped: 10 — BUILD SUCCESS
（10 个 skip 为既有（Task 7 报告已记录），非本任务引入；含上游 -am 模块共 1854 条断言全绿）
```

**稳定性复跑**：

```
InlineTuiRunnerEventDrivenTest + InlineDisplayDiffTest ×3 → 32/32 全绿
CodeTuiViewEventWiringTest ×3                                 → 29/29 全绿
（第一版 preview 测试在全量回归中暴露过 1 次时序 flaky（上界 5 vs 实际 6，动画帧计入），
 按上述理由修正为 12 后 ×3 全绿）
```

### 3.3 新测试覆盖对照 brief Step 1 清单

| brief 要求 | 用例 |
|---|---|
| first streaming tail visibility | `preview_firstStreamingTailIsImmediatelyVisible` |
| multiple tokens inside 150ms producing one pending preview wake | `preview_tokensInsideThrottleWindowProduceSinglePendingWake`（合并语义 = coordinator 每类一在飞 + 批次数有界） |
| immediate clear when tail becomes empty | `preview_emptyTailBypassesThrottleImmediately` |
| no preview task after turn completion | `preview_noTaskAfterTurnCompletion` |
| resize immediately renders and only latest settle generation replays | `resize_settleGenerationReplacementAndCursorParkReset` + 既有 `UiUpdateCoordinatorTest.replacingResizeSettleSuppressesStaleAction` |
| `parkCursorAtTop` always resets after settle failure | `resize_settleGenerationReplacementAndCursorParkReset`（测试态 runner==null 即失败路径，断言 finally 复位）+ `resize_settleDoesNotDisturbEventDrivenBatches` |
| animation continues only in thinking/tool/compacting/running-task states | `animation_demandDrivenOnlyWhileBusyStatesExist`（四态逐一 + 消失即停 + 空闲无 timer） |
| static idle has no timer | `idle_noSpontaneousBatchesOverObservationWindow`（400ms 观测窗零自发批次 + 三类 timer 全无）+ `idleCoordinator_hasNoScheduledTimer`（既有）+ `idleRunnerWithoutCursorBandActivityDoesNotRenderFollowUps`（runner 层） |
| IME cursor-band frames complete on demand then return to zero output | `InlineDisplayDiffTest.needsFollowUpFrameTracksCursorBandRepairWindow`（信号源同源同寿命）+ `InlineTuiRunnerEventDrivenTest.cursorBandRepairArmsDemandDrivenFollowUpFramesThenStops`（8 帧补齐后静止） |

另加 `animation_framesAdvanceAnimTick`（波光恢复动态的驱动量钉）。

---

## 4. Commit

```
9317a29 refactor(code-tui): schedule visual updates only on demand
 8 files changed, 770 insertions(+), 23 deletions(-)
```

提交范围与 brief Step 6 一致（patch 模块 main+test / code-tui ui 包 main+test）。

---

## 5. 自审

- **无永久周期任务**：新增调度全部为 `scheduler.schedule` 一次性（IME 补帧）或 coordinator 的
  one-shot 槽（preview/animation）；「源码扫描钉」测试（`assertNoPeriodicSchedulingTokens`）通过——
  CodeTuiView 生产代码无 `scheduleRepeating`/`scheduleAtFixedRate`/`.tickRate(`/`.onTick(`
  （仅注释中提及，扫描会剥注释）。patch 模块的 `InlineTuiRunner` 既有 `scheduleAtFixedRate`
  （tick 开启时的上游代码路径）未动，code-tui `noTick()` 下不可达——属 Task 1/7 既定形态。
- **动画 demand 驱动、idle 停**：`animation_demandDrivenOnlyWhileBusyStatesExist` 逐态钉 +
  `idle_noSpontaneousBatchesOverObservationWindow` 观测窗钉。
- **视觉语义不变**：`StatusBar`（shimmer/compacting 纯函数）零改动；`ANIMATION_FRAME_DELAY=66ms`
  与旧 drain 周期同值；`animTick` 仍是「每批 +1」——变的只是「谁产生批」。
- **resize 重放内容不变**：`replayAfterResize`/`ScreenCleaner`/`scrollTail` 零改动。
- **IME 修复能力不回退**：`needsFollowUpFrame` 与窗口计数同源（一个 int 字段），帧长 ~100ms×8帧
  ≈ 800ms 与重构前同量级；既有 `InlineDisplayDiffTest` 全部（23 条，含 CJK continuation、
  IL/DL、print batch、无 EL）原样通过。
- **线程纪律**：runner 补帧回调（scheduler 线程）只 `requestRender()`（合并请求），不触碰渲染；
  coordinator timer 回调只 publish dirty bits（既有纪律）。
- **stop 语义**：coordinator.stop() 已取消 preview/animation timer（Task 3 既有）；runner 补帧
  回调有 `running.get()` 守卫，close 后 no-op。

## 6. Concerns

1. **（低）`InlineTuiRunner.draw(Consumer<Frame>)` 公开直画口未接 follow-up**：生产 code-tui 不经
   此口（全走 requestUiUpdate/requestRender），但若未来有外部调用者用它直画，armed 窗口内的
   补帧不会发生（IME 修复窗口在该路径下静默缩短）。如需要可后续在该口也接
   `drawAndMaybeScheduleImeFollowUp`。
2. **（低）`previewRemainingDelay` 依赖 render 前进 `lastPreviewAtNanos`**：生产「批后必有合并
   绘制」成立；纯批消费而无绘制的极端（理论）下窗口会退化为 ZERO 延迟重排（多一次到期，不丢
   内容）。若未来出现「批后不绘制的路径」需回看此处。
3. **（信息）animationDemandActive 刻意不含模态**：模态期状态栏显示模态提示行（非波光），
   不为其保留 timer；与旧 tick 世界相比是**减少**无谓唤醒，非功能回退（若未来模态期需要动画
   ——如等待指示——需把 `state.hasModal()` 加入判定）。
4. **（信息）IME 补帧测试的帧数断言为 8..10**：允许 ±1 次重武装（编辑帧与 latch 的一次竞态），
   而非精确 8——精确断言对调度时序过脆；「有界 + 之后静止」是本测试真正要钉的不变量。
5. **（范围外）PTY 冒烟与 Terminal.app 人工验收未执行**：属设计 §16 验收阶段（含波光实机观感、
   IME 预编辑/取消、resize 拖拽重放），本任务按 brief 只跑 Maven 测试。

---

## 7. Fix round（2026-09-01）

### 7.1 修复摘要与残留测试取舍

- **C-1**：`computeFollowUpFlags()` 的 preview demand 改为
  `!curTail.isEmpty() && !curTail.equals(lastPreviewedTail)`。只有存在尚未被 render 采纳的残行内容才
  `schedulePreview`；静止且已采纳的非空残行停止 preview 链，下一真 token 由 OUTPUT 事件重启。
  同步修正批尾与 `previewRemainingDelay()` javadoc，明确 ZERO 只服务未采纳内容，不允许静止态自续排。
- **残留测试取舍**：保留上个实施者留下的 `preview_staticTailDoesNotSelfRescheduleHotLoop` 主体，因为其
  420ms 长观测窗与批次上界能准确抓住 ZERO 热循环；删除临时 `DEBUG` 测试。原前置用
  `ViewScreen.contains` 读取 styled preview，在测试 renderer 中错误失败，改为直接调用真实采纳点
  `renderForTest()`。修正后在旧实现上观察到 **84 个新增批次**，如期红于 `extra <= 10`；生产修复后转绿。
- **M-1**：从 `UiUpdateCoordinator.UpdateResult` 删除无消费者的 `previewPending` 字段，构造点、测试与
  coordinator/View javadoc 全部同步。preview 调度继续由 View 直调 `schedulePreview`，保持单一所有权。
- **M-3**：原 `preview_tokensInsideThrottleWindowProduceSinglePendingWake` 改名为
  `preview_tokensInsideThrottleWindowProduceBoundedFollowUpBatches`，断言真实 `processedBatches`，上界由
  12 收紧为 10，并新增“最新残行采纳后无 preview timer”断言。
- **M-4**：runner 测试注释由“允许 8 或 9”统一为与断言一致的 `8..10`。

### 7.2 测试命令与精确结果

```text
mvn -f <worktree>/pom.xml -pl springai-code-tui -am \
  -Dtest=CodeTuiViewEventWiringTest -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -f <worktree>/pom.xml -pl springai-code-tui -am \
  -Dtest=UiUpdateCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -f <worktree>/pom.xml -pl springai-tamboui-inline-patch test
→ Tests run: 45, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  （stderr 的 `expected test failure` 为 actionFailureDoesNotKillFollowingActions 故意注入并被 runner 捕获）

mvn -f <worktree>/pom.xml -pl springai-code-tui -am test
→ patch: 45；code-tui: 1810；合计 Tests run: 1855,
  Failures: 0, Errors: 0, Skipped: 10 — BUILD SUCCESS
```

### 7.3 Commit

`3ee16f0` — `fix(code-tui): stop static preview rescheduling`

### 7.4 Concerns

- 未执行范围外的 PTY / Terminal.app 人工验收；Maven 指定与全模块回归均通过。

---

## 8. Fix round 2（2026-09-01）

### 8.1 修复摘要

- **Important I-1**：修正 `preview_staticTailDoesNotSelfRescheduleHotLoop` 末段的新 token 重启断言。
  420ms 观测后节流窗口已过，生产按预期调用 `schedulePreview(Duration.ZERO)`；该 future 可以在测试线程
  检查前完成并发布 VIEW dirty bits，因此不再错误要求 future 必须仍为 pending。
- 新断言接受两种等价且完整的生产链路状态：preview future 尚在飞，**或** ZERO future 已完成且
  coordinator 中已有待消费 dirty bits。随后仍断言新内容当帧可见，确保 token 没有丢失。
- 已验证回归钉有效：临时恢复旧生产条件（只按“残行非空”续排）后，本测试稳定红于静止态热循环
  批次上界（实际新增 85 批）；恢复正确生产实现后转绿。

### 8.2 测试结果

```text
CodeTuiViewEventWiringTest ×5
→ 每次 Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

UiUpdateCoordinatorTest
→ Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -f <worktree>/pom.xml -pl springai-code-tui -am test
→ patch: 45；code-tui: 1810；合计 Tests run: 1855,
  Failures: 0, Errors: 0, Skipped: 10 — BUILD SUCCESS
```

### 8.3 Commit

本节所在提交 — `test(code-tui): stabilize zero-delay preview restart`

### 8.4 Concerns

- 无新增 concern；仍未执行范围外的 PTY / Terminal.app 人工验收。

---

## 9. Fix round 3（2026-09-01）

### 9.1 修复摘要

- 重做 `preview_staticTailDoesNotSelfRescheduleHotLoop` 的新-token 重启段，不再断言
  `hasPendingPreview || pendingDirtyBits != 0` 这类 ZERO future/dirty bits 瞬时状态，也不再用
  `ViewScreen.of(v)` 的直接 render 作为消费证明。
- 新增 package-private seams：`lastPreviewedTailForTest()` 只读真实采纳字段；
  `makePreviewImmediatelyDueForTest()` 将 `lastPreviewAtNanos` 移到足够早，使下一次未采纳残行确定走
  ZERO delay；`runPendingUiUpdatesAndRenderForTest()` 按生产 runner 的 action→draw 顺序逐批驱动。
- 发 token 前记录 batch/tail；发 token 后在 1s 失败 deadline 内循环驱动 coordinator 队列，最终断言：
  producer 批与 ZERO preview wake 批均被消费（batch 至少 +2，因此也满足“> before”）、
  `lastPreviewedTail` 等于预期完整新残行、preview future 为 false、dirty bits 为 0。
- **C-1 的 420ms 长观测钉保持原样**：84×5ms、`extra <= 10`，没有缩短或放宽。

### 9.2 RED / GREEN 证据

- RED：临时把生产 demand 从
  `!curTail.isEmpty() && !curTail.equals(lastPreviewedTail)` 破坏为 `!curTail.isEmpty()`，运行单个 C-1
  测试稳定红于 420ms 长观测上界：实际新增 **104890** 批（`extra <= 10` 失败）。随后立即恢复生产条件。
- GREEN：恢复生产修复后，单个 C-1 生命周期测试通过；完整 EventWiringTest 与全模块回归通过。

### 9.3 测试结果

```text
CodeTuiViewEventWiringTest ×5
→ 5/5 BUILD SUCCESS；每次 Tests run: 30, Failures: 0, Errors: 0, Skipped: 0

mvn -f <worktree>/pom.xml -pl springai-code-tui -am test
→ patch: 45；code-tui: 1810；合计 Tests run: 1855,
  Failures: 0, Errors: 0, Skipped: 10 — BUILD SUCCESS
```

### 9.4 Commit

本节所在提交 — `test(code-tui): prove preview wake lifecycle`

### 9.5 Concerns

- 无新增 concern；仍未执行范围外的 PTY / Terminal.app 人工验收。
