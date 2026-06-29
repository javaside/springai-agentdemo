package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.terminal.Cursor;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.List;

/**
 * 场景 3：全屏菜单选择器。
 * 演示：备用屏（enter/exit_ca_mode）、光标显隐、原始模式 + 方向键解析、getCursorPosition()。
 * 既是独立场景，也被 TerminalDemoLauncher 复用（select 方法）。
 */
public final class FullScreenMenuDemo implements Demo {

    @Override
    public String name() {
        return "全屏菜单选择器";
    }

    @Override
    public String description() {
        return "备用屏 + 方向键导航 + 光标显隐 + 光标位置查询";
    }

    @Override
    public void run(Terminal terminal) throws IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] 该场景需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        List<String> items = List.of("苹果", "香蕉", "樱桃", "返回");
        int chosen = select(terminal, "请选择一项（↑/↓ 移动，Enter 确认，q 退出）", items);
        Terminals.clear(terminal);
        if (chosen < 0) {
            terminal.writer().println("已取消选择。");
        } else {
            terminal.writer().println("你选择了：" + items.get(chosen));
            // 演示 getCursorPosition()：打印当前光标坐标
            Cursor cursor = terminal.getCursorPosition(null);
            if (cursor != null) {
                terminal.writer().printf("当前光标位置: x=%d, y=%d%n", cursor.getX(), cursor.getY());
            }
        }
        terminal.writer().println("（按回车返回菜单）");
        terminal.writer().flush();
        terminal.reader().read();
    }

    /**
     * 在备用屏上渲染一个可上下选择的列表，返回选中下标；q/Esc 返回 -1。
     * 该方法自管理原始模式与备用屏，退出时复原。
     * 此静态方法被 TerminalDemoLauncher 复用作菜单基础设施；删除/重命名本类前须先迁移 select()。
     */
    public static int select(Terminal terminal, String title, List<String> items) throws IOException {
        MenuModel model = new MenuModel(items);
        Attributes prev = terminal.enterRawMode();
        terminal.puts(Capability.enter_ca_mode);
        terminal.puts(Capability.cursor_invisible);
        terminal.flush();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                render(terminal, title, model);
                int c = reader.read();
                if (c == 'q' || (c == 27 && reader.peek(50) < 0)) { // q 或单独的 Esc（50ms 内无后续字节）
                    return -1;
                }
                if (c == '\r' || c == '\n') {
                    return model.selectedIndex();
                }
                if (c == 27) { // ESC，尝试读取 '[' 与终结字节（加超时防止残缺 CSI 卡死）
                    int bracket = reader.read(100L);
                    if (bracket == '[') {
                        int finalByte = reader.read(100L);
                        if (finalByte >= 0) { // 同时挡掉 -1(EOF)/-2(超时)
                            switch (KeyDecoder.arrowFromCsiFinal((char) finalByte)) {
                                case UP -> model.up();
                                case DOWN -> model.down();
                                default -> { /* 忽略 */ }
                            }
                        }
                    }
                }
            }
        } finally {
            terminal.puts(Capability.cursor_visible);
            terminal.puts(Capability.exit_ca_mode);
            terminal.setAttributes(prev);
            terminal.flush();
        }
    }

    private static void render(Terminal terminal, String title, MenuModel model) {
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.writer().println(title);
        terminal.writer().println();
        List<String> items = model.items();
        for (int i = 0; i < items.size(); i++) {
            AttributedStringBuilder b = new AttributedStringBuilder();
            if (i == model.selectedIndex()) {
                b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK).background(AttributedStyle.CYAN))
                        .append("  ▶ ").append(items.get(i)).append("  ");
            } else {
                b.style(AttributedStyle.DEFAULT).append("    ").append(items.get(i));
            }
            terminal.writer().println(b.toAnsi(terminal));
        }
        terminal.flush();
    }
}
