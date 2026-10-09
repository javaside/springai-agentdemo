/*
 * Copyright 2026 Xinghua Zhou
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package dev.tamboui.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TerminalWidth} 序列感知宽度测试。
 *
 * <p>口径来源：2026-10-09 实测事故（keycap 序列按 1 列建模导致输入框错位、
 * Terminal.app SIGBUS 崩溃）+ wcwidth 事实标准（U+26A0+FE0F=2）。
 * 事件与证据见仓库 docs/superpowers/specs/2026-10-09-emoji-sequence-width-design.md。
 */
class TerminalWidthTest {

    /** 事故原文案：CharWidth 逐码点口径 65 列、终端实际 67 列，差值全部来自 9️⃣/8️⃣。 */
    private static final String INCIDENT_TEXT =
            "🍺9月7日周一赛事🍺⚽足球赛事9️⃣场⚽🏀篮球赛事8️⃣场🏀⏰晚上 22:00点⏰ ";

    // ── keycap：[0-9#*] + FE0F? + U+20E3，终端按 keycap 呈现占 2 列 ──────────

    @Test
    void keycapWithSelectorIsTwoColumns() {
        assertEquals(2, TerminalWidth.of("9️⃣"), "9+FE0F+20E3 是完整 keycap，终端渲染 2 列");
        assertEquals(2, TerminalWidth.of("8️⃣"));
    }

    @Test
    void keycapWithoutSelectorIsStillTwoColumns() {
        // UTS#51 keycap 序列的 FE0F 是可选段；部分输入源（老 iOS/网页转文本）会丢 FE0F
        assertEquals(2, TerminalWidth.of("9\u20E3"), "9+20E3（无 FE0F）仍合成 keycap 字形");
    }

    @Test
    void bareKeycapBaseIsOneColumn() {
        assertEquals(1, TerminalWidth.of("9"), "裸数字 1 列");
        assertEquals(1, TerminalWidth.of("#"), "裸井号 1 列（# 不是 emoji 符号，VS16 也不变宽）");
        assertEquals(1, TerminalWidth.of("9\uFE0F"),
                "数字+FE0F 无 20E3：数字没有 emoji 呈现字形，终端仍 1 列——这是与 wcwidth 的口径分歧点，"
                        + "按 Terminal.app（CoreText）实际行为建模");
    }

    // ── 文本符号 + VS16：Terminal.app 实测 1 列（2026-10-09 DSR 反转回调） ─────

    /**
     * ⚠️❤️⏱️ 在 Terminal.app（含 Grass profile）实测 1 列——DSR 光标位置法，
     * 见 docs/superpowers/specs/2026-10-09-cr-paste-leak-and-vs16-width-design.md。
     * 上一版按 wcwidth 15.x 判 2 列是反向错位（TUI 布格 2、终端画 1）；VS16 回归
     * 纯零宽变体，宽度取 base（text 呈现 1 列）。cluster 原子性保留（不切半）。
     */
    @Test
    void vs16KeepsTextPresentationSymbolOneColumn() {
        assertEquals(1, TerminalWidth.of("⚠"), "U+26A0 默认 text 呈现，1 列");
        assertEquals(1, TerminalWidth.of("⚠️"), "⚠+FE0F：Terminal.app 实测 1 列（与 wcwidth 分歧点，按实机建模）");
        assertEquals(1, TerminalWidth.of("❤"));
        assertEquals(1, TerminalWidth.of("❤️"));
        assertEquals(1, TerminalWidth.of("⏱️"));
    }

    // ── 回归口径：非组合字符与 CharWidth 完全一致 ──────────────────────────

    @Test
    void plainCharactersKeepCharWidthSemantics() {
        assertEquals(0, TerminalWidth.of(""));
        assertEquals(1, TerminalWidth.of("a"));
        assertEquals(2, TerminalWidth.of("中"));
        assertEquals(2, TerminalWidth.of("🍺"), "补充平面 emoji 2 列");
        assertEquals(2, TerminalWidth.of("⚽"), "Emoji_Presentation=Yes 的符号 CharWidth 已 2 列");
        assertEquals(2, TerminalWidth.of("👨‍👩‍👧"), "ZWJ 家族序列单 glyph 2 列（CharWidth 既有语义）");
        assertEquals(0, TerminalWidth.of("\u200B"), "零宽空格 0 宽");
    }

