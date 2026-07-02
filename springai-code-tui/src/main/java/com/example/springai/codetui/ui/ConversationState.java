package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AgentListener;
import dev.tamboui.text.CharWidth;

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
        public enum Kind { USER, ASSISTANT, TOOL_START, TOOL_OK, TOOL_FAIL, TODO, ERROR, INFO }

        /** 普通行（无工具元数据）。 */
        public OutputLine(String text, Kind kind) {
            this(text, kind, null, null);
        }
    }

    private final Deque<OutputLine> pending = new ArrayDeque<>();
    private final Deque<String> queued = new ArrayDeque<>();       // 忙时排队的用户消息（回合结束后自动出队提交）
    private final StringBuilder streaming = new StringBuilder();
    private final List<String> todo = new ArrayList<>();          // 当前计划（固定面板显示，不进 scrollback）
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

    // ── 输入缓冲 ────────────────────────────────────────────────────────
    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    /** 追加一条信息行（灰色，进 scrollback）。用于「本回合实际使用的模型」等确定性提示。 */
    public synchronized void pushInfo(String text) { pending.add(new OutputLine(text, OutputLine.Kind.INFO)); }

    // ── 消息队列（忙时排队，回合结束自动出队） ───────────────────────────
    public synchronized void enqueue(String msg) { queued.add(msg); }
    public synchronized String pollQueued() { return queued.poll(); }
    public synchronized int queuedCount() { return queued.size(); }
    public synchronized void clearQueued() { queued.clear(); }
    public synchronized List<String> queuedSnapshot() { return List.copyOf(queued); }

    // ── 单飞 / 状态 ─────────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public String activeToolSummary() { return activeToolSummary; }
    public long acceptingTurnId() { return acceptingTurnId; }

    // ── 压缩状态读取（渲染线程用） ──
    public boolean isCompacting() { return compacting; }
    public String compactReason() { return compactReason; }
    /** 距压缩开始的经过纳秒（用于状态行计时）。 */
    public long compactElapsedNanos() { return compacting ? System.nanoTime() - compactStartNanos : 0L; }

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
    public synchronized void onTodoUpdated(long turnId, List<String> todoLines) {
        if (turnId != acceptingTurnId) return;
        todo.clear();                     // 原地替换：固定面板显示，不进 scrollback
        todo.addAll(todoLines);
    }

    /** 当前计划快照（供底部固定面板显示）。 */
    public synchronized List<String> todoSnapshot() { return List.copyOf(todo); }

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
    public synchronized void onCompactionStarted(String reason) {
        compacting = true;
        compactStartNanos = System.nanoTime();
        compactReason = reason == null ? "" : reason;
    }

    @Override
    public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        compacting = false;
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
}
