package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 结构保护：钉死 ScreenCleaner 依赖的 TamboUI 私有字段仍存在——库升级改名会在此红灯，
 * 提示回去更新反射路径（而非线上静默降级）。真实清屏效果由 pty 冒烟脚本验证，非本单测职责。
 *
 * <p>用 {@code Class.forName} 而非直接 {@code X.class}：{@code InlineViewport} 是 <b>包私有</b>类，
 * 跨包 import 无法编译，只能反射按名解析。
 */
class ScreenCleanerTest {

    @Test
    void reflectionTargets_stillExistInThisTamboUiVersion() throws Exception {
        Class<?> tuiRunner = Class.forName("dev.tamboui.tui.InlineTuiRunner");
        Class<?> viewport = Class.forName("dev.tamboui.tui.InlineViewport");
        Class<?> display = Class.forName("dev.tamboui.inline.InlineDisplay");
        assertNotNull(tuiRunner.getDeclaredField("backend"));
        assertNotNull(tuiRunner.getDeclaredField("viewport"));
        assertNotNull(viewport.getDeclaredField("display"));
        assertNotNull(display.getDeclaredField("lastCursorY"));
        assertNotNull(display.getDeclaredField("currentHeight"));
        org.junit.jupiter.api.Assertions.assertEquals(int.class,
                display.getDeclaredField("lastCursorY").getType(), "lastCursorY 必须是 int（setInt 依赖）");
        org.junit.jupiter.api.Assertions.assertEquals(int.class,
                display.getDeclaredField("currentHeight").getType(), "currentHeight 必须是 int（setInt 依赖）");
    }

    @Test
    void clear_returnsFalse_andDoesNotThrow_whenRunnerIsNull() {
        assertDoesNotThrow(() -> {
            boolean ok = ScreenCleaner.clear(null);
            assertFalse(ok, "无法反射时返回 false");
        });
    }
}
