# BYPASS 真正跳过全部检查 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `--dangerously-skip-permissions` 名副其实——开了之后**永远不会停下来等人**，同时把「我本来会拦这个」的信息留痕给你事后看。

**Architecture:** 把 `PermissionEngine` 里 BYPASS 的判定从决策链第 6 步提到第 1 步之后（deny 规则之后、内置底线之前）。放弃拦截但不放弃告知：新增一个 `AgentListener` 回调，命中内置底线时打一行留痕，回合末汇总一次。

**Tech Stack:** Java 17（`maven.compiler.release=17`，**无类型模式 switch、无 record pattern**）、JUnit 5、pty + pyte 实机冒烟。

**设计依据：** [2026-08-02 BYPASS 真正跳过设计](../specs/2026-08-02-bypass-really-bypasses-design.md)

---

## 全局纪律（每个任务都适用）

- **验证命令必须模块作用域**：`mvn test -pl springai-code-tui`。
  **绝不**加 `-DfailIfNoSpecifiedTests=false`——整仓跑会被 3 个空模块打挂，加这个参数只是把问题盖住。
- **断言用 JUnit `org.junit.jupiter.api.Assertions`**。本模块**没有 assertj 依赖**，**不要往 pom 加**。
- **Java 17**：不写 `case String s ->` 这类类型模式 switch，不写 record pattern，编译不过。
- **绝不用 `git stash` / `git commit --amend` / `git add -A`**——本项目在并行 agent 下已因此出过两次事故。`git add` 逐个列文件。
- **变异验证用 `git worktree`**：
  ```bash
  git worktree add --detach /tmp/mut1 HEAD
  # 在 /tmp/mut1 里改一行、跑测试、看红不红
  git worktree remove --force /tmp/mut1
  ```
  它只在 `.git/worktrees/` 加元数据，**不碰共用工作树的索引与文件**。
- **联网测试默认跳过**（类级 `CODETUI_LIVE_TESTS=1` 门控），不用管。

### 基线

改动前：`mvn test -pl springai-code-tui` → **1098 run / 0 failures / 9 skipped / BUILD SUCCESS**。

---

## ⚠️ 这是**行为变更**，不是加功能

改的是 **v1.6.0 已发布**的权限语义。三条纪律：

1. **只改 BYPASS 一档。** `DEFAULT` / `ACCEPT_EDITS` / `PLAN` 三档的行为**一个字都不能变**——最容易犯的错是把「跳过」写成无条件的。这条有专门的变异守着。
2. **已发布的 `docs/release-notes/v1.6.0.md` 不许改。** 它白纸黑字写着「deny 规则与内置底线在该模式下仍然生效，**这是刻意的**」——那是发出去的承诺，偷偷改掉等于篡改历史。
3. **核心不变量：BYPASS 下不存在任何 ASK 结果。** 这才是用户要的东西，须有测试直接钉住，而不是靠「某几条不弹了」间接推断。

---

## 文件结构

**修改**

| 文件 | 改动 |
|---|---|
| `agent/permission/PermissionEngine.java` | BYPASS 判定提前；放行文案更正；命中内置底线时回报留痕 |
| `agent/AgentListener.java` | 新增 `onGuardrailBypassed(long, String)` 默认空实现 |
| `ui/ConversationState.java` | 累积本回合的留痕，`onTurnComplete` 时汇总 |
| `springai-code-tui/README.md` | 更正「内置底线 BYPASS 也盖不住」的表述 |
| `SECURITY.md` | 同上 |

**刻意不改**：`docs/release-notes/v1.6.0.md`（见上）、`CHANGELOG.md`（纯发版索引，等发版再进）、`permission_smoke.py`（它里面的 BYPASS 断言是「不带启动参数时循环里不该出现 BYPASS」，与本次无关——**先自己 grep 确认**，若确实无关就别动）。

---

