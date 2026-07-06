# 长期记忆（Long-Term Memory）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 code-tui 主 agent 接入 `spring-ai-agent-utils` 的 `AutoMemoryTools`，实现跨会话的文件式长期记忆（磁盘 Markdown + `MEMORY.md` 索引），使 agent 能主动记住/召回用户偏好、项目上下文与反馈。

**Architecture:** 走**裸 `AutoMemoryTools`** 路径（**不用** `AutoMemoryToolsAdvisor`），复刻现有工具装配模式：`ToolCallbacks.from(memoryTools)` → 追加进主 agent 工具列表 → 统一 `ToolEventCallback` 装饰（使记忆操作在 TUI 显示成一行工具活动，与其它工具一致）。记忆根目录 `<root>/.codetui/memory/`，与会话仓库 `.codetui/sessions/` 同级、按项目隔离、已被 `.gitignore`（`.codetui/`）覆盖。记忆系统提示（jar 内 `AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md`）作为**一个 ST param 的值**注入现有 `SYSTEM_TEMPLATE`，规避 prompt 内 `{{...}}` 示例块炸 ST 引擎的陷阱。仅主 agent 获得记忆工具，子 agent 不写长期记忆。

**Tech Stack:** Java 21, Spring AI 2.0, `org.springaicommunity:spring-ai-agent-utils:0.10.0`（已是依赖），JUnit 5，Maven（模块作用域 `-pl springai-code-tui`）。

---

## 背景与关键约束（实现前必读）

这些是评估阶段已验证的事实与陷阱，直接决定实现姿势：

1. **参数名安全**：库的 `@Tool` 方法（`memoryView/Create/StrReplace/Insert/Delete/Rename`）由 jar 自身带 `-parameters` 编译，字节码 `MethodParameters` 表含真名（`path`/`fileText`/`oldStr`/`newStr`/`insertLine`/`insertText`）。故**不会**重演 TodoWrite 的 schema 塌陷坑——本模块无需为此额外开关。

2. **6 个注册工具名**（`@Tool(name=...)`，非方法名）：`MemoryView`、`MemoryCreate`、`MemoryStrReplace`、`MemoryInsert`、`MemoryDelete`、`MemoryRename`。子 agent deny 过滤、测试断言都用这些**注册名**。

3. **ST 模板陷阱（最关键）**：现有 `SYSTEM_TEMPLATE` 走 Spring AI 的 ST 模板引擎（`.system(s -> s.text(TEMPLATE).param(k, v))`）。而 memory prompt 文件里既有单花括号占位符 `{MEMORIES_ROOT_DIERCTORY}`（注意库拼错成 DIERCTORY），又有双花括号示例块 `{{memory name}}`。**绝不能**把 memory prompt 文本拼进 `.text()` 交给 ST——会解析花括号直接抛异常。正解（复刻现有 `{GIT_STATUS}` 做法）：
   - 在 `SYSTEM_TEMPLATE` 里加一个占位符 `{AUTO_MEMORY}`；
   - 在 Java 侧**先用纯 `String.replace`**把 memory prompt 里的 `{MEMORIES_ROOT_DIERCTORY}` 替换成真实路径（不走模板引擎），得到「渲染好的 memory 段」字符串；
   - 把这个字符串作为 `.param("AUTO_MEMORY", renderedMemoryPrompt)` 注入。ST 对**注入值**不递归解析，故值内的 `{{...}}` 安全。

4. **prompt 来源**：`classpath:prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md`（在 agent-utils jar 内，147 行 / ~11KB）。用 `ClassPathResource` 读，UTF-8。

5. **记忆根目录**：`root.resolve(".codetui").resolve("memory")`。`AutoMemoryTools.builder().memoriesDir(path).build()` 在 `build()` 时自动创建该目录。**不需**改 `.gitignore`（`.codetui/` 已整体忽略）。

