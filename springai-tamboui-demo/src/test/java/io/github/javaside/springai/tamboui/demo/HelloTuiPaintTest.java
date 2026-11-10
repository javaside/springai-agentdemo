package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 2 课的手写格子：paint 里用 frame.buffer().set(...) 直接往网格写当前帧标记（A/B）——
 * 让 Buffer 在课程代码里现形（renderWidget 把 Buffer 封装在身后，学员此前在代码里看不到它）。
 * 该格同时出现在屏幕右上角与退出后的 diff 视图（■）中。
 */
class HelloTuiPaintTest {

    private static Buffer paint(boolean frameB, int width, int height) {
        HelloTuiDemo demo = new HelloTuiDemo();
        demo.setFrameBForTest(frameB);
        Buffer buf = Buffer.empty(new Rect(0, 0, width, height));
        demo.paint(Frame.forTesting(buf));
        return buf;
    }

    @Test
    void handwrittenCellMarksCurrentFrame() {
        assertEquals("A", paint(false, 60, 10).get(58, 0).symbol());
        assertEquals("B", paint(true, 60, 10).get(58, 0).symbol());
    }

    @Test
    void paragraphTextLandsInGrid() {
        Buffer buf = paint(false, 60, 10);
        String row0 = rowText(buf, 0);
        org.junit.jupiter.api.Assertions.assertTrue(row0.contains("TamboUI"), "欢迎语应画进网格：" + row0);
    }

    private static String rowText(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < buf.area().width(); x++) {
            sb.append(buf.get(x, y).symbol());
        }
        return sb.toString();
    }
}
