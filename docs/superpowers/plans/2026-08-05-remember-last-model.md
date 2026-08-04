# 记住上次使用的大模型 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/model` 选中的模型落盘到 `<root>/.codetui/model.json`，下次启动（**不带 `-c`**）自动恢复。

**Architecture:** 新增无状态工具类 `ModelPreference`（读/写单键 JSON，全程降级不抛）。写发生在 `CodeTuiView` 的选择器 Enter 分支——**且只在 `selectModel` 真的生效后才写**。读发生在 `CodeTuiApplication` 构造完 `ProviderRegistry` 之后，调 `registry.select(id)`；`select()` 对未知模型静默忽略这个既有行为直接当失效判据，不新增任何 API。

**Tech Stack:** Java 17、Jackson 3（`tools.jackson`）、JUnit 5、TamboUI 0.4.0、pty + pyte 冒烟。

**分支：** `feat/remember-last-model`（已存在，spec 已提交在 `0cebf58`）

**Spec：** `docs/superpowers/specs/2026-08-05-remember-last-model-design.md`

---

## 全局纪律（每个任务都适用）

1. **验证命令必须带 `-pl springai-code-tui`**：
   ```bash
   mvn test -pl springai-code-tui -Dtest=SomeTest
   ```
   整仓 `mvn test -Dtest=…` 会被仓库里 3 个空模块打挂。**不要**用 `-DfailIfNoSpecifiedTests=false` 去盖这个问题。

2. **编译错不是合格的红**。TDD 第 2 步「跑一遍确认它是红的」，红的理由必须是**断言失败**，不能是「类还不存在编译不过」。若某步会因缺类而编译失败，就先建出**空壳类/桩方法**让它编译得过、再让断言红。每个任务里我都标了预期的红是什么样。

3. **本分支基线测试数 = 1262**（`Tests run: 1262, Failures: 0, Errors: 0, Skipped: 9`）。每个任务标了做完后的预期总数，对不上就说明有测试没被执行或多写了。

4. **提交信息用中文**，跟仓库既有风格一致。

5. **不要 `git stash`、不要 `git commit --amend`**：这个仓库可能有并行 agent 共用工作树，两者都会波及别人的改动。只能追加新提交。

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModelPreference.java` | 模型偏好的读/写，单键 JSON，全程降级不抛。**不认识 `ProviderRegistry`，也不认识 UI**——只认 `Path` 和 `String`。 | 新建 |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModelPreferenceTest.java` | 上面那个类的读写与全部降级路径 | 新建 |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java` | 加包私有静态 `restoreLastModel(...)`，并在 `main` 里接上 | 改 |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationModelRestoreTest.java` | 启动恢复的三条路径（成功 / 失效回退 / 无记忆） | 新建 |
| `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java` | 选择器 Enter 分支加写盘 + 失败多打一行 | 改（`onModelPickerKey`，约 1300-1311 行） |
| `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModelMemoryTest.java` | 写侧接线：写了 / 写失败多一行 / 没生效不写 | 新建 |
| `springai-code-tui/src/test/resources/scripts/model_memory_smoke.py` | pty 端到端：进程 A 选 → 退出 → 进程 B 起来就是它 | 新建 |
| `springai-code-tui/src/test/resources/scripts/README.md` | 冒烟脚本清单加一行 + 运行命令加一行 | 改 |
| `springai-code-tui/README.md` | `.codetui/` 文件清单加 `model.json`；补「与权限模式不对称」的说明 | 改 |

---

### Task 1: `ModelPreference` — 读写与降级

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModelPreference.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModelPreferenceTest.java`

**背景（实施者需要知道的）：** 这个仓库用的是 **Jackson 3**（包名 `tools.jackson.*`，不是 `com.fasterxml.jackson.*`）。Jackson 3 里 `JsonNode.stringValue()` 对非文本节点**会抛异常**，所以取值前必须先 `isString()`。原子写的范式照抄 `PermissionConfigWriter.writeAtomically`（同一个模块，`agent/permission/` 下）。

- [ ] **Step 1: 先建空壳，让测试编译得过**

`ModelPreference.java`：

```java
package io.github.javaside.springai.codetui.agent;

import java.nio.file.Path;
import java.util.Optional;

public final class ModelPreference {

    private ModelPreference() {
    }

    public static Path fileFor(Path root) {
        return root.resolve(".codetui").resolve("model.json");
    }

    public static Optional<String> read(Path root) {
        return Optional.empty();
    }

    public static boolean write(Path root, String modelId) {
        return false;
    }
}
```

**为什么先建空壳**：不建的话下一步的测试是「编译不过」，那不是合格的红——它证明不了断言本身写对了。

- [ ] **Step 2: 写全部 9 条失败测试**

`ModelPreferenceTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelPreference} 的读写与全部降级路径。
 *
 * <p><b>降级契约是重点</b>：这个类跑在启动路径上，任何一条路抛异常都等于 code-tui 起不来。
 * 为一个「上次用了哪个模型」的偏好把整个工具搞挂，完全不值。
 */
class ModelPreferenceTest {

