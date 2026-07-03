package com.example.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 排队项携带技能：enqueue(text, skill) → pollQueued() 返回 Queued(text, skill)；snapshot 仍只给文本。 */
class ConversationStateQueueTest {

    @Test
    void enqueueWithSkill_pollReturnsBoth() {
        ConversationState s = new ConversationState();
        s.enqueue("消息A", "git-commit-message");
        s.enqueue("消息B", null);

        assertEquals(2, s.queuedCount());
        assertEquals(List.of("消息A", "消息B"), s.queuedSnapshot(), "快照仍只含文本，渲染不变");

        ConversationState.Queued first = s.pollQueued();
        assertEquals("消息A", first.text());
        assertEquals("git-commit-message", first.skill());

        ConversationState.Queued second = s.pollQueued();
        assertEquals("消息B", second.text());
        assertNull(second.skill(), "未挂载技能时 skill 为 null");

        assertNull(s.pollQueued(), "队列空时返回 null");
    }
}
