package io.github.javaside.springai.tamboui.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 7 课的纯逻辑：笔记统计（字数按字符计、行数按行计、非空行数）。
 * 声明式 UI 里 render() 每帧都读它——纯函数化后可单测。
 */
class NotesStatsTest {

    @Test
    void emptyNotes() {
        NotesStats s = NotesStats.of("");
        assertEquals(0, s.chars());
        assertEquals(1, s.lines());
        assertEquals(0, s.nonEmptyLines());
    }

    @Test
    void singleLine() {
        NotesStats s = NotesStats.of("学 Toolkit DSL");
        assertEquals(13, s.chars());
        assertEquals(1, s.lines());
        assertEquals(1, s.nonEmptyLines());
    }

    @Test
    void emojiCountsOneCharPerCodePoint() {
        // emoji 是增补平面字符（2 个 UTF-16 单元、1 个码点）——若实现误用 length() 此处变红
        NotesStats s = NotesStats.of("笔记\uD83D\uDCDD");   // 笔记 + 📝
        assertEquals(3, s.chars());
    }

    @Test
    void multiLineCounts() {
        NotesStats s = NotesStats.of("第一行\n\n第三行");
        assertEquals(8, s.chars());
        assertEquals(3, s.lines());
        assertEquals(2, s.nonEmptyLines(), "中间空行不计入非空行");
    }
}
