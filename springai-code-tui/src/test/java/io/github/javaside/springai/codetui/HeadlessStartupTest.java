package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** -p 与 --output-last-message 解析（纯函数；stdin 读取在 Runner，不属解析）。 */
class HeadlessStartupTest {

    @Test
    void headlessPrompt_value_stdinSentinel_missingOrFlagOrBlankRejected() {
        assertEquals("帮我修 NPE", CodeTuiApplication.headlessPrompt(new String[]{"-p", "帮我修 NPE"}));
        assertEquals("-", CodeTuiApplication.headlessPrompt(new String[]{"-p", "-"}), "stdin 哨兵");
        assertNull(CodeTuiApplication.headlessPrompt(new String[]{"-c"}), "未给 -p");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p"}), "尾部缺值");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p", "--permission-mode"}),
                "下一参是选项（注意：- 本身除外，它是 stdin 哨兵——与 resumeFlagValue 的差异）");
        assertThrows(IllegalArgumentException.class,
                () -> CodeTuiApplication.headlessPrompt(new String[]{"-p", "  "}), "空白提示词");
    }

    @Test
    void hasPrintFlag_and_outputLastMessagePath() {
        assertTrue(CodeTuiApplication.hasPrintFlag(new String[]{"-c", "-p", "x"}));
        assertFalse(CodeTuiApplication.hasPrintFlag(new String[]{"-c"}));
        assertEquals("/tmp/out.txt",
                CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message", "/tmp/out.txt"}));
        assertEquals("/tmp/out.txt",
                CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message=/tmp/out.txt"}));
        assertNull(CodeTuiApplication.outputLastMessagePath(new String[]{"-p", "x"}));
        assertNull(CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message"}),
                "尾部缺值按未给处理（headless 下 stdout 仍有回复，不算失败）");
        assertNull(CodeTuiApplication.outputLastMessagePath(
                        new String[]{"--output-last-message", "--permission-mode"}),
                "下一参以 - 开头 → 当缺值（选项不得被当成输出路径）");
        assertNull(CodeTuiApplication.outputLastMessagePath(new String[]{"--output-last-message="}),
                "= 后空值按未给处理");
    }
}
