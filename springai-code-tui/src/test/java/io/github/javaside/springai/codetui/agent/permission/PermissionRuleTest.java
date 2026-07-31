package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    private static final Path ROOT = Path.of("/work/proj");

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.SESSION);
    }

    @Test
    @DisplayName("裸工具名 / 工具名(*) 都表示该工具的全部调用")
    void bareToolNameMatchesEverything() {
        PermissionRule bare = allow("TodoWrite");
        assertNull(bare.pattern(), "裸工具名解析出的 pattern 应为 null（= 全部调用）");
        assertTrue(bare.matches("TodoWrite", "任意目标", false, ROOT));
        assertFalse(bare.matches("Bash", "任意目标", false, ROOT), "工具名不同不应命中");

        assertNull(allow("Read(*)").pattern(), "(*) 等价裸工具名");
        assertTrue(allow("Read(*)").matches("Read", "/etc/passwd", true, ROOT));
    }

    @Test
    @DisplayName("`*` 作工具名匹配任意工具")
    void wildcardToolName() {
        assertTrue(allow("*").matches("SomeMcpTool", "{}", false, ROOT));
    }

    @Test
    @DisplayName("前缀匹配：`:` 之前按字面量比较，不再解释任何通配符")
    void prefixPattern() {
        PermissionRule r = allow("Bash(mvn -pl springai-code-tui test:*)");
        assertTrue(r.matches("Bash", "mvn -pl springai-code-tui test -Dtest=Foo", false, ROOT));
        assertTrue(r.matches("Bash", "mvn -pl springai-code-tui test", false, ROOT));
        assertFalse(r.matches("Bash", "mvn -pl other test", false, ROOT));

        // 关键：rm -rf / 是「命令前缀」，绝不能被误读成路径 glob
        PermissionRule rm = PermissionRule.parse("Bash(rm -rf /:*)",
                PermissionBehavior.DENY, RuleScope.USER);
        assertTrue(rm.matches("Bash", "rm -rf /var/tmp/x", false, ROOT));
        assertFalse(rm.matches("Bash", "ls -la", false, ROOT));
    }

    @Test
    @DisplayName("路径 glob：* 单层、** 递归；相对模式对绝对目标按 root 相对化再试")
    void globPattern() {
        PermissionRule etc = allow("Write(/etc/**)");
        assertTrue(etc.matches("Write", "/etc/hosts", true, ROOT));
        assertTrue(etc.matches("Write", "/etc/nginx/nginx.conf", true, ROOT));
        assertFalse(etc.matches("Write", "/var/log/x", true, ROOT));

        PermissionRule rel = allow("Read(src/*.java)");
        assertTrue(rel.matches("Read", "src/Main.java", true, ROOT), "相对目标直接匹配");
        assertTrue(rel.matches("Read", "/work/proj/src/Main.java", true, ROOT),
                "绝对目标应按 root 相对化后匹配");
        assertFalse(rel.matches("Read", "/other/src/Main.java", true, ROOT),
                "root 之外的同名相对路径不应命中");
    }

    @Test
    @DisplayName("非路径目标（bash_id / query 等）按整串相等比较")
    void exactPatternForNonPathTarget() {
        PermissionRule r = allow("KillShell(shell_1)");
        assertTrue(r.matches("KillShell", "shell_1", false, ROOT));
        assertFalse(r.matches("KillShell", "shell_2", false, ROOT));
    }

    @Test
    @DisplayName("目标为 null（入参缺该字段）时，带 pattern 的规则一律不命中")
    void nullTargetNeverMatchesPattern() {
        assertFalse(allow("Grep(src/**)").matches("Grep", null, true, ROOT));
        assertTrue(allow("Grep").matches("Grep", null, true, ROOT), "无 pattern 的规则仍命中");
    }

    @Test
    @DisplayName("非法 DSL 返回 null（调用方记 WARN 跳过，绝不抛异常）")
    void malformedDslReturnsNull() {
        assertNull(allow("Bash(unclosed"));
        assertNull(allow("(nopattern)"));
        assertNull(allow("   "));
        assertNull(allow(null));
    }

    @Test
    @DisplayName("非法 glob 不抛异常，只是不命中")
    void invalidGlobDegradesToNoMatch() {
        assertFalse(allow("Write([)").matches("Write", "/tmp/a", true, ROOT));
    }

    @Test
    @DisplayName("toDsl 可还原（供「允许，永久」回写）")
    void toDslRoundTrip() {
        assertEquals("Bash(mvn test:*)", allow("Bash(mvn test:*)").toDsl());
        assertEquals("TodoWrite(*)", allow("TodoWrite").toDsl(), "无 pattern 统一还原成 (*)");
    }

    @Test
    @DisplayName("模式循环：DEFAULT → ACCEPT_EDITS → DEFAULT；BYPASS 不在普通循环里")
    void modeCycle() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(false));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS.next(false));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(false),
                "未开 bypass 时从 BYPASS 只能出去，回不来");

        // 开了 --dangerously-skip-permissions：BYPASS 进循环
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(true));
        assertEquals(PermissionMode.BYPASS, PermissionMode.ACCEPT_EDITS.next(true));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(true));
    }
}
