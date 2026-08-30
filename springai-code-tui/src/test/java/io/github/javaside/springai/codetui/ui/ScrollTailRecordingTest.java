package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>fix round（审查 I-2）</b>：{@code scrollTail} 留底必须存<b>折行前</b>的原始逻辑行
 * （与 Task 5 之前的实现同语义），resize 变宽重放时按新宽度重新折行——续段必须回流合并。
 *
 * <p>严格分批重构曾把留底换成「折行后的物理段」且报告声称语义保留（不实）：留底条目从逻辑行
 * 缩成物理段后，①变宽重放永远不能回流（段已切死）；②400 条覆盖从「N 条逻辑行」缩水成
 * 「N 个物理段」（长正文几行就占满一屏配额）。本测试从视图外部读留底（反射，生产字段无 accessor）
 * 钉住留底语义：条数按逻辑行计、内容无损、按新宽度重放能回流合并。
 */
class ScrollTailRecordingTest {

    private static final int TAIL_CAP = 400;   // 与 CodeTuiView.SCROLL_TAIL_CAP 对齐

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

    /** 反射读 scrollTail 的各条纯文本（测试态专用；生产只在渲染线程读写，无 accessor）。 */
    private static List<String> tailPlain(CodeTuiView v) throws Exception {
        Field f = CodeTuiView.class.getDeclaredField("scrollTail");
        f.setAccessible(true);
        Object deque = f.get(v);
        Object[] arr = (Object[]) deque.getClass().getMethod("toArray").invoke(deque);
        List<String> plain = new ArrayList<>(arr.length);
        for (Object o : arr) {
            plain.add(o instanceof Text t ? t.rawContent() : String.valueOf(o));
        }
        return plain;
    }

    /** 反复 tick 直到一帧没有新行（输出存量排空）。 */
    private static void drainAll(CodeTuiView v, RecordingSink sink) {
        for (int i = 0; i < 5_000; i++) {
            int before = sink.lines.size();
            v.tickForTest();
            if (sink.lines.size() == before) break;
        }
    }

    /** 按 {@code replayWidth} 逐条重放留底纯文本（等价 CodeTuiView.replayAfterResize 的折行部分）。 */
    private static List<String> replayAt(List<String> tailPlain, int replayWidth) {
        List<String> out = new ArrayList<>();
        for (String raw : tailPlain) {
            String rest = raw;
            if (rest.isEmpty()) { out.add(""); continue; }
            while (!rest.isEmpty()) {
                String seg = CharWidth.substringByWidth(rest, replayWidth);
                if (seg.isEmpty()) seg = rest.substring(0, 1);
                out.add(seg);
                rest = rest.substring(seg.length());
            }
        }
        return out;
    }

    @Test
    @DisplayName("留底存折行前原文：变宽重放后分段回流合并成更长的物理行")
    void scrollTailKeepsPreWrapContent_andReflowsOnWiderReplay(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        // 一条无换行超长 INFO（60k 列）→ 80 列下 ~750 个物理段，远超一批
        state.onTurnStarted(1L);
        String longLine = "tail " + "t".repeat(60_000);
        state.pushInfo(longLine);
        state.onTurnComplete(1L);

        drainAll(v, sink);
        List<String> tail = tailPlain(v);

        // ① 留底条数必须远小于物理段数（~750）：存的是折行前原文，一整条逻辑行占一条留底
        assertTrue(tail.size() >= 1 && tail.size() <= 3,
                "留底应存折行前的逻辑行原文（1 条 INFO + 少量回合边界行，实际 " + tail.size() + " 条）"
                        + "——若存折后物理段则会有 ~750 条，这正是 I-2 要消除的语义缩水");
        // ② 留底里必须有完整原文（内容无损）
        String tailJoined = String.join("\n", tail);
        assertTrue(tailJoined.contains("tail ") && tailJoined.contains("t".repeat(100)),
                "留底必须包含原始逻辑行内容（内容无损）");

        // ③ 变宽重放回流：存原文时，重放宽度 ≥ 原文宽度 ⇒ 一条逻辑行回流成 1 个物理行。
        //    （若存的是折后段，段已切死，任何宽度都至少 751 行——这正是语义差。）留底里
        //    除本逻辑行外还有 ≤2 条回合边界空行，回流断言只看长行本身：按 100_000 列重放后，
        //    必须存在一个与原文一字不差、占满整行的物理行。
        List<String> replayed = replayAt(tail, 100_000);
        assertEquals(1, replayed.stream().filter(s -> s.equals(longLine)).count(),
                "变宽重放必须把整条逻辑行回流成一个物理行（存的是原文才能做到），实际重放 "
                        + replayed.size() + " 行");
        // ④ 常规变宽（80 → 5000）也应按新宽度重折而非按旧段重排：60005 列 → 13 段
        //   （若存折后段则至少 751 行——每段自成一物理行、永不合并）。
        List<String> replayed5k = replayAt(tail, 5000);
        long longSegs = replayed5k.stream().filter(s -> !s.isEmpty()).count();
        assertTrue(longSegs <= 13, "80→5000 重放应把长行重折成 ≤13 段（60005/5000 向上取整），实际 "
                + longSegs + " ——存折后段的行为是恒 751 段");
    }

