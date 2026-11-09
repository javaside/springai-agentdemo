package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.BorderType;

/**
 * 整帧绘制逻辑（示例②使用，纯数据结构操作、可单测）：给定宽高与帧号，
 * 返回一帧「完整描述」的 Buffer——ROUNDED 边框 + 标题 + 操作提示 + 帧计数 + 随帧移动的 ◆。
 *
 * <p>立即模式的教学要点藏在签名里：Artist 从不看上一帧（没有 diff 参数），
 * 每次都从 {@link Buffer#empty} 画整屏——「算最小差量」是渲染层（InlineTuiRunner）
 * 对前后两帧 Buffer 做 {@link Buffer#diff} 的职责，不是画的人的。</p>
 */
public final class BufferBlockArtist {

    private static final String TITLE = "立即模式演示";
    private static final String HINT = "空格=下一帧  q=退出";

    private BufferBlockArtist() {
    }

    /** 画一帧。frame 单调递增即可（来源：按键或 tick），用于帧计数与 ◆ 位置。 */
    public static Buffer draw(int width, int height, long frame) {
        Rect area = new Rect(0, 0, width, height);
        Buffer buf = Buffer.empty(area);

        Block block = Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        block.render(area, buf);
        Rect inner = block.inner(area);

        // 标题：内部首行左侧，加粗青色（setString 返回消耗宽度，教学演示忽略返回值）
        buf.setString(inner.x() + 1, inner.y(), TITLE, Style.create().fg(Color.CYAN).bold());
        // 帧计数：内部首行右侧，差分教学的主角——相邻帧只有这里和 ◆ 在变
        buf.setString(inner.right() - 5, inner.y(), "#" + frame, Style.create().fg(Color.YELLOW));
        // 操作提示：内部第二行，固定不动（相邻帧不变 → 差分不重发）
        buf.setString(inner.x() + 1, inner.y() + 1, HINT, Style.create().fg(Color.GRAY));

        // ◆ 光标点：在最后一内部行按帧移动，取模环绕
        int cursorRow = inner.bottom() - 1;
        int cursorX = inner.x() + (int) (frame % inner.width());
        buf.set(cursorX, cursorRow, new dev.tamboui.buffer.Cell("◆", Style.create().fg(Color.GREEN)));
        return buf;
    }
}
