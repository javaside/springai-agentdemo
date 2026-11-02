package io.github.javaside.springai.codetui.agent.subagent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.function.Function;

/**
 * ReloadableSubagentTool —— {@code Task} / {@code ParallelTasks} 的<b>实例恒定代理</b>
 * {@link ToolCallback}，支持运行期热重载自定义子 agent（spec §4.2）。
 *
 * <p><b>为何是代理</b>：{@link SubagentTool} 把「可用子 agent 清单」快照进两处——工具描述里的
 * roster（{@code DESCRIPTION_TEMPLATE.formatted(roster, ...)}）与路由 map（specs → Dispatcher
 * 闭包），都是 <b>build 时</b>固定的；一旦经 {@code .defaultTools(...)} 烘焙进 ChatClient 便无法
 * 再增删。与 {@code ReloadableSkillTool} 同款论证：已核实 Spring AI 的
 * {@code DefaultToolCallingManager.resolveToolDefinitions} <b>每次请求</b>都逐个现调
 * {@code getToolDefinition()}，故本代理把全部方法转发给「当前」delegate，{@link #reload} 重建
 * delegate 原子替换后，下一次请求模型即见新 roster、新路由——无需重建任何 ChatClient。
 *
 * <p><b>payload 由外部喂入</b>（reload 收 specs、不自己调 loader）：{@code Task} 与
 * {@code ParallelTasks} 两个代理必须出自<b>同一次</b> {@code SubagentCatalog.load} 快照——
 * 各自调 loader 会扫两遍盘，且存在「Task 认识 x 而 ParallelTasks 不认识」的双快照窗口；
 * 单次装载、双投喂由 T3 的 {@code SubagentReloader} 统一承担。
 *
 * <p><b>初始装载</b>：构造不建初始 delegate（null）——由 {@code SubagentReloader} 构造时的首次
 * {@link #reload} 喂入，装配序保证 ChatClient build 发生在其后，正常路径无 NPE 窗口；未装载先
 * 转发则抛 {@link IllegalStateException}（防御性，提示装配序被破坏）。
 *
 * <p><b>并发</b>：{@code /reload} 由 UI 线程调用、委派调用可能正在飞行——delegate 用
 * {@code volatile} 字段原子替换，转发方每次只读一次引用；在飞调用持有的旧 delegate 引用继续
 * 用完即弃（旧 delegate 不可变：其 specs map 构建完成后无人再写，路由闭包只读）。这与 MCP
 * reload 需要 busy 守卫（{@code CodeTuiView.reloadMcpConfig}：回合中摘工具/关连接会撞在飞
 * 调用）不同——代理换快照不关连接、不摘在飞工具，旧 delegate 自然存活到调用结束，故无需守卫。
 *
 * <p>与 {@code ReloadableSkillTool} 的差异：<b>无空兜底</b>（specs 永远 ≥ 内置 4 个，factory
 * 不可能产出空 roster）、无 description 修复链。
 */
public final class ReloadableSubagentTool implements ToolCallback {

    private final Function<Map<String, SubagentSpec>, ToolCallback> factory;
    /** 当前 delegate——volatile 原子替换；初始 null，由 Reloader 构造时的首次 reload() 喂入。 */
    private volatile ToolCallback delegate;

    /**
     * @param factory 装配时以固定材料（models roster、Dispatcher、后台派发器、Task 还是
     *                ParallelTasks 的 create）闭包出的重建函数；每次调用产出<b>完整重建</b>的
     *                工具（新描述 roster + 新路由 map）。public 供 AgentTools 跨包装配。
     */
    public ReloadableSubagentTool(Function<Map<String, SubagentSpec>, ToolCallback> factory) {
        this.factory = factory;
    }

    /**
     * 用 specs 重建 delegate 并原子替换。factory 产出完整重建的工具——「只重建描述、复用旧
     * 路由 map」的半截实现会留下模型看得见却路由不到的 agent（ReloadableSubagentToolTest
     * reloadSwapsRoutingNotJustDescription 专杀此缺陷）。
     */
    public void reload(Map<String, SubagentSpec> specs) {
        this.delegate = factory.apply(specs);
    }

    /** 每次转发只读一次 volatile 引用，保证单次调用内 delegate 一致（reload 竞争下至多用旧）。 */
    private ToolCallback current() {
        ToolCallback d = this.delegate;
        if (d == null) {
            throw new IllegalStateException(
                    "未完成初始装载：delegate 为 null——初始 delegate 应由 SubagentReloader 构造时的首次"
                            + " reload() 喂入，且装配序保证 ChatClient build 在其后；走到这里说明装配序被破坏。");
        }
        return d;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return current().getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return current().getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return current().call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return current().call(toolInput, toolContext);
    }
}
