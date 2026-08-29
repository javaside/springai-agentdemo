package io.github.javaside.springai.codetui.ui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未送达插话的面板（钉在输入框上方，形状照排队消息面板）。
 *
 * <p><b>为什么必须有这个面板</b>：插话在输入那一刻不再往 scrollback 打行（位置会是错的，
 * 且 scrollback 改不了）。未送达期间它在屏幕上的唯一存在就是这个面板——没有它，
 * 用户按完回车会看到<b>什么都没发生</b>。
 *
 * <p>断言落在渲染结果（{@link ViewScreen}）上而不是构造函数的返回值：这类缺陷多半是
 * 面板压根没被挂进 {@code render()} 的 column，只测构造永远是绿的。
 */
class CodeTuiViewInterjectionPanelTest {

    /**
     * ⚠ {@code takePendingInterjections} 必须<b>真的消费</b>队列（照 {@code Interjections} 的语义）。
     * 让它退化成 no-op 默认实现的话，「面板接错到取走那个方法上」这个变异就只会表现成
     * 「面板空了」，而不是它真正的后果——<b>渲染一帧把插话吞掉、面板看上去却很正常</b>。
     */
    private static final class Handler implements SubmitHandler {
        final List<String> pending = new ArrayList<>();
        @Override public Disposable submit(String text) { return () -> {}; }
        @Override public String currentModel() { return "deepseek-chat"; }
        @Override public void interject(String text) { pending.add(text); }
        @Override public int pendingInterjections() { return pending.size(); }
        @Override public List<String> pendingInterjectionTexts() { return List.copyOf(pending); }

        @Override public List<String> takePendingInterjections() {
            List<String> out = List.copyOf(pending);
            pending.clear();
            return out;
        }
    }

    private static CodeTuiView viewWith(ConversationState s, Handler h) {
        return new CodeTuiView(s, h, Path.of("."));
    }

    @Test
    @DisplayName("未送达插话显示在输入框上方")
    void pendingInterjectionIsVisible() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("改用方案 B");

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("改用方案 B"),
                "按完回车屏幕上什么都没有，用户会以为这条消息丢了:\n" + screen);
    }

    /** 多条按输入顺序列全，不能只显示最后一条或一个计数。 */
    @Test
    @DisplayName("多条插话逐条列出，保持输入顺序")
    void multipleInterjectionsListedInOrder() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("第一句");
        h.interject("第二句");

        String screen = ViewScreen.of(v);
        int first = screen.indexOf("第一句");
        int second = screen.indexOf("第二句");
        assertTrue(first >= 0 && second >= 0, "两条都该在:\n" + screen);
        assertTrue(first < second, "顺序反了——先说的该在上面:\n" + screen);
    }

    /**
     * 插话与排队消息<b>都</b>钉在输入框上方、都是「还没走的话」，但去处不同：
     * 插话随本回合下一次模型调用送达，排队要等整个回合跑完。
     * 长得一样的话，用户分不清自己那句话什么时候会被听见。
     */
    @Test
    @DisplayName("插话与排队消息用不同行首符号区分")
    void interjectionIsDistinguishableFromQueued() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("这条马上送");
        s.enqueue("这条等下回合", null);

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("⤷ 这条马上送"), "插话应带自己的行首符号:\n" + screen);
        assertTrue(screen.contains("› 这条等下回合"), "排队消息应保持原样:\n" + screen);
    }

    /**
     * 区分不能只靠行首符号，前景色也得真的不一样——而<b>底色必须与排队面板一致</b>。
     *
     * <p>上面那条只断言了文本，样式改没改它读不出来。这个 TUI 里样式不是装饰：
     * {@code InlineDisplay} 下底色会串到下一行（见 {@code Theme.PICK_SEL} 的说明），
     * 所以「换个底色来区分」是错的做法，必须钉住它没被这么改。
     */
    @Test
    @DisplayName("插话行前景与排队行不同、底色相同")
    void interjectionDiffersInForegroundNotBackground() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("这条马上送");
        s.enqueue("这条等下回合", null);

        Buffer buf = ViewScreen.bufferOf(v);
        Cell ij = firstCellOf(buf, "⤷ 这条马上送");
        Cell queued = firstCellOf(buf, "› 这条等下回合");
        assertNotEquals(ij.style().fg(), queued.style().fg(),
                "两个面板前景色一样——只靠一个行首符号区分太弱");
        assertEquals(queued.style().bg(), ij.style().bg(),
                "底色被改成不一样的了——本 TUI 下底色会串到下一行，区分不能靠它");
    }

    /** 该行第一个非空格单元（行首缩进是空格，样式取不准）。 */
    private static Cell firstCellOf(Buffer buf, String rowText) {
        for (int y = 0; y < buf.height(); y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < buf.width(); x++) {
                Cell c = buf.get(x, y);
                if (!c.isContinuation()) line.append(c.symbol().isEmpty() ? " " : c.symbol());
            }
            if (line.toString().contains(rowText)) {
                for (int x = 0; x < buf.width(); x++) {
                    Cell c = buf.get(x, y);
                    if (!c.isContinuation() && !c.symbol().isBlank()) return c;
                }
            }
        }
        throw new AssertionError("屏幕上找不到 " + rowText);
    }

    /** 面板顺序要反映送达顺序：插话先走（drain 排在 pollQueued 之前），就该排在上面。 */
    @Test
    @DisplayName("插话排在排队消息上方（先走的在上）")
    void interjectionPanelSitsAboveQueued() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("这条马上送");
        s.enqueue("这条等下回合", null);

        String screen = ViewScreen.of(v);
        assertTrue(screen.indexOf("这条马上送") < screen.indexOf("这条等下回合"),
                "先送达的该在上面:\n" + screen);
    }

    /**
     * 送达之后面板必须清干净：那句话此刻已经在信息流里了，面板再留一份就是同一句话
     * 在屏幕上同时出现两次。
     */
    @Test
    @DisplayName("送达后面板不再显示它")
    void deliveredInterjectionLeavesPanel() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("改用方案 B");
        assertTrue(ViewScreen.of(v).contains("改用方案 B"));

        h.pending.clear();                              // 模型层取走了（drainForInjection）

        assertFalse(ViewScreen.of(v).contains("改用方案 B"),
                "送达后面板还留着，同一句话会在屏幕上出现两次");
    }

    /** 零插话时不该占行——固定区每多一行，scrollback 就少一行。 */
    @Test
    @DisplayName("没有插话时面板不占行")
    void emptyPanelTakesNoRow() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);

        assertFalse(ViewScreen.of(v).contains("⤷"), "空面板不该渲染出行首符号");
    }

    /**
     * 面板读的必须是<b>非破坏性</b>快照。接到 {@code takePendingInterjections()} 上的话，
     * 渲染一帧就把队列清空——面板看上去还很正常（它读的就是刚被自己清掉的那份），
     * 但插话再也送不到模型手里。
     */
    @Test
    @DisplayName("渲染不消费插话队列")
    void renderingDoesNotConsumeQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = viewWith(s, h);
        s.onTurnStarted(1);
        h.interject("改用方案 B");

        ViewScreen.of(v);
        ViewScreen.of(v);

        assertEquals(List.of("改用方案 B"), h.pending, "渲染把队列渲没了");
    }
}
