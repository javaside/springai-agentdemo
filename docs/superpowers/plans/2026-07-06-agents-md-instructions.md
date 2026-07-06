# AGENTS.md 项目指令加载 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 增加 CLAUDE.md/AGENTS.md 式的**人手写项目指令文件**加载：启动时读取用户级 + 项目级 `AGENTS.md`，拼接后注入主 agent 与子 agent 的系统提示，使 agent 遵循团队约定（构建/测试命令、代码风格、架构约定）。

**Architecture:** 新增 `ProjectInstructions` 工具类加载分层 `AGENTS.md`；主 agent 走「`{PROJECT_INSTRUCTIONS}` 占位符 + param 值注入」（与既有 `AUTO_MEMORY` 接缝同构，ST-safe）；子 agent 走 `SubagentRunner` 里对 `spec.systemPrompt()` 的纯字符串追加。启动时加载一次。这是**人手写、提交入库、只读注入**的「instructions」，与已有的 `AutoMemoryTools`（agent 自写、机器本地的「auto memory」）正交互补。

**Tech Stack:** Java 17, Spring AI 2.0, JUnit 5（本模块**无 AssertJ**，用 `org.junit.jupiter.api.Assertions`），Maven（模块作用域 `-pl springai-code-tui`）。

---

## 背景与已定设计（实现前必读）

评估阶段与用户敲定的设计点，直接约束实现：

1. **文件名 `AGENTS.md`**（跨工具生态标准；用户项目里已有的 AGENTS.md 直接被读到）。
2. **两层**（无本地层、无 monorepo 子目录）：
   - 用户级 `~/.codetui/AGENTS.md`（跨项目个人指令）
   - 项目级 `<root>/AGENTS.md`（团队共享、提交入库）
3. **加载顺序 user → project**（项目级更具体、后读、优先级更高，与 Claude Code 载入序一致）。
4. **注入为 param 值,不是模板文字**：主 agent 系统提示走 Spring AI 的 ST 引擎（`.system(s -> s.text(SYSTEM_TEMPLATE).param(k,v))`）。AGENTS.md 内容里常有 `{...}`（代码片段、占位符样例），**绝不能**拼进 `.text()`——会炸 ST。必须作为一个 param 的**值**注入（ST 不递归解析注入值）。这与既有 `AUTO_MEMORY_KEY` 完全同构。
5. **主 agent 与子 agent 都注入**：AGENTS.md 语义是「编辑文件的 agent 遵循约定」，code-tui 子 agent（bash/general-purpose）会真编辑文件，故也须看到。子 agent 有自己的 `spec.systemPrompt()`，注入方式是**纯字符串追加**（非 ST，无花括号问题）。
6. **启动时加载一次**（改 AGENTS.md 需重启生效——Claude Code 亦然，可接受）。live-reload 列后续。
7. **缺失/空白文件 → 优雅跳过**；两层都无 → `load` 返回 `""`，注入段为空、无操作。绝大多数项目初始就没有 AGENTS.md，**这条是首要正确性要求**。
8. **v1 明确不做**（YAGNI）：`@import`、`.claude/rules/` 路径域规则、`AGENTS.local.md` 本地层、monorepo 就近加载、`/init` 生成、`#` 快捷追加、HTML 注释剥离。
9. **验证命令必须模块作用域**：`mvn -q -pl springai-code-tui test`。**禁止**整仓 `mvn test`（空模块打挂）。
10. **测试基建**（照抄现有）：registry = `new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")))`；listener = `new io.github.javaside.springai.codetui.ui.ConversationState()`（implements `AgentListener`）；覆盖 `~` 用 `System.setProperty("user.home", fakeHome.toString())` 并在 finally 复原（见 `AgentRuntimeTest.build_alwaysRegistersReloadableSkillTool_evenWhenNoSkills`）。

---

## File Structure

