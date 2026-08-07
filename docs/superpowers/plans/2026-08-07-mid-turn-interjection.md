# 回合中插话 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户在回合进行中输入的消息，不打断回合、随下一次模型调用送达，等待从「剩余整个回合」缩短到「当前这一个工具」。

**Architecture:** 注入挂在 ChatModel 装饰器层（`InterjectingChatModel`），因为合法插入位置只有「所有工具结果之后」，而只有这一层拿得到已配平的完整消息表。队列 `Interjections` 由 `AgentTools.build` 创建，同时交给 ChatClient 装饰链和 `CodingAgent`。回合末由 `CodingAgent` 按 anchor 把插话补进会话历史。

**Tech Stack:** Java 21 · Spring AI 2.0 · `spring-ai-session-management` 0.5.0 · TamboUI · JUnit 5 · pty+pyte 冒烟

**Spec:** `docs/superpowers/specs/2026-08-07-mid-turn-interjection-design.md`

---

## 实施记录（2026-08-07，已全部完成）

14 个任务全部落地，`2443411`..`3c0b366` 共 18 个提交，已并入 `main`。
最终 **1322 个测试全绿**（含 9 skipped），pty 实机冒烟通过。
下方各任务的复选框是**编写计划时**的待办，未回填勾选——以本节为准。

**执行方式与计划有出入**：原计划 13 个任务逐个派 subagent，实际按文件归属合成 4 批
（Task 2 与 3 并行、4+5+6+5b 一批、7–11 一批、12+13 一批）。理由是 Task 7–11 全都改
`CodeTuiView.java` 同一个 2551 行文件，拆开派五个 agent 轮流改，比一个 agent 连着做更慢也更易撞车。

### 计划本身被查出的错（都是执行时 subagent 发现的）

| 错处 | 性质 |
|---|---|
| Task 6 的 `facadeOver()` 替身测试 | **零覆盖**——替身把委托关系又抄了一遍，生产代码委托写反照样全绿 |
| Task 5b 的测试 | **零覆盖**——直接调队列方法，压根没驱动 `clearContext()`，把实现删光也是绿的 |
| Task 11 的 `statusTextForTest()` 钩子 | **零覆盖**——`ijs` 拼进的是 `statusBar.shimmer(...)` 后缀，读 `statusLine()` 返回值测不到 |
| `new ToolResponseMessage(List.of(...))` | 编不过——Spring AI 2.0 里该构造器是 **protected** 且签名不同，须用 builder |
| 「三个单-client 桩构造」 | 数错了，实际只有一个有构造体，其余三个是无体的委托重载 |

前三条是同一类错误：测试写得像模像样，但**把被测实现整个删掉也是绿的**。写计划时凭直觉造测试替身，
没有回过头问「这条断言能杀死什么变异」。

### 变异验证中的坑

两次出现**「变异假存活」**：用 `perl -0pi -e` 改代码时正则没匹配上，代码根本没变就跑出全绿，
差点得出「这条断言没咬住」的错误结论。后续所有变异改完都先 `git diff --stat` 确认真的落盘再跑。

### 验收压在一条断言上

`AgentTools.build` 里 `InterjectingChatModel.wrap()` 那行**没有任何单测覆盖**。实测摘掉它：

- 1322 个单测**一个都不红**
- **插话回显和状态栏计数照样正常显示**（它们读的是 UI 自己的队列，队列只是躺着不动）——肉眼看界面完全正常，功能已经死了
- 只有 pty 冒烟的 `check_delivered_to_model` 会红，它断的是桩模型**实际收到的请求体**

---

## 前置说明（先读，否则会踩）

**已有代码。** 工作树里有三个**未提交**文件，本计划以它们为基础：
`agent/Interjections.java`（103 行）、`agent/InterjectingChatModel.java`（105 行）、
`test/…/MidTurnInjectionProbeTest.java`（294 行）。前两个逻辑完整但**没有任何生产代码引用**，
功能当前不生效。Task 1 之前先 `git add` 它们并提交一次基线，避免后续改动和它们混在一起看不清。

**验证命令一律模块作用域**：`mvn test -pl springai-code-tui -Dtest=…`。
整仓 `mvn test` 会被三个空模块打挂，那不是你的改动导致的。

**共用工作树纪律**：本仓可能有并行 agent 在跑。**绝不 `git commit --amend`、绝不 `git stash`**——
HEAD 随时可能不是你那条，stash 会卷走别人的未提交改动。只能追加新提交。

**`delivered` 存的是原文，不是包裹后的文本。** 三个消费者对文本的要求不同：
模型要包裹后的（带行为指引）、Esc 回填要原文（还给用户重发）、补历史要包裹后的
（与模型看到的逐字一致）。故队列只存原文，包裹由 `InterjectingChatModel.wrapText()`
这一个静态方法产出，注入和补历史都调它——保证两处逐字相同。

---

## File Structure

| 文件 | 责任 | 本次 |
|---|---|---|
| `agent/Interjections.java` | 插话队列 + 送达/锚点记账。无 Spring 依赖，纯状态机 | 补 2 个方法 |
| `agent/InterjectingChatModel.java` | ChatModel 装饰器：注入 + 文本包裹的唯一出处 | 改 1 处、加 1 静态方法 |
| `agent/AgentTools.java` | 装配：创建队列、套装饰器、放进 `AgentRuntime` | 加 3 行 + record 加字段 |
| `agent/CodingAgent.java` | 回合末补历史；`SubmitHandler` 门面 | 加 1 私有方法 + 4 门面方法 + 1 构造重载 |
| `agent/SubmitHandler.java` | UI↔agent 唯一通道 | 加 4 个 default 方法 |
| `ui/CodeTuiView.java` | 路由、`/queue`、Esc 回填、兜底出队、状态栏、回显 | 6 处 |
| `CodeTuiApplication.java` | 把队列从 runtime 传给 agent | 加 1 参数 |

---

## Task 1: 提交基线，并用探针定死补历史的挂载点

**这是唯一的未知数，出结果前不要写后面任何一行。** `handleComplete`（`doOnComplete`）
与 `SessionMemoryAdvisor` 写 assistant 回复，谁先？补历史挂在 `handleComplete` 的前提是
advisor 已经落库；如果反了，插进去的位置会被随后的写入覆盖，Task 5 就得换挂载点。
静态读代码定不了。

**Files:**
- Commit: `agent/Interjections.java`, `agent/InterjectingChatModel.java`, `test/…/MidTurnInjectionProbeTest.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PersistOrderProbeTest.java`

- [ ] **Step 1: 提交现有三个未提交文件作为基线**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/Interjections.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/InterjectingChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MidTurnInjectionProbeTest.java
git commit -m "feat(interjection): 插话队列与 ChatModel 注入装饰器（未接线）

两个类逻辑完整但无任何生产代码引用，功能不生效。接线见后续提交。"
```

- [ ] **Step 2: 写探针，观察回合结束瞬间会话里有什么**

复用 `MidTurnInjectionProbeTest` 里的 `CapturingModel` / `slowTool` 写法（同包，可直接抄）。
关键是在 `AgentListener` 桩的 `onComplete` 回调里立刻读会话事件，看 assistant 收尾消息是否已在。

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.SessionEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 探针（一次性，出结论后删）：回合结束时 handleComplete 与 SessionMemoryAdvisor 落库谁先。
 * 结论决定「补历史」挂在哪，见 spec「动手第一步」。
 */
class PersistOrderProbeTest {

    @Test
    @DisplayName("探针：onComplete 回调触发时，assistant 收尾消息是否已落库")
    void probePersistOrder(@TempDir Path root) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        List<String> typesAtComplete = new CopyOnWriteArrayList<>();

        // 组装一个真实 CodingAgent（照抄 MidTurnInjectionProbeTest 的装配段），
        // listener 的 onComplete 里立刻快照会话事件类型。
        // ↓ 装配细节按该测试现有写法填，此处只标出探针本体
        // AgentListener listener = ... onComplete(turnId) -> {
        //     sessionService.getEvents(sid).forEach(e -> typesAtComplete.add(
        //             e.getMessage().getClass().getSimpleName()));
        //     completed.countDown();
        // }

        assertTrue(completed.await(20, TimeUnit.SECONDS), "回合没结束");
        System.out.println("onComplete 时刻的会话消息类型：" + typesAtComplete);
        assertTrue(typesAtComplete.stream().anyMatch(t -> t.contains("Assistant")),
                "onComplete 时 assistant 尚未落库 → 补历史不能挂 handleComplete，改挂 doFinally 或 advisor 之后");
    }
}
```

