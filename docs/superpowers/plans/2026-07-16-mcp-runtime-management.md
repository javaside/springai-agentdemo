# /mcp 运行期 MCP 管理实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `/mcp` 命令：列出已安装 MCP server（含禁用项），逐个启用/禁用，立即生效并回写 mcp.json。

**Architecture:** 新建可变 `McpRegistry` 取代裸 `McpClientManager` 成为 MCP 中枢（全量条目、连接/发现/装饰/关闭）；MCP 工具不再烧入 `defaultTools`，改为主 agent 每回合 `.tools(registry.activeTools())` 快照注入、子 agent 经 `effectiveTools` 拼接注入；新建 `McpConfigWriter` 读-改-写回条目所属层 mcp.json 的 `enabled` 字段。

**Tech Stack:** Java 17+、Spring AI 2.0（per-request `tools()` 与 defaultTools 合并语义已核实源码）、MCP Java SDK（McpSyncClient）、Jackson 3（`tools.jackson.databind`）、TamboUI、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-07-16-mcp-runtime-management-design.md`

**验证命令一律模块作用域**（整仓 `mvn test -Dtest=…` 会被空模块打挂）：
`mvn test -pl springai-code-tui -Dtest=<TestClass>`

---

## 文件结构总览

| 文件 | 动作 | 职责 |
|---|---|---|
| `agent/McpConfigLoader.java` | 改 | 新增 `loadAll`（保留 disabled 条目 + 来源层/文件路径标注）；现有 `load()` 语义不变 |
| `agent/McpConfigWriter.java` | 建 | 读-改-写单条目 `enabled` 字段，原子写，失败降级返回 false |
| `agent/McpClientManager.java` | 改 | 提炼 `connectDetailed`（带错误文本）/`discoverTools`/`closeAll` 为包可见静态件供 registry 复用 |
| `agent/McpRegistry.java` | 建 | 运行期中枢：条目表 + enable/disable + activeTools 快照 + servers 视图 + close |
| `agent/AgentTools.java` | 改 | `build` 第 4 参改 `McpRegistry`；MCP 工具不再并入 `all` |
| `agent/SubagentRunner.java` | 改 | 持 registry，`effectiveTools(spec)` = 内置 + activeTools 后过滤 |
| `agent/CodingAgent.java` | 改 | 持 registry；submit 链上 `.tools(activeTools)`；实现 SubmitHandler 新门面 |
| `agent/SubmitHandler.java` | 改 | 新增 `mcpServers()/enableMcp()/disableMcp()` 默认方法 |
| `ui/CodeTuiView.java` | 改 | `/mcp` 命令 + 选择器面板 + 后台连接线程 |
| `CodeTuiApplication.java` | 改 | 用 `McpRegistry.init` 取代 loader+manager 流水线 |
| `src/test/resources/scripts/mcp_manage_smoke.py` | 建 | pty 实机冒烟：/mcp 开面板 → 禁用 → 断言状态翻转 + 回写 |

---

### Task 1: McpConfigLoader.loadAll（保留 disabled 条目 + 来源层）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java`（追加）

- [x] **Step 1: 写失败测试**

在 `McpConfigLoaderTest` 追加（沿用该类现有 `@TempDir`/写临时 json 风格）：

```java
@Test
void loadAllKeepsDisabledEntriesAndTagsSource(@TempDir Path dir) throws Exception {
    Path userFile = dir.resolve("user-mcp.json");
    Path projectFile = dir.resolve("project-mcp.json");
    Files.writeString(userFile, """
            {"mcpServers":{
              "alpha":{"command":"echo","enabled":false},
              "beta":{"command":"echo"}
            }}""");
    Files.writeString(projectFile, """
            {"mcpServers":{
              "beta":{"command":"echo2","enabled":false},
              "gamma":{"command":"echo"}
            }}""");

    List<McpConfigLoader.LoadedServer> all = McpConfigLoader.loadAll(userFile, projectFile);

    assertEquals(3, all.size(), "disabled 条目也要保留");
    Map<String, McpConfigLoader.LoadedServer> byName = new HashMap<>();
    all.forEach(s -> byName.put(s.config().name(), s));

    assertFalse(byName.get("alpha").config().enabled());
    assertEquals(McpConfigLoader.ConfigSource.USER, byName.get("alpha").source());
    assertEquals(userFile, byName.get("alpha").file());

    // 项目级覆盖用户级同名项：取项目级配置、来源层与回写目标都是项目级
    assertFalse(byName.get("beta").config().enabled());
    assertEquals(McpConfigLoader.ConfigSource.PROJECT, byName.get("beta").source());
    assertEquals(projectFile, byName.get("beta").file());
    assertEquals("echo2", ((McpServerConfig.StdioServerConfig) byName.get("beta").config()).command());

    assertEquals(McpConfigLoader.ConfigSource.PROJECT, byName.get("gamma").source());
}

@Test
void loadAllDegradesOnMissingFiles(@TempDir Path dir) {
    assertTrue(McpConfigLoader.loadAll(dir.resolve("nope1.json"), dir.resolve("nope2.json")).isEmpty());
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=McpConfigLoaderTest`
Expected: 编译失败（`LoadedServer`/`ConfigSource`/`loadAll` 不存在）

- [x] **Step 3: 实现**

在 `McpConfigLoader` 内追加（`load()` 一字不改；把现有 `parseFile` 的 enabled 过滤上提，内部复用无过滤版本）：

```java
/** 配置来源层：决定 /mcp 面板标注与回写目标文件。 */
public enum ConfigSource { USER, PROJECT }

/** 全量加载的单条目：配置 + 来源层 + 所属文件（回写目标）。 */
public record LoadedServer(McpServerConfig config, ConfigSource source, Path file) { }

/** 生产入口（全量版）：与 {@link #load(Path)} 同源两文件，但保留 enabled:false 条目，供 /mcp 列出再启用。 */
public static List<LoadedServer> loadAll(Path root) {
    Path userFile = Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("mcp.json");
    Path projectFile = root.resolve(".codetui").resolve("mcp.json");
    return loadAll(userFile, projectFile);
}

/** 可测入口（全量版）：项目级覆盖用户级同名项（含来源层与回写目标一并换成项目级）。 */
public static List<LoadedServer> loadAll(Path userFile, Path projectFile) {
    Map<String, LoadedServer> merged = new LinkedHashMap<>();
    for (McpServerConfig c : parseFileAll(userFile)) {
        merged.put(c.name(), new LoadedServer(c, ConfigSource.USER, userFile));
    }
    for (McpServerConfig c : parseFileAll(projectFile)) {
        merged.put(c.name(), new LoadedServer(c, ConfigSource.PROJECT, projectFile));
    }
    return List.copyOf(merged.values());
}
```

