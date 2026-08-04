package io.github.javaside.springai.codetui.ui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tasks} 后台任务面板：列出全部后台任务、Enter 展开结果、{@code k} 终止运行中任务（须确认）、
 * Esc 关闭；以及生命周期——{@code /clear} 与退出时终止全部后台任务。
 *
 * <p>断言一律落在 {@link ViewScreen} 回读的<b>屏幕文本</b>上而非内部字段：面板类缺陷多是「内容构造得出来
 * 但被前面某个分支挡掉了」，只测构造函数是典型的不会失败的测试（同 {@code CodeTuiViewPermissionsPanelTest}）。
 */
class CodeTuiViewTasksPanelTest {

    /** 可编程桩：记录 kill / killAll / shutdown 调用。 */
    private static final class TasksStub implements SubmitHandler {
        final List<String> killed = new ArrayList<>();
        final AtomicInteger killAlls = new AtomicInteger();
        final AtomicInteger shutdowns = new AtomicInteger();
        boolean killResult = true;

        @Override public Disposable submit(String text) { return null; }
        @Override public boolean killBackgroundTask(String taskId) {
            killed.add(taskId);
            return killResult;
        }
        @Override public void killAllBackgroundTasks() { killAlls.incrementAndGet(); }
        @Override public void shutdownBackground() { shutdowns.incrementAndGet(); }
    }

    private static void openPanel(CodeTuiView v) {
        v.setInputForTest("/tasks");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    private static void submitCommand(CodeTuiView v, String cmd) {
        v.setInputForTest(cmd);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    /** 一个运行中 + 一个成功 + 一个失败，覆盖面板要显示的三种状态。 */
    private static ConversationState stateWithThreeTasks() {
        ConversationState s = new ConversationState();
        s.onBackgroundTaskStarted("task_run01", "explore", "调研缓存方案");
        s.onBackgroundTaskStarted("task_done1", "general", "跑一遍测试");
        s.onBackgroundTaskFinished("task_done1", "测试全绿：42 个用例通过", true);
        s.onBackgroundTaskStarted("task_fail1", "explore", "抓取文档");
        s.onBackgroundTaskFinished("task_fail1", "连接超时", false);
        return s;
    }

    @Test
    @DisplayName("/tasks 列出全部后台任务——运行中、已完成、已失败都在")
    void listsAllTasksIncludingFinished(@TempDir Path root) {
        ConversationState s = stateWithThreeTasks();
        CodeTuiView v = new CodeTuiView(s, new TasksStub(), root);

        openPanel(v);

        assertTrue(v.pickingTasksForTest(), "面板应已打开");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("task_run01"), "运行中的要在，实际：\n" + screen);
        assertTrue(screen.contains("task_done1"), "已完成的也要在（这正是面板的价值），实际：\n" + screen);
        assertTrue(screen.contains("task_fail1"), "已失败的也要在，实际：\n" + screen);
        assertTrue(screen.contains("调研缓存方案"), "应显示描述，实际：\n" + screen);
        assertTrue(screen.contains("explore"), "应显示 agent 名，实际：\n" + screen);
    }

    @Test
    @DisplayName("回合进行中也能开——终止后台任务不会撞在飞的工具调用（与 /permissions 一致，不像 /mcp 要求空闲）")
    void opensWhileTurnInProgress(@TempDir Path root) {
        ConversationState s = stateWithThreeTasks();
        s.onTurnStarted(1);
        assertTrue(s.isBusy(), "前置：回合进行中");
        CodeTuiView v = new CodeTuiView(s, new TasksStub(), root);

        openPanel(v);

        assertTrue(v.pickingTasksForTest(), "忙碌中也应能打开面板");
        assertEquals("", s.notice(), "不该给出「忙碌中」之类的拒绝提示");
        assertTrue(ViewScreen.of(v).contains("task_run01"));
    }

    @Test
    @DisplayName("↑↓ 移动选中项")
    void arrowKeysMoveSelection(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);
        openPanel(v);

        assertEquals("task_run01", selectedRowId(v), "默认应选中第一条");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals("task_done1", selectedRowId(v), "↓ 应移到第二条");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        assertEquals("task_run01", selectedRowId(v), "↑ 应移回第一条");
    }

