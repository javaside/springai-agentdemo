package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 2 课「三条带」的离屏测试：每条带各出自谁——
 * 顶部=Frame.renderWidget、中间行=Buffer.setString、★=Cell+set。
 */
class HelloTuiPaintTest {

    @Test
    void threeBandsEachFromItsOwner() {
        Buffer buf = paint(new HelloTuiDemo(), 60, 20);
        assertTrue(rowText(buf, 0).contains("renderWidget"), "带一应标明 Frame 所画");
        assertTrue(rowText(buf, 10).contains("setString"), "带二（rows/2=10）应标明 Buffer 直写");
        assertEquals("★", buf.get(10, 5).symbol(), "带三 ★ 应落在 (starX, starY)");
    }

    @Test
    void keysMoveTheStarCell() {
        HelloTuiDemo demo = new HelloTuiDemo();
        paint(demo, 60, 20);
        demo.onKey(KeyEvent.ofChar('l'), null);
        demo.onKey(KeyEvent.ofChar('j'), null);
        assertEquals("★", paint(demo, 60, 20).get(12, 6).symbol());
    }

    // ---- 工具 ----

    private static Buffer paint(HelloTuiDemo demo, int width, int height) {
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