改造 `parseFile`：把主体重命名为 `parseFileAll`（去掉 `cfg.enabled()` 过滤，只判 `cfg != null` 即加入），`parseFile` 变为：

```java
private static List<McpServerConfig> parseFile(Path file) {
    List<McpServerConfig> out = new ArrayList<>();
    for (McpServerConfig c : parseFileAll(file)) {
        if (c.enabled()) {
            out.add(c);
        }
    }
    return out;
}
```

同时更新类 javadoc：降级契约中「被 enabled:false 关闭 → 跳过」仅适用 `load()`；`loadAll` 保留禁用项。

- [x] **Step 4: 跑测试确认通过（含既有用例零回归）**

Run: `mvn test -pl springai-code-tui -Dtest=McpConfigLoaderTest`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java
git commit -m "feat(mcp): McpConfigLoader.loadAll 保留 disabled 条目并标注来源层"
```

---

### Task 2: McpConfigWriter（回写 enabled 字段）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigWriter.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigWriterTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void setEnabledFlipsOnlyThatField(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, """
                {"mcpServers":{
                  "fs":{"command":"npx","args":["-y","pkg"],"env":{"K":"V"},"timeoutMs":9000},
                  "other":{"command":"echo"}
                }}""");

        assertTrue(McpConfigWriter.setEnabled(f, "fs", false));

        JsonNode root = MAPPER.readTree(Files.readString(f));
        JsonNode fs = root.get("mcpServers").get("fs");
        assertFalse(fs.get("enabled").asBoolean(), "enabled 应翻为 false");
        assertEquals("npx", fs.get("command").asString(), "其余字段不动");
        assertEquals(9000, fs.get("timeoutMs").asLong());
        assertEquals("V", fs.get("env").get("K").asString());
        assertNull(root.get("mcpServers").get("other").get("enabled"), "别的条目不动");
        // 条目顺序保持：fs 在 other 前
        List<String> names = new java.util.ArrayList<>();
        root.get("mcpServers").properties().forEach(e -> names.add(e.getKey()));
        assertEquals(List.of("fs", "other"), names);
    }

    @Test
    void setEnabledTrueWorksToo(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, """
                {"mcpServers":{"fs":{"command":"npx","enabled":false}}}""");
        assertTrue(McpConfigWriter.setEnabled(f, "fs", true));
        JsonNode root = MAPPER.readTree(Files.readString(f));
        assertTrue(root.get("mcpServers").get("fs").get("enabled").asBoolean());
    }

    @Test
    void unknownServerReturnsFalseWithoutTouchingFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mcp.json");
        String original = """
                {"mcpServers":{"fs":{"command":"npx"}}}""";
        Files.writeString(f, original);
        assertFalse(McpConfigWriter.setEnabled(f, "nope", false));
        assertEquals(original, Files.readString(f), "文件不应被改写");
    }

    @Test
    void missingOrBrokenFileDegradesToFalseNeverThrows(@TempDir Path dir) throws Exception {
        assertFalse(McpConfigWriter.setEnabled(dir.resolve("nope.json"), "fs", false));
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertFalse(McpConfigWriter.setEnabled(broken, "fs", false));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=McpConfigWriterTest`
Expected: 编译失败（类不存在）

- [x] **Step 3: 实现**

```java
package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * mcp.json 回写：只改 {@code mcpServers.<name>.enabled} 一个字段，其余树原样保留
 * （Jackson 树模型读改写，条目插入顺序不变）。
 *
 * <p>原子写：先写同目录临时文件再 move（优先 ATOMIC_MOVE，不支持的文件系统降级普通替换），
 * 防写一半损坏配置（照 FileSessionRepository 落盘风格）。
 *
 * <p><b>降级契约</b>：文件缺失 / JSON 非法 / 条目不存在 / 写失败 → 记 WARN、返回 false，<b>绝不抛异常</b>。
 * 调用方（McpRegistry）据 false 提示「仅本次运行生效」。
 */
final class McpConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(McpConfigWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpConfigWriter() {
    }

    static boolean setEnabled(Path file, String serverName, boolean enabled) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode servers = root.get("mcpServers");
            if (servers == null || !servers.isObject()) {
                log.warn("MCP 回写失败：{} 无 mcpServers 对象。", file);
                return false;
            }
            JsonNode entry = servers.get(serverName);
            if (entry == null || !entry.isObject()) {
                log.warn("MCP 回写失败：{} 无条目 '{}'。", file, serverName);
                return false;
            }
            ((ObjectNode) entry).put("enabled", enabled);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.warn("MCP 回写失败：{} '{}' enabled={}：{}", file, serverName, enabled, e.getMessage());
            return false;
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=McpConfigWriterTest`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigWriter.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigWriterTest.java
git commit -m "feat(mcp): McpConfigWriter 原子回写单条目 enabled 字段"
```

---

### Task 3: McpClientManager 提炼可复用静态件

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpClientManager.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpClientManagerTest.java`（追加）

- [x] **Step 1: 写失败测试**

在 `McpClientManagerTest` 追加：

```java
@Test
void connectDetailedReturnsErrorTextOnFailure() {
    McpServerConfig.StdioServerConfig bogus = new McpServerConfig.StdioServerConfig(
            "bogus", true, Duration.ofSeconds(2),
            "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of());
    McpClientManager.ConnectOutcome out = McpClientManager.connectDetailed(bogus);
    assertNull(out.client());
    assertNotNull(out.error(), "失败必须带错误文本（供 /mcp 面板显示）");
}

@Test
void closeAllOnEmptyIsNoop() {
    assertDoesNotThrow(() -> McpClientManager.closeAll(List.of()));
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=McpClientManagerTest`
Expected: 编译失败（`ConnectOutcome`/`connectDetailed`/`closeAll` 不存在）

- [x] **Step 3: 实现**

对 `McpClientManager` 做三处提炼（连接/发现/关闭逻辑均不重写，只挪）：

(a) `connectOne` 改为薄壳，主体移入 `connectDetailed`：

