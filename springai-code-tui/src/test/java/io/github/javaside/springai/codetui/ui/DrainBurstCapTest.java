package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每帧 pty 写入限速：断言单位是<b>真实写进终端的物理行</b>，不是逻辑 OutputLine。
 *
 * <p><b>为什么必须有这张网</b>：限速的存在理由是「别在一帧里给终端灌几百 KB」——
 * 大突发既让渲染线程长时间占住（按键排队，用户感知「打字卡死」），又把 macOS Terminal.app
 * 推进它自己的 use-after-free 危险区（整个终端崩溃，崩溃栈在 IME 的
 * {@code setMarkedText:} → {@code selectedRange}）。而此前限速有多处漏网，都只有按物理行
 * 计数才看得见：① 流式完整行那条路径<b>根本没接限速</b>——偏偏「窗口正在输出」走的就是它；
 * ② {@code drainPending(300)} 的 300 是逻辑条数，长正文经折行放大十几倍；
 * ③ 单条 OutputLine 是<b>原子</b>的——打到一半停不下来，单个大输出（长正文/大 diff/无换行超长行）
 * 展开后照样突破软上限（旧版靠 SLACK=200 掩盖）。
 *
 * <p><b>本任务（严格限流）之后的上限是硬上限</b>：逻辑输出先渲染成可续消费的物理行
 * cursor（见 {@code PhysicalOutputQueue}），drain 在取第 {@code maxPhysicalRows+1} 行之前停下。
 * 故本测试全部断言 {@code <= 300}，<b>不再有任何余量</b>；任何一条用例超限都是回归。
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

    /**
     * 单帧硬上限。旧实现按「条」取、且单条 OutputLine 原子展开，只能给 {@code CAP+SLACK} 的软语义；
     * 改成惰性物理行 cursor 后它是逐行预算，一条都不能多。
     */
    private static final int CAP = 300;

    @Test
    @DisplayName("流式输出：一帧灌 5000 行时，写进 pty 的物理行数严格受限（此前完全无限速）")
    void streamingPath_isRateLimited(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("line ").append(i).append('\n');
        state.onAssistantToken(1L, big.toString());

        v.tickForTest();

        assertTrue(sink.lines.size() <= CAP,
                "一帧写入的物理行数应严格受限于 %d，实际 %d".formatted(CAP, sink.lines.size()));
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
        for (int i = 0; i < body.size(); i++) {
            assertEquals("line " + i, body.get(i), "顺序不能乱：第 " + i + " 行实际 " + body.get(i));
        }
    }

    @Test
    @DisplayName("pending 输出：300 条长正文折行后仍严格受物理行限速（此前放大 15 倍）")
    void pendingPath_capCountsPhysicalRows(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        String para = "x".repeat(1200);          // 80 列下折成 ~16 物理行
        for (int i = 0; i < 300; i++) state.pushInfo(para);

        v.tickForTest();

        assertTrue(sink.lines.size() <= CAP,
                "一帧写入的物理行数应严格受限于 %d，实际 %d".formatted(CAP, sink.lines.size()));
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

    // ── 单个大输出：旧「单条 OutputLine 原子」路径的漏网（strict 版新增） ──────────

    /** 反复 tick 直到没有更多输出可下沉；返回收集到的正文行（过滤缩进/空白差异）。 */
    private static List<String> drainAll(CodeTuiView v, RecordingSink sink) {
        for (int frame = 0; frame < 2000; frame++) {
            int before = sink.lines.size();
            v.tickForTest();
            if (sink.lines.size() == before) break;   // 一帧没写任何新行 ⇒ 输出存量已清空
        }
        return sink.lines.stream().map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    @Test
    @DisplayName("单个超大正文（单条 ASSISTANT，几万行）也不能突破单批硬上限")
    void singleHugeAssistantOutput_isStrictlyCapped(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 20_000; i++) huge.append("para ").append(i).append('\n');
        huge.setLength(huge.length() - 1);           // 无尾换行：末段成为残行，turn 结束前不下沉
        state.onAssistantToken(1L, huge.toString());
        state.onTurnComplete(1L);                    // flushStreaming 把全部行定稿成一条条 ASSISTANT pending

        v.tickForTest();                             // 第一批

        assertTrue(sink.lines.size() <= CAP,
                "单个大输出也必须严格受限于 %d 行/批，实际 %d".formatted(CAP, sink.lines.size()));

        List<String> body = drainAll(v, sink);
        assertEquals(20_000, body.size(), "多批之后一行不丢");
        assertEquals("para 0", body.get(0), "顺序不能乱：首行");
        assertEquals("para 19999", body.get(19_999), "顺序不能乱：末行");
    }

    @Test
    @DisplayName("单个超大 edit/write diff（单个 TOOL_START 展开折行后上千物理行）不能突破单批硬上限")
    void singleHugeDiff_isStrictlyCapped(@TempDir Path root) throws Exception {
        // 新文件 Write：整篇皆 ADD。BODY_CAP 限的是 diff 逻辑行（80），但每条 ADD 行本身 400 列宽，
        // 80 列终端下折成 ~6 物理行 ⇒ 单个 diff 块的物理行数 ≈ 80×6 ≈ 480，远超单批上限。
        // 旧实现的 TOOL_START 是原子调用：一旦开始就要把这 ~480 行全部 println 完才检查预算。
        Path file = root.resolve("Big.java");
        String longLine = "x".repeat(400);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 500; i++) content.append(longLine).append(" L").append(i).append('\n');
        content.setLength(content.length() - 1);
        String json = "{\"filePath\":" + quote(file.toString())
                + ",\"content\":" + quote(content.toString()) + "}";

        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Write", json);      // 文件不存在 → 全 ADD（行号 1..500），BODY_CAP 截到 80
        state.onTurnComplete(1L);

        v.tickForTest();                             // 第一批

        assertTrue(sink.lines.size() <= CAP,
                "单个大 diff 也必须严格受限于 %d 行/批，实际 %d".formatted(CAP, sink.lines.size()));
        assertTrue(sink.lines.size() > 0, "限速不是关闸：第一批仍应写下 diff 头部");

        List<String> body = drainAll(v, sink);
        assertTrue(body.stream().anyMatch(l -> l.contains("Write(Big.java)")),
                "diff 头应在完整输出里，实际首行：" + body.get(0));
        assertTrue(body.get(body.size() - 1).startsWith("… 还有"),
                "末行应为 BODY_CAP 截断概括，实际：" + body.get(body.size() - 1));
    }

    @Test
    @DisplayName("单条无换行超长逻辑行（几万列宽）也不能突破单批硬上限")
    void singleNoNewlineHugeLine_isStrictlyCapped(@TempDir Path root) {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        state.onAssistantToken(1L, "w".repeat(60_000));   // 一条逻辑行，60k 列宽 → 80 列下 ~858 物理行
        state.onTurnComplete(1L);                          // 定稿成单条 ASSISTANT pending

        v.tickForTest();                                   // 第一批

        assertTrue(sink.lines.size() <= CAP,
                "超长无换行逻辑行也必须严格受限于 %d 行/批，实际 %d".formatted(CAP, sink.lines.size()));

        List<String> body = drainAll(v, sink);
        assertEquals(String.join("", body), "w".repeat(60_000), "折行拼回去必须一个字符不丢");
        for (String l : body) {
            assertTrue(dev.tamboui.text.CharWidth.of(l) <= 80,
                    "每物理行宽度必须 ≤ 终端宽 80，实际 " + dev.tamboui.text.CharWidth.of(l));
        }
    }

    /** JSON 字符串字面量（最简转义：路径与换行）。 */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
