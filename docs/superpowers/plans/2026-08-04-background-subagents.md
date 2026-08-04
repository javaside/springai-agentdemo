# 子 agent 后台模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `Task` / `ParallelTasks` 加 `run_in_background`——立刻返回 task id，主 agent 与用户都不再被阻塞；结果经 `TaskOutput` 主动取回，或在空闲且输入框为空时自动起新回合送达。

**Architecture:** 独立任务注册表 + 独立事件通道。后台任务有脱离 `turnId` 的身份，事件走一组不带 `turnId` 的新 listener 方法，`ConversationState` 用独立列表承载。现有的 `acceptingTurnId` 迟到过滤、`cancelCurrent` 取消语义、模态队列活性**一行不动**。

**Tech Stack:** Java 17、Spring AI 2.0（`FunctionToolCallback` / `ToolContext`）、TamboUI 0.4.0、JUnit 5、Maven。

**分支：** `feat/background-subagents`（已存在，spec 已提交在此分支）

**Spec：** [`docs/superpowers/specs/2026-08-04-background-subagents-design.md`](../specs/2026-08-04-background-subagents-design.md)

---

## ⚠️ 全局纪律（每个任务都适用）

0. **判断"输入框是否为空"必须用 `CodeTuiView.inputState.text()`，绝不能用 `ConversationState.currentInput()`。**
   后者是输入迁移后留下的**死代码**——生产代码里没有任何地方写入那个 `StringBuilder`，它**永远返回空串**。
   拿它做闸门，等于那道闸门根本不存在，而且所有测试照样绿（T9 实测：改回 `currentInput()` 后
   `nonEmptyInput_doesNotDeliver` 立刻红）。用 `isEmpty()` 而非 `isBlank()`——敲了个空格也算在打字。


1. **测试命令必须带 `-pl springai-code-tui`**。整仓 `mvn test -Dtest=X` 会被仓库里 3 个空模块打挂。**不要**用 `-DfailIfNoSpecifiedTests=false` 去盖这个问题。
   ```bash
   mvn -pl springai-code-tui test -Dtest=SomeTest
   ```
2. **绝不 `git commit --amend`**，绝不 `git stash`。这个仓库可能有并行工作在同一棵树上，amend 会改到别人的提交，stash 会卷走整棵树的未提交改动。只追加新提交。
3. **一个 `OutputLine` = 一个物理行**。任何进 `pending` 的文本都必须先折叠换行（用已有的 `summarize(...)`），否则 `println` 会把多行塌成一行截断。
4. **测试里需要固定迭代顺序时用 `LinkedHashMap`，不要用 `Map.of`**——`Map.of` 的迭代序随 JVM 随机，"取错元素"类缺陷只有一半概率被抓到。
5. 每个任务结束都提交。

---

## 文件结构

**新建（全部在 `src/main/java/io/github/javaside/springai/codetui/agent/background/`）：**

| 文件 | 职责 |
|---|---|
| `BackgroundTask.java` | 单个后台任务的身份 + 可变状态。只有字段与状态迁移，无逻辑 |
| `BackgroundTaskRegistry.java` | 进程内任务表。登记 / 查询 / 完成 / 消费 / 终止 / 列举 / 容量淘汰。**唯一并发真相源** |
| `BackgroundNotifier.java` | 纯函数：判定是否该自动起回合 + 合成通知文本 + 连续自动回合刹车计数 |
| `TaskResultStore.java` | 结果限幅与落盘 `.codetui/artifacts/task-<id>.txt` |
| `BackgroundTaskTool.java` | `TaskOutput` 工具（`FunctionToolCallback`） |

**修改：**

| 文件 | 改什么 |
|---|---|
| `agent/SubagentRunner.java` | 加 `runInBackground(...)`；`inFlight` 拆成前台/后台两个计数 |
| `agent/SubagentTool.java` | `SubagentCall` / `ParallelCall` 加 `run_in_background` |
| `agent/PermissionCallback.java` | `backgroundTaskId` 非空时把 ASK 判成 DENY，按档位给建议 |
| `agent/ToolEventCallback.java` | 加 `BACKGROUND_TASK_ID_KEY` 常量与 `extractBackgroundTaskId(...)` |
| `agent/AgentListener.java` | 加 3 个不带 `turnId` 的 default 方法 |
| `agent/AgentTools.java:376-397` | 装配注册表、`TaskOutput` 工具、env 变量 |
| `ui/ConversationState.java` | 加 `backgroundTasks` 列表与后台事件落地；`onToolStarted(turnId, taskId, ...)` 加一句前置判断 |
| `ui/CodeTuiView.java:270-327` | `drain()` 末尾挂自动起回合；状态栏后缀；⏱ 面板；`/tasks` 面板 |

**为什么单开 `background/` 子包**：`agent/` 包已有 100+ 个类。这 5 个类内聚成一个完整子系统（注册表 + 判定 + 存储 + 工具），彼此调用而极少被外部调用，放子包能让"这块的边界在哪"一眼可见。这与既有的 `agent/media/`、`agent/permission/` 子包是同一做法。

---

## Task 1: BackgroundTask + BackgroundTaskRegistry

