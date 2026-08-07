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

    /** 回显打的是用户原话，且绝不带「待送达」之类的状态——scrollback 里的行改不了，送达后会永远停在错的状态上。 */
    @Test
    @DisplayName("插话回显用户原话，不写送达状态")
    void interjectionEchoesRawText() {
        ConversationState s = new ConversationState();
        Handler h = new Handler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));
        s.onTurnStarted(1);

        type(v, "改用方案 B");

        List<String> echoed = s.drainPending().stream()
                .map(ConversationState.OutputLine::text)
                .filter(t -> t.contains("改用方案 B"))
                .toList();
        assertEquals(List.of("› 改用方案 B"), echoed, "只回显原话，不附加送达状态");
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
}
