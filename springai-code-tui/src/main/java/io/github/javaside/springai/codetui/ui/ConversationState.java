package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AgentListener;
import io.github.javaside.springai.codetui.agent.AskRequest;
import dev.tamboui.text.CharWidth;
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
 */
public final class ConversationState implements AgentListener {
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

    // ── AskUserQuestion 瞬态：待作答的问询（渲染线程读、工具线程写；迟到过滤后置入） ──
    private volatile AskRequest pendingAsk;

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

    // ── 单飞 / 状态 ─────────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public String activeToolSummary() { return activeToolSummary; }
    public long acceptingTurnId() { return acceptingTurnId; }

    /** 当前待作答的问询（无则 null）；渲染线程读。 */
    public AskRequest pendingAsk() { return pendingAsk; }
    /** 清除待作答问询（UI 答完/取消后调）。 */
    public void clearPendingAsk() { this.pendingAsk = null; }

    // ── 压缩状态读取（渲染线程用） ──
    public boolean isCompacting() { return compacting; }
    public String compactReason() { return compactReason; }
    /** 距压缩开始的经过纳秒（用于状态行计时）。 */
    public long compactElapsedNanos() { return compacting ? System.nanoTime() - compactStartNanos : 0L; }

    /** 「忙」= 有活跃回合或正在压缩：此时不应发起新回合（排队）、也不应触发手动压缩。 */
    public boolean isBusy() { return !isIdle() || compacting; }

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

    /** Esc 取消当前回合：定稿在建行、acceptingTurnId=-1、状态回 IDLE。 */
    public synchronized void cancelCurrent() {
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
        if (turnId != acceptingTurnId) {
            // 迟到：回合已被取消/切换。桥侧的 take() 靠取消路径唤醒，这里直接丢弃不弹面板。
            request.responder().cancel();
            return;
        }
        this.pendingAsk = request;
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
