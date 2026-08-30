# Task 3 实施报告

## 状态

DONE

## 修改文件

- 新建：`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinator.java`
- 新建（测试）：`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiDirtyTest.java`
- 新建（测试）：`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinatorTest.java`

未接 View、未改 View、未改 `ConversationState`/`InlineTuiRunner`（只消费 Task 1 已有接口）。

## 实现摘要

`UiUpdateCoordinator implements UiChangeListener, AutoCloseable`，精确实现 brief 的接口契约
（`Lifecycle`、`UpdateProcessor`、`UpdateResult`（含 `idle()`）、全部公开方法签名逐字一致）。

### 生产者协议（§6.2）

- `onUiChanged(bits)`：非零位先 `dirtyBits.accumulateAndGet(bits, OR)`，再递增 coordinator
  **自持全局** `generation.incrementAndGet()`（不读取、不比较任何来源局部版本），然后
  `maybeSchedule()`。
- `maybeSchedule()`：只有把 `scheduled` 从 false CAS 到 true 的赢家调用
  `uiUpdateSink.accept(this::runBatch)`（生产构造函数下即 `runner.requestUiUpdate`）。
  已有 update 待处理时后来的 publish 只合并位，不再堆事件。

### 消费者协议 runBatch（UI 线程，单个 UI action）

1. `dirtyBits.getAndSet(0)` 原子取走本轮位；
2. `processor.processUpdates(bits)` **恰好一次**；
3. 按 `UpdateResult` 安排 demand-driven follow-ups：
   `outputRemaining` → continuation（`Duration.ZERO` 立即续批，批间交还事件循环）；
   `animationActive` → 按最近声明的帧延迟续排下一帧；
4. `finally` 中 `scheduled.set(false)`（processor 抛异常也保证释放）；
5. 复查 `dirtyBits.get() != 0` → 必要时重新取得调度权入队下一批，关闭
   "消费者清 scheduled 与生产者发通知交错" 的丢唤醒窗口。

绝不在单个 UI action 内循环到空。processor 异常照原样上抛，交由
`InlineTuiRunner` 既有 Throwable 防护记录并保持事件循环存活。

### timer 纪律（§10）

- 四类各自一个槽（`AtomicReference<ScheduledFuture<?>>`；resize 用
  `record ResizeSettleHandle(generation, uiAction, future)`）：continuation / preview /
  animation / resize，每类至多一个在飞 generation；
- timer 回调只 `publishFromTimer(bits)`（进入 `onUiChanged`）或把 `uiAction` 经
  `requestUiUpdate` 转入 UI 线程，**绝不在 timer 线程触碰 View 状态**；
- 新 `scheduleResizeSettle` 在 `resizeLock` 下以新 generation 整体替换旧 handle 并
  cancel 旧 future；`ResizeSettleTask` 到期时二次比对 generation，不匹配即 no-op——
  双保险保证**旧 action 永不执行**；
- preview 节流窗口内重复调度保持首个到期（§10.1 窗口语义）；
- `updateAnimationDemand(false, …)` 立即取消当前帧；`animationActive` 链由 runBatch 续排；
- `stop()`：RUNNING→STOPPING→取消全部四类 timer→STOPPED；此后 publish、迟到回调、
  再调度全部 no-op。`close()` 等价 `stop()`。start/stop/close 幂等。

### 构造函数

- 生产构造：`UiUpdateCoordinator(InlineTuiRunner, ScheduledExecutorService, UpdateProcessor)`
  —— 与 brief 签名逐字一致，委托 `runner::requestUiUpdate`；
- 接缝构造：`UiUpdateCoordinator(Consumer<Runnable>, ScheduledExecutorService, UpdateProcessor)`
  —— public，brief Step 1 明确要求 "a runner seam"（`InlineTuiRunner` 是 final 且只能经
  终端 Backend 工厂构造，无法在 JVM 单测中确定性地驱动），行为与生产构造完全一致。

## TDD 与测试结果

### RED

命令：

