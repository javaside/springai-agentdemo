package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /init} 与 {@code /review}：两条<b>提示词命令</b>（命令本身不干活，把固定中文提示词交给
 * agent 用既有工具自己干，/continue 同构）。
 *
 * <p><b>两条出口都要测</b>：命令有 enqueue（忙时排队）与 dispatch（直接发）两条路，与
 * ContinueDigestTest 同理——只测一条会漏掉另一条上提示词被误改/截断。
 *
 * <p>关键句逐个断言是<b>杀提示词变异</b>的钉子：少一个关键词，说明全文被误改或截断，
 * 而提示词命令的全部价值就在那段文本里。
 */
class CodeTuiViewInitReviewTest {

    /** 记录最后一次真正提交的文本（忙路不入这个桩，断言走 state.queuedSnapshot）。 */
    private static final class Handler implements SubmitHandler {
        final AtomicReference<String> submitted = new AtomicReference<>();

        @Override public Disposable submit(String text) { submitted.set(text); return () -> {}; }
        @Override public Disposable submit(String text, String skill) { return submit(text); }
    }

    private static void runCommand(CodeTuiView v, String cmd) {
        v.setInputForTest(cmd);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    /** 真实 git 用例的公共准备（先例 GitSnapshotTest.initRepo）：@TempDir 里 init + 配 user。 */
    private static void initRepo(Path dir) throws IOException, InterruptedException {
        runOk(dir, "git", "init", "-q");
        runOk(dir, "git", "config", "user.email", "t@t");
        runOk(dir, "git", "config", "user.name", "t");
    }

    private static void runOk(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd));
        }
    }

    @Test
    @DisplayName("/init 空闲：固定提示词 dispatch，四个关键句一个不少")
    void initIdleDispatchesFixedPrompt(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(state, h, root);

        runCommand(v, "/init");

        String sent = h.submitted.get();
        assertTrue(sent != null && !sent.isBlank(), "空闲时必须 dispatch 提示词，实际: " + sent);
        assertTrue(sent.contains("AGENTS.md"), "目标文件名必须在提示词里:\n" + sent);
        assertTrue(sent.contains("项目级"), "必须钉死写项目级而非用户级:\n" + sent);
        assertTrue(sent.contains("重启"), "必须告知重启后生效:\n" + sent);
        assertTrue(sent.contains("改进"), "已存在时是改进不是重写:\n" + sent);
        assertFalse(sent.contains("/init"), "命令名本身不该混进提示词:\n" + sent);
    }

    /**
     * 忙路出口：走 {@code state.enqueue} 而非 dispatch。若只测闲路，忙路拼错/漏拼提示词无人知晓。
     */
    @Test
    @DisplayName("/init 忙碌：入队而非 dispatch，队列里是同一份提示词")
    void initBusyQueuesInsteadOfDispatch(@TempDir Path root) {
        ConversationState state = new ConversationState();
        state.onTurnStarted(1L);              // 回合进行中 ⇒ busy()
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(state, h, root);

        runCommand(v, "/init");

        assertNull(h.submitted.get(), "忙时不许直接 dispatch");
        assertEquals(1, state.queuedCount(), "前提：忙时确实入队了");
        String queued = state.queuedSnapshot().get(0);
        assertTrue(queued.contains("AGENTS.md"), "入队的也必须是同一份提示词:\n" + queued);
        assertTrue(queued.contains("项目级"), "入队提示词钉死项目级:\n" + queued);
        assertTrue(queued.contains("重启"), "入队提示词含重启告知:\n" + queued);
        assertTrue(queued.contains("改进"), "入队提示词含改进语义:\n" + queued);
    }

    @Test
    @DisplayName("/review 空闲（git 仓库）：dispatch 只读审查提示词，六个关键句一个不少")
    void reviewIdleDispatchesReadonlyPrompt(@TempDir Path root) throws Exception {
        initRepo(root);                       // /review 有非 git 预检：先垫一个真仓库
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(state, h, root);

        runCommand(v, "/review");

        String sent = h.submitted.get();
        assertTrue(sent != null && !sent.isBlank(), "git 仓库内空闲时必须 dispatch 提示词，实际: " + sent);
        assertTrue(sent.contains("只读"), "只读边界必须在提示词里:\n" + sent);
        assertTrue(sent.contains("正确性"), "审查维度·正确性:\n" + sent);
        assertTrue(sent.contains("安全"), "审查维度·安全:\n" + sent);
        assertTrue(sent.contains("测试"), "审查维度·测试:\n" + sent);
        assertTrue(sent.contains("可维护性"), "审查维度·可维护性:\n" + sent);
        // 计划关键词表写的是「严重度」，但逐字提示词原文是「严重程度」（前者非后者子串，断言原文）
        assertTrue(sent.contains("严重程度"), "按严重程度排序的要求必须在:\n" + sent);
        assertFalse(sent.contains("/review"), "命令名本身不该混进提示词:\n" + sent);
    }

    /**
     * 非 git 目录 UI 预检拒绝：一行 notice 换来的是不白烧一整回合——agent 拿到提示词后
     * 跑 git diff 只会报错，来回一趟什么也审不了。
     */
    @Test
    @DisplayName("/review 非 git 目录：不 dispatch 不入队，notice 说明原因")
    void reviewRejectsNonGitDir(@TempDir Path root) {
        // 假设声明：@TempDir 须不在任何 git 工作树内（rev-parse 向上搜索父目录；JUnit 默认系统 tmpdir 满足，先例 GitSnapshotTest）
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(state, h, root);

        runCommand(v, "/review");

        assertNull(h.submitted.get(), "非 git 目录不许 dispatch");
        assertEquals(0, state.queuedCount(), "非 git 目录也不许入队（排队同样烧回合）");
        assertTrue(state.notice().contains("git"), "notice 要说明不是 git 仓库，实际: " + state.notice());
    }

    /**
     * 等价路径回填断言（historySnapshotForTest 钩子不存在）：命令跑完后按 ↑，
     * 输入框应回填命令原文——addHistory 对斜杠命令与普通消息统一生效，丢了它
     * 长命令（/review）就得每次重敲。
     */
    @Test
    @DisplayName("命令进 ↑ 历史：跑完后 UP 回填输入框")
    void commandsEnterInputHistory(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(state, h, root);

        runCommand(v, "/init");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));

        assertEquals("/init", v.inputTextForTest(), "斜杠命令也要进 ↑ 历史");
    }
}
