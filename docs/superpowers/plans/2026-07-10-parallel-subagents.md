# 并行子 agent（批量 ParallelTasks 工具）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 新增一个批量子 agent 工具 `ParallelTasks`，在一个回合内并发执行多个相互独立的子 agent，使 `dispatching-parallel-agents` skill 生效。

**Architecture:** 在 `SubagentRunner` 新增 `runAll`，用回合级固定线程池 fan-out 现有 `run()`；turnId 在 fan-out 前显式捕获传入闭包（不依赖 ThreadLocal）。新增 `SubagentTool.createParallel` 批量工具，路由 + 结构化汇总。`AgentTools.build` 读并发度环境变量、装配并加入主 agent 工具集。现有单任务 `Task` 零改动。

**Tech Stack:** Java 17、JDK `java.util.concurrent`（`ExecutorService` / `invokeAll`）、Spring AI 2.0（`ToolCallback` / `FunctionToolCallback`）、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-07-10-parallel-subagents-design.md`

**验证命令基线（模块作用域）:** `mvn -pl springai-code-tui test`

---

## File Structure

- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`
  — 新增内嵌 record `Dispatch`、字段 `maxConcurrency`、新构造重载、方法 `runAll`。现有 `run()`/`filterTools()` 等不动。
- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentTool.java`
  — 新增 record `ParallelCall`、函数式接口 `BatchDispatcher`、工厂 `createParallel`、纯函数 `batchFunction`。现有 `create`/`function`/`SubagentCall`/`Dispatcher` 不动。
- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  — 读 `CODETUI_SUBAGENT_CONCURRENCY`；用新构造建 `SubagentRunner`；建并装饰 `ParallelTasks`；加入 `toolsWithTask`。
- **Create** `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerParallelTest.java`
  — `runAll` 的顺序/失败隔离/turnId 传播/并发上限/并发安全测试。
- **Modify** `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolTest.java`
  — 追加 `ParallelTasks` 的 schema/路由/未知类型降级测试。

**关键约束（贯穿全程）:** fan-out 前在工具线程读一次 `parentTurnId`，作为参数传入每个 Callable；子线程内绝不调用 `ToolEventCallback.currentTurnId()`。

---

## Task 1: SubagentRunner 支持并发度构造 + Dispatch record

给 `SubagentRunner` 增加 `maxConcurrency` 字段与 `Dispatch` 入参 record，为 `runAll` 铺垫。先不加 `runAll` 逻辑，仅结构 + 构造，保证既有测试全绿。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`

- [ ] **Step 1: 加 import 与并发度字段/构造**

在 import 区（现有 `import java.util.function.Supplier;` 之后）追加：

```java
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
```

在类体字段区（现有 `private final Supplier<String> taskIdSupplier;` 之后）追加：

```java
    /** 批量 runAll 的并发上限（同时在飞的子 agent 数）。默认 4，可经装配层用环境变量覆盖。 */
    private final int maxConcurrency;
```

- [ ] **Step 2: 让现有两个构造设默认并发度，并加一个带并发度的构造**

把现有的两个构造函数体改为委托，并新增一个显式带 `maxConcurrency` 的私有主构造。将原有两个构造替换为：

```java
    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions) {
        this(registry, tools, listener, projectInstructions, 4);
    }

    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions, int maxConcurrency) {
        this(registry, tools, listener, projectInstructions, maxConcurrency,
                () -> "task_" + UUID.randomUUID());
    }

    SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                   String projectInstructions, int maxConcurrency, Supplier<String> taskIdSupplier) {
        this.registry = registry;
        this.tools = tools;
        this.listener = listener;
        this.projectInstructions = projectInstructions == null ? "" : projectInstructions;
        this.taskIdSupplier = taskIdSupplier;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }
```

> 注意：原来那个包级 `SubagentRunner(..., Supplier<String> taskIdSupplier)`（5 参）被上面的 6 参版本取代。现有测试若用了 5 参构造需一并更新（Step 4 检查）。

- [ ] **Step 3: 加 Dispatch record**

在类体末尾（现有 `filterTools` 方法之后、类结束 `}` 之前）追加：

```java
    /** 一次批量委派中的单个子任务：路由后的 spec + 该子任务的 prompt/description。 */
    public record Dispatch(SubagentSpec spec, String prompt, String description) {
    }
```

- [ ] **Step 4: 编译并修复既有 5 参构造调用点**

