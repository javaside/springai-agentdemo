package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterjectingChatModelTest {

    private static final class Spy implements ChatModel {
        final AtomicReference<Prompt> seen = new AtomicReference<>();
        final ChatOptions options = ToolCallingChatOptions.builder().build();

        @Override public ChatOptions getOptions() { return options; }
        @Override public ChatResponse call(Prompt p) {
            seen.set(p);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt p) { return Flux.just(call(p)); }
    }

    private static Prompt promptEndingWithToolResult() {
        return new Prompt(List.of(
                new UserMessage("原始提问"),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "someTool", "{}"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "someTool", "工具结果"))).build()));
    }

    /** 插话必须落在所有工具结果之后——落在 assistant(tool_calls) 与 tool 之间就是悬空 tool_calls，下一次请求 400。 */
    @Test
    @DisplayName("插话追加在消息表末尾，紧跟 tool 结果")
    void injectsAfterToolResults() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        Spy spy = new Spy();

        ChatModel wrapped = InterjectingChatModel.wrap(spy, q);
        wrapped.call(promptEndingWithToolResult());

        List<Message> msgs = spy.seen.get().getInstructions();
        assertEquals(4, msgs.size(), "应恰好多出一条插话");
        assertInstanceOf(ToolResponseMessage.class, msgs.get(2), "插话前一条必须是 tool 结果");
        assertInstanceOf(UserMessage.class, msgs.get(3), "插话必须是最后一条 user 消息");
    }

    /** 包裹是为了别让模型把插话误判成「上一轮结束了，这是新任务」。 */
    @Test
    @DisplayName("插话被 [interjection] 包裹，且与 wrapText 逐字相同")
    void injectedTextIsWrapped() {
        Interjections q = new Interjections();
        q.offer("改用方案 B");
        Spy spy = new Spy();

        InterjectingChatModel.wrap(spy, q).call(promptEndingWithToolResult());

        List<Message> msgs = spy.seen.get().getInstructions();
        String text = msgs.get(3).getText();
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"), text,
                "补历史要调同一个 wrapText，两处必须逐字一致");
        assertTrue(text.startsWith("[interjection]"));
        assertTrue(text.endsWith("[/interjection]"));
        assertTrue(text.contains("改用方案 B"));
    }

    /** 队列空时不能凭空多一条消息，也不该新建 Prompt。 */
    @Test
    @DisplayName("队列为空时原样放行")
    void emptyQueueIsNoOp() {
        Spy spy = new Spy();
        Prompt original = promptEndingWithToolResult();

        InterjectingChatModel.wrap(spy, new Interjections()).call(original);

        assertSame(original, spy.seen.get(), "无插话时应原样转发，不重建 Prompt");
    }

    /** 忘了转发 getOptions() 会让 toolCallbacks 被静默丢弃，模型看不到任何工具。这个项目踩过。 */
    @Test
    @DisplayName("getOptions 转发到底层")
    void forwardsOptions() {
        Spy spy = new Spy();
        assertSame(spy.options, InterjectingChatModel.wrap(spy, new Interjections()).getOptions());
    }

    // ── 送达通知 ──

    /**
     * 送达那一刻必须通知 UI。这是插话唯一能进信息流的时机——不发这个信号，
     * 屏幕上「模型采纳了插话」与「插话压根没送出去」长得一模一样。
     */
    @Test
    @DisplayName("注入成功时通知送达，交出原文")
    void notifiesOnDelivery() {
        Interjections q = new Interjections();
        List<String> delivered = new java.util.ArrayList<>();
        q.onDelivered(delivered::add);
        q.offer("改用方案 B");

        InterjectingChatModel.wrap(new Spy(), q).call(promptEndingWithToolResult());

        assertEquals(List.of("改用方案 B"), delivered,
                "交给 UI 的必须是原文——包裹后的文本含给模型的行为指引，那不是用户说的话");
    }

    /** 队列空时通知也不能发：否则每一次模型调用都会往信息流里打一条空的用户消息。 */
    @Test
    @DisplayName("无插话时不发送达通知")
    void noNotificationWhenNothingDelivered() {
        Interjections q = new Interjections();
        List<String> delivered = new java.util.ArrayList<>();
        q.onDelivered(delivered::add);

        InterjectingChatModel.wrap(new Spy(), q).call(promptEndingWithToolResult());

        assertTrue(delivered.isEmpty());
    }

    /** 流式是生产路径（主 agent 走 stream）。只在 call 上接通知等于没接。 */
    @Test
    @DisplayName("stream 路径同样通知送达")
    void notifiesOnStreamDelivery() {
        Interjections q = new Interjections();
        List<String> delivered = new java.util.ArrayList<>();
        q.onDelivered(delivered::add);
        q.offer("改用方案 B");

        InterjectingChatModel.wrap(new Spy(), q).stream(promptEndingWithToolResult()).blockLast();

        assertEquals(List.of("改用方案 B"), delivered);
    }
}
