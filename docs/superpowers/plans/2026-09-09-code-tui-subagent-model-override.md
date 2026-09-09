# code-tui 子 agent 委派模型选择 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让主 agent 调用 `Task` / `ParallelTasks` 时可按次为某个子任务指定模型（`provider:modelId`，支持跨 provider），未知/不可用模型清楚报错，UI 展示每次委派实际用的模型。

**Architecture:** `SubagentTool.SubagentCall` 新增可选 `model` 字段；路由层在派给 dispatcher 前用它派生一份 `spec.withModel(override)`，其余全部复用既有 `SubagentRunner`/`ProviderRegistry` 解析链路——`Dispatcher`/`BackgroundDispatcher`/`SubagentRunner` 的方法签名不变。顺带修复 `ProviderRegistry.requestSelection` 现有的跨 provider 解析缺陷（会静默丢弃 provider 前缀、退化到激活 provider），静态 `spec.model`（agents/*.md frontmatter）与本次新增的动态覆盖共享同一套解析代码。`AgentListener`/`ConversationState`/`CodeTuiView` 新增一条展示通道，把每次委派实际解析出的模型显示在 scrollback 与任务面板。

**Tech Stack:** Java 21 records、Spring AI 2.0（`FunctionToolCallback`/`ChatClient`）、JUnit 5、Maven（模块 `springai-code-tui`，验证命令须 `-pl springai-code-tui` 模块作用域）。

设计依据：`docs/superpowers/specs/2026-09-09-code-tui-subagent-model-override-design.md`（已获用户确认）。

**行号说明：** 每一步给出的「第 N-M 行」都是相对改动前那一刻的原始文件算的。同一个文件在同一任务甚至同一步骤里被连续编辑多次时，后一处编辑发生的那一刻，前面的编辑已经让实际行号往下挪了——按数字翻到那一行可能对不上。所有步骤都配了完整的「把 A 替换为 B」代码块，请始终按代码块内容搜索定位，行号只作为大致方位参考，不要当成精确坐标。

---

## Task 1: ProviderRegistry — 精确跨 provider `requestSelection`

现状缺陷：`ProviderRegistry.requestSelection(String modelId)` 两个 if 分支做的是同一件事（`return selection(provider, modelId)`），从不真正校验 modelId 是否属于激活 provider，也永远不会跨 provider 查找——传一个属于别家 provider 的 modelId，会悄悄拿激活 provider + 这个错误的 modelId 拼出一个坏 selection。本任务修好它，并新增精确的 `requestSelection(providerId, modelId)`，是后续 `SubagentRunner` 支持跨 provider 覆盖的地基。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/ProviderRegistry.java:90-103`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ProviderRegistryThinkingTest.java`

- [ ] **Step 1: 追加 4 个失败测试**

在 `ProviderRegistryThinkingTest.java` 里，紧接现有的 `sameModelIdAcrossProvidersKeepsThinkingSeparate` 测试方法（文件第 74-87 行）之后、类结束的 `}` 之前，插入：

```java
    /** 精确路由必须认 providerId，不能被「谁排前面」决定——即便两家都持有同名 modelId。 */
    @Test
    void requestSelectionTwoArgRoutesToExactProvider() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new OpencodeGoProvider("k")));
        // deepseek 排第一（激活 provider），但显式指定 opencode-go：必须精确路由到 opencode-go，不能落到激活的 deepseek
        ProviderRegistry.RequestSelection sel = reg.requestSelection("opencode-go", "deepseek-v4-pro");
        assertEquals("opencode-go", sel.provider().id());
        assertEquals("deepseek-v4-pro", sel.modelId());
    }

    @Test
    void requestSelectionTwoArgUnknownThrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> reg.requestSelection("deepseek", "deepseek-v4-pro"));
    }

    @Test
    void requestSelectionOneArgUnknownThrows() {
        ProviderRegistry reg = new ProviderRegistry(List.of(new OpenAiProvider("k")));
        assertThrows(IllegalArgumentException.class,
                () -> reg.requestSelection("totally-bogus-model"));
    }

    /** 回归：今天两个分支等价，会把「不属于激活 provider 的 modelId」硬塞给激活 provider。 */
    @Test
    void requestSelectionOneArgFindsOwnerAcrossProviders() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new OpenAiProvider("k")));
        // 激活 provider 是列表首个 available 的（DeepSeek），但这个 modelId 只属于 OpenAI
        ProviderRegistry.RequestSelection sel = reg.requestSelection("gpt-5.6-sol");
        assertEquals("openai", sel.provider().id());
    }
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ProviderRegistryThinkingTest`
Expected: 编译失败或断言失败——`requestSelectionTwoArgRoutesToExactProvider`/`requestSelectionTwoArgUnknownThrows` 调用的 `requestSelection(String, String)` 尚不存在（编译期报错，属正常的 RED 状态）；即便先注掉这两个新方法调用，`requestSelectionOneArgFindsOwnerAcrossProviders` 也会因为现状总是返回 `deepseek` 而断言失败。

- [ ] **Step 3: 实现**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/ProviderRegistry.java`，把第 90-103 行：

```java
    public synchronized RequestSelection activeRequestSelection() {
        return selection(active, activeModelId);
    }

    /** Explicit subagent model remains scoped to the active provider in v1. */
    public synchronized RequestSelection requestSelection(String modelId) {
        LlmProvider provider = active;
        boolean belongsToActive = provider.models().stream().anyMatch(model -> model.id().equals(modelId));
        if (!belongsToActive) {
            // Keep the established v1 behavior: use the active provider even for a custom override.
            return selection(provider, modelId);
        }
        return selection(provider, modelId);
    }
```

替换为：

```java
    public synchronized RequestSelection activeRequestSelection() {
        return selection(active, activeModelId);
    }

    /** 跨 provider 找 modelId 的第一个持有者（宽松语义，兼容裸 modelId 的旧配置）；找不到即抛错。 */
    public synchronized RequestSelection requestSelection(String modelId) {
        ModelOwner owner = ownerOf(modelId);
        if (owner == null) {
            throw new IllegalArgumentException("未知或不可用模型: " + modelId);
        }
        return selection(owner.provider(), owner.model().id());
    }

    /** 精确 provider+model 路由：都匹配才命中，未命中即抛错。子 agent 按次覆盖模型的主入口。 */
    public synchronized RequestSelection requestSelection(String providerId, String modelId) {
        ModelOwner owner = ownerOf(providerId, modelId);
        if (owner == null) {
            throw new IllegalArgumentException("未知或不可用模型: " + providerId + ":" + modelId);
        }
        return selection(owner.provider(), owner.model().id());
    }
```

（`ownerOf(String)` 与 `ownerOf(String, String)` 两个 private 方法已存在，本步骤只是让公开方法真正使用它们。）

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ProviderRegistryThinkingTest`
Expected: 全部通过（含此前已有的 6 个测试）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/llm/ProviderRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ProviderRegistryThinkingTest.java
git commit -m "fix(code-tui): ProviderRegistry.requestSelection 真正跨 provider 路由，未知模型清楚抛错"
```

