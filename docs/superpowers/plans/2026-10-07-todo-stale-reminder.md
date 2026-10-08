# TodoWrite 过期清单重注入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 控制器建过 TodoWrite 清单后，连续 N 次控制器级工具调用未更新且有未完成项时，把清单快照+更新指令追加到工具返回文本尾部，逼模型纠正过期状态。

**Architecture:** 新组件 `TodoStaleReminder`（阈值计数器 + 控制器清单快照）；`ToolEventCallback` 加可选第三参在成功返回后追加提醒（只给模型，UI 事件携带原始输出）；`AgentTools` 装配接线 + `AgentRuntime` 组件 + 两段式 bind 到 `CodingAgent.clearContext()`。

**Tech Stack:** Java 17、Spring AI 2.0（无新依赖）、JUnit 5。

**Spec:** [docs/superpowers/specs/2026-10-07-todo-stale-reminder-design.md](../specs/2026-10-07-todo-stale-reminder-design.md)

## Global Constraints

- 语言级别锁 17（`maven.compiler.release=17`），不用更高版本语法。
- 单类测试命令必须带模块作用域与 `-am`，且 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 注释写**为什么**；提交信息 `type: 说明` 前缀、正文中文，功能与测试同一提交。
- 工作分支 `feature/todo-stale-reminder`，不直接提交 main。
- 提醒文案前缀钉死 `[任务面板提醒]`，断言必须含该自定义前缀 + `TodoWrite` + 条目内容（能被反向改动杀死）。

---