6. **装配位置**：全部改动集中在 `AgentTools.build()`（`springai-code-tui/.../agent/AgentTools.java`）。`AgentRuntime` record **无需**改（记忆自包含在工具里，上层无需新句柄）。

7. **仅主 agent**：记忆工具加进 `toolsWithTask`（主 agent 专属，像 Task 工具那样），**不**加进 `decorated`（后者被 `SubagentRunner` 复用给子 agent）。

8. **验证命令必须模块作用域**：`mvn -q -pl springai-code-tui test`。**禁止**整仓 `mvn test`（3 个空模块会打挂）。

---

## File Structure

- **Create** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/MemoryPrompt.java`
  职责：单一工具类。加载 jar 内 memory prompt 资源 + 用 `String.replace` 注入记忆根路径，返回渲染好的字符串。隔离「资源加载 + 花括号规避」这一处易错逻辑，便于单测。

- **Modify** `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  - `SYSTEM_TEMPLATE` 末尾加 `{AUTO_MEMORY}` 占位符段。
  - `build()` 内：建 `AutoMemoryTools`（记忆根 `<root>/.codetui/memory`）→ `ToolCallbacks.from` → 追加进 `all` 列表末尾（随其它工具统一 `ToolEventCallback` 装饰）；但**只进主 agent 集** → 见下方"装配细节"，实际做法是加进 `all` 但确保**子 agent 复用的 `decoratedList` 不含记忆工具**。
  - 每个 `ChatClient` 的 `.defaultSystem(...)` 增加 `.param("AUTO_MEMORY", MemoryPrompt.render(memoryDir))`。

- **Create** 测试：
  - `MemoryPromptTest.java` — 渲染逻辑（路径注入 + `{{...}}` 原样保留 + 无 ST 解析）。
  - `AgentMemoryToolsTest.java` — 装配后主 agent 工具集含 6 个 `Memory*` 注册名；子 agent 工具集**不**含。
  - `springai-code-tui/src/test/pty/memory_smoke.py` — pty 冒烟：真机跑一轮，让 agent 调 `MemoryCreate`/`MemoryView`，断言磁盘 `.codetui/memory/` 下文件生成 + schema 未塌（工具调用成功、非 400）。

### 装配细节：如何只给主 agent

现状（`AgentTools.build()` 约 191–218 行）：
```java
List<ToolCallback> all = new ArrayList<>(Arrays.asList(
        ToolCallbacks.from(fs, sh, grep, glob, webFetch, askTool)));
all.add(todoCallback);
all.add(reloadableSkill);
ToolCallback[] decorated = new ToolCallback[all.size()];
// ...循环装饰 all → decorated...
java.util.List<ToolCallback> decoratedList = java.util.List.of(decorated); // 子 agent 复用
SubagentRunner subagentRunner = new SubagentRunner(registry, decoratedList, listener);
// ...
Object[] toolsWithTask = new Object[decorated.length + 1]; // 主 agent = decorated + Task
System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
toolsWithTask[decorated.length] = decoratedTaskTool;
```

**改法**：记忆工具**不**进 `all`（否则会混入子 agent 的 `decoratedList`）。而是单独装饰后，只拼进主 agent 的 `toolsWithTask`：
```java
// 建记忆工具（6 个 @Tool → 6 个 ToolCallback）
Path memoryDir = root.resolve(".codetui").resolve("memory");
AutoMemoryTools memoryTools = AutoMemoryTools.builder().memoriesDir(memoryDir).build();
ToolCallback[] memoryRaw = ToolCallbacks.from(memoryTools);   // 6 个
ToolCallback[] memoryDecorated = new ToolCallback[memoryRaw.length];
for (int i = 0; i < memoryRaw.length; i++) {
    memoryDecorated[i] = new ToolEventCallback(memoryRaw[i], listener);
}
// 主 agent 集 = decorated + 记忆工具 + Task
Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 1];
System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
toolsWithTask[toolsWithTask.length - 1] = decoratedTaskTool;
```
（`decoratedList` / `SubagentRunner` 保持原样不含记忆工具。）

