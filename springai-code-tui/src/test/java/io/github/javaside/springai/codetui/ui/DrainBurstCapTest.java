package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每帧 pty 写入限速：断言单位是<b>真实写进终端的物理行</b>，不是逻辑 OutputLine。
 *
 * <p><b>为什么必须有这张网</b>：限速的存在理由是「别在一帧里给终端灌几百 KB」——
 * 大突发既让渲染线程长时间占住（按键排队，用户感知「打字卡死」），又把 macOS Terminal.app
 * 推进它自己的 use-after-free 危险区（整个终端崩溃，崩溃栈在 IME 的
 * {@code setMarkedText:} → {@code selectedRange}）。而此前限速有两处漏网，都只有按物理行
 * 计数才看得见：① 流式完整行那条路径<b>根本没接限速</b>——偏偏「窗口正在输出」走的就是它；
 * ② {@code drainPending(300)} 的 300 是逻辑条数，长正文经折行放大十几倍。
 */
class DrainBurstCapTest {

    /** 记录型输出接缝：视图内层已按终端宽折过行，这里收到的就是一条条物理行。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static CodeTuiView view(ConversationState state, Path root, RecordingSink sink) {
        return new CodeTuiView(state, new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root, sink);
    }

    /** 单条 OutputLine 是原子的（打到一半停不下来），故允许一条的放大量作为超额余量。 */
    private static final int CAP = 300;
    private static final int SLACK = 200;

    @Test
    @DisplayName("流式输出：一帧灌 5000 行时，写进 pty 的物理行数受限（此前完全无限速）")
    void streamingPath_isRateLimited(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        state.onAssistantToken(1L, big.toString());

        v.tickForTest();

        assertTrue(sink.lines.size() <= CAP + SLACK,
                "一帧写入的物理行数应受限于 %d(+%d 余量)，实际 %d".formatted(CAP, SLACK, sink.lines.size()));
        assertTrue(sink.lines.size() > 0, "限速不是关闸：本帧仍应有行下沉");
    }

    @Test
    @DisplayName("流式输出：限速只是渐进显示，多帧之后一行不丢、顺序不乱")
    void streamingPath_losesNothingAcrossFrames(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        int total = 1000;
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < total; i++) big.append("line ").append(i).append('\n');
        state.onAssistantToken(1L, big.toString());

        for (int frame = 0; frame < 200 && sink.lines.size() < total; frame++) v.tickForTest();

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("line ")).toList();
        assertEquals(total, body.size(), "多帧后应把全部 %d 行都下沉完".formatted(total));
        assertEquals("line 0", body.get(0), "顺序不能乱：首行");
        assertEquals("line " + (total - 1), body.get(total - 1), "顺序不能乱：末行");
    }

    @Test
    @DisplayName("pending 输出：300 条长正文折行后仍受物理行限速（此前放大 15 倍）")
    void pendingPath_capCountsPhysicalRows(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        String para = "x".repeat(1200);          // 80 列下折成 ~16 物理行
        for (int i = 0; i < 300; i++) state.pushInfo(para);

        v.tickForTest();

        assertTrue(sink.lines.size() <= CAP + SLACK,
                "一帧写入的物理行数应受限于 %d(+%d 余量)，实际 %d".formatted(CAP, SLACK, sink.lines.size()));
    }

    @Test
    @DisplayName("pending 输出：限速后剩余行留到下一帧，内容不丢")
    void pendingPath_losesNothingAcrossFrames(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        for (int i = 0; i < 500; i++) state.pushInfo("info " + i);

        for (int frame = 0; frame < 200 && sink.lines.size() < 500; frame++) v.tickForTest();

        List<String> body = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("info ")).toList();
        assertEquals(500, body.size(), "多帧后应把全部 500 条都下沉完");
        assertEquals("info 0", body.get(0), "顺序不能乱：首条");
        assertEquals("info 499", body.get(499), "顺序不能乱：末条");
    }

    @Test
    @DisplayName("正常大小的输出不受影响：一帧内打完，不被拆到下一帧")
    void smallOutput_isNotDelayed(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        for (int i = 0; i < 50; i++) state.pushInfo("short " + i);

        v.tickForTest();

        long got = sink.lines.stream().map(String::strip).filter(s -> s.startsWith("short ")).count();
        assertEquals(50, got, "50 条短行远低于限速上限，应当一帧打完");
    }
}
