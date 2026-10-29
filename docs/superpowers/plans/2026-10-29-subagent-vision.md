# 子 agent 视觉接线 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让委派出去的子 agent（Task/ParallelTasks/后台）拥有与主 agent 同语义的视觉链路——引用块按实际模型能力如实标注，视觉模型的出站请求把引用兑现成真 `Media`。

**Architecture:** 每派发新建一个 `VisionBudget`，与能力快照一起塞进该派发的 `ToolContext`；工具侧优先读 ctx 里的预算，出站侧用同一实例包上 `VisionMaterializingChatModel`。链序 `Vision(Retrying(raw))`，装配知识留在 `AgentTools` 一处。

**Tech Stack:** Java 17、Maven 3.9+、Spring AI 2.0.x、JUnit 5、AssertJ（部分测试用 JUnit 断言）。

**Spec:** `docs/superpowers/specs/2026-10-29-subagent-vision-design.md`（v3，执行时一并阅读）

## Global Constraints

- 语言级别锁 17：禁止使用 17 之后的语法/API。
- 测试命令**必须**带模块作用域与 `-am`：`mvn -pl springai-code-tui -am test ...`；单类命令必须带 `-Dsurefire.failIfNoSpecifiedTests=false`。
- 注释写「为什么」，不写「做了什么」；中文。
- 每个用例必须**先看到红**再写实现；看不到红说明没测到目标行为。
- 不许裸静音预期日志：需要时用 `dev.tamboui.testutil.ExpectedLog`（本计划无此类用例）。
- 不直接提交到 `main`；本计划全程在 `feature/subagent-vision` 分支。
- 提交信息 `type: 说明` 前缀，正文中文；一次提交只做一件事，功能与其测试同一提交。

## 文件结构

| 文件 | 动作 | 责任 |
| --- | --- | --- |
| `agent/media/MediaExternalizingCallback.java` | 改 | 新增 `BUDGET_KEY`；预算来源改为「ctx 优先、构造注入回退」 |
| `agent/subagent/SubagentVision.java` | 新建 | 装配层注入的视觉接缝（decorate 一个 ChatModel） |
| `agent/subagent/SubagentRunner.java` | 改 | 加 `vision` 构造参数；每派发建预算并把 caps+budget 冻结进 ctx；出站走 `vision.decorate` |
| `agent/AgentTools.java` | 改 | L593 构造点传入 `SubagentVision` 实现（root/ImageProfile/能力谓词） |
| `docs/implementation-map.md` | 改 | §17.2 从愿景改为实装 |

测试文件：`MediaExternalizingCallbackBudgetTest`、`SubagentRunnerVisionContextTest`、`SubagentRunnerVisionMaterializeTest`、`SubagentVisionWiringTest`（均在 `src/test/java/.../agent/` 下对应包）。

---

### Task 1: 工具侧预算归属（ctx 优先，构造注入回退）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallback.java`（L24-54 常量与构造；L59-68 `call`；L70-123 `externalize`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallbackBudgetTest.java`（新建）

**Interfaces:**
- Produces: `public static final String BUDGET_KEY = "visionBudget"`（`MediaExternalizingCallback`）；`private VisionBudget budgetOf(ToolContext ctx)`
- Consumes: `VisionBudget.currentTurnExhausted()`（既有）、`ModelCapabilities(boolean,boolean)`（既有）、`TextReferenceMediaHandler`（既有）

- [ ] **Step 1: 写失败测试**

新建 `MediaExternalizingCallbackBudgetTest`：

