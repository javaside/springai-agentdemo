package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 1 课的 diff 视图（两帧 Buffer 的差异图）：不同的格子标 ■，相同的留空——
 * 把「框架只把变化的 Cell 发给终端」从一句话变成亲眼所见。
 */
class FrameDiffViewTest {

    private static Buffer buf(String... rows) {
        Buffer b = Buffer.empty(new Rect(0, 0, 3, rows.length));
        for (int y = 0; y < rows.length; y++) {
            b.setString(0, y, rows[y], Style.EMPTY);
        }
        return b;
    }

    @Test
    void marksOnlyChangedCells() {
        Buffer a = buf("abc", "def", "ghi");
        Buffer b = buf("xbc", "def", "ghz");   // (0,0) 与 (2,2) 两格不同
        List<String> view = FrameDiffView.between(a, b);
        assertEquals(List.of(
                "y=0 |■  |",
                "y=1 |   |",
                "y=2 |  ■|"), view);
    }

    @Test
    void identicalFramesMarkNothing() {
        Buffer a = buf("ab", "cd");
        List<String> view = FrameDiffView.between(a, a.copy());
        assertEquals(List.of("y=0 |   |", "y=1 |   |"), view);
    }
}
