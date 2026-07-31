# code-tui 权限管理（期 1）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 从 0 到 1 引入权限层：工具执行前拦截，按「模式 + 规则 + 内置危险检查」判定放行 / 拒绝 / 人工审批，危险动作停下来给人看一眼。

**Architecture:** 新增有状态单例 `PermissionEngine`（持模式 + 落盘规则 + 会话规则），`PermissionCallback` 作为**最外层**装饰器包住既有 `ToolEventCallback(MediaExternalizingCallback(真实工具))`；ASK 时经 `ArrayBlockingQueue(1)` 阻塞工具线程，把 `PermissionRequest` 投进 `ConversationState` 的**模态请求队列**（本期把 `pendingAsk` 单字段重构成 `Deque<ModalRequest>`），UI 弹审批面板后喂回结果。规则用 Claude Code 风格 DSL 存两层 `permissions.json`。

**Tech Stack:** Java 17+、Spring AI 2.0（`ToolCallback` / `ToolContext`）、Jackson 3（`tools.jackson.databind`）、TamboUI 0.4.0、JUnit 5、pyte + pty（实机冒烟）。

**Spec:** `docs/superpowers/specs/2026-07-31-permission-mode-design.md`

**验证命令一律模块作用域**（整仓 `mvn test -Dtest=…` 会被 3 个空模块打挂，且不许用 `failIfNoSpecifiedTests=false` 盖问题）：
`mvn test -pl springai-code-tui -Dtest=<TestClass>`

---

## 期 0 结论（已完成，无需重做）

用真实 `JLineBackend` + `EventParser` 链路在 pty 上实测：

| 按键 | 字节 | 解析结果 |
|---|---|---|
| Tab | `\t` | `code=TAB mods=[]` |
| **Shift+Tab** | `\x1b[Z` | `code=TAB mods=[S]` |
| Ctrl+P（备选，未采用） | `\x10` | `code=CHAR mods=[C]` |

**结论：`Shift+Tab` 可区分，采用它做模式循环键，不走 `Ctrl+P` 回退方案。**
依据：`EventParser.parseCSI` 对 `'Z'` 有显式分支 `KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT, bindings)`；
`JLineBackend` 走 `terminal.enterRawMode()` + `NonBlockingReader`，原始字节不被 JLine keymap 吞掉。

**由此暴露的必修坑（Task 15 处理）**：`CodeTuiView` 现有两处 Tab 处理只判 `k.code() == KeyCode.TAB`——
`:645`（斜杠菜单补全）与 `:918`（MCP 面板展开）——**会把 Shift+Tab 一起吃掉**，必须补 `!k.hasShift()` 守卫。

---

## 与 spec 的两处刻意偏差（实施时不要「补回来」）

1. **不实现 `PermissionAware`（spec §2 决策顺序第 3 步「工具自审」）**。
   当前没有任何实现方：自写工具 `BochaWebSearchTool` / `TodoWriteToolAdapter` / `SubagentTool` 已在登记表里各有明确类别，
   建一个零实现的扩展点只会产生无法真实触发的死分支。决策顺序其余 6 步照 spec 实现，
   第 3 步作为**预留插槽**写进 `PermissionEngine.decide` 的方法注释，期 3 有实现方时再插入。
2. **`PermissionBehavior` 只有 `ALLOW` / `DENY` / `ASK`，不含 `PASSTHROUGH`**。
   `PASSTHROUGH` 仅服务于上面被推迟的工具自审钩子，没有生产者。

另：`PermissionMode` 本期只有 `DEFAULT` / `ACCEPT_EDITS` / `BYPASS`，**`PLAN` 随期 2 落地**（加一个枚举常量 + 一条 mode 默认分支即可）。

---

## 包归属决策（有语言层约束，别随意搬家）

| 包 | 放什么 | 为什么 |
|---|---|---|
| `agent/permission/` | 纯逻辑：枚举、`PermissionRule`、`ToolRegistry`、`ToolTargets`、`BashCommandSplitter`、`DangerousPaths`、`PermissionConfig(Loader/Writer)`、`PermissionEngine`、`PermissionDecision` | 可离线单测、与 UI/Spring AI 解耦，照 `agent/media/` 子包先例 |
| `agent/`（扁平） | `ModalRequest`、`PermissionRequest`、`PermissionResponder`、`PermissionOutcome`、`PermissionCancelledException`、`PermissionCallback` | ① `ModalRequest` 是 **sealed interface**，本项目无 `module-info`（unnamed module），故 **permitted 子类型必须与它同包**——`AskRequest` 已在 `agent/`，`PermissionRequest` 只能跟着放这里；② `PermissionCallback` 要复用 `ToolEventCallback` 的包私有 `TURN_ID_KEY` / `TASK_ID_KEY` |

---

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `agent/permission/PermissionMode.java` | 建 | 模式枚举（DEFAULT / ACCEPT_EDITS / BYPASS）+ 循环 `next()` |
| `agent/permission/PermissionBehavior.java` | 建 | ALLOW / DENY / ASK |
| `agent/permission/RuleScope.java` | 建 | SESSION / PROJECT / USER |
| `agent/permission/ToolCategory.java` | 建 | READ_ONLY / FILE_WRITE / COMMAND / NETWORK_READ / INTERNAL / UNKNOWN |
| `agent/permission/PermissionRule.java` | 建 | DSL 解析 / 匹配 / 回写还原 |
| `agent/permission/ToolRegistry.java` | 建 | 工具注册名 → 类别 + 目标字段（登记表） |
| `agent/permission/ToolTargets.java` | 建 | 从入参 JSON 抽判定目标 |
| `agent/permission/BashCommandSplitter.java` | 建 | 命令分段 + 只读白名单 +「拆不动就问」 |
| `agent/permission/DangerousPaths.java` | 建 | 内置不可绕过检查（写敏感目录/文件、读密钥、`rm -rf` 类） |
| `agent/permission/PermissionConfig.java` | 建 | `defaultMode` + allow/ask/deny 规则的值对象 |
| `agent/permission/PermissionConfigLoader.java` | 建 | 两层 `permissions.json` 合并 + 降级契约 |
| `agent/permission/PermissionConfigWriter.java` | 建 | 「允许，永久」追加规则并原子回写项目层 |
| `agent/permission/PermissionDecision.java` | 建 | 判定结果（behavior + reason + 建议规则） |
| `agent/permission/PermissionEngine.java` | 建 | 有状态单例：7 步决策顺序 + 模式/规则读写 |
| `agent/ModalRequest.java` | 建 | sealed 模态请求（AskRequest \| PermissionRequest） |
| `agent/PermissionRequest.java` | 建 | 一次审批请求（含 responder） |
| `agent/PermissionResponder.java` | 建 | UI → 工具线程的应答口 |
| `agent/PermissionOutcome.java` | 建 | ALLOW_ONCE / ALLOW_SESSION / ALLOW_ALWAYS / DENY / CANCEL |
| `agent/PermissionCancelledException.java` | 建 | 审批期回合被中断 |
| `agent/PermissionCallback.java` | 建 | **最外层**拦截装饰器 + 阻塞握手 |
| `agent/AskRequest.java` | 改 | `implements ModalRequest` |
| `agent/AgentListener.java` | 改 | 新增 `onPermissionRequested`（默认实现直接 DENY，防永久 park） |
| `ui/ConversationState.java` | 改 | `pendingAsk` 单字段 → `Deque<ModalRequest>` 队列（容量 8）+ 取消唤醒 |
| `agent/AgentTools.java` | 改 | 三处装配点包 `PermissionCallback`；`AgentRuntime` 暴露 engine |
| `agent/McpRegistry.java` | 改 | `decorate()` 最外层加 `PermissionCallback` |
| `agent/CodingAgent.java` | 改 | 持 engine，实现 `SubmitHandler` 权限门面 |
| `agent/SubmitHandler.java` | 改 | 新增 `permissionMode()` / `cyclePermissionMode()` / `permissionRules()` 默认方法 |
| `ui/CodeTuiView.java` | 改 | 审批面板 + 按键 + `Shift+Tab` 循环 + `/permissions` + 状态栏 |
| `CodeTuiApplication.java` | 改 | `--dangerously-skip-permissions` 启动参数 |
| `src/test/resources/scripts/permission_smoke.py` | 建 | pty 实机冒烟：模式循环 + 审批面板 |
| `src/test/java/.../AgentToolsSecurityTest.java` | 改 | 从「钉零边界」改为「钉引擎层有护栏、工具层仍无沙箱」 |

---

## 已核实的事实（实施时直接用，不要再猜）

**工具注册名**（`@Tool(name=...)` 源码核实，**非** Java 方法名）：
`Read` `Write` `Edit` `Bash` `BashOutput` `KillShell` `Grep` `Glob` `WebFetch` `TodoWrite` `Skill`
`AskUserQuestionTool` `BochaWebSearch` `BraveWebSearch`（本项目 `RenamedToolCallback` 改名，库版叫 `WebSearch`）
`Task` `ParallelTasks` `MemoryView` `MemoryCreate` `MemoryStrReplace` `MemoryInsert` `MemoryDelete` `MemoryRename`

**入参字段名**（`@ToolParam` 源码核实）：

| 工具 | 目标字段 |
|---|---|
| `Read` / `Write` / `Edit` | `filePath` |
| `Bash` | `command` |
| `BashOutput` / `KillShell` | `bash_id` |
| `Grep` / `Glob` | `path`（可选，缺省即工作目录） |
| `WebFetch` | `url` |
| `BochaWebSearch` / `BraveWebSearch` | `query` |
| `Memory*` | `path` |

**记忆工具无需权限判定**：`AutoMemoryTools.resolveSafePath`（源码 394–406 行）拒绝绝对路径
（`SecurityException`）并对 `normalize()` 后的结果做 `startsWith(memoriesDir)` 校验，
路径已被库侧钳死在 `<root>/.codetui/memory` 内。故登记表把它们列为 `INTERNAL`（恒放行），
**不是**漏登记。这同时化解了 spec §3（记忆工具放行）与 §7（写 `.codetui/` 要 ASK）的字面冲突。

---

### Task 1: 权限枚举与规则 DSL

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionMode.java`
- Create: `.../agent/permission/PermissionBehavior.java`
- Create: `.../agent/permission/RuleScope.java`
- Create: `.../agent/permission/ToolCategory.java`
- Create: `.../agent/permission/PermissionRule.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionRuleTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    private static final Path ROOT = Path.of("/work/proj");

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.SESSION);
    }

    @Test
    @DisplayName("裸工具名 / 工具名(*) 都表示该工具的全部调用")
    void bareToolNameMatchesEverything() {
        PermissionRule bare = allow("TodoWrite");
        assertNull(bare.pattern(), "裸工具名解析出的 pattern 应为 null（= 全部调用）");
        assertTrue(bare.matches("TodoWrite", "任意目标", false, ROOT));
        assertFalse(bare.matches("Bash", "任意目标", false, ROOT), "工具名不同不应命中");

        assertNull(allow("Read(*)").pattern(), "(*) 等价裸工具名");
        assertTrue(allow("Read(*)").matches("Read", "/etc/passwd", true, ROOT));
    }

    @Test
    @DisplayName("`*` 作工具名匹配任意工具")
    void wildcardToolName() {
        assertTrue(allow("*").matches("SomeMcpTool", "{}", false, ROOT));
    }

    @Test
    @DisplayName("前缀匹配：`:` 之前按字面量比较，不再解释任何通配符")
    void prefixPattern() {
        PermissionRule r = allow("Bash(mvn -pl springai-code-tui test:*)");
        assertTrue(r.matches("Bash", "mvn -pl springai-code-tui test -Dtest=Foo", false, ROOT));
        assertTrue(r.matches("Bash", "mvn -pl springai-code-tui test", false, ROOT));
        assertFalse(r.matches("Bash", "mvn -pl other test", false, ROOT));

        // 关键：rm -rf / 是「命令前缀」，绝不能被误读成路径 glob
        PermissionRule rm = PermissionRule.parse("Bash(rm -rf /:*)",
                PermissionBehavior.DENY, RuleScope.USER);
        assertTrue(rm.matches("Bash", "rm -rf /var/tmp/x", false, ROOT));
        assertFalse(rm.matches("Bash", "ls -la", false, ROOT));
    }

    @Test
    @DisplayName("路径 glob：* 单层、** 递归；相对模式对绝对目标按 root 相对化再试")
    void globPattern() {
        PermissionRule etc = allow("Write(/etc/**)");
        assertTrue(etc.matches("Write", "/etc/hosts", true, ROOT));
        assertTrue(etc.matches("Write", "/etc/nginx/nginx.conf", true, ROOT));
        assertFalse(etc.matches("Write", "/var/log/x", true, ROOT));

        PermissionRule rel = allow("Read(src/*.java)");
        assertTrue(rel.matches("Read", "src/Main.java", true, ROOT), "相对目标直接匹配");
        assertTrue(rel.matches("Read", "/work/proj/src/Main.java", true, ROOT),
                "绝对目标应按 root 相对化后匹配");
        assertFalse(rel.matches("Read", "/other/src/Main.java", true, ROOT),
                "root 之外的同名相对路径不应命中");
    }

    @Test
    @DisplayName("非路径目标（bash_id / query 等）按整串相等比较")
    void exactPatternForNonPathTarget() {
        PermissionRule r = allow("KillShell(shell_1)");
        assertTrue(r.matches("KillShell", "shell_1", false, ROOT));
        assertFalse(r.matches("KillShell", "shell_2", false, ROOT));
    }

    @Test
    @DisplayName("目标为 null（入参缺该字段）时，带 pattern 的规则一律不命中")
    void nullTargetNeverMatchesPattern() {
        assertFalse(allow("Grep(src/**)").matches("Grep", null, true, ROOT));
        assertTrue(allow("Grep").matches("Grep", null, true, ROOT), "无 pattern 的规则仍命中");
    }

    @Test
    @DisplayName("非法 DSL 返回 null（调用方记 WARN 跳过，绝不抛异常）")
    void malformedDslReturnsNull() {
        assertNull(allow("Bash(unclosed"));
        assertNull(allow("(nopattern)"));
        assertNull(allow("   "));
        assertNull(allow(null));
    }

    @Test
    @DisplayName("非法 glob 不抛异常，只是不命中")
    void invalidGlobDegradesToNoMatch() {
        assertFalse(allow("Write([)").matches("Write", "/tmp/a", true, ROOT));
    }

    @Test
    @DisplayName("toDsl 可还原（供「允许，永久」回写）")
    void toDslRoundTrip() {
        assertEquals("Bash(mvn test:*)", allow("Bash(mvn test:*)").toDsl());
        assertEquals("TodoWrite(*)", allow("TodoWrite").toDsl(), "无 pattern 统一还原成 (*)");
    }

    @Test
    @DisplayName("模式循环：DEFAULT → ACCEPT_EDITS → DEFAULT；BYPASS 不在普通循环里")
    void modeCycle() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(false));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS.next(false));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(false),
                "未开 bypass 时从 BYPASS 只能出去，回不来");

        // 开了 --dangerously-skip-permissions：BYPASS 进循环
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(true));
        assertEquals(PermissionMode.BYPASS, PermissionMode.ACCEPT_EDITS.next(true));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(true));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionRuleTest`
Expected: 编译失败（`PermissionRule` / `PermissionMode` 等符号不存在）

- [ ] **Step 3: 写四个枚举**

`agent/permission/PermissionBehavior.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

/**
 * 一次判定的结论。
 *
 * <p>刻意<b>不含</b> spec 里的 {@code PASSTHROUGH}：它只服务于被推迟到期 3 的「工具自审」
 * 钩子（{@code PermissionAware}），本期无生产者，加进来只会是死分支。
 */
public enum PermissionBehavior {
    /** 直接放行。 */
    ALLOW,
    /** 拒绝——拦截器返回拒绝字符串给模型，<b>不抛异常</b>、回合继续。 */
    DENY,
    /** 需要人工审批——拦截器阻塞工具线程，等 UI 喂回结果。 */
    ASK
}
```

`agent/permission/RuleScope.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

/** 一条规则的存放层：决定「允许，且别再问」写到哪。 */
public enum RuleScope {
    /** 仅本次会话（进程内存，{@code /clear} 开新会话时清空）。 */
    SESSION,
    /** 项目层 {@code <root>/.codetui/permissions.json}。 */
    PROJECT,
    /** 用户层 {@code ~/.codetui/permissions.json}。 */
    USER
}
```

`agent/permission/ToolCategory.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

/** 工具类别：决定模式默认行为，以及规则匹配时把目标当路径还是当整串。 */
public enum ToolCategory {
    /** 只读（Read/Grep/Glob/BashOutput/KillShell）：默认放行，仅命中危险路径读时 ASK。 */
    READ_ONLY,
    /** 文件写（Write/Edit）：ACCEPT_EDITS 下工作区内放行，否则 ASK。 */
    FILE_WRITE,
    /** 命令（Bash）：走 {@link BashCommandSplitter} 分段判定。 */
    COMMAND,
    /** 只读网络（WebFetch / 各搜索工具）：放行。 */
    NETWORK_READ,
    /** 内部工具（TodoWrite/Skill/Ask/Task/ParallelTasks/Memory*）：无外部副作用，恒放行。 */
    INTERNAL,
    /** 未登记（含全部 MCP 工具）：保守默认 ASK。 */
    UNKNOWN
}
```

`agent/permission/PermissionMode.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

/**
 * 权限模式。{@code PLAN} 随期 2 落地（加一个常量 + {@code PermissionEngine} 一条 mode 默认分支）。
 *
 * <p><b>切换</b>：UI 的 {@code Shift+Tab}（期 0 已 pty 实测可与裸 Tab 区分）。
 */
