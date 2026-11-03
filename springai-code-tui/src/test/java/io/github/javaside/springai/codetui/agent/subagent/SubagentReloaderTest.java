package io.github.javaside.springai.codetui.agent.subagent;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubagentReloader：单次 catalogue 装载喂双代理 + /reload 摘要口径（spec §6.3）。
 *
 * <p>核心保证：<b>一次 {@code reload()} 只调一次 catalog、Task 与 ParallelTasks 出自同一次
 * 快照</b>。「两代理各自调 loader」的变异会扫两遍盘、且存在「Task 认识 x 而
 * ParallelTasks 不认识」的双快照窗口——计数断言（精确值，非至少）专杀此缺陷。
 *
 * <p>catalog 用 {@link AtomicReference}（构造后换值模拟目录变化）+ {@link AtomicInteger}
 * 计数包装成 {@link Supplier}；两个代理是真代理（{@link ReloadableSubagentTool}），factory
 * 写成 lambda {@code specs -> SubagentTool.create(specs, stub)}——工厂消费<b>喂入的</b> specs，
 * 不是闭包固定 specs（否则测不到「reload 的 payload 真的流到了 factory」）。
 */
class SubagentReloaderTest {

    /** 路由证明桩（同 ReloadableSubagentToolTest）：返回 spec.name()。 */
    private static final SubagentTool.Dispatcher STUB = (spec, prompt, desc, turnId) -> spec.name();

    /** 批量桩：本测试只读描述不调 call，返回空清单即可满足 BatchDispatcher 签名。 */
    private static final SubagentTool.BatchDispatcher BATCH_STUB = (dispatches, parentTurnId) -> List.of();

    /** 手造最小 spec（形态对齐 ReloadableSubagentToolTest：name/description/systemPrompt + 空工具清单）。 */
    private static SubagentSpec spec(String name) {
        return new SubagentSpec(name, "does " + name + " things", "sys", List.of(), List.of(), null, List.of());
    }

    /** LinkedHashMap 保序（对齐生产 Loaded 的迭代序契约），unmodifiableMap 包裹对齐生产形态。 */
    private static Map<String, SubagentSpec> specs(String... names) {
        Map<String, SubagentSpec> m = new LinkedHashMap<>();
        for (String name : names) {
            m.put(name, spec(name));
        }
        return Collections.unmodifiableMap(m);
    }

    private static SubagentCatalog.Loaded loaded(Map<String, SubagentSpec> specs, int customCount,
                                                 List<String> warnings) {
        return new SubagentCatalog.Loaded(specs, customCount, warnings, deriveInfos(specs));
    }

    /**
     * 第四参 infos 在助手<b>内部</b>从 specs 现场派生（保序；source 统一测试标签——层标签语义
     * 由 SubagentCatalogTest 钉死，这里只关心 agents() 跟随快照换新）。机械传 {@code List.of()}
     * 会让「构造后 agents() 含初始 agent」断言恒假——spec §3.2 明文警告的假绿灯，故派生而非硬塞。
     */
    private static List<AgentInfo> deriveInfos(Map<String, SubagentSpec> specs) {
        return specs.values().stream()
                .map(s -> new AgentInfo(s.name(), s.description(), "测试"))
                .toList();
    }

