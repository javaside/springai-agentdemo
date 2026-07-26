# MCP Streamable HTTP 传输接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 code-tui 能连远程 MCP server：`mcp.json` 写 `"type": "http"` + `url` + 可选 `headers`（支持 `${ENV_VAR}` 插值）即可。

**Architecture:** 扩展点当初就留好了——`McpServerConfig` 加一个 sealed 变体、`McpConfigLoader.parseEntry` 加一个分支、`McpTransportFactory.create` 加一个分支，三处而已；`McpClientManager` / `McpRegistry` / `/mcp` 面板对传输类型无感，零改动。传输用 `mcp-core 2.0.0` 自带的 `HttpClientStreamableHttpTransport`（JDK HttpClient 底座，无新依赖）。

**Tech Stack:** Java 17、`io.modelcontextprotocol.sdk:mcp-core:2.0.0`、Jackson 3（`tools.jackson.databind`）、JUnit 5

**Spec:** `docs/superpowers/specs/2026-07-27-mcp-streamable-http-design.md`

---

## File Structure

| 文件 | 职责 |
|---|---|
| **创建** `.../agent/EnvInterpolator.java` | `${VAR}` 插值，解析器可注入。单一职责、可独立测 |
| **修改** `.../agent/McpServerConfig.java` | sealed 加 `HttpServerConfig` 变体 |
| **修改** `.../agent/McpConfigLoader.java` | `parseEntry` 改 switch 分型；新增 `parseHttp` + `isAbsoluteHttpUrl`；headers 插值 |
| **修改** `.../agent/McpTransportFactory.java` | http 分支；新增 `baseUriOf` / `endpointOf` / `headerCustomizer`（都做成包级可测） |
| **创建** `.../agent/EnvInterpolatorTest.java` | 插值单测 |
| **修改** `.../agent/McpConfigLoaderTest.java` | http 解析、非法 URL、sse、插值行为 |
| **修改** `.../agent/McpTransportFactoryTest.java` | URL 拆分四态、header customizer 落头断言、http 构造 |
| **创建** `.../agent/McpStreamableHttpSmokeTest.java` | 端到端冒烟，`CODETUI_MCP_SMOKE_URL` 门控 |
| **修改** `README.md` | MCP 传输说明、http 配置示例、插值说明、远程 MCP 的安全披露 |

主源码根：`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/`
测试根：`springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/`

**验证命令一律模块作用域**：`mvn -pl springai-code-tui test`。整仓 `mvn test` 会被几个空模块打挂。
`CodingAgentSpikeTest.todoTurnIdBinding` 偶发 60s 超时（真实 DeepSeek 网络调用），撞上单跑那条确认无关。

---

### Task 1: EnvInterpolator（`${VAR}` 插值）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/EnvInterpolator.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/EnvInterpolatorTest.java`

- [ ] **Step 1: 写失败的测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ${VAR} 插值：解析器可注入，故测试不依赖真实环境变量。 */
class EnvInterpolatorTest {

    private static final Map<String, String> ENV = Map.of("TOKEN", "abc123", "USER", "zxh");

    @Test
    void replacesSingleVariable() {
        assertEquals("Bearer abc123",
                EnvInterpolator.interpolate("Bearer ${TOKEN}", ENV::get));
    }

    @Test
    void replacesMultipleVariablesInOneValue() {
        assertEquals("zxh:abc123",
                EnvInterpolator.interpolate("${USER}:${TOKEN}", ENV::get));
    }

    @Test
    void passesThroughWhenNoPlaceholder() {
        assertEquals("Bearer literal-token",
                EnvInterpolator.interpolate("Bearer literal-token", ENV::get),
                "不含 ${} 的字面值必须原样可用");
    }

    @Test
    void nullInputStaysNull() {
        assertNull(EnvInterpolator.interpolate(null, ENV::get));
    }

    @Test
    void undefinedVariableThrowsWithItsName() {
        EnvInterpolator.UndefinedVariableException ex = assertThrows(
                EnvInterpolator.UndefinedVariableException.class,
                () -> EnvInterpolator.interpolate("Bearer ${NOPE}", ENV::get));

        assertEquals("NOPE", ex.variable(), "异常要带变量名，调用方才能拼出可定位的 WARN");
        assertEquals(true, ex.getMessage().contains("NOPE"), "消息里也应含变量名，实际=" + ex.getMessage());
    }

