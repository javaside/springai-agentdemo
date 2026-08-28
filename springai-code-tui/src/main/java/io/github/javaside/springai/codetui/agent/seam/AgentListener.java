package io.github.javaside.springai.codetui.agent.seam;

import java.util.List;

/**
 * CodingAgent → UI 的唯一接缝。只用纯 Java 类型（不泄漏任何 Spring AI 类型）。
 * 每个方法都带 turnId，供 UI 过滤「已取消回合」的迟到事件。
 */
public interface AgentListener {
    void onTurnStarted(long turnId);                 // submit 顶部同步调用
    void onUserMessage(long turnId, String text);
    void onAssistantToken(long turnId, String token);
    void onToolStarted(long turnId, String toolName, String input);
    void onToolFinished(long turnId, String toolName, String output, boolean ok);

    // ── 子 agent（Task 工具委派） ──
    /**
     * 子 agent 开始。
     *
     * @param turnId      所属回合，供 UI 过滤迟到事件
     * @param taskId      唯一标识一次委派
     * @param agentName   subagent_type
     * @param description 调用方给的简述
     */
    void onSubagentStarted(long turnId, String taskId, String agentName, String description);
    /**
     * 子 agent 结束。
     *
     * @param turnId    所属回合
     * @param taskId    对应 {@link #onSubagentStarted} 的 taskId
     * @param finalText 子 agent 的最终返回文本（回灌主 agent 的内容）
     */
    void onSubagentFinished(long turnId, String taskId, String finalText);