    @Test
    @DisplayName("★ 选中高亮必须是纯前景色——带底色的高亮条在本 TUI 下会串到下一项")
    void selectionHighlightUsesNoBackgroundColor(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);
        openPanel(v);

        Buffer buf = ViewScreen.bufferOf(v);
        int y = rowIndexOf(buf, "❯");
        assertTrue(y >= 0, "应能找到选中行，实际：\n" + ViewScreen.of(v));
        for (int x = 0; x < buf.width(); x++) {
            Cell c = buf.get(x, y);
            assertTrue(c.style().bg().isEmpty(),
                    "选中行第 " + x + " 格带了底色（" + c.style().bg() + "）——底色会串到下一项");
        }
    }

    @Test
    @DisplayName("Enter 展开选中任务的结果，再按收起")
    void enterExpandsResultAndCollapsesAgain(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));       // 移到已完成那条

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        String expanded = ViewScreen.of(v);
        assertTrue(expanded.contains("测试全绿：42 个用例通过"),
                "展开后应看得到结果正文，实际：\n" + expanded);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        String collapsed = ViewScreen.of(v);
        assertFalse(collapsed.contains("测试全绿：42 个用例通过"),
                "再按一次应收起，实际：\n" + collapsed);
        assertTrue(collapsed.contains("task_done1"), "收起的只是结果，列表要留着");
    }

    @Test
    @DisplayName("多行结果按物理行拆开显示——一个 Element = 一行，整块塞进去会被塌成一行")
    void multiLineResultIsSplitIntoRows(@TempDir Path root) {
        ConversationState s = new ConversationState();
        s.onBackgroundTaskStarted("task_multi", "explore", "调研");
        s.onBackgroundTaskFinished("task_multi", "第一行结论\n第二行细节\n第三行建议", true);
        CodeTuiView v = new CodeTuiView(s, new TasksStub(), root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        Buffer buf = ViewScreen.bufferOf(v);
        int y1 = rowIndexOf(buf, "第一行结论");
        int y2 = rowIndexOf(buf, "第二行细节");
        int y3 = rowIndexOf(buf, "第三行建议");
        assertTrue(y1 >= 0 && y2 >= 0 && y3 >= 0, "三行都该看得见，实际：\n" + ViewScreen.of(v));
        assertTrue(y1 < y2 && y2 < y3, "必须落在三个不同物理行上，实际 y=" + y1 + "," + y2 + "," + y3);
    }

    @Test
    @DisplayName("k 对运行中的任务先弹确认——确认前不得调 killBackgroundTask")
    void killOnRunningTaskAsksForConfirmationFirst(@TempDir Path root) {
        TasksStub h = new TasksStub();
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), h, root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofChar('k'));

        assertEquals(List.of(), h.killed, "按 k 不该已经终止任何东西");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("确认终止"), "应出现确认行，实际：\n" + screen);
        assertTrue(screen.contains("Enter 确认"), "应说明怎么确认，实际：\n" + screen);
    }

    @Test
    @DisplayName("确认之后才真的终止，且面板上该任务变为已终止")
    void confirmingActuallyKills(@TempDir Path root) {
        TasksStub h = new TasksStub();
        ConversationState s = stateWithThreeTasks();
        CodeTuiView v = new CodeTuiView(s, h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('k'));

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of("task_run01"), h.killed, "应恰好终止高亮那条");
        assertEquals(0, s.backgroundRunningCount(), "终止后不该再算作运行中");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("已终止"), "面板上要看得出它被终止了，实际：\n" + screen);
    }

    @Test
    @DisplayName("★ 终止后即使那条线程后来跑完了，也不得把状态翻回「已完成」")
    void lateCompletionDoesNotUndoKill(@TempDir Path root) {
        TasksStub h = new TasksStub();
        ConversationState s = stateWithThreeTasks();
        CodeTuiView v = new CodeTuiView(s, h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('k'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        // 注册表的 kill 只改状态、不打断线程；那条线程跑完仍会发完成事件（见 SubagentRunner.runBackgroundBody）。
        s.onBackgroundTaskFinished("task_run01", "迟到的结果", true);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));   // 清掉 notice，免得它回显干扰断言
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("已终止"), "被终止的任务不该翻回已完成，实际：\n" + screen);
        assertEquals(0, s.backgroundRunningCount());
    }

    @Test
    @DisplayName("★ k 对已结束的任务无效——不弹确认，也不调 killBackgroundTask")
    void killOnFinishedTaskDoesNothing(@TempDir Path root) {
        TasksStub h = new TasksStub();
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));   // 移到已完成那条

        v.feedKeyForTest(KeyEvent.ofChar('k'));

        assertEquals(List.of(), h.killed, "已结束的任务不该被终止");
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("确认终止"), "更不该弹确认，实际：\n" + screen);
    }

    @Test
    @DisplayName("确认行按 Esc 取消：什么都不终止，回到列表")
    void escOnConfirmationCancels(@TempDir Path root) {
        TasksStub h = new TasksStub();
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('k'));

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertEquals(List.of(), h.killed, "取消后不得有任何终止");
        assertTrue(v.pickingTasksForTest(), "取消确认只回到列表，不该顺手关掉面板");
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("确认终止"), "确认行应已收起，实际：\n" + screen);
        assertTrue(screen.contains("task_run01"), "应回到列表，实际：\n" + screen);
    }

    @Test
    @DisplayName("终止失败要说出来——面板状态行早于通用 notice 分支 return，必须自己回显")
    void killFailureIsVisibleInStatusLine(@TempDir Path root) {
        TasksStub h = new TasksStub();
        h.killResult = false;
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), h, root);
        openPanel(v);
        v.feedKeyForTest(KeyEvent.ofChar('k'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(v.tasksStatusTextForTest().contains("终止失败"),
                "失败必须说出来，实际：" + v.tasksStatusTextForTest());
        assertTrue(ViewScreen.of(v).contains("终止失败"), "屏幕上也要看得见");
    }

    @Test
    @DisplayName("Esc 关闭面板")
    void escClosesPanel(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);
        openPanel(v);

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertFalse(v.pickingTasksForTest());
        assertFalse(ViewScreen.of(v).contains("k 终止"), "面板应已收起");
    }

    @Test
    @DisplayName("面板底部说明结果怎么交给模型")
    void panelExplainsAutoDelivery(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);

        openPanel(v);

        assertTrue(ViewScreen.of(v).contains("清空输入框后自动交给模型"),
                "必须说清结果怎么进模型，实际：\n" + ViewScreen.of(v));
    }

    @Test
    @DisplayName("零任务时也能打开，给出空态说明而不是空白面板；空列表下按键不得崩")
    void opensWithNoTasks(@TempDir Path root) {
        TasksStub h = new TasksStub();
        CodeTuiView v = new CodeTuiView(new ConversationState(), h, root);

        openPanel(v);

        assertTrue(v.pickingTasksForTest());
        assertTrue(ViewScreen.of(v).contains("（暂无后台任务）"),
                "应给出空态说明，实际：\n" + ViewScreen.of(v));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));      // 空列表下不得 % 0 除零
        v.feedKeyForTest(KeyEvent.ofChar('k'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(List.of(), h.killed);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingTasksForTest(), "Esc 仍应能关闭");
    }

    @Test
    @DisplayName("面板打开时收起常驻 ⏱ 面板——同一份列表并排两遍纯属噪音")
    void residentPanelIsHiddenWhileTasksPanelOpen(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), new TasksStub(), root);
        assertTrue(ViewScreen.of(v).contains("⏱ 后台任务 ("), "前置：常驻面板在");

        openPanel(v);

        assertFalse(ViewScreen.of(v).contains("⏱ 后台任务 ("),
                "面板打开时常驻面板应收起，实际：\n" + ViewScreen.of(v));
    }

    @Test
    @DisplayName("非面板态渲染冒烟——scope 每帧 eager 求值，首行不判空会每帧崩渲染线程")
    void rendersWithoutPanelOpen(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new TasksStub(), root);

        String screen = ViewScreen.of(v);            // 从未打开过面板

        assertFalse(v.pickingTasksForTest());
        assertFalse(screen.contains("k 终止"), "没开面板就不该有面板内容，实际：\n" + screen);
    }

    @Test
    @DisplayName("/tasks 出现在斜杠命令补全菜单里")
    void tasksIsInSlashMenu(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new TasksStub(), root);

        v.setInputForTest("/task");

        assertTrue(ViewScreen.of(v).contains("/tasks"), "补全菜单应列出 /tasks，实际：\n" + ViewScreen.of(v));
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ /clear 终止全部后台任务——否则清完屏任务还在跑，而界面上已看不见")
    void clearKillsAllBackgroundTasks(@TempDir Path root) {
        TasksStub h = new TasksStub();
        ConversationState s = stateWithThreeTasks();
        CodeTuiView v = new CodeTuiView(s, h, root);

        submitCommand(v, "/clear");

        assertEquals(1, h.killAlls.get(), "/clear 应终止全部后台任务");
        assertTrue(s.backgroundTasks().isEmpty(), "⏱ 面板也应清空");
    }

    @Test
    @DisplayName("/clear 被忙碌拒绝时不得终止后台任务——没换会话就不该顺手杀任务")
    void rejectedClearDoesNotKill(@TempDir Path root) {
        TasksStub h = new TasksStub();
        ConversationState s = stateWithThreeTasks();
        s.onTurnStarted(1);                         // 忙碌：/clear 会被拒
        CodeTuiView v = new CodeTuiView(s, h, root);

        submitCommand(v, "/clear");

        assertEquals("忙碌中，无法清空", s.notice(), "前置：确实被拒了");
        assertEquals(0, h.killAlls.get(), "被拒的 /clear 不得终止任何后台任务");
    }

    @Test
    @DisplayName("★ 退出前终止全部后台任务")
    void exitKillsAllBackgroundTasks(@TempDir Path root) {
        TasksStub h = new TasksStub();
        CodeTuiView v = new CodeTuiView(stateWithThreeTasks(), h, root);

        try {
            submitCommand(v, "/exit");
        } catch (RuntimeException ignored) {
            // 测试态 runner()==null，quit() 可能抛——终止必须发生在 quit() 之前，故不影响本断言
        }

        assertEquals(1, h.killAlls.get(), "退出前应终止全部后台任务");
        // 退出是<b>终态</b>，与 /clear 不同：/clear 之后还要能继续派后台任务（故那条路重建线程池），
        // 退出则该真正关掉池、走有界 2s 的收尾窗口。两者共用一个方法的话，要么 /clear 之后
        // 后台模式永久失效，要么退出时那 2s 收尾窗口悄悄消失——README 写着「清理有界 2s」。
        assertEquals(1, h.shutdowns.get(), "退出应关闭后台线程池（有界收尾），而不只是终止任务");
    }

    // ── 断言小工具 ──────────────────────────────────────────────────────

    /** 屏幕上被 {@code ❯} 标中那一行里的 taskId（找不到返回空串）。 */
    private static String selectedRowId(CodeTuiView v) {
        for (String line : ViewScreen.of(v).split("\n")) {
            if (!line.contains("❯")) continue;
            int i = line.indexOf("task_");
            if (i < 0) continue;
            int end = i;
            while (end < line.length() && line.charAt(end) != ' ') end++;
            return line.substring(i, end);
        }
        return "";
    }

    /** 含指定子串的首个物理行号；没有则 -1。 */
    private static int rowIndexOf(Buffer buf, String needle) {
        String[] lines = renderLines(buf);
        for (int y = 0; y < lines.length; y++) {
            if (lines[y].contains(needle)) return y;
        }
        return -1;
    }

    private static String[] renderLines(Buffer buf) {
        String[] out = new String[buf.height()];
        for (int y = 0; y < buf.height(); y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < buf.width(); x++) {
                Cell c = buf.get(x, y);
                if (c.isContinuation()) continue;
                sb.append(c.symbol().isEmpty() ? " " : c.symbol());
            }
            out[y] = sb.toString();
        }
        return out;
    }
}
