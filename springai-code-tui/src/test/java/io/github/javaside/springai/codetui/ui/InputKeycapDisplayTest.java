package io.github.javaside.springai.codetui.ui;

import dev.tamboui.text.Text;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入框 keycap 显示层降级。
 *
 * <p><b>为什么需要</b>：Terminal.app + SF Mono（用户 Grass profile）没有 keycap 字形
 * （U+20E3），把 {@code 9️⃣} 画成一个宽度大于其推进量的回退方块，压住后面的字——
 * 用户实报「9场、8场重叠」。该现象在<b>不经过 code-tui 的裸 {@code cat}</b> 下同样出现
 * （用户实测 + 像素测量：{@code 足球赛事9️⃣场} 与 {@code 足球赛事X场} 同为 11 列），
 * 属终端渲染层，改不了；故在输入框<b>显示层</b>降级。
 *
 * <p><b>契约</b>：①显示成「数字+空格」，仍是 2 列（与 {@code TerminalWidth} 的 keycap 口径
 * 一致，故折行/光标列<b>不受影响</b>）；②内部文本与发给模型的内容<b>一字不改</b>；
 * ③非 keycap 文本原样；④仅在缺字形的终端（Apple_Terminal）启用，可用
 * {@code -Dcodetui.keycapDisplay=always|never} 强制。
 */
class InputKeycapDisplayTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("codetui.keycapDisplay");
    }

    private static final ScrollbackPrinter.Sink NULL_SINK = new ScrollbackPrinter.Sink() {
        @Override public void println(Text line)   { }
        @Override public void println(String line) { }
    };

    private static final SubmitHandler NOOP = new SubmitHandler() {
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public java.util.List<String> takeBackInterjections() { return java.util.List.of(); }
    };

    private static CodeTuiView view(Path root) {
        return new CodeTuiView(new ConversationState(), NOOP, root, NULL_SINK);
    }

    // ── 纯函数：替换形态与宽度守恒 ─────────────────────────────

    /** 降级形态：keycap 三码点序列 → 「数字+空格」（2 列）；裸数字+FE0F 不算 keycap。 */
    @Test
    void keycapBecomesDigitPlusSpace() {
        assertEquals("足球赛事9 场", CodeTuiView.displaySafeInput("足球赛事9️⃣场"),
                "9+FE0F+20E3 → 「9 」（保住 2 列）");
        assertEquals("8 场", CodeTuiView.displaySafeInput("8\u20E3场"),
                "8+20E3（老输入源丢 FE0F）同样是 keycap");
        assertEquals("9\uFE0F", CodeTuiView.displaySafeInput("9\uFE0F"),
                "数字+FE0F 无 20E3 不是 keycap——TerminalWidth 也按 1 列，原样保留");
    }

    /** 宽度守恒是硬约束：替换文本必须与原文本等宽，否则折行/光标列全错。 */
    @Test
    void fallbackPreservesDisplayWidth() {
        for (String s : new String[]{"足球赛事9️⃣场", "8\u20E3场", "#️⃣*️⃣", "纯文本", "9\uFE0F"}) {
            assertEquals(dev.tamboui.text.TerminalWidth.of(s),
                    dev.tamboui.text.TerminalWidth.of(CodeTuiView.displaySafeInput(s)),
                    "宽度必须守恒：" + s);
        }
    }

    @Test
    void nonKeycapTextUntouched() {
        assertEquals("", CodeTuiView.displaySafeInput(""));
        assertEquals("普通 文本 abc", CodeTuiView.displaySafeInput("普通 文本 abc"));
        assertEquals(null, CodeTuiView.displaySafeInput(null));
        assertEquals("⚠️🍺👨‍👩‍👧", CodeTuiView.displaySafeInput("⚠️🍺👨‍👩‍👧"),
                "VS16 与 ZWJ 序列不动——TerminalWidth 口径下它们本来就对");
    }

    // ── 渲染接线：屏幕上是降级文本，内部仍是原文 ────────────────

    @Test
    @DisplayName("输入框画「9 场」但内部文本与提交内容一字不改")
    void screenShowsDegradedFormWhileTextStaysIntact(@TempDir Path root) {
        System.setProperty("codetui.keycapDisplay", "always");
        CodeTuiView v = view(root);
        v.setInputForTest("足球赛事9️⃣场");

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("足球赛事9 场"),
                "屏上应是降级形态（终端画不出 keycap）：" + screen);
        assertFalse(screen.contains("9️⃣"),
                "不得把 keycap 原样写进 Buffer——那正是重叠的来源：" + screen);
        assertEquals("足球赛事9️⃣场", v.inputTextForTest(),
                "内部文本必须一字不改（发出去的、回填的都用原文）");
    }

    @Test
    @DisplayName("非 Apple_Terminal 不降级（iTerm/VSCode 能正确渲染 keycap）")
    void fallbackOffUnlessForced(@TempDir Path root) {
        System.setProperty("codetui.keycapDisplay", "never");
        CodeTuiView v = view(root);
        v.setInputForTest("足球赛事9️⃣场");
        assertTrue(ViewScreen.of(v).contains("9️⃣"),
                "显式关闭时必须原样显示（TerminalWidth 口径下布局本来就对）：" + ViewScreen.of(v));
    }
}
