# code-tui agent 包按功能重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `codetui.agent` 顶层 88 个类按功能拆进 10 个子包，顶层只留 `CodingAgent` 与 `AgentTools`。

**Architecture:** 纯位置调整。每个任务搬一个包：移动 main 文件改 `package` 行 → 移动对应测试文件改 `package` 行 → 给所有引用方补 import → 必要时升 `public`。不改任何方法体，不重命名类，不动 4 个既有子包（`media`/`permission`/`background`/`thinking`）。按「先叶子后核心」排序，每个任务独立编译测试通过后提交。

**Tech Stack:** Java 17（`maven.compiler.release=17`）、Maven 3.9+、JUnit 5、Spring AI

**Spec:** `docs/superpowers/specs/2026-08-28-code-tui-agent-package-restructure-design.md`

## Global Constraints

- 包根：`io.github.javaside.springai.codetui.agent`，main 目录 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/`，test 目录 `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/`
- 测试命令必须带模块作用域与 `-am`：`mvn -pl springai-code-tui -am test`。`-am` 不可省（依赖不发布到远程的兄弟模块 `springai-tamboui-inline-patch`）
- 单类测试须加 `-Dsurefire.failIfNoSpecifiedTests=false`，否则 `-Dtest` 会在兄弟模块上匹配不到而失败
- 不要用整仓 `mvn test`（仓库里有空 demo 模块会失败）
- JDK 17 语言级别：不得使用按类型模式 `switch`（JEP 441，需 21）。消费 `ModalRequest` 一律用 `if (r instanceof AskRequest a) … else if …` 链
- **本次禁止改动任何方法体**。允许的改动只有四类：`package` 行、`import` 行、可见性修饰符（`class` → `public class`、`static` → `public static`）、新增类注释行
- 升 `public` 的类，类注释末尾统一加一行：`<p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。`
- **方法级提权同样合法且同样要标注**：跨包调用方留在别的包时，包级方法（典型是 `forTest(...)` 之类的测试工厂、包级静态工具方法）需升 `public static`，并在该方法的 javadoc 末尾加同一行说明。只提编译器实际报错的那些方法，不要批量提权同类方法
- 用 `git mv` 移动文件（保留改名历史，让 `git log --follow` 可追溯）
- 真机冒烟测试无 key 时自动跳过，不算失败。已知 flaky：`CodingAgentSpikeTest.todoTurnIdBinding` 走真实 DeepSeek、单回合 60s 上限，偶发超时，撞上时单跑确认，不要误判为重构改坏

---

## 每个任务的通用作业流程

所有 10 个任务的机械动作完全同构，此处定义一次，各任务只列「搬哪些文件」「哪些类要升 public」「有哪些特殊坑」。

**Step A：建目录并移动 main 文件**

```bash
cd springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent
mkdir -p <PKG>
git mv <Class1>.java <Class2>.java … <PKG>/
```

**Step B：改 main 文件的 package 行**

每个被移动的文件，首行从
`package io.github.javaside.springai.codetui.agent;`
改为
`package io.github.javaside.springai.codetui.agent.<PKG>;`

**Step C：移动测试文件并改 package 行**

```bash
cd springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent
mkdir -p <PKG>
git mv <Test1>.java <Test2>.java … <PKG>/
```

同样改每个测试文件的首行 package。

**Step D：编译，让编译器列出所有缺失的 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期：一批 `cannot find symbol` / `is not public` 错误。这份错误列表就是待补 import 的清单，不要凭记忆猜。

**Step E：补 import 并按需升 public**

- 对每个报 `cannot find symbol` 的文件，加 `import io.github.javaside.springai.codetui.agent.<PKG>.<Class>;`
- 对每个报 `<Class> is not public` 的类，把声明处的 `class`/`interface`/`record`/`enum` 前加 `public`，并在类注释末尾加内部类型说明行（见 Global Constraints）
- 重复 Step D 直到编译干净

**Step F：跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。红了先看是不是漏补 import，再看是不是误碰了方法体（`git diff` 逐行确认）。

**Step G：确认 diff 只含允许的改动**

```bash
git diff --stat
git diff -- '*.java' | grep -E '^[+-]' | grep -vE '^[+-]{3}' | grep -vE '^[+-]\s*(package|import) ' | grep -vE '^[+-]\s*(\*|/\*\*|\*/)' | grep -vE '^[+-]\s*$' | grep -vE '^[+-]\s*(public )?(final |abstract |sealed |static )*(class|interface|record|enum) ' | grep -vE '^[+-]\s*(public |protected )?static .*\(' 
```

最后一条命令应**无输出**。白名单已放行：package 行、import 行、注释行（含 `/**`、`*/`）、空行、类型声明行、静态方法签名行。

**有输出不等于违规，但每一行都要人工核对**：真正要查的是「有没有方法体、字段、逻辑被改」。核对方法是对每个可疑文件跑旧新全文比对：

```bash
git show <BASE>:<旧路径> > /tmp/old.java
git show HEAD:<新路径> > /tmp/new.java
diff /tmp/old.java /tmp/new.java
```

差异应只有 package 行、import 行、可见性修饰符、新增注释行。任何方法体内的改动都是违规，必须还原。

**Step H：提交**

```bash
git add -A
git commit -m "refactor(code-tui): <说明>"
```

---

## Task 1: 搬 skill 包

**Files:**
- Move（main，3）：`ReloadableSkillTool` `SkillCatalog` `SkillInfo` → `agent/skill/`
- Move（test，2）：`ReloadableSkillToolTest` `SkillCatalogTest` → `test/agent/skill/`

**Interfaces:**
- Produces：`agent.skill.SkillCatalog`、`agent.skill.SkillInfo`、`agent.skill.ReloadableSkillTool`（三者本就 public，可见性不变）
- Consumes：无（本任务是首个，只依赖既有代码）

**预期需补 import 的引用方**：`AgentTools`、`CodingAgent`、`SubagentRunner`（main）；`ui/CodeTuiView`、`CodeTuiApplication` 引用了 `SkillInfo`。以 Step D 的编译错误为准。

**升 public**：无。三个类都已是 public。

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `skill`，文件清单见上。

- [ ] **Step 2: 编译，收集缺失 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期：`cannot find symbol: class SkillCatalog` 等若干条。

- [ ] **Step 3: 补 import 直到编译干净**

对报错文件加：

```java
import io.github.javaside.springai.codetui.agent.skill.SkillCatalog;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;
import io.github.javaside.springai.codetui.agent.skill.ReloadableSkillTool;
```

（只加该文件实际用到的那几行。）重复编译直到通过。

- [ ] **Step 4: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 5: 确认 diff 只含 package/import 行**

跑通用流程 Step G 的 grep，应无输出。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 技能相关类移入 agent.skill 包"
```

