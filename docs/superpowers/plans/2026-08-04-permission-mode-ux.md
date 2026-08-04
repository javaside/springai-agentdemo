# 权限模式交互改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shift+Tab 四档平权自由循环（BYPASS 不再需要启动参数），且任何 notice 都不再遮住状态栏的运行指示。

**Architecture:** 净删除为主——去掉 `PermissionEngine` 里那个把「启动能否进 BYPASS」与「运行期能否进 BYPASS」合并成一个布尔的门禁字段，并把 `statusLine()` 里排在状态开关之前的 notice 独占分支降级为忙时后缀。不新增文件、不新增接口方法。

**Tech Stack:** Java 17 / Maven 多模块 / JUnit 5 / TamboUI 0.4.0 内联 TUI / pty + pyte 实机冒烟

**Spec:** `docs/superpowers/specs/2026-08-04-permission-mode-ux-design.md`

**分支：** `feat/permission-mode-ux`（已建，spec 已提交在 `35caf44`）

---

## 全局纪律（每个任务都适用）

**0. 测试命令必须带 `-pl springai-code-tui`。** 整仓 `mvn test -Dtest=...` 会被三个空模块打挂
（它们没有任何匹配的测试类，surefire 直接失败）。**不要**用
`-DfailIfNoSpecifiedTests=false` 去盖这个问题——那会让「测试类名打错」也静默变绿。

多个测试类用逗号分隔：`-Dtest='A,B'`。**不是** `'A+B'`。

**1. 本任务序列必须按顺序执行。** Task 1 是回归钉，必须在 Task 3 动删除之前先跑绿；
Task 2 的签名改动会让 Task 3 的调用点编译不过，反之亦然——它们在同一个编译单元图里。

**2. 改签名导致的编译错误是好事，别绕过。** `PermissionMode.next(boolean)` → `next()` 与
`PermissionEngine` 构造器去掉第 4 参，两处一共约 30 个调用点。编译器会逐个报出来，
**不存在静默漏网**。不要为了少改几处而保留重载。

**3. 遇到断言与新行为冲突时，先判断它钉的是什么。** 本次会遇到两类：
- 只是**适配**（如三档循环的期望值）→ 改期望值；
- 钉的是**已被推翻的约束**（如 `assert_absent("跳过权限检查")`）→ 删掉，并在提交信息里说明为什么。

把第二类当第一类改（比如把 `assertNotEquals(BYPASS, …)` 改成 `assertEquals`）会留下一条
名字与内容对不上的测试，下一个人读它会得出错误结论。

---

### Task 1: 先立回归钉——两条 BYPASS 禁令必须活下来

**为什么第一个做**：本次改动是「拆掉一道合并的门禁」，最大的风险是**拆过头**。
配置层与 CLI 层那两条禁令守的是完全不同的威胁模型（用户全程无感 vs 用户当面按键），
它们必须原样存活。钉子要在删除动作之前就位，否则拆过头了没人报警。

好消息：两条钉子**都已存在**。本任务不是新写，而是**核实它们现在是绿的**，
并把它们的理由措辞改对——现有措辞（「只能由 `--dangerously-skip-permissions` 进」）
在本次改动后会变成假话，留着会误导下一个读它的人。

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java:83-96`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigLoaderTest.java:129-150`

- [ ] **Step 1: 先核实两条钉子当前是绿的**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionStartupTest,PermissionConfigLoaderTest'
```

Expected: BUILD SUCCESS。**若此刻就是红的，停下来查清楚再往下走**——
本任务后面所有步骤都建立在「这两条约束今天成立」之上。

- [ ] **Step 2: 改写 CLI 层那条钉子的理由措辞**

打开 `PermissionStartupTest.java`，找到 `rejectsBadPermissionMode` 里这一段：

```java
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "bypass"}),
                "BYPASS 只能由 --dangerously-skip-permissions 进；这里认了就等于开了第二个后门");
```

替换为：

```java
        assertNull(CodeTuiApplication.startupMode(new String[]{"--permission-mode", "bypass"}),
                "--dangerously-skip-permissions 已经是「启动即进 BYPASS」的写法，"
                        + "不再设第二条等价路径。注意：运行期 Shift+Tab 进 BYPASS 是允许的，"
                        + "本条约束的是启动参数，两者威胁模型不同（见权限模式 spec §3）");
```

同时把该方法的 `@DisplayName` 从

```java
    @DisplayName("不给、给错、或想用它进 BYPASS —— 一律回退 null（由调用方落到配置的 defaultMode）")
```

改为

```java
    @DisplayName("--permission-mode：不给、给错、或写 bypass 一律回退 null（bypass 另有 --dangerously-skip-permissions）")
```

- [ ] **Step 3: 改写配置层那条钉子的理由措辞**

打开 `PermissionConfigLoaderTest.java`，把

```java
    @DisplayName("配置文件不得声明 BYPASS——那扇门只有 --dangerously-skip-permissions 能开")
```

改为

```java
    @DisplayName("配置文件不得声明 BYPASS——clone 来的仓库不该让你启动即裸奔（键盘切进去则是允许的）")
```

并在该方法体第一行加一条注释，说明它为什么不随四档平权一起放开：

```java
        // ⚠ 本条与「Shift+Tab 四档平权」不矛盾，别一起删：
        // 键盘切档时用户在场、有意图，且切完状态栏行首常驻红色「⚠ 跳过权限检查」；
        // 而 .codetui/permissions.json 是 clone 仓库时一起带过来的，进目录一启动，
        // 模型的第一次工具调用就零检查跑了——用户一个键都没按、屏幕上什么都没变。
```

- [ ] **Step 4: 确认正向用例还在（反向断言的配套）**

`PermissionStartupTest.parsesPermissionMode` 必须存在且断言 `--permission-mode plan`
仍返回 `PLAN`。**核实它在，不要新写。**

为什么必须有它：`rejectsBadPermissionMode` 全是 `assertNull`。一个把 `startupMode`
整个改成 `return null;` 的变异能让它**全部通过**。只有正向用例能杀掉那个变异。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
grep -n "parsesPermissionMode" -A6 \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java
```

Expected: 能看到 `assertEquals(PermissionMode.PLAN, … "--permission-mode", "plan"…)`。

- [ ] **Step 5: 跑测试确认仍绿**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionStartupTest,PermissionConfigLoaderTest'
```

Expected: BUILD SUCCESS（本任务只改了注释与消息串，行为零变化）。

- [ ] **Step 6: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigLoaderTest.java
git commit -m "test(permission): 两条 BYPASS 禁令的理由改对，钉住它们不随四档平权一起放开

配置层与 CLI 层守的是「用户全程无感被放成裸奔」，与运行期当面按键
是两个威胁模型。四档平权只解开后者。"
```

---

