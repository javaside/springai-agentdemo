package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;

import java.nio.charset.StandardCharsets;

import static org.jline.keymap.KeyMap.key;

/**
 * 场景 4：鼠标点选。
 * 演示：trackMouse/readMouseEvent、output() 原始字节流、鼠标按键/滚轮事件。
 * 鼠标点击显示坐标与事件类型；滚轮上下改变计数；q 退出。
 */
public final class MouseInteractionDemo implements Demo {

    @Override
    public String name() {
        return "鼠标点选";
    }

    @Override
    public String description() {
        return "鼠标点击/滚轮事件 + 原始字节流输出";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal) || !terminal.hasMouseSupport()) {
            terminal.writer().println("[当前终端不支持鼠标] 跳过该场景。");
            terminal.writer().flush();
            return;
        }
        Attributes prev = terminal.enterRawMode();
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            // 演示 output()：直接向底层字节流写一行 ANSI（绿色），对比 writer()
            byte[] banner = "\033[32m=== 鼠标点选演示：点击或滚动，按 q 退出 ===\033[0m\r\n"
                    .getBytes(StandardCharsets.UTF_8);
            terminal.output().write(banner);
            terminal.output().flush();

            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keyMap = new KeyMap<>();
            keyMap.bind("quit", "q");
            keyMap.bind("mouse", key(terminal, Capability.key_mouse));

            int wheelCounter = 0;
            while (true) {
                String op = bindingReader.readBinding(keyMap);
                if ("quit".equals(op)) {
                    return;
                }
                if ("mouse".equals(op)) {
                    MouseEvent event = terminal.readMouseEvent();
                    if (event.getType() == MouseEvent.Type.Wheel) {
                        wheelCounter += event.getButton() == MouseEvent.Button.WheelUp ? 1 : -1;
                        terminal.writer().printf("滚轮: %s  计数=%d%n",
                                event.getButton(), wheelCounter);
                    } else {
                        terminal.writer().printf("鼠标 %s 按钮=%s  位置=(%d,%d)%n",
                                event.getType(), event.getButton(), event.getX(), event.getY());
                    }
                    terminal.writer().flush();
                }
            }
        } finally {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }
}
