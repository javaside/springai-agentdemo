# AskUserQuestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 接入 `AskUserQuestionTool`——模型执行中可反问用户（单选/多选/「其他」自定义文本，一次 1–4 问），用户在内联面板作答后模型据答案续跑本回合。

**Architecture:** 工具的 `QuestionHandler.handle()` 在**工具线程**同步阻塞，等 **UI 线程**喂答案——核心是一个 `ArrayBlockingQueue(1)` 跨线程握手桥 `UserQuestionBridge`。桥把 Spring AI 的 `Question` 翻译成纯 Java `QuestionSpec` 经既有 `AgentListener` 接缝交给 `ConversationState`（`pendingAsk`），`CodeTuiView` 弹出模态作答面板；答完回调 `answer(map)` 唤醒工具线程。Esc 取消整回合，复用现有 `doOnCancel` 会话回滚（清悬空 `assistant(tool_calls)`，不 400）。

**Tech Stack:** Java 21（records/sealed）、Spring AI 2.0（`AskUserQuestionTool`/`ToolCallback`/`ToolContext`）、spring-ai-agent-utils 0.10.0、TamboUI 0.4.0（InlineApp）、JUnit 5、Maven、pty+pyte（实机冒烟）。

---

## 约定

- **构建/测试命令**（仓库根目录执行）：
  - 单类测试：`mvn -q -pl springai-code-tui -Dtest=<ClassName> test`
  - 模块全测（排除联网 spike）：`mvn -q -pl springai-code-tui -Dtest='!CodingAgentSpikeTest' test`
  - 打包（pty 冒烟前）：`mvn -q -pl springai-code-tui -DskipTests package`
- **分支**：先建 `feat/ask-user-question`，全程在此分支提交；完成后 `--no-ff` 合并 `main`（仓库惯例，见 CLAUDE 记忆）。
- **包路径**：`agent` = `com.example.springai.codetui.agent`；`ui` = `com.example.springai.codetui.ui`。
- **Jackson**：本仓库用 Jackson 3.x（`tools.jackson.*`），本计划的握手桥不需要 Jackson。
- **提交信息尾行**：以 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` 结尾。

## 文件结构（先锁定边界）

**新增（agent 包）：**
- `QuestionSpec.java` — 纯 Java 问题记录（`question/header/options/multiSelect`）
- `OptionSpec.java` — 纯 Java 选项记录（`label/description`）
- `AskResponder.java` — UI→桥 的回调接口（`answer(Map)` / `cancel()`）
- `AskRequest.java` — 一次问询句柄（`turnId + List<QuestionSpec> + AskResponder`）
- `QuestionCancelledException.java` — 取消时桥抛出的 RuntimeException
- `UserQuestionBridge.java` — 实现 `AskUserQuestionTool.QuestionHandler`：翻译 + 阻塞握手

**修改：**
- `agent/AgentListener.java` — 加 `void onQuestionAsked(long turnId, AskRequest request)`
- `ui/ConversationState.java` — 落地 `pendingAsk`（存/读/清 + 迟到过滤）
- `agent/AgentTools.java` — 建桥、建 `AskUserQuestionTool`、装饰进 `defaultTools` + 系统提示补一句
- `ui/CodeTuiView.java` — 作答面板状态机（单选→多选→自由文本）+ 按键路由 + 渲染 + Esc 联动
- `test/.../agent/StubListener.java` — 补 `onQuestionAsked` 空实现

**新增测试：**
- `agent/UserQuestionBridgeTest.java`、`ui/ConversationStateAskTest.java`、`ui/CodeTuiViewAskTest.java`

---

## Task 0: 建分支

- [ ] **Step 1: 从 main 建特性分支**

Run:
```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git checkout -b feat/ask-user-question
```
Expected: `Switched to a new branch 'feat/ask-user-question'`

---

## Task 1: 纯 Java 数据模型 + 接缝

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/OptionSpec.java`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/QuestionSpec.java`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AskResponder.java`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AskRequest.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java`
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java`
- Modify: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/StubListener.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateAskTest.java`

- [ ] **Step 1: 写四个纯 Java 记录/接口**

`OptionSpec.java`：
```java
package com.example.springai.codetui.agent;

/** 一个选项：展示 label + 说明（AskUserQuestion 的 Option 的纯 Java 镜像，避免 Spring AI 类型泄漏进接缝）。 */
public record OptionSpec(String label, String description) {}
```

`QuestionSpec.java`：
```java
package com.example.springai.codetui.agent;

import java.util.List;

/** 一个问题：文本 + header chip + 选项 + 是否多选（AskUserQuestion 的 Question 的纯 Java 镜像）。 */
public record QuestionSpec(String question, String header, List<OptionSpec> options, boolean multiSelect) {
    public QuestionSpec {
        options = List.copyOf(options);   // 防御性拷贝，保持不可变
    }
}
```

`AskResponder.java`：
```java
package com.example.springai.codetui.agent;

import java.util.Map;

/**
 * UI → 握手桥 的应答回调（纯 Java，不泄漏 Spring AI 类型）。
 * UI 答完调 {@link #answer}，键=问题文本、值=选中 label（多选逗号分隔 / 自由文本）；取消调 {@link #cancel}。
 * 二者都只会被调用一次，用于唤醒阻塞在桥里的工具线程。
 */
public interface AskResponder {
    void answer(Map<String, String> answers);
    void cancel();
}
```

`AskRequest.java`：
```java
package com.example.springai.codetui.agent;

import java.util.List;

/**
 * 一次问询句柄：由 {@code UserQuestionBridge} 构造、经 {@link AgentListener#onQuestionAsked} 交给 UI。
 * UI 据 {@link #questions} 渲染作答面板，答完/取消经 {@link #responder} 唤醒工具线程。
 *
 * @param turnId    发起问询的回合（供 UI 迟到过滤）
 * @param questions 顺序问询的问题列表（1–4 个）
 * @param responder UI 应答回调
 */
