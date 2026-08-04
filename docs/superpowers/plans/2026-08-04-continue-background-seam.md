# `/continue` 与后台子 agent 接缝 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `/continue` 知道后台任务的存在（别重复劳动），并让模型能随时列出自己派出去的后台任务。

**Architecture:** 新增一个纯函数 `BackgroundDigest` 作为**唯一的措辞出处**，三条路径（`/continue` 提示词注入、`ListTasks` 工具、`TaskOutput` 查无此 id）共用它。UI 不直接碰注册表——经 `SubmitHandler` 门面取一次快照。

**Tech Stack:** Java 17 / Maven / JUnit 5 / Spring AI 2.0 `FunctionToolCallback` / pty + pyte 实机冒烟

**Spec:** `docs/superpowers/specs/2026-08-04-continue-background-seam-design.md`

---

## 全局纪律

**0. 测试命令必须带 `-pl springai-code-tui`。** 整仓 `mvn test -Dtest=...` 会被三个空模块打挂。
多个类用逗号分隔：`-Dtest='A,B'`，**不是** `'A+B'`。

**1. `/continue` 的本意是「把没做完的接着做完」，不判断该不该重试。**
这条是 spec §1 的硬约束。实现中任何「先看看失败原因再决定」的分支都是越权。
**FAILED / KILLED 不做任何特殊处理**——它们就是普通的「没做完」，模型看 todo 自然会重派。
Task 1 有一条测试专门钉这个。

**2. 只取一次快照。** `BackgroundDigest` 接 `List<BackgroundTask>`，不接注册表。
注册表方法都是 `synchronized`，调用方取一次 `all()` 就够；让 digest 持有注册表会诱使实现里
取两次，而两次之间状态会变。

**3. 提示词不含结果正文。** 清单只列 id / agent / 描述 / 状态。塞正文有两个坏处：
撑爆 `/continue` 的提示词，以及**绕过 `markConsumed` 互斥**——结果被模型看到了却没标记已消费，
之后自动送达会再送一遍。

**4. 行号是快照，会漂移。** 靠方法名/原文串定位，别盲信行号。

**5. 编译错不是合格的「红」。** 每个任务至少要有**一次断言失败**的红，光有编译错不够——
编译错时断言一条都没执行过，跟「拿一个空文件当红」没区别，证不了测试真接上了缺陷。

- **新建类的任务**（Task 1）：首次必然是编译错（类还不存在），无法避免；
  真正的凭据是那一步**变异验证**（改实现让断言红），所以那一步不能省。
- **改既有类的任务**（Task 2）：编译错之后要**再红一次**——先只补接口/门面让它编译通过、
  **故意不改真正的行为点**，此时断言才生效。两条出口若各自独立地红了，
  才说明它们不是搭彼此的便车。
- Task 3 / Task 4 的 Step 2 本就是行为红（断言失败），不受此条影响。

这条是被实施过程反复验证出来的：Task 1 与 Task 2 各自独立地踩了一次
「红的理由是错的」，而它们都是**照计划做的**——计划自己在一处写了警告，
却在另一处犯了同样的错。

---

## 文件结构

| 文件 | 责任 | 动作 |
| --- | --- | --- |
| `agent/background/BackgroundDigest.java` | 快照 → 给模型看的文本。纯函数、无状态 | **新建** |
| `agent/background/BackgroundTaskListTool.java` | `ListTasks` 工具 | **新建** |
| `agent/background/BackgroundTaskTool.java` | 查无此 id 时附上清单 | 改 |
| `agent/SubmitHandler.java` | 加 `backgroundDigestForContinue()` | 改 |
| `agent/CodingAgent.java` | 实现它：取快照 → 调 digest | 改 |
| `agent/AgentTools.java` | 注册 `ListTasks`（**仅主 agent**） | 改 |
| `ui/CodeTuiView.java` | `/continue` 分支拼接 digest | 改 |
| `src/test/resources/scripts/background_smoke.py` | 补 `StubModel.sent` + 新增一幕 | 改 |

`BackgroundTaskListTool` 单独成文件而不塞进 `BackgroundTaskTool`：后者的类名是单数、
职责是「取一个任务的结果」，塞进去会让类名说谎。

---

### Task 1: `BackgroundDigest` 纯函数