## Task 2: AgentListener + SubagentSpec + SubagentRunner — 按 spec 解析模型并报给 UI

依赖 Task 1（用到新的 `requestSelection(providerId, modelId)`）。本任务让 `SubagentRunner` 能正确解析 `spec.model` 里的 `provider:model`（不再丢前缀），新增 `SubagentSpec.withModel` 供后续路由层派生覆盖值，并让 `AgentListener.onSubagentStarted` 多带一个「这次委派请求的模型标签」参数（新增 default 重载，不破坏其它实现方）。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java:27`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentSpec.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java:223-253,625-636`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerThinkingTest.java`

- [ ] **Step 1: 在 `AgentListener` 新增 5 参 `onSubagentStarted` 默认重载**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java`，在第 27 行（`void onSubagentStarted(long turnId, String taskId, String agentName, String description);`）之后插入：

```java
    /**
     * 子 agent 开始（带本次委派实际请求的模型标签，供 UI 展示）。默认委托 4 参版本，
     * 只有需要展示模型的实现（{@code ConversationState} 面板）覆写本方法。
     *
     * @param modelLabel 本次请求的模型（如 {@code "openai:gpt-5.6-sol"}）；spec 未指定则为当前激活模型
     */
    default void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                   String modelLabel) {
        onSubagentStarted(turnId, taskId, agentName, description);
    }
```

这一步只是加一个 `default` 方法，全仓其它 `AgentListener` 实现方（`StubListener`、各测试里的匿名类等）不需要改动即可继续编译。

- [ ] **Step 2: 给 `SubagentSpec` 加 `withModel`**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentSpec.java`，把文件末尾的：

```java
public record SubagentSpec(String name,
                           String description,
                           String systemPrompt,
                           List<String> allowTools,
                           List<String> denyTools,
                           String model,
                           List<String> skills) {
}
```

替换为：

```java
public record SubagentSpec(String name,
                           String description,
                           String systemPrompt,
                           List<String> allowTools,
                           List<String> denyTools,
                           String model,
                           List<String> skills) {

    /** 派生一份指定 model 覆盖值的副本，其余字段不变；供 {@code SubagentTool} 按次覆盖用。 */
    public SubagentSpec withModel(String model) {
        return new SubagentSpec(name, description, systemPrompt, allowTools, denyTools, model, skills);
    }
}
```

- [ ] **Step 3: 追加失败测试（跨 provider 路由 + 模型标签）**

打开 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerThinkingTest.java`。在 `CapturingProvider` 类定义（第 26-63 行）之后、`spec(String model)` 辅助方法（第 65-67 行）之前，插入一个新的最小 fake provider：

```java
    /** 只关心「谁真正接到了请求」，不涉及思考配置——用于证明覆盖值真的跨 provider 路由，而不是丢前缀落到激活 provider。 */
    private static final class IdOnlyProvider implements LlmProvider {
        private final String providerId;
        private final String modelId;
        private final AtomicBoolean called;
        IdOnlyProvider(String providerId, String modelId, AtomicBoolean called) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.called = called;
        }
        @Override public String id() { return providerId; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() {
            return new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    called.set(true);
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
                @Override public ChatOptions getOptions() { return OpenAiChatOptions.builder().model(modelId).build(); }
            };
        }
        @Override public ChatOptions options(String mid) { return OpenAiChatOptions.builder().model(mid).build(); }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
        @Override public String defaultModel() { return modelId; }
    }
```

然后在文件末尾（`explicitModelUsesItsConfig` 测试方法之后，类结束的 `}` 之前）追加：

```java

    @Test
    void crossProviderModelOverrideRoutesToNamedProvider() {
        AtomicBoolean primaryCalled = new AtomicBoolean();
        AtomicBoolean secondaryCalled = new AtomicBoolean();
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new IdOnlyProvider("openai", "gpt-x", primaryCalled),
                new IdOnlyProvider("deepseek", "deepseek-y", secondaryCalled)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");

        runner.run(spec("deepseek:deepseek-y"), "hi", "desc", 1L);

        assertTrue(secondaryCalled.get(), "跨 provider 覆盖必须真正路由到 deepseek，而不是丢前缀落到激活的 openai");
        assertFalse(primaryCalled.get());
    }

    @Test
    void requestedModelLabelUsesActiveWhenSpecModelBlank() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        AtomicReference<String> label = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        var listener = new StubListener() {
            @Override
            public void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                          String modelLabel) {
                label.set(modelLabel);
            }
        };
        SubagentRunner runner = new SubagentRunner(registry, List.of(), listener, "");

        runner.run(spec(null), "hi", "desc", 1L);

        assertEquals("openai:gpt-5.6-sol", label.get());
    }

    @Test
    void requestedModelLabelShowsRawOverrideBeforeResolution() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        AtomicReference<String> label = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        var listener = new StubListener() {
            @Override
            public void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                          String modelLabel) {
                label.set(modelLabel);
            }
        };
        SubagentRunner runner = new SubagentRunner(registry, List.of(), listener, "");

        runner.run(spec("gpt-5.6-terra"), "hi", "desc", 1L);

        assertEquals("gpt-5.6-terra", label.get());
    }

    /**
     * 回归本任务要修的具体 bug：{@code gpt-5.6-sol} 是真实模型，但挂在不存在的 provider 前缀下。
     * 旧实现会丢弃 "no-such-provider:" 前缀、按裸 modelId 命中唯一配置的 openai，静默"成功"——
     * 这正是设计文档背景里说的那个缺陷。修复后必须真按 providerId 校验，找不到就清楚报错。
     */
    @Test
    void unknownProviderInOverrideSurfacesClearFailureMessage() {
        AtomicReference<ChatOptions> captured = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(new CapturingProvider(captured)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runner.run(spec("no-such-provider:gpt-5.6-sol"), "hi", "desc", 1L));

        assertTrue(ex.getMessage().contains("未知或不可用模型"), ex.getMessage());
    }
```

在文件顶部的 import 块里补上（原文件已 import `AtomicReference`）：

```java
import java.util.concurrent.atomic.AtomicBoolean;
```

以及静态断言：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

（原文件已有 `import static org.junit.jupiter.api.Assertions.assertEquals;`，这三行是新增。）

