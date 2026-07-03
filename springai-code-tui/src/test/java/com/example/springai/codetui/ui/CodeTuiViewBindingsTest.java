package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：输入框「打/粘贴含 q 的文本就退出」的 bug。
 *
 * <p>根因是 TamboUI 默认绑定 {@code quit = q, Q, Ctrl+c}，而输入框是唯一焦点、每个按键先过 {@code isQuit()}。
 * 修复把 {@code CodeTuiView.configure()} 里的 quit 整组重绑为仅 Ctrl+C。这里直接断言重绑后的绑定表：
 * 裸 {@code q}/{@code Q} 不再是 quit，Ctrl+C 仍是 quit。
 */
class CodeTuiViewBindingsTest {

    /** 用测试专用子类暴露 protected 的 {@code configure}（同包也可，显式更清楚）。 */
    private static Bindings resolvedBindings() {
        SubmitHandler stub = text -> null;   // 只需能 new 出 View，submit 不会被调用
        CodeTuiView view = new CodeTuiView(new ConversationState(), stub, Path.of("."));
        InlineTuiConfig cfg = view.configure(4);
        return cfg.bindings();
    }

    @Test
    void bareQ_isNotQuit() {
        Bindings b = resolvedBindings();
        assertFalse(b.matches(KeyEvent.ofChar('q'), Actions.QUIT), "裸 q 不应再触发 quit");
        assertFalse(b.matches(KeyEvent.ofChar('Q'), Actions.QUIT), "裸 Q 不应再触发 quit");
    }

    @Test
    void ctrlC_stillQuits() {
        Bindings b = resolvedBindings();
        KeyEvent ctrlC = new KeyEvent(KeyCode.CHAR, KeyModifiers.CTRL, 'c');
        assertTrue(b.matches(ctrlC, Actions.QUIT), "Ctrl+C 仍应触发 quit");
    }
}