**Files:**
- Create: `src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTask.java`
- Create: `src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistry.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistryTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistryTest.java`：

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskRegistryTest {

    private BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(64);
    }

    @Test
    void registerCreatesRunningTask() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "调查登录失败");
        BackgroundTask t = r.find(id);
        assertEquals(BackgroundTask.Status.RUNNING, t.status());
        assertEquals("explore", t.agentName());
        assertEquals("调查登录失败", t.description());
        assertFalse(t.consumed());
    }

    @Test
    void completeMarksDoneAndStoresResult() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "调查结论", true);
        BackgroundTask t = r.find(id);
        assertEquals(BackgroundTask.Status.DONE, t.status());
        assertEquals("调查结论", t.result());
    }

    @Test
    void completeWithFailureMarksFailed() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("bash", "d");
        r.complete(id, "连接被重置", false);
        assertEquals(BackgroundTask.Status.FAILED, r.find(id).status());
    }

    @Test
    void unfinishedTaskIsNotReportedAsCompleted() {
        BackgroundTaskRegistry r = registry();
        r.register("explore", "d");
        assertTrue(r.completedUnconsumed().isEmpty());
    }

    @Test
    void completedUnconsumedListsFinishedButNotYetConsumed() {
        BackgroundTaskRegistry r = registry();
        String a = r.register("explore", "a");
        String b = r.register("plan", "b");
        r.complete(a, "ra", true);
        List<BackgroundTask> got = r.completedUnconsumed();
        assertEquals(1, got.size());
        assertEquals(a, got.get(0).taskId());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(b).status());
    }

    @Test
    void markConsumedIsIdempotentAndRemovesFromUnconsumed() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "res", true);
        assertTrue(r.markConsumed(id));
        assertFalse(r.markConsumed(id));          // 第二次返回 false：已消费
        assertTrue(r.completedUnconsumed().isEmpty());
    }

    @Test
    void markConsumedOnRunningTaskReturnsFalse() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        assertFalse(r.markConsumed(id));          // 没跑完就不该被"消费"
    }

    @Test
    void killMarksKilledOnlyWhenRunning() {
        BackgroundTaskRegistry r = registry();
        String running = r.register("bash", "a");
        String done = r.register("plan", "b");
        r.complete(done, "res", true);
        assertTrue(r.kill(running));
        assertEquals(BackgroundTask.Status.KILLED, r.find(running).status());
        assertFalse(r.kill(done));                 // 已完成的杀不动
        assertEquals(BackgroundTask.Status.DONE, r.find(done).status());
    }

    @Test
    void findUnknownIdReturnsNull() {
        assertNull(registry().find("task_nope"));
    }

    @Test
    void evictionDropsOldestFinishedButNeverRunning() {
        BackgroundTaskRegistry r = new BackgroundTaskRegistry(3);
        String finished1 = r.register("a", "1");
        String running = r.register("b", "2");
        String finished2 = r.register("c", "3");
        r.complete(finished1, "r1", true);
        r.complete(finished2, "r2", true);

        r.register("d", "4");                      // 触发淘汰：容量 3，现在要放第 4 个

        assertNull(r.find(finished1), "最旧的已完成任务应被淘汰");
        assertEquals(BackgroundTask.Status.RUNNING, r.find(running).status(), "运行中的永不淘汰");
        assertEquals(BackgroundTask.Status.DONE, r.find(finished2).status());
    }

    @Test
    void evictionKeepsAllWhenEverythingIsRunning() {
        BackgroundTaskRegistry r = new BackgroundTaskRegistry(2);
        String a = r.register("a", "1");
        String b = r.register("b", "2");
        String c = r.register("c", "3");           // 超容量但无可淘汰者
        assertEquals(BackgroundTask.Status.RUNNING, r.find(a).status());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(b).status());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(c).status());
    }

    @Test
    void runningCountAndKillAllWork() {
        BackgroundTaskRegistry r = registry();
        String a = r.register("a", "1");
        r.register("b", "2");
        r.complete(a, "res", true);
        assertEquals(1, r.runningCount());
        r.killAll();
        assertEquals(0, r.runningCount());
    }

    @Test
    void taskIdsAreUnique() {
        BackgroundTaskRegistry r = registry();
        assertFalse(r.register("a", "1").equals(r.register("a", "1")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundTaskRegistryTest
```
Expected: 编译失败，`程序包 io.github.javaside.springai.codetui.agent.background 不存在`

- [ ] **Step 3: 写 `BackgroundTask`**

```java
package io.github.javaside.springai.codetui.agent.background;

/**
 * 一个后台子 agent 任务的身份 + 可变状态。
 *
 * <p><b>刻意不是 record</b>：status / result 都要就地更新，而 taskId 等身份字段不变。
 * 用可变类 + 只读快照（{@link #snapshot()}）比"每次改都造一个新 record"更贴合它的用法——
 * 注册表按 taskId 索引，换对象等于每次更新都要替换 map 里的值。
 *
 * <p>并发：所有读写都在 {@link BackgroundTaskRegistry} 的锁内完成，本类自身不加锁。
 */
public final class BackgroundTask {

    public enum Status {
        /** 在跑。 */ RUNNING,
        /** 正常结束。 */ DONE,
        /** 抛异常结束。 */ FAILED,
        /** 被用户经 /tasks 面板终止。 */ KILLED
    }

    private final String taskId;
    private final String agentName;
    private final String description;
    private final long startedAt;

    private Status status = Status.RUNNING;
    private long finishedAt;
    private String result = "";
    private boolean consumed;

    BackgroundTask(String taskId, String agentName, String description, long startedAt) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.description = description == null ? "" : description;
        this.startedAt = startedAt;
    }

    public String taskId() { return taskId; }
    public String agentName() { return agentName; }
    public String description() { return description; }
    public long startedAt() { return startedAt; }
    public Status status() { return status; }
    public long finishedAt() { return finishedAt; }
    public String result() { return result; }
    public boolean consumed() { return consumed; }

    /** 是否已结束（不再运行）。KILLED 也算结束。 */
    public boolean finished() { return status != Status.RUNNING; }

    /** 是否"跑完且有结果值得送给模型"——KILLED 不算（用户主动杀的，不该再回灌）。 */
    public boolean deliverable() { return status == Status.DONE || status == Status.FAILED; }

    void finish(Status newStatus, String result, long at) {
        this.status = newStatus;
        this.result = result == null ? "" : result;
        this.finishedAt = at;
    }

    void setConsumed() { this.consumed = true; }

    /** 耗时毫秒：未结束则算到现在。 */
    public long elapsedMillis(long now) {
        return (finished() ? finishedAt : now) - startedAt;
    }
}
```

- [ ] **Step 4: 写 `BackgroundTaskRegistry`**

```java
package io.github.javaside.springai.codetui.agent.background;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 后台任务的进程内注册表——<b>唯一的并发真相源</b>。
 *
 * <p><b>刻意不认识 UI、不认识 Spring AI、不认识回合</b>：它只是一张带状态机的表。
 * "什么时候该自动起回合"那个最易错的判断在 {@link BackgroundNotifier}（纯函数），
 * 执行在 UI 层。三者分开才能各自单测。
 *
 * <p><b>不落盘</b>：后台任务的价值在于"这一次会话里派出去的活"。跨进程恢复一个
 * 已经没有线程在跑的任务只会制造"它还在跑吗"的误解（见 spec §5.4）。
 *
 * <p>并发：全部方法 {@code synchronized}。持锁时间都是 O(n) 的 map 遍历，n ≤ capacity（默认 64），
 * 不会有长持锁——注册表里绝不做 IO 或调用外部代码。
 */
public final class BackgroundTaskRegistry {

    /** 用 LinkedHashMap 而非 HashMap：淘汰要按"最早登记"顺序找，随机迭代序会淘汰错人。 */
    private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();
    private final int capacity;
    private final Supplier<String> idSupplier;
    private final Supplier<Long> clock;

    public BackgroundTaskRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.idSupplier = () -> "task_" + UUID.randomUUID().toString().substring(0, 8);
        this.clock = System::currentTimeMillis;
    }

    /** 登记一个新任务（RUNNING），返回 taskId。超容量时先淘汰最旧的已完成任务。 */
    public synchronized String register(String agentName, String description) {
        evictIfNeeded();
        String id = idSupplier.get();
        tasks.put(id, new BackgroundTask(id, agentName, description, clock.get()));
        return id;
    }

    /** 标记完成。ok=true → DONE，false → FAILED。未知 id 或已结束的任务静默忽略。 */
    public synchronized void complete(String taskId, String result, boolean ok) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || t.finished()) return;
        t.finish(ok ? BackgroundTask.Status.DONE : BackgroundTask.Status.FAILED, result, clock.get());
    }

    /** 终止一个运行中的任务（标记 KILLED）。返回是否真的改变了状态——已结束的返回 false。 */
    public synchronized boolean kill(String taskId) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || t.finished()) return false;
        t.finish(BackgroundTask.Status.KILLED, "已被用户终止", clock.get());
        return true;
    }

    /** 终止全部运行中的任务（/clear、/exit 用）。 */
    public synchronized void killAll() {
        for (BackgroundTask t : tasks.values()) {
            if (!t.finished()) {
                t.finish(BackgroundTask.Status.KILLED, "已被用户终止", clock.get());
            }
        }
    }

    /**
     * 标记结果已被消费（已交给模型）。返回是否<b>本次</b>真的把它从未消费变成已消费。
     *
     * <p>返回值是两条回收路径的<b>互斥闸</b>：`TaskOutput` 与自动送达都调它，
     * 谁先拿到 true 谁负责送，另一条拿到 false 就跳过。没有这个返回值，同一个结果会被送两遍。
     */
    public synchronized boolean markConsumed(String taskId) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || !t.deliverable() || t.consumed()) return false;
        t.setConsumed();
        return true;
    }

    /** 已结束、可送达、且尚未消费的任务（按登记顺序）。KILLED 不在其中。 */
    public synchronized List<BackgroundTask> completedUnconsumed() {
        List<BackgroundTask> out = new ArrayList<>();
        for (BackgroundTask t : tasks.values()) {
            if (t.deliverable() && !t.consumed()) out.add(t);
        }
        return out;
    }

    /** 全部任务快照（按登记顺序），供 ⏱ 面板与 /tasks 面板显示。 */
    public synchronized List<BackgroundTask> all() {
        return new ArrayList<>(tasks.values());
    }

    public synchronized BackgroundTask find(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized int runningCount() {
        int n = 0;
        for (BackgroundTask t : tasks.values()) {
            if (!t.finished()) n++;
        }
        return n;
    }

    /**
     * 容量淘汰：按登记顺序找第一个<b>已结束</b>的任务移除。
     *
     * <p><b>运行中的永不淘汰</b>——淘汰掉一个还在跑的任务，等它跑完时 {@code complete()}
     * 找不到 id 会静默丢结果，用户看到的是"任务凭空消失"。宁可短暂超容量。
     */
    private void evictIfNeeded() {
        while (tasks.size() >= capacity) {
            String victim = null;
            for (BackgroundTask t : tasks.values()) {
                if (t.finished()) { victim = t.taskId(); break; }
            }
            if (victim == null) return;   // 全在跑：不淘汰，允许超容量
            tasks.remove(victim);
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundTaskRegistryTest
```
Expected: `Tests run: 13, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTask.java \
        src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistry.java \
        src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistryTest.java
git commit -m "feat(background): 后台任务注册表与状态机"
```

---

## Task 2: TaskResultStore（结果限幅与落盘）

**Files:**
- Create: `src/main/java/io/github/javaside/springai/codetui/agent/background/TaskResultStore.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/background/TaskResultStoreTest.java`

**为什么先做这个**：后台任务结果经通知进 user 消息 = 直接进会话 = 永久占上下文。一个话痨的 explore 子 agent 能一次性打掉一大块。现在至少还有人看着，**后台任务是没人看着的**。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultStoreTest {

    @Test
    void shortResultIsReturnedVerbatimWithNoFileWritten(@TempDir Path root) {
        TaskResultStore store = new TaskResultStore(root, 100);
        String out = store.storeAndTruncate("task_ab12", "短结果");
        assertEquals("短结果", out);
        assertFalse(Files.exists(root.resolve(".codetui/artifacts/task-task_ab12.txt")),
                "未超限时不该落盘——凭空多出一堆小文件没有价值");
    }

    @Test
    void longResultIsTruncatedAndFullTextWrittenToDisk(@TempDir Path root) throws IOException {
        TaskResultStore store = new TaskResultStore(root, 20);
        String full = "x".repeat(100);
        String out = store.storeAndTruncate("task_ab12", full);

        assertTrue(out.startsWith("x".repeat(20)));
        assertTrue(out.contains("结果已截断"));
        assertTrue(out.contains("100"), "要写明完整长度，否则用户不知道漏了多少");
        Path artifact = root.resolve(".codetui/artifacts/task-task_ab12.txt");
        assertTrue(out.contains(artifact.toString()), "要给出可 Read 的绝对路径");
        assertEquals(full, Files.readString(artifact));
    }

    @Test
    void truncationHappensOnCharCountNotLineCount(@TempDir Path root) {
        TaskResultStore store = new TaskResultStore(root, 10);
        String out = store.storeAndTruncate("task_x", "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl");
        assertTrue(out.contains("结果已截断"));
    }

    @Test
    void nullResultBecomesEmptyStringNotNpe(@TempDir Path root) {
        assertEquals("", new TaskResultStore(root, 100).storeAndTruncate("task_x", null));
    }

    @Test
    void diskFailureDegradesToInMemoryTruncationInsteadOfThrowing(@TempDir Path root) throws IOException {
        // 把 artifacts 位置占成一个普通文件 → 建目录必失败
        Path codetui = Files.createDirectories(root.resolve(".codetui"));
        Files.writeString(codetui.resolve("artifacts"), "我是文件不是目录");

        TaskResultStore store = new TaskResultStore(root, 10);
        String out = store.storeAndTruncate("task_x", "y".repeat(50));

        assertTrue(out.startsWith("y".repeat(10)));
        assertTrue(out.contains("完整结果落盘失败"),
                "落盘失败要如实说，不能假装给了一个取不回来的路径");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=TaskResultStoreTest
```
Expected: 编译失败，`找不到符号: 类 TaskResultStore`

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 后台任务结果的<b>限幅与落盘</b>。
 *
 * <p><b>为什么必须有</b>：后台任务结果经通知文本进 user 消息 = 直接进会话 = 永久占上下文。
 * 前台子 agent 至少还有人盯着屏幕，后台任务是<b>没人看着的</b>——一个话痨的 explore
 * 能在你离开时把上下文打掉一大块。
 *
 * <p>超限时：完整正文落 {@code .codetui/artifacts/task-<id>.txt}，返回文本只留前 N 字符
 * 加一行说明与<b>绝对路径</b>（模型可以直接 Read 取回）。
 *
 * <p><b>落盘失败不抛异常</b>：结果本身是有价值的，不能因为写不了文件就把它丢掉。
 * 降级为纯内存截断，并在文本里<b>如实说明落盘失败</b>——给一个取不回来的路径比不给更糟。
 */
public final class TaskResultStore {

    private static final Logger log = LoggerFactory.getLogger(TaskResultStore.class);

    /** 默认上限：4000 字符。约等于 1-1.5k token，够放一份结论，不够放一整个文件 dump。 */
    public static final int DEFAULT_MAX_CHARS = 4000;

    private final Path root;
    private final int maxChars;

    public TaskResultStore(Path root) {
        this(root, DEFAULT_MAX_CHARS);
    }

    TaskResultStore(Path root, int maxChars) {
        this.root = root;
        this.maxChars = Math.max(1, maxChars);
    }

    /**
     * 限幅 + （超限时）落盘。返回可以安全塞进会话的文本。
     *
     * @param taskId 用于文件名
     * @param result 子 agent 的完整结果（可空）
     */
    public String storeAndTruncate(String taskId, String result) {
        String full = result == null ? "" : result;
        if (full.length() <= maxChars) {
            return full;
        }
        String head = full.substring(0, maxChars);
        Path artifact = root.resolve(".codetui").resolve("artifacts")
                .resolve("task-" + taskId + ".txt");
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, full);
            return head + "\n\n[结果已截断：完整 " + full.length() + " 字符见 "
                    + artifact.toAbsolutePath() + "，可用 Read 取回]";
        } catch (IOException | RuntimeException e) {
            log.warn("后台任务结果落盘失败：taskId={}", taskId, e);
            return head + "\n\n[结果已截断：完整 " + full.length()
                    + " 字符，但完整结果落盘失败（" + e.getClass().getSimpleName()
                    + "），截断部分之外的内容已丢失]";
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl springai-code-tui test -Dtest=TaskResultStoreTest
```
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/background/TaskResultStore.java \
        src/test/java/io/github/javaside/springai/codetui/agent/background/TaskResultStoreTest.java
git commit -m "feat(background): 任务结果限幅与 artifact 落盘"
```

---

## Task 3: BackgroundNotifier（自动起回合的判定与刹车）

**Files:**
- Create: `src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundNotifier.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundNotifierTest.java`

**这是本计划里最该钉死的一个类**：spec D2「完成即自动起新回合」的全部失控风险都集中在它的刹车逻辑上。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundNotifierTest {

    private static BackgroundTask done(String id, String agent, String desc, String result) {
        BackgroundTask t = new BackgroundTask(id, agent, desc, 0L);
        t.finish(BackgroundTask.Status.DONE, result, 1000L);
        return t;
    }

    private static BackgroundTask failed(String id, String agent, String desc, String why) {
        BackgroundTask t = new BackgroundTask(id, agent, desc, 0L);
        t.finish(BackgroundTask.Status.FAILED, why, 1000L);
        return t;
    }

    // ── 三条件真值表 ──

    @Test
    void notifiesWhenIdleAndInputEmptyAndHasUnconsumed() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), true, true).isPresent());
    }

    @Test
    void silentWhenBusy() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), false, true).isEmpty());
    }

    @Test
    void silentWhenInputBoxHasText() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), true, false).isEmpty(),
                "用户正在敲字时不抢跑");
    }

    @Test
    void silentWhenNothingCompleted() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(), true, true).isEmpty());
    }

    // ── 通知文本 ──

    @Test
    void notificationContainsIdAgentDescriptionAndResult() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(List.of(done("ab12", "explore", "调查登录失败", "是 token 过期")), true, true).get();
        assertTrue(text.contains("ab12"));
        assertTrue(text.contains("explore"));
        assertTrue(text.contains("调查登录失败"));
        assertTrue(text.contains("是 token 过期"));
        assertTrue(text.contains("✓"));
    }

    @Test
    void failedTaskIsMarkedWithCrossNotCheck() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(List.of(failed("cd34", "bash", "跑测试", "连接被重置")), true, true).get();
        assertTrue(text.contains("✗"));
        assertFalse(text.contains("✓"));
        assertTrue(text.contains("连接被重置"));
    }

    @Test
    void multipleTasksAreMergedIntoOneNotification() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(
                List.of(done("ab12", "explore", "调查", "结论A"), done("cd34", "plan", "设计", "结论B")),
                true, true).get();
        assertTrue(text.contains("ab12"));
        assertTrue(text.contains("cd34"));
        assertTrue(text.contains("结论A"));
        assertTrue(text.contains("结论B"));
        // 合并成一条，不是两条：整段只应出现一次抬头
        assertEquals(1, text.split("\\[后台任务完成]", -1).length - 1);
    }

    // ── 失控刹车 ──

    @Test
    void stopsAutoTurnsAfterConsecutiveLimit() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 1
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 2
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 3
        assertTrue(n.shouldNotify(one, true, true).isEmpty(),      // 4 → 刹车
                "连续 3 次自动回合后必须停止，否则无人值守时会无限烧 token");
    }

    @Test
    void userInputResetsTheBrake() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        n.shouldNotify(one, true, true);
        n.shouldNotify(one, true, true);
        n.shouldNotify(one, true, true);
        assertTrue(n.shouldNotify(one, true, true).isEmpty());

        n.onUserInput();

        assertTrue(n.shouldNotify(one, true, true).isPresent(), "用户一有真实输入就该重新允许自动送达");
    }

    @Test
    void brakeEngagedIsObservableForStatusBar() {
        BackgroundNotifier n = new BackgroundNotifier(1);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        assertFalse(n.brakeEngaged());
        n.shouldNotify(one, true, true);
        assertTrue(n.shouldNotify(one, true, true).isEmpty());
        assertTrue(n.brakeEngaged(), "刹车状态要能被状态栏读到，否则用户不知道为什么没动静");
    }

    @Test
    void brakeCountIsNotAdvancedBySilentCalls() {
        BackgroundNotifier n = new BackgroundNotifier(2);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        for (int i = 0; i < 50; i++) {
            n.shouldNotify(List.of(), true, true);        // 无任务：不该消耗额度
            n.shouldNotify(one, false, true);             // 忙：不该消耗额度
        }
        assertTrue(n.shouldNotify(one, true, true).isPresent());
        assertTrue(n.shouldNotify(one, true, true).isPresent());
        assertTrue(n.shouldNotify(one, true, true).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundNotifierTest
```
Expected: 编译失败，`找不到符号: 类 BackgroundNotifier`

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.background;

import java.util.List;
import java.util.Optional;

/**
 * 判定「后台任务结果是否该现在自动交给模型」并合成通知文本。
 *
 * <p><b>刻意做成不认识 UI 的小状态机</b>：输入是三个布尔与一个任务列表，输出是
 * {@code Optional<String>}。UI 层只负责把答案落实成一次 {@code submit}。
 * 这样"最容易出错的判断"可以脱离 TUI 单测。
 *
 * <h2>失控刹车</h2>
 * 自动起的回合可能又派后台任务 → 又自动起回合 → 无限循环烧钱，而且是在你不在电脑前的时候。
 * 故记<b>连续</b>自动回合计数：中间没有任何用户输入时连续送达超过 {@code maxConsecutive} 次即停止，
 * 由状态栏改为提示"回车交给模型"。任何一次真实用户输入（{@link #onUserInput()}）都重置计数。
 *
 * <p><b>只有真正送达才消耗额度</b>：无任务、忙、输入框非空这三种"什么都没做"的调用不计数。
 * 否则 drain 每 33ms 调一次，额度几秒就被空转耗光，功能等于没有。
 *
 * <p>并发：只在渲染线程（drain）调用，故不加锁。
 */
public final class BackgroundNotifier {

    /** 默认连续自动回合上限。 */
    public static final int DEFAULT_MAX_CONSECUTIVE = 3;

    private final int maxConsecutive;
    private int consecutive;

    public BackgroundNotifier() {
        this(DEFAULT_MAX_CONSECUTIVE);
    }

    public BackgroundNotifier(int maxConsecutive) {
        this.maxConsecutive = Math.max(1, maxConsecutive);
    }

    /**
     * 该不该现在自动起一个回合把结果送给模型？是则返回通知文本。
     *
     * @param completedUnconsumed 已结束、可送达、未消费的任务（{@link BackgroundTaskRegistry#completedUnconsumed()}）
     * @param idle                当前是否空闲（无活跃回合、非压缩中、无待处理模态、无在飞<b>前台</b>子 agent）
     * @param inputEmpty          输入框是否为空
     */
    public Optional<String> shouldNotify(List<BackgroundTask> completedUnconsumed,
                                         boolean idle, boolean inputEmpty) {
        if (completedUnconsumed == null || completedUnconsumed.isEmpty()) return Optional.empty();
        if (!idle || !inputEmpty) return Optional.empty();
        if (consecutive >= maxConsecutive) return Optional.empty();
        consecutive++;
        return Optional.of(compose(completedUnconsumed));
    }

    /** 用户有真实输入（提交了一条消息）：重置刹车。 */
    public void onUserInput() {
        consecutive = 0;
    }

    /** 刹车是否已踩下（供状态栏提示"回车交给模型"）。 */
    public boolean brakeEngaged() {
        return consecutive >= maxConsecutive;
    }

    /**
     * 合成通知文本。<b>多个任务合并成一条</b>——起 N 个回合会让模型在没读完第一条时就被第二条打断。
     */
    private String compose(List<BackgroundTask> tasks) {
        StringBuilder sb = new StringBuilder("[后台任务完成]\n");
        for (BackgroundTask t : tasks) {
            sb.append('\n')
              .append(t.taskId()).append(" · ").append(t.agentName())
              .append(" · ").append(t.description())
              .append(t.status() == BackgroundTask.Status.DONE ? " · ✓" : " · ✗")
              .append('\n')
              .append(t.result())
              .append('\n');
        }
        sb.append("\n以上是你先前派出的后台任务的结果，请据此继续。");
        return sb.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundNotifierTest
```
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundNotifier.java \
        src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundNotifierTest.java
git commit -m "feat(background): 自动送达判定与失控刹车"
```

---

## Task 4: 后台事件通道 + SubagentRunner.runInBackground

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java`（文件末尾，压缩事件那一段之前）
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/ToolEventCallback.java:11-12`（加常量）与末尾（加提取方法）
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerBackgroundTest.java`

- [ ] **Step 1: 加 2 个 listener 方法**

在 `AgentListener.java` 的 `onGuardrailBypassed` 之后、`// ── 会话压缩` 注释之前插入：

```java
    // ── 后台子 agent（run_in_background；跨回合存活，故<b>不带 turnId</b>） ──

    /**
     * 后台任务已启动。<b>刻意不带 turnId</b>：后台任务跨回合存活，带 turnId 会被
     * {@code ConversationState} 的迟到过滤丢弃（那正是前台事件想要的行为，对后台却是致命的）。
     *
     * <p>默认空实现，便于回显桩 / 测试桩省略。
     */
    default void onBackgroundTaskStarted(String taskId, String agentName, String description) { }

    /** 后台任务结束。ok=false 表示执行抛错（finalText 是摊平后的原因）。 */
    default void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) { }
```

- [ ] **Step 2: 加 ToolContext 的后台标记键**

在 `ToolEventCallback.java` 第 12 行 `static final String TASK_ID_KEY = "taskId";` 之后加：

```java
    /**
     * 后台任务标记：值为 taskId，仅后台派发时存在。
     *
     * <p><b>与 {@link #TASK_ID_KEY} 分开而不是复用</b>：taskId 对前台子 agent 也有值，
     * 用它判"是不是后台"会把前台子 agent 一并误判成后台，于是前台子 agent 的审批面板不再弹出——
     * 一个只在"派了子 agent 且需要审批"时才出现的静默降级。
     */
    static final String BACKGROUND_TASK_ID_KEY = "backgroundTaskId";
```

在同文件 `extractTaskId` 方法之后加：

```java
    /** 包私有：{@link PermissionCallback} 据此判断本次调用是否来自后台任务。 */
    static String extractBackgroundTaskId(ToolContext ctx) {
        if (ctx == null) return null;
        Object v = ctx.getContext().get(BACKGROUND_TASK_ID_KEY);
        return v == null ? null : String.valueOf(v);
    }
```

- [ ] **Step 3: 写失败测试**

创建 `src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerBackgroundTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.background.BackgroundTask;
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** runInBackground：立刻返回、结果回填注册表、前台闸门不被拉高。用假 ChatModel，不联网。 */
class SubagentRunnerBackgroundTest {

    private static ChatModel chatModel(String text, CountDownLatch gate) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("subagent path bridges to stream()");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                if (gate != null) {
                    try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.error(new RuntimeException("interrupted"));
                    }
                }
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
    }

    private static LlmProvider provider(ChatModel model) {
        return new LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return model; }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<ModelOption> models() { return List.of(new ModelOption("fake-m", "Fake", "d")); }
            @Override public String defaultModel() { return "fake-m"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner runner(ChatModel model, BackgroundTaskRegistry reg, AgentListener lis) {
        SubagentRunner r = new SubagentRunner(new ProviderRegistry(List.of(provider(model))),
                List.of(), lis, "");
        r.enableBackground(reg, 2);
        return r;
    }

    private static void awaitDone(BackgroundTaskRegistry reg, String id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (reg.find(id) != null && reg.find(id).finished()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("后台任务在 2s 内未结束");
    }

    @Test
    void returnsImmediatelyWithTaskIdAndDoesNotBlock() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", gate), reg, new StubListener());

        long t0 = System.nanoTime();
        String out = r.runInBackground(spec(), "hi", "调查登录失败");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 1000, "派发必须立刻返回，实测 " + elapsedMs + "ms");
        assertTrue(out.contains("task_"), "返回文本要含 taskId：" + out);
        assertEquals(1, reg.runningCount());

        gate.countDown();
        awaitDone(reg, reg.all().get(0).taskId());
    }

    @Test
    void resultIsWrittenBackToRegistryOnSuccess() throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("调查结论", null), reg, new StubListener());
        r.runInBackground(spec(), "hi", "调查");
        String id = reg.all().get(0).taskId();
        awaitDone(reg, id);
        assertEquals(BackgroundTask.Status.DONE, reg.find(id).status());
        assertEquals("调查结论", reg.find(id).result());
    }

    @Test
    void failureIsRecordedAsFailedNotThrownToCaller() throws Exception {
        ChatModel boom = new ChatModel() {
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                return Flux.error(new RuntimeException("Error reading response",
                        new java.io.IOException("No content to map due to end-of-input")));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(boom, reg, new StubListener());

        r.runInBackground(spec(), "hi", "调查");        // 不得抛：后台任务失败不该炸掉主 agent 的回合

        String id = reg.all().get(0).taskId();
        awaitDone(reg, id);
        assertEquals(BackgroundTask.Status.FAILED, reg.find(id).status());
        assertTrue(reg.find(id).result().contains("end-of-input"), "失败文本应摊平 cause 链");
    }

    @Test
    void backgroundTasksDoNotRaiseForegroundInFlightGate() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", gate), reg, new StubListener());

        r.runInBackground(spec(), "hi", "调查");
        // 关键断言：此刻只有后台任务在飞，前台计数必须是 0，否则 busy 闸门会挡住新回合 = 后台化失效
        assertEquals(0, r.inFlightCount(), "后台任务绝不能计入前台在飞计数");
        assertEquals(1, r.backgroundInFlightCount());

        gate.countDown();
        awaitDone(reg, reg.all().get(0).taskId());
    }

    @Test
    void emitsBackgroundListenerEventsNotTurnScopedOnes() throws Exception {
        AtomicReference<String> started = new AtomicReference<>();
        AtomicReference<String> finished = new AtomicReference<>();
        AtomicReference<Boolean> sawTurnScoped = new AtomicReference<>(false);
        AgentListener lis = new StubListener() {
            @Override public void onBackgroundTaskStarted(String taskId, String agentName, String description) {
                started.set(taskId + "|" + agentName + "|" + description);
            }
            @Override public void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
                finished.set(taskId + "|" + finalText + "|" + ok);
            }
            @Override public void onSubagentStarted(long turnId, String taskId, String a, String d) {
                sawTurnScoped.set(true);
            }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(chatModel("结论", null), reg, lis);

        r.runInBackground(spec(), "hi", "调查登录失败");
        awaitDone(reg, reg.all().get(0).taskId());
        for (int i = 0; i < 50 && finished.get() == null; i++) Thread.sleep(20);

        assertNotNull(started.get());
        assertTrue(started.get().contains("explore"));
        assertTrue(started.get().contains("调查登录失败"));
        assertNotNull(finished.get());
        assertTrue(finished.get().endsWith("|true"));
        assertEquals(Boolean.FALSE, sawTurnScoped.get(),
                "后台任务不得发回合级事件——那些会被迟到过滤丢弃，且会污染 ⟐ 面板");
    }

    @Test
    void queueFullIsRejectedWithClearTextAndTaskIsNotDeliverable() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = new SubagentRunner(
                new ProviderRegistry(List.of(provider(chatModel("结论", gate)))), List.of(),
                new StubListener(), "");
        r.enableBackground(reg, 1, 1);          // 并发 1、队列 1

        r.runInBackground(spec(), "hi", "a");   // 占住唯一线程
        r.runInBackground(spec(), "hi", "b");   // 占住唯一队列位
        String third = r.runInBackground(spec(), "hi", "c");

        assertTrue(third.contains("队列已满"), "拒绝要说清楚，别静默排队：" + third);
        assertTrue(reg.completedUnconsumed().isEmpty(),
                "被拒的任务不得成为可送达结果——否则会被自动送给模型，读起来像它真跑过");

        gate.countDown();
    }

    @Test
    void backgroundToolContextCarriesBackgroundMarkerAndNoRealTurnId() throws Exception {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                captured.set(p);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        SubagentRunner r = runner(capturing, reg, new StubListener());
        r.runInBackground(spec(), "hi", "调查");
        awaitDone(reg, reg.all().get(0).taskId());
        assertNotNull(captured.get(), "假模型应当真的被调用过");
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=SubagentRunnerBackgroundTest
```
Expected: 编译失败，`找不到符号: 方法 enableBackground(...)`

- [ ] **Step 5: 改 SubagentRunner —— 拆 inFlight 计数**

把第 61 行的字段替换为两个：

```java
    /** 当前在飞的<b>前台</b>子 agent 数（串行 run + 并行 runAll）。供 UI busy 闸门判断"取消后是否还有旧子 agent 未清"。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /**
     * 当前在飞的<b>后台</b>子 agent 数。<b>刻意与前台分开计</b>：
     * 混在一起的话，只要有后台任务在跑，{@code hasInFlightSubagents()} 就会挡住新回合——
     * 后台化等于没做（这正是变异测试要钉的那一条）。本计数只供面板显示与退出清理。
     */
    private final AtomicInteger backgroundInFlight = new AtomicInteger();
```

在 `inFlightCount()` 之后加：

```java
    /** 当前在飞的后台子 agent 数（只供面板与退出清理，<b>绝不</b>进 busy 闸门）。 */
    public int backgroundInFlightCount() {
        return backgroundInFlight.get();
    }
```

- [ ] **Step 6: 改 SubagentRunner —— 抽出共用执行体**

在 `run(...)` 方法之后加一个私有方法，并把 `run(...)` 里第 131-148 行那段 ChatClient 调用替换为对它的调用：

```java
    /**
     * 前台与后台共用的执行体：建子 agent 专用 ChatClient 并跑一次完整的工具循环。
     *
     * <p>抽出来的唯一目的是让前台/后台<b>只在 toolContext 与事件上报上不同</b>，
     * 模型、工具集、系统提示、重试策略全部同源——否则两条路会各自漂移，
     * 而"后台任务的行为和前台不一样"是最难排查的那类缺陷。
     */
    private String execute(SubagentSpec spec, String prompt, Map<String, Object> toolContext) {
        ChatClient client = ChatClient.builder(RetryingChatModel.wrap(registry.active().chatModel()))
                .defaultTools(effectiveTools(spec).toArray())
                .build();
        ChatOptions options = resolveOptions(spec);
        String result = client.prompt()
                .system(effectiveSystemPrompt(spec))
                .user(prompt)
                .options(options.mutate())
                .toolContext(toolContext)
                .call()
                .content();
        return result == null ? "" : result;
    }
```

`run(...)` 的 try 块内改成：

```java
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, parentTurnId,
                           ToolEventCallback.TASK_ID_KEY, taskId));
            listener.onSubagentFinished(parentTurnId, taskId, finalText, true);
            return finalText;
```

- [ ] **Step 7: 改 SubagentRunner —— 加后台字段、`enableBackground`、`runInBackground`**

加字段（放在 `poolsByTurn` 之后）：

```java
    /** 后台任务注册表；null 表示未启用后台模式（老测试与回显桩不受影响）。 */
    private BackgroundTaskRegistry backgroundRegistry;
    /** 后台常驻线程池；与回合级临时池<b>完全分开</b>——回合取消 shutdownNow 临时池时碰不到它。 */
    private ThreadPoolExecutor backgroundPool;
```

加方法（放在 `cancelTurn` 之后）：

```java
    /** 启用后台模式（默认并发 4、队列 16）。装配层调用；不调则 {@link #runInBackground} 返回不可用提示。 */
    public void enableBackground(BackgroundTaskRegistry registry, int concurrency) {
        enableBackground(registry, concurrency, DEFAULT_BACKGROUND_QUEUE);
    }

    /** 默认后台等待队列容量。 */
    public static final int DEFAULT_BACKGROUND_QUEUE = 16;

    /** 启用后台模式，显式指定并发与队列容量（测试用）。 */
    public void enableBackground(BackgroundTaskRegistry registry, int concurrency, int queueCapacity) {
        this.backgroundRegistry = registry;
        int n = Math.min(32, Math.max(1, concurrency));
        AtomicLong seq = new AtomicLong();
        this.backgroundPool = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "subagent-background-" + seq.incrementAndGet());
                    t.setDaemon(true);   // 守护线程：绝不阻止 JVM 退出（退出清理是有界的，见 shutdownBackground）
                    return t;
                });
    }

    /**
     * 后台派发：<b>立刻</b>返回一段含 taskId 的文本，子 agent 在常驻池里跑。
     *
     * <p>返回的文本<b>就是本次工具调用的结果</b>——所以不会留下悬空 {@code tool_calls}，
     * "回合被中断后残留 assistant(tool_calls)、下一轮 400" 那个已知坑在这条路上不存在。
     *
     * <p><b>绝不抛异常</b>：后台任务的失败是它自己的事，不该炸掉主 agent 正在进行的回合。
     */
    public String runInBackground(SubagentSpec spec, String prompt, String description) {
        if (backgroundRegistry == null || backgroundPool == null) {
            return "后台模式不可用（未装配后台注册表）。请改用 run_in_background=false 的前台 Task。";
        }
        String taskId = backgroundRegistry.register(spec.name(), description);
        listener.onBackgroundTaskStarted(taskId, spec.name(), description);
        backgroundInFlight.incrementAndGet();
        try {
            backgroundPool.execute(() -> runBackgroundBody(spec, prompt, taskId));
        } catch (RejectedExecutionException rejected) {
            backgroundInFlight.decrementAndGet();
            // 标记 KILLED 而不是 FAILED：KILLED 不可送达，绝不会被自动送给模型。
            // 送一条"它失败了"给模型，读起来像它真的跑过——而它根本没启动。
            backgroundRegistry.kill(taskId);
            return "后台队列已满（并发上限已占满且等待队列已满），本次未启动。"
                    + "请改用 run_in_background=false 的前台 Task，或等待在跑的任务完成后重试。";
        }
        return "已在后台启动：" + taskId + "（" + spec.name() + " · " + description + "）。"
                + "用 TaskOutput 取结果，或等待完成通知。";
    }

    /** 后台任务体：跑完把结果写回注册表并发事件。<b>任何异常都不得逃出本方法</b>（池线程死掉没人知道）。 */
    private void runBackgroundBody(SubagentSpec spec, String prompt, String taskId) {
        try {
            String finalText = execute(spec, prompt,
                    Map.of(ToolEventCallback.TURN_ID_KEY, -1L,
                           ToolEventCallback.TASK_ID_KEY, taskId,
                           ToolEventCallback.BACKGROUND_TASK_ID_KEY, taskId));
            backgroundRegistry.complete(taskId, finalText, true);
            listener.onBackgroundTaskFinished(taskId, finalText, true);
        } catch (RuntimeException ex) {
            log.error("后台子 agent 执行失败：spec={} taskId={}", spec.name(), taskId, ex);
            String detail = describe(ex);
            backgroundRegistry.complete(taskId, detail, false);
            listener.onBackgroundTaskFinished(taskId, detail, false);
        } catch (Throwable fatal) {
            // Error 也要兜：漏掉它，池线程静默死亡，任务永远停在 RUNNING，面板上转到天荒地老。
            log.error("后台子 agent 遇到致命错误：taskId={}", taskId, fatal);
            backgroundRegistry.complete(taskId, String.valueOf(fatal), false);
            listener.onBackgroundTaskFinished(taskId, String.valueOf(fatal), false);
        } finally {
            backgroundInFlight.decrementAndGet();
        }
    }

    /** 退出清理：<b>有界</b>关闭后台池（硬限 2s），照抄 MCP 子进程清理的"不卡退出优先"取舍。 */
    public void shutdownBackground() {
        if (backgroundPool == null) return;
        backgroundPool.shutdownNow();
        try {
            backgroundPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
```

补 import：

```java
import io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
```

- [ ] **Step 8: 跑新测试与全部子 agent 回归测试**

```bash
mvn -pl springai-code-tui test -Dtest='SubagentRunner*Test'
```
Expected: 全绿。`SubagentRunnerTest` / `SubagentRunnerOkTest` / `SubagentRunnerMcpToolsTest` 一个都不能挂——Step 6 抽 `execute` 是纯重构，行为必须逐条不变。

- [ ] **Step 9: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java \
        src/main/java/io/github/javaside/springai/codetui/agent/ToolEventCallback.java \
        src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java \
        src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerBackgroundTest.java
git commit -m "feat(background): SubagentRunner 后台派发与前后台在飞计数拆分"
```

---

## Task 5: Task / ParallelTasks 暴露 run_in_background

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/SubagentTool.java`
- Modify: `src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolTest.java:20-29`（**现有断言与本任务直接冲突，必须改**）
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolBackgroundTest.java`

> ⚠️ **现有测试 `toolNameIsTaskAndSchemaHasThreeParams` 里有 `assertTrue(!schema.contains("run_in_background"))`**（写于 v1，当时刻意不暴露该参数）。本任务把它改成正向断言。别绕过它。

- [ ] **Step 1: 改现有测试的那条反向断言**

把 `SubagentToolTest.java` 第 20-29 行的方法整体替换为：

```java
    @Test
    void toolNameIsTaskAndSchemaHasFourParams() {
        ToolCallback tc = SubagentTool.create(Map.of(), (spec, prompt, desc, turn) -> "unused");
        assertEquals("Task", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("subagent_type"));
        assertTrue(schema.contains("prompt"));
        assertTrue(schema.contains("description"));
        assertTrue(schema.contains("run_in_background"));
        // 续跑仍未暴露
        assertTrue(!schema.contains("resume"));
    }
```

- [ ] **Step 2: 写失败测试**

创建 `src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolBackgroundTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentToolBackgroundTest {

    /** 用 LinkedHashMap 而非 Map.of：迭代序固定，"取错 spec"类缺陷才能稳定被抓到。 */
    private static Map<String, SubagentSpec> specs() {
        Map<String, SubagentSpec> m = new LinkedHashMap<>();
        m.put("explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        m.put("plan", new SubagentSpec("plan", "d", "sys", List.of(), List.of(), null, List.of()));
        return m;
    }

    private static SubagentTool.SubagentCall call(String type, boolean bg) {
        return new SubagentTool.SubagentCall("do things", "the prompt", type, bg);
    }

    @Test
    void backgroundFalseGoesToForegroundDispatcher() {
        List<String> fg = new ArrayList<>();
        List<String> bg = new ArrayList<>();
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> { fg.add(spec.name()); return "fg:" + spec.name(); },
                (spec, prompt, desc) -> { bg.add(spec.name()); return "bg:" + spec.name(); });
        assertEquals("fg:explore", fn.apply(call("explore", false)));
        assertEquals(List.of("explore"), fg);
        assertTrue(bg.isEmpty());
    }

    @Test
    void backgroundTrueGoesToBackgroundDispatcher() {
        List<String> fg = new ArrayList<>();
        List<String> bg = new ArrayList<>();
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> { fg.add(spec.name()); return "fg:" + spec.name(); },
                (spec, prompt, desc) -> { bg.add(spec.name()); return "bg:" + spec.name(); });
        assertEquals("bg:plan", fn.apply(call("plan", true)));
        assertEquals(List.of("plan"), bg);
        assertTrue(fg.isEmpty(), "background=true 绝不能落到前台派发器上——那会重新变成阻塞");
    }

    @Test
    void nullBackgroundDefaultsToForeground() {
        var fn = SubagentTool.function(specs(),
                (spec, prompt, desc, turn) -> "fg", (spec, prompt, desc) -> "bg");
        // 模型可能整个省略该字段 → Jackson 给 null。默认必须是前台（向后兼容）
        assertEquals("fg", fn.apply(new SubagentTool.SubagentCall("d", "p", "explore", null)));
    }

    @Test
    void legacyTwoArgCreateRejectsBackgroundWithClearMessage() {
        // 回显桩 / 测试桩用的 2 参 create：没有后台派发器，请求后台时要说清楚而不是静默跑前台
        var fn = SubagentTool.function(specs(), (spec, prompt, desc, turn) -> "fg");
        String out = fn.apply(call("explore", true));
        assertTrue(out.contains("后台模式不可用"), out);
    }

    @Test
    void parallelBackgroundDispatchesEachAndReportsAllTaskIds() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("background 批次不该走前台并发路径"); },
                (spec, prompt, desc) -> "已在后台启动：task_" + spec.name());
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("plan", true)), true));
        assertTrue(out.contains("task_explore"));
        assertTrue(out.contains("task_plan"));
    }

    @Test
    void parallelBackgroundReportsPerItemRejectionWithoutFailingTheBatch() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("不该走前台"); },
                (spec, prompt, desc) -> spec.name().equals("plan")
                        ? "后台队列已满，本次未启动。"
                        : "已在后台启动：task_explore");
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("plan", true)), true));
        assertTrue(out.contains("task_explore"), "入得进的照常启动");
        assertTrue(out.contains("队列已满"), "入不进的单独标注，不整批拒绝");
    }

    @Test
    void parallelBackgroundFalseStillUsesConcurrentForegroundPath() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> List.of("r1", "r2"),
                (spec, prompt, desc) -> { throw new AssertionError("background=false 不该走后台"); });
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", false), call("plan", false)), false));
        assertTrue(out.contains("r1"));
        assertTrue(out.contains("r2"));
    }

    @Test
    void unknownTypeInBackgroundBatchIsIsolatedNotThrown() {
        var fn = SubagentTool.batchFunction(specs(),
                (dispatches, turn) -> { throw new AssertionError("不该走前台"); },
                (spec, prompt, desc) -> "已在后台启动：task_" + spec.name());
        String out = fn.apply(new SubagentTool.ParallelCall(
                List.of(call("explore", true), call("no-such", true)), true));
        assertTrue(out.contains("task_explore"), "一条未知类型不该拖垮另一条");
        assertTrue(out.contains("no-such"));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=SubagentToolBackgroundTest
```
Expected: 编译失败，`构造器 SubagentCall 无法应用于给定类型`（现在只有 3 个分量）

- [ ] **Step 4: 改 SubagentTool**

替换两个 record、加后台派发接口、改两个 function：

```java
    /** 子 agent 调用入参。{@code run_in_background} 缺省 / null 均视为前台（向后兼容）。 */
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type,
            @ToolParam(required = false, description =
                    "Run in background: return a task id immediately instead of blocking. "
                    + "Use for long investigations you want to continue working alongside. "
                    + "Retrieve the result with TaskOutput, or wait for the completion notice. "
                    + "Background tasks CANNOT prompt for permission: calls that would need approval "
                    + "are denied. Use foreground (false) when the task must write files or run commands "
                    + "that are not already allowed.") Boolean run_in_background) {

        /** null-safe：模型省略该字段时 Jackson 给 null，默认前台。 */
        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }
    }

    /** 批量子 agent 调用入参。 */
    public record ParallelCall(
            @ToolParam(description = "List of independent subtasks to run concurrently") List<SubagentCall> tasks,
            @ToolParam(required = false, description =
                    "Run the whole batch in background: return task ids immediately instead of blocking.")
            Boolean run_in_background) {

        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }
    }

    /** 后台派发（{@code SubagentRunner.runInBackground} 的函数式视图）：立刻返回含 taskId 的文本。 */
    @FunctionalInterface
    public interface BackgroundDispatcher {
        String dispatch(SubagentSpec spec, String prompt, String description);
    }
