package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 2 课的 diff 视图（纯逻辑）：对比两帧 Buffer，不同格子标 ■、相同留空。
 * 这就是「框架视角」——它每帧做的事即此图：找出 ■ 的格子，只把这些发给终端。
 */
public final class FrameDiffView {

    private FrameDiffView() {
    }

    public static List<String> between(Buffer a, Buffer b) {
        List<String> lines = new ArrayList<>();
        int width = Math.min(a.area().width(), b.area().width());
        int height = Math.min(a.area().height(), b.area().height());
        for (int y = 0; y < height; y++) {
            StringBuilder sb = new StringBuilder("y=").append(y).append(" |");
            for (int x = 0; x < width; x++) {
                sb.append(a.get(x, y).equals(b.get(x, y)) ? " " : "■");
            }
            sb.append('|');
            lines.add(sb.toString());
        }
        return lines;
    }
}