    @Test
    @DisplayName("写进去再读出来是同一个 id")
    void writeThenReadRoundTrips(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "deepseek-v4-flash"));
        assertEquals(Optional.of("deepseek-v4-flash"), ModelPreference.read(root));
    }

    @Test
    @DisplayName("文件不存在 → empty（首次运行是常态，不是错误）")
    void missingFileIsEmpty(@TempDir Path root) {
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("JSON 非法 → empty，且绝不抛")
    void malformedJsonIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("缺 lastModel 键 → empty")
    void missingKeyIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"somethingElse\": \"x\"}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("lastModel 不是字符串 → empty（Jackson 3 的 stringValue() 对非文本节点会抛）")
    void nonStringValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": 42}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName("lastModel 是空串或全空白 → empty")
    void blankValueIsEmpty(@TempDir Path root) throws Exception {
        Path f = ModelPreference.fileFor(root);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"lastModel\": \"   \"}");
        assertEquals(Optional.empty(), ModelPreference.read(root));
    }

    @Test
    @DisplayName(".codetui/ 不存在时 write 会把目录建出来")
    void writeCreatesCodetuiDirectory(@TempDir Path root) {
        assertFalse(Files.exists(root.resolve(".codetui")), "前提：目录本来不存在");
        assertTrue(ModelPreference.write(root, "claude-opus-5"));
        assertTrue(Files.isRegularFile(ModelPreference.fileFor(root)));
    }

    /**
     * 写不进去时必须返回 false 而不是抛。
     *
     * <p><b>用「.codetui 是个普通文件」而不是 chmod 去制造失败</b>：chmod 那条路在 root 用户下
     * POSIX 权限位根本不拦人，测试会静默变绿——那种测试比没有还糟。占位文件这招对谁都成立。
     */
    @Test
    @DisplayName("写不进去 → false，且绝不抛")
    void writeFailureReturnsFalse(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "我不是目录");
        assertFalse(ModelPreference.write(root, "deepseek-v4-pro"));
    }

    /**
     * 原子写用的临时文件必须被 move 走，不能留在 .codetui/ 里堆积。
     * 用户会打开这个目录看，满地 .tmp 是会让人以为出了故障的。
     */
    @Test
    @DisplayName("写完不留 .tmp 残骸")
    void noTempFileLeftBehind(@TempDir Path root) throws Exception {
        assertTrue(ModelPreference.write(root, "deepseek-v4-flash"));
        try (var s = Files.list(root.resolve(".codetui"))) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("model.json"), names, "目录里只该有 model.json:" + names);
        }
    }
}
```

- [ ] **Step 3: 跑一遍确认是红的**

```bash
mvn test -pl springai-code-tui -Dtest=ModelPreferenceTest
```

预期：**编译通过**，`Tests run: 9, Failures: 8`（`missingFileIsEmpty` 会绿，因为空壳 `read` 就返回 `empty`——这条是搭便车绿的，不代表实现对了）。红的理由必须是断言失败，**不能有 compilation error**。

- [ ] **Step 4: 写实现**

把 `ModelPreference.java` 整个替换成：

```java
package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * 「上次用的模型」的落盘：{@code <root>/.codetui/model.json}，单键 {@code lastModel}。
 *
 * <p><b>键名是 lastModel 而不是 model</b>：在名字上说清这是「上次用的」，不是「你配置的默认」。
 * 将来真要加显式配置项，两者可以共存而不打架。
 *
 * <p><b>只记 modelId，不记 provider</b>：{@code ProviderRegistry.select(String)} 的既有语义
 * 就是「在可用 provider 里找拥有该 id 的那家」。{@code *_MODELS} 环境变量可能造成跨家重名，
 * 此时命中列表序靠前的可用家——但 {@code /model} 面板本身也只能按 id 选，UI 层面同样区分不了重名。
 * 只记 id 与现有交互完全一致，不引入新的不一致。这是已知限制，不是疏忽。
 *
 * <p><b>降级契约</b>：读侧任何情况都返回 {@link Optional}、写侧任何情况都返回 boolean，
 * <b>两边都绝不抛</b>。这个类跑在启动路径上，抛一次异常就是 code-tui 起不来。
 *
 * <h2>为什么整份覆盖，而不是照 permissions.json 那样读-改-写</h2>
 * <p>{@code permissions.json} 里有用户手写的规则和未知字段，必须原样保留，所以那边走
 * Jackson 树模型读-改-写。本文件是<b>纯机器写的单键文件</b>，没有用户内容要保护，整份覆盖
 * 更简单也更不容易写坏。
 *
 * <p><b>绊线</b>：这个文件哪天长出第二个键，写侧就<b>必须</b>改回读-改-写，
 * 否则整份覆盖会悄悄吃掉另一个键。
 *
 * <h2>并发</h2>
 * <p>进程内不加锁：写只发生在 UI 线程的 {@code /model} 选中分支。
 * （{@code PermissionConfigWriter} 那把静态锁是因为工具并行审批会同时回写，这里没有对应场景。）
 * 跨进程（同一项目开两个窗口）是 last-writer-wins：单键文件，最坏结果是「记住了另一个窗口选的模型」；
 * 临时文件名带随机后缀，不会互相写坏。
 */
public final class ModelPreference {

    private static final Logger log = LoggerFactory.getLogger(ModelPreference.class);

    private static final String KEY = "lastModel";

    /** 与 {@code PermissionConfigLoader}/{@code Writer} 同一套解析开关：重复键直接判非法。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private ModelPreference() {
    }

    /** 偏好文件路径。暴露出来是为了让测试能直接摆一个坏文件进去。 */
    public static Path fileFor(Path root) {
        return root.resolve(".codetui").resolve("model.json");
    }

    /** 读上次用的模型 id。缺失/坏文件/空值一律 {@link Optional#empty()}，绝不抛。 */
    public static Optional<String> read(Path root) {
        if (root == null) {
            return Optional.empty();
        }
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();      // 首次运行是常态，不是错误——刻意不打日志
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (Exception e) {
            log.warn("读不出模型偏好 {}（{}），本次按无记忆处理。", file, e.getMessage());
            return Optional.empty();
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception e) {
            log.warn("模型偏好 {} 不是合法 JSON（{}），本次按无记忆处理。", file, e.getMessage());
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode v = node.get(KEY);
        if (v == null || !v.isString()) {     // Jackson 3：非文本节点调 stringValue() 会抛
            return Optional.empty();
        }
        String id = v.stringValue().trim();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    /**
     * 记住这个模型 id。失败返回 false 且<b>不改动原文件</b>，绝不抛。
     *
     * <p>原子写：先写同目录临时文件（随机后缀，两个进程同时写不会互相写坏），
     * 再 {@code ATOMIC_MOVE}；不支持的文件系统降级普通替换。
     */
    public static boolean write(Path root, String modelId) {
        if (root == null || modelId == null || modelId.isBlank()) {
            return false;
        }
        Path file = fileFor(root);
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());     // 全新项目还没有 .codetui/
            ObjectNode node = MAPPER.createObjectNode();
            node.put(KEY, modelId);
            tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            cleanup(tmp);
            log.warn("模型偏好没能落盘 {}（{}），仅本次运行生效。", file, e.getMessage());
            return false;
        }
    }

