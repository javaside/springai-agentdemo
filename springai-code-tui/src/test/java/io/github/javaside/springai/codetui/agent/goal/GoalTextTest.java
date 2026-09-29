package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalTextTest {

    @Test
    void continuePrefixRoundTrip() {
        String msg = GoalText.continuePrefix(3, 25) + "\n目标：迁移完成";
        assertTrue(GoalText.isContinueMessage(msg));
        assertFalse(GoalText.isContinueMessage("普通消息"));
        assertEquals("[goal 继续 3/25]", GoalText.continuePrefix(3, 25));
    }

    @Test
    void evaluationWrapUnwrap() {
        String wrapped = GoalText.wrapEvaluation("UNSATISFIED", "还有 2 处未迁移");
        assertTrue(wrapped.startsWith(GoalText.EVAL_OPEN));
        assertTrue(wrapped.endsWith(GoalText.EVAL_CLOSE));
        assertEquals("UNSATISFIED：还有 2 处未迁移", GoalText.unwrapEvaluation(wrapped));
        assertEquals("原文", GoalText.unwrapEvaluation("原文"));   // 未包裹原样返回
    }

    @Test
    void tailTruncatesOnCodePointBoundary() {   // UTF-16 代理对安全
        String emoji = "🙂".repeat(10);          // 10 个码点，20 个 char
        assertEquals("🙂".repeat(5), GoalText.tail(emoji, 10));
        // 截 11 个 char 会劈开代理对——必须退到 10 个 char（5 个码点）
        assertEquals("🙂".repeat(5), GoalText.tail(emoji, 11));
        String short_ = "abc";
        assertSame(short_, GoalText.tail(short_, 100));   // 不动原串
    }
}
