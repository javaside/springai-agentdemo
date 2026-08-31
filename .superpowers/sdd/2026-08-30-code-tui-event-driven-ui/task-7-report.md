# Task 7 Report: Switch CodeTuiView to event-driven processing

**状态**: 完成
**Commit**: `144d4b5` — `refactor(code-tui): drive agent UI updates by events`
**Worktree**: `/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui`（branch `refactor/event-driven-ui`）

---

## 1. 文件

| 文件 | 变更 |
|---|---|
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java` | 修改（+409/−71 核心切换） |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewEventWiringTest.java` | 新增（17 用例） |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java` | **未改**（View 自绑，装配无需变化；brief 允许省略） |
| 既有 `CodeTuiView*Test` | **未改**（`tickForTest()` 兼容别名使全部旧断言原样通过——旧测试只用 `tickForTest`/`feedKeyForTest` 等 seam，未直连 `drain`） |

---

## 2. 实现

### 2.1 删除清单（周期任务全灭）

| 删除项 | 替代 |
|---|---|
| `configure().tickRate(Duration.ofMillis(100))` | `.noTick()`（`InlineTuiConfig.Builder` 原生支持；`tickRate=null` → `ticksEnabled()=false` → `InlineTuiRunner` 构造里**不** `scheduleAtFixedRate`，scheduler 线程空闲） |
| `onStart()` 的 `runner().scheduleRepeating(() -> runOnRenderThread(this::drain), 66ms)` | coordinator 合并投递（见 2.3） |
| `drain()` / `drainInsideBatch()`（66ms 轮询体） | `processUpdates(int)` 有界批 |
| `if (animTick % 30 == 0) contextUsageExecutor.execute(ctxUsage::refresh)`（~2s 周期刷） | Task 6 `ContextUsageRefreshController`：事件标脏 + 500ms 防抖 + 单飞 |
| **每帧宽度轮询**（`polledWidth = terminalWidth()` + `resizeSettle.onTick()` 每帧一喂） | 见 2.5 的存废判断 |
| `animTick++` 的固定帧推进 | 每批推进一次（状态变化事件到达时重绘）；动画帧调度归 Task 8 |

### 2.2 构造期接线（设计 §13.1 启动顺序 1–3）

构造函数尾部：

```java
this.updateScheduler = Executors.newSingleThreadScheduledExecutor(...); // "ui-update-scheduler" daemon
Consumer<Runnable> uiSink = action -> {
    var r = runner();
    if (r != null) r.tuiRunner().requestUiUpdate(action);   // 生产（Task 1 接口）
    else pendingUiUpdatesForTest.offer(action);              // 测试态受控队列
};
this.coordinator = new UiUpdateCoordinator(uiSink, updateScheduler, this::processUpdates);
this.ctxUsageController = new ContextUsageRefreshController(
        ctxUsage, contextUsageExecutor, updateScheduler,
        CONTEXT_USAGE_DEBOUNCE /*500ms*/,
        () -> coordinator.onUiChanged(UiDirty.VIEW));