这是全部四条路径的措辞出处，也是本次唯一有实质逻辑的单元。**先做，后面三个任务都依赖它。**

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundDigest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundDigestTest.java`

**背景**：`BackgroundTask` 的可用访问器（都已存在、都是 public）：
`taskId()` / `agentName()` / `description()` / `status()` / `result()` / `consumed()` /
`finished()` / `deliverable()`。状态枚举 `BackgroundTask.Status` 有
`RUNNING` / `DONE` / `FAILED` / `KILLED`。

**注册表侧**：`BackgroundTaskRegistry.register(agentName, description)` 返回 taskId；
`complete(taskId, result, ok)` 把它推到 DONE(ok=true) / FAILED(ok=false)；
`kill(taskId)` 推到 KILLED；`markConsumed(taskId)` 置消费位；`all()` 返回快照。
测试里用这些方法造数据，**不要**去 new `BackgroundTask`（它的构造是包私有的）。

- [ ] **Step 1: 写失败的测试**

新建 `BackgroundDigestTest.java`：

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackgroundDigest}：给模型看的后台任务摘要。
 *
 * <p><b>本类钉住的核心语义是「哪些状态需要特殊处理」</b>：只有 RUNNING 与 DONE-未消费
 * 会进 {@code forContinue}——因为只有它们会导致<b>重复劳动</b>。FAILED / KILLED 是普通的
 * 「没做完」，模型看 todo 自然会重派，而重派正是 /continue 的定义。
 */
class BackgroundDigestTest {

    /** 造一个处于指定状态的注册表。容量给足，避免淘汰干扰断言。 */
    private static BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(64);
    }

    @Test
    @DisplayName("RUNNING：点名 id 并告诉模型不要重复委派")
    void runningTellsModelNotToRedispatch() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "扫描鉴权相关代码");

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "必须点名 taskId，否则模型无从对应:\n" + s);
        assertTrue(s.contains("explore"), "应带上 agent 名:\n" + s);
        assertTrue(s.contains("扫描鉴权相关代码"), "应带上描述:\n" + s);
        assertTrue(s.contains("不要重复委派"), "RUNNING 的行动指引是「别重派」:\n" + s);
    }

    @Test
    @DisplayName("DONE 未消费：让模型先取回结果，别重做")
    void doneUnconsumedTellsModelToFetch() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "写完了 5 个用例", true);

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "必须点名 taskId:\n" + s);
        assertTrue(s.contains("TaskOutput"), "应指出用哪个工具取回:\n" + s);
        assertTrue(s.contains("可能已经做完"), "要点破「这个 todo 也许已经完成了」:\n" + s);
    }

    @Test
    @DisplayName("DONE 但已消费：不再出现——结果早送出去了，再提一遍是噪声")
    void doneConsumedIsOmitted() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "写完了", true);
        r.markConsumed(id);

        String s = BackgroundDigest.forContinue(r.all());

        assertFalse(s.contains(id), "已消费的结果不该再提，实际:\n" + s);
    }

    /**
     * <b>本类最要紧的一条</b>。/continue 的本意是「把没做完的接着做完」，
     * FAILED / KILLED 就是普通的「没做完」——模型看 todo 自然会重派，而重派正是要的结果。
     * 一旦有人在这里给它们加了「先看看失败原因再决定」之类的指引，就是替用户做了他已经做过的决定。
     */
    @Test
    @DisplayName("只有 FAILED / KILLED：返回空串，一个字都不提")
    void failedAndKilledProduceNothing() {
        BackgroundTaskRegistry r = registry();
        String failed = r.register("bash", "跑全量回归");
        r.complete(failed, "额度不足（402）", false);
        String killed = r.register("explore", "扫目录");
        r.kill(killed);

        String s = BackgroundDigest.forContinue(r.all());

        assertEquals("", s, "FAILED/KILLED 不需要任何特殊处理，实际:\n" + s);
    }

    @Test
    @DisplayName("空注册表：给一句自条件提醒，覆盖 -c 恢复会话的场景")
    void emptyRegistryWarnsAboutCrossProcess() {
        String s = BackgroundDigest.forContinue(List.of());

        assertTrue(s.contains("不跨进程保存"), "-c 恢复后模型可能干等已死的旧任务:\n" + s);
        assertTrue(s.contains("重新派发"), "要给出正确的下一步:\n" + s);
    }

    @Test
    @DisplayName("forContinue 不含结果正文——塞进去会撑爆提示词，且绕过 markConsumed 互斥")
    void forContinueOmitsResultBody() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "这是一段绝不该出现在提示词里的结果正文", true);

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "前提：这条确实进了清单:\n" + s);
        assertFalse(s.contains("绝不该出现在提示词里"), "结果正文必须由 TaskOutput 取，不能塞进提示词:\n" + s);
    }

    @Test
    @DisplayName("full：四种状态一个不漏")
    void fullListsEveryStatus() {
        BackgroundTaskRegistry r = registry();
        String running = r.register("explore", "还在跑");
        String done = r.register("general-purpose", "完成了");
        r.complete(done, "ok", true);
        String failed = r.register("bash", "失败了");
        r.complete(failed, "boom", false);
        String killed = r.register("plan", "被终止");
        r.kill(killed);

        String s = BackgroundDigest.full(r.all());

        for (String id : List.of(running, done, failed, killed)) {
            assertTrue(s.contains(id), "full 漏了 " + id + "，实际:\n" + s);
        }
    }

    @Test
    @DisplayName("full 空注册表：返回一句话而不是空串——工具返回空串会被当成调用失败")
    void fullOnEmptyRegistryIsNotBlank() {
        String s = BackgroundDigest.full(List.of());

        assertFalse(s.isBlank(), "空串会让模型以为 ListTasks 调用失败");
        assertTrue(s.contains("没有后台任务"), "实际:\n" + s);
    }

    @Test
    @DisplayName("入参为 null 不抛——它跑在提示词构造路径上，抛异常会崩掉整个 TUI")
    void nullSnapshotIsSafe() {
        assertFalse(BackgroundDigest.forContinue(null).isEmpty(),
                "null 按空注册表处理，走跨进程提醒那一支");
        assertFalse(BackgroundDigest.full(null).isBlank());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='BackgroundDigestTest'
```

Expected: **COMPILATION ERROR** — `cannot find symbol: class BackgroundDigest`。

- [ ] **Step 3: 写实现**

新建 `BackgroundDigest.java`：

```java
package io.github.javaside.springai.codetui.agent.background;

import java.util.ArrayList;
import java.util.List;

/**
 * 把后台任务快照渲染成<b>给模型看</b>的文本。
 *
 * <p><b>唯一的措辞出处</b>：{@code /continue} 提示词注入、{@code ListTasks} 工具、
 * {@code TaskOutput} 查无此 id 三条路都走它，于是模型无论从哪儿看到后台任务，
 * 读到的都是同一套说法。三处各写一遍的话，改一处忘两处，模型就会在同一件事上
 * 收到互相矛盾的描述。
 *
 * <p><b>接快照而不接注册表</b>：注册表的方法都是 {@code synchronized}，调用方取一次
 * {@link BackgroundTaskRegistry#all()} 就够；本类若持有注册表，实现里就会忍不住取两次
 * （比如再调一次 {@code completedUnconsumed()}），而两次之间状态会变。
 * {@link BackgroundTask#consumed()} 是 public 的，一份快照足以筛出所有分组。
 */
public final class BackgroundDigest {

    private BackgroundDigest() {
    }

    /**
     * 给 {@code /continue} 用：只列会造成<b>重复劳动</b>的两格。
     *
     * <p><b>为什么 FAILED / KILLED 一个字都不提</b>：{@code /continue} 的本意是
     * 「把没做完的接着做完」，而它们就是普通的「没做完」——模型看 todo 自然会重派，
     * 重派正是要的结果。在这里给它们加任何指引（「先看看失败原因」之类），
     * 都是替用户做了他敲下 {@code /continue} 时已经做过的决定。
     *
     * @return 需要提醒时的文本；两格都空且注册表非空时返回<b>空串</b>（调用方据此不改提示词）
     */
    public static String forContinue(List<BackgroundTask> snapshot) {
        List<BackgroundTask> tasks = snapshot == null ? List.of() : snapshot;
        if (tasks.isEmpty()) {
            return CROSS_PROCESS_HINT;
        }
        List<String> rows = new ArrayList<>();
        for (BackgroundTask t : tasks) {
            if (t == null) {
                continue;
            }
            if (t.status() == BackgroundTask.Status.RUNNING) {
                rows.add(row(t) + "  运行中 → 正在做，不要重复委派");
            } else if (t.status() == BackgroundTask.Status.DONE && !t.consumed()) {
                rows.add(row(t) + "  已完成待取回 → 先用 TaskOutput(" + t.taskId()
                        + ") 取回结果，它对应的任务可能已经做完了");
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        return "当前进程仍有后台任务：\n" + String.join("\n", rows);
    }

    /** 给 {@code ListTasks} 用：列全部四种状态。空注册表返回一句话而非空串。 */
    public static String full(List<BackgroundTask> snapshot) {
        List<BackgroundTask> tasks = snapshot == null ? List.of() : snapshot;
        if (tasks.isEmpty()) {
            return "当前没有后台任务。";
        }
        List<String> rows = new ArrayList<>();
        for (BackgroundTask t : tasks) {
            if (t == null) {
                continue;
            }
            rows.add(row(t) + "  " + statusLabel(t));
        }
        return "当前后台任务 " + rows.size() + " 个：\n" + String.join("\n", rows);
    }

    /**
     * {@code -c} 恢复会话后注册表必然是空的，而会话历史里还留着
     * {@code 已在后台启动：task_xxx}。模型读到那些会以为它们还在跑，于是干等或跳过
     * ——而用户敲 {@code /continue} 恰恰是要它继续做那些活。
     *
     * <p><b>写成自条件句</b>：UI 无法便宜地知道「历史里提没提过后台任务」，
     * 但模型正在读那段历史，它自己判断得了。代价是一句话，收益是不必把
     * 「本进程是否恢复自旧会话」这个启动期事实一路穿到视图层。
     */
    private static final String CROSS_PROCESS_HINT =
            "若你在历史里看到过「已在后台启动：task_xxx」，注意后台任务不跨进程保存"
                    + "——恢复会话后它们一律已经结束，需要重新派发，不要等它们。";

    /** 一行的前半段：id + agent + 描述。<b>刻意不含结果正文</b>，见类注释与 forContinue。 */
    private static String row(BackgroundTask t) {
        return "  " + t.taskId() + "  " + t.agentName() + "  " + t.description();
    }

    private static String statusLabel(BackgroundTask t) {
        return switch (t.status()) {
            case RUNNING -> "运行中";
            case DONE -> t.consumed() ? "已完成（结果已取回）" : "已完成待取回";
            case FAILED -> "已失败";
            case KILLED -> "已终止";
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='BackgroundDigestTest'
```

