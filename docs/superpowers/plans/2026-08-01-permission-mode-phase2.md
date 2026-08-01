# 权限管理 期 2（计划模式）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 加一档 `PLAN` 权限模式——模型只能读和查，任何写/命令一律**拒绝**（不是询问）；调查清楚后调用 `ExitPlanMode` 提交计划，人批准后才切到可动手的模式。

**Architecture:** 复用期 1 已落地的三件基础设施，不新造轮子：① `PermissionEngine.decideByMode` 加一条 PLAN 分支（DENY）；② `ModalRequest` 密封接口加第三个子类型 `PlanRequest`，走与审批面板同一条模态队列与取消路径；③ `PlanApprovalBridge` 照抄 `UserQuestionBridge` 的一次性阻塞握手。计划正文用现成 `MarkdownRenderer` 打进 scrollback，面板只放选项。

**Tech Stack:** Java 17（`maven.compiler.release=17`）、Spring AI 2.0.0、TamboUI 0.4.0、JUnit 5、pty+pyte 实机冒烟。

---

## 上游文档

- **spec**：`docs/superpowers/specs/2026-07-31-permission-mode-design.md` §4（模式）、§9（plan 工作流）、§10（子 agent）
- **期 1 计划与实施记录**：`docs/superpowers/plans/2026-07-31-permission-mode.md`——**动手前先读它的「Task 9/10/11 实施记录」与文末补丁**，那里记着一次性握手、模态队列、取消唤醒的全部血泪细节。

## 期 1 留下的硬约束（违反任一条都会造成静默挂死或假绿）

1. **一请求一个一次性应答口**。容量 1 的 `ArrayBlockingQueue` + 非阻塞 `offer`；消费方**只 poll 一次**。
   **禁止重试循环、禁止复用/重新武装同一个 handoff**——会读到陈旧的 CANCEL，杀掉用户刚批准的回合。
2. **listener 回调里绝不阻塞**。`ConversationState` 的 listener 方法是 `synchronized`，与 `drainPending()`/`cancelCurrent()` 共用一把锁，阻塞会冻住**整个 TUI**（连 Esc 都按不动）。
3. **覆写的 listener 方法不得调 `super`**。默认实现会立即应答，一次性口被消费掉，用户随后的真实选择被丢弃。
4. **`ModalRequest.cancel()` 实现不得抛异常**（含 NPE）。取消期排空循环遍历整条队列，一个元素抛异常就让其后所有工具线程永久 park。不变量在**构造期**校验。
5. **sealed 子类型必须与接口同包**（本项目无 `module-info`，unnamed module）——所以 `PlanRequest` 放 `agent/`，不放 `agent/permission/`。
6. **Java 17**：没有类型模式 `switch`（JEP 441 要 21），穷尽性由人保证——**新增 `ModalRequest` 子类型必须手动巡查所有 `instanceof` 链**（本计划 Task 5 列了全部 3 处）。
7. **验证命令必须带模块作用域**：`mvn test -pl springai-code-tui`。整仓 `-Dtest` 会被 3 个空模块打挂；**不许**用 `-DfailIfNoSpecifiedTests=false` 盖问题。
8. **系统提示注入必须作为 param 值**，不能拼进模板字符串——正文里的花括号会炸 SpringTemplate（`AGENTS.md`、长期记忆接入时都踩过）。
9. **经 `EventRouter` 分发的按键行为，单测绿不构成证据**，必须 pty 实机验证；改完要重新 `package` 再跑冒烟。
10. **既有 flaky**：`CodingAgentSpikeTest.todoTurnIdBinding` 打真实模型、60s 超时，在干净 HEAD 上也时红时绿。它的红**单独记录、不计入**判据。

## 本期不做（明确划界）

- **不做**「计划保存成文件」「计划历史」——spec 无此要求。
- **不做** `/permissions` 交互式增删规则（期 3）。
- **不改**子 agent 的权限继承方式——它们与主 agent 共用同一个引擎与模式，PLAN 下同样被 DENY，`SubagentSpec` 不加字段（spec §10）。

---

## 文件结构

| 文件 | 增/改 | 职责 |
|---|---|---|
| `agent/permission/PermissionMode.java` | 改 | 加 `PLAN` 常量、`next()` 三档循环、`label()` |
| `agent/permission/PermissionEngine.java` | 改 | `decideByMode` 加 PLAN 分支（只读放行，其余 DENY） |
| `agent/PlanOutcome.java` | **新** | 计划审批的四种结果（三个选项 + CANCEL） |
| `agent/PlanResponder.java` | **新** | UI → 工具线程的一次性应答口（带反馈文本） |
| `agent/PlanRequest.java` | **新** | 第三个 `ModalRequest` 子类型 |
| `agent/ModalRequest.java` | 改 | `permits` 加 `PlanRequest` |
| `agent/PlanApprovalBridge.java` | **新** | 阻塞握手 + `ExitPlanMode` 的 `ToolCallback` 工厂 |
| `agent/AgentListener.java` | 改 | 加 `onPlanSubmitted`，默认实现「继续完善计划」而非空实现 |
| `agent/AgentTools.java` | 改 | 注册 `ExitPlanMode`；`SYSTEM_TEMPLATE` 加 `{PERMISSION_MODE}` |
| `agent/CodingAgent.java` | 改 | `submit` 每回合按 `permissionEngine.mode()` 注入提示段（**已持有引擎，不加构造参数**） |
| `agent/permission/ToolRegistry.java` | 改 | 登记 `ExitPlanMode` 为 `INTERNAL` |
| `ui/ConversationState.java` | 改 | 接纳 `PlanRequest` 入队 |
| `ui/CodeTuiView.java` | 改 | 模态分派加分支、计划审批面板、按键、正文进 scrollback、批准后切模式 |
| `ui/Theme.java` | 改 | `MODE_PLAN` 配色 |
| `CodeTuiApplication.java` | 改 | `--permission-mode` 解析 |
| `src/test/resources/scripts/permission_smoke.py` | 改 | 计划模式 + 计划审批面板的实机断言 |

---

## Task 1: `PermissionMode.PLAN`（枚举 + 循环 + 标签 + 状态栏配色）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（`modeTag`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionModeTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModeIndicatorTest.java`

- [ ] **Step 1: 写失败测试——三档循环**