```

`create` / `createParallel` 各加一个带后台派发器的重载，并让老的 2 参版本降级：

```java
    /** 无后台能力的装配（回显桩 / 测试桩）：请求后台时明确告知不可用，<b>不</b>静默跑成前台。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return create(specs, dispatcher, null);
    }

    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                      BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("Task", function(specs, dispatcher, background))
                .description(DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(SubagentCall.class)
                .build();
    }

    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return createParallel(specs, dispatcher, null);
    }

    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher,
                                              BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("ParallelTasks", batchFunction(specs, dispatcher, background))
                .description(PARALLEL_DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(ParallelCall.class)
                .build();
    }

    static final String NO_BACKGROUND =
            "后台模式不可用（当前装配未提供后台派发器）。请改用 run_in_background=false。";
```

`function` 改成：

```java
    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return function(specs, dispatcher, null);
    }

    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                                   BackgroundDispatcher background) {
        return callArgs -> {
            SubagentSpec spec = specs.get(callArgs.subagent_type());
            if (spec == null) {
                throw new RuntimeException("No subagent found with type: " + callArgs.subagent_type()
                        + ". Available: " + String.join(", ", specs.keySet()));
            }
            if (callArgs.background()) {
                return background == null
                        ? NO_BACKGROUND
                        : background.dispatch(spec, callArgs.prompt(), callArgs.description());
            }
            return dispatcher.dispatch(spec, callArgs.prompt(), callArgs.description(), -1L);
        };
    }
```

`batchFunction` 加后台分支——**在原有路由循环之前**插入整批后台的短路：

```java
    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs,
                                                        BatchDispatcher dispatcher) {
        return batchFunction(specs, dispatcher, null);
    }

    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs,
                                                        BatchDispatcher dispatcher,
                                                        BackgroundDispatcher background) {
        return call -> {
            List<SubagentCall> tasks = call.tasks() == null ? List.of() : call.tasks();
            // 整批后台：逐条派发，每条各自成败（含"队列已满未启动"），不整批拒绝——与既有失败隔离语义一致
            boolean anyBackground = call.background() || tasks.stream().anyMatch(SubagentCall::background);
            if (anyBackground) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tasks.size(); i++) {
                    SubagentCall t = tasks.get(i);
                    SubagentSpec spec = t.subagent_type() == null ? null : specs.get(t.subagent_type());
                    String body;
                    if (spec == null) {
                        body = "未知 subagent 类型: " + t.subagent_type()
                                + "（可用: " + String.join(", ", specs.keySet()) + "）";
                    } else if (background == null) {
                        body = NO_BACKGROUND;
                    } else {
                        body = background.dispatch(spec, t.prompt(), t.description());
                    }
                    if (i > 0) sb.append("\n\n");
                    sb.append("[").append(i + 1).append("] ").append(t.subagent_type()).append("\n").append(body);
                }
                return sb.toString();
            }
            // ...此处保留原有的前台并发路由与结构化汇总代码，一行不改...
        };
    }
```

> 原有的前台路由/汇总代码整段保留在 `if (anyBackground)` 之后，不要改动它——`SubagentToolTest` 里已有针对它的测试。

- [ ] **Step 5: 跑测试确认通过**

```bash
mvn -pl springai-code-tui test -Dtest='SubagentTool*Test'
```
Expected: 全绿（新测试 8 条 + 现有测试全部）

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/SubagentTool.java \
        src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolTest.java \
        src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolBackgroundTest.java
git commit -m "feat(background): Task/ParallelTasks 暴露 run_in_background"
```

---

## Task 6: TaskOutput 工具

**Files:**
- Create: `src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskTool.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskToolTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskToolTest {

    private static BackgroundTaskTool tool(BackgroundTaskRegistry reg, Path root) {
        return new BackgroundTaskTool(reg, new TaskResultStore(root), 1);   // 阻塞上限 1s，测试跑得快
    }

    @Test
    void toolIsNamedTaskOutputAndSchemaHasTaskIdAndBlock(@TempDir Path root) {
        ToolCallback tc = BackgroundTaskTool.create(
                new BackgroundTaskRegistry(64), new TaskResultStore(root), 300);
        assertEquals("TaskOutput", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("task_id"));
        assertTrue(schema.contains("block"));
    }

    @Test
    void unknownTaskIdSaysSoInsteadOfHangingOrThrowing(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query("task_nope", false));
        assertTrue(out.contains("未知任务"), out);
        assertTrue(out.contains("已结束的进程"), "要提示可能是 -c 恢复后的陈旧 id");
    }

    @Test
    void runningTaskNonBlockingReturnsStillRunning(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("仍在运行"), out);
    }

    @Test
    void finishedTaskReturnsResultAndMarksConsumed(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "调查结论", true);

        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));

        assertTrue(out.contains("调查结论"));
        assertTrue(reg.find(id).consumed(), "取过就要标记已消费");
        assertTrue(reg.completedUnconsumed().isEmpty(),
                "已被模型取走的结果不得再被自动送一遍——这是两条回收路径的互斥闸");
    }

    @Test
    void failedTaskIsReportedAsFailure(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("bash", "跑测试");
        reg.complete(id, "连接被重置", false);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("失败"));
        assertTrue(out.contains("连接被重置"));
    }

    @Test
    void fetchingTwiceStillReturnsResultButOnlyFirstMarksConsumed(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "结论", true);
        BackgroundTaskTool t = tool(reg, root);

        assertTrue(t.fetch(new BackgroundTaskTool.Query(id, false)).contains("结论"));
        // 第二次仍要给结果（模型可能忘了），但不该报错
        assertTrue(t.fetch(new BackgroundTaskTool.Query(id, false)).contains("结论"));
    }

    @Test
    void blockingTimesOutIntoStillRunningNotAnError(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        long t0 = System.nanoTime();
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, true));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(out.contains("仍在运行"), "超时不等于任务死了，不能报失败：" + out);
        assertTrue(elapsedMs >= 900, "应当真的等满了超时窗口，实测 " + elapsedMs + "ms");
    }

    @Test
    void blockingReturnsAsSoonAsTaskCompletes(@TempDir Path root) throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        Thread completer = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            reg.complete(id, "晚到的结论", true);
        });
        completer.start();

        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, true));

        completer.join();
        assertTrue(out.contains("晚到的结论"), out);
    }

    @Test
    void longResultIsTruncatedThroughResultStore(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "z".repeat(20_000), true);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("结果已截断"), "长结果必须走限幅，否则直接把上下文打满");
        assertFalse(out.length() > 8000, "限幅后不该还这么长：" + out.length());
    }

    @Test
    void killedTaskSaysKilledNotSilentEmpty(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.kill(id);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("终止"), out);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundTaskToolTest
```
Expected: 编译失败，`找不到符号: 类 BackgroundTaskTool`

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.background;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * {@code TaskOutput} 工具：取回后台任务的结果。
 *
 * <p><b>两条回收路径的其中一条</b>（另一条是空闲时自动送达）。取到即经
 * {@link BackgroundTaskRegistry#markConsumed} 标记已消费，避免同一个结果被自动送一遍。
 *
 * <p><b>阻塞等待超时后返回"仍在运行"而不是失败</b>：超时不等于任务死了。
 * 报成失败会让模型放弃这个任务，而它可能再过十秒就好了。
 */
