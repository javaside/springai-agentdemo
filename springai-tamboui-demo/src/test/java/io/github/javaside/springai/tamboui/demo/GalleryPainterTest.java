package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.list.ListState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 4 课画廊的离屏渲染测试：四个组件各就各位、各有可见内容。
 * 区域划分函数也一并钉住（手写 Rect 的几何）。
 */
class GalleryPainterTest {

    @Test
    void fourAreasTileTheScreen() {
        Rect screen = new Rect(0, 0, 100, 24);
        Rect p = GalleryPainter.paragraphArea(screen);
        Rect b = GalleryPainter.blockArea(screen);
        Rect l = GalleryPainter.listArea(screen);
        Rect g = GalleryPainter.gaugeArea(screen);
        assertEquals(new Rect(1, 2, 48, 10), p);
        assertEquals(new Rect(51, 2, 48, 10), b);
        assertEquals(new Rect(1, 13, 48, 9), l);
        assertEquals(new Rect(51, 13, 48, 9), g);
    }

    @Test
    void paintedFrameShowsAllFourWidgets() {
        Buffer buf = Buffer.empty(new Rect(0, 0, 100, 24));
        ListState state = new ListState();
        state.select(1);
        GalleryPainter.paint(Frame.forTesting(buf), state);

        assertTrue(contains(buf, 0, 0, "① Paragraph"), "Paragraph 区（上半）应有标题");
        assertTrue(contains(buf, 51, 0, "② Block"), "Block 区（上半）应有标题");
        assertTrue(contains(buf, 0, 13, "▶"), "List 区（下半）选中行应有 ▶ 高亮");
        assertTrue(contains(buf, 51, 13, "④ Gauge"), "Gauge 区（下半）应有标题");
        assertTrue(contains(buf, 51, 13, "%"), "Gauge 应有百分比");
    }

    private static boolean contains(Buffer buf, int xFrom, int yFrom, String needle) {
        for (int y = yFrom; y < buf.area().height(); y++) {
            if (rowOf(buf, y).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String rowOf(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < buf.area().width(); x++) {
            dev.tamboui.buffer.Cell cell = buf.get(x, y);
            sb.append(cell.isContinuation() ? "" : (cell.symbol() == null ? " " : cell.symbol()));
        }
        return sb.toString();
    }
}