### Task 2: `PermissionMode.next()` 改为四档恒定

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionModeTest.java`

- [ ] **Step 1: 写失败的测试**

打开 `PermissionModeTest.java`，**删掉** `cyclesThreeWithoutBypass` 与 `cyclesFourWithBypass`
这两个方法（它们钉的是「有没有门禁参数」这个已被推翻的区分），换成下面两个：

```java
    @Test
    @DisplayName("四档平权循环：默认 → 自动接受编辑 → 计划模式 → 跳过权限检查 → 默认")
    void cyclesFourModes() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next());
        assertEquals(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS.next());
        assertEquals(PermissionMode.BYPASS, PermissionMode.PLAN.next());
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next());
    }

    /**
     * 只断言「按四次回到起点」是不够的：一个把 PLAN 直接接回 DEFAULT 的三档实现，
     * 按四次同样回得到 DEFAULT（DEFAULT→ACCEPT→PLAN→DEFAULT→ACCEPT，不对，
     * 但按三次就回来了、第四次在 ACCEPT）——所以还要断言四次经过的档位<b>互不相同</b>，
     * 这条才真正钉住「四档都在环上」。
     */
    @Test
    @DisplayName("连按四次遍历全部四档，一个不漏、一个不重")
    void fourPressesVisitEveryMode() {
        Set<PermissionMode> seen = new LinkedHashSet<>();
        PermissionMode m = PermissionMode.DEFAULT;
        for (int i = 0; i < 4; i++) {
            m = m.next();
            seen.add(m);
        }
        assertEquals(PermissionMode.DEFAULT, m, "四次之后必须回到起点");
        assertEquals(4, seen.size(), "四次必须经过四个互不相同的档位，实际经过：" + seen);
        assertTrue(seen.contains(PermissionMode.BYPASS),
                "BYPASS 必须在环上——它不再需要任何启动参数");
    }
```

在文件头部补上这三个 import：

```java
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionModeTest'
```

Expected: **COMPILATION ERROR** —
`method next in enum PermissionMode cannot be applied to given types; required: boolean, found: no arguments`。
Java 里「方法还不存在」表现为编译错误，这就是本步要的红。

- [ ] **Step 3: 改 `PermissionMode`**

打开 `PermissionMode.java`。先把 BYPASS 那段**错误的** javadoc 改对
（它现在宣称内置危险检查仍然生效，而 `PermissionEngine.doDecide` 在 BYPASS 分支直接返回、
根本走不到内置检查那一步）：

```java
    /**
     * 全放行——<b>只有 deny 规则还拦得住</b>。
     *
     * <p><b>内置危险检查在本档被跳过</b>（{@code doDecide} 的 BYPASS 分支排在内置检查之前
     * 直接返回），只由 {@code PermissionCallback.onGuardrailBypassed} 事后在对话区留一行
     * {@code ⚠ BYPASS 放行：…}。{@code ask} 规则同样跳过——它的语义是「每次都问我」，
     * 与本档的「不问」直接矛盾。
     *
     * <p><b>本档不产生任何 ASK</b>，这正是它在半无人值守下能用的原因。
     */
    BYPASS;
```

然后把 `next(boolean)` 整个替换为：

```java
    /**
     * 循环到下一个模式（UI 的 {@code Shift+Tab}）。<b>四档平权</b>，BYPASS 排在 PLAN 之后。
     *
     * <p><b>为什么不带门禁参数</b>：这里曾有一个 {@code bypassAllowed} 参数，
     * 由 {@code --dangerously-skip-permissions} 决定 BYPASS 在不在环上。那把两件事
     * 合并成了一个布尔：「启动时能否<b>起步于</b> BYPASS」和「运行期用户能否<b>切进</b> BYPASS」。
     * 前者该受启动参数管（clone 来的配置、命令行别名都可能让用户全程无感），
     * 后者不该——用户当面按键、在场、有意图，且切完状态栏行首常驻红色 {@code ⚠ 跳过权限检查}。
     * 合并的结果是想临时用一下 BYPASS 就得退出进程、改命令行、重启。
     *
     * <p>顺序与此前带 {@code --dangerously-skip-permissions} 启动时完全一致，没有新的排列。
     */
    public PermissionMode next() {
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> PLAN;
            case PLAN -> BYPASS;
            case BYPASS -> DEFAULT;
        };
    }
```

同时把枚举类头部 javadoc 里那句「切换：UI 的 Shift+Tab」下面补一行：

```java
 * <p><b>四档平权</b>：BYPASS 也在 {@code Shift+Tab} 环上，不需要任何启动参数。
 * 配置文件与 {@code --permission-mode} <b>仍然</b>拒绝 BYPASS，见 {@code PermissionConfigLoader}。
```

- [ ] **Step 4: 跑测试，确认报错点已经转移**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionModeTest'
```

Expected: 仍是 COMPILATION ERROR，但**报错点变了**——现在是
`PermissionEngine.java:260`、`CodeTuiViewPermissionModeTest.java:41`、
`CodeTuiViewModeIndicatorTest.java:44` 这些还在传 `boolean` 的调用点。

**`PermissionEngine` 是 main 源码，它编译不过就一条测试都跑不起来**（`mvn test`
先编译全部主源码）。所以本任务必须把它那一行也改掉——否则不存在一个能跑的中间状态。
门禁字段的**彻底删除**仍留给 Task 3，本步只动 `cycleMode` 这一行。

- [ ] **Step 5: 改 `PermissionEngine.cycleMode()` 一行**

`PermissionEngine.java:260`：

```java
        PermissionMode next = mode.next(bypassAllowed);
```
→
```java
        PermissionMode next = mode.next();
```

`bypassAllowed` 字段此刻仍被构造器与 `setMode` 使用，不会变成未使用字段。

- [ ] **Step 6: 修测试侧的两个桩**

`CodeTuiViewPermissionModeTest.java:41`：

```java
            mode = mode.next(false);
```
→
```java
            mode = mode.next();
```

`CodeTuiViewModeIndicatorTest.java:38-45`，把桩里的 `bypassAllowed` 字段整个删掉：

```java
    /** 可切到任意档的桩。四档平权后不再需要模拟启动开关。 */
    private static class Stub implements SubmitHandler {
        PermissionMode mode = PermissionMode.DEFAULT;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public PermissionMode cyclePermissionMode() { mode = mode.next(); return mode; }
    }
```

并删掉 `bypassTagIsPersistentAndRed` 里那行 `stub.bypassAllowed = true;`
（字段没了，留着编译不过；`stub.mode = PermissionMode.BYPASS;` 那行保留）。

- [ ] **Step 7: 修三条「按 N 次回到默认」的断言**

四档之后「按三次回默认」不再成立。三处都要改，**改的是次数不是期望值**：

**7a.** `CodeTuiViewPermissionModeTest.shiftTabThriceReturnsToDefault`
——整个方法替换为：

```java
    @Test
    @DisplayName("连按四次回到默认（四档平权：默认→自动接受编辑→计划模式→跳过权限检查→默认）")
    void shiftTabFourTimesReturnsToDefault(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(shiftTab());
        assertEquals(PermissionMode.PLAN, stub.mode, "第二下到计划模式");
        v.feedKeyForTest(shiftTab());
        assertEquals(PermissionMode.BYPASS, stub.mode,
                "第三下到「跳过权限检查」——它不再需要 --dangerously-skip-permissions");
        v.feedKeyForTest(shiftTab());

        assertEquals(4, stub.cycles.get());
        assertEquals(PermissionMode.DEFAULT, stub.mode);
        assertTrue(state.notice().contains("默认"), "最后一次切换的反馈不该被「按任意键清 notice」吃掉，"
                + "实际 notice：" + state.notice());
    }
```

**7b.** `CodeTuiViewModeIndicatorTest.tagSurvivesNoticeBeingCleared` 末尾那段
「切回 DEFAULT 后标识必须消失」需要多一次 Shift+Tab。把这一段：

