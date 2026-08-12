package dev.tamboui.inline;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlinePatchTest {

    @Test
    void identicalFramesHaveNoRuns() {
        Buffer a = Buffer.withLines("abc", "def");
        assertEquals(List.of(), InlinePatch.runs(a, a.copy()));
    }

    @Test
    void adjacentChangesMergeWithinOneRow() {
        Buffer before = Buffer.withLines("abcdef");
        Buffer after = before.copy();
        after.setString(2, 0, "XY", Style.EMPTY);
        assertEquals(List.of(new InlinePatch.PatchRun(0, 2, 4)), InlinePatch.runs(before, after));
    }

    @Test
    void runsNeverMergeAcrossRows() {
        Buffer before = Buffer.withLines("abc", "def");
        Buffer after = before.copy();
        after.setString(2, 0, "X", Style.EMPTY);
        after.setString(0, 1, "Y", Style.EMPTY);
        assertEquals(List.of(
                new InlinePatch.PatchRun(0, 2, 3),
                new InlinePatch.PatchRun(1, 0, 1)), InlinePatch.runs(before, after));
    }

    @Test
    void continuationChangeExpandsToWideCharacterOwner() {
        Buffer before = Buffer.empty(Rect.of(8, 1));
        before.setString(1, 0, "中", Style.EMPTY);
        Buffer after = before.copy();
        after.set(2, 0, Cell.EMPTY);
        assertEquals(List.of(new InlinePatch.PatchRun(0, 1, 3)), InlinePatch.runs(before, after));
    }

    @Test
    void ownerChangeIncludesWideCharacterContinuation() {
        Buffer before = Buffer.empty(Rect.of(8, 1));
        before.setString(1, 0, "中", Style.EMPTY);
        Buffer after = before.copy();
        after.setString(1, 0, "a", Style.EMPTY);
        assertEquals(List.of(new InlinePatch.PatchRun(0, 1, 3)), InlinePatch.runs(before, after));
    }

    @Test
    void styleOnlyChangeProducesOneCellRun() {
        Buffer before = Buffer.withLines("abc");
        Buffer after = before.copy();
        after.set(1, 0, after.get(1, 0).style(Style.EMPTY.reversed()));
        assertEquals(List.of(new InlinePatch.PatchRun(0, 1, 2)), InlinePatch.runs(before, after));
    }

    @Test
    void preserveOverlapKeepsOnlySharedRectangle() {
        Buffer old = Buffer.withLines("abcd", "efgh");
        Buffer grown = InlinePatch.preserveOverlap(old, 6, 3);
        assertEquals(Rect.of(6, 3), grown.area());
        assertEquals("a", grown.get(0, 0).symbol());
        assertEquals("h", grown.get(3, 1).symbol());
        assertTrue(grown.get(5, 2).isEmpty());
    }
}
