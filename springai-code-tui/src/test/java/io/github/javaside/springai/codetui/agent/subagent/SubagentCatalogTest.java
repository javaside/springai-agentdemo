package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubagentCatalog 六层（用户/项目 × claude/agents/codetui）发现、同名覆盖与坏文件容错测试。
 *
 * <p>用 {@code load(projectRoot, homeDir)} 包级重载注入两个 {@code @TempDir}（后者充当假 {@code ~}），
 * 把真实 {@code ~/.codetui/agents} 等用户级目录隔离在外——否则开发机残留的自定义定义会污染
 * 计数断言（理由同 {@code SkillCatalogTest} 类注释）。
 */
class SubagentCatalogTest {

    /** 内置 4 个的 name 集合（SubagentLoaderTest 已钉），供「内置存活」断言复用。 */
    private static final Set<String> BUILTIN_NAMES = Set.of("general-purpose", "explore", "plan", "bash");

    /** 六层各放一个合法 .md：6 个自定义全进 specs，与内置 4 个共存，无警告。 */
    @Test
    void sixLayersAllDiscovered(@TempDir Path root, @TempDir Path home) throws Exception {
        subagentFile(home, ".claude/agents", "u-claude", "U-CLAUDE-BODY");
        subagentFile(home, ".agents/agents", "u-agents", "U-AGENTS-BODY");
        subagentFile(home, ".codetui/agents", "u-codetui", "U-CODETUI-BODY");
        subagentFile(root, ".claude/agents", "p-claude", "P-CLAUDE-BODY");
        subagentFile(root, ".agents/agents", "p-agents", "P-AGENTS-BODY");
        subagentFile(root, ".codetui/agents", "p-codetui", "P-CODETUI-BODY");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertEquals(10, loaded.specs().size(), "6 自定义 + 4 内置");
        assertEquals(6, loaded.customCount(), "六层各一个文件，计数 N=6");
        assertTrue(loaded.warnings().isEmpty(), "全部合法，不应有警告");
        for (String name : List.of("u-claude", "u-agents", "u-codetui", "p-claude", "p-agents", "p-codetui")) {
            assertTrue(loaded.specs().containsKey(name), "缺少六层发现的 agent: " + name);
        }
        assertEquals(BUILTIN_NAMES.size(),
                loaded.specs().keySet().stream().filter(BUILTIN_NAMES::contains).count(),
                "内置 4 个应原样存活");
    }

    /** 项目自有层的同名定义覆盖内置 explore：正文换成项目版本，总数不变（覆盖不新增）。 */
    @Test
    void projectLayerOverridesBuiltin(@TempDir Path root, @TempDir Path home) throws Exception {
        subagentFile(root, ".codetui/agents", "explore", "OVERRIDDEN-BODY");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertTrue(loaded.specs().get("explore").systemPrompt().contains("OVERRIDDEN-BODY"),
                "项目层应覆盖内置 explore 的系统提示");
        assertEquals(4, loaded.specs().size(), "覆盖是替换而非新增");
        assertEquals(1, loaded.customCount(), "覆盖内置的文件也计入 N（spec 摘要口径）");
    }

    /** 同名跨层：项目 .claude/agents 胜用户 .codetui/agents（项目 > 用户，即使前者是兼容层）。 */
    @Test
    void projectBeatsUserSameName(@TempDir Path root, @TempDir Path home) throws Exception {
        subagentFile(home, ".codetui/agents", "x", "USER-BODY");
        subagentFile(root, ".claude/agents", "x", "PROJECT-BODY");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertTrue(loaded.specs().get("x").systemPrompt().contains("PROJECT-BODY"), "项目层应胜出");
        assertFalse(loaded.specs().get("x").systemPrompt().contains("USER-BODY"),
                "取到的不应还是用户层版本（覆盖失效的变异在此必红）");
        assertEquals(5, loaded.specs().size());
    }