```java
/** 连接结果：client 与 error 恰有一个非 null。error 为面向 UI 的失败摘要。 */
record ConnectOutcome(McpSyncClient client, String error) { }

/** 连接单个 server（带错误文本版，供 McpRegistry 的 /mcp 面板显示失败原因）。 */
static ConnectOutcome connectDetailed(McpServerConfig cfg) {
    McpClientTransport transport = McpTransportFactory.create(cfg).orElse(null);
    if (transport == null) {
        return new ConnectOutcome(null, "构造传输失败（详见日志）");
    }
    try {
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(cfg.timeoutMs())
                .initializationTimeout(cfg.timeoutMs())
                .clientInfo(McpSchema.Implementation.builder("code-tui", AppInfo.version())
                        .title(cfg.name()).build())
                .build();
        client.initialize();
        log.info("MCP server '{}' 已连接。", cfg.name());
        return new ConnectOutcome(client, null);
    } catch (Exception e) {
        log.warn("MCP server '{}' 连接失败，跳过：{}", cfg.name(), e.getMessage());
        return new ConnectOutcome(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}

private static McpSyncClient connectOne(McpServerConfig cfg) {
    return connectDetailed(cfg).client();
}
```

(b) `toolCallbacks()` 的单 client 发现循环提为静态 `discoverTools`，实例方法改为逐 client 调它：

```java
/** 发现单个已连 client 的工具（带 mcp__ 前缀、未装饰）；listTools 失败记 WARN 返回空列表。 */
static List<ToolCallback> discoverTools(McpSyncClient client) {
    List<ToolCallback> out = new ArrayList<>();
    String server = "?";
    try {
        server = client.getClientInfo().title();
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
    return out;
}

public List<ToolCallback> toolCallbacks() {
    List<ToolCallback> out = new ArrayList<>();
    for (McpSyncClient client : clients) {
        out.addAll(discoverTools(client));
    }
    return out;
}
```

(c) `close()` 主体提为静态 `closeAll(Collection<McpSyncClient>)`（2s 预算注释随行迁移），实例 `close()` 变 `closeAll(clients)` 一行。

- [x] **Step 4: 跑测试确认通过（含既有用例零回归）**

Run: `mvn test -pl springai-code-tui -Dtest=McpClientManagerTest`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpClientManager.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpClientManagerTest.java
git commit -m "refactor(mcp): 提炼 connectDetailed/discoverTools/closeAll 供 McpRegistry 复用"
```

---

### Task 4: McpRegistry（运行期中枢）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpRegistry.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpRegistryTest.java`