public record AskRequest(long turnId, List<QuestionSpec> questions, AskResponder responder) {
    public AskRequest {
        questions = List.copyOf(questions);
    }
}
```

- [ ] **Step 2: 给 AgentListener 加接缝方法**

在 `AgentListener.java` 的 `onError` 之后、压缩相关方法之前插入：
```java
    /**
     * 模型经 AskUserQuestionTool 发问：UI 应弹出作答面板并最终经 {@code request.responder()} 应答。
     * 与其它方法一样带 turnId 供迟到过滤。落地端会阻塞工具线程直到 UI 应答（见 UserQuestionBridge）。
     */
    void onQuestionAsked(long turnId, AskRequest request);
```

- [ ] **Step 3: 给测试 StubListener 补空实现**

在 `StubListener.java` 的 `onError` 行后加：
```java
    @Override public void onQuestionAsked(long turnId, AskRequest request) {}
```

- [ ] **Step 4: ConversationState 落地 pendingAsk**

在 `ConversationState.java` 的压缩瞬态字段区（`compactReason` 之后）加字段：
```java
    // ── AskUserQuestion 瞬态：待作答的问询（渲染线程读、工具线程写；迟到过滤后置入） ──
    private volatile AskRequest pendingAsk;
```
`import com.example.springai.codetui.agent.AskRequest;`（与既有 `AgentListener` import 同区）。

在 AgentListener 落地端（`onError` 实现之后）加实现：
```java
    @Override
    public void onQuestionAsked(long turnId, AskRequest request) {
        if (turnId != acceptingTurnId) {
            // 迟到：回合已被取消/切换。桥侧的 take() 靠取消路径唤醒，这里直接丢弃不弹面板。
            request.responder().cancel();
            return;
        }
        this.pendingAsk = request;
    }
```

在状态读取区（`activeToolSummary()` 附近）加读/清方法：
```java
    /** 当前待作答的问询（无则 null）；渲染线程读。 */
    public AskRequest pendingAsk() { return pendingAsk; }
    /** 清除待作答问询（UI 答完/取消后调）。 */
    public void clearPendingAsk() { this.pendingAsk = null; }
```

- [ ] **Step 5: 写失败测试**

`ConversationStateAskTest.java`：
```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AskRequest;
import com.example.springai.codetui.agent.AskResponder;
import com.example.springai.codetui.agent.OptionSpec;
import com.example.springai.codetui.agent.QuestionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 对 AskRequest 的落地：正常回合存入、迟到回合丢弃并 cancel、clear 清空。 */
class ConversationStateAskTest {

    private static AskRequest req(long turnId, AtomicBoolean cancelled) {
        AskResponder r = new AskResponder() {
            @Override public void answer(Map<String, String> a) {}
            @Override public void cancel() { cancelled.set(true); }
        };
        QuestionSpec q = new QuestionSpec("选哪个?", "选择",
                List.of(new OptionSpec("A", "第一"), new OptionSpec("B", "第二")), false);
        return new AskRequest(turnId, List.of(q), r);
    }

    @Test
    void onQuestionAsked_storesForCurrentTurn() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(7);                       // acceptingTurnId = 7
        AskRequest r = req(7, new AtomicBoolean());
        s.onQuestionAsked(7, r);
        assertSame(r, s.pendingAsk(), "当前回合的问询应被存入 pendingAsk");
    }

    @Test
    void onQuestionAsked_dropsAndCancelsLateTurn() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(9);                       // 当前回合是 9
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(8, req(8, cancelled));  // 迟到回合 8
        assertNull(s.pendingAsk(), "迟到回合不应弹面板");
        assertTrue(cancelled.get(), "迟到问询应被 cancel 以唤醒工具线程");
    }

    @Test
    void clearPendingAsk_resets() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        s.onQuestionAsked(1, req(1, new AtomicBoolean()));
        s.clearPendingAsk();
        assertNull(s.pendingAsk());
    }
}
```

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=ConversationStateAskTest test`
Expected: PASS（3 项）

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/OptionSpec.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/QuestionSpec.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/AskResponder.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/AskRequest.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/StubListener.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/ConversationStateAskTest.java
git commit -m "feat(ask): AskUserQuestion 纯 Java 模型 + AgentListener 接缝 + ConversationState.pendingAsk

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 握手桥 UserQuestionBridge

**Files:**
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/QuestionCancelledException.java`
- Create: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/UserQuestionBridge.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/UserQuestionBridgeTest.java`

- [ ] **Step 1: 写取消异常**

`QuestionCancelledException.java`：
```java
package com.example.springai.codetui.agent;

/** 用户取消了问询（Esc）。桥从阻塞中被唤醒后抛出；此时回合已被 dispose，本异常随流被丢弃。 */
public class QuestionCancelledException extends RuntimeException {
    public QuestionCancelledException() { super("user cancelled the question"); }
}
```

- [ ] **Step 2: 写握手桥**

