package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死回归：取消一个进行中的长回合（如正在执行子 agent）后，状态栏的「已取消当前回合」sticky notice
 * 必须能在下一次按键时清除、恢复常态行——否则状态栏永久卡在提示上（真实路径经 inputState 编辑器，
 * 旧的 typeChar 清 notice 早已失效）。
 */
class CodeTuiViewCancelNoticeTest {

    private static CodeTuiView view(ConversationState s) {
        return new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
    }

    @Test
    void cancelNotice_clearsOnNextKeypress() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);                          // 回合进行中（模拟子 agent 长时间执行）
        assertFalse(s.isIdle(), "回合进行中");
        CodeTuiView v = view(s);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));   // Esc 取消
        assertTrue(s.isIdle(), "取消后回 IDLE");
        assertEquals("已取消当前回合", s.notice(), "取消后应显示提示");

        v.feedKeyForTest(KeyEvent.ofChar('a'));             // 下一次按键
        assertEquals("", s.notice(), "下一次按键应清除提示、状态栏恢复常态");
    }
}
