package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.AgentListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 线程安全共享状态，兼任 {@link AgentListener} 实现（CodingAgent → UI 的落地端）。
 *
 * 并发模型：
 * <ul>
 *   <li>复合写入（transcript / input / todo / 流式助手缓冲）走 {@code synchronized}，保证一致快照；</li>
 *   <li>简单标志（status / acceptingTurnId）用 {@code volatile}，供 UI 线程无锁读取判定单飞；</li>
 *   <li>每个带 turnId 的写入先做迟到过滤：{@code if (turnId != acceptingTurnId) return;}
 *       —— 唯一例外是 {@link #onTurnStarted(long)}，它 SET acceptingTurnId 建立当前接受回合。</li>
 * </ul>
 * 流式语义：同一回合的多个 token 合并进 <b>同一行</b>（前缀 {@code "AI> "}），
 * 靠 {@code assistantLineIndex} 定位在建助手行；回合结束/出错时 finalize 使下一回合另起新行。
 */
public final class ConversationState implements AgentListener {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private static final String USER_PREFIX = "你> ";
    private static final String ASSISTANT_PREFIX = "AI> ";

    private final List<String> transcript = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private final List<String> todo = new ArrayList<>();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";   // 状态栏瞬时提示（如「已取消」）；下次输入即清除
    private volatile long acceptingTurnId = -1L;   // 当前接受事件的回合；-1 表示不接受任何回合

    /** 在建助手行在 transcript 中的下标；-1 表示当前回合尚未开始流式助手行。 */
    private int assistantLineIndex = -1;
    /** 在建工具行在 transcript 中的下标；-1 表示无在建工具行。 */
    private int toolLineIndex = -1;

    // ── 里程碑1 既有 API（CodeTuiView 依赖，务必保留） ──────────────────────────

    public synchronized void appendLine(String line) { transcript.add(line); }
    public synchronized List<String> transcriptSnapshot() { return List.copyOf(transcript); }

    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    /** 追加一个可打印按键的文本（可能是多 char，如 CJK 走 codePoint→Character.toChars）。 */
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }
    /** 状态栏瞬时提示（下次输入清除）。 */
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }

    /**
     * Esc 取消当前回合：状态回 IDLE，并把 acceptingTurnId 置 -1，
     * 使被取消回合后续迟到事件全部被 {@code turnId != acceptingTurnId} 过滤掉。
     * 可从 UI 线程调用。
     */
    public synchronized void cancelCurrent() {
        this.acceptingTurnId = -1L;
        this.status = Status.IDLE;
        finalizeStreamingLines();
    }

    // ── AgentListener 落地端 ───────────────────────────────────────────────

    /** submit 顶部同步调用：建立接受回合并进入 THINKING（此方法不过滤——它设定 acceptingTurnId）。 */
    @Override
    public synchronized void onTurnStarted(long turnId) {
        this.acceptingTurnId = turnId;
        this.status = Status.THINKING;
        finalizeStreamingLines();  // 新回合另起，清空在建行游标
    }

    @Override
    public synchronized void onUserMessage(long turnId, String text) {
        if (turnId != acceptingTurnId) return;
        transcript.add(USER_PREFIX + text);
    }

    @Override
    public synchronized void onAssistantToken(long turnId, String token) {
        if (turnId != acceptingTurnId) return;
        if (assistantLineIndex < 0) {
            transcript.add(ASSISTANT_PREFIX + token);
            assistantLineIndex = transcript.size() - 1;
        } else {
            transcript.set(assistantLineIndex, transcript.get(assistantLineIndex) + token);
        }
    }

    @Override
    public synchronized void onToolStarted(long turnId, String toolName, String input) {
        if (turnId != acceptingTurnId) return;
        this.status = Status.RUNNING_TOOL;
        // 工具活动另起一行，助手流式行到此为止（下一段助手 token 会另起新行）
        assistantLineIndex = -1;
        transcript.add("🛠 " + toolName + " …");
        toolLineIndex = transcript.size() - 1;
    }

    @Override
    public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        if (turnId != acceptingTurnId) return;
        this.status = Status.THINKING;
        String mark = ok ? "✓" : "✗";
        String line = "🛠 " + toolName + " " + mark;
        if (toolLineIndex >= 0 && toolLineIndex < transcript.size()) {
            transcript.set(toolLineIndex, line);
        } else {
            transcript.add(line);
        }
        toolLineIndex = -1;
    }

    @Override
    public synchronized void onTodoUpdated(long turnId, List<String> todoLines) {
        if (turnId != acceptingTurnId) return;
        todo.clear();
        todo.addAll(todoLines);
    }

    @Override
    public synchronized void onTurnComplete(long turnId) {
        if (turnId != acceptingTurnId) return;
        this.status = Status.IDLE;
        finalizeStreamingLines();
    }

    @Override
    public synchronized void onError(long turnId, Throwable error) {
        if (turnId != acceptingTurnId) return;
        this.status = Status.IDLE;
        transcript.add("⚠ 出错：" + (error == null ? "unknown" : String.valueOf(error.getMessage())));
        finalizeStreamingLines();
    }

    // ── 读访问器（View/测试用） ─────────────────────────────────────────────

    public long acceptingTurnId() { return acceptingTurnId; }
    public synchronized List<String> todoSnapshot() { return List.copyOf(todo); }

    // ── 内部 ────────────────────────────────────────────────────────────

    /** 结束在建的流式助手/工具行游标，使下一回合另起新行。调用方须持锁。 */
    private void finalizeStreamingLines() {
        assistantLineIndex = -1;
        toolLineIndex = -1;
    }
}