Expected: `Tests run: 9, Failures: 0, Errors: 0`。

**若 `failedAndKilledProduceNothing` 红**，回去看实现里是不是给 FAILED/KILLED 加了分支——
那条测试就是为拦这个而写的（全局纪律 #1）。

- [ ] **Step 5: 变异验证——证明那条最要紧的测试真抓得住**

> ⚠ **必须先做完 Step 6（commit）再做本步。**
> `git worktree add --detach HEAD` 拿到的是**已提交**的状态。若此刻 `BackgroundDigest.java`
> 还没提交，新工作树里根本没有这个文件，跑出来是「找不到符号」的编译错——
> **编译错也算红，但它红的理由是错的**，证不了那条语义被钉住。
> 变异验证的全部价值在于「为**正确的**理由失败」，理由错了等于没验。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git worktree add .worktrees/mut-digest --detach <你刚才那个提交的 SHA>
```

在 `.worktrees/mut-digest` 里给 `forContinue` 的循环加一支：

```java
            } else if (t.status() == BackgroundTask.Status.FAILED) {
                rows.add(row(t) + "  已失败 → 先看清失败原因再决定是否重派");
            }
```

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/.worktrees/mut-digest
mvn test -pl springai-code-tui -Dtest='BackgroundDigestTest'
```

Expected: **`failedAndKilledProduceNothing` FAIL**，消息含「FAILED/KILLED 不需要任何特殊处理」。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git worktree remove .worktrees/mut-digest --force
```

**变异不红就停下来查**：那说明这条语义没有被真正钉住，而它是本次最容易被后人「好心」破坏的一条。

- [ ] **Step 6: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundDigest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundDigestTest.java
git commit -m "feat(background): BackgroundDigest —— 后台任务摘要的唯一措辞出处

/continue 注入、ListTasks、TaskOutput 查无此 id 三条路共用它，
避免同一件事三处各写一遍、改一处忘两处。

forContinue 只列 RUNNING 与 DONE-未消费——只有它们会造成重复劳动。
FAILED/KILLED 一个字不提：它们是普通的「没做完」，而重派正是 /continue 的定义。"
```

---

### Task 2: `/continue` 注入 digest

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（`/continue` 分支）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewContinueDigestTest.java`

**现有 `/continue` 分支原文**（`CodeTuiView`，靠 `cmd.equals("/continue")` 定位）：

```java
        if (cmd.equals("/continue")) {               // 续跑：上一批计划被 Esc/报错中断后，据会话里保留的 todo 从首个未完成项接着做
            clearInput();
            // 工具中立：别硬点 Task/串行——上一批若是 ParallelTasks 并行跑的，"逐个用 Task" 会把独立任务逼回串行、丢掉并行。
            // 让模型按任务独立性自选，并与先前采用的方式保持一致。
            String prompt = "继续执行上一批未完成的计划。请先回顾你的 todo 列表，从第一个尚未完成的任务开始委派子 agent 继续："
                    + "相互独立、无共享状态的子任务用 ParallelTasks 并行委派，有依赖或需共享上下文的用 Task 串行委派"
                    + "（与你先前采用的方式保持一致）；已完成的任务不要重做。若没有未完成的计划，直接说明即可。";
            if (busy()) state.enqueue(prompt, null);   // 忙/压缩中/有在飞子 agent：排队，清空后自动出队（同普通消息）
            else dispatch(prompt, null);
            return;
        }
