package io.github.javaside.springai.tamboui;

import dev.tamboui.buffer.Buffer;
import io.github.javaside.springai.tamboui.demo.BufferBlockArtist;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 整帧绘制逻辑测试：Artist 画的每一帧 Buffer 都是「完整描述」——边框、标题、提示、
 * 帧计数、随帧移动的光标点。框线字符为 TamboUI 0.4.0 Block ROUNDED 的实测值
 * （2026-11-09 本机探针：╭ ─ ╮ │ ╰ ╯，inner(Rect(0,0,10,4)) = Rect(1,1,8,2)；
 * Rect.right()/bottom() 为排他边界——Rect(1,1,18,4).right()=19、bottom()=5，实测）。
 * 坐标推算：draw(20,6,·) 的 inner=Rect(1,1,18,4)，帧计数写在 x=14 起（# 占 14、数字占 15），
 * ◆ 在最后一个内部行 y=4。
 */
class BufferBlockArtistTest {

    @Test
    void frameHasRoundedBorder() {
        Buffer buf = BufferBlockArtist.draw(20, 6, 0);
        assertEquals("╭", buf.get(0, 0).symbol());
        assertEquals("╮", buf.get(19, 0).symbol());
        assertEquals("╰", buf.get(0, 5).symbol());
        assertEquals("╯", buf.get(19, 5).symbol());
        assertEquals("─", buf.get(10, 0).symbol());
        assertEquals("│", buf.get(0, 3).symbol());
    }

    @Test
    void frameHasTitleAndHintInsideBorder() {
        Buffer buf = BufferBlockArtist.draw(20, 6, 0);
        // 标题写在内部区（边框之内）：inner 起点为 (1,1)，标题从 (2,1) 起
        assertEquals("立", buf.get(2, 1).symbol());
        // 操作提示固定在第二内部行
        assertEquals("空", buf.get(2, 2).symbol());
    }

    @Test
    void frameCounterAdvancesWithFrame() {
        assertEquals("0", BufferBlockArtist.draw(20, 6, 0).get(15, 1).symbol());
        assertEquals("1", BufferBlockArtist.draw(20, 6, 1).get(15, 1).symbol());
    }

    @Test
    void cursorDotMovesWithFrame() {
        // ◆ 的 x = inner 起点 + frame 对 inner 宽取模；inner 宽 = 18（x ∈ [1,18]）
        assertEquals("◆", BufferBlockArtist.draw(20, 6, 0).get(1, 4).symbol());
        assertEquals("◆", BufferBlockArtist.draw(20, 6, 1).get(2, 4).symbol());
        assertEquals("◆", BufferBlockArtist.draw(20, 6, 18).get(1, 4).symbol());
    }

    @Test
    void wholeFrameRedrawOnlyCounterAndCursorDiffer() {
        // 教学核心：相邻两帧的内容差异只有「帧计数 + 光标点新旧位置」；
        // 边框、标题、提示完全不变——差分渲染（库只发变化的 cell）因此在。
        Buffer f0 = BufferBlockArtist.draw(20, 6, 0);
        Buffer f1 = BufferBlockArtist.draw(20, 6, 1);
        Set<String> changed = new HashSet<>();
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 20; x++) {
                if (!f0.get(x, y).equals(f1.get(x, y))) {
                    changed.add(x + "," + y);
                }
            }
        }
        // 帧计数数字 "0"→"1"；◆ 从 (1,4) 消失、在 (2,4) 出现——恰好 3 个 cell
        assertEquals(Set.of("15,1", "1,4", "2,4"), changed);
    }
}