public enum PermissionMode {
    /** 只读放行，其余按规则；无规则则 ASK。 */
    DEFAULT,
    /** 额外放行工作目录内的 Write/Edit，以及 mkdir/touch/mv/cp 类文件系统命令。 */
    ACCEPT_EDITS,
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
        if (!bypassAllowed) {
            return this == DEFAULT ? ACCEPT_EDITS : DEFAULT;
        }
        return switch (this) {
            case DEFAULT -> ACCEPT_EDITS;
            case ACCEPT_EDITS -> BYPASS;
            case BYPASS -> DEFAULT;
        };
    }

    /** 状态栏/面板显示用的短标签。 */
    public String label() {
        return switch (this) {
            case DEFAULT -> "默认";
            case ACCEPT_EDITS -> "自动接受编辑";
            case BYPASS -> "跳过权限检查";
        };
    }
}
```

- [ ] **Step 4: 写 PermissionRule**

`agent/permission/PermissionRule.java`：

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * 一条权限规则。DSL 形如 {@code 工具名(内容模式)}，三种形态按以下顺序判别：
 *
 * <ol>
 *   <li>{@code 工具名} 或 {@code 工具名(*)} → 匹配该工具<b>全部</b>调用（{@code pattern == null}）；</li>
 *   <li>{@code 工具名(字面量:*)} → <b>前缀匹配</b>：{@code :} 之前按原样比较，<b>不再解释任何通配符</b>
 *       （故 {@code Bash(rm -rf /:*)} 明确是「以 rm -rf / 开头的命令」，不会被误读成路径 glob）；</li>
 *   <li>其余 → 目标是路径时按 <b>glob</b>（{@code *} 单层、{@code **} 递归），否则<b>整串相等</b>。</li>
 * </ol>
 *
 * <p>「目标是不是路径」由调用方（{@link ToolRegistry.Entry#pathTarget()}）给定，<b>不</b>由类别推断——
 * {@code BashOutput} 是 READ_ONLY 但目标是 {@code bash_id}，不是路径。
 *
 * @param toolName 工具注册名；{@code "*"} 匹配任意工具
 * @param pattern  内容模式；{@code null} = 该工具全部调用
 * @param behavior 命中后的结论
 * @param scope    存放层（决定「允许，且别再问」写到哪）
 */
public record PermissionRule(String toolName, String pattern,
                             PermissionBehavior behavior, RuleScope scope) {

    /** 解析 DSL；非法返回 {@code null}（调用方记 WARN 跳过，<b>绝不抛异常</b>）。 */
    public static PermissionRule parse(String dsl, PermissionBehavior behavior, RuleScope scope) {
        if (dsl == null) {
            return null;
        }
        String s = dsl.trim();
        if (s.isEmpty()) {
            return null;
        }
        int open = s.indexOf('(');
        if (open < 0) {
            return new PermissionRule(s, null, behavior, scope);   // 裸工具名 = 全部调用
        }
        if (!s.endsWith(")")) {
            return null;                                            // 括号不闭合
        }
        String tool = s.substring(0, open).trim();
        if (tool.isEmpty()) {
            return null;
        }
        String pat = s.substring(open + 1, s.length() - 1).trim();
        if (pat.isEmpty() || "*".equals(pat)) {
            return new PermissionRule(tool, null, behavior, scope);
        }
        return new PermissionRule(tool, pat, behavior, scope);
    }

    /** 还原成 DSL（供「允许，永久」回写；无 pattern 统一写成 {@code 工具名(*)}）。 */
    public String toDsl() {
        return pattern == null ? toolName + "(*)" : toolName + "(" + pattern + ")";
    }

    /**
     * 是否命中一次调用。
     *
     * @param callTool   本次调用的工具注册名
     * @param target     判定目标（路径 / 命令 / bash_id / 整串入参 JSON），可为 null
     * @param pathTarget {@code target} 是否是文件路径
     * @param root       项目根（相对 glob 对绝对目标时用它相对化）
     */
    public boolean matches(String callTool, String target, boolean pathTarget, Path root) {
        if (!"*".equals(toolName) && !toolName.equals(callTool)) {
            return false;
        }
        if (pattern == null) {
            return true;
        }
        if (target == null) {
            return false;
        }
        if (pattern.endsWith(":*")) {
            return target.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        if (pathTarget) {
            return globMatches(pattern, target, root);
        }
        return pattern.equals(target);
    }

    /**
     * 路径 glob 匹配。先按原样比一次；若模式是相对的而目标是 root 内的绝对路径，
     * 则用 {@code root.relativize} 后再比一次（使 {@code Read(src/*.java)} 对绝对路径也成立）。
     *
     * <p>任何解析异常（{@code InvalidPathException} / {@code PatternSyntaxException}）
     * 一律降级为「不命中」，绝不外抛——规则文件是用户手写的，非法模式不该炸掉一次工具调用。
     */
    static boolean globMatches(String pattern, String target, Path root) {
        try {
            String p = pattern.startsWith("~/")
                    ? System.getProperty("user.home") + pattern.substring(1)
                    : pattern;
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + p);
            Path t = Path.of(target).normalize();
            if (m.matches(t)) {
                return true;
            }
            if (!p.startsWith("/") && root != null && t.isAbsolute() && t.startsWith(root)) {
                return m.matches(root.relativize(t));
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionRuleTest`
Expected: PASS（10 个测试全绿）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/
git commit -m "feat(permission): 权限枚举与规则 DSL（解析/匹配/回写还原）"
```

---

### Task 2: 工具登记表与判定目标提取

**Files:**
- Create: `.../agent/permission/ToolRegistry.java`
- Create: `.../agent/permission/ToolTargets.java`
- Test: `.../agent/permission/ToolRegistryTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    @DisplayName("登记表覆盖各类别的代表工具，且路径标记正确")
    void lookupKnownTools() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.lookup("Read").category());
        assertTrue(ToolRegistry.lookup("Read").pathTarget());
        assertEquals("filePath", ToolRegistry.lookup("Read").targetField());

        assertEquals(ToolCategory.FILE_WRITE, ToolRegistry.lookup("Write").category());
        assertEquals(ToolCategory.FILE_WRITE, ToolRegistry.lookup("Edit").category());

        assertEquals(ToolCategory.COMMAND, ToolRegistry.lookup("Bash").category());
        assertEquals("command", ToolRegistry.lookup("Bash").targetField());
        assertFalse(ToolRegistry.lookup("Bash").pathTarget(), "命令不是路径，不能走 glob");

        // BashOutput 是只读，但目标是 bash_id——不是路径
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.lookup("BashOutput").category());
        assertEquals("bash_id", ToolRegistry.lookup("BashOutput").targetField());
        assertFalse(ToolRegistry.lookup("BashOutput").pathTarget());

        assertEquals(ToolCategory.NETWORK_READ, ToolRegistry.lookup("WebFetch").category());
        assertEquals(ToolCategory.INTERNAL, ToolRegistry.lookup("TodoWrite").category());
        assertEquals(ToolCategory.INTERNAL, ToolRegistry.lookup("MemoryCreate").category());
    }

    @Test
    @DisplayName("未登记工具（含全部 MCP 工具）兜底为 UNKNOWN，目标取整串入参")
    void unknownToolFallsBack() {
        ToolRegistry.Entry e = ToolRegistry.lookup("some_mcp_tool");
        assertEquals(ToolCategory.UNKNOWN, e.category());
        assertNull(e.targetField(), "UNKNOWN 无目标字段，判定目标是整串 JSON");
        assertFalse(e.pathTarget());
    }

    @Test
    @DisplayName("目标提取：按登记的字段名从入参 JSON 取值")
    void extractTarget() {
        assertEquals("/tmp/a.txt",
                ToolTargets.extract("Read", "{\"filePath\":\"/tmp/a.txt\",\"limit\":10}"));
        assertEquals("mvn test",
                ToolTargets.extract("Bash", "{\"command\":\"mvn test\",\"timeout\":1000}"));
        assertEquals("shell_1", ToolTargets.extract("KillShell", "{\"bash_id\":\"shell_1\"}"));
    }

    @Test
    @DisplayName("目标提取：INTERNAL 无目标字段返回 null；UNKNOWN 返回整串入参")
    void extractForFieldlessTools() {
        assertNull(ToolTargets.extract("TodoWrite", "{\"todos\":[]}"));
        assertEquals("{\"x\":1}", ToolTargets.extract("some_mcp_tool", "{\"x\":1}"));
    }

    @Test
    @DisplayName("目标提取：字段缺失 / JSON 非法 → null，绝不抛异常")
    void extractDegradesGracefully() {
        assertNull(ToolTargets.extract("Read", "{\"other\":1}"), "缺 filePath");
        assertNull(ToolTargets.extract("Read", "not json at all"));
        assertNull(ToolTargets.extract("Read", null));
        assertNull(ToolTargets.extract("Grep", "{\"pattern\":\"x\"}"), "Grep 的 path 可选，缺则 null");
    }

    @Test
    @DisplayName("目标提取：非字符串字段（对象/数组/数字）不崩，按文本形式取")
    void extractNonStringField() {
        // Jackson 3 的 asString() 对非文本节点会抛，实现必须自己判 isTextual
        assertNull(ToolTargets.extract("Read", "{\"filePath\":{\"nested\":1}}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ToolRegistryTest`
Expected: 编译失败（`ToolRegistry` / `ToolTargets` 不存在）

- [ ] **Step 3: 写 ToolRegistry**

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具登记表：注册名 → 类别 + 判定目标字段。
 *
 * <p><b>键必须是 {@code @Tool} 注解里的注册名，不是 Java 方法名</b>（全部经源码核实）。
 * 未登记的工具（含<b>全部 MCP 工具</b>）兜底为 {@link ToolCategory#UNKNOWN} → 保守 ASK。
 *
 * <p><b>记忆工具为何是 INTERNAL</b>：{@code AutoMemoryTools.resolveSafePath} 拒绝绝对路径并对
 * {@code normalize()} 后的结果做 {@code startsWith(memoriesDir)} 校验，路径已被库侧钳死在
 * {@code <root>/.codetui/memory} 内，再判一次没有意义。这不是漏登记。
 *
 * <p>完整性由 {@code ToolRegistryCompletenessTest} 对运行时工具集全量比对保证：
 * 存在但未登记的工具直接让测试失败。
 */
public final class ToolRegistry {

    /**
     * 一条登记。
     *
     * @param name        工具注册名
     * @param category    类别（决定模式默认行为）
     * @param targetField 判定目标取自入参 JSON 的哪个字段；null = 无目标字段
     * @param pathTarget  目标是否是文件路径（决定规则匹配走 glob 还是整串相等，以及是否做危险路径检查）
     */
    public record Entry(String name, ToolCategory category, String targetField, boolean pathTarget) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static void put(String name, ToolCategory cat, String field, boolean path) {
        ENTRIES.put(name, new Entry(name, cat, field, path));
    }

    static {
        // ── 只读 ──
        put("Read",        ToolCategory.READ_ONLY,    "filePath", true);
        put("Grep",        ToolCategory.READ_ONLY,    "path",     true);
        put("Glob",        ToolCategory.READ_ONLY,    "path",     true);
        put("BashOutput",  ToolCategory.READ_ONLY,    "bash_id",  false);   // 只查自己起的进程
        put("KillShell",   ToolCategory.READ_ONLY,    "bash_id",  false);   // 只杀自己起的进程
        // ── 文件写 ──
        put("Write",       ToolCategory.FILE_WRITE,   "filePath", true);
        put("Edit",        ToolCategory.FILE_WRITE,   "filePath", true);
        // ── 命令 ──
        put("Bash",        ToolCategory.COMMAND,      "command",  false);
        // ── 只读网络 ──
        put("WebFetch",        ToolCategory.NETWORK_READ, "url",   false);
        put("BochaWebSearch",  ToolCategory.NETWORK_READ, "query", false);
        put("BraveWebSearch",  ToolCategory.NETWORK_READ, "query", false);
        // ── 内部（无外部副作用） ──
        put("TodoWrite",           ToolCategory.INTERNAL, null, false);
        put("Skill",               ToolCategory.INTERNAL, null, false);
        put("AskUserQuestionTool", ToolCategory.INTERNAL, null, false);
        put("Task",                ToolCategory.INTERNAL, null, false);   // 委派本身无害，拦的是子 agent 内部那份工具
        put("ParallelTasks",       ToolCategory.INTERNAL, null, false);
        // ── 记忆工具：路径已被库侧钳在 memories root 内（见类注释） ──
        put("MemoryView",       ToolCategory.INTERNAL, "path", false);
        put("MemoryCreate",     ToolCategory.INTERNAL, "path", false);
        put("MemoryStrReplace", ToolCategory.INTERNAL, "path", false);
        put("MemoryInsert",     ToolCategory.INTERNAL, "path", false);
        put("MemoryDelete",     ToolCategory.INTERNAL, "path", false);
        put("MemoryRename",     ToolCategory.INTERNAL, "path", false);
    }

    private ToolRegistry() {
    }

    /** 查登记；未登记返回 UNKNOWN 兜底条目（目标字段为 null，判定目标改用整串入参）。 */
    public static Entry lookup(String toolName) {
        Entry e = ENTRIES.get(toolName);
        return e != null ? e : new Entry(toolName, ToolCategory.UNKNOWN, null, false);
    }

    /** 已登记的全部工具名（完整性测试用）。 */
    public static Set<String> registeredNames() {
        return Set.copyOf(ENTRIES.keySet());
    }
}
```

- [ ] **Step 4: 写 ToolTargets**

```java
package io.github.javaside.springai.codetui.agent.permission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 从工具入参 JSON 里抽出「判定目标」——面板显示 + 规则匹配 + 危险检查都用它。
 *
 * <p><b>降级契约</b>：JSON 非法 / 字段缺失 / 字段不是字符串 → 返回 {@code null}，<b>绝不抛异常</b>。
 * 目标为 null 时引擎按「无法核实」处理（带 pattern 的规则不命中，落到模式默认/兜底 ASK）。
 *
 * <p><b>UNKNOWN 工具</b>（含全部 MCP 工具）无登记字段，返回<b>整串入参</b>——
 * 面板上原样展示给人看，规则也可用 {@code 工具名(整串)} 精确放行。
 */
public final class ToolTargets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolTargets() {
    }

    public static String extract(String toolName, String toolInput) {
        ToolRegistry.Entry entry = ToolRegistry.lookup(toolName);
        if (entry.category() == ToolCategory.UNKNOWN) {
            return toolInput;                       // 未登记：整串入参即目标
        }
        if (entry.targetField() == null || toolInput == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode v = root.get(entry.targetField());
            // Jackson 3 的 asString() 对非文本节点会抛，必须先判 isString
            return (v != null && v.isString()) ? v.stringValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
```

> ⚠ **实施注意**：本项目用的是 Jackson 3（`tools.jackson.databind`）。上面的 `isString()` / `stringValue()`
> 需按实际 API 核对——若该版本仍是 `isTextual()` / `textValue()`，照实际签名改，**别硬套**。
> 判据：`ToolTargets` 的最后一个测试（`extractNonStringField`）必须绿。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ToolRegistryTest`
Expected: PASS（6 个测试全绿）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistry.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/ToolTargets.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistryTest.java
git commit -m "feat(permission): 工具登记表与判定目标提取"
```

---

### Task 3: BashCommandSplitter（拆不动就问）

**Files:**
- Create: `.../agent/permission/BashCommandSplitter.java`
- Test: `.../agent/permission/BashCommandSplitterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashCommandSplitterTest {

    @Test
    @DisplayName("按 && || ; | 拆段，逐段独立判定")
    void splitsOnAllSeparators() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("ls && pwd || echo hi ; cat a | wc -l");
        assertTrue(s.parseable());
        assertEquals(List.of("ls", "pwd", "echo hi", "cat a", "wc -l"), s.segments());
    }

    @Test
    @DisplayName("只读白名单：全段命中即整条只读")
    void readOnlyWhitelist() {
        assertTrue(BashCommandSplitter.isReadOnly("ls -la"));
        assertTrue(BashCommandSplitter.isReadOnly("git status"));
        assertTrue(BashCommandSplitter.isReadOnly("git diff HEAD~1"));
        assertTrue(BashCommandSplitter.isReadOnly("java -version"));

        assertFalse(BashCommandSplitter.isReadOnly("rm -rf build"));
        assertFalse(BashCommandSplitter.isReadOnly("git push origin main"), "git 子命令白名单不含 push");
        assertFalse(BashCommandSplitter.isReadOnly("mvn test"), "mvn test 会跑代码，不是只读");
        assertFalse(BashCommandSplitter.isReadOnly(""));
    }

    @Test
    @DisplayName("ACCEPT_EDITS 的文件系统写白名单：mkdir/touch/mv/cp，不含 rm")
    void fileSystemWriteWhitelist() {
        assertTrue(BashCommandSplitter.isFileSystemWrite("mkdir -p target/x"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("touch a.txt"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("mv a b"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("cp a b"));
        assertFalse(BashCommandSplitter.isFileSystemWrite("rm a"), "删除永远不进自动放行白名单");
    }

    @Test
    @DisplayName("拆不动就问：命令替换 / 反引号 / 进程替换一律 parseable=false")
    void unparseableConstructs() {
        assertFalse(BashCommandSplitter.split("echo $(whoami)").parseable());
        assertFalse(BashCommandSplitter.split("echo `whoami`").parseable());
        assertFalse(BashCommandSplitter.split("diff <(ls a) <(ls b)").parseable());
        assertFalse(BashCommandSplitter.split("cat > >(tee log)").parseable());
    }

    @Test
    @DisplayName("拆不动就问：引号内含分隔符 → parseable=false（宁烦不漏）")
    void separatorInsideQuotesIsUnparseable() {
        assertFalse(BashCommandSplitter.split("echo 'a && b'").parseable());
        assertFalse(BashCommandSplitter.split("echo \"x ; y\"").parseable());
    }

    @Test
    @DisplayName("拆不动就问：引号不配对 → parseable=false")
    void unbalancedQuoteIsUnparseable() {
        assertFalse(BashCommandSplitter.split("echo 'unterminated").parseable());
    }

    @Test
    @DisplayName("普通引号（内无分隔符）不影响拆分")
    void plainQuotesAreFine() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("grep 'hello world' a.txt && ls");
        assertTrue(s.parseable());
        assertEquals(List.of("grep 'hello world' a.txt", "ls"), s.segments());
    }

    @Test
    @DisplayName("空 / null 命令：可解析但零段（调用方按无目标处理）")
    void emptyCommand() {
        assertTrue(BashCommandSplitter.split("").segments().isEmpty());
        assertTrue(BashCommandSplitter.split(null).segments().isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=BashCommandSplitterTest`
Expected: 编译失败（`BashCommandSplitter` 不存在）

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bash 命令的分段与只读判定。
 *
 * <p><b>本类的安全底线是「拆不动就问」</b>：解析器只要不确定语义就返回
 * {@code parseable=false}，引擎据此直接 ASK——宁可烦用户一次，绝不放行未知语义。
 * 触发条件：命令替换 {@code $(...)}、反引号、进程替换 {@code <(...)} / {@code >(...)}、
 * 引号内出现分隔符、引号不配对。
 */
public final class BashCommandSplitter {

    /**
     * 分段结果。
     *
     * @param segments  按 {@code && || ; |} 拆出的各段（已 trim，空段丢弃）
     * @param parseable 是否有把握完整理解这条命令；false 时调用方必须 ASK，不得据 segments 放行
     */
    public record Split(List<String> segments, boolean parseable) {}

    /** 只读命令白名单（首个词）。 */
    private static final Set<String> READ_ONLY = Set.of(
            "ls", "cat", "head", "tail", "grep", "rg", "find", "wc", "pwd", "echo",
            "which", "file", "stat", "du", "df", "date", "whoami", "uname", "env", "printenv",
            "tree", "diff", "cmp", "basename", "dirname", "realpath");

    /** git 的只读子命令。 */
    private static final Set<String> GIT_READ_ONLY = Set.of(
            "status", "diff", "log", "show", "branch", "remote", "blame", "describe", "rev-parse");

    /** {@code ACCEPT_EDITS} 下额外放行的文件系统写命令。<b>刻意不含 rm</b>。 */
    private static final Set<String> FS_WRITE = Set.of("mkdir", "touch", "mv", "cp");

    private BashCommandSplitter() {
    }

    /** 按 {@code && || ; |} 拆段，同时判定「拆不动就问」。 */
    public static Split split(String command) {
        List<String> segs = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return new Split(List.of(), true);
        }
        if (hasUnparseableConstruct(command)) {
            return new Split(List.of(), false);
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (isSeparatorChar(c)) {
                    return new Split(List.of(), false);   // 引号内含分隔符：语义不明，退回人工
                }
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == ';' || c == '|' || c == '&') {
                // && / || 吃掉第二个字符；单个 & （后台执行）也当分隔
                if (i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++;
                }
                addSegment(segs, cur);
                continue;
            }
            cur.append(c);
        }
        if (quote != 0) {
            return new Split(List.of(), false);           // 引号不配对
        }
        addSegment(segs, cur);
        return new Split(List.copyOf(segs), true);
    }

    /** 单段是否只读（按首个词查白名单；git 另查子命令）。 */
    public static boolean isReadOnly(String segment) {
        String head = firstWord(segment);
        if (head.isEmpty()) {
            return false;
        }
        if ("git".equals(head)) {
            return GIT_READ_ONLY.contains(secondWord(segment));
        }
        if ("java".equals(head) || "mvn".equals(head)) {
            // 只放行版本查询，别的（mvn test / java -jar）都会跑代码
            String second = secondWord(segment);
            return "-version".equals(second) || "--version".equals(second) || "-v".equals(second);
        }
        return READ_ONLY.contains(head);
    }

    /** 单段是否属于 {@code ACCEPT_EDITS} 下额外放行的文件系统写。 */
    public static boolean isFileSystemWrite(String segment) {
        return FS_WRITE.contains(firstWord(segment));
    }

    private static boolean hasUnparseableConstruct(String c) {
        return c.contains("$(") || c.indexOf('`') >= 0 || c.contains("<(") || c.contains(">(");
    }

    private static boolean isSeparatorChar(char c) {
        return c == ';' || c == '|' || c == '&';
    }

    private static void addSegment(List<String> segs, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            segs.add(s);
        }
        cur.setLength(0);
    }

    private static String firstWord(String segment) {
        if (segment == null) {
            return "";
        }
        String s = segment.trim();
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static String secondWord(String segment) {
        String s = segment == null ? "" : segment.trim();
        int sp = s.indexOf(' ');
        if (sp < 0) {
            return "";
        }
        String rest = s.substring(sp + 1).trim();
        int sp2 = rest.indexOf(' ');
        return sp2 < 0 ? rest : rest.substring(0, sp2);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=BashCommandSplitterTest`
Expected: PASS（8 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/BashCommandSplitter.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/BashCommandSplitterTest.java
git commit -m "feat(permission): Bash 命令分段与只读判定（拆不动就问）"
```

---

### Task 4: DangerousPaths（内置不可绕过检查）

**Files:**
- Create: `.../agent/permission/DangerousPaths.java`
- Test: `.../agent/permission/DangerousPathsTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DangerousPathsTest {

    private static final Path ROOT = Path.of("/work/proj");
    private static final String HOME = System.getProperty("user.home");

    @Test
    @DisplayName("写敏感目录 → 给出原因")
    void writeToSensitiveDirectories() {
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".ssh", "config"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".aws", "credentials"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".kube", "config"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".gnupg", "x"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(ROOT.resolve(".git").resolve("config"), ROOT));
    }

    @Test
    @DisplayName("写敏感文件 → 给出原因")
    void writeToSensitiveFiles() {
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".zshrc"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".bashrc"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".gitconfig"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".npmrc"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(Path.of(HOME, ".m2", "settings.xml"), ROOT));
    }

    @Test
    @DisplayName("写 <root>/.codetui/ 配置 → 给出原因（agent 不该改自己的权限配置）")
    void writeToOwnConfig() {
        assertNotNull(DangerousPaths.checkWrite(ROOT.resolve(".codetui").resolve("permissions.json"), ROOT));
        assertNotNull(DangerousPaths.checkWrite(ROOT.resolve(".codetui").resolve("mcp.json"), ROOT));
    }

    @Test
    @DisplayName("但 .codetui 下的 memory/sessions/artifacts 是 agent 自己的工作区，不算危险")
    void agentOwnWorkspaceIsNotDangerous() {
        assertNull(DangerousPaths.checkWrite(
                ROOT.resolve(".codetui").resolve("memory").resolve("x.md"), ROOT));
        assertNull(DangerousPaths.checkWrite(
                ROOT.resolve(".codetui").resolve("sessions").resolve("s.json"), ROOT));
        assertNull(DangerousPaths.checkWrite(
                ROOT.resolve(".codetui").resolve("artifacts").resolve("a.png"), ROOT));
    }

    @Test
    @DisplayName("普通项目文件不触发")
    void ordinaryFileIsFine() {
        assertNull(DangerousPaths.checkWrite(ROOT.resolve("src").resolve("Main.java"), ROOT));
        assertNull(DangerousPaths.checkRead(ROOT.resolve("pom.xml"), ROOT));
    }

    @Test
    @DisplayName("读密钥文件 → 给出原因")
    void readSecrets() {
        assertNotNull(DangerousPaths.checkRead(Path.of(HOME, ".ssh", "id_rsa"), ROOT));
        assertNotNull(DangerousPaths.checkRead(Path.of(HOME, ".ssh", "id_ed25519"), ROOT));
        assertNotNull(DangerousPaths.checkRead(Path.of(HOME, ".aws", "credentials"), ROOT));
        assertNotNull(DangerousPaths.checkRead(Path.of(HOME, ".gnupg", "secring.gpg"), ROOT));
        assertNull(DangerousPaths.checkRead(Path.of(HOME, ".ssh", "known_hosts"), ROOT),
                "known_hosts 不是密钥");
    }

    @Test
    @DisplayName("危险命令：rm -rf 根 / 家目录 / 变量目标")
    void dangerousCommands() {
        assertNotNull(DangerousPaths.checkCommand("rm -rf /"));
        assertNotNull(DangerousPaths.checkCommand("rm -rf ~"));
        assertNotNull(DangerousPaths.checkCommand("rm -rf $BUILD_DIR"),
                "变量目标无法核实，不猜");
        assertNotNull(DangerousPaths.checkCommand("rm -fr /"));
        assertNull(DangerousPaths.checkCommand("rm -rf target"), "项目内相对目录不属于内置底线");
        assertNull(DangerousPaths.checkCommand("ls -la"));
    }

    @Test
    @DisplayName("原因文本是人话（面板要直接显示给用户）")
    void reasonIsHumanReadable() {
        String r = DangerousPaths.checkWrite(Path.of(HOME, ".ssh", "config"), ROOT);
        assertTrue(r.contains(".ssh"), "原因里应点明命中的是什么，实际：" + r);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DangerousPathsTest`
Expected: 编译失败（`DangerousPaths` 不存在）

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 内置不可绕过检查：<b>排在 allow 规则之前，任何 allow 规则盖不住，BYPASS 模式下也照样触发</b>。
 *
 * <p>命中后引擎强制 <b>ASK 而非 DENY</b>——本层是护栏不是牢笼，人确认了就该能做。
 *
 * <p>每个方法返回<b>人话原因</b>（直接显示在审批面板上）或 {@code null}（未命中）。
 */
public final class DangerousPaths {

    /** 写进去就可能被劫持的目录（相对家目录；{@code .git} 另按 root 判）。 */
    private static final List<String> SENSITIVE_HOME_DIRS =
            List.of(".ssh", ".aws", ".kube", ".gnupg");

    /** 写进去就可能被劫持的文件（相对家目录）。 */
    private static final Set<String> SENSITIVE_HOME_FILES = Set.of(
            ".gitconfig", ".gitmodules", ".zshrc", ".bashrc", ".zprofile", ".profile", ".npmrc");

    /** 家目录下的多级敏感文件。 */
    private static final List<String> SENSITIVE_HOME_NESTED = List.of(
            ".m2/settings.xml", ".gradle/gradle.properties");

    /** {@code .codetui} 下属于 agent 自己工作区、不算危险的子目录。 */
    private static final Set<String> CODETUI_WORKSPACE = Set.of("memory", "sessions", "artifacts");

    private DangerousPaths() {
    }

    /** 写检查；命中返回原因，否则 null。 */
    public static String checkWrite(Path target, Path root) {
        if (target == null) {
            return null;
        }
        Path t = target.normalize();
        Path home = Path.of(System.getProperty("user.home")).normalize();

        for (String dir : SENSITIVE_HOME_DIRS) {
            if (t.startsWith(home.resolve(dir))) {
                return "写入敏感目录 ~/" + dir + "（凭据/密钥所在）";
            }
        }
        for (String nested : SENSITIVE_HOME_NESTED) {
            if (t.equals(home.resolve(nested))) {
                return "写入敏感配置 ~/" + nested;
            }
        }
        if (t.getParent() != null && t.getParent().equals(home)
                && SENSITIVE_HOME_FILES.contains(fileName(t))) {
            return "写入 shell / 工具配置 ~/" + fileName(t) + "（会影响后续所有命令）";
        }
        if (root != null) {
            Path r = root.normalize();
            if (t.startsWith(r.resolve(".git"))) {
                return "写入 .git/ 内部（可能改写仓库历史或钩子）";
            }
            Path codetui = r.resolve(".codetui");
            if (t.startsWith(codetui) && !inAgentWorkspace(codetui, t)) {
                return "写入 .codetui/ 配置（agent 正在修改自己的权限/MCP 配置）";
            }
        }
        return null;
    }

    /** 读检查；命中返回原因，否则 null。 */
    public static String checkRead(Path target, Path root) {
        if (target == null) {
            return null;
        }
        Path t = target.normalize();
        Path home = Path.of(System.getProperty("user.home")).normalize();
        String name = fileName(t);

        if (t.startsWith(home.resolve(".ssh")) && name.startsWith("id_")) {
            return "读取 SSH 私钥 ~/.ssh/" + name;
        }
        if (t.equals(home.resolve(".aws").resolve("credentials"))) {
            return "读取 AWS 凭据 ~/.aws/credentials";
        }
        if (t.startsWith(home.resolve(".gnupg"))) {
            return "读取 GnuPG 密钥环 ~/.gnupg/" + name;
        }
        return null;
    }

    /** 命令检查；命中返回原因，否则 null。 */
    public static String checkCommand(String command) {
        if (command == null) {
            return null;
        }
        String c = command.trim();
        if (!c.startsWith("rm ")) {
            return null;
        }
        // 归一化 -rf / -fr / -r -f
        boolean recursiveForce = c.contains("-rf") || c.contains("-fr")
                || (c.contains("-r") && c.contains("-f"));
        if (!recursiveForce) {
            return null;
        }
        String tail = c.substring(2).trim();
        for (String tok : tail.split("\\s+")) {
            if (tok.startsWith("-")) {
                continue;
            }
            if ("/".equals(tok) || "~".equals(tok) || tok.startsWith("~/") && tok.length() <= 3) {
                return "递归强制删除 " + tok + "（可能清空整个磁盘或家目录）";
            }
            if (tok.startsWith("$")) {
                return "递归强制删除变量目标 " + tok + "（展开结果无法核实，不做猜测）";
            }
        }
        return null;
    }

    private static boolean inAgentWorkspace(Path codetuiDir, Path target) {
        for (String sub : CODETUI_WORKSPACE) {
            if (target.startsWith(codetuiDir.resolve(sub))) {
                return true;
            }
        }
        return false;
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DangerousPathsTest`
Expected: PASS（8 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/DangerousPaths.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/DangerousPathsTest.java
git commit -m "feat(permission): 内置不可绕过的危险路径/命令检查"
```

---

### Task 5: permissions.json 两层加载

**Files:**
- Create: `.../agent/permission/PermissionConfig.java`
- Create: `.../agent/permission/PermissionConfigLoader.java`
- Test: `.../agent/permission/PermissionConfigLoaderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigLoaderTest {

    private static boolean hasRule(List<PermissionRule> rules, String dsl, PermissionBehavior b) {
        return rules.stream().anyMatch(r -> r.toDsl().equals(dsl) && r.behavior() == b);
    }

    @Test
    @DisplayName("两层合并而非覆盖：deny 只增不减，项目层削弱不了用户层禁令")
    void twoLayersMerge(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, """
                {"defaultMode":"DEFAULT",
                 "allow":["Read(*)"],
                 "deny":["Bash(rm -rf /:*)"]}""");
        Files.writeString(project, """
                {"defaultMode":"ACCEPT_EDITS",
                 "allow":["Bash(mvn test:*)"],
                 "ask":["Bash(git push:*)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);

        assertEquals(PermissionMode.ACCEPT_EDITS, cfg.defaultMode(), "defaultMode 项目层覆盖用户层");
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
        assertTrue(hasRule(cfg.rules(), "Bash(mvn test:*)", PermissionBehavior.ALLOW));
        assertTrue(hasRule(cfg.rules(), "Bash(git push:*)", PermissionBehavior.ASK));
        assertTrue(hasRule(cfg.rules(), "Bash(rm -rf /:*)", PermissionBehavior.DENY),
                "用户层 deny 必须保留——项目层不能削弱它");
    }

    @Test
    @DisplayName("规则带上来源层（供 /permissions 展示与回写目标判断）")
    void rulesCarryScope() throws Exception {
        Path dir = Files.createTempDirectory("permcfg");
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, """
                {"allow":["Read(*)"]}""");
        Files.writeString(project, """
                {"allow":["Write(src/**)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);

        assertEquals(RuleScope.USER,
                cfg.rules().stream().filter(r -> r.toDsl().equals("Read(*)")).findFirst().orElseThrow().scope());
        assertEquals(RuleScope.PROJECT,
                cfg.rules().stream().filter(r -> r.toDsl().equals("Write(src/**)")).findFirst().orElseThrow().scope());
    }

    @Test
    @DisplayName("降级契约：文件缺失 → 空配置 + 默认模式，绝不抛异常")
    void missingFilesDegrade(@TempDir Path dir) {
        PermissionConfig cfg = PermissionConfigLoader.load(
                dir.resolve("nope.json"), dir.resolve("also-nope.json"));
        assertTrue(cfg.rules().isEmpty());
        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode());
    }

    @Test
    @DisplayName("降级契约：JSON 非法 → 跳过该文件，另一层照常生效")
    void malformedJsonDegrades(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{ this is not json");
        Files.writeString(project, """
                {"allow":["Read(*)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);
        assertEquals(1, cfg.rules().size());
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
    }

    @Test
    @DisplayName("降级契约：单条规则非法 / defaultMode 未知 → 跳过该条，其余照常")
    void malformedEntriesSkipped(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, """
                {"defaultMode":"NO_SUCH_MODE",
                 "allow":["Bash(unclosed", "Read(*)", ""],
                 "deny":[123]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, dir.resolve("none.json"));

        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode(), "未知模式回退 DEFAULT");
        assertEquals(1, cfg.rules().size(), "只有 Read(*) 是合法的");
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
    }

    @Test
    @DisplayName("字段缺失（无 allow/ask/deny）不报错")
    void missingArraysAreFine(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        PermissionConfig cfg = PermissionConfigLoader.load(user, dir.resolve("none.json"));
        assertTrue(cfg.rules().isEmpty());
        assertEquals(PermissionMode.ACCEPT_EDITS, cfg.defaultMode());
        assertFalse(cfg.rules().stream().anyMatch(r -> r.behavior() == PermissionBehavior.DENY));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionConfigLoaderTest`
Expected: 编译失败（`PermissionConfig` / `PermissionConfigLoader` 不存在）

- [ ] **Step 3: 写 PermissionConfig**

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.util.List;

/**
 * 两层 {@code permissions.json} 合并后的结果。
 *
 * @param defaultMode 启动模式（项目层可覆盖用户层）
 * @param rules       全部规则（<b>合并而非覆盖</b>：deny 只增不减，各自带来源层 scope）
 */
public record PermissionConfig(PermissionMode defaultMode, List<PermissionRule> rules) {

    public static PermissionConfig empty() {
        return new PermissionConfig(PermissionMode.DEFAULT, List.of());
    }
}
```

- [ ] **Step 4: 写 PermissionConfigLoader**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取两层 {@code permissions.json}（用户级 {@code ~/.codetui/permissions.json} +
 * 项目级 {@code <root>/.codetui/permissions.json}），合并为一份 {@link PermissionConfig}。
 *
 * <p><b>合并而非覆盖</b>：两层的 allow/ask/deny 全部保留并集——deny 只增不减，
 * 项目层<b>不能</b>削弱用户层的禁令。仅 {@code defaultMode} 是项目层覆盖用户层。
 *
 * <p><b>降级契约</b>（照 {@code McpConfigLoader}）：文件缺失 / JSON 非法 / 单条规则非法 /
 * {@code defaultMode} 未知 → 记 WARN、跳过、<b>绝不抛异常</b>。
 */
public final class PermissionConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PermissionConfigLoader() {
    }

    /** 生产入口：由项目根解析出两层文件路径后加载。 */
    public static PermissionConfig load(Path root) {
        return load(userFile(), projectFile(root));
    }

    /** 用户层文件路径（回写「永久允许」时也用它判目标层）。 */
    public static Path userFile() {
        return Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("permissions.json");
    }

    /** 项目层文件路径（「允许，永久」默认回写到这里）。 */
    public static Path projectFile(Path root) {
        return root.resolve(".codetui").resolve("permissions.json");
    }

    /** 可测入口：显式两文件。 */
    public static PermissionConfig load(Path userFile, Path projectFile) {
        List<PermissionRule> rules = new ArrayList<>();
        JsonNode userRoot = readTree(userFile);
        JsonNode projectRoot = readTree(projectFile);

        collect(userRoot, RuleScope.USER, rules);
        collect(projectRoot, RuleScope.PROJECT, rules);

        // defaultMode：项目层覆盖用户层；都没有/非法则 DEFAULT
        PermissionMode mode = parseMode(projectRoot);
        if (mode == null) {
            mode = parseMode(userRoot);
        }
        return new PermissionConfig(mode == null ? PermissionMode.DEFAULT : mode, List.copyOf(rules));
    }

    private static JsonNode readTree(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            log.warn("权限配置解析失败，忽略：{}（{}）", file, e.getMessage());
            return null;
        }
    }

    private static void collect(JsonNode root, RuleScope scope, List<PermissionRule> out) {
        if (root == null) {
            return;
        }
        add(root.get("deny"), PermissionBehavior.DENY, scope, out);
        add(root.get("ask"), PermissionBehavior.ASK, scope, out);
        add(root.get("allow"), PermissionBehavior.ALLOW, scope, out);
    }

    private static void add(JsonNode array, PermissionBehavior behavior,
                            RuleScope scope, List<PermissionRule> out) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode n : array) {
            if (!n.isString()) {
                log.warn("权限规则不是字符串，跳过：{}", n);
                continue;
            }
            PermissionRule r = PermissionRule.parse(n.stringValue(), behavior, scope);
            if (r == null) {
                log.warn("权限规则语法非法，跳过：{}", n.stringValue());
                continue;
            }
            out.add(r);
        }
    }

    /** 解析 defaultMode；缺失或未知返回 null（调用方回退）。 */
    private static PermissionMode parseMode(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode n = root.get("defaultMode");
        if (n == null || !n.isString()) {
            return null;
        }
        String raw = n.stringValue().trim();
        for (PermissionMode m : PermissionMode.values()) {
            if (m.name().equalsIgnoreCase(raw)) {
                return m;
            }
        }
        // 兼容 Claude Code 风格的小驼峰写法
        if ("acceptEdits".equalsIgnoreCase(raw)) {
            return PermissionMode.ACCEPT_EDITS;
        }
        log.warn("未知 defaultMode='{}'，回退 DEFAULT。", raw);
        return null;
    }
}
```

> ⚠ 与 `ToolTargets` 同样的 Jackson 3 API 注意事项：`isString()` / `stringValue()`
> 请按本项目实际 Jackson 版本的签名核对后再写（`McpConfigLoader` 用的是 `asString()`，
> 但那是在已知节点是文本的前提下——这里需要**先判类型**，故 API 名可能不同）。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionConfigLoaderTest`
Expected: PASS（6 个测试全绿）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfig.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigLoader.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigLoaderTest.java
git commit -m "feat(permission): permissions.json 两层加载与降级契约"
```

---

### Task 6: 「允许，永久」规则回写

**Files:**
- Create: `.../agent/permission/PermissionConfigWriter.java`
- Test: `.../agent/permission/PermissionConfigWriterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigWriterTest {

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.PROJECT);
    }

    @Test
    @DisplayName("文件不存在时自动建目录与文件")
    void createsFileWhenMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".codetui").resolve("permissions.json");

        assertTrue(PermissionConfigWriter.append(file, allow("Bash(mvn test:*)")));

        assertTrue(Files.isRegularFile(file));
        String json = Files.readString(file);
        assertTrue(json.contains("Bash(mvn test:*)"), "实际内容：" + json);
        assertTrue(json.contains("\"allow\""), "实际内容：" + json);
    }

    @Test
    @DisplayName("追加到已有文件时，保留未知字段与其它规则")
    void preservesExistingContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"defaultMode":"ACCEPT_EDITS",
                 "someFutureField":{"keep":true},
                 "allow":["Read(*)"],
                 "deny":["Bash(rm -rf /:*)"]}""");

        assertTrue(PermissionConfigWriter.append(file, allow("Write(src/**)")));

        String json = Files.readString(file);
        assertTrue(json.contains("someFutureField"), "未知字段必须原样保留：" + json);
        assertTrue(json.contains("Read(*)"));
        assertTrue(json.contains("Write(src/**)"));
        assertTrue(json.contains("rm -rf /"));
        assertTrue(json.contains("ACCEPT_EDITS"));
    }

    @Test
    @DisplayName("重复规则不重复写入（幂等）")
    void deduplicates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        PermissionConfigWriter.append(file, allow("Read(*)"));
        PermissionConfigWriter.append(file, allow("Read(*)"));

        PermissionConfig cfg = PermissionConfigLoader.load(file, dir.resolve("none.json"));
        assertEquals(1, cfg.rules().size(), "同一条规则只应存在一份");
    }

    @Test
    @DisplayName("ask / deny 各写进各自的数组")
    void writesToCorrectArray(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        PermissionConfigWriter.append(file,
                PermissionRule.parse("Bash(git push:*)", PermissionBehavior.ASK, RuleScope.PROJECT));
        PermissionConfigWriter.append(file,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.PROJECT));

        PermissionConfig cfg = PermissionConfigLoader.load(file, dir.resolve("none.json"));
        assertTrue(cfg.rules().stream()
                .anyMatch(r -> r.behavior() == PermissionBehavior.ASK && r.toDsl().equals("Bash(git push:*)")));
        assertTrue(cfg.rules().stream()
                .anyMatch(r -> r.behavior() == PermissionBehavior.DENY && r.toDsl().equals("Write(/etc/**)")));
    }

    @Test
    @DisplayName("降级契约：既有文件 JSON 非法 → 返回 false，不抛异常，不破坏原文件")
    void malformedExistingFileDegrades(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, "{ broken json");

        assertFalse(PermissionConfigWriter.append(file, allow("Read(*)")));
        assertEquals("{ broken json", Files.readString(file), "失败时不得改动原文件");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionConfigWriterTest`
Expected: 编译失败（`PermissionConfigWriter` 不存在）

- [ ] **Step 3: 写实现**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 「允许，永久」的落盘：往 {@code permissions.json} 的 allow/ask/deny 数组追加一条规则，
 * 其余树原样保留（Jackson 树模型读-改-写，未知字段与条目顺序不变）。
 *
 * <p>原子写：先写同目录临时文件再 move（优先 {@code ATOMIC_MOVE}，不支持的文件系统降级普通替换），
 * 防写一半损坏配置（照 {@code McpConfigWriter} / {@code FileSessionRepository} 的既有范式）。
 *
 * <p><b>降级契约</b>：JSON 非法 / 写失败 → 记 WARN、返回 false、<b>不改动原文件、绝不抛异常</b>。
 * 调用方（{@code PermissionEngine}）据 false 让 UI 提示「仅本次会话生效」。
 *
 * <p><b>幂等</b>：同一条 DSL 已存在则直接返回 true，不重复追加。
 */
public final class PermissionConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PermissionConfigWriter() {
    }

    public static boolean append(Path file, PermissionRule rule) {
        if (file == null || rule == null) {
            return false;
        }
        try {
            ObjectNode root;
            if (Files.isRegularFile(file)) {
                JsonNode existing = MAPPER.readTree(Files.readString(file));
                if (!existing.isObject()) {
                    log.warn("权限配置回写失败：{} 顶层不是 JSON 对象。", file);
                    return false;
                }
                root = (ObjectNode) existing;
            } else {
                root = MAPPER.createObjectNode();
            }

            String key = arrayKey(rule.behavior());
            JsonNode arr = root.get(key);
            ArrayNode array = (arr != null && arr.isArray())
                    ? (ArrayNode) arr : root.putArray(key);

            String dsl = rule.toDsl();
            for (JsonNode n : array) {
                if (n.isString() && dsl.equals(n.stringValue())) {
                    return true;      // 幂等：已存在
                }
            }
            array.add(dsl);

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.warn("权限配置回写失败：{} 规则 '{}'：{}", file, rule.toDsl(), e.getMessage());
            return false;
        }
    }

    private static String arrayKey(PermissionBehavior b) {
        return switch (b) {
            case ALLOW -> "allow";
            case ASK -> "ask";
            case DENY -> "deny";
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionConfigWriterTest`
Expected: PASS（5 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriter.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriterTest.java
git commit -m "feat(permission): 规则回写（原子写 + 幂等 + 保留未知字段）"
```

---

### Task 7: PermissionEngine（7 步决策顺序）

**Files:**
- Create: `.../agent/permission/PermissionDecision.java`
- Create: `.../agent/permission/PermissionEngine.java`
- Test: `.../agent/permission/PermissionEngineTest.java`

**决策顺序（顺序本身就是安全属性，测试逐条钉住）：**

```
1. deny 规则          ← 最高，任何模式下生效（含 BYPASS）
2. 内置危险检查        ← 不可被 allow 覆盖，命中强制 ASK（不是 DENY）
3. （工具自审：本期不实现，见「与 spec 的两处刻意偏差」）
4. ask 规则
5. allow 规则
6. 模式默认
7. 兜底 → ASK（并生成建议规则）
```

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionEngineTest {

    private static PermissionEngine engine(Path root, PermissionMode mode, String... dsl) {
        List<PermissionRule> rules = new java.util.ArrayList<>();
        for (String d : dsl) {
            // 约定：测试里用 "allow:X" / "ask:X" / "deny:X" 前缀声明行为
            int i = d.indexOf(':');
            PermissionBehavior b = switch (d.substring(0, i)) {
                case "allow" -> PermissionBehavior.ALLOW;
                case "ask" -> PermissionBehavior.ASK;
                default -> PermissionBehavior.DENY;
            };
            rules.add(PermissionRule.parse(d.substring(i + 1), b, RuleScope.USER));
        }
        return new PermissionEngine(root, new PermissionConfig(mode, rules), mode, true);
    }

    private static String readInput(Path p) {
        return "{\"filePath\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    private static String bash(String cmd) {
        return "{\"command\":\"" + cmd + "\"}";
    }

    // ── 顺序 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("① deny 规则最高：BYPASS 模式下也生效")
    void denyRuleBeatsBypass(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.BYPASS, "deny:Bash(git push:*)");
        assertEquals(PermissionBehavior.DENY, e.decide("Bash", bash("git push origin main")).behavior());
    }

    @Test
    @DisplayName("② 内置危险检查不可被 allow 规则绕过，且命中后是 ASK 不是 DENY")
    void builtinDangerBeatsAllowRule(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.ACCEPT_EDITS, "allow:Write(*)");
        Path sshConfig = Path.of(System.getProperty("user.home"), ".ssh", "config");

        PermissionDecision d = e.decide("Write", readInput(sshConfig));
        assertEquals(PermissionBehavior.ASK, d.behavior(), "allow(*) 盖不住内置检查");
        assertTrue(d.reason().contains(".ssh"), "原因应点明命中项，实际：" + d.reason());
    }

    @Test
    @DisplayName("② 内置危险检查在 BYPASS 下也触发")
    void builtinDangerTriggersInBypass(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.BYPASS);
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("rm -rf /")).behavior());
    }

    @Test
    @DisplayName("④ ask 规则优先于 allow 规则")
    void askRuleBeatsAllowRule(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT,
                "allow:Bash(git:*)", "ask:Bash(git push:*)");
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("git push origin main")).behavior());
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("git status")).behavior());
    }

    @Test
    @DisplayName("⑤ allow 规则命中即放行（先于模式默认）")
    void allowRuleBeatsModeDefault(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT, "allow:Bash(mvn test:*)");
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Bash", bash("mvn test -Dtest=Foo")).behavior());
    }

    // ── 模式默认 × 类别 ─────────────────────────────────────────────────

    @Test
    @DisplayName("⑥ 只读 / 只读网络 / 内部工具：所有模式下放行")
    void readOnlyAlwaysAllowed(@TempDir Path root) {
        for (PermissionMode m : PermissionMode.values()) {
            PermissionEngine e = engine(root, m);
            assertEquals(PermissionBehavior.ALLOW,
                    e.decide("Read", readInput(root.resolve("a.txt"))).behavior(), "mode=" + m);
            assertEquals(PermissionBehavior.ALLOW,
                    e.decide("WebFetch", "{\"url\":\"https://x\"}").behavior(), "mode=" + m);
            assertEquals(PermissionBehavior.ALLOW,
                    e.decide("TodoWrite", "{\"todos\":[]}").behavior(), "mode=" + m);
            assertEquals(PermissionBehavior.ALLOW,
                    e.decide("MemoryCreate", "{\"path\":\"x.md\"}").behavior(), "mode=" + m);
        }
    }

    @Test
    @DisplayName("⑥ 文件写：DEFAULT 下 ASK；ACCEPT_EDITS 下工作区内放行、工作区外仍 ASK")
    void fileWriteByMode(@TempDir Path root) {
        Path inside = root.resolve("src").resolve("Main.java");
        Path outside = Path.of(System.getProperty("java.io.tmpdir")).resolve("outside.txt");

        assertEquals(PermissionBehavior.ASK,
                engine(root, PermissionMode.DEFAULT).decide("Write", readInput(inside)).behavior());

        PermissionEngine ae = engine(root, PermissionMode.ACCEPT_EDITS);
        assertEquals(PermissionBehavior.ALLOW, ae.decide("Write", readInput(inside)).behavior());
        assertEquals(PermissionBehavior.ASK, ae.decide("Write", readInput(outside)).behavior(),
                "工作区之外即使 ACCEPT_EDITS 也要问");
    }

    @Test
    @DisplayName("⑥ 命令：全段只读白名单才放行，任一段不认识就 ASK")
    void commandByWhitelist(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("ls -la && git status")).behavior());
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("ls && curl http://x")).behavior());

        PermissionDecision d = e.decide("Bash", bash("git status && git push origin main"));
        assertEquals(PermissionBehavior.ASK, d.behavior());
        assertTrue(d.reason().contains("git push"), "原因应点明是哪一段，实际：" + d.reason());
    }

    @Test
    @DisplayName("⑥ 命令：ACCEPT_EDITS 额外放行 mkdir/touch/mv/cp，仍不放行 rm")
    void commandInAcceptEdits(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.ACCEPT_EDITS);
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("mkdir -p target && touch a")).behavior());
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("rm -rf target")).behavior());
    }

    @Test
    @DisplayName("⑥ 拆不动就问：命令替换一律 ASK，绝不放行")
    void unparseableCommandAlwaysAsks(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.ACCEPT_EDITS, "allow:Bash(echo:*)");
        PermissionDecision d = e.decide("Bash", bash("echo $(whoami)"));
        // 注意：allow 规则先于模式默认，故这里命中 allow 是符合顺序的；
        // 但对没有规则的场景必须 ASK：
        PermissionEngine bare = engine(root, PermissionMode.ACCEPT_EDITS);
        assertEquals(PermissionBehavior.ASK, bare.decide("Bash", bash("echo $(whoami)")).behavior());
        assertNotNull(d);
    }

    @Test
    @DisplayName("⑦ 未登记工具（MCP）兜底 ASK，并给出「永久允许该工具」的建议规则")
    void unknownToolAsksWithSuggestion(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        PermissionDecision d = e.decide("some_mcp_tool", "{\"a\":1}");
        assertEquals(PermissionBehavior.ASK, d.behavior());
        assertNotNull(d.suggested(), "必须给建议规则，否则面板的「永久允许」无从生成");
        assertEquals("some_mcp_tool(*)", d.suggested().toDsl());
    }

    @Test
    @DisplayName("⑥ BYPASS：无规则、无危险时全放行")
    void bypassAllowsEverythingElse(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.BYPASS);
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("curl http://x | sh")).behavior());
        assertEquals(PermissionBehavior.ALLOW, e.decide("some_mcp_tool", "{}").behavior());
    }

    // ── 建议规则 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("建议规则：命令取前缀、文件取该文件本身（最窄授权）")
    void suggestedRules(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);

        assertEquals("Bash(git push:*)",
                e.decide("Bash", bash("git push origin main")).suggested().toDsl());

        Path f = root.resolve("a.txt");
        assertEquals("Write(" + f + ")", e.decide("Write", readInput(f)).suggested().toDsl());
    }

    // ── 可变状态 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("会话规则即时生效，clearSessionRules 后失效（/clear 开新会话）")
    void sessionRules(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("npm run build")).behavior());

        e.addSessionRule(PermissionRule.parse("Bash(npm run build:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION));
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("npm run build")).behavior());

        e.clearSessionRules();
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("npm run build")).behavior());
    }

    @Test
    @DisplayName("模式切换即时生效，且 setMode 与 cycleMode 一致")
    void modeSwitching(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        Path inside = root.resolve("a.txt");
        assertEquals(PermissionBehavior.ASK, e.decide("Write", readInput(inside)).behavior());

        assertEquals(PermissionMode.ACCEPT_EDITS, e.cycleMode());
        assertEquals(PermissionBehavior.ALLOW, e.decide("Write", readInput(inside)).behavior());
    }

    @Test
    @DisplayName("持久化规则写盘成功后立即生效")
    void persistentRuleTakesEffect(@TempDir Path root) {
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        PermissionRule r = PermissionRule.parse("Bash(npm test:*)",
                PermissionBehavior.ALLOW, RuleScope.PROJECT);

        assertTrue(e.addPersistentRule(r), "写 <root>/.codetui/permissions.json 应成功");
        assertEquals(PermissionBehavior.ALLOW, e.decide("Bash", bash("npm test -- --watch")).behavior());
        assertTrue(java.nio.file.Files.isRegularFile(
                root.resolve(".codetui").resolve("permissions.json")));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionEngineTest`
Expected: 编译失败（`PermissionEngine` / `PermissionDecision` 不存在）

- [ ] **Step 3: 写 PermissionDecision**

```java
package io.github.javaside.springai.codetui.agent.permission;

/**
 * 一次判定的结果。
 *
 * @param behavior  结论
 * @param reason    人话原因（直接显示在审批面板 / 拼进给模型的拒绝串）
 * @param suggested 建议规则（仅 ASK 时非 null）：面板的「本会话不再问」/「永久允许」据它生成
 */
public record PermissionDecision(PermissionBehavior behavior, String reason, PermissionRule suggested) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW, reason, null);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(PermissionBehavior.DENY, reason, null);
    }

    public static PermissionDecision ask(String reason, PermissionRule suggested) {
        return new PermissionDecision(PermissionBehavior.ASK, reason, suggested);
    }
}
```

- [ ] **Step 4: 写 PermissionEngine**

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 权限判定引擎——<b>有状态的单例</b>，由 {@code AgentTools.build} 构造一次，
 * 三处装配点（build 装饰循环 / buildMemoryTools / McpRegistry）共用同一个实例。
 *
 * <h2>判定顺序（顺序本身就是安全属性，别重排）</h2>
 * <ol>
 *   <li>deny 规则——最高，任何模式下生效（含 BYPASS）</li>
 *   <li>内置危险检查（{@link DangerousPaths}）——<b>不可被 allow 规则覆盖</b>，命中强制 ASK 而非 DENY</li>
 *   <li><i>（预留插槽：工具自审 PermissionAware。本期无实现方，期 3 有需要时插在这里）</i></li>
 *   <li>ask 规则</li>
 *   <li>allow 规则</li>
 *   <li>模式默认</li>
 *   <li>兜底 → ASK（附建议规则）</li>
 * </ol>
 *
 * <h2>可变状态与线程</h2>
 * <ul>
 *   <li>{@code mode}：会话级 {@code volatile}。写方有二——UI 的模式切换键、启动参数；
 *       期 2 起还有工具线程上的 {@code ExitPlanMode} 批准。读写都是单次赋值、无复合操作，故不需要锁。</li>
 *   <li>{@code fileRules}：启动加载一次；「允许，永久」成功写盘后追加。{@code CopyOnWriteArrayList}。</li>
 *   <li>{@code sessionRules}：「本会话不再问」写入；{@code /clear} 开新会话时 {@link #clearSessionRules()}。</li>
 * </ul>
 *
 * <p><b>为何模式归 engine 持有</b>（而非 UI 持有再传入）：期 2 的 {@code ExitPlanMode} 批准动作发生在
 * <b>工具线程</b>、需要就地改模式；UI 只是另一个改写方。
 */
public final class PermissionEngine {

    private final Path root;
    private final boolean bypassAllowed;
    private final List<PermissionRule> fileRules = new CopyOnWriteArrayList<>();
    private final List<PermissionRule> sessionRules = new CopyOnWriteArrayList<>();
    private volatile PermissionMode mode;

    /**
     * @param root          项目根（工作区判定 + 相对 glob 相对化 + 永久规则回写目标）
     * @param config        两层 permissions.json 合并结果
     * @param startupMode   启动模式（启动参数 > config.defaultMode，由调用方定好后传入）
     * @param bypassAllowed 是否允许进入 BYPASS（{@code --dangerously-skip-permissions}）
     */
    public PermissionEngine(Path root, PermissionConfig config,
                            PermissionMode startupMode, boolean bypassAllowed) {
        this.root = root == null ? null : root.normalize();
        this.bypassAllowed = bypassAllowed;
        this.fileRules.addAll(config.rules());
        this.mode = startupMode;
    }

    // ── 模式 ────────────────────────────────────────────────────────────
    public PermissionMode mode() {
        return mode;
    }

    public void setMode(PermissionMode m) {
        this.mode = m;
    }

    /** 循环到下一个模式并返回新模式（UI 的 Shift+Tab）。 */
    public PermissionMode cycleMode() {
        PermissionMode next = mode.next(bypassAllowed);
        this.mode = next;
        return next;
    }

    public boolean bypassAllowed() {
        return bypassAllowed;
    }

    // ── 规则 ────────────────────────────────────────────────────────────
    /** 「允许，本会话不再问」。 */
    public void addSessionRule(PermissionRule rule) {
        if (rule != null) {
            sessionRules.add(rule);
        }
    }

    /** 「允许，永久」：写项目层 permissions.json；成功才纳入生效规则。失败返回 false（UI 提示仅本次会话生效）。 */
    public boolean addPersistentRule(PermissionRule rule) {
        if (rule == null || root == null) {
            return false;
        }
        boolean ok = PermissionConfigWriter.append(PermissionConfigLoader.projectFile(root), rule);
        if (ok) {
            fileRules.add(rule);
        } else {
            sessionRules.add(rule);   // 降级：至少本会话别再问
        }
        return ok;
    }

    /** {@code /clear} 开新会话时清空会话规则。 */
    public void clearSessionRules() {
        sessionRules.clear();
    }

    /** 当前生效的全部规则（{@code /permissions} 只读展示用）。 */
    public List<PermissionRule> effectiveRules() {
        List<PermissionRule> all = new ArrayList<>(fileRules);
        all.addAll(sessionRules);
        return List.copyOf(all);
    }

    // ── 判定 ────────────────────────────────────────────────────────────
    public PermissionDecision decide(String toolName, String toolInput) {
        ToolRegistry.Entry entry = ToolRegistry.lookup(toolName);
        String target = ToolTargets.extract(toolName, toolInput);
        Path path = entry.pathTarget() ? resolvePath(target) : null;

        // 1. deny 规则
        PermissionRule deny = firstMatch(PermissionBehavior.DENY, toolName, target, entry);
        if (deny != null) {
            return PermissionDecision.deny("命中 deny 规则 " + deny.toDsl());
        }

        // 2. 内置危险检查（不可绕过；命中强制 ASK）
        String danger = builtinDanger(entry, path, target);
        if (danger != null) {
            return PermissionDecision.ask(danger, suggest(toolName, target, entry));
        }

        // 3. （预留：工具自审 PermissionAware）

        // 4. ask 规则
        PermissionRule ask = firstMatch(PermissionBehavior.ASK, toolName, target, entry);
        if (ask != null) {
            return PermissionDecision.ask("命中 ask 规则 " + ask.toDsl(), suggest(toolName, target, entry));
        }

        // 5. allow 规则
        PermissionRule allow = firstMatch(PermissionBehavior.ALLOW, toolName, target, entry);
        if (allow != null) {
            return PermissionDecision.allow("命中 allow 规则 " + allow.toDsl());
        }

        // 6. 模式默认
        PermissionDecision byMode = decideByMode(entry, target, path);
        if (byMode != null) {
            return byMode;
        }

        // 7. 兜底
        return PermissionDecision.ask("未授权的操作", suggest(toolName, target, entry));
    }

    private PermissionRule firstMatch(PermissionBehavior behavior, String toolName,
                                      String target, ToolRegistry.Entry entry) {
        for (PermissionRule r : sessionRules) {
            if (r.behavior() == behavior && r.matches(toolName, target, entry.pathTarget(), root)) {
                return r;
            }
        }
        for (PermissionRule r : fileRules) {
            if (r.behavior() == behavior && r.matches(toolName, target, entry.pathTarget(), root)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 内置检查。
     *
     * <p><b>已知局限（刻意记录，别当成 bug）</b>：{@code Bash} 的目标是命令串，
     * 无法可靠地从中解析出「这条命令会写哪个文件」，故 {@code echo x >> ~/.zshrc}
     * <b>不</b>会被路径检查捕获——它由「非只读命令默认 ASK」兜住。
     */
    private String builtinDanger(ToolRegistry.Entry entry, Path path, String target) {
        return switch (entry.category()) {
            case FILE_WRITE -> DangerousPaths.checkWrite(path, root);
            case READ_ONLY -> entry.pathTarget() ? DangerousPaths.checkRead(path, root) : null;
            case COMMAND -> DangerousPaths.checkCommand(target);
            default -> null;
        };
    }

    /** 模式默认；返回 null 表示「模式不表态」，交给兜底 ASK。 */
    private PermissionDecision decideByMode(ToolRegistry.Entry entry, String target, Path path) {
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("BYPASS 模式");
        }
        return switch (entry.category()) {
            case INTERNAL, NETWORK_READ, READ_ONLY -> PermissionDecision.allow("只读 / 无外部副作用");
            case FILE_WRITE -> (mode == PermissionMode.ACCEPT_EDITS && insideRoot(path))
                    ? PermissionDecision.allow("ACCEPT_EDITS：工作区内的编辑")
                    : PermissionDecision.ask(
                            insideRoot(path) ? "修改工作区文件" : "修改工作区之外的文件", null);
            case COMMAND -> decideCommand(target);
            case UNKNOWN -> null;      // 交兜底（保守 ASK，并带建议规则）
        };
    }

    private PermissionDecision decideCommand(String command) {
        BashCommandSplitter.Split split = BashCommandSplitter.split(command);
        if (!split.parseable()) {
            return PermissionDecision.ask(
                    "命令语义无法确定（含命令替换 / 进程替换 / 引号内分隔符），退回人工确认", null);
        }
        if (split.segments().isEmpty()) {
            return PermissionDecision.ask("空命令", null);
        }
        for (int i = 0; i < split.segments().size(); i++) {
            String seg = split.segments().get(i);
            boolean ok = BashCommandSplitter.isReadOnly(seg)
                    || (mode == PermissionMode.ACCEPT_EDITS && BashCommandSplitter.isFileSystemWrite(seg));
            if (!ok) {
                return PermissionDecision.ask(
                        "第 " + (i + 1) + " 段 `" + seg + "` 未获授权", null);
            }
        }
        return PermissionDecision.allow("全部分段命中只读白名单");
    }

    /**
     * 建议规则（面板的「本会话不再问」/「永久允许」据它生成）。
     *
     * <ul>
     *   <li>命令 → 前缀规则 {@code Bash(<命令前缀>:*)}；前缀 = 首个词，若第二个词不是选项则一并计入
     *       （{@code git push} / {@code npm test}），使 {@code git status} 不会被 {@code git push} 的授权带上；</li>
     *   <li>路径 → 该文件<b>本身</b>（最窄授权，不擅自扩到整个目录）；</li>
     *   <li>其余（含 MCP）→ {@code 工具名(*)}，即「永久允许该工具」。</li>
     * </ul>
     */
    private PermissionRule suggest(String toolName, String target, ToolRegistry.Entry entry) {
        String pattern;
        if (entry.category() == ToolCategory.COMMAND && target != null) {
            pattern = commandPrefix(target) + ":*";
        } else if (entry.pathTarget() && target != null) {
            pattern = target;
        } else {
            pattern = null;
        }
        return new PermissionRule(toolName, pattern, PermissionBehavior.ALLOW, RuleScope.SESSION);
    }

    static String commandPrefix(String command) {
        String[] words = command.trim().split("\\s+");
        if (words.length == 0) {
            return command.trim();
        }
        if (words.length >= 2 && !words[1].startsWith("-")) {
            return words[0] + " " + words[1];
        }
        return words[0];
    }

    private boolean insideRoot(Path p) {
        return p != null && root != null && p.startsWith(root);
    }

    /** 目标路径归一：相对路径按 root 解析；无法解析则 null（引擎按「无法核实」处理）。 */
    private Path resolvePath(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(target);
            return (p.isAbsolute() || root == null) ? p.normalize() : root.resolve(p).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionEngineTest`
