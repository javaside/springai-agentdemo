# `/clear` 清空当前会话上下文 —— 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `springai-code-tui` 加一个 `/clear` 斜杠命令：换一个新的空会话（旧会话留盘可 `-c` 恢复）、清屏、重置面板。

**Architecture:** 两条解耦机制。**(A) 换会话**：把 `CodingAgent.sessionId` 从 `final` 改成 `volatile`，`/clear` 时换成新 id，下一个回合的 `SessionMemoryAdvisor` 自动开空会话；旧会话文件不动。**(B) 清屏**：反射进 TamboUI 私有 `Backend` 调 `clear()` + 写 `ESC[3J`，并把 `InlineDisplay` 的两个光标记账字段归零；反射失败降级为打印分割线。B 失败不影响 A。

**Tech Stack:** Java 17，Spring AI 2.0（`SessionService` / `SessionMemoryAdvisor`），TamboUI 0.4.0（`InlineApp` / `InlineToolkitRunner` / 反射进 `InlineTuiRunner`+`InlineDisplay`），JUnit 5，Maven。

**分支：** 本工作在 `feat/clear-context` 分支上（brainstorming 阶段已创建并提交了 spec）。

**Spec：** `docs/superpowers/specs/2026-07-06-clear-context-design.md`

**验证命令（模块作用域，勿整仓跑——会被空模块打挂）：**
```bash
mvn -q -pl springai-code-tui test
```
运行单个测试类：
```bash
mvn -q -pl springai-code-tui test -Dtest=类名
```

---

## File Structure

**新建：**
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SessionIds.java` —— 会话 id 生成（从 `CodeTuiApplication` 下沉，两处复用）。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScreenCleaner.java` —— 隔离全部反射 + ANSI 清屏，单一职责、返回 boolean、绝不抛。
- 测试：`SessionIdsTest`、`CodingAgentClearContextTest`、`ConversationStateResetTest`、`ScreenCleanerTest`、`CodeTuiViewClearTest`。

**修改：**
- `agent/CodingAgent.java` —— `sessionId` 改 `volatile` + `clearContext()` + 包级 `sessionId()` 访问器。
- `agent/SubmitHandler.java` —— 新增 `default void clearContext() {}`。
- `ui/ConversationState.java` —— 新增 `resetForNewSession()`。
- `ui/CodeTuiView.java` —— `COMMANDS` 加 `/clear`、`submitInput()` 加分支、`printHelp()` 补一行。
- `CodeTuiApplication.java` —— `newSessionId()` 改调 `SessionIds.newId()`。

---

## Task 1: `SessionIds` 工具类（下沉 id 生成）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SessionIds.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SessionIdsTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`

- [ ] **Step 1: 写失败测试**

Create `SessionIdsTest.java`:
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话 id：文件名安全、每次不同。 */
class SessionIdsTest {

