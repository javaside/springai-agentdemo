# Brave 搜索工具接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已有博查搜索工具旁再接一个 `BraveWebSearch`（复用库里现成的 `BraveWebSearchTool`），两家共存、由模型按内容语言自选。

**Architecture:** 不重写 Brave 的解析逻辑——直接用 `spring-ai-agent-utils` 的 `BraveWebSearchTool`，靠两个新装饰器补它的硬缺陷：`RenamedToolCallback` 改注册名（库版叫 `WebSearch`，与博查撞名）+ 换中文描述，`TimeLimitedToolCallback` 兜一层 20s 总超时（库版自建 RestClient 不暴露 requestFactory，HTTP 层设不了超时）。博查工具注册名同步改为 `BochaWebSearch` 以对称。

**Tech Stack:** Java 17、Spring AI 2.0（`@Tool` / `ToolCallbacks` / `ToolDefinition`）、`spring-ai-agent-utils 0.6.0`、JUnit 5

**Spec:** `docs/superpowers/specs/2026-07-26-brave-search-design.md`

---

## File Structure

| 文件 | 职责 |
|---|---|
| **创建** `.../agent/RenamedToolCallback.java` | 只改写 `ToolDefinition` 的 name / description，其余全透传。取代 `AgentTools` 里私有的 `DescribedToolCallback`（那个只能换描述） |
| **创建** `.../agent/TimeLimitedToolCallback.java` | 给没有自带超时的工具兜一层总超时。daemon 线程池 + `Future.get(timeout)` |
| **修改** `.../agent/BochaWebSearchTool.java` | `@Tool` 注册名 `WebSearch` → `BochaWebSearch` |
| **修改** `.../agent/AgentTools.java` | 删私有 `DescribedToolCallback`/`describedAs` 改用新类；加 `createBraveWebSearchTool` + `resolveBraveResultCount` + Brave 中文描述常量；接线；`webSearchGuide` 改双参四态 |
| **创建** `.../agent/RenamedToolCallbackTest.java` | 改名装饰器单测 |
| **创建** `.../agent/TimeLimitedToolCallbackTest.java` | 超时装饰器单测 |
| **修改** `.../agent/AgentToolsWebSearchWiringTest.java` | 博查改名断言 + Brave 门控 + 条数解析 + 指引段四态 |
| **创建** `.../agent/BraveWebSearchSmokeTest.java` | Brave 真机冒烟，`BRAVE_API_KEY` 门控 |
| **修改** `README.md`、`src/package/bin/config.env.example` | 工具清单、安全披露（数据出境）、两个新 env |
| **修改** `docs/superpowers/specs/2026-07-26-web-search-design.md` | 更正「Brave 国内直连大概率不通」——实测可达 |

Java 源码根：`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/`
测试根：`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/`

**验证命令一律模块作用域**：`mvn -pl springai-code-tui test`。整仓 `mvn test` 会被几个空模块打挂。
`CodingAgentSpikeTest.todoTurnIdBinding` 会偶发 60s 超时（真实 DeepSeek 网络调用），撞上就单跑那条确认无关。

---

### Task 1: RenamedToolCallback（取代只能换描述的私有装饰器）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/RenamedToolCallback.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（删 `describedAs` 与 `DescribedToolCallback`，第 218 行调用点改写）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RenamedToolCallbackTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 改名 / 换描述装饰器：只动 ToolDefinition 的 name 与 description，其余全透传。 */
class RenamedToolCallbackTest {

    /** 最小假工具：记录收到的入参，返回固定串。 */
    private static final class FakeTool implements ToolCallback {
        String lastInput;

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("OriginalName")
                    .description("original description")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override public String call(String toolInput) {
            lastInput = toolInput;
            return "fake-result";
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }

    @Test
    void replacesNameAndDescription() {
        ToolCallback renamed = new RenamedToolCallback(new FakeTool(), "NewName", "新描述");

        assertEquals("NewName", renamed.getToolDefinition().name());
        assertEquals("新描述", renamed.getToolDefinition().description());
    }

    @Test
    void nullMeansKeepOriginal() {
        ToolCallback onlyDescription = new RenamedToolCallback(new FakeTool(), null, "只换描述");
        assertEquals("OriginalName", onlyDescription.getToolDefinition().name(), "name 传 null 应保持原值");
        assertEquals("只换描述", onlyDescription.getToolDefinition().description());

        ToolCallback onlyName = new RenamedToolCallback(new FakeTool(), "只换名", null);
        assertEquals("只换名", onlyName.getToolDefinition().name());
        assertEquals("original description", onlyName.getToolDefinition().description(),
                "description 传 null 应保持原值");
    }

