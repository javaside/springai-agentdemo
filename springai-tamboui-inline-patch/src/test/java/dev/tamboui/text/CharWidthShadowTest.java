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
 * shadow 版 {@link CharWidth} 的宽度口径测试：Cf 格式字符与 ZWJ 按
 * Terminal.app TTBuffer 实测记 1 列。
 *
 * <p>口径来源：2026-10-11 DSR 探针实测（raw 模式 CSI 6n，Terminal.app
 * Grass profile）：U+200B/200C/200E/200F、U+2060/2062、U+FEFF、U+00AD
 * 单独出现各 1 列；ZWJ 家族序列不合并记账（👨‍👩 = 2+1+2 = 5），
 * 「a+ZWJ+b」= 3。组合附标与 VS16 实测 0 列，维持零宽。
 * 证据链见 docs/superpowers/specs/2026-10-11-zwnj-format-width-design.md。
 */
class CharWidthShadowTest {

    /** 2026-10-11 抖音粘贴事故原文：7 个 U+200C，逐字符 DSR 合计 105 列。 */
    private static final String INCIDENT_TEXT =
            "《We Never (Remix) (若如初见)》\u200C，由 \u200CHi Noise\u200C 和 \u200C秋成\u200C "
                    + "合作，是抖音上“Vibe知识大赏”常用的氛围感BGM。\u200C\u200C";

    // ── Cf 格式字符：终端记 1 列，原版判 0（事故根因） ────────────────────

    @Test
    void formatCharsAreOneColumn() {
        assertEquals(1, CharWidth.of(0x200B), "ZWSP：终端 1 列");
        assertEquals(1, CharWidth.of(0x200C), "ZWNJ：终端 1 列（抖音事故主角）");
        assertEquals(1, CharWidth.of(0x200E), "LRM：终端 1 列");
        assertEquals(1, CharWidth.of(0x200F), "RLM：终端 1 列");
        assertEquals(1, CharWidth.of(0x2060), "词连接符：终端 1 列");
        assertEquals(1, CharWidth.of(0x2062), "不可见乘号：终端 1 列");
        assertEquals(1, CharWidth.of(0xFEFF), "零宽不换行空格：终端 1 列");
        assertEquals(1, CharWidth.of(0x00AD), "软连字符：终端 1 列");
    }

    // ── 维持项：实测 0 列的仍须 0（防止表改过宽） ────────────────────────

    @Test
    void zeroWidthSemanticsKept() {
        assertEquals(0, CharWidth.of(0x0301), "组合附标（Mn）拼上 base，0 列");
        assertEquals(0, CharWidth.of(0x0300));
        assertEquals(0, CharWidth.of(0xFE0F), "VS16：DSR 实测 0 列（2026-10-09 口径复验）");
        assertEquals(0, CharWidth.of(0x20E3), "keycap 组合键帽是零宽变体（keycap 2 列由 TerminalWidth/Buffer 建模）");
    }

    // ── ZWJ 去粘合：终端逐码点布格，不合并字形记账 ───────────────────────

    @Test
    void zwjSequenceNotGlued() {
        assertEquals(5, CharWidth.of("👨\u200D👩"), "家族两码点+ZWJ：2+1+2，DSR 实测 5");
        assertEquals(8, CharWidth.of("👨\u200D👩\u200D👧"), "三人家族：2+1+2+1+2 = 8");
        assertEquals(3, CharWidth.of("a\u200Db"), "散置 ZWJ：1+1+1，DSR 实测 3");
        assertEquals(1, CharWidth.of("\u200D"), "孤立 ZWJ 1 列");
    }

    // ── 截取与总量同口径（原 substringByWidth 的 ZWJ 原子性机制按码点身份 ──
    //    触发、漏记 ZWNJ 家族宽度，去粘合后必须一并移除） ──────────────────

    @Test
    void substringByWidthAgreesWithTotalWidth() {
        String s = "👨\u200D👩abc";
        String cut = CharWidth.substringByWidth(s, 3);
        assertEquals(3, CharWidth.of(cut), "截 3 列就是 3 列：👨(2)+ZWJ(1)，不许漏记");
        assertEquals("👨\u200D", cut);
        assertEquals("👨\u200D👩a", CharWidth.substringByWidth(s, 6));
    }

    // ── 事故文本回归 ──────────────────────────────────────────────────────

    @Test
    void incidentDouyinTextWidthMatchesTerminal() {
        // 原版逐码点（7 个 ZWNJ 记 0）= 98 列；终端实测每 ZWNJ 记 1 列 → 105。
        // 若此断言失败，逐字符核对而不是改断言（口径证据见 spec 文档）。
        assertEquals(105, CharWidth.of(INCIDENT_TEXT));
    }
}
