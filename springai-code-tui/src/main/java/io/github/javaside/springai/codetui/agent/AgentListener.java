package io.github.javaside.springai.codetui.agent;

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
    /** 子 agent 开始。taskId 唯一标识一次委派；agentName=subagent_type；description=调用方给的简述。 */
    void onSubagentStarted(long turnId, String taskId, String agentName, String description);
    /** 子 agent 结束。finalText=子 agent 的最终返回文本（回灌主 agent 的内容）。 */
    void onSubagentFinished(long turnId, String taskId, String finalText);

    /**
     * 子 agent 结束（带成败维度）。ok=true 正常返回、false 执行抛错。
     * 默认委托回 3 参版本，只有需要区分成败的实现（ConversationState 面板）覆写本方法。
     */
    default void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        onSubagentFinished(turnId, taskId, finalText);
    }

    /** 带 taskId 的工具事件（子 agent 内部工具）：默认委托无 taskId 版本，只有需缩进渲染的实现覆写。 */
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
     */
    default void onTodoUpdated(long turnId, String taskId, List<String> todoLines) {
        onTodoUpdated(turnId, todoLines);
    }
    void onTurnComplete(long turnId);
    void onError(long turnId, Throwable error);

    /**
     * 模型经 AskUserQuestionTool 发问：UI 应弹出作答面板并最终经 {@code request.responder()} 应答。
     * 与其它方法一样带 turnId 供迟到过滤。落地端会阻塞工具线程直到 UI 应答（见 UserQuestionBridge）。
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
     */
    default void onPlanSubmitted(long turnId, PlanRequest request) {
        request.responder().respond(PlanOutcome.KEEP_PLANNING, "（当前界面不支持计划审批）");
    }

    // ── 会话压缩（跨回合的横切信号；无 turnId） ──
    /** 压缩开始。reason: "auto"（阈值触发）| "manual"（/compact）。 */
    void onCompactionStarted(String reason);
    /** 压缩完成。eventsRemoved：被归档移除的事件数；tokensSaved：估算节省 token。 */
    void onCompactionFinished(int eventsRemoved, int tokensSaved);
    /** 压缩失败。message：失败原因（已做 null 安全处理的字符串）。 */
    void onCompactionFailed(String message);
}