```java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 预算归属：工具执行期「额度已尽」必须问「发起本次调用的 agent 的预算」，不是进程里最后一次请求。 */
class MediaExternalizingCallbackBudgetTest {

    @TempDir Path root;

    /** 返回一段 Read 风格文本；输入是相对 root 的图片路径。 */
    private static ToolCallback fakeRead() {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("Read").description("read").inputSchema("{}").build();
            }
            @Override public String call(String input) { return "read " + input; }
        };
    }

    /**
     * 让工具走「视觉模型」分支（否则 capsOf 兜底 TEXT_ONLY → 永远 reference_only，
     * 本用例三个断言全都不可能成立）。ctx 带能力快照；预算按参数可选。
     */
    private String readImage(ToolCallback cb, VisionBudget ctxBudget) {
        Map<String, Object> m = new HashMap<>();
        m.put(MediaExternalizingCallback.CAPABILITIES_KEY, new ModelCapabilities(true, false));
        if (ctxBudget != null) {
            m.put(MediaExternalizingCallback.BUDGET_KEY, ctxBudget);
        }
        return cb.call("{\"file_path\":\"" + root.resolve("a.png") + "\"}", new ToolContext(m));
    }

    private static VisionBudget exhaustedBudget(int limit) {
        VisionBudget b = new VisionBudget(limit);
        // 用唯一 key 开一次会话并把工具图额度吃满，使 currentTurnExhausted() 为真
        for (int i = 0; i < limit; i++) {
            b.open("turn-" + System.nanoTime()).tryConsumeTurnSlot();
        }
        return b;
    }

    private void png() throws Exception {
        if (root.resolve("a.png").getParent() != null) Files.createDirectories(root.resolve("a.png").getParent());
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", root.resolve("a.png").toFile());
    }

    private ToolCallback callback(VisionBudget ctorBudget) {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        return new MediaExternalizingCallback(fakeRead(), store, new TextReferenceMediaHandler(), root, ctorBudget);
    }

    @Test
    void ctxBudgetWinsOverConstructorBudget() throws Exception {
        png();
        VisionBudget exhausted = exhaustedBudget(1);
        VisionBudget fresh = new VisionBudget(3);
        String out = readImage(callback(fresh), exhausted);
        assertTrue(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED),
                "ctx 里的预算已耗尽、构造注入的未耗尽 → 必须报耗尽（证明 ctx 优先）。实际：" + out);
    }

    @Test
    void fallsBackToConstructorBudgetWhenCtxHasNone() throws Exception {
        png();
        VisionBudget exhausted = exhaustedBudget(1);
        String out = readImage(callback(exhausted), null);
        assertTrue(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED), "构造注入的预算已耗尽应被读到。实际：" + out);
    }

    @Test
    void noBudgetAnywhereMeansNotExhausted() throws Exception {
        png();
        // MCP 形态：构造 budget=null、ctx 也没有 → 恒 false（与引入本功能前一致）
        String out = readImage(cbWithNullBudget(), null);
        assertFalse(out.contains(FileReference.DELIVERY_TURN_EXHAUSTED), "无预算来源时不得报耗尽。实际：" + out);
        assertTrue(out.contains(FileReference.DELIVERY_NOT_IN_VIEW),
                "有视觉能力但额度未知 → not_in_view（Read 一次就能看）。实际：" + out);
    }

    private ToolCallback cbWithNullBudget() {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        return new MediaExternalizingCallback(fakeRead(), store, new TextReferenceMediaHandler(), root, null);
    }
}
```

> 说明：`delivery` 行由 `TextReferenceMediaHandler` 写入，`FileReference.DELIVERY_TURN_EXHAUSTED` / `DELIVERY_NOT_IN_VIEW` 是既有常量。视觉能力为真由既有测试路径保证（`capsOf(null)` → `TEXT_ONLY` 会走 `reference_only`，故最后一例不能传 null ctx 而应传带 capability 的 ctx —— 见 Step 3 的补充）。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='MediaExternalizingCallbackBudgetTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`BUDGET_KEY` 不存在）。

- [ ] **Step 3: 最小实现**

`MediaExternalizingCallback` 加常量与方法：

```java
    /** ToolContext 里「本次工具调用所属派发的预算」键。子 agent 每派发塞一个自有实例（见 SubagentRunner）。 */
    public static final String BUDGET_KEY = "visionBudget";

    /**
     * 本次工具调用的预算来源：ctx 优先——子 agent 的预算随 ToolContext 下发，各带各的，
     * 这样它读到的额度才是「我自己这次派发的」；ctx 没有则回退装配期注入的那个（主 agent 形态）。
     */
    private VisionBudget budgetOf(ToolContext ctx) {
        if (ctx != null && ctx.getContext().get(BUDGET_KEY) instanceof VisionBudget b) {
            return b;
        }
        return visionBudget;
    }
```

`call(String, ToolContext)` 改为：