    /** 替换值里若含 $ 或 \ ，正则替换会把它们当转义符——必须已被 quoteReplacement 处理。 */
    @Test
    void replacementValueWithDollarOrBackslashIsLiteral() {
        Map<String, String> tricky = Map.of("T", "a$b\\c");

        assertEquals("x=a$b\\c", EnvInterpolator.interpolate("x=${T}", tricky::get));
    }

    /** ${} 形状不合法（无变量名）时不匹配，原样保留，不抛。 */
    @Test
    void malformedPlaceholderIsLeftAlone() {
        assertEquals("${}", EnvInterpolator.interpolate("${}", ENV::get));
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='EnvInterpolatorTest'`
Expected: 编译失败，`找不到符号: 类 EnvInterpolator`

- [ ] **Step 3: 写实现**

创建 `EnvInterpolator.java`：

```java
package io.github.javaside.springai.codetui.agent;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${VAR}} 插值：把字符串里的占位符替换成解析器给出的值。
 *
 * <p>用于 {@code mcp.json} 的 headers 值——token 留在环境变量 / {@code ~/.secrets}，配置文件只写引用。
 * 便于多机共用同一份配置，也避免 token 明文散落在配置文件里。
 *
 * <p><b>解析器可注入</b>（生产传 {@code System::getenv}、测试传假 map），故本类不直接读环境变量。
 */
public final class EnvInterpolator {

    /**
     * 引用了未定义变量。<b>抛而不是静默留下字面量</b>：带着 {@code ${TOKEN}} 去请求只会拿到一个
     * 看不懂的 401，排查成本远高于在解析期就报出变量名。
     */
    public static final class UndefinedVariableException extends RuntimeException {

        private final String variable;

        public UndefinedVariableException(String variable) {
            super("引用了未定义的环境变量 " + variable);
            this.variable = variable;
        }

        public String variable() {
            return variable;
        }
    }

    /** 变量名沿用 shell 惯例：字母或下划线开头，后接字母数字下划线。 */
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvInterpolator() {
    }

    public static String interpolate(String raw, Function<String, String> resolver) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        Matcher matcher = VAR.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = resolver.apply(name);
            if (value == null) {
                throw new UndefinedVariableException(name);
            }
            // quoteReplacement：替换值里的 $ 与 \ 必须按字面处理，否则会被当成组引用/转义符
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='EnvInterpolatorTest'`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/EnvInterpolator.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/EnvInterpolatorTest.java
git commit -m "feat: \${ENV_VAR} 插值工具（解析器可注入）"
```

---

### Task 2: HttpServerConfig 变体 + loader 分型

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpServerConfig.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java`

- [ ] **Step 1: 写失败的测试**

在 `McpConfigLoaderTest` 追加（文件里已有 `write(dir, name, content)` 辅助方法与 `@TempDir` 用法，照用）：

```java
    @Test
    void parsesHttpServer(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "ctx7": { "type": "http", "url": "https://mcp.context7.com/mcp", "timeoutMs": 30000 }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size());
        McpServerConfig.HttpServerConfig http = (McpServerConfig.HttpServerConfig) configs.get(0);
        assertEquals("ctx7", http.name());
        assertTrue(http.enabled());
        assertEquals("https://mcp.context7.com/mcp", http.url());
        assertEquals(Map.of(), http.headers());
        assertEquals(30000, http.timeoutMs().toMillis());
    }

    /** "streamable-http" 是规范全称，"http" 是 Claude Code 生态的写法，两种都要认。 */
    @Test
    void acceptsStreamableHttpTypeSpelling(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "ctx7": { "type": "streamable-http", "url": "https://h/mcp" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size());
        assertTrue(configs.get(0) instanceof McpServerConfig.HttpServerConfig);
    }

