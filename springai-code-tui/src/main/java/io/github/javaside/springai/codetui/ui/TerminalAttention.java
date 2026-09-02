package io.github.javaside.springai.codetui.ui;

import dev.tamboui.toolkit.app.InlineToolkitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 终端注意提示：把「需要你看一眼」写进<b>终端自己的 UI</b>——tab 标题（OSC 0/2）与 bell（BEL）。
 *
 * <p><b>解决什么</b>：用户切去别的窗口干活，agent 跑完 / 弹出问询·审批时，code-tui 的 tab
 * 与普通终端毫无区别——只有切回来才发现它已经等了很久。macOS Terminal.app / iTerm2 / tmux
 * 都会为收到的 BEL 在 tab 上标记（默认一个点 / ！图标），OSC 0/2 则直接改写 tab 与窗口标题：
 * 两者合起来就是 Claude Code 那套「tab 上看得出它在等你」的机制。写进 tab 标题的内容
 * <b>只出现在多路复用器/窗口管理器给的壳上</b>，不占屏幕一行。
 *
 * <p><b>为什么必须经 pty writer 队列而不是反射直写 backend</b>（审核 M-3/P1）：
 * OSC/BEL 与模型输出共享同一个 JLine PrintWriter——pty-writer 线程卡死在 write(2) 时
 * <b>持有 PrintWriter 内部锁</b>，直写这几十字节会先阻塞在锁上（根本轮不到内核缓冲），
 * 把「输出时打字卡死」原样引回渲染线程。提示恰在「回合完成/弹审批」时触发——与输出
 * 高峰同现，不是罕见路径。改走 {@code InlineTuiRunner.submitPtyControlSequence}：
 * 小帧豁免软预算、永不阻塞调用方、与内容字节保序。被拒时静默丢弃——提示是锦上添花。
 *
 * <p><b>失败契约</b>：任何失败不抛、返回 false、静默降级。所有调用都发生在渲染线程
 * （UI 批内，两次绘制之间）。
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
     * @return 是否成功提交到 pty writer 队列；失败（runner null / 队列拒收）返回 false
     */
    static boolean alert(InlineToolkitRunner runner, String title) {
        if (runner == null) return false;
        String safe = sanitize(title);
        try {
            // 三段拼接成一个小帧一次性提交（与内容同序，不会被大块输出插队拆散）。
            runner.tuiRunner().submitPtyControlSequence(
                    "\033]0;" + safe + "\033\\"
                            + "\033]2;" + safe + "\033\\"
                            + "\007");
            return true;
        } catch (RuntimeException e) {
            log.debug("终端注意提示提交失败（已降级为无提示）", e);
            return false;
        }
    }

    /**
     * 恢复默认标题。只在「因提示而改过标题」时调用（调用方 {@code AttentionTracker} 记着状态）。
     */
    static boolean restore(InlineToolkitRunner runner, String title) {
        if (runner == null) return false;
        try {
            runner.tuiRunner().submitPtyControlSequence(
                    "\033]0;" + sanitize(title) + "\033\\"
                            + "\033]2;" + sanitize(title) + "\033\\");
            return true;
        } catch (RuntimeException e) {
            log.debug("终端标题恢复失败（已忽略）", e);
            return false;
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
