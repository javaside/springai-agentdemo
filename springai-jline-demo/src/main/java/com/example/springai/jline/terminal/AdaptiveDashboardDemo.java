package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Status;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景 2：自适应窗口仪表盘。
 *
 * <p>演示终端窗口大小自适应的完整流程：用 {@code Signal.WINCH} 监听窗口尺寸变化事件，
 * 通过 {@link org.jline.terminal.Terminal#getSize()} 获取新的行列数，再用 terminfo 能力
 * {@code clear_screen} 清屏并用 {@code cursor_address(row, col)} 将每行内容绝对定位到
 * 窗口中央重绘——无论终端窗口如何缩放，仪表盘始终居中显示。</p>
 *
 * <p>额外演示 JLine 的 {@link Status} 状态栏：它利用终端底部固定行（依赖 terminfo 的
 * {@code status_line} 能力或软状态栏实现）展示持久信息，与主内容互不干扰。</p>
 *
 * <p>玩法：拖动终端窗口边界改变大小，可见仪表盘自动居中重绘；窗口右下角显示实时尺寸；q 退出。</p>
 */
public final class AdaptiveDashboardDemo implements Demo {

    @Override
    public String name() {
        return "自适应窗口仪表盘";
    }

    @Override
    public String description() {
        return "WINCH 监听 + 绝对定位居中 + 状态栏";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] resize/绝对定位需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        // enterRawMode()：关闭行缓冲和回显，让 read(timeout) 可以做非阻塞轮询，支持"随时按 q 退出"。
        Attributes prev = terminal.enterRawMode();
        // dirty 标志：初始为 true，表示首次进入就需要绘制。
        // WINCH 处理器（跨线程）把它设为 true，主循环检测到后安排重绘。AtomicBoolean 保证可见性。
        AtomicBoolean dirty = new AtomicBoolean(true);
        Terminal.SignalHandler oldWinch = null;
        Status status = null;
        try {
            // handle(Signal.WINCH, ...)：注册窗口大小改变的信号处理器。
            // 用户拖拽终端窗口边界时，OS 向进程发送 SIGWINCH，JLine 将其路由到这个 lambda。
            // 处理器只做最轻量的操作（设 dirty flag），真正的 getSize()/重绘留给主线程，避免竞态。
            oldWinch = terminal.handle(Terminal.Signal.WINCH, sig -> dirty.set(true));
            // Status.getStatus(terminal, false)：获取（如终端支持则创建）JLine 状态栏对象。
            // 第二个参数 false = 不强制创建（若终端不支持则返回 null）；调用前务必做 null 检查。
            status = Status.getStatus(terminal, false);
            NonBlockingReader reader = terminal.reader();
            int tokens = 0;
            while (true) {
                // getAndSet(false)：原子地读旧值并置 false，防止在绘制过程中漏掉 WINCH 信号。
                if (dirty.getAndSet(false)) {
                    draw(terminal, status, tokens);
                }
                // read(200L)：非阻塞，最多等 200ms。
                // READ_EXPIRED(-2) 表示超时无输入——利用这个超时当"定时器"推进 token 计数并触发重绘。
                int c = reader.read(200L);
                if (c == 'q' || c == 'Q') {
                    return;
                }
                if (c == NonBlockingReader.READ_EXPIRED) {
                    tokens += 7; // 模拟 token 增长（仅在无按键超时时推进）
                    dirty.set(true);
                }
            }
        } finally {
            // 退出前清空状态栏（传空列表），否则底部蓝条会残留在后续场景中。
            if (status != null) {
                status.update(List.of());
            }
            // 复原 WINCH 处理器、终端属性、并清屏，确保场景退出后终端处于干净状态。
            terminal.handle(Terminal.Signal.WINCH, oldWinch != null ? oldWinch : Terminal.SignalHandler.SIG_DFL);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void draw(Terminal terminal, Status status, int tokens) {
        // getSize()：向终端查询当前行列数（ioctl TIOCGWINSZ）。WINCH 信号触发后调用，
        // 可获取已更新的新尺寸；比缓存 width/height 更准确。
        Size size = terminal.getSize();
        int cols = size.getColumns();
        int rows = size.getRows();
        // clear_screen：终端能力，发出清屏序列（通常是 ESC[2J），抹掉上一帧所有内容。
        // 搭配 cursor_address 绝对定位，是"全量重绘"策略的标准做法（适合内容少、刷新不频繁的场景）。
        terminal.puts(Capability.clear_screen);

        List<String> lines = List.of(
                "┌─────────── AI 智能体仪表盘 ───────────┐",
                "│ 模型:   claude-opus-4-8                │",
                String.format("│ 已用 token: %-6d                    │", tokens),
                "│ 进度:   " + bar(20, (tokens % 200) / 200.0) + " │",
                "└──────────────────────────────────────┘");
        int boxWidth = lines.get(0).length();
        // DashboardLayout.centerStart：纯逻辑计算，返回居中起始坐标 = max(0, (total-content)/2)。
        int top = DashboardLayout.centerStart(rows, lines.size());
        int left = DashboardLayout.centerStart(cols, boxWidth);
        for (int i = 0; i < lines.size(); i++) {
            // cursor_address(row, col)：移动光标到指定行列（0-based），对应 terminfo 的 cup 能力。
            // 与 System.out.println 的顺序流式输出不同，这里可以把每行内容放到屏幕任意位置，
            // 实现真正的"绝对定位"渲染——不依赖当前光标位置，也不受已有内容影响。
            terminal.puts(Capability.cursor_address, top + i, left);
            AttributedStringBuilder b = new AttributedStringBuilder();
            b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)).append(lines.get(i));
            terminal.writer().print(b.toAnsi(terminal));
        }
        terminal.flush();

        if (status != null) {
            // Status.update(List<AttributedString>)：更新状态栏内容。
            // JLine 的 Status 会把这些 AttributedString 渲染到终端底部固定行，
            // 与主内容区域分离——主内容滚动时状态栏不受影响，类似 tmux 的 status-bar。
            AttributedString bar = new AttributedStringBuilder()
                    .style(AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE))
                    .append(String.format(" 窗口: %d x %d  |  按 q 退出 ", cols, rows))
                    .toAttributedString();
            status.update(List.of(bar));
        }
    }

    private static String bar(int width, double progress) {
        int filled = DashboardLayout.filledCells(width, progress);
        return "█".repeat(filled) + "░".repeat(width - filled);
    }
}
