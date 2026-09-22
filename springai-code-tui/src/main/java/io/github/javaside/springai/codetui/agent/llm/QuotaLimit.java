package io.github.javaside.springai.codetui.agent.llm;

import java.time.Instant;
import java.util.Optional;

/**
 * 智谱 Coding Plan 限额错误的检测结果（纯数据，spec §3.1）：{@code code} 为业务码字符串，
 * {@code resetAt} 为从 message 解析出的重置时刻——<b>可为 null</b>（文案改版/解析失灵时的
 * 安全网形态，调用方按普通 429 退化处理，行为退回现状）。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public record QuotaLimit(String code, Instant resetAt) {

    /** 解析不出重置时刻的形态（code 命中但 message 无时间）。 */
    public static QuotaLimit withoutResetAt(String code) {
        return new QuotaLimit(code, null);
    }

    /** resetAt 的 Optional 视图（调用方免手工 null 判）。 */
    public Optional<Instant> resetAtOpt() {
        return Optional.ofNullable(resetAt);
    }
}
