package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 原子写收尾：成功回写后临时文件不残留
        assertFalse(Files.exists(dir.resolve("mcp.json.tmp")));
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