`UserQuestionBridge.java`：
```java
package com.example.springai.codetui.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

/**
 * AskUserQuestionTool 的 QuestionHandler：把工具线程 ↔ UI 线程之间的一次问询变成阻塞握手。
 *
 * <p><b>为何阻塞</b>：{@code handle()} 在工具调用线程上同步执行、必须返回完整答案 map 才算工具执行完毕。
 * 本桥把问询经 {@link AgentListener#onQuestionAsked} 交给 UI，然后阻塞在一次性队列 {@code take()} 上，
 * 直到 UI 经 {@link AskResponder#answer} 喂回答案（或 {@link AskResponder#cancel} 取消）。
 *
 * <p><b>turnId</b>：{@code handle()} 在被 {@link ToolEventCallback} 装饰的工具 call 内同线程同步触发，
 * 故直接读 {@link ToolEventCallback#currentTurnId()}（与 TodoWriteTool 的 handler 同款）。
 *
 * <p><b>取消</b>：cancel 经队列投递哨兵 {@link #CANCEL}，{@code handle()} 唤醒后抛
 * {@link QuestionCancelledException}。此时回合已被 dispose、会话经 doOnCancel 回滚，异常随流被丢弃。
 *
 * <p><b>答案完整性</b>：UI 顺序问询、答完全部问题才 answer，故返回 map 覆盖所有问题文本，
 * {@code AskUserQuestionTool} 的 answersValidation 不会触发 InvalidUserAnswerException。
 */
public final class UserQuestionBridge implements AskUserQuestionTool.QuestionHandler {

    /** 取消哨兵（身份比较）。 */
    private static final Object CANCEL = new Object();

    private final AgentListener listener;

    public UserQuestionBridge(AgentListener listener) {
        this.listener = listener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> handle(List<Question> questions) {
        long turnId = ToolEventCallback.currentTurnId();
        List<QuestionSpec> specs = translate(questions);
        ArrayBlockingQueue<Object> handoff = new ArrayBlockingQueue<>(1);
        AskResponder responder = new AskResponder() {
            @Override public void answer(Map<String, String> a) { handoff.offer(a); }
            @Override public void cancel() { handoff.offer(CANCEL); }
        };
        listener.onQuestionAsked(turnId, new AskRequest(turnId, specs, responder));
        Object result;
        try {
            result = handoff.take();                 // 阻塞工具线程直到 UI 应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuestionCancelledException();
        }
        if (result == CANCEL) {
            throw new QuestionCancelledException();
        }
        return (Map<String, String>) result;
    }

    /** Spring AI 的 Question/Option → 纯 Java QuestionSpec/OptionSpec（剥离 Spring AI 类型，接缝纪律）。 */
    static List<QuestionSpec> translate(List<Question> questions) {
        List<QuestionSpec> out = new ArrayList<>(questions.size());
        for (Question q : questions) {
            List<OptionSpec> opts = new ArrayList<>();
            for (Option o : q.options()) {
                opts.add(new OptionSpec(o.label(), o.description()));
            }
            boolean multi = q.multiSelect() != null && q.multiSelect();
            out.add(new QuestionSpec(q.question(), q.header(), opts, multi));
        }
        return out;
    }
}
```

- [ ] **Step 3: 写失败测试**

`UserQuestionBridgeTest.java`：
```java
package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 握手桥：另起线程调 handle 阻塞，主线程经捕获的 responder 应答/取消。不联网、确定性。 */
class UserQuestionBridgeTest {

    private static Question q() {
        return new Question("选哪个?", "选择",
                List.of(new Option("A", "第一"), new Option("B", "第二")), false);
    }

    /** 捕获 onQuestionAsked 传来的 AskRequest 的 listener。 */
    private static final class CaptureListener extends TestListener {
        final AtomicReference<AskRequest> captured = new AtomicReference<>();
        final CountDownLatch asked = new CountDownLatch(1);
        @Override public void onQuestionAsked(long turnId, AskRequest request) {
            captured.set(request);
            asked.countDown();
        }
    }

    @Test
    void handle_returnsAnswersFromResponder() throws Exception {
        CaptureListener listener = new CaptureListener();
        UserQuestionBridge bridge = new UserQuestionBridge(listener);
        AtomicReference<Map<String, String>> ret = new AtomicReference<>();
        Thread tool = new Thread(() -> ret.set(bridge.handle(List.of(q()))), "tool");
        tool.start();

        assertTrue(listener.asked.await(2, TimeUnit.SECONDS), "handle 应触发 onQuestionAsked");
        AskRequest req = listener.captured.get();
        assertNotNull(req);
        assertEquals(1, req.questions().size());
        assertEquals("选哪个?", req.questions().get(0).question());

        req.responder().answer(Map.of("选哪个?", "A"));
        tool.join(2000);
        assertEquals(Map.of("选哪个?", "A"), ret.get(), "handle 应返回 responder 喂入的答案");
    }

    @Test
    void handle_throwsOnCancel() throws Exception {
        CaptureListener listener = new CaptureListener();
        UserQuestionBridge bridge = new UserQuestionBridge(listener);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try { bridge.handle(List.of(q())); fail("应抛 QuestionCancelledException"); }
            catch (Throwable t) { err.set(t); }
        }, "tool");
        tool.start();

        assertTrue(listener.asked.await(2, TimeUnit.SECONDS));
        listener.captured.get().responder().cancel();
        tool.join(2000);
        assertInstanceOf(QuestionCancelledException.class, err.get());
    }

    @Test
    void translate_stripsSpringTypes() {
        List<QuestionSpec> specs = UserQuestionBridge.translate(List.of(
                new Question("多选?", "特性",
                        List.of(new Option("X", "x"), new Option("Y", "y")), true)));
        assertEquals(1, specs.size());
        QuestionSpec s = specs.get(0);
        assertEquals("多选?", s.question());
        assertEquals("特性", s.header());
        assertTrue(s.multiSelect());
        assertEquals(List.of("X", "Y"), s.options().stream().map(OptionSpec::label).toList());
    }
}
```

- [ ] **Step 4: 写 TestListener 基类（若不存在则新建）**

包 `agent` 下测试用的完整 AgentListener 空实现（`StubListener` 是包私有、Task 1 已存在——直接复用它而非新建）。把上面 `CaptureListener extends TestListener` 改为 `extends StubListener`，删除本步。

> 说明：`StubListener` 已是 `agent` 包内的 AgentListener 空实现，测试直接 `extends StubListener` 即可，无需 `TestListener`。**执行时：把 Step 3 里的 `extends TestListener` 改成 `extends StubListener`。**

- [ ] **Step 5: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=UserQuestionBridgeTest test`
Expected: PASS（3 项）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/QuestionCancelledException.java \
        springai-code-tui/src/main/java/com/example/springai/codetui/agent/UserQuestionBridge.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/UserQuestionBridgeTest.java
git commit -m "feat(ask): UserQuestionBridge 阻塞握手桥（ArrayBlockingQueue + 取消抛异常）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 装配 AgentTools + 系统提示

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java`（追加一个断言）