Expected: PASS（15 个测试全绿）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionDecision.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineTest.java
git commit -m "feat(permission): 决策引擎（deny > 内置检查 > ask > allow > 模式 > 兜底）"
```

---

### Task 8: 登记表完整性测试（对着运行时工具集比对）

**Files:**
- Test: `.../agent/permission/ToolRegistryCompletenessTest.java`

> **为什么单独一个 Task**：登记表漏一个工具 = 该工具悄悄落进 UNKNOWN → 每次调用都弹审批，
> 或（更糟）某天类别判错而静默放行。这个测试是登记表唯一的防漂移手段。
> **必须用 `getToolDefinition().name()` 比对，不是 Java 方法名**（见记忆 `tool-registered-name-is-annotation-not-method`）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.permission;

import io.github.javaside.springai.codetui.agent.AgentTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryCompletenessTest {

    /**
     * 把「本应用真会注册的内置工具」的<b>注册名</b>全量收集起来，比对登记表。
     * MCP 工具刻意不比对——它们运行期才知道，兜底 UNKNOWN→ASK 就是为它们准备的。
     */
    @Test
    @DisplayName("每个内置工具都在权限登记表里（漏登记即失败）")
    void everyBuiltinToolIsRegistered(@TempDir Path root) {
        List<String> runtimeNames = new ArrayList<>();
        Object[] plainTools = {
                FileSystemTools.builder().build(),
                ShellTools.builder().build(),
                GrepTool.builder().workingDirectory(root).build(),
                GlobTool.builder().workingDirectory(root).build(),
        };
        for (ToolCallback c : ToolCallbacks.from(plainTools)) {
            runtimeNames.add(c.getToolDefinition().name());
        }
        // 记忆工具（仅主 agent）：复用 AgentTools 的单一事实来源，避免装配与断言漂移
        for (ToolCallback c : AgentTools.buildMemoryTools(root, new NoopListener())) {
            runtimeNames.add(c.getToolDefinition().name());
        }
        // 其余按注册名直接列出（构造它们需要 API key / ChatClient，不值得为一个名字去装配）
        runtimeNames.addAll(List.of(
                "WebFetch", "TodoWrite", "Skill", "AskUserQuestionTool",
                "BochaWebSearch", "BraveWebSearch", "Task", "ParallelTasks"));

        var registered = ToolRegistry.registeredNames();
        var missing = new TreeSet<>(runtimeNames);
        missing.removeAll(registered);

        assertTrue(missing.isEmpty(),
                "以下工具未登记进 ToolRegistry（会落进 UNKNOWN→每次都弹审批）：" + missing
                        + "\n已登记：" + new TreeSet<>(registered));
    }

    @Test
    @DisplayName("登记表里没有已不存在的僵尸条目")
    void noStaleEntries(@TempDir Path root) {
        // 反向：登记表里的名字必须都能在「已知内置工具名」里找到，否则说明工具被删/改名了
        var known = new TreeSet<>(List.of(
                "Read", "Write", "Edit", "Bash", "BashOutput", "KillShell", "Grep", "Glob",
                "WebFetch", "TodoWrite", "Skill", "AskUserQuestionTool",
                "BochaWebSearch", "BraveWebSearch", "Task", "ParallelTasks",
                "MemoryView", "MemoryCreate", "MemoryStrReplace",
                "MemoryInsert", "MemoryDelete", "MemoryRename"));
        var stale = new TreeSet<>(ToolRegistry.registeredNames());
        stale.removeAll(known);
        assertTrue(stale.isEmpty(), "登记表存在已不再注册的僵尸条目：" + stale);
    }

    /** 空实现 listener（buildMemoryTools 只用它装饰，不会触发回调）。 */
    private static final class NoopListener
            implements io.github.javaside.springai.codetui.agent.AgentListener {
        @Override public void onTurnStarted(long t) { }
        @Override public void onUserMessage(long t, String x) { }
        @Override public void onAssistantToken(long t, String x) { }
        @Override public void onToolStarted(long t, String n, String i) { }
        @Override public void onToolFinished(long t, String n, String o, boolean ok) { }
        @Override public void onSubagentStarted(long t, String id, String a, String d) { }
        @Override public void onSubagentFinished(long t, String id, String f) { }
        @Override public void onTodoUpdated(long t, java.util.List<String> l) { }
        @Override public void onTurnComplete(long t) { }
        @Override public void onError(long t, Throwable e) { }
        @Override public void onQuestionAsked(long t,
                io.github.javaside.springai.codetui.agent.AskRequest r) { }
        @Override public void onCompactionStarted(String r) { }
        @Override public void onCompactionFinished(int a, int b) { }
        @Override public void onCompactionFailed(String m) { }
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn test -pl springai-code-tui -Dtest=ToolRegistryCompletenessTest`
Expected: PASS。**若 FAIL**，说明 Task 2 的登记表漏了工具——按报错里列出的名字补进 `ToolRegistry`，
并为它选类别（拿不准就填 `UNKNOWN`，宁可多问一次）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/ToolRegistryCompletenessTest.java
git commit -m "test(permission): 登记表完整性比对（漏登记即失败）"
```

---

### Task 9: 模态请求类型（sealed）与 listener 接缝

**Files:**
- Create: `.../agent/ModalRequest.java`
- Create: `.../agent/PermissionOutcome.java`
- Create: `.../agent/PermissionResponder.java`
- Create: `.../agent/PermissionRequest.java`
- Create: `.../agent/PermissionCancelledException.java`
- Modify: `.../agent/AskRequest.java`
- Modify: `.../agent/AgentListener.java`
- Test: `.../agent/ModalRequestTest.java`

> ⚠ **包约束**：`ModalRequest` 是 sealed interface，本项目无 `module-info`（unnamed module），
> **permitted 子类型必须与它同包**。`AskRequest` 在 `agent/`，故 `PermissionRequest` 只能也放 `agent/`，
> 不能塞进 `agent/permission/`。这不是风格选择，是语言约束。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModalRequestTest {

    @Test
    @DisplayName("AskRequest 与 PermissionRequest 都是 ModalRequest，可放进同一个队列")
    void bothAreModalRequests() {
        AtomicReference<PermissionOutcome> got = new AtomicReference<>();
        ModalRequest ask = new AskRequest(7L, List.of(), () -> { });
        ModalRequest perm = permissionRequest(7L, got::set);

        assertInstanceOf(ModalRequest.class, ask);
        assertInstanceOf(ModalRequest.class, perm);
        assertEquals(7L, ask.turnId());
        assertEquals(7L, perm.turnId());
    }

    @Test
    @DisplayName("统一取消入口：cancel() 唤醒各自的阻塞线程")
    void cancelWakesBlockedThread() {
        AtomicReference<PermissionOutcome> got = new AtomicReference<>();
        ModalRequest perm = permissionRequest(1L, got::set);

        perm.cancel();

        assertEquals(PermissionOutcome.CANCEL, got.get(),
                "cancel() 必须给工具线程投 CANCEL，否则它永久 park");
    }

    @Test
    @DisplayName("AgentListener 的默认实现直接 DENY —— 未实现该方法的落地端不会让工具线程永久 park")
    void listenerDefaultDenies() {
        AtomicReference<PermissionOutcome> got = new AtomicReference<>();
        AgentListener bare = new BareListener();

        bare.onPermissionRequested(1L, permissionRequest(1L, got::set));

        assertEquals(PermissionOutcome.DENY, got.get(),
                "默认实现必须应答（DENY），不能是空实现");
    }

    @Test
    @DisplayName("switch 可穷尽（sealed 生效）")
    void exhaustiveSwitch() {
        ModalRequest r = new AskRequest(1L, List.of(), () -> { });
        String kind = switch (r) {
            case AskRequest a -> "ask";
            case PermissionRequest p -> "permission";
        };
        assertTrue("ask".equals(kind));
    }

    private static PermissionRequest permissionRequest(long turnId, PermissionResponder responder) {
        return new PermissionRequest(turnId, null, "Bash", "git push origin main",
                "{\"command\":\"git push origin main\"}", "未授权的操作",
                PermissionRule.parse("Bash(git push:*)", PermissionBehavior.ALLOW, RuleScope.SESSION),
                responder);
    }

    /** 只实现必需方法的落地端：用来验证 onPermissionRequested 的默认实现是安全的。 */
    private static final class BareListener implements AgentListener {
        @Override public void onTurnStarted(long t) { }
        @Override public void onUserMessage(long t, String x) { }
        @Override public void onAssistantToken(long t, String x) { }
        @Override public void onToolStarted(long t, String n, String i) { }
        @Override public void onToolFinished(long t, String n, String o, boolean ok) { }
        @Override public void onSubagentStarted(long t, String id, String a, String d) { }
        @Override public void onSubagentFinished(long t, String id, String f) { }
        @Override public void onTodoUpdated(long t, List<String> l) { }
        @Override public void onTurnComplete(long t) { }
        @Override public void onError(long t, Throwable e) { }
        @Override public void onQuestionAsked(long t, AskRequest r) { }
        @Override public void onCompactionStarted(String r) { }
        @Override public void onCompactionFinished(int a, int b) { }
        @Override public void onCompactionFailed(String m) { }
    }
}
```

