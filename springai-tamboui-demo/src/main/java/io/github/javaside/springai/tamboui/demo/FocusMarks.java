package io.github.javaside.springai.tamboui.demo;

/**
 * 第 5 课的焦点指示（纯逻辑）：聚焦面板的标题加 ▶ 前缀，未聚焦保持原样。
 * 上游 Panel 聚焦时不画任何焦点标识——Tab 切换焦点后画面毫无变化，
 * 学习者无从知道焦点在哪（用户实报「看不出变化」「+ 没反应」——焦点其实在
 * 输入框上，+ 被静默插进了文本）。标题 ▶ 让焦点状态肉眼可见。
 */
public final class FocusMarks {

    private FocusMarks() {
    }

    /** 聚焦时返回「▶ 原标题」，否则原标题原样。 */
    public static String title(String base, boolean focused) {
        return focused ? "▶ " + base : base;
    }
}
