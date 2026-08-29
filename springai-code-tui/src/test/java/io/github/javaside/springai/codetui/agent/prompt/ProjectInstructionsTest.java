package io.github.javaside.springai.codetui.agent.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两层 AGENTS.md（用户 ~/.codetui/AGENTS.md + 项目 <root>/AGENTS.md）加载与拼接。 */
class ProjectInstructionsTest {

    private static void writeUserAgents(Path fakeHome, String content) throws Exception {
        Path p = fakeHome.resolve(".codetui").resolve("AGENTS.md");
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String loadWithHome(Path root, Path fakeHome) {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        try {
            return ProjectInstructions.load(root);
        } finally {
            if (prev == null) System.clearProperty("user.home");
            else System.setProperty("user.home", prev);
        }
    }

    @Test
    void bothLayersMissing_returnsEmpty(@TempDir Path root, @TempDir Path fakeHome) {
        assertEquals("", loadWithHome(root, fakeHome), "无任何 AGENTS.md 时应返回空串（注入段为空、无操作）");
    }

    @Test
    void projectOnly_included(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "用 2 空格缩进。提交前跑 mvn test。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("用 2 空格缩进"), "应含项目级内容");
        assertTrue(out.contains("project"), "应带项目级来源标签");
    }

    @Test
    void userOnly_included(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        writeUserAgents(fakeHome, "我偏好函数式风格。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("我偏好函数式风格"), "应含用户级内容");
        assertTrue(out.contains("user"), "应带用户级来源标签");
    }

    @Test
    void bothLayers_userBeforeProject(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        writeUserAgents(fakeHome, "USER_MARKER_文本");
        Files.writeString(root.resolve("AGENTS.md"), "PROJECT_MARKER_文本");
        String out = loadWithHome(root, fakeHome);
        int u = out.indexOf("USER_MARKER_文本");
        int p = out.indexOf("PROJECT_MARKER_文本");
        assertTrue(u >= 0 && p >= 0, "两层内容都应在");
        assertTrue(u < p, "用户级应在项目级之前（项目级后读、优先级更高）");
    }

    @Test
    void blankFile_skipped(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "   \n\t\n ");
        assertEquals("", loadWithHome(root, fakeHome), "空白文件应视同不存在");
    }

    @Test
    void bracesInContent_passThroughVerbatim(@TempDir Path root, @TempDir Path fakeHome) throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "示例：Map.of(\"k\", v) 与 {placeholder} 与 {{double}}。");
        String out = loadWithHome(root, fakeHome);
        assertTrue(out.contains("{placeholder}"), "单花括号应原样保留");
        assertTrue(out.contains("{{double}}"), "双花括号应原样保留");
    }
}