```java
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String raw = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        try {
            VisionBudget budget = budgetOf(toolContext);
            return externalize(raw, toolInput, capsOf(toolContext), budget);
        } catch (RuntimeException e) {
            log.warn("媒体外置失败 tool={}：{}", delegate.getToolDefinition().name(), e.toString());
            return raw;
        }
    }
```

`externalize` 签名加一个参数，并把四处 `turnExhausted()` 换成 `turnExhausted(budget)`：

```java
    private String externalize(String raw, String toolInput, ModelCapabilities caps, VisionBudget budget) {
        // ... 原逻辑不动，仅把 handler.represent(a, caps, turnExhausted()) 改为：
        //     handler.represent(a, caps, turnExhausted(budget))
    }

    /** 该预算的「本回合额度已尽」；无预算来源恒 false（测试桩/旧装配/MCP 形态）。 */
    private static boolean turnExhausted(VisionBudget budget) {
        return budget != null && budget.currentTurnExhausted();
    }
```

删除旧的私有 `turnExhausted()`（无参版）。

> 三个用例都经 `readImage(...)` 带上 `CAPABILITIES_KEY=ModelCapabilities(true,false)`：否则
> `capsOf` 兜底 `TEXT_ONLY`，`TextReferenceMediaHandler` 会走 `reference_only` 分支，
> 三个断言一个都不可能成立。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='MediaExternalizingCallbackBudgetTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 例）

- [ ] **Step 5: 跑既有媒体测试确认无回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='MediaExternalizingCallback*Test,TextReferenceMediaHandlerTest,AttachmentInjectionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallback.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallbackBudgetTest.java
git commit -m "feat: 媒体外置预算改为 ctx 优先、构造注入回退"
```

---

### Task 2: `SubagentVision` 接缝 + 能力/预算冻结进 ToolContext

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentVision.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java`（字段区、构造链、`execute`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerVisionContextTest.java`（新建）

**Interfaces:**
- Produces:
  - `public interface SubagentVision { ChatModel decorate(ChatModel base, ProviderRegistry.RequestSelection selection, VisionBudget budget); }`
  - `SubagentRunner(ProviderRegistry, List<ToolCallback>, AgentListener, String, int, McpRegistry, Supplier<PermissionMode>, SubagentVision)`（新的生产重载；`null` = 不接线）
- Consumes: `MediaExternalizingCallback.CAPABILITIES_KEY` / `BUDGET_KEY`（Task 1）、`LlmProvider.capabilities(String)`、`ToolEventCallback.TASK_ID_KEY` / `BACKGROUND_TASK_ID_KEY`

- [ ] **Step 1: 写失败测试**

新建 `SubagentRunnerVisionContextTest`：

```java
package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结进 ToolContext 的两样东西：能力快照 + 该派发自带的预算。 */
class SubagentRunnerVisionContextTest {

