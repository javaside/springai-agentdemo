package io.github.javaside.springai.codetui.agent.session;

import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.media.FileReference;

/**
 * 会话里存的「注入后」用户文本 → 用户原文的剥离函数，从 {@code ui.HistoryReplay} 搬移而来，
 * {@code HistoryReplay} 委托本类——单一出处；agent.session 的会话摘要需要剥离而不能反向依赖 ui 包
 * （依赖方向要求 ui → agent）。
 */
public final class UserTextStripper {

    private UserTextStripper() {}

    /** 三步剥离的组合：插话包裹 → 技能前缀 → 引用块。顺序与 HistoryReplay.toReplayLines 一致（互不重叠，固定顺序便于推理）。 */
    public static String stripAll(String stored) {
        return stripFileReferences(stripSkillInstruction(InterjectionText.unwrap(stored)));
    }

    /**
     * 剥掉手动 {@code /skill} 注入到用户文本前的 {@code <skill_instruction>…</skill_instruction>} 块，只留用户原文，
     * 使 {@code -c} 回放的用户块与实时一致（实时 {@code onUserMessage} 收到的是原文，注入只作用于发给模型的文本）。
     *
     * <p>注入格式见 {@code CodingAgent.injectSkill}：{@code "<skill_instruction>\n" + body + "\n</skill_instruction>\n\n" + text}。
     * 对新旧会话都适用——即便 body 内含转义残留，闭合标记与其后的换行分隔仍是真实字符。非注入文本原样返回。
     */
    public static String stripSkillInstruction(String userText) {
        if (userText == null || !userText.startsWith("<skill_instruction>")) {
            return userText;
        }
        int close = userText.indexOf("</skill_instruction>");
        if (close < 0) {
            return userText;                                    // 不完整：保底原样，不误删
        }
        int after = close + "</skill_instruction>".length();
        while (after < userText.length()
                && (userText.charAt(after) == '\n' || userText.charAt(after) == '\r')) {
            after++;                                            // 跳过 injectSkill 附加的 "\n\n" 分隔（容忍 \r\n）
        }
        return userText.substring(after);
    }

    /**
     * 把引用块换成一行 {@code 📎 name (w×h)}。
     *
     * <p>与 {@link #stripSkillInstruction} 同一个理由：会话持久化的是「注入后」的有效文本，
     * 而实时 UI 只显示用户原文（{@code onUserMessage} 收到的是注入前的），回放须一致。
     * 不处理的话 {@code -c} 之后每张图都甩八行机器格式给用户看。
     *
     * <p>残缺块（有开标记无闭标记）<b>保底原样</b>，不误删后面的正文——与
     * {@link #stripSkillInstruction} 同纪律。
     */
    public static String stripFileReferences(String text) {
        if (text == null || !text.contains(FileReference.OPEN)) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int open = text.indexOf(FileReference.OPEN, from);
            if (open < 0) {
                break;
            }
            int close = text.indexOf(FileReference.CLOSE, open);
            if (close < 0) {
                break;                                          // 残缺：剩余部分原样收尾
            }
            int end = close + FileReference.CLOSE.length();
            out.append(text, from, open).append(summarise(text.substring(open, end)));
            from = end;
        }
        out.append(text.substring(from));
        return out.toString();
    }

    /** 一个块 → 一行。缺字段就少显示，不报错（历史块的字段集会随版本变）。 */
    private static String summarise(String block) {
        String name = field(block, "name");
        String dim = field(block, "dimensions");
        if (name == null) {
            name = "图片";
        }
        return "📎 " + name + (dim == null ? "" : " (" + dim.replace("x", "×") + ")");
    }

    private static String field(String block, String key) {
        for (String line : block.split("\n")) {
            if (line.startsWith(key + ": ")) {
                return line.substring(key.length() + 2).trim();
            }
        }
        return null;
    }
}