- [ ] **Step 4: 跑测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentRunnerThinkingTest`
Expected: 编译失败或断言失败——`onSubagentStarted(long, String, String, String, String)` 覆写目前找不到匹配的父类型方法签名（Step 1 已加所以能编译，但 `SubagentRunner.run` 还没调用它，标签测试会因为 `label.get()` 恒为 `null` 而断言失败）；`crossProviderModelOverrideRoutesToNamedProvider` 会因为现有 `resolveSelection` 丢弃 provider 前缀、把 `"deepseek:deepseek-y"` 错误地整串当 modelId 传给 `registry.requestSelection("deepseek:deepseek-y")`（Task 1 修复后会抛 `IllegalArgumentException`，因为不存在这个 modelId）而失败；`unknownProviderInOverrideSurfacesClearFailureMessage` 会因为旧实现丢前缀后按裸 `gpt-5.6-sol` 命中 openai、静默"成功"而不抛异常，`assertThrows` 断言失败。

- [ ] **Step 5: 实现——`SubagentRunner.resolveSelection` 修正 + 新增 `requestedModelLabel` + `run()` 改调 5 参**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java`。

第一处，第 225 行左右，把：

```java
        listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description);
```

替换为：

```java
        listener.onSubagentStarted(parentTurnId, taskId, spec.name(), description, requestedModelLabel(spec));
```

第二处，第 625-635 行左右的 `resolveSelection` 方法：

```java
    /** model 空→激活 selection；否则在当前 v1 provider 路由下解析显式模型 selection。 */
    private ProviderRegistry.RequestSelection resolveSelection(SubagentSpec spec) {
        if (spec.model() == null || spec.model().isBlank()) {
            return registry.activeRequestSelection();
        }
        // provider:model 的跨家路由留待 v2（spec §12）；v1 先在激活 provider 上按模型名覆盖。
        String modelId = spec.model().contains(":")
                ? spec.model().substring(spec.model().indexOf(':') + 1)
                : spec.model();
        return registry.requestSelection(modelId);
    }
```

替换为：

```java
    /** model 空→激活 selection；否则解析显式 model（支持 provider:model 跨家路由，裸 modelId 兼容旧配置）。 */
    private ProviderRegistry.RequestSelection resolveSelection(SubagentSpec spec) {
        if (spec.model() == null || spec.model().isBlank()) {
            return registry.activeRequestSelection();
        }
        String m = spec.model();
        int colon = m.indexOf(':');
        return colon < 0
                ? registry.requestSelection(m)
                : registry.requestSelection(m.substring(0, colon), m.substring(colon + 1));
    }

    /** 派发前（可能解析失败）就能确定的展示标签：不解析、不抛异常，仅用于 UI 即时反馈。 */
    private String requestedModelLabel(SubagentSpec spec) {
        return (spec.model() == null || spec.model().isBlank())
                ? registry.active().id() + ":" + registry.activeModelId()
                : spec.model();
    }
```

- [ ] **Step 6: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentRunnerThinkingTest`
Expected: 全部通过（含此前已有的 2 个测试）。

- [ ] **Step 7: 跑受影响的既有测试，确认零回归**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentRunnerTest,SubagentRunnerOkTest,SubagentRunnerParallelTest,SubagentRunnerNotificationTest,SubagentRunnerBackgroundTest,SubagentRunnerMcpToolsTest`
Expected: 全部通过——这些文件都只用 4 参 `onSubagentStarted`，`AgentListener` 的 default 重载保证它们无需改动。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/AgentListener.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentSpec.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerThinkingTest.java
git commit -m "feat(code-tui): SubagentRunner 正确解析 provider:model 跨家覆盖，onSubagentStarted 带模型标签"
```

## Task 3: SubagentTool — 按次 `model` 覆盖的路由机制

依赖 Task 2（用到 `SubagentSpec.withModel`）。`SubagentCall` 新增可选 `model` 字段；`function`/`batchFunction` 在派给 dispatcher 前应用覆盖值。`ParallelCall.tasks()` 本身是 `List<SubagentCall>`，`ParallelTasks` 自动获得逐条独立覆盖的能力，不用单独加字段。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolBackgroundTest.java`

- [ ] **Step 1: 给 `SubagentCall` 加 `model` 字段**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java`。把第 74-91 行的 `SubagentCall` record：

```java
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type,
            @ToolParam(required = false, description =
                    "Background mode (default false/omitted = foreground). "
                    + "Set true ONLY when: the task is independent (you do not need its result to "
                    + "decide your next action), AND the task is read-only or uses only pre-approved "
                    + "tools (background tasks cannot prompt for permission — approval-gated calls are "
                    + "silently denied). Returns a task id immediately; retrieve the result later with "
                    + "TaskOutput. Do NOT set true just because the task is slow — if you need the "
                    + "result eventually in the same turn, foreground is always cleaner.") Boolean run_in_background) {

        /** null-safe：模型省略该字段时 Jackson 给 null，默认前台。 */
        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }
    }
```

替换为：

```java
    public record SubagentCall(
            @ToolParam(description = "3-5 word summary of what the subagent will do") String description,
            @ToolParam(description = "Detailed, self-contained task prompt for the subagent") String prompt,
            @ToolParam(description = "Which subagent type to use") String subagent_type,
            @ToolParam(required = false, description =
                    "Optional model override for this dispatch, as 'provider:modelId' (see the model "
                    + "roster in this tool's description). Omit to use this subagent type's own default "
                    + "model, or the currently active model if it has none.") String model,
            @ToolParam(required = false, description =
                    "Background mode (default false/omitted = foreground). "
                    + "Set true ONLY when: the task is independent (you do not need its result to "
                    + "decide your next action), AND the task is read-only or uses only pre-approved "
                    + "tools (background tasks cannot prompt for permission — approval-gated calls are "
                    + "silently denied). Returns a task id immediately; retrieve the result later with "
                    + "TaskOutput. Do NOT set true just because the task is slow — if you need the "
                    + "result eventually in the same turn, foreground is always cleaner.") Boolean run_in_background) {

        /** null-safe：模型省略该字段时 Jackson 给 null，默认前台。 */
        public boolean background() {
            return Boolean.TRUE.equals(run_in_background);
        }

        /** null-safe：空白视为「不覆盖」，与 {@link #background()} 同一纪律。 */
        public String modelOverride() {
            return (model == null || model.isBlank()) ? null : model.trim();
        }
    }
```

- [ ] **Step 2: 应用覆盖值——`function`**

找到 `function` 方法（Step 1 已经在文件前面插入了几行，具体行号会往后挪几行，按下面的完整代码块搜索定位）：

```java
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

替换为：

```java
    static Function<SubagentCall, String> function(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                                   BackgroundDispatcher background) {
        return callArgs -> {
            SubagentSpec spec = specs.get(callArgs.subagent_type());
            if (spec == null) {
                throw new RuntimeException("No subagent found with type: " + callArgs.subagent_type()
                        + ". Available: " + String.join(", ", specs.keySet()));
            }
            String override = callArgs.modelOverride();
            SubagentSpec effectiveSpec = override == null ? spec : spec.withModel(override);
            if (callArgs.background()) {
                return background == null
                        ? NO_BACKGROUND
                        : background.dispatch(effectiveSpec, callArgs.prompt(), callArgs.description());
            }
            return dispatcher.dispatch(effectiveSpec, callArgs.prompt(), callArgs.description(), -1L);
        };
    }
```

