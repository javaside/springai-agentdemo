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
 * 演示：trackMouse/readMouseEvent、点击命中按钮并高亮、滚轮计数、绝对定位渲染、output() 思路。
 * 玩法：鼠标点击彩色按钮 → 高亮并显示坐标；滚轮上下 → 改变计数；q 退出。
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

        Attributes prev = terminal.enterRawMode();
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keyMap = new KeyMap<>();
            keyMap.bind("quit", "q");
            keyMap.bind("mouse", key(terminal, Capability.key_mouse));

            int selected = -1;
            int wheel = 0;
            String lastEvent = "（等待鼠标事件…）";
            while (true) {
                render(terminal, buttons, selected, wheel, lastEvent);
                String op = bindingReader.readBinding(keyMap);
                if ("quit".equals(op)) {
                    return;
                }
                if (!"mouse".equals(op)) {
                    continue;
                }
                MouseEvent event = terminal.readMouseEvent();
                if (event.getType() == MouseEvent.Type.Wheel) {
                    wheel += event.getButton() == MouseEvent.Button.WheelUp ? 1 : -1;
                    lastEvent = String.format("滚轮 %s  计数=%d", event.getButton(), wheel);
                } else if (event.getType() == MouseEvent.Type.Pressed) {
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
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_address, 0, 0);
        terminal.writer().print("=== 鼠标点选演示：点击彩色按钮 / 滚动滚轮，按 q 退出 ===");
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            terminal.puts(Capability.cursor_address, b.row(), b.col());
            AttributedStyle style = AttributedStyle.DEFAULT
                    .foreground(AttributedStyle.WHITE).background(b.color());
            if (i == selected) {
                style = style.bold().underline();
            }
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(style).append(b.label());
            terminal.writer().print(sb.toAnsi(terminal));
        }
        terminal.puts(Capability.cursor_address, 11, 0);
        terminal.writer().print("最近事件: " + lastEvent + "                    ");
        terminal.puts(Capability.cursor_address, 12, 0);
        terminal.writer().print(String.format("当前选中: %s   滚轮计数: %d        ",
                selected < 0 ? "无" : buttons.get(selected).label().trim(), wheel));
        terminal.flush();
    }
}