    private static void cleanup(Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // 清不掉就算了：留一个 .tmp 远比在失败路径上再抛一次好
        }
    }
}
```

- [ ] **Step 5: 跑一遍确认全绿**

```bash
mvn test -pl springai-code-tui -Dtest=ModelPreferenceTest
```

预期：`Tests run: 9, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/ModelPreference.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ModelPreferenceTest.java
git commit -m "feat(model): ModelPreference——把上次用的模型落到 .codetui/model.json

单键 lastModel，原子写，读写两侧全程降级不抛：这个类跑在启动路径上，
抛一次就是 code-tui 起不来。

写失败的测试用「.codetui 是个普通文件」制造，不用 chmod——后者在 root
下权限位不拦人，测试会静默变绿。"
```

**做完后测试总数：1262 + 9 = 1271**

---

### Task 2: 启动恢复 `restoreLastModel`

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（`main` 里约第 57 行之后接线；新方法加在 `hasContinueFlag` 附近的静态工具方法区）
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderRegistryTest.java:37-42`（给既有测试补一句注释，不改断言）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationModelRestoreTest.java`

**背景（实施者需要知道的）：**

`main()` 本身测不了（它会起 TUI）。所以恢复逻辑**必须**抽成包私有静态方法单独测——不抽出来这个装配点就是零覆盖。这条写得很硬，因为这个项目上一轮刚吃过一模一样的亏：`TaskOutput` 的 `ToolRegistry` 注册完全没有测试，是代码审查翻出来的。

失效检测的地基是 `ProviderRegistry.select()` 对未知模型**静默忽略**。`ProviderRegistryTest.selectUnknownModelIsIgnored`（第 37-42 行）已经钉住了这个行为，断言是 `assertEquals("deepseek-v4-pro", reg.activeModelId())`——**断言是对的，不需要补测试**，只需要加一句注释说明它现在是承重的。

测试里能拿到的现成模型 id（来自各 provider 的内置清单）：

| provider | 默认模型 | 另一个 |
|---|---|---|
| `new DeepSeekProvider("k")` | `deepseek-v4-pro` | `deepseek-v4-flash` |
| `new AnthropicProvider("k")` | `claude-sonnet-5` | `claude-opus-5` |
| `new OpenAiProvider("")` | 不可用（空 key） | 其模型 `gpt-5.5` 不会出现在清单里 |

- [ ] **Step 1: 先加空壳方法，让测试编译得过**

在 `CodeTuiApplication.java` 里，`hasContinueFlag` 方法的**正上方**加：

```java
    static void restoreLastModel(ProviderRegistry registry, Path root, ConversationState state) {
    }
```

需要的 import（检查是否已存在，缺则补）：

```java
import io.github.javaside.springai.codetui.agent.ModelPreference;
import io.github.javaside.springai.codetui.ui.ConversationState;
import java.util.Optional;
```

（`ProviderRegistry` 和 `java.nio.file.Path` 该文件已经 import 了。）

- [ ] **Step 2: 写 3 条失败测试**

`CodeTuiApplicationModelRestoreTest.java`：

```java
package io.github.javaside.springai.codetui;

import io.github.javaside.springai.codetui.agent.AnthropicProvider;
import io.github.javaside.springai.codetui.agent.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.ModelPreference;
import io.github.javaside.springai.codetui.agent.ProviderRegistry;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动时把「上次用的模型」恢复回来。
 *
 * <p><b>为什么这段逻辑是从 main 里抽出来的</b>：{@code main} 会起 TUI，测不了。
 * 不抽出来，这个装配点就是零覆盖——而装配点恰恰是最容易接错、又最不容易被发现的地方。
 */
class CodeTuiApplicationModelRestoreTest {

    /** deepseek（默认 deepseek-v4-pro）+ anthropic，两家都可用。 */
    private static ProviderRegistry registry() {
        return new ProviderRegistry(List.of(new DeepSeekProvider("k"), new AnthropicProvider("k")));
    }

    @Test
    @DisplayName("记住的模型可用：激活它，且一声不吭")
    void restoresRememberedModel(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "claude-opus-5"), "前提：偏好写得进去");
        ProviderRegistry reg = registry();
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("claude-opus-5", reg.activeModelId());
        assertEquals("anthropic", reg.active().id(), "跨家恢复：provider 也要跟着切");
        assertTrue(state.drainPending().isEmpty(), "恢复成功是常态，不该拿一行提示去打扰用户");
    }

    /**
     * 失效兜底。杀掉「删掉 activeModelId 比对」这个变异——没有那道比对，
     * 回退会静默发生，用户只会觉得「记忆功能坏了」。
     */
    @Test
    @DisplayName("记住的模型用不了了：回退默认，并说清楚回退到了哪")
    void unavailableModelFallsBackAndSaysSo(@TempDir Path root) {
        assertTrue(ModelPreference.write(root, "gpt-5.5"), "前提：偏好写得进去");
        ProviderRegistry reg = registry();      // 没有 openai ⇒ gpt-5.5 选不中
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("deepseek-v4-pro", reg.activeModelId(), "选不中就该保持默认");
        List<ConversationState.OutputLine> lines = state.drainPending();
        assertEquals(1, lines.size(), "该有且只有一行提示:" + lines);
        String t = lines.get(0).text();
        assertTrue(t.contains("gpt-5.5"), "要说清是哪个模型没了:" + t);
        assertTrue(t.contains("deepseek-v4-pro"), "也要说清现在用的是哪个:" + t);
    }

    @Test
    @DisplayName("没有记忆：什么都不做，走现在的行为")
    void noMemoryChangesNothing(@TempDir Path root) {
        ProviderRegistry reg = registry();
        ConversationState state = new ConversationState();

        CodeTuiApplication.restoreLastModel(reg, root, state);

        assertEquals("deepseek-v4-pro", reg.activeModelId());
        assertTrue(state.drainPending().isEmpty(), "首次运行不该冒出任何提示");
    }
}
```

- [ ] **Step 3: 跑一遍确认是红的**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiApplicationModelRestoreTest
```