- [ ] **Step 3: 应用覆盖值——`batchFunction`**

在同一文件里，找到 `batchFunction` 方法内的 `anyBackground` 分支（约第 206-224 行），把：

```java
                } else {
                    body = background.dispatch(spec, t.prompt(), t.description());
                }
```

替换为：

```java
                } else {
                    SubagentSpec effectiveSpec = t.modelOverride() == null ? spec : spec.withModel(t.modelOverride());
                    body = background.dispatch(effectiveSpec, t.prompt(), t.description());
                }
```

再找到同一方法内的前台并发分支（约第 229-240 行），把：

```java
                } else {
                    dispatchable.add(new SubagentRunner.Dispatch(spec, t.prompt(), t.description()));
                    dispatchIndex.add(i);
                }
```

替换为：

```java
                } else {
                    SubagentSpec effectiveSpec = t.modelOverride() == null ? spec : spec.withModel(t.modelOverride());
                    dispatchable.add(new SubagentRunner.Dispatch(effectiveSpec, t.prompt(), t.description()));
                    dispatchIndex.add(i);
                }
```

- [ ] **Step 4: 修掉因新增字段而挂掉的既有测试（编译期红）**

`SubagentCall` 从 4 个分量变成 5 个，所有直接 `new SubagentTool.SubagentCall(...)` 的位置都要补一个位置（放在 `subagent_type` 之后、`run_in_background` 之前）。

在 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolTest.java` 里：

第 17 行，把：
```java
        return new SubagentTool.SubagentCall("do things", "the prompt", type, null);
```
替换为：
```java
        return new SubagentTool.SubagentCall("do things", "the prompt", type, null, null);
```

第 70-71 行，把：
```java
                new SubagentTool.SubagentCall("do a", "pa", "explore", null),
                new SubagentTool.SubagentCall("do b", "pb", "no-such", null)), null);
```
替换为：
```java
                new SubagentTool.SubagentCall("do a", "pa", "explore", null, null),
                new SubagentTool.SubagentCall("do b", "pb", "no-such", null, null)), null);
```

第 88-90 行，把：
```java
                new SubagentTool.SubagentCall("a", "pa", "explore", null),
                new SubagentTool.SubagentCall("b", "pb", "no-such", null),
                new SubagentTool.SubagentCall("c", "pc", "explore", null)), null);
```
替换为：
```java
                new SubagentTool.SubagentCall("a", "pa", "explore", null, null),
                new SubagentTool.SubagentCall("b", "pb", "no-such", null, null),
                new SubagentTool.SubagentCall("c", "pc", "explore", null, null)), null);
```

在 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolBackgroundTest.java` 里：

第 24 行，把：
```java
        return new SubagentTool.SubagentCall("do things", "the prompt", type, bg);
```
替换为：
```java
        return new SubagentTool.SubagentCall("do things", "the prompt", type, null, bg);
```

第 56 行，把：
```java
        assertEquals("fg", fn.apply(new SubagentTool.SubagentCall("d", "p", "explore", null)));
```
替换为：
```java
        assertEquals("fg", fn.apply(new SubagentTool.SubagentCall("d", "p", "explore", null, null)));
```

- [ ] **Step 5: 追加新失败测试（覆盖值真的生效）**

在 `SubagentToolTest.java` 顶部 import 块补两行（原文件已有 `java.util.List`/`java.util.Map`）：

```java
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
```

在文件末尾（`parallelInterleavedKnownUnknownPreservesInputOrder` 方法之后，类结束的 `}` 之前）追加：

```java

    @Test
    void modelOverrideAppliedToDispatchedSpec() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> {
            capturedModel.set(spec.model());
            return "ran";
        };
        var fn = SubagentTool.function(specs, dispatch);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", "openai:gpt-5.6-sol", null));

        assertEquals("openai:gpt-5.6-sol", capturedModel.get());
    }

    @Test
    void noModelOverrideLeavesSpecModelUnchanged() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), "static-model", List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> {
            capturedModel.set(spec.model());
            return "ran";
        };
        var fn = SubagentTool.function(specs, dispatch);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", null, null));

        assertEquals("static-model", capturedModel.get(), "未传 model 时沿用 spec 静态配置");
    }

    @Test
    void modelOverrideAppliedInBackgroundDispatch() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        AtomicReference<String> capturedModel = new AtomicReference<>();
        SubagentTool.Dispatcher dispatch = (spec, prompt, desc, turn) -> "fg";
        SubagentTool.BackgroundDispatcher bg = (spec, prompt, desc) -> {
            capturedModel.set(spec.model());
            return "bg";
        };
        var fn = SubagentTool.function(specs, dispatch, bg);

        fn.apply(new SubagentTool.SubagentCall("do a", "pa", "explore", "deepseek:deepseek-v4-pro", true));

        assertEquals("deepseek:deepseek-v4-pro", capturedModel.get());
    }

    @Test
    void parallelTasksApplyIndependentModelOverridesPerSubtask() {
        Map<String, SubagentSpec> specs = Map.of(
                "explore", new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()));
        List<String> capturedModels = new ArrayList<>();
        SubagentTool.BatchDispatcher batch = (dispatches, turn) -> {
            for (var d : dispatches) capturedModels.add(d.spec().model());
            return dispatches.stream().map(d -> "ran " + d.spec().model()).toList();
        };
        var fn = SubagentTool.batchFunction(specs, batch);
        SubagentTool.ParallelCall pc = new SubagentTool.ParallelCall(List.of(
                new SubagentTool.SubagentCall("a", "p", "explore", "openai:gpt-5.6-sol", null),
                new SubagentTool.SubagentCall("b", "p", "explore", "deepseek:deepseek-v4-pro", null),
                new SubagentTool.SubagentCall("c", "p", "explore", null, null)), null);

        fn.apply(pc);

        List<String> expected = new ArrayList<>(List.of("openai:gpt-5.6-sol", "deepseek:deepseek-v4-pro"));
        expected.add(null);
        assertEquals(expected, capturedModels, "同一批里每条子任务各自独立的 model 覆盖，互不影响");
    }
```

- [ ] **Step 6: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentToolTest,SubagentToolBackgroundTest`
Expected: 全部通过。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolBackgroundTest.java
git commit -m "feat(code-tui): Task/ParallelTasks 支持按次 model 覆盖（provider:modelId）"
```

## Task 4: SubagentTool — 工具描述里列出可选模型 roster

