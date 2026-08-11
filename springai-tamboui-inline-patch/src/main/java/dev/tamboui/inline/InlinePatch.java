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
        Buffer preserved = Buffer.empty(Rect.of(newWidth, newHeight));
        int width = Math.min(previous.width(), newWidth);
        int height = Math.min(previous.height(), newHeight);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                preserved.set(col, row, previous.get(col, row));
            }
        }
        return preserved;
    }
}