    /** 永远发一次工具调用——保证假工具真的被执行（否则用例什么也没测到）。 */
    private static ChatModel toolCallingModel(String modelId) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt p) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                                "c1", "function", "capture", "{}"))).build())));
            }
            @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
            @Override public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().model(modelId).build();
            }
        };
    }

    private static LlmProvider provider(String id, String modelId, boolean vision) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return toolCallingModel(modelId); }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
            @Override public String defaultModel() { return modelId; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(vision, false); }
        };
    }

    private static ToolCallback captureTool(AtomicReference<ToolContext> seen) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("capture").description("c").inputSchema("{}").build();
            }
            @Override public String call(String i) { return "ok"; }
            @Override public String call(String i, ToolContext ctx) { seen.set(ctx); return "ok"; }
        };
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner wired(LlmProvider p, ToolCallback tool) {
        ProviderRegistry registry = new ProviderRegistry(List.of(p));
        return new SubagentRunner(registry, List.of(tool), new StubListener(), "",
                4, null, () -> null, (base, sel, budget) -> base);
    }

    @Test
    void visionDispatchFreezesCapabilitiesAndItsOwnBudget() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        wired(provider("glm", "glm-5.3-flash", true), captureTool(seen)).run(spec(), "hi", "d", 1L);

        assertNotNull(seen.get(), "假工具必须真的被调用过一次");
        ModelCapabilities caps = (ModelCapabilities) seen.get().getContext()
                .get(MediaExternalizingCallback.CAPABILITIES_KEY);
        assertNotNull(caps, "视觉派发必须冻结能力快照，否则引用块永远写 reference_only");
        assertTrue(caps.supportsImageInput());
        assertNotNull(seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY),
                "每派发必须自带 VisionBudget 并下发给工具侧");
    }

    @Test
    void textOnlyDispatchFreezesUnsupportedCapabilities() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        wired(provider("openai", "gpt-5.6-sol", false), captureTool(seen)).run(spec(), "hi", "d", 1L);

        ModelCapabilities caps = (ModelCapabilities) seen.get().getContext()
                .get(MediaExternalizingCallback.CAPABILITIES_KEY);
        assertNotNull(caps);
        assertFalse(caps.supportsImageInput());
    }

    @Test
    void notWiredMeansNoVisualKeysAtAll() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("glm", "glm-5.3-flash", true)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(captureTool(seen)), new StubListener(), "");

        runner.run(spec(), "hi", "d", 1L);

        assertNull(seen.get().getContext().get(MediaExternalizingCallback.CAPABILITIES_KEY),
                "未接线不得半开：只冻结 caps 不接出站会造出「说 Read 可见却永远看不见」的假话");
        assertNull(seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY));
    }

    @Test
    void eachDispatchGetsADistinctBudget() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        SubagentRunner runner = wired(provider("glm", "glm-5.3-flash", true), captureTool(seen));

        runner.run(spec(), "hi", "d", 1L);
        VisionBudget first = (VisionBudget) seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY);
        runner.run(spec(), "hi", "d", 1L);
        VisionBudget second = (VisionBudget) seen.get().getContext().get(MediaExternalizingCallback.BUDGET_KEY);

        assertNotSame(first, second,
                "同一 prompt 的两次派发必须各带预算——否则额度跨派发累计且永不回收");
    }

    @Test
    void backgroundDispatchKeepsBackgroundTaskId() throws Exception {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        SubagentRunner runner = wired(provider("glm", "glm-5.3-flash", true), captureTool(seen));
        runner.enableBackground(new io.github.javaside.springai.codetui.agent.background.BackgroundTaskRegistry(4), 1);

        runner.runInBackground(spec(), "hi", "d");
        for (int i = 0; i < 100 && seen.get() == null; i++) {
            Thread.sleep(20);
        }

        assertNotNull(seen.get(), "后台派发必须真的跑起来");
        assertNotNull(seen.get().getContext().get(ToolEventCallback.BACKGROUND_TASK_ID_KEY),
                "整表复制：漏掉 backgroundTaskId 会让后台审批静默变成阻塞询问");
        assertEquals(String.valueOf(seen.get().getContext().get(ToolEventCallback.TASK_ID_KEY)),
                String.valueOf(seen.get().getContext().get(ToolEventCallback.BACKGROUND_TASK_ID_KEY)),
                "后台路径下两个 taskId 键应指向同一个值");
    }
}
```

> `assertNotNull(seen.get())` 之前若为 null，会 NPE——先断言再取值是本用例的既定写法（顺序如上）。
> `BackgroundTaskRegistry` 构造签名以 `SubagentRunnerBackgroundTest` 现有写法为准。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='SubagentRunnerVisionContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`SubagentVision` / 8 参构造不存在）

- [ ] **Step 3: 新建接缝接口**

`agent/subagent/SubagentVision.java`：

```java
package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 装配层注入的视觉接缝。<b>null 实例 = 不接线</b>（完全旧行为）。
 *
 * <p>存在的理由：把「视觉怎么接」（root、ImageProfile、能力谓词）留在装配点一处，执行器只负责调用。
 * 让 {@link SubagentRunner} 自己 new 装饰器，这份知识就会与 {@code AgentTools} 的主链装配各写一份、
 * 必然漂移——本项目在工具容错白名单、L1 重试链序上都栽过「两处各写一份」。
 *
 * <p>{@code budget} 由调用方按<b>每次派发</b>新建，并同时塞进 ToolContext：出站兑现与工具侧
 * 「额度已尽」的判断必须用同一个实例，否则后者永远是错的。
 */