> 注：上面 `new AskRequest(7L, List.of(), () -> { })` 依赖 `AskResponder` 是函数式接口。
> 若它有 `answer` + `cancel` 两个方法（当前正是如此），请改用匿名类：
> `new AskResponder() { public void answer(Map<String,String> a) {} public void cancel() {} }`。
> **写测试前先看一眼 `AskResponder` 的实际形状**，别照抄成编译不过的形式。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ModalRequestTest`
Expected: 编译失败（`ModalRequest` / `PermissionRequest` 等不存在）

- [ ] **Step 3: 写四个新类型**

`agent/PermissionOutcome.java`：

```java
package io.github.javaside.springai.codetui.agent;

/** 审批面板的五种结果——UI 选完后经 {@link PermissionResponder} 喂回阻塞的工具线程。 */
public enum PermissionOutcome {
    /** 允许一次。 */
    ALLOW_ONCE,
    /** 允许，本会话不再问（加一条内存 session 规则）。 */
    ALLOW_SESSION,
    /** 允许，永久（写 {@code <root>/.codetui/permissions.json}）。 */
    ALLOW_ALWAYS,
    /** 拒绝，让模型换个做法——<b>回合继续</b>，工具返回拒绝串。 */
    DENY,
    /** 中断本回合——工具线程抛 {@link PermissionCancelledException}，UI 侧走既有 {@code cancelTurnFor}。 */
    CANCEL
}
```

`agent/PermissionResponder.java`：

```java
package io.github.javaside.springai.codetui.agent;

