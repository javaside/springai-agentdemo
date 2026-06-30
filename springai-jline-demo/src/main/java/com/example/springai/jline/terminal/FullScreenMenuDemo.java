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
 *
 * <p>演示终端"备用屏（alternate screen）"技术：{@code enter_ca_mode} 让终端切换到一块独立的
 * 屏幕缓冲区（与当前终端内容完全隔离，就像 vim/less 的全屏模式）；{@code exit_ca_mode} 退出后
 * 原有终端内容完整恢复，不会留下任何痕迹。同时演示光标显隐（{@code cursor_invisible}/
 * {@code cursor_visible}）和方向键的手动 CSI 序列解析。</p>
 *
 * <p>此类的 {@link #select} 方法也被 {@code TerminalDemoLauncher} 复用作主菜单基础设施；
 * 删除或重命名本类前须先迁移该方法。</p>
 *
 * <p>玩法：↑/↓ 移动高亮项，Enter 确认，q/Esc 取消。退出备用屏后，场景还会演示
 * {@link org.jline.terminal.Terminal#getCursorPosition} 查询光标当前行列坐标。</p>
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
        // select() 内已调用 exit_ca_mode 退出备用屏，这里清屏让后续输出从干净状态开始。
        Terminals.clear(terminal);
        if (chosen < 0) {
            terminal.writer().println("已取消选择。");
        } else {
            terminal.writer().println("你选择了：" + items.get(chosen));
            // getCursorPosition(null)：向终端发送 DSR（Device Status Report，ESC[6n），
            // 终端回应 CPR（Cursor Position Report，ESC[row;colR），JLine 解析后返回 Cursor 对象。
            // 第一个参数可传 IntConsumer 以处理解析进度；传 null 则使用默认实现。
            // 部分终端（如 dumb、某些 CI 环境）不响应 DSR，此时返回 null，调用前须做 null 检查。
            Cursor cursor = terminal.getCursorPosition(null);
            if (cursor != null) {
                terminal.writer().printf("当前光标位置: x=%d, y=%d%n", cursor.getX(), cursor.getY());
            }
        }
        terminal.writer().println("（按任意键返回菜单）");
        terminal.writer().flush();
        // 等待任意键：进入原始模式读一个字符即返回，用完立刻复原属性。
        Attributes back = terminal.enterRawMode();
        try {
            terminal.reader().read();
        } finally {
            terminal.setAttributes(back);
        }
    }

    /**
     * 在备用屏上渲染一个可上下选择的列表，返回选中下标；q/Esc 返回 -1。
     * 该方法自管理原始模式与备用屏，退出时复原。
     * 此静态方法被 TerminalDemoLauncher 复用作菜单基础设施；删除/重命名本类前须先迁移 select()。
     */
    public static int select(Terminal terminal, String title, List<String> items) throws IOException {
        MenuModel model = new MenuModel(items);
        // enterRawMode()：关闭行缓冲和回显，让方向键等控制序列能被逐字节读取，而不是被内核行编辑截获。
        Attributes prev = terminal.enterRawMode();
        // enter_ca_mode（alternate screen）：让终端切换到一块独立的屏幕缓冲区。
        // 效果：当前终端内容被"保存"，后续的清屏/绘制都在新缓冲区上进行，就像 vim/less 进入全屏模式。
        // 退出时调用 exit_ca_mode，原缓冲区内容完整恢复，菜单交互不留任何痕迹。
        terminal.puts(Capability.enter_ca_mode);
        // cursor_invisible：隐藏光标，避免光标在菜单重绘时跳动、影响视觉观感。
        // 对应的 cursor_visible 在 finally 中调用，确保退出后光标正常显示。
        terminal.puts(Capability.cursor_invisible);
        terminal.flush();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                render(terminal, title, model);
                int c = reader.read();
                // q 或单独的 Esc（50ms 内无后续字节）均视为取消。
                // peek(50) < 0：50ms 内无后续字节，说明这是单独的 Esc 按键，而不是控制序列的开头。
                if (c == 'q' || (c == 27 && reader.peek(50) < 0)) {
                    return -1;
                }
                if (c == '\r' || c == '\n') {
                    return model.selectedIndex();
                }
                // 方向键以 CSI 序列形式到来：ESC(27) → '[' → 终结字母（A=上/B=下/C=右/D=左）。
                // 逐字节手动解析：先读 '['，再读终结字节，加超时防止残缺序列卡死循环。
                // 此处不用 BindingReader/KeyMap 以直观展示原始序列结构（见 MouseInteractionDemo 的对比）。
                if (c == 27) {
                    int bracket = reader.read(100L);
                    if (bracket == '[') {
                        int finalByte = reader.read(100L);
                        if (finalByte >= 0) { // 挡掉 -1(EOF) 和 -2(READ_EXPIRED)
                            switch (KeyDecoder.arrowFromCsiFinal((char) finalByte)) {
                                case UP -> model.up();
                                case DOWN -> model.down();
                                default -> { /* 忽略其它方向键或未知字节 */ }
                            }
                        }
                    }
                }
            }
        } finally {
            // 严格按反序复原：先显示光标，再退备用屏（顺序错误会导致光标在旧缓冲区残留不可见）。
            terminal.puts(Capability.cursor_visible);
            // exit_ca_mode：退出备用屏，终端内容恢复到进入前的状态。
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
