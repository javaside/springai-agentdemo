package io.github.javaside.springai.codetui.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkillCatalog 两层（用户 / 项目）装载与去重语义测试。
 *
 * <p>用 {@code load(projectRoot, userDir)} 包级重载注入两个 {@code @TempDir}，把真实 {@code ~/.codetui/skills}
 * 隔离在外——否则开发机若真有用户级技能会污染断言。
 */
class SkillCatalogTest {

    /** 两层都空：无技能、不构建工具。 */
    @Test
    void noSkills_returnsEmptyAndNullTool(@TempDir Path root, @TempDir Path userDir) {
        SkillCatalog.Loaded loaded = SkillCatalog.load(root, userDir);

        assertTrue(loaded.skills().isEmpty(), "无技能时清单应为空");
        assertNull(loaded.tool(), "无技能时不应构建 Skill 工具");
    }

    /** 用户级技能应被加载，来源标「用户」，并构建出名为 Skill 的工具。 */
    @Test
    void userSkill_isAdded(@TempDir Path root, @TempDir Path userDir) throws Exception {
        writeSkill(userDir, "git-commit-message", "按 Conventional Commits 规范撰写提交信息。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, userDir);

        assertNotNull(loaded.tool(), "有技能时必须构建出工具");
        assertEquals("Skill", loaded.tool().getToolDefinition().name(), "工具名固定为 Skill");
        SkillInfo git = find(loaded.skills(), "git-commit-message");
        assertEquals("用户", git.source());
        assertEquals("按 Conventional Commits 规范撰写提交信息。", git.description());
    }

    /** 项目级技能应被加载，来源标「项目」。 */
    @Test
    void projectSkill_isAdded(@TempDir Path root, @TempDir Path userDir) throws Exception {
        writeSkill(root, "api-review", "审查 REST API 设计是否符合团队规范。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, userDir);

        SkillInfo api = find(loaded.skills(), "api-review");
        assertEquals("项目", api.source());
    }

    /** 同名时项目级覆盖用户级：只保留一条，来源变为「项目」（去重 + 后勝ち）。 */
    @Test
    void sameName_projectOverridesUser(@TempDir Path root, @TempDir Path userDir) throws Exception {
        writeSkill(userDir, "git-commit-message", "用户级默认规范。");
        writeSkill(root, "git-commit-message", "项目自定义规范。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, userDir);

        long count = loaded.skills().stream().filter(s -> s.name().equals("git-commit-message")).count();
        assertEquals(1, count, "同名技能只应保留一条（去重）");

        SkillInfo git = find(loaded.skills(), "git-commit-message");
        assertEquals("项目", git.source(), "项目级应覆盖用户级");
        assertEquals("项目自定义规范。", git.description());
    }

    /** 在 {@code <base>/.codetui/skills/<name>/SKILL.md} 写一个技能。 */
    private static void writeSkill(Path base, String name, String description) throws Exception {
        Path dir = base.resolve(SkillCatalog.DIR_NAME).resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # %s

                这是测试技能正文。
                """.formatted(name, description, name));
    }

    private static SkillInfo find(List<SkillInfo> skills, String name) {
        Optional<SkillInfo> hit = skills.stream().filter(s -> s.name().equals(name)).findFirst();
        assertTrue(hit.isPresent(), "应包含技能: " + name);
        return hit.get();
    }
}