```

**注意它有两条出口**：`enqueue`（忙时排队）与 `dispatch`（直接发）。只改一条会漏。

- [ ] **Step 1: 写失败的测试**

新建 `CodeTuiViewContinueDigestTest.java`：

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /continue} 把后台任务摘要拼进提示词。
 *
 * <p><b>钉的是重复劳动</b>：Esc 掐掉前台回合后，后台那批照跑，而 todo 上它们仍是「进行中」。
 * 不告诉模型的话，/continue 会让它把正在跑的活再派一遍。
 *
 * <p><b>两条出口都要测</b>：/continue 有 enqueue（忙时排队）与 dispatch（直接发）两条路，
 * 只测一条会漏掉另一条用的是未拼接的旧提示词。
 */
class CodeTuiViewContinueDigestTest {

    /** 记录最后一次真正提交/入队的文本。 */
    private static final class Handler implements SubmitHandler {
        final AtomicReference<String> submitted = new AtomicReference<>();
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        String digest = "";

        @Override public Disposable submit(String text) { submitted.set(text); return () -> {}; }
        @Override public Disposable submit(String text, String skill) { return submit(text); }
        @Override public boolean hasInFlightSubagents() { return inFlight.get(); }
        @Override public String backgroundDigestForContinue() { return digest; }
    }

    private static void runContinue(CodeTuiView v) {
        v.setInputForTest("/continue");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("digest 非空：提示词同时含原文与摘要，谁也没盖掉谁")
    void digestIsAppendedToPrompt(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "当前进程仍有后台任务：\n  task_x1  explore  扫目录  运行中 → 正在做，不要重复委派";
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        String sent = h.submitted.get();
        assertTrue(sent.contains("继续执行上一批未完成的计划"), "原提示词不能被覆盖:\n" + sent);
        assertTrue(sent.contains("task_x1"), "摘要必须拼进去:\n" + sent);
        assertTrue(sent.contains("不要重复委派"), "行动指引必须拼进去:\n" + sent);
    }

    /**
     * 杀掉「无条件拼接」这个变异：digest 为空时若仍拼一段，提示词尾部会多出分隔符。
     * 用 endsWith 而不是整串相等——后者会在原提示词有任何合理改动时误报。
     */
    @Test
    @DisplayName("digest 为空：提示词尾部不得多出任何东西")
    void emptyDigestLeavesPromptUntouched(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "";                       // 注册表非空但只有 FAILED/KILLED 时就是这个值
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        String sent = h.submitted.get();
        assertTrue(sent.endsWith("若没有未完成的计划，直接说明即可。"),
                "空 digest 不该在尾部追加任何东西（哪怕只是换行）:\n" + sent);
        assertFalse(sent.contains("后台任务"), "空 digest 不该凭空提到后台:\n" + sent);
    }

    /**
     * /continue 的另一条出口。忙时它走 state.enqueue，若只在 dispatch 那条路上做了拼接，
     * 排队的就是没有摘要的旧提示词——而「忙」恰恰是后台任务最可能在跑的时候。
     */
    @Test
    @DisplayName("忙时排队：入队的也必须是拼好摘要的那份")
    void queuedPromptAlsoCarriesDigest(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "当前进程仍有后台任务：\n  task_q9  bash  跑回归  运行中 → 正在做，不要重复委派";
        h.inFlight.set(true);                // busy() 为真 ⇒ 走 enqueue
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        assertEquals(1, state.queuedCount(), "前提：忙时确实入队了");
        String queued = state.queuedSnapshot().get(0);
        assertTrue(queued.contains("task_q9"), "入队的提示词也必须带摘要:\n" + queued);
        assertTrue(queued.contains("继续执行上一批未完成的计划"), "原文也要在:\n" + queued);
    }

    @Test
    @DisplayName("SubmitHandler 的默认实现返回空串——回显桩/测试桩可省略")
    void defaultDigestIsEmpty() {
        SubmitHandler stub = text -> null;
        assertEquals("", stub.backgroundDigestForContinue());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='CodeTuiViewContinueDigestTest'
```

Expected: **COMPILATION ERROR**，**两处**：
- `方法不会覆盖或实现超类型的方法`（桩里 `@Override backgroundDigestForContinue`）
- `找不到符号：方法 backgroundDigestForContinue()`（`defaultDigestIsEmpty` 里那句）

⚠ **这只是第一次红，理由是「方法不存在」，不是「行为不对」**——断言一条都没执行过。
Step 4b 会补第二次红。见全局纪律 #5。

- [ ] **Step 3: 给 `SubmitHandler` 加门面方法**

在 `SubmitHandler.java` 的后台子 agent 那一节（`shutdownBackground()` 之后、
`record BackgroundResult` 之前）插入：

```java
    /**
     * {@code /continue} 用的后台任务摘要——拼进提示词，让模型知道哪些活正在做 / 已经做完。
     *
     * <p><b>为什么由门面出而不是让 UI 读自己的镜像</b>：{@code ConversationState} 里那份
     * {@code BackgroundView} 是<b>显示镜像</b>，注册表才是「唯一并发真相源」。
     * 用一个为渲染而生的副本去构造喂给模型的指令，等于让它承担正确性责任。
     *
     * <p>无需提醒时返回<b>空串</b>，调用方据此保持提示词一个字不变。
     * 默认空串，便于回显桩/测试桩省略。
     */
    default String backgroundDigestForContinue() { return ""; }
```

- [ ] **Step 4: 在 `CodingAgent` 里实现它**

放在 `completedBackgroundTasks()` 附近（后台门面那一节）：

```java
    /**
     * 取一次注册表快照交给 {@link BackgroundDigest}。
     *
     * <p><b>只取一次</b>：注册表方法都是 synchronized，取两次之间状态会变；
     * {@code BackgroundTask.consumed()} 是 public 的，一份 {@code all()} 足以筛出所有分组。
     */
    @Override
    public String backgroundDigestForContinue() {
        if (backgroundRegistry == null) {
            return "";     // 桩路径：没有后台子系统，也就没有可提醒的
        }
        return io.github.javaside.springai.codetui.agent.background.BackgroundDigest
                .forContinue(backgroundRegistry.all());
    }
```

- [ ] **Step 4b: 第二次跑红——这次断言才真正生效**

Step 3、4 只加了门面（接口默认方法 + `CodingAgent` 实现），**先别改 `CodeTuiView`**，
此刻编译已通过、四条断言开始真正执行。跑：

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='CodeTuiViewContinueDigestTest'
```

Expected: `Tests run: 4, Failures: 2` ——
`digestIsAppendedToPrompt`（「摘要必须拼进去」）与
`queuedPromptAlsoCarriesDigest`（「入队的提示词也必须带摘要」）**各自独立地红**。

**两条都要红**：只红一条说明另一条搭了便车——比如 `queuedPromptAlsoCarriesDigest`
若不独立成立，等 Step 5 在 dispatch 那条路上做了拼接，它可能就跟着绿了，
于是「排队出口也带摘要」这件事实际上没有任何测试守着。

另两条（`emptyDigestLeavesPromptUntouched` / `defaultDigestIsEmpty`）此刻应是**绿的**
——它们钉的是「别多做」，此时确实还没多做。

- [ ] **Step 5: 改 `/continue` 分支**

把 `if (busy()) state.enqueue(prompt, null);` 那两行**之前**插入拼接，并让两条出口都用拼好的：

```java
            String prompt = "继续执行上一批未完成的计划。请先回顾你的 todo 列表，从第一个尚未完成的任务开始委派子 agent 继续："
                    + "相互独立、无共享状态的子任务用 ParallelTasks 并行委派，有依赖或需共享上下文的用 Task 串行委派"
                    + "（与你先前采用的方式保持一致）；已完成的任务不要重做。若没有未完成的计划，直接说明即可。";
            // 后台任务不在 todo 的视野里：Esc 掐掉前台回合后它们照跑，而 todo 上仍是「进行中」。
            // 不说的话模型会把正在跑的活再派一遍（或把已经跑完、结果还没送出去的活重做一遍）。
            // ⚠ 空串时<b>一个字都不能加</b>——没用后台功能的人不该为此付噪声。
            String digest = onSubmit.backgroundDigestForContinue();
            if (!digest.isEmpty()) {
                prompt = prompt + "\n\n" + digest;
            }
            if (busy()) state.enqueue(prompt, null);   // 忙/压缩中/有在飞子 agent：排队，清空后自动出队（同普通消息）
            else dispatch(prompt, null);