- [ ] **Step 1: 写失败测试（AskUserQuestionTool 在回调集合里）**

先看 `AgentRuntimeTest.java` 已有的构造/断言风格（它已验证 `runtime.client()` 等）。追加：
```java
    @Test
    void build_registersAskUserQuestionTool() {
        // build 装配的 ChatClient 应含名为 "AskUserQuestionTool" 的工具回调。
        // 通过反射/公开途径拿不到 defaultTools 时，改为断言 build 不抛 + 工具名常量存在。
        // 这里用最稳的方式：AgentTools 暴露一个包私有钩子返回工具名集合（见 Step 2）。
        java.util.List<String> names = AgentTools.toolNamesForTest(model(), tempRoot(), new StubListener());
        org.junit.jupiter.api.Assertions.assertTrue(names.contains("AskUserQuestionTool"),
                "应注册 AskUserQuestionTool，实际：" + names);
    }
```
> 复用 `AgentRuntimeTest` 里已有的 `model()` / `tempRoot()` 辅助（若命名不同，按该文件现有辅助方法名调整）。若该文件无此类辅助，用其现有构造 runtime 的方式取工具名。

- [ ] **Step 2: 在 AgentTools 建桥、建工具、装饰、进 defaultTools**

在 `AgentTools.java` 顶部 import 区加：
```java
import org.springaicommunity.agent.tools.AskUserQuestionTool;
```

在 `build(...)` 内、构造 `SkillCatalog.Loaded skills = SkillCatalog.load(root);` 之后、拼 `all` 列表之前，加：
```java
        // 反问工具：QuestionHandler 阻塞工具线程、等 UI 经 onQuestionAsked → responder 应答（见 UserQuestionBridge）。
        AskUserQuestionTool askTool = AskUserQuestionTool.builder()
                .questionHandler(new UserQuestionBridge(listener))
                .answersValidation(true)   // UI 顺序问询保证答案完整，故校验开着也不会触发异常
                .build();
```

把 `all` 列表构造改为把 `askTool` 一并纳入 `ToolCallbacks.from(...)`（它是 `@Tool` 对象）：
```java
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(fs, sh, grep, glob, todo, webFetch, askTool)));
```
（Skill 工具仍单独 `all.add(skills.tool())`，随后统一 `ToolEventCallback` 装饰的循环不变——`AskUserQuestionTool` 也会被装饰，故「⏺ AskUserQuestionTool」一行工具活动可见、且 `currentTurnId()` 对桥可用。）

- [ ] **Step 3: 系统提示补一句引导**

在 `SYSTEM_TEMPLATE` 的「关于 Skill 的那条」之后、`- 回答简洁` 之前加一行：
```java
            - 当需求含糊、或需要用户在多个实现方案之间拍板时，用 AskUserQuestionTool 向用户提问（一次 1–4 个问题，
              每问 2–4 个选项，可单选或多选）；不要在信息不足时自行臆测方向。
```

- [ ] **Step 4: 加包私有测试钩子 toolNamesForTest**

在 `AgentTools` 里加（紧邻 `build` 之后）：
```java
    /** 测试钩子：返回 build 会注册的工具名集合（不发网络请求）。 */
    static List<String> toolNamesForTest(DeepSeekChatModel model, Path root, AgentListener listener) {
        AgentRuntime rt = build(model, root, listener);
        // ChatClient 不暴露工具列表；改为直接重建同一组工具名。保持与 build 内一致：
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();
        ShellTools sh = ShellTools.builder().build();
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        TodoWriteTool todo = TodoWriteTool.builder().build();
        ChatClient aux = ChatClient.builder(model).build();
        SmartWebFetchTool webFetch = SmartWebFetchTool.builder(aux).domainSafetyCheck(false).build();
        AskUserQuestionTool ask = AskUserQuestionTool.builder()
                .questionHandler(new UserQuestionBridge(listener)).build();
        List<String> names = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(fs, sh, grep, glob, todo, webFetch, ask)) {
            names.add(c.getToolDefinition().name());
        }
        return names;
    }
```
> 若 `AgentRuntimeTest` 已能直接断言工具（例如 build 暴露了工具集合），优先用现成途径、删掉此钩子。此钩子仅为让 Step 1 测试可执行的最小手段。

- [ ] **Step 5: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=AgentRuntimeTest test`
Expected: PASS（含新增 `build_registersAskUserQuestionTool`）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/agent/AgentRuntimeTest.java
git commit -m "feat(ask): 装配 AskUserQuestionTool 进 ChatClient + 系统提示引导

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: UI 作答面板（单选）

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java`

**背景**：`CodeTuiView` 用 `pickingModel`/`pickingSkill` 这套模态：字段标志 + `onXxxKey` 拦截 + `render()` 里 `scope(...)` 面板 + 状态行提示。本任务照抄这套结构做「作答模态」，先只做**单选**。

- [ ] **Step 1: 加作答状态字段**

在 `CodeTuiView.java` 字段区（`pendingSkill` 附近）加：
```java
    private com.example.springai.codetui.agent.AskRequest activeAsk; // 当前正在作答的问询（null=非作答态）
    private int askQ;          // 当前问题下标
    private int askOpt;        // 当前问题内高亮的选项下标
    private final java.util.Map<String, String> askAnswers = new java.util.HashMap<>(); // 已答问题→答案
```
（同文件已 `import com.example.springai.codetui.agent.SkillInfo;`，这里对 `AskRequest`/`QuestionSpec`/`OptionSpec` 用全限定名或补 import，二选一，保持文件风格。）

- [ ] **Step 2: drain() 里侦测新问询、初始化作答态**

在 `drain()` 方法末尾（自动出队那段**之前**）加：
```java
        // 侦测到新问询（身份不同）→ 进入作答态并复位到第一问。
        com.example.springai.codetui.agent.AskRequest pa = state.pendingAsk();
        if (pa != null && pa != activeAsk) {
            activeAsk = pa;
            askQ = 0; askOpt = 0; askAnswers.clear();
        }
```