预期：**编译通过**，`Tests run: 3, Failures: 2`（`noMemoryChangesNothing` 会搭便车绿——空方法本来就什么都不做）。红的理由必须是断言失败。

- [ ] **Step 4: 写实现**

把空壳方法替换成：

```java
    /**
     * 恢复上次用的模型（{@code <root>/.codetui/model.json}）。
     *
     * <p><b>为什么是「构造完 registry 之后 select」而不是做成构造入参</b>：
     * {@link ProviderRegistry} 不该认识磁盘，且改构造签名要牵动全部调用点与测试。
     * 构造后 select 还白送一个失效检测——{@code select()} 对未知模型是<b>静默忽略</b>的，
     * 所以 select 完比一下 {@code activeModelId()} 是不是等于要恢复的那个，
     * 不等就说明「这个模型现在用不了了」。不需要新增任何 API。
     * （那条静默忽略的行为由 {@code ProviderRegistryTest.selectUnknownModelIsIgnored} 钉着。）
     *
     * <p><b>必须在 {@link ConversationState} 建好之后调用</b>：回退提示要走
     * {@code state.pushInfo} 落进开场 scrollback，和权限模式的开场提示同一条路。
     *
     * <p><b>与 {@code -c} 正交</b>：模型记忆独立于会话恢复，不带 {@code -c} 的默认启动
     * 照样生效——那正是这个功能存在的理由。
     */
    static void restoreLastModel(ProviderRegistry registry, Path root, ConversationState state) {
        Optional<String> remembered = ModelPreference.read(root);
        if (remembered.isEmpty()) {
            return;                            // 首次运行：走原有行为，一个字都不多说
        }
        String id = remembered.get();
        registry.select(id);
        if (!id.equals(registry.activeModelId())) {
            state.pushInfo("• 上次用的模型 " + id + " 现在不可用，已回退到 "
                    + registry.activeModelId() + "。");
        }
    }
```

- [ ] **Step 5: 跑一遍确认全绿**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiApplicationModelRestoreTest
```

预期：`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: 在 `main` 里接上**

在 `CodeTuiApplication.main` 里找到这两行（约第 57-58 行）：

```java
        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();
```

在它们之间插入：

```java
        restoreLastModel(registry, root, state);                 // 上次用的模型（<root>/.codetui/model.json）
```

- [ ] **Step 7: 给承重的既有测试补一句注释**

`ProviderRegistryTest.java` 第 37 行的 `@Test` 上方，加：

```java
    /**
     * <b>这条现在是承重的</b>：{@code CodeTuiApplication.restoreLastModel} 拿
     * 「select 完 activeModelId 变没变」当「记住的模型还可用吗」的判据。
     * 这里一旦改成「未知模型抛异常」或「回退到默认」，那边的失效检测就会跟着错，
     * 而它自己的测试未必看得出来。
     */
```

- [ ] **Step 8: 跑全量确认没打挂别处**

```bash
mvn test -pl springai-code-tui
```

预期：`Tests run: 1274, Failures: 0, Errors: 0, Skipped: 9`

- [ ] **Step 9: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationModelRestoreTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/ProviderRegistryTest.java
git commit -m "feat(model): 启动时恢复上次用的模型

抽成包私有静态 restoreLastModel 单独测——main 会起 TUI 测不了，不抽
出来这个装配点就是零覆盖。

失效检测不新增 API：select() 对未知模型静默忽略，select 完比一下
activeModelId 就知道这个模型还在不在。顺手给 ProviderRegistryTest
那条承重的测试补了注释。"
```

**做完后测试总数：1271 + 3 = 1274**

---

### Task 3: 选中时写盘（`CodeTuiView`）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（`onModelPickerKey` 的 ENTER 分支，约 1300-1311 行）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModelMemoryTest.java`

**背景（实施者需要知道的）：**

写盘为什么落在视图层而不是 `CodingAgent`：`CodingAgent` 没有 `root` 字段，且它的构造函数是一条 **11 个重载的伸缩链**（`CodingAgent.java:89` 到 `:233`），加参数要么改一整条链、要么破坏它全 `final` 字段的写法。而 `CodeTuiView` 已经持有 `root`（`:143`），选中后那行 `⚙ 已切换模型` 确认信息也在它手里。

测试驱动按键的方式：`v.setInputForTest("/model")` + `feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER))` 打开选择器，然后 `feedKeyForTest(KeyEvent.ofChar('2'))` 数字快选第 2 项，再 ENTER 确认。`setInputForTest` / `feedKeyForTest` 都是包私有的，测试类必须放在 `io.github.javaside.springai.codetui.ui` 包下。

- [ ] **Step 1: 写 3 条失败测试**

`CodeTuiViewModelMemoryTest.java`：

