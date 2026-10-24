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
            // capture 只会发 "diff HEAD --stat" / "diff HEAD" / "ls-files …" 三种；按首词+长度区分
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
