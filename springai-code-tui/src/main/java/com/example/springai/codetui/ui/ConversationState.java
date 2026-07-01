package com.example.springai.codetui.ui;

import java.util.ArrayList;
import java.util.List;

/** 线程安全共享状态（本 Task 最小可用；Task 4 补流式缓冲/todo/turnId 过滤并加并发测试）。 */
public final class ConversationState {
    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    private final List<String> transcript = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";   // 状态栏瞬时提示（如「已取消」）；下次输入即清除

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
    /** Esc 取消当前回合：状态回 IDLE（Task 4 会叠加 turnId 过滤）。 */
    public void cancelCurrent() { this.status = Status.IDLE; }
}
