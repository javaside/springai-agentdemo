package io.github.javaside.springai.codetui.agent.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * SubagentCatalog —— 六层目录发现自定义子 agent，与内置 4 个合并（同名后层覆盖）。
 *
 * <p><b>六层（加载/覆盖顺序 = 列表顺序，后者覆盖同名）：</b>
 * <ol>
 *   <li>{@code ~/.claude/agents}（兼容 · 用户）；</li>
 *   <li>{@code ~/.agents/agents}（兼容 · 用户）；</li>
 *   <li>{@code ~/.codetui/agents}（自有 · 用户，个人跨项目复用）；</li>
 *   <li>{@code <root>/.claude/agents}（兼容 · 项目）；</li>
 *   <li>{@code <root>/.agents/agents}（兼容 · 项目）；</li>
 *   <li>{@code <root>/.codetui/agents}（自有 · 项目，随仓库版本化，最高优先级）。</li>
 * </ol>
 * 原则：<b>项目 &gt; 用户，自有 &gt; 兼容</b>——与 {@link io.github.javaside.springai.codetui.agent.skill.SkillCatalog}
 * 同层序同原则（内置 4 个最先入 map，六层任何一层都可覆盖：项目 &gt; 用户 &gt; 内置）。
 *
 * <p><b>为何目录约定对齐技能侧三约定</b>（理由同 {@code SkillCatalog} 类注释）：生态按检测到的
 * agent 定向落目录——Claude Code 的子 agent 落 {@code .claude/agents}（官方既成约定），
 * agents.md 桥接生态落 {@code .agents/agents}；只认自有目录等于让每个用户手抄一遍社区/同事
 * 仓库带来的定义。技能层已为同一理由认了三约定，agent 侧不对齐会造成「技能六层、agent 两层」
 * 两套记忆负担。空目录多扫无害（静默跳过）。
 *
 * <p><b>文件形态是平铺单文件</b>（{@code <层>/<名>.md}）：子 agent 生态没有技能那样的
 * references/scripts 附属物，跟 Claude Code {@code .claude/agents/*.md} 同形，name 以
 * frontmatter 为准、文件名只是发现入口。
 *
 * <p><b>坏文件容错纪律</b>（同 {@code HookConfigLoader}：一个坏配置不该让整个功能不可用）：
 * 单文件失败（IO / 解析抛 / 缺 name / 缺 description）只跳过该文件并记入 {@code warnings}，
 * 绝不炸整体——其余文件与内置 4 个照常可用。缺 description 也跳过是对齐 Claude Code 官方
 * 语义：否则工具 roster 里出现 {@code - foo: } 空描述行，agent 可路由但模型无从选择。
 * 层目录不存在静默跳过（多扫约定目录的代价为零）。
 */
public final class SubagentCatalog {

    /** 自有子 agent 目录名（用户级相对 {@code ~}，项目级相对项目根）。同名时本层覆盖同 scope 的兼容层。 */
    public static final String DIR_NAME = ".codetui/agents";

    /**
     * 兼容层目录名：{@code .claude/agents}（Claude Code 官方既成约定）与
     * {@code .agents/agents}（agents.md 桥接约定）。列表顺序即兼容层内部的加载顺序（后者覆盖同名）。
     */
    static final List<String> COMPAT_DIR_NAMES = List.of(".claude/agents", ".agents/agents");

    private SubagentCatalog() {
    }

    /**
     * 装载结果。
     *
     * @param specs       name → spec 的合并结果（插入序 = 内置 4 个先、六层按层序/文件名字典序后；
     *                    保序是 Task 工具 roster 行序跨 reload 不漂移的根基，故不可换 {@code Map.copyOf}
     *                    ——它不保证迭代序）
     * @param customCount 六层成功装载的文件总数（含覆盖内置的；摘要「自定义文件 N」口径）
     * @param warnings    {@code "<绝对路径>: <原因>"} 列表，坏文件/坏目录各一条
     */
    public record Loaded(Map<String, SubagentSpec> specs, int customCount, List<String> warnings) {
    }

    /**
     * 扫六层并合并内置。用户级三层取自真实 {@code ~}（生产入口，{@code AgentTools} 跨包装配用）。
     *
     * @param projectRoot 项目根目录（项目级三层目录相对它解析）
     */
    public static Loaded load(Path projectRoot) {
        return load(projectRoot, homeDir());
    }

    /**
     * 同 {@link #load(Path)}，但显式指定「home 目录」——包级可见，供测试注入 {@code @TempDir}
     * 充当假 {@code ~}（用户级三层都从它派生），隔离开发机真实用户级定义对计数断言的污染。
     *
     * @param projectRoot 项目根目录
     * @param homeDir     假 home 目录（可为 {@code null}，表示无用户级各层）
     */
    static Loaded load(Path projectRoot, Path homeDir) {
        // LinkedHashMap：插入序即覆盖序（put 后勝ち），内置必须最先入 map——
        // 「先层后内置」会让覆盖失效，层序是本类唯一优先级真相。
        LinkedHashMap<String, SubagentSpec> specs = new LinkedHashMap<>();
        // 内置不走容错：classpath 资源随发布包走，坏即程序缺陷，应在装配期爆炸而非静默降级。
        specs.putAll(SubagentLoader.loadBuiltins());
        List<String> warnings = new ArrayList<>();
        int customCount = 0;
        for (Path dir : layers(projectRoot, homeDir)) {
            if (!Files.isDirectory(dir)) {
                continue;   // 约定目录普遍不存在（多扫三约定的常态），静默跳过
            }
            try (Stream<Path> entries = Files.list(dir)) {
                // .sorted() 按文件名字典序：消除目录迭代序的不确定性，否则同层装载序
                // 随 OS 心情漂移，roster 行序跨 reload 不稳定。
                for (Path file : entries.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .toList()) {
                    try {
                        // URI 必须用 Path.toUri()：手拼 "file:" + path 对含 #/? 的文件名必坏
                        // （URL fragment/query 截断）。parse 内部已把 IOException 包成 RuntimeException。
                        SubagentSpec spec = SubagentLoader.parse(file.toUri().toString());
                        if (spec.name().isBlank()) {
                            warnings.add(file.toAbsolutePath() + ": 缺 name");
                            continue;
                        }
                        if (spec.description().isBlank()) {
                            warnings.add(file.toAbsolutePath() + ": 缺 description");
                            continue;
                        }
                        specs.put(spec.name(), spec);
                        customCount++;
                    } catch (RuntimeException ex) {
                        warnings.add(file.toAbsolutePath() + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                // Files.list 打开目录失败（如权限）：整层记一条警告，不影响其余层。
                warnings.add(dir.toAbsolutePath() + ": " + ex);
            }
        }
        // unmodifiableMap 包 LinkedHashMap 而非 Map.copyOf：后者不保迭代序，roster 依赖插入序。
        return new Loaded(Collections.unmodifiableMap(specs), customCount, List.copyOf(warnings));
    }

    /** 按优先级排出六层；projectRoot/homeDir 为 null 的整块跳过。 */
    private static List<Path> layers(Path projectRoot, Path homeDir) {
        List<Path> layers = new ArrayList<>(6);
        if (homeDir != null) {
            for (String compat : COMPAT_DIR_NAMES) {
                layers.add(homeDir.resolve(compat));
            }
            layers.add(homeDir.resolve(DIR_NAME));
        }
        if (projectRoot != null) {
            for (String compat : COMPAT_DIR_NAMES) {
                layers.add(projectRoot.resolve(compat));
            }
            layers.add(projectRoot.resolve(DIR_NAME));
        }
        return layers;
    }

    private static Path homeDir() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home);
    }
}
