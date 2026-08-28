package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.javaside.springai.codetui.agent.skill.ReloadableSkillTool;
import io.github.javaside.springai.codetui.agent.skill.SkillCatalog;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/**
 * CodingAgent 的 {@code /reload} 接线：{@link SubmitHandler#reloadSkills()} 委托给 {@link ReloadableSkillTool}，
 * 且 {@link SubmitHandler#skills()} 走可重载源实时取——即视图 {@code /reload → onSubmit.reloadSkills()} 后
 * {@code /skills} 能反映运行中新增的技能。
 */
class CodingAgentSkillReloadTest {

    /** 生产构造挂一个 forTest 可重载源：初始空 → 运行中写入技能 → reloadSkills() 后 skills() 现取到它。 */
    @Test
    void reloadSkills_makesNewlyAddedSkillVisible(@TempDir Path root, @TempDir Path userDir) throws IOException {
        ReloadableSkillTool reloadable = ReloadableSkillTool.forTest(root, userDir);
        CodingAgent agent = new CodingAgent(null, Map.of(), new StubListener(), "s", new AtomicLong(),
                null, null, null, List.of(), null, null, reloadable);

        assertTrue(agent.skills().isEmpty(), "初始应无技能");

        writeSkill(root, "api-review", "审查 REST API 设计。");
        agent.reloadSkills();

        assertTrue(agent.skills().stream().anyMatch(s -> s.name().equals("api-review")),
                "reloadSkills() 后 skills() 应现取到运行中新增的技能");
    }

    /** 无可重载源（测试桩路径）：reloadSkills() 是安全空操作，skills() 退回固定清单。 */
    @Test
    void reloadSkills_withoutReloadableSource_isNoop() {
        CodingAgent agent = new CodingAgent(null, new StubListener(), "s", new AtomicLong(),
                null, null, null, List.of(new SkillInfo("fixed", "固定技能", "项目")));

        agent.reloadSkills();   // 不应抛

        assertFalse(agent.skills().isEmpty(), "无可重载源时 skills() 退回固定清单");
        assertTrue(agent.skills().stream().anyMatch(s -> s.name().equals("fixed")));
    }

    private static void writeSkill(Path base, String name, String description) throws IOException {
        Path dir = base.resolve(SkillCatalog.DIR_NAME).resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # %s

                正文。
                """.formatted(name, description, name));
    }
}