```

**两条出口共用同一个 `prompt` 局部变量**，所以拼接一次即可——但测试里那条排队用例
就是为了钉住「以后有人把拼接挪进 `dispatch` 分支」这种改法。

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='CodeTuiViewContinueDigestTest'
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 7: 跑模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewContinueDigestTest.java
git commit -m "feat(continue): /continue 把后台任务摘要拼进提示词

Esc 掐掉前台回合后后台那批照跑，而 todo 上仍是「进行中」——不说的话
模型会把正在跑的活再派一遍，或把已跑完、结果还没送出去的活重做一遍。

摘要经 SubmitHandler 门面从注册表取（不读 UI 的显示镜像），空串时
提示词一个字不变。enqueue 与 dispatch 两条出口共用拼好的那份。"
```

---

### Task 3: `ListTasks` 工具

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskListTool.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsBackgroundWiringTest.java`（加断言）

> ## ⚠ 装配有**两个**独立的登记点，漏掉任一个都不会编译错
>
> **① `AgentTools` 的工具数组** —— 决定模型**有没有**这个工具。
> **② `ToolRegistry` 的权限登记** —— 决定这个工具**要不要弹审批**。
>
> 漏掉 ② 的后果：`ListTasks` 落进 `UNKNOWN` 兜底 ⇒ **保守 ASK** ⇒ 模型每次列后台任务
> 都要弹一次审批面板。`ToolRegistry` 里 `TaskOutput` 那行上方的注释已经把这个坑写明了：
>
> > 取自己进程内后台任务的结果，无外部副作用；不登记的话会落进 UNKNOWN→每次取结果都弹审批。
>
> 顺带说一件事：**`TaskOutput` 那行登记目前没有任何测试盖住**——现在把它删掉，
> 全仓不会有一条测试变红。Step 1 的断言把两个一起钉上。

> ## ⚠ `AgentTools` 数组的下标是「相对末尾」的
>
> `AgentTools` 的主 agent 工具数组用的是**相对末尾的下标**，且既有注释已经写明：
>
> > 尾部四个下标是「相对末尾」的：往这里加工具必须同时改数组长度**和每一个既有下标**——
> > 漏改不会编译失败，只会让某个工具静默变成 null 或被覆盖，运行期才炸且报错离原因很远。
>
> 加 `ListTasks` 要动 **5 处**：数组长度 `+4`→`+5`，四个既有下标各减一档，新工具占 `-1`。
> Step 5 的断言就是为拦这个而写的。

- [ ] **Step 1: 写失败的测试**

在 `AgentToolsBackgroundWiringTest.java` 里，**紧挨着** `mainAgentToolSetContainsTaskOutput`
加两个用例（沿用该类既有的 `runtime` / `subNames` 取法，照抄它现有用例的写法）：

```java
    @Test
    @DisplayName("主 agent 工具集含 ListTasks，且 Task/ParallelTasks/TaskOutput 一个都没被挤掉")
    void mainAgentToolSetContainsListTasks(@TempDir Path root) {
        var runtime = toolMap(root);

        // 这四个必须同时在：AgentTools 的尾部下标是「相对末尾」的，加一个工具若漏改某个下标，
        // 会让某个既有工具静默变成 null 或被覆盖——不编译错、不测试错，运行期才炸。
        for (String name : java.util.List.of("Task", "ParallelTasks", "TaskOutput", "ListTasks")) {
            assertTrue(runtime.containsKey(name),
                    "主 agent 工具集缺 " + name + "（尾部下标漏改？），实际=" + runtime.keySet());
            assertNotNull(runtime.get(name), name + " 是 null——下标撞车了");
        }
    }

    @Test
    @DisplayName("子 agent 拿不到 ListTasks——它没有属于自己的后台任务，列出来的只会是别人的")
    void subagentToolSetOmitsListTasks(@TempDir Path root) {
        var subNames = subagentToolNames(root);

        assertFalse(subNames.contains("ListTasks"),
                "子 agent 不该能列出主 agent 的后台任务，实际=" + subNames);
    }

    /**
     * 第二个登记点：{@code ToolRegistry}。它决定这个工具<b>要不要弹审批</b>，
     * 与 {@code AgentTools} 的数组（决定<b>有没有</b>这个工具）是两回事，漏掉不会编译错。
     *
     * <p>漏登记 ⇒ 落进 {@code UNKNOWN} 兜底 ⇒ 保守 ASK ⇒ 模型每次列后台任务都弹一次审批面板。
     *
     * <p><b>连 TaskOutput 一起断言</b>：那行登记至今没有任何测试盖住，现在删掉它
     * 全仓不会有一条测试变红。既然来了就一起钉上。
     */
    @Test
    @DisplayName("ListTasks / TaskOutput 都已在 ToolRegistry 登记——否则每次调用都弹审批")
    void backgroundQueryToolsAreAllowedWithoutAsking(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(
                root, PermissionConfig.empty(), PermissionMode.DEFAULT);

        for (String name : java.util.List.of("ListTasks", "TaskOutput")) {
            assertEquals(PermissionBehavior.ALLOW, engine.decide(name, "{}").behavior(),
                    name + " 未在 ToolRegistry 登记 ⇒ 落进 UNKNOWN ⇒ 每次调用都弹审批面板");
        }
    }
```

补上这些 import（该测试类若已有则跳过）：

```java
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
```

**`PermissionEngine` 的构造器是 3 参**（`root, config, startupMode`）——第 4 个
`bypassAllowed` 参数在「四档平权」那次改动里已经删掉了。若你看到的是 4 参，
说明你的基线不对，停下来查。

（`toolMap` / `subagentToolNames` 是该测试类里已有的辅助取法——**照抄它现有用例里的写法**，
名字以你实际看到的为准；本类已经有 `mainAgentToolSetContainsTaskOutput` 与
`子 agent 工具集不含 TaskOutput / Task / ParallelTasks` 两个用例做样板。
若辅助方法不存在，就把那两个用例里的取法内联过来。）

补上 import：`static org.junit.jupiter.api.Assertions.assertNotNull;`

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='AgentToolsBackgroundWiringTest'
```