依赖 Task 3。`create`/`createParallel` 新增能接收 `List<ProviderModel>` 的重载，格式化成一份 roster 拼进工具描述；已有的 2 参/3 参重载保持签名不变（内部改为委托新重载、roster 传空），零件测试不用改。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolTest.java`

- [ ] **Step 1: 追加失败测试**

在 `SubagentToolTest.java` 顶部 import 块补一行：

```java
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
```

在文件末尾（Task 3 新增的 `parallelTasksApplyIndependentModelOverridesPerSubtask` 之后，类结束的 `}` 之前）追加：

```java

    @Test
    void schemaExposesModelParam() {
        ToolCallback tc = SubagentTool.create(Map.of(), (spec, prompt, desc, turn) -> "unused");
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"model\""), schema);
    }

    @Test
    void descriptionListsAvailableModels() {
        List<ProviderModel> models = List.of(
                new ProviderModel("openai", "gpt-5.6-sol", "Sol", "d"),
                new ProviderModel("deepseek", "deepseek-v4-pro", "Pro", "d"));
        ToolCallback tc = SubagentTool.create(Map.of(), models, (spec, prompt, desc, turn) -> "unused", null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("openai:gpt-5.6-sol"), desc);
        assertTrue(desc.contains("deepseek:deepseek-v4-pro"), desc);
    }

    @Test
    void parallelDescriptionListsAvailableModels() {
        List<ProviderModel> models = List.of(new ProviderModel("openai", "gpt-5.6-sol", "Sol", "d"));
        ToolCallback tc = SubagentTool.createParallel(Map.of(), models, (dispatches, turn) -> List.of(), null);
        String desc = tc.getToolDefinition().description();
        assertTrue(desc.contains("openai:gpt-5.6-sol"), desc);
    }
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentToolTest`
Expected: 编译失败——`SubagentTool.create(Map, List, Dispatcher, BackgroundDispatcher)` 与 `createParallel` 的同形重载还不存在。

- [ ] **Step 3: 实现——新增模型 roster 格式化 + 4 参重载**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java`，在顶部 import 块补一行：

```java
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
```

把 `DESCRIPTION_TEMPLATE` 常量（第 20-47 行）：

```java
    private static final String DESCRIPTION_TEMPLATE = """
            Launch a specialized subagent to handle a complex, multi-step subtask autonomously.

            Each subagent has its own context, system prompt, and restricted tool set. Use this to
            delegate focused work (exploring the codebase, designing a plan, running commands) so the
            main conversation stays clean. The subagent returns a single final message as the result.

            Available subagent types:
            %s

            Usage:
            - Always give a short description (3-5 words) of what the subagent will do.
            - Give a detailed, self-contained prompt: the subagent does not see the main conversation.
            - Tell the subagent whether you want it to just research/read or to actually make changes.
            - Choose subagent_type from the list above.

            Foreground vs background:
            - DEFAULT to foreground (omit run_in_background or set false). Most coding workflows are
              sequential: explore → plan → implement → test. Each step needs the previous result.
            - Use run_in_background=true ONLY when ALL of these hold:
                1. The task is truly independent — its output does not determine your next action.
                2. You will retrieve the result later via TaskOutput once you need it.
                3. The task is read-only or uses only pre-approved tools (background tasks cannot
                   prompt for permission; calls needing approval are silently denied).
            - Do NOT use background merely because a task takes a long time. A slow foreground task
              that you need is always better than a background task whose result you have to wait for
              anyway.
            """;
```

替换为：

```java
    private static final String DESCRIPTION_TEMPLATE = """
            Launch a specialized subagent to handle a complex, multi-step subtask autonomously.

            Each subagent has its own context, system prompt, and restricted tool set. Use this to
            delegate focused work (exploring the codebase, designing a plan, running commands) so the
            main conversation stays clean. The subagent returns a single final message as the result.

            Available subagent types:
            %s

            Usage:
            - Always give a short description (3-5 words) of what the subagent will do.
            - Give a detailed, self-contained prompt: the subagent does not see the main conversation.
            - Tell the subagent whether you want it to just research/read or to actually make changes.
            - Choose subagent_type from the list above.

            Model override:
            - By default the subagent runs on its own configured model, or the currently active model
              if it has none configured.
            - Optionally set `model` to "provider:modelId" to run this specific dispatch on a
              different model. Available models:
            %s
            - To compare how different models handle the same task, dispatch it via ParallelTasks with
              one subtask per model (same prompt, different `model`), then compare the results.

            Foreground vs background:
            - DEFAULT to foreground (omit run_in_background or set false). Most coding workflows are
              sequential: explore → plan → implement → test. Each step needs the previous result.
            - Use run_in_background=true ONLY when ALL of these hold:
                1. The task is truly independent — its output does not determine your next action.
                2. You will retrieve the result later via TaskOutput once you need it.
                3. The task is read-only or uses only pre-approved tools (background tasks cannot
                   prompt for permission; calls needing approval are silently denied).
            - Do NOT use background merely because a task takes a long time. A slow foreground task
              that you need is always better than a background task whose result you have to wait for
              anyway.
            """;
```

把 `PARALLEL_DESCRIPTION_TEMPLATE` 常量（第 49-62 行）：

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

替换为：

```java
    private static final String PARALLEL_DESCRIPTION_TEMPLATE = """
            Launch MULTIPLE subagents CONCURRENTLY to handle several INDEPENDENT subtasks at once.

            Use this ONLY when you have 2+ subtasks that are mutually independent and share no state
            (e.g. investigating unrelated failures, exploring separate subsystems). If subtasks depend
            on each other or need shared context, use the single Task tool instead.

            Each subtask has the same shape as Task: description, prompt, subagent_type, and an
            optional `model` override ("provider:modelId") to run that particular subtask on a
            different model than the others. All subtasks run in parallel; results are returned
            together, one block per subtask (in input order), each marked success/failure
            independently — one failing subtask does not abort the others.

            To compare how different models handle the same task, give every subtask the same prompt
            and subagent_type but a different `model`.

            Available subagent types:
            %s

            Available models (optional per-subtask `model` override, format "provider:modelId"):
            %s
            """;
```

把 `create` 的两个既有重载（Task 3 已在这个文件里插入了几行，具体行号会往后挪，按下面的完整代码块搜索定位，不要死认行号）：

```java
    /** 无后台能力的装配（回显桩 / 测试桩）：请求后台时明确告知不可用，<b>不</b>静默跑成前台。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return create(specs, dispatcher, null);
    }

    /** 构建名为 "Task" 的 ToolCallback。turnId 从 ThreadLocal 取（同主流工具，装配时落实）。 */
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
```

替换为：

```java
    /** 无后台能力的装配（回显桩 / 测试桩）：请求后台时明确告知不可用，<b>不</b>静默跑成前台。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher) {
        return create(specs, dispatcher, null);
    }

    /** 沿用旧 3 参签名：模型 roster 传空（回显桩 / 测试桩，不关心可选模型清单）。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, Dispatcher dispatcher,
                                      BackgroundDispatcher background) {
        return create(specs, List.of(), dispatcher, background);
    }

    /** 生产装配用：带模型 roster，写进 Task 描述供主 agent 选择按次覆盖的 model。 */
    public static ToolCallback create(Map<String, SubagentSpec> specs, List<ProviderModel> models,
                                      Dispatcher dispatcher, BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("Task", function(specs, dispatcher, background))
                .description(DESCRIPTION_TEMPLATE.formatted(roster, modelRoster(models)))
                .inputType(SubagentCall.class)
                .build();
    }
```