- [ ] **Step 3: 跑探针，读结论**

```bash
mvn test -pl springai-code-tui -Dtest=PersistOrderProbeTest
```

两种结果，各自的下一步：

| 结果 | 含义 | Task 5 怎么写 |
|---|---|---|
| PASS（assistant 已落库） | advisor 先于 `doOnComplete` | 补历史挂 `handleComplete` 开头，**在通知 listener 之前**（计划按此写） |
| FAIL（assistant 未落库） | `doOnComplete` 先于 advisor | 改挂 `doFinally`，并**重跑本探针确认**；Task 5 的代码位置随之调整 |

- [ ] **Step 4: 把结论写回 spec，删掉探针**

在 spec 的「动手第一步」一节末尾追加一行实测结论（含日期与观察到的类型序列），
然后 `git rm` 掉 `PersistOrderProbeTest.java`——它是一次性工具，留着会被误当回归测试。

```bash
git add docs/superpowers/specs/2026-08-07-mid-turn-interjection-design.md
git rm springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PersistOrderProbeTest.java
git commit -m "docs(interjection): 探针实测补历史挂载点，结论写回 spec"
```

---

## Task 2: `Interjections` 补两个消费者方法

回合正常结束时 `delivered` 有两个潜在消费者（`handleComplete` 补历史 / UI 兜底出队）。
若合成一个方法，谁先跑到谁拿走——UI 先拿走的话，那条已送达的插话既没进历史，
又被当成新回合**再发一遍**，模型看到同一句话两次。解法是让消费者各取互不重叠的部分。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/Interjections.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/InterjectionsTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterjectionsTest {

    /** 兜底出队只能取未送达的：动了 delivered 就会和补历史抢，同一句话发两遍。 */
    @Test
    @DisplayName("takePendingOnly 取走未送达，且不动 delivered")
    void takePendingOnlyLeavesDelivered() {
        Interjections q = new Interjections();
        q.offer("第一句");
        q.drainForInjection("call-1");     // 第一句送达，进 delivered
        q.offer("第二句");                  // 未送达

        assertEquals(List.of("第二句"), q.takePendingOnly());

        // ⚠ takeForHistory() 会清空 delivered，只能调一次；用返回值做全部断言。
        Interjections.Delivered d = q.takeForHistory().orElse(null);
        assertNotNull(d, "delivered 被 takePendingOnly 顺手清掉了");
        assertEquals("第一句", d.text());
        assertEquals("call-1", d.anchorToolCallId());
    }

    /** Esc 要通吃：模型看过、历史没有、用户也拿不回来的话，那句话就凭空消失了。 */
    @Test
    @DisplayName("drainForRefill 同时交还已送达与未送达，已送达在前")
    void drainForRefillReturnsDeliveredToo() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");     // 送达
        q.offer("后说的");

        assertEquals(List.of("先说的", "后说的"), q.drainForRefill());
        assertTrue(q.takeForHistory().isEmpty(), "drainForRefill 之后 delivered 应已清空");
        assertEquals(0, q.pendingCount());
    }

    /** 回填给用户的是原话，不是 [interjection] 包裹后的文本。 */
    @Test
    @DisplayName("drainForRefill 交还的是原文")
    void refillIsRawText() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        q.drainForInjection("call-1");

        assertEquals(List.of("改用方案 B"), q.drainForRefill());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectionsTest
