package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 2 课「第一个界面」的离屏测试：三块内容各就各位（区域决定位置），
 * 底栏贴着屏幕底（证明用 frame.area() 现场读尺寸）。
 */
class HelloTuiPaintTest {

    @Test
    void threeConceptsEachVisibleInThePaint() {
        Buffer buf = paint(60, 20);
        // Frame 的 renderWidget：三块内容各就各位
        assertTrue(rowText(buf, 1).contains("我的备忘录"), "标题应在第 1 行");
        assertTrue(rowText(buf, 4).contains("买牛奶"), "首条待办应在第 4 行（listArea.y=4）");
        // Buffer + Cell：第一条待办前的 ● 是 set 写进 (3,4) 的一格
        assertEquals("●", buf.get(3, 4).symbol());
    }

    @Test
    void footerSticksToBottomAndFollowsWindowHeight() {
        assertTrue(rowText(paint(60, 20), 17).contains("退出"), "20 行屏：底栏在 17 行");
        assertTrue(rowText(paint(60, 30), 27).contains("退出"), "30 行屏：底栏跟着到 27 行");
    }

    // ---- 工具 ----

    private static Buffer paint(int width, int height) {
        HelloTuiDemo demo = new HelloTuiDemo();
        Buffer buf = Buffer.empty(new Rect(0, 0, width, height));
        demo.paint(Frame.forTesting(buf));
        return buf;
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