把 `createParallel` 的两个既有重载（同样按完整代码块搜索定位，行号已因 Task 3 的插入而往后挪）：

```java
    /** 无后台能力的批量装配：同 {@link #create(Map, Dispatcher)}，后台请求逐条明确拒绝。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return createParallel(specs, dispatcher, null);
    }

    /** 构建名为 "ParallelTasks" 的批量 ToolCallback。parentTurnId 由 BatchDispatcher 实现内部经 ThreadLocal 取，这里占位 -1L。 */
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
```

替换为：

```java
    /** 无后台能力的批量装配：同 {@link #create(Map, Dispatcher)}，后台请求逐条明确拒绝。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher) {
        return createParallel(specs, dispatcher, null);
    }

    /** 沿用旧 3 参签名：模型 roster 传空（回显桩 / 测试桩，不关心可选模型清单）。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, BatchDispatcher dispatcher,
                                              BackgroundDispatcher background) {
        return createParallel(specs, List.of(), dispatcher, background);
    }

    /** 生产装配用：带模型 roster，写进 ParallelTasks 描述供主 agent 为每个子任务各自挑选 model。 */
    public static ToolCallback createParallel(Map<String, SubagentSpec> specs, List<ProviderModel> models,
                                              BatchDispatcher dispatcher, BackgroundDispatcher background) {
        String roster = specs.values().stream()
                .map(s -> "- " + s.name() + ": " + s.description())
                .collect(Collectors.joining("\n"));
        return FunctionToolCallback.builder("ParallelTasks", batchFunction(specs, dispatcher, background))
                .description(PARALLEL_DESCRIPTION_TEMPLATE.formatted(roster, modelRoster(models)))
                .inputType(ParallelCall.class)
                .build();
    }

    /** 模型 roster 的展示文本：一行一个 "provider:modelId — label"；空清单给一句占位说明。 */
    private static String modelRoster(List<ProviderModel> models) {
        if (models.isEmpty()) {
            return "(no models configured)";
        }
        return models.stream()
                .map(m -> "- " + m.providerId() + ":" + m.modelId() + " — " + m.label())
                .collect(Collectors.joining("\n"));
    }
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=SubagentToolTest,SubagentToolBackgroundTest`
Expected: 全部通过。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentTool.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentToolTest.java
git commit -m "feat(code-tui): Task/ParallelTasks 描述里列出可选模型 roster"
```

## Task 5: AgentTools 装配层接上 `registry.allModels()`

依赖 Task 4。零件测试（Task 3/4）都是直接调 `SubagentTool.create`/`function` 喂假参数，证明不了生产装配真的把 `registry.allModels()` 传了进去——那根线漏接的话，零件单测照样全绿，只有从 `AgentTools.build` 真实产物读回来的断言能抓到（同仓已有的 `AgentTools*WiringTest` 系列就是为了防这类漏线）。先写一个会失败的装配级测试，再接线。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:496-514`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsModelOverrideWiringTest.java`

- [ ] **Step 1: 新建失败的装配级测试**

创建 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsModelOverrideWiringTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task / ParallelTasks 的模型 roster 装配契约：真实 {@code registry.allModels()} 有没有接到
 * 工具描述里、入参 schema 有没有暴露 {@code model} 字段。
 *
 * <p><b>为什么必须打在真实装配产物上</b>：{@code SubagentTool.create}/{@code createParallel} 的
 * roster 格式化零件单测直接喂假 {@code List<ProviderModel>}，证明不了 {@code AgentTools.build}
 * 真的把 {@code registry.allModels()} 传了进去——那根线漏接（比如仍调旧的 3 参重载）不会编译错，
 * 零件单测照样全绿，只有从这里读回真实产物的断言能抓到。见 {@link AgentToolsBackgroundWiringTest}
 * 类注释里同一条纪律。
 */
class AgentToolsModelOverrideWiringTest {

    @Test
    @DisplayName("Task / ParallelTasks 描述里带真实模型 roster（provider:modelId）")
    void taskDescriptionExposesRealModelRoster(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        String taskDesc = runtime.get("Task").getToolDefinition().description();
        assertTrue(taskDesc.contains("deepseek:deepseek-v4-pro"),
                "Task 描述应含真实模型 roster，实际=" + taskDesc);

        String parallelDesc = runtime.get("ParallelTasks").getToolDefinition().description();
        assertTrue(parallelDesc.contains("deepseek:deepseek-v4-pro"),
                "ParallelTasks 描述应含真实模型 roster，实际=" + parallelDesc);
    }

    @Test
    @DisplayName("Task 入参 schema 暴露 model 覆盖字段")
    void taskSchemaExposesModelField(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);

        String schema = runtime.get("Task").getToolDefinition().inputSchema();
        assertTrue(schema.contains("\"model\""), "Task 入参 schema 应含 model 字段，实际=" + schema);
    }
}
```

（`RuntimeToolSet.byRegisteredName` 内部固定用一个假 key 的 `DeepSeekProvider` 装配，`deepseek-v4-pro` 是它的真实内置模型 id，故断言直接用这个字符串。）

- [ ] **Step 2: 跑测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=AgentToolsModelOverrideWiringTest`
Expected: `taskDescriptionExposesRealModelRoster` 失败——`AgentTools.java` 目前调的还是 3 参 `create`/`createParallel`（内部 roster 传空），Task 描述里没有 `deepseek:deepseek-v4-pro`。`taskSchemaExposesModelField` 已经通过（Task 3 已经把 `model` 字段加进了 schema，与本任务无关，属于提前变绿的部分——继续即可）。

- [ ] **Step 3: 接线**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`。第 496-501 行左右，把：

```java
        ToolCallback taskTool = SubagentTool.create(subagentSpecs,
                (spec, prompt, desc, turnIgnored) ->
                        // 真实 parentTurnId 从 ThreadLocal 取（Task 工具被 ToolEventCallback 装饰，call 时已压入）
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()),
                // 后台派发不带 turnId：后台任务没有归属回合（见 SubagentRunner.runBackgroundBody 传 -1）。
                subagentRunner::runInBackground);
```

替换为：

```java
        ToolCallback taskTool = SubagentTool.create(subagentSpecs, registry.allModels(),
                (spec, prompt, desc, turnIgnored) ->
                        // 真实 parentTurnId 从 ThreadLocal 取（Task 工具被 ToolEventCallback 装饰，call 时已压入）
                        subagentRunner.run(spec, prompt, desc, ToolEventCallback.currentTurnId()),
                // 后台派发不带 turnId：后台任务没有归属回合（见 SubagentRunner.runBackgroundBody 传 -1）。
                subagentRunner::runInBackground);
```

第 509-512 行左右，把：