- **Create** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ProjectInstructions.java`
  职责：加载 `~/.codetui/AGENTS.md` + `<root>/AGENTS.md`，拼成一段带来源标签与优先级说明的指令文本；都无则返回 `""`。单一职责、可独立单测。

- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  - `SYSTEM_TEMPLATE` 加 `{PROJECT_INSTRUCTIONS}` 占位符段。
  - 加 `PROJECT_INSTRUCTIONS_KEY` 常量。
  - `build()` 内渲染一次 `ProjectInstructions.load(root)`，注入主 agent `.param(...)`，并把同一字符串传给 `SubagentRunner`。

- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`
  - 构造函数增 `String projectInstructions` 字段。
  - 加 package-private `effectiveSystemPrompt(SubagentSpec)`（纯函数：空→仅 spec 提示；非空→追加），`run()` 用它替换裸 `spec.systemPrompt()`。

- **Create** 测试：
  - `ProjectInstructionsTest.java`
  - `AgentToolsProjectInstructionsTest.java`（build 注入路径 ST-safe，不崩）
  - `SubagentInstructionsTest.java`（`effectiveSystemPrompt` 组合）

- **Modify** `springai-code-tui/README.md`（能力清单 + 一节说明）。

---

### Task 1: ProjectInstructions 加载器

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ProjectInstructions.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProjectInstructionsTest.java`

- [ ] **Step 1: 写失败测试**

`ProjectInstructionsTest.java`：
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两层 AGENTS.md（用户 ~/.codetui/AGENTS.md + 项目 <root>/AGENTS.md）加载与拼接。 */
class ProjectInstructionsTest {

    /** 在临时 fakeHome 下写 ~/.codetui/AGENTS.md，隔离真实 home。 */
    private static void writeUserAgents(Path fakeHome, String content) throws Exception {
        Path p = fakeHome.resolve(".codetui").resolve("AGENTS.md");
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String loadWithHome(Path root, Path fakeHome) {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            return ProjectInstructions.load(root);
        } finally {
            if (prev == null) System.clearProperty("user.home");
            else System.setProperty("user.home", prev);
        }
    }

    @Test
    void bothLayersMissing_returnsEmpty(@TempDir Path root, @TempDir Path fakeHome) {
        assertEquals("", loadWithHome(root, fakeHome), "无任何 AGENTS.md 时应返回空串（注入段为空、无操作）");
    }

    @Test
    void projectOnly_included(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "用 2 空格缩进。提交前跑 mvn test。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("用 2 空格缩进"), "应含项目级内容");
        assertTrue(out.contains("project"), "应带项目级来源标签");
    }

    @Test
    void userOnly_included(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        writeUserAgents(fakeHome, "我偏好函数式风格。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("我偏好函数式风格"), "应含用户级内容");
        assertTrue(out.contains("user"), "应带用户级来源标签");
    }

    @Test
    void bothLayers_userBeforeProject(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        writeUserAgents(fakeHome, "USER_MARKER_文本");
        Files.writeString(root.resolve("AGENTS.md"), "PROJECT_MARKER_文本");
        String out = loadWithHome(root, fakeHome);
        int u = out.indexOf("USER_MARKER_文本");
        int p = out.indexOf("PROJECT_MARKER_文本");
        assertTrue(u >= 0 && p >= 0, "两层内容都应在");
        assertTrue(u < p, "用户级应在项目级之前（项目级后读、优先级更高）");
    }

    @Test
    void blankFile_skipped(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "   \n\t\n ");   // 仅空白
        assertEquals("", loadWithHome(root, fakeHome), "空白文件应视同不存在");
    }

    @Test
    void bracesInContent_passThroughVerbatim(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        // AGENTS.md 常含代码片段/占位符 —— load 不做模板渲染，原样保留（注入时作 param 值，ST 不解析）
        Files.writeString(root.resolve("AGENTS.md"), "示例：Map.of(\"k\", v) 与 {placeholder} 与 {{double}}。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("{placeholder}"), "单花括号应原样保留");
        assertTrue(out.contains("{{double}}"), "双花括号应原样保留");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=ProjectInstructionsTest`
Expected: 编译失败——`ProjectInstructions` 不存在。

- [ ] **Step 3: 写实现**

