package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import dev.tamboui.text.CharWidth;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把已恢复会话（{@code -c} 启动）的历史消息转成一批定稿 {@link OutputLine}，喂进正常的 scrollback
 * drain 通道回放，使重开后 TUI 直观重现上次对话（仿 Claude Code 的 {@code --continue}），
 * 而不只是「已恢复 N 条」一行提示。
 *
 * <p>渲染刻意与实时通道一致，回放看起来就和当时一样：用户块 {@code ›} + 助手 markdown 正文 +
 * 紧凑的工具调用/结果标记（{@code ⏺} / {@code ⎿ ✓}）。跳过 {@code SYSTEM}（grounding 提示，本就不显示）。
 * 工具不重绘 diff（{@code raw=null} 即回退单行摘要）——历史里的原文件可能早已改变，重绘会误导。
 */
final class HistoryReplay {

    private HistoryReplay() {}

    /** 历史消息 → 回放行（不含头尾提示，由调用方补）。null/空返回空列表。 */
    static List<OutputLine> toReplayLines(List<Message> messages) {
        List<OutputLine> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (Message m : messages) {
            switch (m.getMessageType()) {
                case USER -> {
                    out.add(new OutputLine("", Kind.ASSISTANT));                 // 回合间留白，与 onUserMessage 一致
                    out.add(new OutputLine("› " + safe(m.getText()), Kind.USER));
                }
                case ASSISTANT -> {
                    String text = m.getText();
                    if (text != null && !text.isBlank()) {
                        // 逐行拆分——实时通道里助手正文经 flushStreaming 按 \n 拆成「一行一 OutputLine」，
                        // printer.assistant 只处理单行；整块多行喂进去会被 println 塌成一行（截断）。此处照抄该不变量。
                        for (String line : text.split("\n", -1)) {
                            out.add(new OutputLine(stripCr(line), Kind.ASSISTANT));
                        }
                    }
                    if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                            String s = summarize(tc.arguments());
                            out.add(new OutputLine("⏺ " + tc.name() + (s.isEmpty() ? "" : "  " + s),
                                    Kind.TOOL_START, tc.name(), null));          // raw=null → 单行摘要，不重绘 diff
                        }
                    }
                }
                case TOOL -> {
                    if (m instanceof ToolResponseMessage trm) {
                        for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                            out.add(new OutputLine("  ⎿ " + r.name() + " ✓", Kind.TOOL_OK));
                        }
                    }
                }
                default -> { /* SYSTEM：grounding 提示，跳过不回放 */ }
            }
        }
        return out;
    }

    /** 历史里的用户轮数（供头部提示，比原始事件数直观）。 */
    static long userTurns(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream().filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER).count();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** 与 {@code ConversationState.summarize} 等价的紧凑单行（折叠空白、按显示宽度截断）。 */
    private static String summarize(String toolInput) {
        if (toolInput == null) {
            return "";
        }
        String oneLine = toolInput.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(0, 200);
        }
        if (CharWidth.of(oneLine) <= 80) {
            return oneLine;
        }
        return CharWidth.substringByWidth(oneLine, 79) + "…";
    }
}
