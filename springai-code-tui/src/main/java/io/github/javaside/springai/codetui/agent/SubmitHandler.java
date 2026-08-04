package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import reactor.core.Disposable;

import java.util.List;

/** 提交一次对话，返回可取消句柄。CodingAgent 实现它；骨架期用回显桩实现（返回 null）。 */
public interface SubmitHandler {
    Disposable submit(String text);

    /**
     * 带指定技能提交：发送前先调用该技能工具、把正文注入到 text 前。
     * skillName 为 null 时等价 {@link #submit(String)}。默认实现委托无技能提交，便于桩省略。
     */
    default Disposable submit(String text, String skillName) { return submit(text); }

    // ── 模型选择（/model 选择器用；默认空实现，便于回显桩/测试桩省略） ──
    /** 可选模型列表。 */
    default List<ModelOption> models() { return List.of(); }

    /** 当前使用的模型 id。 */
    default String currentModel() { return ""; }

    /** 切换到指定模型 id（对后续回合生效）。 */
    default void selectModel(String id) { }

    /** 手动压缩会话历史（/compact）。默认空实现，便于回显桩/测试桩省略。 */
    default void compact() { }

    /** {@code /clear}：切到一个全新空会话（旧会话留盘可 -c 恢复）。默认空实现，便于回显桩/测试桩省略。 */
    default void clearContext() { }

    /** 当前会话上下文用量快照（/context）。默认空快照，便于回显桩/测试桩省略。 */
    default ContextStats contextStats() { return ContextStats.empty(); }

    /** 当前可用技能清单（/skills 展示）。默认空，便于回显桩/测试桩省略。 */
    default List<SkillInfo> skills() { return List.of(); }

    /** 重新扫描技能目录（/reload），使运行中新增/删除的技能生效。默认空实现，便于回显桩/测试桩省略。 */
    default void reloadSkills() { }

    /**
     * 是否仍有在飞子 agent（上一回合被取消后可能仍在跑、且其迟到写入会污染会话）。
     * UI 的 busy 闸门据此把 {@code /continue} 等新回合排队而非立即起跑，避免两回合并发写同一会话。
     * 默认 false，便于回显桩/测试桩省略。
     */
    default boolean hasInFlightSubagents() { return false; }

    // ── 后台子 agent（/tasks 面板与自动送达用；默认空实现，便于回显桩/测试桩省略） ──

    /** 已结束、可送达、尚未消费的后台任务结果（供自动送达判定）。 */
    default List<BackgroundResult> completedBackgroundTasks() { return List.of(); }

    /** 标记某个后台任务的结果已交给模型。返回是否本次真的完成了标记（互斥闸，见注册表）。 */
    default boolean markBackgroundConsumed(String taskId) { return false; }

    /** 终止一个运行中的后台任务（/tasks 面板的 k 键）。 */
    default boolean killBackgroundTask(String taskId) { return false; }

    /**
     * 终止全部后台任务（{@code /clear} 与退出都会先调它）。
     *
     * <p><b>落地端不得在这里关线程池</b>：{@code ThreadPoolExecutor} 一关就是终态、不可复用，
     * 而后台模式只在装配期启用一次——{@code /clear} 上关了池，这个进程此后就再也派不出后台任务了。
     * 正确做法是打断旧线程后<b>重建</b>一个新池，见 {@code SubagentRunner.restartBackground}。
     * 真正的关池是 {@link #shutdownBackground()}，只在退出时走。
     */
    default void killAllBackgroundTasks() { }

    /**
     * 退出时的后台清理：<b>终止全部任务 + 关闭线程池</b>（终态，走有界 2s 收尾）。
     *
     * <p><b>调用方只调这一个，不要先调 {@link #killAllBackgroundTasks()}</b>：那个方法会
     * <b>重建</b>线程池，把在飞任务所在的池换掉；随后这里的 awaitTermination 就等在一个空池上、
     * 0ms 返回，真正在跑的子 agent 一秒宽限都拿不到（若正卡在 Write 中间会被切成半个文件）。
     * 终止任务这件事由落地端在本方法内一并完成。
     *
     * <p>与 {@link #killAllBackgroundTasks()} 的分工：那个是「停掉这批、进程还要继续用」
     * （{@code /clear} 走它，故重建池）；这个是「不会再有下一批了」。
     */
    default void shutdownBackground() { }

    /**
     * 一条可送达的后台任务结果。<b>刻意是 UI 层能直接消费的扁平结构</b>——
     * 不让 UI 去 import agent.background 包的领域类型，接缝两侧各自演进。
     */
    record BackgroundResult(String taskId, String agentName, String description,
                            String result, boolean ok) { }

    // ── MCP 管理（/mcp 面板用；默认空实现，便于回显桩/测试桩省略） ──
    /** 已安装 MCP server 视图（含禁用项）。 */
    default List<McpRegistry.ServerView> mcpServers() { return List.of(); }

    /** 启用（含连接，阻塞秒级——调用方放后台线程）。null 表示无 MCP 支持。 */
    default McpRegistry.ToggleResult enableMcp(String name) { return null; }

    /** 禁用（即时完成）。null 表示无 MCP 支持。 */
    default McpRegistry.ToggleResult disableMcp(String name) { return null; }

    // ── 权限管理（/permissions 与 Shift+Tab 用；默认空实现，便于回显桩/测试桩省略） ──
    /** 当前权限模式（状态栏与面板显示）。默认非空，状态栏不必判 null。 */
    default PermissionMode permissionMode() { return PermissionMode.DEFAULT; }

    /** 循环到下一个模式（Shift+Tab），返回<b>实际</b>生效的新模式（未获授权时可能原地不动）。 */
    default PermissionMode cyclePermissionMode() { return permissionMode(); }

    /** 当前生效的全部规则（{@code /permissions} 面板展示）。 */
    default List<PermissionRule> permissionRules() { return List.of(); }

    /**
     * 删一条规则（{@code /permissions} 面板用）。默认 false，便于回显桩/测试桩省略。
     *
     * <p>落地端须<b>同时</b>更新落盘文件与内存规则表——只改一处会让面板与实际判定分家。
     */
    default boolean removePermissionRule(PermissionRule rule) { return false; }
}
