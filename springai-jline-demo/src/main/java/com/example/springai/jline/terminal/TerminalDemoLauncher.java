package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal 组件演示入口。持有唯一的 Terminal，按 Demo 接口调度各交互场景。
 * 真实终端：全屏方向键菜单（复用 FullScreenMenuDemo）。
 * dumb 终端：退化为编号列表 + 读行选择。
 */
public final class TerminalDemoLauncher {

    public static void main(String[] args) {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            List<Demo> demos = registerDemos();
            if (Terminals.isDumb(terminal)) {
                runDumb(terminal, demos);
            } else {
                runInteractive(terminal, demos);
            }
        } catch (IOException e) {
            System.err.println("终端初始化失败: " + e.getMessage());
        }
    }

    private static List<Demo> registerDemos() {
        List<Demo> demos = new ArrayList<>();
        demos.add(new TerminalApiGuideDemo());
        demos.add(new TerminalPlaygroundDemo());
        demos.add(new InterruptibleStreamDemo());
        demos.add(new AdaptiveDashboardDemo());
        demos.add(new FullScreenMenuDemo());
        demos.add(new MouseInteractionDemo());
        return demos;
    }

    private static void runInteractive(Terminal terminal, List<Demo> demos) throws IOException {
        List<String> labels = new ArrayList<>();
        for (Demo d : demos) {
            labels.add(d.name() + " — " + d.description());
        }
        labels.add("退出");
        while (true) {
            int choice = FullScreenMenuDemo.select(terminal,
                    "JLine Terminal 演示（↑/↓ 选择，Enter 运行，q 退出）", labels);
            if (choice < 0 || choice == demos.size()) {
                Terminals.restore(terminal);
                terminal.writer().println("再见！");
                terminal.writer().flush();
                return;
            }
            Terminals.restore(terminal);
            try {
                demos.get(choice).run(terminal);
            } catch (RuntimeException | IOException e) {
                terminal.writer().println("场景运行异常: " + e.getMessage());
                terminal.writer().flush();
            } finally {
                Terminals.restore(terminal);
            }
        }
    }

    private static void runDumb(Terminal terminal, List<Demo> demos) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            terminal.writer().println("\n=== JLine Terminal 演示（dumb 模式）===");
            for (int i = 0; i < demos.size(); i++) {
                terminal.writer().printf("  %d) %s — %s%n", i + 1, demos.get(i).name(), demos.get(i).description());
            }
            terminal.writer().println("  q) 退出");
            terminal.writer().print("请输入编号: ");
            terminal.writer().flush();
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("q")) {
                terminal.writer().println("再见！");
                terminal.writer().flush();
                return;
            }
            try {
                int idx = Integer.parseInt(line.trim()) - 1;
                if (idx >= 0 && idx < demos.size()) {
                    demos.get(idx).run(terminal);
                } else {
                    terminal.writer().println("编号超出范围。");
                }
            } catch (NumberFormatException e) {
                terminal.writer().println("无效输入。");
            }
            terminal.writer().flush();
        }
    }
}