- [x] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpConfigLoader.LoadedServer bogus(String name, boolean enabled, Path file) {
        return new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig(name, enabled, Duration.ofSeconds(2),
                        "/nonexistent/definitely-not-a-real-binary-xyz", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, file);
    }

    private static ToolCallback fakeTool(String registeredName) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(registeredName).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    /** 写一个含单条目的 mcp.json，返回文件路径（供回写断言）。 */
    private static Path writeCfg(Path dir, String name, boolean enabled) throws Exception {
        Path f = dir.resolve("mcp.json");
        Files.writeString(f, "{\"mcpServers\":{\"" + name + "\":{\"command\":\"echo\",\"enabled\":" + enabled + "}}}");
        return f;
    }

    @Test
    void disabledEntryIsListedButNotConnected(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", false);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            List<McpRegistry.ServerView> views = reg.servers();
            assertEquals(1, views.size());
            assertEquals(McpRegistry.Status.DISABLED, views.get(0).status());
            assertTrue(reg.activeTools().isEmpty());
        } finally {
            reg.close();
        }
    }

    @Test
    void enableOnBrokenServerRecordsErrorAndStillPersistsIntent(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", false);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            McpRegistry.ToggleResult r = reg.enable("s1");
            assertFalse(r.applied(), "连接必失败");
            assertTrue(r.persisted(), "enabled:true 仍应回写（用户意图）");
            assertNotNull(r.error());
            assertEquals(McpRegistry.Status.FAILED, reg.servers().get(0).status());
            assertTrue(MAPPER.readTree(Files.readString(f))
                    .get("mcpServers").get("s1").get("enabled").asBoolean());
        } finally {
            reg.close();
        }
    }

    @Test
    void disableRemovesToolsAndPersists(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            reg.addConnectedForTest("s1", List.of(fakeTool("mcp__s1__ping")));
            assertEquals(1, reg.activeTools().size());
            assertEquals(McpRegistry.Status.CONNECTED, reg.servers().get(0).status());

            McpRegistry.ToggleResult r = reg.disable("s1");
            assertTrue(r.applied());
            assertTrue(r.persisted());
            assertTrue(reg.activeTools().isEmpty());
            assertEquals(McpRegistry.Status.DISABLED, reg.servers().get(0).status());
            assertFalse(MAPPER.readTree(Files.readString(f))
                    .get("mcpServers").get("s1").get("enabled").asBoolean());
        } finally {
            reg.close();
        }
    }

    @Test
    void unknownNameDegrades() {
        McpRegistry reg = McpRegistry.initForTest(Path.of("."), new ConversationState(), List.of());
        assertFalse(reg.enable("nope").applied());
        assertFalse(reg.disable("nope").applied());
    }

    @Test
    void viewCarriesToolCountAndShortNames(@TempDir Path root) throws Exception {
        Path f = writeCfg(root, "s1", true);
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(bogus("s1", false, f)));
        try {
            reg.addConnectedForTest("s1", List.of(fakeTool("mcp__s1__ping"), fakeTool("mcp__s1__pong")));
            McpRegistry.ServerView v = reg.servers().get(0);
            assertEquals(2, v.toolCount());
            assertEquals(List.of("ping", "pong"), v.toolNames(), "面板列 mcp__ 前缀后的短名");
        } finally {
            reg.close();
        }
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=McpRegistryTest`
Expected: 编译失败（类不存在）

- [x] **Step 3: 实现**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.MediaArtifactStore;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.TextReferenceMediaHandler;
import io.github.javaside.springai.codetui.agent.media.ToolResultMediaHandler;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springaicommunity.agent.AgentListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 运行期 MCP 中枢：持有<b>全量</b>条目（含 enabled:false 的），支持 /mcp 的启用/禁用即时生效 + 回写。
 * 取代裸 {@link McpClientManager} 流水线（其连接/发现/关闭静态件在此复用，不重写）。
 *
 * <p><b>装饰职责</b>：enable/初连时即用 ToolEventCallback + MediaExternalizingCallback 装饰，
 * {@link #activeTools()} 返回的始终是已装饰实例——与内置工具行为一致（TUI 工具活动行 + 媒体外置路径①）。
 *
 * <p><b>并发</b>：条目表用 synchronized(this) 保护；连接（秒级阻塞）在锁外做，enable/disable 由 UI 层
 * 保证同一时刻至多一个在飞（connecting 闸门）。activeTools() 每回合取一次快照，回合中途切换不影响在飞回合。
 *
 * <p><b>降级契约</b>：连接失败记入 error 态（enabled 意图仍回写）；回写失败内存态照常生效、
 * ToggleResult.persisted=false 供 UI 提示；一律不抛异常。
 */
public final class McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);

    public enum Status { CONNECTED, FAILED, DISABLED }

    /** /mcp 面板一行的数据。toolNames 为去掉 {@code mcp__<server>__} 前缀的短名。 */
    public record ServerView(String name, McpConfigLoader.ConfigSource source, Status status,
                             int toolCount, List<String> toolNames, String error) { }

    /** 切换结果：applied=内存态是否达成（enable=已连接；disable=恒 true），persisted=回写是否成功。 */
    public record ToggleResult(boolean applied, boolean persisted, String error) { }

    private static final class Entry {
        final McpConfigLoader.LoadedServer loaded;
        boolean enabled;
        McpSyncClient client;                       // null = 未连接
        List<ToolCallback> tools = List.of();       // 已装饰
        String error;                               // 最近一次连接失败摘要
        boolean connectedForTest;                   // 测试钩子：无真实 client 也视作已连接（生产恒 false）

        Entry(McpConfigLoader.LoadedServer loaded) {
            this.loaded = loaded;
            this.enabled = loaded.config().enabled();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Path root;
    private final AgentListener listener;
    private final MediaArtifactStore mediaStore;
    private final ToolResultMediaHandler mediaHandler;

    private McpRegistry(Path root, AgentListener listener) {
        this.root = root;
        this.listener = listener;
        this.mediaStore = new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        this.mediaHandler = new TextReferenceMediaHandler();
    }

    /** 生产入口：全量加载两层配置 + 并行连接 enabled 项（失败进 error 态，不抛）。 */
    public static McpRegistry init(Path root, AgentListener listener) {
        return init(root, listener, McpConfigLoader.loadAll(root));
    }

    static McpRegistry init(Path root, AgentListener listener, List<McpConfigLoader.LoadedServer> loaded) {
        McpRegistry reg = new McpRegistry(root, listener);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        reg.connectEnabledInParallel();
        return reg;
    }

    /** 测试入口：不做启动期连接（条目按 enabled=false 或经 addConnectedForTest 塞假工具驱动）。 */
    static McpRegistry initForTest(Path root, AgentListener listener,
                                   List<McpConfigLoader.LoadedServer> loaded) {
        McpRegistry reg = new McpRegistry(root, listener);
        for (McpConfigLoader.LoadedServer l : loaded) {
            reg.entries.put(l.config().name(), new Entry(l));
        }
        return reg;
    }

    /** 测试钩子：把条目直接置为「已启用、已连接、给定工具」（client 仍 null，close 时自然跳过）。 */
    synchronized void addConnectedForTest(String name, List<ToolCallback> decoratedTools) {
        Entry e = entries.get(name);
        e.enabled = true;
        e.error = null;
        e.tools = List.copyOf(decoratedTools);
        e.connectedForTest = true;
    }

    /** 启动期并行连接（沿 McpClientManager.connectAll 的池模式；此时无并发访问，不加锁）。 */
    private void connectEnabledInParallel() {
        List<Entry> toConnect = entries.values().stream().filter(e -> e.enabled).toList();
        if (toConnect.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(toConnect.size(), 8), r -> {
            Thread t = new Thread(r, "mcp-connect");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Entry e : toConnect) {
                futures.add(CompletableFuture.runAsync(() -> connectAndDiscover(e), pool));
            }
            futures.forEach(CompletableFuture::join);   // connectDetailed 内已 guard，不抛业务异常
        } finally {
            pool.shutdown();
        }
    }

    /** 连接 + 发现 + 装饰单条目，结果写回 entry（成功清 error，失败记 error）。 */
    private void connectAndDiscover(Entry e) {
        McpClientManager.ConnectOutcome out = McpClientManager.connectDetailed(e.loaded.config());
        if (out.client() == null) {
            e.client = null;
            e.tools = List.of();
            e.error = out.error();
            return;
        }
        e.client = out.client();
        e.error = null;
        List<ToolCallback> decorated = new ArrayList<>();
        for (ToolCallback raw : McpClientManager.discoverTools(out.client())) {
            decorated.add(decorate(raw));
        }
        e.tools = List.copyOf(decorated);
    }

    private ToolCallback decorate(ToolCallback raw) {
        return new ToolEventCallback(
                new MediaExternalizingCallback(raw, mediaStore, mediaHandler, root), listener);
    }

    /** /mcp 面板数据源（快照）。 */
    public synchronized List<ServerView> servers() {
        List<ServerView> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            Status status = !e.enabled ? Status.DISABLED
                    : (e.client != null || e.connectedForTest) ? Status.CONNECTED : Status.FAILED;
            List<String> shortNames = e.tools.stream()
                    .map(t -> shortName(t.getToolDefinition().name())).toList();
            out.add(new ServerView(e.loaded.config().name(), e.loaded.source(), status,
                    e.tools.size(), shortNames, e.error));
        }
        return out;
    }

    /** 当前「已启用且已连接」server 的已装饰工具（每回合快照）。 */
    public synchronized List<ToolCallback> activeTools() {
        List<ToolCallback> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.enabled) {
                out.addAll(e.tools);
            }
        }
        return out;
    }

    /**
     * 启用：连接（阻塞，秒级——调用方放后台线程）+ 发现 + 装饰 + 回写 enabled:true。
     * 连接失败也回写（用户意图是启用，下次启动自动重试）；已连接则幂等返回成功。
     */
    public ToggleResult enable(String name) {
        Entry e;
        synchronized (this) {
            e = entries.get(name);
            if (e == null) {
                return new ToggleResult(false, false, "未知 server：" + name);
            }
            if (e.enabled && (e.client != null || e.connectedForTest)) {
                return new ToggleResult(true, true, null);
            }
        }
        connectAndDiscoverLocked(e);
        boolean persisted = McpConfigWriter.setEnabled(e.loaded.file(), name, true);
        synchronized (this) {
            return new ToggleResult(e.client != null, persisted, e.error);
        }
    }

    /** 连接在锁外做（阻塞秒级），仅结果写回时短暂持锁。 */
    private void connectAndDiscoverLocked(Entry e) {
        McpClientManager.ConnectOutcome out = McpClientManager.connectDetailed(e.loaded.config());
        List<ToolCallback> decorated = List.of();
        if (out.client() != null) {
            List<ToolCallback> tmp = new ArrayList<>();
            for (ToolCallback raw : McpClientManager.discoverTools(out.client())) {
                tmp.add(decorate(raw));
            }
            decorated = List.copyOf(tmp);
        }
        synchronized (this) {
            e.enabled = true;
            e.client = out.client();
            e.tools = decorated;
            e.error = out.error();
        }
    }

    /** 禁用：摘除工具（下回合快照即不含）+ 后台优雅关连接 + 回写 enabled:false。即时完成。 */
    public ToggleResult disable(String name) {
        McpSyncClient toClose;
        Entry e;
        synchronized (this) {
            e = entries.get(name);
            if (e == null) {
                return new ToggleResult(false, false, "未知 server：" + name);
            }
            toClose = e.client;
            e.client = null;
            e.tools = List.of();
            e.enabled = false;
            e.error = null;
            e.connectedForTest = false;
        }
        if (toClose != null) {
            Thread t = new Thread(() -> {
                try {
                    toClose.closeGracefully();
                } catch (Exception ex) {
                    log.warn("MCP client 关闭异常（忽略）：{}", ex.getMessage());
                }
            }, "mcp-disable-close");
            t.setDaemon(true);
            t.start();
        }
        boolean persisted = McpConfigWriter.setEnabled(e.loaded.file(), name, false);
        return new ToggleResult(true, persisted, null);
    }

    /** 退出清理：关所有在连 client（复用 2s 预算逻辑）。 */
    public void close() {
        List<McpSyncClient> toClose;
        synchronized (this) {
            toClose = entries.values().stream()
                    .map(e -> e.client).filter(java.util.Objects::nonNull).toList();
        }
        McpClientManager.closeAll(toClose);
    }

    /** {@code mcp__<server>__<tool>} → {@code <tool>}（面板展示短名）；无前缀则原样。 */
    static String shortName(String registered) {
        int i = registered.lastIndexOf("__");
        return i >= 0 ? registered.substring(i + 2) : registered;
    }
}
```

