package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import io.github.javaside.springai.codetui.ui.update.UiUpdateCoordinator;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格 flush 触发点的视图级接线（设计 §3.4 五条触发点 + 一条豁免，§5.4 测试策略）。
 *
 * <p>本设计的失效模式是<b>静默丢内容 / 顺序错乱</b>，不是崩溃，所以每条触发点都要有用例：
 * 漏一条的后果是「表格永不显示」或「⚠ 出错排在它要解释的那张表之前」。
 *
 * <p>不变量是<b>条件式</b>的（写成永真式就是假绿）：
 * {@code !(isIdle() || hasModal()) || !hasBufferedTable() || outputRemaining}。
 * 回合中途缓冲长期非空是设计的一部分——模型吐完表头 + 分隔行就停下思考时，那一批
 * 队列/pending/流式全空而缓冲非空、status 是 THINKING，属正常态，活性由动画帧续批保证。
 */
class CodeTuiViewTableFlushTest {

    private static final List<String> TABLE = List.of(
            "| 参数 | 说明 |",
            "|------|------|",
            "| a | 短 |",
            "| bb | 更长一些的说明 |");

    private static SubmitHandler noopHandler() {
        return new SubmitHandler() {
            @Override public Disposable submit(String text) { return () -> { }; }
        };
    }