public interface SubagentVision {

    /** 给一次派发的 ChatModel 挂上视觉兑现。 */
    ChatModel decorate(ChatModel base, ProviderRegistry.RequestSelection selection, VisionBudget budget);
}
```

- [ ] **Step 4: 接进 `SubagentRunner`**

字段（`modeSupplier` 旁）：

```java
    /** 可空：视觉接缝。null = 不接线（旧行为），见 {@link SubagentVision}。 */
    private final SubagentVision vision;
```

生产重载（放在带 `modeSupplier` 的 public 重载之后）：

```java
    public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                          String projectInstructions, int maxConcurrency, McpRegistry mcpRegistry,
                          Supplier<PermissionMode> modeSupplier, SubagentVision vision) {
        this(registry, tools, listener, projectInstructions, maxConcurrency,
                () -> "task_" + UUID.randomUUID(), mcpRegistry, modeSupplier, vision);
    }
```

最全包私有构造追加第 9 参 `SubagentVision vision` 并赋值；**其余所有重载**在委托时补 `null`。

`execute` 改造（`ChatClient.builder` 首参换成变量 `model`，`.toolContext(ctx)`）：

```java
        // 每派发自带预算：范围 = 一次派发。实例隔离让「同一 prompt 重跑」不会累计进上一个派发的桶，
        // 派发结束随实例被 GC，不需要 release 生命周期。
        VisionBudget dispatchBudget = vision == null ? null : new VisionBudget();
        // 整表复制，绝不列举键——后台派发还有 BACKGROUND_TASK_ID_KEY，漏掉它会让后台审批
        // 静默变成「无人值守线程上等一个不会来的面板」。
        Map<String, Object> ctx = new HashMap<>(toolContext);
        if (vision != null) {
            ctx.put(MediaExternalizingCallback.CAPABILITIES_KEY,
                    selection.provider().capabilities(selection.modelId()));
            ctx.put(MediaExternalizingCallback.BUDGET_KEY, dispatchBudget);
        }

        ChatModel base = RetryingChatModel.wrap(selection.provider().chatModel(), quotaHook);
        // 链序 Vision(Retrying(raw))：Vision 最外 → 瞬态重试复用同一已物化 Prompt，不重复扣预算。
        ChatModel model = vision == null ? base : vision.decorate(base, selection, dispatchBudget);
```

新增 import：`MediaExternalizingCallback`、`VisionBudget`、`java.util.HashMap`、
`org.springframework.ai.chat.model.ChatModel`。

- [ ] **Step 5: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='SubagentRunnerVisionContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（5 例）

- [ ] **Step 6: 跑既有 subagent 测试确认无回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='Subagent*Test' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentVision.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerVisionContextTest.java
git commit -m "feat: 子 agent 每派发自带视觉预算，能力快照冻结进 ToolContext"
```

---

### Task 3: 出站兑现（视觉模型出图、文本模型不出图）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerVisionMaterializeTest.java`（新建）
- 本任务**不改生产代码**：Task 2 的接缝已具备能力，这里用真实 `VisionMaterializingChatModel` 作
  `SubagentVision` 实现来验证链序与谓词注入。若测试红，说明 Task 2 的接线位置错了，回 Task 2 修。