    @Test
    void httpWithoutUrlIsSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "bad": { "type": "http" },
                    "good": { "command": "npx" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size(), "缺 url 的条目应被跳过，其余条目不受影响");
        assertEquals("good", configs.get(0).name());
    }

    /** URI.create("foo") 并不抛异常——它返回 scheme/authority 均为 null 的相对 URI，必须显式校验。 */
    @Test
    void malformedUrlsAreSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "relative": { "type": "http", "url": "foo" },
                    "wrongScheme": { "type": "http", "url": "ftp://h/x" },
                    "noAuthority": { "type": "http", "url": "http://" },
                    "good": { "type": "http", "url": "https://h/mcp" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size(), "三条非法 URL 都应被跳过，实际=" + configs);
        assertEquals("good", configs.get(0).name());
    }

    @Test
    void sseTypeIsSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "legacy": { "type": "sse", "url": "https://h/sse" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertTrue(configs.isEmpty(), "sse 暂未支持，应跳过");
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='McpConfigLoaderTest'`
Expected: 编译失败，`找不到符号: 类 McpServerConfig.HttpServerConfig`

- [ ] **Step 3: 加 sealed 变体**

在 `McpServerConfig.java` 里，把接口声明：

```java
public sealed interface McpServerConfig permits McpServerConfig.StdioServerConfig {
```

改为：

```java
public sealed interface McpServerConfig
        permits McpServerConfig.StdioServerConfig, McpServerConfig.HttpServerConfig {
```

并在 `StdioServerConfig` 记录之后追加：

```java
    /**
     * Streamable HTTP 传输配置：连接远程 MCP server。
     *
     * @param url     完整端点 URL（如 {@code https://mcp.context7.com/mcp}）；
     *                由 {@link McpTransportFactory} 拆成 baseUri + endpoint 两段喂给 SDK
     * @param headers 请求头，值已在 loader 完成 {@code ${ENV_VAR}} 插值；可空 → 空 map
     */
    record HttpServerConfig(String name, boolean enabled, Duration timeoutMs,
                            String url, Map<String, String> headers)
            implements McpServerConfig {
    }
```

同时把类 javadoc 里这句：

```
 * <p>本期只实现 {@link StdioServerConfig}；SSE / Streamable HTTP 是预留的扩展变体
 * （见设计文档 §5 扩展点）。公共访问器 {@link #name()} / {@link #enabled()} / {@link #timeoutMs()}
```

改为：

```
 * <p>已实现 {@link StdioServerConfig}（本地子进程）与 {@link HttpServerConfig}（远程 Streamable HTTP）；
 * SSE 仍未实现。公共访问器 {@link #name()} / {@link #enabled()} / {@link #timeoutMs()}
```

- [ ] **Step 4: 改 loader 分型**

在 `McpConfigLoader.java` 顶部补 import：

```java
import java.net.URI;
```

把整个 `parseEntry` 方法替换为：

```java
    /** 解析单条；不合法则记 WARN 并返回 null（跳过）。 */
    private static McpServerConfig parseEntry(String name, JsonNode node) {
        String type = node.has("type") ? node.get("type").asString() : "stdio";
        boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean();
        Duration timeout = node.has("timeoutMs")
                ? Duration.ofMillis(node.get("timeoutMs").asLong()) : DEFAULT_TIMEOUT;
        return switch (type) {
            case "stdio" -> parseStdio(name, node, enabled, timeout);
            // "http" 对齐 Claude Code 生态的写法，"streamable-http" 是规范全称，两种都认
            case "http", "streamable-http" -> parseHttp(name, node, enabled, timeout);
            case "sse" -> {
                log.warn("MCP server '{}' 的 sse 传输暂未支持（远程 server 请改用 type=http 的 "
                        + "Streamable HTTP），跳过。", name);
                yield null;
            }
            default -> {
                log.warn("MCP server '{}' 传输 type='{}' 未知，跳过。", name, type);
                yield null;
            }
        };
    }

    private static McpServerConfig parseStdio(String name, JsonNode node,
                                              boolean enabled, Duration timeout) {
        JsonNode commandNode = node.get("command");
        if (commandNode == null || commandNode.asString().isBlank()) {
            log.warn("MCP server '{}' 缺 command，跳过。", name);
            return null;
        }
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

    private static McpServerConfig parseHttp(String name, JsonNode node,
                                             boolean enabled, Duration timeout) {
        JsonNode urlNode = node.get("url");
        if (urlNode == null || urlNode.asString().isBlank()) {
            log.warn("MCP server '{}' 缺 url，跳过。", name);
            return null;
        }
        String url = urlNode.asString().trim();
        if (!isAbsoluteHttpUrl(url)) {
            log.warn("MCP server '{}' 的 url 非法（需 http/https 绝对地址）：{}，跳过。", name, url);
            return null;
        }
        return new McpServerConfig.HttpServerConfig(name, enabled, timeout, url, Map.of());
    }

    /**
     * URL 是否是可用的 http/https 绝对地址。
     *
     * <p><b>不能只靠 {@code URI.create} 抛异常来判非法</b>：{@code URI.create("foo")} 并不抛，
     * 它返回 scheme 与 authority 均为 null 的相对 URI，拿去构造 transport 会在连接期才炸出难懂的错。
     */
    static boolean isAbsoluteHttpUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getAuthority() != null && !uri.getAuthority().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
```

同时把类 javadoc 里的「仅 stdio 传输本期落地。」改为「stdio 与 Streamable HTTP 两种传输已落地，sse 仍跳过。」

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='McpConfigLoaderTest'`
Expected: 全绿（原有用例 + 新增 5 条）

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpServerConfig.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java
git commit -m "feat: MCP 配置支持 type=http 的 Streamable HTTP 变体"
```

---

### Task 3: headers 插值接入 loader

Task 2 的 `parseHttp` 里 headers 恒为空 map，本任务把它接上。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java`

**关于测试怎么拿到环境变量**：`EnvInterpolator` 的解析器已经可注入并在 Task 1 用假 map 测透了。
loader 这一层要验证的是「解析器接对了、失败会跳过」，所以直接用**真实环境里必然存在的变量**（`PATH`）
和**必然不存在的变量**（一个足够长的随机名）。不必为此把 resolver 一路穿透进 `load(...)` 的签名。

- [ ] **Step 1: 写失败的测试**

追加：

```java
    @Test
    void interpolatesHeaderValuesFromEnvironment(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "ctx7": { "type": "http", "url": "https://h/mcp",
                              "headers": { "X-Path": "prefix-${PATH}", "X-Literal": "no-placeholder" } }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size());
        McpServerConfig.HttpServerConfig http = (McpServerConfig.HttpServerConfig) configs.get(0);
        assertEquals("no-placeholder", http.headers().get("X-Literal"), "字面值应原样透传");
        assertEquals("prefix-" + System.getenv("PATH"), http.headers().get("X-Path"),
                "${PATH} 应被真实环境变量替换");
    }

    @Test
    void headerReferencingUndefinedVariableSkipsWholeServer(@TempDir Path dir) throws Exception {
        Path project = write(dir, "project.json", """
                { "mcpServers": {
                    "bad": { "type": "http", "url": "https://h/mcp",
                             "headers": { "Authorization": "Bearer ${CODETUI_NO_SUCH_VAR_9F3A}" } },
                    "good": { "type": "http", "url": "https://h/mcp" }
                }}""");

        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("nope.json"), project);

        assertEquals(1, configs.size(),
                "引用未定义变量的 server 应整条跳过（带着字面量 ${} 去请求只会拿到看不懂的 401）");
        assertEquals("good", configs.get(0).name());
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='McpConfigLoaderTest'`
Expected: `interpolatesHeaderValuesFromEnvironment` FAIL —— headers 是空 map（Task 2 里恒为 `Map.of()`），
`X-Literal` 断言拿到 null

- [ ] **Step 3: 改实现**

把 `McpConfigLoader.parseHttp` 里的这一行：

```java
        return new McpServerConfig.HttpServerConfig(name, enabled, timeout, url, Map.of());
```

替换为：

```java
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            for (Map.Entry<String, JsonNode> e : headersNode.properties()) {
                try {
                    headers.put(e.getKey(),
                            EnvInterpolator.interpolate(e.getValue().asString(), System::getenv));
                } catch (EnvInterpolator.UndefinedVariableException ex) {
                    // 整条跳过而非留下字面量：带着 ${X} 去请求只会换来一个看不懂的 401
                    log.warn("MCP server '{}' 的 header {} {}，跳过该 server。",
                            name, e.getKey(), ex.getMessage());
                    return null;
                }
            }
        }
        return new McpServerConfig.HttpServerConfig(name, enabled, timeout, url, Map.copyOf(headers));
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='McpConfigLoaderTest'`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpConfigLoader.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpConfigLoaderTest.java
git commit -m "feat: MCP headers 支持 \${ENV_VAR} 插值，变量缺失即跳过该 server"
```

---

### Task 4: URL 拆分（baseUri / endpoint）

SDK 是 `builder(baseUri)` + `endpoint(path)`，默认 endpoint `/mcp`。**不拆会错**：把整个
`https://h/mcp` 当 baseUri 传进去，SDK 再拼默认 `/mcp` → 实际打到 `/mcp/mcp` → 404。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java`

- [ ] **Step 1: 写失败的测试**

在 `McpTransportFactoryTest` 追加（文件顶部补 `import java.net.URI;` 与
`import static org.junit.jupiter.api.Assertions.assertEquals;`、`assertNull`）：

```java
    @Test
    void splitsBaseUriAndEndpoint() {
        URI uri = URI.create("https://mcp.context7.com/mcp");

        assertEquals("https://mcp.context7.com", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp", McpTransportFactory.endpointOf(uri));
    }

    @Test
    void keepsMultiSegmentPathAsEndpoint() {
        URI uri = URI.create("https://h/api/v1/mcp");

        assertEquals("https://h", McpTransportFactory.baseUriOf(uri));
        assertEquals("/api/v1/mcp", McpTransportFactory.endpointOf(uri));
    }

    /** 无路径 / 只有 "/" 时返回 null，表示交给 SDK 的默认 endpoint（/mcp）。 */
    @Test
    void emptyPathYieldsNullEndpoint() {
        assertNull(McpTransportFactory.endpointOf(URI.create("https://h")));
        assertNull(McpTransportFactory.endpointOf(URI.create("https://h/")));
    }

    @Test
    void keepsQueryStringInEndpoint() {
        URI uri = URI.create("https://h/mcp?tenant=acme");

        assertEquals("https://h", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp?tenant=acme", McpTransportFactory.endpointOf(uri));
    }

    @Test
    void keepsPortInBaseUri() {
        URI uri = URI.create("http://127.0.0.1:8080/mcp");

        assertEquals("http://127.0.0.1:8080", McpTransportFactory.baseUriOf(uri));
        assertEquals("/mcp", McpTransportFactory.endpointOf(uri));
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='McpTransportFactoryTest'`
Expected: 编译失败，`找不到符号: 方法 baseUriOf(java.net.URI)` 与 `endpointOf(java.net.URI)`

- [ ] **Step 3: 写实现**

在 `McpTransportFactory.java` 顶部补 import：

```java
import java.net.URI;
```

在 `create(..)` 方法之后追加两个包级方法：

```java
    /**
     * 拆出 baseUri：{@code scheme://authority}（authority 含端口）。SDK 的 {@code builder(baseUri)}
     * 只认这一段，路径要另外经 {@code endpoint(..)} 给。
     */
    static String baseUriOf(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /**
     * 拆出 endpoint：path（含 query）。为空或 {@code "/"} 时返回 {@code null}，表示用 SDK 默认的 {@code /mcp}。
     *
     * <p><b>不拆会错</b>：把整个 {@code https://h/mcp} 当 baseUri 传进去，SDK 会再拼一个默认 {@code /mcp}，
     * 实际请求打到 {@code /mcp/mcp}。
     */
    static String endpointOf(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='McpTransportFactoryTest'`
Expected: `Tests run: 6, Failures: 0, Errors: 0`（原有 1 条 + 新增 5 条）

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java
git commit -m "feat: MCP 端点 URL 拆分为 baseUri + endpoint"
```

---

### Task 5: transport factory 的 http 分支与鉴权头

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java`

- [ ] **Step 1: 写失败的测试**

追加（文件顶部补 `import io.modelcontextprotocol.common.McpTransportContext;`、
`import java.net.http.HttpRequest;`、`import java.util.Optional;` 若尚无）：

```java
    @Test
    void httpConfigProducesTransport() {
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "ctx7", true, Duration.ofSeconds(30), "https://mcp.context7.com/mcp", Map.of());

        Optional<McpClientTransport> t = McpTransportFactory.create(cfg);

        assertTrue(t.isPresent(), "仅构造 transport 对象，不发网络");
    }

    @Test
    void httpConfigWithHeadersProducesTransport() {
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "ctx7", true, Duration.ofSeconds(30), "https://h/mcp",
                Map.of("Authorization", "Bearer tok"));

        assertTrue(McpTransportFactory.create(cfg).isPresent());
    }

    /**
     * 鉴权头是否真的落到出站请求上——<b>这条必须离线测</b>：真机冒烟证明不了它，
     * 实测 Context7 在 initialize 阶段不校验 key，headers 全丢也照样回 200。
     */
    @Test
    void headerCustomizerPutsConfiguredHeadersOnRequest() {
        var customizer = McpTransportFactory.headerCustomizer(
                Map.of("Authorization", "Bearer tok", "X-Trace", "abc"));
        URI uri = URI.create("https://example.com/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        customizer.customize(builder, "POST", uri, null, McpTransportContext.EMPTY);

        HttpRequest request = builder.build();
        assertEquals(Optional.of("Bearer tok"), request.headers().firstValue("Authorization"));
        assertEquals(Optional.of("abc"), request.headers().firstValue("X-Trace"));
    }

    @Test
    void headerCustomizerWithEmptyMapAddsNothing() {
        var customizer = McpTransportFactory.headerCustomizer(Map.of());
        URI uri = URI.create("https://example.com/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        customizer.customize(builder, "POST", uri, null, McpTransportContext.EMPTY);

        assertEquals(0, builder.build().headers().map().size(), "空 headers 不应凭空加头");
    }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `mvn -pl springai-code-tui test -Dtest='McpTransportFactoryTest'`
Expected: 编译失败（`找不到符号: 方法 headerCustomizer`）；且 `httpConfigProducesTransport` 在补上
方法后仍会失败——`create` 目前对非 stdio 配置走「传输类型未实现」分支返回 `Optional.empty()`

- [ ] **Step 3: 写实现**

在 `McpTransportFactory.java` 顶部补 import：

```java
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.util.Map;
```

在 `create(..)` 里，stdio 分支之后、`log.warn("...传输类型未实现...")` 之前插入：

```java
            if (config instanceof McpServerConfig.HttpServerConfig http) {
                URI uri = URI.create(http.url());
                HttpClientStreamableHttpTransport.Builder builder =
                        HttpClientStreamableHttpTransport.builder(baseUriOf(uri))
                                .connectTimeout(http.timeoutMs())
                                .jsonMapper(McpJsonDefaults.getMapper());
                String endpoint = endpointOf(uri);
                if (endpoint != null) {
                    builder.endpoint(endpoint);
                }
                if (!http.headers().isEmpty()) {
                    builder.httpRequestCustomizer(headerCustomizer(http.headers()));
                }
                return Optional.of(builder.build());
            }
```

并追加：

```java
    /**
     * 把配置里的 headers 逐条加到每个出站请求上。
     *
     * <p>抽成独立方法是为了<b>能直接单测</b>：真机冒烟证明不了它——实测 Context7 在 initialize
     * 阶段不校验 API key，headers 全部丢失它照样返回 200 与完整 serverInfo。
     */
    static McpSyncHttpClientRequestCustomizer headerCustomizer(Map<String, String> headers) {
        return (requestBuilder, method, uri, body, context) ->
                headers.forEach(requestBuilder::header);
    }
```

同时更新已过期的类 javadoc。把：

```
 * <p>这是加新传输的<b>唯一分型点</b>（设计文档 §5 扩展点）：将来接入 SSE / Streamable HTTP，
 * 只需在此加一个分支，用 mcp-core 现成的 {@code HttpClientSseClientTransport} /
 * {@code HttpClientStreamableHttpTransport}（基于 JDK HttpClient，无新依赖）。
 * {@link McpClientManager} 与其余流程<b>零改动</b>。
```

改为：

```
 * <p>这是加新传输的<b>唯一分型点</b>（设计文档 §5 扩展点）。已落地 stdio 与 Streamable HTTP；
 * SSE 仍未实现——旧标准、官方已 deprecated，要加只需在此再补一个分支，用 mcp-core 现成的
 * {@code HttpClientSseClientTransport}（基于 JDK HttpClient，无新依赖）。
 * 加传输时 {@link McpClientManager} 与其余流程<b>零改动</b>——Streamable HTTP 这次即是明证。
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl springai-code-tui test -Dtest='McpTransportFactoryTest'`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: 全模块回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/McpTransportFactory.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpTransportFactoryTest.java
git commit -m "feat: MCP transport factory 支持 Streamable HTTP 与鉴权头"
```

---

### Task 6: 端到端冒烟

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpStreamableHttpSmokeTest.java`

- [ ] **Step 1: 写测试**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Streamable HTTP 端到端冒烟：走完整生产路径（transport → McpSyncClient → initialize → tools/list）。
 *
 * <p>门控 {@code CODETUI_MCP_SMOKE_URL}：设了才跑并连该地址，不设则跳过。用专门的开关而非绑某个
 * API key，是因为验证目标（Context7）不需要鉴权，没有天然可绑的变量；这样也便于指向别的 server。
 * 若同时设了 {@code CONTEXT7_API_KEY}，会带上 Authorization 头，顺带走一遍鉴权头路径。
 *
 * <p><b>本测试不验证鉴权。</b>实测 Context7 在 initialize 阶段不校验 API key（故意传错误 key 仍回
 * 200 与完整 serverInfo），所以 headers 全部丢失它照样绿。鉴权头是否真的发出去，由
 * {@code McpTransportFactoryTest.headerCustomizerPutsConfiguredHeadersOnRequest} 负责。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_MCP_SMOKE_URL", matches = ".+")
class McpStreamableHttpSmokeTest {

    @Test
    void connectsHandshakesAndListsTools() {
        Map<String, String> headers = new LinkedHashMap<>();
        String key = System.getenv("CONTEXT7_API_KEY");
        if (key != null && !key.isBlank()) {
            headers.put("Authorization", "Bearer " + key);
        }
        McpServerConfig.HttpServerConfig cfg = new McpServerConfig.HttpServerConfig(
                "smoke", true, Duration.ofSeconds(30),
                System.getenv("CODETUI_MCP_SMOKE_URL"), Map.copyOf(headers));

        McpClientManager manager = McpClientManager.connectAll(List.of(cfg));
        try {
            List<ToolCallback> tools = manager.toolCallbacks();
            System.out.println("[smoke] 拉到 " + tools.size() + " 个工具："
                    + tools.stream().map(t -> t.getToolDefinition().name()).toList());
            assertFalse(tools.isEmpty(),
                    "应能通过 Streamable HTTP 完成握手并拉到工具列表；空列表说明连接或发现失败");
        } finally {
            manager.close();
        }
    }
}
```

- [ ] **Step 2: 跑测试（无 URL 时应跳过）**

Run: `mvn -pl springai-code-tui test -Dtest='McpStreamableHttpSmokeTest'`
Expected: `Tests run: 0, Skipped: 1`（或 `Tests run: 1, Skipped: 1`），BUILD SUCCESS

- [ ] **Step 3: 跑真机验证**

Run:
```bash
set -a; . ~/.secrets; set +a
CODETUI_MCP_SMOKE_URL=https://mcp.context7.com/mcp \
  mvn -pl springai-code-tui test -Dtest='McpStreamableHttpSmokeTest'
```
Expected: `Tests run: 1, Failures: 0`，并打印形如
`[smoke] 拉到 N 个工具：[mcp__smoke__resolve-library-id, mcp__smoke__get-library-docs]` 的行。

若失败，**先贴出完整错误再动手**——最可能的两个原因是 URL 拆分错（打到 `/mcp/mcp`）
和协议版本不匹配，两者的报错完全不同，不要盲改。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/McpStreamableHttpSmokeTest.java
git commit -m "test: MCP Streamable HTTP 端到端冒烟（CODETUI_MCP_SMOKE_URL 门控）"
```

---

### Task 7: 文档

**Files:**
- Modify: `springai-code-tui/README.md`

- [ ] **Step 1: 改 MCP 简介行**

把 `README.md:14` 里的：

```
连接外部 [MCP](https://modelcontextprotocol.io/) server（本期仅 **stdio**，即 `npx`/`uvx` 一类本地子进程 server，如 `chrome-devtools-mcp`、官方 filesystem server）
```

替换为：

```
连接外部 [MCP](https://modelcontextprotocol.io/) server（**stdio** 本地子进程，如 `npx`/`uvx` 起的 `chrome-devtools-mcp`、官方 filesystem server；以及 **Streamable HTTP** 远程 server，如 Context7）
```

- [ ] **Step 2: 在 MCP 配置章节补 http 示例**

在 README 的「MCP 配置（接入外部工具）」章节里，现有 stdio 示例之后追加：

````markdown
连接**远程 server**（Streamable HTTP）写 `type: "http"`：

```json
{
  "mcpServers": {
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "headers": { "Authorization": "Bearer ${CONTEXT7_API_KEY}" },
      "timeoutMs": 30000
    }
  }
}
```

- `type` 认 `"http"` 与 `"streamable-http"` 两种拼写；`"sse"`（旧标准）**暂未支持**，会被跳过并记 WARN。
- `url` 写完整端点地址，内部会拆成 baseUri + endpoint 两段。必须是 `http`/`https` 绝对地址，否则跳过。
- `headers` 的**值**支持 `${ENV_VAR}` 插值：token 留在环境变量里，配置文件只写引用，便于多机共用同一份
  `mcp.json`。不含 `${}` 的字面值照常可用。
- **引用了未定义的环境变量 → 整条 server 跳过并记 WARN**（而不是带着字面量 `${TOKEN}` 去请求，
  那只会换来一个看不懂的 401）。
````

- [ ] **Step 3: 补安全披露**

在 README 的安全边界章节里，`- **联网出口无过滤**：...` 那条之后追加一条：

```
- **远程 MCP server 会收到工具调用的入参**：配置了 `type: "http"` 的远程 server 后，智能体调用其工具时，**入参会发往该服务端**（例如查询词、库名、代码片段），这与本地 stdio server（数据不出本机）性质完全不同。请只连你信任的服务端；`headers` 里的 token 建议用 `${ENV_VAR}` 引用而非写死在配置文件里。
```

- [ ] **Step 4: 人工核对**

Run: `grep -n "streamable-http\|CONTEXT7_API_KEY\|远程 MCP server 会收到" springai-code-tui/README.md`
Expected: 三处都有命中

- [ ] **Step 5: 最终全量回归**

Run: `mvn -pl springai-code-tui test`
Expected: `BUILD SUCCESS`

- [ ] **Step 6: 提交**

```bash
git add springai-code-tui/README.md
git commit -m "docs: MCP Streamable HTTP 配置说明与远程 server 的数据外发披露"
```

---

## 完成标准

- `mvn -pl springai-code-tui test` 通过（冒烟在未设 `CODETUI_MCP_SMOKE_URL` 时 skipped 属正常）
- 用 Context7 真机跑通冒烟，能打印出拉到的工具名
- 现有 stdio 配置行为完全不变（原有 `McpConfigLoaderTest` 用例全绿）
- 非法 URL 三种形状（相对 URI / 错误 scheme / 无 authority）都在解析期被跳过，不留到连接期

## 本计划范围外

- **不做 SSE 传输**：旧标准、官方已 deprecated，保留「暂未支持」的 WARN 分支即可
- **不做 OAuth 授权流**：SDK 有 `authorizationErrorHandler` 钩子，但完整 OAuth 属独立项目
- **不改** `McpClientManager` / `McpRegistry` / `/mcp` 面板：它们对传输类型无感，这正是当初设计的目的
