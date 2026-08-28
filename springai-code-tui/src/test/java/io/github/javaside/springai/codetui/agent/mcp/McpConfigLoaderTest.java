package io.github.javaside.springai.codetui.agent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(fs.enabled());
        assertEquals("npx", fs.command());
        assertEquals(List.of("-y", "server-fs", "/tmp"), fs.args());
        assertEquals(Map.of(), fs.env());
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

        assertEquals(2, configs.size());
        McpServerConfig.StdioServerConfig fs = (McpServerConfig.StdioServerConfig)
                configs.stream().filter(c -> c.name().equals("fs")).findFirst().orElseThrow();
        assertEquals("project-cmd", fs.command());
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
        assertTrue(configs.isEmpty());
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
        assertEquals("good", configs.get(0).name());
    }

    @Test
    void unknownTypeIsSkipped(@TempDir Path dir) throws Exception {
        Path project = write(dir, "p.json", """
                { "mcpServers": {
                    "remote": { "type": "sse", "url": "http://x" },
                    "local":  { "type": "stdio", "command": "ok" }
                }}""");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), project);
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

    @Test
    void wrongTypedCommandFieldIsSkippedNeverThrows(@TempDir Path dir) throws Exception {
        Path project = write(dir, "p.json", """
                { "mcpServers": {
                    "bad":  { "command": ["npx"] },
                    "good": { "command": "ok" }
                }}""");
        List<McpServerConfig> configs = McpConfigLoader.load(dir.resolve("none.json"), project);
        assertEquals(1, configs.size());          // 类型非法条被跳过，好的保留
        assertEquals("good", configs.get(0).name());
    }

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
}
