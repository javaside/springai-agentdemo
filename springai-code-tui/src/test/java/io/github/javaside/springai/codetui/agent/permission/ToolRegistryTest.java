package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    @DisplayName("登记表覆盖各类别的代表工具，且路径标记正确")
    void lookupKnownTools() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.lookup("Read").category());
        assertTrue(ToolRegistry.lookup("Read").pathTarget());
        assertEquals("filePath", ToolRegistry.lookup("Read").targetField());

        assertEquals(ToolCategory.FILE_WRITE, ToolRegistry.lookup("Write").category());
        assertEquals(ToolCategory.FILE_WRITE, ToolRegistry.lookup("Edit").category());

        assertEquals(ToolCategory.COMMAND, ToolRegistry.lookup("Bash").category());
        assertEquals("command", ToolRegistry.lookup("Bash").targetField());
        assertFalse(ToolRegistry.lookup("Bash").pathTarget(), "命令不是路径，不能走 glob");

        // BashOutput 是只读，但目标是 bash_id——不是路径
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.lookup("BashOutput").category());
        assertEquals("bash_id", ToolRegistry.lookup("BashOutput").targetField());
        assertFalse(ToolRegistry.lookup("BashOutput").pathTarget());

        assertEquals(ToolCategory.NETWORK_READ, ToolRegistry.lookup("WebFetch").category());
        assertEquals(ToolCategory.INTERNAL, ToolRegistry.lookup("TodoWrite").category());
        assertEquals(ToolCategory.INTERNAL, ToolRegistry.lookup("MemoryCreate").category());
    }

    @Test
    @DisplayName("未登记工具（含全部 MCP 工具）兜底为 UNKNOWN，目标取整串入参")
    void unknownToolFallsBack() {
        ToolRegistry.Entry e = ToolRegistry.lookup("some_mcp_tool");
        assertEquals(ToolCategory.UNKNOWN, e.category());
        assertNull(e.targetField(), "UNKNOWN 无目标字段，判定目标是整串 JSON");
        assertFalse(e.pathTarget());
    }

    @Test
    @DisplayName("目标提取：按登记的字段名从入参 JSON 取值")
    void extractTarget() {
        assertEquals("/tmp/a.txt",
                ToolTargets.extract("Read", "{\"filePath\":\"/tmp/a.txt\",\"limit\":10}"));
        assertEquals("mvn test",
                ToolTargets.extract("Bash", "{\"command\":\"mvn test\",\"timeout\":1000}"));
        assertEquals("shell_1", ToolTargets.extract("KillShell", "{\"bash_id\":\"shell_1\"}"));
    }

    @Test
    @DisplayName("目标提取：INTERNAL 无目标字段返回 null；UNKNOWN 返回整串入参")
    void extractForFieldlessTools() {
        assertNull(ToolTargets.extract("TodoWrite", "{\"todos\":[]}"));
        assertEquals("{\"x\":1}", ToolTargets.extract("some_mcp_tool", "{\"x\":1}"));
    }

    @Test
    @DisplayName("目标提取：字段缺失 / JSON 非法 → null，绝不抛异常")
    void extractDegradesGracefully() {
        assertNull(ToolTargets.extract("Read", "{\"other\":1}"), "缺 filePath");
        assertNull(ToolTargets.extract("Read", "not json at all"));
        assertNull(ToolTargets.extract("Read", null));
        assertNull(ToolTargets.extract("Grep", "{\"pattern\":\"x\"}"), "Grep 的 path 可选，缺则 null");
    }

    @Test
    @DisplayName("目标提取：非字符串字段（对象/数组/数字）不崩，按文本形式取")
    void extractNonStringField() {
        // Jackson 3 的 asString() 对非文本节点会抛，实现必须自己判 isTextual
        assertNull(ToolTargets.extract("Read", "{\"filePath\":{\"nested\":1}}"));
    }
}