```java
        // 切回 DEFAULT 后标识必须消失——常态不该有噪声。
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(KeyEvent.ofChar('c'));
        assertEquals(PermissionMode.DEFAULT, stub.mode);
```

替换为：

```java
        // 四档平权后 PLAN 的下一档是 BYPASS，再一下才回 DEFAULT。
        // 中途的 BYPASS 也顺手验一下标识换上了——它是最该可见的一档。
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(KeyEvent.ofChar('c'));
        assertEquals(PermissionMode.BYPASS, stub.mode);
        assertTrue(screen(v).contains(BYPASS_TAG), "切到 BYPASS 后应换成红色警示标识:\n" + screen(v));

        // 切回 DEFAULT 后标识必须消失——常态不该有噪声。
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertEquals(PermissionMode.DEFAULT, stub.mode);
```

并把该方法末尾已有的两条 `assertTrue(!screen(v).contains(...))` 后面补一条：

```java
        assertTrue(!screen(v).contains("⚠ 跳过权限检查"), "切回 DEFAULT 后 BYPASS 标识也必须消失");
```

**7c.** `PermissionStartupTest.facadeCannotEnterBypassWithoutFlag`
——这条钉的是**已被推翻的约束**，不能改期望值蒙混过去（那会留下一条名字与内容相反的测试）。
**整个方法删掉**，换成：

```java
    @Test
    @DisplayName("门面可在运行期切进 BYPASS——不再需要 --dangerously-skip-permissions")
    void facadeCanEnterBypassAtRuntime(@TempDir Path root) {
        CodingAgent agent = agentWith(engine(root, PermissionMode.PLAN, false));

        assertEquals(PermissionMode.BYPASS, agent.cyclePermissionMode(),
                "四档平权：PLAN 的下一档就是 BYPASS，启动参数不再是前置条件");
        assertEquals(PermissionMode.BYPASS, agent.permissionMode(),
                "切完读回的必须是新档，否则状态栏说一套、工具做另一套");
    }
```

（此处 `engine(...)` 辅助方法的第 3 个实参 `false` 暂时保留——它在 Task 3 才随构造器一起删。）

**7d.** `PermissionEngineTest.bypassRequiresFlag`（约 `:683-694`）——整条钉的就是这道门禁。
它的**前两句**断言在本任务就会红（`cycleMode` 已经到得了 BYPASS），后两句要到 Task 3 才红。
本任务先把它换成只断言循环部分的版本：

```java
        @Test
        @DisplayName("BYPASS 就在 Shift+Tab 环上：构造时没给任何「授权」也到得了")
        void bypassIsOnTheCycle(@TempDir Path root) {
            // noBypass 这个辅助方法此刻仍给构造器传 false——刻意用它，
            // 这条断言的分量正在于「传了 false 也照样到得了」。
            // （该辅助方法在 Task 3 随构造器参数一起删掉。）
            PermissionEngine e = noBypass(root, PermissionMode.ACCEPT_EDITS);
            assertEquals(PermissionMode.PLAN, e.cycleMode());
            assertEquals(PermissionMode.BYPASS, e.cycleMode(),
                    "四档平权：运行期切档不再受启动参数管");
            assertEquals(PermissionMode.DEFAULT, e.cycleMode(), "再一次回到起点");
        }
```

`setMode(BYPASS)` 与「构造器不再降级」这两条断言留到 Task 3——那时门禁字段才真正删掉。

- [ ] **Step 8: 跑测试确认全绿**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui \
  -Dtest='PermissionModeTest,PermissionStartupTest,CodeTuiViewPermissionModeTest,CodeTuiViewModeIndicatorTest'
```

Expected: BUILD SUCCESS，`Failures: 0, Errors: 0`。

若 `CodeTuiViewModeIndicatorTest` 报「找不到 `权限模式：自动接受编辑`」——**不该发生**，
notice 文案要到 Task 4 才改。真报了说明有人提前动了 Task 4，回去核对。

- [ ] **Step 9: 跑一次模块全量，确认没有别的调用点被漏掉**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。这一步是本任务的真正验收——`next(boolean)` 的调用点分散在
多个测试类里，只跑上面四个类看不全。

- [ ] **Step 10: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionModeTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPermissionModeTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModeIndicatorTest.java
git commit -m "feat(permission): Shift+Tab 四档平权，next() 去掉门禁参数

BYPASS 进入 Shift+Tab 循环，不再需要 --dangerously-skip-permissions。
那个参数把两件事合并成了一个布尔：启动能否起步于 BYPASS、运行期能否
切进 BYPASS——前者该受启动参数管，后者不该。

删掉 facadeCannotEnterBypassWithoutFlag：它钉的约束已被推翻，改期望值
会留下一条名字与内容相反的测试。

顺带修正 BYPASS 的 javadoc：它此前宣称内置危险检查仍然生效，
而 doDecide 的 BYPASS 分支排在内置检查之前直接返回。"
```

---

### Task 3: `PermissionEngine` 彻底删掉 `bypassAllowed` 门禁

Task 2 只改了 `cycleMode` 那一行。本任务把字段、构造器参数、getter、
以及 `setMode` 与构造器里的两处拒绝分支全部删掉，并扫掉全部 19 个构造器调用点。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java:167,188-203,241-256,265-267`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java:83-84`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:567`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java`

- [ ] **Step 1: 写失败的测试**

在 `PermissionEngineTest.java` 里，把 Task 2 留下的 `bypassIsOnTheCycle` 扩成完整版
（补上 `setMode` 与构造器那两条）：

```java
        @Test
        @DisplayName("BYPASS 是普通一档：环上到得了、setMode 进得去、当启动模式也不被降级")
        void bypassIsAnOrdinaryMode(@TempDir Path root) {
            PermissionEngine e = engine(root, PermissionMode.ACCEPT_EDITS);
            assertEquals(PermissionMode.PLAN, e.cycleMode());
            assertEquals(PermissionMode.BYPASS, e.cycleMode(), "四档平权：运行期切档不受启动参数管");
            assertEquals(PermissionMode.DEFAULT, e.cycleMode(), "再一次回到起点");

            // setMode 直接指定也进得去（/permissions 面板等非循环入口走它）
            assertEquals(PermissionMode.BYPASS, e.setMode(PermissionMode.BYPASS));
            assertEquals(PermissionMode.BYPASS, e.mode(), "setMode 的返回值与读回的档位必须一致");

            // 以 BYPASS 为启动模式构造，不再被静默降级成 DEFAULT
            PermissionEngine started = new PermissionEngine(
                    root, config(PermissionMode.BYPASS), PermissionMode.BYPASS);
            assertEquals(PermissionMode.BYPASS, started.mode(),
                    "--dangerously-skip-permissions 启动即进 BYPASS，构造器不该再降级");
        }
```

**注意**：`bypassIsOnTheCycle` 那个方法整个被上面这个替换掉，不要两个都留。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionEngineTest'
```

Expected: **COMPILATION ERROR** —
`constructor PermissionEngine … cannot be applied to given types; required: Path,PermissionConfig,PermissionMode,boolean; found: Path,PermissionConfig,PermissionMode`。

- [ ] **Step 3: 删掉 `PermissionEngine` 的门禁**

**3a.** 删字段（`:167`）：

```java
    private final boolean bypassAllowed;