```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.ModelOption;
import io.github.javaside.springai.codetui.agent.ModelPreference;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /model} 选中即落盘，下次启动才恢复得回来。
 *
 * <p><b>三条都要测</b>：写成功（主路径）、写失败（用户必须知道这次没记住）、
 * 以及<b>选中没生效就不写</b>——最后这条防的是「把一个选不中的 id 落到盘上，
 * 下次启动再触发一次『用不了，已回退』」，自己给自己制造失效记录。
 */
class CodeTuiViewModelMemoryTest {

    /** 两个模型；selectModel 只接受清单里的（与 ProviderRegistry.select 同语义）。 */
    private static class Handler implements SubmitHandler {
        final List<ModelOption> options = List.of(
                new ModelOption("alpha", "alpha", "第一个"),
                new ModelOption("beta", "beta", "第二个"));
        String current = "alpha";

        @Override public Disposable submit(String text) { return () -> { }; }
        @Override public List<ModelOption> models() { return options; }
        @Override public String currentModel() { return current; }
        @Override public void selectModel(String id) {
            for (ModelOption m : options) {
                if (m.id().equals(id)) { current = id; return; }
            }
            // 未知 id：静默忽略
        }
    }

    /** selectModel 一律不生效——模拟「选了个 registry 里其实没有的模型」。 */
    private static final class DeafHandler extends Handler {
        @Override public void selectModel(String id) { /* 什么都不做 */ }
    }

    /** 打开 /model 选择器 → 数字快选第 2 项 → Enter 确认。 */
    private static void pickSecondModel(CodeTuiView v) {
        v.setInputForTest("/model");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        v.feedKeyForTest(KeyEvent.ofChar('2'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("选中即落盘：下次启动读得到的就是它")
    void selectingWritesPreference(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new Handler(), root);

        pickSecondModel(v);

        assertEquals(Optional.of("beta"), ModelPreference.read(root));
    }

    /**
     * 写失败必须告诉用户。静默失败下次启动还是老模型，用户只会觉得「这功能坏了」，
     * 而且不知道该去看什么。
     */
    @Test
    @DisplayName("写不进去：多打一行「仅本次运行生效」")
    void writeFailureAddsExtraLine(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "我不是目录");   // 让写必然失败
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new Handler(), root);

        pickSecondModel(v);

        List<String> texts = state.drainPending().stream()
                .map(ConversationState.OutputLine::text).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("没能记住")),
                "写失败必须有一行说明:" + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains("已切换模型")),
                "本次运行内确实切换了，那行确认不能因为写失败就消失:" + texts);
    }

    /**
     * 杀掉「无条件写」这个变异。
     */
    @Test
    @DisplayName("选中没生效：一个字节都不许落盘")
    void ineffectiveSelectionWritesNothing(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new DeafHandler(), root);

        pickSecondModel(v);

        assertFalse(Files.exists(ModelPreference.fileFor(root)),
                "没生效的选择不该留下记录，否则下次启动会白白触发一次「用不了，已回退」");
    }
}
```

- [ ] **Step 2: 跑一遍确认是红的**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewModelMemoryTest
```

预期：**编译通过**，`Tests run: 3, Failures: 2`。`ineffectiveSelectionWritesNothing` 会搭便车绿——现在压根就没有写盘代码，文件当然不存在。它要到 Step 5 的变异验证里才真正被证明有用。

- [ ] **Step 3: 加 import**

`CodeTuiView.java` 的 import 区加一行（放在既有的 `io.github.javaside.springai.codetui.agent.ModelOption` 附近，保持字母序）：

```java
import io.github.javaside.springai.codetui.agent.ModelPreference;
```

- [ ] **Step 4: 改 ENTER 分支 + 加私有方法**

在 `onModelPickerKey` 里，把这一段：

```java
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            ModelOption chosen = models.get(pickIndex);
            onSubmit.selectModel(chosen.id());
            pickingModel = false;
            // 不用 sticky notice：notice 会一直占据状态栏、遮蔽常态行（模型名 + 上下文%）直到下次按键，
            // 造成「切换模型后状态栏信息就没了」。改为下沉一行 scrollback 确认，状态栏立刻回到常态。
            state.pushInfo("⚙ 已切换模型 · " + chosen.label());
            lastShownModel = chosen.id();   // 避免下个回合 dispatch 再重复打「⚙ 使用模型」
            return EventResult.HANDLED;
        }
```

替换成（只多了一行 `rememberModel`）：

```java
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            ModelOption chosen = models.get(pickIndex);
            onSubmit.selectModel(chosen.id());
            pickingModel = false;
            // 不用 sticky notice：notice 会一直占据状态栏、遮蔽常态行（模型名 + 上下文%）直到下次按键，
            // 造成「切换模型后状态栏信息就没了」。改为下沉一行 scrollback 确认，状态栏立刻回到常态。
            state.pushInfo("⚙ 已切换模型 · " + chosen.label());
            rememberModel(chosen.id());     // 落盘，下次启动恢复
            lastShownModel = chosen.id();   // 避免下个回合 dispatch 再重复打「⚙ 使用模型」
            return EventResult.HANDLED;
        }
```

紧接在 `onModelPickerKey` 方法**之后**，加这个私有方法：

```java
    /**
     * 把选中的模型记到 {@code <root>/.codetui/model.json}，下次启动自动恢复
     * （见 {@code CodeTuiApplication.restoreLastModel}）。
     *
     * <p><b>只在 selectModel 真的生效后才写</b>：{@code ProviderRegistry.select()} 对未知模型
     * 是静默忽略的，不判这一下就会把一个选不中的 id 落到盘上，下次启动再触发一次
     * 「上次用的模型现在不可用」——自己给自己制造失效记录。
     *
     * <p><b>写失败要说出来</b>：静默失败的话，下次启动还是老模型，用户只会觉得这功能坏了，
     * 而且不知道该去看什么。措辞与权限规则回写失败时的「仅本次运行生效」对齐。
     *
     * <p><b>这是持久化的唯一入口</b>：{@code selectModel} 在生产代码里当前只有本文件
     * 一个调用方（选择器的 Enter 分支）。日后若新增 {@code /model <id>} 这类直接命令、
     * 或任何其它切换模型的入口，<b>必须一并接上这里</b>，否则会出现「切了但没记住」。
     */
    private void rememberModel(String id) {
        if (!id.equals(onSubmit.currentModel())) {
            return;                          // 没生效，不留记录
        }
        if (!ModelPreference.write(root, id)) {
            state.pushInfo("⚠ 没能记住这个选择（仅本次运行生效）");
        }
    }