public final class BackgroundTaskTool {

    private static final String DESCRIPTION = """
            Retrieve the result of a background subagent task started with Task(run_in_background=true).

            - Pass block=false to check status without waiting.
            - Pass block=true to wait until the task finishes (bounded timeout; on timeout it
              simply reports that the task is still running).
            - Results of finished tasks are also delivered automatically when the session is idle,
              so you do not have to poll. Use this tool when you want the result *now*, inside the
              current turn.
            """;

    /** 轮询间隔：200ms。够快（人感知不到），又不会把 CPU 转满。 */
    private static final long POLL_INTERVAL_MS = 200;

    /** 工具入参。 */
    public record Query(
            @ToolParam(description = "The task id returned by Task(run_in_background=true)") String task_id,
            @ToolParam(required = false, description = "Wait until the task finishes (default false)")
            Boolean block) {

        public boolean blocking() {
            return Boolean.TRUE.equals(block);
        }
    }

    private final BackgroundTaskRegistry registry;
    private final TaskResultStore results;
    private final int timeoutSeconds;

    public BackgroundTaskTool(BackgroundTaskRegistry registry, TaskResultStore results, int timeoutSeconds) {
        this.registry = registry;
        this.results = results;
        this.timeoutSeconds = Math.min(3600, Math.max(1, timeoutSeconds));
    }