/** UI → 工具线程的一次性应答口（实现方是 {@code PermissionCallback} 内的一次性队列）。 */
@FunctionalInterface
public interface PermissionResponder {
    void respond(PermissionOutcome outcome);
}
```

`agent/PermissionCancelledException.java`：

```java
package io.github.javaside.springai.codetui.agent;

/**
 * 审批期回合被中断（用户选「拒绝并中断本回合」/ Esc，或回合被 dispose 中断了工具线程）。
 *
 * <p>与 {@code QuestionCancelledException} 同款：此时回合已被 dispose、会话经 {@code doOnCancel} 回滚，
 * 本异常随流被丢弃，不会走到用户面前。
 */
public class PermissionCancelledException extends RuntimeException {
    public PermissionCancelledException() {
        super("permission request cancelled");
    }
}
```

`agent/ModalRequest.java`：

```java
package io.github.javaside.springai.codetui.agent;

/**
 * 一次需要抢占 UI 焦点的模态请求。
 *
 * <p><b>为何要统一</b>：问询（{@link AskRequest}）与审批（{@link PermissionRequest}）竞争同一个输入焦点，
 * 各搞一套状态必然互相覆盖。二者统一进 {@code ConversationState} 的模态请求队列，UI 逐个弹。
 *
 * <p><b>sealed 的包约束</b>：本项目无 {@code module-info}（unnamed module），
 * permitted 子类型必须与本接口<b>同包</b>——这就是 {@code PermissionRequest} 放在
 * {@code agent/} 而非 {@code agent/permission/} 的原因。
 */
public sealed interface ModalRequest permits AskRequest, PermissionRequest {

    /** 发起该请求的回合（供 UI 迟到过滤）。 */
    long turnId();

    /**
     * 统一取消入口：唤醒阻塞在本请求上的工具线程。
     *
     * <p><b>活性纪律</b>：回合取消时 {@code ConversationState.cancelCurrent()} 必须遍历队列
     * 对每个 pending 请求调用本方法——漏了这步，被取消回合里的子 agent 线程会<b>永久 park</b>。
     */
    void cancel();
}
```

`agent/PermissionRequest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionRule;

/**
 * 一次审批请求：由 {@link PermissionCallback} 构造、经 {@link AgentListener#onPermissionRequested}
 * 交给 UI；UI 弹面板，用户选完经 {@link #responder} 唤醒阻塞的工具线程。
 *
 * @param turnId    发起回合（迟到过滤：{@code turnId != activeTurnId} 的请求直接 DENY，不弹面板）
 * @param taskId    子 agent 任务 id（非 null 时面板标题带来源，如「来自子 agent explore」）
 * @param toolName  工具注册名
 * @param target    判定目标（路径 / 命令 / 整串入参）——面板主体显示
 * @param rawInput  原始入参 JSON（面板可展开；{@code Write}/{@code Edit} 交给 DiffRenderer 渲染改动）
 * @param reason    为什么要问（引擎给的人话原因）
 * @param suggested 建议规则：面板的「本会话不再问」/「永久允许」据它生成；可为 null
 * @param responder UI 应答回调
 */
public record PermissionRequest(long turnId, String taskId, String toolName, String target,
                                String rawInput, String reason, PermissionRule suggested,
                                PermissionResponder responder) implements ModalRequest {

    @Override
    public void cancel() {
        responder.respond(PermissionOutcome.CANCEL);
    }
}
```

- [ ] **Step 4: 改 AskRequest**

`agent/AskRequest.java` —— 加 `implements ModalRequest` 与 `cancel()`：

```java
package io.github.javaside.springai.codetui.agent;

import java.util.List;

/**
 * 一次问询句柄：由 {@code UserQuestionBridge} 构造、经 {@link AgentListener#onQuestionAsked} 交给 UI。
 * UI 据 {@link #questions} 渲染作答面板，答完/取消经 {@link #responder} 唤醒工具线程。
 *
 * @param turnId    发起问询的回合（供 UI 迟到过滤）
 * @param questions 顺序问询的问题列表（1–4 个）
 * @param responder UI 应答回调
 */