注意：`Entry` 里补一个字段 `boolean connectedForTest;`（测试钩子把无真实 client 的条目视作已连接；生产路径恒 false）。上面代码已引用，别漏声明。

`AgentListener` 的实际包名以 `AgentTools.java` 顶部 import 为准（`ConversationState` implements 它）；如与上文不符按源码改。

- [x] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=McpRegistryTest`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpRegistryTest.java
git commit -m "feat(mcp): McpRegistry 运行期中枢——enable/disable 即时生效 + 回写"
```

---

### Task 5: 动态注入（AgentTools / SubagentRunner / CodingAgent / CodeTuiApplication）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubagentRunner.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/SubagentRunnerMcpToolsTest.java`（新建）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsMcpWiringTest.java`（改）

- [x] **Step 1: 写失败测试**

新建 `SubagentRunnerMcpToolsTest`：

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunnerMcpToolsTest {

    private static ToolCallback fakeTool(String name) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("fake")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "ok"; }
        };
    }

    private static McpRegistry registryWithTool(Path root, String toolName) {
        McpConfigLoader.LoadedServer l = new McpConfigLoader.LoadedServer(
                new McpServerConfig.StdioServerConfig("s1", false, Duration.ofSeconds(2),
                        "echo", List.of(), Map.of()),
                McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
        McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(l));
        reg.addConnectedForTest("s1", List.of(fakeTool(toolName)));
        return reg;
    }

    @Test
    void mcpToolsFlowIntoSubagentAndRespectDeny(@TempDir Path root) {
        McpRegistry reg = registryWithTool(root, "mcp__s1__ping");
        SubagentRunner runner = new SubagentRunner(McpWiringTestSupport.dummyRegistry(),
                List.of(fakeTool("Grep")), new ConversationState(), "", 4, reg);

        SubagentSpec allowAll = SubagentSpec.builder("t").systemPrompt("x").build();
        List<String> names = runner.effectiveTools(allowAll).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertTrue(names.contains("mcp__s1__ping"), "MCP 工具应进入子 agent 工具集");
        assertTrue(names.contains("Grep"));

        SubagentSpec denyMcp = SubagentSpec.builder("t").systemPrompt("x")
                .denyTools(List.of("mcp__s1__ping")).build();
        List<String> filtered = runner.effectiveTools(denyMcp).stream()
                .map(t -> t.getToolDefinition().name()).toList();
        assertFalse(filtered.contains("mcp__s1__ping"), "deny 按注册名过滤 MCP 工具");
    }

    @Test
    void disableRemovesFromSubagentToolset(@TempDir Path root) {
        McpRegistry reg = registryWithTool(root, "mcp__s1__ping");
        SubagentRunner runner = new SubagentRunner(McpWiringTestSupport.dummyRegistry(),
                List.of(), new ConversationState(), "", 4, reg);
        reg.disable("s1");
        SubagentSpec spec = SubagentSpec.builder("t").systemPrompt("x").build();
        assertTrue(runner.effectiveTools(spec).isEmpty());
    }
}
```

注：`SubagentSpec` 的构造方式（builder/record）以源码为准，测试里按实际 API 调整；`ConversationState` 已实现 AgentListener。

改 `AgentToolsMcpWiringTest`：把 `List.of(fakeMcpTool())` 换为 registry 版本：

```java
@Test
void buildAcceptsMcpRegistryWithoutThrowing(@TempDir Path root) {
    McpConfigLoader.LoadedServer l = new McpConfigLoader.LoadedServer(
            new McpServerConfig.StdioServerConfig("fake", false, java.time.Duration.ofSeconds(2),
                    "echo", List.of(), java.util.Map.of()),
            McpConfigLoader.ConfigSource.PROJECT, root.resolve("mcp.json"));
    McpRegistry reg = McpRegistry.initForTest(root, new ConversationState(), List.of(l));
    reg.addConnectedForTest("fake", List.of(fakeMcpTool()));
    assertDoesNotThrow(() -> AgentTools.build(
            McpWiringTestSupport.dummyRegistry(), root, new ConversationState(), reg));
}
```

（`threeArgOverloadStillBuildsForBackwardCompat` 保留不动。）

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest='SubagentRunnerMcpToolsTest,AgentToolsMcpWiringTest'`
Expected: 编译失败（新构造器/`effectiveTools`/build 新签名不存在）