```java
        ToolCallback parallelTool = SubagentTool.createParallel(subagentSpecs,
                (dispatches, turnIgnored) ->
                        subagentRunner.runAll(dispatches, ToolEventCallback.currentTurnId()),
                subagentRunner::runInBackground);
```

替换为：

```java
        ToolCallback parallelTool = SubagentTool.createParallel(subagentSpecs, registry.allModels(),
                (dispatches, turnIgnored) ->
                        subagentRunner.runAll(dispatches, ToolEventCallback.currentTurnId()),
                subagentRunner::runInBackground);
```

（`registry` 是 `AgentTools.build` 方法本身的参数，此处已在作用域内，无需新增 import。）

- [ ] **Step 4: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=AgentToolsModelOverrideWiringTest,AgentRuntimeTest,AgentToolsBackgroundWiringTest`
Expected: 全部通过——`AgentRuntimeTest.taskToolAssembles` 与 `AgentToolsBackgroundWiringTest` 用的都是不受影响的重载/断言，验证零回归。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsModelOverrideWiringTest.java
git commit -m "feat(code-tui): AgentTools 装配把 registry.allModels() 接进 Task/ParallelTasks 描述"
```

## Task 6: UI 展示——scrollback 与任务面板显示实际用的模型

依赖 Task 2（`AgentListener` 的 5 参 `onSubagentStarted`）。`ConversationState` 覆写新方法承担展示逻辑，`SubtaskView`/`Subtask` 新增 `model` 字段随面板状态一起存，`CodeTuiView.subtaskRowText` 在行尾追加模型标签。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java:97,100-109,722-735,924-928`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:3763-3773`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/SubtaskPanelTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateTest.java`

- [ ] **Step 1: 修既有测试的编译期红——`SubtaskView` 新增分量**

`SubtaskView` 从 4 分量变 5 分量后，`SubtaskPanelTest.java` 里唯一直接构造它的 `v()` 辅助方法要跟着改。打开 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/SubtaskPanelTest.java`，把第 16-18 行：

```java
    private static SubtaskView v(String agent, String desc, SubtaskStatus st, String tool) {
        return new SubtaskView(agent, desc, st, tool);
    }
```

替换为：

```java
    private static SubtaskView v(String agent, String desc, SubtaskStatus st, String tool) {
        return new SubtaskView(agent, desc, st, tool, "");
    }
```

（`model=""` 对既有全部用例是零行为差异——"" 不会给行尾添任何东西，与既有 `!r.contains("·")` 一类断言完全兼容。）

- [ ] **Step 2: 追加失败测试**

在同一文件末尾（`renderTree_withSubtasks_doesNotThrow` 方法之后，类结束的 `}` 之前）追加：

```java

    @Test
    void row_appendsModelWhenPresent() {
        String r = CodeTuiView.subtaskRowText(
                new SubtaskView("explore", "d", SubtaskStatus.RUNNING, "", "deepseek:deepseek-v4-pro"));
        assertTrue(r.contains("· deepseek:deepseek-v4-pro"), "附带模型标签，实际=" + r);
    }

    @Test
    void row_modelBeforeCurrentTool_whenBothPresent() {
        String r = CodeTuiView.subtaskRowText(
                new SubtaskView("explore", "d", SubtaskStatus.RUNNING, "Grep", "openai:gpt-5.6-sol"));
        int modelIdx = r.indexOf("openai:gpt-5.6-sol");
        int toolIdx = r.indexOf("Grep");
        assertTrue(modelIdx >= 0 && toolIdx > modelIdx, "模型标签在前、当前工具在后，实际=" + r);
    }
```

在 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateTest.java` 里，文件末尾（`subagentEvents_stillCoexistWithScrollbackLines` 方法之后，类结束的 `}` 之前）追加：

```java

    @Test
    void taskPanel_startedWithModel_showsModelInScrollbackAndPanel() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        s.onSubagentStarted(1L, "t1", "explore", "审查文档", "deepseek:deepseek-v4-pro");
        List<ConversationState.OutputLine> out = s.drainPending();
        assertTrue(out.stream().anyMatch(o -> o.kind() == ConversationState.OutputLine.Kind.SUBAGENT_START
                && o.text().contains("deepseek:deepseek-v4-pro")));
        assertEquals("deepseek:deepseek-v4-pro", s.subtaskSnapshot().get(0).model());
    }
```

- [ ] **Step 3: 跑测试，确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=SubtaskPanelTest,ConversationStateTest`
Expected: 编译失败——`SubtaskView` 还没有 5 参构造函数，`.model()` 访问器也不存在。

- [ ] **Step 4: 实现——`ConversationState`**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`。

第一处，第 97 行，把：

```java
    public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool) {}
```

替换为：

```java
    public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool,
                              String model) {}
```

第二处，第 100-109 行，把：

```java
    private static final class Subtask {
        final String taskId;
        final String agentName;
        final String description;
        SubtaskStatus status = SubtaskStatus.RUNNING;
        String currentTool = "";
        Subtask(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }
```

替换为：

```java
    private static final class Subtask {
        final String taskId;
        final String agentName;
        final String description;
        final String model;
        SubtaskStatus status = SubtaskStatus.RUNNING;
        String currentTool = "";
        Subtask(String taskId, String agentName, String description, String model) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
            this.model = model;
        }
    }
```

第三处，第 722-735 行，把：

```java
    @Override
    public void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        Change change = null;
        synchronized (this) {
            if (turnId != acceptingTurnId) return;    // 迟到过滤，与其它事件一致
            flushStreaming();                          // 把在建助手残行定稿，子 agent 块另起
            String d = summarize(description);         // 折叠空白/换行，守住「一 OutputLine=一物理行」不变量
            pending.add(new OutputLine("▸ Task(" + agentName + ")" + (d.isEmpty() ? "" : " " + d),
                    OutputLine.Kind.SUBAGENT_START));
            subtasks.add(new Subtask(taskId, agentName, d));   // 任务面板追加一条运行中子 agent（d 已 summarize=一物理行）
            change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
        }
        publish(change);
    }
```

替换为：

```java
    @Override
    public void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        onSubagentStarted(turnId, taskId, agentName, description, "");
    }

    @Override
    public void onSubagentStarted(long turnId, String taskId, String agentName, String description,
                                  String modelLabel) {
        Change change = null;
        synchronized (this) {
            if (turnId != acceptingTurnId) return;    // 迟到过滤，与其它事件一致
            flushStreaming();                          // 把在建助手残行定稿，子 agent 块另起
            String d = summarize(description);         // 折叠空白/换行，守住「一 OutputLine=一物理行」不变量
            String m = modelLabel == null ? "" : modelLabel.trim();
            String tag = m.isEmpty() ? "" : "  · " + m;
            pending.add(new OutputLine("▸ Task(" + agentName + ")" + (d.isEmpty() ? "" : " " + d) + tag,
                    OutputLine.Kind.SUBAGENT_START));
            subtasks.add(new Subtask(taskId, agentName, d, m));   // 任务面板追加一条运行中子 agent（d 已 summarize=一物理行）
            change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
        }
        publish(change);
    }
