package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Esc 取消回合时把插话回填输入框（而非丢弃），以及回合末的兜底出队。
 */
class CodeTuiViewInterjectionCancelTest {

    /**
     * 插话队列的替身：用纯列表模拟「未送达 / 已送达」两态。
     *
     * <p><b>刻意不用真的 {@code Interjections}</b>：它的 {@code drainForInjection} 是包私有的，
     * 本测试在 {@code ui} 包里根本调不到（跨包编译不过）。而且本测试要验的是 View 的路由，
     * 队列自身的语义归 {@code InterjectionsTest}——用替身反而边界更清楚。
     */
    private static final class Handler implements SubmitHandler {
        final List<String> pending = new ArrayList<>();
        String delivered;                        // 已送达模型、尚未入历史的那条
        final List<String> submitted = new ArrayList<>();

        @Override public Disposable submit(String text) { return submit(text, null); }
        @Override public Disposable submit(String text, String skill) { submitted.add(text); return () -> {}; }
        @Override public void interject(String text) { pending.add(text); }
        @Override public int pendingInterjections() { return pending.size(); }

        @Override public List<String> takePendingInterjections() {
            List<String> out = List.copyOf(pending);
            pending.clear();
            return out;
        }

        @Override public List<String> takeBackInterjections() {
            List<String> out = new ArrayList<>();
            if (delivered != null) { out.add(delivered); }
            out.addAll(pending);
            pending.clear();
            delivered = null;
            return out;
        }
    }

    @Test
    @DisplayName("Esc 把未送达与已送达的插话一起回填输入框")
    void cancelRefillsInterjectionsIntoInput() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        h.delivered = "先说的";        // 已送达模型，但回合随后被取消 → 没人补它进历史
        h.pending.add("后说的");       // 还没送出去

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals("先说的\n后说的", v.inputTextForTest(),
                "Esc 后插话应回到输入框——模型看过、历史没有、用户还拿不回来的话就凭空消失了");
        assertTrue(s.notice().contains("插话已放回输入框"), "得告诉用户话去哪了：" + s.notice());
    }

    /** 没有插话时文案不该多出后缀——原样保持，否则是既有回归。 */
    @Test
    @DisplayName("无插话时取消文案不变")
    void cancelNoticeUnchangedWithoutInterjections() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals("已取消当前回合", s.notice());
        assertEquals("", v.inputTextForTest(), "没插话就不该往输入框里塞东西");
    }
}