**Interfaces:**
- Consumes: `SubagentVision`（Task 2）、`VisionMaterializingChatModel.wrap(ChatModel, Path, Predicate<String>, VisionBudget, ImageProfile)`、`ImageProfile.forProvider(String)`、`FileReference.render(...)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.subagent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.ImageProfile;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 agent 的出站兑现：视觉模型必须把引用物化成真 Media，文本模型必须一张都不发。
 *
 * <p><b>文本用例为什么用 gpt-5.6-sol</b>：它在全局白名单（VisionModels）里，而 stub provider 的
 * capabilities 返回 false。若把能力谓词错写成全局 VisionModels::supportsImage，本用例会变绿——
 * 这正是 provider 注入的意义（兼容网关同名模型、OpencodeGo 自定义模型都靠它）。
 */
class SubagentRunnerVisionMaterializeTest {

    @TempDir Path root;

    /** 记录 delegate 真正收到的 Prompt。 */
    private static final class SpyModel implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        private final String model;
        SpyModel(String model) { this.model = model; }
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() {
            // 必须是 ToolCallingChatOptions：否则 ChatClient 拿到的不是它 → ToolCallingAdvisor 整个跳过
            return ToolCallingChatOptions.builder().model(model).build();
        }
    }

    private LlmProvider provider(String id, String modelId, boolean vision, SpyModel spy) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return spy; }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "d")); }
            @Override public String defaultModel() { return modelId; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(vision, false); }
        };
    }

    /** 与 AgentTools 里那份实现同构（本任务就是验证这份链序）。 */
    private static SubagentVision vision(Path root) {
        return (base, sel, budget) -> VisionMaterializingChatModel.wrap(
                base, root,
                id -> sel.provider().capabilities(id).supportsImageInput(),
                budget,
                ImageProfile.forProvider(sel.provider().id()));
    }

    private void png(String rel) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }

    /** 能通过 FileReferenceParser 严格校验的引用块。 */
    private String ref(String rel) {
        return "[file reference]\n"
                + "id: sha256:" + Integer.toHexString(rel.hashCode()) + "\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: a.png\n"
                + "path: " + rel + "\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
    }

    private static boolean hasMedia(Prompt p) {
        return p.getInstructions().stream()
                .filter(Message.class::isInstance)
                .anyMatch(m -> m instanceof MediaContent mc && !mc.getMedia().isEmpty());
    }

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    @Test
    void visionModelGetsRealMediaInOutboundPrompt() throws Exception {
        png("a.png");
        SpyModel spy = new SpyModel("glm-5.3-flash");
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("glm", "glm-5.3-flash", true, spy)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "",
                4, null, () -> null, vision(root));

        runner.run(spec(), "看这个\n" + ref("a.png"), "d", 1L);

        assertTrue(hasMedia(spy.seen.get()),
                "视觉模型的子 agent 出站必须带真 Media；否则引用块只是文本，模型永远看不见图");
    }

    @Test
    void textModelGetsNoMediaEvenIfModelIdIsGloballyVisionCapable() throws Exception {
        png("a.png");
        SpyModel spy = new SpyModel("gpt-5.6-sol");
        ProviderRegistry registry = new ProviderRegistry(List.of(provider("openai", "gpt-5.6-sol", false, spy)));
        SubagentRunner runner = new SubagentRunner(registry, List.of(), new StubListener(), "",
                4, null, () -> null, vision(root));

        runner.run(spec(), "看这个\n" + ref("a.png"), "d", 1L);

        assertFalse(hasMedia(spy.seen.get()),
                "provider 判不支持时一张都不许发——错写全局 VisionModels::supportsImage 会让本用例变红");
        // VisionMaterializer 在 visionCapable=false 时【原样返回同一个 Prompt】，不做任何改写：
        // 故这里断言的是「一行都没被改成 delivered」，而不是 reference_only（那是【入站】写的）。
        assertTrue(spy.seen.get().getInstructions().stream()
                        .noneMatch(m -> m.getText() != null && m.getText().contains("delivery: delivered")),
                "不支持的模型必须一张都不兑现——任何 delivery: delivered 都意味着真把图发出去了");
    }
}
```

> 为什么不是断言 `reference_only`：`VisionMaterializer.materialize` 在 `visionCapable=false` 时
> **原样返回**（L152-155），引用块的 `delivery` 是**入站**侧（`MediaExternalizingCallback` +
> `TextReferenceMediaHandler`）写好的，出站不会改写。断言 delivered 才是本用例要钉的东西。

- [ ] **Step 2: 跑测试**

Run: `mvn -pl springai-code-tui -am test -Dtest='SubagentRunnerVisionMaterializeTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **第一个用例 PASS**（Task 2 已接线），第二个用例应 PASS。若第一个红 → Task 2 的
`vision.decorate` 位置/参数有误（最常见：`budget` 传了 null，或谓词用了全局 `VisionModels`）。

- [ ] **Step 3: 做一次变异确认用例有效**

把 `SubagentRunner.execute` 里的
`ChatModel model = vision == null ? base : vision.decorate(...)` 临时改成 `ChatModel model = base;`

Run: 同 Step 2
Expected: `visionModelGetsRealMediaInOutboundPrompt` **变红**（这证明第一个用例真的在测接线）。
改回后再跑一次确认绿，并 `git status` 确认工作树干净。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunnerVisionMaterializeTest.java
git commit -m "test: 子 agent 出站兑现（视觉出图/文本不出图，provider 谓词注入）"
```

