package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** ⏱ 后台任务面板的行文本组装（模型标签有则附、无则不附）。 */
class BackgroundPanelTest {

    @Test
    void row_appendsModelWhenPresent() {
        ConversationState.BackgroundView v = new ConversationState.BackgroundView(
                "t1", "explore", "d", ConversationState.BackgroundStatus.RUNNING, "",
                0L, 0L, "", "deepseek:deepseek-v4-pro");
        String r = CodeTuiView.backgroundRowText(v, 0L);
        assertTrue(r.contains("deepseek:deepseek-v4-pro"), "附带模型标签，实际=" + r);
    }

    @Test
    void row_noModelOmitsModelTag() {
        ConversationState.BackgroundView v = new ConversationState.BackgroundView(
                "t1", "explore", "d", ConversationState.BackgroundStatus.RUNNING, "",
                0L, 0L, "", "");
        String r = CodeTuiView.backgroundRowText(v, 0L);
        assertTrue(!r.contains("deepseek"), "无模型时不附任何模型相关文本，实际=" + r);
    }
}