`ProjectInstructions.java`：
```java
package io.github.javaside.springai.codetui.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 加载人手写的项目指令文件 {@code AGENTS.md}（CLAUDE.md / AGENTS.md 生态标准），注入 agent 系统提示。
 *
 * <p><b>两层</b>（load 顺序 broad→specific）：用户级 {@code ~/.codetui/AGENTS.md}（跨项目个人约定）→
 * 项目级 {@code <root>/AGENTS.md}（团队共享、提交入库）。项目级后读、优先级更高。任一缺失/空白则跳过；
 * 两层皆无返回 {@code ""}（注入段为空、无操作——绝大多数项目初始没有 AGENTS.md）。
 *
 * <p><b>只读、不渲染</b>：本类只把文件内容原样拼接并加来源标签，<b>不</b>做任何模板替换。AGENTS.md 常含
 * {@code {...}} 代码片段/占位符；调用方（{@code AgentTools}）把整段作为一个 <b>param 值</b>注入主系统模板，
 * Spring AI 的 ST 引擎不递归解析注入值，故花括号安全（与 {@code AUTO_MEMORY} 同构）。子 agent 侧走纯字符串
 * 追加，同样不经 ST。
 *
 * <p>这是<b>人手写、提交入库、只读注入</b>的「instructions」，与 {@code AutoMemoryTools}（agent 自写、
 * 机器本地的「auto memory」）正交互补。
 */
public final class ProjectInstructions {

    /** 项目级文件名（生态标准，位于项目根）。 */
    static final String PROJECT_FILE = "AGENTS.md";

    /** 用户级相对 home 的路径（与 Skills 用户层同处 ~/.codetui 下）。 */
    static final String USER_RELATIVE = ".codetui/AGENTS.md";

    private ProjectInstructions() {
    }

    /**
     * 读取用户级 + 项目级 {@code AGENTS.md} 并拼成一段可注入的指令文本。
     *
     * @param root 项目根目录（项目级文件位于 {@code root/AGENTS.md}）
     * @return 拼好的指令段；两层皆缺/空白时为 {@code ""}
     */
    public static String load(Path root) {
        StringBuilder body = new StringBuilder();
        appendIfPresent(body, userAgentsPath(), "user_instructions", "个人级（跨项目，来自 ~/.codetui/AGENTS.md）");
        appendIfPresent(body, root.resolve(PROJECT_FILE), "project_instructions", "项目级（本仓库，来自 AGENTS.md）");
        if (body.length() == 0) {
            return "";
        }
        return "以下是你需要遵循的项目 / 个人指令（AGENTS.md）。请当作项目约定认真遵守；"
                + "两段冲突时以更具体的「项目级」为准；用户在当前对话中的明确要求高于一切。\n\n"
                + body;
    }

    /** 用户级 AGENTS.md 的绝对路径（每次读实时取 user.home，便于测试用 fakeHome 隔离）。 */
    private static Path userAgentsPath() {
        return Path.of(System.getProperty("user.home")).resolve(USER_RELATIVE);
    }

    /** 若文件存在且非空白，读出并以 {@code <tag>} 包裹追加（带来源说明行）。 */
    private static void appendIfPresent(StringBuilder body, Path file, String tag, String sourceLabel) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取项目指令文件失败：" + file, e);
        }
        if (content.isBlank()) {
            return;
        }
        body.append('<').append(tag).append(" source=\"").append(sourceLabel).append("\">\n")
                .append(content.strip()).append('\n')
                .append("</").append(tag).append(">\n\n");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=ProjectInstructionsTest`
Expected: PASS（6 个测试）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ProjectInstructions.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProjectInstructionsTest.java
git commit -m "feat(agents-md): ProjectInstructions 加载用户级+项目级 AGENTS.md"
```

---

### Task 2: 主 agent 装配（SYSTEM_TEMPLATE 占位符 + param 注入）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  - `SYSTEM_TEMPLATE` 尾部（`{AUTO_MEMORY}` 段之后）加 `{PROJECT_INSTRUCTIONS}`。
  - `AUTO_MEMORY_KEY` 常量旁加 `PROJECT_INSTRUCTIONS_KEY`。
  - `build()` 渲染并 `.param(...)`。
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsProjectInstructionsTest.java`

- [ ] **Step 1: 写失败测试**

此测试证明「含花括号的真实 AGENTS.md 内容经 build() 注入不炸 ST」——即注入走的是 param 值而非模板文字（回归守卫）。

