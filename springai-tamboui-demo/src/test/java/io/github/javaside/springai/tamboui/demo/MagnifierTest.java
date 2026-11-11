package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放大镜教具的离屏测试：逐格框出、读写分区（投影不覆盖被展示的 0-4 行）。
 */
class MagnifierTest {

    @Test
    void projectsBoxedCellsBelowTheReadZone() {
        Buffer buf = Buffer.empty(new Rect(0, 0, 40, 12));
        Frame frame = Frame.forTesting(buf);
        buf.setString(0, 0, "你好A", dev.tamboui.style.Style.create());
        buf.set(2, 2, new dev.tamboui.buffer.Cell("★", dev.tamboui.style.Style.create()));

        Magnifier.project(frame, 10, 5, 6);

        String row0 = rowText(buf, 6);   // 第0行投影
        assertTrue(row0.contains("[你][·][好][·][A]"), "中文主+续格应逐格框出: " + row0);
        assertTrue(rowText(buf, 8).contains("[★]"), "★ 应框在放大镜中");
        assertEquals("★", buf.get(2, 2).symbol(), "读区(第2行)不得被投影覆盖");
    }

    private static String rowText(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < buf.area().width(); x++) {
            dev.tamboui.buffer.Cell c = buf.get(x, y);
            sb.append(c.isContinuation() ? "" : (c.symbol() == null ? " " : c.symbol()));
        }
        return sb.toString();
    }
}