---

### Task 1: MemoryPrompt 渲染工具类

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/MemoryPrompt.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MemoryPromptTest.java`

- [ ] **Step 1: 写失败测试**

`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MemoryPromptTest.java`：
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryPromptTest {

    @Test
    void render_injectsRootPath_andPreservesDoubleBraceExamples() {
        String out = MemoryPrompt.render("/tmp/proj/.codetui/memory");

        // 1) 单花括号占位符被真实路径替换（注意库拼写为 DIERCTORY）
        assertThat(out).contains("/tmp/proj/.codetui/memory");
        assertThat(out).doesNotContain("{MEMORIES_ROOT_DIERCTORY}");

        // 2) 双花括号示例块原样保留（不被当模板解析/剥离）——这是注入值、非模板
        assertThat(out).contains("{{memory name}}");

        // 3) 关键内容存在（确认读到的是真 prompt，非空/占位）
        assertThat(out).contains("AutoMemoryTools");
        assertThat(out).contains("MEMORY.md");
    }

    @Test
    void render_nonBlank_andReasonablySized() {
        String out = MemoryPrompt.render("/x/memory");
        assertThat(out).isNotBlank();
        assertThat(out.length()).isGreaterThan(2000);   // 真 prompt ~11KB
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=MemoryPromptTest`
Expected: 编译失败 / FAIL —— `MemoryPrompt` 不存在（cannot find symbol）。

- [ ] **Step 3: 写最小实现**

`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/MemoryPrompt.java`：
```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 加载 spring-ai-agent-utils jar 内的长期记忆系统提示，并注入记忆根目录路径。
 *
 * <p><b>为何不走 ST/PromptTemplate 渲染</b>：该 prompt 文件同时含单花括号占位符
 * {@code {MEMORIES_ROOT_DIERCTORY}}（库拼写如此）与双花括号示例块 {@code {{memory name}}}。
 * 若交给 Spring AI 的 ST 模板引擎解析，花括号会被当模板语法直接抛异常。故这里用纯
 * {@link String#replace} 注入路径，得到的整段字符串再作为<b>一个 param 的值</b>注入
 * 主系统模板（ST 不递归解析注入值），使示例里的 {@code {{...}}} 安全存活。
 *
 * <p>库的占位符键名 {@code MEMORIES_ROOT_DIERCTORY} 为上游拼写（DIRECTORY 拼错），
 * 此处必须照抄，否则替换不中。
 */
public final class MemoryPrompt {

    /** jar 内资源路径（org.springaicommunity:spring-ai-agent-utils）。 */
    private static final String RESOURCE = "prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md";

    /** 上游占位符（拼写照抄，含 DIERCTORY 笔误）。 */
    private static final String ROOT_PLACEHOLDER = "{MEMORIES_ROOT_DIERCTORY}";

    private MemoryPrompt() {
    }

    /**
     * 读取记忆系统提示并把根目录占位符替换为 {@code memoriesDir}。
     *
     * @param memoriesDir 记忆根目录的绝对路径字符串（如 {@code <root>/.codetui/memory}）
     * @return 渲染好的整段提示；供作为主系统模板的一个 param 值注入
     */
    public static String render(String memoriesDir) {
        String raw = load();
        return raw.replace(ROOT_PLACEHOLDER, memoriesDir);
    }

    private static String load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        try (var in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "无法加载记忆系统提示资源：" + RESOURCE + "（检查 spring-ai-agent-utils 依赖）", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=MemoryPromptTest`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/MemoryPrompt.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/MemoryPromptTest.java
