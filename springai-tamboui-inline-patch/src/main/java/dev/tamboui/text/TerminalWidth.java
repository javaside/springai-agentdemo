/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.text;

/**
 * 序列感知的终端显示宽度：在 {@link CharWidth} 逐码点口径之上，把两类「多码点单字形」
 * 组合按终端实际渲染宽度（2 列）建模：
 *
 * <ul>
 *   <li><b>keycap</b>：{@code [0-9#*] + FE0F? + U+20E3}（如 9️⃣）——终端合成键帽字形占 2 列，
 *       逐码点口径算 1 列（数字 1 + FE0F/20E3 均零宽），每个序列错 1 列；</li>
 *   <li><b>文本符号 + VS16</b>：{@code Emoji_Presentation=No} 的符号（⚠ ❤ ☀ …）后跟
 *       {@code U+FE0F} 被提升为 emoji 呈现占 2 列，逐码点口径算 1 列。</li>
 * </ul>
 *
 * <p>为什么必须序列感知而不是逐码点：2026-10-09 实测事故——用户粘贴含 9️⃣/8️⃣ 的文案，
 * 输入框（live 区经 Buffer 渲染）按 1 列布格、Terminal.app 实际画 2 列，整行从序列处开始
 * 错位（「打字就乱」），并在持续重绘下触发 Terminal.app 主线程 SIGBUS 崩溃
 * （Terminal-2026-10-09-085041.ips）。证据链见仓库
 * docs/superpowers/specs/2026-10-09-emoji-sequence-width-design.md。
 *
 * <p>与 wcwidth 的已知分歧（按 Terminal.app/CoreText 实际行为建模，刻意如此）：
 * 数字/井号/星号仅在 keycap 组合（含 20E3）里变宽，裸 {@code 9+FE0F} 仍 1 列
 * （数字没有 emoji 呈现字形）；wcwidth 对 {@code 9+FE0F} 判 2 是保守口径。
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

    /** 变体选择符 VS16（emoji 呈现）。 */
    private static final int CP_VS16 = 0xFE0F;
    /** 组合封闭键帽（keycap 序列尾码点）。 */
    private static final int CP_KEYCAP = 0x20E3;
    /** 零宽连接符（ZWJ 序列）。 */
    private static final int CP_ZWJ = 0x200D;

    /**
     * 「+FE0F 变 2 列」的 base 字符位图（BMP）。来源：Unicode emoji-data
     * 「Emoji=Yes & Emoji_Presentation=No」BMP 段，经 wcwidth 15.x 实测校验
     * （w(cp)=1 且 w(cp+FE0F)=2）。刻意剔除三类：keycap base（0-9#*，仅 keycap
     * 组合变宽，见类注释分歧说明）、{@code 0x2B05-0x2B07}（CharWidth 已判 2 宽）、
     * Emoji_Presentation=Yes 的符号（CharWidth 已判 2 宽）。
     */
    private static final boolean[] VS16_WIDENS = new boolean[0x10000];

    private static final int[][] VS16_WIDEN_RANGES = {
            {0x00A9, 0x00A9}, {0x00AE, 0x00AE}, {0x203C, 0x203C}, {0x2049, 0x2049},
            {0x2122, 0x2122}, {0x2139, 0x2139}, {0x2194, 0x2199}, {0x21A9, 0x21AA},
            {0x2328, 0x2328}, {0x23CF, 0x23CF}, {0x23ED, 0x23EF}, {0x23F1, 0x23F2},
            {0x23F8, 0x23FA}, {0x24C2, 0x24C2}, {0x25AA, 0x25AB}, {0x25B6, 0x25B6},
            {0x25C0, 0x25C0}, {0x25FB, 0x25FC}, {0x2600, 0x2604}, {0x260E, 0x260E},
            {0x2611, 0x2611}, {0x2618, 0x2618}, {0x261D, 0x261D}, {0x2620, 0x2620},
            {0x2622, 0x2623}, {0x2626, 0x2626}, {0x262A, 0x262A}, {0x262E, 0x262F},
            {0x2638, 0x263A}, {0x2640, 0x2640}, {0x2642, 0x2642}, {0x265F, 0x2660},
            {0x2663, 0x2663}, {0x2665, 0x2666}, {0x2668, 0x2668}, {0x267B, 0x267B},
            {0x267E, 0x267E}, {0x2692, 0x2692}, {0x2694, 0x2697}, {0x2699, 0x2699},
            {0x269B, 0x269C}, {0x26A0, 0x26A0}, {0x26A7, 0x26A7}, {0x26B0, 0x26B1},
            {0x26C8, 0x26C8}, {0x26CF, 0x26CF}, {0x26D1, 0x26D1}, {0x26D3, 0x26D3},
            {0x26E9, 0x26E9}, {0x26F0, 0x26F1}, {0x26F4, 0x26F4}, {0x26F7, 0x26F9},
            {0x2702, 0x2702}, {0x2708, 0x2709}, {0x270C, 0x270D}, {0x270F, 0x270F},
            {0x2712, 0x2712}, {0x2714, 0x2714}, {0x2716, 0x2716}, {0x271D, 0x271D},
            {0x2721, 0x2721}, {0x2733, 0x2734}, {0x2744, 0x2744}, {0x2747, 0x2747},
            {0x2763, 0x2764}, {0x27A1, 0x27A1}, {0x2934, 0x2935},
    };

    static {
        for (int[] range : VS16_WIDEN_RANGES) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                VS16_WIDENS[cp] = true;
            }
        }
    }

    /** 一个显示 cluster：{@code [start,end)} 的 char 区间与显示宽度。 */
    private record Cluster(int end, int width) {
    }

    /**
     * 解析 {@code s} 从 {@code start} 开始的一个显示 cluster。
     * cluster 是折行/布格的原子单元，绝不切半：
     * keycap 序列、base+VS16 组合、区域指示符对、或「base + 零宽后缀（含 ZWJ 链延伸）」。
     */
    private static Cluster cluster(String s, int start) {
        int len = s.length();
        int cp = s.codePointAt(start);
        int n = Character.charCount(cp);

        // keycap：[0-9#*] FE0F? 20E3 —— 尾码点是组合键帽才成立，数字+FE0F 不算（见类注释）
        if (isKeycapBase(cp)) {
            int j = start + n;
            if (j < len && s.codePointAt(j) == CP_VS16) {
                j += Character.charCount(CP_VS16);
            }
            if (j < len && s.codePointAt(j) == CP_KEYCAP) {
                return new Cluster(j + Character.charCount(CP_KEYCAP), 2);
            }
        }

        // 文本符号 + VS16 → emoji 呈现 2 列
        if (cp < 0x10000 && VS16_WIDENS[cp]) {
            int j = start + n;
            if (j < len && s.codePointAt(j) == CP_VS16) {
                return new Cluster(j + Character.charCount(CP_VS16), 2);
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
     * 该 BMP 码点是否「+FE0F 后终端按 2 列渲染」的文本符号
     * （{@link #VS16_WIDEN_RANGES} 命中）。供 shadow Buffer 的布格判定共用，
     * 保证布格与折行/光标口径同源。
     */
    public static boolean isVs16Widened(int codePoint) {
        return codePoint >= 0 && codePoint < 0x10000 && VS16_WIDENS[codePoint];
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