    @Test
    void keepsInputSchemaUntouched() {
        ToolCallback renamed = new RenamedToolCallback(new FakeTool(), "NewName", "新描述");

        assertEquals("{\"type\":\"object\"}", renamed.getToolDefinition().inputSchema(),
                "inputSchema 不能被改写——模型据此构造入参");
    }

    @Test
    void passesCallThroughToDelegate() {
        FakeTool fake = new FakeTool();
        ToolCallback renamed = new RenamedToolCallback(fake, "NewName", "新描述");

        String out = renamed.call("{\"q\":\"x\"}");

        assertEquals("fake-result", out, "返回值应原样透传");
        assertEquals("{\"q\":\"x\"}", fake.lastInput, "入参应原样透传");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='RenamedToolCallbackTest'`
Expected: 编译失败，`找不到符号: 类 RenamedToolCallback`

- [ ] **Step 3: 写实现**

创建 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/RenamedToolCallback.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 只改写 {@link ToolDefinition} 的 name / description，其余（inputSchema、调用）全透传的装饰器。
 * {@code null} 参数表示保持委托对象原值。
 *
 * <p>两个用途：
 * <ul>
 *   <li>把库工具的完整描述移植到自写适配器上（TodoWrite 适配器用，只换 description）；
 *   <li>给库工具<b>改注册名</b>——库版 {@code BraveWebSearchTool} 的注册名是 {@code WebSearch}，
 *       与本项目博查工具撞名。不改名则模型侧工具分发直接坏，且子 agent 的 allow/deny
 *       （按注册名精确匹配）会跟着错乱。
 * </ul>
 */
public final class RenamedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolDefinition definition;

    public RenamedToolCallback(ToolCallback delegate, String name, String description) {
        this.delegate = delegate;
        ToolDefinition d = delegate.getToolDefinition();
        this.definition = ToolDefinition.builder()
                .name(name == null ? d.name() : name)
                .description(description == null ? d.description() : description)
                .inputSchema(d.inputSchema())
                .build();
    }

    @Override public ToolDefinition getToolDefinition() { return definition; }

    @Override public String call(String toolInput) { return delegate.call(toolInput); }

    @Override public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
```

- [ ] **Step 4: 让 AgentTools 改用新类**

在 `AgentTools.java` 里，把第 218 行附近的：

```java
        ToolCallback todoCallback = describedAs(
                ToolCallbacks.from(new TodoWriteToolAdapter(todo))[0],
                ToolCallbacks.from(todo)[0].getToolDefinition().description());
```

改为（`name` 传 `null` = 保持适配器自己的 `TodoWrite`）：

```java
        ToolCallback todoCallback = new RenamedToolCallback(
                ToolCallbacks.from(new TodoWriteToolAdapter(todo))[0],
                null,
                ToolCallbacks.from(todo)[0].getToolDefinition().description());
```

然后**删掉** `AgentTools` 里这两段（连同它们的 javadoc 注释）：

```java
    /** 包一层，仅把 {@link ToolDefinition} 的 description 换成给定文本（name / inputSchema 原样），调用透传委托。 */
    private static ToolCallback describedAs(ToolCallback delegate, String description) {
        return new DescribedToolCallback(delegate, description);
    }
```

```java
    /** 借用另一处描述、其余全透传的 {@link ToolCallback} 装饰器（用于把库工具的完整描述移植到适配器上）。 */
    private static final class DescribedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final ToolDefinition definition;

        DescribedToolCallback(ToolCallback delegate, String description) {
            this.delegate = delegate;
            ToolDefinition d = delegate.getToolDefinition();
            this.definition = ToolDefinition.builder()
                    .name(d.name()).description(description).inputSchema(d.inputSchema()).build();
        }

        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String toolInput) { return delegate.call(toolInput); }
        @Override public String call(String toolInput, ToolContext toolContext) { return delegate.call(toolInput, toolContext); }
    }
```

删完若 `ToolDefinition` / `ToolContext` 的 import 变成未使用，一并删掉（编译器会警告）。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='RenamedToolCallbackTest'`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: 全模块回归（确认 TodoWrite 描述移植未被破坏）**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`。TodoWrite 相关用例仍绿——若有断言 TodoWrite 描述内容的用例失败，说明 `null` 语义写反了。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/RenamedToolCallback.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/RenamedToolCallbackTest.java
git commit -m "refactor: 抽出 RenamedToolCallback，支持改注册名（原装饰器只能换描述）"
```

---

### Task 2: TimeLimitedToolCallback

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/TimeLimitedToolCallback.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/TimeLimitedToolCallbackTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 超时装饰器：给没有自带超时的工具兜一层总超时。 */
class TimeLimitedToolCallbackTest {

    /** 可配置耗时与抛错行为的假工具。 */
    private static final class FakeTool implements ToolCallback {
        private final long sleepMillis;
        private final RuntimeException toThrow;

        FakeTool(long sleepMillis, RuntimeException toThrow) {
            this.sleepMillis = sleepMillis;
            this.toThrow = toThrow;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("SlowTool").description("d").inputSchema("{}").build();
        }

        @Override public String call(String toolInput) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return "fake-result";
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }

    @Test
    void fastCallReturnsNormally() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("fake-result", limited.call("{}"), "未超时应原样返回委托结果");
    }

    @Test
    void slowCallThrowsReadableTimeout() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(2000, null), Duration.ofMillis(100));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> limited.call("{}"));

        assertTrue(ex.getMessage().contains("SlowTool"), "错误消息应点名是哪个工具超时，实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("超时"), "应是可读的超时提示，实际=" + ex.getMessage());
    }

    @Test
    void delegateExceptionIsRethrownUnchanged() {
        IllegalArgumentException original = new IllegalArgumentException("委托自己的错");
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, original), Duration.ofSeconds(5));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> limited.call("{}"));

        assertEquals("委托自己的错", ex.getMessage(),
                "委托抛的异常应原样传播，不能被包装成超时或执行失败——否则错误定位信息丢失");
    }

    @Test
    void definitionPassesThroughUnchanged() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("SlowTool", limited.getToolDefinition().name(), "本装饰器不改名，只管超时");
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='TimeLimitedToolCallbackTest'`
Expected: 编译失败，`找不到符号: 类 TimeLimitedToolCallback`

- [ ] **Step 3: 写实现**

创建 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/TimeLimitedToolCallback.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 给<b>没有自带超时</b>的工具兜一层总超时。
 *
 * <p><b>为什么需要</b>：库版 {@code BraveWebSearchTool} 内部自建 RestClient 且不暴露 requestFactory，
 * 无法在 HTTP 层设超时——Spring 默认走 Apache HttpClient，响应超时为 null 即无限等。
 *
 * <p><b>已知代价，非疏漏</b>：底层阻塞 read 不保证响应中断，{@code cancel(true)} 之后工作线程可能滞留到
 * 请求自行结束。故线程池用 <b>daemon</b> 线程，保证滞留线程不阻止 JVM 退出。这是次优解——比无限等好，
 * 但不如在 HTTP 层设超时干净。
 *
 * <p><b>不要套在博查工具上</b>：那个已在 HTTP 层设了 connect 10s / read 20s，再包一层只会让同一个失败
 * 出现两种措辞、且难判断哪一层先触发。
 *
 * <p><b>ThreadLocal 不跨线程</b>：委托在线程池线程上执行，故本装饰器<b>内层</b>的工具读不到
 * {@link ToolEventCallback#currentTurnId()} 这类 ThreadLocal。当前只用于 Brave（不依赖它），
 * 若将来要套到依赖 ThreadLocal 的工具上，须先解决传递问题。
 */
public final class TimeLimitedToolCallback implements ToolCallback {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "tool-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ToolCallback delegate;
    private final Duration timeout;

    public TimeLimitedToolCallback(ToolCallback delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        Future<String> future = EXECUTOR.submit(() -> delegate.call(toolInput, toolContext));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    name + " 超时（超过 " + timeout.toSeconds() + " 秒未返回）", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " 被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;      // 委托自己的异常原样传播，不改语义、不丢定位信息
            }
            throw new IllegalStateException(name + " 执行失败：" + cause, cause);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='TimeLimitedToolCallbackTest'`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/TimeLimitedToolCallback.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/TimeLimitedToolCallbackTest.java
git commit -m "feat: 通用工具超时装饰器（库版 Brave 工具无超时口子）"
```

---

### Task 3: 博查工具注册名改为 BochaWebSearch

两家共存后，`WebSearch` 这个名字会让模型把博查当成「默认搜索」而冷落 Brave。改成对称命名。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java`（`@Tool` 注解）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（指引段里的工具名）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 改测试断言（先红）**

把 `AgentToolsWebSearchWiringTest` 里的 `registeredToolNameIsWebSearch` 整个方法替换为：

```java
    /** 注册名取 @Tool 注解而非方法名；子 agent 的 allow/deny 按注册名精确匹配，写错会静默失效。 */
    @Test
    void registeredToolNameIsBochaWebSearch() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);

        List<String> names = Arrays.stream(ToolCallbacks.from(tool))
                .map(c -> c.getToolDefinition().name()).toList();

        assertEquals(List.of("BochaWebSearch"), names,
                "两家共存后改用对称命名，避免模型把某一家当默认搜索。实际=" + names);
    }
```

并把 `decoratedToolCallbackKeepsName` 里的两处 `"WebSearch"` 改为 `"BochaWebSearch"`：

```java
    @Test
    void decoratedToolCallbackKeepsName() {
        BochaWebSearchTool tool = AgentTools.createWebSearchTool("fake-key", null);
        ToolCallback raw = ToolCallbacks.from(tool)[0];

        ToolCallback decorated = new ToolEventCallback(raw, new ConversationState());

        assertTrue("BochaWebSearch".equals(decorated.getToolDefinition().name()),
                "装饰后注册名不能变，实际=" + decorated.getToolDefinition().name());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: 2 条 FAIL，实际值都是 `WebSearch`

- [ ] **Step 3: 改实现**

在 `BochaWebSearchTool.java` 里把注解：

```java
    @Tool(name = "WebSearch", description = """
```

改为：

```java
    @Tool(name = "BochaWebSearch", description = """
```

并在 `AgentTools.webSearchGuide` 的正文里，把两处 `WebSearch` 改成 `BochaWebSearch`：

```java
                - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时，先用 BochaWebSearch 搜索，
                  拿到标题、网址和摘要；需要网页原文细节时，再把该网址交给 webFetch 抓取。
                - BochaWebSearch 的 freshness 参数一般不要传（默认不限时间效果最好），
                  只有明确需要「最近一天 / 最近一周」的最新消息时才用。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest,BochaWebSearchToolTest'`
Expected: `Tests run: 34, Failures: 0, Errors: 0`（26 + 8）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/BochaWebSearchTool.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "refactor: 博查工具注册名改为 BochaWebSearch（为两家共存做对称命名）"
```

---

### Task 4: Brave 工具的创建与条数解析

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 写失败的测试**

在 `AgentToolsWebSearchWiringTest` 追加：

```java
    @Test
    void resolveBraveResultCountFallsBackAndClamps() {
        assertEquals(5, AgentTools.resolveBraveResultCount(null), "缺失应回退 5（Brave 免费档 2000 次/月，比博查更该省）");
        assertEquals(5, AgentTools.resolveBraveResultCount("  "), "空白应回退 5");
        assertEquals(5, AgentTools.resolveBraveResultCount("abc"), "非数字应回退 5");
        assertEquals(1, AgentTools.resolveBraveResultCount("0"), "低于下界应钳到 1");
        assertEquals(20, AgentTools.resolveBraveResultCount("999"), "高于上界应钳到 20");
        assertEquals(10, AgentTools.resolveBraveResultCount(" 10 "), "合法值应生效（允许两侧空白）");
    }

    @Test
    void noBraveKey_noBraveTool() {
        assertNull(AgentTools.createBraveWebSearchTool(null, null), "未配 BRAVE_API_KEY 时不应创建");
        assertNull(AgentTools.createBraveWebSearchTool("   ", null), "空白 key 时不应创建");
    }

    /** 库版注册名是 WebSearch，与博查撞名，必须被改成 BraveWebSearch。 */
    @Test
    void braveToolIsRenamedToAvoidCollision() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);

        assertNotNull(brave, "配了 key 就应创建");
        assertEquals("BraveWebSearch", brave.getToolDefinition().name(),
                "库版注册名 WebSearch 会与博查工具撞名，必须改写");
    }