    @Test
    void newId_isFilenameSafe_andUnique() {
        String a = SessionIds.newId();
        String b = SessionIds.newId();
        assertTrue(a.matches("[0-9A-Za-z-]+"), "只含文件名安全字符：" + a);
        assertNotEquals(a, b, "两次生成应不同");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=SessionIdsTest`
Expected: FAIL —— 编译错误 `cannot find symbol: class SessionIds`。

- [ ] **Step 3: 写最小实现**

Create `SessionIds.java`（逻辑照搬 `CodeTuiApplication.newSessionId()`）:
```java
package io.github.javaside.springai.codetui.agent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 新会话 id 生成：UTC 时间戳 + 短随机，文件名安全（[0-9A-Za-z-]），可按名近似排序。 */
public final class SessionIds {

    private SessionIds() {
    }

    public static String newId() {
        String ts = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return ts + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=SessionIdsTest`
Expected: PASS。

- [ ] **Step 5: 让 `CodeTuiApplication` 复用它（移除重复）**

在 `CodeTuiApplication.java` 中：把私有方法 `newSessionId()` 的调用点改为 `SessionIds.newId()`，并删除该私有方法。

找到（约第 52 行）:
```java
        String sessionId = resumed ? latest.get() : newSessionId();
```
改为:
```java
        String sessionId = resumed ? latest.get() : io.github.javaside.springai.codetui.agent.SessionIds.newId();
```

删除文件末尾的私有方法（约第 85–90 行）:
```java
    /** 新会话 id：UTC 时间戳 + 短随机，文件名安全（[0-9A-Za-z-]），可按名近似排序。 */
    private static String newSessionId() {
        String ts = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return ts + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
```
如果删除后 `Instant` / `ZoneOffset` / `DateTimeFormatter` / `UUID` 的 import 变为未使用，一并删除这些 import（IDE 或编译警告会提示）。

- [ ] **Step 6: 跑整模块测试确认无回归**

Run: `mvn -q -pl springai-code-tui test`
Expected: PASS（全绿）。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SessionIds.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SessionIdsTest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "refactor(session): 下沉会话 id 生成到 SessionIds，两处复用"
```

---

## Task 2: `CodingAgent.clearContext()`（换 volatile sessionId）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentClearContextTest.java`

- [ ] **Step 1: 写失败测试**

Create `CodingAgentClearContextTest.java`:
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** clearContext：换一个新的 sessionId（volatile 写），不依赖 chatClient/session/estimator，故全传 null。 */
class CodingAgentClearContextTest {

    @Test
    void clearContext_swapsSessionId_toNewNonEmptyValue() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        assertEquals("old-session", agent.sessionId(), "初始 id");

        agent.clearContext();

        String after = agent.sessionId();
        assertNotEquals("old-session", after, "clear 后应换新 id");
        assertNotEquals(null, after, "新 id 非空");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodingAgentClearContextTest`
Expected: FAIL —— `cannot find symbol: method sessionId()` / `method clearContext()`。

- [ ] **Step 3: 写最小实现**

在 `CodingAgent.java`：

(a) 把字段声明（约第 58 行）从
```java
    private final String sessionId;
```
改为
```java
    private volatile String sessionId;   // /clear 换新会话时原地替换；submit 里 advisor param 每回合实时读取
```

(b) 在 `clearContext` 邻近（`compact()` 方法附近）新增方法 + 包级访问器:
```java
    /**
     * {@code /clear}：切到一个全新空会话。仅换 {@link #sessionId}（volatile 写），下一个回合的
     * {@code SessionMemoryAdvisor} 会按新 id 自动创建空会话；旧会话事件/文件<b>原样保留</b>，可 {@code -c} 恢复。
     * 调用方（{@code CodeTuiView}）已保证仅在空闲（非回合中/非压缩中）时调用，故此处无需再守卫并发。
     */
    @Override
    public void clearContext() {
        this.sessionId = SessionIds.newId();
    }

    /** 当前会话 id（包级可见，供测试断言换会话是否生效）。 */
    String sessionId() {
        return sessionId;
    }
```

> 注：`@Override` 依赖 Task 3 先给 `SubmitHandler` 加了 `clearContext()`。若按顺序执行本 Task 在 Task 3 之前，暂时去掉 `@Override`；执行完 Task 3 后补上。推荐先做 Task 3 再做本 Task（见下方执行顺序建议），届时 `@Override` 直接可用。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodingAgentClearContextTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentClearContextTest.java
git commit -m "feat(session): CodingAgent.clearContext 换新空会话（volatile sessionId）"
```

---

## Task 3: `SubmitHandler.clearContext()` 默认方法

> 建议在 Task 2 之前执行本 Task，这样 Task 2 的 `@Override` 直接成立。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java`

- [ ] **Step 1: 加默认方法**

在 `SubmitHandler.java` 里，`compact()` 默认方法附近新增:
```java
    /** {@code /clear}：切到一个全新空会话（旧会话留盘可 -c 恢复）。默认空实现，便于回显桩/测试桩省略。 */
    default void clearContext() { }
```

- [ ] **Step 2: 编译确认通过**

Run: `mvn -q -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS（无新测试，仅确认接口编译通过）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java
git commit -m "feat(api): SubmitHandler 新增 clearContext 默认方法"
```

---

## Task 4: `ConversationState.resetForNewSession()`

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateResetTest.java`

- [ ] **Step 1: 写失败测试**

Create `ConversationStateResetTest.java`:
```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** resetForNewSession：清空 todo / 排队消息 / notice（面板与状态回到刚启动）。 */
class ConversationStateResetTest {

    @Test
    void resetForNewSession_clearsTodoQueueAndNotice() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        s.onTodoUpdated(1, List.of("[ ] 任务一", "[ ] 任务二"));
        s.enqueue("排队消息", null);
        s.setNotice("某提示");
        assertEquals(2, s.todoSnapshot().size(), "前置：todo 有 2 条");
        assertEquals(1, s.queuedCount(), "前置：排队 1 条");

        s.resetForNewSession();

        assertTrue(s.todoSnapshot().isEmpty(), "todo 应清空");
        assertEquals(0, s.queuedCount(), "排队应清空");
        assertEquals("", s.notice(), "notice 应清空");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=ConversationStateResetTest`
Expected: FAIL —— `cannot find symbol: method resetForNewSession()`。

- [ ] **Step 3: 写最小实现**

在 `ConversationState.java` 新增方法（放在 `clearQueued()` 附近；复用已有集合字段 `todo` / `subtasks` / `pending` / `queued` 与 `notice`）:
```java
    /**
     * {@code /clear}：把面板与状态复位到「刚启动」——清 todo 面板、子 agent 任务面板、未定稿输出、排队消息、状态提示。
     * 不动会话事件（那是 {@link io.github.javaside.springai.codetui.agent.CodingAgent#clearContext()} 的职责）。
     */
    public synchronized void resetForNewSession() {
        todo.clear();
        subtasks.clear();
        pending.clear();
        queued.clear();
        notice = "";
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=ConversationStateResetTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateResetTest.java
git commit -m "feat(ui): ConversationState.resetForNewSession 复位面板/排队/提示"
```

---

## Task 5: `ScreenCleaner`（反射真清屏 + 结构保护性测试）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScreenCleaner.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ScreenCleanerTest.java`

反射链（TamboUI 0.4.0，均在 `dev.tamboui.*` 包，`setAccessible(true)`）：
- `InlineToolkitRunner.tuiRunner()` → `dev.tamboui.tui.InlineTuiRunner` 实例
- 其私有字段 `backend`（`dev.tamboui.terminal.Backend`）→ 调 `clear()` + `writeRaw("\033[3J\033[H")`
- 其私有字段 `viewport`（`dev.tamboui.tui.InlineViewport`）→ 私有字段 `display`（`dev.tamboui.inline.InlineDisplay`）→ 私有 int 字段 `lastCursorY`、`currentHeight` 置 0

- [ ] **Step 1: 写失败测试（结构保护 + 不抛约定）**

Create `ScreenCleanerTest.java`。它不启动真终端，只断言：(1) 反射目标字段在当前 TamboUI 版本存在（库升级即红灯）；(2) 传入无法反射的对象时 `clear` 返回 `false` 不抛。
```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 结构保护：钉死 ScreenCleaner 依赖的 TamboUI 私有字段仍存在——库升级改名会在此红灯，
 * 提示回去更新反射路径（而非线上静默降级）。真实清屏效果由 pty 冒烟脚本验证，非本单测职责。
 *
 * <p>用 {@code Class.forName} 而非直接 {@code X.class}：{@code InlineViewport} 是 <b>包私有</b>类，
 * 跨包 import 无法编译，只能反射按名解析。
 */
class ScreenCleanerTest {

    @Test
    void reflectionTargets_stillExistInThisTamboUiVersion() throws Exception {
        Class<?> tuiRunner = Class.forName("dev.tamboui.tui.InlineTuiRunner");
        Class<?> viewport = Class.forName("dev.tamboui.tui.InlineViewport");
        Class<?> display = Class.forName("dev.tamboui.inline.InlineDisplay");
        assertNotNull(tuiRunner.getDeclaredField("backend"));
        assertNotNull(tuiRunner.getDeclaredField("viewport"));
        assertNotNull(viewport.getDeclaredField("display"));
        assertNotNull(display.getDeclaredField("lastCursorY"));
        assertNotNull(display.getDeclaredField("currentHeight"));
    }

    @Test
    void clear_returnsFalse_andDoesNotThrow_whenRunnerHasNoTuiRunner() {
        // 传 null runner：反射链一开始就取不到 tuiRunner，应稳妥返回 false、绝不抛。
        assertDoesNotThrow(() -> {
            boolean ok = ScreenCleaner.clear(null);
            org.junit.jupiter.api.Assertions.assertFalse(ok, "无法反射时返回 false");
        });
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=ScreenCleanerTest`
Expected: FAIL —— `cannot find symbol: class ScreenCleaner`（结构断言那条可能已过，但类不存在整体编译失败）。

- [ ] **Step 3: 写最小实现**

Create `ScreenCleaner.java`:
```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.app.InlineToolkitRunner;

import java.lang.reflect.Field;

/**
 * 真清屏：TamboUI 0.4.0 未公开清屏 API，故反射进内联运行器的私有 {@code Backend} 调 {@code clear()} 并写
 * {@code ESC[3J}（抹掉回滚缓冲），再把 {@code InlineDisplay} 的相对光标记账（{@code lastCursorY} /
 * {@code currentHeight}）归零——否则下一帧渲染会光标漂移。
 *
 * <p><b>约定</b>：任何反射/IO 失败都不抛，返回 {@code false}，由调用方降级为打印一行分割线。库升级导致字段
 * 改名时，{@code ScreenCleanerTest} 的结构断言会先红灯提醒；线上则安全降级、功能不残（换会话与清屏解耦）。
 *
 * <p>必须在渲染线程（{@code runOnRenderThread}，两帧之间）调用，勿在绘制中途执行。
 */
final class ScreenCleaner {

    private ScreenCleaner() {
    }

    /** 尝试真清屏；成功返回 true，任何失败返回 false（不抛）。runner 为 null 直接返回 false。 */
    static boolean clear(InlineToolkitRunner runner) {
        if (runner == null) {
            return false;
        }
        try {
            Object tui = runner.tuiRunner();                 // dev.tamboui.tui.InlineTuiRunner
            Backend backend = (Backend) get(tui, "backend");
            backend.clear();                                 // 清可见区
            backend.writeRaw("\033[3J\033[H");           // 抹回滚缓冲 + 光标 home
            backend.flush();

            Object viewport = get(tui, "viewport");          // dev.tamboui.tui.InlineViewport
            Object display = get(viewport, "display");       // dev.tamboui.inline.InlineDisplay
            setInt(display, "lastCursorY", 0);
            setInt(display, "currentHeight", 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException e) {
            return false;   // 降级：调用方改打印分割线
        }
    }

    private static Object get(Object target, String field) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setInt(Object target, String field, int value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }
}
```

> 注：`backend.clear()` / `writeRaw` / `flush` 声明抛 `IOException`；`get(...)` 抛 `ReflectiveOperationException`。catch 已覆盖三者 + `RuntimeException`（如 `ClassCastException`、`NullPointerException`）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=ScreenCleanerTest`
Expected: PASS（两条都过：字段存在 + null runner 返回 false 不抛）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScreenCleaner.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ScreenCleanerTest.java
git commit -m "feat(ui): ScreenCleaner 反射真清屏（失败安全降级）"
```

---

## Task 6: `CodeTuiView` 接线 `/clear`

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java`

设计要点：视图在单测里 `runner()==null`（未 `run()`）。故 `/clear` 分支把**可测的非视觉副作用**（`clearContext` + `resetForNewSession` + 反馈）同步做完，**视觉清屏**只在 `runner()!=null` 时经 `runOnRenderThread` 执行；`runner()==null` 时走 `pushInfo` 分割线反馈（进 pending，可断言）。

- [ ] **Step 1: 写失败测试**

Create `CodeTuiViewClearTest.java`。用一个记录调用的 `SubmitHandler` 桩，经 `feedKeyForTest` 键入 `/clear` 回车驱动分发。
```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /clear 命令分发：空闲时触发 clearContext + 面板复位；忙时拒绝并置 notice、不触发。
 * 视图在测试里 runner()==null（未 run），故视觉清屏被跳过、只断言非视觉副作用。
 */
class CodeTuiViewClearTest {

    /** 记录 clearContext 调用次数的桩。 */
    private static final class RecordingHandler implements SubmitHandler {
        final AtomicInteger clears = new AtomicInteger();
        @Override public Disposable submit(String text) { return null; }
        @Override public void clearContext() { clears.incrementAndGet(); }
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
    }

    @Test
    void clear_whenIdle_invokesClearContext_andResetsPanels() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        s.onTodoUpdated(1, java.util.List.of("[ ] 旧任务"));
        s.onTurnComplete(1);                         // 回到 IDLE，但 todo 面板仍在
        assertTrue(s.isIdle(), "前置：空闲");
        assertEquals(1, s.todoSnapshot().size(), "前置：todo 有 1 条");

        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(1, h.clears.get(), "空闲 /clear 应调用 clearContext 一次");
        assertTrue(s.todoSnapshot().isEmpty(), "面板应复位");
    }

    @Test
    void clear_whenBusy_isRejected_withNotice_andDoesNotClear() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);                          // 回合进行中
        assertTrue(s.isBusy(), "前置：忙碌");

        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(0, h.clears.get(), "忙时不应换会话");
        assertEquals("忙碌中，无法清空", s.notice(), "忙时应置提示");
    }
}
```

> 说明：`onTurnComplete(long)` 是回合结束回到 IDLE 的真实回调（已核实存在于 `ConversationState`）。目标只是构造「空闲 + todo 面板仍非空」这一前置状态。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodeTuiViewClearTest`
Expected: FAIL —— `/clear` 尚未被识别为命令，被当普通文本提交（`clears==0`、todo 未清、忙时 notice 也不对）。

- [ ] **Step 3: 写最小实现（COMMANDS + submitInput 分支）**

(a) 在 `COMMANDS` 列表（约第 112–122 行）里，`/compact` 之后加一行:
```java
            new SlashCommand("/clear",   "清空上下文，开新会话"),
```

(b) 在 `submitInput()` 里，`/compact` 分支之后新增 `/clear` 分支:
```java
        if (cmd.equals("/clear")) {                  // 换新空会话：旧会话留盘可 -c 恢复
            inputState.clear();
            if (state.isBusy()) {                    // 回合中 或 压缩中：拒绝（isBusy = !isIdle || compacting）
                state.setNotice("忙碌中，无法清空");   // 注意：必须用 isBusy 而非 isIdle——/compact 后台压缩时 status 仍 IDLE
                return;
            }
            onSubmit.clearContext();                 // (A) 换 sessionId
            state.resetForNewSession();              // 复位面板/排队/提示
            lastShownModel = "";                     // 新会话首个回合重新打「⚙ 使用模型 X」
            pendingSkill = null;                     // 清掉未发送的技能挂载：新会话不继承
            var r = runner();
            if (r != null) {                         // (B) 真清屏只在运行态做（测试态 runner==null 跳过）
                r.runOnRenderThread(() -> {
                    boolean ok = ScreenCleaner.clear(r);
                    if (ok) {
                        printer.welcome(onSubmit.currentModel(),
                                io.github.javaside.springai.codetui.AppInfo.versionLabel());
                    } else {
                        state.pushInfo("─── 新会话（上下文已清空）───");   // 反射失败降级；pushInfo 经 drain 下沉 scrollback
                    }
                });
            } else {
                state.pushInfo("─── 新会话（上下文已清空）───");
            }
            return;
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=CodeTuiViewClearTest`
Expected: PASS（两条）。

- [ ] **Step 5: 补 `/help` 文案**

在 `printHelp()` 里，`/compact` 那行附近补一行 `/clear` 说明（照该方法既有格式；先 `grep -n '/compact' CodeTuiView.java` 定位 help 里的写法再仿写）。示例:
```java
        printer.println("  /clear    清空上下文，开新会话（旧会话可 -c 恢复）");
```

- [ ] **Step 6: 跑整模块测试确认无回归**

Run: `mvn -q -pl springai-code-tui test`
Expected: PASS（全绿）。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java
git commit -m "feat(tui): /clear 命令——换新空会话 + 清屏 + 复位面板"
```

---

## Task 7: pty 实机冒烟验证（真清屏路径，必做）

真清屏是本特性唯一高风险点：反射改 `InlineDisplay` 记账后，光标是否漂移、输入框/横幅是否正常，只有真终端能验。按项目既有规矩：`pty.fork` 须设窗口大小（`TIOCSWINSZ`）+ `TERM=xterm-256color`，改完先 `mvn -q -pl springai-code-tui package` 再验。

**Files:**
- 参考并复用已有 pty 冒烟脚本（先定位）：

- [ ] **Step 1: 找到既有 pty 冒烟脚本作模板**

Run:
```bash
grep -rlE 'TIOCSWINSZ|pty\.fork|pyte|xterm-256color' springai-code-tui --include=*.py --include=*.sh -l 2>/dev/null; \
find . -iname '*smoke*' -o -iname '*pty*' | grep -viE 'target/' 
```
Expected: 列出既有 pty/pyte 冒烟脚本路径（记忆中已有此类脚本）。若确无，参照记忆条目 `pty-smoke-inline-tui-needs-winsize-and-term` 新建一个最小 pyte 版。

- [ ] **Step 2: 打包**

Run: `mvn -q -pl springai-code-tui package -DskipTests`
Expected: 生成可运行 dist（jar / 启动脚本）。

- [ ] **Step 3: 手动或脚本化冒烟**

在 pty 里启动 code-tui，执行序列并观察：
1. 发一条普通消息（如「你好」），等回合结束、scrollback 有内容、必要时让 todo 面板出现。
2. 输入 `/clear` 回车。
3. 断言/肉眼确认：
   - 屏幕内容被清（上方 scrollback 抹掉）；
   - 欢迎横幅重新出现；
   - 输入框在正确位置、光标不漂移、无残行；
   - todo/任务面板已消失（复位）。
4. 再发一条消息，确认新回合正常流式、面板正常、模型行「⚙ 使用模型 X」重新打印。
5. （可选）验证换会话语义：`/clear` 后退出，重启带 `-c`，应恢复到 `/clear` 之前的旧会话（新空会话未发消息不落盘）。

- [ ] **Step 4: 若光标漂移 / 横幅错位 → 回退到分割线版**

若真清屏在实机下不稳（漂移/错位/花屏），把 Task 6 (b) 中 `r != null` 分支的 `ScreenCleaner.clear(r)` 结果强制当作 `false`（即恒走分割线 `state.pushInfo("─── 新会话（上下文已清空）───")`），保留换会话 + 复位面板，只放弃真清屏。记录到 spec 的「YAGNI/明确不做」或新增「已知限制」。这是可接受的降级终态。

- [ ] **Step 5: 提交冒烟脚本/结论（如有新增或改动）**

```bash
git add -A
git commit -m "test(smoke): /clear 真清屏 pty 冒烟验证"
```

---

## 执行顺序建议

Task 3 → Task 1 → Task 2 → Task 4 → Task 5 → Task 6 → Task 7。
（先加接口默认方法，Task 2 的 `@Override` 即成立；Task 1/4/5 相互独立可并行；Task 6 依赖 2/4/5；Task 7 最后实机兜底。）

## 全部完成后的最终验证

```bash
mvn -q -pl springai-code-tui test        # 全绿
mvn -q -pl springai-code-tui package -DskipTests   # 可打包
# + Task 7 的 pty 冒烟人工确认通过
```
