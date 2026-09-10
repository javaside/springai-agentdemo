/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;

import java.util.ArrayList;
import java.util.List;

/** Produces minimal row-local update ranges for inline frames. */
final class InlinePatch {

    record PatchRun(int row, int startCol, int endColExclusive) {
        PatchRun {
            if (row < 0 || startCol < 0 || endColExclusive <= startCol) {
                throw new IllegalArgumentException("invalid patch run");
            }
        }
    }

    private InlinePatch() {
    }

    static List<PatchRun> runs(Buffer previous, Buffer current) {
        if (!previous.area().equals(current.area())) {
            throw new IllegalArgumentException("same-sized buffers required");
        }
        List<PatchRun> result = new ArrayList<>();
        for (int row = 0; row < current.height(); row++) {
            List<PatchRun> rowRuns = rawRuns(previous, current, row);
            for (PatchRun raw : rowRuns) {
                PatchRun expanded = expandWide(previous, current, raw);
                if (!result.isEmpty()) {
                    PatchRun last = result.get(result.size() - 1);
                    if (last.row() == expanded.row() && last.endColExclusive() >= expanded.startCol()) {
                        result.set(result.size() - 1, new PatchRun(last.row(), last.startCol(),
                                Math.max(last.endColExclusive(), expanded.endColExclusive())));
                        continue;
                    }
                }
                result.add(expanded);
            }
        }
        return List.copyOf(result);
    }

    private static List<PatchRun> rawRuns(Buffer previous, Buffer current, int row) {
        List<PatchRun> result = new ArrayList<>();
        int start = -1;
        for (int col = 0; col < current.width(); col++) {
            boolean changed = !previous.get(col, row).equals(current.get(col, row));
            if (changed && start < 0) start = col;
            if (!changed && start >= 0) {
                result.add(new PatchRun(row, start, col));
                start = -1;
            }
        }
        if (start >= 0) result.add(new PatchRun(row, start, current.width()));
        return result;
    }

    private static PatchRun expandWide(Buffer previous, Buffer current, PatchRun run) {
        int start = run.startCol();
        while (start > 0 && (previous.get(start, run.row()).isContinuation()
                || current.get(start, run.row()).isContinuation())) {
            start--;
        }
        int end = run.endColExclusive();
        int width = current.width();
        while (end < width && (previous.get(end, run.row()).isContinuation()
                || current.get(end, run.row()).isContinuation())) {
            end++;
        }
        return new PatchRun(run.row(), start, end);
    }

    static Buffer preserveOverlap(Buffer previous, int newWidth, int newHeight) {
        return realign(previous, newWidth, newHeight, 0);
    }

    /**
     * 把上一帧搬进新尺寸的快照，并按 {@code rowShift} 整体平移：{@code new[row] = previous[row - rowShift]}。
     *
     * <p>{@code rowShift == 0} 即顶部对齐（尺寸变化时保留重叠部分）。live 区在顶部插/删行时，
     * 终端里的内容<b>确实</b>整体下移/上移了，快照必须跟着移——不移的话下一帧的逐行差分会把每一行
     * 都判成变化，等于白插/白删，还会重画出新旧两条边框（见 {@code InlineDisplay#resizeDisplay}）。
     */
    static Buffer realign(Buffer previous, int newWidth, int newHeight, int rowShift) {
        Buffer moved = Buffer.empty(Rect.of(newWidth, newHeight));
        int width = Math.min(previous.width(), newWidth);
        for (int row = 0; row < newHeight; row++) {
            int source = row - rowShift;
            if (source < 0 || source >= previous.height()) continue;
            for (int col = 0; col < width; col++) {
                moved.set(col, row, previous.get(col, source));
            }
        }
        return moved;
    }

    /**
     * 两段式版本的 {@link #realign}：{@code [0, prefixLen)} 顶部对齐（不平移，对应 DL/IL 落在
     * {@code prefixLen} 行之后、完全没碰到的那段），{@code [prefixLen, newHeight)} 按 {@code rowShift}
     * 平移（对应终端上真被 DL/IL 挪动过的那段）。{@code prefixLen==0} 时退化为
     * {@code realign(previous, newWidth, newHeight, rowShift)}。
     *
     * <p>存在的理由：{@link InlineDisplay#resizeDisplay} 顶部有一段本轮没变的稳定内容（如 todo 面板）时，
     * DL/IL 会发在 {@code prefixLen} 行之后而不是第 0 行——快照的对齐方式必须跟终端上<b>实际发生的
     * 操作</b>一致（分段平移），用单一 {@code rowShift} 整体平移会错误地把稳定前缀那段也移位，
     * 导致内部快照与终端真实内容对不上，下一帧的差分因此瞎画（该改的没改、不该动的又被判成变了）。
     */
    static Buffer realignWithStablePrefix(Buffer previous, int newWidth, int newHeight, int prefixLen, int rowShift) {
        Buffer moved = Buffer.empty(Rect.of(newWidth, newHeight));
        int width = Math.min(previous.width(), newWidth);
        for (int row = 0; row < newHeight; row++) {
            int source = row < prefixLen ? row : row - rowShift;
            if (source < 0 || source >= previous.height()) continue;
            for (int col = 0; col < width; col++) {
                moved.set(col, row, previous.get(col, source));
            }
        }
        return moved;
    }
}
