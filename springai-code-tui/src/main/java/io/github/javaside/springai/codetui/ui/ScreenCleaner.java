package io.github.javaside.springai.codetui.ui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.app.InlineToolkitRunner;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * 真清屏：TamboUI 0.4.0 未公开清屏 API，故反射进内联运行器的私有 {@code Backend} 调 {@code clear()} 并写
 * {@code ESC[3J}（抹掉回滚缓冲），再把 {@code InlineDisplay} 的相对光标记账（{@code lastCursorY} /
 * {@code currentHeight}）归零——否则下一帧渲染会光标漂移。
 *
 * <p><b>约定</b>：任何反射/IO 失败都不抛，返回 {@code false}，由调用方降级为打印一行分割线。库升级导致字段
 * 改名时，{@code ScreenCleanerTest} 的结构断言会先红灯提醒；线上则安全降级、功能不残（换会话与清屏解耦）。
 *
 * <p>必须在渲染线程（{@code runOnRenderThread}，两帧之间）调用，勿在绘制中途执行。
 */
final class ScreenCleaner {

    private ScreenCleaner() {
    }

    /** 尝试真清屏；成功返回 true，任何失败返回 false（不抛）。runner 为 null 直接返回 false。 */
    static boolean clear(InlineToolkitRunner runner) {
        if (runner == null) {
            return false;
        }
        try {
            Object tui = runner.tuiRunner();                     // dev.tamboui.tui.InlineTuiRunner
            // 先完成所有反射解析（无副作用）——任一字段改名/缺失都会在改屏之前抛出并被 catch，
            // 从而不会出现「屏已清、但光标记账没归零」的半清态（那比走分割线降级更糟：光标漂移）。
            Backend backend = (Backend) get(tui, "backend");
            Object viewport = get(tui, "viewport");              // dev.tamboui.tui.InlineViewport
            Object display = get(viewport, "display");           // dev.tamboui.inline.InlineDisplay
            Field lastCursorY = declaredField(display, "lastCursorY");
            Field currentHeight = declaredField(display, "currentHeight");

            // 反射全部成功后，才执行有副作用的清屏 + 记账归零。
            backend.clear();                                     // 清可见区
            backend.writeRaw("\033[3J\033[H");                  // 抹回滚缓冲 + 光标 home（\033 = ESC）
            backend.flush();
            lastCursorY.setInt(display, 0);
            currentHeight.setInt(display, 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | IOException e) {
            return false;   // 降级：调用方改打印分割线
        }
    }

    private static Object get(Object target, String field) throws ReflectiveOperationException {
        return declaredField(target, field).get(target);
    }

    private static Field declaredField(Object target, String field) throws NoSuchFieldException {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f;
    }
}