```

**3b.** 构造器（`:188-203`）——删第 4 个参数、删它的 `@param`、删赋值、删降级分支：

```java
    /**
     * @param root          项目根（工作区判定 + 相对路径解析 + 永久规则回写目标）。
     *                      <b>不可为 null</b>：{@link ToolTargets#extract} 的契约就是漏传即立即失败——
     *                      少一个 root 会让全部路径规则静默失效（deny 被相对路径绕过且无任何日志），
     *                      与其在这里补一层「容忍 null」的伪装，不如启动时就炸
     * @param config        两层 permissions.json 合并结果
     * @param startupMode   启动模式（启动参数 &gt; config.defaultMode，由调用方定好后传入）；
     *                      null 则取 {@code config.defaultMode()}。
     *                      <b>接受 BYPASS</b>——{@code --dangerously-skip-permissions} 走这条路；
     *                      拦住「用户全程无感就被放成裸奔」是 {@link PermissionConfigLoader} 与
     *                      {@code CodeTuiApplication.startupMode} 的职责，不在这里重复。
     */
    public PermissionEngine(Path root, PermissionConfig config, PermissionMode startupMode) {
        this.root = Objects.requireNonNull(root, "root 不可为 null：漏传会让全部路径规则静默失效").normalize();
        this.foldedRoot = foldPath(this.root);
        Objects.requireNonNull(config, "config 不可为 null");
        this.fileRules.addAll(config.rules());
        this.mode = startupMode != null ? startupMode : config.defaultMode();
        warnDeadRules(config.rules());
    }
```

被删掉的是这三行：

```java
        this.bypassAllowed = bypassAllowed;
        …
        PermissionMode start = startupMode != null ? startupMode : config.defaultMode();
        if (start == PermissionMode.BYPASS && !bypassAllowed) {
            log.warn("启动模式 BYPASS 未获授权（没有 --dangerously-skip-permissions），已降级为 DEFAULT。");
            start = PermissionMode.DEFAULT;
        }
        this.mode = start;
```

**为什么降级分支能删**：它唯一的作用是「未授权时把 BYPASS 启动模式降成 DEFAULT」。
启动模式只可能来自 `CodeTuiApplication.startMode`，而那里能产出 BYPASS 的只有
`--dangerously-skip-permissions` 一条路（配置层与 `--permission-mode` 都仍然拒绝，
由 Task 1 的两条钉子保证）。所以这个分支删后永不命中——留着就是一段假装还在守什么的死代码。

**3c.** `setMode`（`:241-256`）删拒绝分支：

```java
    /**
     * 直接设模式（{@code /permissions} 等非循环入口走它）。
     *
     * @return 设置后的实际模式（入参为 null 时返回当前模式，不改状态）
     */
    public PermissionMode setMode(PermissionMode m) {
        if (m == null) {
            return mode;
        }
        this.mode = m;
        return m;
    }
```

**3d.** 删 getter（`:265-267`）：

```java
    public boolean bypassAllowed() {
        return bypassAllowed;
    }
```

全仓 `grep '\.bypassAllowed()'` 为空，这个 getter 没有任何调用方，删它不波及别处。

- [ ] **Step 4: 扫掉两个主源码调用点**

`CodeTuiApplication.java:83-84`：

```java
        PermissionEngine permissionEngine = new PermissionEngine(root, permissionConfig,
                startMode, skipPermissions);
```
→
```java
        PermissionEngine permissionEngine = new PermissionEngine(root, permissionConfig, startMode);
```

`skipPermissions` 变量**保留**——它仍决定 `startMode`（`:81-82`）与启动横幅（`:85-93`）。

`AgentTools.java:567`：

```java
        return new PermissionEngine(root, PermissionConfig.empty(), PermissionMode.DEFAULT, false);
```
→
```java
        return new PermissionEngine(root, PermissionConfig.empty(), PermissionMode.DEFAULT);
```

顺手把它上面那句 javadoc（`:560`）里的「+ 不许 BYPASS」删掉——那个描述已不成立：

```java
    /** 测试/兼容用的隔离引擎：空配置 + DEFAULT 模式。 */
```

- [ ] **Step 5: 扫掉全部测试侧调用点**

编译器会逐个报出来。跑一次拿到完整清单：

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn -q test-compile -pl springai-code-tui 2>&1 | grep -E "PermissionEngine|ERROR" | head -40
```

改法一律是**删掉最后一个实参**（`true` 或 `false`）。截至 `35caf44` 的清单：

| 文件 | 行 |
| --- | --- |
| `PermissionStartupTest.java` | 41（辅助方法，见下）、113 |
| `agent/AgentToolsSecurityTest.java` | 184、197、266 |
| `agent/PermissionWiringTest.java` | 313 |
| `agent/McpRegistryTest.java` | 150 |
| `agent/PermissionTestSupport.java` | 50 |
| `agent/PermissionCallbackTest.java` | 52、106 |
| `agent/permission/PermissionEnginePlanModeTest.java` | 22 |
| `agent/permission/PermissionEngineRemoveRuleTest.java` | 26 |
| `agent/permission/AcceptEditsCommandScopeTest.java` | 30 |
| `agent/permission/PermissionEngineAliasTest.java` | 45 |
| `agent/permission/PermissionEngineTest.java` | 35、40、767 |

（行号以编译器实际输出为准——前面几个任务已经动过这些文件。）

三处需要**多改一点**，不只是删实参：

**5a.** `PermissionStartupTest.java:40-42` 的辅助方法，删掉那个已无意义的参数：

```java
    private static PermissionEngine engine(Path root, PermissionMode mode) {
        return new PermissionEngine(root, PermissionConfig.empty(), mode);
    }
```

它的两个调用点（`facadeCanEnterBypassAtRuntime`、`clearContextClearsSessionRules`）
相应去掉最后一个实参。

**5b.** `PermissionEngineTest.java:34-42` 的两个辅助方法**合并成一个**——
`engine` 与 `noBypass` 的唯一区别就是那个已删的参数，留着两个同义方法只会让人以为还有区别：

```java
    private static PermissionEngine engine(Path root, PermissionMode mode, String... dsl) {
        return new PermissionEngine(root, config(mode, dsl), mode);
    }
```

然后把该文件里全部 `noBypass(` 改成 `engine(`。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -c "noBypass(" src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java
```

替换后这个计数应为 0。

**5c.** `agent/PermissionTestSupport.java:47-51` 的注释解释「为什么传 true」，参数没了，注释也要走：

```java
        return new PermissionEngine(Path.of("."), new PermissionConfig(mode, List.of(all)), mode);
```

把它上面那段以「bypassAllowed 传 true：否则以 BYPASS 启动会被静默降级成 DEFAULT」开头的
注释整段删掉——降级行为已经不存在了，留着会让人去找一个不存在的机制。

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionEngineTest,PermissionStartupTest'
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: 跑模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。若有别的测试红，看它断言的是不是「未授权进不了 BYPASS」——
是的话按全局纪律 #3 第二类处理（删掉并在提交信息说明），不是的话停下来查。

- [ ] **Step 8: 确认门禁真的没了**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -rn "bypassAllowed" src/main src/test
```

Expected: **无输出**。有残留说明漏了一处。

- [ ] **Step 9: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add -A springai-code-tui/src
git commit -m "refactor(permission): 删掉 bypassAllowed 门禁字段

