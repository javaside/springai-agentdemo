package io.github.javaside.springai.codetui.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 {@code provider:model} 记录「摘要输入量」的已验证区间,供压缩策略跨次学习模型的真实容量。
 * 进程内共享(auto/manual 两条策略同用一个实例——校准是模型的属性,不是策略实例的属性),不持久化。
 *
 * <p>记录口径:值是<b>用户文本估计量</b>(estimate(text) 或切块 budget),不含 system prompt 与消息封装
 * ——与策略比对时的 E 同口径,自洽。
 */
final class CalibrationState {

    /** 保守起点:任何现代模型窗口都装得下,且高于策略的安全阀下限 16k。<b>假设值</b>,非验证值。 */
    static final long SAFE_FALLBACK_BUDGET = 32_000L;

    /**
     * 不可变区间。{@code knownGood}=已成功的最大输入量;{@code goodProven}=knownGood 是否经过真实成功验证
     * (初始 32k 是假设,数字解析的下限钳制不能拿假设当证据);{@code knownBad}=已失败的最小输入量(null=+∞)。
     */
    record Interval(long knownGood, boolean goodProven, Long knownBad) {
        static final Interval INITIAL = new Interval(SAFE_FALLBACK_BUDGET, false, null);

        /** 数字解析/探测减半的下限:只钳到「已证明」的安全水平;未证明时到 1(即不钳)。 */
        long provenFloor() { return goodProven ? knownGood : 1L; }
    }

    private final ConcurrentHashMap<String, Interval> byModel = new ConcurrentHashMap<>();

    Interval get(String key) {
        return byModel.getOrDefault(key, Interval.INITIAL);
    }

    /** 单调:knownGood 只涨。成功量 ≥ knownBad 说明旧失败观察过期(窗口变大),knownBad 清空。 */
    void recordGood(String key, long value) {
        byModel.compute(key, (k, old) -> {
            Interval base = old == null ? Interval.INITIAL : old;
            long good = Math.max(base.knownGood(), value);
            Long bad = base.knownBad() != null && base.knownBad() <= good ? null : base.knownBad();
            return new Interval(good, true, bad);
        });
    }

    /** 单调:knownBad 只降。失败量 ≤ knownGood 说明窗口中途变小,以最新失败为准收缩下界并撤销证明。 */
    void recordBad(String key, long value) {
        byModel.compute(key, (k, old) -> {
            Interval base = old == null ? Interval.INITIAL : old;
            long bad = base.knownBad() == null ? value : Math.min(base.knownBad(), value);
            if (base.knownGood() >= bad) {
                return new Interval(Math.max(1L, bad - 1), false, bad);
            }
            return new Interval(base.knownGood(), base.goodProven(), bad);
        });
    }

    /** 测试隔离用。 */
    void reset() {
        byModel.clear();
    }
}
