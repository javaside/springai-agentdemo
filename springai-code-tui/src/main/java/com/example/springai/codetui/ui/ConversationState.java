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

    /** 一条定稿输出行 + 其语义类型（UI 据此上色）。 */
    public record OutputLine(String text, Kind kind) {
        public enum Kind { USER, ASSISTANT, TOOL_START, TOOL_OK, TOOL_FAIL, TODO, ERROR }
    }

    private static final String USER_PREFIX = "你 › ";

    private final Deque<OutputLine> pending = new ArrayDeque<>();
    private final StringBuilder streaming = new StringBuilder();
    private final List<String> todo = new ArrayList<>();          // 当前计划（固定面板显示，不进 scrollback）
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";
    private volatile String activeTool = "";
    private volatile String activeToolSummary = "";
    private volatile long acceptingTurnId = -1L;

    // ── 输入缓冲 ────────────────────────────────────────────────────────
    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    // ── 单飞 / 状态 ─────────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public String activeToolSummary() { return activeToolSummary; }
    public long acceptingTurnId() { return acceptingTurnId; }

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
        // 注意：不清空 todo——保留上一份计划面板（新计划来时才替换），
        // 使 live 区高度只增不减，规避 InlineDisplay 收缩(deleteLines)时的光标漂移导致面板消失。
    }

    @Override
    public synchronized void onUserMessage(long turnId, String text) {
        if (turnId != acceptingTurnId) return;
        pending.add(new OutputLine("", OutputLine.Kind.ASSISTANT));   // 回合间留白，分隔更清晰
        pending.add(new OutputLine(USER_PREFIX + text, OutputLine.Kind.USER));
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
        pending.add(new OutputLine(line, OutputLine.Kind.TOOL_START));
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