bindChangeSources();
```

- **必须遵守的接口约束**：coordinator 用的是「`Consumer<Runnable>` 接缝构造」直接包 `requestUiUpdate`，而不是 `UiUpdateCoordinator(InlineTuiRunner, …)` 生产构造——因为构造期 `runner()==null`（`InlineApp.run()` 才赋值），生产构造在构造期会把 sink 定死为 null。该接缝与生产构造**只差 sink 目标，合并/调度行为逐字一致**（Task 3 结转的语义），且一旦 run()，后续投递全部走真实 `requestUiUpdate`。
- **构造即绑定两路 listener**（`state.setUiChangeListener(coordinator)` + `onSubmit.setUiChangeListener(coordinator)`）：这是「View 启动时必须构造后立刻绑定才能听到 McpRegistry『connecting 进入』通知」的落点——`CodeTuiApplication` 在 `view.run()` 之前就可能让 MCP 进入 CONNECTING（`McpRegistry.init` 在 agent 构造前跑），此时通知落进 coordinator 的 dirty bits，`onStart` 后首批（初始 ALL 同步）即消费。
- coordinator 在 `start()` 前的 publish 是 no-op（Task 3 生命周期语义）——启动前写入的**状态**由初始 ALL 同步接住（设计 §13.1「即使绑定前已有状态，初始全量同步也能接住」），不依赖通知本身。
- scheduler **不复用** `runner().scheduler()`：onStop 时 runner 先关、coordinator 还要排空一次性任务；自持 `updateScheduler` 并在停止序列 shutdown。

### 2.3 `onStart()`（设计 §13.1 第 4–5 步）

```java
lastSeenWidth = terminalWidth();
runner().eventRouter().addGlobalHandler(event -> {       // ResizeEvent → onWidthChanged()
    if (event instanceof ResizeEvent re && re.width() > 0 && re.width() != lastSeenWidth) {
        lastSeenWidth = re.width();
        onWidthChanged();
    }
    return EventResult.UNHANDLED;                         // 只旁观（保持既有语义）
});
coordinator.start();
ctxUsageController.markDirty();                           // 启动期显式一次：空会话首刷
runner().runOnRenderThread(() -> { printer.welcome(...); welcomePrinted = true; });
publishInitialAllSync();                                  // publish(UiDirty.ALL) 一次
```

- **欢迎横幅**：保持旧实现同一投递路径（`runOnRenderThread`，onStart 时 RenderThread 标记未打 → 入队，事件循环内两帧之间打印——不抢在首帧绘制前写屏）。
- **初始全量同步**：`coordinator.onUiChanged(UiDirty.ALL)` 一次；生产由 `requestUiUpdate` 在事件循环跑该批，测试态（`runner()==null`）同步执行测试队列。接住 View 启动前的权限提示（`--dangerously-skip-permissions`/plan mode 的 `pushInfo`）、`replayHistory`（-c 恢复）、MCP 早期状态。
- 初始同步是**有界批**：首批吃 pending（队头惰性 + 防插队语义保留——流式完整行只在队列已排空时才取，Task 5 语义），存量较大时由 continuation 后续批接走（测试里以「排空到静止」断言）。

### 2.4 `processUpdates(int dirtyBits)` → `UpdateResult`（brief Step 4 严格顺序）

```
processUpdates(bits) = InlineRenderBatch.open(runner()) 包裹 processUpdatesInsideBatch(bits)

processUpdatesInsideBatch:
  1. lastDirtyBitsForFlag / continuationScheduled=false / processedBatches++ / animTick++
  2. 本批共享预算：batchDeadlineNanos = now + 12ms；batchRowsUsed=0（fix round I-3 语义保留）
  3. pending 转入（≤600 条/批，Task 5 有界转入语义保留）
  4. outputQueue 空时取流式完整行（≤300 条；防新流式行插队语义保留）
  5. drainQueuedOutput(300)（严格物理批 + 共享绝对 deadline）
  6. modal 身份同步（activePermission/activePlan 队首失配退出）
  7. 新模态进入（Ask 畸形降级 cancelTurnFor；Plan 正文同批入队 + 剩余预算续 drain——
     注意补了 batchRowsUsed += ，修复旧实现里计划段行数不记账的小漏洞）
  8. advanceAttention(modalWaiting)（边沿推进，出队路径提前 return 也已覆盖）
  9. !busy() 时：未送达插话 → else queued → else 后台结果（每批至多一条提交；
     自动出队执行点完整重读 busy()/modal/compacting/in-flight）
 10. computeFollowUpFlags()
