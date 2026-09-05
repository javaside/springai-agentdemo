package io.github.javaside.springai.codetui.agent.interjection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeNoticeTest {

    private static final String NOTICE = "<system-notice>连接恢复，请继续</system-notice>";

    private static final class Spy implements ChatModel {
        private final AtomicReference<Prompt> seen = new AtomicReference<>();

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            seen.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    private static Prompt originalPrompt() {
        return new Prompt(new UserMessage("原始提问"));
    }

    @Test
    @DisplayName("仅恢复通知时追加一条裸 UserMessage 并原子取清")
    void injectsAndTakesNoticeWithoutWrapping() {
        Interjections interjections = new Interjections();
        interjections.setResumeNotice(NOTICE);
        Spy spy = new Spy();

        InterjectingChatModel.wrap(spy, interjections).call(originalPrompt());

        List<Message> messages = spy.seen.get().getInstructions();
        assertEquals(2, messages.size());
        assertEquals(NOTICE, messages.get(1).getText(), "仅 notice 不得经过 interjection 包裹");
        assertNull(interjections.peekResumeNoticeForTest(), "inject 必须通过读即清通道消费 notice");
    }

    @Test
    @DisplayName("插话与恢复通知合并为末尾同一条 UserMessage，插话在前")
    void combinesInterjectionsAndNoticeIntoOneMessage() {
        Interjections interjections = new Interjections();
        interjections.offer("插1");
        interjections.offer("插2");
        interjections.setResumeNotice(NOTICE);
        Spy spy = new Spy();

        InterjectingChatModel.wrap(spy, interjections).call(originalPrompt());

        List<Message> messages = spy.seen.get().getInstructions();
        assertEquals(2, messages.size(), "注入产物必须恒为一条消息");
        assertEquals(InterjectingChatModel.wrapText("插1\n插2") + "\n\n" + NOTICE,
                messages.get(1).getText());
    }

    @Test
    @DisplayName("drainForRefill 即使没有插话也清除恢复通知且不推进 UI 版本")
    void escapeClearsNoticeWithoutPublishingUiChange() {
        Interjections interjections = new Interjections();
        interjections.setResumeNotice(NOTICE);
        long version = interjections.uiVersion();

        assertTrue(interjections.drainForRefill().isEmpty());

        assertNull(interjections.peekResumeNoticeForTest());
        assertEquals(version, interjections.uiVersion(), "notice 不进入 UI 面板，单独清理无需 publish");
    }

    @Test
    @DisplayName("refillForResume 将整个 delivered 作为单元素放回 pending 头部")
    void refillForResumePreservesMergedTextAndChronology() {
        Interjections interjections = new Interjections();
        interjections.offer("A");
        interjections.drainForInjection("call-1");
        interjections.offer("B");

        interjections.refillForResume();

        assertEquals(List.of("A", "B"), interjections.pendingSnapshot(),
                "退避前已送达插话必须排在退避期新插话之前");
        assertTrue(interjections.takeForHistory().isEmpty(), "回填后 delivered 不得再落历史");
    }

    @Test
    @DisplayName("takeAllForResumeUser 原子取走 delivered 与全部 pending 并保持时序，空调用不发布")
    void takeAllForResumeUserConsumesAllResumeTextAtomically() {
        Interjections interjections = new Interjections();
        interjections.offer("A1");
        interjections.offer("A2");
        interjections.drainForInjection("call-1");
        interjections.offer("B");
        interjections.offer("C");
        interjections.setResumeNotice(NOTICE);

        AtomicInteger publishes = new AtomicInteger();
        interjections.setUiChangeListener(bits -> publishes.incrementAndGet());

        assertEquals(List.of("A1\nA2", "B", "C"), interjections.takeAllForResumeUser(),
                "delivered 是一个含换行的完整元素，其后才是 pending 原文");
        assertEquals(1, publishes.get(), "真实移除必须恰好发布一次 UI 变化");
        assertTrue(interjections.pendingSnapshot().isEmpty(), "返回后 pending 必须为空，避免 Interjecting 再注入");
        assertTrue(interjections.takeForHistory().isEmpty(), "取走后 delivered 不得再落历史");
        assertEquals(NOTICE, interjections.peekResumeNoticeForTest(), "resume API 不得消费独立 notice 通道");

        long version = interjections.uiVersion();
        assertTrue(interjections.takeAllForResumeUser().isEmpty(), "再次调用应为空 no-op");
        assertEquals(version, interjections.uiVersion(), "空调用不得推进 UI 版本");
        assertEquals(1, publishes.get(), "空调用不得有任何 publish 副作用");
    }

    @Test
    @DisplayName("仅恢复通知不触发送达回调，空状态仍原样放行")
    void noticeDoesNotFireDelivered() {
        Interjections interjections = new Interjections();
        List<String> delivered = new ArrayList<>();
        interjections.onDelivered(delivered::add);
        interjections.setResumeNotice(NOTICE);
        Spy spy = new Spy();

        InterjectingChatModel.wrap(spy, interjections).call(originalPrompt());
        assertTrue(delivered.isEmpty(), "notice 不进入 delivered 路径");

        Prompt second = originalPrompt();
        InterjectingChatModel.wrap(spy, interjections).call(second);
        assertSame(second, spy.seen.get(), "插话与 notice 都空时才原样早退");
    }
}
