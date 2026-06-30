package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;

import static org.jline.keymap.KeyMap.key;

/**
 * 场景 4：鼠标点选。
 *
 * <p>演示 JLine 鼠标支持的完整流程：{@link org.jline.terminal.Terminal#trackMouse} 开启鼠标跟踪后，
 * 终端将鼠标事件（按下/释放/移动/滚轮）编码成特定输入序列（X10/SGR 编码）混入标准输入流；
 * {@link org.jline.keymap.BindingReader} 配合 {@link org.jline.keymap.KeyMap} 把
 * {@code key(terminal, Capability.key_mouse)} 绑定到字符串 "mouse"，从而区分"鼠标事件"
 * 与普通按键——当 {@code readBinding} 返回 "mouse" 时，调用
 * {@link org.jline.terminal.Terminal#readMouseEvent()} 解析出
 * {@link org.jline.terminal.MouseEvent}，获取事件类型（Pressed/Released/Wheel/Move）、
 * 按钮（Button1/WheelUp/WheelDown）和坐标 (x, y)。</p>
 *
 * <p>玩法：鼠标点击彩色按钮 → 按钮高亮并显示点击坐标；滚动滚轮 → 计数增减；q 退出。</p>
 */
public final class MouseInteractionDemo implements Demo {

    /** 一个按钮：起始行/列 + 文本 + 颜色。 */
    private record Button(int row, int col, String label, int color) {
    }

    @Override
    public String name() {
        return "鼠标点选";
    }

