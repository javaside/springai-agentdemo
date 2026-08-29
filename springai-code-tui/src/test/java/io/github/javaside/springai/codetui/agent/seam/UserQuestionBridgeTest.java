package io.github.javaside.springai.codetui.agent.seam;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 握手桥：另起线程调 handle 阻塞，主线程经捕获的 responder 应答/取消。不联网、确定性。 */
class UserQuestionBridgeTest {

    private static Question q() {
        return new Question("选哪个?", "选择",
                List.of(new Option("A", "第一"), new Option("B", "第二")), false);
    }

    /** 捕获 onQuestionAsked 传来的 AskRequest 的 listener。 */
    private static final class CaptureListener extends StubListener {
        final AtomicReference<AskRequest> captured = new AtomicReference<>();
        final CountDownLatch asked = new CountDownLatch(1);
        @Override public void onQuestionAsked(long turnId, AskRequest request) {
            captured.set(request);
            asked.countDown();
        }
    }

    @Test
    void handle_returnsAnswersFromResponder() throws Exception {
        CaptureListener listener = new CaptureListener();
        UserQuestionBridge bridge = new UserQuestionBridge(listener);
        AtomicReference<Map<String, String>> ret = new AtomicReference<>();
        Thread tool = new Thread(() -> ret.set(bridge.handle(List.of(q()))), "tool");
        tool.start();

        assertTrue(listener.asked.await(2, TimeUnit.SECONDS), "handle 应触发 onQuestionAsked");
        AskRequest req = listener.captured.get();
        assertNotNull(req);
        assertEquals(1, req.questions().size());
        assertEquals("选哪个?", req.questions().get(0).question());

        req.responder().answer(Map.of("选哪个?", "A"));
        tool.join(2000);
        assertEquals(Map.of("选哪个?", "A"), ret.get(), "handle 应返回 responder 喂入的答案");
    }

    @Test
    void handle_throwsOnCancel() throws Exception {
        CaptureListener listener = new CaptureListener();
        UserQuestionBridge bridge = new UserQuestionBridge(listener);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            try { bridge.handle(List.of(q())); fail("应抛 QuestionCancelledException"); }
            catch (Throwable t) { err.set(t); }
        }, "tool");
        tool.start();

        assertTrue(listener.asked.await(2, TimeUnit.SECONDS));
        listener.captured.get().responder().cancel();
        tool.join(2000);
        assertInstanceOf(QuestionCancelledException.class, err.get());
    }

    @Test
    void translate_stripsSpringTypes() {
        List<QuestionSpec> specs = UserQuestionBridge.translate(List.of(
                new Question("多选?", "特性",
                        List.of(new Option("X", "x"), new Option("Y", "y")), true)));
        assertEquals(1, specs.size());
        QuestionSpec s = specs.get(0);
        assertEquals("多选?", s.question());
        assertEquals("特性", s.header());
        assertTrue(s.multiSelect());
        assertEquals(List.of("X", "Y"), s.options().stream().map(OptionSpec::label).toList());
    }
}
