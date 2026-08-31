package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.VisionBudget;
import io.github.javaside.springai.codetui.agent.session.ContextStats;

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
 * {@link #refresh()} 由 {@code ui.update.ContextUsageRefreshController} 按需（事件标脏 + 防抖）调度。
 *
 * <p><b>跨包可见性</b>：类/构造器/{@link #refresh()} 为 public——controller 在 {@code ui.update} 包，
 * 需要构造被测对象并消费 refresh 的「可见数据是否变化」返回值；其余成员保持包内可见。
 */
public final class ContextUsage {

    private final Supplier<ContextStats> source;   // 现算：读一遍当前会话（估算 token）
    private final Consumer<String> sink;           // 输出：灰色信息行下沉 scrollback
    private volatile ContextStats cached = ContextStats.empty();   // 状态栏节流缓存：refresh 写、suffix 读

    public ContextUsage(Supplier<ContextStats> source, Consumer<String> sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * /context：把当前会话上下文用量（事件数分桶 + 上下文占用总数/构成占比 + 距自动压缩阈值）打进 scrollback（灰色信息行）。
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
        // 唯一的总数口径：上下文占用 = 系统提示词 + 会话消息（就是当前上下文里躺着的全部 token，
        // 也是每回合真实发出去的量）。系统提示词烘焙在 ChatClient 里、从不进会话存储，单列一个
        // 「会话估算」行会与它互相矛盾（两行数字不同，用户无从对账）。
        // 占窗口也按这个总数算，与状态栏「上下文 N%」同一口径。
        if (s.contextWindow() > 0) {
            sink.accept(String.format("  上下文占用：%,d / %,d token（占窗口 %s）",
                    s.perTurnTokens(), s.contextWindow(), pct(s.perTurnTokens(), s.contextWindow())));
        } else {
            sink.accept(String.format("  上下文占用：%,d token", s.perTurnTokens()));
        }
        // 构成逐行展示（挤在一行长串里读不动）：系统提示词 + 消息四桶，各占上下文占用的比，
        // 一次 largest remainder 分配、合计恒为 100%（各自四舍五入会得 99%/101%，又对不上账）。
        // 桩路径 systemPromptTokens=0 时分母即消息总数；「系统/摘要」桶为 0 时省略（与事件分桶的「其他」同款处理）。
        ContextStats.TokenBreakdown b = s.tokens() == null ? ContextStats.TokenBreakdown.empty() : s.tokens();
        boolean showSystem = b.systemTokens() > 0;
        boolean includePrompt = s.systemPromptTokens() > 0;
        if (includePrompt || b.userTokens() + b.assistantTokens() + b.toolTokens() + b.systemTokens() > 0) {
            long[] parts;
            String[] names;
            if (includePrompt) {
                parts = showSystem
                        ? new long[]{s.systemPromptTokens(), b.userTokens(), b.assistantTokens(), b.toolTokens(), b.systemTokens()}
                        : new long[]{s.systemPromptTokens(), b.userTokens(), b.assistantTokens(), b.toolTokens()};
                names = showSystem
                        ? new String[]{"系统提示词", "用户消息", "助手消息", "工具结果", "系统/摘要"}
                        : new String[]{"系统提示词", "用户消息", "助手消息", "工具结果"};
            } else {
                parts = showSystem
                        ? new long[]{b.userTokens(), b.assistantTokens(), b.toolTokens(), b.systemTokens()}
                        : new long[]{b.userTokens(), b.assistantTokens(), b.toolTokens()};
                names = showSystem
                        ? new String[]{"用户消息", "助手消息", "工具结果", "系统/摘要"}
                        : new String[]{"用户消息", "助手消息", "工具结果"};
            }
            long total = 0L;
            for (long p : parts) total += p;
            int[] pcts = percents(parts);
            sink.accept(String.format("  构成（合计 %,d token）：", total));
            for (int i = 0; i < parts.length; i++) {
                StringBuilder line = new StringBuilder("    ").append(names[i]).append(' ')
                        .append(String.format("%,d", parts[i])).append(" · ").append(pcts[i]).append('%');
                if (includePrompt && i == 0) line.append("（每回合固定重发）");
                sink.accept(line.toString());
            }
        }
        // 缓存命中率：有计费输入才打印。命中/计费输入用 %,d 原值（小会话不足千也不显示成 0）。
        if (s.cacheHitPercent() != null) {
            sink.accept(String.format("  缓存命中率：%d%%（命中 %,d / 计费输入 %,d token）",
                    s.cacheHitPercent(), s.cacheReadTokens(), s.billedInputTokens()));
        }
        // 视觉占用单列一行，紧跟文本合计之后：图片从不进会话存储，上面那笔估算<b>看不见它们</b>，
        // 恒比真实请求小（最多差 6k）。不写出来这笔钱就等于不存在，用户没法管理。
        // 用 %,d 原值而不是 /1000 的「k」：一张小图不足 1000 token 会显示成「0k」，读起来像不要钱。
        // 口径是「本回合累计」而非「上次请求」：一个回合有几十次工具迭代，按请求记则用户按下
        // /context 那一刻几乎必然是 0（额度用尽后每次都兑现 0、回合结束后引用已成历史更不兑现）。
        // 顺带写出每回合上限，用户才读得出还剩多少额度——这也是把口径对齐到 VisionBudget 的意义。
        // 严格说单位是「张·次」（同一张图跨迭代重发计两次，与上限同一口径），面板上从简写作「张」。
        if (s.visionImages() > 0) {
            sink.accept(String.format("  视觉图片：本回合 %,d 张 · 约 %,d token（每回合上限 %d 张，不计入上方合计）",
                    s.visionImages(), s.visionTokens(), VisionBudget.MAX_TURN_DELIVERIES));
        }
        if (s.tokenThreshold() > 0) {
            // 压缩阈值按「会话消息」（不含系统提示词）判定——压缩删的也是消息，系统提示词不在其列。
            // 分子写成原值：与上方构成里的消息项之和对得上账，不写原值则「50% 是谁的 50%」无解。
            sink.accept(String.format("  自动压缩：会话消息达 %,d token 触发（当前 %,d · %s）· 按 token 保留近期完整回合",
                    s.tokenThreshold(), s.estimatedTokens(), pct(s.estimatedTokens(), s.tokenThreshold())));
        }
        if (s.manualKeepEvents() > 0) {
            sink.accept("  手动 /compact：立即按 token 更激进压缩");
        }
    }

    /**
     * 重算状态栏用的上下文用量快照（按需调度：{@code ui.update.ContextUsageRefreshController}，
     * 绝不每帧）。用量是辅助信息：估算失败绝不能拖垮主 UI，异常时静默保留旧值。
     *
     * <p><b>返回值（Task 6）</b>：仅当缓存的<b>可见数据</b>真实变化时返回 {@code true}——
     * 也就是 {@link #suffix()} / {@link #cacheHitSuffix()} / {@link #report()} 全部输出会变的情况。
     * 判变用 record 自带的 equals（全部组件逐一比较），它是可见口径的<b>超集</b>，方向安全
     * （多通知、不漏通知）：可见数据变化 ⇒ 必有渲染组件变化 ⇒ equals 不相等 ⇒ true；
     * 反向只可能「多通知」——仅未渲染组件变化时也返回 true、多一次重画，可接受。
     * 未渲染组件只有 {@code autoKeepEvents}（{@code manualKeepEvents} 有「手动 /compact」行，
     * {@code autoKeepEvents} 无任何输出），且生产取值为常量
     * （{@code AgentTools.MAX_EVENTS_TO_KEEP}，编译期定死、不随会话变化），不产生实际噪音。
     * 故等值但身份不同的新快照返回 {@code false}，不会触发无意义的 UI 重画。
     * null 快照沿旧语义<b>不更新缓存</b>（返回 {@code false}）；异常同样保留旧快照并返回
     * {@code false}。
     */
    public boolean refresh() {
        try {
            ContextStats next = source.get();
            if (next == null) {
                return false;   // 旧语义保留：null 不更新缓存（视为无变化，勿把状态栏重置回空）
            }
            ContextStats prev = cached;
            if (next.equals(prev)) {
                return false;   // 可见口径一字未变：不重写 volatile（免无谓的内存屏障），也不触发 UI 重画
            }
            cached = next;
            return true;
        } catch (RuntimeException ignore) {
            // 尽力而为：保留上一次快照，不影响状态栏其余内容
            return false;
        }
    }

    /** 状态栏上下文用量后缀（如 {@code " · 上下文 3% · 缓存命中 80%"}）；尚无对话时返回空串。 */
    String suffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0) return "";
        StringBuilder sb = new StringBuilder();
        if (s.contextWindow() > 0) {
            // 与 /context 报告同一分母（上下文占用，含系统提示词），两处百分比不会互相矛盾。
            sb.append(" · 上下文 ").append(pct(s.perTurnTokens(), s.contextWindow()));
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
