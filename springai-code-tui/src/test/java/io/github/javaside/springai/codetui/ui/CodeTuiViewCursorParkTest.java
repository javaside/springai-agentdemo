package io.github.javaside.springai.codetui.ui;

import dev.tamboui.layout.Position;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 硬件光标的停放策略：<b>平时停在输入文本行、resize 进行中才临时钉到显示区第 0 行</b>。
 *
 * <p>硬件光标是 IME 预编辑串的锚点（Terminal.app 把拼音画在硬件光标处）。旧 resize
 * 方案曾把它<b>永久</b>钉在第 0 行（框顶边框），代价被低估了：中文用户每次拼字，拼音
 * 都浮在边框上——用户实报「打字时错位」。
 * 现在钉 0 行只发生在「宽度变化 → 停稳重放完成」窗口内（拖拽中没人打字，IME 无所谓）。
 *
 * <p>可见的反显块光标不在此列（那是 Buffer 样式），这里只断言 {@code frame.setCursorPosition}。
 */
class CodeTuiViewCursorParkTest {

    private static final class NoopHandler implements SubmitHandler {
        @Override public Disposable submit(String text) { return null; }
    }

    private static CodeTuiView view() {
        return new CodeTuiView(new ConversationState(), new NoopHandler(), Path.of("."));
    }

    private static void type(CodeTuiView v, String s) {
        s.codePoints().forEach(cp -> v.feedKeyForTest(KeyEvent.ofChar((char) cp)));
    }

    /** 文本 "abc" 所在的缓冲行号——光标行必须与它一致，写死行号会跟布局漂移。 */
    private static int rowOf(String screen, String needle) {
        String[] lines = screen.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("屏上找不到 %s：\n%s".formatted(needle, screen));
    }

    @Test
    void typing_parksHardwareCursorOnTextRow() {
        CodeTuiView v = view();
        type(v, "abc");
        int textRow = rowOf(ViewScreen.of(v), "abc");
        Position cur = ViewScreen.cursorOf(v, 120);
        assertNotNull(cur, "输入框聚焦时必须设置硬件光标（IME 锚点）");
        assertEquals(textRow, cur.y(), "平时（非 resize 窗口）硬件光标应停在输入文本行——钉在别处 IME 拼字就错位");
        assertEquals(1 + 3, cur.x(), "列应跟在已输入文本之后（边框 1 列 + 'abc' 3 列）");
    }

    @Test
    void typingCjk_cursorColumnCountsDisplayWidth() {
        CodeTuiView v = view();
        type(v, "你好");
        int textRow = rowOf(ViewScreen.of(v), "你好");
        Position cur = ViewScreen.cursorOf(v, 120);
        assertNotNull(cur);
        assertEquals(textRow, cur.y());
        assertEquals(1 + 4, cur.x(), "中文占 2 列：边框 1 列 + '你好' 4 列");
    }

    @Test
    void duringResizeWindow_cursorPinnedToRow0() {
        CodeTuiView v = view();
        type(v, "abc");
        v.parkCursorAtTop = true;                    // 宽度变化事件置位（同包直写，见字段注释）
        Position cur = ViewScreen.cursorOf(v, 120);
        assertNotNull(cur);
        assertEquals(0, cur.y(), "resize 窗口内应钉到显示区第 0 行，避免终端 reflow 改变相对光标记账");
        assertEquals(1 + 3, cur.x(), "列仍跟随输入（宽度变化时反推清扫起点用不到列，但 IME 半途切回时列对得上）");
    }

    @Test
    void emptyInput_followsSameParkingRule() {
        CodeTuiView v = view();
        int boxTop = rowOf(ViewScreen.of(v), "╭");   // 输入框顶边框所在行，布局漂移也跟得住
        Position idle = ViewScreen.cursorOf(v, 120);
        assertNotNull(idle);
        assertEquals(boxTop + 1, idle.y(), "空态光标应在框内文本行——IME 拼字常从空输入开始，钉边框上一样错位");
        assertEquals(1, idle.x());
        v.parkCursorAtTop = true;
        Position parked = ViewScreen.cursorOf(v, 120);
        assertNotNull(parked);
        assertEquals(0, parked.y(), "resize 窗口内空态也钉第 0 行");
        v.parkCursorAtTop = false;
        Position back = ViewScreen.cursorOf(v, 120);
        assertNotNull(back);
        assertEquals(boxTop + 1, back.y(), "窗口结束应回到文本行");
    }
}
