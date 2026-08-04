package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /continue} 把后台任务摘要拼进提示词。
 *
 * <p><b>钉的是重复劳动</b>：Esc 掐掉前台回合后，后台那批照跑，而 todo 上它们仍是「进行中」。
 * 不告诉模型的话，/continue 会让它把正在跑的活再派一遍。
 *
 * <p><b>两条出口都要测</b>：/continue 有 enqueue（忙时排队）与 dispatch（直接发）两条路，
 * 只测一条会漏掉另一条用的是未拼接的旧提示词。
 */
class CodeTuiViewContinueDigestTest {

    /** 记录最后一次真正提交/入队的文本。 */
    private static final class Handler implements SubmitHandler {
        final AtomicReference<String> submitted = new AtomicReference<>();
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        String digest = "";

        @Override public Disposable submit(String text) { submitted.set(text); return () -> {}; }
        @Override public Disposable submit(String text, String skill) { return submit(text); }
        @Override public boolean hasInFlightSubagents() { return inFlight.get(); }
        @Override public String backgroundDigestForContinue() { return digest; }
    }

    private static void runContinue(CodeTuiView v) {
        v.setInputForTest("/continue");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("digest 非空：提示词同时含原文与摘要，谁也没盖掉谁")
    void digestIsAppendedToPrompt(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "当前进程仍有后台任务：\n  task_x1  explore  扫目录  运行中 → 正在做，不要重复委派";
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        String sent = h.submitted.get();
        assertTrue(sent.contains("继续执行上一批未完成的计划"), "原提示词不能被覆盖:\n" + sent);
        assertTrue(sent.contains("task_x1"), "摘要必须拼进去:\n" + sent);
        assertTrue(sent.contains("不要重复委派"), "行动指引必须拼进去:\n" + sent);
    }

    /**
     * 杀掉「无条件拼接」这个变异：digest 为空时若仍拼一段，提示词尾部会多出分隔符。
     * 用 endsWith 而不是整串相等——后者会在原提示词有任何合理改动时误报。
     */
    @Test
    @DisplayName("digest 为空：提示词尾部不得多出任何东西")
    void emptyDigestLeavesPromptUntouched(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "";                       // 注册表非空但只有 FAILED/KILLED 时就是这个值
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        String sent = h.submitted.get();
        assertTrue(sent.endsWith("若没有未完成的计划，直接说明即可。"),
                "空 digest 不该在尾部追加任何东西（哪怕只是换行）:\n" + sent);
        assertFalse(sent.contains("后台任务"), "空 digest 不该凭空提到后台:\n" + sent);
    }

    /**
     * /continue 的另一条出口。忙时它走 state.enqueue，若只在 dispatch 那条路上做了拼接，
     * 排队的就是没有摘要的旧提示词——而「忙」恰恰是后台任务最可能在跑的时候。
     */
    @Test
    @DisplayName("忙时排队：入队的也必须是拼好摘要的那份")
    void queuedPromptAlsoCarriesDigest(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Handler h = new Handler();
        h.digest = "当前进程仍有后台任务：\n  task_q9  bash  跑回归  运行中 → 正在做，不要重复委派";
        h.inFlight.set(true);                // busy() 为真 ⇒ 走 enqueue
        CodeTuiView v = new CodeTuiView(state, h, root);

        runContinue(v);

        assertEquals(1, state.queuedCount(), "前提：忙时确实入队了");
        String queued = state.queuedSnapshot().get(0);
        assertTrue(queued.contains("task_q9"), "入队的提示词也必须带摘要:\n" + queued);
        assertTrue(queued.contains("继续执行上一批未完成的计划"), "原文也要在:\n" + queued);
    }

    @Test
    @DisplayName("SubmitHandler 的默认实现返回空串——回显桩/测试桩可省略")
    void defaultDigestIsEmpty() {
        SubmitHandler stub = text -> null;
        assertEquals("", stub.backgroundDigestForContinue());
    }
}