字段、构造器第 4 参、getter、setMode 的拒绝分支、构造器的降级分支全部移除。
降级分支删后永不命中：能产出 BYPASS 启动模式的只有 --dangerously-skip-permissions，
配置层与 --permission-mode 仍然拒绝（Task 1 的两条钉子保证）。

PermissionEngineTest 的 engine/noBypass 两个辅助方法合并——它们的唯一区别
就是这个已删的参数。"
```

---

### Task 4: 状态栏 notice 降级为忙时后缀

**问题不是 Shift+Tab 特有的。** `statusLine()` 里 notice 分支排在 `state.status()` 开关**之前**
且无条件 `return`，于是**任何**忙时 notice 都会把波光转轮整条盖掉，而且是 sticky 的
（要等下一次按键才还回来）。今天只有 Shift+Tab 会在忙时设 notice，所以只表现为这一个症状；
结构在那儿，以后再加任何忙时提示都会重蹈覆辙。**按结构修，不按症状修。**

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:2401-2435`（`statusLine`）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:781`（notice 文案）
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewBusyNoticeTest.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModeIndicatorTest.java`（notice 文案断言）

- [ ] **Step 1: 写失败的测试**

新建 `CodeTuiViewBusyNoticeTest.java`：

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忙时的 notice 不得盖掉状态栏的运行指示。
 *
 * <p><b>钉的是一个真实缺陷</b>：{@code statusLine()} 里 notice 分支排在 {@code state.status()}
 * 开关之前且无条件 return。回合正在跑时按 Shift+Tab 切个档，「● 思考中…」的波光行就被
 * 「权限模式：X」整条顶掉，且 sticky——不按下一个键不还回来。用户看不出回合还在跑。
 *
 * <p><b>为什么必须渲染进 Buffer 再回读</b>：这是分支顺序缺陷，被挡住的那一段本身构造得出来。
 * 只断言 {@code statusLine()} 的返回对象或只测某个纯函数，永远是绿的——而且测不到 shimmer
 * 到底有没有把 suffix 拼进去。断言必须落在屏幕文本上。真实 ANSI 与路由器那一层由 pty 冒烟兜底。
 */
class CodeTuiViewBusyNoticeTest {

    /**
     * <b>刻意不覆写 {@code cyclePermissionMode}</b>：本类一次 Shift+Tab 都不按，
     * notice 全靠 {@code state.setNotice} 直接设。覆写它就要调 {@code mode.next(…)}，
     * 于是本文件被绑到那个方法的签名上——而它正在另一条并行赛道上被改
     * （{@code next(boolean)} → {@code next()}），合并时必炸。桩只提供用得到的东西。
     */
    private static class Stub implements SubmitHandler {
        PermissionMode mode = PermissionMode.DEFAULT;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public String currentModel() { return "deepseek-chat"; }
    }

    private static CodeTuiView view(ConversationState state, Path root) {
        return new CodeTuiView(state, new Stub(), root);
    }

    @Test
    @DisplayName("思考中设 notice：转轮与切换反馈同屏，转轮不被顶掉")
    void thinkingKeepsSpinner(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);                      // → THINKING
        state.setNotice("已切到 计划模式");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("思考中"), "运行指示必须还在:\n" + s);
        assertTrue(s.contains("已切到 计划模式"), "切换反馈必须也在:\n" + s);
    }

    @Test
    @DisplayName("跑工具时设 notice：工具名与切换反馈同屏")
    void runningToolKeepsToolName(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Bash", "{\"command\":\"npm test\"}");   // → RUNNING_TOOL
        state.setNotice("已切到 跳过权限检查");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("运行 Bash"), "正在跑哪个工具必须还看得见:\n" + s);
        assertTrue(s.contains("已切到 跳过权限检查"), "切换反馈必须也在:\n" + s);
    }

    @Test
    @DisplayName("空闲时 notice 仍独占整行——那时没有动态信息要保")
    void idleNoticeStillTakesWholeLine(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.setNotice("已切到 计划模式");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("已切到 计划模式"), "notice 必须显示:\n" + s);
        assertFalse(s.contains("deepseek-chat"),
                "空闲态 notice 是独占的，不该与常态提示行并存（并存会把行挤爆）:\n" + s);
    }

    /**
     * 杀掉「{@code String ns = " · " + notice;} 没判空」这个变异：
     * 空 notice 时会渲染出一段悬空的分隔符。
     */
    @Test
    @DisplayName("notice 为空时不留悬空分隔符")
    void emptyNoticeLeavesNoDanglingSeparator(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);

        String s = ViewScreen.of(v);
        assertTrue(s.contains("思考中"), "前提：确实在思考态:\n" + s);
        assertFalse(s.contains("·  ·"), "空 notice 拼出了悬空分隔符:\n" + s);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='CodeTuiViewBusyNoticeTest'
```

Expected: `thinkingKeepsSpinner` 与 `runningToolKeepsToolName` **FAIL**，
报「运行指示必须还在」——屏幕上只有 `已切到 … · Ctrl+C 退出` 那一行。
另两条（`idleNoticeStillTakesWholeLine`、`emptyNoticeLeavesNoDanglingSeparator`）
此刻应已是绿的，它们钉的是**不该被改坏**的既有行为。

- [ ] **Step 3: 改 `statusLine()`**

`CodeTuiView.java:2412-2421`，把这一段：

```java
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        String notice = state.notice();
        if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
        // 空闲但仍有已取消子 agent 在收尾：给提示，避免「消息静默入队、无转轮」被误判为卡死（见 busy()/drainingSubagentsHint）。
        String draining = drainingSubagentsHint(state.isIdle(), onSubmit.hasInFlightSubagents());
        Span mode = modeTag(onSubmit.permissionMode());
        if (draining != null) return richText(statusBar.shimmer(draining, qs + " · Ctrl+C 退出", THINK, animTick, mode));
```

替换为：

```java
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        String notice = state.notice();
        // 空闲但仍有已取消子 agent 在收尾：给提示，避免「消息静默入队、无转轮」被误判为卡死（见 busy()/drainingSubagentsHint）。
        // ⚠ 必须算在 notice 判定之前：它也是一条动态信息，不能被 notice 盖掉。
        String draining = drainingSubagentsHint(state.isIdle(), onSubmit.hasInFlightSubagents());
        // notice 独占整行只留给「真空闲」：那时本来就没有动态信息要保。
        // ⚠ 这一句以前无条件 return 且排在 status 开关之前，于是<b>任何</b>忙时 notice 都会把
        // 波光转轮整条盖掉——回合正跑着按一下 Shift+Tab，用户就看不出还在跑了，且 sticky，
        // 不按下一个键不还回来。修法是改结构而不是给 Shift+Tab 开小灶：忙时降级为后缀、与转轮共存。
        if (!notice.isEmpty() && state.isIdle() && draining == null) {
            return text(notice + " · Ctrl+C 退出").style(THINK);
        }
        // 忙时的 notice 后缀。<b>空串必须判</b>：不判会渲染出一段悬空的 " · "。
        String ns = notice.isEmpty() ? "" : " · " + notice;
        Span mode = modeTag(onSubmit.permissionMode());
        if (draining != null) return richText(statusBar.shimmer(draining, qs + ns + " · Ctrl+C 退出", THINK, animTick, mode));
