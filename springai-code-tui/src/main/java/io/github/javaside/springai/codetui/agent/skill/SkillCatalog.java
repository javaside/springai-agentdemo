package io.github.javaside.springai.codetui.agent.skill;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * SkillCatalog —— 解析两层技能来源、去重、并构建名为 {@code Skill} 的工具。
 *
 * <p><b>两层（加载/覆盖顺序 = 用户 → 项目，后者覆盖同名）：</b>
 * <ul>
 *   <li><b>用户</b>：{@code ~/.codetui/skills/<名>/SKILL.md}（个人跨项目复用）；</li>
 *   <li><b>项目</b>：{@code <root>/.codetui/skills/<名>/SKILL.md}（随仓库版本化，最高优先级）。</li>
 * </ul>
 *
 * <p><b>为何不带「内置」层</b>：技能应由使用者按需提供（放文件即安装），不预置任何默认技能，
 * 避免把某种规范强加给所有项目；空目录/无技能时不注册 {@code Skill} 工具（见下）。
 *
 * <p><b>为何按「层」加载</b>：已核实 {@link Skills#loadResource} 会对每个 Resource 调 {@code getFile()}
 * 当成<b>目录</b>交给 {@code loadDirectory} 遍历——它期望「技能根目录」而非单个 {@code SKILL.md}。
 * 故这里两层都直接用 {@link Skills#loadDirectory}（传层目录），Builder 亦用 {@code addSkillsDirectory}。
 *
 * <p><b>去重（后勝ち）</b>：两层各自加载出的技能按 name 汇入一个 {@link LinkedHashMap}，项目级覆盖同名的用户级——
 * {@code /skills} 清单里同名只剩一条、来源标最高优先级层。
 *
 * <p><b>无技能则不注册工具</b>：两层都空时 {@code tool()} 返回 {@code null}，调用方据此跳过注册，
 * 避免空 {@code <available_skills>} 污染上下文。目录不存在的层静默跳过；某层加载抛错只跳过该层。纯本地 IO。
 */
public final class SkillCatalog {

    /** 用户级与项目级共用的相对目录名（用户级相对 {@code ~}，项目级相对项目根）。 */
    public static final String DIR_NAME = ".codetui/skills";

    private SkillCatalog() {
    }

    /**
     * 装载结果：给 UI 的清单 + 可空的 {@code Skill} 工具。
     *
     * @param skills 去重后的技能元数据（供 {@code /skills} 展示）；无技能时为空列表
     * @param tool   名为 {@code Skill} 的 {@link ToolCallback}；<b>无技能时为 {@code null}</b>，
     *               调用方据此决定是否注册（空技能不注册，避免污染上下文）
     */
    public record Loaded(List<SkillInfo> skills, ToolCallback tool) {
    }

    /**
     * 解析两层、按 name 去重、构建 {@code Skill} 工具。用户级目录取自 {@code ~/.codetui/skills}。
     *
     * @param projectRoot 项目根目录（项目级技能目录 = {@code <root>/.codetui/skills}）
     */
    public static Loaded load(Path projectRoot) {
        return load(projectRoot, userSkillsDir());
    }

    /**
     * 同 {@link #load(Path)}，但显式指定用户级目录——包级可见，供测试注入临时目录以隔离真实 {@code ~}。
     *
     * @param projectRoot 项目根目录
     * @param userDir     用户级技能目录（可为 {@code null}，表示无用户级）
     */
    static Loaded load(Path projectRoot, Path userDir) {
        Path projectDir = projectRoot.resolve(DIR_NAME);

        // 顺序即优先级：用户 → 项目（LinkedHashMap.put 后勝ち去重）。
        LinkedHashMap<String, SkillInfo> byName = new LinkedHashMap<>();
        boolean user = collect(loadDirectory(userDir), "用户", byName);
        boolean project = collect(loadDirectory(projectDir), "项目", byName);

        List<SkillInfo> infos = List.copyOf(byName.values());
        if (infos.isEmpty()) {
            return new Loaded(List.of(), null);   // 无技能：不注册工具
        }

        // 只把「确有技能」的层喂给 Builder（build() 要求至少一个技能，且避免加空目录）。
        SkillsTool.Builder builder = SkillsTool.builder();
        if (user) {
            builder.addSkillsDirectory(userDir.toString());
        }
        if (project) {
            builder.addSkillsDirectory(projectDir.toString());
        }
        return new Loaded(infos, builder.build());
    }

    /** 把一层加载出的技能按 name 汇入 map（后者覆盖同名）。返回该层是否贡献了至少一个技能。 */
    private static boolean collect(List<SkillsTool.Skill> layer, String source,
                                   LinkedHashMap<String, SkillInfo> out) {
        boolean any = false;
        for (SkillsTool.Skill sk : layer) {
            Object d = sk.frontMatter() == null ? null : sk.frontMatter().get("description");
            out.put(sk.name(), new SkillInfo(sk.name(), d == null ? "" : d.toString(), source));
            any = true;
        }
        return any;
    }

    /** 文件系统层：目录存在才遍历（{@code <dir>/<名>/SKILL.md}）。 */
    private static List<SkillsTool.Skill> loadDirectory(Path dir) {
        try {
            return (dir != null && Files.isDirectory(dir)) ? Skills.loadDirectory(dir.toString()) : List.of();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static Path userSkillsDir() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home).resolve(DIR_NAME);
    }
}