    @Test
    void incidentTextWidthMatchesTerminal() {
        // 终端口径实测 67（wcwidth 67 vs 逐码点 65）；若实现后此断言失败，
        // 逐字符核对而不是改断言——每个分歧字符都要能单独说明
        assertEquals(67, TerminalWidth.of(INCIDENT_TEXT));
    }

    // ── ofPrefix：光标列计算（切点可在序列中间） ────────────────────────────

    @Test
    void ofPrefixStopsAtCharBoundary() {
        String s = "足球9️⃣场";   // char 布局: 足0 球1 9=2 FE0F=3 20E3=4 场5, len=6
        assertEquals(0, TerminalWidth.ofPrefix(s, 0));
        assertEquals(2, TerminalWidth.ofPrefix(s, 1), "『足』");
        assertEquals(4, TerminalWidth.ofPrefix(s, 2), "『足球』=4 列");
        assertEquals(5, TerminalWidth.ofPrefix(s, 3), "『足球9』：keycap 未闭合，按 base 宽 1");
        assertEquals(5, TerminalWidth.ofPrefix(s, 4), "含 9 不含 20E3：组合未闭合仍按 base 宽");
        assertEquals(6, TerminalWidth.ofPrefix(s, 5), "完整 keycap 2 列");
        assertEquals(8, TerminalWidth.ofPrefix(s, 6), "完整 keycap + 场");
        assertEquals(8, TerminalWidth.ofPrefix(s, s.length()));
        assertEquals(8, TerminalWidth.ofPrefix(s, 99), "endIndex 越界夹到串尾");
    }

    @Test
    void ofPrefixClampsToCodePointBoundary() {
        String s = "🍺场";       // char 布局: 🍺占 0-1（代理对）, 场=2, len=3
        assertEquals(0, TerminalWidth.ofPrefix(s, 0));
        assertEquals(2, TerminalWidth.ofPrefix(s, 1),
                "切点落在 🍺 代理对中间：夹到 codePoint 边界（含整个 emoji），不许给孤立半代理算宽");
        assertEquals(2, TerminalWidth.ofPrefix(s, 2));
        assertEquals(4, TerminalWidth.ofPrefix(s, 3));
    }

    // ── substringByWidth：折行不切半组合序列 ───────────────────────────────

    @Test
    void substringKeepsKeycapWhole() {
        String s = "足球9️⃣场";
        assertEquals("足球9️⃣", TerminalWidth.substringByWidth(s, 6), "预算 6 恰好容纳序列");
        assertEquals("足球", TerminalWidth.substringByWidth(s, 5),
                "预算 5 放不下『足球』+序列(6)：序列整体留下段，不切出孤立 9");
        assertEquals("足球9️⃣场", TerminalWidth.substringByWidth(s, 100), "预算充足返回全部");
    }

    @Test
    void substringKeepsVs16Whole() {
        // ⚠️=1 列（Terminal.app 实测）：前(2)+⚠️(1)=3。cluster 原子性不因宽度回调改变——
        // 预算 2 放不下整个 ⚠️ 组合时整体留下段，绝不切出孤立 base 或 FE0F。
        String s = "前⚠️后";
        assertEquals("前", TerminalWidth.substringByWidth(s, 2));
        assertEquals("前⚠️", TerminalWidth.substringByWidth(s, 3));
    }

    @Test
    void substringPlainBehaviorUnchanged() {
        assertEquals("足球", TerminalWidth.substringByWidth("足球赛事", 4));
        assertEquals("", TerminalWidth.substringByWidth("足球", 0));
        assertEquals("abc", TerminalWidth.substringByWidth("abcdef", 3));
    }
}