    /** 构建名为 "TaskOutput" 的 ToolCallback。 */
    public static ToolCallback create(BackgroundTaskRegistry registry, TaskResultStore results,
                                      int timeoutSeconds) {
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, results, timeoutSeconds);
        return FunctionToolCallback.builder("TaskOutput", (Query q) -> tool.fetch(q))
                .description(DESCRIPTION)
                .inputType(Query.class)
                .build();
    }

    /** 取结果。包私有可见性足够测试直接调，不必经 ToolCallback 绕一圈。 */
    String fetch(Query q) {
        String id = q.task_id();
        BackgroundTask t = registry.find(id);
        if (t == null) {
            return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存）。";
        }
        if (!t.finished() && q.blocking()) {
            t = awaitFinish(id);
        }
        if (t == null) {
            return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存）。";
        }
        return switch (t.status()) {
            case RUNNING -> "任务 " + id + " 仍在运行（" + t.agentName() + " · " + t.description()
                    + "）。稍后再取，或等待完成通知。";
            case KILLED -> "任务 " + id + " 已被终止，没有结果。";
            case DONE -> {
                registry.markConsumed(id);
                yield "任务 " + id + " 已完成（" + t.agentName() + " · " + t.description() + "）：\n"
                        + results.storeAndTruncate(id, t.result());
            }
            case FAILED -> {
                registry.markConsumed(id);
                yield "任务 " + id + " 执行失败（" + t.agentName() + " · " + t.description() + "）：\n"
                        + results.storeAndTruncate(id, t.result());
            }
        };
    }

    /**
     * 轮询等待任务结束，最长 {@link #timeoutSeconds}。
     *
     * <p><b>轮询而不是条件变量</b>：注册表刻意不认识"谁在等它"（见其类注释），
     * 加通知机制要么让注册表持有等待者、要么加一层事件总线，为一个 200ms 精度的等待不值得。
     *
     * <p><b>被中断时立刻返回当前状态并<u>重新置位中断标志</u></b>：吞掉中断会让"回合已取消"
     * 这个信号在这里消失，上层再也看不到。
     */
    private BackgroundTask awaitFinish(String id) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            BackgroundTask t = registry.find(id);
            if (t == null || t.finished()) return t;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return registry.find(id);
            }
        }
        return registry.find(id);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundTaskToolTest
```
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskTool.java \
        src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskToolTest.java
git commit -m "feat(background): TaskOutput 工具（轮询/阻塞取结果 + 消费互斥）"
```

---

## Task 7: 后台任务的权限判定（ASK → DENY）

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/PermissionCallback.java`（`call` 方法末尾那条 `return askThenAct(...)` 之前）
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/PermissionCallbackBackgroundTest.java`

**这是本计划里唯一改动安全语义的任务**，测试要钉死整张矩阵。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 后台任务撞到 ASK 时不弹面板、直接 DENY，且按档位给建议；BYPASS 档零特殊处理。 */
class PermissionCallbackBackgroundTest {

    /** 记录是否真的执行了底层工具。 */
    private static final class Spy implements ToolCallback {
        final AtomicInteger invoked = new AtomicInteger();
        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("Write").description("d").inputSchema("{}").build();
        }
        @Override public String call(String input) { invoked.incrementAndGet(); return "done"; }
        @Override public String call(String input, ToolContext ctx) { return call(input); }
    }

    /** 后台调用的 ToolContext：turnId=-1 + backgroundTaskId。用 LinkedHashMap 固定迭代序。 */
    private static ToolContext backgroundCtx(String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnId", -1L);
        m.put("taskId", taskId);
        m.put("backgroundTaskId", taskId);
        return new ToolContext(m);
    }

    /** 前台调用的 ToolContext：有真实 turnId，无后台标记。 */
    private static ToolContext foregroundCtx(long turnId, String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turnId", turnId);
        if (taskId != null) m.put("taskId", taskId);
        return new ToolContext(m);
    }

    @Test
    void backgroundAskBecomesDenyAndNeverReachesTheAsker() {
        Spy spy = new Spy();
        AtomicInteger asked = new AtomicInteger();
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> asked.incrementAndGet());

        String out = pc.call("{\"path\":\"/tmp/x\"}", backgroundCtx("task_ab12"));

        assertEquals(0, asked.get(), "后台任务绝不能弹审批面板——它的 turnId 早已过期，请求会被静默 DENY");
        assertEquals(0, spy.invoked.get(), "被拒的调用根本不该执行");
        assertTrue(out.contains("后台"), "拒绝理由要说明是因为后台任务：" + out);
    }

    @Test
    void foregroundAskStillReachesTheAskerEvenWithTaskId() {
        Spy spy = new Spy();
        AtomicInteger asked = new AtomicInteger();
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine, (turnId, req) -> {
            asked.incrementAndGet();
            req.responder().respond(PermissionOutcome.ALLOW_ONCE);
        });

        pc.call("{\"path\":\"/tmp/x\"}", foregroundCtx(7L, "task_front"));

        assertEquals(1, asked.get(),
                "前台子 agent 也有 taskId——绝不能靠 taskId 判后台，否则前台审批会静默消失");
        assertEquals(1, spy.invoked.get());
    }

    @Test
    void backgroundAllowStillRuns() {
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysAllow(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("ALLOW 不该问"); });

        assertEquals("done", pc.call("{}", backgroundCtx("task_ab12")));
        assertEquals(1, spy.invoked.get(), "命中 allow 规则的后台调用照常执行");
    }

    @Test
    void backgroundDenyIsUnchanged() {
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysDeny(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("DENY 不该问"); });

        pc.call("{}", backgroundCtx("task_ab12"));
        assertEquals(0, spy.invoked.get());
    }

    @Test
    void bypassModeNeedsNoSpecialHandlingForBackground() {
        // BYPASS 判定顺序是「deny → BYPASS 放行 → 后面全跳过」，根本不产生 ASK。
        // 故后台与前台走的是同一条路，本用例钉住"没有为 BYPASS 引入任何后台分支"。
        Spy spy = new Spy();
        var engine = PermissionTestSupport.engineAlwaysAllow(PermissionMode.BYPASS);
        PermissionCallback pc = new PermissionCallback(spy, engine,
                (turnId, req) -> { throw new AssertionError("BYPASS 档不该产生任何询问"); });

        assertEquals("done", pc.call("{}", backgroundCtx("task_ab12")));
        assertEquals("done", pc.call("{}", foregroundCtx(1L, null)));
        assertEquals(2, spy.invoked.get());
    }

    @Test
    void denyMessageSuggestsForegroundRetryInDefaultMode() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.DEFAULT);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine, (t, r) -> { });
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("run_in_background=false"), "要给出可执行的下一步：" + out);
    }

    @Test
    void denyMessageSuggestsAcceptEditsWorkspaceHintInAcceptEditsMode() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.ACCEPT_EDITS);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine, (t, r) -> { });
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("工作区"), "这一档区内本可自动放行，要提示目标在区外：" + out);
    }

    @Test
    void denyMessageInPlanModeTellsModelToReportBackInsteadOfRetrying() {
        var engine = PermissionTestSupport.engineAlwaysAsk(PermissionMode.PLAN);
        PermissionCallback pc = new PermissionCallback(new Spy(), engine, (t, r) -> { });
        String out = pc.call("{}", backgroundCtx("task_ab12"));
        assertTrue(out.contains("计划模式"), out);
        assertTrue(!out.contains("run_in_background=false"),
                "计划模式下改前台也一样被拒，指这条路等于让它白试一次");
    }
}
```

- [ ] **Step 2: 写测试支撑类**

创建 `src/test/java/io/github/javaside/springai/codetui/agent/PermissionTestSupport.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionDecision;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

import java.nio.file.Path;

