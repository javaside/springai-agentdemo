package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /clear 命令分发：空闲时触发 clearContext + 面板复位；忙时拒绝并置 notice、不触发。
 * 视图在测试里 runner()==null（未 run），故视觉清屏被跳过、只断言非视觉副作用。
 */
class CodeTuiViewClearTest {

    /** 记录 clearContext 调用次数的桩。 */
    private static final class RecordingHandler implements SubmitHandler {
        final AtomicInteger clears = new AtomicInteger();
        @Override public Disposable submit(String text) { return null; }
        @Override public void clearContext() { clears.incrementAndGet(); }
    }

    private static void type(CodeTuiView v, String s) {
        for (char c : s.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
    }

    @Test
    void clear_whenIdle_invokesClearContext_andResetsPanels() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        s.onTodoUpdated(1, java.util.List.of("[ ] 旧任务"));
        s.onTurnComplete(1);                         // 回到 IDLE，但 todo 面板仍在
        assertTrue(s.isIdle(), "前置：空闲");
        assertEquals(1, s.todoSnapshot().size(), "前置：todo 有 1 条");

        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(1, h.clears.get(), "空闲 /clear 应调用 clearContext 一次");
        assertTrue(s.todoSnapshot().isEmpty(), "面板应复位");
    }

    @Test
    void clear_whenBusy_isRejected_withNotice_andDoesNotClear() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);                          // 回合进行中
        assertTrue(s.isBusy(), "前置：忙碌");

        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(0, h.clears.get(), "忙时不应换会话");
        assertEquals("忙碌中，无法清空", s.notice(), "忙时应置提示");
    }

    @Test
    void clear_whenCompactingButIdle_isRejected() {
        ConversationState s = new ConversationState();
        s.onCompactionStarted("manual");             // compacting=true，但 status 仍 IDLE
        assertTrue(s.isIdle(), "前置：status 仍 IDLE");
        assertTrue(s.isCompacting(), "前置：压缩中");

        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(0, h.clears.get(), "压缩中不应换会话");
        assertEquals("忙碌中，无法清空", s.notice(), "压缩中应置提示");
    }

    /**
     * 降级提示必须是两条独立 INFO：分隔行 + 说明行。
     * 屏障降级时 writer 在飞旧批仍会晚到（旧内容出现在分割线之后），说明行消除
     * 「清屏了旧内容又冒出来」的困惑；独立成行才能被断言钉住（合并成一行会红）。
     */
    @Test
    void clearDegradedShowsSeparateExplanationLine() {
        ConversationState s = new ConversationState();
        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        // 不调 tickForTest：测试态无批消费，pushInfo 停在 pending，直接取。

        java.util.List<String> lines = new java.util.ArrayList<>();
        for (ConversationState.OutputLine ol; (ol = s.pollPending()) != null; ) {
            lines.add(ol.text());
        }
        long separators = lines.stream()
                .filter(l -> l.startsWith("───") && l.contains("新会话")).count();
        long explanations = lines.stream()
                .filter(l -> l.contains("上方若浮现旧内容")).count();
        assertEquals(1, separators, "分隔行必须是独立一条: " + lines);
        assertEquals(1, explanations, "说明行必须是独立另一条（不得与分隔行合并）: " + lines);
    }
}