```

再把下面 switch 里的两条忙时分支各加一个 `ns`：

```java
            case THINKING -> richText(statusBar.shimmer("● 思考中…", qs + ns + " · Esc 取消 · Ctrl+C 退出", THINK, animTick, mode));
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
                        qs + ns + " · Esc 取消", RUNNING, animTick, mode));
            }
```

**`IDLE` 分支不动**：走到那里时 notice 必为空（非空且空闲已在上面独占返回了），`ns` 恒为空串。

**`state.isCompacting()` 分支保持在 notice 之前**，不动——压缩条本身就是动画状态指示，
既有注释已说明「已在压缩：动画条已表明状态，不再叠加 notice」。

**面板/模态分支保持在最前**，不动——`permStatusText()` 那几个自己回显 notice（既有设计）。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='CodeTuiViewBusyNoticeTest'
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 5: 改 notice 文案为「已切到 X」**

`CodeTuiView.java:781`：

```java
            state.setNotice("权限模式：" + onSubmit.cyclePermissionMode().label());
```
→
```java
            // 「已切到」而不是「权限模式：」：这条 notice 现在会出现在忙时的后缀位置，
            // 那里读起来必须像一个刚发生的<b>事件</b>；「当前是哪一档」这个常驻状态由行首
            // 的 modeTag 负责，两者分工分开后就不再是同一件事说两遍。
            state.setNotice("已切到 " + onSubmit.cyclePermissionMode().label());
```

- [ ] **Step 6: 跟着改受影响的断言**

`CodeTuiViewModeIndicatorTest.tagSurvivesNoticeBeingCleared` 里：

```java
        assertTrue(screen(v).contains("权限模式：自动接受编辑"), "切换当下应有 notice 反馈");
```
→
```java
        assertTrue(screen(v).contains("已切到 自动接受编辑"), "切换当下应有 notice 反馈");
```

`CodeTuiViewPermissionModeTest` 里的两条断言用的是 `contains("自动接受编辑")` /
`contains("默认")`，**不受影响**，核实一下即可，别改。

- [ ] **Step 7: 跑模块全量**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS。若有别处断言「权限模式：」，一并改掉
（`grep -rn '权限模式：' src/test src/main` 可查；注意 `/permissions` 报告里那句
「权限模式：X（Shift+Tab 循环切换）」是**另一处**，属于报告文案，不动）。

- [ ] **Step 8: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add -A springai-code-tui/src
git commit -m "fix(ui): 忙时 notice 降级为后缀，不再盖掉状态栏的运行指示

statusLine 里 notice 分支排在 status 开关之前且无条件 return，任何忙时
notice 都会把波光转轮整条盖掉，且 sticky。回合正跑着按 Shift+Tab 切档，
用户就看不出还在跑了。

按结构修：独占只留给真空闲态，忙时拼进后缀与转轮共存。notice 文案
「权限模式：X」→「已切到 X」——后缀位置要读起来像事件，常驻状态由 modeTag 负责。"
```

---

### Task 5: 更新 pty 冒烟脚本

`permission_smoke.py` 是唯一走真实 JLine backend + EventParser 的证据链，单测到不了那一层。
本次改动直接冲击它：**4 处 `PLAN → DEFAULT` 一键跳转不再成立，4 条 `assert_absent("跳过权限检查")`
断言的正好是反面**。

**Files:**
- Modify: `springai-code-tui/src/test/resources/scripts/permission_smoke.py`

- [ ] **Step 1: 改三个模式常量的文案，并补上 BYPASS**

`:65-67`：

```python
MODE_DEFAULT = "权限模式：默认"
MODE_ACCEPT = "权限模式：自动接受编辑"
MODE_PLAN = "权限模式：计划模式"
```
→
```python
# 与 CodeTuiView 的切档 notice 一致（Task 4 起是「已切到 X」）。
# ⚠ 这与 /permissions 报告里的「权限模式：X（Shift+Tab 循环切换）」是<b>两处不同的文案</b>，
#   后者没改，last_mode_line() 仍按老串找它。
MODE_DEFAULT = "已切到 默认"
MODE_ACCEPT = "已切到 自动接受编辑"
MODE_PLAN = "已切到 计划模式"
MODE_BYPASS = "已切到 跳过权限检查"
```

`MODE_TAG_ACCEPT` / `MODE_TAG_PLAN`（`:70-71`）**不改**——那是常驻标识 `modeTag`，本次没动。
在它们旁边补一条：

```python
MODE_TAG_BYPASS = "⚠ 跳过权限检查"
```

- [ ] **Step 2: 重写 `check_mode_cycle`（`:391-414`）为四档**

整个函数替换为：

```python
def check_mode_cycle(session):
    """Shift+Tab 四档循环：DEFAULT -> ACCEPT_EDITS -> PLAN -> BYPASS -> DEFAULT.

    BYPASS 不再需要 --dangerously-skip-permissions —— 本进程正是<b>不带</b>该参数启动的，
    所以「它出现在环上」这件事本身就是四档平权的证据。
    """
    session.write(SHIFT_TAB)
    session.wait_for(MODE_ACCEPT)
    print("Shift+Tab OK: 切到「自动接受编辑」.")

    session.write(SHIFT_TAB)
    session.wait_for(MODE_PLAN)
    print("Shift+Tab OK: 第二下到「计划模式」.")

    session.write(SHIFT_TAB)
    session.wait_for(MODE_BYPASS)
    if MODE_TAG_BYPASS not in session.screen_text():
        die("切到 BYPASS 后行首应常驻红色警示标识", session.screen.display)
    print("Shift+Tab OK: 第三下到「跳过权限检查」（无启动参数也进得去）.")

    session.write(SHIFT_TAB)
    session.wait_for(MODE_DEFAULT)
    print("Shift+Tab OK: 第四下回「默认」（四档闭环）.")

    # 再走一整圈：确认循环是稳定的四档，而不是首圈碰巧对。
    for expect in (MODE_ACCEPT, MODE_PLAN, MODE_BYPASS, MODE_DEFAULT):
        session.write(SHIFT_TAB)
        session.wait_for(expect)
    print("Shift+Tab OK: 第二圈同样是 默认→自动接受编辑→计划模式→跳过权限检查→默认.")
```

**四条 `assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")`
（`:396,401,406,413`）随之全部消失**——它们钉的约束已被推翻。按全局纪律 #3，
这是「删掉」而不是「改期望值」：把 `assert_absent` 改成 `assert_present` 会留下一条
名字与内容相反的断言。

- [ ] **Step 3: `restore_default_mode`（`:356-368`）从两下改三下**

```python
def restore_default_mode(session):
    """从 ACCEPT_EDITS 循环回 DEFAULT（四档平权后要按<b>三下</b>）并核实。

    <b>不能</b>用 wait_for(MODE_DEFAULT) 核实：屏幕上一旦留有 /permissions 报告
    （"权限模式：默认（Shift+Tab 循环切换）"），这句 wait 就会命中那条<b>陈旧的 scrollback</b>
    而立刻返回——期 1 的两档写法正是这样在三档下静默变绿，把后面的场景带进了错档
    （实测：check_mcp_tab_guard 因此拿到 PLAN 而非 DEFAULT）。走一次报告才是真凭据。

    <b>档位数每变一次，这里的按键次数就得跟着变</b>，而写死的次数一旦错位就是静默的错档，
    不是红。所以结尾那次 assert_mode_via_report 是本函数的命根子，别为了省时间删掉。
    """
    session.write(SHIFT_TAB)                 # ACCEPT_EDITS -> PLAN
    session.pump(0.4)
    session.write(SHIFT_TAB)                 # PLAN -> BYPASS
    session.pump(0.4)
    session.write(SHIFT_TAB)                 # BYPASS -> DEFAULT
    session.pump(0.4)
    assert_mode_via_report(session, "默认")
```