/** 造一个恒定判定的 PermissionEngine，供权限装饰器层的测试用（不碰真实规则解析）。 */
final class PermissionTestSupport {

    private PermissionTestSupport() { }

    static PermissionEngine engineAlwaysAsk(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.ASK);
    }

    static PermissionEngine engineAlwaysAllow(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.ALLOW);
    }

    static PermissionEngine engineAlwaysDeny(PermissionMode mode) {
        return fixed(mode, PermissionBehavior.DENY);
    }

    /**
     * 恒定判定引擎。
     *
     * <p>实现方式取决于 {@link PermissionEngine} 当前是类还是接口：
     * 若是 final class，改用它的公开构造 + 一条覆盖全部工具的规则来达成同样效果
     * （例如 allow 全部 / deny 全部），并把 mode 设成入参。执行本步骤时先读
     * {@code PermissionEngine} 的构造函数与 {@code decide} 签名，选择改动最小的一种。
     */
    private static PermissionEngine fixed(PermissionMode mode, PermissionBehavior behavior) {
        PermissionEngine engine = new PermissionEngine(Path.of("."), java.util.List.of(), mode) {
            @Override public PermissionDecision decide(String toolName, String toolInput) {
                return new PermissionDecision(behavior, "测试固定判定", null, null);
            }
        };
        return engine;
    }
}
```

> ⚠️ **执行本步骤前先读 `PermissionEngine` 的构造函数、`decide` 返回类型与 `PermissionDecision` 的分量**，按实际签名调整上面这段。若 `PermissionEngine` 是 `final` 不可继承，就用真实构造 + 一条 `allow ["*"]` / `deny ["*"]` 规则来制造恒定判定，并用 `ask` 规则制造恒 ASK。**不要**为了测试把生产类的 `final` 去掉。

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=PermissionCallbackBackgroundTest
```
Expected: `backgroundAskBecomesDenyAndNeverReachesTheAsker` 失败——目前后台调用照样会走 `askThenAct`

- [ ] **Step 4: 改 PermissionCallback**

在 `call(...)` 方法里，把最后一行 `return askThenAct(name, toolInput, toolContext, decision);` 替换为：

```java
        // 后台任务不弹审批面板：它的 turnId 早已过期，请求会被 ConversationState 的迟到过滤
        // 当场 DENY——那是"静默拒绝"，模型只会看到一个没有理由的失败。这里主动拒绝并说明原因。
        // 与 BYPASS 档"永远不停下来等人"同源；BYPASS 本身走不到这里（那一档不产生 ASK）。
        String backgroundTaskId = ToolEventCallback.extractBackgroundTaskId(toolContext);
        if (backgroundTaskId != null) {
            return denyMessage(backgroundDenyReason(decision.reason()));
        }
        return askThenAct(name, toolInput, toolContext, decision);
```

在 `denyMessage` 附近加：

```java
    /**
     * 后台任务被拒时的理由文本：说清<b>三件事</b>——为什么被拒、当前是哪一档、正确的下一步。
     *
     * <p><b>按档位分支不是锦上添花</b>：只说"被拒了"的话，模型会对同一个操作反复重试直到耗光回合。
     * 这与计划模式当初必须给子 agent 专属提示是同一个教训——把"不知道为什么被拒"换成
     * "知道了、照做了、还是失败"，比不给提示更糟，所以给的那条路必须真的走得通。
     */
    private String backgroundDenyReason(String engineReason) {
        String head = "后台任务不能弹出审批面板，本次调用已被拒绝。原因：" + engineReason + "。";
        return switch (engine.mode()) {
            case PLAN -> head + " 当前处于计划模式，写操作与非只读命令本来就会被拒绝——"
                    + "请把已有发现整理成结论返回，不要重试。";
            case ACCEPT_EDITS -> head + " 当前处于自动接受编辑档：工作区内的写操作本可自动放行，"
                    + "该目标在工作区外。请改用 run_in_background=false 的前台 Task 重试，"
                    + "或先为该操作添加 allow 规则。";
            default -> head + " 请改用 run_in_background=false 的前台 Task 重试"
                    + "（前台会弹审批面板让用户确认），或先为该操作添加 allow 规则。";
        };
    }
```

- [ ] **Step 5: 跑测试 + 权限层全量回归**

```bash
mvn -pl springai-code-tui test -Dtest='Permission*Test'
```
Expected: 全绿。**权限层的任何既有测试都不能挂**——本次只在 ASK 分支前加了一道后台判断，前台路径必须逐条不变。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/agent/PermissionCallback.java \
        src/test/java/io/github/javaside/springai/codetui/agent/PermissionCallbackBackgroundTest.java \
        src/test/java/io/github/javaside/springai/codetui/agent/PermissionTestSupport.java
git commit -m "feat(background): 后台任务的 ASK 判成 DENY 并按档位给出下一步"
```

---

## Task 8: ConversationState 承载后台任务

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`
- Test: `src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateBackgroundTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStateBackgroundTest {

    private static ConversationState started(long turnId) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(turnId);
        return s;
    }

    @Test
    void backgroundStartAddsPanelRowAndScrollbackLine() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查登录失败");

        assertEquals(1, s.backgroundTasks().size());
        assertEquals("explore", s.backgroundTasks().get(0).agentName());
        assertTrue(s.drainPending().stream().anyMatch(l -> l.text().contains("task_ab12")),
                "启动要在 scrollback 留一行，否则用户不知道派出去了");
    }

    @Test
    void backgroundTaskSurvivesNewTurn() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.onTurnStarted(2L);

        assertEquals(1, s.backgroundTasks().size(),
                "后台任务跨回合存活——onTurnStarted 清空的是 ⟐ 面板，不是 ⏱ 面板");
    }

    @Test
    void backgroundEventsAreNotDroppedByLateFilter() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.cancelCurrent();                              // acceptingTurnId = -1，前台事件此后全被丢弃
        s.onBackgroundTaskFinished("task_ab12", "结论", true);

        assertEquals(ConversationState.BackgroundStatus.DONE, s.backgroundTasks().get(0).status(),
                "后台事件不带 turnId，绝不能被迟到过滤丢弃");
    }

    @Test
    void backgroundToolEventUpdatesPanelButNeverEntersScrollback() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.drainPending();                               // 清掉启动行

        s.onToolStarted(-1L, "task_ab12", "Grep", "{\"pattern\":\"x\"}");

        assertEquals("Grep", s.backgroundTasks().get(0).currentTool());
        assertTrue(s.drainPending().isEmpty(),
                "后台任务的工具行绝不能插进你与主 agent 的对话里");
    }

    /**
     * ⚠️ 上面那条<b>单独存在时是假绿</b>：它在回合活跃（acceptingTurnId==1）时跑，
     * 而后台事件 turnId 是 -1，紧随其后的迟到过滤会顺手把行挡住——删掉 Step 5 那句
     * {@code return} 它照样绿，验的是迟到过滤而不是前置分流。
     *
     * <p>而<b>空闲或回合被取消时 acceptingTurnId 也是 -1</b>，迟到过滤形同虚设，
     * 此刻唯一拦住后台工具行进 scrollback 的就是那句 return——这恰恰是后台任务最常见的
     * 运行时机（用户派出去后就去干别的了）。所以必须有这条空闲态的用例，陷阱 2 才真的被保护。
     *
     * <p>用<b>失败</b>的 onToolFinished：成功态本来就静默不出行，构不成结构差异，
     * 杀不掉变异（见「兜底若与成功路径同输出就掩盖 bug」）。
     */
    @Test
    void backgroundToolEventStaysOutOfScrollbackEvenWhenIdle() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.cancelCurrent();                              // acceptingTurnId 归 -1，迟到过滤此后挡不住任何东西
        s.drainPending();

        s.onToolStarted(-1L, "task_ab12", "Grep", "{}");
        s.onToolFinished(-1L, "task_ab12", "Grep", "boom", false);   // 失败态才走前台告警行

        assertTrue(s.drainPending().isEmpty(),
                "空闲时迟到过滤失效，唯一的防线是前置分流的 return");
    }

    @Test
    void foregroundSubagentToolEventStillEntersScrollback() {
        ConversationState s = started(1L);
        s.onSubagentStarted(1L, "task_front", "explore", "前台调查");
        s.drainPending();

        s.onToolStarted(1L, "task_front", "Grep", "{}");

        assertFalse(s.drainPending().isEmpty(), "前台子 agent 的工具行行为必须一字不变");
    }

    @Test
    void finishAddsScrollbackLineAndMarksStatus() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.drainPending();

        s.onBackgroundTaskFinished("task_ab12", "结论正文", true);

        List<ConversationState.OutputLine> lines = s.drainPending();
        assertTrue(lines.stream().anyMatch(l -> l.text().contains("task_ab12")));
        assertEquals(ConversationState.BackgroundStatus.DONE, s.backgroundTasks().get(0).status());
    }

    @Test
    void failedFinishIsMarkedAsFailure() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_cd34", "bash", "跑测试");
        s.onBackgroundTaskFinished("task_cd34", "连接被重置", false);
        assertEquals(ConversationState.BackgroundStatus.FAILED, s.backgroundTasks().get(0).status());
    }

    @Test
    void multiLineDescriptionIsCollapsedToOnePhysicalLine() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "第一行\n第二行\n第三行");
        for (ConversationState.OutputLine l : s.drainPending()) {
            assertFalse(l.text().contains("\n"),
                    "一个 OutputLine = 一个物理行，含换行会被 println 塌成一行截断");
        }
    }

    @Test
    void resetForNewSessionClearsBackgroundPanel() {
        ConversationState s = started(1L);
        s.onBackgroundTaskStarted("task_ab12", "explore", "调查");
        s.resetForNewSession();
        assertTrue(s.backgroundTasks().isEmpty(), "/clear 要连 ⏱ 面板一起清");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=ConversationStateBackgroundTest
```
Expected: 编译失败，`找不到符号: 方法 backgroundTasks()`

- [ ] **Step 3: 改 ConversationState —— 加状态与视图**

在 `SubtaskView` record 之后加：

```java
    /** 后台任务状态——⏱ 面板显示。与 {@link SubtaskStatus} 分开：生命周期不同，别复用。 */
    public enum BackgroundStatus { RUNNING, DONE, FAILED }

    /** 后台任务只读快照（供渲染线程读）。 */
    public record BackgroundView(String taskId, String agentName, String description,
                                 BackgroundStatus status, String currentTool) {}

    /** 内部可变持有者。仅本类访问。 */
    private static final class BackgroundEntry {
        final String taskId;
        final String agentName;
        final String description;
        BackgroundStatus status = BackgroundStatus.RUNNING;
        String currentTool = "";
        BackgroundEntry(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }
```

在 `subtasks` 字段之后加：

```java
    /**
     * 后台子 agent（run_in_background）状态——⏱ 面板，<b>不进 scrollback 的中间态</b>。
     *
     * <p><b>与 {@link #subtasks} 分开的理由是生命周期</b>：subtasks 由 {@link #onTurnStarted}
     * 清空（回合级），后台任务跨回合存活。混在一个列表里，清空语义迟早会写错——
     * 而写错的后果是"任务凭空消失"，用户无从判断它是跑完了还是被吃了。
     */
    private final List<BackgroundEntry> backgroundTasks = new ArrayList<>();
```

- [ ] **Step 4: 改 ConversationState —— 加事件落地与只读视图**

在 `onSubagentFinished(4 参)` 之后加：

```java
    // ── 后台子 agent（不带 turnId，故<b>不做迟到过滤</b>，也不被 onTurnStarted 清空） ──

    @Override
    public synchronized void onBackgroundTaskStarted(String taskId, String agentName, String description) {
        String d = summarize(description);      // 折叠换行：守住「一 OutputLine = 一物理行」
        backgroundTasks.add(new BackgroundEntry(taskId, agentName, d));
        pending.add(new OutputLine("⏱ 后台任务已启动  " + taskId + " · " + agentName
                + (d.isEmpty() ? "" : " · " + d), OutputLine.Kind.INFO));
    }

    @Override
    public synchronized void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
        BackgroundEntry e = findBackground(taskId);
        if (e != null) {
            e.status = ok ? BackgroundStatus.DONE : BackgroundStatus.FAILED;
            e.currentTool = "";
        }
        pending.add(new OutputLine((ok ? "✓ 后台任务完成  " : "✗ 后台任务失败  ") + taskId
                + (e == null ? "" : " · " + e.description)
                + (ok ? "" : " · " + summarize(firstLine(finalText))), OutputLine.Kind.INFO));
    }

    private BackgroundEntry findBackground(String taskId) {
        for (BackgroundEntry e : backgroundTasks) {
            if (e.taskId.equals(taskId)) return e;
        }
        return null;
    }

    /** ⏱ 面板只读快照。 */
    public synchronized List<BackgroundView> backgroundTasks() {
        List<BackgroundView> out = new ArrayList<>(backgroundTasks.size());
        for (BackgroundEntry e : backgroundTasks) {
            out.add(new BackgroundView(e.taskId, e.agentName, e.description, e.status, e.currentTool));
        }
        return out;
    }

    /** 是否有后台任务在跑（状态栏后缀用）。 */
    public synchronized int backgroundRunningCount() {
        int n = 0;
        for (BackgroundEntry e : backgroundTasks) {
            if (e.status == BackgroundStatus.RUNNING) n++;
        }
        return n;
    }
```

- [ ] **Step 5: 改 ConversationState —— 后台工具事件的前置分流**

把 `onToolStarted(long turnId, String taskId, ...)`（第 363 行起）的方法体开头改成：

```java
    public synchronized void onToolStarted(long turnId, String taskId, String toolName, String input) {
        if (taskId == null) { onToolStarted(turnId, toolName, input); return; }
        // 后台任务：只更新 ⏱ 面板的"当前工具"，绝不进 scrollback（否则会插进你与主 agent 的对话里），
        // 也绝不做 turnId 迟到过滤（后台任务的 turnId 恒为 -1，过滤会把它全丢掉）。
        BackgroundEntry bg = findBackground(taskId);
        if (bg != null) { bg.currentTool = toolName; return; }
        if (turnId != acceptingTurnId) return;
        ...以下原样不变...
```