### Task 1：引擎——BYPASS 判定提前

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java`

#### 先读现状

```bash
grep -n "1. deny 规则\|2. 内置危险检查\|4. ask 规则\|mode == PermissionMode.BYPASS" \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java
grep -n "BYPASS" springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java
```

现在的决策链（`decide` 方法内）：第 1 步 deny 规则 → 第 2 步内置底线（`askOnly`）→ 第 3 步预留插槽 → 第 4 步 ask 规则 → 第 5 步 allow 规则 → 第 6 步 `decideByMode`（BYPASS 在这里返回 allow）。

现有测试里**这一条要反转**：`PermissionEngineTest` 的 `② 内置危险检查在 BYPASS 下也触发`（约 117 行）。

- [ ] **Step 1: 先改测试（TDD：让它先红）**

把那条 `②` 用例整体替换成下面这一组。**保留它原有的 `@Nested` 分组位置与 `engine(...)` 辅助方法用法**，只换用例本身：

```java
        @Test
        @DisplayName("② BYPASS 下内置危险检查不再触发——开关不该说谎")
        void builtinDangerIsSkippedUnderBypass(@TempDir Path root) throws Exception {
            PermissionEngine e = engine(root, PermissionMode.BYPASS);
            PermissionDecision d = e.decide("Write",
                    "{\"filePath\":\"" + root.resolve(".git/hooks/pre-commit") + "\"}");
            assertEquals(PermissionBehavior.ALLOW, d.behavior(),
                    "BYPASS 下命中内置底线仍在询问，开关等于假的：" + d.reason());
        }

        @Test
        @DisplayName("② BYPASS 下 ask 规则也不再触发（「每次问我」与「别问」直接矛盾，按 BYPASS 走）")
        void askRuleIsSkippedUnderBypass(@TempDir Path root) throws Exception {
            PermissionEngine e = engine(root, PermissionMode.BYPASS, "ask:Bash(mvn test:*)");
            PermissionDecision d = e.decide("Bash", "{\"command\":\"mvn test\"}");
            assertEquals(PermissionBehavior.ALLOW, d.behavior(), d.reason());
        }

        /**
         * ★ 核心不变量：BYPASS 下<b>不存在任何 ASK 结果</b>。
         *
         * <p>用户要的不是「某几条不弹了」，而是「跑起来之后不会停下来等我」。
         * 逐条断言容易漏掉新增的分支，这条直接从结果侧钉死。
         */
        @Test
        @DisplayName("② BYPASS 下永远不会出现 ASK（核心不变量）")
        void bypassNeverAsks(@TempDir Path root) throws Exception {
            PermissionEngine e = engine(root, PermissionMode.BYPASS, "ask:Bash(mvn test:*)");
            String[][] cases = {
                    {"Write", "{\"filePath\":\"" + root.resolve(".git/hooks/pre-commit") + "\"}"},
                    {"Write", "{\"filePath\":\"" + System.getProperty("user.home") + "/.zshrc\"}"},
                    {"Read",  "{\"filePath\":\"" + System.getProperty("user.home") + "/.ssh/id_rsa\"}"},
                    {"Bash",  "{\"command\":\"rm -rf $BUILD_DIR\"}"},
                    {"Bash",  "{\"command\":\"mvn test\"}"},
                    {"Bash",  "{\"command\":\"echo hi\"}"},
            };
            for (String[] c : cases) {
                PermissionDecision d = e.decide(c[0], c[1]);
                assertNotEquals(PermissionBehavior.ASK, d.behavior(),
                        "BYPASS 下仍会停下来等人：" + c[0] + " " + c[1] + " → " + d.reason());
            }
        }

        /**
         * ★ deny 规则保留：它是用户自己写的明令，且 clone 别人仓库时它保护你。
         * 但注意——deny <b>不阻塞</b>，直接拒绝并告知模型，回合继续。
         */
        @Test
        @DisplayName("② BYPASS 下 deny 规则仍硬拒（且不阻塞）")
        void denyRuleStillAppliesUnderBypass(@TempDir Path root) throws Exception {
            PermissionEngine e = engine(root, PermissionMode.BYPASS, "deny:Bash(git push:*)");
            PermissionDecision d = e.decide("Bash", "{\"command\":\"git push origin main\"}");
            assertEquals(PermissionBehavior.DENY, d.behavior(), d.reason());
        }

        /**
         * ★ 只改 BYPASS 一档。这条守着最容易犯的错：把「跳过」写成无条件的。
         */
        @Test
        @DisplayName("② 另外三档的内置底线一个字不变")
        void otherModesStillEnforceBuiltinDanger(@TempDir Path root) throws Exception {
            String hook = "{\"filePath\":\"" + root.resolve(".git/hooks/pre-commit") + "\"}";
            for (PermissionMode m : new PermissionMode[]{
                    PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS}) {
                PermissionEngine e = engine(root, m);
                assertEquals(PermissionBehavior.ASK, e.decide("Write", hook).behavior(),
                        m + " 档的内置底线被误伤了");
            }
            // PLAN 档的危险写操作本就该是 DENY 而非 ASK（否则只有最危险的那批能当场批准）
            PermissionEngine plan = engine(root, PermissionMode.PLAN);
            assertEquals(PermissionBehavior.DENY, plan.decide("Write", hook).behavior(),
                    "PLAN 档的特例被误伤了");
        }