新建 `PermissionModeTest.java`（若已存在则把这两个方法加进去）：

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PermissionModeTest {

    @Test
    @DisplayName("无启动参数：三档循环 DEFAULT → ACCEPT_EDITS → PLAN → DEFAULT，BYPASS 不混入")
    void cyclesThreeWithoutBypass() {
        PermissionMode m = PermissionMode.DEFAULT;
        m = m.next(false);
        assertEquals(PermissionMode.ACCEPT_EDITS, m);
        m = m.next(false);
        assertEquals(PermissionMode.PLAN, m);
        m = m.next(false);
        assertEquals(PermissionMode.DEFAULT, m, "无 --dangerously-skip-permissions 时不得走到 BYPASS");

        // 从 BYPASS 出发只能走到 DEFAULT，回不去（启动参数是唯一入口）
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(false));
    }

    @Test
    @DisplayName("带启动参数：四档循环，BYPASS 排在 PLAN 之后")
    void cyclesFourWithBypass() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(true));
        assertEquals(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS.next(true));
        assertEquals(PermissionMode.BYPASS, PermissionMode.PLAN.next(true));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(true));
    }

    @Test
    @DisplayName("每档都有互不相同的非空标签（状态栏与面板直接显示它）")
    void labelsAreDistinct() {
        for (PermissionMode a : PermissionMode.values()) {
            assertNotEquals("", a.label(), a + " 的 label 不能为空");
            for (PermissionMode b : PermissionMode.values()) {
                if (a != b) {
                    assertNotEquals(a.label(), b.label(), a + " 与 " + b + " 标签撞了，用户分不清在哪一档");
                }
            }
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionModeTest`
Expected: 编译失败，`找不到符号: 变量 PLAN`

- [ ] **Step 3: 加 `PLAN` 常量与循环**

`PermissionMode.java` 全文替换为：

```java
package io.github.javaside.springai.codetui.agent.permission;

/**
 * 权限模式。
 *
 * <p><b>切换</b>：UI 的 {@code Shift+Tab}（期 0 已 pty 实测可与裸 Tab 区分）。
 */
public enum PermissionMode {
    /** 只读放行，其余按规则；无规则则 ASK。 */
    DEFAULT,
    /** 额外放行工作目录内的 Write/Edit，以及 mkdir/touch/mv/cp 类文件系统命令。 */
    ACCEPT_EDITS,
    /**
     * 计划模式：只读放行，写文件 / 非只读命令 / 未登记工具一律 <b>DENY</b>——
     * <b>不是 ASK</b>。计划模式的意义就在于「这段时间里根本不可能动手」，
     * 弹个面板让人能当场批准就破功了。模型应转而调用 {@code ExitPlanMode} 提交计划。
     */
    PLAN,
    /**
     * 全放行——但 deny 规则与内置危险检查<b>仍然生效</b>（它们排在 mode 默认之前）。
     * 仅 {@code --dangerously-skip-permissions} 启动可进。
     */
    BYPASS;

    /**
     * 循环到下一个模式。
     *
     * @param bypassAllowed 启动时是否开了 {@code --dangerously-skip-permissions}；
     *                      false 时 BYPASS 不进循环（从 BYPASS 出发只能走到 DEFAULT，回不去）
     */
    public PermissionMode next(boolean bypassAllowed) {
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> PLAN;
            case PLAN -> bypassAllowed ? BYPASS : DEFAULT;
            case BYPASS -> DEFAULT;
        };
    }

    /** 状态栏/面板显示用的短标签。 */
    public String label() {
        return switch (this) {
            case DEFAULT -> "默认";
            case ACCEPT_EDITS -> "自动接受编辑";
            case PLAN -> "计划模式";
            case BYPASS -> "跳过权限检查";
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionModeTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 补状态栏常驻标识的 PLAN 分支**

`Theme.java`，在 `MODE_BYPASS` 那一行下面加：

```java
    static final Style MODE_PLAN   = Style.create().fg(Color.indexed(115)).bold();   // 计划模式=冷薄荷加粗（与暖橙的「自动接受编辑」拉开色相）
```

`CodeTuiView.java` 的 `modeTag` 加一个 case（`switch` 是穷尽的，不加则**编译失败**——这正是想要的）：

```java
    static Span modeTag(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> null;
            case ACCEPT_EDITS -> Span.styled("⏵⏵ " + mode.label() + " · ", MODE_ACCEPT);
            case PLAN -> Span.styled("⏸ " + mode.label() + " · ", MODE_PLAN);
            case BYPASS -> Span.styled("⚠ " + mode.label() + " · ", MODE_BYPASS);
        };
    }
```

- [ ] **Step 6: 给状态栏标识补 PLAN 用例**

在 `CodeTuiViewModeIndicatorTest.java` 的 `modeTagPerMode()` 里追加两行：

```java
        assertEquals(Theme.MODE_PLAN, CodeTuiView.modeTag(PermissionMode.PLAN).style());
        assertTrue(CodeTuiView.modeTag(PermissionMode.PLAN).content().contains("计划模式"));
```

同一文件的 `tagSurvivesNoticeBeingCleared` 里，把「切回 DEFAULT」那段从**按 1 次** Shift+Tab 改成**按 2 次**（循环变长了，不改这条会红）：

```java
        // 切回 DEFAULT 后标识必须消失——现在是三档循环，要按两次
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(KeyEvent.ofChar('b'));
        assertEquals(PermissionMode.DEFAULT, stub.mode);
```

- [ ] **Step 7: 跑相关测试**

Run: `mvn test -pl springai-code-tui -Dtest='PermissionModeTest,CodeTuiView*Test'`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionModeTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModeIndicatorTest.java
git commit -m "feat(code-tui): 加 PLAN 权限模式常量与三档循环"
```

---

## Task 2: 引擎的 PLAN 分支（只读放行，其余 DENY）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java:479`（`decideByMode`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEnginePlanModeTest.java`

**背景（实施者必读）**：`decide()` 的七步顺序是 deny 规则 → 内置危险检查 → (预留) → ask 规则 → allow 规则 → **模式默认** → 兜底。PLAN 分支落在第 6 步，因此：

- **deny 规则仍然优先**（第 1 步），PLAN 不改变这一点；
- **allow 规则排在模式默认之前**——这意味着一条 `Bash(rm -rf /tmp/x:*)` 的 allow 规则**会在 PLAN 模式下放行**。这是决策顺序的既定语义，不在本任务范围内更改；测试要把它钉住，免得后人以为是 bug 而「顺手修好」。

- [ ] **Step 1: 写失败测试**

新建 `PermissionEnginePlanModeTest.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN 模式：只读放行，其余一律 <b>DENY 而非 ASK</b>。
 *
 * <p>为什么是 DENY：能当场批准就等于没有计划模式。DENY 串还要引导模型转向
 * {@code ExitPlanMode}，否则它只会反复重试同一个写操作、把回合耗光。
 */
class PermissionEnginePlanModeTest {

    private static PermissionEngine plan(Path root, PermissionRule... rules) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of(rules)),
                PermissionMode.PLAN, false);
    }

    @Test
    @DisplayName("只读工具照常放行（计划模式要能调查）")
    void readOnlyStillAllowed(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Read", "{\"filePath\":\"" + root.resolve("a.txt") + "\"}").behavior());
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Grep", "{\"path\":\"" + root + "\"}").behavior());
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Bash", "{\"command\":\"git status\"}").behavior(),
                "只读命令白名单在 PLAN 下同样放行");
    }

    @Test
    @DisplayName("写文件 / 非只读命令 / 未登记工具一律 DENY，且原因引导模型去 ExitPlanMode")
    void mutationsDenied(@TempDir Path root) {
        PermissionEngine e = plan(root);

        PermissionDecision write = e.decide("Write", "{\"filePath\":\"" + root.resolve("a.txt") + "\"}");
        assertEquals(PermissionBehavior.DENY, write.behavior(), "PLAN 下写文件必须 DENY，不是 ASK");
        assertTrue(write.reason().contains("ExitPlanMode"),
                "拒绝串必须指路，否则模型只会反复重试同一个写操作：" + write.reason());

        assertEquals(PermissionBehavior.DENY,
                e.decide("Bash", "{\"command\":\"mvn test\"}").behavior());
        assertEquals(PermissionBehavior.DENY,
                e.decide("mcp__x__do_thing", "{\"any\":\"payload\"}").behavior(),
                "未登记工具（含全部 MCP）在 PLAN 下也不许动手");
    }

    @Test
    @DisplayName("内部工具放行——否则 ExitPlanMode 自己就被拦住了，计划模式成了死胡同")
    void internalToolsAllowed(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ALLOW, e.decide("ExitPlanMode", "{\"plan\":\"# 计划\"}").behavior());
        assertEquals(PermissionBehavior.ALLOW, e.decide("TodoWrite", "{}").behavior());
        assertEquals(PermissionBehavior.ALLOW, e.decide("AskUserQuestionTool", "{}").behavior());
    }

    @Test
    @DisplayName("网络工具在 PLAN 下仍是 ASK——调查要查资料，但请求内容会离开本机")
    void networkStillAsks(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ASK,
                e.decide("WebFetch", "{\"url\":\"https://docs.spring.io/x\"}").behavior());
    }

    @Test
    @DisplayName("deny 规则仍排第一；allow 规则仍排在模式默认之前（既定语义，别当 bug 修）")
    void ruleOrderUnchanged(@TempDir Path root) {
        PermissionEngine denied = plan(root,
                PermissionRule.parse("Read(**/secret.txt)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                denied.decide("Read", "{\"filePath\":\"" + root.resolve("secret.txt") + "\"}").behavior(),
                "deny 排第 1 步，PLAN 不改变它");

        PermissionEngine allowed = plan(root,
                PermissionRule.parse("Write(**/notes.md)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ALLOW,
                allowed.decide("Write", "{\"filePath\":\"" + root.resolve("notes.md") + "\"}").behavior(),
                "allow 规则排在模式默认之前，故 PLAN 下也放行——这是决策顺序的既定语义");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionEnginePlanModeTest`
Expected: FAIL——`mutationsDenied` 报「expected DENY but was ASK」（PLAN 目前落到 `FILE_WRITE`/`COMMAND` 的既有分支）

- [ ] **Step 3: 加 PLAN 分支**

`PermissionEngine.java` 的 `decideByMode`，在 `if (mode == PermissionMode.BYPASS)` 之后、`switch (entry.category())` 之前插入：

```java
        if (mode == PermissionMode.PLAN) {
            PermissionDecision d = decideByPlanMode(entry, target);
            if (d != null) {
                return d;
            }
        }
```

并在 `decideByMode` 下方新增方法：

```java
    /**
     * 计划模式的默认：只读与内部工具放行，其余一律 <b>DENY</b>；网络工具返回 null 交回原分支（仍是 ASK）。
     *
     * <p><b>为什么是 DENY 不是 ASK</b>：ASK 意味着用户能当场批准，那计划模式就名存实亡了。
     * 拒绝是这一档的全部意义——「这段时间里根本不可能动手」。
     *
     * <p><b>拒绝串必须指路</b>：它会原样进模型的 tool 结果。只说「被拒绝」模型会反复重试同一个写操作，
     * 把回合耗在无效调用上；写明「改调 ExitPlanMode」才能把它推回计划轨道。
     */
    private PermissionDecision decideByPlanMode(ToolRegistry.Entry entry, String target) {
        switch (entry.category()) {
            case INTERNAL:
                return PermissionDecision.allow("内部工具，无外部副作用");
            case READ_ONLY:
                return PermissionDecision.allow("只读操作（计划模式允许调查）");
            case NETWORK_READ:
                return null;                       // 交回原分支：仍然每次询问
            case COMMAND:
                // 只读白名单内的命令照常放行，否则拒绝
                BashCommandSplitter.Split s = BashCommandSplitter.split(target);
                if (s.parseable() && !s.segments().isEmpty()
                        && s.segments().stream().allMatch(BashCommandSplitter::isReadOnly)) {
                    return PermissionDecision.allow("命令的每一段都在只读白名单内");
                }
                return planDeny("执行命令 " + display(target));
            default:
                // FILE_WRITE / UNKNOWN（含全部 MCP 工具）
                return planDeny("这个操作可能修改文件或产生副作用：" + display(target));
        }
    }

    /** 计划模式的拒绝串：说清楚现在处于哪一档、以及正确的下一步。 */
    private static PermissionDecision planDeny(String what) {
        return PermissionDecision.deny("当前处于计划模式（只读），不能" + what
                + "。请继续用只读工具把方案调查清楚，然后调用 ExitPlanMode 提交计划等待用户批准；"
                + "批准后你才能动手。不要重试本次操作。");
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionEnginePlanModeTest`
Expected: PASS（5 个测试）

> ⚠ `internalToolsAllowed` 里的 `ExitPlanMode` 断言此刻会**因为工具尚未登记**而落到 `UNKNOWN` → DENY。
> 这是刻意的顺序依赖：**Task 7 Step 3 登记该工具后这条才转绿**。若本步就想全绿，可先只跑
> `-Dtest=PermissionEnginePlanModeTest#mutationsDenied+readOnlyStillAllowed+networkStillAsks+ruleOrderUnchanged`，
> 并在 Task 7 完成后回来补跑整类。**不要为了让它变绿而改断言**。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEnginePlanModeTest.java
git commit -m "feat(code-tui): 引擎加 PLAN 模式分支，写与命令一律拒绝并指路 ExitPlanMode"
```

---

## Task 3: `--permission-mode` 启动参数

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java`

- [ ] **Step 1: 写失败测试**

在既有 `PermissionStartupTest.java` 里追加：

```java
    @Test
    @DisplayName("--permission-mode 解析：认 plan/default/acceptEdits，大小写不敏感")
    void parsesPermissionMode() {
        assertEquals(PermissionMode.PLAN,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "plan"}));
        assertEquals(PermissionMode.ACCEPT_EDITS,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "acceptEdits"}));
        assertEquals(PermissionMode.ACCEPT_EDITS,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "ACCEPT_EDITS"}));
        assertEquals(PermissionMode.DEFAULT,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode", "default"}));
        assertEquals(PermissionMode.PLAN,
                CodeTuiApplication.startupMode(new String[]{"--permission-mode=plan"}), "= 形式也要认");
    }

    @Test
    @DisplayName("不给、给错、或想用它进 BYPASS —— 一律回退 null（由调用方落到配置的 defaultMode）")
    void rejectsBadPermissionMode() {
        assertNull(CodeTuiApplication.startupMode(new String[]{}));
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode"}), "缺值不得读到越界");
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "banana"}));
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "bypass"}),
                "BYPASS 只能由 --dangerously-skip-permissions 进；这里认了就等于开了第二个后门");
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-modex", "plan"}),
                "前缀相近的参数不得误判");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionStartupTest`
Expected: 编译失败，`找不到符号: 方法 startupMode`

- [ ] **Step 3: 实现解析**

`CodeTuiApplication.java`，在 `hasBypassFlag` 附近新增：

```java
    /** {@code --permission-mode} 的取值名（{@code plan}/{@code default}/{@code acceptEdits}）。 */
    private static final String PERMISSION_MODE_FLAG = "--permission-mode";

    /**
     * 解析 {@code --permission-mode <值>} 或 {@code --permission-mode=<值>}；无/非法/想进 BYPASS 一律返回 null
     * （调用方回退到配置文件的 {@code defaultMode}）。
     *
     * <p><b>刻意不认 {@code bypass}</b>：BYPASS 的契约是「仅 {@code --dangerously-skip-permissions} 可进」。
     * 这里认了它，那道显眼的开关就有了一个不显眼的同义词——用户看着命令行以为只是「选了个模式」。
     *
     * <p><b>必须整串精确相等</b>：{@code --permission-modex} 这种前缀相近的参数不得误判（与 hasBypassFlag 同纪律）。
     */
    static PermissionMode startupMode(String[] args) {
        String raw = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (PERMISSION_MODE_FLAG.equals(a)) {
                if (i + 1 >= args.length) {
                    return null;                       // 缺值：别读到越界，也别猜
                }
                raw = args[i + 1];
                break;
            }
            if (a != null && a.startsWith(PERMISSION_MODE_FLAG + "=")) {
                raw = a.substring(PERMISSION_MODE_FLAG.length() + 1);
                break;
            }
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (PermissionMode.BYPASS.name().equalsIgnoreCase(v)) {
            log.warn("--permission-mode 不接受 bypass：全放行只能由 --dangerously-skip-permissions 进。已忽略。");
            return null;
        }
        for (PermissionMode m : PermissionMode.values()) {
            if (m.name().equalsIgnoreCase(v)) {
                return m;
            }
        }
        if ("acceptEdits".equalsIgnoreCase(v)) {       // 兼容 Claude Code 风格的小驼峰
            return PermissionMode.ACCEPT_EDITS;
        }
        log.warn("未知 --permission-mode='{}'，已忽略。可选值：default / acceptEdits / plan。", v);
        return null;
    }
```

- [ ] **Step 4: 接进启动装配**

把既有的引擎构造改成（`CodeTuiApplication.java:68-76` 一带）：

```java
        boolean skipPermissions = hasBypassFlag(args);
        PermissionConfig permissionConfig = PermissionConfigLoader.load(root);
        PermissionMode cliMode = startupMode(args);
        // 优先级：--dangerously-skip-permissions > --permission-mode > 配置文件 defaultMode。
        // 前者是显式的「我知道我在做什么」，不该被一个同时出现的 --permission-mode plan 悄悄改掉语义。
        PermissionMode startMode = skipPermissions ? PermissionMode.BYPASS
                : (cliMode != null ? cliMode : permissionConfig.defaultMode());
        PermissionEngine permissionEngine = new PermissionEngine(root, permissionConfig,
                startMode, skipPermissions);
        if (skipPermissions) {
            state.pushInfo("⚠ 已启用 --dangerously-skip-permissions：除 deny 规则与内置危险检查外，"
                    + "全部工具调用不再询问。");
            if (cliMode != null) {
                state.pushInfo("• 已忽略 --permission-mode " + cliMode.label()
                        + "：--dangerously-skip-permissions 优先级更高。");
            }
        } else if (startMode == PermissionMode.PLAN) {
            state.pushInfo("⏸ 已进入计划模式：只能读取和探索，产出计划后经你批准才会动手。");
        }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionStartupTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java
git commit -m "feat(code-tui): 加 --permission-mode 启动参数（不接受 bypass）"
```

---

## Task 4: `{PERMISSION_MODE}` 系统提示段

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`SYSTEM_TEMPLATE` 约 :161、`.param(...)` 约 :406）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java:266`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PermissionModePromptTest.java`

**背景（实施者必读）**：

- `CodingAgent` **已经持有** `permissionEngine` 字段（全参构造末位）。**不要再加构造参数**——那个类已有 9 个重载，再加一个就是灾难。
- 提示段必须**每回合**渲染：`defaultSystem` 是 build 期烘焙的，而模式在运行期随 `Shift+Tab` 变。`CodingAgent.submit` 已有 per-request 的 `.system(s -> s.param(...))` 钩子（就是注入 `{AGENT_MODEL}` 那句），搭它的车。
- **必须作为 param 值注入**，不得拼进模板字符串——正文里出现花括号会炸 SpringTemplate（`AGENTS.md` / 长期记忆接入时都踩过）。

- [ ] **Step 1: 写失败测试**

新建 `PermissionModePromptTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模式提示段是纯函数，便于单测；真正「有没有被注入进请求」由 Task 4 Step 4 的装配断言 + pty 冒烟兜。
 */
class PermissionModePromptTest {

    @Test
    @DisplayName("只有 PLAN 有内容，其余模式是空串（别给模型灌无关噪音）")
    void onlyPlanHasGuidance() {
        assertEquals("", PermissionModePrompt.of(PermissionMode.DEFAULT));
        assertEquals("", PermissionModePrompt.of(PermissionMode.ACCEPT_EDITS));
        assertEquals("", PermissionModePrompt.of(PermissionMode.BYPASS));
        assertEquals("", PermissionModePrompt.of(null), "null 也要给空串，不能 NPE 掉整个回合");

        String plan = PermissionModePrompt.of(PermissionMode.PLAN);
        assertFalse(plan.isBlank());
        assertTrue(plan.contains("ExitPlanMode"), "必须告诉模型出口在哪");
        assertTrue(plan.contains("计划模式"));
    }

    @Test
    @DisplayName("提示段里不得出现花括号——它作为 param 值注入，带花括号会炸模板引擎")
    void noBracesInGuidance() {
        for (PermissionMode m : PermissionMode.values()) {
            String s = PermissionModePrompt.of(m);
            assertFalse(s.contains("{") || s.contains("}"),
                    m + " 的提示段含花括号，注入时会被 SpringTemplate 当占位符解析：" + s);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionModePromptTest`
Expected: 编译失败，`找不到符号: 类 PermissionModePrompt`

- [ ] **Step 3: 新建提示段（纯函数，单独一个类）**

Create `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionModePrompt.java`:

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

/**
 * 按当前权限模式产出一段系统提示，注入 {@code SYSTEM_TEMPLATE} 的 {@code {PERMISSION_MODE}} 占位符。
 *
 * <p><b>只有 PLAN 有内容</b>：其余模式注入空串。模型在默认档看到「你可以写文件」是废话，
 * 反而稀释真正重要的指令。
 *
 * <p><b>正文里绝不能有花括号</b>：本段<b>作为 param 值</b>注入（不是拼进模板字符串），
 * 但 Spring 的模板引擎会解析渲染后的文本，正文里的 <code>{</code> 会被当占位符炸掉整个回合
 * （项目里 AGENTS.md 与长期记忆接入时都踩过，见记忆 agents-md-project-instructions）。
 * 有一条单测专门钉这一点。
 *
 * <p><b>提示不是护栏</b>：真正拦住写操作的是 {@code PermissionEngine} 的 PLAN 分支（DENY）。
 * 这段话只是让模型<b>知道</b>自己在哪一档、以及出口在哪，从而少撞几次墙、早点去调 ExitPlanMode。
 */
public final class PermissionModePrompt {

    private static final String PLAN_GUIDANCE = """
            当前处于「计划模式」：你只能读取和探索，不能修改任何文件、也不能执行有副作用的命令
            （这不是建议——权限层会直接拒绝这类调用，重试没有意义）。
            请先用 Read / Grep / Glob 与只读命令把现状调查清楚，然后调用 ExitPlanMode 工具，
            把你打算怎么做写成一份 markdown 计划提交给用户；用户批准后才会切换到可以动手的模式。
            """;

    private PermissionModePrompt() {
    }

    /** 该模式对应的提示段；非 PLAN（含 null）一律空串。 */
    public static String of(PermissionMode mode) {
        return mode == PermissionMode.PLAN ? PLAN_GUIDANCE : "";
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionModePromptTest`
Expected: PASS（2 个测试）

- [ ] **Step 5: 接进模板与每回合注入**

① `AgentTools.java` 的 `SYSTEM_TEMPLATE`，在 `{PROJECT_INSTRUCTIONS}` 之后加一行占位符：

```
            {PROJECT_INSTRUCTIONS}

            {PERMISSION_MODE}
            """;
```

② 同文件，在 `WEB_SEARCH_GUIDE_KEY` 附近加常量：

```java
    /** 权限模式提示注入的 param 键；与 SYSTEM_TEMPLATE 里的 {PERMISSION_MODE} 占位符对应。 */
    public static final String PERMISSION_MODE_KEY = "PERMISSION_MODE";
```

③ 同文件的 `.defaultSystem(s -> s.text(SYSTEM_TEMPLATE)...)` 链上补一个默认值——**不补则模板渲染时缺 param 会抛**：

```java
                            .param(WEB_SEARCH_GUIDE_KEY, webSearchGuide)
                            // 装配期给空串占位；真实值由 CodingAgent.submit 每回合覆盖（merge 语义）
                            .param(PERMISSION_MODE_KEY, "")
```

④ `CodingAgent.java:266` 那句 per-request `.system(...)` 扩成两个 param：

```java
                    // 同步覆盖系统提示里的 {AGENT_MODEL} grounding，使模型自报身份与实际所选一致（其余 param 沿用默认，merge 语义）；
                    // {PERMISSION_MODE} 必须每回合重算——模式随 Shift+Tab 运行期变化，而 defaultSystem 是 build 期烘焙的。
                    .system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, modelGrounding)
                            .param(AgentTools.PERMISSION_MODE_KEY, PermissionModePrompt.of(
                                    permissionEngine == null ? null : permissionEngine.mode())))
```

- [ ] **Step 6: 全量回归（模板改动会影响所有装配路径）**

Run: `mvn test -pl springai-code-tui`
Expected: 除既有 flaky `CodingAgentSpikeTest.todoTurnIdBinding` 外全绿。
**若出现 `SpringTemplate` 相关异常**，多半是第 ③ 步的默认 param 漏了——模板里有占位符而 param 缺失会抛。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionModePrompt.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PermissionModePromptTest.java
git commit -m "feat(code-tui): 系统提示按回合注入权限模式段（仅 PLAN 有内容）"
```

---

## Task 5: `PlanRequest` —— 第三个 `ModalRequest` 子类型

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanOutcome.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanResponder.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanRequest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModalRequest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionCancelledException.java`（扩写 javadoc）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PlanRequestTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModalRequestTest.java`（补穷尽性巡查）

**⚠ 新增密封子类型的巡查清单**——Java 17 没有类型模式 `switch`，穷尽性**由人保证**。全仓共 3 处 `instanceof` 链，本任务必须全部走到：

1. `ui/CodeTuiView.java:248` 模态分派（Task 8/9 补 `PlanRequest` 分支）
2. `test/.../agent/ModalRequestTest.java:105` 穷尽性测试（本任务补）
3. `agent/ModalRequest.java:15` 类注释里的示例（本任务顺手更新）

- [ ] **Step 1: 写失败测试**

新建 `PlanRequestTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRequestTest {

    private record Answered(PlanOutcome outcome, String feedback) {}

    @Test
    @DisplayName("cancel() 投 CANCEL——取消期排空循环靠它唤醒工具线程")
    void cancelRespondsCancel() {
        List<Answered> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = new PlanRequest(7L, "# 计划\n- 步骤一",
                (o, f) -> sink.add(new Answered(o, f)));

        r.cancel();

        assertEquals(1, sink.size());
        assertEquals(PlanOutcome.CANCEL, sink.get(0).outcome());
        assertEquals(7L, r.turnId());
    }

    @Test
    @DisplayName("null responder 在构造期就失败——留到排空期会中断循环、让其后的工具线程永久 park")
    void rejectsNullResponder() {
        assertThrows(NullPointerException.class, () -> new PlanRequest(1L, "x", null));
    }

    @Test
    @DisplayName("cancel() 可重复调用且不抛——契约要求（排空循环遍历整条队列）")
    void cancelIsIdempotentAndNeverThrows() {
        List<Answered> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = new PlanRequest(1L, "x", (o, f) -> sink.add(new Answered(o, f)));
        r.cancel();
        r.cancel();
        assertTrue(sink.size() >= 1, "重复取消不得抛异常");
    }

    @Test
    @DisplayName("计划正文为 null 时归一成空串——面板渲染不判空")
    void nullPlanBecomesEmpty() {
        PlanRequest r = new PlanRequest(1L, null, (o, f) -> { });
        assertEquals("", r.plan());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PlanRequestTest`
Expected: 编译失败，`找不到符号: 类 PlanRequest`

- [ ] **Step 3: 建三个新类型**

Create `PlanOutcome.java`:

```java
package io.github.javaside.springai.codetui.agent;

/**
 * 计划审批的结果。前三项对应面板三个选项，{@link #CANCEL} 对应 Esc / 回合被取消。
 */
public enum PlanOutcome {
    /** 批准并切到「自动接受编辑」——工作区内的改动不再逐个问。 */
    APPROVE_ACCEPT_EDITS,
    /** 批准并切到「默认」——逐个确认。 */
    APPROVE_DEFAULT,
    /** 继续完善计划：留在 PLAN，把用户的反馈文本带回给模型。 */
    KEEP_PLANNING,
    /** 中断本回合——工具线程抛 {@link PermissionCancelledException}，UI 走既有 {@code cancelTurnFor}。 */
    CANCEL
}
```

Create `PlanResponder.java`:

```java
package io.github.javaside.springai.codetui.agent;

/**
 * UI → 工具线程的一次性应答口（实现方是 {@code PlanApprovalBridge} 内的一次性队列）。
 *
 * <p>契约与 {@link PermissionResponder} <b>完全一致</b>，逐条复述是因为违反任一条都会造成静默挂死：
 * <ul>
 *   <li><b>实现方必须非阻塞</b>——本口由 UI 线程调用，阻塞就是冻住整个界面；</li>
 *   <li><b>一次性消费</b>：容量 1 的队列 + 非阻塞 {@code offer}，消费方<b>只读一次</b>。
 *       <b>不得写重试循环、不得复用/重新武装同一个 handoff</b>——否则会读到陈旧的 CANCEL，
 *       杀掉用户刚刚批准的计划；</li>
 *   <li><b>实现不得抛异常</b>——取消期排空循环遍历整条模态队列，一个实现抛异常
 *       就让其后的工具线程永久 park。</li>
 * </ul>
 *
 * @see ModalRequest#cancel()
 */
@FunctionalInterface
public interface PlanResponder {
    /**
     * @param outcome  用户的选择
     * @param feedback 仅 {@link PlanOutcome#KEEP_PLANNING} 有意义（用户希望怎么改）；其余情况传空串
     */
    void respond(PlanOutcome outcome, String feedback);
}
```

Create `PlanRequest.java`:

```java
package io.github.javaside.springai.codetui.agent;

import java.util.Objects;

/**
 * 一次计划审批请求：由 {@code PlanApprovalBridge} 构造、经 {@link AgentListener#onPlanSubmitted}
 * 交给 UI；UI 把 {@link #plan} 渲染进 scrollback 并弹三选项面板，用户选完经 {@link #responder}
 * 唤醒阻塞的工具线程。
 *
 * @param turnId    发起回合（迟到过滤）
 * @param plan      模型提交的 markdown 计划正文（null 归一成空串）
 * @param responder UI 应答回调，<b>不可为 null</b>
 */
public record PlanRequest(long turnId, String plan, PlanResponder responder) implements ModalRequest {

    /**
     * 归一 + 拒绝 null responder：<b>在构造期失败，而不是在排空期</b>。
     *
     * <p>理由与 {@link PermissionRequest} 完全相同：排空循环在某个元素上抛 NPE 会中断，
     * 队列里其后的工具线程永不被唤醒，且异常沿 {@code synchronized} 的 {@code cancelCurrent()}
     * 传到 UI 线程的 Esc 处理器。
     */
    public PlanRequest {
        Objects.requireNonNull(responder, "responder 不可为 null：null 会让取消期的排空循环中断，"
                + "队列里其后的工具线程永久 park");
        plan = plan == null ? "" : plan;
    }

    /** {@link ModalRequest} 统一取消入口：投 CANCEL，工具线程随后抛 {@link PermissionCancelledException}。 */
    @Override
    public void cancel() {
        responder.respond(PlanOutcome.CANCEL, "");
    }
}
```

- [ ] **Step 4: 密封接口加 permits**

`ModalRequest.java`：

```java
public sealed interface ModalRequest permits AskRequest, PermissionRequest, PlanRequest {
```

并把类注释里那句示例更新成三分支（它是后人抄写的模板，漏了就会漏分支）：

```java
 * {@code if (r instanceof AskRequest a) … else if (r instanceof PermissionRequest p) …
 *  else if (r instanceof PlanRequest pl) …}
```

- [ ] **Step 5: 扩写取消异常的 javadoc（复用而非新造第三个异常类）**

`PermissionCancelledException.java` 的类注释第一句改为：

```java
/**
 * 审批 / 计划审批期回合被中断（用户选「拒绝并中断本回合」、Esc，或回合被 dispose 中断了工具线程）。
 *
 * <p>{@code PermissionCallback} 与 {@code PlanApprovalBridge} 共用本异常——两者的语义完全一致
 * （「这个回合结束了」），再造一个近乎同名的异常只会让调用方多一个必须记住的分支。
 *
 * <p>与 {@link QuestionCancelledException} 同款：此时回合已被 dispose、会话经 {@code doOnCancel} 回滚，
 * 本异常随流被丢弃，不会走到用户面前。
 */
```

- [ ] **Step 6: 补穷尽性巡查测试**

`ModalRequestTest.java:105` 一带的 instanceof 链补第三分支，并把「permits 全覆盖」钉死：

```java
    @Test
    @DisplayName("permits 列表全覆盖——新增子类型忘了改消费方时这里报警")
    void everyPermittedSubtypeIsHandled() {
        Class<?>[] permitted = ModalRequest.class.getPermittedSubclasses();
        assertEquals(3, permitted.length,
                "ModalRequest 新增/删除了子类型。Java 17 没有类型模式 switch，穷尽性由人保证——"
                        + "请巡查全部 instanceof 链：CodeTuiView 的模态分派、本测试、ModalRequest 类注释里的示例");
        assertEquals(java.util.Set.of("AskRequest", "PermissionRequest", "PlanRequest"),
                java.util.Arrays.stream(permitted).map(Class::getSimpleName)
                        .collect(java.util.stream.Collectors.toSet()));
    }
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest='PlanRequestTest,ModalRequestTest'`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanOutcome.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanResponder.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanRequest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModalRequest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionCancelledException.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PlanRequestTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModalRequestTest.java
git commit -m "feat(code-tui): 加 PlanRequest 作为第三个模态请求类型"
```

---

## Task 6: `PlanApprovalBridge`（阻塞握手）+ listener 接缝

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridge.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridgeTest.java`

**背景**：照抄 `UserQuestionBridge` 的形状（`agent/UserQuestionBridge.java`）——一次性 `ArrayBlockingQueue(1)`、`take()` 阻塞工具线程、`InterruptedException` 时**重新置上中断位**再抛。

- [ ] **Step 1: 写失败测试**

新建 `PlanApprovalBridgeTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanApprovalBridgeTest {

    /** 捕获请求、不应答的 listener（模拟 UI 还没选）。 */
    private static class Capturing implements AgentListenerAdapter {
        final AtomicReference<PlanRequest> seen = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);
        @Override public void onPlanSubmitted(long turnId, PlanRequest request) {
            seen.set(request);
            arrived.countDown();
        }
    }

    @Test
    @DisplayName("批准：工具线程被唤醒，返回串告诉模型可以开始，并按选项切了模式")
    void approveWakesToolThreadAndSwitchesMode() throws Exception {
        Capturing listener = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, switched::set);

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划\n- 步骤一")));
        tool.start();

        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS), "请求应立刻交给 listener，不得阻塞在回调里");
        listener.seen.get().responder().respond(PlanOutcome.APPROVE_ACCEPT_EDITS, "");
        tool.join(2000);

        assertTrue(result.get().contains("批准"), "返回串要让模型知道可以动手了：" + result.get());
        assertEquals(PermissionMode.ACCEPT_EDITS, switched.get(), "选项一应切到自动接受编辑");
    }

    @Test
    @DisplayName("继续完善：模式不动，反馈文本原样带回给模型")
    void keepPlanningCarriesFeedback() throws Exception {
        Capturing listener = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, switched::set);

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划")));
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        listener.seen.get().responder().respond(PlanOutcome.KEEP_PLANNING, "先补上回滚方案");
        tool.join(2000);

        assertTrue(result.get().contains("先补上回滚方案"), "反馈必须原样回给模型：" + result.get());
        assertEquals(null, switched.get(), "继续完善时不得切模式");
    }

    @Test
    @DisplayName("取消：工具线程抛 PermissionCancelledException（回合已被 dispose，异常随流丢弃）")
    void cancelThrows() throws Exception {
        Capturing listener = new Capturing();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, m -> { });

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                bridge.handle("# 计划");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        listener.seen.get().cancel();          // 走 ModalRequest 统一取消入口
        tool.join(2000);

        assertNotNull(thrown.get());
        assertEquals(PermissionCancelledException.class, thrown.get().getClass());
    }

    @Test
    @DisplayName("回合被 dispose 中断工具线程 → 抛异常且中断位被重新置上（第二条逃生口）")
    void interruptEscapes() throws Exception {
        Capturing listener = new Capturing();
        PlanApprovalBridge bridge = new PlanApprovalBridge(listener, m -> { });

        AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try {
                bridge.handle("# 计划");
            } catch (PermissionCancelledException e) {
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        tool.start();
        assertTrue(listener.arrived.await(2, TimeUnit.SECONDS));

        tool.interrupt();
        tool.join(2000);

        assertEquals(Boolean.TRUE, interruptFlag.get(),
                "吞掉 InterruptedException 而不重新置位，会让上层再也看不到取消信号");
    }

    @Test
    @DisplayName("listener 默认实现不得挂死——没接管 UI 的落地端应当「继续完善计划」而不是永久 park")
    void defaultListenerDoesNotHang() throws Exception {
        AgentListenerAdapter bare = new AgentListenerAdapter() { };   // 不覆写 onPlanSubmitted
        PlanApprovalBridge bridge = new PlanApprovalBridge(bare, m -> { });

        AtomicReference<String> result = new AtomicReference<>();
        Thread tool = new Thread(() -> result.set(bridge.handle("# 计划")));
        tool.start();
        tool.join(2000);

        assertTrue(tool.getState() == Thread.State.TERMINATED, "默认实现让工具线程永久 park 就是静默挂死");
        assertNotNull(result.get());
    }
}
```

> **`AgentListenerAdapter`**：`AgentListener` 有十几个抽象方法，测试里逐个实现是噪音。
> 若仓库还没有这个测试适配器，本步一并新建
> `src/test/java/io/github/javaside/springai/codetui/agent/AgentListenerAdapter.java`：
> 一个 `interface AgentListenerAdapter extends AgentListener`，把所有抽象方法给**空默认实现**。
> 先 `grep -rn "implements AgentListener" src/test | head` 看有没有现成的可复用。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PlanApprovalBridgeTest`
Expected: 编译失败，`找不到符号: 类 PlanApprovalBridge`

- [ ] **Step 3: 实现桥**

Create `PlanApprovalBridge.java`:

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * {@code ExitPlanMode} 的落地端：把工具线程 ↔ UI 线程之间的一次计划审批变成阻塞握手。
 * 形状照抄 {@link UserQuestionBridge}（一次性队列 + {@code take()}），只是载荷不同。
 *
 * <p><b>为何阻塞</b>：工具的 {@code call()} 在工具线程上同步执行，必须返回一个字符串才算执行完毕。
 * 本桥把计划经 {@link AgentListener#onPlanSubmitted} 交给 UI，然后阻塞在一次性队列上，直到用户选完。
 *
 * <p><b>切模式发生在工具线程</b>：{@code PermissionEngine.mode} 是 {@code volatile}，
 * 本来就是按「UI 线程与工具线程都可能写」设计的（见其类注释）。这里经构造注入的
 * {@code modeSwitch} 回调去改，而不是直接依赖引擎类型——桥只管握手，不必认识权限包的实现细节。
 *
 * <p><b>活性依赖（本类不自保）</b>：{@code take()} 无超时，解除阻塞完全靠两条外部逃生口——
 * ① UI 总会应答（面板保证，且 {@link AgentListener#onPlanSubmitted} 的默认实现也会立刻应答）；
 * ② 回合被 dispose 时框架中断本线程。二者缺失则线程永久 park，<b>而它持着回合</b>。
 */
public final class PlanApprovalBridge {

    private final AgentListener listener;
    private final Consumer<PermissionMode> modeSwitch;

    public PlanApprovalBridge(AgentListener listener, Consumer<PermissionMode> modeSwitch) {
        this.listener = listener;
        this.modeSwitch = modeSwitch;
    }

    /** 提交一份计划、阻塞到用户选完；返回给模型的字符串。 */
    public String handle(String plan) {
        long turnId = ToolEventCallback.currentTurnId();
        ArrayBlockingQueue<Object[]> handoff = new ArrayBlockingQueue<>(1);
        PlanResponder responder = (outcome, feedback) ->
                handoff.offer(new Object[]{outcome, feedback == null ? "" : feedback});

        listener.onPlanSubmitted(turnId, new PlanRequest(turnId, plan, responder));

        Object[] answer;
        try {
            answer = handoff.take();                 // 阻塞工具线程直到 UI 应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();       // 重新置位：吞掉它上层就再也看不到取消信号
            throw new PermissionCancelledException();
        }
        PlanOutcome outcome = (PlanOutcome) answer[0];
        String feedback = (String) answer[1];
        switch (outcome) {
            case APPROVE_ACCEPT_EDITS:
                modeSwitch.accept(PermissionMode.ACCEPT_EDITS);
                return "计划已批准，开始执行。后续工作区内的改动会自动放行，无需逐个确认。";
            case APPROVE_DEFAULT:
                modeSwitch.accept(PermissionMode.DEFAULT);
                return "计划已批准，开始执行。后续每个有副作用的操作仍会逐个请求用户确认。";
            case KEEP_PLANNING:
                return "用户希望继续完善计划：" + feedback
                        + "\n请据此修改方案，然后再次调用 ExitPlanMode 提交。仍处于计划模式，不要动手改。";
            case CANCEL:
            default:
                throw new PermissionCancelledException();
        }
    }
}
```

- [ ] **Step 4: 加 listener 接缝**

`AgentListener.java`，在 `onPermissionRequested` 之后加：

```java
    /**
     * 模型经 {@code ExitPlanMode} 提交了一份计划：UI 应把正文渲染进 scrollback、把请求排进模态队列、
     * 弹三选项面板，最终经 {@code request.responder()} 应答。落地端会阻塞工具线程直到应答
     * （见 {@link PlanApprovalBridge}）。
     *
     * <p><b>默认实现直接「继续完善计划」而不是空实现</b>：空实现会让工具线程永久 park，
     * 而它持着回合——整个 agent 静默挂死。没有接管计划 UI 的落地端（回显桩 / 测试桩）
     * 应当给一个能让回合继续下去的答复。
     *
     * <p>覆写本方法的实现须守与 {@link #onPermissionRequested} <b>完全相同</b>的两条禁令：
     * <b>不得调 {@code super}</b>（默认实现会立刻应答，一次性口被消费掉，用户随后的真实选择被丢弃）、
     * <b>不得在本回调里阻塞</b>（{@code ConversationState} 的 listener 方法是 {@code synchronized}，
     * 阻塞会冻住整个 TUI）。
     */
    default void onPlanSubmitted(long turnId, PlanRequest request) {
        request.responder().respond(PlanOutcome.KEEP_PLANNING, "（当前界面不支持计划审批）");
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PlanApprovalBridgeTest`
Expected: PASS（5 个测试）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridge.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridgeTest.java
git commit -m "feat(code-tui): 加 PlanApprovalBridge 阻塞握手与 onPlanSubmitted 接缝"
```

---

## Task 7: `ExitPlanMode` 工具（注册 + 登记表）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridge.java`（加 `ToolCallback` 工厂）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（装配）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ExitPlanModeToolTest.java`

**背景（实施者必读）**：

- 工具的**注册名取 `@Tool` 注解 / builder 里给的名字，不是方法名**（记忆 `tool-registered-name-is-annotation-not-method`）。这里用 `FunctionToolCallback.builder("ExitPlanMode", ...)`，注册名就是 `ExitPlanMode`。
- 参照现成写法：`ReloadableSkillTool.emptyDelegate()`（`FunctionToolCallback.builder(名, (输入类型, ToolContext) -> 结果).description(...).inputType(...).build()`）。
- 装配时必须与其它工具一样被 `PermissionCallback(ToolEventCallback(...))` 装饰——**`ToolEventCallback` 是 `currentTurnId()` 的来源**，不装饰的话桥里拿到的 turnId 是错的，请求会被 UI 当迟到丢弃。
- 期 1 有一个**登记表完整性测试**（对运行时工具集全量比对，未登记即失败）。新工具不登记它就会红——那正是它的作用。

- [ ] **Step 1: 写失败测试**

新建 `ExitPlanModeToolTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.ToolCategory;
import io.github.javaside.springai.codetui.agent.permission.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPlanModeToolTest {

    private static class Capturing implements AgentListenerAdapter {
        final AtomicReference<PlanRequest> seen = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);
        @Override public void onPlanSubmitted(long turnId, PlanRequest request) {
            seen.set(request);
            arrived.countDown();
        }
    }

    @Test
    @DisplayName("注册名是 ExitPlanMode，且已登记为 INTERNAL——否则它自己会被 PLAN 模式拦住")
    void registeredAsInternal() {
        Capturing l = new Capturing();
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(new PlanApprovalBridge(l, m -> { }));
        assertEquals("ExitPlanMode", tool.getToolDefinition().name());

        ToolRegistry.Entry e = ToolRegistry.lookup("ExitPlanMode");
        assertEquals(ToolCategory.INTERNAL, e.category(),
                "没登记就是 UNKNOWN，PLAN 模式下会 DENY 掉它——计划模式成了死胡同");
    }

    @Test
    @DisplayName("call 走桥：计划正文原样交给 UI，用户选完的结果原样回给模型")
    void callGoesThroughBridge() throws Exception {
        Capturing l = new Capturing();
        AtomicReference<PermissionMode> switched = new AtomicReference<>();
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(new PlanApprovalBridge(l, switched::set));

        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(tool.call("{\"plan\":\"# 我的计划\\n- 第一步\"}")));
        t.start();

        assertTrue(l.arrived.await(2, TimeUnit.SECONDS));
        assertTrue(l.seen.get().plan().contains("# 我的计划"), "计划正文要原样传到 UI");

        l.seen.get().responder().respond(PlanOutcome.APPROVE_DEFAULT, "");
        t.join(2000);

        assertTrue(out.get().contains("批准"));
        assertEquals(PermissionMode.DEFAULT, switched.get());
    }

    @Test
    @DisplayName("描述里必须写清「什么时候调它」——否则模型在计划模式下不知道出口在哪")
    void descriptionTellsWhenToCall() {
        ToolCallback tool = PlanApprovalBridge.exitPlanModeTool(
                new PlanApprovalBridge(new Capturing(), m -> { }));
        String d = tool.getToolDefinition().description();
        assertTrue(d.contains("计划模式") || d.contains("plan mode"), "描述：" + d);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ExitPlanModeToolTest`
Expected: 编译失败，`找不到符号: 方法 exitPlanModeTool`

- [ ] **Step 3: 加工具工厂 + 登记**

① `PlanApprovalBridge.java` 末尾加：

```java
    /** {@code ExitPlanMode} 的入参：一份 markdown 计划。 */
    public record PlanInput(String plan) {
    }

    /**
     * 把桥包成名为 {@code ExitPlanMode} 的 {@link org.springframework.ai.tool.ToolCallback}。
     *
     * <p><b>注册名是这里给的字符串，不是方法名</b>——权限规则、登记表、allow/deny 过滤全按注册名匹配
     * （项目里踩过：{@code askUserQuestion} 方法名不匹配 {@code AskUserQuestionTool} 注册名，deny 静默失效）。
     */
    public static org.springframework.ai.tool.ToolCallback exitPlanModeTool(PlanApprovalBridge bridge) {
        return org.springframework.ai.tool.function.FunctionToolCallback
                .builder("ExitPlanMode",
                        (PlanInput in, org.springframework.ai.chat.model.ToolContext ctx) ->
                                bridge.handle(in == null ? "" : in.plan()))
                .description("""
                        提交一份实施计划，请用户批准后再开始动手。

                        什么时候调用：你处于「计划模式」（系统提示里会告知），已经把现状调查清楚、
                        想好了要怎么做。把方案写成 markdown 传给 plan 参数。

                        用户可以选择「批准并自动接受编辑」「批准并逐个确认」或「继续完善计划」。
                        批准后模式会自动切换，你才能修改文件；若用户要求继续完善，本工具会把他的
                        反馈返回给你，据此改完再提交一次。

                        不在计划模式时不要调用它。
                        """)
                .inputType(PlanInput.class)
                .build();
    }
```

② `ToolRegistry.java` 的 `static {}` 块，在内部工具那一段加一行：

```java
        put("ExitPlanMode",        ToolCategory.INTERNAL, null, false);   // 提交计划本身无副作用，PLAN 下必须放行
```

- [ ] **Step 4: 装配进主 agent**

`AgentTools.java` 的工具组装处（与 `AskUserQuestionTool` / `Skill` 同一段），加：

```java
        // ExitPlanMode：计划模式的唯一出口。与其它工具一样被 PermissionCallback(ToolEventCallback(...)) 装饰——
        // ToolEventCallback 是 currentTurnId() 的来源，不装饰的话桥里拿到的 turnId 是错的，
        // 请求会被 UI 当作「迟到」直接丢弃，工具线程随之永久 park。
        PlanApprovalBridge planBridge = new PlanApprovalBridge(listener, permissionEngine::setMode);
        callbacks.add(new PermissionCallback(
                new ToolEventCallback(PlanApprovalBridge.exitPlanModeTool(planBridge), listener),
                permissionEngine, listener));
```

> **实施提示**：上面的 `callbacks.add(...)` 是示意——照抄该文件里**紧邻的那个工具**的实际装饰写法与变量名，
> 不要凭这段伪代码硬套。判据是：`ExitPlanMode` 出现在最终工具集里，且被两层装饰包住。
>
> **子 agent 不注册它**：子 agent 没有自己的模式（继承主会话），提交计划这件事只属于主 agent。
> 若装配代码里主/子共用一个工具列表，须显式把它排除在子 agent 之外——照 `AutoMemoryTools`「仅主 agent」的既有做法。

- [ ] **Step 5: 跑测试确认通过 + 登记表完整性**

Run: `mvn test -pl springai-code-tui -Dtest='ExitPlanModeToolTest,PermissionEnginePlanModeTest'`
Expected: PASS——**包括 Task 2 Step 4 里那条暂时红着的 `internalToolsAllowed`**

Run: `mvn test -pl springai-code-tui -Dtest='*ToolRegistry*,*Security*'`
Expected: PASS（登记表完整性测试认得新工具）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PlanApprovalBridge.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ExitPlanModeToolTest.java
git commit -m "feat(code-tui): 加 ExitPlanMode 工具并登记为 INTERNAL"
```

---

## Task 8: `ConversationState` 接纳 `PlanRequest`

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java:445` 一带
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStatePlanTest.java`

**背景**：模态队列（`Deque<ModalRequest> modals`，上限 `MODAL_QUEUE_CAP`）、`offerModal`、`cancelCurrent()` 的排空循环都已就位，**不需要改**。本任务只加一个 listener 方法。

**关键取舍**：迟到 / 队满时应答什么？

- **迟到**（`turnId != acceptingTurnId`）→ `CANCEL`。那个回合已经取消/切换，让工具线程抛异常随流丢弃即可；回 `KEEP_PLANNING` 反而会让一个已死的回合继续跑。
- **队满** → `KEEP_PLANNING` + 一行用户可见提示。计划审批不该因为面板拥挤就把整个回合杀掉。

- [ ] **Step 1: 写失败测试**

新建 `ConversationStatePlanTest.java`：

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStatePlanTest {

    private static PlanRequest req(long turnId, List<PlanOutcome> sink) {
        return new PlanRequest(turnId, "# 计划", (o, f) -> sink.add(o));
    }

    @Test
    @DisplayName("当前回合的计划请求进模态队列，不立刻应答（等 UI 弹面板）")
    void currentTurnEnqueues() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();
        PlanRequest r = req(1L, sink);

        s.onPlanSubmitted(1L, r);

        assertSame(r, s.peekModal());
        assertTrue(sink.isEmpty(), "入队阶段不得应答——那会把用户的真实选择挤掉");
    }

    @Test
    @DisplayName("迟到请求（回合已切换）立刻 CANCEL，不弹面板")
    void staleTurnCancelled() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(2L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();

        s.onPlanSubmitted(1L, req(1L, sink));

        assertEquals(List.of(PlanOutcome.CANCEL), sink);
        assertTrue(s.peekModal() == null, "迟到请求不该进队列");
    }

    @Test
    @DisplayName("取消回合会唤醒排在队列里的计划请求——否则工具线程永久 park")
    void cancelDrainsPlanRequests() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1L);
        List<PlanOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPlanSubmitted(1L, req(1L, sink));

        s.cancelCurrent();

        assertEquals(List.of(PlanOutcome.CANCEL), sink);
    }
}
```

> **`cancelCurrent()` 的确切方法名/签名以仓库现状为准**——若不同，照 `CodeTuiViewPermissionTest`
> 里既有的取消用例抄写调用方式，别自己发明。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStatePlanTest`
Expected: 编译失败，`找不到符号: 方法 onPlanSubmitted`

- [ ] **Step 3: 实现**

`ConversationState.java`，在 `onPermissionRequested` 之后加：

```java
    /**
     * 计划审批请求入队。
     *
     * <p><b>迟到 → CANCEL，队满 → KEEP_PLANNING</b>，两条路径都<b>必须</b>应答——静默丢弃
     * 就是工具线程永久 park。二者刻意不同：迟到的那个回合已经取消/切换，让它抛异常随流丢弃即可；
     * 而队满只是面板挤，不该因此杀掉整个回合，故给一个能让模型继续下去的答复 + 一行用户看得见的提示。
     *
     * <p><b>不阻塞</b>（契约要求）：本方法与 {@link #drainPending()} / {@link #cancelCurrent()}
     * 共用同一把监视器锁，这里一旦阻塞冻住的是<b>整个 TUI</b>。
     */
    @Override
    public synchronized void onPlanSubmitted(long turnId, PlanRequest request) {
        if (turnId != acceptingTurnId) {
            request.responder().respond(PlanOutcome.CANCEL, "");
            return;
        }
        if (!offerModal(request)) {
            pending.add(new OutputLine("⚠ 待处理的模态请求过多（已达上限 " + MODAL_QUEUE_CAP
                    + "），本次计划未能展示：请先处理面板里的请求", OutputLine.Kind.ERROR));
            request.responder().respond(PlanOutcome.KEEP_PLANNING,
                    "（界面繁忙，未能展示计划，请稍后重新提交）");
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStatePlanTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStatePlanTest.java
git commit -m "feat(code-tui): ConversationState 接纳计划审批请求"
```

---

## Task 9: 计划审批面板（UI）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（模态分派 :248、面板、按键、`finishPlan`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPlanTest.java`

**设计要点**：

1. **计划正文进 scrollback，不进面板**。一份计划几十行，塞进行内面板会把输入框顶出屏幕。进入模态时用现成的 markdown 渲染路径打一次（照 `printer` 打助手正文的既有写法），面板只留标题 + 三个选项。
2. **「继续完善计划」需要一段反馈文本** → 选中第三项按 Enter 后进入**自由文本子模式**，照搬作答面板的 `askFreeText` + `askInput`（`TextAreaState`）那套；再按 Enter 提交，Esc 退回选项。
3. **Esc 在选项态 = 中断本回合**，必须走既有 `cancelTurnFor`（dispose + `doOnCancel` 回滚会话），**不能只 responder.cancel**——否则残留悬空 `tool_calls`，下一条消息 400。
4. **不得存在「既不应答也不取消」的出口**：面板背后 park 着一个持有回合的工具线程。

- [ ] **Step 1: 写失败测试**

新建 `CodeTuiViewPlanTest.java`：

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewPlanTest {

    private record Answer(PlanOutcome outcome, String feedback) {}

    private static KeyEvent enter() { return KeyEvent.ofKey(KeyCode.ENTER); }

    private static CodeTuiView inPlanModal(ConversationState state, PlanRequest r, Path root) {
        state.onTurnStarted(1L);
        state.onPlanSubmitted(1L, r);
        CodeTuiView v = new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root);
        v.tickForTest();          // 让 drain 侦测到队首模态并进入模态态
        return v;
    }

    private static PlanRequest req(List<Answer> sink) {
        return new PlanRequest(1L, "# 计划\n\n- 第一步\n- 第二步",
                (o, f) -> sink.add(new Answer(o, f)));
    }

    @Test
    @DisplayName("计划正文进 scrollback，面板只有标题和三个选项")
    void planBodyGoesToScrollback(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = inPlanModal(state, req(new CopyOnWriteArrayList<>()), root);

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("批准，自动接受编辑"), "缺选项一：\n" + screen);
        assertTrue(screen.contains("批准，逐个确认"), "缺选项二：\n" + screen);
        assertTrue(screen.contains("继续完善计划"), "缺选项三：\n" + screen);
        assertTrue(v.drainedForTest().contains("第一步"),
                "计划正文必须打进 scrollback，而不是挤在面板里");
    }

    @Test
    @DisplayName("1/2 快选 + Enter → 对应的批准结果")
    void approveOptions(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);
        v.feedKeyForTest(KeyEvent.ofChar('1'));
        v.feedKeyForTest(enter());
        assertEquals(PlanOutcome.APPROVE_ACCEPT_EDITS, sink.get(0).outcome());

        List<Answer> sink2 = new CopyOnWriteArrayList<>();
        CodeTuiView v2 = inPlanModal(new ConversationState(), req(sink2), root);
        v2.feedKeyForTest(KeyEvent.ofChar('2'));
        v2.feedKeyForTest(enter());
        assertEquals(PlanOutcome.APPROVE_DEFAULT, sink2.get(0).outcome());
    }

    @Test
    @DisplayName("选「继续完善」→ 进自由文本子模式，打字后 Enter 把反馈带回")
    void keepPlanningCollectsFeedback(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);

        v.feedKeyForTest(KeyEvent.ofChar('3'));
        v.feedKeyForTest(enter());
        assertTrue(sink.isEmpty(), "选第三项后应先收反馈，不能立刻应答");

        for (char c : "补上回滚".toCharArray()) {
            v.feedKeyForTest(KeyEvent.ofChar(c));
        }
        v.feedKeyForTest(enter());

        assertEquals(PlanOutcome.KEEP_PLANNING, sink.get(0).outcome());
        assertEquals("补上回滚", sink.get(0).feedback());
    }

    @Test
    @DisplayName("Esc = 中断本回合：应答 CANCEL 且回合结束（不能只 responder.cancel）")
    void escCancelsTurn(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        ConversationState state = new ConversationState();
        CodeTuiView v = inPlanModal(state, req(sink), root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(PlanOutcome.CANCEL, sink.get(0).outcome());
        assertTrue(state.isIdle(), "Esc 必须走 cancelTurnFor 结束回合，否则残留悬空 tool_calls → 下条 400");
    }

    @Test
    @DisplayName("任何按键都不得让面板停在「既不应答也不取消」的状态")
    void noDeadEnd(@TempDir Path root) {
        List<Answer> sink = new CopyOnWriteArrayList<>();
        CodeTuiView v = inPlanModal(new ConversationState(), req(sink), root);

        for (char c : "xyz789".toCharArray()) {      // 越界数字与无关字符
            v.feedKeyForTest(KeyEvent.ofChar(c));
        }
        assertTrue(sink.isEmpty(), "无关键不该隐式应答");

        v.feedKeyForTest(enter());
        assertEquals(1, sink.size(), "Enter 之后必须恰好应答一次");
    }
}
```

> **`drainedForTest()`**：若 `CodeTuiView` 没有这个钩子，照 `CodeTuiViewPermissionTest` 里
> `drained(state)` 的既有写法取 scrollback 文本，别新造钩子。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPlanTest`
Expected: FAIL——屏幕上没有任何选项（模态分派还不认 `PlanRequest`）

- [ ] **Step 3: 模态分派加分支**

`CodeTuiView.java:248` 的 instanceof 链末尾加（**这是 Task 5 巡查清单的第 1 处**）：

```java
            } else if (head instanceof PlanRequest pl) {
                activePlan = pl;
                planOpt = 0;
                planFeedback = false;
                planInput.clear();
                // 计划正文一次性打进 scrollback：面板放不下几十行，且 markdown 要走既有渲染
                printer.printAssistantMarkdown(pl.plan());
            }
```

新增字段（与 `activePermission` / `permOpt` 并排）：

```java
    private PlanRequest activePlan;                   // 当前正在审批的计划（null=非计划态）
    private int planOpt;                              // 计划面板高亮项下标
    private boolean planFeedback;                     // 自由文本子模式（选了「继续完善计划」）
    private final TextAreaState planInput = new TextAreaState();   // 反馈文本缓冲
```

> `printer.printAssistantMarkdown(...)` 是示意——用 `ScrollbackPrinter` 里**已有的**打助手 markdown 正文的
> 那个方法，方法名以仓库现状为准。**注意「一个 OutputLine = 一个物理行」**：多行文本必须按 `\n` 逐行拆，
> 直接塞一个多行字符串会被 `println` 塌成一行截断（记忆 `one-outputline-is-one-physical-line`）。

- [ ] **Step 4: 面板 + 按键 + 应答**

`render()` 的 `column(...)` 里，在权限面板那一行之后加：

```java
                scope(activePlan != null, planChildren()),          // 计划审批面板
```

面板与按键（放在权限面板方法附近）：

```java
    /** 计划审批面板：标题 + 三选项；选了「继续完善」后换成一行输入。正文已在进入模态时打进 scrollback。 */
    private Element[] planChildren() {
        // scope 每帧 eager 求值：首行必须判空，否则非计划态每帧崩渲染线程
        if (activePlan == null) return new Element[0];
        List<Element> els = new ArrayList<>();
        els.add(text("  📋 计划待批准").style(PICK_TITLE));
        if (planFeedback) {
            els.add(text("     请说明希望怎么改（Enter 提交 · Esc 返回选项）").style(DIM));
            els.add(text("     ❯ " + planInput.text()).style(PICK_SEL));
            return els.toArray(new Element[0]);
        }
        String[] labels = {"批准，自动接受编辑", "批准，逐个确认", "继续完善计划"};
        for (int i = 0; i < labels.length; i++) {
            boolean sel = i == planOpt;
            els.add(text("  " + (sel ? "❯ " : "  ") + (i + 1) + ". " + labels[i])
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /**
     * 计划审批按键：↑↓/kj 移动、1–3 移动高亮（不隐式确认）、Enter 确认、Esc = 中断本回合。始终 HANDLED。
     *
     * <p>与审批面板同一条纪律：只有「移动高亮（面板留着）」与「应答并退出」两类结果——
     * 请求背后 park 着一个持有回合的工具线程，面板卡住就是 agent 静默挂死。
     */
    private EventResult onPlanKey(KeyEvent k) {
        if (planFeedback) {
            if (k.isCancel()) { planFeedback = false; planInput.clear(); return EventResult.HANDLED; }
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                finishPlan(PlanOutcome.KEEP_PLANNING, planInput.text().trim());
                return EventResult.HANDLED;
            }
            inputKeys.handleKeyEvent(k, true);      // 复用 textArea 的完整编辑键处理
            return EventResult.HANDLED;
        }
        if (k.isCancel()) { finishPlan(PlanOutcome.CANCEL, ""); return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { planOpt = (planOpt + 2) % 3; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { planOpt = (planOpt + 1) % 3; return EventResult.HANDLED; }
        for (int i = 0; i < 3; i++) {
            if (k.isChar((char) ('1' + i))) { planOpt = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            if (planOpt == 2) { planFeedback = true; planInput.clear(); return EventResult.HANDLED; }
            finishPlan(planOpt == 0 ? PlanOutcome.APPROVE_ACCEPT_EDITS : PlanOutcome.APPROVE_DEFAULT, "");
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /**
     * 应答并退出计划模态；CANCEL 额外走既有回合取消路径。
     *
     * <p>先摘队列再应答，且按<b>身份</b>摘（record 的 equals 会摘错人，让被误摘者的线程永久 park）。
     * CANCEL 分支不自己 respond——交给 {@code cancelTurnFor} 走 {@code ModalRequest.cancel()}，
     * 与问询/审批共用同一套已验证的回滚（只 respond 不 dispose 会残留悬空 tool_calls，下条消息 400）。
     */
    private void finishPlan(PlanOutcome outcome, String feedback) {
        PlanRequest req = activePlan;
        activePlan = null;
        planOpt = 0;
        planFeedback = false;
        planInput.clear();
        state.removeModal(req);
        if (outcome == PlanOutcome.CANCEL) {
            cancelTurnFor(req, "已取消当前回合");
            return;
        }
        req.responder().respond(outcome, feedback);
        if (outcome == PlanOutcome.APPROVE_ACCEPT_EDITS || outcome == PlanOutcome.APPROVE_DEFAULT) {
            // 模式由桥在工具线程上切；这里只回显，措辞不把结果说成既成事实
            state.pushInfo("✓ 计划已批准，开始执行");
        } else {
            state.pushInfo("• 已把你的意见交回给模型，继续完善计划");
        }
    }
```

`onInputKey` 里，在 `if (activePermission != null) return onPermissionKey(k);` **之后**加一行：

```java
        if (activePlan != null) return onPlanKey(k);    // 计划审批模态（背后同样 park 着工具线程）
```

`statusLine()` 顶部（`activeAsk` 分支附近）加：

```java
        if (activePlan != null) return text(planFeedback
                ? "✎ 写下修改意见 · Enter 提交 · Esc 返回选项"
                : "📋 计划待批准 · ↑↓ 选择 · 1-3 快选 · Enter 确认 · Esc 中断").style(THINK);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPlanTest`
Expected: PASS（5 个测试）

- [ ] **Step 6: 变异实测（本分支的硬规矩：绿了不算数）**

把 Step 4 里 `finishPlan` 的 `state.removeModal(req)` 临时注释掉，重跑 `CodeTuiViewPlanTest`。
Expected: **有用例变红**（面板不退出/重复应答）。若全绿，说明测试没覆盖到退出路径，**补测试**再恢复代码。

- [ ] **Step 7: 全量回归 + 提交**

Run: `mvn test -pl springai-code-tui`
Expected: 除既有 flaky 外全绿

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPlanTest.java
git commit -m "feat(code-tui): 计划审批面板（三选项 + 反馈输入 + Esc 中断回合）"
```

---

## Task 10: pty 实机冒烟 + 文档

**Files:**
- Modify: `springai-code-tui/src/test/resources/scripts/permission_smoke.py`
- Modify: `springai-code-tui/README.md`
- Modify: `docs/superpowers/specs/2026-07-31-permission-mode-design.md`（分期表勾掉期 2）

**背景**：脚本自带一个进程内 DeepSeek SSE 桩模型，**不需要 key、不需要网络**。跑法：

```bash
mvn -q -o package -pl springai-code-tui -DskipTests
mvn -q -o dependency:build-classpath -pl springai-code-tui -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && python3 src/test/resources/scripts/permission_smoke.py
```

**改完代码必须重新 `package`**，否则跑的是旧 jar（本分支踩过）。

- [ ] **Step 1: 桩模型加一条「提交计划」的触发**

照脚本里既有的 marker 机制（如 `PUSHNOW` / `SSHNOW`），加一个 `PLANNOW`：收到含该标记的用户消息时，桩模型发一个调用 `ExitPlanMode` 的 tool_call，入参为 `{"plan":"# 测试计划\n\n- 第一步\n- 第二步"}`。**照抄既有 marker 的写法**，不要另起一套。

- [ ] **Step 2: 加模式循环断言**

`check_mode_cycle` 现在只验两档，改成三档：

```python
    session.write(SHIFT_TAB)
    session.wait_for(MODE_ACCEPT)
    session.write(SHIFT_TAB)
    session.wait_for("权限模式：计划模式")
    print("Shift+Tab OK: 第三档是计划模式.")
    session.write(SHIFT_TAB)
    session.wait_for(MODE_DEFAULT)
    assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")
    print("Shift+Tab OK: 三档循环闭合，BYPASS 未混入.")
```

并给常驻标识补一条：切到计划模式、按键清掉 notice 后，屏幕上仍有 `⏸ 计划模式`。

> **Task 1 交办的两条只有 pty 能证伪的疑虑，本步一并验掉**：
> ① `MODE_PLAN` 用 `Color.indexed(115)`（冷薄荷），此前只在离屏 Buffer 里验了 `Style` 相等——
> **没验过真实 ANSI 下与相邻文字的对比度**。照 `check_panel_layout` 里既有的 `row_backgrounds`
> 手法，断言该行无背景色残留，并把整行打进 `print_screen` 供人眼复核配色。
> ② `⏸`（U+23F8）是**宽度不确定**的符号，部分终端按双宽渲染。状态行本就接近终端宽度，
> 挤位会导致换行、撑破单行布局。断言：切到计划模式后状态行**仍只占一个物理行**
> （用 `find_row` 取状态行行号，确认其下一行仍是预期内容而非续行）。
> 若实测发现 `⏸` 排版有问题，换成窄符号（如 `>>`/`::`）并同步改 `CodeTuiView#modeTag` 与单测。

- [ ] **Step 3: 加计划审批面板断言**

新增 `check_plan_approval(session)`：

```python
def check_plan_approval(session):
    """PLAN 模式下模型提交计划 → 正文进 scrollback、面板三选项 → 批准 → 模式自动切换。

    这条是期 2 的核心链路：工具线程阻塞在桥上，UI 弹面板，用户选完线程被唤醒并切模式。
    """
    session.write(SHIFT_TAB); session.write(SHIFT_TAB)      # 切到计划模式
    session.wait_for("权限模式：计划模式")

    session.write(("做个方案 " + PLAN_MARKER).encode() + b"\r")
    session.wait_for("📋 计划待批准", timeout=40)
    session.pump(0.6)
    print_screen("PLAN APPROVAL PANEL", session.screen.display)

    text = session.screen_text()
    for needle in ("第一步", "1. 批准，自动接受编辑", "2. 批准，逐个确认", "3. 继续完善计划"):
        if needle not in text:
            die("计划面板缺少 %r（正文应在 scrollback、选项应在面板）" % needle, session.screen.display)
    if "1-3 快选" not in text:
        die("状态栏没有切成计划审批的提示", session.screen.display)

    rows = [find_row(session, n) for n in
            ("1. 批准，自动接受编辑", "2. 批准，逐个确认", "3. 继续完善计划")]
    if rows != [rows[0] + i for i in range(3)]:
        die("三个选项没有各占一个连续物理行（行号=%s）" % rows, session.screen.display)
    sel = find_row(session, "❯ 1. 批准，自动接受编辑")
    if row_backgrounds(session, sel) != {"default"} or \
            row_backgrounds(session, sel + 1) != {"default"}:
        die("计划面板高亮用了背景色（会串到下一行）", session.screen.display)
    print("计划面板 OK: 正文进 scrollback、三选项各占一行、高亮纯前景.")

    session.write(b"1\r")                                    # 批准，自动接受编辑
    session.wait_for("计划已批准")
    session.write(b"x")                                      # 清掉 notice，看常驻标识
    session.wait_for(MODE_TAG_ACCEPT)
    session.write(CTRL_U)
    print("计划批准 OK: 工具线程被唤醒、模式自动切到「自动接受编辑」.")
```

把它接进 `main()` 的场景序列（放在 `check_mode_indicator_persists` 之后）。

- [ ] **Step 4: 跑冒烟**

```bash
mvn -q -o package -pl springai-code-tui -DskipTests
mvn -q -o dependency:build-classpath -pl springai-code-tui -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && python3 src/test/resources/scripts/permission_smoke.py
```

Expected: `SMOKE PASS`，输出里含「Shift+Tab OK: 第三档是计划模式」「计划面板 OK」「计划批准 OK」

- [ ] **Step 5: 文档**

① `springai-code-tui/README.md`：
- 「权限模式」表加 `计划模式 / ⏸ 计划模式（冷薄荷）/ 只读放行，写与命令一律拒绝；产出计划经批准后切档`
- 把「`Shift+Tab` 只在**前两档**之间循环」改成三档
- 「已知限制」里删掉「**无 plan 模式**——已在设计里，列为下一期」这条
- 「启动参数」一节补 `--permission-mode plan|default|acceptEdits`（并写明它**不接受 bypass**）
- 「判定顺序」表的「模式默认」行补一句 PLAN 是 DENY 不是 ASK
- 斜杠命令表下方补一句：计划审批面板的按键（↑↓ / 1-3 / Enter / Esc）

② `docs/superpowers/specs/2026-07-31-permission-mode-design.md` 的分期表：期 2 一行标 ✅ 并注明落地日期；删掉「分期带来的两处临时状态」那段里已被本期补齐的两条。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/permission_smoke.py \
        springai-code-tui/README.md \
        docs/superpowers/specs/2026-07-31-permission-mode-design.md
git commit -m "test(code-tui): 计划模式与计划审批面板的 pty 实机冒烟；补文档"
```

---

## 完成标准

- [ ] `mvn test -pl springai-code-tui` 全绿（`CodingAgentSpikeTest.todoTurnIdBinding` 的红单独记录、不计入——它是打真实模型的既有 flaky）
- [ ] `permission_smoke.py` 输出 `SMOKE PASS`，含三条新断言
- [ ] 手动验收（需真实 key）：`--permission-mode plan` 启动 → 让模型改文件 → **被拒绝且它自己转去调 `ExitPlanMode`** → 面板弹出 → 选「批准，逐个确认」→ 模式切到默认、模型开始动手
- [ ] 手动验收：计划面板上选「继续完善计划」→ 写一句意见 → **模型据此改了方案并再次提交**
- [ ] 手动验收：计划面板上按 Esc → 回合结束，**下一条消息不报 400**

---

## 计划自检

**1. spec 覆盖**

| spec §9 / §4 要求 | 对应 Task |
|---|---|
| `PLAN` 模式（只读放行，其余 DENY） | Task 1（枚举）、Task 2（引擎分支） |
| `Shift+Tab` 循环含 PLAN | Task 1 |
| `ExitPlanMode` 工具（非 PLAN 时不调用） | Task 7（描述里写明；模型不调即可，无需运行期硬拦——硬拦要引擎认识「当前模式」，属过度设计） |
| `PlanApprovalBridge` 阻塞桥 | Task 6 |
| 计划正文用 `MarkdownRenderer` 进 scrollback | Task 9 Step 3 |
| 面板三选项 + 各自的工具返回串 | Task 6 Step 3（返回串）、Task 9（面板） |
| 批准后切 `ACCEPT_EDITS` / `DEFAULT` | Task 6 Step 3（`modeSwitch`） |
| `{PERMISSION_MODE}` 提示段、作为 param 值注入 | Task 4 |
| `--permission-mode plan\|default\|acceptEdits` | Task 3 |
| 子 agent 继承模式、`SubagentSpec` 不加字段 | 无需改动（共用同一引擎）；Task 7 Step 4 显式说明子 agent 不注册该工具 |

**2. 占位扫描**：无 TBD / 「类似 Task N」/「加适当的错误处理」。三处标了「以仓库现状为准」的地方（`printAssistantMarkdown` 的真实方法名、`cancelCurrent()` 的签名、`drained` 的取法）都给了**判据与去处**，不是含糊其辞——它们是「照抄邻居的既有写法」而非「自己想一个」。

**3. 类型一致性**：`PlanOutcome`（4 值）在 Task 5 定义、Task 6/8/9 消费一致；`PlanResponder.respond(PlanOutcome, String)` 两参签名三处一致；`PlanRequest(long, String, PlanResponder)` 三字段在 Task 5 定义、Task 6 构造、Task 8/9 消费一致；`PermissionModePrompt.of(PermissionMode)` 在 Task 4 定义并被同一 Task 消费；`PlanApprovalBridge.exitPlanModeTool(PlanApprovalBridge)` 在 Task 7 定义与消费。

**4. 已知实施顺序依赖**：Task 2 的 `internalToolsAllowed` 用例**要等 Task 7 登记工具后才转绿**（计划里已写明，禁止改断言凑绿）。Task 5 的 `permits` 一改，`CodeTuiView` 的 instanceof 链就必须同步补分支（Task 9 Step 3），中间不要停在半编译状态。

---

## Task 2 修订（实施期发现，2026-08-01）

**impl-t2 用真实引擎实测发现 PLAN 下结论倒置：**

```
PLAN 模式、无任何规则：
  rm -rf ~                       -> ASK    ← 用户能当场批准，真的会执行
  Write ~/.ssh/authorized_keys   -> ASK    ← 同上
  mvn test                       -> DENY
  echo $(whoami)（拆不动）        -> DENY
```

**根因**：决策顺序第 2 步（内置危险检查）命中即 `askOnly` 提前 return，永远走不到第 6 步的 PLAN 分支。
第 2 步的初衷是「护栏不是牢笼，人确认了就该能做」——那在另外三档都对；但 PLAN 的立意恰恰是
「这段时间里根本不可能动手」，而普通写操作已在第 6 步被拒，于是这条初衷**恰好把唯一一道
可当场批准的口子留给了最危险的那批操作**。

> **计划原文有误导**：Task 2 的背景一节写着「allow 规则排在模式默认之前……是既定语义，
> 别当 bug 修」。那句只针对 **allow 规则**那一条。第 2 步在 PLAN 下的结论不是既定语义，
> 是漏考虑了新档位。实施者据此没有擅自修改而是上报——行为正确，是计划的措辞该改。

**修法**（判据：**PLAN 本来就会拒的，危险检查不得把它降级成可批准；只读仍 ASK**）：

```java
        String danger = builtinDanger(entry, path, target);
        if (danger != null) {
            if (mode == PermissionMode.PLAN) {
                PermissionDecision planned = decideByPlanMode(entry, target);
                if (planned != null && planned.behavior() == PermissionBehavior.DENY) {
                    return planned;
                }
            }
            return PermissionDecision.askOnly(danger);
        }
```

**为什么只读不一并拒**：`Read ~/.ssh/id_rsa` 在 PLAN 下仍是 ASK。
PLAN 承诺的是「不会**动手**」，不是「不会读」——只读调查正是这一档要允许的事；
读密钥逐次确认是内置底线的本职，与模式无关。复用 `decideByPlanMode` 而非另写判断，
保证「PLAN 会拒什么」只有一处定义。

**新增用例**：`dangerousOpsDeniedNotAsked`（`rm -rf ~` / 写 `authorized_keys` 均为 DENY 且 reason 指路）、
`readingSecretsStillAsks`（读私钥仍 ASK）。变异实测：注释掉该 PLAN 特例段，前者必须变红。

**连带**：`PermissionEngineTest$Robustness.concurrentDecideAndMutate` 的断言
「工作区内的写绝不会是 DENY」写在 PLAN 存在之前，需按当时 `mode()` 快照分档断言；
**不得**弱化成「不抛异常 + reason 非空」——那是把有内容的断言换成几乎不会失败的断言。
