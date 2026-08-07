package io.github.javaside.springai.codetui.agent;

/**
 * 插话落库文本的格式：{@code [interjection]} 包裹 + 给模型的行为指引 + {@code ---} + 用户原话。
 *
 * <p><b>为什么包裹和拆包裹要放在一个类里</b>：这两件事必须严丝合缝，而它们错开时<b>不会报错</b>。
 * 这个项目已经因此吃过一次亏——包裹在 {@code InterjectingChatModel}，而 {@code -c} 回放那边
 * 压根没人拆，于是「用户在任务执行中插话……」这段<b>给模型的指引</b>被当成用户原话回放了出来。
 * 同类护栏 {@code <skill_instruction>} 与 {@code [file reference]} 都装了，唯独这条漏了。
 *
 * <p>沿用 {@code FileReference} 的方括号成对标签约定。
 */
public final class InterjectionText {

    public static final String OPEN = "[interjection]";
    public static final String CLOSE = "[/interjection]";

    /** 指引与原话之间的分隔，避免模型把指引当成用户说的。也是 {@link #unwrap} 定位原话的依据。 */
    static final String SEPARATOR = "---";

    /**
     * 给模型的行为指引。没有它，模型看到 tool 结果之后突然冒出一条 user 消息，很可能判定
     * 「上一轮结束了，这是新任务」——丢下没做完的工具循环去做新事，或者提前收尾。
     */
    static final String GUIDE = """
            用户在任务执行中插话，未完成的工作仍在进行中。
            若与当前方向冲突就调整，否则先把手头的做完。""";

    private InterjectionText() {
    }

    /** 原话 → 落库/送模型的文本。注入与回合末补历史都调它，两处必须逐字一致。 */
    public static String wrap(String raw) {
        return OPEN + "\n" + GUIDE + "\n" + SEPARATOR + "\n" + raw + "\n" + CLOSE;
    }

    /**
     * 落库文本 → 用户原话，供 {@code -c} 回放显示。不是包裹形态的一律<b>原样返回</b>。
     *
     * <p><b>保底原样，绝不猜</b>（与 {@code HistoryReplay.stripSkillInstruction} 同纪律）：
     * 缺闭标记、缺分隔符、或只是正文里恰好提到了这个词——任何一种都直接返还原文。
     * 猜错的代价是把用户真说过的话删掉，比多显示一段包裹严重得多。
     */
    public static String unwrap(String stored) {
        if (stored == null || !stored.startsWith(OPEN)) {
            return stored;
        }
        int close = stored.lastIndexOf(CLOSE);
        if (close < 0) {
            return stored;                                  // 残缺块：不误删后面的正文
        }
        // 分隔符必须是独占一行的那个，且要在闭标记之前——原话里出现 "---" 不该被当成分隔
        int sep = stored.indexOf("\n" + SEPARATOR + "\n");
        if (sep < 0 || sep >= close) {
            return stored;                                  // 格式对不上：绝不猜哪一段是用户原话
        }
        int from = sep + SEPARATOR.length() + 2;            // 跳过前后两个 \n
        return stored.substring(from, close).stripTrailing();
    }
}
