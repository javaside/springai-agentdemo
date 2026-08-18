package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AskRequest;
import io.github.javaside.springai.codetui.agent.AskResponder;
import io.github.javaside.springai.codetui.agent.QuestionSpec;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * view 集成：drain 一拍把模态出现 / 回合完成翻译成 {@link AttentionTracker} 的边沿动作。
 * 状态机本身的边沿语义由 {@code AttentionTrackerTest} 钉；这里只钉「view 的接线」——
 * busy 的取值口径（不含模态）、模态侦测源（peekModal + 三个 active* 字段）、Esc 置位抑制。
 * 测试态 runner()==null，TerminalAttention 静默降级，断言落在 attention 的 phase 上。
 */
class CodeTuiViewAttentionTest {

    private static final class Stub implements SubmitHandler {
        @Override public Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return PermissionMode.DEFAULT; }
        @Override public String currentModel() { return "deepseek-chat"; }
    }

    private static AskRequest ask(long turnId) {
        QuestionSpec q = new QuestionSpec("继续吗？", "确认", List.of(
                new io.github.javaside.springai.codetui.agent.OptionSpec("是", ""),
                new io.github.javaside.springai.codetui.agent.OptionSpec("否", "")),
                false);
        return new AskRequest(turnId, List.of(q), new AskResponder() {
            @Override public void answer(java.util.Map<String, String> answers) { }
            @Override public void cancel() { }
        });
    }

    @Test
    @DisplayName("模态入队 → drain 一拍后处于 WAITING_USER")
    void modalQueuedAdvancesToWaiting(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new Stub(), root);
        state.onTurnStarted(1L);
        state.onQuestionAsked(1L, ask(1L));

        v.tickForTest();
        assertEquals(AttentionTracker.Phase.WAITING_USER, v.attentionForTest().phase());
    }

    @Test
    @DisplayName("回合完成 → drain 一拍后处于 DONE")
    void turnCompleteAdvancesToDone(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new Stub(), root);
        state.onTurnStarted(1L);
        v.tickForTest();                                     // → BUSY
        state.onTurnComplete(1L);

        v.tickForTest();
        assertEquals(AttentionTracker.Phase.DONE, v.attentionForTest().phase());
    }

    @Test
    @DisplayName("Esc 取消的忙→闲 → 不进 DONE（抑制），保持 IDLE")
    void escCancelledTurnSuppressed(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new Stub(), root);
        state.onTurnStarted(1L);
        v.tickForTest();                                     // → BUSY

        // Esc：走真实按键入口（置位 userCancelledSinceLastTick），随后 cancelCurrent 让 state 回 IDLE
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        v.tickForTest();
        assertEquals(AttentionTracker.Phase.IDLE, v.attentionForTest().phase());
    }
}