```

期望：编译失败，`找不到符号: 方法 takePendingOnly()`。

- [ ] **Step 3: 实现**

在 `Interjections` 里新增 `takePendingOnly()`，并改写 `drainForRefill()`：

```java
    /**
     * 只取未送达的（回合<b>正常结束</b>时 UI 兜底出队用）。
     *
     * <p><b>刻意不动 {@code delivered}</b>：那一条归 {@link #takeForHistory} 补历史。两者若共用一个
     * 方法，回合末谁先跑到谁拿走——UI 先拿走的话，已送达的插话既没进历史，又被当成新回合再发一遍，
     * 模型会看到同一句话两次。
     */
    public synchronized List<String> takePendingOnly() {
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /**
     * Esc 取消回合时把插话取走——调用方<b>回填输入框</b>，不是丢弃。
     *
     * <p>按 Esc 通常正是「别跑了，听我的」，那句话不该跟着一起没。旧的排队队列在 Esc 时
     * 直接 {@code clearQueued()}，用户得重打一遍。
     *
     * <p><b>连已送达的一起交还</b>：那条模型已经看过，但回合被取消 → {@code handleComplete} 不会跑
     * （取消走 {@code doOnCancel}）→ 没人补它进历史。不交还的话它就凭空消失：模型看过、历史没有、
     * 用户也拿不回来。取消路径与补历史天然不撞车，故这里通吃是安全的。
     *
     * <p>已送达的排在前面——它在时序上更早。交还的是<b>原文</b>，不是 {@code [interjection]}
     * 包裹后的文本：用户要的是自己那句话，好改一改重发。
     */
    public synchronized List<String> drainForRefill() {
        List<String> out = new ArrayList<>();
        if (delivered != null) {
            out.add(delivered);
        }
        out.addAll(pending);
        pending.clear();
        delivered = null;
        anchorToolCallId = null;
        return out;
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectionsTest
```

期望：3 个测试全 PASS。

- [ ] **Step 5: 变异验证——这三条断言不是假绿**

逐个改回去跑一次，确认**每个变异都有测试挂**（一次只改一处，改完立刻还原）：

| 变异 | 应挂的测试 |
|---|---|
| `takePendingOnly` 里加一行 `delivered = null;` | `takePendingOnlyLeavesDelivered` |
| `drainForRefill` 去掉 `if (delivered != null) out.add(delivered);` | `drainForRefillReturnsDeliveredToo` |
| `drainForRefill` 里把 delivered 加到 `out` **末尾**而非开头 | `drainForRefillReturnsDeliveredToo`（断言的是有序 List） |

三个变异有任何一个跑出全绿，说明对应断言没咬住，先修测试再继续。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/Interjections.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/InterjectionsTest.java
git commit -m "feat(interjection): 队列区分「只取未送达」与「Esc 通吃」两个消费者

合成一个方法的话，回合末补历史与 UI 兜底出队会抢同一条 delivered，
结果是同一句话发两遍。"
```

---

## Task 3: `InterjectingChatModel` 包裹插话文本

裸 user 消息追在 tool 结果之后，模型很可能判定「上一轮结束了，这是新任务」——
丢下没做完的工具循环去做新事，或者提前收尾。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/InterjectingChatModel.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/InterjectingChatModelTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

注意断言**索引**而不是「包含」：只断言消息表里含插话文本的话，
`merged.add(0, ...)` 这种变异照样过。

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterjectingChatModelTest {

    private static final class Spy implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        final ChatOptions options = ToolCallingChatOptions.builder().build();

        @Override public ChatOptions getOptions() { return options; }
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
    }

    private static Prompt promptEndingWithToolResult() {
        return new Prompt(List.of(
                new UserMessage("原始提问"),
                new AssistantMessage("", java.util.Map.of(), List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "someTool", "{}"))),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "someTool", "工具结果")))));
    }

    /** 插话必须落在所有工具结果之后——落在 assistant(tool_calls) 与 tool 之间就是悬空 tool_calls，下一次请求 400。 */
    @Test
    @DisplayName("插话追加在消息表末尾，紧跟 tool 结果")
    void injectsAfterToolResults() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        Spy spy = new Spy();

        ChatModel wrapped = InterjectingChatModel.wrap(spy, q);
        wrapped.call(promptEndingWithToolResult());

        List<Message> msgs = spy.seen.get().getInstructions();
        assertEquals(4, msgs.size(), "应恰好多出一条插话");
        assertInstanceOf(ToolResponseMessage.class, msgs.get(2), "插话前一条必须是 tool 结果");
        assertInstanceOf(UserMessage.class, msgs.get(3), "插话必须是最后一条 user 消息");
    }

    /** 包裹是为了别让模型把插话误判成「上一轮结束了，这是新任务」。 */
    @Test
    @DisplayName("插话被 [interjection] 包裹，且与 wrapText 逐字相同")
    void injectedTextIsWrapped() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        Spy spy = new Spy();

        InterjectingChatModel.wrap(spy, q).call(promptEndingWithToolResult());

        List<Message> msgs = spy.seen.get().getInstructions();
        String text = ((UserMessage) msgs.get(3)).getText();
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"), text,
                "补历史要调同一个 wrapText，两处必须逐字一致");
        assertTrue(text.startsWith("[interjection]"));
        assertTrue(text.endsWith("[/interjection]"));
        assertTrue(text.contains("改用方案 B"));
    }

    /** 队列空时不能凭空多一条消息，也不该新建 Prompt。 */
    @Test
    @DisplayName("队列为空时原样放行")
    void emptyQueueIsNoOp() {
        Spy spy = new Spy();
        Prompt original = promptEndingWithToolResult();

        InterjectingChatModel.wrap(spy, new Interjections()).call(original);

        assertSame(original, spy.seen.get(), "无插话时应原样转发，不重建 Prompt");
    }

    /** 忘了转发 getOptions() 会让 toolCallbacks 被静默丢弃，模型看不到任何工具。这个项目踩过。 */
    @Test
    @DisplayName("getOptions 转发到底层")
    void forwardsOptions() {
        Spy spy = new Spy();
        assertSame(spy.options, InterjectingChatModel.wrap(spy, new Interjections()).getOptions());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectingChatModelTest
```

期望：编译失败，`wrapText` 不存在；且 `InterjectingChatModel` 当前是包私有 `final class`，
测试在同包内，可见性没问题。

- [ ] **Step 3: 实现包裹**

在 `InterjectingChatModel` 里加常量与 `wrapText`，并改 `inject()` 用它：

```java
    static final String OPEN = "[interjection]";
    static final String CLOSE = "[/interjection]";

    /**
     * 给模型的行为指引。没有它，模型看到 tool 结果之后突然冒出一条 user 消息，很可能判定
     * 「上一轮结束了，这是新任务」——丢下没做完的工具循环去做新事，或者提前收尾。
     */
    private static final String GUIDE = """
            用户在任务执行中插话，未完成的工作仍在进行中。
            若与当前方向冲突就调整，否则先把手头的做完。""";

    /**
     * 包裹插话文本。沿用 {@code FileReference} 的方括号成对标签约定。
     *
     * <p><b>唯一出处</b>：注入（本类）与回合末补历史（{@code CodingAgent}）都调这个方法。
     * 两处若各写各的，落库文本与模型实际看到的就会有出入，而这种不一致<b>不会报错</b>——
     * 只会在 {@code -c} 恢复后表现为「历史里的话和模型当时的反应对不上」。
     *
     * <p>指引与原话之间用 {@code ---} 分隔，避免模型把指引当成用户说的。
     */
    static String wrapText(String raw) {
        return OPEN + "\n" + GUIDE + "\n---\n" + raw + "\n" + CLOSE;
    }
```

`inject()` 里那一行改成：

```java
        merged.add(new UserMessage(wrapText(text.get())));
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectingChatModelTest
```

期望：4 个测试全 PASS。

- [ ] **Step 5: 变异验证**

| 变异 | 应挂的测试 |
|---|---|
| `merged.add(...)` → `merged.add(0, ...)` | `injectsAfterToolResults` |
| `wrapText(text.get())` → `text.get()`（不包裹） | `injectedTextIsWrapped` |
| `getOptions()` 改成 `return ToolCallingChatOptions.builder().build();` | `forwardsOptions` |
| `inject()` 里 `text.isEmpty()` 分支改成照样 new Prompt | `emptyQueueIsNoOp` |

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/InterjectingChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/InterjectingChatModelTest.java
git commit -m "feat(interjection): 插话用 [interjection] 包裹并附行为指引

裸 user 消息追在 tool 结果之后，模型容易判定「上一轮结束了」而提前收尾。
wrapText 是唯一出处，补历史调同一个方法保证逐字一致。"
```

---

## Task 4: 接线——创建队列、套装饰器、传到 `CodingAgent`

`CodingAgent` 拿到的是**已经建好的 `ChatClient`**，不是 `ChatModel`，所以包装点不在它内部，
而在 `AgentTools.build` 里按 provider 建 ChatClient 的那个循环（`AgentTools.java:509-538`）。
子 agent 走 `SubagentRunner.java:197` 的 `RetryingChatModel.wrap()`，是另一条链，**不包**。

**Files:**
- Modify: `agent/AgentTools.java:508-543`（创建队列 + wrap + record 加字段）
- Modify: `agent/CodingAgent.java`（加字段 + 一个构造重载）
- Modify: `CodeTuiApplication.java:114-119`（传参）

- [ ] **Step 1: `AgentTools.build` 创建队列并套上装饰器**

在 provider 循环**之前**创建（一个实例供所有 provider 共用——插话与用哪家模型无关）：

```java
        // 插话队列：UI 忙时投递，ChatModel 装饰层在下一次调用时取走随 prompt 送达。
        // 所有 provider 共用一个实例——插话与用哪家模型无关，切模型不该把没送出去的话弄丢。
        Interjections interjections = new Interjections();
```

循环里把 `ChatClient.builder(visionModel)` 改成：

```java
            // 插话注入包在<b>最外层</b>：位置必须在整条 advisor 链下游，才拿得到已配平的完整消息表
            // （工具结果落库与「构建下一次 prompt」是同一步，会话存储层看不到这个位置）。
            // 若将来主 agent 也用上 RetryingChatModel，本层必须仍在它<b>外面</b>——反了的话重试时
            // 队列已被第一次尝试排空，插话会在一次网络抖动后静默消失。
            ChatClient c = ChatClient.builder(InterjectingChatModel.wrap(visionModel, interjections))
```

`AgentRuntime` record 末尾加一个组件，`build` 的 `return` 相应补上 `interjections`：

```java
                               BackgroundTaskRegistry backgroundRegistry,
                               TaskResultStore backgroundResults,
                               Interjections interjections) {
```

```java
        return new AgentRuntime(clients, registry.active().id(), sessionService, sessionRepository,
                manualStrategy, tokenCountEstimator, reloadableSkill.skills(), decoratedSkillTool,
                reloadableSkill, subagentRunner, fileExternalizer, permissionEngine, visionModels,
                backgroundRegistry, backgroundResults, interjections);
```

- [ ] **Step 2: `CodingAgent` 加字段与构造重载**

字段（跟在 `backgroundResults` 后面）：

```java
    /** 可空（测试桩）：插话队列，与 ChatClient 装饰链共用同一实例；null 时插话相关方法全 no-op。 */
    private final Interjections interjections;
```

现有那个 18 参构造（`CodingAgent.java:233`）**保留并降级为向后兼容重载**，委托时补 `null`；
新增一个 19 参的真构造。这加深了已有的望远镜结构（8 层重载），但这正是本类的既定模式
（每层都写着「向后兼容重载（无 X：测试桩用）」），另起炉灶反而不一致：

```java
    /** 向后兼容重载（无插话队列：测试桩用）。等价 interjections=null——插话门面全 no-op、回合末不补历史。 */
    public CodingAgent(ProviderRegistry registry, java.util.Map<String, ChatClient> clientsByProvider,
                       AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills,
                       ToolCallback skillTool, SessionRepository sessionRepository,
                       ReloadableSkillTool reloadableSkill, SubagentRunner subagentRunner,
                       SessionFileExternalizer fileExternalizer, McpRegistry mcpRegistry,
                       PermissionEngine permissionEngine,
                       java.util.Map<String, VisionMaterializingChatModel> visionModels,
                       BackgroundTaskRegistry backgroundRegistry,
                       TaskResultStore backgroundResults) {
        this(registry, clientsByProvider, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, sessionRepository, reloadableSkill, subagentRunner,
                fileExternalizer, mcpRegistry, permissionEngine, visionModels,
                backgroundRegistry, backgroundResults, null);
    }
```

真构造在原 18 参构造体的基础上加末参 `Interjections interjections`，并在体内赋值
`this.interjections = interjections;`。**另外三个单-client 桩构造体里也要补
`this.interjections = null;`**——`final` 字段每条构造路径都得赋值，漏一条编译就挂。

- [ ] **Step 3: `CodeTuiApplication` 传参**

`CodeTuiApplication.java:114-119` 的 `new CodingAgent(...)` 末尾补一个实参：

```java
                    runtime.backgroundRegistry(), runtime.backgroundResults(), runtime.interjections());
```

- [ ] **Step 4: 编译**

```bash
mvn -q -pl springai-code-tui compile
```

期望：BUILD SUCCESS。若报「可能尚未初始化变量 interjections」，是 Step 2 里漏了某条构造路径的赋值。

- [ ] **Step 5: 跑全模块测试，确认没打挂既有用例**

```bash
mvn test -pl springai-code-tui
```

期望：全绿。改了公开构造签名，任何直接 new `CodingAgent` 的测试都可能受影响——
它们应该都走的是短重载，不受影响；若有失败，是漏改的调用点。

> **覆盖度说明（诚实交代）**：`AgentTools.build` 这条装配线**没有单测**——它要真实 provider
> 才跑得起来。Step 1 的正确性由 Task 12 的 pty 冒烟兜底（真进程里插话能送达 = 装饰器确实套上了）。
> 不要为了「有测试」而给 build 造一堆 mock provider，那种测试只证明 mock 接得上。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "feat(interjection): 接线——队列建在 AgentTools，装饰器包在最外层

CodingAgent 拿到的是已建好的 ChatClient 而非 ChatModel，故包装点在
AgentTools 按 provider 建 client 的循环里。子 agent 那条链不包。"
```

---

## Task 5: 回合末按 anchor 补历史

不补的话插话只活在那一次 `Prompt` 里。落盘后 `-c` 恢复、以及压缩之后，历史上就会出现
一次「无来由的转向」——模型照着一句不存在的话改了方向，后来的人和后来的模型都看不懂。

> **先确认 Task 1 的探针结论**。若探针显示 assistant 尚未落库，本任务的挂载点要从
> `handleComplete` 改到 `doFinally`，下面的代码位置随之调整（逻辑不变）。

**Files:**
- Modify: `agent/CodingAgent.java:808-810`（`handleComplete`）+ 新增两个私有方法 + 一个 Logger
- Test: `test/…/agent/InterjectionHistoryTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

**anchor 定位的测试必须让成功路径与兜底路径结构上可区分。** 成功路径是「插在 anchor 那条
tool 之后」，兜底是「追加末尾」。若构造的会话里 anchor tool 就在末尾，两条路径产出**完全相同**
的历史——故意破坏 anchor 查找的变异根本杀不掉，测试全绿。所以会话必须造成
`user → assistant(tool_calls) → tool(anchor) → assistant(收尾)`。

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class InterjectionHistoryTest {

    /** 造一条「anchor 不在末尾」的历史：末尾还有 assistant 收尾，故「插在 anchor 后」≠「追加末尾」。 */
    private static List<SessionEvent> history(String sid) {
        List<SessionEvent> out = new ArrayList<>();
        out.add(ev(sid, new UserMessage("原始提问")));
        out.add(ev(sid, AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "someTool", "{}"))).build()));
        out.add(ev(sid, new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "someTool", "工具结果")))));
        out.add(ev(sid, new AssistantMessage("收工")));
        return out;
    }

    private static SessionEvent ev(String sid, Message m) {
        return SessionEvent.builder().sessionId(sid).message(m).build();
    }

    @Test
    @DisplayName("插话插在 anchor 那条 tool 之后，不是追加末尾")
    void insertsAfterAnchorNotAtTail() {
        List<SessionEvent> events = history("s1");

        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                events, "s1", "call-1", "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(ToolResponseMessage.class, out.get(2).getMessage());
        assertInstanceOf(UserMessage.class, out.get(3).getMessage(), "插话应在 tool 之后");
        assertInstanceOf(AssistantMessage.class, out.get(4).getMessage(), "收尾 assistant 仍在最后");
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"),
                out.get(3).getMessage().getText(), "落库文本须与模型看到的逐字一致");
    }

    @Test
    @DisplayName("anchor 为 null 时追加到末尾")
    void nullAnchorAppendsToTail() {
        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                history("s1"), "s1", null, "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(UserMessage.class, out.get(4).getMessage());
    }

    @Test
    @DisplayName("anchor 找不到时追加到末尾（被 sanitize 裁掉的情形）")
    void unknownAnchorAppendsToTail() {
        List<SessionEvent> out = CodingAgent.insertInterjectionForTest(
                history("s1"), "s1", "call-已被裁掉", "改用方案 B");

        assertEquals(5, out.size());
        assertInstanceOf(UserMessage.class, out.get(4).getMessage());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectionHistoryTest
```

期望：编译失败，`insertInterjectionForTest` 不存在。

- [ ] **Step 3: 实现**

`CodingAgent` 顶部加 Logger（本类目前没有；`InterjectingChatModel` 已有先例）：

```java
    private static final Logger log = LoggerFactory.getLogger(CodingAgent.class);
```

新增纯函数 + 落库方法：

```java
    /**
     * 把插话插进事件表：anchor 那条 tool 事件之后；anchor 为 null 或找不到 → 追加末尾。
     *
     * <p>追加末尾会让历史以 {@code UserMessage} 结尾，看着危险，但下个回合
     * {@link #foldTrailingUserIntoOutbound} 正好接住这个形状、把它折进出站文本，
     * 不会变成两条连续 user 被 DeepSeek 400 拒收。<b>不需要为此新写保护。</b>
     *
     * <p>纯函数，不碰仓库——便于单测直接断言位置（见 {@code InterjectionHistoryTest}）。
     */
    static List<SessionEvent> insertInterjectionForTest(List<SessionEvent> events, String sid,
                                                        String anchorToolCallId, String rawText) {
        List<SessionEvent> out = new ArrayList<>(events);
        out.add(insertIndex(events, anchorToolCallId),
                SessionEvent.builder().sessionId(sid)
                        .message(new UserMessage(InterjectingChatModel.wrapText(rawText)))
                        .build());
        return out;
    }

    /** anchor 那条 tool 事件的下一个位置；anchor 为 null 或找不到 → 末尾。 */
    private static int insertIndex(List<SessionEvent> events, String anchorToolCallId) {
        if (anchorToolCallId == null) {
            return events.size();
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).getMessage() instanceof ToolResponseMessage trm
                    && trm.getResponses().stream().anyMatch(r -> anchorToolCallId.equals(r.id()))) {
                return i + 1;
            }
        }
        return events.size();
    }

    /**
     * 回合末把「已送达模型、但尚未落库」的插话补进会话历史。
     *
     * <p><b>必须在通知 listener 之前调用。</b>UI 的出队钩子靠 {@code !busy()} 放行，而 state 回 IDLE
     * 由 listener 事件驱动。放到通知之后，新回合的 {@code SessionMemoryAdvisor.before()} 会先写入
     * 新 user，本方法随后按 anchor 插入就会错位。<b>重排这两行不会报任何错</b>，只会在
     * {@code -c} 恢复后表现为消息顺序离奇。
     *
     * <p>{@code sid} 必须快照：本方法跑在 reactive 线程，{@code /clear} 会在 UI 线程换掉
     * volatile {@code sessionId}（纪律同 {@link #trimDanglingToolCalls}）。
     *
     * <p>失败静默跳过：回合已经成功完成，不该因为一句插话没归档就变成错误。
     */
    private void persistInterjection() {
        if (interjections == null || sessionService == null || sessionRepository == null) {
            return;
        }
        Interjections.Delivered d = interjections.takeForHistory().orElse(null);
        if (d == null) {
            return;
        }
        String sid = sessionId;
        try {
            List<SessionEvent> out = insertInterjectionForTest(
                    sessionService.getEvents(sid), sid, d.anchorToolCallId(), d.text());
            sessionRepository.replaceEvents(sid, List.copyOf(out));
        } catch (RuntimeException ex) {
            log.debug("插话补历史失败，跳过（回合已成功完成）", ex);
        }
    }