    /**
     * 子 agent 结束（带成败维度）。
     * 默认委托回 3 参版本，只有需要区分成败的实现（ConversationState 面板）覆写本方法。
     *
     * @param turnId    所属回合
     * @param taskId    对应 {@link #onSubagentStarted} 的 taskId
     * @param finalText 子 agent 的最终返回文本
     * @param ok        true 正常返回、false 执行抛错
     */
    default void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        onSubagentFinished(turnId, taskId, finalText);
    }

    /**
     * 带 taskId 的工具事件（子 agent 内部工具）：默认委托无 taskId 版本，只有需缩进渲染的实现覆写。
     *
     * @param turnId   所属回合
     * @param taskId   发起该工具调用的子 agent
     * @param toolName 工具注册名
     * @param input    工具入参（已序列化）
     */
    default void onToolStarted(long turnId, String taskId, String toolName, String input) {
        onToolStarted(turnId, toolName, input);
    }
    default void onToolFinished(long turnId, String taskId, String toolName, String output, boolean ok) {
        onToolFinished(turnId, toolName, output, ok);
    }
    void onTodoUpdated(long turnId, List<String> todoLines);   // Todos 转成可显示的行

    /**
     * 带 taskId 的 Todo 事件：taskId==null 是控制器（主 agent）的计划 todo（开发计划进度，进任务面板）；
     * taskId!=null 是子 agent 内部 todo（当前子 agent 的进度，进 todo 面板）。
     * 默认委托无 taskId 版本，只有需按层分流的实现（ConversationState）覆写本方法。
     *
     * @param turnId    所属回合
     * @param taskId    子 agent 的 taskId；null = 主 agent
     * @param todoLines 已转成可显示形式的 todo 行
     */
    default void onTodoUpdated(long turnId, String taskId, List<String> todoLines) {
        onTodoUpdated(turnId, todoLines);
    }
    void onTurnComplete(long turnId);
    void onError(long turnId, Throwable error);

    /**
     * 模型经 AskUserQuestionTool 发问：UI 应弹出作答面板并最终经 {@code request.responder()} 应答。
     * 与其它方法一样带 turnId 供迟到过滤。落地端会阻塞工具线程直到 UI 应答（见 UserQuestionBridge）。
     *
     * @param turnId  所属回合
     * @param request 问询请求（含题目、选项与应答出口）
     */
    void onQuestionAsked(long turnId, AskRequest request);

    /**
     * 模型调用的某个工具需要人工授权：UI 应把请求排进模态队列、弹审批面板，
     * 最终经 {@code request.responder()} 应答。落地端会阻塞工具线程直到应答（见 {@code PermissionCallback}）。
     *
     * <p><b>默认实现直接 DENY 而不是空实现</b>：空实现会让工具线程永久 park，
     * 而它持着回合——整个 agent 静默挂死，无报错也无出口。
     * 任何没有真正接管审批 UI 的落地端（回显桩 / 测试桩）都应该「拒绝并让回合继续」，而不是挂死。
     *
     * <p>覆写本方法的实现（审批面板）有义务在<b>所有分支上</b>最终应答恰好一次，且须守两条禁令：
     * <ul>
     *   <li><b>不得调 {@code super.onPermissionRequested(...)}</b>。默认实现会<b>立即</b>应答 DENY；
     *       一次性应答口只被消费一次，故工具线程拿到的是 DENY，用户随后真实选择的
     *       {@code ALLOW_ONCE} 被丢弃（实测 responder 被调 2 次、线程拿到 DENY）。
     *       「务必应答」的措辞很容易诱导人顺手写一句 {@code super}——这里恰恰不能写。
     *       接管审批的实现要做的是<b>把请求排进队列</b>，应答留给面板。</li>
     *   <li><b>不得在本回调里阻塞</b>（等用户选完再返回）。{@code ConversationState} 的 listener 方法是
     *       {@code synchronized}，与 {@code drainPending()} / {@code cancelCurrent()} 共用同一把锁，
     *       在回调里阻塞会冻住<b>整个 TUI</b>（连 Esc 都按不动），而不只是一个工具线程。
     *       本方法必须立刻返回，阻塞由工具线程在应答口上完成。</li>
     * </ul>
     *
     * @param turnId  所属回合，供迟到过滤
     * @param request 审批请求（含工具名、判定目标、理由、建议规则与应答出口）
     */
    default void onPermissionRequested(long turnId, PermissionRequest request) {
        request.responder().respond(PermissionOutcome.DENY);
    }

    /**
     * 模型经 {@code ExitPlanMode} 提交了一份计划：UI 应把正文渲染进 scrollback、把请求排进模态队列、
     * 弹三选项面板，最终经 {@code request.responder()} 应答。落地端会阻塞工具线程直到应答
     * （见 {@link PlanApprovalBridge}）。
     *
     * <p><b>默认实现直接「继续完善计划」而不是空实现</b>：空实现会让工具线程永久 park，
     * 而它持着回合——整个 agent 静默挂死。没有接管计划 UI 的落地端（回显桩 / 测试桩）
     * 应当给一个能让回合继续下去的答复。
     *
     * <p>覆写本方法的实现须守与 {@link #onPermissionRequested} <b>完全相同</b>的两条禁令：
     * <b>不得调 {@code super}</b>（默认实现会立刻应答，一次性口被消费掉，用户随后的真实选择被丢弃）、
     * <b>不得在本回调里阻塞</b>（{@code ConversationState} 的 listener 方法是 {@code synchronized}，
     * 阻塞会冻住整个 TUI）。
     *
     * @param turnId  所属回合，供迟到过滤
     * @param request 计划审批请求（含计划正文与应答出口）
     */
    default void onPlanSubmitted(long turnId, PlanRequest request) {
        request.responder().respond(PlanOutcome.KEEP_PLANNING, "（当前界面不支持计划审批）");
    }

    /**
     * 「允许，本会话不再问」/「允许，永久」的<b>记录结果</b>，由工具线程在真正写完之后回报。
     *
     * <p><b>为什么必须由这一侧回报</b>：UI 应答的那一刻，写盘还没发生——应答只是把工具线程
     * 唤醒，写盘在它醒来之后才做。此前 UI 在应答后立刻打一句「✓ 已记下允许规则…（写盘失败则
     * 仅本会话生效）」，那是<b>在事情发生前用完成时描述它</b>，而且失败了也不会更正：
     * {@code PermissionOutcome} 没有回传通道，UI 根本无从得知成败。
     *
     * <p>四种结果都经这里回报：会话规则、永久写入成功、写盘失败降级、被 deny 规则遮蔽未记录。
     *
     * @param turnId  所属回合
     * @param ok      是否达成用户选的那个效果（写盘失败 / 被遮蔽都是 false）
     * @param message 给用户看的一句话，已含规则 DSL 与落点；实现方直接下沉即可
     */
    default void onRuleRecorded(long turnId, boolean ok, String message) { }

    /**
     * BYPASS 下放行了一个<b>通常需要确认</b>的操作（{@code --dangerously-skip-permissions} 打开时，
     * 命中内置底线却没被拦住的那些）。
     *
     * <p>默认空实现，便于回显桩 / 测试桩省略。落地端应：即时打一行进 scrollback，
     * 并按 {@code turnId} 累积、在 {@link #onTurnComplete} 时汇总一次。
     *
     * <p><b>为什么即时行与汇总都要</b>：即时行保证「正在发生时屏幕上有」，
     * 汇总保证「人回来时不必翻几百行 scrollback」。半无人值守场景（丢个大任务给 agent
     * 然后人离开、之后回来看）两者都必要——内置底线的价值有两半，BYPASS 放弃了「拦住你」，
     * 就不该连「让你知道发生了什么」一起丢。
     *
     * <p><b>本回调绝不能阻塞</b>——与 {@link #onPermissionRequested} 同纪律：
     * {@code ConversationState} 的 listener 方法是 {@code synchronized}，
     * 与 {@code drainPending()} 共用同一把锁，在这里阻塞会冻住<b>整个 TUI</b>。
     * 更不得在这里发起询问：BYPASS 的定义就是不问，问了就是把死锁又请回来。
     *
     * @param turnId 所属回合
     * @param what 危险理由串（引擎的内置底线原话，形如「写入 .git/ 内部（…）：/p/.git/hooks/pre-commit」）
     */
    default void onGuardrailBypassed(long turnId, String what) { }

    // ── 后台子 agent（run_in_background；跨回合存活，故<b>不带 turnId</b>） ──

    /**
     * 后台任务已启动。<b>刻意不带 turnId</b>：后台任务跨回合存活，带 turnId 会被
     * {@code ConversationState} 的迟到过滤丢弃（那正是前台事件想要的行为，对后台却是致命的）。
     *
     * <p>默认空实现，便于回显桩 / 测试桩省略。
     *
     * @param taskId      后台任务 id
     * @param agentName   subagent_type
     * @param description 委派时给的简述
     */
    default void onBackgroundTaskStarted(String taskId, String agentName, String description) { }

    /**
     * 后台任务结束。ok=false 表示执行抛错（finalText 是摊平后的原因）。
     *
     * <p><b>刻意没有配套的「进度」事件</b>：后台任务「当前在跑哪个工具」不走这条通道，
     * 而是照常经 {@link ToolEventCallback} 发 {@code onToolStarted(turnId, taskId, ...)}，
     * 由 UI 层按 taskId 认出后台任务、写进它自己的镜像状态。多摆一个没有发射点的
     * {@code onBackgroundTaskProgress} 只会立一块「后台进度该从这里报」的假路标，
     * 照着接线的人会发现事件永远不来。
     *
     * @param taskId    后台任务 id
     * @param finalText 结果正文；ok=false 时是摊平后的失败原因
     * @param ok        true = 正常完成，false = 执行抛错
     */
    default void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) { }

    /**
     * 启动期 MCP 后台连接<b>全部</b>结束（成功与失败都算）。只发一次。
     *
     * <p>这条事件的存在是因为 {@code McpRegistry.init} 不再等连接完成——那行
     * 「（MCP：已发现 N 个工具。）」原来是 {@code CodeTuiApplication} 同步算出来的，
     * 现在算的时刻已经在 TUI 起来之后了，只能由 registry 回头通知。
     *
     * @param serverCount 连上的 server 数（失败的不计）
     * @param toolCount   发现的工具总数
     */
    default void onMcpReady(int serverCount, int toolCount) { }

    // ── 会话压缩（跨回合的横切信号；无 turnId） ──
    /** 压缩开始。
     *
     * @param reason {@code "auto"}（阈值触发）或 {@code "manual"}（/compact）
     */
    void onCompactionStarted(String reason);
    /** 压缩完成。
     *
     * @param eventsRemoved 被归档移除的事件数
     * @param tokensSaved   估算节省的 token 数
     */
    void onCompactionFinished(int eventsRemoved, int tokensSaved);
    /** 压缩失败。
     *
     * @param message 失败原因（已做 null 安全处理的字符串）
     */
    void onCompactionFailed(String message);
}