```

`computeFollowUpFlags()`（第 7 步 flags）：

- `outputRemaining = !outputQueue.isEmpty() || state.hasPendingOutput() || state.hasCompleteStreamingLine()`
  → 为 true 时 `coordinator.scheduleOutputContinuation(Duration.ZERO)`（Task 3 结转：批间延迟 ZERO；排空后自然停止，**绝不在单 action 内循环到空**）。
- `previewPending = 残行非空` —— 本任务只透传标记（Task 8 接管节流调度）。
- `animationActive` —— **本任务透传为 false**（见 2.6）：忙时置 true 会让 coordinator 自续 66ms 帧循环，等价固定 tick 还魂；降级为「状态变化事件到达时重绘」（每批 requestUiUpdate 自带新 animTick），波光暂静止、不崩溃、不死循环。
- `contextUsageDirty` —— 本批 bits 含 `OUTPUT|CONTROL` 时调 `ctxUsageController.markDirty()`（Task 6 接线：事件源挂 markDirty，防抖合并突发）。

### 2.5 resize settle 的存废判断（brief 明确要求说明）

**删除了每帧宽度轮询，保留事件驱动的 settle**，理由：

- **删**：旧实现每个 drain tick 都 `terminalWidth()` 比对一次 + `resizeSettle.onTick()` 喂一拍。事件驱动下没有 tick 可搭车，为它单设周期任务正是本次重构要消灭的东西；而 `InlineTuiRunner` 的 backend `onResize` 回调已主动把 `ResizeEvent` 投进事件队列（Task 1 前 libc/jline 路径就有），我们挂的 global handler 能第一时间看到每次宽度变化——**主动通知已覆盖正常路径**。
- **保留 settle（防抖重放）**：拖拽连发 resize 不能逐事件清屏重放（Windows Terminal 中间帧问题，见原注释），132ms 静默窗口后一次性重放仍是必要语义。实现改为 `coordinator.scheduleResizeSettle(RESIZE_SETTLE_DELAY=132ms, this::settleResizeOnUiThread)`——按需一次性、generation 替换（连发只重放最后一次）、经 `requestUiUpdate` 转 UI 线程执行（timer 线程不触碰 View，§12.1 纪律）。
- **放弃的兜底**：SIGWINCH 内核只留一个 pending、快拖中间档位被合并的场景。主动 `ResizeEvent` 每次宽度**变化**（`!newSize.equals(previous)`，jline backend 判定）都会到达，最后一个宽度必然有事件；丢失的只是中间档位——而 settle 本来就只关心最终宽度，中间档位丢了正好是被合并的对象。残余风险（backend 完全漏发最后一个事件）属 Task 1 层接口契约，若实机（§16 Terminal.app 验收）发现再在 runner 层补，不在 View 恢复轮询。

### 2.6 本地 UI 状态变化 → UI 的接线清单（这些不在 Agent 源里）

统一入口 `publishLocalViewChange()`（`coordinator.onUiChanged(UiDirty.VIEW)` + `requestRenderOnce()`），挂在**按键/粘贴必经之路**：

| 挂点 | 覆盖的本地状态 |
|---|---|
| `InputBox.handleKeyEvent`（HANDLED 分支：`onInputKey` 拦截后） | 选择器开关/高亮移动（/model /skill /mcp /permissions /tasks）、思考设置面板、模态选项移动（ask/perm/plan）、slash 菜单开合与高亮、技能标签挂/摘、附件取消态、历史回溯、Esc 各分支、Enter 提交后的 `/clear`（outputQueue.clear、pendingSkill、lastShownModel 等本地复位）、权限模式切换（Shift+Tab）、Ctrl+X |
| `InputBox.handleKeyEvent`（UNHANDLED → 编辑器分支） | 输入框文本编辑（附件行出现/消失、输入框高度、slash 前缀过滤） |
| `InputBox.handlePasteEvent` | 多行粘贴后的文本/附件行变化 |
| `toggleMcp` 的后台线程 `finally` | `mcpConnecting = null`（后台线程写 volatile 本地状态，旧的「下一帧即看到」没了，必须主动唤醒） |

设计取舍（写进 `publishLocalViewChange` javadoc）：**输入框逐字符文本不经 state 迁移**（保持现状，`ConversationState.currentInput()` 早已是死代码），publish 挂在按键路径而非文本变更——每个按键一次 VIEW 合并（coordinator 把同类 burst 合成一批），避免 token 级事件风暴。

其他非按键的本地变化路径核对：
- `state.setNotice(...)` 全部经 state listener（Task 4 已发 VIEW）✓
- `ctxUsage.report()`（/context）同步打 sink + pushInfo ✓
- `confirmKillTask` 的 `state.markBackgroundKilled` → state listener ✓（Enter 键路径也有本地 publish 双保险）
- `selectModel`/`cyclePermissionMode`/`saveThinkingSettings` 等 handler 侧变化 → 按键路径 publish ✓

### 2.7 `onStop()`（brief Step 5 停止顺序）

```java
coordinator.stop();                    // 1) STOPPING→STOPPED：取消 continuation/preview/animation/resize 全部一次性 timer；后续 publish/迟到回调 no-op
ctxUsageController.stop();             //    防抖 timer 取消；在飞 refresh 允许跑完但静默
state.setUiChangeListener(null);       // 2) 解绑两路变化源 → no-op（数据不丢，只是没人听）
onSubmit.setUiChangeListener(null);
contextUsageExecutor.shutdownNow();    // 3) 关闭自持设施
updateScheduler.shutdownNow();
super.onStop();                        // 4) 既有超类清理（terminal 恢复等）
```

- `/clear` 与退出的后台语义**不动**：`shutdownAndQuit` 仍只调 `onSubmit.shutdownBackground()`（不先 killAll）；MCP 关闭仍归 `CodeTuiApplication` 的 try/finally。
- `stop()` 期间迟到的 Agent 通知进 no-op listener（`UiChangeListener.noop()`），不抛、不堆 dirty bits（有测试钉）。

### 2.8 test seam（brief「Produces test seams」）

```java
UiUpdateCoordinator coordinatorForTest();
UiUpdateCoordinator.UpdateResult processUpdatesForTest(int dirtyBits);
void runPendingUiUpdatesForTest();   // 执行测试队列里已投递的 UI update（等价事件循环跑一批）
void startForTest();                 // 等价 onStart 关键步骤（不真正起 runner）
void stopForTest();                  // 等价 onStop 停止序列
boolean hasContinuationScheduledForTest();
void tickForTest();                  // 兼容别名：跑一批 processUpdates(UiDirty.ALL)，不启动任何周期任务
```

既有 `CodeTuiView*Test` **零改动**通过（旧测试全部经由 `tickForTest`/`feedKeyForTest`/`renderForTest` 等 seam，无人直连已删的 `drain`）——断言未弱化。`drainDeadlinesObservedForTest`/`pendingIntakeCountForTest`（fix round I-3 的回归钉）继续工作，`DrainBudgetSharingTest`/`DrainBurstCapTest`/`StrictOutputFairnessTest` 全绿。

---

## 3. 测试

### 命令与结果（精确）

Step 2（红）——新测试对旧实现编译失败（缺 seam）：
```
mvn -pl springai-code-tui -am -Dtest=CodeTuiViewEventWiringTest -Dsurefire.failIfNoSpecifiedTests=false test
→ COMPILATION ERROR（coordinatorForTest/startForTest/processUpdatesForTest/… 找不到符号）
```

Step 6（绿）：
```
mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,DrainBurstCapTest,StrictOutputFairnessTest,UiUpdateCoordinatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 238, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