    /** 库里那段描述写死了「Claude」与「US only」，两条都不适用，必须换掉。 */
    @Test
    void braveDescriptionIsReplacedWithChineseGuidance() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);
        String description = brave.getToolDefinition().description();

        assertFalse(description.contains("Claude"), "不应把别家产品名塞进本项目的工具描述");
        assertFalse(description.contains("only available in the US"), "实测国内直连可达，这句是错的");
        assertTrue(description.contains("英文"), "应说明它主打英文内容（与博查分工），实际=" + description);
        assertTrue(description.contains("site:"), "应提示用 site: 运算符而非 allowedDomains，实际=" + description);
    }
```

并在文件顶部补 import：

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: 编译失败，`找不到符号: 方法 resolveBraveResultCount` 与 `createBraveWebSearchTool`

- [ ] **Step 3: 写实现**

在 `AgentTools.java` 顶部补 import：

```java
import org.springaicommunity.agent.tools.BraveWebSearchTool;
import java.time.Duration;
```

在 `WEB_SEARCH_GUIDE_KEY` 常量之后追加常量：

```java
    /** Brave 工具的注册名——库版是 {@code WebSearch}，与博查工具撞名，必须改写。 */
    static final String BRAVE_TOOL_NAME = "BraveWebSearch";

    /** Brave 默认条数。比博查的 8 保守：Brave 免费档 2000 次/月，更该省。 */
    static final int BRAVE_DEFAULT_COUNT = 5;

    /** Brave 单次条数上限。 */
    static final int BRAVE_MAX_COUNT = 20;

    /** Brave 总超时。库版自建 RestClient 不暴露 requestFactory，只能在工具层兜（见 TimeLimitedToolCallback）。 */
    private static final Duration BRAVE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 替换库版 Brave 工具的描述。库里那段写死了「Allows Claude to search」（串了别家产品名）
     * 和「Web search is only available in the US」（实测国内直连可达，这句是错的），都不适用。
     * 这里改成中文，并写明与 BochaWebSearch 的分工，以及 allowedDomains 那个配额坑。
     */
    private static final String BRAVE_DESCRIPTION = """
            用 Brave 搜索引擎搜索互联网，返回网页标题、网址与简短描述。

            用法：
            - 主打英文内容：英文技术文档、GitHub issue、Stack Overflow、英文新闻优先用它。
              中文内容、国内站点、中文技术社区请改用 BochaWebSearch。
            - 它只返回简短描述，不含长摘要。需要网页原文细节时，把结果里的网址交给 webFetch 抓取。
            - 不要用 allowedDomains 限定域名：它是拿到结果之后在客户端过滤的，被过滤掉的结果照样
              消耗了配额条数。想限定站点请把 site:example.com 直接写进搜索词。
            - 搜索最新资料时在搜索词里带上年份，例如「React documentation 2026」。
            - 引用了搜索结果，请在回答末尾用 markdown 链接列出实际参考的网址（Sources）。
            """;
