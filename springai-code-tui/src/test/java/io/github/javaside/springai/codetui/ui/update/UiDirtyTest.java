package io.github.javaside.springai.codetui.ui.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiDirty} 脏位常量契约（事件驱动 UI 的 Task 3）：
 *
 * <ul>
 *   <li>OUTPUT / VIEW / CONTROL 各占独立位，ALL 为三者之并；</li>
 *   <li>{@link UiDirty#contains(int, int)} 只在目标位完整存在时为真；</li>
 *   <li>NONE 不含任何类别。</li>
 * </ul>
 */
class UiDirtyTest {

    @Test
    @DisplayName("OUTPUT/VIEW/CONTROL 为独立位且 ALL 为并集")
    void dirtyBitsAreIndependentAndCombineIntoAll() {
        assertEquals(0b001, UiDirty.OUTPUT);
        assertEquals(0b010, UiDirty.VIEW);
        assertEquals(0b100, UiDirty.CONTROL);
        assertEquals(UiDirty.OUTPUT | UiDirty.VIEW | UiDirty.CONTROL, UiDirty.ALL);
        assertEquals(0, UiDirty.NONE);
    }

    @Test
    @DisplayName("contains 匹配完整位")
    void containsRequiresFullBits() {
        int bits = UiDirty.OUTPUT | UiDirty.CONTROL;
        assertTrue(UiDirty.contains(bits, UiDirty.OUTPUT));
        assertFalse(UiDirty.contains(bits, UiDirty.VIEW));
        assertTrue(UiDirty.contains(bits, UiDirty.CONTROL));
        assertTrue(UiDirty.contains(UiDirty.ALL, UiDirty.OUTPUT));
        assertTrue(UiDirty.contains(UiDirty.ALL, UiDirty.VIEW));
        assertTrue(UiDirty.contains(UiDirty.ALL, UiDirty.CONTROL));
        assertFalse(UiDirty.contains(UiDirty.NONE, UiDirty.OUTPUT));
        assertFalse(UiDirty.contains(UiDirty.NONE, UiDirty.VIEW));
        assertFalse(UiDirty.contains(UiDirty.NONE, UiDirty.CONTROL));
    }

    @Test
    @DisplayName("组合位可以包含多个类别")
    void combinedBitsContainEveryCategory() {
        int bits = UiDirty.OUTPUT | UiDirty.VIEW | UiDirty.CONTROL;
        assertTrue(UiDirty.contains(bits, UiDirty.OUTPUT));
        assertTrue(UiDirty.contains(bits, UiDirty.VIEW));
        assertTrue(UiDirty.contains(bits, UiDirty.CONTROL));
    }
}
