package io.github.javaside.springai.tamboui;

import io.github.javaside.springai.tamboui.demo.FakeStreamer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 假流式分帧逻辑测试：全文逐字符推进，未定稿的残段留在 liveTail（live 区原地刷新），
 * 遇 \n 定稿的完整行进 settledLines（下沉 scrollback），全文耗尽后残段自动定稿。
 */
class FakeStreamerTest {

    @Test
    void streamingAccumulatesInLiveTailBeforeNewline() {
        FakeStreamer s = new FakeStreamer("你好\n世界吗");
        s.advance();
        s.advance();
        assertEquals("你好", s.view().liveTail());
        assertEquals(List.of(), s.view().settledLines());
        assertFalse(s.done());
    }

    @Test
    void newlineSettlesLineAndClearsTail() {
        FakeStreamer s = new FakeStreamer("你好\n世界吗");
        s.advance();
        s.advance();
        s.advance();   // 越过 \n
        assertEquals("", s.view().liveTail());
        assertEquals(List.of("你好"), s.view().settledLines());
        assertFalse(s.done());
    }

    @Test
    void doneSettlesRemainingTail() {
        FakeStreamer s = new FakeStreamer("你好\n世界吗");
        while (!s.done()) {
            s.advance();
        }
        assertEquals("", s.view().liveTail());
        assertEquals(List.of("你好", "世界吗"), s.view().settledLines());
        assertTrue(s.done());
    }

    @Test
    void singleLineWithoutNewlineSettlesAtDone() {
        FakeStreamer s = new FakeStreamer("abc");
        s.advance();
        assertEquals("a", s.view().liveTail());
        while (!s.done()) {
            s.advance();
        }
        assertEquals(List.of("abc"), s.view().settledLines());
    }

    @Test
    void emptyTextIsImmediatelyDoneWithNothingSettled() {
        FakeStreamer s = new FakeStreamer("");
        assertTrue(s.done());
        assertEquals("", s.view().liveTail());
        assertEquals(List.of(), s.view().settledLines());
    }
}