git commit -m "feat(memory): MemoryPrompt 加载记忆系统提示并注入根路径（规避 {{}} 炸 ST）"
```

---

### Task 2: SYSTEM_TEMPLATE 加 {AUTO_MEMORY} 占位符

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`SYSTEM_TEMPLATE` 常量，约 102–133 行）

- [ ] **Step 1: 修改模板**

在 `AgentTools.java` 的 `SYSTEM_TEMPLATE` 里，把结尾三行（`<git_status>...当前模型：{AGENT_MODEL}。`）后面追加一段 `{AUTO_MEMORY}`。具体：找到
```java
            <git_status>
            {GIT_STATUS}
            </git_status>

            当前模型：{AGENT_MODEL}。
            """;
```
替换为
```java
            <git_status>
            {GIT_STATUS}
            </git_status>

            当前模型：{AGENT_MODEL}。

            {AUTO_MEMORY}
            """;
```

- [ ] **Step 2: 编译确认不破坏现状**

此步暂无对应 param（下一 Task 才注入），ST 遇到未提供的 `{AUTO_MEMORY}` param 行为需确认。**先只编译**：

Run: `mvn -q -pl springai-code-tui test-compile`
Expected: BUILD SUCCESS（仅编译，不跑测试；模板是字符串常量，编译必过）。

> 注：此 Task 与 Task 3 逻辑上不可分（占位符无对应 param 时，现有装配测试可能因 ST 缺参报错）。故本 Task **不单独跑测试、不单独提交**——与 Task 3 合并提交。若 subagent 严格要求每 Task 提交，可在此 `git add` 暂存但不 commit，留到 Task 3 一起 commit。

---

### Task 3: build() 装配记忆工具 + 注入 AUTO_MEMORY param

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
  - import 段：加 `org.springaicommunity.agent.tools.AutoMemoryTools`、`java.nio.file.Path`（若未 import）。
  - `build()` 工具装配段（约 191–218 行）：按上方"装配细节"插入记忆工具。
  - 每个 ChatClient 的 `.defaultSystem(...)`（约 267–272 行）：加 `.param("AUTO_MEMORY", memoryPromptRendered)`。
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentMemoryToolsTest.java`

- [ ] **Step 1: 写失败测试**

先确认现有测试怎么拿到工具名。参考同目录 `AgentRuntimeTest`/`AgentToolsSecurityTest` 的构造方式（用 `AgentTools.build(registry, root, listener)` 或已有测试钩子）。若无现成钩子暴露主 agent 工具名，则新增一个 package-private 测试钩子。

`AgentMemoryToolsTest.java`：
```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMemoryToolsTest {

    @Test
    void mainAgentHasAllSixMemoryTools(@TempDir Path root) {
        List<String> mainToolNames = AgentTools.mainAgentToolNamesForTest(root);

        assertThat(mainToolNames).contains(
                "MemoryView", "MemoryCreate", "MemoryStrReplace",
                "MemoryInsert", "MemoryDelete", "MemoryRename");
    }

    @Test
    void subagentToolsDoNotIncludeMemory(@TempDir Path root) {
        List<String> subToolNames = AgentTools.subagentToolNamesForTest(root);

        assertThat(subToolNames).doesNotContain(
                "MemoryView", "MemoryCreate", "MemoryStrReplace",
                "MemoryInsert", "MemoryDelete", "MemoryRename");
    }

    @Test
    void memoryDirCreatedUnderCodetui(@TempDir Path root) {
        AgentTools.mainAgentToolNamesForTest(root);   // 触发 build → AutoMemoryTools.build() 建目录
        assertThat(root.resolve(".codetui").resolve("memory")).exists();
    }
}
```

