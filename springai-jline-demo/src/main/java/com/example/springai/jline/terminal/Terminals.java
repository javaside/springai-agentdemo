package com.example.springai.jline.terminal;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

/**
 * Terminal 相关的 IO 辅助：dumb 判断、capability 守卫、状态复原。
 * 所有场景退出时都应调用 restore()，保证不把用户终端遗留在异常状态。
 */
public final class Terminals {

    private Terminals() {
    }

    /** 是否为 dumb 终端（IDE 控制台 / 管道 / CI），交互能力受限。 */
    public static boolean isDumb(Terminal terminal) {
        String type = terminal.getType();
        return type == null || "dumb".equals(type);
    }

    /** 该 capability 是否可用（字符串型）。 */
    public static boolean hasCapability(Terminal terminal, Capability cap) {
        return terminal.getStringCapability(cap) != null;
    }

    /** 把终端复原到干净状态：关鼠标跟踪、退备用屏、显示光标、清屏、移到左上。 */
    public static void restore(Terminal terminal) {
        if (isDumb(terminal)) {
            return;
        }
        // 关掉鼠标跟踪：避免上一个场景残留的鼠标跟踪让后续场景把鼠标移动读成乱码、且无法选中复制。
        if (terminal.hasMouseSupport()) {
            terminal.trackMouse(Terminal.MouseTracking.Off);
        }
        terminal.puts(Capability.exit_ca_mode);
        terminal.puts(Capability.cursor_visible);
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.flush();
    }

    /** 在真实终端上清屏并把光标移到左上。 */
    public static void clear(Terminal terminal) {
        if (isDumb(terminal)) {
            return;
        }
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.flush();
    }
}
