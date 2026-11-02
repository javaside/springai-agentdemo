package io.github.javaside.springai.codetui.agent.subagent;

import java.util.function.Supplier;

/**
 * SubagentReloader —— Task / ParallelTasks 双代理的重载协调者（spec §4.3）：一次 catalog
 * 装载喂双代理 + {@code /reload} 摘要通道。
 *
 * <p><b>为何单次 load</b>：{@code Task} 与 {@code ParallelTasks} 两个代理必须出自<b>同一次</b>
 * {@link SubagentCatalog.Loaded} 快照。若两代理各自取 loader：一是扫两遍盘；二是产生
 * 「Task 认识 x 而 ParallelTasks 不认识」的<b>双快照窗口</b>——目录在两次装载之间变化时，
 * 模型在两个工具里看到互相矛盾的 roster，按 ParallelTasks 描述选中的 agent 会被 Task 语义
 * 拒绝（或反过来），且该窗口随文件数增长而变宽。这也是 {@link ReloadableSubagentTool} 的
 * payload 由外部喂入、而非代理自己调 loader 的原因（spec §4.2）——共享代码不共享结果，
 * 单次性必须由一个持有「load → 双投喂」全过程的协调者承担（评审同时否掉了「经 listener
 * pushInfo」：{@code AgentListener} 根本没有该方法）。
 *
 * <p><b>初始装载时序</b>：构造体第一件事就是 {@link #reload()}——两个代理的 delegate 在
 * <b>装配期</b>就有了全量 specs（内置 + 自定义），不存在「首次 reload 前描述为空」的窗口；
 * 装配 build ChatClient 发生在构造<b>之后</b>，故正常路径无 NPE，{@link #summary()} 也因
 * 构造即首装而<b>永不返回 null</b>（lastLoaded 至少是初始快照）。
 *
 * <p><b>摘要是一行</b>，不附坏文件明细——明细将来可进 {@code /agents} 面板（本期非目标）；
 * K&gt;0 时只把首条警告截短附上，保证「有坏文件」这件事在 /reload 输出里可见。
 */
public final class SubagentReloader {

    private final Supplier<SubagentCatalog.Loaded> catalog;
    private final ReloadableSubagentTool task;
    private final ReloadableSubagentTool parallel;

    /** 最近一次装载结果——volatile 原子替换；summary() 读它。构造即首次 reload，故永不为 null。 */
    private volatile SubagentCatalog.Loaded lastLoaded;

    /**
     * @param catalog  装载来源（生产装配传 {@code () -> SubagentCatalog.load(root)}；supplier 形态
     *                 让本类与文件系统解耦，也使「每次 reload 现调一次」可被测试计数钉死）
     * @param task     Task 工具的代理
     * @param parallel ParallelTasks 工具的代理
     */
    public SubagentReloader(Supplier<SubagentCatalog.Loaded> catalog,
                            ReloadableSubagentTool task, ReloadableSubagentTool parallel) {
        this.catalog = catalog;
        this.task = task;
        this.parallel = parallel;
        // 构造体第一件事：初始装载一并喂两代理。字段就位后立即执行，其余装配动作一概排在其后。
        reload();
    }

    /**
     * 一次 {@code catalog.get()} 的结果喂给两个代理并记住。单次性是本类存在的全部理由
     * （见类 javadoc「为何单次 load」）——两代理各自取 loader 的写法会被
     * SubagentReloaderTest 的精确计数断言（变异下 2 变 4）杀死。
     */
    public void reload() {
        SubagentCatalog.Loaded loaded = catalog.get();
        task.reload(loaded.specs());
        parallel.reload(loaded.specs());
        this.lastLoaded = loaded;
    }

    /**
     * {@code /reload} 摘要行：「子 agent：共 M 个（内置 B · 自定义文件 N，跳过 K 个坏文件，如 …）」。
     * 口径（spec §4.1）：M = specs.size()（与 roster 实际行数严格一致）、N = customCount、
     * K = warnings.size()；K = 0 时省略「跳过」段。内置数 B 按 M − N 推导而非硬编码 4：
     * 每个自定义文件要么新增一行（M+1）要么覆盖内置（M 不变），N 都 +1，故 M − N 恒等于
     * 存活内置行数——硬编码 4 在「自定义覆盖内置」场景会加出不自洽的行数（内置 4 + 自定义 1
     * 却「共 4 个」）。构造即首装，返回值永不为 null。
     */
    public String summary() {
        SubagentCatalog.Loaded loaded = this.lastLoaded;
        int total = loaded.specs().size();
        int custom = loaded.customCount();
        int skipped = loaded.warnings().size();
        StringBuilder sb = new StringBuilder("子 agent：共 ").append(total)
                .append(" 个（内置 ").append(total - custom)
                .append(" · 自定义文件 ").append(custom);
        if (skipped > 0) {
            sb.append("，跳过 ").append(skipped).append(" 个坏文件，如 ")
                    .append(abbreviate(loaded.warnings().get(0)));
        }
        return sb.append("）").toString();
    }

    /** 首条警告截 80 字符：绝对路径 + 原因可能很长，整条塞进一行摘要会挤爆 /reload 输出；截断加省略号保留「还有下文」信号。 */
    private static String abbreviate(String warning) {
        return warning.length() <= 80 ? warning : warning.substring(0, 80) + "…";
    }
}