```

`handleComplete` 改成两行，**顺序不能反**：

```java
    private void handleComplete(long turnId) {
        persistInterjection();          // ⚠ 必须在 onTurnComplete 之前，见该方法注释
        listener.onTurnComplete(turnId);
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=InterjectionHistoryTest
```

期望：3 个测试全 PASS。

- [ ] **Step 5: 变异验证**

| 变异 | 应挂的测试 |
|---|---|
| `insertIndex` 里 `return i + 1;` → `return i;` | `insertsAfterAnchorNotAtTail`（插到 tool 之前） |
| `insertIndex` 直接 `return events.size();`（无视 anchor） | `insertsAfterAnchorNotAtTail` |
| `insertInterjectionForTest` 里去掉 `wrapText`，直接用 `rawText` | `insertsAfterAnchorNotAtTail` 的最后一条断言 |

第一、二条变异若跑出全绿，说明测试数据里 anchor 恰好在末尾——回去看
Step 1 的 `history()` 是不是被简化掉了收尾 assistant。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/InterjectionHistoryTest.java
git commit -m "feat(interjection): 回合末按 anchor 把插话补进会话历史

不补的话插话只活在那一次 Prompt 里，-c 恢复和压缩后历史上会出现无来由的转向。
persistInterjection 必须排在 onTurnComplete 之前，否则新回合的 advisor 先写入会错位。"
```

---

## Task 6: `SubmitHandler` 门面 + `CodingAgent` 实现

`CodeTuiView` 只通过 `SubmitHandler` 跟 agent 说话（构造参数就 `state` / `onSubmit` / `root`），
这个接口本来就有近 20 个 default 方法在管后台任务、MCP、技能、权限。加四个同构方法不引入新概念。

**Files:**
- Modify: `agent/SubmitHandler.java`
- Modify: `agent/CodingAgent.java`
- Test: `test/…/agent/CodingAgentInterjectionFacadeTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingAgentInterjectionFacadeTest {

    /** 桩路径（interjections==null）必须全 no-op，不能 NPE——大量既有测试走的是短构造。 */
    @Test
    @DisplayName("SubmitHandler 默认实现全 no-op")
    void defaultsAreNoOp() {
        SubmitHandler h = text -> () -> {};

        h.interject("随便说点");                                  // 不应抛
        assertEquals(0, h.pendingInterjections());
        assertEquals(List.of(), h.takePendingInterjections());
        assertEquals(List.of(), h.takeBackInterjections());
    }

    /** 兜底出队与 Esc 回填取的是不同的东西——门面不能把两者委托到同一个队列方法。 */
    @Test
    @DisplayName("takePending 只取未送达，takeBack 通吃")
    void facadeDelegatesToDistinctConsumers() {
        Interjections q = new Interjections();
        q.offer("先说的");
        q.drainForInjection("call-1");   // 送达
        q.offer("后说的");

        SubmitHandler pending = facadeOver(q);
        assertEquals(List.of("后说的"), pending.takePendingInterjections(),
                "兜底出队取到了已送达的那条 → 会和补历史抢，同一句话发两遍");
        assertTrue(q.takeForHistory().isPresent(), "delivered 应仍在，留给补历史");
    }

    /** 用一个最小门面替身验证委托方向；真实实现是 CodingAgent，装配它需要一整套依赖。 */
    private static SubmitHandler facadeOver(Interjections q) {
        return new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return () -> {}; }
            @Override public void interject(String text) { q.offer(text); }
            @Override public int pendingInterjections() { return q.pendingCount(); }
            @Override public List<String> takePendingInterjections() { return q.takePendingOnly(); }
            @Override public List<String> takeBackInterjections() { return q.drainForRefill(); }
        };
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodingAgentInterjectionFacadeTest
```

期望：编译失败，`SubmitHandler` 没有 `interject` 等方法。

- [ ] **Step 3: 实现**

`SubmitHandler` 加四个 default 方法：

```java
    /** 忙时提交一条插话：不打断回合，随下一次模型调用送达。默认无操作（桩）。 */
    default void interject(String text) { }

    /** 尚未送达模型的插话条数（状态栏用）。 */
    default int pendingInterjections() { return 0; }

    /**
     * 取走<b>尚未送达</b>的插话，供回合末兜底出队。
     *
     * <p>刻意不取已送达的那条——它归回合末补历史。两者若共用一个方法，回合末谁先跑到谁拿走，
     * UI 先拿走的话那句话会被当成新回合再发一遍，模型看到两次。
     */
    default List<String> takePendingInterjections() { return List.of(); }

    /** Esc 取消回合时取走<b>全部</b>插话（含已送达的），调用方回填输入框而非丢弃。 */
    default List<String> takeBackInterjections() { return List.of(); }
```

`CodingAgent` 实现（`interjections` 为 null 时全部退化为无操作）：

```java
    @Override
    public void interject(String text) {
        if (interjections != null) {
            interjections.offer(text);
        }
    }

    @Override
    public int pendingInterjections() {
        return interjections == null ? 0 : interjections.pendingCount();
    }

    @Override
    public List<String> takePendingInterjections() {
        return interjections == null ? List.of() : interjections.takePendingOnly();
    }

    @Override
    public List<String> takeBackInterjections() {
        return interjections == null ? List.of() : interjections.drainForRefill();
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodingAgentInterjectionFacadeTest
```

期望：2 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentInterjectionFacadeTest.java
git commit -m "feat(interjection): SubmitHandler 插话门面

takePending 与 takeBack 必须是两个方法：兜底出队只能取未送达的，
Esc 才通吃已送达的。合并就是「同一句话发两遍」。"
```

---

## Task 7: `CodeTuiView` 路由——Enter 默认插话，三条回落

**Files:**
- Modify: `ui/CodeTuiView.java:1241-1247`
- Test: `test/…/ui/CodeTuiViewInterjectionRouteTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

**反向断言要为正确的理由失败。** 测「压缩中不走插话」时，输入必须满足除目标外的全部前置条件：
`state` 为 IDLE **且** `compacting == true`。图省事构造纯 IDLE 的 state 的话，`busy()` 也是 false，
走的是 `dispatch` 而不是 `enqueue`，测试会因为一个完全无关的原因变绿。

```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyCode;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeTuiViewInterjectionRouteTest {

    private static final class Handler implements SubmitHandler {
        final List<String> interjected = new ArrayList<>();
        final List<String> submitted = new ArrayList<>();
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        @Override public Disposable submit(String text) { submitted.add(text); return () -> {}; }
        @Override public Disposable submit(String text, String skill) { return submit(text); }
        @Override public boolean hasInFlightSubagents() { return inFlight.get(); }
        @Override public void interject(String text) { interjected.add(text); }
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) {
            v.feedKeyForTest(KeyEvent.ofChar(c));
        }
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("回合在飞时 Enter 走插话，不入排队队列")
    void turnInFlightRoutesToInterjection() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // 回合在飞：!isIdle()

        type(v, "改用方案 B");

        assertEquals(List.of("改用方案 B"), h.interjected);
        assertEquals(0, s.queuedCount(), "回合在飞时不应进老队列");
    }

    /** 压缩中没有在跑的模型循环，插话进去石沉大海——必须回落老队列。 */
    @Test
    @DisplayName("压缩中回落老队列")
    void compactingFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onCompactionStarted("手动");            // IDLE 但 compacting ⇒ busy()==true

        type(v, "改用方案 B");

        assertEquals(List.of(), h.interjected, "压缩中不该走插话——不会再有模型调用");
        assertEquals(1, s.queuedCount());
    }

    /** 回合已被 Esc 取消、只剩子 agent 在收尾：同样不会再调模型。 */
    @Test
    @DisplayName("仅子 agent 在飞时回落老队列")
    void onlySubagentsInFlightFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.cancelCurrent();
        h.inFlight.set(true);                     // IDLE 但有在飞子 agent ⇒ busy()==true

        type(v, "改用方案 B");

        assertEquals(List.of(), h.interjected);
        assertEquals(1, s.queuedCount());
    }

    /** 空闲时照旧立即起回合，插话路径不该抢它。 */
    @Test
    @DisplayName("空闲时仍走 dispatch")
    void idleStillDispatches() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "新任务");

        assertEquals(List.of("新任务"), h.submitted);
        assertEquals(List.of(), h.interjected);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionRouteTest
```

期望：`turnInFlightRoutesToInterjection` FAIL（`interjected` 为空，消息进了 `queuedCount`）；
其余三个可能已经 PASS——它们断言的是现状行为，是防回归用的。

- [ ] **Step 3: 实现**

`CodeTuiView.java:1241-1246` 那个 `if (busy())` 块改成：

```java
        if (busy()) {                              // 忙/压缩中/有在飞子 agent
            // 插话 vs 排队：只有「回合在飞」才会再有模型调用，插话才送得出去。
            // 压缩中、以及回合已被 Esc 取消只剩子 agent 收尾（两者 state 都已回 IDLE）
            // 都不会再调模型——插话进去会一直躺在队列里，直到用户下次发消息才被捎走。
            // 带技能挂载的也不能走插话：插话是纯 UserMessage，带不了 submit 的第二参数，会静默丢技能。
            if (!state.isIdle() && skill == null) {
                onSubmit.interject(effective);
                // 回显<b>原话</b>（不是 effective：附件已被渲染成一大段 FileReference 文本）。
                // ⚠ 不写「待送达」之类的状态：内联 TUI 打印进 scrollback 的行改不了，
                // 送达之后那行会永远停在错的状态上。送达与否交给状态栏的实时计数。
                state.pushInfo("› " + text);
                return;
            }
            // 入队的是注入后的文本：出队时输入框早已换成别的内容，再想兑现附件无从谈起。
            state.enqueue(effective, skill);         // 反馈靠状态行的实时「已排队 N 条」
            return;
        }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionRouteTest
```

期望：4 个测试全 PASS。

- [ ] **Step 5: 变异验证**

| 变异 | 应挂的测试 |
|---|---|
| 判据 `!state.isIdle()` → `busy()` | `compactingFallsBackToQueue` + `onlySubagentsInFlightFallsBackToQueue` |
| 去掉 `&& skill == null` | Task 8 的技能回落测试（本任务测不到，见下） |
| `interject(effective)` → 整个 if 块删掉 | `turnInFlightRoutesToInterjection` |

> 若第一条变异跑出全绿，说明两个回落测试的 state 没真的落进「IDLE 但 busy」那个窗口——
> 回去检查 `onCompactionStarted` / `inFlight` 是否真的生效了。

- [ ] **Step 6: 补技能回落的测试**

在同一个测试类里追加（`pendingSkill` 需经 `/skill` 挂载，故用已有的技能挂载路径驱动；
若该路径在测试中不可达，改为断言 `submit(text, skill)` 的 skill 参数非 null 亦可）：

```java
    /** 带技能挂载走插话会静默丢技能——插话是纯 UserMessage，带不了 submit 的第二参数。 */
    @Test
    @DisplayName("带技能挂载时回落老队列")
    void skillMountedFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);
        v.mountSkillForTest("brainstorming");     // 见下方说明

        type(v, "帮我想想");

        assertEquals(List.of(), h.interjected, "带技能不该走插话，会丢技能");
        assertEquals(1, s.queuedCount());
    }
```

`mountSkillForTest` 若不存在，在 `CodeTuiView` 里加一个包私有测试钩子
（与既有的 `feedKeyForTest` / `tickForTest` 同纪律）：

```java
    /** 测试钩子：直接挂载技能，绕开 /skill 选择器。 */
    void mountSkillForTest(String skill) { this.pendingSkill = skill; }
```

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInterjectionRouteTest.java
git commit -m "feat(interjection): 忙时 Enter 默认走插话，三条回落

判据用 !state.isIdle() 而非 busy()：压缩中和仅子 agent 在飞都不会再调模型，
插话进去石沉大海。带技能挂载走插话会静默丢技能。"
```

---

## Task 8: `/queue` 显式排到下回合

不用 `Alt+Enter`：`CodeTuiView.java:838-841` 里 `Shift/Alt+Enter` 已经是输入框换行，
且那行注释自己写着「Apple Terminal 等区分不了」——在区分不了修饰键的终端上 `Alt+Enter`
到达时**就是裸 Enter**，用户以为排了队实际走了插话，静默错路由。斜杠命令是纯文本判定，
与终端无关，单测就能覆盖。

**Files:**
- Modify: `ui/CodeTuiView.java:194`（命令清单）+ 斜杠命令分支区
- Test: `test/…/ui/CodeTuiViewInterjectionRouteTest.java`（追加）

- [ ] **Step 1: 写失败的测试**

```java
    /** /queue 明确要求排到下回合，即使回合在飞也不插话。 */
    @Test
    @DisplayName("/queue 强制排队，不插话")
    void queueCommandForcesEnqueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // 回合在飞，默认本会走插话

        type(v, "/queue 等你忙完再看这个");

        assertEquals(List.of(), h.interjected, "/queue 不该走插话");
        assertEquals(1, s.queuedCount());
        assertEquals(List.of("等你忙完再看这个"), s.queuedSnapshot(), "命令前缀不该进消息正文");
    }

    /** 空参数没有意义，给提示而不是排一条空消息。 */
    @Test
    @DisplayName("/queue 不带内容时给提示")
    void bareQueueCommandShowsNotice() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        type(v, "/queue");

        assertEquals(0, s.queuedCount());
        assertEquals("用法：/queue <消息> — 排到下一回合再发", s.notice());
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionRouteTest
```

期望：两个新测试 FAIL——`/queue …` 当前会被当成普通文本走插话。

- [ ] **Step 3: 实现**

命令清单（`CodeTuiView.java:194` 附近，与 `/compact` `/clear` 并列）：

```java
            new SlashCommand("/queue",   "排到下一回合再发（默认 Enter 是立即插话）"),
