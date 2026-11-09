package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例③的画布适配测试：三段式教学文本全部落在 LIVE_HEIGHT 行内——
 * 「药方」最后一行必须可见，底边框完整。
 *
 * <p>背景（终审 Critical 修复）：此前 config 高度 12 而内容画到第 13 行，
 * Buffer.setString 对越界行静默丢弃，「药方」末行每次运行都不可见。</p>
 */
class InlineEventsPaintTest {

    @Test
    void medicineLastLineVisibleWithinLiveHeight() {
        InlineEventsDemo demo = new InlineEventsDemo();
        int h = InlineEventsDemo.LIVE_HEIGHT;
        Buffer buf = Buffer.empty(new dev.tamboui.layout.Rect(0, 0, 80, h));
        demo.paint(buf, 80, h);

        // 倒数第二行（最后一个内容行）应含「药方」第二行的结尾文字
        String lastContent = rowText(buf, h - 2);
        assertTrue(lastContent.contains("静默预算"), "药方末行应在画布内可见，实际底行内容：" + lastContent);

        // 最后一行是完整底边框（药方文字不得覆盖它）
        assertEquals("╰", buf.get(0, h - 1).symbol());
    }

    private static String rowText(Buffer buf, int y) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < 80; x++) {
            sb.append(buf.get(x, y).symbol());
        }
        return sb.toString();
    }
}