```

- [ ] **Step 5: 跑一遍确认全绿**

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewModelMemoryTest
```

预期：`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: 变异验证——证明「只在生效时写」那条测试真的有用**

把 `rememberModel` 开头那三行守卫**临时删掉**：

```java
    private void rememberModel(String id) {
        if (!ModelPreference.write(root, id)) {
            state.pushInfo("⚠ 没能记住这个选择（仅本次运行生效）");
        }
    }
```

再跑：

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiViewModelMemoryTest
```

预期：`ineffectiveSelectionWritesNothing` **必须红**，且失败信息是「没生效的选择不该留下记录」，**不是编译错**。确认之后把守卫改回去，再跑一遍确认恢复全绿。

- [ ] **Step 7: 跑全量**

```bash
mvn test -pl springai-code-tui
```

预期：`Tests run: 1277, Failures: 0, Errors: 0, Skipped: 9`

- [ ] **Step 8: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewModelMemoryTest.java
git commit -m "feat(model): /model 选中即落盘

只在 selectModel 真的生效后才写：select() 对未知模型静默忽略，不判这
一下就会把选不中的 id 落到盘上，下次启动再触发一次「用不了，已回退」。

写失败多打一行「仅本次运行生效」，措辞与权限规则回写失败对齐——静默
失败的话用户只会觉得功能坏了，还不知道该去看什么。"
```

**做完后测试总数：1274 + 3 = 1277**

---

### Task 4: pty 端到端冒烟

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/model_memory_smoke.py`

**背景（实施者需要知道的）：**

这个功能的价值**全在「重启之后」**。前三个任务的单测证明不了装配顺序对不对——`restoreLastModel` 可以写得完全正确，却因为在 `main` 里插错了位置（比如插在 `ProviderRegistry` 构造之前）而完全不生效，单测一个都不会红。只有真起两个进程才证得了。

脚本复用 `clear_smoke.py` 里的 `PtySession` / `build_classpath` / `die` / `print_screen`（`sys.path` 插入脚本自身目录后 import）。`PtySession` 已经处理好了 `ioctl TIOCSWINSZ` 设窗口大小和 `ESC[6n` 光标查询自动应答——这两件事漏一个都会渲染出全空白屏。`TERM=xterm-256color` 也必须设。

**不需要真实 key、不需要网络**：全程不发消息，只走启动 + 选择器 + 重启。

**环境必须洗干净**：脚本会继承你 shell 里的真实 key。多一家 provider 可用，`/model` 清单就多几行，数字快选的 `2` 就选到别的模型上去了。所以除 `DEEPSEEK_API_KEY` 外全部 pop 掉，连 `DEEPSEEK_MODELS` / `DEEPSEEK_BASE_URL` 也要 pop——前者会替换内置清单，后者会把请求指到别处。

- [ ] **Step 1: 写脚本**

`model_memory_smoke.py`：