`AgentToolsProjectInstructionsTest.java`：
```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** AGENTS.md 内容含花括号时，build() 装配（含系统提示 ST 渲染）不得抛异常——证明作 param 值注入而非模板文字。 */
class AgentToolsProjectInstructionsTest {

    private static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }

    @Test
    void build_withBraceHeavyAgentsMd_doesNotThrow(@TempDir Path root) throws Exception {
        // 花括号密集的项目指令：若被当模板文字交给 ST 会炸；作 param 值注入则安全。
        Files.writeString(root.resolve("AGENTS.md"),
                "构建：`mvn -q test`。示例配置：{ \"key\": \"{value}\" } 与 {{占位}}。");
        AgentTools.AgentRuntime rt = assertDoesNotThrow(
                () -> AgentTools.build(dummyRegistry(), root, new ConversationState()),
                "含花括号的 AGENTS.md 注入不得炸 ST");
        assertNotNull(rt.client(), "ChatClient 应正常装配");
    }

    @Test
    void build_withoutAgentsMd_doesNotThrow(@TempDir Path root) {
        // 无 AGENTS.md（最常见）：注入段为空，装配照常。
        AgentTools.AgentRuntime rt = AgentTools.build(dummyRegistry(), root, new ConversationState());
        assertNotNull(rt.client());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentToolsProjectInstructionsTest`
Expected: FAIL —— `SYSTEM_TEMPLATE` 尚无 `{PROJECT_INSTRUCTIONS}` param 但已引用（或反之），ST 渲染缺参/多花括号导致 `build` 抛异常（正是我们要修的）。

> 注：若此步意外通过（例如 build 尚未引用占位符、恰好不炸），仍继续 Step 3 实装并确保 Step 4 通过——本测试的价值在实装后作为 ST-safety 回归守卫。

- [ ] **Step 3: 实装**

3a. `SYSTEM_TEMPLATE` 尾部由：
```java
            当前模型：{AGENT_MODEL}。

            {AUTO_MEMORY}
            """;
```
改为：
```java
            当前模型：{AGENT_MODEL}。

            {AUTO_MEMORY}

            {PROJECT_INSTRUCTIONS}
            """;
```

3b. `AUTO_MEMORY_KEY` 常量旁加：
```java
/** 项目指令注入的 param 键；与 SYSTEM_TEMPLATE 里的 {PROJECT_INSTRUCTIONS} 占位符对应。 */
private static final String PROJECT_INSTRUCTIONS_KEY = "PROJECT_INSTRUCTIONS";
```

3c. `build()` 里，在 `autoMemoryPrompt` 渲染附近加：
```java
// 项目指令（AGENTS.md，人手写、提交入库）：渲染一次供主/子 agent 共用；无文件则为空段。
String projectInstructions = ProjectInstructions.load(root);
```

3d. 主 agent ChatClient 的 `.defaultSystem(...)` 链，在 `.param(AUTO_MEMORY_KEY, autoMemoryPrompt)` 之后加：
```java
                            .param(AUTO_MEMORY_KEY, autoMemoryPrompt)
                            .param(PROJECT_INSTRUCTIONS_KEY, projectInstructions))
```

（`projectInstructions` 供 Task 3 传入 `SubagentRunner`；本 Task 先只接主 agent，Task 3 接子 agent。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentToolsProjectInstructionsTest`
Expected: PASS（2 个）。

- [ ] **Step 5: 全模块回归**

Run: `mvn -q -pl springai-code-tui test`
Expected: BUILD SUCCESS，0 failures。特别关注任何断言系统提示文本的测试（新增 `{PROJECT_INSTRUCTIONS}` 段）；若挂，按新段更新期望值。

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsProjectInstructionsTest.java
git commit -m "feat(agents-md): 主 agent 系统提示注入 AGENTS.md 项目指令（作 param 值，ST-safe）"
```

---

### Task 3: 子 agent 注入（SubagentRunner）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（build 处把 `projectInstructions` 传进 `new SubagentRunner(...)`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentInstructionsTest.java`

- [ ] **Step 1: 写失败测试**

