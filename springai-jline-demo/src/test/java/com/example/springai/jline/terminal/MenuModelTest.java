package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MenuModelTest {

    @Test
    void downAdvancesSelection() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        assertEquals(0, m.selectedIndex());
        m.down();
        assertEquals(1, m.selectedIndex());
    }

    @Test
    void downWrapsToTop() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        m.down();
        m.down();
        m.down(); // 从最后一项再下 -> 回到 0
        assertEquals(0, m.selectedIndex());
    }

    @Test
    void upWrapsToBottom() {
        MenuModel m = new MenuModel(List.of("a", "b", "c"));
        m.up(); // 从 0 向上 -> 回到最后一项
        assertEquals(2, m.selectedIndex());
    }

    @Test
    void exposesItemsAndSelectedLabel() {
        MenuModel m = new MenuModel(List.of("a", "b"));
        assertEquals(List.of("a", "b"), m.items());
        m.down();
        assertEquals("b", m.selectedLabel());
    }

    @Test
    void emptyItemsThrows() {
        assertThrows(IllegalArgumentException.class, () -> new MenuModel(List.of()));
    }
}
