# MCP 客户端接入 code-tui 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 code-tui 能读 `.codetui/mcp.json` 连接外部 stdio MCP server，把其工具装配给主/子 agent 调用；连接失败优雅降级，退出时清理子进程。

**Architecture:** 复用 `spring-ai-mcp` 的 `SyncMcpToolCallback` 做「MCP Tool → Spring AI ToolCallback」适配；自写 `McpConfigLoader`（两层配置）、`McpTransportFactory`（传输接缝，本期只实现 stdio）、`McpClientManager`（连接/发现/关闭，逐 server guard）。连接+发现在 `CodeTuiApplication` 启动期完成（`AgentTools.build` 保持不发网络请求），发现出的 `ToolCallback` 经 `ToolEventCallback` 装饰后并入 `AgentTools` 的共享工具列表，使主 agent 与子 agent 都能用。

**Tech Stack:** Java 17、Spring AI 2.0、`io.modelcontextprotocol.sdk:mcp-core`（经 `spring-ai-mcp` 传递）、Jackson 3（`tools.jackson`，项目已用）、JUnit 5、TamboUI（TUI）。

**设计文档：** `docs/superpowers/specs/2026-07-12-mcp-client-integration-design.md`

**通用约定：**
- 所有 mvn 命令**模块作用域**：`-pl springai-code-tui`（整仓 `-Dtest` 会被空模块打挂——见项目记忆）。
- 包路径：`io.github.javaside.springai.codetui.agent`。
- 每个 Task 结束都 commit。分支已在 `feat/mcp-client-integration`。

---

### Task 1: 加 `spring-ai-mcp` 依赖

**Files:**
- Modify: `springai-code-tui/pom.xml`（在 `<dependencies>` 内、`spring-ai-agent-utils` 附近加一条）

- [ ] **Step 1: 加依赖（版本由 spring-ai-bom 托管，不写 version）**

在 `springai-code-tui/pom.xml` 的 `<dependencies>` 中加入：

```xml
        <!-- MCP 客户端：只用其 SyncMcpToolCallback 适配层（Tool→ToolCallback）；
             传递带入 io.modelcontextprotocol.sdk:mcp-core（含 stdio/SSE/Streamable 传输）。 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-mcp</artifactId>
        </dependency>
```

- [ ] **Step 2: 编译并核对传递依赖**

Run: `mvn -q -pl springai-code-tui -am compile && mvn -q -pl springai-code-tui dependency:tree | grep -iE 'spring-ai-mcp|mcp-core|mcp-json'`
Expected: 输出含 `org.springframework.ai:spring-ai-mcp:jar:2.0.0`、`io.modelcontextprotocol.sdk:mcp-core:jar:2.0.0`、`io.modelcontextprotocol.sdk:mcp-json-jackson3:jar:2.0.0`（版本号以实际 bom 为准，只要能解析到即可）。

- [ ] **Step 3: Commit**

```bash
git add springai-code-tui/pom.xml
git commit -m "build(code-tui): 加 spring-ai-mcp 依赖（MCP 客户端适配层）"
```

---

### Task 2: `McpServerConfig` sealed 接口 + `StdioServerConfig`

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpServerConfig.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpServerConfigTest.java`

- [ ] **Step 1: 写失败测试**

Create `McpServerConfigTest.java`:

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConfigTest {

    @Test
    void stdioConfigHoldsFieldsAndExposesCommonAccessors() {
        McpServerConfig.StdioServerConfig cfg = new McpServerConfig.StdioServerConfig(
                "fs", true, Duration.ofSeconds(20), "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
                Map.of("FOO", "bar"));

        // 通过 sealed 接口的公共访问器读取
        McpServerConfig base = cfg;
        assertEquals("fs", base.name());
        assertTrue(base.enabled());
        assertEquals(Duration.ofSeconds(20), base.timeoutMs());

        // stdio 专属字段
        assertEquals("npx", cfg.command());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), cfg.args());
        assertEquals(Map.of("FOO", "bar"), cfg.env());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpServerConfigTest`
Expected: 编译失败（`McpServerConfig` 不存在）。

- [ ] **Step 3: 实现**

Create `McpServerConfig.java`:

