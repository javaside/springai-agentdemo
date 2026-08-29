package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import io.github.javaside.springai.codetui.agent.background.TaskResultStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台任务结果的<b>自动送达</b>（drain 里起新回合）+ ⏱ 面板 + 状态栏后缀。
 *
 * <p><b>为什么断言落在 drain 与渲染结果上</b>：判定本身（{@code BackgroundNotifier}）已有纯函数
 * 单测，这里钉的是「UI 有没有把答案落实成一次提交」——最容易出错的恰是接缝：忙时抢跑、
 * 送达后忘记标记消费导致同一结果反复送、提交失败却把结果丢了、后台送达插到用户排队消息前面。
 * 面板与状态栏则必须真渲染进 Buffer（见 {@link ViewScreen}），只测构造函数证不了它出现在屏幕上。
 */
class CodeTuiViewBackgroundTest {

    /**
     * SubmitHandler 桩：记录提交文本，并模拟注册表的「完成未消费」列表与消费闸
     * （{@code markBackgroundConsumed} 后该条不再出现在 {@code completedBackgroundTasks}）。
     */
    private static final class Handler implements SubmitHandler {
        final List<String> submits = new ArrayList<>();
        final List<BackgroundResult> pending = new ArrayList<>();
        final List<String> consumed = new ArrayList<>();
        PermissionMode mode = PermissionMode.DEFAULT;
        RuntimeException failNext;      // 非 null = 下一次 submit 抛这个异常（模拟网关抖动）

        @Override public Disposable submit(String text) {
            if (failNext != null) { RuntimeException e = failNext; failNext = null; throw e; }
            submits.add(text);
            return () -> { };
        }
        @Override public List<BackgroundResult> completedBackgroundTasks() { return List.copyOf(pending); }
        @Override public boolean markBackgroundConsumed(String taskId) {
            consumed.add(taskId);
            return pending.removeIf(r -> r.taskId().equals(taskId));
        }
        @Override public PermissionMode permissionMode() { return mode; }
    }

    private static SubmitHandler.BackgroundResult done(String id, String desc, String result) {
        return new SubmitHandler.BackgroundResult(id, "explore", desc, result, true);
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
    }

    /** 屏幕上包含 needle 的那一行（不存在则返回空串），用于断言「后缀挂在哪一行、行首是什么」。 */
    private static String lineWith(String screen, String needle) {
        for (String line : screen.split("\n")) {
            if (line.contains(needle)) return line;
        }
        return "";
    }

    // ── 自动送达 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("空闲 + 输入框为空 + 有已完成任务 → 起一个新回合，正文含任务结果")
    void idleAndEmptyInput_deliversResult(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        h.pending.add(done("ab12", "调查登录失败", "登录失败是因为 token 过期"));

        v.tickForTest();

        assertEquals(1, h.submits.size(), "空闲且输入框为空时应自动起一个回合:\n" + h.submits);
        assertTrue(h.submits.get(0).contains("登录失败是因为 token 过期"), "正文必须带上任务结果全文");
        assertTrue(h.submits.get(0).contains("ab12"), "正文应带任务 id，模型才能对上是哪一个");
    }

    @Test
    @DisplayName("输入框有内容 → 不抢跑（用户正在敲字）")
    void nonEmptyInput_doesNotDeliver(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        h.pending.add(done("ab12", "调查登录失败", "结论"));

        type(v, "我正在打字");
        v.tickForTest();

        assertEquals(0, h.submits.size(), "用户正在敲字时抢跑会把他的输入挤到下一轮");
        assertTrue(h.consumed.isEmpty(), "没送达就不能标记消费");
    }

    @Test
    @DisplayName("回合进行中 → 不送达")
    void busy_doesNotDeliver(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        s.onTurnStarted(1);
        h.pending.add(done("ab12", "调查登录失败", "结论"));

        v.tickForTest();

        assertEquals(0, h.submits.size(), "回合进行中起第二个回合 = 两回合并发写同一会话");
        assertTrue(h.consumed.isEmpty());
    }

    @Test
    @DisplayName("送达后标记已消费，下一帧不再重复送同一条")
    void delivered_marksConsumed_andDoesNotRepeat(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        h.pending.add(done("ab12", "调查登录失败", "结论"));

        v.tickForTest();
        assertEquals(List.of("ab12"), h.consumed, "送达后必须标记消费");

        v.tickForTest();
        v.tickForTest();
        assertEquals(1, h.submits.size(), "已消费的结果不能每 33ms 再送一遍");
    }