- [x] **Step 3: 实现——SubagentRunner**

加字段 + 新构造器（现有构造器全部委托 `mcpRegistry=null`，零破坏）：

```java
private final McpRegistry mcpRegistry;   // 可空：MCP 工具的每次委派实时来源（enable/disable 即时反映）

public SubagentRunner(ProviderRegistry registry, List<ToolCallback> tools, AgentListener listener,
                      String projectInstructions, int maxConcurrency, McpRegistry mcpRegistry) {
    this(registry, tools, listener, projectInstructions, maxConcurrency,
            () -> "task_" + UUID.randomUUID(), mcpRegistry);
}
```

全参包私构造器追加 `McpRegistry mcpRegistry` 尾参并赋值；原有全参构造器委托传 null。新增：

```java
/** 子 agent 有效工具 = 内置装饰工具 + MCP 实时工具，再按 spec allow/deny 过滤（注册名精确匹配）。 */
List<ToolCallback> effectiveTools(SubagentSpec spec) {
    List<ToolCallback> all = new ArrayList<>(tools);
    if (mcpRegistry != null) {
        all.addAll(mcpRegistry.activeTools());
    }
    return filterTools(all, spec);
}
```

`run()` 里 `.defaultTools(filterTools(tools, spec).toArray())` 改为 `.defaultTools(effectiveTools(spec).toArray())`。

- [x] **Step 4: 实现——AgentTools**

`build` 第 4 参 `List<ToolCallback> mcpTools` 改为 `McpRegistry mcpRegistry`（可空）：

- 删除 `all.addAll(mcpTools);`（内置工具装饰循环不变——MCP 工具已由 registry 自行装饰）。
- SubagentRunner 构造改为：`new SubagentRunner(registry, decoratedList, listener, projectInstructions, subagentConcurrency, mcpRegistry);`
- 3 参向后兼容重载改为委托 `build(registry, root, listener, (McpRegistry) null)`。
- 相应更新方法 javadoc：MCP 工具不再烧入 defaultTools，改由 CodingAgent/SubagentRunner 每回合从 registry 取快照。

- [x] **Step 5: 实现——CodingAgent**

- 加字段 `private final McpRegistry mcpRegistry;`
- 全参生产构造器追加尾参 `McpRegistry mcpRegistry`；现有全参构造器改为委托传 null；单-client 桩构造器链全部 `this.mcpRegistry = null;`（在链尾真构造器赋值）。
- `submit` 的 prompt 链，在 `.user(effectiveText)` 之后插一行：

```java
.tools(mcpRegistry == null ? new Object[0] : mcpRegistry.activeTools().toArray())
```

（Spring AI 2.0 `ChatClientRequestSpec.tools(Object...)` 与 defaultTools 合并，已核实 DefaultChatClient 源码第 1028 行起；`mcp__` 前缀保证不与内置工具重名。空数组为 no-op。）

- [x] **Step 6: 实现——CodeTuiApplication**

替换启动期 MCP 三行流水线（62-67 行附近）：

```java
// MCP：启动期全量加载 .codetui/mcp.json（两层，含禁用项）→ 并行连接 enabled 项 → 发现+装饰工具。
// 运行期 /mcp 可启停（详见 McpRegistry）。连接失败进 error 态、静默降级。
McpRegistry mcpRegistry = McpRegistry.init(root, state);
int mcpToolCount = mcpRegistry.activeTools().size();
if (mcpToolCount > 0) {
    state.pushInfo("（MCP：已发现 " + mcpToolCount + " 个工具。）");
}
```

- `AgentTools.build(registry, root, state, mcpTools)` → `AgentTools.build(registry, root, state, mcpRegistry)`
- `new CodingAgent(...)` 用新的带 `mcpRegistry` 尾参的全参构造器。
- `finally { mcpManager.close(); }` → `finally { mcpRegistry.close(); }`
- 删掉 `McpClientManager`/旧局部变量的 import 与引用。

- [x] **Step 7: 跑测试确认通过 + 全模块回归**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿（含既有 MCP wiring / CodingAgent / SubagentRunner 用例）

- [x] **Step 8: Commit**

```bash
git add springai-code-tui/src/main/java springai-code-tui/src/test/java
git commit -m "feat(mcp): MCP 工具改每回合从 McpRegistry 动态注入（主 agent .tools + 子 agent effectiveTools）"
```

---

### Task 6: SubmitHandler 门面 + CodingAgent 实现

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Test: 由 Task 7 的视图测试覆盖（门面是纯委托，无独立逻辑）

- [x] **Step 1: SubmitHandler 追加默认方法**

```java
// ── MCP 管理（/mcp 面板用；默认空实现，便于回显桩/测试桩省略） ──
/** 已安装 MCP server 视图（含禁用项）。 */
default List<McpRegistry.ServerView> mcpServers() { return List.of(); }

/** 启用（含连接，阻塞秒级——调用方放后台线程）。null 表示无 MCP 支持。 */
default McpRegistry.ToggleResult enableMcp(String name) { return null; }

/** 禁用（即时完成）。null 表示无 MCP 支持。 */
default McpRegistry.ToggleResult disableMcp(String name) { return null; }
```

- [x] **Step 2: CodingAgent 实现（纯委托）**

```java
@Override
public List<McpRegistry.ServerView> mcpServers() {
    return mcpRegistry == null ? List.of() : mcpRegistry.servers();
}

@Override
public McpRegistry.ToggleResult enableMcp(String name) {
    return mcpRegistry == null ? null : mcpRegistry.enable(name);
}

@Override
public McpRegistry.ToggleResult disableMcp(String name) {
    return mcpRegistry == null ? null : mcpRegistry.disable(name);
}
```

- [x] **Step 3: 编译验证 + Commit**

Run: `mvn test -pl springai-code-tui -Dtest=McpRegistryTest`（顺带编译全模块）
Expected: 编译通过、全绿

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java
git commit -m "feat(mcp): SubmitHandler MCP 管理门面 + CodingAgent 委托实现"
```

---

### Task 7: CodeTuiView /mcp 面板

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewMcpTest.java`（新建）