```

斜杠命令分支区加一支（放在 `/help` 分支附近即可；`String cmd = text.strip()` 是整条去空白文本，
`slashMenuActive()` 要求不含空格，故 `/queue 内容` 不会误开补全菜单）：

```java
        if (cmd.equals("/queue") || cmd.startsWith("/queue ")) {
            String body = cmd.length() > "/queue".length() ? cmd.substring("/queue".length()).strip() : "";
            if (body.isEmpty()) {
                // 不 clearInput()：用户多半是想接着把内容打完。
                state.setNotice("用法：/queue <消息> — 排到下一回合再发");
                return;
            }
            clearInput();
            releaseBrake();
            String queuedSkill = pendingSkill;   // 一次性：同普通提交，取走挂载
            pendingSkill = null;
            if (busy()) {
                state.enqueue(body, queuedSkill);
            } else {
                dispatch(body, queuedSkill);     // 空闲时「排队」等价于直接发，不必让用户再按一次
            }
            return;
        }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionRouteTest
```

期望：6 个测试全 PASS。

- [ ] **Step 5: 变异验证**

| 变异 | 应挂的测试 |
|---|---|
| `state.enqueue(body, …)` → `onSubmit.interject(body)` | `queueCommandForcesEnqueue` |
| `substring` 改成传整条 `cmd`（含 `/queue ` 前缀） | `queueCommandForcesEnqueue` 的最后一条断言 |
| 空参数分支删掉 | `bareQueueCommandShowsNotice` |

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInterjectionRouteTest.java
git commit -m "feat(interjection): /queue 显式排到下回合

不用 Alt+Enter：那个键已是输入框换行，且在区分不了修饰键的终端上
到达时就是裸 Enter，会静默错路由。"
```

