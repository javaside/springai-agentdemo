/*
 * Copyright 2026 Xinghua Zhou
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package dev.tamboui.buffer;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shadow 版 {@link Buffer} 的组合序列布格测试：keycap / 文本符号+VS16 组合
 * 必须按终端实际渲染占 2 列（base cell + CONTINUATION），C1 控制字符必须
 * 替换为安全显示字形。
 *
 * <p>事故背景与口径依据见 {@code TerminalWidth} 及仓库
 * docs/superpowers/specs/2026-10-09-emoji-sequence-width-design.md。
 */
class BufferGraphemeCellTest {

    private static Buffer buf(int w, int h) {
        return Buffer.empty(new Rect(0, 0, w, h));
    }

    // ── keycap：base cell 含完整三码点 + continuation，col 前进 2 ──────────

    @Test
    void keycapOccupiesTwoColumns() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "9️⃣", Style.EMPTY);
        assertEquals(2, end, "keycap 按 2 列布格，返回列 = x+2");
        assertEquals("9️⃣", b.get(0, 0).symbol(), "base cell 的 symbol 含完整三码点");
        assertTrue(b.get(1, 0).isContinuation(), "第 2 列是 continuation 占位");
    }

    @Test
    void keycapAfterTextKeepsAlignment() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "足球9️⃣场", Style.EMPTY);
        // 足(0,1) 球(2,3) 9️⃣(4,5) 场(6,7)：终端渲染与 Buffer 布格逐列对齐
        assertEquals(8, end);
        assertEquals("9️⃣", b.get(4, 0).symbol());
        assertTrue(b.get(5, 0).isContinuation());
        assertEquals("场", b.get(6, 0).symbol());
        assertTrue(b.get(7, 0).isContinuation());
    }

    @Test
    void vs16CombiningOccupiesTwoColumns() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "⚠️", Style.EMPTY);
        assertEquals(2, end, "⚠+FE0F 组合按 2 列布格");
        assertEquals("⚠️", b.get(0, 0).symbol());
        assertTrue(b.get(1, 0).isContinuation());
    }

    @Test
    void bareSymbolStaysOneColumn() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "⚠", Style.EMPTY);
        assertEquals(1, end, "裸 ⚠（无 VS16）仍 1 列——与 0.4.0 原版一致");
        assertEquals("⚠", b.get(0, 0).symbol());
    }

    // ── 行尾边界：2 列组合放不下时替换空格，不越界 ────────────────────────

    @Test
    void keycapAtRightEdgeDegradesToSpace() {
        Buffer b = buf(5, 1);
        int end = b.setString(4, 0, "9️⃣", Style.EMPTY);   // 只剩 1 列，放不下 2 列组合
        assertEquals(5, end);
        assertEquals(" ", b.get(4, 0).symbol(), "右边缘放不下整个组合：该格替换空格（同 wide char 语义）");
        assertFalse(b.get(4, 0).isContinuation());
    }

    // ── C1 控制字符：显示层替换为空格（原样输出会被终端当控制码执行） ──────

    @Test
    void c1ControlCharsRenderAsSpace() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "a\u009Bb\u0085c", Style.EMPTY);
        assertEquals(5, end, "C1 字符按 1 列占位，col 照常前进");
        assertEquals("a", b.get(0, 0).symbol());
        assertEquals(" ", b.get(1, 0).symbol(), "U+009B(CSI) 显示替换为空格");
        assertEquals("b", b.get(2, 0).symbol());
        assertEquals(" ", b.get(3, 0).symbol(), "U+0085(NEL) 显示替换为空格");
        assertEquals("c", b.get(4, 0).symbol());
    }

    // ── 回归：非组合字符行为与 0.4.0 原版逐字一致 ────────────────────────

    @Test
    void wideCharBehaviorUnchanged() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "中a", Style.EMPTY);
        assertEquals(3, end, "CJK 2 列 + ASCII 1 列");
        assertEquals("中", b.get(0, 0).symbol());
        assertTrue(b.get(1, 0).isContinuation());
        assertEquals("a", b.get(2, 0).symbol());
    }

    @Test
    void zwjSequenceJoinsIntoBaseCell() {
        Buffer b = buf(10, 1);
        int end = b.setString(0, 0, "👨‍👩‍👧x", Style.EMPTY);
        assertEquals(3, end, "ZWJ 家族单 glyph 2 列 + x 1 列（原版行为）");
        assertEquals("👨‍👩‍👧", b.get(0, 0).symbol());
        assertTrue(b.get(1, 0).isContinuation());
        assertEquals("x", b.get(2, 0).symbol());
    }

    @Test
    void withLinesSizesByTerminalWidth() {
        Buffer b = Buffer.withLines("足球9️⃣", "ab");
        assertEquals(6, b.width(), "withLines 总宽按 TerminalWidth：4+2=6（原版 CharWidth 会算 5）");
    }
}