```

**注意**：
- `engine(root, mode, rules...)` 是该测试类**已有**的辅助方法，先读它的签名确认参数形态（规则是 `"deny:Bash(...)"` 这种字符串还是别的），不一致就按实际调整。
- `decide(...)` 的实际签名也先 grep 确认（是 `(String toolName, String toolInput)` 还是带更多参数）。
- 需要 `assertNotEquals` 的 import。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=PermissionEngineTest
```
期望：新增的四条里至少 `builtinDangerIsSkippedUnderBypass` / `askRuleIsSkippedUnderBypass` / `bypassNeverAsks` 变红（当前实现下它们本来就该红）。
`denyRuleStillAppliesUnderBypass` 与 `otherModesStillEnforceBuiltinDanger` **现在就该是绿的**——它们守的是不该变的部分。

**看到该红的红了才继续。**

- [ ] **Step 3: 改引擎**

在 `decide(...)` 里第 1 步 deny 规则**之后**、第 2 步内置底线**之前**插入：

```java
        // ★ BYPASS：到此为止。deny 规则（第 1 步，用户自己写的明令）已经过了，其余一律放行。
        //
        // 为什么排在这里而不是原来的第 6 步：一个名字带 dangerously 的开关，打开之后还在弹窗，
        // 那它就是在骗人。原设计「护栏不是牢笼，人确认了就该能做」本身没错，但它默认了
        // 总有人在场——半无人值守（丢个大任务给 agent 然后人离开）时，护栏变成死锁：
        // PermissionCallback 阻塞在无超时的一次性队列上，只有 UI 应答或回合 dispose 能解开。
        //
        // 保留 deny 而不保留内置底线，是因为二者来源不同：内置底线是本项目的意见，
        // deny 是用户写在 permissions.json 里的明令，且 <项目根>/.codetui/permissions.json
        // 是仓库带来的——clone 别人的仓库时那些 deny 规则本来就是保护你的。
        //
        // ask 规则一并跳过：它的语义是「每次都问我」，与 BYPASS 的「不问」直接矛盾，
        // 此时按 BYPASS 走是唯一自洽的解释。
        //
        // ⚠️ 核心不变量：本档下不得产生任何 ASK。改这里之前先看 bypassNeverAsks 那条测试。
        if (mode == PermissionMode.BYPASS) {
            String danger = builtinDanger(entry, path, target);
            if (danger != null) {
                // 放弃拦截，但不放弃告知——回合末汇总给回来的人看（见 Task 2）。
                notifyGuardrailBypassed(danger);
            }
            return PermissionDecision.allow("BYPASS 模式已跳过权限检查（deny 规则仍然生效）");
        }
```

`notifyGuardrailBypassed` 的实现属于 **Task 2**。本任务先加一个**空的私有方法**占位，让代码能编译：

```java
    /** BYPASS 下放行了一个通常需要确认的操作。留痕接线见 Task 2。 */
    private void notifyGuardrailBypassed(String what) {
        // Task 2 接上 AgentListener 回调
    }
```

同时把**第 6 步 `decideByMode` 里的 BYPASS 分支删掉**（它现在永远到不了），并在 `decideByMode` 的 javadoc 或原位置留一行注释说明「BYPASS 已在决策链更前面处理」——否则下一个读代码的人会以为漏了一档。