- [ ] **Step 3: render() 里加作答面板 scope**

在 `render()` 的 `column(...)` 里，`scope(pickingSkill, skillPickerChildren())` 之后加一行：
```java
                scope(activeAsk != null, askChildren()),            // AskUserQuestion 作答面板
```

- [ ] **Step 4: onInputKey 顶部路由到作答态**

在 `onInputKey(...)` 里、`if (k.isCtrlC())` 块之后、`if (pickingModel)` 之前加：
```java
        if (activeAsk != null) return onAskKey(k);   // 作答模态：全部按键交给它，屏蔽文本编辑
```

- [ ] **Step 5: 写 onAskKey（单选）+ askChildren + 收尾方法**

在 `CodeTuiView` 的 `/skill` 相关方法附近加：
```java
    // ── AskUserQuestion 作答面板 ─────────────────────────────────────────
    /** 作答按键（单选）：↑↓/kj 移动高亮、1–9 移到第 n 项（不隐式确认）、Enter 选中进下一问、Esc 取消整回合。 */
    private EventResult onAskKey(KeyEvent k) {
        List<com.example.springai.codetui.agent.QuestionSpec> qs = activeAsk.questions();
        com.example.springai.codetui.agent.QuestionSpec q = qs.get(askQ);
        int n = q.options().size();
        if (k.isCancel()) { cancelAsk(); return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { askOpt = (askOpt - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { askOpt = (askOpt + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {
            if (k.isChar((char) ('1' + i))) { askOpt = i; return EventResult.HANDLED; }   // 仅移动高亮
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            askAnswers.put(q.question(), q.options().get(askOpt).label());   // 记本问答案（单选=label）
            advanceOrFinish();
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /** 本问答完：还有下一问则前进（复位高亮），否则提交全部答案唤醒工具线程。 */
    private void advanceOrFinish() {
        if (askQ + 1 < activeAsk.questions().size()) {
            askQ++; askOpt = 0;
            return;
        }
        com.example.springai.codetui.agent.AskRequest req = activeAsk;
        java.util.Map<String, String> answers = new java.util.HashMap<>(askAnswers);
        clearAskState();
        req.responder().answer(answers);   // 唤醒阻塞的工具线程
    }

    /** Esc 取消：唤醒工具线程 + 取消整回合（复用既有回合取消 → doOnCancel 回滚会话，不 400）。 */
    private void cancelAsk() {
        com.example.springai.codetui.agent.AskRequest req = activeAsk;
        clearAskState();
        req.responder().cancel();
        if (current != null) { current.dispose(); current = null; }
        state.cancelCurrent();
        state.clearQueued();
        state.setNotice("已取消当前回合");
    }

    /** 清作答态并从 state 摘除 pendingAsk（避免 drain 再次进入）。 */
    private void clearAskState() {
        activeAsk = null; askQ = 0; askOpt = 0; askAnswers.clear();
        state.clearPendingAsk();
    }

    /** 作答面板：进度 + header + 问题文本 + 逐项选项（单选 ❯ 高亮）。 */
    private Element[] askChildren() {
        List<com.example.springai.codetui.agent.QuestionSpec> qs = activeAsk.questions();
        com.example.springai.codetui.agent.QuestionSpec q = qs.get(askQ);
        List<Element> els = new ArrayList<>();
        String progress = qs.size() > 1 ? "（第 " + (askQ + 1) + "/" + qs.size() + " 问）" : "";
        els.add(text("  ❓ [" + q.header() + "] " + q.question() + progress).style(PICK_TITLE));
        for (int i = 0; i < q.options().size(); i++) {
            com.example.springai.codetui.agent.OptionSpec o = q.options().get(i);
            boolean sel = i == askOpt;
            els.add(text("  " + (sel ? "❯ " : "  ") + (i + 1) + ". " + o.label() + "   " + o.description())
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }
```

- [ ] **Step 6: 状态行提示**

在 `statusLine()` 顶部（`if (pickingModel)` 之前）加：
```java
        if (activeAsk != null) return text("↑↓/kj 选择 · Enter 确认 · Esc 取消").style(THINK);
```

- [ ] **Step 7: 写失败测试（单选推进 + 多问 + Esc 取消）**

`CodeTuiViewAskTest.java`：
```java
package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AskRequest;
import com.example.springai.codetui.agent.AskResponder;
import com.example.springai.codetui.agent.OptionSpec;
import com.example.springai.codetui.agent.QuestionSpec;
import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作答面板（单选）纯状态断言：不起真实 TUI，直接喂 KeyEvent 给 View 的按键入口。
 * 用测试子类暴露 protected/private 交互点（同包可直接访问包私有；private 经测试专用 hook）。
 */
class CodeTuiViewAskTest {

    /** 暴露按键入口 + 注入 pendingAsk 的测试子类。 */
    static final class TestView extends CodeTuiView {
        TestView(ConversationState s) { super(s, (SubmitHandler) text -> null, Path.of(".")); }
        // onInputKey / drain 是 private；用包私有转发（在 CodeTuiView 加 test-only 方法，见 Step 8）。
    }

    private static AskRequest ask(AtomicReference<Map<String, String>> got, AtomicBoolean cancelled, QuestionSpec... q) {
        AskResponder r = new AskResponder() {
            @Override public void answer(Map<String, String> a) { got.set(a); }
            @Override public void cancel() { cancelled.set(true); }
        };
        return new AskRequest(1, List.of(q), r);
    }

    private static QuestionSpec single(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "选择", os, false);
    }

    @Test
    void singleSelect_enterRecordsLabelAndFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(1, ask(got, cancelled, single("选哪个?", "A", "B", "C")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();                              // = drain 侦测进入作答态
        v.feedKeyForTest(KeyEvent.of(KeyCode.DOWN));  // 高亮 A→B
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER)); // 选 B
        assertEquals(Map.of("选哪个?", "B"), got.get());
        assertNull(s.pendingAsk(), "答完应清除 pendingAsk");
    }

    @Test
    void multiQuestion_advancesThenFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(),
                single("Q1?", "A", "B"), single("Q2?", "X", "Y")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));   // Q1 → A
        v.feedKeyForTest(KeyEvent.of(KeyCode.DOWN));    // Q2 高亮 X→Y
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));   // Q2 → Y
        assertEquals(Map.of("Q1?", "A", "Q2?", "Y"), got.get());
    }

    @Test
    void esc_cancelsAndClears() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, single("选?", "A", "B")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.of(KeyCode.ESC));
        assertTrue(cancelled.get(), "Esc 应触发 responder.cancel");
        assertNull(s.pendingAsk());
    }
}
```