```bash
mvn -pl springai-code-tui -am \
  -Dtest=UiDirtyTest,UiUpdateCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

精确结果：`BUILD FAILURE`，测试编译报 `找不到符号: 类 UiUpdateCoordinator`（18 处），
符合预期 RED（缺类导致编译失败）。

说明：第一次 RED 运行先暴露了测试草稿自身的两条语法问题（一处非法语句、一处
`boolean || lambda`），修正后才得到"缺类"这一预期 RED 原因。全程未运行过主工作区 pom。

### GREEN：聚焦测试

命令同上。

精确结果：`BUILD SUCCESS`；`Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`
（UiDirtyTest 3 + UiUpdateCoordinatorTest 15）。

覆盖与 brief Step 1 清单一一对应：

| brief 要求 | 测试 |
|---|---|
| 1,000 并发 `onUiChanged(OUTPUT)` 产生有界调度 | `thousandConcurrentPublishesAreCoalescedIntoBoundedWork`（未执行任何批时投递数 == 1） |
| `OUTPUT \| VIEW \| CONTROL` 全部送达 | `combinedBitsAreAllDeliveredInOneBatch`（单批合并） |
| 清 scheduled 与复查之间 publish → 第二批 | `publishBetweenClearAndRecheckProducesSecondBatch`（latch 卡住 processor，批执行期间 publish VIEW，断言复查补第二批且位不丢） |
| `outputRemaining=true` 无生产者也 continuation | `outputRemainingSchedulesContinuationWithoutProducerEvent`（两连批后排空到 idle） |
| preview 单 generation | `previewKeepsSingleGeneration`（5 次调度 1 个到期批） |
| animation 单 generation + demand 驱动 | `animationFramesAreDemandDriven` / `animationStopsWhenDemandDisappears` |
| resize 替换后旧 action 不执行 | `replacingResizeSettleSuppressesStaleAction` |
| stop 取消 timer、迟到回调 no-op | `stopCancelsTimersAndLateCallbacksAreNoOps` |
| processor 失败不永久持 scheduled | `processorFailureReleasesScheduled`（同时断言异常原样上抛给 runner 防护、后续可再调度） |
| 生命周期 | `lifecycleTransitions` / `publishAndContinuationBeforeStartAreNoOps` / `closeDelegatesToStop` / `updateResultIdleIsAllFalse` |

### Task 2 结转：8 线程 × 500 token 压测

`eightThreadsFiveHundredTokenNotificationsKeepSchedulingBounded`，双场景：

1. **内联投递**（每个 publish 恰逢 scheduled==false 空窗的最坏情形）：
   断言 `1 ≤ delivered ≤ 4000`（结构上界）且位合并无丢失、无残留位；
2. **滞后投递**（UI 线程不消费——真实 runner 场景）：
   断言 **4000 次通知只产生 1 个已调度 update**——调度上界与通知数无关，
   不随通知数线性增长（这是与 Task 2 `concurrentTokens_noException_flushToOnePendingLine`
   的 8×500 形状对应的复刻）。

### 稳定性

- 聚焦测试连跑 5 次：5/5 `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`；
- `UiUpdateCoordinatorTest` 单独连跑 10 次：10/10 `Tests run: 15, Failures: 0`。

### 模块回归

命令：

```bash
mvn -pl springai-code-tui -am test
```

精确结果：`BUILD SUCCESS`；`Tests run: 1700, Failures: 0, Errors: 0, Skipped: 10`。

基线对照（stash 我的改动后在 HEAD `caece5a` 上重跑同命令）：
`Tests run: 1682, Failures: 0, Errors: 0, Skipped: 10` ——
本任务净增 18 个测试，10 个 skip 为存量（与本任务无关），无任何既有测试被删除或降断言。

### 静态差异检查

```bash
git diff --check
```

精确结果：退出码 0，无输出。

## 提交

- Commit: `d774581`
- Message: `feat(code-tui): add coalesced UI update coordinator`
- 文件：3 files changed, 1042 insertions(+)（main 1 + test 2，均为新建）

## 自审（对照关键约束）

- **coordinator 自持全局 generation，不比较 source-local 版本** ✔ —— `generation` 只增不比，
  没有任何对 `UiChangeSource.uiVersion()` 的读取；
- **publish 原子 OR bits；只有 CAS 赢家调 runner** ✔ —— `accumulateAndGet(…, OR)` +
  `scheduled.compareAndSet(false,true)` 唯一投递点；
- **runBatch 顺序** ✔ —— 取位 → processor 一次 → follow-ups → `finally` 清 scheduled →
  复查位再入队，顺序与 brief 逐条一致；
- **每类 timer 至多一个在飞 generation** ✔ —— 四个独立槽，`scheduleOneShot` 遇未完成
  同类任务直接保持现状；resize 新 generation 替换旧 + cancel + 到期二次比对；
- **timer 线程只 publish / 请求 UI action** ✔ —— 回调体只有 `publishFromTimer` 和
  `uiUpdateSink.accept(uiAction)`；
- **stop 后一切 no-op；stop 取消所有 timer** ✔ —— 生命周期门 + 四类 cancel；
- **processor 异常不永久卡 scheduled** ✔ —— `finally` 释放 + 测试钉死；
- **单个 UI action 内绝不循环到空** ✔ —— runBatch 无循环，follow-up 全部走一次性 timer；
- **不接 View、不改 View** ✔ —— `CodeTuiView.java`、`ConversationState.java` 零改动；
- **TDD、聚焦提交** ✔ —— RED（缺类编译失败）→ GREEN → 回归 → 单一 commit 只含本任务文件。

## Concerns

1. **公共接缝构造函数**（`Consumer<Runnable>` 版）是为满足 brief Step 1 的 "runner seam"
   要求而新增的 public API：`InlineTuiRunner` 为 final 且只能经真实终端 Backend 工厂构造，
   无法在 JVM 单测中确定驱动。它与生产构造行为完全一致（后者直接委托）。Task 7 接线时
   应使用 `InlineTuiRunner` 版本，避免装配层误用接缝。
2. **`UpdateResult.previewPending` / `contextUsageDirty` 本任务只透传不消费**：preview 节流
   由 Task 8 接管，context refresh 由 Task 6 的 `ContextUsageRefreshController` 接管；
   这是计划中的分工，不是遗漏。
3. **continuation 批间延迟当前为 `Duration.ZERO`**（立即续批，公平性由
   `requestUiUpdate` 的事件队列批边界保证）；Task 5（严格物理行预算）落地时若需要
   显式批间退避，`scheduleOneShot(continuationFuture, Duration.ZERO, …)` 一处即可调。
4. **动画帧延迟默认 66ms**（与现状动画节拍一致）由 `updateAnimationDemand` 最近一次
   声明值驱动；Task 8 会传真实节拍。若 View 从不调用 `updateAnimationDemand(true, …)`
   而只依赖 `UpdateResult.animationActive`，将沿用默认值。
