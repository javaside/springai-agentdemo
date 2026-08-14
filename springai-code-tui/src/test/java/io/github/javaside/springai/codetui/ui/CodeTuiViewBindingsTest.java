package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SkillInfo;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void bracketedPaste_isEnabled() {
        SubmitHandler stub = text -> null;
        CodeTuiView view = new CodeTuiView(new ConversationState(), stub, Path.of("."));
        assertTrue(view.configure(4).bracketedPaste(),
                "必须开启 bracketed paste：多行粘贴才不会被拆成若干次 Enter 提交");
    }

    @Test
    void skillPickerCapsVisibleRowsWhenCatalogIsLarge() {
        List<SkillInfo> skills = IntStream.range(0, 40)
                .mapToObj(i -> new SkillInfo("skill-" + i, "description " + i, "用户"))
                .toList();
        SubmitHandler stub = new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
            @Override public List<SkillInfo> skills() { return skills; }
        };
        CodeTuiView view = new CodeTuiView(new ConversationState(), stub, Path.of("."));

        for (char c : "/skill".toCharArray()) view.feedKeyForTest(KeyEvent.ofChar(c));
        view.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        Element[] rows = view.skillPickerChildrenForTest();
        assertEquals(12, rows.length, "标题 + 10 个技能 + 1 个范围提示，面板高度必须有界");
    }

    @Test
    void skillPickerCanSelectItemBeyondFirstWindow() {
        List<SkillInfo> skills = IntStream.range(0, 40)
                .mapToObj(i -> new SkillInfo("skill-" + i, "description " + i, "用户"))
                .toList();
        SubmitHandler stub = new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
            @Override public List<SkillInfo> skills() { return skills; }
        };
        CodeTuiView view = new CodeTuiView(new ConversationState(), stub, Path.of("."));
        for (char c : "/skill".toCharArray()) view.feedKeyForTest(KeyEvent.ofChar(c));
        view.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        for (int i = 0; i < 15; i++) view.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        String screen = ViewScreen.of(view);
        assertTrue(screen.contains("❯ 16. skill-15"), "窗口应跟随高亮项滚动");
        view.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals("skill-15", view.pendingSkillForTest(), "窗口外技能仍应能确认挂载");
    }
}