- [x] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.McpRegistry;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CodeTuiViewMcpTest {

    private static McpRegistry.ServerView view(String name, McpRegistry.Status st) {
        return new McpRegistry.ServerView(name, McpConfigLoader.ConfigSource.PROJECT, st,
                st == McpRegistry.Status.CONNECTED ? 2 : 0,
                st == McpRegistry.Status.CONNECTED ? List.of("ping", "pong") : List.of(),
                st == McpRegistry.Status.FAILED ? "timeout" : null);
    }

    /** 可编程桩：mcpServers 固定返回、enable/disable 记录调用。 */
    private static final class McpStub implements SubmitHandler {
        volatile List<McpRegistry.ServerView> servers = List.of();
        final AtomicInteger enables = new AtomicInteger();
        final AtomicInteger disables = new AtomicInteger();
        final CountDownLatch enableCalled = new CountDownLatch(1);
        @Override public Disposable submit(String text) { return null; }
        @Override public List<McpRegistry.ServerView> mcpServers() { return servers; }
        @Override public McpRegistry.ToggleResult enableMcp(String name) {
            enables.incrementAndGet(); enableCalled.countDown();
            return new McpRegistry.ToggleResult(true, true, null);
        }
        @Override public McpRegistry.ToggleResult disableMcp(String name) {
            disables.incrementAndGet();
            return new McpRegistry.ToggleResult(true, true, null);
        }
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
    }

    private static void submitMcp(CodeTuiView v) {
        type(v, "/mcp");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        // "/mcp" 是斜杠补全菜单前缀，首个 Enter 可能被菜单吃掉选中命令文本；再回车提交
        if (!v.pickingMcpForTest()) v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    void mcpWhenBusyIsRejected() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertFalse(v.pickingMcpForTest());
        assertEquals("忙碌中，无法管理 MCP", s.notice());
    }

    @Test
    void mcpWithNoServersShowsNotice() {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, new McpStub(), Path.of("."));
        submitMcp(v);
        assertFalse(v.pickingMcpForTest());
        assertEquals("未配置 MCP server（.codetui/mcp.json）", s.notice());
    }

    @Test
    void enterOnConnectedRowDisablesSynchronously() {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertTrue(v.pickingMcpForTest());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(1, h.disables.get());
        assertEquals(0, h.enables.get());
    }

    @Test
    void enterOnDisabledRowEnablesOnBackgroundThread() throws Exception {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.DISABLED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        assertTrue(v.pickingMcpForTest());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(h.enableCalled.await(3, TimeUnit.SECONDS), "enable 应在后台线程被调用");
    }

    @Test
    void escClosesPanel() {
        ConversationState s = new ConversationState();
        McpStub h = new McpStub();
        h.servers = List.of(view("s1", McpRegistry.Status.CONNECTED));
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        submitMcp(v);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESC));
        assertFalse(v.pickingMcpForTest());
    }
}
```

注：Esc 的 KeyEvent 构造以现有测试（如 CodeTuiViewAskTest）里的取消键写法为准，若是 `KeyEvent.ofKey(KeyCode.ESC)` 之外的形式照抄现有。

- [x] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=CodeTuiViewMcpTest`
Expected: 编译失败（`pickingMcpForTest` 等不存在）

- [x] **Step 3: 实现**

(a) 字段 + 命令注册：

```java
private boolean pickingMcp;                  // /mcp 面板激活
private boolean mcpExpanded;                 // Tab 展开选中项工具清单
private volatile String mcpConnecting;       // 非 null = 正在后台连接的 server 名（渲染线程读）
```

`SLASH_COMMANDS` 在 `/reload` 后加：`new SlashCommand("/mcp", "管理 MCP 服务器（启用/禁用）")`（printHelp 自动带上）。

(b) `submitInput()` 加分支（放 `/reload` 分支后）：

```java
if (cmd.equals("/mcp")) {                    // MCP 管理面板：仅空闲可开（回合中摘工具/关连接会撞在飞调用）
    inputState.clear();
    if (busy()) { state.setNotice("忙碌中，无法管理 MCP"); return; }
    openMcpPicker();
    return;
}
```

(c) 按键路由（`onInputKey` 里 `if (pickingSkill) ...` 之后）：

```java
if (pickingMcp) return onMcpPickerKey(k);    // MCP 面板激活：按键全交给它
```

(d) 渲染挂载（`scope(pickingSkill, ...)` 之后）：

```java
scope(pickingMcp, mcpPickerChildren()),      // /mcp 管理面板
```

(e) 面板逻辑（放技能选择器代码块之后；高亮**纯前景 PICK_SEL，严禁背景色条**）：

