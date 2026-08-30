package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>可续折行</b>（fix round I-1）：把「一条逻辑行 → 全部物理段」的一次性物化，改成
 * 「每次只折出<b>下一段</b>」的增量推进。折行语义与 {@link TextWrap} / {@code CodeTuiView.wrapSegments}
 * 一致（同用 {@link CharWidth#substringByWidth} 逐段截取、宽字符不切半、样式跨拆分点保留），
 * 区别只在物化时机：TextWrap 返回完整 List（调用方会一次建完 ~770 段），本类持有推进状态、
 * 每次 {@code nextSegment()} 只做 O(一段) 的工作。
 *
 * <p><b>为什么必须有它</b>：严格分批的第一版把「单条逻辑行折行的全部段」当作 staging 上界
 * （OutputCursor 契约），但一条 60k 列的无换行长行第一次 {@code next()} 就要物化 ~770 个
 * PhysicalLine 并完成 O(len) 折行——这笔「整行段一次物化」的突刺发生在任何时间预算检查之前
 * （预算只在行与行之间检查），正是审查 I-1 指出的缺陷。改成段级推进后，物化上界与逻辑行长度
 * <b>无关</b>：任意时刻只有「当前正在产出的那一个物理段」+ O(1) 推进状态。
 *
 * <p><b>与 TextWrap 的一致性由 {@code SegmentedWrapTest} 钉住</b>：同一输入下本类逐段产出的
 * 纯文本序列与 {@code TextWrap.wrap} / {@code wrapSegments} 的结果逐一相等。两处实现若分家，
 * 「打出去的行」与「留底重放的行」会对不上。
 *
 * <p>两个工厂：{@link #plain(String, int)}（纯字符串：INFO/TOOL 单色行、用户块、diff 回退摘要）、
 * {@link #styled(List, int)}（已渲染的 span 行：markdown 正文、diff 主体行，样式保留）。
 */
final class SegmentedWrap {

    private SegmentedWrap() {
    }

    /**
     * 纯字符串段推进器：每次 {@link #nextSegment()} 返回下一段（宽度 ≤ width，宽字符不切半），
     * 产出序列与 {@code CodeTuiView.wrapSegments} 一致（含「空逻辑行 → 单个空段」的语义）。
     * 推进状态只有剩余字符串引用，O(1)。
     */
    static final class Plain {
        private String rest;          // 剩余未折内容；null = 耗尽（含空行已吐出空段的情形）
        private final int width;

        private Plain(String source, int width) {
            this.rest = source;
            this.width = Math.max(1, width);
        }

        boolean hasNextSegment() {
            return rest != null;
        }

        /** 下一段；耗尽后返回 null。 */
        String nextSegment() {
            if (rest == null) return null;
            if (rest.isEmpty()) {                             // 空逻辑行：一个空段，随后耗尽
                rest = null;
                return "";
            }
            String seg = CharWidth.substringByWidth(rest, width);
            if (seg.isEmpty()) seg = rest.substring(0, 1);    // 窄到放不下 1 个宽字符：硬吃 1 个防死循环
            rest = rest.length() == seg.length() ? null : rest.substring(seg.length());
            return seg;
        }
    }

    /**
     * span 行段推进器：每次 {@link #nextSegment()} 返回下一段的 span 列表（被拆 span 在拆分点
     * 两侧保留同一样式），产出序列与 {@code TextWrap.wrap} 一致（含空行补一个空段）。
     * 推进状态 = 当前 span 引用 + 其剩余内容 + 后续 span 下标 + 当前行已用宽度，O(1)。
     */
    static final class Styled {
        private final List<Span> spans;
        private final int width;
        private int spanAt;          // 下一个未消费的 span 下标
        private String rest = "";    // 当前 span 的剩余内容（""=当前 span 已耗尽）
        private Span restSpan;       // 当前 span（null=尚未开始）
        private int used;            // 当前物理段已用宽度
        private boolean emittedAny;  // 是否已产出过至少一段（没有 ⇒ 空行，补一个空段）

        private Styled(List<Span> spans, int width) {
            this.spans = spans == null ? List.of() : spans;
            this.width = Math.max(1, width);
        }

        boolean hasNextSegment() {
            if (spanAt < spans.size() || (restSpan != null && !rest.isEmpty())) return true;
            return !emittedAny;                              // 空 span 列表 / 全空 span：补一个空段
        }

        /** 下一段的 span 列表；耗尽后返回 null。 */
        List<Span> nextSegment() {
            if (!hasNextSegment()) return null;
            List<Span> cur = new ArrayList<>(4);
            used = 0;
            while (true) {
                if (restSpan == null || rest.isEmpty()) {     // 换下一个 span（空 span 自然跳过）
                    if (spanAt >= spans.size()) break;
                    restSpan = spans.get(spanAt++);
                    rest = restSpan.content();
                    if (rest.isEmpty()) { restSpan = null; continue; }
                    continue;
                }
                String take = CharWidth.substringByWidth(rest, width - used);
                if (take.isEmpty()) {
                    if (used > 0) break;                      // 本段放不下下一字符：落段（剩余留给下段）
                    take = rest.substring(0, 1);              // 整段连 1 个宽字符都放不下：硬吃 1 个
                }
                cur.add(Span.styled(take, restSpan.style()));
                used += CharWidth.of(take);
                rest = rest.length() == take.length() ? "" : rest.substring(take.length());
                if (used >= width && !rest.isEmpty()) break;  // 行满且当前 span 还有剩余：落段
                if (rest.isEmpty() && spanAt >= spans.size()) break;   // 内容全部消费完：末段落段
            }
            emittedAny = true;
            return cur;
        }
    }

    /** 纯字符串段推进器（{@code source} 为 null 视为空行）。 */
    static Plain plain(String source, int width) {
        return new Plain(source == null ? "" : source, width);
    }

    /** span 行段推进器（一行 = 一个 span 列表，通常来自 markdown/diff 渲染）。 */
    static Styled styled(List<Span> spans, int width) {
        return new Styled(spans, width);
    }
}
