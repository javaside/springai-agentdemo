package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;
import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.thinking.ModelThinkingSettings;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import reactor.core.Disposable;

import java.util.List;

/** 提交一次对话，返回可取消句柄。CodingAgent 实现它；骨架期用回显桩实现（返回 null）。 */
public interface SubmitHandler {
    Disposable submit(String text);

    /**
     * 带指定技能提交：发送前先调用该技能工具、把正文注入到 text 前。
     * skillName 为 null 时等价 {@link #submit(String)}。默认实现委托无技能提交，便于桩省略。
     *
     * @param text      用户输入的原文
     * @param skillName {@code /skill} 挂载的技能名；null = 不挂载
     * @return 可用于 Esc 取消的 handle
     */
    default Disposable submit(String text, String skillName) { return submit(text); }

    // ── 模型选择（/model 选择器用；默认空实现，便于回显桩/测试桩省略） ──
    /** 可选模型列表（每条带 provider 归属，见 {@link ProviderModel}）。
     *
     * @return 全部可用模型
     */
    default List<ProviderModel> models() { return List.of(); }

    /** 当前使用的模型 id（裸 id，用于欢迎横幅/视觉判断等不关心 provider 的场合）。
     *
     * @return 模型 id
     */
    default String currentModel() { return ""; }

    /** 当前模型的 provider 专属输入能力；附件闸门必须用它，不能仅凭裸 modelId 判断。 */
    default ModelCapabilities currentModelCapabilities() { return ModelCapabilities.TEXT_ONLY; }

    /** 当前使用模型的 provider id（模型选择器/思考强度显示需要精确区分同名模型）。
     *
     * @return provider id
     */
    default String currentProviderId() { return ""; }

    /** 切换到指定 provider 下的指定模型（对后续回合生效）。
     *
     * @param providerId 目标 provider
     * @param modelId    目标模型
     */
    default void selectModel(String providerId, String modelId) { }

    /** 读取某 provider 下某模型的思考设置与能力；默认 null（回显桩/测试桩）。
     *
     * @param providerId 目标 provider
     * @param modelId    目标模型
     * @return 思考设置与能力；不支持时为 null
     */
    default ModelThinkingSettings thinkingSettings(String providerId, String modelId) { return null; }

    /** 保存某 provider 下某模型的思考设置；返回写盘是否成功。默认 false。
     *
     * @param providerId 目标 provider
     * @param modelId    目标模型
     * @param config     要保存的思考配置；DEFAULT 语义是删记录
     * @return 写盘是否成功
     */
    default boolean saveThinkingSettings(String providerId, String modelId, ThinkingConfig config) { return false; }

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

    /**
     * 启动期还在后台连接的 MCP server 数（状态栏后缀用）。
     *
     * <p><b>刻意不进 busy 闸门</b>：MCP 连接不该拦住用户发消息——那正是把它挪到后台要换来的东西。
     * 这个数只用来解释「为什么现在还看不到 MCP 工具」。
     */
    default int connectingMcpCount() { return 0; }

    // ── 后台子 agent（/tasks 面板与自动送达用；默认空实现，便于回显桩/测试桩省略） ──

    /** 已结束、可送达、尚未消费的后台任务结果（供自动送达判定）。 */
    default List<BackgroundResult> completedBackgroundTasks() { return List.of(); }

    /** 标记某个后台任务的结果已交给模型。返回是否本次真的完成了标记（互斥闸，见注册表）。
     *
     * @param taskId 后台任务 id
     * @return 是否本次真的完成了标记
     */
    default boolean markBackgroundConsumed(String taskId) { return false; }

    /** 终止一个运行中的后台任务（/tasks 面板的 k 键）。
     *
     * @param taskId 后台任务 id
     * @return 是否真的改变了状态；已结束返回 false
     */
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
     * {@code /continue} 用的后台任务摘要——拼进提示词，让模型知道哪些活正在做 / 已经做完。
     *
     * <p><b>为什么由门面出而不是让 UI 读自己的镜像</b>：{@code ConversationState} 里那份
     * {@code BackgroundView} 是<b>显示镜像</b>，注册表才是「唯一并发真相源」。
     * 用一个为渲染而生的副本去构造喂给模型的指令，等于让它承担正确性责任。
     *
     * <p>无需提醒时返回<b>空串</b>，调用方据此保持提示词一个字不变。
     * 默认空串，便于回显桩/测试桩省略。
     */
    default String backgroundDigestForContinue() { return ""; }

    /**
     * 一条可送达的后台任务结果。<b>刻意是 UI 层能直接消费的扁平结构</b>——
     * 不让 UI 去 import agent.background 包的领域类型，接缝两侧各自演进。
     *
     * @param taskId      后台任务 id
     * @param agentName   subagent_type
     * @param description 委派时给的简述
     * @param result      结果正文（已由 {@code TaskResultStore} 限幅）
     * @param ok          true = 正常完成，false = 执行失败
     */
    record BackgroundResult(String taskId, String agentName, String description,
                            String result, boolean ok) { }

    // ── MCP 管理（/mcp 面板用；默认空实现，便于回显桩/测试桩省略） ──
    /** 已安装 MCP server 视图（含禁用项）。 */
    default List<McpRegistry.ServerView> mcpServers() { return List.of(); }

    /** 启用（含连接，阻塞秒级——调用方放后台线程）。null 表示无 MCP 支持。
     *
     * @param name server 逻辑名
     * @return 切换结果；无 MCP 支持时为 null
     */
    default McpRegistry.ToggleResult enableMcp(String name) { return null; }

    /** 禁用（即时完成）。null 表示无 MCP 支持。
     *
     * @param name server 逻辑名
     * @return 切换结果；无 MCP 支持时为 null
     */
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
     *
     * @param rule 要删除的规则（按 equals 匹配，scope 是分量之一故不会摘错层）
     * @return 是否真的删掉了
     */
    default boolean removePermissionRule(PermissionRule rule) { return false; }

    // ── 插话（回合进行中输入的消息，不打断回合、随下一次模型调用送达） ──

    /** 忙时提交一条插话：不打断回合，随下一次模型调用送达。默认无操作（桩）。
     *
     * @param text 插话原文
     */
    default void interject(String text) { }

    /** 尚未送达模型的插话条数（状态栏用）。 */
    default int pendingInterjections() { return 0; }

    /**
     * 取走<b>尚未送达</b>的插话，供回合末兜底出队。
     *
     * <p>刻意不取已送达的那条——它归回合末补历史。两者若共用一个方法，回合末谁先跑到谁拿走，
     * UI 先拿走的话那句话会被当成新回合再发一遍，模型看到两次。
     */
    default List<String> takePendingInterjections() { return List.of(); }

    /**
     * Esc 取消回合时取走<b>全部</b>插话（含已送达的），调用方回填输入框而非丢弃。
     *
     * <p>已送达那条在取消路径上必须交还：取消走 {@code doOnCancel}，补历史的
     * {@code handleComplete} 根本不跑，不还给用户那句话就凭空消失。
     */
    default List<String> takeBackInterjections() { return List.of(); }

    /**
     * 未送达插话的<b>非破坏性</b>快照，供输入框上方的面板每帧读取（形状同排队面板）。
     *
     * <p>与 {@link #takePendingInterjections()} 只差一个字：那个<b>取走</b>，这个只<b>看</b>。
     * 面板接错到取走那个上，每渲染一帧就把队列清空一次。
     */
    default List<String> pendingInterjectionTexts() { return List.of(); }
}