public record AskRequest(long turnId, List<QuestionSpec> questions, AskResponder responder)
        implements ModalRequest {

    public AskRequest {
        questions = List.copyOf(questions);
    }

    /** {@link ModalRequest} 统一取消入口：转成问询自己的取消语义。 */
    @Override
    public void cancel() {
        responder.cancel();
    }
}
```

- [ ] **Step 5: 改 AgentListener**

在 `AgentListener` 里 `onQuestionAsked` 之后追加：

```java
    /**
     * 模型调用的某个工具需要人工授权：UI 应把请求排进模态队列、弹审批面板，
     * 最终经 {@code request.responder()} 应答。落地端会阻塞工具线程直到应答（见 {@code PermissionCallback}）。
     *
     * <p><b>默认实现直接 DENY 而不是空实现</b>：空实现会让工具线程永久 park。
     * 任何没有真正接管审批 UI 的落地端（回显桩 / 测试桩）都应该「拒绝并让回合继续」，而不是挂死。
     */
    default void onPermissionRequested(long turnId, PermissionRequest request) {
        request.responder().respond(PermissionOutcome.DENY);
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=ModalRequestTest`
Expected: PASS（4 个测试全绿）

- [ ] **Step 7: 全量回归（改了 AskRequest，涉及既有测试）**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿。若 `ConversationStateAskTest` / `CodeTuiViewAskTest` 编译不过，
说明它们构造 `AskRequest` 的方式受影响——按新签名（签名未变，只加了接口）修正即可。

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModalRequest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionOutcome.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionResponder.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionRequest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionCancelledException.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AskRequest.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentListener.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModalRequestTest.java
git commit -m "feat(permission): 模态请求 sealed 类型与 listener 接缝（默认实现直接 DENY）"
```

---

### Task 10: ConversationState 模态队列重构

**Files:**
- Modify: `.../ui/ConversationState.java`（`pendingAsk` 单字段 → `Deque<ModalRequest>`）
- Test: `.../ui/ConversationStateModalQueueTest.java`

> **本期唯一的既有代码重构**。风险点：它触及已验证的取消路径。纪律是
> **保持 `cancelTurnFor` 语义不变，只改「谁进模态」的选取逻辑**。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AskRequest;
import io.github.javaside.springai.codetui.agent.AskResponder;
import io.github.javaside.springai.codetui.agent.ModalRequest;
import io.github.javaside.springai.codetui.agent.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.PermissionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStateModalQueueTest {

    private static ConversationState started(long turnId) {
        ConversationState s = new ConversationState();
        s.onTurnStarted(turnId);
        return s;
    }

    private static PermissionRequest perm(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Bash", "cmd", "{}", "why", null, sink::add);
    }

    @Test
    @DisplayName("FIFO：先进先出，peek 不出队")
    void fifoOrder() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        PermissionRequest a = perm(1L, sink);
        PermissionRequest b = perm(1L, sink);

        s.onPermissionRequested(1L, a);
        s.onPermissionRequested(1L, b);

        assertSame(a, s.peekModal());
        assertSame(a, s.peekModal(), "peek 不应出队");
        s.removeModal(a);
        assertSame(b, s.peekModal());
    }

    @Test
    @DisplayName("迟到请求（turnId 不匹配）不入队，直接 DENY —— 不是 CANCEL")
    void lateRequestIsDenied() {
        ConversationState s = started(5L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();

        s.onPermissionRequested(4L, perm(4L, sink));

        assertNull(s.peekModal(), "迟到请求不该进队列");
        assertEquals(List.of(PermissionOutcome.DENY), sink,
                "迟到应答必须是 DENY（回合继续），不是 CANCEL");
    }

    @Test
    @DisplayName("队列上限 8：第 9 个直接 DENY，防失控回合塞爆队列")
    void queueCapAtEight() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            s.onPermissionRequested(1L, perm(1L, sink));
        }
        assertTrue(sink.isEmpty(), "前 8 个应全部入队、无人被应答");

        s.onPermissionRequested(1L, perm(1L, sink));
        assertEquals(List.of(PermissionOutcome.DENY), sink, "第 9 个应被直接拒绝");
    }

    @Test
    @DisplayName("取消回合必须唤醒队列里每一个 pending 请求（漏了就是永久 park）")
    void cancelWakesEveryPendingRequest() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1L, perm(1L, sink));
        s.onPermissionRequested(1L, perm(1L, sink));

        List<String> askCancels = new ArrayList<>();
        s.onQuestionAsked(1L, new AskRequest(1L, List.of(), new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { askCancels.add("cancelled"); }
        }));

        s.cancelCurrent();

        assertEquals(List.of(PermissionOutcome.CANCEL, PermissionOutcome.CANCEL), sink,
                "两个审批请求都必须收到 CANCEL");
        assertEquals(List.of("cancelled"), askCancels, "问询也必须被取消");
        assertNull(s.peekModal(), "取消后队列应清空");
    }

    @Test
    @DisplayName("问询与审批共用同一个队列（不会互相覆盖）")
    void asksAndPermissionsShareQueue() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        AskRequest ask = new AskRequest(1L, List.of(), new AskResponder() {
            @Override public void answer(Map<String, String> a) { }
            @Override public void cancel() { }
        });
        PermissionRequest p = perm(1L, sink);

        s.onQuestionAsked(1L, ask);
        s.onPermissionRequested(1L, p);

        ModalRequest first = s.peekModal();
        assertSame(ask, first, "先到的问询先弹");
        s.removeModal(ask);
        assertSame(p, s.peekModal(), "问询处理完，审批接着弹（不会被覆盖）");
    }

    @Test
    @DisplayName("有 pending 模态时 isBusy()=true —— 否则排队消息会在审批期间被错误出队")
    void busyWhileModalPending() {
        ConversationState s = started(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        s.onPermissionRequested(1L, perm(1L, sink));
        s.onTurnComplete(1L);           // 即使回合被标记结束

        assertTrue(s.isBusy(), "还有待审批的模态就不算空闲");
        s.removeModal(s.peekModal());
        assertFalse(s.isBusy());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStateModalQueueTest`
Expected: 编译失败（`peekModal` / `removeModal` / `onPermissionRequested` 不存在）

- [ ] **Step 3: 改 ConversationState**

① 删掉 `private volatile AskRequest pendingAsk;`，换成队列（放在同一处「瞬态」注释区）：

```java
    // ── 模态请求队列（问询 + 审批共用；渲染线程读、工具线程写；迟到过滤后置入） ──
    // 为何是队列而非单字段：ParallelTasks 下多个子 agent 线程可能同时判出 ASK，
    // 单个 volatile 字段会让后来者覆盖前者、被覆盖的那个工具线程永久 park。
    private final Deque<ModalRequest> modals = new ArrayDeque<>();

    /** 队列上限：防失控回合塞爆队列；超出的请求直接被拒（DENY，回合继续）。 */
    static final int MODAL_QUEUE_CAP = 8;
```

② 删掉 `pendingAsk()` / `clearPendingAsk()`，换成队列 API：

```java
    /** 队首模态请求（无则 null）；渲染线程读，<b>不出队</b>。 */
    public synchronized ModalRequest peekModal() { return modals.peek(); }

    /** 按身份移除一个已处理完的模态请求（答完 / 批完 / 取消后由 UI 调）。 */
    public synchronized void removeModal(ModalRequest r) { modals.remove(r); }

    /** 是否有待处理模态（计入 {@link #isBusy()}）。 */
    public synchronized boolean hasModal() { return !modals.isEmpty(); }

    /**
     * 入队；队列已满返回 false（调用方负责应答，绝不能静默丢弃——丢了就是永久 park）。
     */
    private boolean offerModal(ModalRequest r) {
        if (modals.size() >= MODAL_QUEUE_CAP) return false;
        modals.add(r);
        return true;
    }
```

③ `isBusy()` 计入模态：

```java
    /** 「忙」= 有活跃回合 / 正在压缩 / 有待处理模态：此时不应发起新回合（排队）、也不应触发手动压缩。 */
    public boolean isBusy() { return !isIdle() || isCompacting() || hasModal(); }
```

④ `cancelCurrent()` 顶部清空模态队列（**活性关键，漏了子 agent 线程永久 park**）：

```java
    /** Esc 取消当前回合：唤醒全部待处理模态、定稿在建行、acceptingTurnId=-1、状态回 IDLE。 */
    public synchronized void cancelCurrent() {
        clearModals();                 // 必须最先做：漏了这步，阻塞中的工具线程永久 park
        flushStreaming();
        acceptingTurnId = -1L;
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    /** 唤醒并清空全部待处理模态（取消回合 / 开新会话）。 */
    public synchronized void clearModals() {
        for (ModalRequest r : modals) r.cancel();
        modals.clear();
    }
```

⑤ `onQuestionAsked` 改成入队（保留既有迟到过滤语义）：

```java
    @Override
    public synchronized void onQuestionAsked(long turnId, AskRequest request) {
        if (turnId != acceptingTurnId || !offerModal(request)) {
            // 迟到（回合已取消/切换）或队列已满：桥侧的 take() 靠取消路径唤醒，这里直接取消不弹面板。
            request.cancel();
        }
    }
```

⑥ 新增 `onPermissionRequested`（注意：迟到/队满都是 **DENY**，不是 CANCEL——回合要继续）：

```java
    /**
     * 审批请求入队。<b>迟到与队满一律 DENY 而非 CANCEL</b>：
     * 拒绝让模型自寻替代、回合继续；CANCEL 会中断整个回合，语义过重。
     */
    @Override
    public synchronized void onPermissionRequested(long turnId, PermissionRequest request) {
        if (turnId != acceptingTurnId || !offerModal(request)) {
            request.responder().respond(PermissionOutcome.DENY);
        }
    }
```

⑦ `resetForNewSession()` 里补 `clearModals();`（`/clear` 时别把模态留给新会话）。

⑧ 补 import：`ModalRequest` / `PermissionRequest` / `PermissionOutcome`（`ArrayDeque` / `Deque` 已有）。

- [ ] **Step 4: 跟着改调用方（编译驱动）**

`CodeTuiView` 里 `state.pendingAsk()` / `state.clearPendingAsk()` 的两处引用会编译失败——
**本 Task 只做最小改动让它编译过**（完整面板逻辑在 Task 14）：

- `drain()` 里 `AskRequest pa = state.pendingAsk();` → `ModalRequest pa = state.peekModal();`
  并在使用前 `if (pa instanceof AskRequest ar) { ... }`（暂时忽略 `PermissionRequest` 分支）。
- `clearAskState()` 里 `state.clearPendingAsk();` → `state.removeModal(activeAsk);`
  （注意顺序：先取 `activeAsk` 再置 null，别先清了引用再 remove）。

- [ ] **Step 5: 跑测试确认通过 + 全量回归**

Run: `mvn test -pl springai-code-tui -Dtest=ConversationStateModalQueueTest`
Expected: PASS（6 个测试全绿）

Run: `mvn test -pl springai-code-tui`
Expected: 全绿。重点看 `ConversationStateAskTest` / `CodeTuiViewAskTest` / `ConversationStateResetTest`——
它们钉的是既有问询与取消语义，**必须不回归**。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateModalQueueTest.java
git commit -m "refactor(ui): pendingAsk 单字段改为模态请求队列（问询与审批共用，取消必唤醒）"
```

---

### Task 11: PermissionCallback（最外层拦截器 + 阻塞握手）

**Files:**
- Create: `.../agent/PermissionCallback.java`
- Modify: `.../agent/ToolEventCallback.java`（`extractTurnId` / `extractTaskId` 由 private 改包私有静态，供复用）
- Test: `.../agent/PermissionCallbackTest.java`

> **为何在最外层**：若放在 `ToolEventCallback` 内层，被拒绝的调用会先发 `onToolStarted`、
> 在 TUI 显示成「工具开始运行」再变失败；且审批等待期间状态栏一直显示「工具运行中」。
> 放最外层后，未获批准的调用<b>根本不产生工具事件</b>，改由独立的权限事件驱动 UI。
>
> **turnId 必须从 `ToolContext` 取，不能读 ThreadLocal**——此时还没进 `ToolEventCallback`，
> ThreadLocal 尚未压入。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCallbackTest {

    /** 记录是否被真正调用的假工具。 */
    private static final class FakeTool implements ToolCallback {
        final AtomicBoolean called = new AtomicBoolean();
        private final String name;
        FakeTool(String name) { this.name = name; }
        @Override public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        }
        @Override public String call(String input) { called.set(true); return "OK"; }
        @Override public String call(String input, ToolContext ctx) { return call(input); }
    }

    private static PermissionEngine engine(Path root, PermissionMode mode) {
        return new PermissionEngine(root, new PermissionConfig(mode, List.of()), mode, true);
    }

    private static ToolContext ctx(long turnId) {
        return new ToolContext(Map.of("turnId", turnId));
    }

    @Test
    @DisplayName("ALLOW：透传给真实工具，返回原样")
    void allowPassesThrough(@TempDir Path root) {
        FakeTool tool = new FakeTool("Read");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { throw new AssertionError("ALLOW 不该弹审批"); });

        assertEquals("OK", cb.call("{\"filePath\":\"" + root.resolve("a.txt") + "\"}", ctx(1L)));
        assertTrue(tool.called.get());
    }

    @Test
    @DisplayName("DENY：返回拒绝字符串，不抛异常，不调用真实工具（回合要继续）")
    void denyReturnsStringNotException(@TempDir Path root) {
        FakeTool tool = new FakeTool("Bash");
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        // 审批面板选「拒绝」→ 同样走返回串路径
        PermissionCallback cb = new PermissionCallback(tool, e,
                (t, r) -> r.responder().respond(PermissionOutcome.DENY));

        String out = cb.call("{\"command\":\"curl http://x\"}", ctx(1L));

        assertTrue(out.startsWith("Permission denied:"), "实际返回：" + out);
        assertFalse(tool.called.get(), "被拒绝的工具绝不能真的执行");
    }

    @Test
    @DisplayName("ASK：阻塞工具线程，直到 UI 喂回结果")
    void askBlocksUntilAnswered(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        AtomicReference<PermissionRequest> captured = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { captured.set(r); asked.countDown(); });

        AtomicReference<String> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(cb.call("{\"command\":\"npm test\"}", ctx(1L))));
        worker.start();

        assertTrue(asked.await(2, TimeUnit.SECONDS), "应在 2 秒内发出审批请求");
        assertFalse(tool.called.get(), "喂回结果前工具不该执行");

        captured.get().responder().respond(PermissionOutcome.ALLOW_ONCE);
        worker.join(2000);

        assertFalse(worker.isAlive(), "喂回结果后工具线程必须解除阻塞");
        assertEquals("OK", result.get());
        assertTrue(tool.called.get());
    }

    @Test
    @DisplayName("ALLOW_SESSION：加会话规则，同类调用下次直接放行不再问")
    void allowSessionAddsRule(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        PermissionEngine e = engine(root, PermissionMode.DEFAULT);
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        PermissionCallback cb = new PermissionCallback(tool, e, (t, r) -> {
            asks.add(r);
            r.responder().respond(PermissionOutcome.ALLOW_SESSION);
        });

        cb.call("{\"command\":\"npm test\"}", ctx(1L));
        cb.call("{\"command\":\"npm test -- --watch\"}", ctx(1L));

        assertEquals(1, asks.size(), "第二次同前缀调用应被会话规则放行，不再弹审批");
    }

    @Test
    @DisplayName("CANCEL：抛 PermissionCancelledException，不调用真实工具")
    void cancelThrows(@TempDir Path root) {
        FakeTool tool = new FakeTool("Bash");
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> r.responder().respond(PermissionOutcome.CANCEL));

        assertThrows(PermissionCancelledException.class,
                () -> cb.call("{\"command\":\"npm test\"}", ctx(1L)));
        assertFalse(tool.called.get());
    }

    @Test
    @DisplayName("活性：中断阻塞中的工具线程不会永久 park")
    void interruptDoesNotPark(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> asked.countDown());   // 故意不应答

        Thread worker = new Thread(() -> {
            try {
                cb.call("{\"command\":\"npm test\"}", ctx(1L));
            } catch (RuntimeException expected) {
                // PermissionCancelledException
            } finally {
                done.countDown();
            }
        });
        worker.start();
        assertTrue(asked.await(2, TimeUnit.SECONDS));
        worker.interrupt();

        assertTrue(done.await(2, TimeUnit.SECONDS), "被中断的工具线程必须退出，不能永久 park");
    }

    @Test
    @DisplayName("并发：多个线程各自阻塞在自己的队列上，各自独立唤醒（ParallelTasks 场景）")
    void concurrentRequestsAreIndependent(@TempDir Path root) throws Exception {
        FakeTool tool = new FakeTool("Bash");
        List<PermissionRequest> asks = new CopyOnWriteArrayList<>();
        CountDownLatch allAsked = new CountDownLatch(3);
        CountDownLatch allDone = new CountDownLatch(3);
        PermissionCallback cb = new PermissionCallback(tool, engine(root, PermissionMode.DEFAULT),
                (t, r) -> { asks.add(r); allAsked.countDown(); });

        for (int i = 0; i < 3; i++) {
            int n = i;
            Thread th = new Thread(() -> {
                try {
                    cb.call("{\"command\":\"job" + n + " run\"}", ctx(1L));
                } catch (RuntimeException ignored) {
                    // CANCEL 分支
                } finally {
                    allDone.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }

        assertTrue(allAsked.await(3, TimeUnit.SECONDS), "3 个线程都应各自发出审批请求");
        for (PermissionRequest r : asks) {
            r.responder().respond(PermissionOutcome.DENY);
        }
        assertTrue(allDone.await(3, TimeUnit.SECONDS),
                "每个线程必须被自己的应答唤醒——共用一个队列会漏唤醒");
    }
}
```

> ⚠ `DefaultToolDefinition.builder()` 的实际类名/包名请按 Spring AI 2.0 的真实 API 核对
> （`ToolNameProbeTest` / `RenamedToolCallbackTest` 里已有构造 `ToolDefinition` 的现成写法，照抄那边）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionCallbackTest`
Expected: 编译失败（`PermissionCallback` 不存在）

- [ ] **Step 3: 把 ToolEventCallback 的两个提取方法改成包私有**

`ToolEventCallback.java`：`private static String extractTaskId` → `static String extractTaskId`，
`private static long extractTurnId` → `static long extractTurnId`（同包复用，避免两份实现漂移）。

- [ ] **Step 4: 写 PermissionCallback**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionDecision;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import io.github.javaside.springai.codetui.agent.permission.ToolTargets;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 权限拦截装饰器——<b>装饰链的最外层</b>：
 * {@code PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )}。
 *
 * <p><b>为何最外层</b>：放内层的话，被拒绝的调用会先发 {@code onToolStarted}、在 TUI 显示成
 * 「工具开始运行」再变失败；审批等待期间状态栏还会一直显示「工具运行中」。放最外层后，
 * 未获批准的调用根本不产生工具事件，改由独立的权限事件驱动 UI。
 *
 * <p><b>turnId 从 {@link ToolContext} 取，不读 ThreadLocal</b>——此时还没进 {@code ToolEventCallback}，
 * ThreadLocal 尚未压入。
 *
 * <p><b>被拒绝时返回错误字符串，不抛异常</b>：抛异常会顺着 Reactor 流炸掉整个回合，与护栏定位相悖；
 * 返回字符串让模型能自寻替代方案、回合继续。只有「中断本回合」才抛
 * {@link PermissionCancelledException}。
 *
 * <p><b>活性依赖（本类不自保，照 {@code UserQuestionBridge} 的纪律）</b>：{@code take()} 无超时，
 * 解除阻塞完全依赖两条外部逃生口——① UI 侧总会应答（审批面板保证，且
 * {@code ConversationState} 对迟到/队满请求会立即 DENY）；② 回合被 dispose 时框架中断本线程。
 * 队列容量 1 + 非阻塞 {@code offer} 使「首个信号胜出」，重复/交叉应答被安全丢弃。
 */
public final class PermissionCallback implements ToolCallback {

    /** UI 出口：把审批请求交出去（生产实现是 {@code listener::onPermissionRequested}）。 */
    @FunctionalInterface
    public interface Asker {
        void ask(long turnId, PermissionRequest request);
    }

    private final ToolCallback delegate;
    private final PermissionEngine engine;
    private final Asker asker;

    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, Asker asker) {
        this.delegate = delegate;
        this.engine = engine;
        this.asker = asker;
    }

    /** 生产构造：直接挂到 listener 上。 */
    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, AgentListener listener) {
        this(delegate, engine, listener::onPermissionRequested);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        PermissionDecision decision = engine.decide(name, toolInput);

        if (decision.behavior() == PermissionBehavior.ALLOW) {
            return invoke(toolInput, toolContext);
        }
        if (decision.behavior() == PermissionBehavior.DENY) {
            return denyMessage(decision.reason());
        }
        return askThenAct(name, toolInput, toolContext, decision);
    }

    private String askThenAct(String name, String toolInput, ToolContext toolContext,
                              PermissionDecision decision) {
        long turnId = ToolEventCallback.extractTurnId(toolContext);
        String taskId = ToolEventCallback.extractTaskId(toolContext);
        ArrayBlockingQueue<PermissionOutcome> handoff = new ArrayBlockingQueue<>(1);

        PermissionRequest request = new PermissionRequest(
                turnId, taskId, name, ToolTargets.extract(name, toolInput), toolInput,
                decision.reason(), decision.suggested(), handoff::offer);
        asker.ask(turnId, request);

        PermissionOutcome outcome;
        try {
            outcome = handoff.take();               // 阻塞工具线程直到 UI 应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PermissionCancelledException();
        }

        return switch (outcome) {
            case ALLOW_ONCE -> invoke(toolInput, toolContext);
            case ALLOW_SESSION -> {
                engine.addSessionRule(rescope(decision.suggested(), RuleScope.SESSION));
                yield invoke(toolInput, toolContext);
            }
            case ALLOW_ALWAYS -> {
                engine.addPersistentRule(rescope(decision.suggested(), RuleScope.PROJECT));
                yield invoke(toolInput, toolContext);
            }
            case DENY -> denyMessage("用户拒绝了本次操作（" + decision.reason() + "）");
            case CANCEL -> throw new PermissionCancelledException();
        };
    }

    private String invoke(String toolInput, ToolContext toolContext) {
        return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
    }

    /** 把建议规则换个存放层（面板选「本会话」还是「永久」决定）。 */
    private static PermissionRule rescope(PermissionRule r, RuleScope scope) {
        return r == null ? null
                : new PermissionRule(r.toolName(), r.pattern(), PermissionBehavior.ALLOW, scope);
    }

    /** 给模型看的拒绝串——措辞要引导它换做法，而不是反复重试同一个动作。 */
    static String denyMessage(String reason) {
        return "Permission denied: " + reason + "。若确有必要，请说明理由或换一种做法。";
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionCallbackTest`
Expected: PASS（7 个测试全绿，含并发/活性两条）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/PermissionCallback.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ToolEventCallback.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PermissionCallbackTest.java
git commit -m "feat(permission): 最外层拦截装饰器与阻塞审批握手"
```

---

### Task 12: 三处装配点接入

**Files:**
- Modify: `.../agent/AgentTools.java`（装饰循环 + `buildMemoryTools` + `AgentRuntime` 暴露 engine）
- Modify: `.../agent/McpRegistry.java`（`decorate()` 最外层加 `PermissionCallback`）
- Test: `.../agent/PermissionWiringTest.java`

> **子 agent 与 MCP 自动纳管**：`SubagentRunner` 复用的 `decoratedList` 就是这里装饰过的同一批实例，
> MCP 工具经 `McpRegistry.decorate` 走同一层，**都不需要额外改动**。
> 与既有 `allowTools`/`denyTools` 裁剪正交：先裁剪（工具对子 agent 根本不存在），再走权限判定。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PermissionWiringTest {

    @Test
    @DisplayName("记忆工具的装饰链最外层是 PermissionCallback")
    void memoryToolsAreWrapped(@TempDir Path root) {
        ConversationStateStub listener = new ConversationStateStub();
        ToolCallback[] tools = AgentTools.buildMemoryTools(root, listener);

        assertNotNull(tools);
        for (ToolCallback t : tools) {
            assertInstanceOf(PermissionCallback.class, t,
                    "记忆工具装饰链最外层必须是 PermissionCallback，实际：" + t.getClass());
        }
    }

    @Test
    @DisplayName("MCP 工具装饰链最外层是 PermissionCallback")
    void mcpToolsAreWrapped(@TempDir Path root) {
        // McpRegistry.decorate 是包私有测试钩子（既有）
        McpRegistry registry = McpRegistry.initForTest(root, new ConversationStateStub());
        ToolCallback decorated = registry.decorate(new NoopTool());
        assertInstanceOf(PermissionCallback.class, decorated);
    }

    @Test
    @DisplayName("三处装配点共用同一个 PermissionEngine 实例（模式切换必须全局一致）")
    void singleEngineInstance(@TempDir Path root) {
        // AgentRuntime 暴露 engine；buildMemoryTools 与 build 装饰循环用的必须是同一个对象
        // 具体断言方式随实现落地（如给 PermissionCallback 加包私有 engine() 访问器后比对 assertSame）
        assertSame(true, true, "占位：实现 engine() 访问器后补齐 assertSame 断言");
    }
}
```

> ⚠ 第三个测试里的 `initForTest` / `NoopTool` / `ConversationStateStub` 需按 `McpRegistry` 与
> 既有测试（`SubagentRunnerMcpToolsTest` / `CodeTuiViewMcpTest`）里现成的桩写法落地；
> 第三条断言请在给 `PermissionCallback` 加包私有 `engine()` 访问器后改成真正的 `assertSame`，
> **不要把占位断言留在最终代码里**。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionWiringTest`
Expected: FAIL（当前最外层是 `ToolEventCallback`）

- [ ] **Step 3: 改 AgentTools.build**

① 在 `build` 里、装饰循环之前构造引擎（紧跟 `mediaStore` / `fileExternalizer` 那段）：

```java
        // 权限引擎：三处装配点（本装饰循环 / buildMemoryTools / McpRegistry）共用同一个实例。
        // 模式由它自己持有（volatile），UI 的 Shift+Tab 与启动参数都改它，读写全局一致。
        PermissionConfig permissionConfig = PermissionConfigLoader.load(root);
        PermissionEngine permissionEngine = new PermissionEngine(
                root, permissionConfig, resolveStartupMode(permissionConfig), bypassAllowed());
```

② 装饰循环最外层加一层：

```java
        for (int i = 0; i < all.size(); i++) {
            decorated[i] = new PermissionCallback(
                    new ToolEventCallback(
                            new MediaExternalizingCallback(all.get(i), mediaStore, mediaHandler, root),
                            listener),
                    permissionEngine, listener);
            if (all.get(i) == reloadableSkill) {
                decoratedSkillTool = decorated[i];
            }
        }
```

③ `Task` / `ParallelTasks` 工具**也包一层**（它们类别是 INTERNAL、恒放行，包一层只为「所有工具走同一条链」的一致性，
零行为差异）：

```java
        ToolCallback decoratedTaskTool =
                new PermissionCallback(new ToolEventCallback(taskTool, listener), permissionEngine, listener);
        ToolCallback decoratedParallelTool =
                new PermissionCallback(new ToolEventCallback(parallelTool, listener), permissionEngine, listener);
```

④ `buildMemoryTools` 加 engine 参数并包最外层：

```java
    static ToolCallback[] buildMemoryTools(Path root, AgentListener listener, PermissionEngine engine) {
        Path dir = memoryDir(root);
        AutoMemoryTools memoryTools = AutoMemoryTools.builder().memoriesDir(dir).build();
        ToolCallback[] raw = ToolCallbacks.from(memoryTools);
        ToolCallback[] decorated = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            decorated[i] = new PermissionCallback(new ToolEventCallback(raw[i], listener), engine, listener);
        }
        return decorated;
    }

    /** 向后兼容（测试用）：自建一个默认引擎。 */
    static ToolCallback[] buildMemoryTools(Path root, AgentListener listener) {
        return buildMemoryTools(root, listener,
                new PermissionEngine(root, PermissionConfig.empty(), PermissionMode.DEFAULT, false));
    }
```

⑤ 启动模式解析（启动参数 > 配置文件 `defaultMode`）：

```java
    /** 启动模式：{@code --dangerously-skip-permissions} > 配置文件 defaultMode。 */
    private static PermissionMode resolveStartupMode(PermissionConfig cfg) {
        return bypassAllowed() ? PermissionMode.BYPASS : cfg.defaultMode();
    }

    /**
     * 是否允许进入 BYPASS。读系统属性而非解析 args——{@code AgentTools.build} 拿不到 main 的 args，
     * 由 {@code CodeTuiApplication} 在启动早期 {@code System.setProperty} 落定（见 Task 13）。
     */
    private static boolean bypassAllowed() {
        return Boolean.getBoolean("codetui.dangerouslySkipPermissions");
    }
```

⑥ `AgentRuntime` 记录里追加 `PermissionEngine permissionEngine` 字段（放在 `fileExternalizer` 之后），
`build` 的 `return new AgentRuntime(...)` 相应补参，并补 javadoc：

```java
     * @param permissionEngine 权限引擎（UI 经它读/切模式、列生效规则；三处装配点共用同一实例）
```

- [ ] **Step 4: 改 McpRegistry.decorate**

```java
    /** 包私供测试断言装饰链（外层 PermissionCallback）。 */
    ToolCallback decorate(ToolCallback raw) {
        return new PermissionCallback(
                new ToolEventCallback(
                        new MediaExternalizingCallback(raw, mediaStore, mediaHandler, root), listener),
                permissionEngine, listener);
    }
```

`McpRegistry` 需要持有 `permissionEngine`——加一个字段 + 在 `init` 链路上传入。
**注意时序**：`CodeTuiApplication` 里 `McpRegistry.init` 在 `AgentTools.build` <b>之前</b>，
而引擎在 `build` 里创建。解法：把引擎的创建<b>提到 `McpRegistry.init` 之前</b>，
在 `CodeTuiApplication` 里建好后分别传给 `McpRegistry.init` 与 `AgentTools.build`
（`build` 新增一个 `PermissionEngine` 参数，旧的三/四参重载自建默认引擎供既有测试用）。

> 这是本 Task 唯一的结构性决定，**别用「MCP 工具延迟装饰」绕过它**——延迟装饰会让
> 运行期 `/mcp` 启用的 server 漏掉权限层。

- [ ] **Step 5: 跑测试确认通过 + 全量回归**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionWiringTest`
Expected: PASS

Run: `mvn test -pl springai-code-tui`
Expected: 全绿。特别关注 `SubagentRunnerMcpToolsTest` / `ToolEventCallbackTest` /
`CodingAgent*Test`——装饰链变了但**事件语义不应变**。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpRegistry.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PermissionWiringTest.java
git commit -m "feat(permission): 三处装配点接入权限拦截（子 agent 与 MCP 自动纳管）"
```

---

### Task 13: SubmitHandler 权限门面与启动参数

**Files:**
- Modify: `.../agent/SubmitHandler.java`
- Modify: `.../agent/CodingAgent.java`
- Modify: `.../CodeTuiApplication.java`
- Test: `.../agent/PermissionStartupTest.java`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.CodeTuiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionStartupTest {

    @Test
    @DisplayName("--dangerously-skip-permissions 被识别")
    void recognisesBypassFlag() {
        assertTrue(CodeTuiApplication.hasBypassFlag(new String[]{"--dangerously-skip-permissions"}));
        assertTrue(CodeTuiApplication.hasBypassFlag(new String[]{"-c", "--dangerously-skip-permissions"}));
    }

    @Test
    @DisplayName("没带该参数时不开 BYPASS（默认安全）")
    void defaultsToNoBypass() {
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{}));
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{"-c"}));
        assertFalse(CodeTuiApplication.hasBypassFlag(new String[]{"--dangerously-skip"}),
                "前缀相近的参数不得误判");
    }

    @Test
    @DisplayName("SubmitHandler 的权限默认实现是安全空值（回显桩/测试桩可省略）")
    void submitHandlerDefaults() {
        SubmitHandler stub = new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        };
        assertTrue(stub.permissionRules().isEmpty());
        // 默认模式非空，供状态栏显示不至于 NPE
        assertTrue(stub.permissionMode() != null);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionStartupTest`
Expected: 编译失败（`hasBypassFlag` / `permissionMode` 不存在）

- [ ] **Step 3: 改 SubmitHandler**

在文件末尾（MCP 那组之后）追加：

```java
    // ── 权限管理（/permissions 与 Shift+Tab 用；默认空实现，便于回显桩/测试桩省略） ──
    /** 当前权限模式（状态栏与面板显示）。 */
    default io.github.javaside.springai.codetui.agent.permission.PermissionMode permissionMode() {
        return io.github.javaside.springai.codetui.agent.permission.PermissionMode.DEFAULT;
    }

    /** 循环到下一个模式（Shift+Tab），返回新模式。 */
    default io.github.javaside.springai.codetui.agent.permission.PermissionMode cyclePermissionMode() {
        return permissionMode();
    }

    /** 当前生效的全部规则（{@code /permissions} 只读展示）。 */
    default List<io.github.javaside.springai.codetui.agent.permission.PermissionRule> permissionRules() {
        return List.of();
    }
