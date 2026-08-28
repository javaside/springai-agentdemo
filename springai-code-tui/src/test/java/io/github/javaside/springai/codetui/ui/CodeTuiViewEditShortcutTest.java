package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * readline 式编辑快捷键：Ctrl+A/E 行首尾、Ctrl/Alt+←→ 与 Alt+B/F 按词跳、
 * Ctrl+W / Alt+Backspace 删前词、Ctrl+U/K 删至行首/行尾。
 * 词边界纯函数（prevWordStart / nextWordEnd）单独断言：ASCII 词整段跳、CJK 按单字跳。
 */
class CodeTuiViewEditShortcutTest {

    private static CodeTuiView view() {
        return new CodeTuiView(new ConversationState(), (SubmitHandler) t -> null, Path.of("."));
    }

    private static KeyEvent ctrl(char c) { return KeyEvent.ofChar(c, KeyModifiers.CTRL); }
    private static KeyEvent alt(char c)  { return KeyEvent.ofChar(c, KeyModifiers.ALT); }
    private static KeyEvent altKey(KeyCode k) { return KeyEvent.ofKey(k, KeyModifiers.ALT); }
    private static KeyEvent ctrlKey(KeyCode k) { return KeyEvent.ofKey(k, KeyModifiers.CTRL); }

    // ── 行内移动 ─────────────────────────────────────────────────────────

    @Test
    void ctrlA_movesToLineStart_ctrlE_movesToLineEnd() {
        CodeTuiView v = view();
        v.setInputForTest("hello world");
        v.feedKeyForTest(ctrl('a'));
        assertEquals(0, v.cursorColForTest(), "Ctrl+A → 行首");
        v.feedKeyForTest(ctrl('e'));
        assertEquals(11, v.cursorColForTest(), "Ctrl+E → 行尾");
    }

    @Test
    void ctrlLeft_jumpsWordwise_ctrlRight_jumpsBack() {
        CodeTuiView v = view();
        v.setInputForTest("foo bar baz");            // 光标在末尾 11
        v.feedKeyForTest(ctrlKey(KeyCode.LEFT));
        assertEquals(8, v.cursorColForTest(), "跳到 baz 词首");
        v.feedKeyForTest(ctrlKey(KeyCode.LEFT));
        assertEquals(4, v.cursorColForTest(), "跳到 bar 词首");
        v.feedKeyForTest(ctrlKey(KeyCode.RIGHT));
        assertEquals(7, v.cursorColForTest(), "跳到 bar 词尾");
    }

    @Test
    void altB_altF_wordwise() {
        CodeTuiView v = view();
        v.setInputForTest("alpha beta");
        v.feedKeyForTest(alt('b'));
        assertEquals(6, v.cursorColForTest(), "Alt+B → beta 词首");
        v.feedKeyForTest(alt('b'));
        assertEquals(0, v.cursorColForTest(), "Alt+B → alpha 词首");
        v.feedKeyForTest(alt('f'));
        assertEquals(5, v.cursorColForTest(), "Alt+F → alpha 词尾");
    }

    @Test
    void wordLeft_atLineStart_crossesToPreviousLineEnd() {
        CodeTuiView v = view();
        v.setInputForTest("ab\ncd");
        v.feedKeyForTest(ctrl('a'));                  // 第二行行首
        assertEquals(1, v.cursorRowForTest());
        assertEquals(0, v.cursorColForTest());
        v.feedKeyForTest(ctrlKey(KeyCode.LEFT));
        assertEquals(0, v.cursorRowForTest(), "行首按词左移 → 上一行");
        assertEquals(2, v.cursorColForTest(), "落到上一行行尾");
    }

    // ── 删除 ────────────────────────────────────────────────────────────

    @Test
    void ctrlW_deletesPreviousWord() {
        CodeTuiView v = view();
        v.setInputForTest("git commit -m msg");
        v.feedKeyForTest(ctrl('w'));
        assertEquals("git commit -m ", v.inputTextForTest());
        v.feedKeyForTest(ctrl('w'));
        assertEquals("git commit ", v.inputTextForTest(), "连删：标点+词一起吃");
    }

