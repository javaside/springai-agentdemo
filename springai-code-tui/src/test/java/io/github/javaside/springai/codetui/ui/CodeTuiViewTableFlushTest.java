package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
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

    /**
     * 把一张表当作流式 token 灌进去。<b>末行不带换行</b>：表格是回复的最后一块内容、后面没有空行，
     * 于是块结束只能靠显式 flush 触发点——带上尾部空行的话状态机自己就把块排出来了，
     * 「flush cursor 条件化」这类变异会被那条空行掩盖。
     */
    private static void streamTable(ConversationState s, long turnId) {
        s.onAssistantToken(turnId, String.join("\n", TABLE));
    }

    /**
     * 让整张表<b>先落进渲染器缓冲</b>：末行带换行 → 本批被 takeCompleteStreamingLines 取走喂进
     * 渲染器，块因「后面还没有非表格行」停在 IN_BLOCK 态。用于测那些<b>不</b> flushStreaming 的
     * 生产者事件（TOOL_OK / SUBAGENT_END）：它们的行进 pending 时缓冲已非空，
     * 于是「该 kind 在不在 flush 集合里」是唯一变量。
     */
    private static void bufferTableIntoRenderer(ConversationState s, CodeTuiView v, Recording sink) {
        s.onAssistantToken(1L, String.join("\n", TABLE) + "\n");
        v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(v.printerForTest().hasBufferedTable(), "前置：整块已在缓冲里");
        assertFalse(hasTableSeparator(sink.lines), "前置：此刻还没排出来");
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
        CodeTuiView v = view(s, root, sink);
        // ⚠ 先建视图再灌表格：表格行与工具行落在<b>同一批</b> pending，flush cursor 入队时刻
        // 前面那些 ASSISTANT 行还没喂给渲染器、hasBufferedTable() 必为 false——
        // 写成 if (hasBufferedTable()) 就在这个时序上整条失效。
        streamTable(s, 1L);
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
        CodeTuiView v = view(s, root, sink);
        streamTable(s, 1L);
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
        CodeTuiView v = view(s, root, sink);
        bufferTableIntoRenderer(s, v, sink);
        s.onToolFinished(1L, "Read", "ok", true);   // ⚠ 与 onToolStarted 不同，它<b>不</b> flushStreaming

        drainBatches(v);
        int sepAt = indexOfSeparator(sink.lines);
        assertTrue(sepAt >= 0, "TOOL_OK 前必须 flush：" + sink.lines);
        assertTrue(sepAt < indexOfContaining(sink.lines, "Read ✓"),
                "表格必须排在工具结果行之前：" + sink.lines);

        ConversationState s2 = new ConversationState();
        Recording sink2 = new Recording();
        s2.onTurnStarted(1L);
        CodeTuiView v2 = view(s2, root, sink2);
        bufferTableIntoRenderer(s2, v2, sink2);
        s2.onSubagentFinished(1L, "t1", "子任务结论");

        drainBatches(v2);
        int sepAt2 = indexOfSeparator(sink2.lines);
        assertTrue(sepAt2 >= 0, "SUBAGENT_END 前必须 flush：" + sink2.lines);
        assertTrue(sepAt2 < indexOfContaining(sink2.lines, "子任务结论"),
                "表格必须排在子 agent 结论行之前：" + sink2.lines);
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

    @Test
    @DisplayName("模态期间：输入还没排空就不许 flush（钉「输入已排空」那一半闸门）")
    void modalWithHalfFedTableDoesNotFlushWhileInputRemains(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        CodeTuiView v = view(s, root, sink);

        // ① 先把表头 + 分隔行喂进渲染器（一批走完，块进 IN_BLOCK 态）
        s.onAssistantToken(1L, TABLE.get(0) + "\n" + TABLE.get(1) + "\n");
        v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(v.printerForTest().hasBufferedTable(), "前置：表头 + 分隔行已在缓冲里");

        // ② 剩下两条数据行留在 streaming 里（已成整行、但本批取不到），并弹一个模态让 hasModal() 为真
        s.onAssistantToken(1L, TABLE.get(2) + "\n" + TABLE.get(3) + "\n");
        s.onPermissionRequested(1L, new PermissionRequest(1L, null, "Write", "a.txt",
                "{}", "写文件需要确认", null, outcome -> { }));
        // ③ 再塞一条 INFO（豁免、不 flush）占住队列：本批取流式行的闸门是「队列已排空」，
        //    于是这一批只打 INFO，剩下两条数据行留到下一批 —— 批尾「缓冲非空 + 输入未排空」成立
        s.pushInfo("⚙ 使用模型 X");

        UiUpdateCoordinator.UpdateResult r = v.processUpdatesForTest(UiDirty.ALL);

        assertTrue(s.hasModal(), "前置：模态已弹出（闸门前件成立）");
        assertTrue(v.printerForTest().hasBufferedTable(), "前置：缓冲仍非空");
        assertTrue(r.outputRemaining(), "两条数据行还没成为输出——输入未排空，此刻不许 flush");
        assertFalse(hasTableSeparator(sink.lines),
                "少了「输入已排空」这一半，这里就会排出「只看过两行算出列宽的半张表」，"
                        + "剩下两行随后按原样落下：" + sink.lines);

        // 后续批把输入排空 → 整张表一次落地，且只有一条分隔线
        drainBatches(v);
        assertEquals(1, sink.lines.stream().filter(CodeTuiViewTableFlushTest::isTableSeparator).count(),
                "输入排空后整张表一次排出：" + tail(sink.lines, 10));
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

    @Test
    @DisplayName("长计划 + 秒批：正文跨批还没打完时模态就没了，只剩第 5 条能救这张表")
    void longPlanAnsweredMidBodyStillFlushesTable(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        CodeTuiView v = view(s, root, sink);

        // 正文 > 单批 300 行预算：第一批打不完，批尾「输入已排空」不成立 → 第 4 条不介入
        StringBuilder body = new StringBuilder("计划正文：\n\n");
        for (int i = 0; i < 400; i++) {
            body.append("步骤 ").append(i).append("\n");
        }
        body.append(String.join("\n", TABLE));
        PlanRequest plan = new PlanRequest(1L, body.toString(), (outcome, feedback) -> { });
        s.onPlanSubmitted(1L, plan);

        UiUpdateCoordinator.UpdateResult first = v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(first.outputRemaining(), "前置：第一批没打完，剩余行留在队列里");

        // 用户在正文打完前就批准 → 模态消失，而回合仍在跑（非 IDLE）：
        // 此后每一批的第 4 条前件 (isIdle() || hasModal()) 恒为假，兜底再也不会介入
        s.removeModal(plan);
        assertFalse(s.hasModal() || s.isIdle(), "前置：模态没了、回合还在跑——第 4 条已出局");

        drainBatches(v);

        assertFalse(v.printerForTest().hasBufferedTable(),
                "第 5 条是这条时序上唯一的 flush 点：去掉它，表格就永远压在缓冲里等下一个偶然的触发点");
        assertTrue(hasTableSeparator(sink.lines), "表格应已落地：" + tail(sink.lines, 8));
    }

    @Test
    @DisplayName("Esc 取消：半张表也要排空（cancelCurrent 把残行定稿 + 置 IDLE）")
    void escapeCancelDrainsBufferedTable(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Recording sink = new Recording();
        s.onTurnStarted(1L);
        CodeTuiView v = view(s, root, sink);
        streamTable(s, 1L);   // 末行还是在建残行
        v.processUpdatesForTest(UiDirty.ALL);
        assertTrue(v.printerForTest().hasBufferedTable(), "前置：表格压在缓冲里（THINKING 态第 4 条不介入）");
        assertFalse(hasTableSeparator(sink.lines), "前置：此刻还没排出来");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));   // cancelCurrent：flushStreaming + IDLE

        drainBatches(v);

        assertFalse(v.printerForTest().hasBufferedTable(),
                "取消不能把已收到的表格连同回合一起吞掉——用户看到的会是「回复到一半凭空少了一段」");
        assertEquals(1, sink.lines.stream().filter(CodeTuiViewTableFlushTest::isTableSeparator).count(),
                "整张表一次排出：" + tail(sink.lines, 10));
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