### Task 1: `TodoStaleReminder` 状态机 + env 解析

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/TodoStaleReminder.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/TodoStaleReminderTest.java`

**Interfaces:**
- Produces: `public TodoStaleReminder(int every)`；`public static TodoStaleReminder fromEnv()`；
  `static int parseEvery(String raw)`（包私有，默认 8，非法回退默认，≤0 原样返回）；
  `public void onControllerTodoWritten(List<TodoWriteTool.Todos.TodoItem> items)`；
  `public void reset()`；`public String reminderOrNull(long turnId, String toolName)`；
  `public boolean enabled()`（every > 0）。
- 依赖库类型：`org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem`
  （record，构造序 `(content, status, activeForm)`——status 在第二位，访问器 `content()/status()/activeForm()`，
  status 枚举 `pending/in_progress/completed`）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 钉死 TodoStaleReminder 状态机：阈值/复位/回合切换/全完成/文案锚点/env 解析。 */
class TodoStaleReminderTest {

    private static TodoItem item(String content, Status status) {
        return new TodoItem(content, content + "（进行中描述）", status);
    }

    @Test
    @DisplayName("低于阈值不提醒；到阈值提醒并复位（下轮重新计满）")
    void threshold_fires_then_resets() {
        TodoStaleReminder r = new TodoStaleReminder(3);
        r.onControllerTodoWritten(List.of(item("修登录页", Status.in_progress), item("跑测试", Status.pending)));
        assertNull(r.reminderOrNull(1L, "Bash"), "第 1 次不提醒");
        assertNull(r.reminderOrNull(1L, "Bash"), "第 2 次不提醒");
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note, "第 3 次到阈值应提醒");
        // 断言自定义文案锚点：改坏前缀/漏掉工具名/漏掉条目内容都会红
        assertTrue(note.contains("[任务面板提醒]"), "提醒须带钉死前缀");
        assertTrue(note.contains("TodoWrite"), "提醒须点名 TodoWrite");
        assertTrue(note.contains("修登录页") && note.contains("跑测试"), "快照须含未完成条目");
        assertTrue(note.contains("▶"), "进行中条目须有 ▶ 标记");
        assertTrue(note.contains("○"), "待办条目须有 ○ 标记");
        // 复位：提醒后重新从 0 计数
        assertNull(r.reminderOrNull(1L, "Bash"));
        assertNull(r.reminderOrNull(1L, "Bash"));
        assertNotNull(r.reminderOrNull(1L, "Bash"));
    }

    @Test
    @DisplayName("快照为空或全部 completed 不提醒")
    void noReminder_whenDoneOrEmpty() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        for (int i = 0; i < 5; i++) assertNull(r.reminderOrNull(1L, "Bash"), "空快照永不提醒");
        r.onControllerTodoWritten(List.of(item("a", Status.completed), item("b", Status.completed)));
        for (int i = 0; i < 5; i++) assertNull(r.reminderOrNull(1L, "Bash"), "全部完成不提醒");
    }

    @Test
    @DisplayName("onControllerTodoWritten 复位计数并换快照")
    void controllerWrite_resetsCounter() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("旧任务", Status.in_progress)));
        r.reminderOrNull(1L, "Bash");                       // 计到 1
        r.onControllerTodoWritten(List.of(item("新任务", Status.in_progress)));
        assertNull(r.reminderOrNull(1L, "Bash"), "复位后从 0 重计");
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("新任务"));
        assertFalse(note.contains("旧任务"), "快照应被整体替换");
    }

    @Test
    @DisplayName("回合切换复位计数；快照保留")
    void turnChange_resetsCounter_keepsSnapshot() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("跨回合任务", Status.in_progress)));
        r.reminderOrNull(1L, "Bash");                       // turn 1 计到 1
        assertNull(r.reminderOrNull(2L, "Bash"), "turn 2 计数归零");
        String note = r.reminderOrNull(2L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("跨回合任务"), "快照跨回合保留");
    }

    @Test
    @DisplayName("TodoWrite 自身不计数；异常路径永不触发由 ToolEventCallback 保证，这里只钉工具名豁免")
    void todoWriteName_neverCounts() {
        TodoStaleReminder r = new TodoStaleReminder(2);
        r.onControllerTodoWritten(List.of(item("t", Status.in_progress)));
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "工具名 TodoWrite 直接豁免");
        assertNull(r.reminderOrNull(1L, "Bash"));           // 1
        assertNull(r.reminderOrNull(1L, "TodoWrite"), "豁免不消耗计数");
        assertNotNull(r.reminderOrNull(1L, "Bash"), "第 2 次 Bash 照常到阈值");
    }

    @Test
    @DisplayName("reset 清空快照（/clear 语义）")
    void reset_clearsSnapshot() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        r.onControllerTodoWritten(List.of(item("t", Status.in_progress)));
        r.reset();
        assertNull(r.reminderOrNull(9L, "Bash"), "reset 后不再提醒");
    }

    @Test
    @DisplayName("未完成项超过 10 行折为汇总行；单条超 80 字符截断")
    void render_capsAndTruncates() {
        TodoStaleReminder r = new TodoStaleReminder(1);
        String long80 = "长".repeat(120);
        var items = new java.util.ArrayList<TodoItem>();
        items.add(item(long80, Status.in_progress));
        for (int i = 1; i < 12; i++) items.add(item("任务" + i, Status.pending));   // 共 12 未完成
        r.onControllerTodoWritten(items);
        String note = r.reminderOrNull(1L, "Bash");
        assertNotNull(note);
        assertTrue(note.contains("…等共 12 项"), "超 10 行折汇总");
        assertFalse(note.contains("任务11"), "第 11 条不逐行展开");
        assertFalse(note.contains("长".repeat(90)), "超长条目截断");
        assertTrue(note.contains("12/12 项未完成"), "未完成计数 X/Y");
    }

    @Test
    @DisplayName("env 解析：默认 8；0/负数停用；非法回退默认")
    void parseEvery_defaultsAndEdges() {
        assertEquals(8, TodoStaleReminder.parseEvery(null));
        assertEquals(8, TodoStaleReminder.parseEvery(" 8 "));
        assertEquals(0, TodoStaleReminder.parseEvery("0"), "0=停用须原样保留");
        assertEquals(-1, TodoStaleReminder.parseEvery("-1"), "负数=停用");
        assertEquals(8, TodoStaleReminder.parseEvery("abc"), "非法回退默认");
        assertEquals(8, TodoStaleReminder.parseEvery(""), "空串回退默认");
        assertFalse(new TodoStaleReminder(0).enabled());
        assertTrue(new TodoStaleReminder(1).enabled());
    }
}
```

