# 手动指定技能（`/skill` 选择器）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户通过 `/skill` 选择器手动指定一个技能，紧接着的下一条消息在发送前真正调用被装饰的 Skill 工具（UI 显示工具活动）、把正文注入后发送。

**Architecture:** 手动路径与模型自动路径共用同一个被 `ToolEventCallback` 装饰的 Skill `ToolCallback`——`AgentTools.build` 把它经 `AgentRuntime` 上抛；`CodingAgent.submit(text, skillName)` 在发送前用本回合 turnId 构造 `ToolContext` 调该工具，拿正文拼进 `<skill_instruction>` 再发。UI 侧 `CodeTuiView` 加 `/skill` 选择器（仿 `/model`）与「一次性挂载」状态；挂载随排队消息一起入队。

**Tech Stack:** Java 21, Spring AI 2.0 (`ChatClient`/`ToolCallback`/`ToolContext`), spring-ai-agent-utils `SkillsTool`, TamboUI (`InlineApp`), JUnit 5, Maven。

**参考 spec:** `docs/superpowers/specs/2026-07-03-manual-skill-selection-design.md`

**全程约定:** 所有 `mvn` 命令从仓库根 `/Users/zxh/IdeaProjects/springai-agentdemo` 运行（shell 每条命令后 cwd 会重置到根，故用 `-pl springai-code-tui`）。

---

## File Structure

| 文件 | 责任 | 动作 |
| --- | --- | --- |
| `agent/AgentTools.java` | 装配：捕获被装饰的 Skill 工具，经 `AgentRuntime` 上抛 | 修改 |
| `agent/SubmitHandler.java` | UI↔Agent 接缝：加 `submit(text, skillName)` 默认方法 | 修改 |
| `agent/CodingAgent.java` | 手动注入：`submit(text, skillName)` 发送前调工具、拼正文 | 修改 |
| `ui/ConversationState.java` | 排队项携带技能（`Queued(text, skill)`） | 修改 |
| `ui/CodeTuiView.java` | `/skill` 选择器 + 一次性挂载 + 发送路径消费挂载 | 修改 |
| `test/.../CodingAgentSkillInjectTest.java` | 注入逻辑单测（工具事件 + 文本注入 + 边界） | 新建 |
| `test/.../ConversationStateQueueTest.java` | 排队携带技能单测 | 新建 |
| 现有 `CodingAgentSpikeTest`/`CompactTest`/`ContextTest`/`SubmitErrorTest` | 构造函数/调用点跟随 | 视情况修改（本计划不改其签名——见 Task 3 说明） |

---

