package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import static org.jline.keymap.KeyMap.key;

/**
 * Terminal 基础用法（先看这个）——一个例子学完 Terminal 接口的全部用法。
 *
 * <p>按 Terminal 接口 Javadoc 的 7 大块组织：{@link #run} 像一份目录，依次调用 7 个小方法，
 * 每个方法只讲清楚一块、短小专注、逐行注释。读源码就能学会。</p>
 */
public final class TerminalBasics implements Demo {

    @Override
    public String name() {
        return "Terminal 基础用法（先看这个）";
    }

    @Override
    public String description() {
        return "一个例子学完 Terminal 接口 7 大块用法";
    }

    /** 目录：按接口 Javadoc 的 7 大块顺序依次演示。 */
    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        block1Creating(terminal);
        block2InputOutput(terminal);
        block3Capabilities(terminal);
        block4Attributes(terminal);
        block5Signals(terminal);
        block6Mouse(terminal);
        block7Lifecycle(terminal);
    }

    /** 第 1 块 Creating Terminals：如何创建。 */
    private void block1Creating(Terminal terminal) {
        title(terminal, "1. Creating Terminals（创建）");
        // Terminal 由 TerminalBuilder 创建，用完要 close()（见第 7 块）。标准写法是 try-with-resources：
        //     try (Terminal t = TerminalBuilder.builder().system(true).build()) { ... }
        // 本演示由启动器统一创建并传入，所以这里直接用传入的 terminal、不自行创建。
        terminal.writer().println("Terminal t = TerminalBuilder.builder().system(true).build();");
        terminal.writer().println("（创建由启动器统一负责，本场景直接使用传入的 terminal）");
        terminal.flush();
    }

    /** 第 2 块 Input and Output：输出、查信息、彩色、输入入口。 */
    private void block2InputOutput(Terminal terminal) {
        title(terminal, "2. Input and Output（输入输出）");
        // 输出：writer() 拿到 PrintWriter；写完要 flush() 才会真正显示（终端可能缓冲）。
        terminal.writer().println("你好，Terminal！");
        // 终端信息：类型 / 名称 / 尺寸 / 编码。
        terminal.writer().println("getType()  = " + terminal.getType());
        terminal.writer().println("getName()  = " + terminal.getName());
        Size size = terminal.getSize();
        terminal.writer().println("getSize()  = " + size.getColumns() + " 列 x " + size.getRows() + " 行");
        terminal.writer().println("encoding() = " + terminal.encoding());
        // 彩色也是输出：AttributedStringBuilder 设样式 → toAnsi(terminal) → writer() 打印。
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()).append("这是一段红色加粗文字");
        terminal.writer().println(sb.toAnsi(terminal));
        // 输入入口是 reader()（NonBlockingReader）；具体怎么读取决于终端模式，见第 4 块。
        terminal.flush();
    }

    /** 第 3 块 Terminal Capabilities：查 terminfo 能力，并用 puts 发送控制序列（清屏）。 */
    private void block3Capabilities(Terminal terminal) {
        title(terminal, "3. Terminal Capabilities（能力）");
        // 能力来自 terminfo，分三类查：getString / getBoolean / getNumericCapability。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("getNumericCapability(max_colors) = " + maxColors + "（dumb 终端为 null）");
        // puts(Capability) 发送该能力的控制序列，并【返回 boolean】：支持才发送并返回 true，
        // 不支持（如 dumb）则什么都不发、返回 false——这就是 dumb 下看不到清屏的原因。
        // 要【看到】清屏效果，先让屏幕有内容、停顿一下，再清。
        terminal.writer().println("（2 秒后用 clear_screen 清屏……）");
        terminal.flush();
        sleep(2000);
        boolean cleared = terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);   // clear_screen 通常已归位，这里显式保险
        terminal.flush();
        terminal.writer().println(cleared
                ? "（屏幕已清空、光标回到左上角——这就是 clear_screen 的效果）"
                : "（本终端不支持 clear_screen：puts 返回 false，未发送任何序列）");
        terminal.flush();
    }

    /** 第 4 块 Terminal Attributes：原始模式 + 读取输入。 */
    private void block4Attributes(Terminal terminal) throws java.io.IOException {
        title(terminal, "4. Terminal Attributes（属性/模式）");
        // 默认「行模式」（要回车、有回显）。enterRawMode() 进入「原始模式」：逐字符、不回显、不等回车，
        // 返回旧属性；用完务必 setAttributes(prev) 复原（try/finally）。
        // read(超时毫秒) 是非阻塞读：超时返回 READ_EXPIRED(-2)，输入流结束返回 EOF(-1)。
        terminal.writer().println("进入原始模式，2 秒内按一个键（不按则超时）……");
        terminal.flush();
        Attributes prev = terminal.enterRawMode();
        try {
            int ch = terminal.reader().read(2000L);
            if (ch == NonBlockingReader.READ_EXPIRED) {
                terminal.writer().println("超时，没等到按键。");
            } else if (ch == NonBlockingReader.EOF) {
                terminal.writer().println("输入已结束(EOF)。");
            } else {
                terminal.writer().println("收到：'" + (char) ch + "'  (ASCII " + ch + ")");
            }
            terminal.flush();
        } finally {
            terminal.setAttributes(prev);   // 复原回行模式
        }
    }

    /** 第 5 块 Signal Handling：注册信号处理器并触发。 */
    private void block5Signals(Terminal terminal) {
        title(terminal, "5. Signal Handling（信号）");
        // handle(Signal, handler) 注册处理器，返回【旧 handler】便于复原。
        // raise(Signal) 主动触发一次（真实场景：Ctrl+C 触发 INT、改变窗口大小触发 WINCH）。
        Terminal.SignalHandler oldInt = terminal.handle(Terminal.Signal.INT, sig -> {
            terminal.writer().println("  → [handler] 收到信号 " + sig);
            terminal.flush();
        });
        terminal.writer().println("已注册 INT 处理器，调用 raise(Signal.INT) 触发它：");
        terminal.flush();
        terminal.raise(Terminal.Signal.INT);   // 立刻看到上面 handler 打印的那行
        // 复原成旧 handler（没有旧的就用默认 SIG_DFL）。
        terminal.handle(Terminal.Signal.INT, oldInt != null ? oldInt : Terminal.SignalHandler.SIG_DFL);
    }

    /** 第 6 块 Mouse Support：开启鼠标跟踪并读一个鼠标事件。 */
    private void block6Mouse(Terminal terminal) throws java.io.IOException {
        title(terminal, "6. Mouse Support（鼠标）");
        terminal.writer().println("hasMouseSupport() = " + terminal.hasMouseSupport());
        terminal.flush();
        if (!terminal.hasMouseSupport()) {
            terminal.writer().println("（本终端不支持鼠标，跳过）");
            terminal.flush();
            return;
        }
        // trackMouse(Normal) 开启跟踪；输入里遇到鼠标序列时用 readMouseEvent() 解析；用完 trackMouse(Off)。
        // 用 BindingReader + KeyMap 区分「鼠标事件」与「普通按键」。
        Attributes prev = terminal.enterRawMode();
        terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            terminal.writer().println("点一下鼠标（或按任意键跳过）……");
            terminal.flush();
            BindingReader br = new BindingReader(terminal.reader());
            KeyMap<String> km = new KeyMap<>();
            km.bind("mouse", key(terminal, Capability.key_mouse));
            km.setNomatch("key");   // 非鼠标输入归为 "key"
            if ("mouse".equals(br.readBinding(km))) {
                MouseEvent e = terminal.readMouseEvent();
                terminal.writer().printf("鼠标事件：%s 按钮=%s 位置=(%d,%d)%n",
                        e.getType(), e.getButton(), e.getX(), e.getY());
            } else {
                terminal.writer().println("（按键跳过鼠标演示）");
            }
            terminal.flush();
        } finally {
            terminal.trackMouse(Terminal.MouseTracking.Off);
            terminal.setAttributes(prev);
        }
    }

    /** 第 7 块 Lifecycle：生命周期。 */
    private void block7Lifecycle(Terminal terminal) throws java.io.IOException {
        title(terminal, "7. Lifecycle（生命周期）");
        // Terminal 用完必须 close() 以恢复终端原始状态。本演示由启动器（try-with-resources）负责 close，
        // 各场景共用同一个 terminal，所以本类绝不 close 它。
        pressAnyKey(terminal, "全部演示完毕，按任意键返回菜单。");
    }

    // ===== 两个小工具 =====

    /** 打印一个分块标题（青色加粗）。 */
    private static void title(Terminal terminal, String text) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
                .append("\n===== ").append(text).append(" =====");
        terminal.writer().println(b.toAnsi(terminal));
        terminal.flush();
    }

    /** 等待按下任意一个键（无回显）：原始模式读一个键的标准写法（见第 4 块）。 */
    private static void pressAnyKey(Terminal terminal, String prompt) throws java.io.IOException {
        terminal.writer().println(prompt);
        terminal.flush();
        Attributes prev = terminal.enterRawMode();
        try {
            terminal.reader().read();
        } finally {
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