    @Test
    void altBackspace_deletesPreviousWord() {
        CodeTuiView v = view();
        v.setInputForTest("one two");
        v.feedKeyForTest(altKey(KeyCode.BACKSPACE));
        assertEquals("one ", v.inputTextForTest());
    }

    @Test
    void ctrlU_deletesToLineStart_ctrlK_deletesToLineEnd() {
        CodeTuiView v = view();
        v.setInputForTest("prefix suffix");
        // 光标移到 "prefix " 之后（词左移到 suffix 词首）
        v.feedKeyForTest(ctrlKey(KeyCode.LEFT));
        v.feedKeyForTest(ctrl('k'));
        assertEquals("prefix ", v.inputTextForTest(), "Ctrl+K 删到行尾");
        v.feedKeyForTest(ctrl('u'));
        assertEquals("", v.inputTextForTest(), "Ctrl+U 删到行首");
    }

    @Test
    void ctrlU_onlyAffectsCurrentLine() {
        CodeTuiView v = view();
        v.setInputForTest("line1\nline2");             // 光标在第二行末
        v.feedKeyForTest(ctrl('u'));
        assertEquals("line1\n", v.inputTextForTest(), "Ctrl+U 不吞上一行");
    }

    // ── 普通字符不受影响 ─────────────────────────────────────────────────

    @Test
    void bareLetters_stillInsert() {
        CodeTuiView v = view();
        v.setInputForTest("x");
        v.feedKeyForTest(KeyEvent.ofChar('a'));       // 无修饰的 a/e/w/u/k 须照常上屏
        v.feedKeyForTest(KeyEvent.ofChar('e'));
        v.feedKeyForTest(KeyEvent.ofChar('w'));
        v.feedKeyForTest(KeyEvent.ofChar('u'));
        v.feedKeyForTest(KeyEvent.ofChar('k'));
        assertEquals("xaewuk", v.inputTextForTest());
    }

    // ── 词边界纯函数 ─────────────────────────────────────────────────────

    @Test
    void prevWordStart_ascii() {
        assertEquals(4, CodeTuiView.prevWordStart("foo bar", 7), "词中→词首");
        assertEquals(0, CodeTuiView.prevWordStart("foo bar", 4), "词首→吃空白到上一词首");
        assertEquals(0, CodeTuiView.prevWordStart("foo", 0), "行首不动");
        assertEquals(4, CodeTuiView.prevWordStart("foo bar()", 9), "标点后→吃标点再吃词");
    }

    @Test
    void nextWordEnd_ascii() {
        assertEquals(3, CodeTuiView.nextWordEnd("foo bar", 0), "词首→词尾");
        assertEquals(7, CodeTuiView.nextWordEnd("foo bar", 3), "词尾→吃空白到下一词尾");
        assertEquals(7, CodeTuiView.nextWordEnd("foo bar", 7), "行尾不动");
    }

    @Test
    void cjk_movesBySingleCharacter() {
        assertEquals(1, CodeTuiView.prevWordStart("中文词", 2), "CJK 左移一次只跨一个字");
        assertEquals(2, CodeTuiView.nextWordEnd("中文词", 1), "CJK 右移一次只跨一个字");
        assertEquals(2, CodeTuiView.prevWordStart("ab中", 3), "混排：CJK 单字");
        assertEquals(1, CodeTuiView.prevWordStart("中ab", 3), "混排：ASCII 词整段（回到 ab 词首）");
    }

    @Test
    void ctrlW_cjk_deletesOneCharAtATime() {
        CodeTuiView v = view();
        v.setInputForTest("修改这段文字");
        v.feedKeyForTest(ctrl('w'));
        assertEquals("修改这段文", v.inputTextForTest(), "CJK Ctrl+W 只删一个字，不整段清光");
    }
}