- [ ] **Step 4: 三处 `PLAN -> DEFAULT` 各补一次 Shift+Tab**

三处都是「注释写着 `# PLAN -> DEFAULT` 的那一行」，各在它**前面**插一次
「PLAN -> BYPASS」并把原注释改对：

**4a.** `check_mode_indicator_persists`（原 `:458`）：

```python
    session.write(CTRL_U)
    session.write(SHIFT_TAB)                         # PLAN -> BYPASS
    session.pump(0.4)
    session.write(SHIFT_TAB)                         # BYPASS -> DEFAULT
    session.wait_for(MODE_DEFAULT)
```

**4b.** `check_plan_approval`（原 `:569`）：

```python
    session.write(SHIFT_TAB)                          # PLAN -> BYPASS
    session.pump(0.4)
    session.write(SHIFT_TAB)                          # BYPASS -> DEFAULT
    session.wait_for(MODE_DEFAULT)
```

**4c.** `check_plan_cancel_turn`（原 `:672`）：

```python
    session.write(SHIFT_TAB)                          # PLAN -> BYPASS
    session.pump(0.4)
    session.write(SHIFT_TAB)                          # BYPASS -> DEFAULT
    session.pump(0.4)
    assert_mode_via_report(session, "默认")
```

**其余 Shift+Tab 站点不动**：`:478,480`、`:629,631`（都是 DEFAULT→ACCEPT→PLAN 两下，
四档下仍然对）、`:786`、`:819`（都只按一下，只为证明 Tab 守卫生效，不依赖终点档位）。

- [ ] **Step 5: 给桩加一个「慢回合」触发词**

新场景需要一个稳定的「思考中」窗口。在 `:106-111` 的标记常量旁加：

```python
SLOW_MARKER = "SLOWNOW"      # -> 桩先睡 6 秒再回，制造一个稳定的「思考中」窗口
SLOW_REPLY = "冒烟回复：慢速回合结束。"
```

在 `StubModel.do_POST` 的分支链里（`:172` 的 `elif SSH_MARKER…` 之前）插一支：

```python
        elif SLOW_MARKER in tail:
            kind, chunks = "slow_text", self._text_chunks(SLOW_REPLY)
            slow = True
```

并在函数开头 `tail = …` 之后加 `slow = False`，在 `self.send_response(200)` **之前**加：

```python
        if slow:
            # 睡在应答之前：应用已提交、处于「思考中」，而流还没开始。
            time.sleep(6.0)
```

（文件顶部已 `import time`，无需新增。）

- [ ] **Step 6: 加「运行中切档」场景**

在 `check_mode_cycle` 之后新增：

```python
def check_mode_switch_during_turn(session):
    """回合运行中切权限模式：波光转轮不能消失。

    钉的缺陷：statusLine 里 notice 分支曾排在 status 开关之前且<b>无条件 return</b>，
    于是切档的 notice 把「● 思考中…」整条盖掉，而且是 sticky 的——不按下一个键不还回来。
    用户看不出回合还在跑。

    <b>断言必须落在「同一个物理行」上</b>，不能只查两个子串都在屏上：
    屏幕上留着前面场景的旧「思考中」与旧 notice，两个独立的 `in screen_text()`
    可以各自命中一条陈旧 scrollback 而双双变绿（与 wait_for 命中陈旧 scrollback 同族）。
    只有「转轮与切换反馈在同一行」才真正证明后缀降级生效了。

    进入时 mode=DEFAULT，离开时 mode=DEFAULT。
    """
    session.write(SLOW_MARKER.encode() + b"\r")
    wait_until(session, lambda: "思考中" in session.screen_text(), 10, "进入思考态")

    session.write(SHIFT_TAB)
    session.pump(0.8)

    row = find_row(session, MODE_ACCEPT)
    if row < 0:
        die("运行中切档没有给出反馈（屏上找不到 %r）" % MODE_ACCEPT, session.screen.display)
    line = session.screen.display[row]
    if "思考中" not in line:
        die("转轮与切换反馈不在同一行 —— notice 仍然独占了状态行：%r" % line,
            session.screen.display)
    print("运行中切档 OK: 转轮与切换反馈同屏同行（%r）." % line.strip())

    session.wait_for(SLOW_REPLY, timeout=30)
    restore_default_mode(session)          # 当前在 ACCEPT_EDITS，按三下回默认
```

- [ ] **Step 7: 把新场景挂进 `main()`**

在 `main()` 里 `check_mode_cycle(session)` 那一行**之后**加：

```python
    check_mode_switch_during_turn(session)
```

- [ ] **Step 8: 实机跑冒烟**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn -q -pl springai-code-tui package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && /usr/bin/python3 src/test/resources/scripts/permission_smoke.py
```

Expected: 末行 `SMOKE PASS`，退出码 0。

**改了 Java 就必须重新 `package`**——冒烟跑的是打好的包，不是 target/classes。
忘了这一步会拿旧代码跑出假绿。

- [ ] **Step 9: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/src/test/resources/scripts/permission_smoke.py
git commit -m "test(smoke): 冒烟脚本跟上四档平权，新增「运行中切档」一幕

- 四条 assert_absent(\"跳过权限检查\") 删除：它们钉的约束已被推翻
- 三处 PLAN->DEFAULT 一键跳转改为两键（中间经过 BYPASS）
- restore_default_mode 从两下改三下
- 新增 SLOWNOW 触发词与 check_mode_switch_during_turn：断言转轮与切换
  反馈落在<b>同一个物理行</b>——两个独立的子串检查会各自命中陈旧 scrollback
  而双双假绿"
```

---

### Task 6: README

README 有 6 处会因本次改动变成假话。**只改「怎么进 BYPASS」，不碰「BYPASS 里会发生什么」**
——后者本次一行代码都没动（spec §3 N3）。

**Files:**
- Modify: `springai-code-tui/README.md:12,41,204,274,324,662`

- [ ] **Step 1: 特性列表（`:12`）**

把这一句：

```
`Shift+Tab` 在「默认 / 自动接受编辑 / 计划模式」三档间循环，当前档位**常驻状态栏**；
```
改为：
```
`Shift+Tab` 在「默认 / 自动接受编辑 / 计划模式 / 跳过权限检查」四档间循环，当前档位**常驻状态栏**；
```

- [ ] **Step 2: 命令判定那段（`:41`）**

把「只在**前三档**如此——`--dangerously-skip-permissions` 会跳过内置底线」改为
按**档位**说而不是按启动参数说（那一档现在有两种进法）：

```
⚠️ 但**只在前三档**如此——「跳过权限检查」档会跳过内置底线，那一档里 **deny 规则反过来成了唯一还拦得住的东西**。
```

- [ ] **Step 3: 权限模式表下方（`:204`）**

把这一条：

```
- `Shift+Tab` 在**前三档**之间循环（默认 → 自动接受编辑 → 计划模式 → 默认）。**BYPASS 只能由 `--dangerously-skip-permissions` 启动进入**，键盘和配置文件都进不去（否则一个按键或一次 `git clone` 就把这道启动开关架空了）。
```