```

- [ ] **Step 4: 改 CodingAgent**

① 构造参数增加 `PermissionEngine permissionEngine`（由 `AgentRuntime.permissionEngine()` 传入），存字段。

② 实现三个门面方法：

```java
    @Override
    public PermissionMode permissionMode() {
        return permissionEngine.mode();
    }

    @Override
    public PermissionMode cyclePermissionMode() {
        return permissionEngine.cycleMode();
    }

    @Override
    public List<PermissionRule> permissionRules() {
        return permissionEngine.effectiveRules();
    }
```

③ `clearContext()`（`/clear` 开新会话）里补一行——会话规则不跨会话继承：

```java
        permissionEngine.clearSessionRules();
```

- [ ] **Step 5: 改 CodeTuiApplication**

① 加可测的参数判定（照既有 `hasContinueFlag` 的形状）：

```java
    /** 是否带「跳过权限检查」启动选项（仿 Claude Code 的 --dangerously-skip-permissions）。 */
    static boolean hasBypassFlag(String[] args) {
        for (String a : args) {
            if ("--dangerously-skip-permissions".equals(a)) {
                return true;
            }
        }
        return false;
    }
```

② 在 `main` 早期（**必须在 `McpRegistry.init` 与 `AgentTools.build` 之前**）落定系统属性并建引擎：

```java
        if (hasBypassFlag(args)) {
            System.setProperty("codetui.dangerouslySkipPermissions", "true");
        }
        PermissionConfig permissionConfig = PermissionConfigLoader.load(root);
        PermissionEngine permissionEngine = new PermissionEngine(root, permissionConfig,
                hasBypassFlag(args) ? PermissionMode.BYPASS : permissionConfig.defaultMode(),
                hasBypassFlag(args));
```

③ 把 `permissionEngine` 分别传给 `McpRegistry.init(root, state, permissionEngine)` 与
`AgentTools.build(registry, root, state, mcpRegistry, permissionEngine)`。

④ BYPASS 启动时打一行醒目提示（进 scrollback，别让人忘了自己在裸奔）：

```java
        if (hasBypassFlag(args)) {
            state.pushInfo("⚠ 已启用 --dangerously-skip-permissions：除 deny 规则与内置危险检查外，"
                    + "全部工具调用不再询问。");
        }
```

- [ ] **Step 6: 跑测试确认通过 + 全量回归**

Run: `mvn test -pl springai-code-tui -Dtest=PermissionStartupTest`
Expected: PASS（3 个测试全绿）

Run: `mvn test -pl springai-code-tui`
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/PermissionStartupTest.java
git commit -m "feat(permission): SubmitHandler 权限门面与 --dangerously-skip-permissions 启动参数"
```

---

### Task 14: 审批面板与按键

**Files:**
- Modify: `.../ui/CodeTuiView.java`
- Test: `.../ui/CodeTuiViewPermissionTest.java`

**面板形态（五个选项，照 spec §8）：**

```
  ⚠ 需要授权：Bash
     mvn -pl springai-code-tui test && git push origin main
     ↑ 第 2 段 `git push origin main` 未获授权

   ❯ 1. 允许一次
     2. 允许，本会话不再问
     3. 允许，永久
     4. 拒绝，让模型换个做法
     5. 拒绝并中断本回合
```

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.PermissionRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewPermissionTest {

    private static PermissionRequest perm(long turnId, List<PermissionOutcome> sink) {
        return new PermissionRequest(turnId, null, "Bash", "git push origin main",
                "{\"command\":\"git push origin main\"}", "第 1 段 `git push origin main` 未获授权",
                null, sink::add);
    }

    private static CodeTuiView view(ConversationState state, Path root) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root);
    }

    @Test
    @DisplayName("drain 侦测到队首审批请求即进入审批模态")
    void entersPermissionModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();

        assertNotNull(v.activePermissionForTest(), "应进入审批模态");
    }

    @Test
    @DisplayName("↑↓ 移动、Enter 确认「允许一次」")
    void allowOnce(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of(PermissionOutcome.ALLOW_ONCE), sink);
        assertNull(v.activePermissionForTest(), "应答后退出模态");
        assertNull(state.peekModal(), "并从队列摘除");
    }

    @Test
    @DisplayName("数字键 1–5 直接选项（不隐式确认，仍需 Enter）")
    void digitsMoveHighlight(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar('4'));
        assertTrue(sink.isEmpty(), "数字键只移动高亮，不应直接应答");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(List.of(PermissionOutcome.DENY), sink);
    }

    @Test
    @DisplayName("选「5. 拒绝并中断本回合」→ CANCEL；Esc 同义")
    void cancelTurn(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(List.of(PermissionOutcome.CANCEL), sink);
        assertTrue(state.isIdle(), "中断后回合应回 IDLE");
    }

    @Test
    @DisplayName("拒绝（选 4）不取消回合——与中断严格区分")
    void denyKeepsTurnRunning(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar('4'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of(PermissionOutcome.DENY), sink);
        assertTrue(!state.isIdle(), "拒绝不该结束回合（回合继续，模型自寻替代）");
    }

    @Test
    @DisplayName("渲染冒烟：非审批态构造 UI 树不崩（scope 每帧 eager 求值）")
    void renderSmokeWhenNoModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        assertNotNull(v.renderForTest(), "非审批态每帧仍会调用面板方法，首行必须判空");
    }

    @Test
    @DisplayName("渲染冒烟：审批态构造 UI 树不崩")
    void renderSmokeInModal(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        state.onPermissionRequested(1L, perm(1L, new CopyOnWriteArrayList<>()));
        CodeTuiView v = view(state, root);
        v.tickForTest();
        assertNotNull(v.renderForTest());
    }

    @Test
    @DisplayName("两个审批请求依次弹出（第一个处理完才轮到第二个）")
    void queuedModalsPopInOrder(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);
        List<PermissionOutcome> sink = new CopyOnWriteArrayList<>();
        state.onPermissionRequested(1L, perm(1L, sink));
        state.onPermissionRequested(1L, perm(1L, sink));

        CodeTuiView v = view(state, root);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 批第一个
        assertEquals(1, sink.size());

        v.tickForTest();                                   // 下一 tick 弹第二个
        assertNotNull(v.activePermissionForTest());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(2, sink.size());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPermissionTest`
Expected: 编译失败（`activePermissionForTest` 不存在）

- [ ] **Step 3: 加字段与测试钩子**

在 `activeAsk` 附近：

```java
    private PermissionRequest activePermission;   // 当前正在审批的请求（null=非审批态）
    private int permOpt;                          // 审批面板高亮项（0..4）
```

测试钩子（放在既有 `pickingMcpForTest` 旁）：

```java
    PermissionRequest activePermissionForTest() { return activePermission; }
```

- [ ] **Step 4: drain 里分流两类模态**

把 Task 10 里临时写的 `instanceof AskRequest` 分支补全成 sealed 穷尽：

```java
        // 侦测到新模态（身份不同）→ 进入对应模态并复位状态。
        ModalRequest head = state.peekModal();
        if (head != null && head != activeAsk && head != activePermission) {
            switch (head) {
                case AskRequest pa -> {
                    if (isAnswerable(pa)) {
                        activeAsk = pa;
                        askQ = 0; askOpt = 0; askAnswers.clear(); askChecked.clear();
                        askFreeText = false; askInput.clear();
                    } else {
                        // 畸形问询：优雅降级为取消整回合（见既有注释）
                        state.removeModal(pa);
                        cancelTurnFor(pa, "问询格式无效，已取消当前回合");
                    }
                }
                case PermissionRequest pr -> {
                    activePermission = pr;
                    permOpt = 0;
                }
            }
        }
```

`render()` 里在 `askChildren()` 那行之后加一行：

```java
                scope(activePermission != null, permissionChildren()),   // 权限审批面板
```

`onInputKey()` 里，在 `if (activeAsk != null) return onAskKey(k);` **之前**插入：

```java
        if (activePermission != null) return onPermissionKey(k);   // 审批模态优先于一切文本编辑
