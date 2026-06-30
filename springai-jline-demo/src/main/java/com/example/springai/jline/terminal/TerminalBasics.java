package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.jline.keymap.KeyMap.key;

/**
 * Terminal 基础用法（先看这个）——每个方法都用【真实运行的代码】演示一遍。
 *
 * <p>按 Terminal 接口 Javadoc 的 7 大块组织，{@link #run} 依次调用 7 个小方法。每块都不是“打印说明”，
 * 而是真的调用该块的 API 并让你看到效果。</p>
 *
 * <p>注：本场景共用启动器创建并持有的 {@code terminal}（不可关闭它）。为了真实演示「创建」与「关闭」，
 * 第 1 块会用 {@link TerminalBuilder} 另外 build 一个临时 Terminal，第 7 块再 close 它。</p>
 */
public final class TerminalBasics implements Demo {

    @Override
    public String name() {
        return "Terminal 基础用法（先看这个）";
    }

    @Override
    public String description() {
        return "每个方法都用真实代码演示一遍";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        block1CreatingAndLifecycle(terminal);
        block2InputOutput(terminal);
        block3Capabilities(terminal);
        block4Attributes(terminal);
        block5Signals(terminal);
        block6Mouse(terminal);
        pressAnyKey(terminal, "\n全部演示完毕，按任意键返回菜单。");
    }