---

## Task 2: 搬 prompt 包

**Files:**
- Move（main，3）：`MemoryPrompt` `PermissionModePrompt` `ProjectInstructions` → `agent/prompt/`
- Move（test，3）：`MemoryPromptTest` `PermissionModePromptTest` `ProjectInstructionsTest` → `test/agent/prompt/`

**Interfaces:**
- Produces：`agent.prompt.MemoryPrompt`、`agent.prompt.ProjectInstructions`、`agent.prompt.PermissionModePrompt`
- Consumes：Task 1 的 `agent.skill.*`（本任务不直接用，但同一代码库已就位）

**特殊坑**：`PermissionModePrompt` import 了 `agent.permission.PermissionMode`。移动后这条 import 依然有效（`permission` 包没动），不要误删。

**升 public**：无。

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `prompt`。

- [ ] **Step 2: 编译，收集缺失 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期报错来自 `AgentTools`（用 `MemoryPrompt`、`ProjectInstructions`）、`CodingAgent` 与 `SubagentRunner`（用 `PermissionModePrompt`）。

- [ ] **Step 3: 补 import 直到编译干净**

```java
import io.github.javaside.springai.codetui.agent.prompt.MemoryPrompt;
import io.github.javaside.springai.codetui.agent.prompt.PermissionModePrompt;
import io.github.javaside.springai.codetui.agent.prompt.ProjectInstructions;
```

- [ ] **Step 4: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 5: 确认 diff 只含 package/import 行**

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 系统提示片段加载类移入 agent.prompt 包"
```

---

## Task 3: 搬 mcp 包

**Files:**
- Move（main，7）：`EnvInterpolator` `McpClientManager` `McpConfigLoader` `McpConfigWriter` `McpRegistry` `McpServerConfig` `McpTransportFactory` → `agent/mcp/`
- Move（test，9）：`EnvInterpolatorTest` `McpClientManagerTest` `McpConfigLoaderTest` `McpConfigWriterTest` `McpRegistryBackgroundConnectTest` `McpRegistryTest` `McpServerConfigTest` `McpStreamableHttpSmokeTest` `McpTransportFactoryTest` → `test/agent/mcp/`

**Interfaces:**
- Produces：`agent.mcp.McpRegistry`、`agent.mcp.McpConfigLoader`、`agent.mcp.McpServerConfig` 等（public 者不变）
- Consumes：无新增

**特殊坑**：
- `McpConfigWriter` 是包私有，唯一调用方 `McpRegistry` 与它同包同步移动，**继续保持包私有**，不要顺手升 public
- `EnvInterpolator` 随唯一调用方 `McpConfigLoader` 进 `mcp`（spec 已论证）
- `McpRegistry` 引用 `PermissionCallback` 与 `ToolEventCallback`——这两个类 Task 10 才搬，本任务它们还在 `agent` 顶层，需在 `McpRegistry` 里加 `import io.github.javaside.springai.codetui.agent.PermissionCallback;` 等。Task 10 会再把这些 import 改成 `agent.tools.*`，属预期返工，不是错误
- `McpStreamableHttpSmokeTest` 由 `CODETUI_MCP_SMOKE_URL` 门控，无变量时跳过

**升 public**：无。

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `mcp`。

- [ ] **Step 2: 编译，收集缺失 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

- [ ] **Step 3: 补 import 直到编译干净**

两个方向都要补：
① 顶层残留类（`AgentTools`、`CodingAgent`、`SubagentRunner`）与包外（`ui/CodeTuiView`、`CodeTuiApplication`）需 import `agent.mcp.*`；
② 移入 `mcp` 的类反过来需 import 仍在顶层的 `agent.PermissionCallback`、`agent.ToolEventCallback`、`agent.seam` 尚未建立前的 `agent.AgentListener` 等。

- [ ] **Step 4: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 5: 确认 diff 只含 package/import 行**

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): MCP 客户端与配置类移入 agent.mcp 包"
```

---

## Task 4: 搬 interjection 包

**Files:**
- Move（main，3）：`InterjectingChatModel` `InterjectionText` `Interjections` → `agent/interjection/`
- Move（test，3）：`InterjectingChatModelTest` `InterjectionsTest` `MidTurnInjectionTest` → `test/agent/interjection/`