子 agent 的系统提示组合是纯函数，可直接单测（不发网络）。

`SubagentInstructionsTest.java`：
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子 agent 系统提示 = spec.systemPrompt() 追加项目指令（非空时）。 */
class SubagentInstructionsTest {

    private static SubagentSpec spec() {
        // 真实签名（已核实）：SubagentSpec(name, description, systemPrompt, List allowTools,
        // List denyTools, String model, List skills)。见 SubagentRunnerOkTest:57。
        return new SubagentSpec("explore", "探索型子 agent。", "SUBAGENT 系统提示正文",
                List.of(), List.of(), null, List.of());
    }

    private static SubagentRunner runnerWith(String projectInstructions) {
        return new SubagentRunner(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))),
                List.of(), new StubListener(), projectInstructions);
    }

    @Test
    void emptyInstructions_systemPromptUnchanged() {
        String eff = runnerWith("").effectiveSystemPrompt(spec());
        assertEquals("SUBAGENT 系统提示正文", eff, "无项目指令时应原样返回 spec 提示");
    }

    @Test
    void nonEmptyInstructions_appendedAfterSpecPrompt() {
        String eff = runnerWith("PROJ_INSTR_文本").effectiveSystemPrompt(spec());
        assertTrue(eff.startsWith("SUBAGENT 系统提示正文"), "spec 提示在前");
        assertTrue(eff.contains("PROJ_INSTR_文本"), "项目指令被追加");
        assertTrue(eff.indexOf("SUBAGENT 系统提示正文") < eff.indexOf("PROJ_INSTR_文本"),
                "项目指令应在 spec 提示之后");
    }
}
```

> **实装者注（已核实，可直接用）**：`SubagentSpec` 真实签名为 7 参 `(name, description, systemPrompt, List allowTools, List denyTools, String model, List skills)`（见 `SubagentRunnerOkTest.java:57`）。`StubListener` 存在于 `src/test/.../agent/StubListener.java`，实现 `AgentListener`。上面 `spec()` 已按真实签名写好。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=SubagentInstructionsTest`
Expected: 编译失败——`SubagentRunner` 无 `projectInstructions` 构造参数，无 `effectiveSystemPrompt`。

- [ ] **Step 3: 实装**

3a. `SubagentRunner` 加字段 + 构造参数。现有两个构造：
```java
private final ProviderRegistry registry;
private final List<ToolCallback> tools;
private final AgentListener listener;
private final Supplier<String> taskIdSupplier;
```
加 `private final String projectInstructions;`，并把它加入两个构造函数：
```java
public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                      String projectInstructions) {
    this(registry, tools, listener, projectInstructions, () -> "task_" + UUID.randomUUID());
}

SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
               String projectInstructions, Supplier<String> taskIdSupplier) {
    this.registry = registry;
    this.tools = tools;
    this.listener = listener;
    this.projectInstructions = projectInstructions == null ? "" : projectInstructions;
    this.taskIdSupplier = taskIdSupplier;
}
```
（原 3 参 public 构造若被别处/测试调用，见下方 3d 一并更新调用点；若要保留旧签名可加一个委托 `this(registry, tools, listener, "")` 的重载，但**优先**改所有调用点显式传入，避免隐式空指令。）

3b. 加纯函数：
```java
/** 子 agent 有效系统提示：spec 自身提示 + 项目指令（非空时追加）。纯函数，便于单测。 */
String effectiveSystemPrompt(SubagentSpec spec) {
    if (projectInstructions.isEmpty()) {
        return spec.systemPrompt();
    }
    return spec.systemPrompt() + "\n\n" + projectInstructions;
}
```

3c. `run()` 里把 `.system(spec.systemPrompt())` 改为 `.system(effectiveSystemPrompt(spec))`。

3d. `AgentTools.build()` 的 `new SubagentRunner(registry, decoratedList, listener)` 改为
`new SubagentRunner(registry, decoratedList, listener, projectInstructions)`（`projectInstructions` 已在 Task 2 Step 3c 渲染）。

