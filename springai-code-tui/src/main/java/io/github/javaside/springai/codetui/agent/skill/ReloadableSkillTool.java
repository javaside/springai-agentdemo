package io.github.javaside.springai.codetui.agent.skill;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * ReloadableSkillTool —— 名为 {@code Skill} 的<b>稳定代理</b> {@link ToolCallback}，支持运行期热加载技能。
 *
 * <p><b>为何是代理</b>：{@link SkillCatalog} 构建出的 {@code SkillsTool} 把「可用技能列表」快照进
 * 工具描述（{@code <available_skills>…</available_skills>}）与可执行的技能 map——都是 <b>build 时</b>固定的。
 * 而 {@code SkillsTool} 一旦经 {@code .defaultTools(...)} 烘焙进 {@link org.springframework.ai.chat.client.ChatClient}
 * 便无法再增删。若想「运行中新增 {@code SKILL.md} 不重启也能被模型看到」，只能靠一个<b>实例恒定、内部可换</b>的代理：
 * <ul>
 *   <li>已核实 Spring AI 的 {@code DefaultToolCallingManager.resolveToolDefinitions} <b>每次请求</b>都 stream
 *       已注册的 {@code ToolCallback} 实例、逐个现调 {@link #getToolDefinition()}；</li>
 *   <li>故本代理每次把 {@code getToolDefinition()} / {@code call()} 转发给「当前」重载出的 delegate，
 *       {@link #reload()} 后下一次请求模型即看到最新技能——无需重建任何 ChatClient。</li>
 * </ul>
 *
 * <p><b>始终注册（支持从零）</b>：即便启动时零技能，本代理也会被注册（delegate 退化为 {@link #emptyDelegate()}
 * 的空 {@code Skill} 工具），这样 {@code /reload} 才能把「第一个新增技能」热加载出来。代价是空技能时上下文里
 * 多一个 {@code <available_skills>} 为空的 {@code Skill} 工具——很轻微，换来的是从零起步也能热加载。
 * 这与 {@link SkillCatalog}「零技能不注册工具」的取舍不同：那是给一次性装配用的，本代理是给可重载场景用的。
 *
 * <p><b>并发</b>：{@link #reload()} 由 UI 线程在 {@code /reload} 命令或打开选择器时调用；delegate 与清单用
 * {@code volatile} 快照原子替换，转发方读到的始终是一致的一对（列表 + delegate）。纯本地文件 IO。
 */
public final class ReloadableSkillTool implements ToolCallback {

    /** delegate 与其对应技能清单的一致快照——volatile 整体替换，避免读到「新列表 + 旧 delegate」的错配。 */
    private record Snapshot(List<SkillInfo> infos, ToolCallback delegate) {
    }

    private final Supplier<SkillCatalog.Loaded> loader;   // 注入的装载源（生产=真实两层；测试=临时目录两层）
    private volatile Snapshot snapshot;

    /** 生产构造：项目根 + 真实用户级目录（{@code ~/.codetui/skills}）。构造即做一次 {@link #reload()}。 */
    public ReloadableSkillTool(Path projectRoot) {
        this(() -> SkillCatalog.load(projectRoot));
    }

    /**
     * 测试构造：显式两层临时目录，隔离真实 {@code ~}。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ReloadableSkillTool forTest(Path projectRoot, Path userDir) {
        return new ReloadableSkillTool(() -> SkillCatalog.load(projectRoot, userDir));
    }

    private ReloadableSkillTool(Supplier<SkillCatalog.Loaded> loader) {
        this.loader = loader;
        reload();
    }

    /** 重扫两层技能目录，原子替换 delegate 与清单。有技能→真 {@code SkillsTool}；零技能→空 {@code Skill} 工具。 */
    public void reload() {
        SkillCatalog.Loaded loaded = loader.get();
        ToolCallback delegate = (loaded.tool() != null) ? loaded.tool() : emptyDelegate();
        this.snapshot = new Snapshot(loaded.skills(), delegate);
    }

    /** 当前可用技能清单（{@code /skills} 展示 / {@code /skill} 选择器）；随最近一次 {@link #reload()} 变化。 */
    public List<SkillInfo> skills() {
        return snapshot.infos();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return snapshot.delegate().getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return snapshot.delegate().getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return snapshot.delegate().call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return snapshot.delegate().call(toolInput, toolContext);
    }

    // ── 空技能兜底 delegate ───────────────────────────────────────────────

    /** 空 {@code Skill} 工具的入参（与真 {@code SkillsTool} 一致的 {@code command} 字段，供模型/手动路径调用形态统一）。 */
    private record EmptyInput(String command) {
    }

    /**
     * 零技能时的兜底 delegate：名 {@code Skill}、{@code <available_skills>} 为空、调用返回可读提示。
     * 真 {@code SkillsTool.build()} 在零技能时会抛 {@code At least one skill must be configured}，故这里自造一个。
     */
    private static ToolCallback emptyDelegate() {
        return FunctionToolCallback.builder("Skill",
                        (EmptyInput in, ToolContext ctx) ->
                                "当前没有可用技能（技能目录为空）。请在 ~/.codetui/skills/<名>/SKILL.md "
                                        + "或 <项目>/.codetui/skills/<名>/SKILL.md 放入技能后，用 /reload 重新加载。")
                .description("""
                        Execute a skill within the main conversation.

                        <available_skills>
                        (当前无可用技能)
                        </available_skills>
                        """)
                .inputType(EmptyInput.class)
                .build();
    }
}
