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
 * SkillCatalog 六层（用户/项目 × codetui/claude/agents）装载与去重语义测试。
 *
 * <p>用 {@code load(projectRoot, homeDir)} 包级重载注入两个 {@code @TempDir}（后者充当假 {@code ~}），
 * 把真实 {@code ~/.codetui} / {@code ~/.claude} / {@code ~/.agents} 全部隔离在外——否则开发机
 * 真装过技能（如 {@code ~/.claude/skills}，skills CLI 常见落点）会污染断言。
 */
class SkillCatalogTest {

    /** 六层都空：无技能、不构建工具。 */
    @Test
    void noSkills_returnsEmptyAndNullTool(@TempDir Path root, @TempDir Path homeDir) {
        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertTrue(loaded.skills().isEmpty(), "无技能时清单应为空");
        assertNull(loaded.tool(), "无技能时不应构建 Skill 工具");
    }

    /** 用户级技能应被加载，来源标「用户」，并构建出名为 Skill 的工具。 */
    @Test
    void userSkill_isAdded(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(homeDir, "git-commit-message", "按 Conventional Commits 规范撰写提交信息。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertNotNull(loaded.tool(), "有技能时必须构建出工具");
        assertEquals("Skill", loaded.tool().getToolDefinition().name(), "工具名固定为 Skill");
        SkillInfo git = find(loaded.skills(), "git-commit-message");
        assertEquals("用户", git.source());
        assertEquals("按 Conventional Commits 规范撰写提交信息。", git.description());
    }

    /** 项目级技能应被加载，来源标「项目」。 */
    @Test
    void projectSkill_isAdded(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(root, "api-review", "审查 REST API 设计是否符合团队规范。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        SkillInfo api = find(loaded.skills(), "api-review");
        assertEquals("项目", api.source());
    }

    /** 同名时项目级覆盖用户级：只保留一条，来源变为「项目」（去重 + 后勝ち）。 */
    @Test
    void sameName_projectOverridesUser(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(homeDir, "git-commit-message", "用户级默认规范。");
        writeSkill(root, "git-commit-message", "项目自定义规范。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        long count = loaded.skills().stream().filter(s -> s.name().equals("git-commit-message")).count();
        assertEquals(1, count, "同名技能只应保留一条（去重）");

        SkillInfo git = find(loaded.skills(), "git-commit-message");
        assertEquals("项目", git.source(), "项目级应覆盖用户级");
        assertEquals("项目自定义规范。", git.description());
    }

    /** 用户级兼容目录（skills CLI 常见落点）：~/.claude/skills 与 ~/.agents/skills 都应被加载并标来源。 */
    @Test
    void compatUserDirs_areLoaded(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(homeDir, ".claude/skills", "design-taste", "设计品味技能。");
        writeSkill(homeDir, ".agents/skills", "seam-craft", "镜像仓库技能。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertEquals("用户·claude", find(loaded.skills(), "design-taste").source());
        assertEquals("用户·agents", find(loaded.skills(), "seam-craft").source());
    }

    /** 项目级兼容目录：<root>/.claude/skills 与 <root>/.agents/skills 同样加载。 */
    @Test
    void compatProjectDirs_areLoaded(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(root, ".claude/skills", "repo-claude-skill", "仓库内 claude 目录技能。");
        writeSkill(root, ".agents/skills", "repo-agents-skill", "仓库内 agents 目录技能。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertEquals("项目·claude", find(loaded.skills(), "repo-claude-skill").source());
        assertEquals("项目·agents", find(loaded.skills(), "repo-agents-skill").source());
    }

    /**
     * 镜像仓库场景（如 hyperframes 把 .claude/skills 与 .agents/skills 维护成字节级镜像）：
     * 同名技能装两处只留一条——防重复靠的是按 name 去重，不是少读目录。
     */
    @Test
    void mirror_sameNameInClaudeAndAgents_dedupedToOne(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(root, ".claude/skills", "motion-graphics", "镜像技能（claude 侧）。");
        writeSkill(root, ".agents/skills", "motion-graphics", "镜像技能（agents 侧）。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        long count = loaded.skills().stream().filter(s -> s.name().equals("motion-graphics")).count();
        assertEquals(1, count, "镜像两侧同名只应保留一条");
    }

    /** 同名时 .codetui 层覆盖同 scope 的兼容层：code-tui 自己的目录始终是最终话语权。 */
    @Test
    void codetuiOverridesCompat_sameName(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(homeDir, ".claude/skills", "chrome-devtools", "兼容层版本。");
        writeSkill(homeDir, SkillCatalog.DIR_NAME, "chrome-devtools", "codetui 个人版本。");
        writeSkill(root, ".agents/skills", "writing-plans", "项目兼容层版本。");
        writeSkill(root, SkillCatalog.DIR_NAME, "writing-plans", "项目 codetui 版本。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertEquals("用户", find(loaded.skills(), "chrome-devtools").source(), "用户级 .codetui 应覆盖 ~/.claude");
        assertEquals("项目", find(loaded.skills(), "writing-plans").source(), "项目级 .codetui 应覆盖 .agents");
    }

    /**
     * 块标量修复（`>` 折叠）：hyperframes 生态的 SKILL.md 用 YAML 折叠块标量写多行 description
     * （本机 2026-10-09 实测 55 个技能里 9 个这么写）。库 MarkdownParser 逐行按第一个冒号切
     * key/value、不认块标量，会把 description 解析成字面 {@code ">"}，续行里带冒号的还会变成
     * 幽灵 key（如 {@code Mandatory entry point}）。SkillCatalog 须重读文件折叠出真实描述。
     */
    @Test
    void foldedScalarDescription_isRecovered(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeRawSkill(homeDir, "hyperframes", """
                ---
                name: hyperframes
                description: >
                  Mandatory entry point: read this first for any request to make, create, edit,
                  animate, or render a video, animation, or motion graphic.
                ---

                # hyperframes

                正文。
                """);

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertEquals("Mandatory entry point: read this first for any request to make, create, edit,"
                        + " animate, or render a video, animation, or motion graphic.",
                find(loaded.skills(), "hyperframes").description(),
                "`>` 折叠块标量应折叠成一行真实描述，而不是裸指示符 \">\"");
    }

    /**
     * 块标量修复（`|` 字面 + `>-` 折叠截断变体）：`|` 保留换行、`>-` 空格折叠，
     * 指示符后缀的截断符号（-）不影响补解析。
     */
    @Test
    void literalAndChompedScalarDescriptions_areRecovered(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeRawSkill(root, "captions", """
                ---
                name: captions
                description: |-
                  第一行
                  第二行
                ---
                正文。
                """);
        writeRawSkill(root, "audio", """
                ---
                name: audio
                description: >-
                  Use when audio needs to be mixed:
                ---
                正文。
                """);

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        // |- 字面块：库会解析成裸 "|"；补解析应保留逐行结构
        assertEquals("第一行\n第二行", find(loaded.skills(), "captions").description(), "`|` 字面块应保留换行");
        // >- 折叠块：库会解析成裸 ">-"；首续行以冒号结尾（hyperframes-audio 实际形态），不得丢字
        assertEquals("Use when audio needs to be mixed:", find(loaded.skills(), "audio").description(),
                "`>-` 应折叠成一行且保留结尾冒号");
    }

    /** 单行 description 的健康技能在修复逻辑引入后不受影响（回归护栏）。 */
    @Test
    void singleLineDescription_untouched(@TempDir Path root, @TempDir Path homeDir) throws Exception {
        writeSkill(homeDir, "git-commit-message", "按 Conventional Commits 规范撰写提交信息。");

        SkillCatalog.Loaded loaded = SkillCatalog.load(root, homeDir);

        assertEquals("按 Conventional Commits 规范撰写提交信息。",
                find(loaded.skills(), "git-commit-message").description(), "单行描述应原样保留");
    }

    /** 在 {@code <base>/<dirName>/<name>/SKILL.md} 写一个技能。 */
    private static void writeSkill(Path base, String name, String description) throws Exception {
        writeSkill(base, SkillCatalog.DIR_NAME, name, description);
    }

    /**
     * 直接写整份 SKILL.md 原文（不走 writeSkill 的单行 description 模板），
     * 用于构造块标量等真实生态写法。
     */
    private static void writeRawSkill(Path base, String name, String md) throws Exception {
        Path dir = base.resolve(SkillCatalog.DIR_NAME).resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), md);
    }

    /** 在指定层目录（相对 base）写一个技能——兼容层与 codetui 层共用。 */
    private static void writeSkill(Path base, String dirName, String name, String description) throws Exception {
        Path dir = base.resolve(dirName).resolve(name);
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