```

在 `createWebSearchTool` 方法之后追加两个方法：

```java
    /**
     * 解析 {@code BRAVE_SEARCH_COUNT}：缺失 / 非数字回退 {@link #BRAVE_DEFAULT_COUNT}，
     * 越界钳到 {@code [1, BRAVE_MAX_COUNT]}。形状照 {@link #resolveSubagentConcurrency}。
     */
    static int resolveBraveResultCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BRAVE_DEFAULT_COUNT;
        }
        try {
            return Math.min(BRAVE_MAX_COUNT, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return BRAVE_DEFAULT_COUNT;
        }
    }

    /**
     * 按 env 决定是否创建 Brave 搜索工具：{@code apiKey} 空即返回 null（不注册）。
     *
     * <p>直接复用库版 {@code BraveWebSearchTool}（不重写响应解析），外面套两层补它的硬缺陷：
     * {@link RenamedToolCallback} 改注册名 + 换描述，{@link TimeLimitedToolCallback} 兜总超时。
     *
     * <p><b>能力退化</b>：库版 baseUrl 是硬编码常量，无法指向本地 stub，故 Brave 的响应解析
     * <b>无法离线单测</b>——那半边的正确性只能靠 {@code BraveWebSearchSmokeTest} 真机冒烟保证。
     * 我们能离线测的只有自己写的外壳（改名、描述、超时、门控）。
     */
    static ToolCallback createBraveWebSearchTool(String apiKey, String countEnv) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        BraveWebSearchTool tool = BraveWebSearchTool.builder(apiKey.trim())
                .resultCount(resolveBraveResultCount(countEnv))
                .build();
        ToolCallback raw = ToolCallbacks.from(tool)[0];
        return new TimeLimitedToolCallback(
                new RenamedToolCallback(raw, BRAVE_TOOL_NAME, BRAVE_DESCRIPTION), BRAVE_TIMEOUT);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 12, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: Brave 搜索工具的创建与条数解析（改名 + 换描述 + 兜超时）"