Expected: `mainAgentToolSetContainsListTasks` **FAIL** —「主 agent 工具集缺 ListTasks」。
`subagentToolSetOmitsListTasks` 此刻应已是**绿的**（还没加，当然不含）——
它钉的是「以后别不小心加进子 agent 集」。

- [ ] **Step 3: 写工具**

新建 `BackgroundTaskListTool.java`：

```java
package io.github.javaside.springai.codetui.agent.background;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * {@code ListTasks} 工具：列出本进程全部后台子 agent 任务及其状态。
 *
 * <p><b>为什么单独一个类而不塞进 {@link BackgroundTaskTool}</b>：那个类名是单数、
 * 职责是「取<b>一个</b>任务的结果」，塞进去会让类名说谎。
 *
 * <p><b>为什么需要它</b>：{@code TaskOutput} 的 {@code task_id} 是必填，模型必须先知道 id；
 * 而 id 只存在于会话历史里那条 {@code Task} 的返回值中。{@code /compact} 之后 id 被压掉，
 * 任务还在跑，模型再也查不了——它能派后台任务，却看不见自己派了什么。
 *
 * <p><b>仅主 agent</b>（不进 {@code decoratedList}），与 {@code TaskOutput} 同一条理由：
 * 子 agent 拿不到 {@code Task}，自然也没有属于自己的后台任务，给了它只会让它列出别人的。
 */
public final class BackgroundTaskListTool {

    private static final String DESCRIPTION = """
            List all background subagent tasks started with Task(run_in_background=true) in this
            process, with their status. Use TaskOutput(task_id) to retrieve a finished task's
            result. Background tasks do not survive a process restart.
            """;

    /** 无参。四种状态一共不会超过注册表上限，加 status 过滤是替一个不存在的问题写代码。 */
    public record NoArgs() {
    }

    private BackgroundTaskListTool() {
    }

    /** 构建名为 "ListTasks" 的 ToolCallback。 */
    public static ToolCallback create(BackgroundTaskRegistry registry) {
        return FunctionToolCallback.builder("ListTasks",
                        (NoArgs a) -> BackgroundDigest.full(registry.all()))
                .description(DESCRIPTION)
                .inputType(NoArgs.class)
                .build();
    }
}
```

工具描述最后那句 `Background tasks do not survive a process restart.` 是给
「`-c` 恢复后模型干等已死的旧任务」兜底的：即使模型没走 `/continue`，
只要它调过一次 `ListTasks` 看到空清单，也能明白历史里那些 id 已经没了。

- [ ] **Step 3b: 登记进 `ToolRegistry`（第二个登记点，别漏）**

在 `ToolRegistry` 里 `TaskOutput` 那行**紧下方**加：

```java
        // 列自己进程内的后台任务，只读、无外部副作用；同 TaskOutput，不登记会落进 UNKNOWN→每次都弹审批。
        put("ListTasks",           ToolCategory.INTERNAL, null, false);
```

- [ ] **Step 4: 装配（`AgentTools` 数组，改 5 处）**

在 `AgentTools` 里 `decoratedTaskOutputTool` 的定义**之后**加：

```java
        // ListTasks：同样<b>仅主 agent</b>——理由与 TaskOutput 逐字相同（子 agent 没有属于自己的后台任务）。
        ToolCallback decoratedListTasksTool = new PermissionCallback(
                new ToolEventCallback(BackgroundTaskListTool.create(backgroundRegistry), listener),
                permissionEngine, listener);
```

然后把数组那一段整体替换（**五处全改**，一处不漏）：

```java
        // 主 agent 工具集 = 原装饰工具 + 记忆工具（仅主 agent）+ ExitPlanMode（仅主 agent）+ Task + ParallelTasks + TaskOutput + ListTasks
        // 注意：ParallelTasks / TaskOutput / ListTasks 仅给主 agent，不进 decoratedList，故子 agent 不能再派并行子 agent（禁递归 fan-out）、
        // 也拿不到、看不到别人的后台任务。
        // ⚠ 尾部五个下标是「相对末尾」的：往这里加工具必须同时改数组长度<b>和每一个既有下标</b>——
        // 漏改不会编译失败，只会让某个工具静默变成 null 或被覆盖，运行期才炸且报错离原因很远。
        Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 5];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
        toolsWithTask[toolsWithTask.length - 5] = decoratedExitPlanTool;
        toolsWithTask[toolsWithTask.length - 4] = decoratedTaskTool;
        toolsWithTask[toolsWithTask.length - 3] = decoratedParallelTool;
        toolsWithTask[toolsWithTask.length - 2] = decoratedTaskOutputTool;
        toolsWithTask[toolsWithTask.length - 1] = decoratedListTasksTool;
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='AgentToolsBackgroundWiringTest'
```

Expected: 全绿。**若某条报「X 是 null——下标撞车了」，就是第 4 步漏改了某个下标。**

- [ ] **Step 6: 跑模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskListTool.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsBackgroundWiringTest.java
git commit -m "feat(background): 新增 ListTasks 工具，仅主 agent

模型能派后台任务却看不见自己派了什么：TaskOutput 的 task_id 必填，
而 id 只在会话历史里那条 Task 的返回值中——/compact 之后就查不了了。

两个登记点都要动，漏掉任一个都不会编译错：
- AgentTools 的数组决定「有没有」——尾部下标从四个变五个，既有下标全部下移
- ToolRegistry 的权限登记决定「要不要弹审批」——漏了会落进 UNKNOWN 兜底，
  模型每次列任务都弹一次面板

测试同时钉住两者，并顺带把 TaskOutput 的权限登记也钉上（它此前没有任何
测试盖住，删掉那行全仓不会红）。"
```

---

### Task 4: `TaskOutput` 查无此 id 时附上清单

模型拿着一个不存在的 id 来问，正是它最需要看清单的那一刻，而现在只回一句「未知任务」。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskToolUnknownIdTest.java`

- [ ] **Step 1: 写失败的测试**

新建 `BackgroundTaskToolUnknownIdTest.java`：

