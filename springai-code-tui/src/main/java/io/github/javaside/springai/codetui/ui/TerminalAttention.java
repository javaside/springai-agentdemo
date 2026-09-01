package io.github.javaside.springai.codetui.ui;

import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.app.InlineToolkitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * 终端注意提示：把「需要你看一眼」写进<b>终端自己的 UI</b>——tab 标题（OSC 0/2）与 bell（BEL）。
 *
 * <p><b>解决什么</b>：用户切去别的窗口干活，agent 跑完 / 弹出问询·审批时，code-tui 的 tab
 * 与普通终端毫无区别——只有切回来才发现它已经等了很久。macOS Terminal.app / iTerm2 / tmux
 * 都会为收到的 BEL 在 tab 上标记（默认一个点 / ！图标），OSC 0/2 则直接改写 tab 与窗口标题：
 * 两者合起来就是 Claude Code 那套「tab 上看得出它在等你」的机制。写进 tab 标题的内容
 * <b>只出现在多路复用器/窗口管理器给的壳上</b>，不占屏幕一行。
 *
 * <p><b>为什么反射</b>：TamboUI 0.4.0 的 {@code InlineTuiRunner} 持有私有 {@code Backend}
 * 但不公开它；{@code Backend.writeRaw} 是 default 方法、公开可用。本项目已有同路先例
 * （{@link ScreenCleaner}），库升级改名时由 {@code TerminalAttentionTest} 的结构断言红灯提醒。
 *
 * <p><b>失败契约</b>：任何反射/IO 失败不抛、返回 false、静默降级——提示是锦上添花，绝不能
 * 拖垮主流程。所有调用都发生在渲染线程（UI 批内，两次绘制之间），与 {@code ScreenCleaner}
 * 同一条纪律：不能在绘制中途写裸转义序列。
 */
final class TerminalAttention {

    private static final Logger log = LoggerFactory.getLogger(TerminalAttention.class);

    private TerminalAttention() {
    }

    /**
     * 写终端标题（OSC 0 + OSC 2 双发）并响一声 BEL。
     *
     * <p>OSC 0 同时设置 icon 名与窗口标题（多数终端的 tab 标题取其中之一）；OSC 2 只设置
     * 窗口标题——tmux/多路复用器场景里窗口管理器认的是它，双发保证各家都能吃到。
     * BEL 跟在标题后面一并写出：终端收到后会按用户配置标记 tab（macOS Terminal.app
     * 默认画一个点）或弹通知（iTerm2 可配）。<b>响铃频率由调用方的边沿检测守着</b>，
     * 这里不自限频。
     *
     * @return 是否成功写到底层 backend；失败（runner null / 反射失败 / IO 异常）返回 false
     */
    static boolean alert(InlineToolkitRunner runner, String title) {
        Backend backend = backend(runner);
        if (backend == null) return false;
        String safe = sanitize(title);
        try {
            // OSC 序列以 BEL 终止；末尾再补一个独立 BEL 作为铃。tmux 的 passthrough 不需要
            // 额外包裹——OSC 0/2 是终端原生命令，多路复用器会按规则转发。
            backend.writeRaw("\033]0;" + safe + "\033\\");
            backend.writeRaw("\033]2;" + safe + "\033\\");
            backend.writeRaw("\007");
            backend.flush();
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("终端注意提示写入失败（已降级为无提示）", e);
            return false;
        }
    }

    /**
     * 恢复默认标题。只在「因提示而改过标题」时调用（调用方 {@code AttentionTracker} 记着状态）。
     */
    static boolean restore(InlineToolkitRunner runner, String title) {
        Backend backend = backend(runner);
        if (backend == null) return false;
        try {
            backend.writeRaw("\033]0;" + sanitize(title) + "\033\\");
            backend.writeRaw("\033]2;" + sanitize(title) + "\033\\");
            backend.flush();
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("终端标题恢复失败（已忽略）", e);
            return false;
        }
    }

    /** 反射取 InlineTuiRunner 的私有 backend；任何失败返回 null（调用方降级）。 */
    private static Backend backend(InlineToolkitRunner runner) {
        if (runner == null) return null;
        try {
            Object tui = runner.tuiRunner();
            Field f = tui.getClass().getDeclaredField("backend");
            f.setAccessible(true);
            return (Backend) f.get(tui);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** OSC 字符串里 ESC 与 BEL 会提前截断标题（用户输入可能进入模态摘要），剥掉控制字符。 */
    private static String sanitize(String title) {
        if (title == null) return "";
        StringBuilder sb = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (c >= 0x20 || c == 0x09) sb.append(c);   // 可见字符 + TAB；ESC(0x1b)/BEL(0x07) 等全部剥掉
        }
        return sb.toString();
    }
}
