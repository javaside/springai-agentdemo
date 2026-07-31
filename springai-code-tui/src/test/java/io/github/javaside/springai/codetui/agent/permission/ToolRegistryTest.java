package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
                ToolTargets.extract("Read", "{\"filePath\":\"/tmp/a.txt\",\"limit\":10}", null));
        assertEquals("mvn test",
                ToolTargets.extract("Bash", "{\"command\":\"mvn test\",\"timeout\":1000}", null));
        assertEquals("shell_1", ToolTargets.extract("KillShell", "{\"bash_id\":\"shell_1\"}", null));
    }

    @Test
    @DisplayName("目标提取：INTERNAL 无目标字段返回 null；UNKNOWN 返回整串入参")
    void extractForFieldlessTools() {
        assertNull(ToolTargets.extract("TodoWrite", "{\"todos\":[]}", null));
        assertEquals("{\"x\":1}", ToolTargets.extract("some_mcp_tool", "{\"x\":1}", null));
    }

    @Test
    @DisplayName("目标提取：字段缺失 / JSON 非法 → null，绝不抛异常")
    void extractDegradesGracefully() {
        assertNull(ToolTargets.extract("Read", "{\"other\":1}", null), "缺 filePath");
        assertNull(ToolTargets.extract("Read", "not json at all", null));
        assertNull(ToolTargets.extract("Read", null, null));
        assertNull(ToolTargets.extract("Grep", "{\"pattern\":\"x\"}", null), "Grep 的 path 可选，缺则 null");
    }

    @Test
    @DisplayName("目标提取：非字符串字段（对象/数组/数字）不崩，按文本形式取")
    void extractNonStringField() {
        // Jackson 3 的 asString() 对非文本节点会抛，实现必须自己判 isTextual
        assertNull(ToolTargets.extract("Read", "{\"filePath\":{\"nested\":1}}", null));
    }

    @Test
    @DisplayName("路径目标：相对路径按 root 解析掉 ..，绝对路径只 normalize")
    void relativePathIsResolvedAgainstRoot() {
        Path root = Path.of("/work/proj");
        assertEquals("/etc/passwd",
                ToolTargets.extract("Write", "{\"filePath\":\"../../etc/passwd\"}", root),
                "相对路径的 .. 必须按 root 解析掉，否则 deny /etc/** 形同虚设");
        assertEquals("/work/proj/src/Main.java",
                ToolTargets.extract("Read", "{\"filePath\":\"src/Main.java\"}", root));
        assertEquals("/etc/hosts",
                ToolTargets.extract("Read", "{\"filePath\":\"/etc/hosts\"}", root),
                "绝对路径只 normalize，不再拼 root");
    }

    @Test
    @DisplayName("非路径目标不得被当路径解析；空白目标返回 null")
    void nonPathTargetsAreNotResolved() {
        Path root = Path.of("/work/proj");
        assertEquals("git status",
                ToolTargets.extract("Bash", "{\"command\":\"git status\"}", root),
                "命令不是路径，不得被当路径解析");
        assertNull(ToolTargets.extract("Write", "{\"filePath\":\"   \"}", root),
                "空白目标无法核实，返回 null 让引擎落保守 ASK");
    }
}
