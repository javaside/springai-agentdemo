package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 2 课网格实验室的离屏测试：三个核心概念各有可见的演示位——
 * Cell（手写格 ◆ 与角标）、Buffer（观察窗实时回放）、Frame（渲染入口承载以上一切）。
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
    void handwrittenCellsMarkCurrentFrame() {
        // Cell 演示：◆ 与角标都是 grid.set 写的一格
        Buffer a = paint(false, 60, 20);
        assertEquals("◆", a.get(6, 3).symbol());          // 帧 A：◆ 在左
        assertEquals("A", a.get(HelloTuiDemo.OBS_WIDTH - 4, 0).symbol());
        Buffer b = paint(true, 60, 20);
        assertEquals("◆", b.get(22, 3).symbol());         // 帧 B：◆ 在右
        assertEquals("B", b.get(HelloTuiDemo.OBS_WIDTH - 4, 0).symbol());
    }

    @Test
    void observationWindowMirrorsTheSameBuffer() {
        // Buffer 演示：观察窗（下半屏）回放同一帧的左上区域——上下是同一张表
        Buffer buf = paint(false, 60, 20);
        int half = 20 / 2;
        boolean foundWindowTitle = false;
        boolean foundMirrorRow = false;
        for (int y = half; y < 20; y++) {
            String row = rowText(buf, y);
            if (row.contains("观察窗")) {
                foundWindowTitle = true;
            }
            if (row.startsWith("y=0 |") || row.contains("y=0 |")) {
                foundMirrorRow = true;   // 回放行存在
            }
        }
        assertTrue(foundWindowTitle, "观察窗标题应出现在下半屏");
        assertTrue(foundMirrorRow, "观察窗应有 y=0 回放行");
    }

    @Test
    void paragraphTextLandsInGrid() {
        Buffer buf = paint(false, 60, 20);
        assertTrue(rowText(buf, 1).contains("网格实验室"), "主区标题应画进网格");
    }

    private static String rowText(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < buf.area().width(); x++) {
            dev.tamboui.buffer.Cell cell = buf.get(x, y);
            sb.append(cell.isContinuation() ? "" : (cell.symbol() == null ? " " : cell.symbol()));
        }
        return sb.toString();
    }
}