替换为：

```
- `Shift+Tab` 在**四档**之间循环（默认 → 自动接受编辑 → 计划模式 → 跳过权限检查 → 默认）。BYPASS 也在环上，**不需要任何启动参数**——你当面按键、人在场，且进去之后行首常驻红色 `⚠ 跳过权限检查`。
- **配置文件仍然进不去**：`.codetui/permissions.json` 里写 `defaultMode: "BYPASS"` 一律被丢弃并记 WARN。它与键盘切档是两个威胁模型——那个文件是 `git clone` 时一起带过来的，进目录一启动，模型的第一次工具调用就零检查跑了，你一个键都没按、屏幕上什么都没变。
```

- [ ] **Step 4: `--permission-mode` 段（`:274`）**

把「**它不接受 `bypass`**：全放行只能由 `--dangerously-skip-permissions` 进，否则那道启动开关就等于有了第二个入口」
改为：

```
**它不接受 `bypass`**：`--dangerously-skip-permissions` 已经是「启动即进该档」的写法，不再设第二条等价路径（运行期用 `Shift+Tab` 切进去则是允许的）。
```

同一段里「优先级：`--dangerously-skip-permissions` > `--permission-mode` > 配置文件 `defaultMode`」
**保持不变**——这条仍然成立。

- [ ] **Step 5: 后台子 agent 那节（`:324`）**

把第 3 条：

```
3. **`--dangerously-skip-permissions` 启动**——全放行，后台与前台再无差别。只适合一次性容器（先读完上方「安全声明」）。
```

改为：

```
3. **切到「跳过权限检查」档**（`Shift+Tab` 四下，或 `--dangerously-skip-permissions` 启动即进）——全放行，后台与前台再无差别。只适合一次性容器或你完全清楚后果的场合（先读完上方「安全声明」）。
```

- [ ] **Step 6: 快捷键表（`:662`）**

```
| Shift+Tab | 循环权限模式（默认 → 自动接受编辑 → 计划模式 → 默认；当前档位常驻状态栏） |
```
→
```
| Shift+Tab | 循环权限模式（默认 → 自动接受编辑 → 计划模式 → 跳过权限检查 → 默认；当前档位常驻状态栏） |
```

- [ ] **Step 7: 核对没有漏网**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -n "三档\|前三档\|只能由 --dangerously-skip-permissions\|唯一的例外" README.md
```

Expected: 只剩 `:41` 那处「只在**前三档**如此」（讲的是内置底线在哪几档生效，是对的）
与 `:12` 那处「**`--dangerously-skip-permissions` 是唯一的例外**」——**后者要改**：
它现在说的是「哪一档跳过内置底线」，主语该是档位不是参数：

```
**「跳过权限检查」档是唯一的例外——它连内置底线与 `ask` 规则都跳过，只留 deny 规则**
```

改完再跑一次上面的 grep，确认「只能由 --dangerously-skip-permissions」不再出现。

- [ ] **Step 8: Commit**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git add springai-code-tui/README.md
git commit -m "docs(readme): 权限模式改为四档，BYPASS 不再需要启动参数

只改「怎么进 BYPASS」，不碰「BYPASS 里会发生什么」——后者本次一行没动。
描述 BYPASS 行为的地方主语一律从启动参数改回档位（那一档现在有两种进法）。"
```

---

### Task 7: 全量回归与验收

前六个任务各自跑过局部测试，本任务把整条链再走一遍，并逐条核对 spec §11 的验收标准。

**Files:** 无（只跑与核对；若发现问题回到对应任务修）

- [ ] **Step 1: 模块全量测试**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui
```

Expected: BUILD SUCCESS，`Failures: 0, Errors: 0`。
基线参考：本分支起点（`35caf44`）是 `Tests run: 1240, Skipped: 9`。
本次净变化预期：`PermissionModeTest` −1（三档/四档两个方法合成两个新方法，净 0）、
`CodeTuiViewBusyNoticeTest` +4、`PermissionEngineTest` 净 0、`PermissionStartupTest` 净 0。
**总数应在 1244 上下**。若总数比基线少，说明有测试被删而没有等价替代，回去查。

- [ ] **Step 2: 确认门禁字段真的不存在了**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -rn "bypassAllowed" src/ README.md
```

Expected: **无输出**。

- [ ] **Step 3: 确认两条禁令还在**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn test -pl springai-code-tui -Dtest='PermissionStartupTest,PermissionConfigLoaderTest'
```

Expected: BUILD SUCCESS。这是 Task 1 立的钉子——它俩现在是全仓唯一还在守
「用户全程无感被放成裸奔」的东西。

- [ ] **Step 4: 重新打包并跑冒烟**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
mvn -q -pl springai-code-tui package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && /usr/bin/python3 src/test/resources/scripts/permission_smoke.py
```

Expected: `SMOKE PASS`。

- [ ] **Step 5: 跑其余冒烟脚本，确认没有被连累**

状态栏是所有脚本都会看到的东西，`statusLine` 改了结构，别的脚本可能有断言踩在上面。

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
for s in clear_smoke background_smoke mcp_manage_smoke memory_smoke edit_shortcut_smoke attachment_smoke; do
  echo "=== $s ==="
  /usr/bin/python3 "src/test/resources/scripts/$s.py" 2>&1 | tail -3
done
```

Expected: 每个都以 `SMOKE PASS` 结尾。
若某个红了，先看它是不是断言了「notice 独占整行」——那是本次故意改掉的行为，
按新语义更新它；其它原因要停下来查。

- [ ] **Step 6: 逐条核对 spec §11 验收标准**

打开 `docs/superpowers/specs/2026-08-04-permission-mode-ux-design.md` §11，逐条对照。
第 1、2、5、6 条**必须实机确认**（跑起来自己按），不能只靠测试绿：

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
java -jar target/springai-code-tui.jar
```

（jar 名以 `target/` 下实际产物为准。）

- 连按 Shift+Tab 三下 → 行首出现红色 `⚠ 跳过权限检查`；
- 第四下 → 标识消失，回到默认；
- 发一条会跑一会儿的消息，运行中按 Shift+Tab → 波光转轮**不消失**，右侧出现「已切到 …」；
- `Ctrl+C` 退出，改用 `--dangerously-skip-permissions` 启动 → 直接进 BYPASS 且打横幅。

- [ ] **Step 7: 确认文档与代码没有互相打脸**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo/springai-code-tui
grep -n "内置危险检查\|内置底线" \
  src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java
```

Expected: 能看到「内置危险检查在本档被跳过」而**不是**「仍然生效」。
这是 spec §2 的那条错误 javadoc——它误导过本设计的第一版，别让它活下来。

- [ ] **Step 8: 收尾提交（若前面各步有零星修补）**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git status --short
```

若有未提交改动，提交它们；若干净，本任务无需提交。

- [ ] **Step 9: 合并前的最后一眼**

```bash
cd /Users/zxh/IdeaProjects/springai-agentdemo
git log --oneline main..feat/permission-mode-ux
git diff --stat main...feat/permission-mode-ux
```

预期形状：**净删除多于净新增**（本次的主体是拆掉一道门禁），
新增的只有 `CodeTuiViewBusyNoticeTest`、冒烟的新场景与两份文档。

合并交由用户决定，**不要自行合并或推送**。