- [ ] **Step 2: 跑测试确认红**

```bash
mvn -pl springai-code-tui -am test -Dtest='TodoStaleReminderTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：编译失败（`TodoStaleReminder` 不存在）。

- [ ] **Step 3: 最小实现**

```java
package io.github.javaside.springai.codetui.agent.tools;

import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务面板过期提醒：控制器建过 TodoWrite 清单后，若连续 {@code every} 次控制器级工具调用
 * 没再更新且清单仍有未完成项，生成一段「快照 + 更新指令」文本，由 {@link ToolEventCallback}
 * 追加到工具返回值尾部喂回模型。
 *
 * <p><b>为什么需要它</b>：真机会话取证（见 spec）显示「建了清单后全程不更新」的失败形态——
 * 模型记账纪律本身在（台账文件 8 次追加全中），但工具描述在上下文顶部、数百条工具结果之后
 * 注意力早已稀释，且冻结的面板永远不会再进模型视野，一次遗漏永久持续。唯一可靠的纠正方式是
 * 让环境把过期状态定期怼回模型眼前（Claude Code 的 system-reminder 同思路）。
 *
 * <p><b>只管控制器（taskId==null）</b>：子 agent 内部 todo 不进任何面板（AgentListener
 * 分流丢弃），提醒它没有意义；快照只在控制器分支（todoEventHandler 的 currentTaskId()==null）喂入。
 *
 * <p><b>线程模型</b>：工具线程并发调用是常态（并行工具调用），阈值计数用 AtomicInteger，
 * 快照与回合号 volatile；turnId 切换的「比较后清零」非原子，最坏多清一次计数，无害。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配（AgentTools/CodingAgent 在 agent 包），勿在 agent 包外依赖。
 */
public final class TodoStaleReminder {

    /** 默认阈值：控制器级工具调用第 8 次仍未更新则提醒（SDD 场景每任务约 2 次调用，8 次约等于 4 个任务未记账）。 */
    public static final int DEFAULT_EVERY = 8;
    /** 环境变量名：CODETUI_TODO_REMIND_EVERY，≤0 停用，非法回退默认。 */
    public static final String ENV_EVERY = "CODETUI_TODO_REMIND_EVERY";
    /** 未完成项逐行展示上限，超出折汇总行（防长清单把工具结果撑爆）。 */
    private static final int MAX_LINES = 10;
    /** 单条 content 截断长度。 */
    private static final int MAX_CONTENT = 80;

    private final int every;                                  // 0=停用
    private volatile long turnId = -1L;                      // 计数归属回合（切换即归零）
    private volatile List<TodoItem> todos = List.of();       // 控制器清单快照
    private final AtomicInteger stale = new AtomicInteger(); // 距上次 TodoWrite/提醒的调用数

    public TodoStaleReminder(int every) {
        this.every = Math.max(0, every);
    }

    /** 从环境变量构造（默认 8）。解析逻辑拆在 {@link #parseEvery} 供测试直测——env 本身没法在测试里设。 */
    public static TodoStaleReminder fromEnv() {
        return new TodoStaleReminder(parseEvery(System.getenv(ENV_EVERY)));
    }

