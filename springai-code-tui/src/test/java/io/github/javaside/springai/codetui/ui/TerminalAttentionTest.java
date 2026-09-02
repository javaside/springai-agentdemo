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
        // TerminalAttention 已不再反射 backend（改走 submitPtyControlSequence 公开口——
        // pty writer 队列，审核 M-3/P1：直写会在 pty-writer 持锁卡死时冻死渲染线程）。
        // 结构钉改为：提交口必须存在（patch shadow 与本模块的 compile 依赖契约）。
        Class<?> tuiRunner = Class.forName("dev.tamboui.tui.InlineTuiRunner");
        assertNotNull(tuiRunner.getMethod("submitPtyControlSequence", String.class),
                "submitPtyControlSequence 是 TerminalAttention 的新依赖面，签名漂移在此红灯");
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
