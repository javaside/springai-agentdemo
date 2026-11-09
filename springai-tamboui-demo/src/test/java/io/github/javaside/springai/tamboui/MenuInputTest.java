package io.github.javaside.springai.tamboui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 菜单输入解析的纯逻辑测试：合法序号、退出、非法输入三类口径。
 *
 * <p>命名规则：方法名 = 被测行为 + 期望。反向改动（如放宽越界判断、把 quit 改成 1）会让对应断言变红。</p>
 */
class MenuInputTest {

    @Test
    void parseReturnsIndexForValidChoice() {
        assertEquals(1, MenuInput.parse("1", 4));
        assertEquals(4, MenuInput.parse("4", 4));
    }

    @Test
    void parseTrimsSurroundingWhitespace() {
        assertEquals(2, MenuInput.parse(" 2 ", 4));
    }

    @Test
    void parseReturnsZeroForQuit() {
        assertEquals(0, MenuInput.parse("0", 4));
    }

    @Test
    void parseRejectsNonNumeric() {
        assertEquals(-1, MenuInput.parse("q", 4));
        assertEquals(-1, MenuInput.parse("1a", 4));
    }

    @Test
    void parseRejectsBlank() {
        assertEquals(-1, MenuInput.parse("", 4));
        assertEquals(-1, MenuInput.parse("   ", 4));
    }

    @Test
    void parseRejectsOutOfRange() {
        assertEquals(-1, MenuInput.parse("9", 4));
        assertEquals(-1, MenuInput.parse("5", 4));
    }

    @Test
    void parseRejectsNegative() {
        assertEquals(-1, MenuInput.parse("-1", 4));
    }

    @Test
    void parseRejectsNull() {
        assertEquals(-1, MenuInput.parse(null, 4));
    }
}