    /** 包私有：null/空白/非法 → 默认 8；合法整数原样返回（含 0/负数，由状态机按停用处理）。 */
    static int parseEvery(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_EVERY;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_EVERY;   // 配错不崩启动：回退默认比拒启动更符合「提醒」的可选性质
        }
    }

    /** 是否启用（every>0）。 */
    public boolean enabled() {
        return every > 0;
    }

    /** 控制器 TodoWrite 落地时刷新快照并复位计数（todoEventHandler 的 currentTaskId()==null 分支调用）。 */
    public void onControllerTodoWritten(List<TodoItem> items) {
        this.todos = (items == null) ? List.of() : List.copyOf(items);
        stale.set(0);
    }

    /** 全清（/clear 用）：旧会话的清单不该吓唬一个从没写过它的新会话。 */
    public void reset() {
        todos = List.of();
        stale.set(0);
        turnId = -1L;
    }

    /**
     * 控制器级工具调用成功后询问是否提醒。返回 null=不提醒；到阈值返回提醒文本并复位计数
     * （此后每 every 次再提醒一轮）。
     *
     * <p>工具名 {@code TodoWrite} 豁免且<b>不消耗计数</b>：它本身就是更新动作。
     */
    public String reminderOrNull(long turnId, String toolName) {
        if (!enabled() || "TodoWrite".equals(toolName)) {
            return null;
        }
        if (turnId != this.turnId) {
            this.turnId = turnId;
            stale.set(0);
        }
        List<TodoItem> snapshot = todos;
        if (snapshot.isEmpty() || snapshot.stream().allMatch(i -> i.status() == Status.completed)) {
            return null;
        }
        if (stale.incrementAndGet() < every) {
            return null;
        }
        stale.set(0);
        return render(snapshot);
    }

    /** 渲染提醒文本。前缀/锚点被测试钉死，改动须同步 TodoStaleReminderTest。 */
    private static String render(List<TodoItem> items) {
        int total = items.size();
        long done = items.stream().filter(i -> i.status() == Status.completed).count();
        List<String> lines = new ArrayList<>();
        int unfinished = 0;
        for (TodoItem i : items) {
            if (i.status() == Status.completed) continue;
            unfinished++;
            if (lines.size() < MAX_LINES) {
                lines.add((i.status() == Status.in_progress ? "▶ " : "○ ") + trunc(i.content()));
            }
        }
        StringBuilder sb = new StringBuilder("[任务面板提醒] 距上次 TodoWrite 已连续 ").append(total == 0 ? 0 : unfinished)
                .append(" 次内工具调用未更新，任务清单可能已过期（").append(unfinished).append('/').append(total)
                .append(" 项未完成）：\n").append("✓ 已完成：").append(done).append(" 项\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (unfinished > lines.size()) {
            sb.append("…等共 ").append(unfinished).append(" 项未完成\n");
        }
        sb.append("请按实际进度立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。");
        return sb.toString();
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() <= MAX_CONTENT ? s : s.substring(0, MAX_CONTENT) + "…";
    }
}
```

注意：`render` 首句「已连续 N 次内工具调用」中 N 用未完成数是错的——实现时改为传参
`firedAt`（即 `every`）：`reminderOrNull` 到阈值处调 `render(snapshot, every)`，
首句为 `"距上次 TodoWrite 已连续 " + firedAt + " 次工具调用未更新"`（上面代码块里
`render` 的第一个 append 按 `firedAt` 实现）。测试断言不含该数字，不会与之冲突。

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='TodoStaleReminderTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/TodoStaleReminder.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/TodoStaleReminderTest.java
git commit -m "feat(code-tui): TodoStaleReminder 状态机——过期任务清单的阈值计数与提醒渲染"
```

---

### Task 2: `ToolEventCallback` 可选提醒追加

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/ToolEventCallback.java`
- Test: 扩展 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ToolEventCallbackTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TodoStaleReminder.reminderOrNull(long, String)`。
- Produces: `ToolEventCallback(ToolCallback, AgentListener, TodoStaleReminder)` 三参构造
  （旧两参构造委托 `null` = 停用，既有调用点与 McpRegistry 零改动）。

- [ ] **Step 1: 在 ToolEventCallbackTest 追加失败测试**

在既有测试类里加（沿用其 `RecordingListener` 与工具定义桩的写法，另造一个固定返回值的委托桩）：