```java
package io.github.javaside.springai.codetui.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 单个 MCP server 的不可变配置，按传输类型分型（sealed）。
 *
 * <p>本期只实现 {@link StdioServerConfig}；SSE / Streamable HTTP 是预留的扩展变体
 * （见设计文档 §5 扩展点）。公共访问器 {@link #name()} / {@link #enabled()} / {@link #timeoutMs()}
 * 供 {@link McpClientManager} 以传输无关的方式处理。
 */
public sealed interface McpServerConfig permits McpServerConfig.StdioServerConfig {

    /** server 逻辑名（配置里的键；用于工具名前缀与日志）。 */
    String name();

    /** 是否启用；配置省略时由 loader 填 true。 */
    boolean enabled();

    /** request / initialization 超时；配置省略时由 loader 填默认 20s。 */
    Duration timeoutMs();

    /**
     * stdio 传输配置：以子进程方式启动本地 MCP server。
     *
     * @param command 可执行命令（如 {@code npx}），必填
     * @param args    命令参数（可空 → 空列表）
     * @param env     追加环境变量（可空 → 空 map）
     */
    record StdioServerConfig(String name, boolean enabled, Duration timeoutMs,
                             String command, List<String> args, Map<String, String> env)
            implements McpServerConfig {
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpServerConfigTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpServerConfig.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpServerConfigTest.java
git commit -m "feat(mcp): McpServerConfig sealed 接口 + StdioServerConfig 变体"
```

---

### Task 3: `McpConfigLoader` —— 两层加载 + 合并 + 降级

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java`

**约定：** 用户级文件 `~/.codetui/mcp.json`、项目级文件 `<root>/.codetui/mcp.json`。为可测，`load` 接受两个显式文件路径；生产入口 `load(Path root)` 解析出这两个路径后委托。

- [ ] **Step 1: 写失败测试**

Create `McpConfigLoaderTest.java`:

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigLoaderTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    @Test
    void parsesStdioServerWithDefaults(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "fs": { "command": "npx", "args": ["-y", "server-fs", "/tmp"] }
                }}""");
        Path userAbsent = dir.resolve("nope.json");

        List<McpServerConfig> configs = McpConfigLoader.load(userAbsent, project);

        assertEquals(1, configs.size());
        McpServerConfig.StdioServerConfig fs = (McpServerConfig.StdioServerConfig) configs.get(0);
        assertEquals("fs", fs.name());
        assertTrue(fs.enabled());                       // 省略 → 默认 true
        assertEquals("npx", fs.command());
        assertEquals(List.of("-y", "server-fs", "/tmp"), fs.args());
        assertEquals(Map.of(), fs.env());               // 省略 → 空 map
    }

    @Test
    void projectOverridesUserBySameName(@TempDir Path dir) throws Exception {
        Path user = write(dir, "user.json", """
                { "mcpServers": {
                    "fs": { "command": "user-cmd" },
                    "onlyUser": { "command": "u" }
                }}""");
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "fs": { "command": "project-cmd" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(user, project);

        // 合并后：fs（项目覆盖用户）+ onlyUser（仅用户）
        assertEquals(2, configs.size());
        McpServerConfig.StdioServerConfig fs = (McpServerConfig.StdioServerConfig)
                configs.stream().filter(c -> c.name().equals("fs")).findFirst().orElseThrow();
        assertEquals("project-cmd", fs.command());      // 项目级胜出
        assertTrue(configs.stream().anyMatch(c -> c.name().equals("onlyUser")));
    }

    @Test
    void missingFilesYieldEmpty(@TempDir Path dir) {
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("a.json"), dir.resolve("b.json"));
        assertTrue(configs.isEmpty());
    }

    @Test
    void malformedJsonDegradesToEmptyNeverThrows(@TempDir Path dir) throws Exception {
        Path bad = write(dir, "bad.json", "{ not valid json ");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), bad);
        assertTrue(configs.isEmpty());                  // 非法 → 空、不抛
    }

    @Test
    void entryMissingCommandIsSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "p.json", """
                { "mcpServers": {
                    "good": { "command": "ok" },
                    "bad":  { "args": ["x"] }
                }}""");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), project);
        assertEquals(1, configs.size());
        assertEquals("good", configs.get(0).name());    // 缺 command 的被跳过
    }

    @Test
    void unknownTypeIsSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "p.json", """
                { "mcpServers": {
                    "remote": { "type": "sse", "url": "http://x" },
                    "local":  { "type": "stdio", "command": "ok" }
                }}""");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), project);
        // 本期只实现 stdio：sse 条目被跳过（降级），stdio 保留
        assertEquals(1, configs.size());
        assertEquals("local", configs.get(0).name());
    }

    @Test
    void disabledEntryIsExcluded(@TempDir Path dir) throws Exception {
        Path project = write(dir, "p.json", """
                { "mcpServers": {
                    "on":  { "command": "a" },
                    "off": { "command": "b", "enabled": false }
                }}""");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), project);
        assertEquals(1, configs.size());
        assertEquals("on", configs.get(0).name());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpConfigLoaderTest`