全模块回归：
```
mvn -pl springai-code-tui -am test
→ Tests run: 1804, Failures: 0, Errors: 0, Skipped: 10（10 个 skip 为既有，非本任务引入）— BUILD SUCCESS
```

tamboui patch 模块：
```
mvn -pl springai-tamboui-inline-patch -am test
→ Tests run: 42, Failures: 0, Errors: 0 — BUILD SUCCESS
```

稳定性复跑（新测试曾有一处 continuation 时序 flaky，已改为确定性「排空到静止」断言后）：
```
CodeTuiViewEventWiringTest ×8：17/17 全绿
CodeTuiView*Test ×3：212/212 全绿
```

### 新测试覆盖（CodeTuiViewEventWiringTest，17 用例）

- **启动/配置形态**：`configure_disablesTicks`（`assertFalse(config.ticksEnabled())`）、`configure_keepsBindingsAndPaste`（Ctrl+C quit / bracketed paste 不回归）、`startup_...`（`repeatingDrainScheduledForTest()==0` ＝ `assertNoRepeatingDrainScheduled`；欢迎横幅打印；初始 ALL 同步完成并消费启动前 pending/流式完整行）、`idleCoordinator_hasNoScheduledTimer`（空闲 150ms 后无任何 timer 悄悄 publish、无 continuation 在飞）。
- **参数化接线表**（`changeSources_eachWakeCoordinator`，13 类）：pushInfo / assistantToken / toolStarted / toolFinished / todoUpdated / subagentStarted / backgroundStarted / backgroundFinished / notice / enqueue / turnStarted / turnComplete / compaction——每类走**真实事件源**（state 方法），断言 dirty bits 非零 → 一批消费后清零。
- **模态**：Ask/Permission/Plan 三类一批进入；畸形问询一批内移除 + cancel 恰一次 + 回 IDLE。
- **fan-out**：`onSubmit.setUiChangeListener` 恰绑一次且目标是 coordinator 本体（同一实例断言）。
- **控制顺序**：`controlOrder_outputBeforeModalBeforeAttentionBeforeAutoDelivery`（① pending 先消费 ② 模态同批进入 ③ attention→WAITING_USER ④ busy 闸住后台送达；应答 + onTurnComplete 后释放才送达）；`autoDeliveryPriority_...`（插话 → 排队 → 后台，逐批一条）。
- **本地 UI**：/model 打开与高亮移动后主动发布 VIEW。
- **continuation**：5000 行首批 `outputRemaining=true` 且 continuation 已安排；无生产者事件排空到 idle；一行输出排空后 false/无 timer。
- **停止**：coordinator STOPPED 先于超类清理、onSubmit 解绑恰一次、解绑后迟到通知 no-op。
- **兼容别名**：`tickForTest` 仍能进模态且不安排周期任务。
- **resize**：不再有每帧宽度轮询（`widthPollingEveryFrameForTest()==false`）。

