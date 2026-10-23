package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.goal.GoalText;
import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.session.UserTextStripper;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine.Kind;
import dev.tamboui.text.TerminalWidth;   // 口径：组合序列（keycap/VS16）按终端实际渲染宽度
import org.springaicommunity.agent.tools.TodoWriteTool.Todos;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 把已恢复会话（{@code -c} 启动）的历史消息转成一批定稿 {@link OutputLine}，喂进正常的 scrollback
 * 输出队列通道回放，使重开后 TUI 直观重现上次对话（仿 Claude Code 的 {@code --continue}），
 * 而不只是「已恢复 N 条」一行提示。
 *
 * <p>渲染刻意与实时通道一致，回放看起来就和当时一样：用户块 {@code ›} + 助手 markdown 正文 +
 * 紧凑的工具调用/结果标记（{@code ⏺} / {@code ⎿ ✓}）。跳过 {@code SYSTEM}（grounding 提示，本就不显示）。
 * 工具不重绘 diff（{@code raw=null} 即回退单行摘要）——历史里的原文件可能早已改变，重绘会误导。
 */
final class HistoryReplay {

    private HistoryReplay() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 历史消息 → 回放行（不含头尾提示，由调用方补）。null/空返回空列表。 */
    static List<OutputLine> toReplayLines(List<Message> messages) {
        List<OutputLine> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (Message m : messages) {
            switch (m.getMessageType()) {
                case USER -> {
                    String stored = safe(m.getText());
                    // goal 合成消息（GoalText）：自动轮与评估结论标记都不是用户说的话——
                    // 各渲一行 INFO 指路（详情看 goal 轨迹/评估标记聚合行），不重放正文为用户块，
                    // 否则 -c 之后满屏重复的循环 prompt。
                    if (GoalText.isContinueMessage(stored)) {
                        out.add(new OutputLine("◎ goal 继续（自动轮，详情见评估轨迹）", Kind.INFO));
                        continue;
                    }
                    if (stored.startsWith(GoalText.EVAL_OPEN)) {
                        out.add(new OutputLine("◎ goal 评估："
                                + GoalText.unwrapEvaluation(stored).replaceAll("\\R+", " "), Kind.INFO));
                        continue;
                    }
                    out.add(new OutputLine("", Kind.ASSISTANT));                 // 回合间留白，与 onUserMessage 一致
                    // 会话持久化的是「注入后」的有效文本；实时 UI 只显示用户原文（onUserMessage 传 text 而非
                    // effectiveText），回放须一致，故剥掉 CodingAgent.injectSkill 注入的 <skill_instruction> 前缀。
                    // 贴图注入的引用块同理：存的是八行机器格式，回放须渲成一行 📎。
                    // 先剥技能前缀再剥引用块——两者互不重叠，固定顺序便于推理。
                    // 插话的 [interjection] 包裹同理，且更要紧：里面那段是<b>给模型的行为指引</b>，
                    // 不剥就成了「用户说过这句话」（拆包裹与包裹同住 InterjectionText）。
                    // 单行 OutputLine 即可——userBlock 自身按 \n 拆行、软折（与实时同路径）。
                    // 超长用户块与实时 onUserMessage 同规则折叠（foldUserEcho）：重开会话回放
                    // 照登全文的话，粘贴折叠只是把刷屏从当时挪到了重开之后。
                    out.add(new OutputLine("› " + ConversationState.foldUserEcho(stripFileReferences(
                            stripSkillInstruction(InterjectionText.unwrap(stored)))), Kind.USER));
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

    /** 恢复会话时重建 todo 面板：找历史里最后一次 TodoWrite 调用，取其参数还原成显示行（格式与
     *  {@link AgentTools#toLines} 完全一致——直接复用，不新写一份格式化逻辑）。找不到、或参数反序列化
     *  失败（容忍老版本残留数据）都返回空列表，不让恢复流程崩掉。 */
    static List<String> lastTodoSnapshot(List<Message> messages) {
        if (messages == null) {
            return List.of();
        }
        String lastArgs = null;
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    if ("TodoWrite".equals(tc.name())) {
                        lastArgs = tc.arguments();
                    }
                }
            }
        }
        if (lastArgs == null) {
            return List.of();
        }
        try {
            return AgentTools.toLines(MAPPER.readValue(lastArgs, Todos.class));
        } catch (Exception ignore) {
            return List.of();
        }
    }

    /**
     * 历史里的用户轮数（供头部提示，比原始事件数直观）。
     *
     * <p><b>插话不算一轮</b>：它是回合<b>进行中</b>补的一条 user 消息，属于当时那一轮，
     * 不是新起的一轮。算进去的话「已恢复 N 轮对话」会比用户记得的多出几轮。
     *
     * <p><b>goal 合成消息同样不算</b>（Task 13）：自动轮（{@code [goal 继续 N/M]}）与评估结论
     * 标记（{@code [goal 评估]}）都是程序合成、不是用户说话——一个跑满 25 轮的 goal 会让
     * 「已恢复 N 轮」凭空多出 25+ 条。
     */
    static long userTurns(List<Message> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream()
                .filter(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                .filter(m -> !safe(m.getText()).startsWith(InterjectionText.OPEN))
                .filter(m -> !GoalText.isContinueMessage(safe(m.getText())))
                .filter(m -> !safe(m.getText()).startsWith(GoalText.EVAL_OPEN))
                .count();
    }

    /**
     * 历史里是否有 goal 痕迹（任一 USER 消息是自动轮或评估结论标记）——恢复方
     * {@code ConversationState.replayHistory} 据此提示「上次会话有未完成 goal，已失效」：
     * goal 只活在进程内存态（spec §1「退出即失」），历史里有痕迹即说明它没跑完就没了。
     */
    static boolean hasGoalTraces(List<Message> messages) {
        if (messages == null) {
            return false;
        }
        for (Message m : messages) {
            if (m.getMessageType() != org.springframework.ai.chat.messages.MessageType.USER) continue;
            String t = safe(m.getText());
            if (GoalText.isContinueMessage(t) || t.startsWith(GoalText.EVAL_OPEN)) return true;
        }
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // 实现已搬至 agent.session.UserTextStripper（单一出处）：agent 侧的会话摘要也要做同样的剥离，
    // 而依赖方向是 ui → agent，搬过去后两边共用。此处保留包级签名委托，toReplayLines 与既有测试零改动。
    static String stripSkillInstruction(String userText) { return UserTextStripper.stripSkillInstruction(userText); }

    static String stripFileReferences(String text) { return UserTextStripper.stripFileReferences(text); }

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
        if (TerminalWidth.of(oneLine) <= 80) {
            return oneLine;
        }
        return TerminalWidth.substringByWidth(oneLine, 79) + "…";
    }
}
