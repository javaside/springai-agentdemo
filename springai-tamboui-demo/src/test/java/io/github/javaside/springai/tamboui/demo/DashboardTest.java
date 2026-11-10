package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.widgets.list.ListState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 5 课测试：布局切分（纯逻辑）与整帧绘制（离屏渲染）。
 *
 * <p>布局断言把 0.4.0 Layout 引擎在 100×30 屏上的实际切分结果钉死——引擎口径变化（如
 * 取整规则）会在此红灯。</p>
 */
class DashboardTest {

    @Test
    void verticalSplitGivesHeaderFixedMiddlePercentFooterFill() {
        List<Rect> parts = DashboardLayout.split(new Rect(0, 0, 100, 30));
        assertEquals(3, parts.size());
        assertEquals(new Rect(0, 0, 100, 3), parts.get(0));      // length(3)：固定头
        assertEquals(new Rect(0, 3, 100, 18), parts.get(1));     // percentage(60)：60% 中段
        assertEquals(new Rect(0, 21, 100, 9), parts.get(2));     // fill()：剩余全给尾
    }

    @Test
    void middleSplitGivesListPercentAndGaugeFill() {
        List<Rect> parts = DashboardLayout.splitMiddle(new Rect(0, 3, 100, 18));
        assertEquals(2, parts.size());
        assertEquals(new Rect(0, 3, 60, 18), parts.get(0));      // percentage(60)
        assertEquals(new Rect(60, 3, 40, 18), parts.get(1));     // fill() 吃剩余
    }

    @Test
    void paintedFrameShowsTitleListHighlightAndGauge() {
        Buffer buf = Buffer.empty(new Rect(0, 0, 100, 30));
        Frame frame = Frame.forTesting(buf);
        ListState state = new ListState();
        state.select(1);
        DashboardPainter.paint(frame, state);

        String header = rowText(buf, 0).replace("·", "");   // 去续格标记后断言文本
        assertTrue(header.contains("第 5 课"), "标题应渲染在头部区域: " + header);

        // List（StatefulWidget）：选中行带 ▶ 高亮符号，状态在 ListState 里外部持有
        boolean foundHighlight = false;
        boolean foundItem = false;
        for (int y = 4; y < 20; y++) {
            String row = rowText(buf, y).replace("·", "");
            if (row.contains("▶")) foundHighlight = true;
            if (row.contains("第 1 课") || row.contains("第 8 课")) foundItem = true;   // 钉真实条目文本
        }
        assertTrue(foundHighlight, "List 选中行应有 ▶ 高亮");
        assertTrue(foundItem, "List 条目应可见");

        // Gauge 区域应有进度条字符（█ 或比率标签 %）
        boolean foundGauge = false;
        for (int y = 4; y < 20; y++) {
            String row = rowText(buf, y).replace("·", "");
            if (row.contains("█") || row.contains("%")) foundGauge = true;
        }
        assertTrue(foundGauge, "Gauge 进度条应可见");
    }

    private static String rowText(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < buf.area().width(); x++) {
            dev.tamboui.buffer.Cell cell = buf.get(x, y);
            sb.append(cell.isContinuation() ? "·" : (cell.symbol() == null ? " " : cell.symbol()));
        }
        return sb.toString();
    }
}
