package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一帧 Buffer 网格转成带行号的可打印文本（第 1 课教具）。
 *
 * <p>格式：每行 {@code y=<行号> |<该行 Cell 内容>|}。这是<b>网格视角</b>：每个 Cell 恰好一个
 * 可见字符——宽字符（如中文）占「主格 + 续格」，续格以 · 呈现（实测 0.4.0：Buffer.setString
 * 是宽度感知的，续格 symbol 为空串、isContinuation() 为 true）；空格 Cell 显示为空格。</p>
 */
public final class FrameDump {

    private FrameDump() {
    }

    public static List<String> dump(Buffer buf) {
        List<String> lines = new ArrayList<>();
        int width = buf.area().width();
        int height = buf.area().height();
        for (int y = 0; y < height; y++) {
            StringBuilder sb = new StringBuilder("y=").append(y).append(" |");
            for (int x = 0; x < width; x++) {
                dev.tamboui.buffer.Cell cell = buf.get(x, y);
                // 续格 symbol 是空串，直接拼会让行「看起来短了」——用 · 让每个 Cell 都可见
                sb.append(cell.isContinuation() ? "·" : (cell.symbol() == null ? " " : cell.symbol()));
            }
            sb.append('|');
            lines.add(sb.toString());
        }
        return lines;
    }
}