---

## 4. Commit

```
144d4b5 refactor(code-tui): drive agent UI updates by events
 2 files changed, 861 insertions(+), 71 deletions(-)
```

同一提交内完成切换：删轮询与接通知（含全部本地 UI 状态的按键路径发布、MCP 后台线程发布）原子落地，无「已删轮询但某变化源未通知」的中间状态。

---

## 5. 自审

1. **线程纪律**：所有 `processUpdates`/终端写/自动出队都在 UI 线程（生产 = `requestUiUpdate` 投递的事件循环线程）。timer 回调只 publish（Task 3/6 类内纪律）。`publishLocalViewChange` 从 mcp-enable 后台线程调用时只做 `onUiChanged`（原子合并）与 `requestRender`（enqueue），不碰 View 状态。✓
2. **队头惰性**：`PhysicalOutputQueue` 使用方式未变（工厂惰性 + 游标消费 + per-tick 共享 deadline），continuation 只是「再来一批」，不破坏队头惰性。`MAX_PENDING_INTAKE_PER_TICK=600`、`MAX_ROWS_PER_DRAIN=300`、`MAX_DRAIN_NANOS=12ms` 全保留。✓
3. **有界性**：单 UI action 绝不循环到空；pending 转入有界；continuation 每类至多一个在飞（Task 3 语义）。✓
4. **丢唤醒**：coordinator `runBatch` 的 finally 复查 + `maybeSchedule`（Task 3）接住「清 scheduled 与生产者 publish 交错」窗口。✓
5. **修复顺手发现的小漏洞**：旧 `drainInsideBatch` 计划模态分支 `drainQueuedOutput(MAX_ROWS_PER_DRAIN - batchRowsUsed)` 的返回值没加回 `batchRowsUsed`（同一 tick 内行数记账偏低）——新实现补 `batchRowsUsed +=`。无行为断言依赖旧值（`DrainBudgetSharingTest` 只钉 deadline 共享），全绿。
6. **不弱化断言**：既有 `CodeTuiView*Test` 文件零改动、全绿；fix round I-3 的观测点继续有效。