    @Test
    @DisplayName("submit 抛异常时不标记已消费，下一帧重试")
    void submitFailure_keepsResultUnconsumed(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        h.pending.add(done("ab12", "调查登录失败", "结论"));
        h.failNext = new IllegalStateException("网关抖动");

        assertDoesNotThrow(v::tickForTest, "送达失败不能把异常抛回渲染线程，否则整个 drain 循环停摆");
        assertTrue(h.consumed.isEmpty(), "一次提交失败不该把结果丢掉");
        assertEquals(1, h.pending.size(), "结果仍应留在未消费列表里");

        v.tickForTest();
        assertEquals(1, h.submits.size(), "下一帧应重试并送达");
        assertEquals(List.of("ab12"), h.consumed);
    }

    @Test
    @DisplayName("连续 3 次自动送达后刹车，第 4 次不再起回合")
    void brakeStopsRunawayAutoTurns(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);

        for (int i = 1; i <= 3; i++) {
            h.pending.add(done("id" + i, "任务" + i, "结论" + i));
            v.tickForTest();
        }
        assertEquals(3, h.submits.size(), "前提：连续送达三次");

        h.pending.add(done("id4", "任务4", "结论4"));
        v.tickForTest();
        assertEquals(3, h.submits.size(), "自动回合可能又派后台任务 → 又自动起回合，必须有刹车");
        assertTrue(h.consumed.stream().noneMatch("id4"::equals), "没送达就不能标记消费");