- [ ] **Step 4: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=PermissionEngineTest
```
期望：全绿。

- [ ] **Step 5: 全模块回归**

```bash
mvn test -pl springai-code-tui     # 基线 1098
```

**很可能有别的测试因这次行为变更而红**（`AgentToolsSecurityTest`、`PermissionStartupTest` 等都提到过 BYPASS）。逐个看：

- 断言的是**本次要改的行为**（BYPASS 下仍拦） → **反转它**，并在提交信息里列出改了哪些
- 断言的是**别的东西**（比如「没有 `--dangerously-skip-permissions` 时进不了 BYPASS」） → **不该红**，红了说明你改宽了，回去查

**不要为了让测试变绿而放宽实现。** 分不清就在报告里问。

- [ ] **Step 6: 变异验证（强制，`git worktree`，一次只拆一处）**

| 变异 | 期望变红 |
|---|---|
| 把新加的 BYPASS 分支挪回第 6 步（即还原） | `builtinDangerIsSkippedUnderBypass`、`askRuleIsSkippedUnderBypass`、`bypassNeverAsks` |
| 把 BYPASS 分支的条件去掉（**无条件**跳过全部检查） | `otherModesStillEnforceBuiltinDanger` |
| 把 BYPASS 分支挪到第 1 步**之前**（deny 也被跳过） | `denyRuleStillAppliesUnderBypass` |

第二个变异是本任务最重要的一条：**它守着「只改一档」**。若它没红，说明另外三档没有被测试保护，你必须先补测试。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java
# 若还反转了别的测试文件，一并逐个列上
git commit -m "feat(permission): BYPASS 真正跳过全部检查（行为变更）

一个写着 --dangerously-skip-permissions 的开关，打开之后还在弹窗，
那它就是在骗人。原设计「护栏不是牢笼、人确认了就该能做」本身没错，
但它默认了总有人在场——半无人值守时护栏变成死锁：PermissionCallback
阻塞在无超时的一次性队列上，只有 UI 应答或回合 dispose 能解开。

BYPASS 判定从决策链第 6 步提到第 1 步之后：内置底线与 ask 规则不再
执行；deny 规则保留（那是用户写在 permissions.json 里的明令，且
项目层的 deny 是仓库带来的、clone 别人仓库时保护你）。ask 一并跳过
是因为「每次问我」与「不问」直接矛盾，按 BYPASS 走才自洽。

核心不变量「BYPASS 下不存在任何 ASK」有专门测试直接钉住，不靠逐条
断言间接推断。另有一条守着「只改 BYPASS 一档」——最容易犯的错是把
跳过写成无条件的。

⚠️ 这是 v1.6.0 已发布语义的变更，发版说明另行交代。"
```

---

### Task 2：留痕——放弃拦截，不放弃告知

内置底线的价值有两半：**拦住你**，和**让你知道发生了什么**。Task 1 放弃了前一半，这个任务保住后一半。

用户明确说了「人会回来看」，所以留痕不是可选项。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/GuardrailBypassNoticeTest.java`（新建）

#### 接缝形状（已有先例，照抄）

`AgentListener` 里已有 `onRuleRecorded(long turnId, boolean ok, String message)` ——权限层向 UI 报「规则记下了没」的默认空实现。留痕用同一形状：

```java
    /**
     * BYPASS 下放行了一个<b>通常需要确认</b>的操作。
     *
     * <p>默认空实现，便于回显桩/测试桩省略。落地端应：即时打一行进 scrollback，
     * 并按 {@code turnId} 累积、在 {@link #onTurnComplete} 时汇总一次。
     *
     * <p><b>为什么即时行与汇总都要</b>：即时行保证「正在发生时屏幕上有」，
     * 汇总保证「人回来时不必翻几百行 scrollback」。半无人值守场景两者都必要。
     */
    default void onGuardrailBypassed(long turnId, String what) { }