Expected: 编译失败（`McpConfigLoader` 不存在）。

- [ ] **Step 3: 实现**

Create `McpConfigLoader.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取两层 {@code mcp.json}（用户级 {@code ~/.codetui/mcp.json} + 项目级 {@code <root>/.codetui/mcp.json}），
 * 合并为 {@link McpServerConfig} 列表。项目级同名项覆盖用户级。
 *
 * <p><b>降级契约</b>：文件缺失 / JSON 非法 / 单条缺必填字段 / 未知 {@code type} / 被 {@code enabled:false} 关闭
 * → 视为空或跳过该条，记 WARN，<b>绝不抛异常</b>（照 {@code SkillCatalog} 风格）。仅 stdio 传输本期落地。
 */
public final class McpConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private McpConfigLoader() {
    }

    /** 生产入口：由项目根解析出用户级 + 项目级两个文件路径后加载。 */
    public static List<McpServerConfig> load(Path root) {
        Path userFile = Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("mcp.json");
        Path projectFile = root.resolve(".codetui").resolve("mcp.json");
        return load(userFile, projectFile);
    }

    /** 可测入口：显式两文件。项目级覆盖用户级同名项，保持插入顺序（用户项在前、项目新增在后）。 */
    public static List<McpServerConfig> load(Path userFile, Path projectFile) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        for (McpServerConfig c : parseFile(userFile)) {
            merged.put(c.name(), c);
        }
        for (McpServerConfig c : parseFile(projectFile)) {
            merged.put(c.name(), c);   // 同名覆盖
        }
        return new ArrayList<>(merged.values());
    }

    private static List<McpServerConfig> parseFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            log.warn("MCP 配置解析失败，忽略：{}（{}）", file, e.getMessage());
            return List.of();
        }
        JsonNode servers = root.get("mcpServers");
        if (servers == null || !servers.isObject()) {
            return List.of();
        }
        List<McpServerConfig> out = new ArrayList<>();
        servers.properties().forEach(entry -> {
            McpServerConfig cfg = parseEntry(entry.getKey(), entry.getValue());
            if (cfg != null && cfg.enabled()) {
                out.add(cfg);
            }
        });
        return out;
    }

    /** 解析单条；不合法则记 WARN 并返回 null（跳过）。 */
    private static McpServerConfig parseEntry(String name, JsonNode node) {
        String type = node.has("type") ? node.get("type").asString() : "stdio";
        if (!"stdio".equals(type)) {
            log.warn("MCP server '{}' 传输 type='{}' 暂未支持，跳过。", name, type);
            return null;
        }
        JsonNode commandNode = node.get("command");
        if (commandNode == null || commandNode.asString().isBlank()) {
            log.warn("MCP server '{}' 缺 command，跳过。", name);
            return null;
        }
        boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean();
        Duration timeout = node.has("timeoutMs")
                ? Duration.ofMillis(node.get("timeoutMs").asLong()) : DEFAULT_TIMEOUT;

        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.get("args");
        if (argsNode != null && argsNode.isArray()) {
            argsNode.forEach(a -> args.add(a.asString()));
        }
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.get("env");
        if (envNode != null && envNode.isObject()) {
            envNode.properties().forEach(e -> env.put(e.getKey(), e.getValue().asString()));
        }
        return new McpServerConfig.StdioServerConfig(name, enabled, timeout,
                commandNode.asString(), List.copyOf(args), Map.copyOf(env));
    }
}
```

> 注：Jackson 3（`tools.jackson`）节点 API 用 `asString()` / `asBoolean()` / `asLong()` / `properties()`。若某方法名在实际版本略有出入（如 `asText()`），按编译错误提示改用等价方法——语义不变。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpConfigLoaderTest`
Expected: PASS（7 个用例全绿）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java
git commit -m "feat(mcp): McpConfigLoader 两层加载+合并+降级"
```

---

### Task 4: `McpTransportFactory` —— 传输接缝（本期仅 stdio）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java`