## Task 1: `AgentRuntime` 上抛被装饰的 Skill 工具

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java`

- [ ] **Step 1: 写失败测试 —— AgentRuntime 暴露 skillTool（有技能时非空、名为 Skill）**

在 `AgentRuntimeTest.java` 末尾（`build_returnsRuntime_withAllHandles` 之后、类结束 `}` 之前）加：

```java
    @Test
    void build_exposesDecoratedSkillTool_whenSkillsPresent(@TempDir Path root) throws Exception {
        // 造一个项目级技能，确保 SkillCatalog 至少加载到一个技能
        Path dir = root.resolve(".codetui/skills/demo-skill");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo-skill
                description: 演示技能。
                ---

                # 正文
                """);

        AgentTools.AgentRuntime rt = AgentTools.build(dummyModel(), root, new ConversationState());

        assertNotNull(rt.skillTool(), "有技能时应暴露被装饰的 Skill 工具");
        assertEquals("Skill", rt.skillTool().getToolDefinition().name(), "工具名应为 Skill");
    }

    @Test
    void build_skillToolNull_whenNoSkills(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyModel(), root, new ConversationState());
        assertNull(rt.skillTool(), "无技能时 skillTool 应为 null");
    }
```

在文件顶部 import 区补（若尚无）：

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.springframework.ai.tool.ToolCallback;
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=AgentRuntimeTest 2>&1 | tail -15`
Expected: 编译失败，`找不到符号 ... skillTool()`（record 尚无该组件）。

- [ ] **Step 3: 在装饰循环里捕获 Skill 工具实例**

在 `AgentTools.java` 装饰循环处（`ToolCallback[] decorated = new ToolCallback[all.size()];` 起）替换为：

```java
        ToolCallback[] decorated = new ToolCallback[all.size()];
        ToolCallback decoratedSkillTool = null;   // 手动 /skill 路径复用同一个被装饰实例（事件/返回与自动路径一致）
        for (int i = 0; i < all.size(); i++) {
            decorated[i] = new ToolEventCallback(all.get(i), listener);
            if (all.get(i) == skills.tool()) {
                decoratedSkillTool = decorated[i];   // 记住 Skill 工具装饰后的实例（无技能时保持 null）
            }
        }
```

- [ ] **Step 4: `AgentRuntime` 增加 `skillTool` 组件并回填**

把 `return new AgentRuntime(...)`（当前在文件末尾）改为：

```java
        return new AgentRuntime(client, sessionService, manualStrategy, tokenCountEstimator,
                skills.skills(), decoratedSkillTool);
```

把 `AgentRuntime` record 定义改为（在其 javadoc 的 `@param skills` 行后加一行 `@param skillTool`）：

```java
    public record AgentRuntime(ChatClient client,
                               SessionService sessionService,
                               CompactionStrategy manualStrategy,
                               TokenCountEstimator tokenCountEstimator,
                               List<SkillInfo> skills,
                               ToolCallback skillTool) {}
```

（`ToolCallback` 已在 `AgentTools.java` import，无需新增。）

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=AgentRuntimeTest 2>&1 | tail -15`
Expected: `Tests run: 3, Failures: 0`（原 1 + 新 2）。

- [ ] **Step 6: 提交**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java
git commit -m "feat(code-tui): AgentRuntime 上抛被装饰的 Skill 工具（供手动 /skill 复用）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `SubmitHandler` 加带技能提交入口

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubmitHandler.java`

（此任务只加一个默认方法，无独立行为可测；由 Task 3 的 `CodingAgent` 测试间接覆盖。故不写单测，仅编译验证。）

- [ ] **Step 1: 加默认方法**

在 `SubmitHandler.java` 的 `Disposable submit(String text);` 之后加：

```java
    /**
     * 带指定技能提交：发送前先调用该技能工具、把正文注入到 text 前。
     * skillName 为 null 时等价 {@link #submit(String)}。默认实现委托无技能提交，便于桩省略。
     */
    default Disposable submit(String text, String skillName) { return submit(text); }
```

- [ ] **Step 2: 编译验证**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui compile 2>&1 | tail -5`
Expected: 无输出（BUILD SUCCESS）。

- [ ] **Step 3: 提交**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/SubmitHandler.java
git commit -m "feat(code-tui): SubmitHandler 加 submit(text, skillName) 默认方法

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `CodingAgent.submit(text, skillName)` 发送前调工具注入正文

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSkillInjectTest.java` (create)

**关键设计说明（不改现有构造函数签名）:** 现有 7 个测试点用 7/8 参构造 `new CodingAgent(...)`，为避免改动它们，本任务**新增一个可选的 `skillTool` 字段**，并**只在 8 参构造后再链一个 9 参构造**。7 参与 8 参构造都委托到 9 参、`skillTool` 传 `null`。这样现有调用点全部保持编译通过。

- [ ] **Step 1: 写失败测试 —— 注入触发工具事件 + 文本含正文**

创建 `CodingAgentSkillInjectTest.java`：

```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodingAgent 手动 /skill 注入：发送前真正调用（被装饰的）Skill 工具，
 * 触发工具事件（UI 可见）并把返回正文注入到 user 消息前。
 *
 * <p>ChatClient 用一个「同步抛异常」的桩——我们只验证「发送前」的工具调用与文本注入，
 * 不关心之后的流式（异常经 onError 复位，不影响本测试断言）。effectiveText 通过在
 * fakeSkillTool 之外单独记录：因为注入发生在 chatClient.prompt() 之前无法从桩截获，
 * 故改为断言「onUserMessage 记录的是原文」+「工具被调用且入参含技能名」这一对可观测事实。
 */
class CodingAgentSkillInjectTest {

    /** 记录工具事件的假 listener（仅实现本测试需要的方法，其余空实现见 StubListener）。 */
    private static final class RecordingListener extends StubListener {
        final List<String> toolStarts = new ArrayList<>();
        final List<String> toolInputs = new ArrayList<>();
        final AtomicReference<Boolean> lastOk = new AtomicReference<>();
        String userMessage;
        @Override public void onToolStarted(long turnId, String toolName, String input) {
            toolStarts.add(toolName); toolInputs.add(input);
        }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {
            lastOk.set(ok);
        }
        @Override public void onUserMessage(long turnId, String text) { this.userMessage = text; }
    }

    /** 假 Skill 工具：记录被调入参，返回固定正文（模拟库返回的 "Base directory...\n\n正文"）。 */
    private static ToolCallback fakeSkillTool(AtomicReference<String> capturedInput) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("Skill").description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) { return call(toolInput, null); }
            @Override public String call(String toolInput, ToolContext toolContext) {
                capturedInput.set(toolInput);
                return "Base directory for this skill: /tmp/demo\n\n技能正文内容ABC";
            }
        };
    }

    @Test
    void submitWithSkill_callsToolAndReportsEvents() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<String> toolInput = new AtomicReference<>();
        // ChatClient 用 null——注入逻辑在 chatClient.prompt() 之前执行；prompt() 触发 NPE 会被 submit 的
        // catch(RuntimeException) 兜底为 onError（状态复位），不影响「工具已被调用」这一先决事实。
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), fakeSkillTool(toolInput));

        agent.submit("帮我写提交信息", "git-commit-message");

        assertEquals(List.of("Skill"), listener.toolStarts, "应触发一次名为 Skill 的工具事件");
        assertTrue(listener.toolInputs.get(0).contains("git-commit-message"),
                "工具入参应含技能名");
        assertEquals("{\"command\":\"git-commit-message\"}", toolInput.get(),
                "入参应为 SkillsInput 的 command JSON");
        assertTrue(listener.lastOk.get(), "工具应报告成功完成");
        assertEquals("帮我写提交信息", listener.userMessage,
                "onUserMessage 应记录用户原文（注入只影响发给模型的文本，不改 UI 展示的用户消息）");
    }

    @Test
    void submitWithNullSkill_doesNotCallTool() {
        RecordingListener listener = new RecordingListener();
        AtomicReference<String> toolInput = new AtomicReference<>();
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), fakeSkillTool(toolInput));

        agent.submit("普通消息", null);

        assertTrue(listener.toolStarts.isEmpty(), "skillName 为 null 时不应调用工具");
        assertEquals("普通消息", listener.userMessage);
    }

    @Test
    void submitWithSkill_butNoSkillTool_doesNotCrash() {
        RecordingListener listener = new RecordingListener();
        // skillTool 为 null（无技能装配）：即使传了技能名也应安全降级为不注入
        CodingAgent agent = new CodingAgent(null, listener, "s", new AtomicLong(),
                null, null, null, List.of(), null);

        agent.submit("消息", "some-skill");

        assertTrue(listener.toolStarts.isEmpty(), "skillTool 为 null 时不调用、不崩");
        assertFalse(listener.userMessage == null, "用户消息仍应被记录");
    }
}
```

**说明：** `StubListener` 是一个把 `AgentListener` 全部方法空实现的测试基类。若仓库尚无，需在本任务一并创建（见 Step 1b）。

- [ ] **Step 1b: 若无 `StubListener` 则创建**

先检查：`cd /Users/zxh/IdeaProjects/springai-agentdemo && grep -rl "class StubListener\|abstract.*AgentListener" springai-code-tui/src/test`

若无输出，创建 `springai-code-tui/src/test/java/com/example/springai/codetui/agent/StubListener.java`：

```java
package com.example.springai.codetui.agent;

import java.util.List;

/** AgentListener 的空实现基类：测试只覆写关心的方法。 */
class StubListener implements AgentListener {
    @Override public void onTurnStarted(long turnId) {}
    @Override public void onUserMessage(long turnId, String text) {}
    @Override public void onAssistantToken(long turnId, String token) {}
    @Override public void onToolStarted(long turnId, String toolName, String input) {}
    @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {}
    @Override public void onTodoUpdated(long turnId, List<String> todoLines) {}
    @Override public void onTurnComplete(long turnId) {}
    @Override public void onError(long turnId, Throwable error) {}
    @Override public void onCompactionStarted(String reason) {}
    @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) {}
    @Override public void onCompactionFailed(String message) {}
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=CodingAgentSkillInjectTest 2>&1 | tail -15`
Expected: 编译失败——`CodingAgent` 无 9 参构造、无 `submit(String,String)`。

- [ ] **Step 3: `CodingAgent` 加 skillTool 字段 + 9 参构造 + submit(text, skillName)**

在 imports 区（`import reactor.core.Disposables;` 之后）加：

```java
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
```

在字段区（`private final List<SkillInfo> skills;` 之后）加：

```java
    private final ToolCallback skillTool;   // 被 ToolEventCallback 装饰的 Skill 工具（可空）；手动 /skill 发送前调用它
```

把现有 8 参构造体（`this.skills = List.copyOf(skills);` 那一段）改为委托 9 参：

```java
    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, null);
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills, ToolCallback skillTool) {
        this.chatClient = chatClient;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
    }
```

（7 参构造保持不变——它委托 8 参、8 参再委托 9 参，`skillTool` 一路传 `null`。）

把 `public Disposable submit(String text)` 整个方法替换为「无参委托 + 新带技能重载」：

```java
    @Override
    public Disposable submit(String text) {
        return submit(text, null);
    }

    @Override
    public Disposable submit(String text, String skillName) {
        long turnId = activeTurnId.incrementAndGet();
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);  // UI 展示用户原文（注入只影响发给模型的文本）
        String effectiveText = injectSkill(text, skillName, turnId);
        try {
            return chatClient.prompt()
                    .user(effectiveText)
                    .options(DeepSeekChatOptions.builder().model(model))   // 每次请求按当前所选模型覆盖
                    // 同步覆盖系统提示里的 {AGENT_MODEL} grounding，使模型自报身份与实际所选一致（其余 param 沿用默认，merge 语义）
                    .system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, model))
                    .toolContext(Map.of("turnId", turnId))
                    // 会话记忆键：SessionMemoryAdvisor 按此解析/自动创建会话（值即 chat_memory_conversation_id）
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .stream().chatClientResponse()
                    .doOnNext(resp -> handleChunk(resp, turnId))
                    .doOnError(err -> handleError(err, turnId))
                    .doOnComplete(() -> handleComplete(turnId))
                    .subscribe();
        } catch (RuntimeException ex) {
            // 同步组装/订阅异常不走 doOnError：手动复位状态（onError → IDLE），
            // 否则 UI 会永远卡在 THINKING（无终态事件），且异常会逃逸出 View.handle。
            listener.onError(turnId, ex);
            return Disposables.disposed();
        }
    }

    /**
     * 手动 /skill：发送前真正调用（被装饰的）Skill 工具——触发工具事件（UI 可见）并拿正文注入到 text 前。
     * skillName/skillTool 任一为空则原样返回（不注入）；工具调用失败也降级为不注入（事件已由装饰器上报）。
     */
    private String injectSkill(String text, String skillName, long turnId) {
        if (skillName == null || skillTool == null) {
            return text;
        }
        try {
            ToolContext ctx = new ToolContext(Map.of("turnId", turnId));
            String body = skillTool.call(toJsonCommand(skillName), ctx);
            return "<skill_instruction>\n" + body + "\n</skill_instruction>\n\n" + text;
        } catch (RuntimeException ex) {
            return text;   // 注入失败不阻断本轮：按原文发送（工具失败事件已由 ToolEventCallback 上报）
        }
    }

    /** 构造 SkillsInput 的 JSON 入参 {"command":"<name>"}；技能名来自选择器选中，仍最小转义防引号/反斜杠。 */
    private static String toJsonCommand(String skillName) {
        String escaped = skillName.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"command\":\"" + escaped + "\"}";
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=CodingAgentSkillInjectTest 2>&1 | tail -20`
Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 5: 跑全量确认无回归（现有 CodingAgent 测试构造函数未受影响）**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test 2>&1 | grep -E "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" | tail -1`
Expected: 全绿（含新增 3 项）。

- [ ] **Step 6: 提交**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/CodingAgentSkillInjectTest.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/StubListener.java
git commit -m "feat(code-tui): CodingAgent.submit(text,skill) 发送前调工具注入技能正文

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `ConversationState` 排队项携带技能

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateQueueTest.java` (create)

- [ ] **Step 1: 写失败测试**

创建 `ConversationStateQueueTest.java`：

```java
package com.example.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 排队项携带技能：enqueue(text, skill) → pollQueued() 返回 Queued(text, skill)；snapshot 仍只给文本。 */
class ConversationStateQueueTest {

    @Test
    void enqueueWithSkill_pollReturnsBoth() {
        ConversationState s = new ConversationState();
        s.enqueue("消息A", "git-commit-message");
        s.enqueue("消息B", null);

        assertEquals(2, s.queuedCount());
        assertEquals(List.of("消息A", "消息B"), s.queuedSnapshot(), "快照仍只含文本，渲染不变");

        ConversationState.Queued first = s.pollQueued();
        assertEquals("消息A", first.text());
        assertEquals("git-commit-message", first.skill());

        ConversationState.Queued second = s.pollQueued();
        assertEquals("消息B", second.text());
        assertNull(second.skill(), "未挂载技能时 skill 为 null");

        assertNull(s.pollQueued(), "队列空时返回 null");
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=ConversationStateQueueTest 2>&1 | tail -15`
Expected: 编译失败——无 `Queued` 类型、`enqueue(String,String)`、`pollQueued()` 返回类型不符。

- [ ] **Step 3: 升级 `ConversationState` 队列**

把 `ConversationState.java` 中队列字段与 5 个方法（当前）：

```java
    private final Deque<String> queued = new ArrayDeque<>();       // 忙时排队的用户消息（回合结束后自动出队提交）
```
```java
    public synchronized void enqueue(String msg) { queued.add(msg); }
    public synchronized String pollQueued() { return queued.poll(); }
    public synchronized int queuedCount() { return queued.size(); }
    public synchronized void clearQueued() { queued.clear(); }
    public synchronized List<String> queuedSnapshot() { return List.copyOf(queued); }
```

替换为：

```java
    /** 排队的用户消息 + 其挂载技能（可空）。挂载随消息入队，出队时一并带出。 */
    public record Queued(String text, String skill) {}

    private final Deque<Queued> queued = new ArrayDeque<>();       // 忙时排队的用户消息（回合结束后自动出队提交）
```
```java
    public synchronized void enqueue(String msg, String skill) { queued.add(new Queued(msg, skill)); }
    public synchronized Queued pollQueued() { return queued.poll(); }
    public synchronized int queuedCount() { return queued.size(); }
    public synchronized void clearQueued() { queued.clear(); }
    public synchronized List<String> queuedSnapshot() { return queued.stream().map(Queued::text).toList(); }
```

（`Deque`/`ArrayDeque`/`List` 已 import；`queuedSnapshot` 现用 `stream().map(...).toList()`，无需新 import。）

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test -Dtest=ConversationStateQueueTest 2>&1 | tail -15`
Expected: `Tests run: 1, Failures: 0`。

- [ ] **Step 5: 编译整模块（此时 CodeTuiView 的旧 enqueue/pollQueued 调用点会编译失败——预期，下一任务修）**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui compile 2>&1 | grep -E "enqueue|pollQueued|ERROR" | head`
Expected: 报 `CodeTuiView.java` 两处调用点不匹配（`enqueue(text)` / `String next = pollQueued()`）。**这是预期的**，Task 5 修复。

- [ ] **Step 6: 提交（含预期的下游编译缺口，紧接 Task 5 修复）**

> 注：为保持「每次提交可编译」，本步骤**不单独提交**。跳到 Task 5 完成后一并提交 State+View 改动。若严格要求本任务独立提交，则先把 `CodeTuiView` 两处调用点临时改为 `enqueue(text, null)` 与 `pollQueued().text()`（丢弃技能），使其可编译再提交，Task 5 再补全技能传递。**推荐后者**——见下方 Step 6b。

- [ ] **Step 6b: 临时修 CodeTuiView 使可编译（最小改动，仅为独立提交）**

在 `CodeTuiView.java`：
- 把 `state.enqueue(text);` 改为 `state.enqueue(text, null);`
- 把 drain 里 `String next = state.pollQueued(); if (next != null) dispatch(next);` 改为：
  ```java
  ConversationState.Queued next = state.pollQueued();
  if (next != null) dispatch(next.text());
  ```

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui test 2>&1 | grep -E "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$" | tail -1`
Expected: 全绿。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateQueueTest.java
git commit -m "feat(code-tui): 排队项携带挂载技能（ConversationState.Queued）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `CodeTuiView` 加 `/skill` 选择器 + 一次性挂载

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`

**说明:** 选择器 UI 深依赖 TamboUI 运行时，难以纯单测；核心行为「一次性消费」与「随消息入队」已在 Task 3/4 的 agent/state 层覆盖。本任务以**手动冒烟 + 编译/全量回归**验收，不新增 UI 单测（与既有 `/model` 选择器同策略——它也无 UI 单测）。

- [ ] **Step 1: 加字段 `pickingSkill` / `pendingSkill`**

在字段区 `private boolean pickingModel;` 之后加：

```java
    private boolean pickingSkill;                                    // /skill 选择器是否激活
    private String pendingSkill;                                     // 已挂载、待下一条消息生效的技能名（可空）
```

- [ ] **Step 2: `COMMANDS` 加 `/skill`**

在 `COMMANDS` 列表里 `new SlashCommand("/skills", ...)` 之后（或 `/context` 之后）加一行：

```java
            new SlashCommand("/skill",   "指定技能（下条消息生效）"),
```

- [ ] **Step 3: `submitInput()` 加 `/skill` 分支**

在 `submitInput()` 里 `/skills` 分支（`if (cmd.equals("/skills")) {...}`）之后加：

```java
        if (cmd.equals("/skill")) {                  // 打开技能选择器（挂载后下条消息生效）
            inputState.clear();
            openSkillPicker();
            return;
        }
```

- [ ] **Step 4: `onInputKey` 加选择器守卫**

把 `if (pickingModel) return onModelPickerKey(k);` 一行改为两行：

```java
        if (pickingModel) return onModelPickerKey(k);   // 选择器激活：按键全部交给它，屏蔽文本编辑
        if (pickingSkill) return onSkillPickerKey(k);   // 技能选择器同理
```

- [ ] **Step 5: `render()` 加技能选择器面板 scope**

在 `render()` 的 column 里 `scope(pickingModel, modelPickerChildren()),` 之后加：

```java
                scope(pickingSkill, skillPickerChildren()),         // /skill 选择器面板
```

- [ ] **Step 6: 加三个选择器方法（仿 /model）**

在 `modelPickerChildren()` 方法之后加：

```java
    // ── /skill 技能选择器 ───────────────────────────────────────────────
    /** 打开技能选择器；无技能则提示不弹。默认高亮第一项。 */
    private void openSkillPicker() {
        List<SkillInfo> list = onSubmit.skills();
        if (list.isEmpty()) { state.setNotice("当前没有可用技能"); return; }
        pickIndex = 0;
        pickingSkill = true;
    }

    /** 技能选择器按键：↑↓/kj 移动、数字快选、Enter 挂载、Esc 取消。始终 HANDLED。 */
    private EventResult onSkillPickerKey(KeyEvent k) {
        List<SkillInfo> list = onSubmit.skills();
        int n = list.size();
        if (k.isCancel()) { pickingSkill = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {
            if (k.isChar((char) ('1' + i))) { pickIndex = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            SkillInfo chosen = list.get(pickIndex);
            pendingSkill = chosen.name();
            pickingSkill = false;
            state.setNotice("已挂载 " + chosen.name() + " · 下条消息生效");
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    /** 技能选择器面板：标题 + 每个技能一行（❯ 高亮、名字 + 来源层 + 暗色描述）。 */
    private Element[] skillPickerChildren() {
        List<SkillInfo> list = onSubmit.skills();
        List<Element> els = new ArrayList<>();
        els.add(text("  选择技能（↑↓ 选择 · Enter 挂载 · Esc 取消）").style(PICK_TITLE));
        for (int i = 0; i < list.size(); i++) {
            SkillInfo s = list.get(i);
            boolean sel = i == pickIndex;
            String marker = sel ? "❯ " : "  ";
            els.add(text("  " + marker + (i + 1) + ". " + s.name() + "  [" + s.source() + "]   " + s.description())
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }
```

（`SkillInfo` 已在 Task 无关的既有 import 中——文件顶部已 `import com.example.springai.codetui.agent.SkillInfo;`。若缺失则补。）

- [ ] **Step 7: 发送路径消费挂载 —— `dispatch` 重载 + 入队带技能**

把现有 `dispatch(String text)` 方法替换为「带技能重载 + 无技能委托」：

```java
    /** 真正发起一个回合：提交给 agent。仅当模型与上次不同时才打一行「⚙ 使用模型 X」（首个回合也会打）。 */
    private void dispatch(String text) { dispatch(text, null); }

    private void dispatch(String text, String skill) {
        current = onSubmit.submit(text, skill);
        String m = onSubmit.currentModel();
        if (!m.equals(lastShownModel)) {
            state.pushInfo("⚙ 使用模型 " + m);
            lastShownModel = m;
        }
    }
```

把 `submitInput()` 结尾的忙碌/发送段（当前）：

```java
        inputState.clear();
        if (state.isBusy()) {                        // 忙或压缩中：排队，不打断/不并发
            state.enqueue(text, null);
            return;
        }
        dispatch(text);
```

替换为（消费 `pendingSkill`，一次性）：

```java
        inputState.clear();
        String skill = pendingSkill;                 // 一次性：本条消息取走挂载
        pendingSkill = null;
        if (state.isBusy()) {                        // 忙或压缩中：排队，挂载随消息入队
            state.enqueue(text, skill);
            return;
        }
        dispatch(text, skill);
```

把 Task 4 Step 6b 里 drain 的出队改为携带技能：

```java
            ConversationState.Queued next = state.pollQueued();
            if (next != null) dispatch(next.text(), next.skill());
```

- [ ] **Step 8: 状态栏显示挂载 + Esc 清挂载**

在 `statusLine()` 方法开头 `if (pickingModel) ...` 之后加技能选择器提示，并在 IDLE 前插挂载提示。具体：

把 `statusLine()` 中：

```java
        if (pickingModel) return text("↑↓/kj 选择 · 1-9 快选 · Enter 确认 · Esc 取消").style(THINK);
```
之后加：
```java
        if (pickingSkill) return text("↑↓/kj 选择 · 1-9 快选 · Enter 挂载 · Esc 取消").style(THINK);
```

在 `String notice = state.notice(); if (!notice.isEmpty()) ...` 这一段**之后**、`return switch (state.status())` **之前**加挂载提示（仅空闲态展示，避免盖住思考/工具态）：

```java
        if (pendingSkill != null && state.isIdle()) {
            return text("已挂载技能 " + pendingSkill + " · 下条消息生效 · Esc 取消挂载").style(THINK);
        }
```

Esc 清挂载：在 `onInputKey` 的 `if (k.isCancel()) {` 分支**最开头**（进入取消回合逻辑之前）加：

```java
        if (k.isCancel() && pendingSkill != null && state.isIdle()) {
            pendingSkill = null;
            state.setNotice("已取消技能挂载");
            return EventResult.HANDLED;
        }
```

> 定位：该判断要放在现有 `if (k.isCancel()) { boolean running = ...; }` 这个大分支**之前**，确保挂载态下的 Esc 优先清挂载、不误取消回合。

- [ ] **Step 9: 编译 + 全量回归**

Run: `cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -pl springai-code-tui test 2>&1 | grep -E "Tests run: [0-9]+, Fail.*Skipped: [0-9]+$|BUILD" | tail -2`
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 10: 手动冒烟（需要 DEEPSEEK_API_KEY + 至少一个技能）**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo && mvn -q -pl springai-code-tui -am package -DskipTests
mkdir -p .codetui/skills/git-commit-message
cp springai-agent-demo/skills/git-commit-message/SKILL.md .codetui/skills/git-commit-message/SKILL.md
java -jar springai-code-tui/target/springai-code-tui.jar
```
手动核对：
1. 输入 `/skill` → 弹出选择器，列出 `git-commit-message [项目]`。
2. Enter 挂载 → 状态栏显示「已挂载技能 git-commit-message · 下条消息生效」。
3. 打「帮我写这次改动的提交信息」+ Enter → **界面出现一行 Skill 工具活动**（`⏺ 运行 Skill …`），随后模型按规范作答。
4. 再发一条普通消息 → 不再注入、无技能挂载提示（一次性已消费）。
5. 挂载后按 Esc（空闲态）→ 挂载清除、提示「已取消技能挂载」。
清理：`rm -rf .codetui/skills/git-commit-message`（若不想留测试技能）。

- [ ] **Step 11: 提交**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java
git commit -m "feat(code-tui): /skill 选择器 + 一次性挂载（发送前注入技能，UI 显示工具调用）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 更新设计文档状态 + `/help` 一致性检查

**Files:**
- Modify: `docs/superpowers/specs/2026-07-03-manual-skill-selection-design.md`

- [ ] **Step 1: 标记 spec 状态为已实现**

把文档头 `状态：设计草案，待用户评审` 改为：

```markdown
状态：**v1 已实现并通过测试**（新增 CodingAgentSkillInjectTest / ConversationStateQueueTest；/skill 选择器手动冒烟通过）
```

- [ ] **Step 2: 提交**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add docs/superpowers/specs/2026-07-03-manual-skill-selection-design.md
git commit -m "docs(code-tui): 标记手动指定技能设计为已实现

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 收尾：合并回 main

全部任务完成、全量测试绿后：

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git checkout main
git merge --no-ff <当前分支> -m "Merge: 手动指定技能（/skill 选择器）"
```

（是否合并/删分支由用户决定——参考前几次交互，合并前先确认。）