```

**汇总不另加回调**——视图按 `turnId` 累积，在已有的 `onTurnComplete(turnId)` 里 flush 并清空。少一个接缝、少一处要保持同步的状态。

- [ ] **Step 1: 先写测试**

新建 `GuardrailBypassNoticeTest.java`。先读 `ConversationState` 现有测试怎么造实例、怎么读 `pending` 行（`grep -rn "pushInfo\|drainPending" springai-code-tui/src/test/java | head`）。

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailBypassNoticeTest {

    /** 即时行：正在发生时屏幕上就要有。 */
    @Test
    void eachBypassProducesAnImmediateLine() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：/p/.git/hooks/pre-commit");
        String text = drainText(s);
        assertTrue(text.contains("BYPASS"), text);
        assertTrue(text.contains(".git/hooks/pre-commit"), text);
    }

    /** 回合末汇总：人回来时不必翻几百行 scrollback。 */
    @Test
    void turnCompleteSummarisesAllBypassesOfThatTurn() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        s.onGuardrailBypassed(1L, "读取凭据：b");
        drainText(s);                      // 清掉即时行，只看汇总
        s.onTurnComplete(1L);
        String text = drainText(s);
        assertTrue(text.contains("2"), "汇总没说放行了几个：" + text);
        assertTrue(text.contains("a"), text);
        assertTrue(text.contains("b"), text);
    }

    /** 汇总要去重——同一个操作在一个回合里可能命中多次。 */
    @Test
    void summaryDeduplicatesRepeatedBypasses() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        s.onGuardrailBypassed(1L, "写入 .git/ 内部：a");
        drainText(s);
        s.onTurnComplete(1L);
        String text = drainText(s);
        assertTrue(text.contains("1"), "重复项没去重：" + text);
    }

    /** 汇总只列本回合的——上一回合的账不该算到这一回合头上。 */
    @Test
    void summaryOnlyCoversTheCurrentTurn() {
        ConversationState s = new ConversationState();
        s.onGuardrailBypassed(1L, "回合一的操作");
        drainText(s);
        s.onTurnComplete(1L);
        drainText(s);
        s.onGuardrailBypassed(2L, "回合二的操作");
        drainText(s);
        s.onTurnComplete(2L);
        String text = drainText(s);
        assertTrue(text.contains("回合二的操作"), text);
        assertFalse(text.contains("回合一的操作"), "上一回合的记录漏进来了：" + text);
    }

    /** 没有任何放行时，回合结束不该多出一行噪音。 */
    @Test
    void turnWithNoBypassProducesNoSummary() {
        ConversationState s = new ConversationState();
        s.onTurnComplete(1L);
        assertEquals("", drainText(s).trim());
    }

    /** 取走当前待显示的行并拼成一段文本。具体 API 以 ConversationState 实际的为准。 */
    private static String drainText(ConversationState s) {
        StringBuilder b = new StringBuilder();
        for (OutputLine l : s.drainPending()) b.append(l.text()).append('\n');
        return b.toString();
    }
}
```

