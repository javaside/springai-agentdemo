package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态栏的「插话 N 条」实时计数。
 *
 * <p><b>为什么断言落在渲染结果上而不是 {@code statusLine()} 的返回值</b>：这一段是拼进 shimmer
 * 的 suffix 的，只断言拼串函数测不到「有没有真被拼进去」，也测不到前面某个分支（notice/压缩指示器）
 * 是否把整行盖掉——那正是本项目 {@code CodeTuiViewBusyNoticeTest} 钉过的真实缺陷。故走 ViewScreen。
 *
 * <p>计数是<b>实时</b>的，这也是插话回显那行刻意不写送达状态的前提：scrollback 里的行改不了，
 * 状态会永远停在错的位置上，只有状态栏能如实反映「还有几条没送出去」。
 */
class CodeTuiViewInterjectionStatusTest {

    private static final class Handler implements SubmitHandler {
        int pending;
        @Override public Disposable submit(String text) { return () -> {}; }
        @Override public int pendingInterjections() { return pending; }
        @Override public String currentModel() { return "deepseek-chat"; }
    }

    @Test
    @DisplayName("有未送达插话时状态栏显示条数")
    void statusShowsPendingInterjections() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // → THINKING（插话只在回合在飞时可能存在）
        h.pending = 2;

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("插话 2 条"), "状态栏应显示未送达条数:\n" + screen);
        assertTrue(screen.contains("思考中"), "运行指示不该被这一段挤掉:\n" + screen);
    }

    /** 跑工具时同样要显示——插话最典型的场景正是「工具跑着呢，我想改个方向」。 */
    @Test
    @DisplayName("跑工具时也显示插话条数")
    void statusShowsPendingWhileRunningTool() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);
        s.onToolStarted(1, "Bash", "{}");          // → RUNNING_TOOL
        h.pending = 1;

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("插话 1 条"), "状态栏应显示未送达条数:\n" + screen);
    }

    /** 空串必须判：不判会渲染出一段悬空的 " · "，或者更糟的「插话 0 条」。 */
    @Test
    @DisplayName("没有插话时不渲染该段（避免悬空分隔符）")
    void statusOmitsSegmentWhenZero() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("插话"), "无插话时不该出现该段:\n" + screen);
        assertFalse(screen.contains("思考中… ·  · "), "不该留下悬空分隔符:\n" + screen);
    }
}