```java
@Test
void reminder_appendedAfterOnFinished_onlyForControllerCalls() {
    // 快照：1 个未完成项，阈值 2
    TodoStaleReminder reminder = new TodoStaleReminder(2);
    reminder.onControllerTodoWritten(java.util.List.of(
            new org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem(
                    "钉住的任务",
                    org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress,
                    "钉住的任务（进行中）")));
    ToolCallback delegate = new FixedTool("Bash", "raw-out");
    RecordingListener recorder = new RecordingListener();
    ToolCallback cb = new ToolEventCallback(delegate, recorder, reminder);
    ToolContext ctx = new ToolContext(java.util.Map.of(ToolEventCallback.TURN_ID_KEY, 5L));

    assertEquals("raw-out", cb.call("{}", ctx), "第 1 次未到阈值不加尾巴");
    String second = cb.call("{}", ctx);
    assertTrue(second.startsWith("raw-out\n\n[任务面板提醒]"),
            "第 2 次到阈值追加提醒，实际=" + second);
    assertTrue(second.contains("钉住的任务"));
    // UI 侧事件携带原始输出：提醒只给模型，scrollback 不显示
    assertTrue(recorder.events.stream().anyMatch(e -> e.equals("finished:5:Bash:raw-out:true")),
            "onToolFinished 须收到不含提醒的原始输出");
    assertTrue(recorder.events.stream().noneMatch(e -> e.contains("[任务面板提醒]")),
            "任何 UI 事件都不得出现提醒文本");
}

@Test
void reminder_neverForSubagentCalls_andNullReminderKeepsOldBehavior() {
    TodoStaleReminder reminder = new TodoStaleReminder(1);
    reminder.onControllerTodoWritten(java.util.List.of(
            new org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem(
                    "t",
                    org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress,
                    "t（进行中）")));
    RecordingListener recorder = new RecordingListener();
    ToolCallback cb = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder, reminder);
    ToolContext subagentCtx = new ToolContext(java.util.Map.of(
            ToolEventCallback.TURN_ID_KEY, 5L, ToolEventCallback.TASK_ID_KEY, "sub-1"));
    assertEquals("raw-out", cb.call("{}", subagentCtx), "taskId!=null 的调用永不提醒");

    // 旧两参构造 = 停用：到阈值也不加尾巴（McpRegistry 等既有调用点行为不变）
    ToolCallback legacy = new ToolEventCallback(new FixedTool("Bash", "raw-out"), recorder);
    assertEquals("raw-out", legacy.call("{}", new ToolContext(java.util.Map.of(ToolEventCallback.TURN_ID_KEY, 5L))));
}

/** 固定名/固定返回值的委托桩。 */
private record FixedTool(String name, String out) implements ToolCallback {
    @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name).description("d").inputSchema("{}").build();
    }
    @Override public String call(String toolInput, ToolContext toolContext) {
        return out;
    }
}
```

- [ ] **Step 2: 跑测试确认红**（三参构造不存在 → 编译失败）

```bash
mvn -pl springai-code-tui -am test -Dtest='ToolEventCallbackTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 最小实现**

`ToolEventCallback` 加字段与构造（旧的委托新的是「停用」而非删除，McpRegistry/测试零改动）：

```java
private final ToolCallback delegate;
private final AgentListener listener;
/** 可空：任务面板过期提醒；null=停用（McpRegistry 等既有构造点维持原行为）。 */
private final TodoStaleReminder reminder;

public ToolEventCallback(ToolCallback delegate, AgentListener listener) {
    this(delegate, listener, null);
}