## 6. Concerns（给 Task 8 / 验收的已知事项）

1. **动画暂退化为静态**（brief 明确允许）：`animationActive` 本任务透传 `false`，忙时波光/压缩条不逐帧推进，仅状态变化事件到达时重绘（每批 animTick++）。`animationDemandActive()` 已就位（忙/压缩/后台运行/在飞子 agent），Task 8 用它 + `coordinator.updateAnimationDemand(active, ANIMATION_FRAME_DELAY)` 做按需帧调度即可。`ANIMATION_FRAME_DELAY=66ms` 常量已备好但当前未接线（避免编译器 unused 警告问题无——javac 不警告字段）。
2. **预览节流暂为「事件到达即重绘」**：`previewPending` 透传，render 里 150ms 节流逻辑原样保留（每次 render 才判定），Task 8 接 `coordinator.schedulePreview` 做显式节流窗口。
3. **每帧宽度轮询已删**（SIGWINCH 合并兜底，见 2.5 论证）：依赖 backend `onResize` 对「最终宽度」必然发事件。若 §16 Terminal.app/tmux 实机验收发现漏发终值，应在 runner 层（Task 1 接口）补一次性对账，不在 View 恢复轮询。
4. **`ctxUsage` 启动首刷后，长空闲会话状态栏上下文用量是启动值**——语义与旧版一致（旧版也只是 ~2s 一次重算，空闲时数值本就不变）；事件到来即刷新。
5. **`pendingUiUpdatesForTest` 与生产 sink 的二选一**在 `runner()` 变非 null 后自动切到生产路径；若极端情况下 View 在 run() 之后仍有测试态投递残留（不存在此路径：onStart 后 sink 一致走 runner），队列会被 `runPendingUiUpdatesForTest` 空转消费——无泄漏风险。
6. **CodeTuiApplication 未改**：View 构造即自绑，装配顺序（MCP init → agent 构造 → view 构造 → run）无需调整。若未来有人把 View 构造挪到 McpRegistry.init 之前，绑定仍成立（listener 先挂、通知后到）。

---

# Task 7 Fix Round 报告（I-1..I-3 / M-1..M-6）

**状态**: 完成
**Commit**: `68d4797` — `fix(code-tui): tighten Task 7 review findings (I-1..I-3, M-1..M-6)`
**基线**: `144d4b5`（本报告上半部分的原始提交）

## 修复摘要

### I-1 批处理异常：warn + 有界补发重试（选 View 自处理，不上抛）

- **改动**：`processUpdates` 的 `catch (Exception impossible)` 拆分为两类——close 失败仍由 `InlineRenderBatch` 内部以 debug 降级（本来就在该类内兜住）；**处理失败**走新 `handleBatchFailure`：warn 级日志 + 经 coordinator 补发一次 `UiDirty.ALL` 重试批，连续失败计数封顶 `MAX_BATCH_FAILURE_RETRIES=2`（任一整批成功才清零），失败批返回 `UpdateResult.idle()`（不声明 follow-up，防 continuation 盲目重跑）。
- **关键坑（实现中自己踩到并修正）**：失败计数最初在 `processUpdatesInsideBatch` 开头清零——而异常恰恰发生在批内，导致「清零→炸→计数=1→补发→清零→炸…」无限循环（测试挂死，thread dump 定位）。修正为**只在整批成功返回后清零**（`processUpdates` 包装层），这才是「连续失败序列」的正确语义。
- **契约对齐（选择说明）**：coordinator javadoc 改为「processor 异常照原样上抛的契约保留给未防护处理器与测试桩（`processorFailureReleasesScheduled` 继续钉）；生产 processor（CodeTuiView）自行捕获处理」。理由：runBatch 在调 processor 前已 `getAndSet(0)` 取走 dirty bits，上抛给 InlineTuiRunner 防护只留一条日志，本批该做的消费全部丢失且空队列路径无兜底——View 侧自恢复是唯一能让「补发重跑」发生的层。