- [ ] **Step 8: 在 CodeTuiView 加两个 test-only 包私有转发方法**

`drain()` 与 `onInputKey(...)` 是 private，测试同包但需稳定入口。加：
```java
    /** 测试专用：跑一次 drain（侦测 pendingAsk 并进入作答态）。 */
    void tickForTest() { drain(); }
    /** 测试专用：把一个按键喂给输入框按键入口（等价真实按键路由）。 */
    EventResult feedKeyForTest(KeyEvent k) { return onInputKey(k); }
```
> 若 `KeyEvent.of(KeyCode.X)` 工厂不存在，按 `CodeTuiViewBindingsTest` 里的构造法（`new KeyEvent(KeyCode.X, KeyModifiers.NONE, ...)` 或 `KeyEvent.ofChar`）调整测试里的按键构造。ESC 对应 `k.isCancel()`——确认 `KeyEvent` 里 ESC 的表示（`KeyCode.ESC`），使 `isCancel()` 为真。

- [ ] **Step 9: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=CodeTuiViewAskTest test`
Expected: PASS（3 项）

- [ ] **Step 10: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java
git commit -m "feat(ask): UI 作答面板（单选 + 多问顺序问询 + Esc 取消整回合）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 多选支持

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java`（追加）

- [ ] **Step 1: 加多选勾选态字段**

在 `CodeTuiView` 字段区加：
```java
    private final java.util.Set<Integer> askChecked = new java.util.LinkedHashSet<>(); // 当前多选问题已勾选的选项下标（保序）
```
在 `drain()` 侦测新问询处与 `advanceOrFinish()` 前进到下一问处，都 `askChecked.clear();`（进入每个新问题时清空）。具体：Step 2 的 drain 初始化块加 `askChecked.clear();`；`advanceOrFinish()` 的 `askQ++` 分支加 `askChecked.clear();`。

- [ ] **Step 2: onAskKey 支持多选（空格勾选 / Enter 确认至少一项）**

把 `onAskKey` 里 Enter 分支之前加多选处理，并改写 Enter 分支：
```java
        // 多选：空格切换当前高亮项勾选
        if (q.multiSelect() && (k.code() == KeyCode.CHAR && k.isChar(' '))) {
            if (!askChecked.remove(askOpt)) askChecked.add(askOpt);
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            if (q.multiSelect()) {
                if (askChecked.isEmpty()) { state.setNotice("至少选择一项"); return EventResult.HANDLED; }
                java.util.List<String> picked = new java.util.ArrayList<>();
                for (int i : askChecked) picked.add(q.options().get(i).label());
                askAnswers.put(q.question(), String.join(", ", picked));   // 多选=逗号分隔（同 Claude Code）
            } else {
                askAnswers.put(q.question(), q.options().get(askOpt).label());
            }
            advanceOrFinish();
            return EventResult.HANDLED;
        }
```
（删掉原来单独的单选 Enter 分支，合并进上面这个。）

- [ ] **Step 3: askChildren 渲染多选勾选框**

把 `askChildren` 里选项行改为按是否多选画 `[x]/[ ]`：
```java
        for (int i = 0; i < q.options().size(); i++) {
            com.example.springai.codetui.agent.OptionSpec o = q.options().get(i);
            boolean sel = i == askOpt;
            String box = q.multiSelect() ? (askChecked.contains(i) ? "[x] " : "[ ] ") : "";
            els.add(text("  " + (sel ? "❯ " : "  ") + box + (i + 1) + ". " + o.label() + "   " + o.description())
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
```

- [ ] **Step 4: 多选状态行提示**

`statusLine()` 的作答分支改为按多选切换提示：
```java
        if (activeAsk != null) {
            boolean multi = activeAsk.questions().get(askQ).multiSelect();
            return text(multi ? "↑↓ 移动 · 空格勾选 · Enter 确认 · Esc 取消"
                              : "↑↓/kj 选择 · Enter 确认 · Esc 取消").style(THINK);
        }
```

- [ ] **Step 5: 追加多选测试**