```java
package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TaskOutput} 查无此 id 时，把当前实际存在的任务清单一并回给模型。
 *
 * <p>这一刻是它最需要看清单的时刻：它手上的 id 不对（陈旧、来自上个进程、或被淘汰了），
 * 而正确的下一步取决于「现在到底有哪些任务」。只回一句「未知任务」等于让它去猜。
 */
class BackgroundTaskToolUnknownIdTest {

    @Test
    @DisplayName("未知 id：既保留原有解释，也附上当前清单")
    void unknownIdCarriesTheCurrentList() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(64);
        String alive = registry.register("explore", "扫描鉴权相关代码");
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, null, 1);

        String out = tool.fetch(new BackgroundTaskTool.Query("task_不存在", false));

        assertTrue(out.contains("未知任务"), "原有解释必须保留:\n" + out);
        assertTrue(out.contains("重新派发"), "原有的下一步指引必须保留:\n" + out);
        assertTrue(out.contains(alive), "应附上当前实际存在的任务清单:\n" + out);
        assertTrue(out.contains("扫描鉴权相关代码"), "清单要能认得出是哪个任务:\n" + out);
    }

    @Test
    @DisplayName("未知 id 且注册表为空：清单说「当前没有后台任务」，而不是留白让模型猜")
    void unknownIdOnEmptyRegistrySaysSo() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(64);
        BackgroundTaskTool tool = new BackgroundTaskTool(registry, null, 1);

        String out = tool.fetch(new BackgroundTaskTool.Query("task_不存在", false));

        assertTrue(out.contains("未知任务"), "原有解释必须保留:\n" + out);
        assertTrue(out.contains("没有后台任务"), "空清单也要明说:\n" + out);
    }
}
```

**`TaskResultStore` 传 `null`**：本用例走不到取结果那一支（id 根本不存在），
而该类既有的降级契约就是「`backgroundResults` 为空时不限幅」。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='BackgroundTaskToolUnknownIdTest'
```

Expected: 两条都 **FAIL** —「应附上当前实际存在的任务清单」/「空清单也要明说」。

- [ ] **Step 3: 改实现**

`unknownTask(String id)` 现在是 `private static`，拿不到注册表。改成实例方法并附清单：

```java
    /**
     * 查无此 id 的解释——<b>三件事都要说</b>。
     *
     * <p>原来只说「可能来自已结束的进程」，但那只是其中一种：注册表满 64 条时
     * {@link BackgroundTaskRegistry} 会淘汰最旧的<b>已结束</b>任务，其中完全可能含已完成但还没送达的
     * ——那是<b>本进程</b>刚刚丢掉的结果。只给前一种解释，模型会以为这是上个进程的陈旧 id 而就此放弃，
     * 而实际上它该做的是重新派一次。
     *
     * <p><b>第三件是当前清单</b>：模型手上的 id 不对，而正确的下一步取决于「现在到底有哪些任务」。
     * 这一刻正是它最需要看清单的时刻，只回一句「未知任务」等于让它去猜。
     * 措辞走 {@link BackgroundDigest}，与 {@code ListTasks} 和 {@code /continue} 保持一致。
     */
    private String unknownTask(String id) {
        return "未知任务 " + id + "（可能来自已结束的进程——后台任务不跨进程保存；"
                + "也可能是本进程后台任务过多，这条已结束的记录被淘汰了。若仍需要结果，请重新派发）。\n"
                + BackgroundDigest.full(registry.all());
    }
```

`fetch` 里那两处 `return unknownTask(id);` 无需改动（`static` 去掉后仍然调得通，
因为 `fetch` 本就是实例方法）。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='BackgroundTaskToolUnknownIdTest,BackgroundDigestTest'
```

Expected: 全绿。

- [ ] **Step 5: 跑模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。既有测试里若有断言「未知任务」的完整返回串相等，改成 `contains`。

- [ ] **Step 6: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskToolUnknownIdTest.java
git commit -m "feat(background): TaskOutput 查无此 id 时附上当前清单

模型手上的 id 不对时，正确的下一步取决于「现在到底有哪些任务」——
只回一句「未知任务」等于让它去猜。措辞走 BackgroundDigest，与
ListTasks 和 /continue 保持一致。"
```

---

### Task 5: pty 冒烟——证明模型真的收到了摘要

单测只证明「拼接函数会拼」，证不了那串东西**真的走完了 UI → agent → HTTP 这条链**。
而 `/continue` 的提示词是发出去的、**不回显在屏幕上**，所以断言必须落在**请求体**上。

**Files:**
- Modify: `springai-code-tui/src/test/resources/scripts/background_smoke.py`

**已有素材**（读过确认）：
- `BG_THREE_MARKER = "BGTHREENOW"` → 桩发三个 `Task(run_in_background=true)`，
  子 agent 用 `FOREVER_JOB`（每 2s 吐一个 token，测试窗口内**永不结束**）——正是本幕要的。
- `StubModel.requests` 只记 `(role, kind)`，**不留请求体**。
  `StubModel.sent`（整串 messages）是 `permission_smoke.py` 那份桩才有的，本文件**没有**。

- [ ] **Step 1: 给桩补 `sent` 记录点**

在 `StubModel` 的类属性里（`requests` 旁边）加：

```python
    sent = []          # 每次请求的整串 messages —— 断言「模型到底收到了什么」的唯一依据
```

在 `do_POST` 里那句 `StubModel.requests.append((role, kind))` 旁边补一行：

```python
        with StubModel.lock:
            StubModel.requests.append((role, kind))
            StubModel.sent.append(messages)
```

- [ ] **Step 2: 加新场景**

在 `check_auto_turn_wraps_lines` 之后新增：

```python
def check_continue_carries_background_digest(session):
    """/continue 必须把「哪些后台任务正在跑」告诉模型，否则它会把正在跑的活再派一遍.

    <b>断言落在请求体而不是屏幕上</b>：/continue 的提示词是发出去的，屏幕上一个字都看不到。
    只看屏幕的话这条永远测不到——那正是这类接缝缺陷能活很久的原因。

    进入时无后台任务；离开时留着三个 FOREVER 任务（由后续场景或退出清理）。
    """
    session.write(("派活 " + BG_THREE_MARKER).encode() + b"\r")
    wait_until(session, lambda: "个后台任务" in session.screen_text(), 30,
               "三个后台任务起来（⏱ 面板出现）")

    with StubModel.lock:
        before = len(StubModel.sent)

    session.write(b"/continue\r")
    wait_until(session, lambda: len(StubModel.sent) > before, 30, "/continue 把请求发了出去")

    with StubModel.lock:
        msgs = StubModel.sent[-1]

    users = [m for m in msgs if m.get("role") == "user"]
    if not users:
        die("发出的历史里没有 user 消息（角色序列=%s）" % [m.get("role") for m in msgs],
            session.screen.display)
    content = users[-1].get("content") or ""
    if not isinstance(content, str):
        content = json.dumps(content, ensure_ascii=False)

    if "继续执行上一批未完成的计划" not in content:
        die("最后一条 user 消息不是 /continue 的提示词：%r" % content[:200], session.screen.display)
    if "当前进程仍有后台任务" not in content:
        die("/continue 没把后台任务摘要带上——模型会把正在跑的活再派一遍：%r" % content,
            session.screen.display)
    if "不要重复委派" not in content:
        die("摘要缺行动指引：%r" % content, session.screen.display)
    if "task_" not in content:
        die("摘要没点名 taskId，模型无从对应：%r" % content, session.screen.display)
    print("/continue OK: 提示词里带上了后台任务摘要（模型确实收到了）.")