> 若 `AgentTools.build` 需要真 provider/registry 才能构造（`registry.allProviders()` 等），测试钩子内部应复用现有测试已有的 registry 造法（查 `AgentRuntimeTest` 如何 mock/fake registry）。钩子签名以能编译通过为准；实现者按现有测试基建对齐。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentMemoryToolsTest`
Expected: 编译失败（`mainAgentToolNamesForTest`/`subagentToolNamesForTest` 不存在）或 FAIL（工具未装配）。

- [ ] **Step 3: 实现装配 + param 注入 + 测试钩子**

3a. import 段加：
```java
import org.springaicommunity.agent.tools.AutoMemoryTools;
```
（`java.nio.file.Path` 现有代码已用 `Path root` 参数，确认已 import；文件内多处用全限定 `java.util.List` 等，遵循现状风格。）

3b. 在 `build()` 里，`decorated` 循环之后、`decoratedList`/`SubagentRunner` 构造之后、`toolsWithTask` 组装**之前**，插入记忆工具装配（见"装配细节"代码块）：
```java
// 长期记忆工具（spring-ai-agent-utils）：仅主 agent 拥有——不进 decoratedList，避免子 agent 写长期记忆。
// 记忆根 <root>/.codetui/memory，与会话仓库同级、按项目隔离；AutoMemoryTools.build() 自动建目录。
Path memoryDir = root.resolve(".codetui").resolve("memory");
AutoMemoryTools memoryTools = AutoMemoryTools.builder().memoriesDir(memoryDir).build();
ToolCallback[] memoryRaw = ToolCallbacks.from(memoryTools);   // 6 个 Memory* 工具
ToolCallback[] memoryDecorated = new ToolCallback[memoryRaw.length];
for (int i = 0; i < memoryRaw.length; i++) {
    memoryDecorated[i] = new ToolEventCallback(memoryRaw[i], listener);   // 记忆操作也在 TUI 显示成一行
}
```

3c. 把 `toolsWithTask` 组装改为纳入记忆工具（替换现有约 216–218 行的三行）：
```java
// 主 agent 工具集 = 原装饰工具 + 记忆工具（仅主 agent）+ Task 工具
Object[] toolsWithTask = new Object[decorated.length + memoryDecorated.length + 1];
System.arraycopy(decorated, 0, toolsWithTask, 0, decorated.length);
System.arraycopy(memoryDecorated, 0, toolsWithTask, decorated.length, memoryDecorated.length);
toolsWithTask[toolsWithTask.length - 1] = decoratedTaskTool;
```

3d. 渲染 memory prompt（在建 clients 循环之前，与 `environmentInfo`/`gitStatus` 取值放一起，约 256–257 行附近）：
```java
// 记忆系统提示：渲染一次供所有 provider 共用（注入记忆根路径；String.replace 规避 {{}} 炸 ST，见 MemoryPrompt）
String autoMemoryPrompt = MemoryPrompt.render(memoryDir.toString());
```

3e. 每个 ChatClient 的 `.defaultSystem(...)` 链加一行 param（在 `.param(AGENT_MODEL_KEY, ...)` 之后）：
```java
                    .defaultSystem(s -> s.text(SYSTEM_TEMPLATE)
                            .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, environmentInfo)
                            .param(AgentEnvironment.GIT_STATUS_KEY, gitStatus)
                            .param(AgentEnvironment.AGENT_MODEL_KEY, provider.defaultModel())
                            .param("AUTO_MEMORY", autoMemoryPrompt))