Run:
```bash
grep -rn "new SubagentRunner(" springai-code-tui/src
```
Expected: 找出所有调用点。若有传 5 个参数（旧 `taskIdSupplier` 重载）的测试，改为 6 参（在 `projectInstructions` 与 `taskIdSupplier` 之间插入并发度实参，如 `2`）。生产调用点 `AgentTools` 是 4 参，Task 3 再改。

- [ ] **Step 5: 运行既有子 agent 测试确认未破坏**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentRunnerTest,SubagentRunnerOkTest'`
Expected: PASS（BUILD SUCCESS）。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java
git commit -m "refactor(subagent): SubagentRunner 增并发度字段与 Dispatch record"
```

---

## Task 2: runAll 并发执行（fan-out + join + 失败隔离 + turnId 传播）

TDD 落地 `runAll`：先写会失败的测试（顺序、失败隔离、turnId、并发上限），再实现。

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerParallelTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`

- [ ] **Step 1: 写失败测试文件**

Create `SubagentRunnerParallelTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SubagentRunner.runAll 并发执行：顺序、失败隔离、turnId 传播、并发上限。全部用假 ChatModel，不联网。 */
class SubagentRunnerParallelTest {

    /** 假 ChatModel：返回固定 replyText；needleThrows=true 时抛异常（模拟子 agent 失败）。 */
    private static ChatModel chatModel(String replyText, boolean shouldThrow) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (shouldThrow) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage(replyText))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
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

    @Test
    void runAll_returnsResultsInInputOrder() {
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"),
                new SubagentRunner.Dispatch(spec(), "p3", "d3"));
        List<String> out = runner.runAll(ds, 1L);
        assertEquals(3, out.size());
        assertEquals(List.of("ok", "ok", "ok"), out);
    }

    @Test
    void runAll_isolatesFailures_othersStillSucceed() {
        // provider 用一个「按 prompt 决定成败」的 chatModel：prompt 含 "fail" 则抛
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (prompt.getContents().contains("fail")) throw new RuntimeException("boom");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("blocking path only");
            }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(model)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "please succeed", "d1"),
                new SubagentRunner.Dispatch(spec(), "please fail here", "d2"),
                new SubagentRunner.Dispatch(spec(), "succeed again", "d3"));
        List<String> out = runner.runAll(ds, 1L);
        assertEquals(3, out.size());
        assertEquals("ok", out.get(0));
        assertTrue(out.get(1).startsWith("失败："), "失败条应以「失败：」开头，实际=" + out.get(1));
        assertEquals("ok", out.get(2));
    }

    @Test
    void runAll_propagatesParentTurnIdToListener_offMainThread() {
        // 断言子 agent 事件带的 turnId == 传入 parentTurnId（锁死 ThreadLocal 陷阱）
        Set<Long> seenTurnIds = ConcurrentHashMap.newKeySet();
        StubListener lis = new StubListener() {
            @Override public void onSubagentStarted(long turnId, String taskId, String name, String desc) {
                seenTurnIds.add(turnId);
            }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(chatModel("ok", false))));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), lis, "", 4);
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"));
        runner.runAll(ds, 77L);
        assertEquals(Set.of(77L), seenTurnIds, "所有子 agent 事件应带传入的 parentTurnId=77，不得为 -1（ThreadLocal 丢失）");
    }

    @Test
    void runAll_respectsConcurrencyLimit() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        // chatModel 进入后累加在飞计数、记录峰值，然后阻塞在 latch 上，直到测试放行
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                int now = inFlight.incrementAndGet();
                maxObserved.accumulateAndGet(now, Math::max);
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                inFlight.decrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().build(); }
        };
        ProviderRegistry reg = new ProviderRegistry(List.of(provider(model)));
        SubagentRunner runner = new SubagentRunner(reg, List.of(), new StubListener(), "", 2);  // 上限 2
        List<SubagentRunner.Dispatch> ds = List.of(
                new SubagentRunner.Dispatch(spec(), "p1", "d1"),
                new SubagentRunner.Dispatch(spec(), "p2", "d2"),
                new SubagentRunner.Dispatch(spec(), "p3", "d3"),
                new SubagentRunner.Dispatch(spec(), "p4", "d4"));
        // 在后台线程跑 runAll，主线程等一小会儿再放行 latch，观察峰值
        Thread t = new Thread(() -> runner.runAll(ds, 1L));
        t.start();
        Thread.sleep(300);   // 让前两个进入并阻塞
        assertTrue(maxObserved.get() <= 2, "同时在飞不得超过并发上限 2，实际峰值=" + maxObserved.get());
        release.countDown();
        t.join(3000);
    }
}
```

