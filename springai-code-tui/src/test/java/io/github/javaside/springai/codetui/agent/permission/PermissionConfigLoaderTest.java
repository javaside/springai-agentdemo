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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode(),
                "项目层只能收紧：ACCEPT_EDITS 比用户层的 DEFAULT 宽，不采纳");
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
        // 项目层用收窄形态：Read(*) 作为项目层通配放行现已被拒（见 projectLayerCannotGrantBlanketAllow），
        // 而本例要钉的是「一层坏了另一层照常」，不是规则形态
        Files.writeString(project, """
                {"allow":["Read(src/**)"]}""");

        PermissionConfig cfg = PermissionConfigLoader.load(user, project);
        assertEquals(1, cfg.rules().size());
        assertTrue(hasRule(cfg.rules(), "Read(src/**)", PermissionBehavior.ALLOW));
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
    @DisplayName("配置文件不得声明 BYPASS——clone 来的仓库不该让你启动即裸奔（键盘切进去则是允许的）")
    void configCannotDeclareBypass(@TempDir Path dir) throws Exception {
        // ⚠ 本条与「Shift+Tab 四档平权」不矛盾，别一起删：
        // 键盘切档时用户在场、有意图，且切完状态栏行首常驻红色「⚠ 跳过权限检查」；
        // 而 .codetui/permissions.json 是 clone 仓库时一起带过来的，进目录一启动，
        // 模型的第一次工具调用就零检查跑了——用户一个键都没按、屏幕上什么都没变。
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

    // ── 项目层不得放宽（Task 5R）────────────────────────────────────────
    //
    // 贯穿原则：~/.codetui/ 是用户自己的配置，<root>/.codetui/ 是仓库带来的配置。
    // clone 一个仓库不得让 agent 变得更宽松——项目层只能收紧。

    @Test
    @DisplayName("Critical：项目层的通配放行等价于 BYPASS，必须拒绝")
    void projectLayerCannotGrantBlanketAllow(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("project.json");

        // 全工具全放行：一条 ALLOW 命中在决策顺序第 5 步短路掉 BYPASS 在第 6 步做的事
        Files.writeString(project, "{\"allow\":[\"*\"]}");
        assertTrue(PermissionConfigLoader.load(null, project).rules().isEmpty(),
                "项目层的 \"*\" 必须被拒绝");

        // 整工具放行：pattern == null 即「该工具全部调用」
        Files.writeString(project, "{\"allow\":[\"Bash(*)\",\"Write(*)\"]}");
        assertTrue(PermissionConfigLoader.load(null, project).rules().isEmpty(),
                "整工具放行同样不可由仓库配置声明");

        // 裸工具名是 Bash(*) 的另一种写法，同样拒绝
        Files.writeString(project, "{\"allow\":[\"Bash\"]}");
        assertTrue(PermissionConfigLoader.load(null, project).rules().isEmpty(),
                "裸工具名等价于 Bash(*)，不能从这条路绕过去");

        // 收窄型的项目级放行仍须接受——这正是「允许，永久」写入的形态
        Files.writeString(project, "{\"allow\":[\"Bash(mvn test:*)\"]}");
        assertEquals(1, PermissionConfigLoader.load(null, project).rules().size(),
                "团队共享的收窄型 allow 必须照常生效");
    }

    @Test
    @DisplayName("Critical 续：项目层的全域路径 glob 也是通配放行的一种写法")
    void projectLayerCannotGrantUniversalGlobAllow(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("project.json");
        // 这几个 glob 实测命中 /etc/passwd、~/.ssh/id_rsa、~/.aws/credentials——
        // 与 Write(*) 的杀伤面完全一致，只是换了个写法躲开 pattern==null 判据
        for (String pat : new String[]{"**", "/**", "**/*", "/**/*", "*/**", "**/**", "~/**"}) {
            Files.writeString(project, "{\"allow\":[\"Write(" + pat + ")\"]}");
            assertTrue(PermissionConfigLoader.load(null, project).rules().isEmpty(),
                    "项目层 Write(" + pat + ") 覆盖整个文件系统，必须拒绝");
        }
        // 真正收窄的路径 glob 照常接受
        Files.writeString(project, "{\"allow\":[\"Write(src/**)\",\"Read(**/*.java)\"]}");
        assertEquals(2, PermissionConfigLoader.load(null, project).rules().size(),
                "收窄到子目录/扩展名的 glob 是正常用法");
    }

    @Test
    @DisplayName("对照：用户自己的配置不受通配放行限制")
    void userLayerMayGrantBlanketAllow(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"allow\":[\"Bash(*)\"]}");
        assertEquals(1, PermissionConfigLoader.load(user, null).rules().size(),
                "~/.codetui/ 是用户自己的机器，限制只针对仓库带来的配置");

        Files.writeString(user, "{\"allow\":[\"*\",\"Write(**)\"]}");
        assertEquals(2, PermissionConfigLoader.load(user, null).rules().size());
    }

    @Test
    @DisplayName("项目层的 deny / ask 不受限——它们只会收紧")
    void projectLayerMayStillDenyAndAskBroadly(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("project.json");
        Files.writeString(project, "{\"deny\":[\"Bash(*)\",\"*\"],\"ask\":[\"Write(**)\"]}");
        assertEquals(3, PermissionConfigLoader.load(null, project).rules().size(),
                "通配的 deny/ask 是收紧方向，照常生效");
    }

    @Test
    @DisplayName("Important：项目层不能把用户选的 DEFAULT 抬成 ACCEPT_EDITS")
    void projectLayerCannotRaiseDefaultMode(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{\"defaultMode\":\"DEFAULT\"}");
        Files.writeString(project, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        assertEquals(PermissionMode.DEFAULT,
                PermissionConfigLoader.load(user, project).defaultMode(),
                "项目层只能收紧，不能把用户选的 DEFAULT 抬成 ACCEPT_EDITS");
    }

    @Test
    @DisplayName("Important 续：用户层没写时，项目层也不能抬——缺省即 DEFAULT")
    void projectLayerCannotRaiseAbsentUserMode(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("project.json");
        Files.writeString(project, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        assertEquals(PermissionMode.DEFAULT,
                PermissionConfigLoader.load(dir.resolve("none.json"), project).defaultMode(),
                "没有用户层文件 = 用户取默认的 DEFAULT，项目层同样抬不动");
    }

    @Test
    @DisplayName("收紧方向允许：项目层 DEFAULT 压过用户层 ACCEPT_EDITS")
    void projectLayerMayLowerDefaultMode(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Path project = dir.resolve("project.json");
        Files.writeString(user, "{\"defaultMode\":\"ACCEPT_EDITS\"}");
        Files.writeString(project, "{\"defaultMode\":\"DEFAULT\"}");
        assertEquals(PermissionMode.DEFAULT,
                PermissionConfigLoader.load(user, project).defaultMode(), "收紧方向允许");
    }

    @Test
    @DisplayName("Important：重复键让人读到一条不生效的 deny，整文件按非法处理")
    void duplicateKeysRejectWholeFile(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user,
                "{\"deny\":[\"Bash(rm -rf /:*)\"],\"allow\":[\"Read(*)\"],\"deny\":[]}");
        assertTrue(PermissionConfigLoader.load(user, null).rules().isEmpty(),
                "重复键会让人读到一条实际不生效的 deny，整文件按非法处理");
    }

    @Test
    @DisplayName("Minor：未识别的顶层字段记 WARN 而非静默——拼错的 deny 是隐形缺失的禁令")
    void unknownTopLevelKeysAreNotSilentlyDropped(@TempDir Path dir) throws Exception {
        Path user = dir.resolve("user.json");
        Files.writeString(user, "{\"Deny\":[\"Bash(rm -rf /:*)\"],\"denies\":[],\"allow\":[\"Read(*)\"]}");
        // 拼错的字段本身仍旧无效（不猜用户意图），但合法字段照常生效
        PermissionConfig cfg = PermissionConfigLoader.load(user, null);
        assertEquals(1, cfg.rules().size());
        assertTrue(hasRule(cfg.rules(), "Read(*)", PermissionBehavior.ALLOW));
        assertFalse(cfg.rules().stream().anyMatch(r -> r.behavior() == PermissionBehavior.DENY),
                "大小写拼错的 Deny 不该被当成 deny 生效——但必须已记 WARN");
    }

    @Test
    @DisplayName("Minor：load((Path) null) 不 NPE")
    void loadWithNullRootDegrades() {
        PermissionConfig cfg = PermissionConfigLoader.load((Path) null);
        assertNotNull(cfg);
        assertEquals(PermissionMode.DEFAULT, cfg.defaultMode());
    }
}
