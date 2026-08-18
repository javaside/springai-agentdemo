package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 结构保护（同 {@code ScreenCleanerTest} 纪律）：钉死 TerminalAttention 依赖的 TamboUI 私有字段
 * 仍存在——库升级改名在此红灯，提示回去更新反射路径（而非线上静默降级）。
 * 真实 BEL / 标题写入由 pty 冒烟验证。
 */
class TerminalAttentionTest {

    @Test
    void reflectionTargets_stillExistInThisTamboUiVersion() throws Exception {
        Class<?> tuiRunner = Class.forName("dev.tamboui.tui.InlineTuiRunner");
        assertNotNull(tuiRunner.getDeclaredField("backend"));
    }

    @Test
    void nullRunner_degradesQuietly() {
        assertDoesNotThrow(() -> assertFalse(TerminalAttention.alert(null, "⏳ x")));
        assertDoesNotThrow(() -> assertFalse(TerminalAttention.restore(null, "x")));
    }

    @Test
    void sanitize_stripsControlChars() throws Exception {
        java.lang.reflect.Method m = TerminalAttention.class.getDeclaredMethod("sanitize", String.class);
        m.setAccessible(true);
        assertEquals("abc", m.invoke(null, "a\033b\007c"), "ESC/BEL 必须剥掉：它们会截断 OSC 标题");
        assertEquals("标题 ok", m.invoke(null, "标题 ok"));
        assertEquals("", m.invoke(null, (Object) null));
    }
}