在 `CodeTuiViewAskTest.java` 加：
```java
    private static QuestionSpec multi(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "特性", os, true);
    }

    @Test
    void multiSelect_spaceTogglesCommaJoined() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "认证", "数据库", "缓存")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar(' '));        // 勾选「认证」(高亮在 0)
        v.feedKeyForTest(KeyEvent.of(KeyCode.DOWN));   // 高亮→数据库
        v.feedKeyForTest(KeyEvent.ofChar(' '));        // 勾选「数据库」
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));  // 确认
        assertEquals(Map.of("要哪些?", "认证, 数据库"), got.get());
    }

    @Test
    void multiSelect_enterWithNoneShowsNoticeAndStays() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "A", "B")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));  // 未勾选
        assertNull(got.get(), "未勾选不应提交");
        assertEquals("至少选择一项", s.notice());
    }
```
> `KeyEvent.ofChar(' ')` 与 `k.isChar(' ')` 的判定要一致（见 `CodeTuiViewBindingsTest` 用 `KeyEvent.ofChar('q')`）。若空格的 `code()` 不是 `KeyCode.CHAR`，把 Step 2 的空格判定改为纯 `k.isChar(' ')`。

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=CodeTuiViewAskTest test`
Expected: PASS（5 项：3 单选 + 2 多选）

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java
git commit -m "feat(ask): 多选（空格勾选 + Enter 确认，逗号分隔答案）

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 「其他」自由文本

**Files:**
- Modify: `springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java`（追加）

**设计**：每个问题的选项列表末尾追加一条合成项「✎ 其他（自定义输入）」，下标 = `options().size()`（真实选项之后）。选中它并 Enter（多选则空格标记后 Enter）→ 进自由文本子模式：复用一个 `TextAreaState` 单行输入，Enter 确认输入文本为答案。**先只对单选做**（多选 + 自由文本组合罕见，YAGNI；多选时「其他」项 Enter 直接把已勾选项 + 自由文本一并提交超出范围，故多选面板不显示「其他」项）。

- [ ] **Step 1: 加自由文本子模式字段**

```java
    private boolean askFreeText;                                     // 自由文本子模式（选了「其他」）
    private final dev.tamboui.widgets.input.TextAreaState askInput = new dev.tamboui.widgets.input.TextAreaState();
    private final dev.tamboui.toolkit.element.Element askInputKeys =
            dev.tamboui.toolkit.Toolkit.textArea(askInput);         // 复用键处理（不渲染，同 inputKeys 手法）
```
在 `clearAskState()` 与 `advanceOrFinish()` 前进分支里加 `askFreeText = false; askInput.clear();`。

- [ ] **Step 2: 合成「其他」项：仅单选显示，下标越界即为「其他」**

在 `askChildren` 的选项循环**之后**（return 之前），单选时追加一行：
```java
        if (!q.multiSelect()) {
            int otherIdx = q.options().size();
            boolean sel = askOpt == otherIdx;
            els.add(text("  " + (sel ? "❯ " : "  ") + "✎ 其他（自定义输入）").style(sel ? PICK_SEL : PICK_DESC));
            if (askFreeText) {
                els.add(text("     ▏" + askInput.text()).style(PICK_TITLE));   // 输入回显
            }
        }
```

- [ ] **Step 3: onAskKey：单选高亮范围含「其他」项；选中它进自由文本子模式**

单选时 `n` 应含「其他」项：把 `onAskKey` 顶部的 `int n = q.options().size();` 改为：
```java
        int n = q.options().size() + (q.multiSelect() ? 0 : 1);   // 单选多一条「其他」
```
在 `onAskKey` 里、`if (k.isCancel())` 之后加自由文本子模式的按键处理（优先于导航）：
```java
        if (askFreeText) {
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                String txt = askInput.text();
                if (txt.isBlank()) { state.setNotice("请输入内容或 Esc 返回"); return EventResult.HANDLED; }
                askAnswers.put(q.question(), txt);
                askFreeText = false; askInput.clear();
                advanceOrFinish();
                return EventResult.HANDLED;
            }
            askInputKeys.handleKeyEvent(k, true);   // 其余键交给（不渲染的）textArea 编辑
            return EventResult.HANDLED;
        }
```
在单选 Enter 分支里，若高亮在「其他」项则进子模式而非记录 label：
```java
        // …单选 Enter 分支内，替换 askAnswers.put(...) 为：
        if (!q.multiSelect() && askOpt == q.options().size()) {   // 选中「其他」
            askFreeText = true; askInput.clear();
            return EventResult.HANDLED;
        }
        askAnswers.put(q.question(), q.options().get(askOpt).label());
        advanceOrFinish();
        return EventResult.HANDLED;
```
> 注意：数字快选 `1–9` 循环上界仍是真实 `options().size()`（不给「其他」编号），保持 Step 5 的数字键循环 `i < q.options().size()`。

- [ ] **Step 4: 自由文本子模式的状态行**

`statusLine()` 作答分支最前面加：
```java
            if (askFreeText) return text("输入自定义内容 · Enter 确认 · Esc 取消回合").style(THINK);
```
> Esc 在自由文本子模式仍走 `cancelAsk()`（取消整回合，与全局 Esc 语义一致）——`onAskKey` 顶部的 `k.isCancel()` 分支已覆盖，无需额外处理。

- [ ] **Step 5: 追加自由文本测试**

```java
    @Test
    void other_entersFreeTextAndSubmitsTyped() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选?", "A", "B")));
        CodeTuiView v = new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.of(KeyCode.DOWN));   // A→B
        v.feedKeyForTest(KeyEvent.of(KeyCode.DOWN));   // B→「其他」
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));  // 进自由文本
        v.feedKeyForTest(KeyEvent.ofChar('C'));
        v.feedKeyForTest(KeyEvent.ofChar('!'));
        v.feedKeyForTest(KeyEvent.of(KeyCode.ENTER));  // 确认
        assertEquals(Map.of("选?", "C!"), got.get());
    }