    @Override
    public String description() {
        return "鼠标点击按钮高亮 + 滚轮计数 + 事件坐标";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal) || !terminal.hasMouseSupport()) {
            terminal.writer().println("[当前终端不支持鼠标] 跳过该场景。");
            terminal.writer().flush();
            return;
        }
        List<Button> buttons = List.of(
                new Button(4, 6, "  [ 红色按钮 ]  ", AttributedStyle.RED),
                new Button(6, 6, "  [ 绿色按钮 ]  ", AttributedStyle.GREEN),
                new Button(8, 6, "  [ 蓝色按钮 ]  ", AttributedStyle.BLUE));

        // enterRawMode()：关闭行缓冲和回显，让鼠标产生的 ESC 序列能被程序逐字节读取，
        // 而不是被内核行编辑器截断或等待回车才返回。
        Attributes prev = terminal.enterRawMode();
        // trackMouse(Normal)：向终端发送开启鼠标跟踪的控制序列（通常是 ESC[?1000h）。
        // 开启后，鼠标的按下/释放/滚轮事件被终端编码成 ESC[M 序列混入输入流。
        // MouseTracking.Normal 只报告按下/释放；Button 模式还报告移动；Any 模式报告全部移动。
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            // BindingReader 包装 NonBlockingReader，提供基于 KeyMap 的高层"绑定读取"能力。
            // 它在内部维护一个已读字节的前缀树，能正确处理多字节控制序列（如方向键、鼠标事件）。
            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keyMap = new KeyMap<>();
            keyMap.bind("quit", "q");
            // key(terminal, Capability.key_mouse)：从 terminfo 查出当前终端鼠标事件的前缀序列
            // （通常是 "\033[M" 或 SGR 格式的 "\033[<"），绑定到 "mouse" 标记。
            // 之后 readBinding 返回 "mouse" 时，说明下一段输入是鼠标事件——再调 readMouseEvent() 解析。
            keyMap.bind("mouse", key(terminal, Capability.key_mouse));

            int selected = -1;
            int wheel = 0;
            String lastEvent = "（等待鼠标事件…）";
            while (true) {
                render(terminal, buttons, selected, wheel, lastEvent);
                // readBinding(keyMap)：阻塞读取，匹配 KeyMap 中最长的前缀，返回绑定的值（"quit"/"mouse"）。
                // 若输入序列不匹配任何绑定，默认返回 null（keyMap 未设置 nomatch）——这里直接 continue 忽略。
                String op = bindingReader.readBinding(keyMap);
                if ("quit".equals(op)) {
                    return;
                }
                if (!"mouse".equals(op)) {
                    continue;
                }
                // readMouseEvent()：必须在 readBinding 返回 "mouse" 之后立即调用。
                // BindingReader 已经把鼠标序列的前缀字节消费掉，readMouseEvent() 接着读剩余字节，
                // 解析出 MouseEvent（类型、按钮、坐标）。两次调用之间不能插入其他 read，否则字节会错位。
                MouseEvent event = terminal.readMouseEvent();
                if (event.getType() == MouseEvent.Type.Wheel) {
                    wheel += event.getButton() == MouseEvent.Button.WheelUp ? 1 : -1;
                    lastEvent = String.format("滚轮 %s  计数=%d", event.getButton(), wheel);
                } else if (event.getType() == MouseEvent.Type.Pressed) {
                    // event.getX()/getY()：鼠标点击的列/行坐标（通常 0-based 或 1-based，依终端而异）。
                    // hitTest 做了 ±1 容差，以兼容不同终端的坐标基准偏差。
                    int hit = hitTest(buttons, event.getX(), event.getY());
                    if (hit >= 0) {
                        selected = hit;
                        lastEvent = String.format("点击 (%d,%d) → 命中：%s",
                                event.getX(), event.getY(), buttons.get(hit).label().trim());
                    } else {
                        lastEvent = String.format("点击 (%d,%d) → 未命中按钮", event.getX(), event.getY());
                    }
                } else {
                    lastEvent = String.format("%s (%d,%d)", event.getType(), event.getX(), event.getY());
                }
            }
        } finally {
            // 必须在 setAttributes 之前关闭鼠标跟踪：trackMouse(Off) 向终端发 ESC[?1000l，
            // 关掉后续的鼠标序列输入；若先复原属性再关鼠标，可能导致关闭序列在错误模式下发送。
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    /**
     * 命中测试：行做 ±1 容差、列在按钮文本范围内（±1），以兼容不同终端 0/1 基坐标差异。
     * 返回命中按钮下标，未命中返回 -1。
     */
    private static int hitTest(List<Button> buttons, int x, int y) {
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            boolean rowHit = Math.abs(y - b.row()) <= 1;
            boolean colHit = x >= b.col() - 1 && x <= b.col() + b.label().length();
            if (rowHit && colHit) {
                return i;
            }
        }
        return -1;
    }

    private static void render(Terminal terminal, List<Button> buttons, int selected, int wheel, String lastEvent) {
        // 每帧先清屏，再用 cursor_address 把每个元素定位到固定坐标——全量重绘策略。
        // 按钮数量少、刷新由事件驱动，因此全量重绘比差量更新简单且够用。
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_address, 0, 0);
        terminal.writer().print("=== 鼠标点选演示：点击彩色按钮 / 滚动滚轮，按 q 退出 ===");
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            // cursor_address(row, col)：把光标移到按钮预设的行列位置，之后 writer().print 从该位置输出。
            terminal.puts(Capability.cursor_address, b.row(), b.col());
            AttributedStyle style = AttributedStyle.DEFAULT
                    .foreground(AttributedStyle.WHITE).background(b.color());
            // 被选中的按钮额外加粗+下划线，给用户视觉反馈。
            if (i == selected) {
                style = style.bold().underline();
            }
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(style).append(b.label());
            terminal.writer().print(sb.toAnsi(terminal));
        }
        // 信息行也用绝对定位，避免按钮行数变化时信息位置漂移。
        terminal.puts(Capability.cursor_address, 11, 0);
        terminal.writer().print("最近事件: " + lastEvent + "                    ");
        terminal.puts(Capability.cursor_address, 12, 0);
        terminal.writer().print(String.format("当前选中: %s   滚轮计数: %d        ",
                selected < 0 ? "无" : buttons.get(selected).label().trim(), wheel));
        terminal.flush();
    }
}
