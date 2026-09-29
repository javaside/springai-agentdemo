package io.github.javaside.springai.codetui.agent.goal;

import java.util.Locale;

/**
 * 评估器输出的解析结果（spec §6.3 四行协议）。
 *
 * <pre>
 * VERDICT: SATISFIED | UNSATISFIED | IMPOSSIBLE
 * REASON: &lt;一句话，语言跟随条件&gt;
 * PROGRESS: advancing | stalled
 * STATE: &lt;压缩的累积进度账本（checklist 风格）&gt;
 * </pre>
 *
 * <p>{@link #parse(String)}：逐行扫描、{@code strip()} 后<b>行首</b>大小写不敏感前缀匹配、容忍
 * 围栏代码块包裹、<b>取最后一个 VERDICT</b>（防条件文本自注入）。缺 PROGRESS 默认 {@code
 * stalled}（保守——宁可暂停也不虚度轮次）；缺 STATE 为 {@code null}（调用方沿用旧账本）；
 * 无有效 VERDICT 行（含非法取值）抛 {@link GoalProtocolException}，由调用方按独立计数熔断。
 *
 * @param outcome     结论
 * @param reason      一句话原因（语言跟随条件；缺 REASON 行时为空串）
 * @param stalled     本轮是否停滞（缺 PROGRESS 行时保守为 {@code true}）
 * @param stateLedger 累积进度账本；{@code null} 表示缺 STATE 行，沿用旧账本
 * @param rawOutput   评估器原始输出，用于 PAUSED(PROTOCOL) 时展示助诊断
 */
public record GoalVerdict(Outcome outcome, String reason, boolean stalled, String stateLedger, String rawOutput) {

    /** 评估结论。 */
    public enum Outcome {
        /** 目标已达成。 */
        SATISFIED,
        /** 尚未达成（继续推进）。 */
        UNSATISFIED,
        /** 目标不可达成。 */
        IMPOSSIBLE
    }

    private static final String VERDICT_MARKER = "VERDICT:";
    private static final String REASON_MARKER = "REASON:";
    private static final String PROGRESS_MARKER = "PROGRESS:";
    private static final String STATE_MARKER = "STATE:";
    private static final String FENCE = "```";
    private static final String ADVANCING_VALUE = "ADVANCING";
    private static final int ABBREV_MAX_CODE_POINTS = 200;

    /**
     * 解析评估器输出（见类 javadoc 的规则）。
     *
     * @param text 评估器原始输出，可为 {@code null}
     * @return 解析结果
     * @throws GoalProtocolException 无有效 VERDICT 行（缺失或取值非法）
     */
    public static GoalVerdict parse(String text) {
        String raw = text == null ? "" : text;
        Outcome verdict = null;
        String reason = "";
        Boolean stalled = null;
        String ledger = null;
        for (String line : raw.split("\\R")) {
            String s = line.strip();
            if (s.startsWith(FENCE) || s.isEmpty()) continue;   // 容忍围栏行与空行
            String upper = s.toUpperCase(Locale.ROOT);
            if (upper.startsWith(VERDICT_MARKER)) {
                try {
                    verdict = Outcome.valueOf(upper.substring(VERDICT_MARKER.length()).strip());
                } catch (IllegalArgumentException e) {
                    // 非法值：留着 verdict 不动，末尾无有效值则抛
                }
            } else if (upper.startsWith(REASON_MARKER)) {
                reason = s.substring(REASON_MARKER.length()).strip();
            } else if (upper.startsWith(PROGRESS_MARKER)) {
                // M8 收紧（保守口径，与缺行同向）：PROGRESS 值大小写不敏感地<b>恰等于</b> ADVANCING 才算推进；
                // 其余一切取值（含带尾缀的 "advancing."、拼错的 "progressing"、空值）一律 stalled——
                // 宁可误报停滞触发熔断，也不放过一轮假推进。
                stalled = !upper.substring(PROGRESS_MARKER.length()).strip().equals(ADVANCING_VALUE);
            } else if (upper.startsWith(STATE_MARKER)) {
                ledger = s.substring(STATE_MARKER.length()).strip();
            }
        }
        if (verdict == null) {
            throw new GoalProtocolException("评估输出缺少 VERDICT 行: " + abbreviate(raw));
        }
        return new GoalVerdict(verdict, reason, stalled == null ? true : stalled, ledger, raw);
    }

    /** 异常消息用：压平空白 + 码点安全截断，避免把整段输出塞进日志/UI。 */
    private static String abbreviate(String raw) {
        String flat = raw.strip().replaceAll("\\s+", " ");
        if (flat.codePointCount(0, flat.length()) <= ABBREV_MAX_CODE_POINTS) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, ABBREV_MAX_CODE_POINTS)) + "…";
    }

    /** 评估器输出不符合四行协议（无有效 VERDICT 行）。 */
    public static class GoalProtocolException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public GoalProtocolException(String message) {
            super(message);
        }
    }
}