```

- [ ] **Step 3: 挂进 `main()`**

在 `check_auto_turn_wraps_lines(session)` 那一行**之后**加：

```python
        check_continue_carries_background_digest(session)
```

- [ ] **Step 4: 重新打包并跑冒烟**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn -q -pl springai-code-tui package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && /usr/bin/python3 src/test/resources/scripts/background_smoke.py
```

Expected: 末行 `SMOKE PASS`。

**改了 Java 就必须重新 `package`**——冒烟跑的是打好的包，不是 `target/classes`。
忘了这一步会拿旧代码跑出假绿。

- [ ] **Step 5: 反向验证这一幕真的抓得住**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git worktree add .worktrees/mut-continue --detach HEAD
```

在 `.worktrees/mut-continue` 里把 `CodeTuiView` 的 `/continue` 拼接**整段**注释掉
（即退回「不拼 digest」），重新 package 后单跑这一幕。

⚠ **要注释的是 4 行代码**：`String digest = onSubmit.backgroundDigestForContinue();`
那一行**加上**整个 `if (!digest.isEmpty()) { ... }` 块（共 3 行）。
光注释掉 `if` 块会留下一个未使用的局部变量——能编译，但变异不完整，
digest 仍然被求值了一次，只是没用上。

Expected: `SMOKE FAIL: /continue 没把后台任务摘要带上——模型会把正在跑的活再派一遍`。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git worktree remove .worktrees/mut-continue --force
```

**不红就停下来查**：多半是 `sent` 记录点没接上，或断言取错了那条 user 消息。

- [ ] **Step 6: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/test/resources/scripts/background_smoke.py
git commit -m "test(smoke): 证明 /continue 的后台摘要真的走到了模型

断言落在请求体而不是屏幕：/continue 的提示词是发出去的，屏幕上一个字
都看不到，只看屏幕这条永远测不到。

顺带给 background_smoke 的桩补上 StubModel.sent 记录点——它此前只记
(role, kind)，没有请求体可查。"
```

---

### Task 6: 文档

**Files:**
- Modify: `springai-code-tui/README.md`

- [ ] **Step 1: `/continue` 的说明补上后台那一半**

README 的斜杠命令表里 `/continue` 现有描述是「继续执行上一批未完成的计划」。
在权限/后台那一节（`⏱ 后台子 agent` 附近）补一段：

```markdown
- **`/continue` 与后台任务**：`/continue` 的本意是「把 todo 里没做完的接着做完」，
  不管上次是怎么停的（`Esc`、超时、额度、进程退出）。它会把**当前进程正在跑**、
  以及**已完成但结果还没送出去**的后台任务一并告诉模型，避免同一批活跑两遍；
  失败/被终止的任务不特殊处理——它们就是普通的「没做完」，重派正是你要的。
- **后台任务不跨进程**：`-c` 恢复上次会话时，上个进程的后台任务**已经全部结束**
  （它们只活在内存里）。`/continue` 会提醒模型重新派发，而不是干等。
- **`ListTasks`**：模型可随时列出本进程的后台任务及状态；`TaskOutput(task_id)` 取回某一个的结果。
```

- [ ] **Step 2: 核对没有别处说错**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -n "continue" README.md | grep -v "^.*continue;" | head
```

逐条看：凡是说 `/continue` 只管 todo、不提后台的，补一句或指向上面那段。

- [ ] **Step 3: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/README.md
git commit -m "docs(readme): 写清 /continue 与后台任务的关系

/continue 的本意是把没做完的接着做完，不判断该不该重试；它会告诉模型
哪些后台任务正在跑/已完成待取回，避免重复劳动。-c 恢复后旧任务已随
进程结束，需要重新派发。"
```

---

### Task 7: 全量回归与验收

**Files:** 无（只跑与核对；发现问题回到对应任务修）

- [ ] **Step 1: 模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS，`Failures: 0, Errors: 0`。
基线是 1244（本分支起点）。本次净新增：`BackgroundDigestTest` +9、
`CodeTuiViewContinueDigestTest` +4、`BackgroundTaskToolUnknownIdTest` +2、
`AgentToolsBackgroundWiringTest` +2 ⇒ **预期 1261**。
比这个数少，说明有测试被删而没有等价替代，回去查。

- [ ] **Step 2: 措辞出处唯一性核对**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -rn "不要重复委派\|已完成待取回\|不跨进程保存" src/main/java
```

Expected: 这几句**只出现在 `BackgroundDigest.java` 一个文件里**
（`BackgroundTaskTool` 里那句「后台任务不跨进程保存」是它自己的解释文案，属既有，可以在）。
若在 `CodeTuiView` 或 `BackgroundTaskListTool` 里也出现，说明有人各写了一遍——
那正是本设计要消灭的东西（spec §5.1）。

- [ ] **Step 3: 全部冒烟**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn -q -pl springai-code-tui package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd springai-code-tui
for s in background_smoke permission_smoke clear_smoke mcp_manage_smoke memory_smoke edit_shortcut_smoke attachment_smoke; do
  echo "=== $s ==="
  /usr/bin/python3 "src/test/resources/scripts/$s.py" 2>&1 | tail -2
done
```

Expected: 每个都以 `SMOKE PASS` 结尾。
`ListTasks` 进了主 agent 工具集，任何断言「工具数量」或列举工具名的冒烟都可能受影响——
真红了先看是不是这个原因。

- [ ] **Step 4: 逐条核对 spec §10 验收标准**

打开 `docs/superpowers/specs/2026-08-04-continue-background-seam-design.md` §10，
八条逐条对照。第 1、2、5 条由 `BackgroundDigestTest` + `CodeTuiViewContinueDigestTest`
+ 冒烟共同覆盖；第 3 条（只有 FAILED/KILLED 时提示词逐字不变）由
`failedAndKilledProduceNothing` + `emptyDigestLeavesPromptUntouched` 两条合起来覆盖——
**单看任何一条都不够**：前者证明 digest 是空串，后者证明空串时提示词不变。

- [ ] **Step 5: 合并前的最后一眼**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git log --oneline main..HEAD
git diff --stat main...HEAD
```

预期形状：两个新生产文件（`BackgroundDigest`、`BackgroundTaskListTool`）、
三个新测试文件、四个既有文件小改、一份 spec + 一份 plan。

合并交由用户决定，**不要自行合并或推送**。