```python
#!/usr/bin/env python3
"""PTY smoke test：/model 选中的模型，重启后还在。

这条链只有真起两个进程才验得了：

  进程 A  启动 → 状态栏是默认模型 deepseek-v4-pro
          → /model → 数字快选第 2 项 → Enter
          → scrollback 出现「⚙ 已切换模型 · deepseek-v4-flash」
          → <root>/.codetui/model.json 落盘
          → /exit
  进程 B  同一个 cwd、**不带 -c** 启动
          → 状态栏一上来就是 deepseek-v4-flash
          → 且没有「现在不可用」的回退提示

为什么单测替代不了：restoreLastModel 可以写得完全正确，却因为在 main 里
插错了位置（例如插在 ProviderRegistry 构造之前、或插在 ConversationState
之前）而彻底不生效——那种错单测一条都不会红。

不需要真实 key、不需要网络：全程不发消息。

Usage:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/model_memory_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>".
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
)

DEFAULT_MODEL = "deepseek-v4-pro"      # DeepSeekProvider.models() 的第 1 项
PICKED_MODEL = "deepseek-v4-flash"     # 第 2 项——数字快选按 '2'
SWITCH_LINE = "⚙ 已切换模型"
FALLBACK_MARK = "现在不可用"           # restoreLastModel 的回退提示


def clean_env():
    """洗掉会改变模型清单的一切环境变量。

    多一家 provider 可用，/model 清单就多几行，数字快选的 '2' 就选到别的模型上
    去了——而脚本会静默通过，因为它只断言「第 2 项被选中」这件事的结果。
    """
    env = dict(os.environ)
    for key in list(env):
        if key.endswith("_API_KEY") or key.endswith("_BASE_URL") or key.endswith("_MODELS"):
            env.pop(key)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    return env


def launch(cmd, cwd, env, label):
    session = PtySession(cmd, cwd, env)
    session.wait_for(WELCOME_1, timeout=20)
    session.pump(0.8)
    print("%s: started" % label)
    return session


def main():
    classpath = build_classpath()
    workdir = tempfile.mkdtemp(prefix="codetui-modelmem-")
    home = tempfile.mkdtemp(prefix="codetui-modelmem-home-")
    env = clean_env()
    # 用户层配置（~/.codetui/）隔离掉，免得开发机上的真实配置影响启动。
    cmd = ["java", "-Duser.home=" + home, "-cp", classpath, MAIN_CLASS]

    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % workdir)

    failures = []

    # ── 进程 A：选一个非默认模型 ──────────────────────────────
    a = launch(cmd, workdir, env, "process A")
    try:
        text = a.screen_text()
        if DEFAULT_MODEL not in text:
            die("A: 状态栏没有默认模型 %r（前提不成立）" % DEFAULT_MODEL, a.screen.display)
        print("A: 默认模型 %s OK" % DEFAULT_MODEL)

        a.write(b"/model\r")
        a.wait_for("选择模型", timeout=10)
        a.write(b"2")
        a.pump(0.3)
        a.write(b"\r")
        # 断言的是「切换确认行 + 新模型名」这个组合：只找 PICKED_MODEL 会命中
        # 选择器面板里那一行（它一直就在屏幕上），证明不了「已经切过去了」。
        a.wait_for(SWITCH_LINE, timeout=10)
        a.pump(0.5)

        after = a.screen_text()
        if PICKED_MODEL not in after:
            failures.append("A: 切换后屏幕上没有 %r" % PICKED_MODEL)
        print_screen("A AFTER /model", a.screen.display)

        pref = os.path.join(workdir, ".codetui", "model.json")
        if not os.path.isfile(pref):
            failures.append("A: %s 没有落盘" % pref)
        else:
            with open(pref) as f:
                data = json.load(f)
            if data.get("lastModel") != PICKED_MODEL:
                failures.append("A: model.json 里是 %r，期望 %r"
                                % (data.get("lastModel"), PICKED_MODEL))
            else:
                print("A: model.json OK -> %s" % PICKED_MODEL)

        a.write(b"/exit\r")
        a.pump(1.5)
    finally:
        a.close()

    if failures:
        die("; ".join(failures))

    # ── 进程 B：同一目录重启，不带 -c ─────────────────────────
    b = launch(cmd, workdir, env, "process B")
    try:
        text = b.screen_text()
        print_screen("B STARTUP", b.screen.display)
        if PICKED_MODEL not in text:
            failures.append("B: 重启后状态栏不是 %r——记忆没生效" % PICKED_MODEL)
        if FALLBACK_MARK in text:
            failures.append("B: 冒出了回退提示 %r，说明恢复失败了" % FALLBACK_MARK)
        b.write(b"/exit\r")
        b.pump(1.5)
    finally:
        b.close()

    if failures:
        die("; ".join(failures))

    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 编译 + 生成 classpath**

```bash
mvn -q -pl springai-code-tui compile
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

- [ ] **Step 3: 跑冒烟，确认 PASS**

```bash
cd springai-code-tui && /usr/bin/python3 src/test/resources/scripts/model_memory_smoke.py
```

预期：末行 `SMOKE PASS`，退出码 0。

失败的话脚本会把最后一屏打出来——先看进程 B 的那一屏，状态栏那行末尾就是当前模型名。

- [ ] **Step 4: 变异验证——证明这条冒烟真的在守装配点**

把 `CodeTuiApplication.main` 里那行 `restoreLastModel(registry, root, state);` **临时注释掉**，重新 `mvn -q -pl springai-code-tui compile`，再跑一遍冒烟。

预期：**必须 FAIL**，且失败信息是 `B: 重启后状态栏不是 'deepseek-v4-flash'——记忆没生效`。**不能是编译错、不能是超时**——超时说明脚本挂在别的地方，那证明不了任何事。

确认后把那行恢复回来，重新编译，再跑一遍确认 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/model_memory_smoke.py
git commit -m "test(smoke): 两个进程证明模型记忆真的跨重启

单测替代不了这条：restoreLastModel 可以完全正确，却因为在 main 里插错
位置而彻底不生效，那种错一条单测都不会红。

环境变量要洗干净——多一家 provider 可用，/model 清单就多几行，数字快选
的 '2' 会选到别的模型上，而脚本会静默通过。"
```

**测试总数不变（1277）**：冒烟脚本不在 surefire 里跑。

---

### Task 5: 文档

**Files:**
- Modify: `springai-code-tui/README.md`（4 处）
- Modify: `springai-code-tui/src/test/resources/scripts/README.md`（2 处）

**背景：** 这个仓库的 README 是逐条精确写的，改动要落在**已经在讲这件事**的那几行上，不要另起新章节。

- [ ] **Step 1: README 第 10 行——多 provider 那条加上记忆**

把第 10 行里的这一句：

```
首个可用者激活；`/model` 在当前 provider 的模型间切换（子 agent 也可用 `provider:model` 跨 provider 路由）。
```

改成：

```
首个可用者激活；`/model` 在当前 provider 的模型间切换（子 agent 也可用 `provider:model` 跨 provider 路由），**选中的模型记在 `<项目根>/.codetui/model.json`，下次启动自动恢复**（该模型已不可用时回退到首个可用者并提示一行）。
```

- [ ] **Step 2: README 第 103 行附近——会话持久化旁边补一句模型偏好**

第 103 行现在是：

```
会话事件持久化在 `<项目根>/.codetui/sessions/<sessionId>.json`（**按项目隔离**，已被 `.gitignore`）。
```

在它**下面**另起一段：

```
`/model` 选中的模型记在 `<项目根>/.codetui/model.json`（单键 `lastModel`，**按项目隔离**，已被 `.gitignore`）。与会话恢复**正交**：不带 `-c` 的默认启动照样恢复模型——那正是它存在的理由。选中即写；写盘失败会在对话区提示「仅本次运行生效」。
```

- [ ] **Step 3: README 第 210 行——补上与权限模式的不对称**

第 210 行那条「权限模式不跨进程，也不跨会话恢复」**末尾**追加：

```
（**模型偏好是反过来的**：`/model` 选中的模型会跨重启恢复。两者性质不同——权限档记错了会让不该执行的东西执行，模型记错了最坏是多花点钱或慢一点，且状态栏一直显示着当前模型名，一眼看得见、随时改得回来。）
```

- [ ] **Step 4: README 第 686 行——命令表那行补一句**

把：

```
| `/model` | 打开模型选择器，在当前 provider 的模型间切换 |
```

改成：

```
| `/model` | 打开模型选择器，在当前 provider 的模型间切换（选中即记住，下次启动自动恢复） |
```

- [ ] **Step 5: 冒烟脚本 README——清单加一行**

`springai-code-tui/src/test/resources/scripts/README.md` 的表格里，在 `background_smoke.py` 那行**之后**加：

```
| `model_memory_smoke.py` | 模型记忆实机冒烟：进程 A 用 `/model` 选一个非默认模型 → 断言切换确认行 + `model.json` 落盘 → `/exit`；进程 B 同目录**不带 `-c`** 重启 → 断言状态栏一上来就是它、且没有回退提示。**单测替代不了**：`restoreLastModel` 可以写得完全正确，却因为在 `main` 里插错位置而彻底不生效，那种错一条单测都不会红。**不需要真实 key、不需要网络。** |
```

同一文件下方的运行命令清单里，在 `python3 src/test/resources/scripts/background_smoke.py` 之后加一行：

```
python3 src/test/resources/scripts/model_memory_smoke.py
```

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/README.md springai-code-tui/src/test/resources/scripts/README.md
git commit -m "docs(model): 记录模型记忆，并说清它为什么与权限模式相反

权限模式刻意不跨重启恢复，模型偏好却恢复——不写清楚看着像自相矛盾。
理由是两者性质不同：权限档记错了会让不该执行的东西执行，模型记错了
最坏是多花点钱，且状态栏一直显示着当前模型名。"
```