---

## Task 9: Esc 取消时回填插话

按 Esc 通常正是「别跑了，听我的」，那句话不该跟着一起没。已送达的那条尤其重要：
模型看过、历史没有（取消走 `doOnCancel`，`handleComplete` 不跑）、用户也拿不回来。

**Files:**
- Modify: `ui/CodeTuiView.java:827-836`（Esc 分支）
- Test: `test/…/ui/CodeTuiViewInterjectionCancelTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyCode;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeTuiViewInterjectionCancelTest {

    /**
     * 插话队列的替身：用纯列表模拟「未送达 / 已送达」两态。
     *
     * <p><b>刻意不用真的 {@code Interjections}</b>：它的 {@code drainForInjection} 是包私有的，
     * 本测试在 {@code ui} 包里根本调不到（跨包编译不过）。而且本测试要验的是 View 的路由，
     * 队列自身的语义归 {@code InterjectionsTest}——用替身反而边界更清楚。
     */
    private static final class Handler implements SubmitHandler {
        final List<String> pending = new ArrayList<>();
        String delivered;                        // 已送达模型、尚未入历史的那条
        final List<String> submitted = new ArrayList<>();

        @Override public Disposable submit(String text) { submitted.add(text); return () -> {}; }
        @Override public Disposable submit(String text, String skill) { return submit(text); }
        @Override public void interject(String text) { pending.add(text); }
        @Override public int pendingInterjections() { return pending.size(); }

        @Override public List<String> takePendingInterjections() {
            List<String> out = List.copyOf(pending);
            pending.clear();
            return out;
        }

        @Override public List<String> takeBackInterjections() {
            List<String> out = new ArrayList<>();
            if (delivered != null) { out.add(delivered); }
            out.addAll(pending);
            pending.clear();
            delivered = null;
            return out;
        }
    }

    @Test
    @DisplayName("Esc 把未送达与已送达的插话一起回填输入框")
    void cancelRefillsInterjectionsIntoInput() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        h.delivered = "先说的";        // 已送达模型，但回合随后被取消 → 没人补它进历史
        h.pending.add("后说的");       // 还没送出去

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals("先说的\n后说的", v.inputTextForTest(),
                "Esc 后插话应回到输入框——模型看过、历史没有、用户还拿不回来的话就凭空消失了");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionCancelTest
```

期望：FAIL——输入框为空（当前 Esc 分支只清 `queued`，不碰插话）。
若报 `inputTextForTest` 不存在，按下面 Step 3 一并加上。

