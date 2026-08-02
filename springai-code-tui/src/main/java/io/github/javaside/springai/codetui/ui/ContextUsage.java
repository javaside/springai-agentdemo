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
            sink.accept(String.format("  自动压缩：达 %,d token 触发（当前 %s）· 保留最近 %,d 条",
                    s.tokenThreshold(), pct(s.estimatedTokens(), s.tokenThreshold()), s.autoKeepEvents()));
        }
        if (s.manualKeepEvents() > 0) {
            sink.accept(String.format("  手动 /compact：立即压缩，保留最近 %,d 条（更激进）", s.manualKeepEvents()));
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

    /** 状态栏上下文用量后缀（如 {@code " · 上下文 3%"}，占窗口比例）；尚无对话/窗口未知时返回空串。 */
    String suffix() {
        ContextStats s = cached;
        if (s == null || s.events() == 0 || s.contextWindow() <= 0) return "";
        return " · 上下文 " + pct(s.estimatedTokens(), s.contextWindow());
    }

    /** 占比（part/whole）取整成百分号字符串；whole<=0 视为 0%。 */
    private static String pct(long part, long whole) {
        if (whole <= 0) return "0%";
        return Math.round(part * 100.0 / whole) + "%";
    }
}