```

---

### Task 5: Brave 接线进工具链

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 写失败的测试**

追加一条（**不要**再加「无 Brave key 仍能装配」的用例——已有的
`build_withoutBochaKey_stillAssemblesOffline` 在测试环境里两个 key 都没配，实质就是同一个测试，重复无价值）：

```java
    /** 完整装饰链之后注册名仍须是 BraveWebSearch——中间任何一层丢了改名，工具分发就会撞上博查。 */
    @Test
    void braveKeepsRenamedNameThroughFullDecorationChain() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);

        ToolCallback decorated = new ToolEventCallback(brave, new ConversationState());

        assertEquals("BraveWebSearch", decorated.getToolDefinition().name(),
                "装饰链末端的注册名，实际=" + decorated.getToolDefinition().name());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 13`，**新增那条直接 PASS**——它验证的是 Task 4 已实现的行为（装饰器保留 definition），
不该红。若 `braveKeepsRenamedNameThroughFullDecorationChain` 意外失败，说明某层装饰器丢了 definition，
报告实际值，不要改断言凑绿。

- [ ] **Step 3: 接线**

在 `AgentTools.build` 里，博查那段之后追加 Brave 的创建：

```java
        // Brave 搜索（库版 BraveWebSearchTool）：BRAVE_API_KEY 配了才注册。与博查共存，
        // 由模型按内容语言自选（分工写在各自的工具描述里）。
        ToolCallback braveWebSearch =
                createBraveWebSearchTool(System.getenv("BRAVE_API_KEY"), System.getenv("BRAVE_SEARCH_COUNT"));
