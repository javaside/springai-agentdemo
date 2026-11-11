package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 2 课「放大镜」的离屏测试：三概念在画面上各自可见——
 * Buffer（表格本体，被放大镜逐格展示）、Cell（★ 那一格）、Frame（顶部说明由它画出）。
 */
class HelloTuiPaintTest {

    @Test
    void starIsWrittenToItsCell() {
        HelloTuiDemo demo = new HelloTuiDemo();          // 初始 (3, 2)
        Buffer buf = paint(demo, 60, 20);
        assertEquals("★", buf.get(3, 2).symbol());
    }

    @Test
    void magnifierBoxesEveryCellIncludingTheStar() {
        Buffer buf = paint(new HelloTuiDemo(), 60, 20);
        String starRow = rowText(buf, 6 + 2);            // 放大镜「第2行」投在真实 y=8（targetY=6）
        assertTrue(starRow.contains("[★]"), "★ 应以框格形式出现在放大镜中: " + starRow);
        // 第 0 行放的是顶部说明文字：中文一字占两格 → 放大镜里出现 [x][·] 相邻形态
        String row0 = rowText(buf, 6 + 0);
        assertTrue(row0.contains("[·]"), "中文续格应以 [·] 呈现: " + row0);
    }

    @Test
    void bottomLinesAnswerWhoIsThePictureAndHowToOperate() {
        Buffer buf = paint(new HelloTuiDemo(), 60, 24);
        assertTrue(rowText(buf, 21).contains("Buffer"), "结论行应指明画面=Buffer");
        assertTrue(rowText(buf, 22).contains("set"), "结论行应给出操作三招");
    }

    @Test
    void keysMoveTheStarInsideTheMagnifier() {
        HelloTuiDemo demo = new HelloTuiDemo();
        paint(demo, 60, 20);                             // 先画一帧（记边界无关紧要，钳制是常量）
        demo.onKey(KeyEvent.ofChar('l'), null);
        demo.onKey(KeyEvent.ofChar('j'), null);
        assertEquals("★", paint(demo, 60, 20).get(4, 3).symbol());
        demo.onKey(KeyEvent.ofChar('h'), null);
        assertEquals("★", paint(demo, 60, 20).get(3, 3).symbol());
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
