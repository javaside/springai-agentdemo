package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 5 课 + / - 按键判定的口径：除 ASCII 半角外，必须兼容<b>全角</b> ＋（U+FF0B）/ －（U+FF0D）——
 * 中文输入法开着时按 + / -，终端送达的常是全角字符（用户实报「按 + 没变化」的根因之一）。
 * 另收 = / _ 作别名（US 键盘上 + / - 的未按 Shift 形态）。
 */
class AdjustKeysTest {

    @Test
    void asciiPlusMinusMatch() {
        assertTrue(ToolkitDslDemo.isPlus(KeyEvent.ofChar('+')));
        assertTrue(ToolkitDslDemo.isMinus(KeyEvent.ofChar('-')));
    }

    @Test
    void fullWidthPlusMinusMatch() {
        assertTrue(ToolkitDslDemo.isPlus(KeyEvent.ofChar('＋')), "全角 ＋ 必须命中（中文输入法形态）");
        assertTrue(ToolkitDslDemo.isMinus(KeyEvent.ofChar('－')), "全角 － 必须命中（中文输入法形态）");
    }

    @Test
    void unshiftedAliasesMatch() {
        assertTrue(ToolkitDslDemo.isPlus(KeyEvent.ofChar('=')));
        assertTrue(ToolkitDslDemo.isMinus(KeyEvent.ofChar('_')));
    }

    @Test
    void otherCharsDoNotMatch() {
        assertFalse(ToolkitDslDemo.isPlus(KeyEvent.ofChar('x')));
        assertFalse(ToolkitDslDemo.isMinus(KeyEvent.ofChar('j')));
    }
}