    /**
     * 第 1 块 Creating + 第 7 块 Lifecycle：真实演示一个 Terminal 的完整一生——建 → 用 → 关。
     *
     * <p>建：TerminalBuilder.build()；用：向它写两行（含彩色），并把它实际产出的字节打印出来证明它在工作；
     * 关：try-with-resources 离开时自动 close()。创建和关闭是同一个对象的两端，放一起演示才不脱节。</p>
     */
    private void block1CreatingAndLifecycle(Terminal terminal) throws java.io.IOException {
        title(terminal, "1. Creating & Lifecycle（创建 → 使用 → 关闭）");
        terminal.writer().println("用 TerminalBuilder 建一个临时 Terminal，往它写两行，再看它实际产出的内容：");
        terminal.flush();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();   // 用它来“接住”临时终端的输出
        try (Terminal demo = TerminalBuilder.builder()                 // 建
                .name("临时演示终端")
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), captured)
                .build()) {
            // 用：真的往这个新建的 Terminal 写东西
            demo.writer().println("第一行：你好，我是被新建出来的 Terminal");
            AttributedStringBuilder color = new AttributedStringBuilder();
            color.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()).append("第二行：红色加粗字");
            demo.writer().println(color.toAnsi(demo));
            demo.writer().flush();
        }   // 关：try 结束自动 close()（这就是 Lifecycle——释放资源、复原状态）

        // 证明它真在工作：把它产出的字节原样打印出来（含 ANSI，所以红色会真的显示成红色）。
        terminal.writer().println("—— 临时 Terminal 实际产出的内容 ↓ ——");
        terminal.writer().print(captured.toString(terminal.encoding()));
        terminal.writer().println("—— 上面内容由临时 Terminal 产出，它已 close() ——");
        terminal.flush();
    }

    /** 第 2 块 Input and Output：真实地输出、查编码、并读一行输入。 */
    private void block2InputOutput(Terminal terminal) throws java.io.IOException {
        title(terminal, "2. Input and Output（输入输出）");
        // 输出：writer() 是高层文本输出；写完 flush()（否则会被缓冲、晚于下面的 output() 显示）。
        terminal.writer().println("【writer()】这行是用 writer() 打印的");
        terminal.flush();
        // 原始字节输出：output() 是底层 OutputStream，直接写字节（这里写一段 ANSI 绿色）。
        terminal.output().write("\033[32m【output()】这行是用底层字节流 output() 写的（绿色）\033[0m\r\n"
                .getBytes(terminal.encoding()));
        terminal.output().flush();
        // 编码：encoding()。
        terminal.writer().println("【encoding()】= " + terminal.encoding());
        // 输入：reader() 真实读一行（行模式下逐字符读到回车为止）。
        terminal.writer().print("【reader()】请输入一行文字后回车：");
        terminal.flush();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = terminal.reader().read()) != -1 && c != '\n' && c != '\r') {
            line.append((char) c);
        }
        terminal.writer().println("你输入了：" + line);
        terminal.flush();
    }

    /** 第 3 块 Terminal Capabilities：真实查能力并用 puts 清屏。 */
    private void block3Capabilities(Terminal terminal) {
        title(terminal, "3. Terminal Capabilities（能力）");
        // getNumericCapability：数值能力，不支持返回 null。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("getNumericCapability(max_colors) = " + maxColors);
        // getStringCapability：字符串能力（控制序列本身）。
        String clr = terminal.getStringCapability(Capability.clear_screen);
        terminal.writer().println("getStringCapability(clear_screen) 是否存在 = " + (clr != null));
        // puts：发送该能力的控制序列，返回 boolean（不支持则返回 false 且什么都不发）。
        terminal.writer().println("（2 秒后用 puts(clear_screen) 清屏……）");
        terminal.flush();
        sleep(2000);
        boolean cleared = terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.flush();
        terminal.writer().println(cleared
                ? "puts 返回 true：屏幕已清空、光标回左上角"
                : "puts 返回 false：本终端不支持 clear_screen，未发送任何序列");
        terminal.flush();
    }

    /** 第 4 块 Terminal Attributes：真实进入原始模式并读一个键。 */
    private void block4Attributes(Terminal terminal) throws java.io.IOException {
        title(terminal, "4. Terminal Attributes（属性/模式）");
        // enterRawMode()：进入原始模式（逐字符、不回显、不等回车），返回旧属性；用完 setAttributes 复原。
        // read(超时毫秒)：非阻塞读，超时返回 READ_EXPIRED(-2)、流结束返回 EOF(-1)。
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
                terminal.writer().println("收到：'" + (char) ch + "'  (ASCII " + ch + ")  —— 注意没有回显、无需回车");
            }
            terminal.flush();
        } finally {
            terminal.setAttributes(prev);   // 复原回行模式
        }
    }

    /** 第 5 块 Signal Handling：真实注册处理器并触发。 */
    private void block5Signals(Terminal terminal) {
        title(terminal, "5. Signal Handling（信号）");
        // handle(Signal, handler)：注册处理器，返回旧 handler 便于复原。
        Terminal.SignalHandler oldInt = terminal.handle(Terminal.Signal.INT, sig -> {
            terminal.writer().println("  → [handler] 收到信号 " + sig);
            terminal.flush();
        });
        // raise(Signal)：主动触发一次（真实里：Ctrl+C 触发 INT、改窗口大小触发 WINCH）。
        terminal.writer().println("已注册 INT 处理器，调用 raise(Signal.INT) 触发它：");
        terminal.flush();
        terminal.raise(Terminal.Signal.INT);
        terminal.handle(Terminal.Signal.INT, oldInt != null ? oldInt : Terminal.SignalHandler.SIG_DFL);
    }

    /** 第 6 块 Mouse Support：真实开启鼠标跟踪并读一个鼠标事件。 */
    private void block6Mouse(Terminal terminal) throws java.io.IOException {
        title(terminal, "6. Mouse Support（鼠标）");
        terminal.writer().println("hasMouseSupport() = " + terminal.hasMouseSupport());
        terminal.flush();
        if (!terminal.hasMouseSupport()) {
            terminal.writer().println("（本终端不支持鼠标，跳过）");
            terminal.flush();
            return;
        }
        Attributes prev = terminal.enterRawMode();
        terminal.trackMouse(Terminal.MouseTracking.Normal);     // 开启鼠标跟踪
        try {
            terminal.writer().println("点一下鼠标（或按任意键跳过）……");
            terminal.flush();
            BindingReader br = new BindingReader(terminal.reader());
            KeyMap<String> km = new KeyMap<>();
            km.bind("mouse", key(terminal, Capability.key_mouse));   // 鼠标事件的输入序列
            km.setNomatch("key");
            if ("mouse".equals(br.readBinding(km))) {
                MouseEvent e = terminal.readMouseEvent();            // 解析鼠标事件
                terminal.writer().printf("鼠标事件：%s 按钮=%s 位置=(%d,%d)%n",
                        e.getType(), e.getButton(), e.getX(), e.getY());
            } else {
                terminal.writer().println("（按键跳过鼠标演示）");
            }
            terminal.flush();
        } finally {
            terminal.trackMouse(Terminal.MouseTracking.Off);        // 关闭跟踪
            terminal.setAttributes(prev);
        }
    }

    // ===== 小工具 =====

    /** 打印一个分块标题（青色加粗）。 */
    private static void title(Terminal terminal, String text) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
                .append("\n===== ").append(text).append(" =====");
        terminal.writer().println(b.toAnsi(terminal));
        terminal.flush();
    }

    /** 等待按下任意一个键（无回显）：原始模式读一个键的标准写法（同第 4 块）。 */
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