```

第四处，第 924-928 行左右，把：

```java
    public synchronized List<SubtaskView> subtaskSnapshot() {
        List<SubtaskView> out = new ArrayList<>(subtasks.size());
        for (Subtask s : subtasks) out.add(new SubtaskView(s.agentName, s.description, s.status, s.currentTool));
        return out;
    }
```

替换为：

```java
    public synchronized List<SubtaskView> subtaskSnapshot() {
        List<SubtaskView> out = new ArrayList<>(subtasks.size());
        for (Subtask s : subtasks) {
            out.add(new SubtaskView(s.agentName, s.description, s.status, s.currentTool, s.model));
        }
        return out;
    }
```

- [ ] **Step 5: 实现——`CodeTuiView.subtaskRowText`**

打开 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`，把第 3762-3773 行：

```java
    /** 一条子任务的行文本："  <图标> <agent>  <描述>[ · <当前工具>]"（运行态且有当前工具才附尾巴）。 */
    static String subtaskRowText(ConversationState.SubtaskView s) {
        String icon = switch (s.status()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            case RUNNING -> "▶";
        };
        String tail = (s.status() == ConversationState.SubtaskStatus.RUNNING
                && s.currentTool() != null && !s.currentTool().isEmpty())
                ? " · " + s.currentTool() : "";
        return "  " + icon + " " + s.agentName() + "  " + s.description() + tail;
    }
```

替换为：

```java
    /** 一条子任务的行文本："  <图标> <agent>  <描述>[ · <模型>][ · <当前工具>]"（模型有值就附，
     * 当前工具只在运行态且有值才附；顺序固定：模型在前、当前工具在后）。 */
    static String subtaskRowText(ConversationState.SubtaskView s) {
        String icon = switch (s.status()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            case RUNNING -> "▶";
        };
        StringBuilder tail = new StringBuilder();
        if (s.model() != null && !s.model().isEmpty()) {
            tail.append(" · ").append(s.model());
        }
        if (s.status() == ConversationState.SubtaskStatus.RUNNING
                && s.currentTool() != null && !s.currentTool().isEmpty()) {
            tail.append(" · ").append(s.currentTool());
        }
        return "  " + icon + " " + s.agentName() + "  " + s.description() + tail;
    }
```

- [ ] **Step 6: 跑测试，确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=SubtaskPanelTest,ConversationStateTest,CodeTuiViewEventWiringTest`
Expected: 全部通过。

- [ ] **Step 7: 跑其余用到 `onSubagentStarted`/`SubtaskView` 的既有 UI 测试，确认零回归**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStateBackgroundTest,ConversationStateNotificationTest`
Expected: 全部通过——这两个文件都只用 4 参 `onSubagentStarted`，靠 `AgentListener` 的 default 委托保持不变；完整的 UI 包回归由 Task 7 的模块全量测试兜底。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/SubtaskPanelTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateTest.java
git commit -m "feat(code-tui): scrollback 与任务面板显示每次子 agent 委派实际用的模型"
```

## Task 7: 全量验证 + 对照设计文档验收标准

依赖 Task 1-6 全部完成。跑一遍模块全量测试，逐条对照设计文档（`docs/superpowers/specs/2026-09-09-code-tui-subagent-model-override-design.md`）的验收标准收尾。

**Files:** 无代码改动（本任务只验证）。

- [ ] **Step 1: 模块全量测试**

Run: `mvn test -pl springai-code-tui`
Expected: `BUILD SUCCESS`，无失败无跳过（除既有的、与本功能无关的既定跳过项，若有）。

- [ ] **Step 2: 逐条核对设计文档验收标准**

对照 `docs/superpowers/specs/2026-09-09-code-tui-subagent-model-override-design.md` 的「验收标准」小节：

1. 同一份 prompt 通过一次 `ParallelTasks` 派给 3 个不同 `model`（含跨 provider），互不干扰，结果各自独立返回——由 Task 3 的 `parallelTasksApplyIndependentModelOverridesPerSubtask` 覆盖。
2. 指定一个不存在/未配置 key 的 provider:model，得到清楚的失败文本，不会静默改跑别的模型——由 Task 1 的 `requestSelectionTwoArgUnknownThrows`/`requestSelectionOneArgUnknownThrows`（`ProviderRegistry` 抛错）与 Task 2 的 `unknownProviderInOverrideSurfacesClearFailureMessage`（端到端：`SubagentRunner.run()` 真的把它包成带清楚文案的 `SubagentFailedException` 抛出，不静默改跑别的模型）共同覆盖。
3. 不传 `model` 时行为与今天完全一致（激活模型，或 spec 自带的静态 model）——由 Task 2 的 `defaultModelUsesActiveConfig`/`explicitModelUsesItsConfig`（既有测试，零改动仍通过）与 Task 3 的 `noModelOverrideLeavesSpecModelUnchanged` 覆盖。
4. scrollback 与任务面板都能看到每次委派实际用的模型——由 Task 6 的 `taskPanel_startedWithModel_showsModelInScrollbackAndPanel`/`row_appendsModelWhenPresent` 覆盖。
5. `mvn test -pl springai-code-tui` 全绿——Step 1 已确认。

若发现某条验收标准找不到对应测试，回到相应任务补测试，不要跳过。

- [ ] **Step 3:（可选，需要真实 API key）手工冒烟**

本功能的最终效果依赖模型自主决定何时使用 `model` 参数，自动化测试只能证明"参数生效、UI 展示正确"，证明不了"真的问了两个不同厂商的模型、拿到两份不同意见"。若本机已配置至少两家 provider 的 API key（如 `DEEPSEEK_API_KEY` 与 `OPENAI_API_KEY`），可手工验证：

```bash
mvn package -pl springai-code-tui -am -DskipTests
java -jar springai-code-tui/target/*.jar
```

进入 TUI 后，提出类似「用 ParallelTasks 把这份 README 的开头两段分别派给 deepseek 和 openai 的子 agent 评估优缺点，再告诉我两边的差异」的请求，确认：
- scrollback 里两条 `▸ Task(...)` 行分别带不同的 `provider:modelId` 标签；
- 任务面板两条子任务行也各自带模型标签；
- 两个子 agent 的回复内容确实来自不同模型（不是同一段文本重复两次）。

这一步不阻塞交付——本任务的自动化测试已完整覆盖设计文档的验收标准，此步仅用于人工确认真实端到端体验。

**不在本计划范围内：** 仓库根目录的 `CHANGELOG.md` 按发布版本组织（每行链接一份 `docs/release-notes/vX.Y.Z.md`），是独立于功能开发的发版流程产物（参照仓库既有的 `release: vX.Y.Z` 提交），不是逐功能追加的「未发布」清单。本功能何时随哪个版本发布、release notes 怎么写，交给用户后续决定，不属于本实施计划的任务。