- [ ] **Step 2: 运行确认测试失败（runAll 未定义）**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentRunnerParallelTest'`
Expected: 编译失败 —— `cannot find symbol: method runAll(...)`。

- [ ] **Step 3: 实现 runAll**

在 `SubagentRunner` 类体（`run(...)` 方法之后）追加：

```java
    /**
     * 批量并发执行多个子 agent，join 全部后按<b>入参顺序</b>返回各自结果。
     *
     * <p>失败隔离：单个子 agent 抛错不影响其他，该位置返回「失败：<msg>」文本（子 agent 内 run() 已 emit
     * onSubagentFinished(ok=false)）。turnId <b>显式</b>传入每个任务闭包——绝不在子线程读 ThreadLocal
     * （否则 turnId 丢失、UI 事件被迟到过滤器丢弃）。
     *
     * <p>线程池为<b>回合级局部</b>：容量 min(N, maxConcurrency)，join 后 shutdownNow 立即回收，无常驻线程。
     */
    public List<String> runAll(List<Dispatch> dispatches, long parentTurnId) {
        int n = dispatches.size();
        if (n == 0) {
            return new ArrayList<>();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, maxConcurrency));
        try {
            List<Callable<String>> tasks = new ArrayList<>(n);
            for (Dispatch d : dispatches) {
                // parentTurnId 显式捕获进闭包；子线程不读 ThreadLocal
                tasks.add(() -> {
                    try {
                        return run(d.spec(), d.prompt(), d.description(), parentTurnId);
                    } catch (RuntimeException ex) {
                        return "失败：" + ex.getMessage();   // 失败隔离：不外抛
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            List<String> results = new ArrayList<>(n);
            for (Future<String> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception ex) {   // 理论上 Callable 已吞异常；兜底
                    results.add("失败：" + ex.getMessage());
                }
            }
            return results;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new RuntimeException("并行子任务被中断", ie);
        } finally {
            pool.shutdownNow();
        }
    }
```

- [ ] **Step 4: 运行确认全部通过**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentRunnerParallelTest'`
Expected: PASS（4 个测试全绿）。

- [ ] **Step 5: 运行既有子 agent 测试确认无回归**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentRunnerTest,SubagentRunnerOkTest'`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerParallelTest.java
git commit -m "feat(subagent): SubagentRunner.runAll 并发执行 + 失败隔离 + turnId 显式传播"
```

---

## Task 3: ParallelTasks 批量工具（schema + 路由 + 结构化汇总）

TDD 落地批量 `ToolCallback`：入参 `tasks` 列表，路由每个 `subagent_type`，未知类型降级为失败，结构化汇总。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentTool.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolTest.java`

- [ ] **Step 1: 追加失败测试**

在 `SubagentToolTest` 类体末尾（现有 `knownTypeDispatchesToRunner` 之后）追加：

```java
    @Test
    void parallelToolNameAndSchemaHasTasksList() {
        ToolCallback tc = SubagentTool.createParallel(Map.of(), (dispatches, turn) -> List.of());
        assertEquals("ParallelTasks", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("tasks"));
        assertTrue(schema.contains("subagent_type"));
        assertTrue(schema.contains("prompt"));
    }

    @Test
    void parallelRoutesKnownAndDegradesUnknownToFailure() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        // BatchDispatcher 桩：把每个 Dispatch 回显为 "ran <name>"
        SubagentTool.BatchDispatcher batch = (dispatches, turn) ->
                dispatches.stream().map(d -> "ran " + d.spec().name()).toList();
        var fn = SubagentTool.batchFunction(specs, batch);
        SubagentTool.ParallelCall pc = new SubagentTool.ParallelCall(List.of(
                new SubagentTool.SubagentCall("do a", "pa", "explore"),
                new SubagentTool.SubagentCall("do b", "pb", "no-such")));
        String out = fn.apply(pc);
        // 第 1 条成功、第 2 条未知类型降级为失败，且不抛异常
        assertTrue(out.contains("[1] explore"), out);
        assertTrue(out.contains("ran explore"), out);
        assertTrue(out.contains("[2] no-such ✗"), out);
        assertTrue(out.contains("未知 subagent 类型"), out);
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentToolTest'`
Expected: 编译失败 —— `cannot find symbol: createParallel / BatchDispatcher / batchFunction / ParallelCall`。