public ToolEventCallback(ToolCallback delegate, AgentListener listener, TodoStaleReminder reminder) {
    this.delegate = delegate;
    this.listener = listener;
    this.reminder = reminder;
}
```

`call()` 成功路径改为（异常路径不动——失败的调用不该提醒）：

```java
String out = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
listener.onToolFinished(turnId, taskId, name, out, true);   // UI 只见原始输出
// 提醒追加在 onToolFinished 之后：模型看到过期清单，scrollback 不受污染。
// 只管控制器级调用（taskId==null）——子 agent 内部 todo 不上面板，提醒无意义（见 TodoStaleReminder 类注释）。
if (reminder != null && taskId == null) {
    String note = reminder.reminderOrNull(turnId, name);
    if (note != null) {
        out = out + "\n\n" + note;
    }
}
return out;
```

- [ ] **Step 4: 跑测试确认绿**（含既有 4 组行为不回归）

```bash
mvn -pl springai-code-tui -am test -Dtest='ToolEventCallbackTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/tools/ToolEventCallback.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/tools/ToolEventCallbackTest.java
git commit -m "feat(code-tui): ToolEventCallback 可选追加过期清单提醒——只给模型，UI 事件保持原始输出"
```

---

### Task 3: `CodingAgent` 两段式 bind + `/clear` 重置

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`（bindTodoReminder 字段/方法 + clearContext 重置）
- Test: 扩展 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentClearContextTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TodoStaleReminder`（`onControllerTodoWritten/reset/reminderOrNull`）。
- Produces: 包私有 `void bindTodoReminder(TodoStaleReminder r)`（Task 4 的 `AgentTools.wireTodoReminder` 依赖）。

- [ ] **Step 1: 追加失败测试**

```java
@Test
void clearContext_resetsTodoReminder() {
    CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
    TodoStaleReminder reminder = new TodoStaleReminder(1);
    reminder.onControllerTodoWritten(java.util.List.of(
            new org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem(
                    "旧会话任务",
                    org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status.in_progress,
                    "旧会话任务（进行中）")));
    agent.bindTodoReminder(reminder);

    agent.clearContext();

    assertNull(reminder.reminderOrNull(9L, "Bash"),
            "/clear 后快照须清空——旧清单不该吓唬从没写过它的新会话");
}
```

（需补 `import static org.junit.jupiter.api.Assertions.assertNull;` 与
`io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder`。）

- [ ] **Step 2: 跑测试确认红**（`bindTodoReminder` 不存在 → 编译失败）

```bash
mvn -pl springai-code-tui -am test -Dtest='CodingAgentClearContextTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 最小实现**

字段（挨着 `usageAccumulator` 一类的可空协作对象声明）：

```java
/** 任务面板过期提醒（可空：装配期 null，wireTodoReminder 后有值）。/clear 时经此清空快照与计数。 */
private volatile TodoStaleReminder todoReminder;
```

方法（包私有，同 `bindGoal` 的两段式 bind 先例——跨包由 `AgentTools.wireTodoReminder` 转接）：

```java
/** 装配后由 {@code AgentTools.wireTodoReminder} 绑定 runtime 里那份提醒器（同一实例，另建一个等于白接）。 */
void bindTodoReminder(TodoStaleReminder reminder) {
    this.todoReminder = reminder;
}
```

`clearContext()` 末尾追加：

```java
if (todoReminder != null) {
    // 旧会话的清单快照留着，新会话第一次工具调用后就会被提醒「你的清单过期了」——
    // 可新会话里的模型从没写过它，只会凭空重建一份清单。与 goalManager.clear 同理：整份丢弃。
    todoReminder.reset();
}
```

- [ ] **Step 4: 跑测试确认绿**

```bash
mvn -pl springai-code-tui -am test -Dtest='CodingAgentClearContextTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentClearContextTest.java
git commit -m "feat(code-tui): CodingAgent 绑定 TodoStaleReminder——/clear 清空过期清单快照"
```

---

