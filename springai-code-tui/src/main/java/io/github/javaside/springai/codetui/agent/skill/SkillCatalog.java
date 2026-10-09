package io.github.javaside.springai.codetui.agent.skill;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkillCatalog —— 解析六层技能来源、去重、并构建名为 {@code Skill} 的工具。
 *
 * <p><b>六层（加载/覆盖顺序 = 列表顺序，后者覆盖同名）：</b>
 * <ol>
 *   <li>{@code ~/.claude/skills}（兼容 · 用户）；</li>
 *   <li>{@code ~/.agents/skills}（兼容 · 用户）；</li>
 *   <li>{@code ~/.codetui/skills}（自有 · 用户，个人跨项目复用）；</li>
 *   <li>{@code <root>/.claude/skills}（兼容 · 项目）；</li>
 *   <li>{@code <root>/.agents/skills}（兼容 · 项目）；</li>
 *   <li>{@code <root>/.codetui/skills}（自有 · 项目，随仓库版本化，最高优先级）。</li>
 * </ol>
 * 原则：<b>项目 &gt; 用户，自有 &gt; 兼容</b>（同 scope 内 {@code .codetui} 覆盖兼容层）。
 *
 * <p><b>为何要兼容 {@code .claude/skills} 与 {@code .agents/skills} 两约定</b>：开源技能项目的
 * 安装命令（{@code npx skills add …}，skills.sh 生态）<b>按检测到的 agent 定向落目录</b>，并不总是
 * 两个都写——本机 2026-09-08 实测：装完只剩 {@code ~/.claude/skills} 有技能文件，{@code ~/.agents/}
 * 下仅锁文件无 skills 目录；上游 v1.5.13 起全局 {@code --copy} 才同时写 claude 与 universal 两个
 * store，版本行为在漂移。项目级同理分裂：Claude Code 落 {@code .claude/skills}，Cursor/Codex/
 * Copilot/Gemini CLI/OpenCode 等落 {@code .agents/skills}。只认一个目录就会在「另一半机器」上漏技能。
 *
 * <p><b>两侧都装不会重复</b>：镜像仓库（如 hyperframes 把 {@code .claude/skills} 与
 * {@code .agents/skills} 维护成字节级镜像）的同一技能 frontmatter name 相同——本类按 name 汇入
 * {@link LinkedHashMap}（后勝ち），库层 {@code SkillsTool.toSkillsMap} 亦为 name→skill 的 HashMap，
 * 双重去重后清单与工具里各只剩一条。<b>防重复靠去重，不靠少读目录。</b>
 *
 * <p><b>为何按「层」精确扫目录</b>：已核实 {@link Skills#loadResource} 会对每个 Resource 调
 * {@code getFile()} 当成<b>目录</b>交给 {@code loadDirectory} 递归遍历——它期望「技能根目录」而非单个
 * {@code SKILL.md}，且递归会把层根之外的杂物也捞进来。故这里每层都传<b>精确的</b>
 * {@code <base>/<目录名>}，Builder 亦用 {@code addSkillsDirectory}。
 *
 * <p><b>无技能则不注册工具</b>：六层全空时 {@code tool()} 返回 {@code null}，调用方据此跳过注册，
 * 避免空 {@code <available_skills>} 污染上下文。目录不存在的层静默跳过；某层加载抛错只跳过该层。纯本地 IO。
 */
public final class SkillCatalog {

    /** 自有技能目录名（用户级相对 {@code ~}，项目级相对项目根）。同名时本层覆盖同 scope 的兼容层。 */
    public static final String DIR_NAME = ".codetui/skills";

    /**
     * 兼容层目录名（skills.sh 生态两大约定）：{@code .claude/skills}（Claude Code）与
     * {@code .agents/skills}（agents.md 通用标准）。列表顺序即兼容层内部的加载顺序（后者覆盖同名）。
     */
    static final List<String> COMPAT_DIR_NAMES = List.of(".claude/skills", ".agents/skills");

    private SkillCatalog() {
    }

    /**
     * 装载结果：给 UI 的清单 + 可空的 {@code Skill} 工具。
     *
     * @param skills 去重后的技能元数据（供 {@code /skills} 展示）；无技能时为空列表
     * @param tool   名为 {@code Skill} 的 {@link ToolCallback}；<b>无技能时为 {@code null}</b>，
     *               调用方据此决定是否注册（空技能不注册，避免污染上下文）
     * @param descriptionFixes name → 块标量修复后的真实 description（<b>仅含被修复过的技能</b>）。
     *                         库 MarkdownParser 不认 YAML 块标量，{@code description: >} 会被解析成
     *                         裸 {@code ">"} 且工具描述里混入幽灵元素（见 {@link SkillFrontmatterRepair}）；
     *                         消费方（{@link ReloadableSkillTool}）据此修复模型侧工具描述，清单侧
     *                         已在装载时直接修正
     */
    public record Loaded(List<SkillInfo> skills, ToolCallback tool, Map<String, String> descriptionFixes) {
    }

    /** 一层 = 精确的技能根目录 + {@code /skills} 清单里的来源标签。 */
    private record Layer(Path dir, String source) {
    }

    /**
     * 解析六层、按 name 去重、构建 {@code Skill} 工具。用户级各层取自真实 {@code ~}。
     *
     * @param projectRoot 项目根目录（项目级三层目录相对它解析）
     */
    public static Loaded load(Path projectRoot) {
        return load(projectRoot, homeDir());
    }

    /**
     * 同 {@link #load(Path)}，但显式指定「home 目录」——包级可见，供测试注入 {@code @TempDir} 充当假
     * {@code ~}（用户级 {@code .codetui} / {@code .claude} / {@code .agents} 三层都从它派生），
     * 隔离真实用户级技能。
     *
     * @param projectRoot 项目根目录
     * @param homeDir     假 home 目录（可为 {@code null}，表示无用户级各层）
     */
    static Loaded load(Path projectRoot, Path homeDir) {
        // 顺序即优先级（LinkedHashMap.put 后勝ち去重）：用户兼容 → 用户自有 → 项目兼容 → 项目自有。
        LinkedHashMap<String, SkillInfo> byName = new LinkedHashMap<>();
        // 块标量修复记录：与 byName 同序覆盖（镜像两侧同写法时后层覆盖前层，保持一致）。
        LinkedHashMap<String, String> fixes = new LinkedHashMap<>();
        // 只把「确有技能」的层喂给 Builder（build() 要求至少一个技能，且避免加空目录）。
        List<Path> contributed = new ArrayList<>();
        for (Layer layer : layers(projectRoot, homeDir)) {
            if (collect(loadDirectory(layer.dir()), layer.source(), byName, fixes)) {
                contributed.add(layer.dir());
            }
        }

        List<SkillInfo> infos = List.copyOf(byName.values());
        if (infos.isEmpty()) {
            return new Loaded(List.of(), null, Map.of());   // 无技能：不注册工具
        }
        SkillsTool.Builder builder = SkillsTool.builder();
        contributed.forEach(dir -> builder.addSkillsDirectory(dir.toString()));
        return new Loaded(infos, builder.build(), Map.copyOf(fixes));
    }

    /** 按优先级排出六层；projectRoot/homeDir 为 null 的整块跳过。 */
    private static List<Layer> layers(Path projectRoot, Path homeDir) {
        List<Layer> layers = new ArrayList<>(6);
        if (homeDir != null) {
            layers.add(new Layer(homeDir.resolve(".claude/skills"), "用户·claude"));
            layers.add(new Layer(homeDir.resolve(".agents/skills"), "用户·agents"));
            layers.add(new Layer(homeDir.resolve(DIR_NAME), "用户"));
        }
        if (projectRoot != null) {
            layers.add(new Layer(projectRoot.resolve(".claude/skills"), "项目·claude"));
            layers.add(new Layer(projectRoot.resolve(".agents/skills"), "项目·agents"));
            layers.add(new Layer(projectRoot.resolve(DIR_NAME), "项目"));
        }
        return layers;
    }

    /**
     * 把一层加载出的技能按 name 汇入 map（后者覆盖同名）。返回该层是否贡献了至少一个技能。
     *
     * <p>块标量修复：库把 {@code description: >} 解析成裸指示符——检测到即重读 SKILL.md 折叠出
     * 真实描述（见 {@link SkillFrontmatterRepair}），清单用修复值、修复记录汇入 {@code fixes}
     * 供代理修模型侧工具描述；健康技能则从 fixes 摘除（同名的后层健康版本覆盖前层损坏版本时，
     * 工具描述里已无损坏块，残留修复记录反而不必要）。
     */
    private static boolean collect(List<SkillsTool.Skill> layer, String source,
                                   LinkedHashMap<String, SkillInfo> out, LinkedHashMap<String, String> fixes) {
        boolean any = false;
        for (SkillsTool.Skill sk : layer) {
            Object d = sk.frontMatter() == null ? null : sk.frontMatter().get("description");
            String description = d == null ? "" : d.toString();
            if (SkillFrontmatterRepair.isBlockIndicator(description)) {
                String folded = SkillFrontmatterRepair.foldDescription(Path.of(sk.basePath()));
                if (folded != null) {
                    fixes.put(sk.name(), folded);
                    description = folded;
                }
            } else {
                fixes.remove(sk.name());
            }
            out.put(sk.name(), new SkillInfo(sk.name(), description, source));
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

    private static Path homeDir() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home);
    }
}
