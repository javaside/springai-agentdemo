package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigLoaderTest {

    private static boolean hasRule(List<PermissionRule> rules, String dsl, PermissionBehavior b) {
        return rules.stream().anyMatch(r -> r.toDsl().equals(dsl) && r.behavior() == b);
    }

    @Test
    @DisplayName("两层合并而非覆盖：deny 只增不减，项目层削弱不了用户层禁令")
    void twoLayersMerge(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, """
                {"defaultMode":"DEFAULT",
                 "allow":["Read(*)"],
                 "deny":["Bash(rm -rf /:*)"]}""");
        Files.writeString(project, """
                {"defaultMode":"ACCEPT_EDITS",
                 "allow":["Bash(mvn test:*)"],
                 "ask":["Bash(git push:*)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);

        assertEquals(PermissionMode.ACCEPT_EDITS, cfg.defaultMode(), "defaultMode 项目层覆盖用户层");
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
        assertTrue(hasRule(cfg.rules(), "Bash(mvn test:*)", PermissionBehavior.ALLOW));
        assertTrue(hasRule(cfg.rules(), "Bash(git push:*)", PermissionBehavior.ASK));
        assertTrue(hasRule(cfg.rules(), "Bash(rm -rf /:*)", PermissionBehavior.DENY),
                "用户层 deny 必须保留——项目层不能削弱它");
    }

    @Test
    @DisplayName("规则带上来源层（供 /permissions 展示与回写目标判断）")
    void rulesCarryScope(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, """
                {"allow":["Read(*)"]}""");
        Files.writeString(project, """
                {"allow":["Write(src/**)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);

        assertEquals(RuleScope.USER,
                cfg.rules().stream().filter(r -> r.toDsl().equals("Read(*)")).findFirst().orElseThrow().scope());
        assertEquals(RuleScope.PROJECT,
                cfg.rules().stream().filter(r -> r.toDsl().equals("Write(src/**)")).findFirst().orElseThrow().scope());
    }

    @Test
    @DisplayName("降级契约：文件缺失 → 空配置 + 默认模式，绝不抛异常")
    void missingFilesDegrade(@TempDir Path dir) {
        PermissionConfig cfg = PermissionConfigLoader.load(
                dir.resolve("nope.json"), dir.resolve("also-nope.json"));
        assertTrue(cfg.rules().isEmpty());
        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode());
    }

    @Test
    @DisplayName("降级契约：JSON 非法 → 跳过该文件，另一层照常生效")
    void malformedJsonDegrades(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{ this is not json");
        Files.writeString(project, """
                {"allow":["Read(*)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);
        assertEquals(1, cfg.rules().size());
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
    }

    @Test
    @DisplayName("降级契约：单条规则非法 / defaultMode 未知 → 跳过该条，其余照常")
    void malformedEntriesSkipped(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, """
                {"defaultMode":"NO_SUCH_MODE",
                 "allow":["Bash(unclosed", "Read(*)", ""],
                 "deny":[123]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, dir.resolve("none.json"));

        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode(), "未知模式回退 DEFAULT");
        assertEquals(1, cfg.rules().size(), "只有 Read(*) 是合法的");
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
    }

    @Test
    @DisplayName("字段缺失（无 allow/ask/deny）不报错")
    void missingArraysAreFine(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        PermissionConfig cfg = PermissionConfigLoader.load(user, dir.resolve("none.json"));
        assertTrue(cfg.rules().isEmpty());
        assertEquals(PermissionMode.ACCEPT_EDITS, cfg.defaultMode());
        assertFalse(cfg.rules().stream().anyMatch(r -> r.behavior() == PermissionBehavior.DENY));
    }

    @Test
    @DisplayName("兼容 Claude Code 风格的小驼峰 acceptEdits")
    void acceptsCamelCaseMode(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"defaultMode\":\"acceptEdits\"}");
        assertEquals(PermissionMode.ACCEPT_EDITS,
                PermissionConfigLoader.load(user, dir.resolve("none.json")).defaultMode());
    }

    // ── 提权防线 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("配置文件不得声明 BYPASS——那扇门只有 --dangerously-skip-permissions 能开")
    void configCannotDeclareBypass(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");

        // 项目层：clone 下来的仓库不能靠一个 JSON 文件把整个会话开成全放行
        Files.writeString(project, "{\"defaultMode\":\"BYPASS\"}");
        assertEquals(PermissionMode.DEFAULT,
                PermissionConfigLoader.load(dir.resolve("none.json"), project).defaultMode(),
                "项目层 BYPASS 必须被拒绝");

        // 用户层同样拒绝：PermissionMode.BYPASS 的契约是「仅启动参数可进」
        Files.writeString(user, "{\"defaultMode\":\"BYPASS\"}");
        assertEquals(PermissionMode.DEFAULT,
                PermissionConfigLoader.load(user, dir.resolve("none.json")).defaultMode(),
                "用户层 BYPASS 同样必须被拒绝");

        // 被拒绝的项目层 BYPASS 不该顺带吃掉用户层的合法取值
        Files.writeString(user, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        PermissionConfig cfg = PermissionConfigLoader.load(user, project);
        assertEquals(PermissionMode.ACCEPT_EDITS, cfg.defaultMode(),
                "项目层非法值应回落到用户层，而非一路跌到 DEFAULT");
        assertNotEquals(PermissionMode.BYPASS, cfg.defaultMode());
    }
}
