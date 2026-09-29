package io.github.javaside.springai.codetui.agent.goal;

import java.util.regex.Pattern;

/**
 * goal 合成消息文本：自动轮前缀标记、评估结论标记、评估器素材的尾部截断。
 *
 * <p>自动轮的 user message 以 {@code [goal 继续 N/M]} 开头（spec §4.1）——HistoryReplay 靠它把
 * 自动轮排除在对话轮数之外、并渲成一行 INFO 而不重放正文；评估结论以
 * {@value #EVAL_OPEN} / {@value #EVAL_CLOSE} 包裹后独立落库（spec §3.4），恢复时同样只渲一行。
 *
 * <p>{@link #tail(String, int)} 供评估器素材（agent 末文本 ≤2000 字符）使用：尾部截断且
 * <b>码点对齐</b>——绝不劈开 UTF-16 代理对，否则窄模型收到孤立 surrogate 可能直接报错。
 */
public final class GoalText {

    /** 评估结论合成块的起始标记（行首判定依据）。 */
    public static final String EVAL_OPEN = "[goal 评估]";

    /** 评估结论合成块的结束标记。 */
    public static final String EVAL_CLOSE = "[/goal 评估]";

    private static final String CONTINUE_MARKER = "[goal 继续 ";
    private static final Pattern CONTINUE_PREFIX = Pattern.compile("^\\[goal 继续 \\d+/\\d+\\]");
    private static final String REASON_SEPARATOR = "：";

    private GoalText() {
    }

    /**
     * 自动轮 user message 的前缀标记 {@code [goal 继续 N/M]}（英文数字形式，语言仅作标记）。
     *
     * @param n 已用自动轮次
     * @param m 自动轮上限
     */
    public static String continuePrefix(int n, int m) {
        return CONTINUE_MARKER + n + "/" + m + "]";
    }

    /** 该文本是否为自动轮消息（行首带 {@link #continuePrefix} 标记）。 */
    public static boolean isContinueMessage(String text) {
        return text != null && CONTINUE_PREFIX.matcher(text.strip()).find();
    }

    /**
     * 把评估结论包成合成块：{@code [goal 评估]\n<verdictLine>：<reason>\n[/goal 评估]}。
     *
     * @param verdictLine 结论行（如 {@code UNSATISFIED}）
     * @param reason      一句话原因
     */
    public static String wrapEvaluation(String verdictLine, String reason) {
        return EVAL_OPEN + "\n" + verdictLine + REASON_SEPARATOR + reason + "\n" + EVAL_CLOSE;
    }

    /** 取合成块内容（未包裹的原样返回）。 */
    public static String unwrapEvaluation(String text) {
        if (text == null) return "";
        String s = text.strip();
        if (s.startsWith(EVAL_OPEN) && s.endsWith(EVAL_CLOSE)) {
            return s.substring(EVAL_OPEN.length(), s.length() - EVAL_CLOSE.length()).strip();
        }
        return text;
    }

    /**
     * 取文本尾部至多 {@code maxChars} 个 char，且不劈开代理对。
     *
     * @param text     原文（{@code null} → {@code ""}）
     * @param maxChars 字符数预算；不足以容纳尾部码点时退让到更短（宁可少截也不劈对）
     * @return 尾部子串；原文不超预算时原样返回（同一引用）
     */
    public static String tail(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        int end = text.length();
        int budget = maxChars;
        while (budget > 0 && end > 0) {
            int cp = text.codePointBefore(end);
            int chars = Character.charCount(cp);
            if (chars > budget) break;          // 劈开代理对：不取
            end -= chars;
            budget -= chars;
        }
        return text.substring(end);
    }
}
