package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardLayoutTest {

    @Test
    void centersContentWithinLargerSpace() {
        assertEquals(30, DashboardLayout.centerStart(80, 20));
    }

    @Test
    void clampsToZeroWhenContentTooLarge() {
        assertEquals(0, DashboardLayout.centerStart(10, 20));
    }

    @Test
    void barWidthScalesWithProgress() {
        // 进度 50%、可用 10 格 -> 填充 5 格
        assertEquals(5, DashboardLayout.filledCells(10, 0.5));
    }

    @Test
    void barWidthClampsToRange() {
        assertEquals(0, DashboardLayout.filledCells(10, -1.0));
        assertEquals(10, DashboardLayout.filledCells(10, 2.0));
    }
}