- [ ] **Step 3: 实现批量工具**

在 `SubagentTool` 类体追加以下成员。先在 import 区确认已有 `java.util.List`（若无则加 `import java.util.List;`），并加 `import java.util.ArrayList;`。

入参 record（放在现有 `SubagentCall` record 之后）：

```java
    /** 批量子 agent 调用入参：一组子任务，每个复用单任务的三元组。 */
    public record ParallelCall(
            @ToolParam(description = "List of independent subtasks to run concurrently") List<SubagentCall> tasks) {
    }
```

批量执行器接口（放在现有 `Dispatcher` 接口之后）：

```java
    /** 把一批路由后的委派交给并发执行器（SubagentRunner.runAll 的函数式视图），按入参顺序返回各自结果文本。 */
    @FunctionalInterface
    public interface BatchDispatcher {
        List<String> dispatch(List<SubagentRunner.Dispatch> dispatches, long parentTurnId);
    }
```

批量工具描述常量（放在类顶部 `DESCRIPTION_TEMPLATE` 之后）：

```java
    private static final String PARALLEL_DESCRIPTION_TEMPLATE = """
            Launch MULTIPLE subagents CONCURRENTLY to handle several INDEPENDENT subtasks at once.

            Use this ONLY when you have 2+ subtasks that are mutually independent and share no state
            (e.g. investigating unrelated failures, exploring separate subsystems). If subtasks depend
            on each other or need shared context, use the single Task tool instead.

            Each subtask has the same shape as Task: description, prompt, subagent_type. All subtasks
            run in parallel; results are returned together, one block per subtask (in input order),
            each marked success/failure independently — one failing subtask does not abort the others.

            Available subagent types:
            %s
            """;
```

工厂方法（放在现有 `create(...)` 之后）：

```java
    /** 构建名为 "ParallelTasks" 的批量 ToolCallback。parentTurnId 由 BatchDispatcher 实现内部经 ThreadLocal 取，这里占位 -1L。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("ParallelTasks", batchFunction(specs, dispatcher))
                .description(PARALLEL_DESCRIPTION_TEMPLATE.formatted(roster))
                .inputType(ParallelCall.class)
                .build();
    }
```

纯路由 + 汇总函数（放在现有 `function(...)` 之后）：

```java
    /**
     * 批量路由函数：把每个 SubagentCall 路由成 SubagentRunner.Dispatch（未知类型该条降级为失败、<b>不抛</b>，
     * 服从失败隔离——与单任务 Task 抛异常有意不同）。已知条交 BatchDispatcher 并发执行，最后按入参顺序结构化汇总。
     */
    static Function<ParallelCall, String> batchFunction(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return call -> {
            List<SubagentTool.SubagentCall> tasks = call.tasks() == null ? List.of() : call.tasks();
            // 记录每条的路由结果：命中→索引进 dispatchable；未命中→占位失败文本
            List<SubagentRunner.Dispatch> dispatchable = new ArrayList<>();
            List<Integer> dispatchIndex = new ArrayList<>();   // dispatchable[k] 对应 tasks 的原始下标
            String[] failure = new String[tasks.size()];       // 未知类型的失败文本（其余为 null）
            String[] typeName = new String[tasks.size()];      // 每条的 subagent_type（用于汇总标题）
            for (int i = 0; i < tasks.size(); i++) {
                SubagentTool.SubagentCall t = tasks.get(i);
                typeName[i] = t.subagent_type();
                SubagentSpec spec = specs.get(t.subagent_type());
                if (spec == null) {
                    failure[i] = "未知 subagent 类型: " + t.subagent_type()
                            + "（可用: " + String.join(", ", specs.keySet()) + "）";
                } else {
                    dispatchable.add(new SubagentRunner.Dispatch(spec, t.prompt(), t.description()));
                    dispatchIndex.add(i);
                }
            }
            List<String> ran = dispatcher.dispatch(dispatchable, -1L);
            // 回填：已执行的按 dispatchIndex 归位
            String[] body = new String[tasks.size()];
            boolean[] ok = new boolean[tasks.size()];
            for (int i = 0; i < tasks.size(); i++) {
                if (failure[i] != null) { body[i] = failure[i]; ok[i] = false; }
            }
            for (int k = 0; k < ran.size(); k++) {
                int idx = dispatchIndex.get(k);
                String r = ran.get(k);
                boolean failed = r != null && r.startsWith("失败：");
                body[idx] = r;
                ok[idx] = !failed;
            }
            // 结构化汇总：[n] <type> ✓/✗，空行分隔，按入参顺序
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) sb.append("\n\n");
                sb.append("[").append(i + 1).append("] ").append(typeName[i])
                        .append(ok[i] ? " ✓" : " ✗");
                sb.append("\n").append(body[i] == null ? "" : body[i]);
            }
            return sb.toString();
        };
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='SubagentToolTest'`
Expected: PASS（含新增 2 个测试）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentToolTest.java
git commit -m "feat(subagent): ParallelTasks 批量工具（路由+未知降级+结构化汇总）"
```

---

## Task 4: AgentTools 装配 —— 并发度配置 + 注册 ParallelTasks

把并发度环境变量、`runAll` 桥接、`ParallelTasks` 工具接进主 agent 工具集。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:216-236`

