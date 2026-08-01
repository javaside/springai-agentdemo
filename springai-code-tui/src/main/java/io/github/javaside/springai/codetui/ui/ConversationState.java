package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AgentListener;
import io.github.javaside.springai.codetui.agent.AskRequest;
import io.github.javaside.springai.codetui.agent.ModalRequest;
import io.github.javaside.springai.codetui.agent.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.PermissionRequest;
import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import dev.tamboui.text.CharWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 线程安全共享状态，兼任 {@link AgentListener} 落地端。<b>Claude Code 式行内滚动模型</b>：
 *
 * <ul>
 *   <li><b>pending</b>：已定稿的输出行（带 {@link OutputLine.Kind 类型}，供 UI 分色做层次），
 *       交渲染线程用 {@code InlineTuiRunner.println} 推进终端 scrollback。</li>
 *   <li><b>streaming</b>：在建助手行；凑满整（显示宽度）行即 {@link #takeCompleteStreamingLines}
 *       下沉 scrollback，只把最后残段留在底部 live 区预览。</li>
 * </ul>
 *
 * 并发：写在 Reactor 线程、读/drain 在渲染线程；复合操作 {@code synchronized}、标志 {@code volatile}；
 * 每个带 turnId 的写入先做迟到过滤（{@link #onTurnStarted} 例外——它设定 acceptingTurnId）。
 *
 * <p><b>活性责任（本类是别人的逃生口）</b>：模态请求队列里每一个元素背后都<b>阻塞着一个工具线程</b>，
 * 而那个线程持着回合。故凡是让请求离队的路径都必须应答它一次：入队失败（迟到 / 队满）由入队方法当场应答，
 * 已入队的由 {@link #clearModals()}（{@link #cancelCurrent()} / {@link #resetForNewSession()} 调）唤醒，
 * 正常处理完的由 UI 应答后调 {@link #removeModal}。漏任一条就是工具线程永久 park ——
 * 整个 agent 静默挂死，无报错也无出口（见 {@code ModalRequest#cancel()} 的活性纪律）。
 */
public final class ConversationState implements AgentListener {
    /** 日志只落文件（logback 无 CONSOLE appender）——任何 stdout 输出都会撕裂内联 TUI 画面。 */
    private static final Logger log = LoggerFactory.getLogger(ConversationState.class);

    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    /**
     * 一条定稿输出行 + 其语义类型（UI 据此上色）。
     *
     * @param text     供无差别显示/断言的文本
     * @param kind     语义类型
     * @param toolName 仅 {@code TOOL_START}：工具名（供 UI 判断是否渲染 diff）；其余为 null
     * @param raw      仅 {@code TOOL_START}：工具原始 JSON 入参（供 UI 侧 {@link DiffRenderer} 渲染 diff）；其余为 null
     */
    public record OutputLine(String text, Kind kind, String toolName, String raw) {
        public enum Kind { USER, ASSISTANT, TOOL_START, TOOL_OK, TOOL_FAIL, TODO, ERROR, INFO,
                           SUBAGENT_START, SUBAGENT_TOOL, SUBAGENT_END }

        /** 普通行（无工具元数据）。 */
        public OutputLine(String text, Kind kind) {
            this(text, kind, null, null);
        }
    }

    /** 子任务（一次子 agent 作业）状态——<b>任务面板</b>显示。 */
    public enum SubtaskStatus { RUNNING, DONE, FAILED }

    /** 子任务只读快照（供渲染线程读，与内部可变状态解耦）。 */
    public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool) {}

    /** 内部可变持有者：status/currentTool 就地更新。仅本类访问。 */
    private static final class Subtask {
        final String taskId;
        final String agentName;
        final String description;
        SubtaskStatus status = SubtaskStatus.RUNNING;
        String currentTool = "";
        Subtask(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }

    private final Deque<OutputLine> pending = new ArrayDeque<>();

    /** 排队的用户消息 + 其挂载技能（可空）。挂载随消息入队，出队时一并带出。 */
    public record Queued(String text, String skill) {}

    private final Deque<Queued> queued = new ArrayDeque<>();       // 忙时排队的用户消息（回合结束后自动出队提交）
    private final StringBuilder streaming = new StringBuilder();
    private final List<String> todo = new ArrayList<>();          // 主 agent（控制器）的 todo/计划（todo 面板，不进 scrollback）
    private final List<Subtask> subtasks = new ArrayList<>();     // 本回合派出的子 agent 状态（任务面板，不进 scrollback）
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";
    private volatile String activeTool = "";
    private volatile String activeToolSummary = "";
    private volatile long acceptingTurnId = -1L;

    // ── 会话压缩瞬态状态（供状态行显示；独立于 Status，自动/手动共用） ──
    private volatile boolean compacting = false;
    private volatile long compactStartNanos = 0L;
    private volatile String compactReason = "";

    // ── 模态请求队列（问询 + 审批共用；渲染线程读、工具线程写；迟到过滤后置入） ──
    // 为何是队列而非单字段：ParallelTasks 下多个子 agent 线程可能同时判出 ASK，
    // 单个 volatile 字段会让后来者覆盖前者、被覆盖的那个工具线程永久 park。
    private final Deque<ModalRequest> modals = new ArrayDeque<>();

    /** 队列上限：防失控回合塞爆队列；超出的请求直接被拒（DENY，回合继续）。 */
    static final int MODAL_QUEUE_CAP = 8;

    // ── 输入缓冲 ────────────────────────────────────────────────────────
    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    /** 追加一条信息行（灰色，进 scrollback）。用于「本回合实际使用的模型」等确定性提示。 */
    public synchronized void pushInfo(String text) { pending.add(new OutputLine(text, OutputLine.Kind.INFO)); }

    /**
     * -c 恢复启动：把历史消息回放进 scrollback（仿 Claude Code --continue），直观重现上次对话，
     * 而非只提示「已恢复 N 条」。转换出的定稿行走正常 drain 通道下沉，故排在欢迎横幅之后、首条新输入之前。
     * 空历史则什么都不做。
     */
    public synchronized void replayHistory(List<Message> messages) {
        List<OutputLine> body = HistoryReplay.toReplayLines(messages);
        if (body.isEmpty()) return;
        pending.add(new OutputLine("↺ 已恢复上次会话（" + HistoryReplay.userTurns(messages) + " 轮对话）",
                OutputLine.Kind.INFO));
        pending.addAll(body);
        pending.add(new OutputLine("──── 以上为历史 · 可继续对话，或 /continue 续跑未完成的计划 ────",
                OutputLine.Kind.INFO));
    }

    // ── 消息队列（忙时排队，回合结束自动出队） ───────────────────────────
    public synchronized void enqueue(String msg, String skill) { queued.add(new Queued(msg, skill)); }
    public synchronized Queued pollQueued() { return queued.poll(); }
    public synchronized int queuedCount() { return queued.size(); }
    public synchronized void clearQueued() { queued.clear(); }
    public synchronized List<String> queuedSnapshot() { return queued.stream().map(Queued::text).toList(); }

    /**
     * {@code /clear}：把面板与状态复位到「刚启动」——清 todo 面板、子 agent 任务面板、未定稿输出、排队消息、状态提示。
     * 不动会话事件（那是 {@link io.github.javaside.springai.codetui.agent.CodingAgent#clearContext()} 的职责）。
     */
    public synchronized void resetForNewSession() {
        clearModals();      // 别把待处理模态留给新会话：它们背后各有一个 park 着的工具线程
        todo.clear();
        subtasks.clear();
        pending.clear();
        queued.clear();
        notice = "";
    }

    // ── 单飞 / 状态 ─────────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public String activeToolSummary() { return activeToolSummary; }
    public long acceptingTurnId() { return acceptingTurnId; }

    /** 队首模态请求（无则 null）；渲染线程读，<b>不出队</b>。 */
    public synchronized ModalRequest peekModal() { return modals.peek(); }

    /**
     * 按<b>身份</b>移除一个已处理完的模态请求（答完 / 批完 / 取消后由 UI 调）。
     *
     * <p>不用 {@code Deque.remove(Object)}：它按 {@code equals} 找，而 {@link AskRequest} /
     * {@link PermissionRequest} 都是 record（逐分量 equals），分量恰好相同的两个请求会让它摘错人——
     * 被误摘的那个请求再无人应答，其工具线程永久 park。
     *
     * <p>不负责应答：调用方（UI）已经应答过了。要「移除并唤醒」用 {@link #clearModals()}。
     */
    public synchronized void removeModal(ModalRequest r) {
        modals.removeIf(m -> m == r);
    }

    /** 是否有待处理模态（计入 {@link #isBusy()}）。 */
    public synchronized boolean hasModal() { return !modals.isEmpty(); }

    /**
     * 入队；队列已满返回 false（调用方负责应答，绝不能静默丢弃——丢了就是永久 park）。
     */
    private boolean offerModal(ModalRequest r) {
        if (modals.size() >= MODAL_QUEUE_CAP) return false;
        modals.add(r);
        return true;
    }

    /**
     * 唤醒并清空全部待处理模态（取消回合 / 开新会话）。
     *
     * <p>先清队列再逐个 {@code cancel()}：① 应答口可能回调进本类（{@code synchronized} 可重入），
     * 边遍历边改会 {@code ConcurrentModificationException}；② 万一某个 {@code cancel()} 抛异常，
     * 队列也已经是干净的，不会把剩下的请求留在里面反复重试。
     * 被唤醒的工具线程随后要抢本类的锁写迟到事件——它们会等到本方法所在的 {@code synchronized}
     * 块（{@link #cancelCurrent()} / {@link #resetForNewSession()}）走完，那时 acceptingTurnId 已复位，迟到写入自然被过滤。
     *
     * <p><b>逐个 try/catch 是契约兜底，不是防御性编程</b>：{@code ModalRequest.cancel()} 明文规定
     * 实现不得抛异常。但队列 {@code [A, B]} 里若 A 违约抛了，不兜底就会：① B 的工具线程<b>永不被唤醒</b>
     * ——正是本类要防的那种挂死；② 异常沿 {@code synchronized} 的 {@link #cancelCurrent()} 传到 UI 线程的
     * Esc 处理器。一个坏元素不得殃及它后面的每一个。故记日志后继续排空。
     */
    public synchronized void clearModals() {
        if (modals.isEmpty()) return;
        List<ModalRequest> doomed = new ArrayList<>(modals);
        modals.clear();
        for (ModalRequest r : doomed) {
            try {
                r.cancel();
            } catch (RuntimeException e) {
                // 违约的实现（如 null responder 引发的 NPE）：吞掉并继续，保证后面的元素照样被唤醒。
                log.warn("模态请求 cancel() 抛异常（违反 ModalRequest.cancel() 契约），继续唤醒其余请求", e);
            }
        }
    }

    // ── 压缩状态读取（渲染线程用） ──
    public boolean isCompacting() { return compacting; }
    public String compactReason() { return compactReason; }
    /** 距压缩开始的经过纳秒（用于状态行计时）。 */
    public long compactElapsedNanos() { return compacting ? System.nanoTime() - compactStartNanos : 0L; }

    /** 「忙」= 有活跃回合 / 正在压缩 / 有待处理模态：此时不应发起新回合（排队）、也不应触发手动压缩。 */
    public boolean isBusy() { return !isIdle() || compacting || hasModal(); }

    /** 渲染线程调用：取走并清空「待 println」的定稿行。 */
    public synchronized List<OutputLine> drainPending() {
        if (pending.isEmpty()) return List.of();
        List<OutputLine> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /**
     * 渲染线程调用：把在建助手行里<b>已换行（遇到真实 \n）</b>的完整逻辑行取出去下沉 scrollback，
     * 只保留最后一段未换行的残行继续预览。按真实 {@code \n} 切分（不是按显示宽度——终端自己会折长行），
     * 从根上避免多行内容 + 预览叠加造成的重复。锁内完成，避免与 {@link #onAssistantToken} 竞争。
     */
    public synchronized List<String> takeCompleteStreamingLines() {
        int idx = streaming.lastIndexOf("\n");
        if (idx < 0) return List.of();                      // 还没换行，全留着预览
        String complete = streaming.substring(0, idx);      // 含 idx 之前的若干完整行
        String partial = streaming.substring(idx + 1);
        streaming.setLength(0);
        streaming.append(partial);
        List<String> out = new ArrayList<>();
        for (String l : complete.split("\n", -1)) {
            out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
        return out;
    }

    /** live 区显示：在建助手行的当前残行（未换行段）。 */
    public synchronized String streaming() { return streaming.toString(); }

    /**
     * Esc 取消当前回合：唤醒全部待处理模态、定稿在建行、acceptingTurnId=-1、状态回 IDLE。
     *
     * <p>{@link #clearModals()} 放在最前：它是本方法里唯一会调用外部代码的一步，
     * 排在前面保证后续任何一步抛异常都不会把请求连同其阻塞的工具线程留在队列里（漏了就是永久 park）。
     */
    public synchronized void cancelCurrent() {
        clearModals();                 // 必须最先做：漏了这步，阻塞中的工具线程永久 park
        flushStreaming();
        acceptingTurnId = -1L;
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    // ── AgentListener 落地端 ────────────────────────────────────────────
    @Override
    public synchronized void onTurnStarted(long turnId) {
        acceptingTurnId = turnId;
        status = Status.THINKING;
        streaming.setLength(0);
        // 新回合清空上一份计划：面板内容变空（用完即走）。这只是清内容、不改 live 高度，
        // 不触发 InlineDisplay 收缩(deleteLines)的漂移，因此不会复现「面板消失」。
        todo.clear();
        subtasks.clear();                 // 新回合清空任务面板（子 agent 状态），与 todo 同生命周期
    }

    @Override
    public synchronized void onUserMessage(long turnId, String text) {
        if (turnId != acceptingTurnId) return;
        pending.add(new OutputLine("", OutputLine.Kind.ASSISTANT));   // 回合间留白，分隔更清晰
        pending.add(new OutputLine("› " + text, OutputLine.Kind.USER));   // 保留 › 提示符，去掉「你」
    }

    @Override
    public synchronized void onAssistantToken(long turnId, String token) {
        if (turnId != acceptingTurnId) return;
        streaming.append(token);
    }

    @Override
    public synchronized void onToolStarted(long turnId, String toolName, String toolInput) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        status = Status.RUNNING_TOOL;
        activeTool = toolName;
        activeToolSummary = summarize(toolInput);
        String line = "⏺ " + toolName + (activeToolSummary.isEmpty() ? "" : "  " + activeToolSummary);
        // 文件写入工具（edit/write）额外携带原始 JSON 入参：渲染线程据此读原文件、生成带真实行号的 diff。
        String raw = DiffRenderer.isFileWrite(toolName) ? toolInput : null;
        pending.add(new OutputLine(line, OutputLine.Kind.TOOL_START, toolName, raw));
    }

    @Override
    public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        if (turnId != acceptingTurnId) return;
        status = Status.THINKING;
        activeTool = "";
        activeToolSummary = "";
        pending.add(new OutputLine("  ⎿ " + toolName + (ok ? " ✓" : " ✗"),
                ok ? OutputLine.Kind.TOOL_OK : OutputLine.Kind.TOOL_FAIL));
    }

    @Override
    public synchronized void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        if (turnId != acceptingTurnId) return;           // 迟到过滤，与其它事件一致
        flushStreaming();                                // 把在建助手残行定稿，子 agent 块另起
        String d = summarize(description);               // 折叠空白/换行，守住「一 OutputLine=一物理行」不变量
        pending.add(new OutputLine("▸ Task(" + agentName + ")" + (d.isEmpty() ? "" : " " + d),
                OutputLine.Kind.SUBAGENT_START));
        subtasks.add(new Subtask(taskId, agentName, d));   // 任务面板追加一条运行中子 agent（d 已 summarize=一物理行）
    }

    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText) {
        onSubagentFinished(turnId, taskId, finalText, true);
    }

    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        if (turnId != acceptingTurnId) return;
        String prefix = ok ? "  ⎿ " : "  ⎿ ✗ ";
        pending.add(new OutputLine(prefix + firstLine(finalText), OutputLine.Kind.SUBAGENT_END));   // 保留 scrollback 结论行
        Subtask st = findSubtask(taskId);
        if (st != null) {
            st.status = ok ? SubtaskStatus.DONE : SubtaskStatus.FAILED;
            st.currentTool = "";
        }
    }

    /** 子 agent 内部工具（taskId 非空）：缩进一级挂在当前 Task 块下；taskId 为空则走主流工具路径。 */
    @Override
    public synchronized void onToolStarted(long turnId, String taskId, String toolName, String input) {
        if (taskId == null) { onToolStarted(turnId, toolName, input); return; }
        if (turnId != acceptingTurnId) return;
        String s = summarize(input);
        pending.add(new OutputLine("    ⎿ " + toolName + (s.isEmpty() ? "" : " " + s),
                OutputLine.Kind.SUBAGENT_TOOL));
        Subtask st = findSubtask(taskId);
        if (st != null) st.currentTool = toolName;   // 任务面板：更新该子 agent 的当前工具
    }

    /** 子 agent 内部工具结束：taskId 非空时不再单独出行（起始行已够，减少噪音）；taskId 为空走主流。 */
    @Override
    public synchronized void onToolFinished(long turnId, String taskId, String toolName, String output, boolean ok) {
        if (taskId == null) { onToolFinished(turnId, toolName, output, ok); return; }
        // 子 agent 内部工具：仅在失败时补一行更深缩进的告警，成功时静默（起始行已展示活动）。
        if (turnId != acceptingTurnId || ok) return;
        pending.add(new OutputLine("      ✗ " + toolName, OutputLine.Kind.SUBAGENT_TOOL));
    }

    @Override
    public synchronized void onTodoUpdated(long turnId, List<String> todoLines) {
        onTodoUpdated(turnId, null, todoLines);   // 无 taskId：视为控制器计划（任务面板）
    }

    /**
     * 分流：taskId==null 是主 agent（控制器）的 todo → todo 面板；
     * taskId!=null 是子 agent 内部 todo → 丢弃（不进任何面板，仅 scrollback 有其工具活动行）。
     */
    @Override
    public synchronized void onTodoUpdated(long turnId, String taskId, List<String> todoLines) {
        if (turnId != acceptingTurnId) return;
        if (taskId != null) return;       // 子 agent 内部 todo 不上面板
        todo.clear();                     // todo 面板原地替换：不进 scrollback
        todo.addAll(todoLines);
    }

    /** todo 面板快照（主 agent 的 todo/计划）。 */
    public synchronized List<String> todoSnapshot() { return List.copyOf(todo); }

    /** 任务面板快照：本回合派出的子 agent 状态。返回不可变值副本，与内部可变状态解耦。 */
    public synchronized List<SubtaskView> subtaskSnapshot() {
        List<SubtaskView> out = new ArrayList<>(subtasks.size());
        for (Subtask s : subtasks) out.add(new SubtaskView(s.agentName, s.description, s.status, s.currentTool));
        return List.copyOf(out);
    }

    /** 按 taskId 定位子任务；找不到（迟到/已清）返回 null，调用方静默忽略。 */
    private Subtask findSubtask(String taskId) {
        for (Subtask s : subtasks) if (s.taskId.equals(taskId)) return s;
        return null;
    }

    @Override
    public synchronized void onTurnComplete(long turnId) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    @Override
    public synchronized void onError(long turnId, Throwable error) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        pending.add(new OutputLine("⚠ 出错：" + (error == null ? "unknown" : String.valueOf(error.getMessage())),
                OutputLine.Kind.ERROR));
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    @Override
    public synchronized void onQuestionAsked(long turnId, AskRequest request) {
        if (turnId != acceptingTurnId || !offerModal(request)) {
            // 迟到（回合已取消/切换）或队列已满：桥侧的 take() 靠取消路径唤醒，这里直接取消不弹面板。
            request.cancel();
        }
    }

    /**
     * 审批请求入队。<b>迟到与队满一律 DENY 而非 CANCEL</b>：
     * 拒绝让模型自寻替代、回合继续；CANCEL 会中断整个回合，语义过重。
     * 两条路径都<b>必须</b>应答——静默丢弃就是工具线程永久 park。
     *
     * <p><b>不阻塞</b>（契约要求）：本方法与 {@link #drainPending()} / {@link #cancelCurrent()}
     * 共用同一把监视器锁，这里一旦阻塞冻住的是<b>整个 TUI</b>而不只是一个工具线程。
     * 实现只做 O(1) 的入队判断；应答走的是消费方一次性、非阻塞的 handoff，故必然立即返回。
     *
     * <p><b>队满溢出必须留下用户看得见的一行</b>：线程一定会醒（活性没问题），但「有个工具被悄悄拒了」
     * 只在日志里就等于没发生——用户会以为模型自己改了主意。迟到请求<b>不</b>提示：那个回合已经取消/切换，
     * 再打一行只是噪音。
     */
    @Override
    public synchronized void onPermissionRequested(long turnId, PermissionRequest request) {
        if (turnId != acceptingTurnId) {
            request.responder().respond(PermissionOutcome.DENY);
            return;
        }
        if (!offerModal(request)) {
            pending.add(new OutputLine("⚠ 待审批请求过多（已达上限 " + MODAL_QUEUE_CAP + "），已自动拒绝 "
                    + request.toolName() + "：请先处理面板里的请求", OutputLine.Kind.ERROR));
            request.responder().respond(PermissionOutcome.DENY);
        }
    }

    /**
     * 计划审批请求入队。
     *
     * <p><b>迟到 → CANCEL，队满 → KEEP_PLANNING</b>，两条路径都<b>必须</b>应答——静默丢弃
     * 就是工具线程永久 park。二者刻意不同：迟到的那个回合已经取消/切换，让它抛异常随流丢弃即可；
     * 而队满只是面板挤，不该因此杀掉整个回合，故给一个能让模型继续下去的答复 + 一行用户看得见的提示
     * （迟到<b>不</b>提示：那个回合已经没了，再打一行只是噪音）。
     *
     * <p><b>不阻塞</b>（契约要求）：本方法与 {@link #drainPending()} / {@link #cancelCurrent()}
     * 共用同一把监视器锁，这里一旦阻塞冻住的是<b>整个 TUI</b>而不只是一个工具线程。
     */
    public synchronized void onPlanSubmitted(long turnId, PlanRequest request) {
        if (turnId != acceptingTurnId) {
            request.responder().respond(PlanOutcome.CANCEL, "");
            return;
        }
        if (!offerModal(request)) {
            pending.add(new OutputLine("⚠ 待处理的模态请求过多（已达上限 " + MODAL_QUEUE_CAP
                    + "），本次计划未能展示：请先处理面板里的请求", OutputLine.Kind.ERROR));
            request.responder().respond(PlanOutcome.KEEP_PLANNING,
                    "（界面繁忙，未能展示计划，请稍后重新提交）");
        }
    }

    @Override
    public synchronized void onCompactionStarted(String reason) {
        compactStartNanos = System.nanoTime();
        compactReason = reason == null ? "" : reason;
        compacting = true;   // 最后写：作为安全发布标志，读者见到 true 即保证看到上面两个字段的新值
    }

    @Override
    public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        compacting = false;
        compactReason = "";
        if (eventsRemoved <= 0) {
            pending.add(new OutputLine("• 无可压缩内容（历史尚短）", OutputLine.Kind.INFO));
        } else {
            pending.add(new OutputLine(
                    "✓ 已压缩会话：移除 " + eventsRemoved + " 个事件，约省 " + tokensSaved + " tokens",
                    OutputLine.Kind.INFO));
        }
    }

    @Override
    public synchronized void onCompactionFailed(String message) {
        compacting = false;
        compactReason = "";
        pending.add(new OutputLine("✗ 压缩失败：" + (message == null ? "unknown" : message),
                OutputLine.Kind.ERROR));
    }

    // ── 内部 ────────────────────────────────────────────────────────────
    private void flushStreaming() {
        if (streaming.length() == 0) return;
        for (String l : streaming.toString().split("\n", -1)) {   // 残段也按真实换行拆，避免嵌入 \n 的整块
            pending.add(new OutputLine(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l,
                    OutputLine.Kind.ASSISTANT));
        }
        streaming.setLength(0);
    }

    private static String summarize(String toolInput) {
        if (toolInput == null) return "";
        String oneLine = toolInput.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) oneLine = oneLine.substring(0, 200);
        if (CharWidth.of(oneLine) <= 80) return oneLine;
        return CharWidth.substringByWidth(oneLine, 79) + "…";
    }

    /** 取首行 + 超长按显示宽度截断（子 agent 结论行用）。 */
    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "";
        String one = s.lines().findFirst().orElse("").strip();
        if (CharWidth.of(one) <= 80) return one;
        return CharWidth.substringByWidth(one, 79) + "…";
    }
}
