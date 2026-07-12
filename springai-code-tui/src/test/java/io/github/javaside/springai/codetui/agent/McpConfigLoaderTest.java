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
}