```

并在 `all.add(reloadableSkill);` 之后追加：

```java
        if (braveWebSearch != null) {
            all.add(braveWebSearch);   // 已是 ToolCallback（非 @Tool 对象），故不进 rawTools
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 13, Failures: 0, Errors: 0`

- [ ] **Step 5: 全模块回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: Brave 搜索工具按 BRAVE_API_KEY 门控接入工具链"
```

---

### Task 6: 系统提示指引段改双参四态

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java`

- [ ] **Step 1: 改测试（先红）**

把现有的 `guideIsEmptyWhenNoTool`、`guideMentionsWebSearchAndFetchHandoff`、`guideContainsNoTemplateBraces`
三个方法**整体替换**为四态版本：

```java
    @Test
    void guideIsEmptyWhenNeitherToolRegistered() {
        assertEquals("", AgentTools.webSearchGuide(false, false),
                "两家都没注册时，系统提示不应出现任何搜索相关指引");
    }

    @Test
    void guideCoversBochaOnly() {
        String guide = AgentTools.webSearchGuide(true, false);

        assertTrue(guide.contains("BochaWebSearch"), "应点名博查工具，实际=" + guide);
        assertFalse(guide.contains("BraveWebSearch"), "Brave 没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
    }

    @Test
    void guideCoversBraveOnly() {
        String guide = AgentTools.webSearchGuide(false, true);

        assertTrue(guide.contains("BraveWebSearch"), "应点名 Brave 工具，实际=" + guide);
        assertFalse(guide.contains("BochaWebSearch"), "博查没注册就不该提它，实际=" + guide);
        assertTrue(guide.contains("webFetch"), "应说明与 webFetch 的分工，实际=" + guide);
    }

    @Test
    void guideExplainsDivisionWhenBothRegistered() {
        String guide = AgentTools.webSearchGuide(true, true);

        assertTrue(guide.contains("BochaWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("BraveWebSearch"), "实际=" + guide);
        assertTrue(guide.contains("中文"), "应讲清中文走哪家，实际=" + guide);
        assertTrue(guide.contains("英文"), "应讲清英文走哪家，实际=" + guide);
        assertTrue(guide.contains("Sources"), "应要求列出来源，实际=" + guide);
    }

    /** 指引段作为 param 值注入，正文里的花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示。 */
    @Test
    void noGuideVariantContainsTemplateBraces() {
        for (boolean bocha : new boolean[]{false, true}) {
            for (boolean brave : new boolean[]{false, true}) {
                String guide = AgentTools.webSearchGuide(bocha, brave);
                assertTrue(!guide.contains("{") && !guide.contains("}"),
                        "指引正文不得含花括号（bocha=" + bocha + ", brave=" + brave + "），实际=" + guide);
            }
        }
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: 编译失败，`webSearchGuide(boolean,boolean)` 不存在（现有是单参）

- [ ] **Step 3: 改实现**

把 `AgentTools.webSearchGuide` 整个方法（含 javadoc）替换为：

```java
    /**
     * 搜索指引段：按实际注册了哪些搜索工具四态渲染——都没注册就返回空串，模型看不到指引，
     * 也就不会去调不存在的工具。
     *
     * <p>正文<b>不得含花括号</b>：它作为 param 值注入（与 AUTO_MEMORY / PROJECT_INSTRUCTIONS 同法），
     * 花括号会被 StringTemplate 当占位符解析而炸掉整个系统提示渲染。
     */
    static String webSearchGuide(boolean bocha, boolean brave) {
        if (bocha && brave) {
            return """
                - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时先搜索，别凭记忆臆断外部事实。
                  两个搜索工具按内容语言分工：中文内容、国内站点、中文技术社区用 BochaWebSearch（返回长摘要，
                  常常一次就够）；英文技术文档、GitHub issue、英文新闻用 BraveWebSearch（只返回简短描述）。
                - 拿到网址后，需要网页原文细节就把该网址交给 webFetch 抓取。
                - BochaWebSearch 的 freshness 一般不要传（默认不限时间效果最好）；
                  BraveWebSearch 不要用 allowedDomains 限定域名（客户端过滤，白烧配额），改把 site:xxx 写进搜索词。
                - 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。""";
        }
        if (bocha) {
            return """
                - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时，先用 BochaWebSearch 搜索，
                  拿到标题、网址和摘要；需要网页原文细节时，再把该网址交给 webFetch 抓取。
                - BochaWebSearch 的 freshness 参数一般不要传（默认不限时间效果最好），
                  只有明确需要「最近一天 / 最近一周」的最新消息时才用。
                - 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。""";
        }
        if (brave) {
            return """
                - 需要项目之外的最新信息（库的用法、报错含义、版本变更、新闻等）时，先用 BraveWebSearch 搜索，
                  拿到标题、网址和简短描述；需要网页原文细节时，再把该网址交给 webFetch 抓取。
                - BraveWebSearch 不要用 allowedDomains 限定域名（它是拿到结果后在客户端过滤的，白烧配额），
                  想限定站点就把 site:xxx 直接写进搜索词。
                - 回答里引用了搜索结果，就在末尾列出 Sources，用 markdown 链接列出你实际参考的网址。""";
        }
        return "";
    }
```

并把 `build` 里的调用点：

```java
        String webSearchGuide = webSearchGuide(webSearch != null);
```

改为：

```java
        String webSearchGuide = webSearchGuide(webSearch != null, braveWebSearch != null);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='AgentToolsWebSearchWiringTest'`
Expected: `Tests run: 15, Failures: 0, Errors: 0`（13 − 替换掉的 3 条旧指引用例 + 5 条四态用例）

- [ ] **Step 5: 全模块回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsWebSearchWiringTest.java
git commit -m "feat: 搜索指引段按两家注册状态四态渲染"
```

---

### Task 7: Brave 真机冒烟

Brave 的响应解析在库里且 baseUrl 硬编码，**离线测不了**——这条冒烟是它唯一的正确性保障。

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BraveWebSearchSmokeTest.java`

- [ ] **Step 1: 写测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Brave API 冒烟。绑 {@code BRAVE_API_KEY}：有 key 则 {@code mvn test} 自动跑，无 key 优雅跳过
 * （门控模式同 {@link CodingAgentSpikeTest}）。
 *
 * <p><b>这条测试比一般冒烟更重要</b>：库版 {@code BraveWebSearchTool} 的 baseUrl 是硬编码常量，
 * 无法指向本地 stub，所以它的响应解析与错误处理<b>没有任何离线用例</b>——正确性全靠这条。
 */
@EnabledIfEnvironmentVariable(named = "BRAVE_API_KEY", matches = ".+")
class BraveWebSearchSmokeTest {

    @Test
    void realSearchReturnsResultsWithUrls() {
        ToolCallback brave = AgentTools.createBraveWebSearchTool(System.getenv("BRAVE_API_KEY"), "3");
        assertNotNull(brave, "配了 key 就应创建成功");
        assertEquals("BraveWebSearch", brave.getToolDefinition().name());

        String out = brave.call("{\"query\":\"Spring AI framework documentation 2026\"}");

        System.out.println("[smoke] Brave 搜索返回：\n" + out);
        assertTrue(out.contains("http"), "结果里应含可访问的网址，实际=" + out);
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn -pl springai-code-tui test -Dtest='BraveWebSearchSmokeTest'`
Expected：
- 没配 `BRAVE_API_KEY` → `Tests run: 0, Skipped: 1`（正常，不是失败）
- 配了 key → `Tests run: 1, Failures: 0`，并打印真实搜索结果

**注意**：入参是 JSON 字符串而非 Java 参数，因为这里调的是 `ToolCallback.call(String)`（模型侧的调用形态）。
若报入参解析错误，先 `System.out.println(brave.getToolDefinition().inputSchema())` 看库版期望的字段名，
按实际 schema 调整 JSON，并在报告里说明实际 schema 是什么。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/BraveWebSearchSmokeTest.java
git commit -m "test: Brave 搜索真实 API 冒烟（BRAVE_API_KEY 门控）"
```

---

### Task 8: 文档同步与前序 spec 更正

**Files:**
- Modify: `springai-code-tui/src/package/bin/config.env.example`
- Modify: `springai-code-tui/README.md`（工具清单 + 安全披露）
- Modify: `docs/superpowers/specs/2026-07-26-web-search-design.md`（更正 Brave 可达性）

- [ ] **Step 1: 改 config.env.example**

把现有的博查区块（以 `# ────────────── 可选：网络搜索（博查 Bocha） ──────────────` 开头那段）整体替换为：

```bash
# ────────────── 可选：网络搜索（两家可共存） ──────────────
# 博查 Bocha（中文内容优先，返回长摘要）。key 申请：https://open.bochaai.com/
#BOCHA_API_KEY=你的key
# 单次搜索返回条数，范围 [1, 50]，默认 8。不暴露给模型（避免模型乱开条数烧搜索额度）。
#BOCHA_SEARCH_COUNT=8
#
# Brave（英文技术内容优先，只返回简短描述）。key 申请：https://brave.com/search/api/
# 注意：查询词会发给 Brave（美国公司），属于数据出境；博查是国内服务，两者性质不同。
#BRAVE_API_KEY=你的key
# 单次搜索返回条数，范围 [1, 20]，默认 5（免费档 2000 次/月，比博查更该省）。
#BRAVE_SEARCH_COUNT=5
#
# 两家都配则模型按内容语言自选（中文走博查、英文走 Brave）；都不配则没有联网搜索能力
# （webFetch 抓取网页不受影响）。

# ────────────── 可选：自定义模型清单 ──────────────
```

- [ ] **Step 2: 改 README 工具清单**

把 README 第 11 行里的：

```
`WebSearch`（联网搜索，博查 Bocha API，需配 `BOCHA_API_KEY`，不配则该工具不注册）、
```

替换为：

```
`BochaWebSearch`（联网搜索·中文内容优先，博查 API，需配 `BOCHA_API_KEY`）、`BraveWebSearch`（联网搜索·英文内容优先，Brave API，需配 `BRAVE_API_KEY`；两家可共存，模型按内容语言自选，都不配则均不注册）、
```

- [ ] **Step 3: 改 README 安全披露**

把 README 里那条「联网出口无过滤」整行替换为：

```
- **联网出口无过滤**：`SmartWebFetchTool`（抓取任意网址）与两个搜索工具（`BochaWebSearch` / `BraveWebSearch`，把搜索词发给第三方搜索服务）都可发起对外 HTTP 请求，无域名白名单/出网限制——被提示注入时可能外泄本地读到的内容。搜索工具额外意味着智能体构造的查询词会离开本机；其中 **`BraveWebSearch` 的查询词发往 Brave（美国公司），属于数据出境**，与博查（国内服务、内容合规过滤）性质不同，按需自行取舍是否配置该 key。
```

- [ ] **Step 4: 更正前序 spec 里未经验证的说法**

在 `docs/superpowers/specs/2026-07-26-web-search-design.md` 里找到淘汰备选那段：

```
- **复用库里现成的 `BraveWebSearchTool`**：`api.search.brave.com` 国内直连大概率不通，库里没有
  代理配置口子；且其 `@Tool` 描述写死了「Claude」和「Web search is only available in the US」，
  两条都不适用（描述可用现有 `describedAs()` 换掉，但网络可达性无解）。
```

替换为：

```
- **复用库里现成的 `BraveWebSearchTool`**：其 `@Tool` 描述写死了「Claude」和「Web search is only
  available in the US」，两条都不适用；且库版 Builder 只有 `resultCount`，没有 baseUrl（离线单测做不了）
  也没有超时配置。
  > **2026-07-26 更正**：本条原先还写着「`api.search.brave.com` 国内直连大概率不通」，那是**未经验证的猜测**，
  > 实测可达（`curl` → `HTTP 422 · 1.59s`，422 因未带 key，说明请求到达了服务端）。该理由不成立，已删除。
  > Brave 后来作为第二家搜索后端接入，见 `2026-07-26-brave-search-design.md`。
```

- [ ] **Step 5: 人工核对**

Run: `grep -n "BRAVE_API_KEY" springai-code-tui/README.md springai-code-tui/src/package/bin/config.env.example`
Expected: 两个文件都有命中

Run: `grep -n "大概率不通" docs/superpowers/specs/2026-07-26-web-search-design.md`
Expected: 无输出（那句已被更正段落取代）

- [ ] **Step 6: 最终全量回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/README.md \
        springai-code-tui/src/package/bin/config.env.example \
        docs/superpowers/specs/2026-07-26-web-search-design.md
git commit -m "docs: Brave 搜索配置说明、数据出境披露，并更正前序 spec 里未验证的可达性判断"
```

---

## 完成标准

- `mvn -pl springai-code-tui test` 通过（`BraveWebSearchSmokeTest` 在无 key 时 skipped 属正常）
- 两个工具注册名分别是 `BochaWebSearch` 与 `BraveWebSearch`，**不再有任何工具叫 `WebSearch`**
- 只配博查 / 只配 Brave / 两家都配 / 都不配，四种组合下系统提示指引段都自洽
- Brave 超时可观测：装饰器在 20s 未返回时抛出点名工具的可读异常

## 本计划范围外

- 不做 `SearchProvider` 抽象（共存而非二选一，没有分派需求）
- 不做故障转移（与既有「不重试」决策冲突）
- 不重写 Brave 的响应解析——**代价是它没有离线用例**，正确性靠真机冒烟
- 不改子 agent frontmatter：两个搜索工具都只会落到 `general-purpose`（`explore`/`plan`/`bash` 的
  allow 列表不含它们）
