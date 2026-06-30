package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景 1：可中断的流式输出。
 *
 * <p>模拟大模型逐字输出的场景，并演示如何通过 JLine 信号机制让 Ctrl+C 只停止输出、而不杀进程。
 * 关键组合：① {@code Signal.INT} 注册处理器捕获 Ctrl+C；② 保留 {@code ISIG} 属性（而不用
 * {@code enterRawMode()}，后者会清除 ISIG）让内核仍把 Ctrl+C 转化为信号；③ 每个字符之间用
 * {@link NonBlockingReader#read(long)} 做非阻塞轮询，超时返回 {@link NonBlockingReader#READ_EXPIRED}
 * 时继续输出，有按键时立即停止；④ 关回显（ECHO=false）让用户输入不干扰输出画面；⑤ 输出完毕后
 * 用 {@code terminal.puts(bell)} 响铃或视觉提示。</p>
 *
 * <p>玩法：程序启动后开始逐字流式输出；在输出过程中按 Ctrl+C 可优雅停止（bell 响铃提示）；
 * 也可按任意其它键提前结束。</p>
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

        // 先用 getAttributes() 快照当前属性（而非 enterRawMode()），这样可以精细控制哪些标志要改。
        // new Attributes(prev) 拷贝一份，再对副本做修改，不影响原件。
        Attributes prev = terminal.getAttributes();
        Attributes raw = new Attributes(prev);
        // ECHO=false：关闭终端内核的自动回显。流式输出期间若用户无意敲键，字符不会混入输出画面。
        raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        // ICANON=false：关闭行缓冲（规范模式），让 read() 不必等回车即可返回。
        // 关键差异：这里 **不关 ISIG**（enterRawMode() 会清除它）。保留 ISIG 意味着内核仍把
        // Ctrl+C 转换为 SIGINT 发给进程，JLine 的 Signal.INT 处理器才能接到它——这是"优雅停止"的基础。
        raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);

        // AtomicBoolean 用于跨线程（信号处理器在独立线程执行）安全地传递"已停止"状态。
        AtomicBoolean stopped = new AtomicBoolean(false);
        Terminal.SignalHandler oldInt = null;
        try {
            terminal.setAttributes(raw);
            // handle(Signal.INT, handler)：注册 Ctrl+C 的处理器，返回旧 handler 以便 finally 中复原。
            // 处理器在信号线程执行，只做原子写入，避免复杂操作。
            oldInt = terminal.handle(Terminal.Signal.INT, sig -> stopped.set(true));
            NonBlockingReader reader = terminal.reader();
            for (int i = 0; i < TEXT.length(); i++) {
                if (stopped.get()) {
                    // bell：向终端发出响铃序列（audible bell）。大多数现代终端会发声或闪烁标题栏。
                    terminal.puts(Capability.bell);
                    terminal.writer().println("\n\n[已被 Ctrl+C 中断]");
                    break;
                }
                // read(20L)：非阻塞读取——最多等 20 毫秒。
                // 返回值含义：READ_EXPIRED(-2) = 超时无输入（继续输出下一字符）；
                //             -1(EOF) = 输入流关闭；>=0 = 读到字符（用户按了键，提前结束）。
                int key = reader.read(20L);
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
            // 末尾等待回车时，临时把 INT 恢复成系统默认（让 Ctrl+C 在此处也能正常工作），
            // 避免用户按 Ctrl+C 后 stopped 仍为 false 导致逻辑混乱。
            // finally 块仍会将 INT 正确恢复为 oldInt。
            terminal.handle(Terminal.Signal.INT, Terminal.SignalHandler.SIG_DFL);
            terminal.writer().println("（按回车返回菜单）");
            terminal.writer().flush();
            reader.read();
        } finally {
            // 无论是否异常，都必须：① 把 Signal.INT 处理器恢复为进入本场景前的 oldInt；
            // ② setAttributes(prev) 重新开启 ECHO 和 ICANON，复原终端状态。
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