- [ ] **Step 3: 实现**

`CodeTuiView.java:827-836` 的 Esc 分支改成：

```java
        if (k.isCancel()) {
            boolean running = !state.isIdle();
            int dropped = state.queuedCount();
            // 未送达 + 已送达的插话一起要回来，回填输入框而不是丢弃。已送达的那条尤其不能丢：
            // 取消走 doOnCancel，handleComplete 不跑，没人补它进历史——不还给用户就凭空消失了。
            List<String> refill = onSubmit.takeBackInterjections();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.clearQueued();                         // 取消时一并清空排队消息
            if (!refill.isEmpty()) {
                inputState.setText(String.join("\n", refill));
            }
            state.setNotice(running || dropped > 0
                    ? "已取消当前回合" + (dropped > 0 ? "，丢弃 " + dropped + " 条排队" : "")
                            + (refill.isEmpty() ? "" : "，插话已放回输入框")
                    : "");
            return EventResult.HANDLED;
        }
```

若 `inputTextForTest` 不存在，加一个包私有测试钩子（同 `feedKeyForTest` 纪律）：

```java
    /** 测试钩子：读输入框当前文本。 */
    String inputTextForTest() { return inputState.getText(); }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionCancelTest
```

期望：PASS。

- [ ] **Step 5: 跑既有取消相关测试，确认没打挂**

```bash
mvn test -pl springai-code-tui -Dtest='CodeTuiViewCancelNoticeTest,CodeTuiViewInFlightGateTest'
```

期望：全绿。notice 文案改了，若 `CodeTuiViewCancelNoticeTest` 断言的是精确串会挂——
那是真回归，按新文案更新断言即可（无插话时后缀为空串，原文案不变）。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInterjectionCancelTest.java
git commit -m "feat(interjection): Esc 取消时把插话回填输入框

已送达的那条尤其不能丢：取消走 doOnCancel，没人补它进历史，
不还给用户就是模型看过、历史没有、用户也拿不回来。"
```

---

## Task 10: 回合结束时的兜底出队

用户在**收尾流**期间插话时，最后一次模型调用已经发出去了，不会再有 `inject()`。
这句话会卡在队列里直到用户下次发消息才被捎走，那时 anchor 语义早就不对了。

放 UI 侧、复用**已有的**出队钩子（`CodeTuiView.java:362-371`，渲染线程），
不放 `handleComplete`：那里在 reactive 线程，从那起新回合要跟 UI 线程的出队逻辑抢。

**Files:**
- Modify: `ui/CodeTuiView.java:362-371`
- Test: `test/…/ui/CodeTuiViewInterjectionCancelTest.java`（追加）

- [ ] **Step 1: 写失败的测试**

```java
    /** 收尾流期间插的话没赶上 inject()，回合结束后必须自动起一个新回合把它发出去。 */
    @Test
    @DisplayName("回合结束时未送达的插话转成新回合")
    void leftoverInterjectionBecomesNewTurn() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        h.pending.add("没赶上的那句");   // 未送达
        s.cancelCurrent();               // 回合结束，回 IDLE

        v.tickForTest();                 // drain：!busy() ⇒ 先出插话残余

        assertEquals(List.of("没赶上的那句"), h.submitted);
        assertEquals(0, h.pendingInterjections());
    }

    /** 兜底只取未送达的：已送达那条归补历史，抢走会让同一句话发两遍。 */
    @Test
    @DisplayName("兜底出队不碰已送达的插话")
    void drainDoesNotStealDeliveredInterjection() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        h.delivered = "已送达的";        // 只有已送达的，没有未送达的
        s.cancelCurrent();

        v.tickForTest();

        assertEquals(List.of(), h.submitted, "已送达的插话不该被当成新回合重发");
    }
```

> `Handler` 已在本测试类里定义（见 Task 9），含 `pending` / `delivered` / `submitted` 三个字段，
> 无需再改。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionCancelTest
```

期望：`leftoverInterjectionBecomesNewTurn` FAIL（`submitted` 为空）；
`drainDoesNotStealDeliveredInterjection` 可能已 PASS（防回归）。

- [ ] **Step 3: 实现**

`CodeTuiView.java:362-371` 的出队钩子改成：

```java
        // 回合结束后自动出队下一条排队消息。submit() 同步置 THINKING，故本 tick 只会出队一条，无重复提交竞态。
        if (!busy()) {   // 空闲、非压缩中、且无在飞子 agent 才出队（见 busy()）
            // 兜底：用户在收尾流期间插的话没赶上 inject()（最后一次模型调用已发出），
            // 不接住的话它会一直躺在队列里，直到下次发消息才被捎走、且 anchor 语义已不对。
            // ⚠ 只取未送达的：已送达那条归 handleComplete 补历史，抢走会让同一句话发两遍。
            // 排在 pollQueued 之前——插话在时序上更早。
            List<String> leftover = onSubmit.takePendingInterjections();
            if (!leftover.isEmpty()) {
                releaseBrake();
                dispatch(String.join("\n", leftover), null);
                return;
            }
            ConversationState.Queued next = state.pollQueued();
            if (next != null) {
                releaseBrake();              // 用户的真实输入：重置自动回合刹车
                dispatch(next.text(), next.skill());
                return;                      // 本帧已提交，后台送达让到下一帧——用户排的队优先
            }
            deliverBackgroundResults();
        }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionCancelTest
```

期望：3 个测试全 PASS。

- [ ] **Step 5: 变异验证**

| 变异 | 应挂的测试 |
|---|---|
| `takePendingInterjections()` → `takeBackInterjections()` | `drainDoesNotStealDeliveredInterjection` |
| 把 `leftover` 那段挪到 `pollQueued` **之后** | 新增一条断言：同时有插话和排队时，插话先发（见下） |
| 整段删掉 | `leftoverInterjectionBecomesNewTurn` |

第二条变异需要一条专门的断言才杀得掉，补上：

```java
    /** 插话在时序上早于排队消息，必须先发。 */
    @Test
    @DisplayName("插话残余先于排队消息出队")
    void leftoverInterjectionGoesBeforeQueued() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        h.pending.add("先插的话");
        s.enqueue("后排的队", null);
        s.cancelCurrent();

        v.tickForTest();

        assertEquals(List.of("先插的话"), h.submitted, "插话应先出队");
        assertEquals(1, s.queuedCount(), "排队消息留到下一帧");
    }
```

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInterjectionCancelTest.java
git commit -m "feat(interjection): 回合末把没赶上的插话转成新回合

收尾流期间插的话赶不上 inject()。兜底只取未送达的——
已送达那条归补历史，抢走就是同一句话发两遍。"
```

---

## Task 11: 状态栏「插话 N 条」

**Files:**
- Modify: `ui/CodeTuiView.java:2462-2463`
- Test: `test/…/ui/CodeTuiViewInterjectionStatusTest.java`（新建）

- [ ] **Step 1: 写失败的测试**

`statusLine` 相关分支可以直接断言文本（本项目已有 `Frame.forTesting` 离屏渲染的先例，
但这里只需读拼出的串，用现有的状态行取值钩子即可）：

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewInterjectionStatusTest {

    private static final class Handler implements SubmitHandler {
        int pending;
        @Override public Disposable submit(String text) { return () -> {}; }
        @Override public int pendingInterjections() { return pending; }
    }

    @Test
    @DisplayName("有未送达插话时状态栏显示条数")
    void statusShowsPendingInterjections() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);
        h.pending = 2;

        assertTrue(v.statusTextForTest().contains("插话 2 条"));
    }

    @Test
    @DisplayName("没有插话时不渲染该段（避免悬空分隔符）")
    void statusOmitsSegmentWhenZero() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        assertFalse(v.statusTextForTest().contains("插话"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionStatusTest
```

期望：FAIL 或编译失败（`statusTextForTest` 不存在 / 不含「插话」）。

- [ ] **Step 3: 实现**

`CodeTuiView.java:2462-2463` 那两行下面补一段，并把它拼进状态行（与 `qs` 同处使用）：

```java
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        // 未送达的插话条数。⚠ 空串必须判：不判会渲染出一段悬空的 " · "（同 qs 的既有纪律）。
        int ij = onSubmit.pendingInterjections();
        String ijs = ij > 0 ? " · 插话 " + ij + " 条" : "";
```

随后把 `ijs` 加到与 `qs` 相同的拼接位置。若 `statusTextForTest` 不存在，加测试钩子：

```java
    /** 测试钩子：读状态行的纯文本。 */
    String statusTextForTest() { return statusLine().plainText(); }
```