⚠️ **`drainPending()` / `OutputLine.text()` 的实际 API 先 grep 确认**，不一致就按实际改 `drainText`。别猜。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=GuardrailBypassNoticeTest
```
期望：编译失败，`ConversationState` 没有 `onGuardrailBypassed`。

- [ ] **Step 3: 加 `AgentListener` 回调**

把上面「接缝形状」那段 `default void onGuardrailBypassed(...)` 加进 `AgentListener`，位置紧邻 `onRuleRecorded`（同类职责放一起）。

- [ ] **Step 4: 让引擎真的回报**

`PermissionEngine` 现在有 Task 1 留的空方法 `notifyGuardrailBypassed(String)`。把它接上：

- 引擎需要拿到 `AgentListener` 与当前 `turnId`。**先看清楚它现在有没有**——`grep -n "listener\|turnId" PermissionEngine.java | head`。
- **若引擎构造时拿不到 listener**：不要为此重构引擎的依赖。改成让 `PermissionCallback`（它本来就持有 listener 和 turnId）来发这个通知——引擎只需把「这次是 BYPASS 放行且命中了内置底线」这个事实带在 `PermissionDecision` 里（比如加一个 `String bypassedGuardrail` 字段，null 表示没有）。

  **这个方向更好**：引擎保持纯判定、不认识 UI；`PermissionCallback` 本来就是判定与 UI 之间的那一层。**你自己判断，并在报告里说明选了哪条、为什么。**

- [ ] **Step 5: `ConversationState` 落地**

实现 `onGuardrailBypassed`：即时 `pushInfo` 一行 + 按 turnId 累积（用 `LinkedHashSet` 天然去重且保序）。在 `onTurnComplete` 里 flush 并清空该 turnId 的集合。

⚠️ **本项目铁律：一个 `OutputLine` = 一个物理行。** 汇总有多行就 push 多次，**不要**在一个字符串里塞 `\n`（会被 `println` 塌成一行截断）。

⚠️ `ConversationState` 的 listener 方法是 `synchronized`，与 `drainPending()` 共用同一把锁。**本方法必须立刻返回、绝不阻塞**（在这里阻塞会冻住整个 TUI，连 Esc 都按不动）。

- [ ] **Step 6: 跑测试 + 全模块回归**

```bash
mvn test -pl springai-code-tui -Dtest=GuardrailBypassNoticeTest
mvn test -pl springai-code-tui
```

- [ ] **Step 7: 变异验证（强制，`git worktree`，一次只拆一处）**

| 变异 | 期望变红 |
|---|---|
| `onGuardrailBypassed` 里去掉即时 `pushInfo` | `eachBypassProducesAnImmediateLine` |
| `onTurnComplete` 里不 flush 汇总 | `turnCompleteSummarisesAllBypassesOfThatTurn` |
| 累积容器换成 `ArrayList`（不去重） | `summaryDeduplicatesRepeatedBypasses` |
| `onTurnComplete` 里 flush 但**不清空** | `summaryOnlyCoversTheCurrentTurn` |
| **引擎里去掉 `notifyGuardrailBypassed` 调用**（纯函数不动） | **需要一条接线断言**——若你现有的测试一条都没红，说明「引擎真的会回报」这件事没被测到，**必须补一条**（构造 BYPASS 引擎 + 危险操作，断言 listener 收到了通知） |

最后一条是重点：前四条测的都是 `ConversationState` 这一端，**就算引擎压根不调用它，四条照样全绿**。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/GuardrailBypassNoticeTest.java
# 若还改了 PermissionCallback / PermissionDecision，一并逐个列上
git commit -m "feat(permission): BYPASS 放行内置底线时留痕

内置底线的价值有两半：拦住你，和让你知道发生了什么。上一个提交
放弃了前一半，这个提交保住后一半——用户明说人会回来看。

即时行保证「正在发生时屏幕上有」，回合末汇总保证「回来时不必翻
几百行 scrollback」，两者都必要。汇总按 turnId 累积、去重、
flush 后清空。

不阻塞、不询问，只留痕。"
```

---

### Task 3：文档

**Files:**
- Modify: `springai-code-tui/README.md`
- Modify: `SECURITY.md`

#### ⚠️ 不许改的

- **`docs/release-notes/v1.6.0.md`** —— 已发布的承诺，偷偷改等于篡改历史。**一个字都不要动。**
- **`CHANGELOG.md`** —— 纯发版索引表，没有 Unreleased 段，等发版时再进。

- [ ] **Step 1: 找出所有过期表述**

```bash
grep -rn "BYPASS\|dangerously-skip-permissions\|内置底线\|盖不住" \
  springai-code-tui/README.md SECURITY.md
```

至少这几处会命中（**先看实际措辞再改，别按我的描述猜**）：
- README 里「内置底线（任何 allow 规则与 BYPASS 都盖不住）」这类说法
- README 里「`--dangerously-skip-permissions`（**但 deny 规则与内置底线在该模式下仍然生效**）」这类括注
- `SECURITY.md` 的「已知且被接受的风险」一节可能提到权限层

- [ ] **Step 2: 改 README**

要说清三件事：

1. **`--dangerously-skip-permissions` 真的跳过全部检查**——内置底线与 ask 规则都不再执行
2. **只有 deny 规则仍生效**，且它**不阻塞**（直接拒、告知模型、回合继续）。所以**开了 BYPASS 就不会停下来等人**——这正是无人值守能用的原因
3. **想在无人值守下保留某些禁令，得自己写 deny 规则进 `permissions.json`**。这条必须写，否则用户不知道还有这条路