---

### Task 6: 全量回归 + 剩余变异验证

**Files:** 无改动（除非发现问题）

**背景：** Task 3 和 Task 4 里已经各做过一次变异验证。这里补齐剩下两条，并跑一遍完整的回归——包括**全部 8 个冒烟脚本**，因为这次动了 `CodeTuiApplication.main` 的启动序列和 `CodeTuiView` 的按键分支，两处都是别的冒烟脚本正在踩的路。

- [ ] **Step 1: 全量单测**

```bash
mvn test -pl springai-code-tui
```

预期：`Tests run: 1277, Failures: 0, Errors: 0, Skipped: 9`

对不上就停下来查：多了说明写了计划外的测试，少了说明有测试没被 surefire 捡到（类名不以 `Test` 结尾是最常见的原因）。

- [ ] **Step 2: 变异验证——失效检测**

把 `CodeTuiApplication.restoreLastModel` 里的比对**临时删掉**：

```java
        String id = remembered.get();
        registry.select(id);
        // if (!id.equals(registry.activeModelId())) { ... } 整段删掉
```

```bash
mvn test -pl springai-code-tui -Dtest=CodeTuiApplicationModelRestoreTest
```

预期：`unavailableModelFallsBackAndSaysSo` **必须红**，失败信息是「该有且只有一行提示」（实际 0 行）。改回去，重跑确认绿。

- [ ] **Step 3: 变异验证——读侧降级**

把 `ModelPreference.read` 里解析 JSON 的 catch **临时改成往外抛**：

```java
        try {
            node = MAPPER.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException(e);        // 变异：不再降级
        }
```

```bash
mvn test -pl springai-code-tui -Dtest=ModelPreferenceTest
```

预期：`malformedJsonIsEmpty` **必须红**，且是 **Errors 而不是 Failures**（抛出来了，不是断言不符）。改回去，重跑确认绿。

- [ ] **Step 4: 跑全部 8 个冒烟脚本**

```bash
mvn -q -pl springai-code-tui compile
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd springai-code-tui
for s in clear memory attachment permission background model_memory; do
  echo "=== $s ==="
  /usr/bin/python3 src/test/resources/scripts/${s}_smoke.py || echo "FAILED: $s"
done
```

预期：每个都以 `SMOKE PASS` 结尾。

`mcp_smoke.py` 与 `mcp_manage_smoke.py` 需要 `npx`（Node.js）——本机有就一并跑，没有就在最终报告里**明说跳过了这两条**，不要默默不提。

- [ ] **Step 5: 确认工作区干净、提交历史完整**

```bash
git status --short
git log --oneline main..HEAD
```

预期：`git status` 无输出；`git log` 列出本分支的 6 笔提交（spec 1 笔 + Task 1~5 各 1 笔）。

- [ ] **Step 6: 若 Step 2/3 的变异改动有残留，现在修掉并补一笔提交**

变异验证是「改坏 → 确认红 → 改回」，改回之后 `git status` 应该是干净的。若不干净，说明某处没恢复——`git diff` 看清楚再决定是恢复还是保留。

**不要用 `git checkout --` 一把梭**：这个仓库可能有并行 agent 共用工作树，那会连别人在途的改动一起冲掉。只恢复你自己动过的那几行。

---

## 完成标准

- [ ] `mvn test -pl springai-code-tui` → `Tests run: 1277, Failures: 0, Errors: 0, Skipped: 9`
- [ ] `model_memory_smoke.py` → `SMOKE PASS`
- [ ] 其余 5 个不依赖 Node 的冒烟脚本 → 全部 `SMOKE PASS`
- [ ] 4 条变异各自杀掉了对应的测试，且**红的理由正确**（不是编译错、不是超时）
- [ ] `git status` 干净

## 实施记录

（实施者在此追加：实际测试数、变异验证结论、踩到的坑。这一节是这套代码「为什么长这样」的第一手材料，不要留空。）

