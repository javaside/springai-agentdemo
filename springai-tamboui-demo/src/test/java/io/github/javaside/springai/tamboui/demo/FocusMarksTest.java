package io.github.javaside.springai.tamboui.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 第 5 课的焦点指示（纯逻辑）：聚焦面板标题加 ▶ 前缀，未聚焦保持原样——
 * Tab 切换焦点时标题随之变化，焦点状态肉眼可见（此前面板聚焦无任何视觉反馈，
 * 用户实报「+ 按键没反应」——焦点其实在输入框上，+ 静默进了文本）。
 */
class FocusMarksTest {

    @Test
    void focusedTitleGetsMarker() {
        assertEquals("▶ 目标行数练习", FocusMarks.title("目标行数练习", true));
    }

    @Test
    void unfocusedTitleStaysPlain() {
        assertEquals("目标行数练习", FocusMarks.title("目标行数练习", false));
    }
}