### I-2 「无周期任务」假钉 → 真钉

- 删除两个恒真断言载体：`repeatingDrainScheduled`（从未递增）与 `widthPollingEveryFrame`（编译期常量 false）字段及 seam。
- **真钉 1（源码扫描）**：测试读 `CodeTuiView.java` 生产源，剥掉字符串/字符字面量与两种注释后断言不含 `scheduleRepeating` / `scheduleAtFixedRate` / `.tickRate(` / `.onTick(`。**已验证会变红**：临时在 onStart 恢复一行 `scheduleRepeating` 后该测试确实失败（消息指向具体符号），随后还原。
- **真钉 2（tickRate 前提）**：`configure_disablesTicks` 补 `assertNull(cfg.tickRate())`——这是 InlineTuiRunner 构造里跳过 `scheduleAtFixedRate` 的判定前提（`ticksEnabled() && tickRate()!=null`），比只断言 `ticksEnabled()` 更接近机制。
- **resize 面**：`resize_keepsEventPathWithoutPerFramePolling` 改为断言源码无 `.onTick(`（每帧喂拍的载体）且无 `new ResizeSettle(`；`tickForTest` 用例补 coordinator 侧无在飞 continuation 的行为断言。

### I-3 continuation 单一调度方 + contextUsageDirty 删除

- **选择：生产路径唯一调度方 = coordinator.runBatch**（View 只声明 `outputRemaining`）。理由：runBatch 已经按 result 排 ZERO 延迟一次性任务；View 侧再排一份是两条并行链，批次翻倍、封顶语义失真。删除了 View 的 `coordinator.scheduleOutputContinuation(ZERO)` 调用与 `continuationScheduled` 镜像 flag；`hasContinuationScheduledForTest()` 改为委托新 coordinator 诊断口 `hasPendingContinuation()`（读 continuation 槽的真实 future）。`scheduleOutputContinuation` 保留为测试直连 seam（UiUpdateCoordinatorTest 继续用）。
- **选择：从 UpdateResult 删除 contextUsageDirty**（3 参 record）。理由：runBatch 从不消费该字段，经它路由是纯死参数；真实接线本就是 View 侧 `computeFollowUpFlags` 里按 dirty bits 直接 `ctxUsageController.markDirty()`（Task 6 契约），保留字段反而诱导「以为 coordinator 会处理」。coordinator/测试同步改为 3 参构造，`updateResultIdleIsAllFalse` 保留三断言并注明删除原因（非弱化：被断言的对象已不存在）。

### M-1..M-6 顺手修复

| 项 | 内容 |
|---|---|
| M-1(a) | 两处「pre-start 通知落进 dirty bits」改为如实描述：NEW 态通知被丢弃，接住启动前状态的是初始 ALL 同步 + render 活读 |
| M-1(b) | 删除「只在 OUTPUT 位或队列未清空时转入」的不存在闸门描述（实际无位闸门，空转无副作用） |
| M-1(c) | `publishLocalViewChange` javadoc 改为「输入框文本也经此路」（与 InputBox 两分支逐键调用的事实一致；合并吸收 token 风暴） |
| M-1(d) | `bindChangeSources` 注明只在构造期调用一次（onStart 不再调，原「幂等再绑定」描述失实） |
| M-1(e) | `initialAllSyncDone` 置位时机如实描述：publish（测试态含同步排空）之后，非「批内」 |
| M-2 | `ResizeSettle` 类整体删除（确认无其他消费方：生产引用只剩本 View 的死字段）；`ResizeSettleTest`（7 用例）一并删；`implementation-map.md` 与 `resize_smoke.py` 文档引用同步更新为 coordinator 一次性 settle 语义 |
| M-5 | `animationDemandActive()` 与 `ANIMATION_FRAME_DELAY` 显式标注 Task 8 预留 seam（含接线点说明） |
| M-6 | `stopForTest` javadoc 写明与生产 onStop 的唯一差异（不调 `super.onStop()`，其余步骤逐字一致）及原因 |

