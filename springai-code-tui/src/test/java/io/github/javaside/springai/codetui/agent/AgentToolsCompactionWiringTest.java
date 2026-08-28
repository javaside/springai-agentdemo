package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.compaction.NotifyingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import io.github.javaside.springai.codetui.agent.media.MediaReferencePreservingCompactionStrategy;
import io.github.javaside.springai.codetui.agent.media.VisionMaterializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;

/**
 * 装配级：<b>自动</b>与<b>手动</b>两条压缩路径都必须保住图片引用清单。
 *
 * <p><b>为什么单靠 {@code MediaReferencePreservingCompactionStrategyTest} 不够</b>：那一组测的是装饰器
 * <b>本身</b>，装配上漏了哪条路径它一条都不会红。而漏接恰恰是这里最可能发生的错——自动和手动是两个
 * <b>独立</b>的策略对象（手动那条刻意不包 Notifying，不能顺手复用自动那条），只接自动的话代码照样编译、
 * 自动压缩照样保住图，直到用户按下 {@code /compact} 图才全丢。
 *
 * <p><b>为什么不用反射穿透 delegate 链</b>：断言「链条里含某个类」只认结构，装饰器一换形状（比如将来
 * 改成组合而非嵌套）测试就假红或假绿。这里改为<b>行为断言</b>——给装配方法塞一个假 base，让它吐出
 * 「归档里有一条引用」的结果，然后看走完整条链之后清单在不在。它不关心链条长什么样，只关心那个
 * 缺陷还在不在。代价是装配方法要把 base 作为参数收进来（见 {@link AgentTools#autoCompaction}），
 * 而这正好也让测试不必起一个真 LLM。
 */
class AgentToolsCompactionWiringTest {

    private static final String ARCHIVED_PATH = ".codetui/artifacts/b7e2f1a0cafebabe.png";

    /** 一条被归档、含图片引用的用户事件。 */
    private static SessionEvent archivedWithReference() {
        String block = FileReference.OPEN + "\n"
                + "id: sha256:b7e2f1a0cafebabe\n"
                + "kind: image\n"
                + "mime_type: image/png\n"
                + "size_bytes: 519531\n"
                + "dimensions: 1440x900\n"
                + "name: cart.png\n"
                + "path: " + ARCHIVED_PATH + "\n"
                + "delivery: not_in_view\n"
                + "reason: 更早的回合\n"
                + FileReference.CLOSE;
        return SessionEvent.builder().sessionId("sess-1")
                .message(UserMessage.builder().text("看这张\n" + block).build()).build();
    }

    /** 真实的压缩请求。绝不传 null——见 MediaReferencePreservingCompactionStrategyTest#request 的理由。 */
    private static CompactionRequest request() {
        return CompactionRequest.of(Session.builder().id("sess-1").userId("u").build(), List.of());
    }

    private static SessionEvent summary() {
        return SessionEvent.builder().sessionId("sess-1")
                .message(UserMessage.builder().text("用户提供了一张截图。").build()).build();
    }

    /** 假的「真正做摘要的策略」：引用块已被改写成自然语言，原块进了 archivedEvents。 */
    private static CompactionStrategy fakeSummariser(SessionEvent summary) {
        CompactionResult r = new CompactionResult(List.of(summary), List.of(archivedWithReference()), 9000);
        return req -> r;
    }

    /** 断言压缩结果里确实多了一条带 synthetic 标记、逐字保留了 path 的清单事件。 */
    private static void assertManifestPresent(CompactionResult out, SessionEvent summary, String path) {
        assertEquals(2, out.compactedEvents().size(),
                "应在摘要之外多出一条附件清单事件，实际：" + out.compactedEvents());
        Message manifest = out.compactedEvents().get(0).getMessage();
        String text = manifest.getText();
        assertTrue(text.startsWith(MediaReferencePreservingCompactionStrategy.MANIFEST_HEADER),
                "首条应是附件清单，实际：" + text);
        assertTrue(text.contains(path), "path 是唯一寻址依据，必须逐字保留，实际：" + text);
        assertTrue(VisionMaterializer.isSynthetic(manifest), "清单消息必须带 synthetic 标记");
        assertEquals(summary.getMessage().getText(), out.compactedEvents().get(1).getMessage().getText(),
                "摘要本身不应被改动");
    }

    @Test
    void autoPath_preservesMediaReferences() {
        SessionEvent summary = summary();
        RecordingListener listener = new RecordingListener();

        CompactionResult out = AgentTools.autoCompaction(fakeSummariser(summary), listener).compact(request());

        assertManifestPresent(out, summary, ARCHIVED_PATH);
        assertEquals("auto", listener.startedReason, "自动路径仍须经 Notifying 上报，reason=auto");
        assertEquals(9000, listener.finishedSaved, "上报的 token 账应来自 delegate");
    }

    @Test
    void manualPath_preservesMediaReferences() {
        SessionEvent summary = summary();

        CompactionResult out = AgentTools.manualCompaction(fakeSummariser(summary)).compact(request());

        assertManifestPresent(out, summary, ARCHIVED_PATH);
    }

    @Test
    void manualPath_doesNotNotify() {
        // 手动路径由 CodingAgent.runCompaction 自行上报；这里若冒出 started，就是同一次压缩被报了两遍。
        //
        // 断言的是<b>类型</b>而不是「某个 listener 没被回调」：manualCompaction 压根不收 listener 参数，
        // 造一个本地 listener 去断言它没被调用，无论实现怎么改都恒真——那种测试拆掉整条防线也不会红。
        // 手动路径一旦被包上 Notifying，最外层类型必然变成 NotifyingCompactionStrategy，这里立刻红。
        CompactionStrategy manual = AgentTools.manualCompaction(fakeSummariser(summary()));
        assertFalse(manual instanceof NotifyingCompactionStrategy,
                "手动策略不该挂 Notifying（会和 CodingAgent.runCompaction 的自报重复），实际：" + manual.getClass());
    }

    /** 只记压缩三事件的最小 listener，其余接缝方法留空。 */
    private static final class RecordingListener implements AgentListener {
        String startedReason;
        int finishedSaved = -1;
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        @Override public void onToolStarted(long turnId, String toolName, String input) { }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) { }
        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long turnId, Throwable error) { }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }
        @Override public void onCompactionStarted(String reason) { startedReason = reason; }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { finishedSaved = tokensSaved; }
        @Override public void onCompactionFailed(String message) { }
    }
}