再补一句留痕：BYPASS 放行内置底线时会打一行，回合末汇总，**方便你回来时看到不在期间发生了什么**。

- [ ] **Step 3: 改 `SECURITY.md`**

在「已知且被接受的风险」一节加/改一条，说清：

- BYPASS 下**没有最后一道询问**了——提示注入把模型说服去 `rm -rf ~` 时，只有你自己写的 deny 规则能拦
- 留痕能让你**事后**发现，**阻止不了**
- 留痕只进 scrollback，`/clear` 或滚出屏幕就没了，**不落盘**

按该节既有条目的长度与语气写，别写得比邻居长一倍。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/README.md SECURITY.md
git commit -m "docs(permission): 更正 BYPASS 语义，补留痕与 deny 兜底说明

BYPASS 现在真的跳过全部检查，README 里「内置底线连 BYPASS 都盖不住」
的说法已过期。同时写清两件用户需要知道的事：deny 规则是无人值守下
唯一还生效的防线（想保留禁令就自己写进 permissions.json）；放行时
会留痕，方便回来时看到不在期间发生了什么。

SECURITY 补一条：BYPASS 下没有最后一道询问，留痕只能事后发现、
阻止不了，且只进 scrollback 不落盘。

已发布的 v1.6.0 发版说明不动——那是发出去的承诺，行为变更在下一版
说明里交代。"
```

---

## 自审结果

**1 · spec 覆盖检查**

| spec 章节 | 落点 |
|---|---|
| §1 问题（三步排在 BYPASS 之前、无超时阻塞） | Task 1 的注释与提交信息 |
| §2.1 BYPASS 判定提前 | Task 1 Step 3 |
| §2.2 为什么保留 deny、为什么跳过 ask | Task 1 Step 3 注释 + `denyRuleStillAppliesUnderBypass` / `askRuleIsSkippedUnderBypass` |
| §2.3 核心不变量「永不阻塞」 | Task 1 的 `bypassNeverAsks` |
| §2.4 PLAN 不受影响 | Task 1 的 `otherModesStillEnforceBuiltinDanger` |
| §3.1 即时行 | Task 2 `eachBypassProducesAnImmediateLine` |
| §3.2 回合末汇总 | Task 2 三条汇总用例 |
| §3.3 接缝：一个回调 | Task 2 Step 3 |
| §3.4 不做日志文件 / 不做事后追认 | 不实现即满足 |
| §4 连带改动表 | Task 1 Step 5（反转既有测试）+ Task 3 |
| §4.1 v1.6.0 不改 | Task 3 开头的「不许改的」 |
| §5 测试 | Task 1 Step 6、Task 2 Step 7 的变异表 |
| §6 风险 | Task 3 的 SECURITY 条目 |

**无缺口。**

**2 · 占位扫描**：无 TBD。四处**刻意的「按实际调整」**——`engine(...)` 辅助方法签名、`decide(...)` 签名、`drainPending()`/`OutputLine.text()` API、引擎能否拿到 listener——都明写了「先 grep 确认，不要猜」。这是本项目既定纪律（有过 `javap` 手打路径命中 `.m2` 旧 jar 的教训）。

Task 2 Step 4 里「引擎拿不到 listener 怎么办」给了**两条路并说明了我倾向哪条**，要求实施者自己判断并报告理由——那是个真实的设计岔口，不该由计划书替他拍死。

**3 · 类型一致性**：`onGuardrailBypassed(long turnId, String what)` 在 `AgentListener`（Task 2 Step 3）与 `ConversationState`（Step 5）与测试（Step 1）三处签名一致；`notifyGuardrailBypassed(String)` 在 Task 1 Step 3 建、Task 2 Step 4 接。

**4 · 风险最高的一步**：Task 1 Step 5 的全模块回归。**很可能有别的测试因行为变更而红**，而「反转它」与「我改宽了」的区别需要判断。计划里给了判据，但这一步要实施者真的去分辨，不能为了变绿而放宽实现。

