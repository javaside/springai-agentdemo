package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 2 课最小 demo 的离屏测试：三行用法各有可见效果——
 * area() 尺寸行、renderWidget 文字落格、buffer().set() 的 ★ 可被按键移动。
 */
class HelloTuiPaintTest {

    @Test
    void starIsWrittenToItsCell() {
        HelloTuiDemo demo = new HelloTuiDemo();          // 初始 (10, 5)
        Buffer buf = paint(demo, 60, 20);
        assertEquals("★", buf.get(10, 5).symbol());
    }

    @Test
    void gridWritesItsOwnFooterLine() {
        // Buffer 不经过组件、自己写的底行（60×20 → 落在 y=18）
        Buffer buf = paint(new HelloTuiDemo(), 60, 20);
        assertTrue(rowText(buf, 18).contains("setString"), "底行应由 grid.writeString 写入");
    }

    @Test
    void sizeLineReflectsArea() {
        Buffer buf = paint(new HelloTuiDemo(), 60, 20);
        assertTrue(rowText(buf, 0).contains("60"), "尺寸行应显示实时列数: " + rowText(buf, 0));
        Buffer wide = paint(new HelloTuiDemo(), 100, 20);
        assertTrue(rowText(wide, 0).contains("100"), "区域变了数字要跟着变");
    }

    @Test
    void keysMoveTheStarByWritingAnotherCell() {
        HelloTuiDemo demo = new HelloTuiDemo();
        Buffer buf = paint(demo, 60, 20);                // 先画一帧，记下画布尺寸
        demo.onKey(KeyEvent.ofChar('l'), null);          // 右移 2 格
        demo.onKey(KeyEvent.ofChar('j'), null);          // 下移 1 行
        assertEquals("★", paint(demo, 60, 20).get(12, 6).symbol());
        demo.onKey(KeyEvent.ofChar('h'), null);          // 回左
        assertEquals("★", paint(demo, 60, 20).get(10, 6).symbol());
    }

    // ---- 测试工具 ----

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