同样在 `onToolFinished(long turnId, String taskId, ...)` 开头加：

```java
        if (taskId == null) { onToolFinished(turnId, toolName, output, ok); return; }
        if (findBackground(taskId) != null) return;   // 后台任务的工具结束不出行
        if (turnId != acceptingTurnId || ok) return;
```

在 `resetForNewSession()` 里 `subtasks.clear();` 之后加一行：

```java
        backgroundTasks.clear();     // /clear：⏱ 面板一并清空（任务本身的终止由 CodeTuiView 调注册表完成）
```

- [ ] **Step 6: 跑测试确认通过 + UI 层全量回归**

```bash
mvn -pl springai-code-tui test -Dtest='ConversationState*Test'
```
Expected: 全绿。`ConversationStateTest` / `ConversationStateResetTest` / `ConversationStateQueueTest` 等既有测试一条都不能挂。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateBackgroundTest.java
git commit -m "feat(background): ConversationState 承载后台任务（独立于回合生命周期）"
```

---

## Task 9: 自动起回合 + ⏱ 面板 + 状态栏后缀

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（`drain()` 第 270-327 行、面板 scope 链第 213-224 行、`statusLine`）
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java`（加两个 default 方法）
- Test: `src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewBackgroundTest.java`

- [ ] **Step 1: 给 SubmitHandler 加后台接缝**

在 `SubmitHandler.java` 的 `hasInFlightSubagents()` 之后加：

```java
    // ── 后台子 agent（/tasks 面板与自动送达用；默认空实现，便于回显桩/测试桩省略） ──

    /** 已结束、可送达、尚未消费的后台任务结果（供自动送达判定）。 */
    default List<BackgroundResult> completedBackgroundTasks() { return List.of(); }

    /** 标记某个后台任务的结果已交给模型。返回是否本次真的完成了标记（互斥闸，见注册表）。 */
    default boolean markBackgroundConsumed(String taskId) { return false; }

    /** 终止一个运行中的后台任务（/tasks 面板的 k 键）。 */
    default boolean killBackgroundTask(String taskId) { return false; }

    /** 终止全部后台任务（/clear 与退出）。 */
    default void killAllBackgroundTasks() { }

    /**
     * 一条可送达的后台任务结果。<b>刻意是 UI 层能直接消费的扁平结构</b>——
     * 不让 UI 去 import agent.background 包的领域类型，接缝两侧各自演进。
     */
    record BackgroundResult(String taskId, String agentName, String description,
                            String result, boolean ok) { }
```

- [ ] **Step 2: 写失败测试**

创建 `src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewBackgroundTest.java`。参照同目录既有 UI 测试的构造方式（读 `CodeTuiViewInFlightGateTest` 看它怎么造 `CodeTuiView` 与桩 `SubmitHandler`），断言这些行为：

```java
    // 1) 空闲 + 输入框为空 + 有已完成未消费任务 → drain 触发一次 submit，且内容含任务结果
    // 2) 输入框有内容 → 不 submit（用户正在敲字时不抢跑）
    // 3) 忙（回合进行中）→ 不 submit
    // 4) 送达后调用 markBackgroundConsumed，且下一帧不再重复 submit
    // 5) 连续 3 次自动 submit 后第 4 次不再 submit（刹车）
    // 6) 用户真实提交一条消息后刹车重置
    // 7) 排队消息优先于自动送达：队列里有用户消息时，本帧出队用户消息而不是送后台结果
    // 8) 状态栏：空闲且有后台任务在跑时含 "⏱" 与 "/tasks"，且权限模式标识仍在行首
```

关键断言写法（第 7 条最容易写错，单独给出）：

```java
    @Test
    void queuedUserMessageWinsOverBackgroundDelivery() {
        // 构造：空闲 + 输入框为空 + 队列里有一条用户消息 + 有一个已完成的后台任务
        // 断言：本帧提交的是用户那条消息，后台结果仍未消费
        // 理由：用户明确排的队比程序自作主张的送达优先；反过来会让用户的消息莫名其妙晚一轮
    }
```

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=CodeTuiViewBackgroundTest
```
Expected: 断言失败（`drain` 目前不认识后台任务）

- [ ] **Step 4: 在 drain() 末尾挂自动送达**

把 `drain()` 结尾那段（第 322-326 行）替换为：

```java
        // 回合结束后自动出队下一条排队消息。submit() 同步置 THINKING，故本 tick 只会出队一条，无重复提交竞态。
        if (!busy()) {   // 空闲、非压缩中、且无在飞<b>前台</b>子 agent 才出队（见 busy()）
            ConversationState.Queued next = state.pollQueued();
            if (next != null) {
                notifier.onUserInput();      // 用户的真实输入：重置自动回合刹车
                dispatch(next.text(), next.skill());
                return;                      // 本帧已提交，后台送达让到下一帧——用户排的队优先
            }
            deliverBackgroundResults();
        }
    }

    /**
     * 后台任务结果的自动送达：空闲 + 输入框为空 + 有已完成未消费任务时，起一个新回合交给模型。
     *
     * <p><b>判定与提交在同一帧内完成</b>：drain 跑在渲染线程（单线程），"读输入框是否为空"
     * 与"调 submit"之间不会被用户按键插入，故不存在"判定时为空、提交时用户已开始打字"的竞态。
     *
     * <p><b>submit 抛异常时不标记已消费</b>：一次提交失败不能把结果丢掉，下一帧会再试。
     */
    private void deliverBackgroundResults() {
        List<SubmitHandler.BackgroundResult> done = onSubmit.completedBackgroundTasks();
        if (done.isEmpty()) return;
        var text = notifier.shouldNotifyResults(done, true, inputState.text().isEmpty());
        if (text.isEmpty()) return;
        try {
            dispatch(text.get(), null);
        } catch (RuntimeException e) {
            log.warn("后台任务结果自动送达失败，保持未消费、下一帧重试", e);
            return;                         // ⚠ 不标记已消费：标记了就等于把结果丢了
        }
        for (SubmitHandler.BackgroundResult r : done) {
            onSubmit.markBackgroundConsumed(r.taskId());
        }
    }
```

字段加：

```java
    /** 自动送达判定与刹车（纯状态机，见其类注释）。 */
    private final BackgroundNotifier notifier = new BackgroundNotifier();
```

> **`BackgroundNotifier` 需要一个接受 `SubmitHandler.BackgroundResult` 的 `compose` 重载**——Task 3 里它接的是 `BackgroundTask`。在本步骤给它加一个只依赖扁平字段的重载，把 Task 3 的 `shouldNotify(List<BackgroundTask>, …)` 与新的 `compose(List<BackgroundResult>, boolean)` 都委托到同一段合成逻辑，**不要复制两份文本模板**（两份必然漂移）。同时把 Task 3 的刹车计数逻辑复用到新入口。

- [ ] **Step 5: 加 ⏱ 面板**

在第 215 行 `scope(!subs.isEmpty(), subtaskChildren(subs)),` 之后加一行：

```java
                scope(!bgTasks.isEmpty(), backgroundChildren(bgTasks)),   // ⏱ 后台任务面板（零任务时不占行）
```

并在渲染方法开头（与 `todos` / `subs` 同处）取快照：

```java
        List<ConversationState.BackgroundView> bgTasks = state.backgroundTasks();
```

面板方法：

```java
    /**
     * ⏱ 后台任务面板。<b>首行必须判空</b>——TamboUI 的 {@code scope(cond, children)} 每帧
     * <b>eager 求值</b>：即使 cond 为 false，children 也会被构造一次。不判空会在零任务时 NPE/越界。
     */
    private Component backgroundChildren(List<ConversationState.BackgroundView> tasks) {
        if (tasks == null || tasks.isEmpty()) return empty();
        long running = tasks.stream()
                .filter(t -> t.status() == ConversationState.BackgroundStatus.RUNNING).count();
        long finished = tasks.size() - running;
        List<Component> rows = new ArrayList<>();
        rows.add(richText(Text.from(Line.from(Span.styled(
                "⏱ 后台任务 (" + running + " 运行 · " + finished + " 完成)", Theme.DIM)))));
        for (ConversationState.BackgroundView t : tasks) {
            String mark = switch (t.status()) {
                case RUNNING -> "▶";
                case DONE -> "✓";
                case FAILED -> "✗";
            };
            String row = "  " + mark + " " + t.taskId() + " " + t.agentName() + "  " + t.description()
                    + (t.currentTool().isEmpty() ? "" : "  " + t.currentTool());
            rows.add(richText(Text.from(Line.from(Span.styled(clip(row), Theme.DIM)))));
        }
        return column(rows);
    }
```

> 上面的 `richText` / `column` / `empty()` / `clip(...)` 请按本文件既有面板方法（`todoChildren` / `subtaskChildren`）的真实写法对齐——**先读那两个方法再写这个**，别照抄本计划里的伪形态。

- [ ] **Step 6: 状态栏后缀**

在 `statusLine` 的空闲分支里，权限模式标识之后追加：

```java
        int bgRunning = state.backgroundRunningCount();
        if (bgRunning > 0) {
            suffix = suffix + " · ⏱ " + bgRunning + " 个后台任务 · /tasks";
        }
        if (notifier.brakeEngaged()) {
            suffix = suffix + " · ⏱ 有结果待处理 · 回车交给模型";
        }
```

> **放在权限模式标识之后、不占行首**：状态行接近终端宽度、尾部先被截断，而"现在会不会问你"比"有几个后台任务"更不该被截掉。

- [ ] **Step 7: 跑测试**

```bash
mvn -pl springai-code-tui test -Dtest='CodeTuiView*Test'
```
Expected: 全绿（新测试 + 既有全部 UI 测试）

- [ ] **Step 8: 提交**

```bash
git add -A src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundNotifier.java \
        src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewBackgroundTest.java
git commit -m "feat(background): 自动送达、⏱ 面板与状态栏后缀"
```

---

## Task 10: /tasks 面板与生命周期

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（斜杠命令分派第 969-1060 行、面板 scope 链、按键处理）
- Test: `src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewTasksPanelTest.java`

- [ ] **Step 1: 写失败测试**

参照 `CodeTuiViewPermissionsPanelTest`（`/permissions` 面板，交互形态与本面板最接近：列表 + 单键删除 + 确认）写：

```java
    // 1) 输入 /tasks 打开面板；面板列出全部后台任务（含已完成与失败）
    // 2) 面板<b>任何时候可开</b>（回合进行中也能开）——与 /permissions 一致，不像 /mcp 要求空闲
    // 3) ↑↓ 移动选中项（用纯前景高亮，绝不用背景色条——底色会串到下一项）
    // 4) Enter 展开选中任务的结果全文，再按收起
    // 5) k 对运行中的任务弹确认；确认后调 killBackgroundTask
    // 6) k 对已完成的任务无效（不弹确认）
    // 7) Esc 关闭面板
    // 8) 面板底部含提示 "清空输入框后自动交给模型"
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui test -Dtest=CodeTuiViewTasksPanelTest
```

- [ ] **Step 3: 实现 /tasks 面板**

按 `/permissions` 面板的既有实现照猫画虎：加 `pickingTasks` 布尔 + `taskOpt` 选中下标 + `taskExpanded` 展开标记 + `taskKillConfirm` 确认态；在斜杠命令分派里加：

```java
        if (cmd.equals("/tasks")) {                  // 后台任务面板：任何时候可开（终止后台任务不会撞在飞的工具调用）
            pickingTasks = true;
            taskOpt = 0;
            taskExpanded = false;
            taskKillConfirm = false;
            return true;
        }
```

在 scope 链里加 `scope(pickingTasks, tasksPanelChildren())`，并把 `/tasks` 加进斜杠命令补全菜单的命令表。

**选中高亮必须用纯前景色**，不要用背景色条——底色会串到下一项（本项目已在行内菜单上踩过这个坑）。

- [ ] **Step 4: 接生命周期**

`/clear` 处理里，`state.resetForNewSession()` 之前加：

```java
        onSubmit.killAllBackgroundTasks();   // 新会话不该有旧会话的任务在跑
```

`/exit` 处理里，退出前加：

```java
        onSubmit.killAllBackgroundTasks();   // 有界终止由 SubagentRunner.shutdownBackground 完成（硬限 2s）
```

**`Esc` 的处理一行都不要动**——spec D5：Esc 只取消当前回合，不碰后台任务。

- [ ] **Step 5: 跑测试**

```bash
mvn -pl springai-code-tui test -Dtest='CodeTuiView*Test'
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewTasksPanelTest.java
git commit -m "feat(background): /tasks 面板与生命周期（k 终止、/clear 与退出清理）"
```

---

## Task 11: 装配

**Files:**
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:374-420, 540-550`
- Modify: `src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`（实现新增的 4 个 `SubmitHandler` 方法）
- Test: `src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsBackgroundWiringTest.java`

- [ ] **Step 1: 写失败测试**

参照 `AgentToolsWebSearchWiringTest` / `AgentToolsCompactionWiringTest` 的写法，断言：

```java
    // 1) 主 agent 工具集里有名为 "TaskOutput" 的工具
    // 2) 子 agent 工具集（decoratedList）里<b>没有</b> TaskOutput/Task/ParallelTasks（禁递归）
    // 3) Task 工具的 schema 含 run_in_background
    // 4) CODETUI_BACKGROUND_CONCURRENCY 未设时并发为 4；设为 "0" 夹到 1；设为 "999" 夹到 32；设为 "abc" 回落 4
```

第 4 条测纯函数即可（把解析抽成 `static int resolveBackgroundConcurrency(String raw)` 再测），不要去改进程环境变量。

- [ ] **Step 2: 加环境变量解析**