**Interfaces:**
- Produces：`agent.interjection.Interjections`（public）、`agent.interjection.InterjectionText`（public）、`agent.interjection.InterjectingChatModel`（**本任务升为 public**，暴露 `wrap(ChatModel, Interjections)` 与 `wrapText(String)` 两个静态方法给 `AgentTools`/`CodingAgent`）
- Consumes：无新增

**升 public（1 个）**：`InterjectingChatModel` —— 跨包引用方为 `AgentTools`（`InterjectingChatModel.wrap(visionModel, interjections)`）、`CodingAgent`（`InterjectingChatModel.wrapText(rawText)`）、以及留在 root 的 `InterjectionHistoryTest`。

**特殊坑**：
- `InterjectingChatModel` 的类注释里提到 `RetryingChatModel`、`VisionMaterializingChatModel`，那些只是注释，**不构成引用**，不要为它们加 import
- `InterjectionHistoryTest` 留在 root（它主要测 `CodingAgent`），故它需要 import `agent.interjection.InterjectingChatModel`——这正是 `InterjectingChatModel` 必须 public 的原因之一
- `ui/HistoryReplay` 引用 `InterjectionText`，需补 import

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `interjection`。

- [ ] **Step 2: 编译，收集缺失 import 与可见性错误**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期含 `InterjectingChatModel is not public` 一类错误。

- [ ] **Step 3: 把 InterjectingChatModel 升为 public**

声明行改为：

```java
public final class InterjectingChatModel implements ChatModel {
```

并在类注释末尾（`*/` 前）加：

```java
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
```

- [ ] **Step 4: 补 import 直到编译干净**

```java
import io.github.javaside.springai.codetui.agent.interjection.InterjectingChatModel;
import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
```

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 6: 确认 diff 只含 package/import/可见性/注释行**

