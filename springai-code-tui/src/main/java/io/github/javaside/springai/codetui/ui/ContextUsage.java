package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.ContextStats;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 会话<b>上下文用量</b>的追踪与报告：{@code /context} 多行报告 + 状态栏 {@code " · 上下文 N%"} 后缀。
 * 有状态（{@link #cached} 节流缓存快照）但<b>不认识 tamboui</b>——经 {@code Supplier<ContextStats>} 现算源入、
 * {@code Consumer<String>} 灰色信息行出，故可脱离 {@link dev.tamboui.toolkit.app.InlineApp} 离线单测。
 *
 * <p><b>为什么抽出</b>：报告的多分支 {@code String.format} / 百分比舍入 / 缓存刷新容错，此前埋在
 * {@link CodeTuiView} 里无法单测。抽成纯 Java 类后可喂 {@link ContextStats} 断言每一行文案与分桶。
 *
 * <p><b>现算 vs 缓存</b>：{@link #report()} 读<em>实时</em> {@code source}（报告要最新）；
 * {@link #suffix()} 读<em>缓存</em> {@code cached}（状态栏每帧读，绝不每帧重算——重算要遍历全部消息 + 估算 token）。
 * {@link #refresh()} 由视图 drain 每 ~1s 调一次，把最新快照存进缓存。
 */
final class ContextUsage {

    private final Supplier<ContextStats> source;   // 现算：读一遍当前会话（估算 token）
    private final Consumer<String> sink;           // 输出：灰色信息行下沉 scrollback
    private volatile ContextStats cached = ContextStats.empty();   // 状态栏节流缓存：refresh 写、suffix 读

    ContextUsage(Supplier<ContextStats> source, Consumer<String> sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * /context：把当前会话上下文用量（事件数分桶 + 估算 token + 距自动压缩阈值）打进 scrollback（灰色信息行）。
     * 只读快照，任何时刻都可查；尚无对话时各项为 0，明确提示「尚无对话历史」。
     */
    void report() {
        ContextStats s = source.get();
        if (s == null) s = ContextStats.empty();
        sink.accept("📊 上下文用量");
        if (s.events() == 0) {
            sink.accept("  （尚无对话历史）");
            return;
        }
        sink.accept(String.format("  事件数：%,d 条（用户 %,d · 助手 %,d · 工具 %,d%s）",
                s.events(), s.userEvents(), s.assistantEvents(), s.toolEvents(),
                s.otherEvents() > 0 ? " · 其他 " + s.otherEvents() : ""));
        if (s.contextWindow() > 0) {
            sink.accept(String.format("  估算 token：%,d / %,d（占窗口 %s）",
                    s.estimatedTokens(), s.contextWindow(), pct(s.estimatedTokens(), s.contextWindow())));
        } else {
            sink.accept(String.format("  估算 token：%,d", s.estimatedTokens()));
        }
        // 每回合请求构成：系统提示词 + 会话消息 = 每回合真实发出的总 token。
        // 系统提示词烘焙在 ChatClient 里、从不进会话存储，上方「估算 token」看不见它，
        // 但它每回合都完整重发，是真实的固定开销——不写出来这笔钱就等于不存在。
        // 各分类占「每回合总请求」的比，一次 largest remainder 分配，合计恒为 100%
        // （各自四舍五入会得 99%/101%，又会对不上账）。「系统/摘要」桶为 0 时省略（与事件分桶的「其他」同款处理）。
        ContextStats.TokenBreakdown b = s.tokens();
        if (b == null) b = ContextStats.TokenBreakdown.empty();
        boolean showSystem = b.systemTokens() > 0;
        if (s.systemPromptTokens() > 0) {
            long[] parts = showSystem
                    ? new long[]{s.systemPromptTokens(), b.userTokens(), b.assistantTokens(), b.toolTokens(), b.systemTokens()}
                    : new long[]{s.systemPromptTokens(), b.userTokens(), b.assistantTokens(), b.toolTokens()};
            String[] names = showSystem
                    ? new String[]{"系统提示词", "用户", "助手", "工具", "系统/摘要"}
                    : new String[]{"系统提示词", "用户", "助手", "工具"};
            long total = 0L;
            for (long p : parts) total += p;
            int[] pcts = percents(parts);
            StringBuilder line = new StringBuilder("  每回合请求（合计 ")
                    .append(String.format("%,d", total)).append(" token，系统提示词每回合固定重发）：");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) line.append(" · ");
                line.append(names[i]).append(' ').append(String.format("%,d", parts[i]))
                        .append('（').append(pcts[i]).append('%').append('）');
            }
            sink.accept(line.toString());
        } else if (b.userTokens() + b.assistantTokens() + b.toolTokens() + b.systemTokens() > 0) {
            // 无系统提示词数据（桩路径）：分类占比以消息总数为分母。
            long[] parts = showSystem
                    ? new long[]{b.userTokens(), b.assistantTokens(), b.toolTokens(), b.systemTokens()}
                    : new long[]{b.userTokens(), b.assistantTokens(), b.toolTokens()};
            String[] names = showSystem
                    ? new String[]{"用户", "助手", "工具", "系统/摘要"}
                    : new String[]{"用户", "助手", "工具"};
            int[] pcts = percents(parts);
            StringBuilder cls = new StringBuilder("  消息分类：");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) cls.append(" · ");
                cls.append(names[i]).append(' ').append(String.format("%,d", parts[i]))
                        .append('（').append(pcts[i]).append('%').append('）');
            }
            sink.accept(cls.toString());
        }
        // 缓存命中率：有计费输入才打印。命中/计费输入用 %,d 原值（小会话不足千也不显示成 0）。
        if (s.cacheHitPercent() != null) {
            sink.accept(String.format("  缓存命中率：%d%%（命中 %,d / 计费输入 %,d token）",
                    s.cacheHitPercent(), s.cacheReadTokens(), s.billedInputTokens()));
        }
        // 视觉占用单列一行，紧跟文本估算之后：图片从不进会话存储，上面那笔 JTokkit 估算<b>看不见它们</b>，
        // 恒比真实请求小（最多差 6k）。不写出来这笔钱就等于不存在，用户没法管理。
        // 用 %,d 原值而不是 /1000 的「k」：一张小图不足 1000 token 会显示成「0k」，读起来像不要钱。
        // 口径是「本回合累计」而非「上次请求」：一个回合有几十次工具迭代，按请求记则用户按下
        // /context 那一刻几乎必然是 0（额度用尽后每次都兑现 0、回合结束后引用已成历史更不兑现）。
        // 顺带写出每回合上限，用户才读得出还剩多少额度——这也是把口径对齐到 VisionBudget 的意义。
        // 严格说单位是「张·次」（同一张图跨迭代重发计两次，与上限同一口径），面板上从简写作「张」。
        if (s.visionImages() > 0) {
            sink.accept(String.format("  视觉图片：本回合 %,d 张 · 约 %,d token（每回合上限 %d 张，不计入上方文本估算）",
                    s.visionImages(), s.visionTokens(), VisionBudget.MAX_TURN_DELIVERIES));
        }
        if (s.tokenThreshold() > 0) {
            sink.accept(String.format("  自动压缩：达 %,d token 触发（当前 %s）· 按 token 保留近期完整回合",
                    s.tokenThreshold(), pct(s.estimatedTokens(), s.tokenThreshold())));
        }
        if (s.manualKeepEvents() > 0) {
            sink.accept("  手动 /compact：立即按 token 更激进压缩");
        }
    }

    /**
     * 重算状态栏用的上下文用量快照（视图 drain 里节流调用，绝不每帧）。用量是辅助信息：
     * 估算失败绝不能拖垮主 UI，异常时静默保留旧值。
     */
    void refresh() {
        try {
            ContextStats s = source.get();
            if (s != null) cached = s;
        } catch (RuntimeException ignore) {
            // 尽力而为：保留上一次快照，不影响状态栏其余内容
        }
    }

    /** 状态栏上下文用量后缀（如 {@code " · 上下文 3% · 缓存命中 80%"}）；尚无对话时返回空串。 */
    String suffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0) return "";
        StringBuilder sb = new StringBuilder();
        if (s.contextWindow() > 0) {
            sb.append(" · 上下文 ").append(pct(s.estimatedTokens(), s.contextWindow()));
        }
        if (s.cacheHitPercent() != null) {
            sb.append(" · 缓存命中 ").append(s.cacheHitPercent()).append("%");
        }
        return sb.toString();
    }

    /** 忙碌态状态栏只需缓存命中率；无计费输入时不追加任何分隔符。 */
    String cacheHitSuffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0 || s.cacheHitPercent() == null) return "";
        return " · 缓存命中 " + s.cacheHitPercent() + "%";
    }

    /** 占比（part/whole）取整成百分号字符串；whole<=0 视为 0%。 */
    private static String pct(long part, long whole) {
        if (whole <= 0) return "0%";
        return Math.round(part * 100.0 / whole) + "%";
    }

    /**
     * 把非负 token 桶换算成<b>总和恒为 100</b> 的整数百分比（largest remainder）：
     * 先截断取整，再把余量（最多 {@code parts.length-1} 点）按小数部分从大到小各补 1%。
     * 每项都落在自己的 floor/ceil 之间、永不越界——比起「各自四舍五入、最后一项凑数」，
     * 不会出现 99.5% 四舍五入成 100% 后把最后一项逼成 -1% 的边角。
     */
    private static int[] percents(long[] parts) {
        long total = 0L;
        for (long p : parts) total += p;
        int[] pct = new int[parts.length];
        if (total <= 0) return pct;
        double[] exact = new double[parts.length];
        int sum = 0;
        for (int i = 0; i < parts.length; i++) {
            exact[i] = parts[i] * 100.0 / total;
            pct[i] = (int) exact[i];
            sum += pct[i];
        }
        for (int remain = 100 - sum; remain > 0; remain--) {
            int best = 0;
            for (int i = 1; i < parts.length; i++) {
                if (exact[i] - pct[i] > exact[best] - pct[best]) best = i;
            }
            pct[best]++;
        }
        return pct;
    }
}