3e. 更新其它 `new SubagentRunner(` 调用点（已核实全量）：`SubagentRunnerOkTest.java` 有 **2 处** `new SubagentRunner(reg, List.of(), lis)`，各补 `""` 实参 → `new SubagentRunner(reg, List.of(), lis, "")`。（`SubagentRunnerTest` 只用静态 `SubagentRunner.filterTools`，不构造 runner，无需改。）

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

Run: `mvn -q -pl springai-code-tui test -Dtest=SubagentInstructionsTest`
Expected: PASS（2 个）。

Run: `mvn -q -pl springai-code-tui test`
Expected: BUILD SUCCESS，0 failures（现有 `SubagentRunner*` 测试若因构造签名变化编译失败，须补实参）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentInstructionsTest.java
git commit -m "feat(agents-md): 子 agent 系统提示追加项目指令（纯字符串，非 ST）"
```

---

### Task 4: 文档

**Files:**
- Modify: `springai-code-tui/README.md`

- [ ] **Step 1: 更新能力清单**

在 README「模块用途」里,`长期记忆` 一节后加：
```
- **项目指令（AGENTS.md）**：启动时读取用户级 `~/.codetui/AGENTS.md` + 项目级 `<项目根>/AGENTS.md`（跨工具生态标准，Codex/Cursor/Aider 等通用），把团队约定（构建/测试命令、代码风格、架构约定）注入主 agent 与子 agent 的系统提示。人手写、提交入库、启动全量注入、**只读**（编辑文件即改约定；与 agent 自写的长期记忆正交互补）。改动需重启生效。
```

- [ ] **Step 2: 提交**

```bash
git add springai-code-tui/README.md
git commit -m "docs(agents-md): README 增补 AGENTS.md 项目指令能力"
```

---

## Self-Review

**1. Spec coverage**（对照已定设计点）：
- 文件名 AGENTS.md → Task 1 `PROJECT_FILE`/`USER_RELATIVE` ✅
- 两层 user+project、无本地层 → Task 1 只读这两处 ✅
- 顺序 user→project → Task 1 `bothLayers_userBeforeProject` 断言 ✅
- 作 param 值注入（ST-safe）→ Task 2 `{PROJECT_INSTRUCTIONS}` + `.param` + 花括号不炸测试 ✅
- 主 + 子 agent 都注入 → Task 2（主）+ Task 3（子，`effectiveSystemPrompt`）✅
- 启动加载一次 → `build()` 内渲染一次 ✅
- 缺失/空白优雅无操作 → Task 1 `bothLayersMissing_returnsEmpty` / `blankFile_skipped` ✅
- v1 不做项列表 → 未出现在任何 Task ✅

**2. Placeholder scan**：Task 1/3 各留了一处「以现有基建为准」的实装者注（`SubagentSpec` 构造签名、`StubListener` 存在性）——这是**必要的**，因为这些类型的精确签名需实装者读现有测试对齐，非空话占位；已给出参照测试文件名。其余步骤均含完整代码与精确命令。

**3. Type consistency**：
- `ProjectInstructions.load(Path)` → Task 1 定义、Task 2 `ProjectInstructions.load(root)` 调用一致 ✅
- param 键：`PROJECT_INSTRUCTIONS_KEY = "PROJECT_INSTRUCTIONS"` 常量 ↔ `{PROJECT_INSTRUCTIONS}` 占位符 ↔ `.param(PROJECT_INSTRUCTIONS_KEY, ...)` 一致 ✅
- `projectInstructions` 变量：Task 2 Step 3c 渲染 → Task 2 主 agent param + Task 3 传 `SubagentRunner` 同一字符串 ✅
- `SubagentRunner.effectiveSystemPrompt(SubagentSpec)` → Task 3 定义、`run()` 调用、测试断言一致 ✅

**风险复核**：
- 唯一软点是 Task 3 的 `SubagentSpec` 构造签名与 `StubListener`——实装者须先读 `SubagentDefinitionsTest`/`SubagentLoader`/`AgentRuntimeTest` 对齐，已显式标注。
- `new SubagentRunner(` 调用点全量更新（3e）——现有 `SubagentRunnerTest`/`SubagentRunnerOkTest` 很可能直接 new，须补实参，否则编译失败；Task 3 Step 4 全模块回归会兜住。
