package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忙时 Enter 的路由：默认走插话，三条回落走老排队队列。
 *
 * <p>判据是 {@code !state.isIdle()} 而非 {@code busy()}——后者还含「压缩中」和「有在飞子 agent」，
 * 那两种情况 state 都已回 IDLE、<b>不会再有下一次模型调用</b>，插话进去会一直躺在队列里。
 */
class CodeTuiViewInterjectionRouteTest {

    private static final class Handler implements SubmitHandler {
        final List<String> interjected = new ArrayList<>();
        final List<String> submitted = new ArrayList<>();
        final List<String> submittedSkills = new ArrayList<>();
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        @Override public Disposable submit(String text) { return submit(text, null); }

        @Override public Disposable submit(String text, String skill) {
            submitted.add(text);
            submittedSkills.add(skill);
            return () -> {};
        }

        @Override public boolean hasInFlightSubagents() { return inFlight.get(); }
        @Override public void interject(String text) { interjected.add(text); }
    }

    private static void type(CodeTuiView v, String s) {
        v.setInputForTest(s);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
    }

    @Test
    @DisplayName("回合在飞时 Enter 走插话，不入排队队列")
    void turnInFlightRoutesToInterjection() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // 回合在飞：!isIdle()

        type(v, "改用方案 B");

        assertEquals(List.of("改用方案 B"), h.interjected);
        assertEquals(0, s.queuedCount(), "回合在飞时不应进老队列");
        assertEquals(List.of(), h.submitted, "插话不该起新回合");
    }

    @Test
    @DisplayName("一次提交只读取一次路由状态，避免回合结束竞态把插话误排队")
    void submissionUsesOneRoutingSnapshot() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);

        ConversationState.SubmissionSnapshot snapshot = s.submissionSnapshot();
        s.onTurnComplete(1);                      // Reactor 在快照后、路由前恰好结束回合
        CodeTuiView.SubmissionRoute route = CodeTuiView.submissionRoute(snapshot, false, null);

        assertEquals(CodeTuiView.SubmissionRoute.INTERJECT, route,
                "同一次 Enter 的路由必须基于一个快照，不能因随后状态变 IDLE 而改走排队");
        assertTrue(s.isIdle(), "前提：竞态钩子确实结束了回合");
    }

    /**
     * 输入那一刻<b>不</b>往 scrollback 打行——那时它还没送达，而 scrollback 里的行改不了，
     * 打下去就永远停在「输入时」这个错位置上（真实位置在后面那条工具结果之后）。
     * 未送达期间的可见性交给输入框上方的面板（见 {@code CodeTuiViewInterjectionPanelTest}），
     * 送达时才由 {@code onUserMessage} 打进信息流。
     */
    @Test
    @DisplayName("输入插话时不写 scrollback")
    void interjectionDoesNotWriteScrollbackOnInput() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        type(v, "改用方案 B");

        List<String> echoed = s.drainPending().stream()
                .map(ConversationState.OutputLine::text)
                .filter(t -> t.contains("改用方案 B"))
                .toList();
        assertEquals(List.of(), echoed,
                "输入时就打进 scrollback 的话，位置是错的且再也改不了：" + echoed);
        assertEquals(List.of("改用方案 B"), h.interjected, "但话必须已经进队列");
    }

    /** 压缩中没有在跑的模型循环，插话进去石沉大海——必须回落老队列。 */
    @Test
    @DisplayName("压缩中回落老队列")
    void compactingFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onCompactionStarted("手动");            // IDLE 但 compacting ⇒ busy()==true

        type(v, "改用方案 B");

        assertEquals(List.of(), h.interjected, "压缩中不该走插话——不会再有模型调用");
        assertEquals(1, s.queuedCount());
    }

    /** 回合已被 Esc 取消、只剩子 agent 在收尾：同样不会再调模型。 */
    @Test
    @DisplayName("仅子 agent 在飞时回落老队列")
    void onlySubagentsInFlightFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.cancelCurrent();
        h.inFlight.set(true);                     // IDLE 但有在飞子 agent ⇒ busy()==true

        type(v, "改用方案 B");

        assertEquals(List.of(), h.interjected);
        assertEquals(1, s.queuedCount());
    }

    /** 带技能挂载走插话会静默丢技能——插话是纯 UserMessage，带不了 submit 的第二参数。 */
    @Test
    @DisplayName("带技能挂载时回落老队列")
    void skillMountedFallsBackToQueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // 回合在飞：默认本会走插话
        v.mountSkillForTest("brainstorming");

        type(v, "帮我想想");

        assertEquals(List.of(), h.interjected, "带技能不该走插话，会丢技能");
        assertEquals(1, s.queuedCount());
        assertEquals("brainstorming", s.pollQueued().skill(),
                "技能必须随消息一起入队，出队时才兑现得了");
    }

    /** 空闲时照旧立即起回合，插话路径不该抢它。 */
    @Test
    @DisplayName("空闲时仍走 dispatch")
    void idleStillDispatches() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "新任务");

        assertEquals(List.of("新任务"), h.submitted);
        assertEquals(List.of(), h.interjected);
    }

    /** /queue 明确要求排到下回合，即使回合在飞也不插话。 */
    @Test
    @DisplayName("/queue 强制排队，不插话")
    void queueCommandForcesEnqueue() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);                       // 回合在飞，默认本会走插话

        type(v, "/queue 等你忙完再看这个");

        assertEquals(List.of(), h.interjected, "/queue 不该走插话");
        assertEquals(1, s.queuedCount());
        assertEquals(List.of("等你忙完再看这个"), s.queuedSnapshot(), "命令前缀不该进消息正文");
    }

    /** 空闲时「排队」等价于直接发，不必让用户再按一次回车。 */
    @Test
    @DisplayName("/queue 空闲时直接发")
    void queueCommandWhenIdleDispatches() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/queue 直接发这条");

        assertEquals(List.of("直接发这条"), h.submitted);
        assertEquals(0, s.queuedCount());
    }

    /** 技能挂载是一次性的：/queue 也得取走，否则下一条消息会莫名其妙带上它。 */
    @Test
    @DisplayName("/queue 带走技能挂载")
    void queueCommandCarriesMountedSkill() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);
        v.mountSkillForTest("brainstorming");

        type(v, "/queue 帮我想想");

        assertEquals("brainstorming", s.pollQueued().skill(), "技能应随排队消息带走");

        s.cancelCurrent();
        type(v, "下一条");
        assertEquals(List.of("下一条"), h.submitted);
        assertNull(h.submittedSkills.get(0), "技能是一次性的，不该粘在下一条消息上");
    }

    /** 空参数没有意义，给提示而不是排一条空消息。 */
    @Test
    @DisplayName("/queue 不带内容时给提示")
    void bareQueueCommandShowsNotice() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        type(v, "/queue");

        assertEquals(0, s.queuedCount());
        assertEquals(List.of(), h.interjected, "空 /queue 更不该走插话");
        assertEquals("用法：/queue <消息> — 排到下一回合再发", s.notice());
        assertEquals("/queue", v.inputTextForTest(), "输入保留：用户多半是想接着把内容打完");
    }
}