```
> 若 `askInputKeys.handleKeyEvent` 对单字符的落字方式与 `KeyEvent.ofChar` 不匹配（导致 `askInput.text()` 非 "C!"），改用 `askInput.insert('C')` 风格的直接注入，或在测试里改用 `PasteEvent`/`askInput` 直填后仅测 Enter 分支。以 `askInput.text()` 实际能被这些按键改成 "C!" 为准。

- [ ] **Step 6: 跑测试验证通过**

Run: `mvn -q -pl springai-code-tui -Dtest=CodeTuiViewAskTest test`
Expected: PASS（6 项）

- [ ] **Step 7: 全模块回归（排除联网 spike）**

Run: `mvn -q -pl springai-code-tui -Dtest='!CodingAgentSpikeTest' test`
Expected: 全绿（原有 + 新增）

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/com/example/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/com/example/springai/codetui/ui/CodeTuiViewAskTest.java
git commit -m "feat(ask): 单选「其他」自定义文本子模式

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: pty+pyte 实机冒烟 + 打包

**背景（记忆铁律）**：内联 TUI 改动必须用真实 pty 验证，且改完要重新 package。参考既有 pty 冒烟脚本（此前 `/skill` 前缀碰撞就是这样抓到的）。

**Files:**
- Create: `scratchpad/ask_smoke.py`（pty+pyte 脚本，临时，不进仓库）

- [ ] **Step 1: 打包**

Run: `mvn -q -pl springai-code-tui -DskipTests package`
Expected: BUILD SUCCESS，生成 `springai-code-tui/target/*.jar` 及 `target/lib`。

- [ ] **Step 2: 写 pty 冒烟脚本（桩驱动，不必真连 DeepSeek）**

思路：无法保证真实模型必然调 AskUserQuestion。用一个**最小 main 或测试专用启动**直接把一个 `pendingAsk` 注入 `ConversationState` 后跑 `CodeTuiView`，或用真实 App + 一句强诱导 prompt。优先**真实 App**：
```python
# scratchpad/ask_smoke.py — 在 pty 里跑真实 App，诱导模型发问，验证面板渲染 + 选择
import os, sys, time, pty, select
import pyte

cols, rows = 100, 30
screen = pyte.Screen(cols, rows); stream = pyte.ByteStream(screen)
pid, fd = pty.fork()
if pid == 0:
    os.environ["DEEPSEEK_API_KEY"] = os.environ.get("DEEPSEEK_API_KEY", "")
    os.execvp("mvn", ["mvn", "-q", "-pl", "springai-code-tui",
                      "exec:java", "-Dexec.mainClass=com.example.springai.codetui.CodeTuiApplication"])
def pump(t=0.4):
    end = time.time() + t
    while time.time() < end:
        r,_,_ = select.select([fd], [], [], 0.1)
        if fd in r:
            try: stream.feed(os.read(fd, 65536))
            except OSError: return
def send(b): os.write(fd, b)
def dump(): return "\n".join(screen.display)

pump(20)  # 等启动 + 编译
send("如果你不确定就用 AskUserQuestionTool 问我：这个项目该用 REST 还是 gRPC？给我两个选项。\r")
pump(30)  # 等模型发问
print("=== 面板 ==="); print(dump())
assert "❓" in dump() or "REST" in dump(), "未见作答面板"
send(b"\x1b[B")  # ↓
pump(1)
send(b"\r")      # Enter 选择
pump(20)
print("=== 选择后 ==="); print(dump())
os.write(fd, b"\x03")  # Ctrl+C 退出
```
运行：`DEEPSEEK_API_KEY=$KEY python3 scratchpad/ask_smoke.py`

- [ ] **Step 3: 跑冒烟，人工核对**

Run: `DEEPSEEK_API_KEY=<你的key> python3 scratchpad/ask_smoke.py`
核对：
1. 模型确实调了 AskUserQuestion（scrollback 有「⏺ AskUserQuestionTool」一行）；
2. 输入框上方弹出作答面板（❓ + header + 选项，❯ 高亮随 ↓ 移动）；
3. Enter 选择后回合继续、无 400、无悬空 tool_calls；
4. 另跑一次：面板出现后按 Esc → 显示「已取消当前回合」，再发一条普通消息**不报 400**（验证 doOnCancel 回滚生效）。

> 若模型不肯调工具，改用桩：临时在 `CodeTuiApplication` 加一个 `-Dask.smoke=1` 分支，启动后直接 `state.onTurnStarted(1); state.onQuestionAsked(1, <构造的 AskRequest>)`，只验证面板渲染/选择/Esc 三条 UI 行为（把它作为回退方案，冒烟后删除该分支）。

- [ ] **Step 4: 记录冒烟结论 + 清理**

- 冒烟通过后删除 `scratchpad/ask_smoke.py`（临时文件，不入库）。
- 若发现渲染/按键问题（如底色串行、Esc 不生效、面板不收起），回到对应 Task 修复并**重新 package + 重跑冒烟**。

- [ ] **Step 5: 合并到 main**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git checkout main
git merge --no-ff feat/ask-user-question -m "Merge feat/ask-user-question: AskUserQuestion 反问功能（单选/多选/自定义文本）"
git branch -d feat/ask-user-question
```
> 合并前确认 `mvn -q -pl springai-code-tui -Dtest='!CodingAgentSpikeTest' test` 全绿。**不 push**（仓库为本地状态，push 需另行征询）。

---

## 自检清单（写完计划后回看，已核对）

- **Spec 覆盖**：§0 决策（范围/握手/Esc/多选）→ Task 2/1/4/5；§4 桥 → Task 2；§5 UI（单选/多选/Other/Esc/顺序问询）→ Task 4/5/6；§6 装配 + 系统提示 → Task 3；§8 测试策略（单测 + pty）→ 各 Task + Task 7。全部有对应任务。
- **占位符**：无 TBD/TODO；每个代码步给了完整代码；pty 脚本给了可运行内容。
- **类型/命名一致性**：`AskRequest(turnId, questions, responder)`、`AskResponder.answer/cancel`、`QuestionSpec.question()/header()/options()/multiSelect()`、`OptionSpec.label()/description()`、`ConversationState.pendingAsk()/clearPendingAsk()`、`UserQuestionBridge.translate`、test-only `tickForTest()/feedKeyForTest()` 在各 Task 间一致。
- **已知不确定点（执行时按实际 API 校准，已在步骤内标注）**：
  1. `KeyEvent` 的构造/ESC/空格判定（`KeyEvent.of` / `ofChar` / `isCancel` / `isChar(' ')`）——以 `CodeTuiViewBindingsTest` 现有用法为准。
  2. `TextAreaState` 落字方式（`handleKeyEvent` vs 直接 `insert`）——Task 6 Step 5 已给回退。
  3. `AgentRuntimeTest` 是否已有取工具名的途径——Task 3 Step 4 的 `toolNamesForTest` 钩子为回退，优先用现成途径。