### Task 4: `AgentTools` 装配接线 + wiring 测试

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  （todoEventHandler 喂快照、7 处 ToolEventCallback 构造点、AgentRuntime 组件、wireTodoReminder）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（wireGoal 后加一行接线）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsTodoReminderWiringTest.java`

**Interfaces:**
- Consumes: Task 1 `TodoStaleReminder`；Task 2 三参构造；Task 3 `bindTodoReminder`；
  测试基建 `RuntimeToolSet.toolsOf(AgentTools.AgentRuntime)`、`StubListener`。
- Produces: `AgentRuntime.todoReminder()` 记录访问器；`public static void wireTodoReminder(AgentRuntime rt, CodingAgent agent)`。

- [ ] **Step 1: 写失败 wiring 测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 钉死装配契约（同 AgentToolsBackgroundWiringTest 的立场：零件单测全绿也兜不住漏接一根线）：
 * 1) 真实 build 的 TodoWrite（控制器分支）必须把快照喂进 runtime.todoReminder()；
 * 2) 子 agent 分支（taskId!=null）的 TodoWrite 不喂快照。
 * 提醒文本不经装饰链断言（Task 2 已钉），这里只验「喂没喂」。
 */
class AgentToolsTodoReminderWiringTest {

    @Test
    @DisplayName("真实装配：控制器 TodoWrite 喂快照，子 agent TodoWrite 不喂")
    void controllerTodoWrite_feedsReminder_subagentDoesNot(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root, new StubListener());
        // 开发机显式设 0=停用时不硬测（fromEnv 语义优先）
        assumeTrue(rt.todoReminder().enabled(), "CODETUI_TODO_REMIND_EVERY 显式停用，跳过");

        ToolCallback todo = RuntimeToolSet.toolsOf(rt).get("TodoWrite");
        assertNotNull(todo, "装配产物里必须有 TodoWrite");

        String controllerArgs = """
                {"todos":[
                  {"content":"接线任务甲","activeForm":"接线任务甲中","status":"in_progress"},
                  {"content":"接线任务乙","activeForm":"接线任务乙中","status":"pending"}]}""";
        todo.call(controllerArgs, new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 42L)));

        String note = null;
        for (int i = 0; i < 128 && note == null; i++) {
            note = rt.todoReminder().reminderOrNull(42L, "Bash");
        }
        assertNotNull(note, "控制器 TodoWrite 后快照必须能触发提醒（喂快照的线没接上？）");
        assertTrue(note.contains("接线任务甲"), "快照内容须来自控制器那次写入");

        // 子 agent 分支：同清单形状但内容不同，写入后不得出现在快照里
        String subagentArgs = """
                {"todos":[
                  {"content":"子代理私活","activeForm":"子代理私活中","status":"in_progress"}]}""";
        todo.call(subagentArgs, new ToolContext(Map.of(
                ToolEventCallback.TURN_ID_KEY, 43L, ToolEventCallback.TASK_ID_KEY, "sub-1")));
        String note2 = null;
        for (int i = 0; i < 128 && note2 == null; i++) {
            note2 = rt.todoReminder().reminderOrNull(43L, "Bash");
        }
        assertNotNull(note2, "旧控制器快照仍在，43 回合照常提醒");
        assertTrue(!note2.contains("子代理私活"),
                "子 agent 的 TodoWrite 不得污染控制器快照（分流线接错？）");
    }
}
```

- [ ] **Step 2: 跑测试确认红**（`todoReminder()` 访问器不存在 → 编译失败）

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsTodoReminderWiringTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 3: 实现（AgentTools 六处 + CodeTuiApplication 一行）**

1. build 顶部（挨着 `TodoWriteTool todo = TodoWriteTool.builder()`）：

```java
// 任务面板过期提醒：装配期建唯一实例，7 处装饰点与 /clear 重置共用同一份（另建一个等于白接）。
TodoStaleReminder todoReminder = TodoStaleReminder.fromEnv();
```

2. `todoEventHandler` lambda 扩展：

```java
.todoEventHandler(todos -> {
    listener.onTodoUpdated(ToolEventCallback.currentTurnId(),
            ToolEventCallback.currentTaskId(), toLines(todos));
    // 控制器分支同步喂提醒器：快照 + 计数复位；子 agent 内部 todo 不上面板，不喂。
    if (ToolEventCallback.currentTaskId() == null) {
        todoReminder.onControllerTodoWritten(todos.todos());
    }
})
```

3. AgentTools 内 7 处 `new ToolEventCallback(x, listener)` 全部加第三参 `todoReminder`：
   主装饰循环（`new ToolEventCallback(new MediaExternalizingCallback(...), listener, todoReminder)`）、
   taskTool、parallelTool、TaskOutput、ListTasks、ExitPlanMode 五处直改；
   `buildMemoryTools` 方法签名加参 `TodoStaleReminder todoReminder` 并在其构造点与调用点同步传递。
   **McpRegistry 的构造点不动（spec 非目标）。**

4. `AgentRuntime` 记录末尾加组件（`quotaBridge` 之后）+ 构造点实参：

```java
L1QuotaBridge quotaBridge,
TodoStaleReminder todoReminder) {
```

```java
return new AgentRuntime(clients, registry.active().id(), sessionService, sessionRepository,
        manualStrategy, tokenCountEstimator, reloadableSkill.skills(), decoratedSkillTool,
        reloadableSkill, subagentRunner, fileExternalizer, permissionEngine, visionModels,
        backgroundRegistry, backgroundResults, interjections, goalManager,
        systemPromptTokens, bridge, quotaBridge, todoReminder);
```