    /** 记录型 sink：收下每一条真正落到 scrollback 的物理行。 */
    private static final class Recording implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line)   { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static CodeTuiView view(ConversationState s, Path root, Recording sink) {
        CodeTuiView v = new CodeTuiView(s, noopHandler(), root, sink);
        v.startForTest();
        return v;
    }

    /** 把一张表当作流式 token 灌进去（每行以 \n 结尾即定稿）。 */
    private static void streamTable(ConversationState s, long turnId) {
        s.onAssistantToken(turnId, String.join("\n", TABLE) + "\n");
    }

    /** 排空：反复跑批直到不再声明 outputRemaining（有上限防死循环）。 */
    private static UiUpdateCoordinator.UpdateResult drainBatches(CodeTuiView v) {
        UiUpdateCoordinator.UpdateResult r = v.processUpdatesForTest(UiDirty.ALL);
        for (int i = 0; i < 50 && r.outputRemaining(); i++) {
            r = v.processUpdatesForTest(UiDirty.OUTPUT);
        }
        return r;
    }

    private static boolean isAligned(String line) {
        return !line.contains("|");   // 重排后不含原文竖线
    }

    // ── 第 4 条：回合结束兜底 ──────────────────────────────────────────────

    @Test
    @DisplayName("回合以表格结尾 + IDLE：表格在回合结束的批里自己落地，缓冲排空")
    void turnEndingWithTable_flushesWithoutFurtherInput(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        streamTable(s, 1L);
        CodeTuiView v = view(s, root, sink);
        s.onTurnComplete(1L);   // flushStreaming + 置 IDLE，此刻表格尾行才进缓冲

        drainBatches(v);

        assertFalse(v.printerForTest().hasBufferedTable(), "回合已结束、输入已排空 → 缓冲必须被排空");
        assertTrue(sink.lines.stream().anyMatch(l -> l.contains("参数") && isAligned(l)),
                "表格必须已重排落地，实际：" + sink.lines);
        assertTrue(hasTableSeparator(sink.lines), "应有分隔线");
    }

    @Test
    @DisplayName("不变量：回合中途缓冲非空是正常态，但此时必须仍在续批（活性）")
    void midTurnBufferedTableKeepsBatchesComing(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "| 参数 | 说明 |\n|------|------|\n");   // 只到分隔行就停下思考
        CodeTuiView v = view(s, root, sink);

        UiUpdateCoordinator.UpdateResult r = drainBatches(v);

        assertTrue(v.printerForTest().hasBufferedTable(), "块未结束，缓冲非空是设计的一部分");
        assertFalse(s.isIdle(), "此刻仍在回合中");
        // 条件式不变量：非 IDLE 且无模态 → 前件不成立，不要求缓冲为空；活性由动画帧保证
        assertTrue(r.animationActive(), "忙态必须接通动画帧，缓冲才有机会被后续批带出");
    }

    @Test
    @DisplayName("跨批不劈表：一大批 pending 里夹一张表，跨批消费后仍只出一张对齐表")
    void tableSpanningBatchesIsNotSplit(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            body.append("正文第 ").append(i).append(" 行\n");
        }
        body.append(String.join("\n", TABLE)).append('\n');
        s.onAssistantToken(1L, body.toString());
        CodeTuiView v = view(s, root, sink);
        s.onTurnComplete(1L);

        drainBatches(v);

        assertFalse(v.printerForTest().hasBufferedTable());
        // 只能有一条分隔线：两条 = 表被劈成「半张对齐 + 半张对齐」
        assertEquals(1, sink.lines.stream().filter(CodeTuiViewTableFlushTest::isTableSeparator).count(),
                "整张表必须一次排出，不许劈成两半：" + tail(sink.lines, 12));
        assertTrue(sink.lines.stream().noneMatch(l -> l.contains("| bb |")),
                "不许出现原样的表格行（半张原样）：" + tail(sink.lines, 12));
    }

    // ── 第 2 条：模型流水线上的行入队前 ────────────────────────────────────

    @Test
    @DisplayName("TOOL_START 插在表格中间：表格先落地、工具行后出（同一批）")
    void toolStartFlushesTableFirst(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        streamTable(s, 1L);
        CodeTuiView v = view(s, root, sink);
        s.onToolStarted(1L, "Read", "{\"path\":\"a.txt\"}");   // 内部会先 flushStreaming

        drainBatches(v);

        int sepAt = indexOfSeparator(sink.lines);
        int toolAt = indexOfContaining(sink.lines, "Read");
        assertTrue(sepAt >= 0, "表格应已落地：" + sink.lines);
        assertTrue(toolAt >= 0, "工具行应已落地：" + sink.lines);
        assertTrue(sepAt < toolAt, "表格必须排在工具行之前，实际 sep=%d tool=%d：%s"
                .formatted(sepAt, toolAt, sink.lines));
    }

    @Test
    @DisplayName("ERROR 不在豁免里：「⚠ 出错」必须排在它要解释的那张表之后")
    void errorFlushesTableFirst(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        streamTable(s, 1L);
        CodeTuiView v = view(s, root, sink);
        s.onError(1L, new IllegalStateException("boom"));

        drainBatches(v);

        int sepAt = indexOfSeparator(sink.lines);
        int errAt = indexOfContaining(sink.lines, "出错");
        assertTrue(sepAt >= 0 && errAt >= 0, "表格与错误行都应落地：" + sink.lines);
        assertTrue(sepAt < errAt, "ERROR 放进豁免的后果就是这一条会反过来：" + sink.lines);
    }

    @Test
    @DisplayName("TOOL_OK / SUBAGENT_END 也 flush（default 分支不能一律不 flush）")
    void toolOkAndSubagentEndAlsoFlush(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        streamTable(s, 1L);
        CodeTuiView v = view(s, root, sink);
        s.onToolFinished(1L, "Read", "ok", true);

        drainBatches(v);
        assertTrue(indexOfSeparator(sink.lines) >= 0, "TOOL_OK 前必须 flush：" + sink.lines);

        ConversationState s2 = new ConversationState();
        Recording sink2 = new Recording();
        s2.onTurnStarted(1L);
        streamTable(s2, 1L);
        CodeTuiView v2 = view(s2, root, sink2);
        s2.onSubagentFinished(1L, "t1", "子任务结论");

        drainBatches(v2);
        assertTrue(indexOfSeparator(sink2.lines) >= 0, "SUBAGENT_END 前必须 flush：" + sink2.lines);
    }

    @Test
    @DisplayName("豁免：INFO 通知行越过缓冲里的表格，表格继续攒")
    void infoDoesNotFlushTable(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "| 参数 | 说明 |\n|------|------|\n| a | 短 |\n");
        CodeTuiView v = view(s, root, sink);
        s.pushInfo("⚙ 使用模型 X");

        drainBatches(v);

        assertTrue(v.printerForTest().hasBufferedTable(),
                "INFO 是 UI 异步注入的，位置本来就不确定——在这里 flush 会拼出「半张对齐 + 半张原样」");
        assertTrue(indexOfContaining(sink.lines, "使用模型 X") >= 0, "通知行照常打");
        assertFalse(hasTableSeparator(sink.lines), "表格不该被 INFO 顶出来");
    }

    @Test
    @DisplayName("新回合的 USER 行：上一回合末尾的表格不被 userBlockCursor 的 md.reset() 顺手丢掉")
    void userLineFlushesPreviousTurnTable(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        s.onAssistantToken(1L, "| 参数 | 说明 |\n|------|------|\n| a | 短 |\n");
        CodeTuiView v = view(s, root, sink);
        v.processUpdatesForTest(UiDirty.ALL);   // 三条行喂进渲染器，整块压在缓冲里
        assertTrue(v.printerForTest().hasBufferedTable(), "前置条件：块未结束，压在缓冲里");

        // 不经 onTurnComplete（不让第 4 条兜底介入），直接开下一回合
        s.onTurnStarted(2L);
        s.onUserMessage(2L, "下一个问题");
        drainBatches(v);

        int sepAt = indexOfSeparator(sink.lines);
        int userAt = indexOfContaining(sink.lines, "下一个问题");
        assertTrue(sepAt >= 0, "上一回合的表格不能被 md.reset() 静默丢掉（reset 在 drain 时刻执行，"
                + "flush 必须排在 USER 行前面）：" + sink.lines);
        assertTrue(userAt >= 0, "USER 行应已落地：" + sink.lines);
        assertTrue(sepAt < userAt, "表格必须排在 USER 行之前：" + sink.lines);
    }

    // ── 第 5 条：整篇 ASSISTANT 文档灌完 ───────────────────────────────────

    @Test
    @DisplayName("计划正文以表格结尾：批准之前表格已经在屏幕上")
    void planBodyEndingWithTableIsFlushedBeforeApproval(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        CodeTuiView v = view(s, root, sink);

        List<PlanOutcome> answered = new ArrayList<>();
        s.onPlanSubmitted(1L, new PlanRequest(1L, "计划正文：\n\n" + String.join("\n", TABLE),
                (outcome, feedback) -> answered.add(outcome)));

        drainBatches(v);

        assertFalse(v.printerForTest().hasBufferedTable(),
                "printPlan 绕过 enqueueOutputLine，收尾不 flush 的话用户要在看不见表的情况下批准计划");
        assertTrue(hasTableSeparator(sink.lines), "表格应已落地：" + sink.lines);
    }

    // ── 第 6 条：/clear ───────────────────────────────────────────────────

    @Test
    @DisplayName("/clear 丢缓冲且状态机回空闲（测试态 runner 为空也要成立）")
    void clearDropsBufferedTable(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        CodeTuiView v = view(s, root, sink);

        // 攒一张未结束的表进缓冲
        drain(v.printerForTest().streamingLinesCursor(List.of("| k | v |", "|---|---|", "| a | b |")));
        assertTrue(v.printerForTest().hasBufferedTable(), "前置条件：缓冲里压着表格");

        v.setInputForTest("/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertFalse(v.printerForTest().hasBufferedTable(), "/clear 必须同步丢缓冲——"
                + "塞进 runOnRenderThread lambda 的话测试态（runner 为空）走不到");

        // 状态机也要回空闲：只丢缓冲不复位，降级态会活过清屏
        drain(v.printerForTest().streamingLinesCursor(List.of("| k | v |", "|---|---|", "| a | b |")));
        assertTrue(v.printerForTest().hasBufferedTable(), "清屏后新表格仍能被识别");
    }

    private static void drain(io.github.javaside.springai.codetui.ui.output.OutputCursor c) {
        while (c.next() != null) {
            // 只为推进状态机
        }
    }

    /** 表格分隔线：缩进 2 空格 + 全是 ─。欢迎横幅的圆角边框也含 ─，不能只用 contains 判。 */
    private static boolean isTableSeparator(String line) {
        return line.startsWith("  ─") && line.strip().chars().allMatch(c -> c == '─');
    }

    private static boolean hasTableSeparator(List<String> lines) {
        return lines.stream().anyMatch(CodeTuiViewTableFlushTest::isTableSeparator);
    }

    private static int indexOfSeparator(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (isTableSeparator(lines.get(i))) return i;
        }
        return -1;
    }

    private static int indexOfContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return -1;
    }

    private static List<String> tail(List<String> lines, int n) {
        return lines.subList(Math.max(0, lines.size() - n), lines.size());
    }
}