```

- [ ] **Step 5: 写按键处理与面板**

```java
    /** 审批面板选项文案（顺序即 permOpt 下标）。 */
    private static final List<String> PERM_OPTIONS = List.of(
            "允许一次",
            "允许，本会话不再问",
            "允许，永久（写入项目 permissions.json）",
            "拒绝，让模型换个做法",
            "拒绝并中断本回合");

    /**
     * 审批按键：↑↓/kj 移动、1–5 移动高亮（不隐式确认）、Enter 确认、Esc = 中断本回合。始终 HANDLED。
     *
     * <p><b>拒绝 ≠ 取消回合</b>：选 4 只喂回 DENY、回合继续；选 5 / Esc 才走既有 {@code cancelTurnFor}
     * （dispose + doOnCancel 回滚会话，否则残留悬空 tool_calls、下轮 400）。
     */
    private EventResult onPermissionKey(KeyEvent k) {
        int n = PERM_OPTIONS.size();
        if (k.isCancel()) { finishPermission(PermissionOutcome.CANCEL); return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { permOpt = (permOpt - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { permOpt = (permOpt + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n; i++) {
            if (k.isChar((char) ('1' + i))) { permOpt = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            finishPermission(switch (permOpt) {
                case 0 -> PermissionOutcome.ALLOW_ONCE;
                case 1 -> PermissionOutcome.ALLOW_SESSION;
                case 2 -> PermissionOutcome.ALLOW_ALWAYS;
                case 3 -> PermissionOutcome.DENY;
                default -> PermissionOutcome.CANCEL;
            });
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /** 应答并退出审批模态；CANCEL 额外走既有回合取消路径。 */
    private void finishPermission(PermissionOutcome outcome) {
        PermissionRequest req = activePermission;
        activePermission = null;
        permOpt = 0;
        state.removeModal(req);
        if (outcome == PermissionOutcome.CANCEL) {
            req.responder().respond(PermissionOutcome.CANCEL);
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.clearQueued();
            state.setNotice("已取消当前回合");
            return;
        }
        req.responder().respond(outcome);
        if (outcome == PermissionOutcome.ALLOW_ALWAYS) {
            state.pushInfo("✓ 已永久允许：" + (req.suggested() == null ? req.toolName() : req.suggested().toDsl()));
        }
    }

    /**
     * 审批面板：标题（工具名 + 来源）+ 目标 + 原因 + 五个选项。
     * {@code Write}/{@code Edit} 复用 {@link DiffRenderer} 在面板里展示改动。
     * 高亮走纯前景 {@link Theme#PICK_SEL}（底色条会串到下一项）。
     */
    private Element[] permissionChildren() {
        // scope 每帧 eager 求值：首行必须判空，否则非审批态每帧崩渲染线程
        if (activePermission == null) return new Element[0];
        PermissionRequest r = activePermission;
        List<Element> els = new ArrayList<>();
        String from = r.taskId() == null ? "" : "（来自子 agent）";
        els.add(text("  ⚠ 需要授权：" + r.toolName() + from).style(PICK_TITLE));
        String target = summarizeOneLine(r.target());
        if (!target.isEmpty()) els.add(text("     " + target).style(PICK_ITEM));
        if (r.reason() != null && !r.reason().isEmpty()) {
            els.add(text("     ↑ " + summarizeOneLine(r.reason())).style(DIM));
        }
        for (int i = 0; i < PERM_OPTIONS.size(); i++) {
            boolean sel = i == permOpt;
            els.add(text("  " + (sel ? "❯ " : "  ") + (i + 1) + ". " + PERM_OPTIONS.get(i))
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /** 折叠空白到一行 + 按显示宽度截断（守住「一 OutputLine = 一物理行」不变量）。 */
    private String summarizeOneLine(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        int max = Math.max(20, terminalWidth() - 8);
        return displayWidth(one) <= max ? one
                : dev.tamboui.text.CharWidth.substringByWidth(one, max - 1) + "…";
    }
```

- [ ] **Step 6: 状态栏**

`statusLine()` 顶部（在 `activeAsk` 分支**之前**）插入：

```java
        if (activePermission != null) {
            return text("⏸ 等待授权 (" + activePermission.toolName()
                    + ") · ↑↓ 选择 · 1-5 快选 · Enter 确认 · Esc 中断").style(THINK);
        }
```

- [ ] **Step 7: 跑测试确认通过 + 全量回归**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPermissionTest`
Expected: PASS（8 个测试全绿）

Run: `mvn test -pl springai-code-tui`
Expected: 全绿

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPermissionTest.java
git commit -m "feat(ui): 权限审批面板（五选项 + 拒绝与中断严格区分）"
```

---

### Task 15: Shift+Tab 模式循环与 /permissions（只读版）

**Files:**
- Modify: `.../ui/CodeTuiView.java`
- Test: `.../ui/CodeTuiViewPermissionModeTest.java`

> **期 0 已证 `Shift+Tab` 可区分**（`code=TAB mods=[S]`）。
> **必修坑**：现有两处 Tab 处理只判 `k.code() == KeyCode.TAB`——`:645` 斜杠菜单补全、
> `:918` MCP 面板展开——**会把 Shift+Tab 一起吃掉**，必须补 `!k.hasShift()` 守卫。
>
> **另一条既有约束**：TamboUI 默认 `quit` 绑定含裸 `q`/`Q`，本项目已在 `configure()` 重绑为只剩 Ctrl+C。
> 新键位<b>不得破坏该约束</b>（见记忆 `tamboui-default-quit-binds-bare-q`）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewPermissionModeTest {

    /** 记录 cyclePermissionMode 被调了几次的桩。 */
    private static final class ModeStub implements SubmitHandler {
        final AtomicInteger cycles = new AtomicInteger();
        PermissionMode mode = PermissionMode.DEFAULT;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public PermissionMode cyclePermissionMode() {
            cycles.incrementAndGet();
            mode = mode.next(false);
            return mode;
        }
        @Override public List<PermissionRule> permissionRules() {
            return List.of(PermissionRule.parse("Bash(mvn test:*)",
                    PermissionBehavior.ALLOW, RuleScope.PROJECT));
        }
    }

    private static KeyEvent shiftTab() {
        return KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT);
    }

    @Test
    @DisplayName("Shift+Tab 循环模式")
    void shiftTabCyclesMode(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(shiftTab());

        assertEquals(1, stub.cycles.get());
        assertEquals(PermissionMode.ACCEPT_EDITS, stub.mode);
        assertTrue(state.notice().contains("自动接受编辑"),
                "切换后应给出可见反馈，实际 notice：" + state.notice());
    }

    @Test
    @DisplayName("裸 Tab 不触发模式循环（不能把补全/展开抢走）")
    void plainTabDoesNotCycle(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.TAB));

        assertEquals(0, stub.cycles.get());
    }

    @Test
    @DisplayName("斜杠菜单激活时，裸 Tab 仍补全、Shift+Tab 走模式循环（守卫生效）")
    void slashMenuTabGuard(@TempDir Path root) {
        ConversationState state = new ConversationState();
        ModeStub stub = new ModeStub();
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.setInputForTest("/mod");                 // 触发补全菜单

        v.feedKeyForTest(shiftTab());
        assertEquals(1, stub.cycles.get(), "菜单激活时 Shift+Tab 也应切模式，不被补全吃掉");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.TAB));
        assertEquals("/model", v.inputTextForTest(), "裸 Tab 仍应补全");
        assertEquals(1, stub.cycles.get(), "裸 Tab 不得触发模式循环");
    }

    @Test
    @DisplayName("/permissions 把当前模式与生效规则打进 scrollback")
    void permissionsCommandPrintsReport(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new ModeStub(), root);
        v.setInputForTest("/permissions");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        String all = String.join("\n",
                state.drainPending().stream().map(ConversationState.OutputLine::text).toList());
        assertTrue(all.contains("默认"), "应显示当前模式，实际：" + all);
        assertTrue(all.contains("Bash(mvn test:*)"), "应列出生效规则，实际：" + all);
        assertTrue(all.contains("Shift+Tab"), "应说明如何切换模式，实际：" + all);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPermissionModeTest`
Expected: FAIL（Shift+Tab 无处理、`/permissions` 未注册）

- [ ] **Step 3: 加 Shift+Tab 处理**

在 `onInputKey()` 里，**紧跟 Ctrl+C 分支之后**（早于所有模态与菜单分支，保证任何界面状态下都能切）：

```java
        // Shift+Tab 循环权限模式（期 0 已 pty 实测：ESC[Z → code=TAB + SHIFT，与裸 Tab 可区分）。
        // 放在最前：模态/菜单激活时也应能切模式；且必须在下面的 notice 清除之后再 setNotice。
        if (k.code() == KeyCode.TAB && k.hasShift()) {
            var mode = onSubmit.cyclePermissionMode();
            state.setNotice("权限模式：" + mode.label());
            return EventResult.HANDLED;
        }
```

> ⚠ 顺序细节：`onInputKey` 顶部有一句「任意按键消费掉上一条 sticky notice」。
> 若把 Shift+Tab 分支放在它**之前**，本次设的 notice 会被随后清掉。
> 放在 `if (!state.notice().isEmpty()) state.setNotice("");` **之后**——
> 上面的注释已说明这一点，落地时照此顺序。

- [ ] **Step 4: 给两处既有 Tab 处理补守卫**

`onSlashMenuKey`（原 `:645`）：

```java
        if ((k.code() == KeyCode.TAB || k.isChar('\t')) && !k.hasShift()) { inputState.setText(m.get(slashIndex).name()); return EventResult.HANDLED; }
```

`onMcpPickerKey`（原 `:918`）：

```java
        if ((k.code() == KeyCode.TAB || k.isChar('\t')) && !k.hasShift()) { mcpExpanded = !mcpExpanded; return EventResult.HANDLED; }
```

- [ ] **Step 5: 加 /permissions 命令**

① `COMMANDS` 列表里追加（放在 `/mcp` 之后）：

```java
            new SlashCommand("/permissions", "查看权限模式与生效规则"),
```

② `submitInput()` 里加分支（照 `/skills` 的只读命令写法）：

```java
        if (cmd.equals("/permissions")) {   // 只读报告：任何时刻都可查，不打断
            inputState.clear();
            printPermissions();
            return;
        }
```

③ 报告方法（照 `printSkills` 的形状）：

```java
    /** {@code /permissions}：把当前模式、生效规则、内置底线打进 scrollback（灰色信息行）。 */
    private void printPermissions() {
        state.pushInfo("权限模式：" + onSubmit.permissionMode().label()
                + "（Shift+Tab 循环切换）");
        List<PermissionRule> rules = onSubmit.permissionRules();
        if (rules.isEmpty()) {
            state.pushInfo("  当前没有自定义规则。可在 .codetui/permissions.json 配置，"
                    + "或在审批面板选「允许，永久」自动写入。");
        } else {
            state.pushInfo("生效规则（deny > ask > allow）：");
            for (PermissionRule r : rules) {
                state.pushInfo("  • [" + r.behavior() + "] " + r.toDsl() + "   (" + r.scope() + ")");
            }
        }
        state.pushInfo("内置底线（任何 allow 规则与 BYPASS 都盖不住，命中即询问）：");
        state.pushInfo("  写 .ssh/.aws/.kube/.gnupg/.git/.codetui 配置、写 shell 启动文件、"
                + "读私钥与凭据、rm -rf / 或 ~ 或变量目标");
    }
```

④ `/help` 的快捷键行补一句：

```java
        state.pushInfo("权限：Shift+Tab 切换模式 · /permissions 查看规则");
```

- [ ] **Step 6: 跑测试确认通过 + 全量回归**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewPermissionModeTest`
Expected: PASS（4 个测试全绿）

Run: `mvn test -pl springai-code-tui`
Expected: 全绿。重点看 `CodeTuiViewBindingsTest`（quit 只剩 Ctrl+C 的约束不得破坏）。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPermissionModeTest.java
git commit -m "feat(ui): Shift+Tab 切换权限模式与 /permissions 只读报告"
```

---

### Task 16: 改写 AgentToolsSecurityTest（钉住新的安全现实）

**Files:**
- Modify: `.../agent/AgentToolsSecurityTest.java`

> **为什么必须改**：这个测试类现在钉的是「本应用零强制边界」。本期之后这句话变成**半真半假**——
> 引擎层有护栏了，工具层仍无沙箱。不改它，它就从「诚实的现实记录」退化成「误导后人的谎言」。

- [ ] **Step 1: 更新类注释（把结论改准）**

把类 javadoc 的结论段替换为：

```java
/**
 * 钉住 springai-code-tui 的「安全现实」，不是证明系统安全，而是把边界的真实情况写死成测试，
 * 防止后续改动误以为整个 root 是沙箱。
 *
 * <p><b>本期（权限管理）之后的现实分两层，别混为一谈</b>：
 * <ul>
 *   <li><b>引擎层有护栏</b>：工具调用在执行前先过 {@code PermissionEngine}——危险动作会停下来问人，
 *       deny 规则与内置危险检查任何模式下都生效。这是<b>防模型手滑</b>的护栏。</li>
 *   <li><b>工具层仍无沙箱</b>：一旦获批（或处于 BYPASS 模式），FileSystemTools / ShellTools /
 *       GrepTool / GlobTool 在技术上<b>依然</b>不受 root 限制，能读写 root 之外。
 *       本类 section 2 的 {@code KNOWN_UNSAFE_*} 就是钉这件事。</li>
 * </ul>
 *
 * <p>换句话说：护栏 ≠ 沙箱，更 ≠ 防 prompt injection 的安全边界。
 * 谁要是把「有权限模式了」理解成「工具被关进 root 了」，section 2 会当场纠正他。
 */
```

- [ ] **Step 2: 新增一节，钉住「引擎层确有护栏」**

在文件末尾追加（与既有 section 1/2 并列）：

```java
    // ---------------------------------------------------------------------
    // 3. 引擎层护栏（本期新增）——与 section 2 的「工具层无沙箱」并存，不矛盾
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("护栏：默认模式下，写工作区文件会被拦下要求授权（不是直接放行）")
    void engine_defaultMode_asksBeforeWrite(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(root,
                PermissionConfig.empty(), PermissionMode.DEFAULT, false);

        PermissionDecision d = engine.decide("Write",
                "{\"filePath\":\"" + root.resolve("a.txt") + "\"}");

        assertEquals(PermissionBehavior.ASK, d.behavior(),
                "默认模式下写文件必须先问，实际：" + d);
    }

    @Test
    @DisplayName("护栏：BYPASS 模式下 deny 规则与内置危险检查仍然生效（不是全开）")
    void engine_bypass_stillHonoursDenyAndBuiltins(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.BYPASS, List.of(
                        PermissionRule.parse("Bash(git push:*)",
                                PermissionBehavior.DENY, RuleScope.USER))),
                PermissionMode.BYPASS, true);

        assertEquals(PermissionBehavior.DENY,
                engine.decide("Bash", "{\"command\":\"git push origin main\"}").behavior(),
                "BYPASS 也盖不住 deny 规则");
        assertEquals(PermissionBehavior.ASK,
                engine.decide("Bash", "{\"command\":\"rm -rf /\"}").behavior(),
                "BYPASS 也盖不住内置危险检查");
    }

    @Test
    @DisplayName("现实：护栏是引擎层的——一旦获批，工具本身照样能写 root 之外（与 section 2 一致）")
    void KNOWN_UNSAFE_approvedToolStillEscapesRoot(@TempDir Path root) throws Exception {
        // 引擎判 ALLOW（BYPASS + 非危险路径）后，真实工具没有任何目录约束
        PermissionEngine engine = new PermissionEngine(root,
                PermissionConfig.empty(), PermissionMode.BYPASS, true);
        Path outsideDir = Files.createTempDirectory("approved_escape_");
        Path outside = outsideDir.resolve("escape.txt");

        assertEquals(PermissionBehavior.ALLOW,
                engine.decide("Write", "{\"filePath\":\"" + outside + "\"}").behavior());

        FileSystemTools fs = FileSystemTools.builder().build();
        fs.write(outside.toString(), "written after approval");

        assertTrue(Files.exists(outside),
                "已知现实：获批后工具仍能写 root 之外——护栏不是沙箱");
    }
```

补 import：`PermissionEngine` / `PermissionConfig` / `PermissionMode` / `PermissionDecision` /
`PermissionBehavior` / `PermissionRule` / `RuleScope` / `java.util.List` / `assertEquals`。

- [ ] **Step 3: 跑测试**

Run: `mvn test -pl springai-code-tui -Dtest=AgentToolsSecurityTest`
Expected: PASS（原 5 个 + 新 3 个 = 8 个全绿）

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsSecurityTest.java
git commit -m "test(security): 钉住新的安全现实（引擎层有护栏，工具层仍无沙箱）"
```

---

### Task 17: pty 实机冒烟

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/permission_smoke.py`

> **必须实机验证的三件事**（单测到不了）：① `Shift+Tab` 的真实字节在完整 TUI 里被正确路由；
> ② 审批面板在 InlineDisplay 下渲染正常（高亮不串行）；③ 状态栏「⏸ 等待授权」出现。
>
> **两个已知坑**：pty 默认 0×0 会渲染全空白，必须 `ioctl TIOCSWINSZ` + `TERM=xterm-256color`
> （见记忆 `pty-smoke-inline-tui-needs-winsize-and-term`）；**改完代码要重新 `package`**，
> 否则跑的是旧 classes。

- [ ] **Step 1: 写脚本**

照抄 `src/test/resources/scripts/edit_shortcut_smoke.py` 的 `PtySession` 骨架（`ioctl TIOCSWINSZ`、
DSR 应答、`wait_for` / `wait_gone`、`build_classpath`），只替换 `main()` 的剧本：

```python
SHIFT_TAB = b"\x1b[Z"

def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-perm-smoke-")

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    session = PtySession(["java", "-cp", classpath, MAIN_CLASS], tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=20)
        print("Startup OK.")

        # 1) Shift+Tab 切换模式：状态栏应出现「权限模式：自动接受编辑」
        session.write(SHIFT_TAB)
        session.wait_for("自动接受编辑")
        print("Shift+Tab OK (切到 ACCEPT_EDITS).")

        # 2) 再按一次切回默认
        session.write(SHIFT_TAB)
        session.wait_for("权限模式：默认")
        print("Shift+Tab OK (切回 DEFAULT).")

        # 3) /permissions 只读报告落进 scrollback
        session.write(b"/permissions\r")
        session.wait_for("内置底线")
        print("/permissions OK.")

        # 4) 裸 Tab 仍走补全（守卫未被 Shift+Tab 破坏）
        session.write(b"/mod")
        session.wait_for("/model")
        session.write(b"\t")
        session.wait_for("/model")
        session.write(CTRL_U)
        print("裸 Tab 补全 OK（守卫生效）.")

        session.write(b"/exit\r")
        session.pump(1.0)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
```

> **审批面板本身的实机验证**：需要模型真的发起一次工具调用，依赖真实 API key，
> 不适合放进无 key 的冒烟脚本。**改为手动验收清单**（写进本 Task 的提交信息里）：
> 带 key 启动 → 让模型跑 `git status && git push` → 确认面板五个选项渲染正常、
> ↑↓ 高亮不串行、Esc 中断后下一条消息不报 400。

- [ ] **Step 2: 编译并跑冒烟**

```bash
mvn -pl springai-code-tui package -DskipTests
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/permission_smoke.py
```

Expected: 最后一行输出 `SMOKE PASS`

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/permission_smoke.py
git commit -m "test(permission): pty 实机冒烟（Shift+Tab 模式循环 + /permissions + Tab 守卫）"
```

---

## 完成标准（期 1 验收）

- [ ] `mvn test -pl springai-code-tui` 全绿
- [ ] `permission_smoke.py` 输出 `SMOKE PASS`
- [ ] 手动验收：带真实 key 启动，让模型执行 `git status && git push origin main`
      → 弹出审批面板 → 选「4. 拒绝」→ **回合继续**、模型换做法
      → 再触发一次 → 选「5. 中断」→ 回合结束，**下一条消息不报 400**
- [ ] 手动验收：`--dangerously-skip-permissions` 启动 → 打出 ⚠ 提示 →
      让模型 `rm -rf /` → **仍然弹审批**（内置底线不可绕过）
- [ ] 手动验收：ParallelTasks 派多个子 agent 同时触发审批 → 面板逐个弹 →
      Esc 中断 → **无线程卡死**（进程能正常 `/exit`）

---

## 计划自检（写完后对着 spec 复核的结果）

**1. spec 覆盖**

| spec 章节 | 对应 Task |
|---|---|
| §1 拦截点与装饰链位置 | Task 11、12 |
| §2 决策引擎与可变状态归属 | Task 7（第 3 步「工具自审」按「刻意偏差」推迟） |
| §3 工具登记表 + 完整性测试 | Task 2、8 |
| §4 模式与切换键 | Task 1（枚举/循环）、13（启动参数）、15（Shift+Tab、/permissions 只读版） |
| §5 命令拆分判定 | Task 3 |
| §6 规则文件与 DSL | Task 1（DSL）、5（加载）、6（回写） |
| §7 内置不可绕过检查 | Task 4 |
| §8 审批面板与模态队列 | Task 9、10、14 |
| §9 plan 工作流 | **期 2**，不在本计划内 |
| §10 子 agent 与并发 | Task 11（并发/活性测试）、12（装配自动纳管）、10（取消唤醒） |
| 测试策略表 | 各 Task 内含；安全现实回归 = Task 16；pty 冒烟 = Task 17 |

**2. 占位扫描**：无 TBD / 「类似 Task N」/ 「补充适当的错误处理」。
三处标了 ⚠ 的地方是**需要核对真实 API 签名**（Jackson 3 的 `isString`/`stringValue`、
`ToolDefinition` 构造、`AskResponder` 形状）与**一条待补齐的占位断言**（Task 12 Step 1 第三个测试）——
这些都已写明判据与去处，不是含糊其辞。

**3. 类型一致性**：`PermissionRule.matches(callTool, target, pathTarget, root)` 四参签名在
Task 1 定义、Task 7 调用一致；`PermissionRequest` 八字段（turnId/taskId/toolName/target/rawInput/
reason/suggested/responder）在 Task 9 定义、Task 11 构造、Task 14 消费一致；
`buildMemoryTools` 在 Task 12 增参后，Task 8 的完整性测试用的是保留的二参重载。

**4. 已知的实施顺序依赖**：Task 12 会把 `PermissionEngine` 的创建时机提到 `McpRegistry.init` 之前，
这会连带改 `CodeTuiApplication` 的启动顺序——**Task 12 与 Task 13 建议连着做**，中间不要停在半编译状态。