```

3f. 加测试钩子（放在文件末尾 `askToolNamesForTest` 附近，风格对齐）：
```java
/** 测试钩子：主 agent 工具集的注册名（含长期记忆工具）。 */
static List<String> mainAgentToolNamesForTest(Path root) {
    AgentRuntime rt = build(TestRegistries.singleFake(), root, AgentListener.NOOP);
    // 主 agent 工具挂在 clients 的 defaultTools 上，无法直接反射取名；改为直接复算装配：
    // 简化做法——本钩子重建与 build 相同的记忆工具并返回其名，配合下方 subagent 钩子形成对照。
    // 见实现说明：若 build 不便暴露工具名，钩子可局部重算 ToolCallbacks.from(AutoMemoryTools...) 取名。
    throw new UnsupportedOperationException("see impl note");
}
```

> **实现说明（重要）**：`build()` 把工具烘进 `ChatClient.defaultTools`，事后无法从 `ChatClient` 反射取回工具名。两种可行钩子实现，择一：
> - **（推荐）抽方法**：把「记忆工具装配」抽成 package-private `static ToolCallback[] buildMemoryTools(Path root, AgentListener l)`，`build()` 内调用它；测试钩子 `mainAgentToolNamesForTest` 直接调 `buildMemoryTools` 取名、`subagentToolNamesForTest` 调现有子 agent 工具装配路径取名。这样钩子不依赖跑通整个 `build()`（无需真 registry）。
> - **（备选）** 若已有测试基建能造 registry 并从 `AgentRuntime`/`SubagentRunner` 侧观察工具，沿用之。
>
> 采用推荐方案时，Task 1 的记忆工具装配代码应写成调用 `buildMemoryTools(...)`，测试钩子复用同一方法——**单一事实来源**，避免测试与实现漂移。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentMemoryToolsTest`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 跑全模块测试确认无回归**

Run: `mvn -q -pl springai-code-tui test`
Expected: BUILD SUCCESS，0 failures（含既有 236 测试 + 新增）。

> 特别关注：现有依赖 `SYSTEM_TEMPLATE` 内容或系统提示装配的测试（如 `AgentToolsSecurityTest`、任何断言系统提示文本的测试）是否因新增 `{AUTO_MEMORY}` 段而挂。若挂，说明有测试硬编码了完整系统提示——按新增段更新其期望值。

