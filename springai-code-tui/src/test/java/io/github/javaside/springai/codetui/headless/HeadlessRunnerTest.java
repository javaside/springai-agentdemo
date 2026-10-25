package io.github.javaside.springai.codetui.headless;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runner 的可单测纯函数：env 超时值解析、提示词解析（stdin 哨兵/不 close/空校验）、开场会话提示。run() 全链路属真机冒烟（env 门控）。 */
class HeadlessRunnerTest {

    @Test
    void envTimeouts_defaultAndClamp() {
        assertEquals(10L, HeadlessRunner.mcpWaitSeconds(), "默认 10（env 未设走默认）");
        assertEquals(3600L, HeadlessRunner.maxSeconds(), "默认 3600");
        // 钳制边界：env 由 System.getenv 读——本用例只钉默认值与返回类型契约；
        // 钳制逻辑经私有 clamp 实现，真机/环境类测试不在此展开（CI 无 env 干扰）。
    }

    /** close 敏感流：真 System.in（FileInputStream）close 后再 read 抛 IOException，这里同构模拟。 */
    private static final class CloseSensitiveIn extends ByteArrayInputStream {
        private boolean closed;

        CloseSensitiveIn(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() {
            closed = true;
        }

        @Override public synchronized int read() {
            if (closed) {
                throw new IllegalStateException("Stream closed");   // 非受检：ByteArrayInputStream.read() 不声明受检异常
            }
            return super.read();
        }
    }

    @Test
    void resolvePrompt_stdinSentinelReadsAll() {
        InputStream old = System.in;
        System.setIn(new ByteArrayInputStream("第一行\n第二行".getBytes(StandardCharsets.UTF_8)));
        try {
            assertEquals("第一行\n第二行", HeadlessRunner.resolvePrompt(new String[]{"-p", "-"}), "stdin 全读含换行");
            assertEquals("直接文本", HeadlessRunner.resolvePrompt(new String[]{"-p", "直接文本"}));
        } finally {
            System.setIn(old);
        }
    }

    @Test
    void resolvePrompt_doesNotCloseStdin() throws Exception {
        // System.in 是进程级单例，不归本次读取所有——冒烟测试同 JVM 直调 run() 多次的纪律依赖它不被 close
        CloseSensitiveIn in = new CloseSensitiveIn("提示词");
        InputStream old = System.in;
        System.setIn(in);
        try {
            assertEquals("提示词", HeadlessRunner.resolvePrompt(new String[]{"-p", "-"}));
            assertDoesNotThrow(() -> {
                assertEquals(-1, in.read(), "读尽后 EOF 可读（readAllBytes 已读完），但绝不能是 Stream closed");
            }, "读 stdin 不得 close System.in——关掉后同 JVM 第二次 -p - 会炸");
        } finally {
            System.setIn(old);
        }
    }

    @Test
    void resolvePrompt_blankStdin_rejected() {
        // 与 headlessPrompt 对 "-p \"\"" 的拒绝对称：echo "" | codetui -p - 不许把空串 submit 给模型
        InputStream old = System.in;
        System.setIn(new ByteArrayInputStream("  \n ".getBytes(StandardCharsets.UTF_8)));
        try {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> HeadlessRunner.resolvePrompt(new String[]{"-p", "-"}),
                    "stdin 读到空白提示词要抛");
            assertTrue(e.getMessage().contains("stdin"), "错误文案要点明是 stdin 读到空：" + e.getMessage());
        } finally {
            System.setIn(old);
        }
    }

    @Test
    void resumeNotice_resumed_beatsContinueFlag_withSessionId() {
        assertEquals("（已恢复会话 abc123，历史进入上下文。）",
                HeadlessRunner.resumeNotice(true, true, "abc123"), "-c 且恢复成功时只报恢复");
    }

    @Test
    void resumeNotice_continueButNothingToResume_announcesNewSession() {
        String notice = HeadlessRunner.resumeNotice(false, true, null);
        assertTrue(notice.contains("没有可恢复的会话"), "脚本以为在续跑、实际新开——必须明说：" + notice);
        assertTrue(notice.contains("新会话"), "要说清已开新会话：" + notice);
    }

    @Test
    void resumeNotice_defaultStartup_silent() {
        assertNull(HeadlessRunner.resumeNotice(false, false, null), "默认新会话不打扰（对齐 TUI 第三分支不打扰的精神）");
    }
}