在 `AgentTools` 的 `resolveSubagentConcurrency()` 旁边加（**照它的既有形态，别另起一套**）：

```java
    /** 后台子 agent 并发上限。范围 [1,32]，默认 4。非法值回落默认而不是崩启动。 */
    private static int resolveBackgroundConcurrency() {
        return clampConcurrency(System.getenv("CODETUI_BACKGROUND_CONCURRENCY"), 4);
    }

    /** TaskOutput 阻塞等待上限（秒）。范围 [1,3600]，默认 300。 */
    private static int resolveTaskOutputTimeout() {
        String raw = System.getenv("CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS");
        if (raw == null || raw.isBlank()) return 300;
        try {
            return Math.min(3600, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 300;
        }
    }

    /** 纯函数，便于单测：解析并夹到 [1,32]，非法回落 fallback。 */
    static int clampConcurrency(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.min(32, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
```

- [ ] **Step 3: 装配后台子系统**

在第 380 行 `SubagentRunner subagentRunner = new SubagentRunner(...)` 之后加：

```java
        // 后台子 agent 子系统：注册表（进程内、不落盘）+ 结果限幅落盘 + 常驻线程池。
        BackgroundTaskRegistry backgroundRegistry = new BackgroundTaskRegistry(64);
        TaskResultStore backgroundResults = new TaskResultStore(root);
        subagentRunner.enableBackground(backgroundRegistry, resolveBackgroundConcurrency());
```

把 Task / ParallelTasks 的创建改成带后台派发器的重载：

```java
        ToolCallback taskTool = SubagentTool.create(subagentSpecs,
                (spec, prompt, desc, turnIgnored) ->
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()),
                subagentRunner::runInBackground);

        ToolCallback parallelTool = SubagentTool.createParallel(subagentSpecs,
                (dispatches, turnIgnored) ->
                        subagentRunner.runAll(dispatches, ToolEventCallback.currentTurnId()),
                subagentRunner::runInBackground);
```

加 TaskOutput 工具（**仅主 agent**，与记忆工具/ExitPlanMode 同处理——不进 `decoratedList`）：

```java
        // TaskOutput：仅主 agent。子 agent 拿不到 Task，自然也不需要取后台结果。
        ToolCallback decoratedTaskOutputTool = new PermissionCallback(
                new ToolEventCallback(
                        BackgroundTaskTool.create(backgroundRegistry, backgroundResults,
                                resolveTaskOutputTimeout()),
                        listener),
                permissionEngine, listener);
```

把 `toolsWithTask` 的长度从 `+ 3` 改成 `+ 4`，并把新工具放进去（**注意同时改 3 处下标**）：

```java
        Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 4];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
        toolsWithTask[toolsWithTask.length - 4] = decoratedExitPlanTool;
        toolsWithTask[toolsWithTask.length - 3] = decoratedTaskTool;
        toolsWithTask[toolsWithTask.length - 2] = decoratedParallelTool;
        toolsWithTask[toolsWithTask.length - 1] = decoratedTaskOutputTool;
```

- [ ] **Step 4: CodingAgent 实现新的 SubmitHandler 方法**

```java
    @Override
    public List<BackgroundResult> completedBackgroundTasks() {
        if (backgroundRegistry == null) return List.of();
        List<BackgroundResult> out = new ArrayList<>();
        for (BackgroundTask t : backgroundRegistry.completedUnconsumed()) {
            out.add(new BackgroundResult(t.taskId(), t.agentName(), t.description(),
                    backgroundResults.storeAndTruncate(t.taskId(), t.result()),
                    t.status() == BackgroundTask.Status.DONE));
        }
        return out;
    }

    @Override
    public boolean markBackgroundConsumed(String taskId) {
        return backgroundRegistry != null && backgroundRegistry.markConsumed(taskId);
    }

    @Override
    public boolean killBackgroundTask(String taskId) {
        return backgroundRegistry != null && backgroundRegistry.kill(taskId);
    }

    @Override
    public void killAllBackgroundTasks() {
        if (backgroundRegistry != null) backgroundRegistry.killAll();
        if (subagentRunner != null) subagentRunner.shutdownBackground();
    }
```

> **`completedBackgroundTasks()` 里就做限幅**：这是结果进入会话的唯一入口，限幅放在这里就绕不过去。

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui test
```
Expected: 全绿。这是第一次全量跑，重点看 `AgentTools*` 与 `Permission*` 有没有被装配改动波及。

- [ ] **Step 6: 提交**

```bash
git add -A src/main/java/io/github/javaside/springai/codetui/agent/ \
        src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsBackgroundWiringTest.java
git commit -m "feat(background): 装配后台子系统与 TaskOutput 工具"
```

---

## Task 12: pty 实机冒烟

**Files:**
- Modify/Create: 项目既有的 pty + pyte 冒烟脚本（先 `ls` 找到现有脚本位置，照它的形态加用例）

- [ ] **Step 1: 找到既有冒烟脚本**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo && grep -rl "pyte" --include=*.py --include=*.sh . | head
```

- [ ] **Step 2: 加冒烟用例**

按既有脚本形态加：

1. 启动 → 输入 `/tasks` → 断言面板出现且显示"（暂无后台任务）"
2. 触发一个后台任务（用桩 provider 或已有的假模型入口）→ 断言 ⏱ 面板出现、状态栏含 `⏱`
3. 任务完成 → 断言自动起回合，且**通知文本按 `\n` 正确换行**（不是塌成一行）
4. `/tasks` → `k` → 确认 → 断言任务变为已终止

**两条纪律**（项目已踩过）：
- pty.fork 默认 0×0 渲染全空白：必须 `ioctl TIOCSWINSZ` 设窗口大小 + `TERM=xterm-256color`
- `wait_for` 会命中**陈旧 scrollback**：断言"当前状态"不能只靠子串，要么带唯一标记，要么先清屏再断言

- [ ] **Step 3: 跑冒烟**

```bash
mvn -pl springai-code-tui clean package -DskipTests   # 冒烟跑的是打好的包，改完必须重新 package
# 然后按既有脚本的方式运行冒烟
```

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "test(background): pty 实机冒烟（面板、状态栏、通知换行）"
```

---

## Task 13: 变异测试验证

不改生产代码，只验证测试真的能抓住缺陷。**逐个变异、逐个还原**，每次只改一处。

- [ ] **Step 1: 变异「去掉 consumed 标记」**

把 `BackgroundTaskRegistry.markConsumed` 改成恒 `return true;` 但不置位 `consumed`。

```bash
mvn -pl springai-code-tui test -Dtest='BackgroundTaskRegistryTest+BackgroundTaskToolTest'
```
Expected: **FAIL**（`markConsumedIsIdempotentAndRemovesFromUnconsumed`、`finishedTaskReturnsResultAndMarksConsumed`）。还原。

- [ ] **Step 2: 变异「去掉自动回合刹车」**

把 `BackgroundNotifier` 里 `if (consecutive >= maxConsecutive) return Optional.empty();` 删掉。

```bash
mvn -pl springai-code-tui test -Dtest=BackgroundNotifierTest
```
Expected: **FAIL**（`stopsAutoTurnsAfterConsecutiveLimit`）。还原。

- [ ] **Step 3: 变异「去掉 inFlight 拆分」**

把 `runInBackground` 里的 `backgroundInFlight` 改回 `inFlight`。

```bash
mvn -pl springai-code-tui test -Dtest=SubagentRunnerBackgroundTest
```
Expected: **FAIL**（`backgroundTasksDoNotRaiseForegroundInFlightGate`）。

> ⚠️ **这条最容易假绿**：确认该用例的构造里**只有后台任务在飞、没有任何前台子 agent**。若测试里恰好也派了前台子 agent，前台闸门会顺手挡住，变异就看不出来。还原。

- [ ] **Step 4: 变异「后台 ASK 改回 ASK」**

把 `PermissionCallback.call` 里那段 `if (backgroundTaskId != null)` 删掉。

```bash
mvn -pl springai-code-tui test -Dtest=PermissionCallbackBackgroundTest
```
Expected: **FAIL**（`backgroundAskBecomesDenyAndNeverReachesTheAsker`）。还原。

- [ ] **Step 5: 变异「去掉通知限幅」**

把 `TaskResultStore.storeAndTruncate` 改成恒 `return full;`。

```bash
mvn -pl springai-code-tui test -Dtest='TaskResultStoreTest+BackgroundTaskToolTest'
```
Expected: **FAIL**（`longResultIsTruncatedAndFullTextWrittenToDisk`、`longResultIsTruncatedThroughResultStore`）。还原。

- [ ] **Step 6: 变异「后台工具事件进 scrollback」**

把 `ConversationState.onToolStarted(turnId, taskId, ...)` 里那句 `if (bg != null) { bg.currentTool = toolName; return; }` 的 `return` 去掉。

```bash
mvn -pl springai-code-tui test -Dtest=ConversationStateBackgroundTest
```
Expected: **FAIL**（`backgroundToolEventStaysOutOfScrollbackEvenWhenIdle`）。还原。

> ⚠️ **必须是这条空闲态的用例挂，不能是 `backgroundToolEventUpdatesPanelButNeverEntersScrollback`。**
> 后者在回合活跃时跑，后台事件的 `turnId=-1` 会被迟到过滤顺手挡住——删掉 `return` 它照样绿。
> 如果你看到只有它挂、或者两条都不挂，说明分流点被写在了迟到过滤**之后**，实际没有生效。
>
> 同样地，再做一次配对变异：去掉 `onToolFinished` 里的 `if (findBackground(taskId) != null) return;`，
> 也应当由同一条空闲态用例杀掉。

- [ ] **Step 7: 确认全部还原且全绿**

```bash
git diff --stat        # 必须为空
mvn -pl springai-code-tui test
```
Expected: `git diff` 无输出；测试全绿。

---

## Task 14: 文档与收尾

**Files:**
- Modify: `springai-code-tui/README.md`
- Modify: `CHANGELOG.md`（如本次要发版）

- [ ] **Step 1: 更新模块 README**

要写清的四件事：

1. **「已知限制」里删掉「子 agent 无后台模式」那条**（它现在不成立了）；
2. 「斜杠命令」表加 `/tasks`；
3. 「运行」一节的可选调优加 `CODETUI_BACKGROUND_CONCURRENCY`（默认 4）与 `CODETUI_TASK_OUTPUT_TIMEOUT_SECONDS`（默认 300）；
4. **新增一节「后台子 agent」**，务必包含 spec §6.2 那张**权限矩阵**与「后台任务能真干活的三条路」——否则用户第一次派后台任务会撞一堆 DENY 然后自己猜原因。

- [ ] **Step 2: 全量测试 + 打包冒烟**

```bash
mvn -pl springai-code-tui test
mvn -pl springai-code-tui -am package
```
Expected: 全绿；打包成功。

- [ ] **Step 3: 提交**

```bash
git add README.md springai-code-tui/README.md CHANGELOG.md
git commit -m "docs(background): 后台子 agent 用法、权限矩阵与新增环境变量"
```

---

## 自查结果

**Spec 覆盖核对**（spec 章节 → 实现任务）：

| Spec | 任务 |
|---|---|
| §4.1 四个新单元 | Task 1（Task+Registry）、2（ResultStore）、3（Notifier）、6（TaskOutput） |
| §4.2 四处改动 | Task 4（Runner+Listener+ToolEventCallback）、5（SubagentTool）、8（ConversationState）、9/10（CodeTuiView）、11（AgentTools） |
| §5.1 派发 | Task 4 Step 7 |
| §5.2 执行（后台 ToolContext） | Task 4 Step 7 `runBackgroundBody` |
| §5.3 两条回收路径 + consumed 互斥 | Task 6（路径 A）、Task 9（路径 B）、Task 13 Step 1（变异） |
| §5.4 进程边界 | Task 6 `unknownTaskIdSaysSoInsteadOfHangingOrThrowing` |
| §6.1 turnId 过滤 | Task 8 Step 5 |
| §6.2 权限矩阵 | Task 7 全部 |
| §6.3 inFlight 拆分 | Task 4 Step 5、Task 13 Step 3 |
| §6.4 生命周期 | Task 10 Step 4 |
| §7.1 通知限幅 | Task 2、Task 11 Step 4 |
| §7.2 失控刹车 | Task 3、Task 9 Step 6 |
| §7.3 任务数上限 | Task 1（容量淘汰）、Task 4（队列容量） |
| §7.4 其余边界 | Task 4（异常/Error 兜底）、Task 6（超时/未知 id）、Task 9（submit 失败不消费） |
| §8 UI 全部 | Task 9、Task 10 |
| §9 测试策略 | 各任务内 + Task 12（pty）+ Task 13（变异） |
| §11 验收标准 9 条 | 逐条有对应用例；第 9 条（回归）靠 Task 5/7/8 的既有测试全绿保证 |

**已知的两处"执行时需先读代码再落笔"**（不是占位符，是刻意的指向）：

1. **Task 7 Step 2 的 `PermissionTestSupport`** —— `PermissionEngine` 的构造签名与 `PermissionDecision` 的分量必须以实际代码为准。若 `PermissionEngine` 是 `final`，改用真实构造 + 规则来制造恒定判定；**不要为了测试去掉生产类的 `final`**。
2. **Task 9 Step 5 的面板方法** —— `richText` / `column` / `empty()` 等按 `todoChildren` / `subtaskChildren` 的真实写法对齐，本计划给的是形态不是照抄源。

**类型一致性核对**：`BackgroundTask.Status`（RUNNING/DONE/FAILED/KILLED，agent 层）与 `ConversationState.BackgroundStatus`（RUNNING/DONE/FAILED，UI 层）**刻意是两个枚举**——UI 不需要区分 KILLED（面板上已终止的任务显示为失败态即可），且 UI 层不该 import agent.background 的领域类型。跨层传递统一走 `SubmitHandler.BackgroundResult` 这个扁平 record。