        assertTrue(ViewScreen.of(v).contains("回车交给模型"),
                "刹车踩下后必须在状态栏告诉用户怎么手动放行，否则结果像是被吃了:\n" + ViewScreen.of(v));
    }

    @Test
    @DisplayName("用户真实提交一条消息后刹车重置")
    void realUserInputResetsBrake(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        for (int i = 1; i <= 3; i++) {
            h.pending.add(done("id" + i, "任务" + i, "结论" + i));
            v.tickForTest();
        }
        assertEquals(3, h.submits.size(), "前提：刹车已踩下");

        type(v, "继续");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(4, h.submits.size(), "前提：用户那条消息已提交");

        h.pending.add(done("id4", "任务4", "结论4"));
        v.tickForTest();
        assertEquals(5, h.submits.size(), "用户回来了：刹车必须重置，否则后台结果永远送不出去");
        assertTrue(h.submits.get(4).contains("结论4"));
    }

    @Test
    @DisplayName("刹车踩下后：空输入框按回车必须真的把结果交出去")
    void enterOnEmptyInputReleasesBrake(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        for (int i = 1; i <= 3; i++) {
            h.pending.add(done("id" + i, "任务" + i, "结论" + i));
            v.tickForTest();
        }
        h.pending.add(done("id4", "任务4", "结论4"));
        v.tickForTest();
        assertEquals(3, h.submits.size(), "前提：刹车已把第 4 条扣住");
        assertTrue(ViewScreen.of(v).contains("回车交给模型"), "前提：状态栏正在让用户按回车");

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 输入框为空，正是状态栏教的那一下
        v.tickForTest();

        // 屏幕上写着「回车交给模型」，而空输入框按回车却是空操作 —— 用户照做后什么都不发生，
        // 结果再也送不出去，且没有任何地方告诉他"其实得打一条真消息"。
        assertEquals(4, h.submits.size(), "状态栏写着回车交给模型，那句提示就必须是真的:\n" + h.submits);
        assertTrue(h.submits.get(3).contains("结论4"), "放行后送的应当是那条被扣住的结果");
    }

    @Test
    @DisplayName("刹车踩下但结果已全部送完 → 状态栏不常驻「有结果待处理」")
    void brakeWithNothingPending_showsNoStaleHint(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        for (int i = 1; i <= 3; i++) {
            h.pending.add(done("id" + i, "任务" + i, "结论" + i));
            v.tickForTest();
        }
        assertEquals(3, h.submits.size(), "前提：三次都送到了，没有任何结果被扣住");

        v.tickForTest();

        // 三条都已送达并消费，这时候提示「有结果待处理」是在指使用户去处理一个不存在的东西。
        assertFalse(ViewScreen.of(v).contains("有结果待处理"),
                "结果已全部送完，不该常驻待处理提示:\n" + ViewScreen.of(v));
    }

    // ── 落盘（限幅在 CodingAgent.completedBackgroundTasks 里做）不得跟着渲染帧跑 ────────────

    /**
     * 照抄 {@code CodingAgent.completedBackgroundTasks()} 的调用位置：取列表<b>顺手做限幅落盘</b>。
     * 用它钉住「闸门关着时一帧一帧地重写结果文件」——drain 每 33ms 一次，一份 200KB 的报告
     * 就是 6MB/s 的同步写、还跑在渲染线程上；模型此刻若去 Read 那个 artifact 还会读到中间态。
     */
    private static final class StoringHandler implements SubmitHandler {
        final List<String> submits = new ArrayList<>();
        final List<BackgroundResult> pending = new ArrayList<>();
        private final TaskResultStore store;
        int fetches;                       // completedBackgroundTasks() 被调了几次（= 限幅落盘跑了几次）

        StoringHandler(Path root) { this.store = new TaskResultStore(root); }

        @Override public Disposable submit(String text) { submits.add(text); return () -> { }; }
        @Override public List<BackgroundResult> completedBackgroundTasks() {
            fetches++;
            List<BackgroundResult> out = new ArrayList<>();
            for (BackgroundResult r : pending) {
                out.add(new BackgroundResult(r.taskId(), r.agentName(), r.description(),
                        store.storeAndTruncate(r.taskId(), r.result()), r.ok()));
            }
            return out;
        }
        @Override public boolean markBackgroundConsumed(String taskId) {
            return pending.removeIf(r -> r.taskId().equals(taskId));
        }
    }

    /** 超限幅（>4000 字符）的结果：取一次列表就落一次盘。 */
    private static SubmitHandler.BackgroundResult huge(String id) {
        return new SubmitHandler.BackgroundResult(id, "explore", "一份大报告", "x".repeat(5000), true);
    }

    @Test
    @DisplayName("用户正在打字 → 不取结果列表，也就不会每帧重写落盘文件")
    void typingGate_doesNotFetchOrWriteArtifact(@TempDir Path root) {
        ConversationState s = new ConversationState();
        StoringHandler h = new StoringHandler(root);
        CodeTuiView v = new CodeTuiView(s, h, root);
        h.pending.add(huge("ab12"));

        type(v, "我正在打字");
        for (int i = 0; i < 5; i++) v.tickForTest();

        assertEquals(0, h.fetches, "闸门关着就不该取列表——取列表顺手就把结果限幅落盘了");
        assertFalse(Files.exists(root.resolve(".codetui").resolve("artifacts").resolve("task-ab12.txt")),
                "这一帧根本不会送达，不该有任何落盘");
    }

    @Test
    @DisplayName("刹车踩下 → 结果列表最多再取一次，不随渲染帧反复落盘")
    void brakeGate_stopsPerFrameFetching(@TempDir Path root) {
        ConversationState s = new ConversationState();
        StoringHandler h = new StoringHandler(root);
        CodeTuiView v = new CodeTuiView(s, h, root);
        for (int i = 1; i <= 3; i++) {
            h.pending.add(done("id" + i, "任务" + i, "结论" + i));
            v.tickForTest();
        }
        assertEquals(3, h.submits.size(), "前提：刹车已踩下");

        h.pending.add(huge("ab12"));
        int before = h.fetches;
        for (int i = 0; i < 10; i++) v.tickForTest();

        // 刹车 + 有大结果被扣住 = 永久闸门。每帧取一次列表就是每 33ms 重写一次文件。
        assertTrue(h.fetches - before <= 1,
                "刹车期间只需探明一次「确有结果被扣住」，之后每帧再取只是白白重写落盘文件，实际取了 "
                        + (h.fetches - before) + " 次");
        assertTrue(ViewScreen.of(v).contains("有结果待处理"), "探明之后状态栏仍要告诉用户结果被扣住了");
    }

    @Test
    @DisplayName("排队的用户消息优先于后台送达")
    void queuedUserMessageWinsOverBackgroundDelivery(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        s.enqueue("用户排队的消息", null);
        h.pending.add(done("ab12", "调查登录失败", "结论"));

        v.tickForTest();

        // 用户明确排的队比程序自作主张的送达优先；反过来会让用户的消息莫名其妙晚一轮。
        assertEquals(List.of("用户排队的消息"), h.submits, "本帧应出队用户消息，而不是送后台结果");
        assertTrue(h.consumed.isEmpty(), "后台结果本帧仍未消费");

        v.tickForTest();
        assertEquals(2, h.submits.size(), "下一帧才轮到后台结果");
        assertTrue(h.submits.get(1).contains("结论"));
    }

    // ── ⏱ 面板 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("⏱ 面板：计数标题 + 每条任务（状态图标 / id / agent / 描述 / 耗时）")
    void backgroundPanelRendersTasks(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, root);
        s.onBackgroundTaskStarted("ab12", "explore", "调查登录失败");
        s.onBackgroundTaskStarted("cd34", "bash", "跑回归测试");
        s.onBackgroundTaskFinished("cd34", "全部通过", true);

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("⏱ 后台任务 (1 运行 · 1 完成)"), "标题应给出运行/完成计数:\n" + screen);
        assertTrue(lineWith(screen, "ab12").contains("▶"), "运行中用 ▶:\n" + screen);
        assertTrue(lineWith(screen, "ab12").contains("explore"), "要能看出是哪个 agent 在跑");
        assertTrue(lineWith(screen, "ab12").contains("调查登录失败"), "要能看出它在干什么");
        assertTrue(lineWith(screen, "cd34").contains("✓"), "已完成用 ✓:\n" + screen);
        // 耗时列：后台任务跨回合存活，「跑了多久」是判断它是否卡死的唯一线索。
        assertTrue(lineWith(screen, "ab12").matches(".*\\d+m\\d{2}s.*"), "运行行必须带耗时:\n" + screen);
    }

    @Test
    @DisplayName("⏱ 面板行数有上限：超出的折叠掉并注明还有几项")
    void backgroundPanelIsCapped(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, new Handler(), root);
        // ⏱ 列表跨回合累积、只有 /clear 清，已完成的永不移除：一个会话派九个后台任务，
        // 面板就常驻十行，把输入框一路顶下去。
        for (int i = 1; i <= 9; i++) s.onBackgroundTaskStarted("bg0" + i, "explore", "任务" + i);

        String screen = ViewScreen.of(v);
        long rows = screen.lines().filter(l -> l.contains("bg0")).count();
        assertTrue(rows <= 6, "面板必须封顶，实际渲染了 " + rows + " 行:\n" + screen);
        assertTrue(screen.contains("bg09"), "最新的任务必须可见（折叠靠前的已完成条）:\n" + screen);
        assertFalse(screen.contains("bg01"), "超出上限的靠前任务应被折叠:\n" + screen);
        assertTrue(screen.contains("前 3 项已折叠"), "折叠掉几项要如实注明:\n" + screen);
    }

    @Test
    @DisplayName("零后台任务时面板不占行，且 scope 的 eager 求值不炸")
    void noBackgroundTasks_panelAbsent(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, new Handler(), root);

        // TamboUI 的 scope(cond, children) 每帧 eager 求值：cond 为 false 时 children 照样被构造一次。
        String screen = assertDoesNotThrow(() -> ViewScreen.of(v), "零任务时构造面板不能 NPE/越界");
        assertFalse(screen.contains("后台任务"), "没有后台任务就不该占一行:\n" + screen);
    }

    @Test
    @DisplayName("耗时渲染：分秒两段、秒补零、时钟回拨兜成 0m00s")
    void elapsedTextFormat() {
        assertEquals("0m00s", CodeTuiView.elapsedText(0));
        assertEquals("0m18s", CodeTuiView.elapsedText(18_000));
        assertEquals("1m42s", CodeTuiView.elapsedText(102_000));
        assertEquals("2m03s", CodeTuiView.elapsedText(123_000), "秒必须补零，否则 2m3s 和 2m30s 一眼看错");
        assertEquals("0m00s", CodeTuiView.elapsedText(-5_000), "时钟回拨/NTP 校时不该渲染出负数");
    }

    // ── 状态栏后缀 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("状态栏：空闲且有后台任务在跑时含 ⏱ 与 /tasks，权限模式标识仍在行首")
    void statusLineSuffixKeepsModeTagFirst(@TempDir Path root) {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        h.mode = PermissionMode.ACCEPT_EDITS;
        CodeTuiView v = new CodeTuiView(s, h, root);
        s.onBackgroundTaskStarted("ab12", "explore", "调查登录失败");
        s.onBackgroundTaskStarted("cd34", "bash", "跑回归测试");

        String screen = ViewScreen.of(v);
        String status = lineWith(screen, "/tasks");
        assertTrue(status.contains("⏱ 2 个后台任务"), "空闲时要能看出还有几个后台任务在跑:\n" + screen);
        // 状态行接近终端宽度、尾部先被截断，而「现在会不会问你」比「有几个后台任务」更不该被截掉。
        assertTrue(status.stripLeading().startsWith("⏵⏵"), "后缀不能挤掉行首的权限模式标识:\n" + status);
    }

    @Test
    @DisplayName("状态栏：没有后台任务时不加后缀")
    void statusLineHasNoSuffixWithoutBackgroundTasks(@TempDir Path root) {
        ConversationState s = new ConversationState();
        CodeTuiView v = new CodeTuiView(s, new Handler(), root);

        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("/tasks"), "常态状态行不该被无关提示挤占:\n" + screen);
    }
}