## 测试命令与结果（精确）

```
mvn -pl springai-code-tui -am -Dtest=CodeTuiViewEventWiringTest -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
   （原 17 + 新 3：batchFailure_warnsAndRetriesOnceThenStopsBounded /
     batchFailure_returnsIdleResultAndKeepsLoopAlive /
     continuation_soleSchedulerIsCoordinatorRunBatch；startup/configure/resize/tickForTest 四个既有用例换真钉）

mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,DrainBurstCapTest,StrictOutputFairnessTest,UiUpdateCoordinatorTest,DrainBudgetSharingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 245, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -pl springai-code-tui -am test
→ Tests run: 1800, Failures: 0, Errors: 0, Skipped: 10（10 个 skip 为既有）— BUILD SUCCESS
   （原 1804 = 现 1800 + 删除的 ResizeSettleTest 7 − 新增 3）

mvn -pl springai-tamboui-inline-patch -am test
→ Tests run: 42, Failures: 0, Errors: 0 — BUILD SUCCESS

稳定性复跑（CodeTuiViewEventWiringTest ×3）：20/20 ×3 全绿

钉子有效性验证（人工注入回归）：临时在 onStart 恢复 scheduleRepeating →
  startup_consumesPreStartPendingWithoutPeriodicDrain 立即失败（「生产代码不得包含周期调度符号
  scheduleRepeating」）→ 还原后全绿。
```

## Commit

```
68d4797 fix(code-tui): tighten Task 7 review findings (I-1..I-3, M-1..M-6)
 8 files changed, 404 insertions(+), 233 deletions(-)
```

## Concerns（给 Task 8 / 验收）

1. **失败重试批的 dirtyBits=ALL**：补发用 ALL 而非「原批 bits」，代价是纯 VIEW 批失败后重试批会跑一遍输出空转（无副作用、有界两次）。若未来要精化可把原 bits 传给 handleBatchFailure 再 publish——当前异常路径本就是罕见分支，简单优先。
2. **源码扫描钉依赖文件路径**：`viewSource()` 从模块根（surefire 工作目录）读 `src/main/java/...`，带一级父目录回退兜 IDE 内联运行。若未来重构挪动 CodeTuiView 位置，需同步改测试里的路径。
3. **风暴封顶后需真实事件解锁补发**：连续失败 ≥3 次后 View 停止补发（不锁死事件路径，测试已钉），但该连续失败序列要等一个成功批才清零。持续故障（如状态源持续抛异常）下 UI 表现是「该类事件不再驱动补发」+ warn 日志——这与旧世界 66ms tick 的无限重试相比是刻意取舍。

## Fix round 补记：既有用例的竞态消除（随本修复落地）

复跑中发现既有用例 `outputRemaining_flagDrivesContinuation` 存在竞态（约 1/8 概率失败）：它直连
`processUpdatesForTest(ALL)` 后立即断言 `hasContinuationScheduledForTest()`——旧实现里 View 自排
continuation 时同步置镜像 flag，恒真；I-3 删除镜像 flag、改读 coordinator 真实槽位后，该断言的
成立取决于「初始同步批排的 ZERO 延迟 future 是否恰好还没触发」，成了竞态。修复（非弱化）：

- 直连批只断言**声明位** `r1.outputRemaining()`（View 在单一调度方模型下的全部职责）；
- 「remaining → continuation 已排」改为经**真实链**断言：pushInfo → `runPendingUiUpdatesForTest()`
  （runBatch 收到 remaining 自己排）→ 断言 coordinator 槽在飞；
- 收尾「排空后无残留 timer」改为带等待的确定性判定（让 ZERO 延迟 timer 自然触发完再断言）。

槽位排定的真实链断言另由新用例 `continuation_soleSchedulerIsCoordinatorRunBatch` 全程钉住。
修后 CodeTuiViewEventWiringTest ×6、CodeTuiView*Test 全组 ×3（245/245）全绿。