> `statusLine()` 的返回类型若不提供 `plainText()`，改为把状态行的字符串拼装抽成一个
> 包私有纯函数（如 `String statusSuffix(int queued, int interjections)`）再单测它——
> 本项目已有 `drainingSubagentsHint` 这种「纯函数便于单测」的先例。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewInterjectionStatusTest
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInterjectionStatusTest.java
git commit -m "feat(interjection): 状态栏显示未送达插话条数"
```

---

## Task 12: 把探针测试改造成回归测试

`MidTurnInjectionProbeTest` 的 294 行里，三个探针是「验证假设」用的一次性工具
（它们对比了三种注入点，结论已写进 spec），不是回归测试。留着的话，以后改动逻辑会被
一堆探测性断言挡住，却分不清哪些是真契约。

但里面的**脚手架要留**：`CapturingModel`（多轮工具循环）和 `slowTool(entered, gate)`
（闸门卡住给出「回合跑到一半」的窗口）正是端到端回归需要的。

**Files:**
- Delete: `test/…/agent/MidTurnInjectionProbeTest.java`
- Create: `test/…/agent/MidTurnInjectionTest.java`

- [ ] **Step 1: 新建回归测试，搬运脚手架**

保留 `CapturingModel` / `slowTool` 原样，只留一个端到端断言——插话在**工具循环中途**
投递后，出现在**下一次**模型调用的消息表末尾且紧跟 tool 结果：

```java
    @Test
    @DisplayName("工具循环中途插话，随下一次模型调用送达且位置合法")
    void interjectionArrivesAtNextModelCall(@TempDir Path root) throws Exception {
        // 装配照抄原 probeInjectAtModelLayer 的 setup 段（CapturingModel + slowTool + Interjections
        // + InterjectingChatModel.wrap + ChatClient.builder），此处只列断言。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        // ... 起回合，等 entered（回合已卡在第一个工具里）

        interjections.offer("改用方案 B");
        gate.countDown();                       // 放行工具，让循环进入下一次模型调用

        List<Prompt> prompts = model.awaitPrompts(2);
        List<Message> second = prompts.get(1).getInstructions();
        assertInstanceOf(ToolResponseMessage.class, second.get(second.size() - 2),
                "插话前一条必须是 tool 结果——落在 assistant(tool_calls) 与 tool 之间就是 400");
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"),
                second.get(second.size() - 1).getText());
        assertEquals(0, interjections.pendingCount(), "送达后队列应清空");
    }
```

- [ ] **Step 2: 跑新测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=MidTurnInjectionTest
```

- [ ] **Step 3: 删掉探针，跑全模块**

```bash
git rm springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MidTurnInjectionProbeTest.java
mvn test -pl springai-code-tui
```

期望：全绿。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MidTurnInjectionTest.java
git commit -m "test(interjection): 探针改造为回归测试

三个对比探针是验证假设用的一次性工具，结论已进 spec；
脚手架（CapturingModel/slowTool）保留，只留端到端契约断言。"
```

---

## Task 13: pty 实机冒烟

单测到不了两类东西：**装饰器是否真的套在了生产装配链上**（`AgentTools.build` 没有单测），
以及**状态栏/回显在真终端里长什么样**。

- [ ] **Step 1: 重新 package**

```bash
mvn -q -pl springai-code-tui package -DskipTests
```

⚠ 改完不 package，pty 跑的是旧 jar，会得到一个看起来很像回归的假失败。

- [ ] **Step 2: 写 pty 冒烟脚本**

照本项目既有 pty 冒烟的写法（`pty.fork` + `pyte`），**必须设窗口大小和 TERM**：

```python
import os, pty, struct, fcntl, termios
pid, fd = pty.fork()
if pid == 0:
    os.environ["TERM"] = "xterm-256color"      # 不设则渲染全空白
    os.execvp("java", ["java", "-jar", JAR])
fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))  # 默认 0×0 什么都读不到
```

- [ ] **Step 3: 冒烟场景**

| 场景 | 操作 | 期望 |
|---|---|---|
| 插话回显 | 起一个会跑工具的回合 → 回合进行中输入「换个思路」+ Enter | scrollback 出现 `› 换个思路`，且**不含**「待送达」字样 |
| 状态栏计数 | 同上，插话后立刻读状态行 | 含「插话 1 条」；模型调用发生后该段消失 |
| `/queue` 补全 | 输入 `/qu` → Tab | 补全成 `/queue` |
| Esc 回填 | 回合中插话 → Esc | 输入框里出现刚才那句话 |

⚠ `wait_for` 断言「当前状态」不能只靠子串匹配——它会命中陈旧 scrollback。
读状态行要取**当前屏幕**的那一行，不是整个 scrollback。

- [ ] **Step 4: 提交冒烟脚本**

```bash
git add <冒烟脚本路径>
git commit -m "test(interjection): pty 实机冒烟——回显、状态栏、/queue 补全、Esc 回填"
```

---

## 收尾

- [ ] **跑一遍全模块，确认全绿**

```bash
mvn test -pl springai-code-tui
```

- [ ] **更新 spec 状态**

把 spec 头部的「**状态**：待实现」改成「已实现」并附最终提交号，
同时把 Task 1 探针的实测结论补进「动手第一步」一节。

- [ ] **更新 CHANGELOG.md**（本仓有此惯例）

---

## Plan Self-Review

对照 spec 逐节检查覆盖：

| Spec 小节 | 对应任务 |
|---|---|
| 决定（ChatModel 层注入） | Task 4 |
| 判定：什么算插话（Enter / `/queue` / 三条回落） | Task 7、Task 8 |
| 组件与改动（5 个文件） | Task 2、3、4、5、6、7 |
| 数据流（注入 → 补历史） | Task 3、Task 5 |
| anchor 定位失败的回落 | Task 5 Step 1 的后两个测试 |
| 兜底：回合结束时 pending 仍有货 | Task 10 |
| ① 竞态（三个消费者各取所需） | Task 2、Task 6、Task 10 |
| ② 顺序（补历史在通知 listener 之前） | Task 5 Step 3 |
| ③ 装饰顺序（最外层） | Task 4 Step 1 |
| ④ 只包主 agent | Task 4（子 agent 链不动） |
| ⑤ `/clear` 交错（sid 快照 + 清队列） | Task 5（sid 快照）；**清队列见下方缺口** |
| ⑥ 已知不精确（二次插话位置） | 无需实现，spec 已记录 |
| ⑦ 补历史失败不带崩回合 | Task 5 Step 3 的 try/catch |
| 测试口径（变异、pty、探针去留） | Task 12、Task 13 及各任务的变异验证步 |

**发现一处缺口并补上**：spec ⑤ 要求「`/clear` 要一并清空 `Interjections`」，
上面没有任何任务实现它。补一个小任务：

### Task 5b: `/clear` 时清空插话队列

`/clear` 换新会话后，`delivered` / `anchor` 指向的是已经不存在的旧会话，
留着会让下一个回合的补历史插到一个对不上的位置。

**Files:** Modify `agent/CodingAgent.java` 的 `clearContext()`

- [ ] **Step 1: 写失败的测试**（追加到 `CodingAgentInterjectionFacadeTest`）

```java
    @Test
    @DisplayName("/clear 后插话队列清空")
    void clearContextDropsInterjections() {
        Interjections q = new Interjections();
        q.offer("旧会话里说的");
        q.drainForInjection("call-1");

        q.drainForRefill();   // clearContext 应等价于此：pending 与 delivered 一起清

        assertEquals(0, q.pendingCount());
        assertTrue(q.takeForHistory().isEmpty());
    }
```

- [ ] **Step 2: 在 `CodingAgent.clearContext()` 里加一行**

```java
        if (interjections != null) {
            // /clear 换了新会话：delivered/anchor 指向的旧会话已经不存在，留着会让下个回合
            // 的补历史插到一个对不上的位置。整体丢弃（未送达的也一并丢——用户主动清空了上下文）。
            interjections.drainForRefill();
        }
```

- [ ] **Step 3: 跑测试 + 提交**

```bash
mvn test -pl springai-code-tui -Dtest=CodingAgentInterjectionFacadeTest
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentInterjectionFacadeTest.java
git commit -m "fix(interjection): /clear 时一并清空插话队列

换新会话后 delivered/anchor 指向的旧会话已不存在，留着会让补历史插错位置。"
```

**执行顺序**：Task 5b 排在 Task 6 之后（它复用 Task 6 的测试类）。
