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
 * 演示：Signal.WINCH（resize）+ getSize()、clear_screen、cursor_address 绝对定位、Status 状态栏。
 * 拖动改变窗口大小时自动重绘居中；q 退出。
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
        Attributes prev = terminal.enterRawMode();
        AtomicBoolean dirty = new AtomicBoolean(true);
        Terminal.SignalHandler oldWinch = terminal.handle(Terminal.Signal.WINCH, sig -> dirty.set(true));
        Status status = Status.getStatus(terminal, false);
        try {
            NonBlockingReader reader = terminal.reader();
            int tokens = 0;
            while (true) {
                if (dirty.getAndSet(false)) {
                    draw(terminal, status, tokens);
                }
                int c = reader.read(200L);
                if (c == 'q' || c == 'Q') {
                    return;
                }
                tokens += 7; // 模拟 token 增长
                dirty.set(true);
            }
        } finally {
            if (status != null) {
                status.update(List.of()); // 清空状态栏
            }
            terminal.handle(Terminal.Signal.WINCH, oldWinch);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void draw(Terminal terminal, Status status, int tokens) {
        Size size = terminal.getSize();
        int cols = size.getColumns();
        int rows = size.getRows();
        terminal.puts(Capability.clear_screen);

        List<String> lines = List.of(
                "┌─────────── AI 智能体仪表盘 ───────────┐",
                "│ 模型:   claude-opus-4-8                │",
                String.format("│ 已用 token: %-6d                    │", tokens),
                "│ 进度:   " + bar(20, (tokens % 200) / 200.0) + " │",
                "└──────────────────────────────────────┘");
        int boxWidth = lines.get(0).length();
        int top = DashboardLayout.centerStart(rows, lines.size());
        int left = DashboardLayout.centerStart(cols, boxWidth);
        for (int i = 0; i < lines.size(); i++) {
            terminal.puts(Capability.cursor_address, top + i, left);
            AttributedStringBuilder b = new AttributedStringBuilder();
            b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)).append(lines.get(i));
            terminal.writer().print(b.toAnsi(terminal));
        }
        terminal.flush();

        if (status != null) {
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