    /**
     * 杀虫关键（spec §6.6 变异 B 的常驻版）：一次 reload 只允许一次 catalog 装载。
     * 精确断言计数——变异「两代理各自调 catalog」下构造即 +2、手动 reload 再 +2 变 4，必红。
     * 同时验证快照真的流到了两个 factory：换 beta 后双代理描述都含 beta、都不含 alpha。
     */
    @Test
    void reloadLoadsCatalogExactlyOnceAndFeedsBoth() {
        AtomicReference<SubagentCatalog.Loaded> catalog =
                new AtomicReference<>(loaded(specs("alpha"), 1, List.of()));
        AtomicInteger loadCount = new AtomicInteger();
        Supplier<SubagentCatalog.Loaded> countingCatalog = () -> {
            loadCount.incrementAndGet();
            return catalog.get();
        };
        ReloadableSubagentTool task =
                new ReloadableSubagentTool(specs -> SubagentTool.create(specs, STUB));
        ReloadableSubagentTool parallel =
                new ReloadableSubagentTool(specs -> SubagentTool.createParallel(specs, BATCH_STUB));

        SubagentReloader reloader = new SubagentReloader(countingCatalog, task, parallel);
        assertEquals(1, loadCount.get(), "构造即首次装载，应恰好 load 一次");
        assertEquals(List.of("alpha"), reloader.agents().stream().map(AgentInfo::name).toList(),
                "构造即首装，agents() 应含初始快照的 alpha");

        catalog.set(loaded(specs("beta"), 1, List.of()));
        reloader.reload();
        assertEquals(2, loadCount.get(),
                "一次 reload 只许一次 catalog 装载——两代理各自调 catalog 的变异下会变 4，必红");

        String taskDesc = task.getToolDefinition().description();
        assertTrue(taskDesc.contains("- beta: does beta things"),
                "Task 代理描述应含新快照的 beta：\n" + taskDesc);
        assertFalse(taskDesc.contains("alpha"), "Task 代理描述不应残留旧快照的 alpha：\n" + taskDesc);
        String parallelDesc = parallel.getToolDefinition().description();
        assertTrue(parallelDesc.contains("- beta: does beta things"),
                "ParallelTasks 代理描述应含同一次快照的 beta：\n" + parallelDesc);
        assertFalse(parallelDesc.contains("alpha"),
                "ParallelTasks 代理描述不应残留旧快照的 alpha：\n" + parallelDesc);
        assertEquals(List.of("beta"), reloader.agents().stream().map(AgentInfo::name).toList(),
                "agents() 应跟随 reload 换新——恰为 beta，不残留 alpha");
    }

    /**
     * spec §4.3 初始装载：构造体第一件事就是 reload()——构造后<b>不调任何方法</b>，
     * 两代理的 delegate 即已就位（描述含初始 specs 的 agent）。若构造不首装，代理转发
     * null delegate 会抛 IllegalStateException，此用例必红。
     */
    @Test
    void constructionPerformsInitialLoad() {
        ReloadableSubagentTool task =
                new ReloadableSubagentTool(specs -> SubagentTool.create(specs, STUB));
        ReloadableSubagentTool parallel =
                new ReloadableSubagentTool(specs -> SubagentTool.createParallel(specs, BATCH_STUB));

        SubagentReloader reloader =
                new SubagentReloader(() -> loaded(specs("alpha"), 1, List.of()), task, parallel);

        assertTrue(task.getToolDefinition().description().contains("- alpha: does alpha things"),
                "构造后未调任何方法，Task 代理描述即应含初始装载的 alpha");
        assertTrue(parallel.getToolDefinition().description().contains("- alpha: does alpha things"),
                "构造后未调任何方法，ParallelTasks 代理描述即应含初始装载的 alpha");
        assertTrue(reloader.agents().stream().anyMatch(i -> i.name().equals("alpha")),
                "构造后未调任何方法，agents() 即应含初始装载的 alpha（非空断言的杀恒空版）");
    }

    /** 摘要口径（spec §4.1）：M=specs.size()、N=customCount、K=warnings.size() 各归各位。 */
    @Test
    void summaryReflectsLoaded() {
        // M=6（现实里 4 内置 + 2 自定义新增）、N=2、K=1
        SubagentReloader reloader = new SubagentReloader(
                () -> loaded(specs("a", "b", "c", "d", "e", "f"), 2, List.of("/tmp/broken.md: 缺 name")),
                new ReloadableSubagentTool(specs -> SubagentTool.create(specs, STUB)),
                new ReloadableSubagentTool(specs -> SubagentTool.createParallel(specs, BATCH_STUB)));

        String s = reloader.summary();
        assertTrue(s.contains("共 6 个"), "M 应取 specs.size()：\n" + s);
        assertTrue(s.contains("内置 4"), "内置数应与 M、N 自洽（6-2）：\n" + s);
        assertTrue(s.contains("自定义文件 2"), "N 应取 customCount：\n" + s);
        assertTrue(s.contains("跳过 1"), "K 应取 warnings.size()：\n" + s);
    }
}
