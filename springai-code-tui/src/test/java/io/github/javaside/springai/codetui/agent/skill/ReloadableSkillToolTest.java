package io.github.javaside.springai.codetui.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReloadableSkillTool：稳定代理 + {@code /reload} 触发的运行期热加载语义。
 *
 * <p>核心保证：<b>同一个 ToolCallback 实例</b>（名恒为 {@code Skill}）在 {@code reload()} 后
 * {@code getToolDefinition()} 现取到最新技能列表——正因每次请求 Spring AI 都重调 getToolDefinition()，
 * 故无需重建 ChatClient，运行中新增/删除 {@code SKILL.md} 经 {@code reload()} 即对模型可见。
 *
 * <p>用 {@link ReloadableSkillTool#forTest} 注入两个 {@code @TempDir} 隔离真实 {@code ~/.codetui/skills}。
 */
class ReloadableSkillToolTest {

    /** 从零起步：两层皆空时代理仍在（名 Skill、清单空、call 返回无技能提示），以支持「从零 /reload 出第一个技能」。 */
    @Test
    void emptyAtStart_toolStillPresent(@TempDir Path root, @TempDir Path userDir) {
        ReloadableSkillTool tool = ReloadableSkillTool.forTest(root, userDir);

        assertEquals("Skill", tool.getToolDefinition().name(), "空技能时工具仍注册、名恒为 Skill");
        assertTrue(tool.skills().isEmpty(), "空技能时清单为空");
        assertNotNull(tool.call("{\"command\":\"nope\"}"), "空技能时 call 应返回无技能提示而非抛错");
    }

    /** 运行期新增技能 + reload：同一实例的 description 与 skills() 现取到新技能，call 能执行到它。 */
    @Test
    void addSkillThenReload_becomesVisible(@TempDir Path root, @TempDir Path userDir) throws IOException {
        ReloadableSkillTool tool = ReloadableSkillTool.forTest(root, userDir);
        assertFalse(tool.getToolDefinition().description().contains("api-review"), "reload 前不应含新技能");

        writeSkill(root, "api-review", "审查 REST API 设计。");
        tool.reload();

        assertTrue(tool.getToolDefinition().description().contains("api-review"),
                "reload 后工具描述（<available_skills>）应含新技能名——模型据此才能调用");
        assertTrue(tool.skills().stream().anyMatch(s -> s.name().equals("api-review")), "skills() 应含新技能");
        String body = tool.call("{\"command\":\"api-review\"}");
        assertTrue(body.contains("测试技能正文"), "call 应执行到新技能、返回其正文");
    }

    /** 运行期删除技能 + reload：清单回空，但工具实例仍在、名恒为 Skill（不会因清空而消失）。 */
    @Test
    void removeSkillThenReload_backToEmptyButToolRemains(@TempDir Path root, @TempDir Path userDir) throws IOException {
        writeSkill(root, "api-review", "审查 REST API 设计。");
        ReloadableSkillTool tool = ReloadableSkillTool.forTest(root, userDir);
        assertFalse(tool.skills().isEmpty(), "初始应有一个技能");

        deleteRecursively(root.resolve(SkillCatalog.DIR_NAME).resolve("api-review"));
        tool.reload();

        assertTrue(tool.skills().isEmpty(), "删除后 reload 清单应回空");
        assertEquals("Skill", tool.getToolDefinition().name(), "清空后工具仍在、名恒为 Skill");
    }

    /** 在 {@code <base>/.codetui/skills/<name>/SKILL.md} 写一个技能（正文含可断言的标记文本）。 */
    private static void writeSkill(Path base, String name, String description) throws IOException {
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

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