Step G 的 grep 会放行 `public final class …` 这类可见性改动与 `*` 注释行，其余仍应无输出。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 插话相关类移入 agent.interjection 包"
```

---

## Task 5: 搬 session 包

**Files:**
- Move（main，7）：`CacheUsageExtractor` `ContextStats` `FileSessionRepository` `SessionEvents` `SessionIds` `SessionTokenEstimator` `TokenUsageAccumulator` → `agent/session/`
- Move（test，5）：`CacheUsageExtractorTest` `FileSessionRepositoryTest` `SessionEventsTest` `SessionIdsTest` `TokenUsageAccumulatorTest` → `test/agent/session/`

**Interfaces:**
- Produces：
  - `agent.session.ContextStats`（public record，`/context` 命令的快照，被 `ui/ContextUsage` 消费）
  - `agent.session.TokenUsageAccumulator`（public，含嵌套 `Snapshot`）
  - `agent.session.SessionIds`、`agent.session.FileSessionRepository`、`agent.session.CacheUsageExtractor`（public 不变）
  - `agent.session.SessionEvents`（**升 public**）
  - `agent.session.SessionTokenEstimator`（**升 public**，Task 6 的 compaction 包要用）
- Consumes：无新增

**升 public（2 个）**：
- `SessionEvents` —— 跨包引用方 `CodingAgent`、留在 root 的 `CodingAgentTrimTest`
- `SessionTokenEstimator` —— 跨包引用方 `CodingAgent`，以及 Task 6 之后的 `BoundedSummarizationCompactionStrategy`、`CompleteTokenCountTrigger`、`ContextStatsTest`、`BoundedSummarizationCalibrationTest`

**特殊坑**：
- `ContextStatsTest` **不搬到 session**，它归 compaction（Task 6）。本任务不要动它，但它会因 `ContextStats` 移走而编译失败——本任务需给它补 `import io.github.javaside.springai.codetui.agent.session.ContextStats;` 与 `session.SessionTokenEstimator`（此时它还在 root，Task 6 才移走）
- `SubmitHandler` 接口的 `contextStats()` 返回 `ContextStats`，`SubmitHandler` 本任务还在顶层，需补 import
- `SessionEvents` 被 `SessionIdStreamGuardAdvisor` 引用，后者 Task 8 才进 llm；本任务两者一个在 `session` 一个在顶层，已跨包，故 `SessionEvents` 此时就必须 public

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `session`。注意测试只搬 5 个，`ContextStatsTest` 留在原地。

- [ ] **Step 2: 编译，收集缺失 import 与可见性错误**

```bash
mvn -pl springai-code-tui -am test-compile
```

- [ ] **Step 3: 把 SessionEvents 与 SessionTokenEstimator 升为 public**

```java
public final class SessionEvents {
```

```java
public final class SessionTokenEstimator {
```

两者的静态方法也须可见：`SessionEvents` 与 `SessionTokenEstimator` 的方法目前是包私有（`static` 无修饰符），跨包调用需一并加 `public`。逐个方法核对编译器报的 `is not public` 错误，只改被跨包调用的那些方法签名，不动方法体。两个类的注释末尾各加内部类型说明行。

- [ ] **Step 4: 补 import 直到编译干净**

```java
import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.session.SessionEvents;
import io.github.javaside.springai.codetui.agent.session.SessionIds;
import io.github.javaside.springai.codetui.agent.session.SessionTokenEstimator;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.session.FileSessionRepository;
import io.github.javaside.springai.codetui.agent.session.CacheUsageExtractor;
```

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 6: 确认 diff 只含 package/import/可见性/注释行**

本任务因方法可见性放宽，Step G 的 grep 可能输出 `static` → `public static` 的方法签名行。逐条核对：只允许修饰符变化，参数与返回类型必须逐字符相同。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 会话持久化与 token 记账类移入 agent.session 包"
```

---

## Task 6: 搬 compaction 包

**Files:**
- Move（main，7）：`BoundedSummarizationCompactionStrategy` `CalibrationState` `CompleteTokenCountTrigger` `ModelContextWindows` `NotifyingCompactionStrategy` `PreflightCompactionAdvisor` `SummarizerOverflow` → `agent/compaction/`
- Move（test，6）：`BoundedSummarizationCalibrationTest` `CalibrationStateTest` `ContextStatsTest` `NotifyingCompactionStrategyTest` `PreflightCompactionAdvisorTest` `SummarizerOverflowTest` → `test/agent/compaction/`

**Interfaces:**
- Consumes：`agent.session.SessionTokenEstimator`（Task 5 已升 public）
- Produces：
  - `agent.compaction.NotifyingCompactionStrategy`（已 public）
  - `agent.compaction.BoundedSummarizationCompactionStrategy`（**升 public**，含嵌套 `ModelSnapshot`——`AgentTools` 用 `new BoundedSummarizationCompactionStrategy.ModelSnapshot(...)`，嵌套类型也须 public）
  - `agent.compaction.CalibrationState`（**升 public**，`AgentTools` 里 `new CalibrationState()`）
  - `agent.compaction.ModelContextWindows`（**升 public**，`AgentTools` 里 `ModelContextWindows.fromEnvironment()`）
  - `agent.compaction.PreflightCompactionAdvisor`（**升 public**，`AgentTools` 里 `new PreflightCompactionAdvisor(...)`）

**升 public（4 个）**：`BoundedSummarizationCompactionStrategy`（含嵌套 `ModelSnapshot` 与被 `AgentTools` 调用的构造器）、`CalibrationState`、`ModelContextWindows`、`PreflightCompactionAdvisor`。

**保持包私有（2 个）**：`CompleteTokenCountTrigger`（仅被同包 `PreflightCompactionAdvisor` 与同包测试 `ContextStatsTest` 用）、`SummarizerOverflow`（仅被同包 `BoundedSummarizationCompactionStrategy` 用）。

**特殊坑**：
- `ContextStatsTest` 搬到 compaction，是因为它 17 个方法里 13 个测压缩相关类。文件名与内容不符，但本次不改名。它同时用 `ContextStats`（在 session）与 `SessionTokenEstimator`（在 session），需补两条 import
- `ModelContextWindows` 的 `DEFAULT_UNKNOWN_WINDOW` 常量与 `parse`/`fromEnvironment` 静态方法被跨包调用，须一并 public
- `BoundedSummarizationCompactionStrategy` 的嵌套 record `ModelSnapshot` 被 `AgentTools` 直接 `new`，嵌套类型与其构造器都要 public

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `compaction`。

- [ ] **Step 2: 编译，收集缺失 import 与可见性错误**

```bash
mvn -pl springai-code-tui -am test-compile
```

- [ ] **Step 3: 升 4 个类为 public**

```java
public final class BoundedSummarizationCompactionStrategy implements CompactionStrategy {
```

嵌套类型（当前声明是 `record ModelSnapshot(String calibrationKey, long windowTokens, long inputReserve) {`）：

```java
    public record ModelSnapshot(String calibrationKey, long windowTokens, long inputReserve) {
```

```java
public final class CalibrationState {
```

```java
public final class ModelContextWindows {
```

```java
public final class PreflightCompactionAdvisor implements … {
```

按编译器报错逐个把被跨包调用的构造器、静态方法、常量加 `public`。四个类的注释末尾各加内部类型说明行。`CompleteTokenCountTrigger` 与 `SummarizerOverflow` 保持原样。

- [ ] **Step 4: 补 import 直到编译干净**

`AgentTools` 需要：

```java
import io.github.javaside.springai.codetui.agent.compaction.BoundedSummarizationCompactionStrategy;
import io.github.javaside.springai.codetui.agent.compaction.CalibrationState;
import io.github.javaside.springai.codetui.agent.compaction.ModelContextWindows;
import io.github.javaside.springai.codetui.agent.compaction.NotifyingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.compaction.PreflightCompactionAdvisor;
```

搬入 compaction 的类需要：

```java
import io.github.javaside.springai.codetui.agent.session.SessionTokenEstimator;
```

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。压缩策略的测试（`BoundedSummarizationCalibrationTest`、`ContextStatsTest`）尤其要看清全过。

- [ ] **Step 6: 确认 diff 只含 package/import/可见性/注释行**

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 上下文压缩策略类移入 agent.compaction 包"
```

---

## Task 7: 搬 subagent 包

**Files:**
- Move（main，5）：`SubagentFailedException` `SubagentLoader` `SubagentRunner` `SubagentSpec` `SubagentTool` → `agent/subagent/`
- Move（test，11）：`SubagentDefinitionsTest` `SubagentInstructionsTest` `SubagentLoaderTest` `SubagentRunnerBackgroundTest` `SubagentRunnerMcpToolsTest` `SubagentRunnerOkTest` `SubagentRunnerParallelTest` `SubagentRunnerTest` `SubagentRunnerThinkingTest` `SubagentToolBackgroundTest` `SubagentToolTest` → `test/agent/subagent/`

**Interfaces:**
- Consumes：`agent.prompt.PermissionModePrompt`（Task 2）、`agent.mcp.McpRegistry`（Task 3）、`agent.skill.*`（Task 1）
- Produces：`agent.subagent.SubagentRunner`、`agent.subagent.SubagentSpec`（public，被 `CodeTuiApplication` 引用）、`agent.subagent.SubagentTool`、`agent.subagent.SubagentLoader`、`agent.subagent.SubagentFailedException`

**特殊坑**：
- `SubagentRunner` 用 `RetryingChatModel.wrap(...)`。`RetryingChatModel` 此刻还在 `agent` 顶层（Task 8 才进 llm），且是包私有——**本任务必须把它升 public**，否则 `SubagentRunner` 移出后编译失败。这是 spec 里 `RetryingChatModel` 升 public 的唯一真实原因（`AgentTools` 与 `InterjectingChatModel` 只在注释里提到它）
- `SubagentRunnerOkTest` 也引用 `RetryingChatModel`，同样依赖这次升 public
- `SubagentRunnerMcpToolsTest` 用 `McpWiringTestSupport`，后者 Task 8 才进 llm，此刻在 root 顶层。本任务需给 `SubagentRunnerMcpToolsTest` 补 import 指向顶层，Task 8 再改一次

**升 public（1 个）**：`RetryingChatModel`（连同被 `SubagentRunner` 调用的 `wrap` 静态方法）。它本任务仍留在顶层 `agent` 包，Task 8 才移进 llm。

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `subagent`。

- [ ] **Step 2: 编译，收集缺失 import 与可见性错误**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期含 `RetryingChatModel is not public`。

- [ ] **Step 3: 把 RetryingChatModel 升为 public**

在仍位于 `agent/` 顶层的 `RetryingChatModel.java` 里：

```java
public final class RetryingChatModel implements ChatModel {
```

并把 `wrap` 静态方法加 `public`，类注释末尾加内部类型说明行。

- [ ] **Step 4: 补 import 直到编译干净**

`AgentTools`、`CodingAgent`、`CodeTuiApplication`、`ui/*` 需要：

```java
import io.github.javaside.springai.codetui.agent.subagent.SubagentRunner;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import io.github.javaside.springai.codetui.agent.subagent.SubagentTool;
```

搬入 subagent 的类需要指向仍在顶层的 `agent.RetryingChatModel`、`agent.AgentListener`、`agent.ToolEventCallback` 等，以及已迁移的 `agent.prompt.PermissionModePrompt`、`agent.mcp.McpRegistry`。

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。

- [ ] **Step 6: 确认 diff 只含 package/import/可见性/注释行**

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 子 agent 相关类移入 agent.subagent 包"
```

---

## Task 8: 搬 llm 包

这是最大的一步（26 main + 30 test）。若中途编译错误过多难以收敛，可按「6 家 provider 及其专属编解码」与「ChatModel 装饰器 + timeouts」分两次提交，但两次都必须各自全绿。

**Files:**
- Move（main，26）：`AnthropicProvider` `DeepSeekProvider` `DeepSeekThinkingBodyCodec` `DeepSeekThinkingChatModel` `DeepSeekThinkingChatOptions` `DeepSeekThinkingClientHttpConnector` `DynamicAuxChatModel` `LlmProvider` `LlmTimeouts` `ModelListEnv` `ModelOption` `ModelPreference` `OpenAiProvider` `OpenAiTimeouts` `OpencodeGoProvider` `ProviderModel` `ProviderRegistry` `QwenProvider` `QwenSseNormalizingHttpClient` `RetryingChatModel` `SessionIdStreamGuardAdvisor` `StreamIdleTimeoutChatModel` `StreamIdleTimeoutProvider` `UsageRecordingChatModel` `UsageRecordingProvider` `ZhipuProvider` → `agent/llm/`
- Move（test，30）：`DeepSeekProviderVisionTest` `DeepSeekThinkingBodyCodecTest` `DeepSeekThinkingChatModelTest` `DeepSeekThinkingChatModelVisionTest` `DeepSeekThinkingHttpIntegrationTest` `DeepSeekUsageStreamingTest` `DeepSeekVisionBodyCodecTest` `DeepSeekVisionSmokeTest` `DynamicAuxChatModelTest` `LlmProviderTest` `LlmTimeoutsTest` `McpWiringTestSupport` `ModelListEnvTest` `ModelPreferenceTest` `OpenAiTimeoutsTest` `ProviderCapabilitiesTest` `ProviderModelsEnvTest` `ProviderRegistryTest` `ProviderRegistryThinkingTest` `ProviderThinkingOptionsTest` `ProviderTimeoutRuntimeTest` `QwenChunkMergerHypothesisTest` `QwenRealStreamingToolCallSmokeTest` `QwenSseNormalizingHttpClientTest` `RetryingChatModelTest` `SessionIdStreamGuardAdvisorTest` `StreamIdleTimeoutChatModelTest` `StreamIdleTimeoutProviderTest` `UsageRecordingChatModelTest` `UsageRecordingProviderTest` → `test/agent/llm/`

**Interfaces:**
- Consumes：`agent.session.SessionEvents`（Task 5 已 public，被 `SessionIdStreamGuardAdvisor` 用）、`agent.session.TokenUsageAccumulator`（被 `UsageRecordingChatModel`/`UsageRecordingProvider` 用）、`agent.thinking.*`（既有子包，未动）
- Produces：
  - `agent.llm.LlmProvider`（接口）、`agent.llm.ProviderRegistry`（含嵌套 `RequestSelection`）、`agent.llm.ProviderModel`、`agent.llm.ModelOption`、`agent.llm.ModelPreference`、`agent.llm.LlmTimeouts`、`agent.llm.StreamIdleTimeoutProvider`、`agent.llm.UsageRecordingProvider`、6 家 Provider 实现（均已 public）
  - `agent.llm.RetryingChatModel`（Task 7 已升 public）
  - `agent.llm.SessionIdStreamGuardAdvisor`（**本任务升 public**，`AgentTools` 里 `new SessionIdStreamGuardAdvisor()`）
  - `agent.llm.StreamIdleTimeoutChatModel`（**本任务升 public**，留在 root 的 `CodingAgentSubmitErrorTest` 里 `new StreamIdleTimeoutChatModel(hanging, Duration.ofMillis(100))`）

**升 public（2 个）**：`SessionIdStreamGuardAdvisor`、`StreamIdleTimeoutChatModel`（含被调用的构造器）。

**保持包私有（6 个）**：`DeepSeekThinkingBodyCodec`、`DeepSeekThinkingChatModel`、`DeepSeekThinkingChatOptions`、`DeepSeekThinkingClientHttpConnector`、`QwenSseNormalizingHttpClient`、`OpenAiTimeouts`、`ModelListEnv`——调用方都随它们一起进了 llm。

**特殊坑**：
- `ModelListEnv` 看似被 `AgentTools` 引用，实际只出现在一句注释（"与 `ModelListEnv.parse` 一样把 env 值作为参数传入"）。**不要升 public**。若编译器报错说明判断有误，届时再升
- `McpWiringTestSupport` 归 llm（它造假 `ProviderRegistry`，与 MCP 无关）。它被 `SubagentRunnerMcpToolsTest`（在 subagent）与 `AgentToolsMcpWiringTest`（在 root）引用，跨包 → **须升 public**（当前声明是 `final class McpWiringTestSupport {`，加 `public`）
- 5 个真机冒烟/集成测试（`DeepSeekVisionSmokeTest`、`QwenRealStreamingToolCallSmokeTest`、`DeepSeekThinkingHttpIntegrationTest` 等）由环境变量门控，无 key 时跳过，属正常
- `media/LiveVisionEndToEndProbe`、`media/LiveVisionSequenceProbe`、`background/BackgroundNotifier` 这三个既有子包里的文件引用了顶层类，本任务可能需给它们补 import

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `llm`。26 个 main + 30 个 test。

- [ ] **Step 2: 编译，收集缺失 import 与可见性错误**

```bash
mvn -pl springai-code-tui -am test-compile
```

预期错误量最大，逐轮收敛。

- [ ] **Step 3: 升 3 个类型为 public**

```java
public final class SessionIdStreamGuardAdvisor implements StreamAdvisor {
```

```java
public final class StreamIdleTimeoutChatModel implements ChatModel {
```

测试辅助类：

```java
public final class McpWiringTestSupport {
```

前两个加内部类型说明行（`McpWiringTestSupport` 是测试辅助类，加一行说明它跨包被 root/subagent 的测试引用即可）。

- [ ] **Step 4: 补 import 直到编译干净**

`AgentTools`/`CodingAgent`/`CodeTuiApplication`/`ui/*` 需要 `agent.llm.*` 的一批 import；搬入 llm 的类需要 `agent.session.SessionEvents`、`agent.session.TokenUsageAccumulator` 等。反复编译收敛。

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。若 `CodingAgentSpikeTest.todoTurnIdBinding` 超时失败，单跑确认：

```bash
mvn -pl springai-code-tui -am test -Dtest='CodingAgentSpikeTest#todoTurnIdBinding' -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] **Step 6: 确认 diff 只含 package/import/可见性/注释行**

本任务文件多，务必逐条核对 grep 输出。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): provider 接入与 ChatModel 装饰类移入 agent.llm 包"
```

---

## Task 9: 搬 seam 包

**Files:**
- Move（main，17）：`AgentListener` `AskRequest` `AskResponder` `ModalRequest` `OptionSpec` `PermissionCancelledException` `PermissionOutcome` `PermissionRequest` `PermissionResponder` `PlanApprovalBridge` `PlanOutcome` `PlanRequest` `PlanResponder` `QuestionCancelledException` `QuestionSpec` `SubmitHandler` `UserQuestionBridge` → `agent/seam/`
- Move（test，9）：`AgentListenerAdapter` `AgentListenerCancelTest` `AgentListenerSubagentTest` `ExitPlanModeToolTest` `ModalRequestTest` `PlanApprovalBridgeTest` `PlanRequestTest` `StubListener` `UserQuestionBridgeTest` → `test/agent/seam/`

**Interfaces:**
- Consumes：`agent.permission.PermissionRule`（既有子包，`PermissionRequest` 用）、`agent.permission.PermissionMode`（`PlanApprovalBridge` 用）、`agent.session.ContextStats`（`SubmitHandler.contextStats()` 返回）
- Produces：全部 17 个类型的 `agent.seam.*` 形态。`SubmitHandler` 被 34 个包外文件引用，是本任务改动面最广的一处

**关键约束（务必先读）**：
`ModalRequest` 是 `sealed interface`，`permits AskRequest, PermissionRequest, PlanRequest`。本项目无 `module-info`（unnamed module），**permitted 子类型必须与接口同包**。所以这四个类必须在同一次移动中一起进 `agent.seam`，不能分批——分批会在中间状态编译失败（`sealed` 的 permits 引用跨包类型）。

**特殊坑**：
- 消费 `ModalRequest` 的代码用 `if (r instanceof AskRequest a) … else if …` 链（因为编译在 release 17，按类型模式 `switch` 需 21）。本任务不要"顺手优化"成 `switch`
- `PermissionRequest`、`PermissionResponder`、`PermissionOutcome`、`PermissionCancelledException` 进 `seam` 而**不是** `agent.permission`——`permission` 是纯规则包，不该依赖 UI 接缝
- `StubListener` 被 22 个测试文件引用（分布在 root、subagent、seam、tools 等多个包）→ **须升 public**（当前声明 `class StubListener implements AgentListener {`）
- `AgentListenerAdapter` 是个**包私有 interface**（`interface AgentListenerAdapter extends AgentListener {`），只被 seam 内的 `PlanApprovalBridgeTest`、`ExitPlanModeToolTest` 引用 → **保持包私有**
- `ui/ConversationState`、`ui/CodeTuiView` 大量引用 `ModalRequest`/`AskRequest`/`PermissionRequest`/`PlanRequest` 及各 Responder/Outcome，需补一批 import

**升 public（1 个测试辅助类）**：`StubListener`。main 侧 17 个类均已是 public，无需改动。

- [ ] **Step 1: 执行通用流程 Step A–C（17 个 main 必须一次性移完）**

`<PKG>` = `seam`。特别注意 `ModalRequest`、`AskRequest`、`PermissionRequest`、`PlanRequest` 必须同批移动。

- [ ] **Step 2: 编译，收集缺失 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

若出现 `permitted subclass … must be in the same package`，说明四个 sealed 相关类没搬齐，回到 Step 1 补齐。

- [ ] **Step 3: 把 StubListener 升为 public**

```java
public class StubListener implements AgentListener {
```

- [ ] **Step 4: 补 import 直到编译干净**

`ui/`、`CodeTuiApplication` 与顶层残留类需要：

```java
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.seam.ModalRequest;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionResponder;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanResponder;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.UserQuestionBridge;
import io.github.javaside.springai.codetui.agent.seam.PlanApprovalBridge;
import io.github.javaside.springai.codetui.agent.seam.PermissionCancelledException;
import io.github.javaside.springai.codetui.agent.seam.QuestionCancelledException;
```

（每个文件只加实际用到的行。）

- [ ] **Step 5: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。`ui/` 下的模态队列相关测试（`ConversationStateModalQueueTest`、`CodeTuiViewPermissionTest`、`CodeTuiViewAskTest`、`CodeTuiViewPlanTest`）尤其要确认全过。

- [ ] **Step 6: 确认 diff 只含 package/import/可见性行**

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): UI 接缝类移入 agent.seam 包"
```

---

## Task 10: 搬 tools 包并收尾核对

**Files:**
- Move（main，8）：`BochaWebSearchTool` `PermissionCallback` `RenamedToolCallback` `ResilientToolCallingManager` `ResilientToolExecutionExceptionProcessor` `TimeLimitedToolCallback` `TodoWriteToolAdapter` `ToolEventCallback` → `agent/tools/`
- Move（test，12）：`BochaWebSearchSmokeTest` `BochaWebSearchToolTest` `BraveWebSearchSmokeTest` `PermissionCallbackBackgroundTest` `PermissionCallbackTest` `PermissionTestSupport` `RenamedToolCallbackTest` `ResilientToolCallingManagerTest` `ResilientToolExecutionExceptionProcessorTest` `TimeLimitedToolCallbackTest` `TodoWriteToolAdapterTest` `ToolEventCallbackTest` → `test/agent/tools/`

**Interfaces:**
- Consumes：`agent.seam.PermissionRequest`/`PermissionResponder`/`PermissionOutcome`/`PermissionCancelledException`/`AgentListener`（Task 9）、`agent.permission.*`（既有子包：`PermissionEngine`、`PermissionBehavior`、`PermissionDecision`、`PermissionConfigLoader`、`PermissionRule`、`RuleScope`、`ToolTargets`）、`agent.media.MediaExternalizingCallback`（既有子包）
- Produces：`agent.tools.PermissionCallback`、`agent.tools.ToolEventCallback`、`agent.tools.TimeLimitedToolCallback`、`agent.tools.RenamedToolCallback`、`agent.tools.BochaWebSearchTool`、`agent.tools.TodoWriteToolAdapter`、`agent.tools.ResilientToolCallingManager`、`agent.tools.ResilientToolExecutionExceptionProcessor`

**特殊坑**：
- 装饰链顺序是 `PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )`，`PermissionCallback` 必须在最外层。本任务只搬位置，**不要调整装饰顺序**
- `PermissionCallback` 放 `tools` 而非 `permission`：它是 ToolCallback 装饰链最外层，与 `ToolEventCallback` 同类；放 `permission` 会让纯规则包反向依赖 `seam`
- `media/MediaExternalizingCallback`、`background/BackgroundNotifier` 引用了 `ToolEventCallback`/`AgentListener`，需补 import
- `PermissionTestSupport` 只被同包 `PermissionCallbackBackgroundTest` 引用 → 保持包私有
- `ui/CodeTuiView` 引用 `PermissionCallback`，需补 import

**升 public**：无。8 个类均已是 public。

- [ ] **Step 1: 执行通用流程 Step A–C**

`<PKG>` = `tools`。

- [ ] **Step 2: 编译，收集缺失 import**

```bash
mvn -pl springai-code-tui -am test-compile
```

- [ ] **Step 3: 补 import 直到编译干净**

```java
import io.github.javaside.springai.codetui.agent.tools.PermissionCallback;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import io.github.javaside.springai.codetui.agent.tools.TimeLimitedToolCallback;
import io.github.javaside.springai.codetui.agent.tools.RenamedToolCallback;
import io.github.javaside.springai.codetui.agent.tools.BochaWebSearchTool;
import io.github.javaside.springai.codetui.agent.tools.TodoWriteToolAdapter;
import io.github.javaside.springai.codetui.agent.tools.ResilientToolCallingManager;
import io.github.javaside.springai.codetui.agent.tools.ResilientToolExecutionExceptionProcessor;
```

- [ ] **Step 4: 跑全量测试**

```bash
mvn -pl springai-code-tui -am test
```

预期：BUILD SUCCESS。权限相关测试（`PermissionWiringTest`、`PermissionStartupTest`、`ui/CodeTuiViewPermissionTest`、`permission/` 下 20 个）要确认全过。

- [ ] **Step 5: 核对顶层只剩 2 个文件**

```bash
ls springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/*.java
```

预期恰好两行：`AgentTools.java`、`CodingAgent.java`。多出任何文件说明前面某步漏搬。

- [ ] **Step 6: 核对各包类数与 spec 一致**

```bash
cd springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent
for d in . llm mcp subagent skill session compaction seam tools prompt interjection media permission background thinking; do
  printf "%-14s %s\n" "$d" "$(ls $d/*.java 2>/dev/null | wc -l)"
done
```

预期：`.` 2、llm 26、mcp 7、subagent 5、skill 3、session 7、compaction 7、seam 17、tools 8、prompt 3、interjection 3，以及未动的 media 28、permission 15、background 7、thinking 6。

- [ ] **Step 6.5: 提权回退复查 + javadoc 断链修复（收口清理）**

Task 1–9 的执行中出现过 brief 未预料的提权。逐项核对它们当前的跨包调用方，把「仅由留在 root 的测试驱动、未来可回退」的记入报告（本任务不改代码，为后续清理窗口留清单）：

- `Interjections.drainForInjection` / `fireDelivered`：Task 4 审查确认仅 `CodingAgentInterjectionFacadeTest`（root）驱动，若把该测试搬进 `agent.interjection` 即可回退提权
- `McpRegistry.initForTest(3参)` / `addConnectedForTest` / `decorate`：由留在 root 的 `PermissionWiringTest`/`AgentToolsMcpWiringTest`/`CodingAgentMcpInjectionTest`/`SubagentRunnerMcpToolsTest` 驱动
- `AgentTools.testEngine`：撤销了 `4cb2c50` 的封装决定，且造成 `agent ↔ agent.mcp` 包循环——重构收尾后建议挪到中立位置（permission 侧测试支撑类）以断循环并收回可见性
- 本任务执行中新发现的其他「仅测试驱动」提权，一并记入

```bash
grep -rn "内部类型" springai-code-tui/src/main/java --include=*.java | grep -v "^Binary"
```

预期输出即全部提权处的标注行，逐行回查其调用方。

同时修复搬迁累积的 javadoc 断链（Task 5 审查发现 4 处，属「import 行/注释行」两类允许改动）。以 doclint 的报告为准，修法二选一：给 javadoc 专用的 import（若代码本体不用该类型，加在 import 块即可）；或把 `{@link X}` 降级为 `{@code X}`（与相邻写法统一）。修完验证：

```bash
mvn -pl springai-code-tui javadoc:javadoc
```

预期：0 error（doclint=all,-missing 只拦真错）。


- [ ] **Step 7: 全量核对 diff 无方法体改动**

对整个重构区间跑一次（`<BASE>` 为 Task 1 之前的提交）：

```bash
git diff <BASE>..HEAD -- '*.java' | grep -E '^[+-]' | grep -vE '^[+-]{3}' | grep -vE '^[+-]\s*(package|import) ' | grep -vE '^[+-]\s*\*' | grep -vE '^[+-](public )?(final |abstract |sealed |static )*(class|interface|record|enum) '
```

剩余输出应只有 Task 5/6/7/8 里被放宽可见性的方法签名行（`static` → `public static`）。逐条确认参数与返回类型逐字符未变。

- [ ] **Step 8: 更新 CHANGELOG**

在 CHANGELOG.md 顶部的未发布段落加一条：

```markdown
- **重构**：`codetui.agent` 顶层 88 个类按功能拆进 10 个子包（`llm`/`mcp`/`subagent`/`skill`/`session`/`compaction`/`seam`/`tools`/`prompt`/`interjection`），顶层只留 `CodingAgent` 与 `AgentTools`。行为无变化。`docs/` 下 2026-08-28 之前的历史文档里的 `codetui.agent.*` 类路径按当时结构书写，未随本次调整更新。
```

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "refactor(code-tui): 工具装饰器移入 agent.tools 包并收尾核对"
```

---

## 自检记录

对照 spec 逐节核过：

- 包划分 10 个子包 + 根 → Task 1–10 逐一对应，类数合计 88（2+26+7+5+3+7+7+17+8+3+3）
- 测试迁移 121 个文件 → 各任务清单合计 121（2+3+9+3+5+6+11+30+9+12+31，其中 31 个留在 root 不动）
- 10 个升 public 的类 → 分布在 Task 4（`InterjectingChatModel`）、Task 5（`SessionEvents`、`SessionTokenEstimator`）、Task 6（4 个 compaction 类）、Task 7（`RetryingChatModel`）、Task 8（`SessionIdStreamGuardAdvisor`、`StreamIdleTimeoutChatModel`）
- 2 个升 public 的测试辅助类 → Task 8（`McpWiringTestSupport`）、Task 9（`StubListener`）
- `ModalRequest` sealed 同包约束 → Task 9 显式标注四个类必须同批移动
- 文档处理 → Task 10 Step 8
- 验证纪律（每步全绿、flaky 处理、diff 只含允许改动）→ 通用流程 Step F/G 与各任务末尾
