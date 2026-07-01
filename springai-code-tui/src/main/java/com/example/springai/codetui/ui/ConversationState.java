package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AgentListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 线程安全共享状态，兼任 {@link AgentListener} 落地端。采用 <b>Claude Code 式行内滚动模型</b>：
 *
 * <ul>
 *   <li><b>pending</b>：已「定稿」的输出行，交由渲染线程用 {@code InlineTuiRunner.println} 推进
 *       终端 scrollback —— 自然向上滚动、留在终端原生历史里可上翻，不是固定一屏。</li>
 *   <li><b>streaming</b>：在建的助手行，显示在底部 live 区；在回合结束 / 工具开始 / 出错 / 取消时
 *       flush 进 pending（定稿后滚入历史）。</li>
 * </ul>
 *
 * 并发：写在 Reactor 线程、读/drain 在渲染线程；复合操作走 {@code synchronized}，
 * 单飞标志走 {@code volatile}。每个带 turnId 的写入先做迟到过滤
 * {@code if (turnId != acceptingTurnId) return;}（{@link #onTurnStarted} 例外——它设定 acceptingTurnId）。
 */
public final class ConversationState implements AgentListener {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private static final String USER_PREFIX = "你 › ";

    private final Deque<String> pending = new ArrayDeque<>();    // 待 println 到 scrollback 的定稿行
    private final StringBuilder streaming = new StringBuilder(); // 在建助手行（底部 live 区显示）
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";
    private volatile String activeTool = "";   // 正在运行的工具名（状态栏显示）
    private volatile long acceptingTurnId = -1L;

    // ── 输入缓冲（View 依赖） ────────────────────────────────────────────
    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    /** 追加一个可打印按键的文本（可能多 char，如 CJK 走 codePoint→Character.toChars）。 */
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    // ── 单飞 / 状态位 ───────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public long acceptingTurnId() { return acceptingTurnId; }

    /** 渲染线程调用：取走并清空「待 println」的定稿行（推进 scrollback）。 */
    public synchronized List<String> drainPending() {
        if (pending.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** 底部 live 区显示：在建助手行（可能为空）。 */
    public synchronized String streaming() { return streaming.toString(); }

    /**
     * Esc 取消当前回合：把在建助手行定稿、acceptingTurnId 置 -1（迟到事件全部被过滤）、状态回 IDLE。
     */
    public synchronized void cancelCurrent() {
        flushStreaming();
        acceptingTurnId = -1L;
        activeTool = "";
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
    public synchronized void onToolStarted(long turnId, String toolName, String input) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();                 // 工具前把在建助手行定稿滚入历史
        status = Status.RUNNING_TOOL;
        activeTool = toolName;
    }

    @Override
    public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        if (turnId != acceptingTurnId) return;
        status = Status.THINKING;
        activeTool = "";
        pending.add("🛠 " + toolName + " " + (ok ? "✓" : "✗"));
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
        status = Status.IDLE;
    }

    @Override
    public synchronized void onError(long turnId, Throwable error) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        pending.add("⚠ 出错：" + (error == null ? "unknown" : String.valueOf(error.getMessage())));
        activeTool = "";
        status = Status.IDLE;
    }

    /** 把在建助手行定稿进 pending（调用方须持锁）。 */
    private void flushStreaming() {
        if (streaming.length() > 0) {
            pending.add(streaming.toString());
            streaming.setLength(0);
        }
    }
}