- [ ] **Step 1: 读并发度环境变量并用新构造建 SubagentRunner**

定位现有片段（约 AgentTools.java:220-221）：

```java
        java.util.List<ToolCallback> decoratedList = java.util.List.of(decorated);
        SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener, projectInstructions);
```

替换为：

```java
        java.util.List<ToolCallback> decoratedList = java.util.List.of(decorated);
        int subagentConcurrency = resolveSubagentConcurrency();
        SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener,
                projectInstructions, subagentConcurrency);
```

- [ ] **Step 2: 在 AgentTools 加并发度解析辅助方法**

在 `AgentTools` 类体（`build` 方法之后、类的其它 private static 辅助方法附近）追加：

```java
    /** 子 agent 并行并发度：环境变量 CODETUI_SUBAGENT_CONCURRENCY，非法/缺失回退 4，下限 1。 */
    private static int resolveSubagentConcurrency() {
        String raw = System.getenv("CODETUI_SUBAGENT_CONCURRENCY");
        if (raw == null || raw.isBlank()) {
            return 4;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 4;
        }
    }
```

- [ ] **Step 3: 构造并装饰 ParallelTasks 工具**

定位现有片段（约 AgentTools.java:223-227）：

```java
        ToolCallback taskTool = SubagentTool.create(subagentSpecs,
                (spec, prompt, desc, turnIgnored) ->
                        // 真实 parentTurnId 从 ThreadLocal 取（Task 工具被 ToolEventCallback 装饰，call 时已压入）
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()));
        ToolCallback decoratedTaskTool = new ToolEventCallback(taskTool, listener);
```

在其后追加：

```java
        // 批量 ParallelTasks 工具：并发执行多个独立子 agent。parentTurnId 在工具线程（fan-out 前）从 ThreadLocal 取，
        // 再由 runAll 显式传入每个子任务闭包（子线程不读 ThreadLocal）。
        ToolCallback parallelTool = SubagentTool.createParallel(subagentSpecs,
                (dispatches, turnIgnored) ->
                        subagentRunner.runAll(dispatches, ToolEventCallback.currentTurnId()));
        ToolCallback decoratedParallelTool = new ToolEventCallback(parallelTool, listener);
```

- [ ] **Step 4: 加入主 agent 工具集**

定位现有片段（约 AgentTools.java:232-236）：

```java
        // 主 agent 工具集 = 原装饰工具 + 记忆工具（仅主 agent）+ Task 工具
        Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 1];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
        toolsWithTask[toolsWithTask.length - 1] = decoratedTaskTool;
```

替换为：

```java
        // 主 agent 工具集 = 原装饰工具 + 记忆工具（仅主 agent）+ Task 工具 + ParallelTasks 工具
        // 注意：ParallelTasks 仅给主 agent，不进 decoratedList，故子 agent 不能再派并行子 agent（禁递归 fan-out）。
        Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 2];
        System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
        System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
        toolsWithTask[toolsWithTask.length - 2] = decoratedTaskTool;
        toolsWithTask[toolsWithTask.length - 1] = decoratedParallelTool;
```

- [ ] **Step 5: 编译整模块**

