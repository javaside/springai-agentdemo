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
 *   <li><b>pending</b>：已定稿的输出行，交渲染线程用 {@code InlineTuiRunner.println} 推进终端 scrollback。</li>
 *   <li><b>streaming</b>：在建助手行；每凑满一整（显示宽度）视觉行就 {@link #takeCompleteStreamingLines}
 *       下沉进 scrollback，只把最后不满一行的残段留在底部 live 区预览。</li>
 * </ul>
 *
 * 并发：写在 Reactor 线程、读/drain 在渲染线程；复合操作 {@code synchronized}、标志 {@code volatile}；
 * 每个带 turnId 的写入先做迟到过滤（{@link #onTurnStarted} 例外——它设定 acceptingTurnId）。
 */
public final class ConversationState implements AgentListener {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private static final String USER_PREFIX = "你 › ";

    private final Deque<String> pending = new ArrayDeque<>();
    private final StringBuilder streaming = new StringBuilder();
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";
    private volatile String activeTool = "";        // 正在运行的工具名
    private volatile String activeToolSummary = ""; // 正在运行的工具摘要（如 shell 命令）
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
    public synchronized List<String> drainPending() {
        if (pending.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /**
     * 渲染线程调用：把在建助手行里<b>已凑满整行</b>的部分（按显示宽度折行）取出去 println 进 scrollback，
     * 只保留最后不满一行的残段继续在 live 区预览。锁内完成，避免与 {@link #onAssistantToken} 并发 append 竞争。
     */
    public synchronized List<String> takeCompleteStreamingLines(int width) {
        if (streaming.length() == 0) return List.of();
        List<String> rows = wrapByWidth(streaming.toString(), width);
        if (rows.size() <= 1) return List.of();          // 还不足一整行，全留着
        List<String> complete = new ArrayList<>(rows.subList(0, rows.size() - 1));
        String partial = rows.get(rows.size() - 1);
        streaming.setLength(0);
        streaming.append(partial);
        return complete;
    }

    /** live 区显示：在建助手行的当前残段（≤ 一个视觉行）。 */
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
    }

    @Override
    public synchronized void onUserMessage(long turnId, String text) {
        if (turnId != acceptingTurnId) return;
        pending.add(USER_PREFIX + text);
    }

    @Override
    public synchronized void onAssistantToken(long turnId, String token) {
        if (turnId != acceptingTurnId) return;
        streaming.append(token);
    }

    @Override
    public synchronized void onToolStarted(long turnId, String toolName, String toolInput) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();                                // 工具前把在建助手行定稿
        status = Status.RUNNING_TOOL;
        activeTool = toolName;
        activeToolSummary = summarize(toolInput);
        pending.add("⏳ " + toolName + (activeToolSummary.isEmpty() ? "" : "  " + activeToolSummary));
    }

    @Override
    public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        if (turnId != acceptingTurnId) return;
        status = Status.THINKING;
        activeTool = "";
        activeToolSummary = "";
        pending.add((ok ? "✓ " : "✗ ") + toolName);
    }

    @Override
    public synchronized void onTodoUpdated(long turnId, List<String> todoLines) {
        if (turnId != acceptingTurnId) return;
        pending.add("📋 计划：");
        for (String l : todoLines) pending.add("   " + l);
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
        pending.add("⚠ 出错：" + (error == null ? "unknown" : String.valueOf(error.getMessage())));
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    // ── 内部 ────────────────────────────────────────────────────────────
    /** 把在建助手行（含残段）整体定稿进 pending。 */
    private void flushStreaming() {
        if (streaming.length() > 0) {
            pending.add(streaming.toString());
            streaming.setLength(0);
        }
    }

    /** 工具入参摘要：单行化 + 截断到 ~80 显示列（如 shell 的命令、grep 的模式）。 */
    private static String summarize(String toolInput) {
        if (toolInput == null) return "";
        String oneLine = toolInput.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) oneLine = oneLine.substring(0, 200);   // 先粗砍，避免超大输入
        if (CharWidth.of(oneLine) <= 80) return oneLine;
        return CharWidth.substringByWidth(oneLine, 79) + "…";
    }

    /** 按显示宽度把一行折成多视觉行（CJK 双宽，不断开宽字符）。空串 → 一个空行。 */
    private static List<String> wrapByWidth(String line, int width) {
        int w = Math.max(1, width);
        List<String> rows = new ArrayList<>();
        if (line.isEmpty()) { rows.add(""); return rows; }
        String rest = line;
        while (!rest.isEmpty()) {
            String row = CharWidth.substringByWidth(rest, w);
            if (row.isEmpty()) row = rest.substring(0, 1);
            rows.add(row);
            rest = rest.substring(row.length());
        }
        return rows;
    }
}