- [ ] **Step 1: 写失败测试**

Create `McpTransportFactoryTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportFactoryTest {

    @Test
    void stdioConfigProducesTransport() {
        McpServerConfig.StdioServerConfig cfg = new McpServerConfig.StdioServerConfig(
                "fs", true, Duration.ofSeconds(20), "echo", List.of("hi"), Map.of());

        Optional<McpClientTransport> t = McpTransportFactory.create(cfg);

        assertTrue(t.isPresent());   // 仅构造 transport 对象，不启动进程
    }
}
```

> 说明：`create` 只构造 `StdioClientTransport` 对象（不 `initialize`、不启动子进程），故用无害 `echo` 命令即可，测试不产生真实 IO。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpTransportFactoryTest`
Expected: 编译失败（`McpTransportFactory` 不存在）。

- [ ] **Step 3: 实现**

Create `McpTransportFactory.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 传输接缝：把 {@link McpServerConfig} 变体映射为 SDK 的 {@link McpClientTransport}。
 *
 * <p>这是加新传输的<b>唯一分型点</b>（设计文档 §5 扩展点）：将来接入 SSE / Streamable HTTP，
 * 只需在此加一个分支，用 mcp-core 现成的 {@code HttpClientSseClientTransport} /
 * {@code HttpClientStreamableHttpTransport}（基于 JDK HttpClient，无新依赖）。
 * {@link McpClientManager} 与其余流程<b>零改动</b>。
 *
 * <p>构造失败或传输未实现 → 记 WARN、返回 {@link Optional#empty()}（降级，不抛）。
 */
public final class McpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFactory.class);

    private McpTransportFactory() {
    }

    public static Optional<McpClientTransport> create(McpServerConfig config) {
        try {
            if (config instanceof McpServerConfig.StdioServerConfig stdio) {
                ServerParameters params = ServerParameters.builder(stdio.command())
                        .args(stdio.args())
                        .env(stdio.env())
                        .build();
                return Optional.of(new StdioClientTransport(params, McpJsonDefaults.getMapper()));
            }
            log.warn("MCP server '{}' 传输类型未实现，跳过。", config.name());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("MCP server '{}' 构造传输失败，跳过：{}", config.name(), e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpTransportFactoryTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java
git commit -m "feat(mcp): McpTransportFactory 传输接缝（本期仅 stdio）"
```

---

### Task 5: `McpClientManager` —— 连接 / 发现 / 关闭

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpClientManager.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpClientManagerTest.java`

**职责：**
- `connectAll(List<McpServerConfig>)`：并行、每 server try/catch + 超时，建 `McpSyncClient` 并 `initialize()`；失败跳过。
- `toolCallbacks()`：逐 client `listTools()`（guard），每 tool 造 `SyncMcpToolCallback`，工具名前缀 `mcp__<server>__<tool>`。返回未装饰 `List<ToolCallback>`。
- `close()`：逐 client `closeGracefully()`，总超时 2s 兜底，吞异常。
- `prefixedName(server, tool)`：纯函数，单测覆盖。

- [ ] **Step 1: 写失败测试**（纯函数 + 无依赖的降级路径）

Create `McpClientManagerTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientManagerTest {

    @Test
    void prefixedNameNamespacesAndSanitizes() {
        // server 与 tool 名里的 '-' 归一为 '_'，前缀 mcp__<server>__<tool>
        assertEquals("mcp__chrome_devtools__take_screenshot",
                McpClientManager.prefixedName("chrome-devtools", "take-screenshot"));
    }

    @Test
    void bogusServerDegradesToEmptyNeverThrows() {
        // command 指向不存在的可执行文件：连接/初始化必失败，但 connectAll 不抛、降级为 0 工具。
        McpServerConfig.StdioServerConfig bogus = new McpServerConfig.StdioServerConfig(
                "bogus", true, Duration.ofSeconds(2),
                "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of());

        McpClientManager mgr = McpClientManager.connectAll(List.of(bogus));
        try {
            assertTrue(mgr.toolCallbacks().isEmpty());   // 坏 server → 无工具，且未抛
        } finally {
            mgr.close();                                 // 关闭也不抛
        }
    }

    @Test
    void emptyConfigYieldsEmptyManager() {
        McpClientManager mgr = McpClientManager.connectAll(List.of());
        try {
            assertTrue(mgr.toolCallbacks().isEmpty());
        } finally {
            mgr.close();
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpClientManagerTest`
Expected: 编译失败（`McpClientManager` 不存在）。

- [ ] **Step 3: 实现**

Create `McpClientManager.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理一组 stdio MCP server 的生命周期：连接 → 工具发现 → 关闭。
 *
 * <p><b>失败隔离</b>：每个 server 的连接与发现各自 try/catch，一个坏 server 不影响其他
 * （不用 {@code SyncMcpToolCallbackProvider} 的聚合 flatMap——它一处失败会带崩全部，见设计文档 §2）。
 *
 * <p><b>并发安全</b>：每个 server 只建<b>一个</b>共享 {@link McpSyncClient}；stdio 传输出站串行化 +
 * JSON-RPC id 多路复用，故并行子 agent 同时调用同一 server 安全（设计文档 §2）。
 *
 * <p><b>传输无关</b>：只经 {@link McpTransportFactory} 拿抽象 {@link McpClientTransport}，
 * 连接/发现/关闭逻辑不含任何 stdio 专属分支。
 */
public final class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final long CLOSE_BUDGET_MS = 2_000;   // 关闭总预算：绝不让清理拖慢 /exit

    private final List<McpSyncClient> clients;

    private McpClientManager(List<McpSyncClient> clients) {
        this.clients = clients;
    }

    /** 并行连接所有 server；每个各自 guard，失败跳过。返回持有已连 client 的 manager（可能 0 个）。 */
    public static McpClientManager connectAll(List<McpServerConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return new McpClientManager(List.of());
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(configs.size(), 8), r -> {
                    Thread t = new Thread(r, "mcp-connect");
                    t.setDaemon(true);   // daemon：绝不阻止 JVM 退出
                    return t;
                });
        try {
            List<CompletableFuture<McpSyncClient>> futures = new ArrayList<>();
            for (McpServerConfig cfg : configs) {
                futures.add(CompletableFuture.supplyAsync(() -> connectOne(cfg), pool));
            }
            List<McpSyncClient> connected = new ArrayList<>();
            for (CompletableFuture<McpSyncClient> f : futures) {
                McpSyncClient c = f.join();   // supplyAsync 内已 guard，join 不抛业务异常
                if (c != null) {
                    connected.add(c);
                }
            }
            return new McpClientManager(List.copyOf(connected));
        } finally {
            pool.shutdown();
        }
    }

    /** 连接单个 server：构造 transport → sync client → initialize()。任何失败 → 记 WARN、返回 null。 */
    private static McpSyncClient connectOne(McpServerConfig cfg) {
        McpClientTransport transport = McpTransportFactory.create(cfg).orElse(null);
        if (transport == null) {
            return null;
        }
        try {
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(cfg.timeoutMs())
                    .initializationTimeout(cfg.timeoutMs())
                    // 把 server 逻辑名塞进 client Implementation 的 title，toolCallbacks() 再读回作前缀。
                    .clientInfo(McpSchema.Implementation.builder("code-tui", AppInfo.version())
                            .title(cfg.name()).build())
                    .build();
            client.initialize();   // 阻塞握手，超时/失败抛异常
            log.info("MCP server '{}' 已连接。", cfg.name());
            return client;
        } catch (Exception e) {
            log.warn("MCP server '{}' 连接失败，跳过：{}", cfg.name(), e.getMessage());
            return null;
        }
    }

    /**
     * 发现所有已连 server 的工具，转成带前缀的 {@link ToolCallback}（未装饰）。
     * 逐 client guard：某 server {@code listTools} 失败只丢它的工具，不影响其他。
     */
    public List<ToolCallback> toolCallbacks() {
        List<ToolCallback> out = new ArrayList<>();
        for (McpSyncClient client : clients) {
            String server = client.getClientInfo().title();   // = cfg.name()（见 connectOne 的 Implementation）
            try {
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    out.add(SyncMcpToolCallback.builder()
                            .mcpClient(client)
                            .tool(tool)
                            .prefixedToolName(prefixedName(server, tool.name()))
                            .build());
                }
            } catch (Exception e) {
                log.warn("MCP server '{}' 工具发现失败，跳过：{}", server, e.getMessage());
            }
        }
        return out;
    }

    /** 关闭所有 client（优雅），总预算 {@value #CLOSE_BUDGET_MS}ms；超时/异常一律不阻塞退出。 */
    public void close() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_BUDGET_MS);
        for (McpSyncClient client : clients) {
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (leftMs <= 0) {
                break;   // 预算耗尽：剩下的交给 System.exit 带走
            }
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.warn("MCP client 关闭异常（忽略）：{}", e.getMessage());
            }
        }
    }

    /** 工具名前缀：mcp__<server>__<tool>，把 '-' 归一为 '_' 以符合工具名字符集，避免与内置工具/多 server 重名。 */
    static String prefixedName(String server, String tool) {
        return "mcp__" + sanitize(server) + "__" + sanitize(tool);
    }

    private static String sanitize(String s) {
        return s.replace('-', '_');
    }
}
```

> 说明：
> - `McpSchema.Implementation.builder(name, version).title(cfg.name())`（已核实此 builder 签名）——把 server 逻辑名放进 client info 的 `title`，`toolCallbacks()` 再用 `getClientInfo().title()` 取回作前缀。（`getClientInfo()` 返回的是<b>本 client</b>自报的 Implementation，即此处所设，非 server 的。）
> - `AppInfo.version()`：项目已有 `AppInfo`（`io.github.javaside.springai.codetui.AppInfo`）。若无 `version()` 方法，改传 `AppInfo` 中已有的版本常量或字面量 `"1.2.1"`——仅用于握手自报，无功能影响。实现时先看 `AppInfo` 有哪个访问器。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=McpClientManagerTest`
Expected: PASS（3 个用例全绿；`bogusServerDegradesToEmptyNeverThrows` 在几秒内因坏命令降级完成）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpClientManager.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpClientManagerTest.java
git commit -m "feat(mcp): McpClientManager 连接/发现/关闭（逐 server 失败隔离）"
```

---

### Task 6: `AgentTools.build` 接入 MCP 工具（主 + 子 agent）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`（`build` 签名 + `all` 列表）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsMcpWiringTest.java`

**接入点（关键）：** 在 `build` 里，MCP 工具（原始 `ToolCallback`）在装饰循环<b>之前</b>并入 `all` 列表，即可随现有循环被 `ToolEventCallback` 统一装饰，并自动流入 `decorated[]`（主 agent 的 `toolsWithTask`）与 `decoratedList`（`SubagentRunner` → 子 agent）。这样一处改动即让主+子都拿到。

- [ ] **Step 1: 写失败测试**

Create `AgentToolsMcpWiringTest.java`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AgentToolsMcpWiringTest {

    /** 一个最简假 MCP 工具，验证 build 能接收并装配 mcpTools 列表。 */
    private static ToolCallback fakeMcpTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("mcp__fake__ping")
                        .description("fake mcp tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }
            @Override public String call(String toolInput) { return "pong"; }
        };
    }

    @Test
    void buildAcceptsMcpToolsWithoutThrowing(@TempDir Path root) {
        // 装配期不发网络请求：假 key registry 也能 build（沿用 AgentRuntimeTest 的前提）。
        assertDoesNotThrow(() -> AgentTools.build(
                AgentRuntimeTestSupport.dummyRegistry(), root, new ConversationState(),
                List.of(fakeMcpTool())));
    }

    @Test
    void threeArgOverloadStillBuildsForBackwardCompat(@TempDir Path root) {
        assertDoesNotThrow(() -> AgentTools.build(
                AgentRuntimeTestSupport.dummyRegistry(), root, new ConversationState()));
    }
}
```

> 注：`AgentRuntimeTestSupport.dummyRegistry()` 是对现有测试里 `dummyRegistry()` 的复用。若项目没有这个共享测试工具类，则把 `AgentRuntimeTest` 里构造假 registry 的私有方法提取为包级 `static` 供两处共用，或在本测试内内联同样的假 registry 构造（与 `AgentRuntimeTest.dummyRegistry()` 完全一致，避免漂移）。实现时先看 `AgentRuntimeTest` 现有写法照搬。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentToolsMcpWiringTest`
Expected: 编译失败（`build` 无 4 参重载）。

- [ ] **Step 3: 实现——加 4 参重载并接入**

在 `AgentTools.java` 中，把现有 3 参 `build` 改为委托，新增 4 参实现。找到：

```java
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener) {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();
```

改为（**保留原方法体，仅重命名为 4 参并加 mcpTools 参数 + 加一个 3 参委托**）：

```java
    /** 向后兼容：无 MCP 工具（等价空列表）。现有测试与旧调用走这条。 */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener) {
        return build(registry, root, listener, java.util.List.of());
    }

    /**
     * 组装编码 Agent 的 ChatClient。仅做装配，不发起任何网络请求。
     *
     * @param mcpTools 启动期已由 {@link McpClientManager} 连接+发现好的 MCP 工具（原始 ToolCallback）；
     *                 在此并入共享工具列表并统一用 {@link ToolEventCallback} 装饰，故主 agent 与子 agent 都可用。
     *                 连接失败时上层传空列表（降级）。
     */
    public static AgentRuntime build(ProviderRegistry registry, Path root, AgentListener listener,
                                     java.util.List<ToolCallback> mcpTools) {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();
```

然后在 `all` 列表构造处（原代码）：

```java
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(fs, sh, grep, glob, webFetch, askTool)));
        all.add(todoCallback);      // 薄适配器版 TodoWrite（名仍为 "TodoWrite"）
        all.add(reloadableSkill);   // 始终注册可重载 Skill 代理（支持运行期 /reload 从零热加载）
```

在其后追加一行（MCP 工具并入，随后统一装饰 + 流入 decorated/decoratedList）：

```java
        // MCP 工具（启动期已连接+发现）：并入共享列表，随下方循环被 ToolEventCallback 装饰，
        // 从而同时流入 decorated[]（主 agent）与 decoratedList（子 agent）。空列表则无副作用。
        all.addAll(mcpTools);
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl springai-code-tui test -Dtest=AgentToolsMcpWiringTest,AgentRuntimeTest`
Expected: PASS（新用例 + 原 `AgentRuntimeTest` 回归全绿）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsMcpWiringTest.java
git commit -m "feat(mcp): AgentTools.build 接入 MCP 工具（主+子 agent 共享）"
```

---

### Task 7: `CodeTuiApplication` 装配 manager + 退出清理

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`

**要点：** 启动期 load 配置 → connectAll → 传 `toolCallbacks()` 进 `build` → 打印连接概要；两条退出路径（正常 `System.exit(0)` 前、崩溃 `System.exit(1)` 前）都先 `manager.close()`（有界，不阻塞退出）。**close 很重要**：stdio server 是 fork 的子进程，`System.exit` 未必回收，`closeGracefully` 会 destroy 子进程，避免孤儿 node 残留。

- [ ] **Step 1: 加载配置 + 连接 + 传入 build**

在 `CodeTuiApplication.main` 中，找到：

```java
        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state);
```

改为（在其前插入 MCP 连接，并把工具传入 build）：

```java
        // MCP：启动期读 .codetui/mcp.json（两层）→ 并行连接 → 发现工具。连接失败静默降级为空。
        java.util.List<io.github.javaside.springai.codetui.agent.McpServerConfig> mcpConfigs =
                io.github.javaside.springai.codetui.agent.McpConfigLoader.load(root);
        io.github.javaside.springai.codetui.agent.McpClientManager mcpManager =
                io.github.javaside.springai.codetui.agent.McpClientManager.connectAll(mcpConfigs);
        java.util.List<org.springframework.ai.tool.ToolCallback> mcpTools = mcpManager.toolCallbacks();
        if (!mcpTools.isEmpty()) {
            state.pushInfo("（MCP：已连接 " + mcpConfigs.size() + " 个 server，发现 " + mcpTools.size() + " 个工具。）");
        }

        AgentTools.AgentRuntime runtime = AgentTools.build(registry, root, state, mcpTools);
```

- [ ] **Step 2: 退出前清理（两条路径）**

找到结尾：

```java
        try {
            view.run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);   // 交互期崩溃...
        }
        // /exit 后立即终止 JVM...
        System.exit(0);
```

改为（两处 `System.exit` 前都插入 `mcpManager.close()`）：

```java
        try {
            view.run();
        } catch (Throwable t) {
            t.printStackTrace();
            mcpManager.close();   // 关闭 MCP 子进程（有界 2s），避免孤儿进程
            System.exit(1);       // 交互期崩溃...
        }
        // /exit 后立即终止 JVM...
        mcpManager.close();       // 优雅关闭 MCP 子进程（有界 2s）后再强制退出
        System.exit(0);
```

- [ ] **Step 3: 编译 + 全模块测试回归**

Run: `mvn -q -pl springai-code-tui test`
Expected: 全模块用例 PASS（含前几个 Task 的新用例；无回归）。

- [ ] **Step 4: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java
git commit -m "feat(mcp): 启动装配 McpClientManager + 退出前有界清理子进程"
```

---

### Task 8: 端到端实机验证（真实 stdio server + /exit 不卡）

**Files:**
- 临时：`<repo-root>/.codetui/mcp.json`（本地验证用，`.codetui/` 已 gitignore，不入库）

> 这是集成真相验证（照项目「pty 实机 + verify」传统），非单测断言。需本机有 `node`/`npx`。

- [ ] **Step 1: 打包**

Run: `mvn -q -pl springai-code-tui -am package -DskipTests`
Expected: BUILD SUCCESS，产出可运行 jar。

- [ ] **Step 2: 写一个真实 MCP server 配置**

创建 `<repo-root>/.codetui/mcp.json`：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    }
  }
}
```

- [ ] **Step 3: 启动并确认工具接入**

启动 code-tui（按项目既有启动方式，如 `java -jar springai-code-tui/target/<artifact>.jar`）。
Expected：开场出现「（MCP：已连接 1 个 server，发现 N 个工具。）」。

在对话里让模型「用 filesystem 工具列出 /tmp 下的文件」。
Expected：工具活动行显示 `mcp__filesystem__*` 被调用，返回目录内容；结果正确。

- [ ] **Step 4: 验证 /exit 不卡 + 无孤儿子进程**

先记录当前 node 子进程：`pgrep -laf server-filesystem`（应看到一个）。
在 code-tui 内执行 `/exit`。
Expected：进程**立即**退出（无 ~60s 卡顿）。
退出后再次 `pgrep -laf server-filesystem`。
Expected：**无残留**（`closeGracefully` 已 destroy 子进程）。

- [ ] **Step 5: 验证降级——坏配置不崩启动**

把 `mcp.json` 的 `command` 改成 `"definitely-not-real-xyz"`，重启。
Expected：启动正常（无「已连接」提示或提示 0 工具），TUI 可用，日志有一条 WARN；**不崩溃、不卡**。

- [ ] **Step 6: 清理临时配置（不入库）**

```bash
rm -f .codetui/mcp.json
```

> 无需 commit（`.codetui/` 已被 gitignore）。若验证中发现问题，回到对应 Task 修复。

---

## Self-Review（作者自检）

**1. Spec 覆盖：**
- §4 配置格式（两层/合并/type/降级）→ Task 3 ✅
- §5 组件（Config/Loader/TransportFactory/Manager）→ Task 2/3/4/5 ✅；扩展点（factory 接缝、sealed 分型）→ Task 2/4 ✅
- §6 接入装配（build 之外连接、并入 decorated、主+子）→ Task 6/7 ✅
- §7 退出清理（有界 close、子进程）→ Task 5(close) + Task 7(调用) + Task 8(验证) ✅
- §8 错误处理（缺失/非法/单 server 失败/调用失败/关闭超时）→ Task 3/5 用例 + Task 8 降级验证 ✅
- §9 测试策略（config 纯单元、命名/隔离单元、集成 smoke、模块作用域）→ Task 3/4/5 单测 + Task 8 smoke ✅
- §10 依赖 → Task 1 ✅
- §2 结论（只用 SyncMcpToolCallback、不用 Provider 聚合、排除 Async、单 client 并发安全）→ Task 5 实现体现 ✅

**2. 占位符扫描：** 无 TBD/TODO；每个代码步骤含完整代码与预期输出。少数「若签名略有出入按编译提示改等价方法」是对 SDK 次要版本差异的应对指引，非占位（核心 API 已在写计划前用源码核实：`McpClient.sync/requestTimeout/initializationTimeout/clientInfo`、`ServerParameters.builder(cmd).args().env()`、`StdioClientTransport(params, McpJsonDefaults.getMapper())`、`SyncMcpToolCallback.builder().mcpClient().tool().prefixedToolName()`、`McpSyncClient.listTools()/callTool()/closeGracefully()/getClientInfo()`）。

**3. 类型一致性：** `McpServerConfig`（sealed）/`StdioServerConfig`（`name,enabled,timeoutMs,command,args,env`）在 Task 2 定义，Task 3/4/5 用法一致；`McpConfigLoader.load` 双入口签名一致；`McpTransportFactory.create → Optional<McpClientTransport>` 一致；`McpClientManager.connectAll/toolCallbacks/close/prefixedName` 在 Task 5 定义、Task 7 消费一致；`AgentTools.build` 4 参重载在 Task 6 定义、Task 7 调用一致。
