package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景 1：可中断的流式输出。
 * 演示：Signal.INT（Ctrl+C 优雅停止）、非阻塞 read(timeout)、getAttributes/setAttributes、bell。
 */
public final class InterruptibleStreamDemo implements Demo {

    private static final String TEXT =
            "JLine 让 Java 程序拥有现代终端交互能力：彩色输出、行编辑、自动补全、历史记录。"
            + "这段文字正在被逐字“流式”打印，模拟大模型的输出。你可以随时按 Ctrl+C 优雅地停止，"
            + "而不会杀掉整个进程——这正是构建可中断 AI 智能体界面的关键能力。";

    @Override
    public String name() {
        return "可中断流式输出";
    }

    @Override
    public String description() {
        return "Ctrl+C 优雅停止 + 非阻塞读取 + 回显控制 + 响铃";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            terminal.writer().println("[dumb 终端] 信号/非阻塞读取需真实终端，跳过。");
            terminal.writer().flush();
            return;
        }
        terminal.writer().println("=== 可中断流式输出 ===");
        terminal.writer().println("开始流式输出，按 Ctrl+C 停止，或按任意键提前结束。\n");
        terminal.writer().flush();

        // 关回显，演示 Attributes 控制（规范模式下默认回显，这里关闭）
        Attributes prev = terminal.getAttributes();
        Attributes raw = new Attributes(prev);
        raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        // 保留 ISIG：让 Ctrl+C 仍触发 SIGINT → 由 Signal.INT handler 捕获；不可用 enterRawMode() 替代（它会清除 ISIG）
        raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);

        AtomicBoolean stopped = new AtomicBoolean(false);
        Terminal.SignalHandler oldInt = null;
        try {
            terminal.setAttributes(raw);
            oldInt = terminal.handle(Terminal.Signal.INT, sig -> stopped.set(true));
            NonBlockingReader reader = terminal.reader();
            for (int i = 0; i < TEXT.length(); i++) {
                if (stopped.get()) {
                    terminal.puts(Capability.bell); // 响铃提示被打断
                    terminal.writer().println("\n\n[已被 Ctrl+C 中断]");
                    break;
                }
                int key = reader.read(20L); // 非阻塞：最多等 20ms
                if (key != NonBlockingReader.READ_EXPIRED && key != -1) {
                    terminal.writer().println("\n\n[已被按键提前结束]");
                    break;
                }
                terminal.writer().print(TEXT.charAt(i));
                terminal.writer().flush();
                sleep(60);
            }
            if (!stopped.get()) {
                terminal.writer().println("\n\n[输出完成]");
            }
            // 末尾阻塞读取前临时恢复默认 INT，让 Ctrl+C 生效；finally 仍会把 INT 恢复成 oldInt
            terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_DFL);
            terminal.writer().println("（按回车返回菜单）");
            terminal.writer().flush();
            reader.read();
        } finally {
            terminal.handle(Terminal.Signal.INT, oldInt != null ? oldInt : Terminal.SignalHandler.SIG_DFL);
            terminal.setAttributes(prev);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
