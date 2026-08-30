# Task 1 实施报告

## 状态

DONE

## 修改文件

- `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineTuiRunner.java`
- `springai-tamboui-inline-patch/src/test/java/dev/tamboui/tui/InlineTuiRunnerEventDrivenTest.java`

`InlineViewport.java` 无需修改。

## 实现摘要

- 新增线程安全 API：`requestUiUpdate(Runnable)` 与 `requestRender()`。
- 使用 `ConcurrentLinkedQueue<Runnable>`、`uiUpdateQueued` 和 `renderRequested` 合并并发 UI 工作及重绘请求。
- 每个 wake 只消费事件开始时已有的 action 数量，逐个通过既有 `handleThrowable(Throwable)` 隔离失败；批处理中新增的工作会重新取得调度权并排入下一次 wake，不在当前事件中无界循环。
- 每个 wake 批次最多调用一次 `viewport.draw(activeRenderer::render)`；ticks 禁用时主动更新仍可唤醒阻塞事件循环并绘制。
- `activeRenderer` 在初始绘制前发布，并在 `run()` 的 `finally` 中清理。
- `quit()` 在将 `running` 置为 false 后投递一次空 `UiRunnable`，使长 poll timeout 下的阻塞 loop 立即退出。
- 保留现有 `runOnRenderThread`、`runLater` API 和事件循环 Throwable 防护；未引入永久 tick。
- close/quit 后主动更新 API 成为 no-op。

## TDD 与测试结果

### RED

命令：

```bash
mvn -f "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui/pom.xml" \
  -pl springai-tamboui-inline-patch \
  -Dtest=InlineTuiRunnerEventDrivenTest test
```

精确结果：`BUILD FAILURE`，测试编译报告 6 个 `找不到符号`，均为缺少 `requestUiUpdate(...)` / `requestRender()`，符合预期 RED。

说明：第一次未指定 worktree pom 的命令从主工作区执行，因而报告 `No tests matching pattern`；随后使用绝对 worktree pom 重新执行并得到上述预期编译失败。所有后续验证均显式使用隔离 worktree pom。

### GREEN：聚焦测试

命令：

```bash
mvn -f "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui/pom.xml" \
  -pl springai-tamboui-inline-patch \
  -Dtest=InlineTuiRunnerEventDrivenTest test
```

精确结果：`BUILD SUCCESS`；`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

覆盖：禁用 tick 主动唤醒/绘制、1,000 个并发 render 请求合并、action Throwable 后继续、空闲跨至少三个 poll timeout 不绘制、quit 快速唤醒阻塞 loop、close 后 no-op。

异常隔离用例按设计向 STDERR 记录一次预期 `AssertionError: expected test failure`，测试和构建均成功。

### 模块回归

命令：

```bash
mvn -f "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui/pom.xml" \
  -pl springai-tamboui-inline-patch test
```

精确结果：`BUILD SUCCESS`；`Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`。

### 静态差异检查

命令：

```bash
git -C "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui" diff --check
```

精确结果：退出码 0，无输出。

## 提交

- Commit: `daea486`
- Message: `feat(tamboui): add coalesced active UI redraw`

## 自审

- 精确签名与 brief 一致，且只修改 Task 1 runner 和新增测试。
- action 在 draw 前执行；单个 wake 批次只消费起始快照并至多绘制一次。
- `uiUpdateQueued` 在 `finally` 释放，draw 异常不会永久卡住合并标记；外层既有 `UiRunnable` Throwable 防护仍会保持 loop 存活。
- 清标记后重新检查 action/render 状态，关闭生产者与消费者交错导致的丢唤醒窗口。
- 未修改 `InlineViewport`，未增加周期 scheduler，未改变 `runOnRenderThread` / `runLater` 行为。

## Concerns

无功能性 concern。测试中的故意异常会产生一条 SEVERE 日志和堆栈，这是验证既有 Throwable 防护的预期输出，不代表测试失败。

---

## Fix Round：严格 wake 批次边界

### 修复摘要

- 移除 `ConcurrentLinkedQueue.size()` 批次计数，不再依赖弱一致且 O(n) 的并发遍历。
- 在 `uiActionsLock` 下原子交换 action 队列，并在同一临界区消费该批次的 render 标志；交换完成即形成严格 wake 边界，之后提交的 action/render 请求只进入下一批。
- 当前 wake 只 drain 已交换出的有限队列；执行期间新增工作由 `finally` 重新排入后续 wake，不会在当前 wake 无界循环。
- 新增受控回归覆盖：首个 action 执行期间提交第二个 action，直接断言两个 draw 分别观察到 1、2 个已完成 action，从而观测两个批次边界。
- 将 1,000 个并发 render 请求竞态改为 runner 已启动后触发，并在对应 draw 回调中直接断言 action 已完成。

### 测试命令与精确结果

聚焦测试：

```bash
mvn -f "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui/pom.xml" \
  -pl springai-tamboui-inline-patch \
  -Dtest=InlineTuiRunnerEventDrivenTest test
```

精确结果：`BUILD SUCCESS`；`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。

Patch 模块全测：

```bash
mvn -f "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui/pom.xml" \
  -pl springai-tamboui-inline-patch test
```

精确结果：`BUILD SUCCESS`；`Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`。

静态差异检查：

```bash
git -C "/Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/event-driven-ui" diff --check
```

精确结果：退出码 0，无输出。

### 提交

- Commit: `COMMIT_HASH_PENDING`
- Message: `fix(tamboui): enforce strict UI wake batches`

### Concerns

无新增功能性 concern。`actionFailureDoesNotKillFollowingActions` 仍会按设计输出一次预期 SEVERE 日志与 `AssertionError: expected test failure` 堆栈；测试结果为成功。