---

### Task 4: AgentTools 生产装配 + 端到端守卫

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（L587-595 子 agent 装配段）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentVisionWiringTest.java`（新建）

**Interfaces:**
- Consumes: `SubagentRunner(..., SubagentVision)`（Task 2）、`AgentRuntime.subagentRunner()`（既有）、`AgentTools.build(ProviderRegistry, Path, AgentListener, McpRegistry, PermissionEngine)`（既有，5 参重载）

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产装配守卫：两张表（装配传参、执行器建链）都改了才算真的接上。
 *
 * <p>单测里 new 一个 SubagentRunner 全绿、而 AgentTools 少传一个参数，是这类改动的经典漏法
 * （同 AuxClientNotVisionWrappedTest 存在的理由）。本用例走真实 build()，只放行子 agent 出站那一步。
 */
class SubagentVisionWiringTest {

    @TempDir Path root;

    private static final class SpyModel implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().model("glm-5.3-flash").build();
        }
    }

    @Test
    void productionWiringMaterializesSubagentImages() throws Exception {
        Path img = root.resolve("a.png");
        ImageIO.write(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", img.toFile());

        SpyModel spy = new SpyModel();
        LlmProvider provider = new LlmProvider() {
            @Override public String id() { return "glm"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() { return spy; }
            @Override public ChatOptions options(String m) {
                return ToolCallingChatOptions.builder().model(m).build();
            }
            @Override public List<ModelOption> models() {
                return List.of(new ModelOption("glm-5.3-flash", "flash", "d"));
            }
            @Override public String defaultModel() { return "glm-5.3-flash"; }
            @Override public ModelCapabilities capabilities(String m) { return new ModelCapabilities(true, false); }
        };
        ProviderRegistry registry = new ProviderRegistry(List.of(provider));
        AgentTools.AgentRuntime rt = AgentTools.build(registry, root, new StubListener(), null, null);

        String ref = "[file reference]\n"
                + "id: sha256:abcd\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 10\n"
                + "name: a.png\n"
                + "path: a.png\n"
                + "delivery: not_in_view\n"
                + "reason: x\n"
                + "[/file reference]";
        rt.subagentRunner().run(
                new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of()),
                "看这个\n" + ref, "d", 1L);

        assertTrue(spy.seen.get().getInstructions().stream()
                        .filter(Message.class::isInstance)
                        .anyMatch(m -> m instanceof MediaContent mc && !mc.getMedia().isEmpty()),
                "AgentTools 必须把 root/预算/谓词传给 SubagentRunner，否则子 agent 永远看不见图");
    }
}
```

> `AgentTools.build` 的 permissionEngine 传 null 若导致 NPE，改传既有测试里的桩（参考
> `AgentTools*Test` 的用法）；`AgentRuntime` 是 `AgentTools` 的嵌套 record，按实际包名引用。

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='SubagentVisionWiringTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **FAIL** ——断言失败（AgentTools 还没传 `SubagentVision`，出站无 Media）。

- [ ] **Step 3: 装配实现**

`AgentTools` L593 构造点改为：

```java
        // 子 agent 视觉接缝：能力谓词按 provider 注入（勿用全局 VisionModels——兼容网关同名模型、
        // OpencodeGo 自定义模型都会判错），ImageProfile 按 provider 取口径（统一 Anthropic 口径会
        // 让 DeepSeek 的 token 高估 3.6 倍）。预算由 SubagentRunner 每派发新建并随 ToolContext 下发。
        SubagentVision subagentVision = (base, sel, budget) -> VisionMaterializingChatModel.wrap(
                base, root,
                id -> sel.provider().capabilities(id).supportsImageInput(),
                budget,
                ImageProfile.forProvider(sel.provider().id()));
        SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener,
                projectInstructions, subagentConcurrency, mcpRegistry, permissionEngine::mode, subagentVision);
```

