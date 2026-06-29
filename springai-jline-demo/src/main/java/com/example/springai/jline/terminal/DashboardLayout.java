package com.example.springai.jline.terminal;

/**
 * 纯逻辑：仪表盘居中与进度条计算。无 IO，可单测。
 */
public final class DashboardLayout {

    private DashboardLayout() {
    }

    /** 在 total 宽（或高）内居中放置 content 宽（或高）的起始坐标，最小 0。 */
    public static int centerStart(int total, int content) {
        return Math.max(0, (total - content) / 2);
    }

    /** 进度条填充格数：progress 取 [0,1]，越界自动夹取。 */
    public static int filledCells(int width, double progress) {
        double p = Math.max(0.0, Math.min(1.0, progress));
        return (int) Math.round(width * p);
    }
}
