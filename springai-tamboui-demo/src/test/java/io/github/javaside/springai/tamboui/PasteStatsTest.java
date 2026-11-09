package io.github.javaside.springai.tamboui;

import io.github.javaside.springai.tamboui.demo.PasteStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 粘贴统计纯逻辑测试：字符数 / 行数（\n、\r\n、孤立 \r 都算换行）/ \r\n 出现次数。
 *
 * <p>口径背景：上游 0.4.0 粘贴链路存在 CR 泄漏坑（\r\n 的 CR 可能被当独立按键漏出），
 * 统计层必须先把三种换行都数对，示例③才能拿它当场「验伤」——CR 有没有被吃掉，一测便知。</p>
 */
class PasteStatsTest {

    @Test
    void singleLineNoNewline() {
        PasteStats s = PasteStats.of("abc");
        assertEquals(3, s.chars());
        assertEquals(1, s.lines());
        assertEquals(0, s.crlfCount());
    }

    @Test
    void lfCountsAsNewline() {
        PasteStats s = PasteStats.of("a\nb");
        assertEquals(3, s.chars());
        assertEquals(2, s.lines());
        assertEquals(0, s.crlfCount());
    }

    @Test
    void crlfCountsOnceAsNewline() {
        PasteStats s = PasteStats.of("a\r\nb");
        assertEquals(4, s.chars());
        assertEquals(2, s.lines());
        assertEquals(1, s.crlfCount());
    }

    @Test
    void loneCrCountsAsNewline() {
        PasteStats s = PasteStats.of("a\rb");
        assertEquals(3, s.chars());
        assertEquals(2, s.lines());
        assertEquals(0, s.crlfCount());
    }

    @Test
    void emptyTextIsOneEmptyLine() {
        PasteStats s = PasteStats.of("");
        assertEquals(0, s.chars());
        assertEquals(1, s.lines());
        assertEquals(0, s.crlfCount());
    }

    @Test
    void multiLineCjkWithCrlf() {
        PasteStats s = PasteStats.of("行1\r\n行2\r\n行3");
        assertEquals(10, s.chars());   // 行1(2)+CRLF(2)+行2(2)+CRLF(2)+行3(2)
        assertEquals(3, s.lines());
        assertEquals(2, s.crlfCount());
    }
}