    @Test
    @DisplayName("留底按逻辑行覆盖：N 条短 INFO 占 N 条留底，而不是被折行段稀释")
    void scrollTailCapacityCountsLogicalLines(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        state.onTurnStarted(1L);
        int n = 40;
        for (int i = 0; i < n; i++) state.pushInfo("short " + i);
        state.onTurnComplete(1L);

        drainAll(v, sink);

        List<String> body = tailPlain(v).stream().filter(s -> s.startsWith("short ")).toList();
        assertEquals(n, body.size(), TAIL_CAP + " 条留底配额应覆盖 N 条逻辑行（旧语义）；若被折行段稀释则覆盖缩水");
        assertEquals("short 0", body.get(0), "顺序不能乱");
        assertEquals("short " + (n - 1), body.get(n - 1), "顺序不能乱");
    }

    @Test
    @DisplayName("留底里的 Text 行同样存折行前原文（样式保真，宽度信息无损）")
    void scrollTailStyledLinesKeepPreWrapText(@TempDir Path root) throws Exception {
        ConversationState state = new ConversationState();
        RecordingSink sink = new RecordingSink();
        CodeTuiView v = view(state, root, sink);

        // ASSISTANT 走 Text 路径；60k 无换行 → ~858 物理段
        state.onTurnStarted(1L);
        String longAssistant = "a".repeat(60_000);
        state.onAssistantToken(1L, longAssistant);
        state.onTurnComplete(1L);

        drainAll(v, sink);

        List<String> tail = tailPlain(v);
        // 单条逻辑行 → 留底里该以极少条 Text 记录原文（≤3 条），且拼回去 == 原文（去缩进）
        assertTrue(tail.size() <= 3, "单条逻辑行的留底应 ≤ 3 条（原文一条），实际 " + tail.size());
        String joined = String.join("", tail).replace("  ", "");
        assertEquals(longAssistant, joined, "留底 Text 原文拼回去必须一字不差（去缩进后）");

        // 变宽重放回流：5000 列下这条 60k 逻辑行（含缩进 60002 列）应重折成 13 段，而非 858 段
        //（存折后段的行为：段已切死，任何宽度都 ≥858 行、永不合并）。
        List<String> replayed = replayAt(tail, 5000);
        assertTrue(replayed.size() <= 16, "60k 原文按 5000 列重放应得 ~13 段（858 是存折后段的行为），实际 "
                + replayed.size());
        assertEquals(longAssistant, String.join("", replayed).replace(" ", ""), "重放拼回去不丢字");

        // 回流上限：重放宽度 ≥ 原文宽度 ⇒ 整条逻辑行回流成一个物理行
        List<String> wide = replayAt(tail, 100_000);
        assertEquals(1, wide.stream().filter(s -> s.replace(" ", "").equals(longAssistant)).count(),
                "重放到 100_000 列时长行必须回流成单个物理行（存原文才能做到），实际 " + wide.size() + " 行");
    }
}
