/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.text;

/**
 * 序列感知的终端显示宽度：在 {@link CharWidth} 逐码点口径之上，把「多码点单字形」
 * 组合按终端实际渲染宽度建模。当前唯一加宽规则：
 *
 * <ul>
 *   <li><b>keycap</b>：{@code [0-9#*] + FE0F? + U+20E3}（如 9️⃣）——终端合成键帽字形占 2 列，
 *       逐码点口径算 1 列（数字 1 + FE0F/20E3 均零宽），每个序列错 1 列。</li>
 * </ul>
 *
 * <p><b>「文本符号 + VS16」不加宽（2026-10-09 DSR 实测回调）</b>：上一版曾按 wcwidth 15.x
 * 把 {@code ⚠+FE0F} 等判 2 列，Terminal.app（含 Grass profile）DSR 光标位置实测 1 列
 * ——TUI 布 2 格、终端画 1 列，行尾每个组合反向错 1 列。VS16 回归纯零宽变体
 * （宽度取 base），cluster 原子性保留。keycap 的 2 列经同一轮实测确认不变。
 * 证据链见 docs/superpowers/specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md。
 *
 * <p>与 wcwidth 的已知分歧（按 Terminal.app/CoreText 实际行为建模，刻意如此）：
 * 数字/井号/星号仅在 keycap 组合（含 20E3）里变宽，裸 {@code 9+FE0F} 仍 1 列
 * （数字没有 emoji 呈现字形）；{@code ⚠+FE0F} 同理 1 列（wcwidth 保守判 2）。
 *
 * <p>ZWJ 家族序列（👨‍👩‍👧）{@link CharWidth#of(String)} 既有口径已正确（ZWJ 及其后码点
 * 宽度全跳，单 glyph 2 列），本类保持；区域指示符对（旗帜）同理由 CharWidth 处理，
 * 本类在 cluster 层面把它当作原子单元（折行不切半）。
 *
 * <p>三个 API（{@link #of}/{@link #ofPrefix}/{@link #substringByWidth}）共用同一
 * cluster 迭代器，保证「总宽 / 前缀宽 / 截取」永远同口径——这正是 Buffer 布格、
 * 输入框折行、光标列三处必须一致的宽度口径。
 */
public final class TerminalWidth {

    private TerminalWidth() {
    }

    /** 组合封闭键帽（keycap 序列尾码点）。 */
    private static final int CP_KEYCAP = 0x20E3;
    /** 零宽连接符（ZWJ 序列）。 */
    private static final int CP_ZWJ = 0x200D;

    /** 一个显示 cluster：{@code [start,end)} 的 char 区间与显示宽度。 */
    private record Cluster(int end, int width) {
    }

    /**
     * 解析 {@code s} 从 {@code start} 开始的一个显示 cluster。
     * cluster 是折行/布格的原子单元，绝不切半：
     * keycap 序列、区域指示符对、或「base + 零宽后缀（VS16/ZWJ 链延伸）」。
     * VS16 是零宽后缀不改变 cluster 宽度（DSR 实测口径，见类注释）。
     */
    private static Cluster cluster(String s, int start) {
        int len = s.length();
        int cp = s.codePointAt(start);
        int n = Character.charCount(cp);

        // keycap：[0-9#*] FE0F? 20E3 —— 尾码点是组合键帽才成立，数字+FE0F 不算（见类注释）
        if (isKeycapBase(cp)) {
            int j = start + n;
            if (j < len && s.codePointAt(j) == 0xFE0F) {
                j += Character.charCount(0xFE0F);
            }
            if (j < len && s.codePointAt(j) == CP_KEYCAP) {
                return new Cluster(j + Character.charCount(CP_KEYCAP), 2);
            }
        }

        // 区域指示符对（旗帜）：两码点单字形 2 列
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) {
            int j = start + n;
            if (j < len) {
                int next = s.codePointAt(j);
                if (next >= 0x1F1E6 && next <= 0x1F1FF) {
                    return new Cluster(j + Character.charCount(next), 2);
                }
            }
        }

        // 普通 base + 零宽后缀；后缀含 ZWJ 时把下一个非零宽部件并入（链式），
        // 宽度只记 base（ZWJ 部件零贡献，CharWidth.of(String) 既有语义）
        int width = CharWidth.of(cp);
        int i = start + n;
        while (i < len) {
            int mark = s.codePointAt(i);
            int markN = Character.charCount(mark);
            if (mark == CP_ZWJ) {
                i += markN;
                if (i < len) {
                    i += Character.charCount(s.codePointAt(i));
                }
                continue;
            }
            if (CharWidth.of(mark) == 0) {
                i += markN;
                continue;
            }
            break;
        }
        return new Cluster(i, width);
    }

    /** keycap 的 base 集合：数字与可上键帽的两个符号。 */
    private static boolean isKeycapBase(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }

    /**
     * 序列感知总宽。语义同 {@link CharWidth#of(String)}，另见类注释；
     * {@code null} 返回 0。
     */
    public static int of(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int width = 0;
        int i = 0;
        while (i < s.length()) {
            Cluster c = cluster(s, i);
            width += c.width();
            i = c.end();
        }
        return width;
    }

    /**
     * {@code s[0,endIndex)} 的显示宽（光标列计算用）。endIndex 是 char 边界：
     * 切在代理对中间时夹到 codePoint 边界（含完整字符）；切在组合序列中间时
     * 该序列按「未闭合」计——base 自身宽度（CharWidth 逐码点口径），
     * 与「光标停在 keycap 呈现的前半」视觉一致。
     */
    public static int ofPrefix(String s, int endIndex) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int end = clampToCodePoint(s, Math.min(Math.max(endIndex, 0), s.length()));
        int width = 0;
        int i = 0;
        while (i < end) {
            Cluster c = cluster(s, i);
            if (c.end() <= end) {
                width += c.width();
            } else {
                // 跨界 cluster：截到 end 的部分按未闭合口径（base + 零宽尾 = base 宽）
                width += CharWidth.of(s.substring(i, end));
            }
            i = Math.max(c.end(), i + 1);
        }
        return width;
    }

    /** 把边界夹到 codePoint 边界：切在代理对中间时含完整字符。 */
    private static int clampToCodePoint(String s, int index) {
        if (index > 0 && index < s.length()
                && Character.isHighSurrogate(s.charAt(index - 1))
                && Character.isLowSurrogate(s.charAt(index))) {
            return index + 1;
        }
        return index;
    }

    /**
     * 按显示宽度截取前缀，宽度不超过 {@code maxWidth}。组合序列（keycap/VS16/ZWJ 链/
     * 旗帜）是原子单元：预算放不下整个序列时整个序列留给下一段，绝不切出孤立的
     * base 或变体码点。{@code maxWidth <= 0} 返回空串（调用方按需兜底）。
     */
    public static String substringByWidth(String s, int maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) {
            return "";
        }
        int width = 0;
        int cut = 0;
        int i = 0;
        while (i < s.length()) {
            Cluster c = cluster(s, i);
            if (width + c.width() > maxWidth) {
                break;
            }
            width += c.width();
            cut = c.end();
            i = c.end();
        }
        return s.substring(0, cut);
    }
}