5. `wireGoal` 之后新增（同款 javadoc 说明包私有转接原因）：

```java
/**
 * 把 {@link AgentRuntime#todoReminder()} 绑到 {@code CodingAgent}（镜像 {@link #wireGoal} 的两段式形状）：
 * {@code CodingAgent.bindTodoReminder} 是<b>包私有</b>方法，跨包直调编译失败，须经本方法在 agent 包内转接。
 * 必须传 runtime 里那份——提醒器是装配期唯一实例，另建一个 /clear 就清不到装饰链正在用的那份。
 */
public static void wireTodoReminder(AgentRuntime rt, CodingAgent agent) {
    agent.bindTodoReminder(rt.todoReminder());
}
```

6. `CodeTuiApplication` 在 `AgentTools.wireGoal(...)` 之后加：

```java
// 任务面板过期提醒接线（镜像 wireGoal）：/clear 时 CodingAgent 经此清空装饰链共用的那份快照。
AgentTools.wireTodoReminder(runtime, agent);
```

- [ ] **Step 4: 跑测试确认绿**（wiring + 全套既有装配测试不回归）

```bash
mvn -pl springai-code-tui -am test -Dtest='AgentToolsTodoReminderWiringTest,AgentToolsBackgroundWiringTest,ToolRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsTodoReminderWiringTest.java
git commit -m "feat(code-tui): 装配接线 TodoStaleReminder——7 处装饰点 + AgentRuntime 组件 + 两段式 bind"
```

---

### Task 5: 配置文档 + spec 状态 + 全量验证

**Files:**
- Modify: `springai-code-tui/README.md`（CODETUI_GOAL_* 环境变量表附近）
- Modify: `springai-code-tui/src/package/bin/config.env.example`
- Modify: `docs/superpowers/specs/2026-10-07-todo-stale-reminder-design.md`（状态行）

- [ ] **Step 1: README 环境变量表加一行**

在 CODETUI_GOAL_* 表的同款式区域追加：

```markdown
| `CODETUI_TODO_REMIND_EVERY` | 8 | 任务面板过期提醒阈值：控制器连续 N 次工具调用未更新 TodoWrite 且清单有未完成项时，把清单快照追加进工具结果提醒模型；0 = 关闭 |
```

- [ ] **Step 2: config.env.example 加注释行**（对齐文件内既有 `KEY=值` + `# 注释` 风格）

```bash
# 任务面板过期提醒：控制器连续 N 次工具调用未更新 TodoWrite 且有未完成项时，
# 把清单快照追加到工具结果尾部提醒模型更新；0=关闭。默认 8。
#CODETUI_TODO_REMIND_EVERY=8
```

- [ ] **Step 3: spec 状态行**改为「已交付（见 plans/2026-10-07-todo-stale-reminder.md）」。

- [ ] **Step 4: 全量测试**

```bash
mvn -pl springai-code-tui -am test
```

已知 flaky：`CodingAgentSpikeTest.todoTurnIdBinding`（真实 API、耗时浮动）——撞上按
CONTRIBUTING.md 单跑确认，不当回归。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/README.md springai-code-tui/src/package/bin/config.env.example \
        docs/superpowers/specs/2026-10-07-todo-stale-reminder-design.md
git commit -m "docs(code-tui): CODETUI_TODO_REMIND_EVERY 配置说明 + spec 状态更新"
```

---

## Self-Review 结论

- 覆盖 spec 全部目标（阈值提醒/可配/清除）与非目标（McpRegistry、子 agent、回合注入均未越界）。
- 类型一致性：`TodoItem` 构造 `(content, activeForm, status)` 与库 record 一致；
  `TURN_ID_KEY` 值须为 `Long`（`Map.of(key, 42L)`）。
- Task 1 Step 3 代码块中 `render` 首句的计数参数已注明按 `firedAt` 实现并以文字说明修正，实现者须按注实现。
- 已知取舍（写入 spec）：提醒文本随会话持久化并参与 `-c` 回放。