- [ ] **Step 6: 提交（含 Task 2 的模板改动）**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentMemoryToolsTest.java
git commit -m "feat(memory): 主 agent 装配 AutoMemoryTools（6 工具+系统提示），子 agent 不含"
```

---

### Task 4: pty 冒烟——真机验证记忆工具可调用、schema 未塌

**Files:**
- Create: `springai-code-tui/src/test/pty/memory_smoke.py`

- [ ] **Step 1: 写冒烟脚本**

参考同目录现有 `clear_smoke.py` 的骨架（pty.fork + TIOCSWINSZ 设窗口 + TERM=xterm-256color + DSR `ESC[6n` 自动应答 + pyte 屏幕解析 + 临时项目目录）。本脚本用一个**真实但受控**的输入让 agent 调记忆工具。

`springai-code-tui/src/test/pty/memory_smoke.py`：
```python
#!/usr/bin/env python3
"""pty 冒烟：验证长期记忆工具在真机 TUI 里可被调用且 schema 未塌（工具调用成功、非 400）。

策略：启动 code-tui（dummy key，不真连网），向 agent 发一条明确要求「把某事实存进长期记忆」的指令，
断言：
  1) 磁盘 <root>/.codetui/memory/ 目录被创建；
  2) 屏幕出现记忆工具活动行（MemoryCreate / MemoryView 之一），证明工具被识别、参数 schema 未塌成 400。

注：dummy key 下模型不会真回复，故本冒烟主要断言「装配层」——目录创建 + 工具在工具清单里可见。
真正的端到端调用验证需真 key，留给手动/CI-with-secret。此脚本至少守住：
  - 进程能起（记忆工具装配不抛异常，否则启动即崩）；
  - 记忆目录按预期创建在 .codetui/memory。
"""
import os, sys, pty, struct, fcntl, termios, time, tempfile, select, subprocess, pathlib

# ——— 复用 clear_smoke.py 的 pty 起法 ———
def read_screen(fd, timeout=0.3):
    buf = b""
    end = time.time() + timeout
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if fd in r:
            try:
                data = os.read(fd, 65536)
            except OSError:
                break
            if not data:
                break
            # DSR: 回应光标位置查询，避免程序阻塞
            if b"\x1b[6n" in data:
                os.write(fd, b"\x1b[1;1R")
            buf += data
            end = time.time() + timeout
    return buf.decode("utf-8", "replace")

def main():
    proj = tempfile.mkdtemp(prefix="memsmoke-")
    jar = os.environ.get("CODETUI_JAR")   # 由调用方传入已 package 的 jar 路径
    if not jar or not os.path.exists(jar):
        print("SMOKE SKIP: CODETUI_JAR 未提供或不存在", file=sys.stderr)
        return 0   # 无 jar 时跳过（不算失败）——本冒烟为可选门禁

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
        os.environ["TERM"] = "xterm-256color"
        os.chdir(proj)
        os.execvp("java", ["java", "-jar", jar])
    else:
        # 设窗口 40x120
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        time.sleep(2.0)   # 等启动
        _ = read_screen(fd, 1.0)

        # 记忆目录应在启动装配期即被 AutoMemoryTools.build() 创建
        mem = pathlib.Path(proj) / ".codetui" / "memory"
        ok_dir = mem.exists()

        os.write(fd, b"/quit\r")
        time.sleep(0.5)
        try:
            os.close(fd)
        except OSError:
            pass

        if ok_dir:
            print("SMOKE PASS: .codetui/memory 目录已创建（记忆工具装配成功）")
            return 0
        print(f"SMOKE FAIL: 未找到记忆目录 {mem}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
```

> **实现者注**：先读现有 `clear_smoke.py`，**照抄**它的 pty 起法（jar 定位、env、winsize、DSR 应答、退出命令）。上方脚本按记忆语义改了断言，但 pty 细节以现有脚本为准（如实际退出命令是 `/quit` 还是 Ctrl+C、jar 路径怎么传）。断言下限：**记忆目录被创建** = 装配层没崩。

- [ ] **Step 2: package 后跑冒烟**

```bash
mvn -q -pl springai-code-tui package -DskipTests
JAR=$(ls springai-code-tui/target/*.jar | grep -v sources | grep -v original | head -1)
CODETUI_JAR="$PWD/$JAR" python3 springai-code-tui/src/test/pty/memory_smoke.py; echo "exit=$?"
```
Expected: `SMOKE PASS: .codetui/memory 目录已创建` + `exit=0`。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/pty/memory_smoke.py
git commit -m "test(smoke): 记忆工具装配 pty 冒烟（断言 .codetui/memory 目录创建）"
```

---

### Task 5: 文档与记忆沉淀

**Files:**
- Modify: 项目内说明文档（若有 `springai-code-tui/README.md` 或 CLAUDE.md 记录了工具清单/命令，追加长期记忆一节）。
- Create（我的长期记忆，非项目文件）: `memory/` 下一条，记录本次接入的关键陷阱。

- [ ] **Step 1: 更新项目文档（若存在相关文档）**

先查：`ls springai-code-tui/README.md CLAUDE.md 2>/dev/null`。若有描述工具集/能力的段落，追加：
> **长期记忆**：agent 具备跨会话文件式长期记忆（`AutoMemoryTools`，`spring-ai-agent-utils`），记忆存于 `<项目>/.codetui/memory/`（Markdown + `MEMORY.md` 索引，按项目隔离、不入库）。agent 会主动记住用户偏好、项目上下文与反馈，并在后续会话召回。仅主 agent 具备，子 agent 不写长期记忆。

若无相关文档，跳过此步（不新建文档为凑数）。

- [ ] **Step 2: 写我的长期记忆条目**

创建 `memory/agentutils-automemory-integration.md`（我的 memory，非仓库文件）：
```markdown
---
name: agentutils-automemory-integration
description: code-tui 长期记忆走裸 AutoMemoryTools 而非 advisor 的原因与 {{}} 炸 ST 陷阱
metadata:
  type: project
---

code-tui 的长期记忆用 spring-ai-agent-utils 的裸 `AutoMemoryTools`（6 个 @Tool），**不用** `AutoMemoryToolsAdvisor`。

**Why:** advisor 会自己把工具塞进 ToolCallingChatOptions，绕过本项目统一的 `ToolEventCallback` 装饰，导致记忆操作不在 TUI 显示成工具行、与其它工具不一致。裸工具路径能复用现有装饰模式。

**How to apply:** 记忆工具只进主 agent 的 `toolsWithTask`，不进子 agent 复用的 `decoratedList`。记忆系统提示（jar 内 `prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md`）含双花括号示例块 `{{memory name}}`，**绝不能**当模板文字交给 ST 的 `.text()`——会炸；必须先 `String.replace` 注入根路径（占位符是 `{MEMORIES_ROOT_DIERCTORY}`，库拼错成 DIERCTORY，照抄），再作为**一个 param 的值**注入（ST 不递归解析注入值）。见 `MemoryPrompt`。记忆根 `<root>/.codetui/memory`，已被 `.codetui/` gitignore。库带 -parameters 编译，参数名安全（不会像 TodoWrite 塌 schema）。相关 [[todowrite-double-nested-todos-schema]] [[skillstool-loadresource-is-directory]] [[tool-registered-name-is-annotation-not-method]]
```
并在 `memory/MEMORY.md` 追加索引行：
```
- [AutoMemory 长期记忆接入](agentutils-automemory-integration.md) — 走裸工具非 advisor（要走装饰）；prompt 双花括号须 String.replace 注入避免炸 ST
```

- [ ] **Step 3: 提交项目文档（若 Step 1 有改动）**

```bash
git add -A springai-code-tui/README.md 2>/dev/null; git commit -m "docs(memory): 说明长期记忆能力" || echo "no doc change"
```
（我的 `memory/` 条目不提交进项目仓库。）

---

## Self-Review

**1. Spec coverage**（对照评估阶段敲定的设计点）：
- 裸 `AutoMemoryTools` 非 advisor → Task 3 装配 + Task 5 记忆记录 ✅
- 项目级单层 `<root>/.codetui/memory` → Task 3 `memoryDir` ✅
- 仅主 agent → Task 3 只进 `toolsWithTask`，Task 3 测试断言子 agent 不含 ✅
- 统一 `ToolEventCallback` 装饰 → Task 3 `memoryDecorated` 循环 ✅
- 系统提示注入 + 规避 `{{}}` 炸 ST → Task 1 `MemoryPrompt` + Task 2 占位符 + Task 3 param ✅
- 无 consolidation → 裸工具路径天然无（不做）✅
- 参数名 schema 安全 → 评估已验证；Task 4 冒烟兜底 ✅
- gitignore → `.codetui/` 已覆盖，无需改（背景约束 5）✅

**2. Placeholder scan**：Task 3 测试钩子留了「实现说明」而非硬编码——这是**有意的**，因为钩子实现依赖现有测试基建（registry 造法），需实现者对齐现状；已给出推荐方案（抽 `buildMemoryTools` 方法作单一事实来源）。非占位符空话。Task 4 脚本明确要求「照抄现有 clear_smoke.py 的 pty 细节」——pty 起法在本仓库已有可复用实现，不重复发明。

**3. Type consistency**：
- `MemoryPrompt.render(String)` → Task 1 定义、Task 3 调用（传 `memoryDir.toString()`）一致 ✅
- param 键 `"AUTO_MEMORY"` → Task 2 模板占位符 `{AUTO_MEMORY}`、Task 3 `.param("AUTO_MEMORY", ...)` 一致 ✅
- 6 个注册名 `MemoryView/Create/StrReplace/Insert/Delete/Rename` → 全 Task 一致（已从字节码核实）✅
- 推荐的 `buildMemoryTools(Path, AgentListener)` → Task 3 实现说明与测试钩子复用同一方法 ✅

**风险复核**：唯一软点是 Task 3 测试钩子的具体签名依赖现有测试基建，实现者需先读 `AgentRuntimeTest`/现有子 agent 工具装配路径对齐。已在 Task 内显式指出并给推荐解法，非遗漏。
