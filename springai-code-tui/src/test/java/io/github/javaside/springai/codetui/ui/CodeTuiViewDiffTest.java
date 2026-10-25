package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /diff}：git 快照不经模型、逐字打进 scrollback 的本地命令（daemon 线程采集）。
 *
 * <p>四条输出通道各有钉子：正常（INFO 头 → {@code Kind.DIFF} 逐字 → INFO 未跟踪）、
 * 干净、error，外加一条<b>端到端渲染钉子</b>——DIFF 行穿完整输出管道（pending → 输出队列 →
 * Sink）后 {@code -}/{@code #}/{@code |} 逐字存活，守护 {@code enqueueOutputLine} 的
 * {@code default -> lineCursor} 路由不被改成 markdown 游标。
 *
 * <p><b>两条并发纪律</b>：代际守卫（/clear 换代后在飞 daemon 的迟到批次弃写——对照
 * sessionEpoch 同构）与单飞（连按两次不派两个 daemon、不产出两条交错输出）。
 *
 * <p><b>完成信号先于断言</b>：daemon 异步写 {@code state.pending}，负断言若在 daemon 未跑到
 * 写点时排水必假绿——一律先轮询 {@code diffRunningForTest()} 变 false 再断言，不用裸 sleep。
 */
class CodeTuiViewDiffTest {

    /**
     * git 桩：按命令名返回预置行（{@code GitSnapshot.capture} 的三次 {@code run} 各得其所）。
     * latch 非 null 时每次调用先挂起——单飞/代际用例要 daemon 停在 capture 中途，
     * 测试线程才有机会完成「第二次按键」或「/clear 换代」再放行。
     */
    private static final class StubGit implements GitSnapshot.GitCommand {
        List<String> stat = List.of();
        List<String> body = List.of();
        List<String> untracked = List.of();
        String error;                                      // 非 null：任何命令都抛 IOException → capture 走 failed
        final CountDownLatch latch;
        final AtomicInteger calls = new AtomicInteger();   // 单飞断言：第二次 /diff 不得再产生任何 run

        StubGit(CountDownLatch latch) { this.latch = latch; }

        @Override
        public List<String> run(Path root, String... args) throws IOException {
            calls.incrementAndGet();
            if (error != null) throw new IOException(error);
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // capture 只会发 "diff HEAD --stat" / "diff HEAD" / "-c core.quotepath=false ls-files …"
            // 三种；按首词区分（diff→stat/body 按长度二分，其余（-c 开头的 ls-files）→untracked）
            return args[0].equals("diff") ? (args.length > 2 ? stat : body) : untracked;
        }
    }

    /** 照抄 ScrollbackPrinterTest 的记录型 Sink（两方法接口——不是函数式接口，不能 lambda）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(Text line) { lines.add(line.rawContent()); }
        @Override public void println(String line) { lines.add(line); }
    }

    private static void runDiff(CodeTuiView v) {
        v.setInputForTest("/diff");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    private static void runClear(CodeTuiView v) {
        v.setInputForTest("/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    /** 轮询 daemon 完成信号（SessionsPanelTest 同款 spin-wait；不用裸 sleep）。 */
    private static void awaitDiffDone(CodeTuiView v) {
        long deadline = System.currentTimeMillis() + 2000;
        while (v.diffRunningForTest() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(v.diffRunningForTest(), "2s 内 diff daemon 必须收尾（finally 复位 diffRunning 是一切断言的前提）");
    }

    private static CodeTuiView newView(ConversationState state, Path root, GitSnapshot.GitCommand git) {
        CodeTuiView v = new CodeTuiView(state, new SubmitHandler() {
            @Override public Disposable submit(String text) { return null; }
        }, root);
        v.diffGit = git;
        return v;
    }

    /** 反复 tick 直到一帧没有新行（输出存量排空，同 ScrollTailRecordingTest.drainAll）。 */
    private static void drainAll(CodeTuiView v, RecordingSink sink) {
        for (int i = 0; i < 5_000; i++) {
            int before = sink.lines.size();
            v.tickForTest();
            if (sink.lines.size() == before) {
                break;
            }
        }
    }

    @Test
    @DisplayName("正常输出：INFO(头·statLine) → 三行 DIFF 逐字 → INFO(未跟踪)")
    void ok_emitsHeaderThenVerbatimDiffThenUntracked(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("new.txt"), "0123456789");   // 恰 10 字节：capture 给未跟踪名拼 "(10 bytes)"
        StubGit git = new StubGit(null);
        git.stat = List.of("3 files changed");
        git.body = List.of("+a", "-b", "@@ -1 +1 @@");
        git.untracked = List.of("new.txt");
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();
        assertEquals(6, lines.size(), "1 头 + 3 正文 + 2 未跟踪（标题 + 条目），实际: " + lines);
        assertEquals(OutputLine.Kind.INFO, lines.get(0).kind());
        assertTrue(lines.get(0).text().contains("工作树改动"), "头行要点名工作树改动: " + lines.get(0).text());
        assertTrue(lines.get(0).text().contains("3 files changed"), "头行带 --stat 尾行: " + lines.get(0).text());
        assertEquals("+a", lines.get(1).text());
        assertEquals(OutputLine.Kind.DIFF, lines.get(1).kind());
        assertEquals("-b", lines.get(2).text());
        assertEquals(OutputLine.Kind.DIFF, lines.get(2).kind());
        assertEquals("@@ -1 +1 @@", lines.get(3).text());
        assertEquals(OutputLine.Kind.DIFF, lines.get(3).kind());
        assertEquals(OutputLine.Kind.INFO, lines.get(4).kind());
        assertTrue(lines.get(4).text().contains("未跟踪"), "未跟踪段有标题行: " + lines.get(4).text());
        assertEquals("new.txt (10 bytes)", lines.get(5).text());
        assertEquals(OutputLine.Kind.INFO, lines.get(5).kind());
    }

    /**
     * 端到端渲染路由钉子：RawDiffChannelTest 只钉了 printer 单级；这里让 /diff 的输出穿
     * <b>完整</b>管道（daemon → pending → enqueueOutputLine → 输出队列 → Sink），钉住
     * {@code default -> lineCursor} 那一级——若 DIFF 被误送 markdown 游标，{@code - 删 #x}
     * 会变列表项、{@code | a |} 会进表格攒块不输出，断言必红。
     */
    @Test
    @DisplayName("端到端渲染钉子：DIFF 行穿完整输出管道落 Sink，-/#/| 逐字存活")
    void diffLinesReachSinkVerbatim(@TempDir Path root) {
        RecordingSink sink = new RecordingSink();
        StubGit git = new StubGit(null);
        git.stat = List.of("1 file changed");
        git.body = List.of("- 删 #x", "| a |");   // markdown 语法雷区：列表项 / 标题 / 表格
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new SubmitHandler() {
            @Override public Disposable submit(String text) { return null; }
        }, root, sink);                            // 四参测试构造器：注入记录型 Sink
        v.diffGit = git;

        runDiff(v);
        awaitDiffDone(v);
        drainAll(v, sink);

        String all = String.join("\n", sink.lines);
        assertTrue(all.contains("- 删 #x"), "`-` 与 `#` 逐字存活（不许被 markdown 改写）: " + all);
        assertTrue(all.contains("| a |"), "`|` 逐字存活（不许进表格攒块）: " + all);
    }

    @Test
    @DisplayName("空改动：仅一行 INFO，明说工作树干净")
    void cleanTree_emitsSingleInfoLine(@TempDir Path root) {
        StubGit git = new StubGit(null);           // 三路全空 → ok("", [], [])
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();
        assertEquals(1, lines.size(), "干净树一行说完，实际: " + lines);
        assertEquals(OutputLine.Kind.INFO, lines.get(0).kind());
        assertTrue(lines.get(0).text().contains("干净"), "要明说干净: " + lines.get(0).text());
    }

    @Test
    @DisplayName("error 通道：一行 ERROR，自定义前缀与 git 原始原因都在")
    void gitFailure_emitsSingleErrorLine(@TempDir Path root) {
        StubGit git = new StubGit(null);
        git.error = "git 不可用";
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();
        assertEquals(1, lines.size(), "error 一行说完，实际: " + lines);
        assertEquals(OutputLine.Kind.ERROR, lines.get(0).kind());
        assertTrue(lines.get(0).text().contains("git 执行失败"), "自定义前缀要可辨（区别于裸异常文案）: " + lines.get(0).text());
        assertTrue(lines.get(0).text().contains("git 不可用"), "git 原始原因要带上: " + lines.get(0).text());
    }

    /**
     * 代际守卫的<b>负</b>断言必须有完成信号才具备杀死力：先放行 latch、轮询到 daemon 确已收尾，
     * 再排水断言无 DIFF 行。桩刻意带非空正文——若给空树，「删掉 epoch 校验」的变异也产不出
     * DIFF 行，负断言就恒绿了（对照用例 1 的正向出现）。
     */
    @Test
    @DisplayName("代际守卫：/clear 换代后，在飞 daemon 的全部输出弃写")
    void clearDuringDiff_discardsAllDaemonOutput(@TempDir Path root) {
        CountDownLatch latch = new CountDownLatch(1);
        StubGit git = new StubGit(latch);
        git.stat = List.of("1 file changed");
        git.body = List.of("+迟到的行", "-同样迟到的行");
        git.untracked = List.of();
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);            // daemon 进入桩挂起（capture 未返回）
        try {
            runClear(v);       // diffEpoch++：此后 daemon 的一切写入都属于刚被清掉的旧画面
            latch.countDown(); // 放行：daemon 完成 capture，随即发现换代而弃写
        } finally {
            latch.countDown(); // 放行幂等：断言在放行前炸也不许留挂死的 daemon
        }
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();   // 含 /clear 自己的降级 INFO——但不该有任何 DIFF 行
        assertTrue(lines.stream().noneMatch(l -> l.kind() == OutputLine.Kind.DIFF),
                "换代后不得再出现任何 DIFF 行，实际: " + lines);
        assertTrue(lines.stream().noneMatch(l -> l.text().contains("工作树改动")),
                "头行同属旧画面，也不得出现，实际: " + lines);
    }

    /**
     * 分批边界 + 批间代际校验（终审核心建议）：201 行正文恰分 200+1 两批。挂闸点不在桩里
     * （桩只能拦 capture 中途，拦不住两批之间）——挂在 {@code state} 的 UiChangeListener 上：
     * {@code pushInfo}/{@code pushRawLines} 的契约是<b>锁内入 pending、锁外同步 publish</b>，
     * 第 1 次通知必是头行 INFO、第 2 次必是首批 200 行——listener 在第 2 次调用上挂起，就把
     * daemon 精确冻结在「首批已落盘、第二批未写」之间（此刻 state 监视器已释放，测试线程可
     * drain、可 /clear）。/clear 会清 pending，故 clear 前先 drain 存快照：
     * <ul>
     *   <li>快照 = 头 + 恰 200 行（line-0…line-199）——钉死 {@code from+200} 边界：
     *       批变大则 line-200 混进快照、批变小则 line-199 缺席，都会红；</li>
     *   <li>放行后 daemon 在批间发现换代（diffEpoch++）弃写第二批——/clear 之后的新 pending
     *       不得再出现任何 DIFF 行；把批间 epoch 校验删掉，line-200 必在此露头。</li>
     * </ul>
     */
    @Test
    @DisplayName("分批边界：首批恰 200 行落盘；批间 /clear 换代则第二批弃写")
    void clearBetweenBatches_firstBatchLands_secondBatchDiscarded(@TempDir Path root) throws InterruptedException {
        StubGit git = new StubGit(null);                 // 不挂桩 latch：挂闸点在 UiChangeListener
        git.stat = List.of("42 files changed");
        List<String> body = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            body.add("line-" + i);                       // 201 行：200 一批 + 尾批 1 行
        }
        git.body = List.copyOf(body);
        git.untracked = List.of();
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        CountDownLatch firstBatchLanded = new CountDownLatch(1);
        CountDownLatch releaseDaemon = new CountDownLatch(1);
        AtomicInteger fires = new AtomicInteger();
        state.setUiChangeListener(bits -> {
            if (fires.incrementAndGet() == 2) {          // 1=头行 INFO，2=首批 200 行（挂起 daemon）
                firstBatchLanded.countDown();
                try {
                    releaseDaemon.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        runDiff(v);
        try {
            assertTrue(firstBatchLanded.await(2, TimeUnit.SECONDS),
                    "2s 内必须等到首批落盘信号（第 2 次 publish）——daemon 未启动或首批未写");
            List<OutputLine> snapshot = state.drainPending();   // /clear 前快照（clear 会清 pending）
            assertEquals(201, snapshot.size(),
                    "头 1 + 首批恰 200 行（钉 from+200 边界），实际: " + snapshot.size());
            assertEquals(OutputLine.Kind.INFO, snapshot.get(0).kind());
            assertEquals("line-0", snapshot.get(1).text(), "首批首行");
            assertEquals(OutputLine.Kind.DIFF, snapshot.get(1).kind());
            assertEquals("line-199", snapshot.get(200).text(), "首批末行是 line-199，line-200 属第二批");
            assertTrue(snapshot.stream().noneMatch(l -> l.text().contains("line-200")),
                    "边界若错位（如一批 201/整批下沉）line-200 会混进首批，实际快照尾: "
                            + snapshot.get(snapshot.size() - 1).text());

            runClear(v);        // diffEpoch++：挂着的 daemon 醒来后在批间校验处弃写第二批
        } finally {
            releaseDaemon.countDown();   // 断言在放行前炸也不许留挂死的 daemon（幂等）
        }
        awaitDiffDone(v);

        List<OutputLine> after = state.drainPending();   // /clear 之后的新 pending：只该有清屏降级提示
        assertTrue(after.stream().noneMatch(l -> l.kind() == OutputLine.Kind.DIFF),
                "批间校验若被删，line-200 会作为第二批 DIFF 行写进新 pending，实际: " + after);
        assertTrue(after.stream().noneMatch(l -> l.text().contains("line-200")),
                "第二批 line-200 必须被代际守卫弃写，实际: " + after);
    }

    /**
     * 边界补刀（与上一用例互补）：不受打断的 201 行必须整批走完。分批的「增量」与「切片上限」
     * 若错位（如增 201、切 200），尾行会在无任何报错下<b>静默丢失</b>——上一用例的 /clear 视角
     * 看不见这种丢（被弃写与被弄丢长得一样），只有不打断的全量断言能杀。
     */
    @Test
    @DisplayName("不打断的 201 行全部落盘：分批增量与切片上限不得错位丢尾行")
    void bodyOf201Lines_deliversAllWithoutInterruption(@TempDir Path root) {
        StubGit git = new StubGit(null);
        git.stat = List.of("42 files changed");
        List<String> body = new ArrayList<>();
        for (int i = 0; i <= 200; i++) {
            body.add("line-" + i);                       // 201 行：200 一批 + 尾批 1 行
        }
        git.body = List.copyOf(body);
        git.untracked = List.of();
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();
        assertEquals(202, lines.size(), "头 1 + 正文 201 行一行不丢，实际: " + lines.size());
        assertEquals("line-0", lines.get(1).text(), "首行");
        assertEquals("line-200", lines.get(201).text(), "尾行 line-200 必须到达（增量/上限错位会静默丢尾）");
        assertEquals(OutputLine.Kind.DIFF, lines.get(201).kind());
    }

    /** 头行格式钉子：statLine 空时一句说完、不悬尾「· 」；非空维持「· stat」原状。 */
    @Test
    @DisplayName("头行：statLine 空则无尾悬「· 」，非空维持「· stat」")
    void headerDropsDanglingSeparatorWhenStatLineEmpty(@TempDir Path root) {
        StubGit empty = new StubGit(null);       // stat 空列表 → capture 给 statLine ""
        empty.body = List.of("+x");
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, empty);

        runDiff(v);
        awaitDiffDone(v);

        List<OutputLine> lines = state.drainPending();
        assertEquals(2, lines.size(), "头 + 1 行正文，实际: " + lines);
        assertEquals("↺ 工作树改动（相对 HEAD）", lines.get(0).text(),
                "statLine 为空不许悬尾「· 」，实际: " + lines.get(0).text());

        StubGit filled = new StubGit(null);
        filled.stat = List.of("3 files changed");
        filled.body = List.of("+x");
        ConversationState state2 = new ConversationState();
        CodeTuiView v2 = newView(state2, root, filled);

        runDiff(v2);
        awaitDiffDone(v2);

        assertEquals("↺ 工作树改动（相对 HEAD）· 3 files changed",
                state2.drainPending().get(0).text(), "statLine 非空维持「· stat」原状");
    }

    /**
     * 单飞：第一次 /diff 在命令分支（按键线程上）同步置位 {@code diffRunning}，第二次按键
     * 确定性撞闸——测试线程串行按键，无竞态。calls==3 钉死「没派第二个 daemon」：
     * 闸门删掉的话第二次会再跑一次 capture（+3 条 git run）。
     */
    @Test
    @DisplayName("单飞：在飞期间第二次 /diff 只给 notice，不派第二个 daemon")
    void secondDiffWhileRunning_isRejectedWithNotice(@TempDir Path root) {
        CountDownLatch latch = new CountDownLatch(1);
        StubGit git = new StubGit(latch);
        git.stat = List.of("1 file changed");
        git.body = List.of("+x");
        ConversationState state = new ConversationState();
        CodeTuiView v = newView(state, root, git);

        runDiff(v);                    // 第一次：diffRunning 同步置位后 daemon 挂在桩里
        try {
            runDiff(v);                // 第二次：必须撞单飞闸
            assertTrue(state.notice().contains("正在生成"),
                    "单飞拒绝要说出来，实际 notice: " + state.notice());
        } finally {
            latch.countDown();         // 无论断言成败都放行第一个 daemon
        }
        awaitDiffDone(v);
        assertEquals(3, git.calls.get(),
                "整个用例只允许一次 capture（恰 3 条 git 命令）——第二个 daemon 若被派起会变 6");
    }
}