新增 import：`io.github.javaside.springai.codetui.agent.subagent.SubagentVision`、
`io.github.javaside.springai.codetui.agent.media.ImageProfile`（`VisionMaterializingChatModel` 已在 import 中）。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='SubagentVisionWiringTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: 跑装配相关守卫与 subagent 全家**

Run: `mvn -pl springai-code-tui -am test -Dtest='AuxClient*Test,AgentTools*Test,Subagent*Test,CodingAgentVisionStatsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentVisionWiringTest.java
git commit -m "feat: AgentTools 装配子 agent 视觉接缝（provider 谓词 + 各家 ImageProfile）"
```

---

### Task 5: 文档订正

**Files:**
- Modify: `springai-code-tui/docs/implementation-map.md:1155-1160`（出站统一收口一段）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java:130-133`（`accumulate` javadoc）

**Interfaces:** 无代码接口变化。

- [ ] **Step 1: 订正 implementation-map**

把「`call` 与 `stream` 都要走（主 agent 走 stream，子 agent 经 `RetryingChatModel` 桥到 stream，只改一条等于子 agent 没有视觉）」一段改为实装描述，追加：

```markdown
- **子 agent 侧已实装**（2026-10-29）：`SubagentRunner.execute` 在 `RetryingChatModel` 外包一层
  `VisionMaterializingChatModel`（链序 `Vision(Retrying(raw))`，`call` 入口），接缝见
  `SubagentVision`、装配在 `AgentTools`。**每派发新建一个 `VisionBudget`** 并随 `ToolContext`
  的 `BUDGET_KEY` 下发给工具侧（`MediaExternalizingCallback` 优先读 ctx、回退构造注入）——
  实例隔离让「同一 prompt 重跑」不会累计进同一个桶（turnKey 只看锚点文本+下标，子 agent 锚点恒为 1），
  派发结束随实例 GC，无需释放钩子。能力快照同样冻结进 ctx（`CAPABILITIES_KEY`）。
  代价：拒绝集是实例态，同一张被服务端拒过的图每次派发都会重传并再吃一个 400。
```

- [ ] **Step 2: 订正 VisionMaterializer javadoc**

把「代价是并发子 agent 的回合会覆盖主 agent 的账，两者交替时数字会跳。」改为：

```java
     * 代价是并发 agent 的回合会覆盖彼此的账，交替时数字会跳（子 agent 侧每派发新建实例后，
     * 该覆盖只发生在主 agent 自己的回合序列里；`/context` 读的也只有主链实例）。
```

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/docs/implementation-map.md \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java
git commit -m "docs: 子 agent 视觉接线从愿景改为实装，订正统计口径注释"
```

---

### Task 6: 全量测试与合并

**Files:** 无

- [ ] **Step 1: 全量测试**

Run: `mvn -pl springai-code-tui -am test`
Expected: BUILD SUCCESS（无失败、无跳过以外的异常）

- [ ] **Step 2: 确认工作树干净**

Run: `git status --short`
Expected: 无输出

- [ ] **Step 3: 合并回 main**

```bash
git checkout main
git merge --no-ff feature/subagent-vision -m "merge: feature/subagent-vision —— 子 agent 视觉接线（能力快照 + 每派发自有预算 + 出站兑现）"
```

- [ ] **Step 4: 合并后再跑一次全量**

Run: `mvn -pl springai-code-tui -am test`
Expected: BUILD SUCCESS

## Self-Review 记录

- **spec 覆盖**：§5.1（Task 2）、§5.2 接缝（Task 2）、§5.3 预算随 ctx（Task 1/2）、§5.4 装配（Task 4）、
  §5.5 链序（Task 3 变异验证）、§7 T1–T5（Task 1/2/3/4）、§8 文档（Task 5）。§3 非目标无对应任务（正确）。
- **占位符**：无 TBD/TODO；每步都有可执行命令或完整代码。
- **类型一致性**：`SubagentVision.decorate(ChatModel, RequestSelection, VisionBudget)` 在 Task 2/3/4 一致；
  `BUDGET_KEY`/`CAPABILITIES_KEY` 均挂在 `MediaExternalizingCallback` 上。
