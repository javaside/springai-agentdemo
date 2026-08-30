package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出批次之间的<b>输入公平性</b>：大输出占住 UI 线程期间，按键必须能在「全部输出完成之前」被处理。
 *
 * <p><b>为什么必须有这张网</b>：渲染线程是单线程事件循环，drain 与按键处理串行。旧实现的物理行
 * 限速是软上限——单条 OutputLine（长正文 / 大 diff / 无换行超长行）原子展开后一帧可写几百上千行，
 * 期间按键全部排队，用户感知就是「输出的时候打字卡死」（见 {@link DrainBurstCapTest} 的动机）。
 * 严格分批（逻辑输出 → 可续消费物理行 cursor，见 {@code PhysicalOutputQueue}）之后，每个输出批次
 * 结束就返回事件循环，批与批之间键盘事件可被处理。
 *
 * <p><b>fake 事件队列</b>：把「输出批次」和「按键」都建模成事件，按真实事件循环的顺序消费——
 * 队头是输出批次就 drain 一批，是按键就处理按键。然后构造「先有一大坨输出，中途插一个按键」
 * 的交错，断言按键的处理时刻<b>早于</b>最后一行输出下沉的时刻。若输出仍按旧方式一帧灌完，
 * 队列里按键必然排在全部输出之后——断言失败。
 */
class StrictOutputFairnessTest {

    /** 记录型输出接缝：按行记录「何时被写」。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(dev.tamboui.text.Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static CodeTuiView view(ConversationState state, Path root, RecordingSink sink) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root, sink);
    }

    /** fake 事件：一次输出批次（等价 66ms drain 到达），或一次按键。 */
    private sealed interface Event permits OutputBatch, KeyPress {}
    private record OutputBatch() implements Event {}
    private record KeyPress(KeyEvent key) implements Event {}

    /**
     * 按事件循环语义消费队列：输出批次 → {@code tickForTest()}（一批 drain），按键 → 输入框。
     * 记录每个事件处理完成时刻的全局步数，供公平性断言。
     */
    private static final class FakeEventLoop {
        int clock;                                       // 单调递增的「处理完成时刻」
        int keyHandledAt = -1;                           // 那个按键被处理的时刻
        int lastOutputAt = -1;                           // 最后一行输出下沉的时刻
        final CodeTuiView view;
        final RecordingSink sink;

        FakeEventLoop(CodeTuiView view, RecordingSink sink) {
            this.view = view;
            this.sink = sink;
        }

        void run(List<Event> events) {
            for (Event e : events) {
                if (e instanceof KeyPress kp) {
                    view.feedKeyForTest(kp.key());
                    keyHandledAt = ++clock;
                } else if (e instanceof OutputBatch) {
                    int before = sink.lines.size();
                    view.tickForTest();
                    clock++;
                    if (sink.lines.size() > before) {
                        lastOutputAt = clock;            // 本批仍有输出 ⇒ 输出尚未全部完成
                    }
                }
            }
        }
    }

    private static final int DRAIN_BATCH_LIMIT = 300;    // 与 CodeTuiView.MAX_ROWS_PER_DRAIN 对齐

    @Test
    @DisplayName("批间公平：大输出 drain 到一半插入的按键，先于全部输出完成被处理")
    void keyPressBetweenBatches_isHandledBeforeOutputCompletes(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        // 场景刻意做成「单条输出就超过一批」：一条无换行超长 INFO（~500 物理行）+ 尾随一条短行。
        // 旧实现里单条 OutputLine 是原子的——第一个 tick 必然把这条打完（全部输出随之完成），
        // 队列里的按键只能排在其后；严格分批后第一批在 300 行停下，按键落在两批之间。
        state.onTurnStarted(1L);
        state.pushInfo("long " + "y".repeat(40_000));   // ~500 物理行
        state.pushInfo("tail");

        FakeEventLoop loop = new FakeEventLoop(v, sink);
        List<Event> events = new ArrayList<>();
        events.add(new OutputBatch());                                // 第一批（300 行，剩余 ~200）
        events.add(new KeyPress(KeyEvent.ofChar('h')));               // 用户此刻按下了一个键
        for (int i = 0; i < 100; i++) events.add(new OutputBatch());  // continuation 直到排空

        loop.run(events);

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        assertTrue(body.size() > DRAIN_BATCH_LIMIT,
                "前置：这坨输出必须跨多个批次（实际 " + body.size() + " 行 > 上限 " + DRAIN_BATCH_LIMIT
                        + "）——否则「批间」无从谈起");
        assertTrue(body.stream().anyMatch(s -> s.startsWith("tail")), "尾随短行也必须最终下沉");
        assertTrue(loop.keyHandledAt >= 0 && loop.lastOutputAt >= 0, "按键与输出都应被处理过");
        assertTrue(loop.keyHandledAt < loop.lastOutputAt,
                "按键（t=" + loop.keyHandledAt + "）必须先于全部输出完成（t=" + loop.lastOutputAt
                        + "）被处理——批间输入公平性被破坏");
        assertEquals("h", v.inputTextForTest(), "按键应已落进输入框（真实到达，不是被吞）");
    }

    @Test
    @DisplayName("无新 Agent 写入时，纯 continuation 也能把输出排空（不依赖新事件才前进）")
    void continuationDrainsAll_withoutNewAgentEvents(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        int total = 2000;
        for (int i = 0; i < total; i++) state.pushInfo("row " + i);
        state.onTurnComplete(1L);                        // 此后再无任何新写入

        FakeEventLoop loop = new FakeEventLoop(v, sink);
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < 2000; i++) events.add(new OutputBatch());
        loop.run(events);

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("row ")).toList();
        assertEquals(total, body.size(), "无新事件也应最终排空全部 " + total + " 行");
        assertEquals("row 0", body.get(0), "顺序不能乱：首行");
        assertEquals("row " + (total - 1), body.get(total - 1), "顺序不能乱：末行");
    }

    @Test
    @DisplayName("首帧被截断的输出在按键插入后继续按原顺序下沉（批间不重排、不丢行）")
    void interleavedKeyDoesNotDisturbOutputOrder(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        int total = 600;
        for (int i = 0; i < total; i++) state.pushInfo("ord " + i);

        v.tickForTest();                                // 第一批（被 300 行上限截断）
        int firstBatchRows = sink.lines.size();
        assertTrue(firstBatchRows > 0, "前置：首批应有输出");
        assertTrue(firstBatchRows <= DRAIN_BATCH_LIMIT, "前置：首批应被上限截断，实际 " + firstBatchRows);

        FakeEventLoop loop = new FakeEventLoop(v, sink);
        List<Event> events = new ArrayList<>();
        events.add(new KeyPress(KeyEvent.ofChar('a')));
        for (int i = 0; i < 1000; i++) events.add(new OutputBatch());
        loop.run(events);

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("ord ")).toList();
        assertEquals(total, body.size(), "按键穿插后输出仍一行不丢");
        for (int i = 0; i < total; i++) {
            assertEquals("ord " + i, body.get(i), "穿插按键后顺序仍不能乱：第 " + i + " 行");
        }
    }
}