    /** 坏文件（缺 name）：跳过并记警告，同层好文件与内置 4 个不受影响。 */
    @Test
    void missingNameSkippedWithWarning(@TempDir Path root, @TempDir Path home) throws Exception {
        Path dir = root.resolve(".codetui/agents");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("noname.md"), "---\ndescription: d\n---\nBODY-NO-NAME");
        subagentFile(root, ".codetui/agents", "good", "GOOD-BODY");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertEquals(1, loaded.warnings().size(), "坏文件只产生一条警告");
        assertTrue(loaded.warnings().get(0).contains("noname.md"), "警告应含文件名: " + loaded.warnings());
        assertEquals(5, loaded.specs().size(), "跳过后 = 内置 4 + good");
        assertTrue(loaded.specs().containsKey("good"), "同层好文件应存活");
        assertTrue(loaded.specs().keySet().containsAll(BUILTIN_NAMES), "内置 4 个不受坏文件影响");
        assertEquals(1, loaded.customCount());
    }

    /** 有 name 无 description：同样跳过进警告（对齐 Claude Code 语义，否则 roster 出现空描述行）。 */
    @Test
    void missingDescriptionSkipped(@TempDir Path root, @TempDir Path home) throws Exception {
        Path dir = root.resolve(".agents/agents");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("nodesc.md"), "---\nname: nodesc\n---\nBODY-NO-DESC");
        subagentFile(root, ".codetui/agents", "good", "GOOD-BODY");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertTrue(loaded.warnings().size() == 1
                        && loaded.warnings().get(0).contains("nodesc.md"),
                "应跳过并记警告: " + loaded.warnings());
        assertFalse(loaded.specs().containsKey("nodesc"), "缺 description 的不应进 specs");
        assertEquals(5, loaded.specs().size());
        assertEquals(1, loaded.customCount());
    }

    /** 两个根目录全空：只剩内置 4 个，零自定义、零警告。 */
    @Test
    void emptyRootYieldsBuiltinsOnly(@TempDir Path root, @TempDir Path home) {
        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        assertEquals(BUILTIN_NAMES, loaded.specs().keySet(), "恰为内置 4 个");
        assertEquals(0, loaded.customCount());
        assertTrue(loaded.warnings().isEmpty());
    }

    /**
     * 同层多文件按文件名字典序装载：specs.keySet()（LinkedHashMap 保插入序）中，
     * 4 个内置之后 a.md 的 alpha 先于 b.md 的 bravo。排序消除目录迭代序的不确定性
     * （roster 行序跨 reload 不漂移的根基）；目录迭代序不受 OS 控制，若此钉因实现
     * 未排序但碰巧同序而偶发通过，属 plan 预告的尽力而为，变异（去掉 .sorted()）
     * 仍会在多文件机器序下命中。
     */
    @Test
    void sameLayerDeterministicOrder(@TempDir Path root, @TempDir Path home) throws Exception {
        subagentFile(root, ".codetui/agents", "bravo", "B");
        subagentFile(root, ".codetui/agents", "alpha", "A");

        SubagentCatalog.Loaded loaded = SubagentCatalog.load(root, home);

        List<String> keys = List.copyOf(loaded.specs().keySet());
        int alpha = keys.indexOf("alpha");
        int bravo = keys.indexOf("bravo");
        assertTrue(alpha >= 4 && bravo >= 4, "自定义应在 4 个内置之后: " + keys);
        assertTrue(alpha < bravo, "同层应按文件名字典序（a.md 先于 b.md）: " + keys);
    }

    /**
     * infos 投影（spec §4.1）：六层各一 .md → infos 序 = 内置 4（source=「内置」）先、
     * 六层按层序后，各条 source 与 {@code SkillCatalog} 层标签逐字同口径；再放项目
     * {@code .codetui/agents/explore.md} 覆盖内置 → 该条<b>换条目</b>（source/描述跟随胜者）
     * 但<b>位置保持</b>（LinkedHashMap 同名 put 不挪位）。两段装载后各附一次 keySet 等序
     * 断言——锁死「只写 specs 忘写 infos」的将来漂移；第二段同时锁「覆盖换条目但位置保持」这半边。
     */
    @Test
    void infosCarrySourceAndFollowOverride(@TempDir Path root, @TempDir Path home) throws Exception {
        subagentFile(home, ".claude/agents", "u-claude", "U-CLAUDE-BODY");
        subagentFile(home, ".agents/agents", "u-agents", "U-AGENTS-BODY");
        subagentFile(home, ".codetui/agents", "u-codetui", "U-CODETUI-BODY");
        subagentFile(root, ".claude/agents", "p-claude", "P-CLAUDE-BODY");
        subagentFile(root, ".agents/agents", "p-agents", "P-AGENTS-BODY");
        subagentFile(root, ".codetui/agents", "p-codetui", "P-CODETUI-BODY");

        SubagentCatalog.Loaded first = SubagentCatalog.load(root, home);

        // 内置 4 按 BUILTIN_URIS 插入序（description 不硬编码，取装载结果本身）+ 六层按层序。
        List<AgentInfo> expected = new ArrayList<>();
        for (String builtin : List.of("general-purpose", "explore", "plan", "bash")) {
            expected.add(new AgentInfo(builtin, first.specs().get(builtin).description(), "内置"));
        }
        // 六个自定义条目的 description 都是 subagentFile 写死的 "d"（探明区别于内置的长描述）。
        expected.add(new AgentInfo("u-claude", "d", "用户·claude"));
        expected.add(new AgentInfo("u-agents", "d", "用户·agents"));
        expected.add(new AgentInfo("u-codetui", "d", "用户"));
        expected.add(new AgentInfo("p-claude", "d", "项目·claude"));
        expected.add(new AgentInfo("p-agents", "d", "项目·agents"));
        expected.add(new AgentInfo("p-codetui", "d", "项目"));
        assertEquals(expected, first.infos(), "infos 应 = 内置 4 + 六层按层序，source 跟随所在层");
        assertEquals(List.copyOf(first.specs().keySet()),
                first.infos().stream().map(AgentInfo::name).toList(),
                "infos 与 specs 必须同 key 同序（spec §4.1 原文断言）");

        // 第二段：项目自有层覆盖内置 explore。
        subagentFile(root, ".codetui/agents", "explore", "OVERRIDDEN-BODY");
        SubagentCatalog.Loaded second = SubagentCatalog.load(root, home);

        AgentInfo explore = second.infos().stream()
                .filter(i -> i.name().equals("explore")).findFirst().orElseThrow();
        assertEquals("项目", explore.source(), "同名覆盖后 source 应跟随胜者（项目层）");
        assertEquals("d", explore.description(), "覆盖应是整条换成项目层条目，而非只改标签");
        expected.set(1, new AgentInfo("explore", "d", "项目"));   // explore 固定位次 1：整表重断也锁住「位置保持」
        assertEquals(expected, second.infos(), "除 explore 换条目外，其余条目与序应原样保持");
        assertEquals(List.copyOf(second.specs().keySet()),
                second.infos().stream().map(AgentInfo::name).toList(),
                "覆盖后 infos 仍须与 specs 同 key 同序");
    }

    /** 在 base/<layerDir> 下写 <name>.md：frontmatter name/description + 一行正文（plan T1 约定的最小合法形态）。 */
    private static void subagentFile(Path base, String layerDir, String name, String body) throws Exception {
        Path dir = base.resolve(layerDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"),
                "---\nname: %s\ndescription: d\n---\n%s".formatted(name, body));
    }
}
