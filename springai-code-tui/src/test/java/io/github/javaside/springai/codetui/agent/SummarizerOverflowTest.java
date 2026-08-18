package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizerOverflowTest {

    // ---- 分类:是不是「超限」 ----

    @Test
    void recognizesCommonOverflowVariantsCaseInsensitively() {
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "400 - {\"error\":{\"code\":\"context_length_exceeded\"}}")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "Prompt is too long: 195300 tokens > 190000 maximum")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException(
                "This model's MAXIMUM CONTEXT LENGTH is 65536 tokens.")));
        assertTrue(SummarizerOverflow.isOverflow(new RuntimeException("input too long for model")));
    }

    @Test
    void walksCauseChainForWrappedServerErrors() {
        // Spring AI 常把服务端 4xx 包在通用 RuntimeException 里
        RuntimeException wrapped = new RuntimeException("call failed",
                new IllegalStateException(new RuntimeException("maximum context length is 163857 tokens")));
        assertTrue(SummarizerOverflow.isOverflow(wrapped));
    }

    @Test
    void networkAndAuthErrorsAreNotOverflow() {
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException("connection reset by peer")));
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException("401 invalid api key")));
        assertFalse(SummarizerOverflow.isOverflow(new RuntimeException((String) null)));
        assertFalse(SummarizerOverflow.isOverflow(cyclicCause()));
    }

    private static RuntimeException cyclicCause() {
        RuntimeException a = new RuntimeException("network glitch");
        RuntimeException b = new RuntimeException("retry", a);
        a.initCause(b);   // 环:遍历必须有深度上限,不能死循环
        return a;
    }

    // ---- 数字解析:锚定窗口值,不是「找第一个数字」 ----

    @Test
    void anchorsOpenAiStyleAfterMaximumContextLength() {
        assertEquals(163_857L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "This model's maximum context length is 163857 tokens. However, you requested 250000 tokens.")));
    }

    @Test
    void anchorsAnthropicStyleBetweenGtAndMaximum() {
        // 两个数字:195300 是请求量,200000 才是窗口——必须取后者
        assertEquals(200_000L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "prompt is too long: 195300 tokens > 200000 maximum")));
    }

    @Test
    void anchorsChineseVariants() {
        assertEquals(131_072L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "输入过长,该模型最大上下文长度为 131072 tokens")));
    }

    @Test
    void parsesNumbersWithThousandsSeparators() {
        assertEquals(163_857L, SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "maximum context length is 163,857 tokens")));
    }

    @Test
    void parsesFromCauseChain() {
        RuntimeException wrapped = new RuntimeException("outer",
                new RuntimeException("Maximum context length is 65536"));
        assertEquals(65_536L, SummarizerOverflow.parseWindowTokens(wrapped));
    }

    @Test
    void returnsNullWhenNoAnchoredNumber() {
        assertNull(SummarizerOverflow.parseWindowTokens(new RuntimeException("prompt is too long")));
        // 有数字但没锚:不瞎猜
        assertNull(SummarizerOverflow.parseWindowTokens(new RuntimeException(
                "input too long, request id 8845123")));
    }
}