Run: `mvn -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS（无编译错误）。

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java
git commit -m "feat(subagent): AgentTools 注册 ParallelTasks + CODETUI_SUBAGENT_CONCURRENCY 并发度"
```

---

## Task 5: 全量验证 + 取消可靠性实测（开放风险确认）

跑全量测试；实测取消行为，确认 spec 里标记的开放风险，必要时落地退路。

**Files:**
- （可能）Modify: `SubagentRunner.java` / `AgentTools.java`（仅当取消实测暴露问题）

- [ ] **Step 1: 全量模块测试**

Run: `mvn -pl springai-code-tui test`
Expected: BUILD SUCCESS，全部测试通过（含既有 + 新增并发测试）。

- [ ] **Step 2: 打包并 pty 冒烟（若改了渲染无关，可跳过 pty；此处仅确认可启动）**

Run: `mvn -pl springai-code-tui -Pdist package -DskipTests`
Expected: 产出 dist 包，无打包错误。

> 记忆规范：涉及 TUI 渲染变更须 pty 实机验证。本功能不改渲染（事件已按 taskId keyed），故只需确认启动与工具注册。

- [ ] **Step 3: 取消可靠性实测（开放风险 1）**

手动或脚本：配置一个可用 provider，让主 agent 触发 `ParallelTasks`（可在 prompt 里明确要求「同时用两个 general-purpose 子 agent 做两件独立的事」），在子 agent 在飞时按 Esc。
观察：UI 是否**立即**回到 IDLE、状态栏是否残留、是否出现下一轮 400（悬空 tool_calls）。

- [ ] **Step 4: 若取消未立即生效，落地退路（volatile 取消标志）**

仅当 Step 3 发现 Esc 后 UI 未及时回 IDLE 或阻塞：在 `SubagentRunner` 加一个由 CodingAgent `doOnCancel` 置位的 volatile 标志（或经构造注入 `Supplier<Boolean> cancelled`），在 `runAll` 的结果收集循环里检查、短路剩余 join。具体接线在实测确认信号来源后补齐（此步为条件触发，非必然）。

- [ ] **Step 5: 最终提交（若 Step 4 有改动）**

```bash
git add -A
git commit -m "test(subagent): 并行子 agent 全量验证 + 取消实测（含退路，如需）"
```

---

## Self-Review

**1. Spec coverage（逐项对照 spec）:**
- 批量 fan-out + join → Task 2 `runAll`. ✅
- 新增 `ParallelTasks` 保留单任务 `Task` → Task 3（`create`/`function` 不动）. ✅
- 并发度默认 4 可配 `CODETUI_SUBAGENT_CONCURRENCY` → Task 1（构造）+ Task 4（`resolveSubagentConcurrency`）. ✅
- 失败隔离 → Task 2 `runAll` catch + Task 3 未知类型降级. ✅
- 结构化结果（按入参顺序、`[n] <type> ✓/✗`）→ Task 3 `batchFunction` 汇总. ✅
- 取消尽力中断 + 开放风险退路 → Task 5 实测 + 条件退路. ✅
- turnId 显式传播非 ThreadLocal → Task 2 闭包捕获 + `runAll_propagatesParentTurnId` 测试. ✅
- 不给子 agent 分发 ParallelTasks → Task 4 Step 4 注释与装配（不进 decoratedList）. ✅
- 测试策略全部项 → Task 2 + Task 3 测试. ✅

**2. Placeholder scan:** Task 5 Step 4 是**条件触发**（仅取消实测出问题时），非占位——已注明「此步为条件触发」，退路方向明确（volatile 标志/Supplier 注入 + 收集循环短路）。其余步骤均含完整代码/命令。

**3. Type consistency:**
- `SubagentRunner.Dispatch(SubagentSpec, String, String)` — Task 1 定义，Task 2/3 一致引用。
- `runAll(List<Dispatch>, long)` — Task 2 定义、测试与 Task 4 桥接一致。
- `SubagentTool.ParallelCall(List<SubagentCall> tasks)`、`BatchDispatcher.dispatch(List<Dispatch>, long)`、`createParallel`、`batchFunction` — Task 3 定义，Task 3 测试与 Task 4 装配一致。
- 失败前缀 `"失败："` — Task 2 产生、Task 3 `batchFunction` 以 `startsWith("失败：")` 判定，一致。
- 工具名 `"ParallelTasks"` — Task 3 常量与测试断言一致。

无不一致。