```java
// ── /mcp MCP 管理面板 ───────────────────────────────────────────────
/** 打开 MCP 面板；无 server 声明则提示不弹。 */
private void openMcpPicker() {
    List<McpRegistry.ServerView> list = onSubmit.mcpServers();
    if (list.isEmpty()) { state.setNotice("未配置 MCP server（.codetui/mcp.json）"); return; }
    pickIndex = 0;
    mcpExpanded = false;
    pickingMcp = true;
}

/** MCP 面板按键：↑↓/kj 移动、数字快选、Enter/Space 切换、Tab 展开工具清单、Esc 关闭。始终 HANDLED。 */
private EventResult onMcpPickerKey(KeyEvent k) {
    List<McpRegistry.ServerView> list = onSubmit.mcpServers();
    int n = list.size();
    if (n == 0) { pickingMcp = false; return EventResult.HANDLED; }
    pickIndex = clampIndex(pickIndex, n);
    if (k.isCancel()) { pickingMcp = false; return EventResult.HANDLED; }
    if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; mcpExpanded = false; return EventResult.HANDLED; }
    if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     mcpExpanded = false; return EventResult.HANDLED; }
    for (int i = 0; i < n && i < 9; i++) {
        if (k.isChar((char) ('1' + i))) { pickIndex = i; mcpExpanded = false; return EventResult.HANDLED; }
    }
    if (k.code() == KeyCode.TAB || k.isChar('\t')) { mcpExpanded = !mcpExpanded; return EventResult.HANDLED; }
    if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n') || k.isChar(' ')) {
        toggleMcp(list.get(pickIndex));
        return EventResult.HANDLED;
    }
    return EventResult.HANDLED;
}

/** 切换一项：CONNECTED→同步禁用；DISABLED/FAILED→后台线程启用（连接秒级，不冻结渲染循环）。 */
private void toggleMcp(McpRegistry.ServerView v) {
    if (mcpConnecting != null) return;                       // 已有连接在飞：忽略（一次一个）
    if (v.status() == McpRegistry.Status.CONNECTED) {
        var r = onSubmit.disableMcp(v.name());
        if (r != null && !r.persisted()) state.setNotice("已禁用（仅本次运行，写回配置失败）");
        return;
    }
    mcpConnecting = v.name();
    Thread t = new Thread(() -> {
        try {
            var r = onSubmit.enableMcp(v.name());
            if (r == null) return;
            if (!r.applied()) state.setNotice("MCP " + v.name() + " 连接失败：" + brief(r.error()));
            else if (!r.persisted()) state.setNotice("已启用（仅本次运行，写回配置失败）");
        } finally {
            mcpConnecting = null;                            // 渲染线程下一帧即看到
        }
    }, "mcp-enable");
    t.setDaemon(true);
    t.start();
}

/** 错误摘要截断（面板/notice 单行显示）。 */
private static String brief(String s) {
    if (s == null) return "未知错误";
    return s.length() > 60 ? s.substring(0, 60) + "…" : s;
}

/** MCP 面板：标题 + 每 server 一行（状态标记/来源层/工具数/错误摘要），Tab 展开工具短名清单。 */
private Element[] mcpPickerChildren() {
    List<McpRegistry.ServerView> list = onSubmit.mcpServers();
    if (list.isEmpty()) return new Element[0];               // scope 每帧 eager 求值：首行判空
    int sel = clampIndex(pickIndex, list.size());
    List<Element> els = new ArrayList<>();
    els.add(text("  MCP 服务器（↑↓ 选择 · Enter 启用/禁用 · Tab 查看工具 · Esc 关闭）").style(PICK_TITLE));
    for (int i = 0; i < list.size(); i++) {
        McpRegistry.ServerView v = list.get(i);
        boolean isSel = i == sel;
        boolean connecting = v.name().equals(mcpConnecting);
        String mark = connecting ? "⟳" : switch (v.status()) {
            case CONNECTED -> "✓";
            case DISABLED -> "○";
            case FAILED -> "✗";
        };
        String layer = v.source() == McpConfigLoader.ConfigSource.PROJECT ? "[项目级]" : "[用户级]";
        String detail = connecting ? "连接中…" : switch (v.status()) {
            case CONNECTED -> "已连接 · " + v.toolCount() + " 工具";
            case DISABLED -> "已禁用";
            case FAILED -> "连接失败：" + brief(v.error());
        };
        els.add(text("  " + (isSel ? "❯ " : "  ") + mark + " " + (i + 1) + ". " + v.name()
                + "  " + layer + " " + detail)
                .style(isSel ? PICK_SEL : PICK_ITEM));
        if (isSel && mcpExpanded) {
            if (v.toolNames().isEmpty()) {
                els.add(text("        （未连接，无工具信息）").style(PICK_DESC));
            } else {
                for (String tn : v.toolNames()) {
                    els.add(text("        · " + tn).style(PICK_DESC));
                }
            }
        }
    }
    return els.toArray(new Element[0]);
}

// 测试钩子
boolean pickingMcpForTest() { return pickingMcp; }
```

（`clampIndex` 已存在——斜杠菜单在用；`pickIndex` 复用现有字段，与 /model、/skill 一致，三个面板互斥激活。）

- [x] **Step 4: 跑测试确认通过 + 全模块回归**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewMcpTest.java
git commit -m "feat(mcp): /mcp 管理面板——状态/来源层/工具数展示 + Enter 切换 + Tab 展开"
```

---

### Task 8: pty 实机冒烟 + package 验证

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/mcp_manage_smoke.py`

- [x] **Step 1: 重新构建（项目惯例：改渲染必须重新 package/compile 再实机验证）**

```bash
mvn -q -pl springai-code-tui compile test-compile
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

- [x] **Step 2: 写冒烟脚本**

复用 `clear_smoke.py` 的 `PtySession`/classpath 脚手架与 `mcp_smoke.py` 的真实 server 配置方式（`npx @modelcontextprotocol/server-filesystem`，dummy API key）。脚本流程与断言：

```python
#!/usr/bin/env python3
"""PTY smoke test for /mcp runtime management.

Boots the real app with one real stdio MCP server, then:
1. Opens /mcp -> asserts the panel lists the server as connected (✓ + 工具数).
2. Presses Enter to disable -> asserts the row flips to ○ 已禁用 AND the
   project mcp.json now has "enabled": false (persisted write-back).
3. Presses Enter again to re-enable -> asserts the row returns to ✓ and
   mcp.json flips back to "enabled": true.
4. Esc closes the panel; /exit terminates promptly; no orphaned child.

pyte renders the real screen; unit tests cannot reach the real panel
rendering nor the real write-back path end-to-end.
"""
```

关键实现点（完整代码按 `mcp_smoke.py` 现有骨架展开）：

- 临时目录写 `.codetui/mcp.json`：`{"mcpServers":{"fs":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","<tmpdir>"]}}}`
- 等欢迎横幅 + 「已发现 N 个工具」出现后：`send("/mcp")`，`send("\r")`（补全菜单选中）→ 必要时再 `send("\r")` 提交
- `wait_for_screen_contains("✓")` 且屏上有 `已连接`、`[项目级]`
- `send("\r")` 禁用 → `wait_for_screen_contains("○")` 且 `已禁用`；读 mcp.json 断言 `json.load(...)["mcpServers"]["fs"]["enabled"] is False`
- `send("\r")` 重启用（真实重连 npx，等待放宽到 30s）→ 等 `✓` 回来；断言 mcp.json `enabled` 翻回 `True`
- `send("\x1b")` 关面板 → `send("/exit\r")` → 断言 10s 内退出、`server-filesystem` 无孤儿进程（复用 `mcp_smoke.py` 的 `count_orphans`）

- [x] **Step 3: 跑既有 + 新增冒烟**

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/mcp_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/mcp_manage_smoke.py
```
Expected: 两个都 `SMOKE PASS`（需要 npx 与网络/npm 缓存；失败时脚本打印屏幕快照供人眼排查）

- [x] **Step 4: 全模块最终回归**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿

- [x] **Step 5: Commit**

```bash
git add springai-code-tui/src/test/resources/scripts/mcp_manage_smoke.py
git commit -m "test(mcp): /mcp 面板 pty 实机冒烟——切换生效 + mcp.json 回写 + 无孤儿进程"
```
