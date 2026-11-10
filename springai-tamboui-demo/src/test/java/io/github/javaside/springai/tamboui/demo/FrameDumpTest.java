package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FrameDump（把一帧 Buffer 网格转成带行号的可打印文本）的测试——第 2 课的教具：
 * 全屏应用退出后，用它把「刚才那一帧在 Buffer 里的样子」打印给学习者看，
 * 把「终端屏幕 = Cell 网格」从概念变成亲眼所见。
 */
class FrameDumpTest {

    @Test
    void dumpsEveryRowWithIndexAndBars() {
        Buffer buf = Buffer.empty(new Rect(0, 0, 10, 2));
        buf.setString(0, 0, "hi", dev.tamboui.style.Style.EMPTY);
        List<String> lines = FrameDump.dump(buf);
        assertEquals(List.of("y=0 |hi        |", "y=1 |          |"), lines);
    }

    @Test
    void wideCharOccupiesMainAndContinuationCells() {
        // 实测 0.4.0：setString 宽度感知——中文占「主格 + 续格」，续格 symbol 为空串。
        // dump 用 · 呈现续格，保证每行可见字符数 = 网格列数（宽度口径是第 3 课的正题）。
        Buffer buf = Buffer.empty(new Rect(0, 0, 6, 1));
        buf.setString(0, 0, "你好", dev.tamboui.style.Style.EMPTY);
        assertEquals(List.of("y=0 |你·好·  |"), FrameDump.dump(buf));
    }

    @Test
    void tallBufferDumpsAllRowsInOrder() {
        Buffer buf = Buffer.empty(new Rect(0, 0, 3, 3));
        List<String> lines = FrameDump.dump(buf);
        assertEquals(3, lines.size());
        assertEquals("y=2 |   |", lines.get(2));
    }
}
