package com.example.springai.jline.terminal;

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
 * <p>按 Terminal 接口 Javadoc 的几大块组织，{@link #run} 依次调用各小方法。每块都不是“打印说明”，
 * 而是真的调用该块的 API 并让你【看到/操作】效果：交互块会提示你按键/拖动窗口/点鼠标，你操作后立刻有反应；
 * 每节之间会停下来等你按键，不会一闪而过。</p>
 *
 * <p>独立运行（自带 main，会自己创建并关闭一个真实终端）。请在真实终端里运行：</p>
 * <pre>
 * mvn -q -pl springai-jline-demo package
 * java -jar springai-jline-demo/target/springai-jline-demo-1.0.0.jar
 * </pre>
 */
public final class TerminalBasics {

    public static void main(String[] args) throws java.io.IOException {
        // 第 1 块的“创建/关闭”最直观的标准写法就在这里：try-with-resources 创建一个真实终端，
        // 用完离开 try 自动 close() 复原。下面把这个 terminal 交给 run() 演示各块用法。
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            new TerminalBasics().run(terminal);
        }
    }

    public void run(Terminal terminal) throws java.io.IOException {
        block1CreatingAndLifecycle(terminal);
        sep(terminal);
        block2InputOutput(terminal);
        sep(terminal);
        block3Capabilities(terminal);
        sep(terminal);
        block4Attributes(terminal);
        sep(terminal);
        block5Signals(terminal);
        sep(terminal);
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
        // 输入：reader() 读一行。进入原始模式逐字符读取，对可见字符【手动回显】，回车结束。
        // 这样无论终端当前处于什么模式都稳定工作，也演示了“原始模式下回显要自己做、控制键要自己处理”。
        terminal.writer().print("【reader()】请输入一行文字，回车结束：");
        terminal.flush();
        StringBuilder line = new StringBuilder();
        Attributes prev = terminal.enterRawMode();
        try {
            while (true) {
                int c = terminal.reader().read();
                if (c == -1 || c == '\r' || c == '\n') {
                    break;                                   // 回车 / 流结束 → 结束输入
                }
                if (c == 127 || c == 8) {                    // 退格 / Backspace
                    if (line.length() > 0) {
                        line.deleteCharAt(line.length() - 1);
                        terminal.writer().write("\b \b");    // 在屏幕上抹掉一个字符
                        terminal.flush();
                    }
                } else if (c >= 32) {                        // 可见字符：记录并手动回显
                    line.append((char) c);
                    terminal.writer().write(c);
                    terminal.flush();
                }
                // 其它控制字符（方向键等发出的转义序列）忽略，避免回显成乱码
            }
        } finally {
            terminal.setAttributes(prev);                    // 复原
        }
        terminal.writer().println();
        terminal.writer().println("你输入了：" + line);
        terminal.flush();
    }

    /** 第 3 块 Terminal Capabilities：把能力“看见/听见”——画颜色、看控制序列、响铃。 */
    private void block3Capabilities(Terminal terminal) {
        title(terminal, "3. Terminal Capabilities（能力）");
        // 终端“能做什么”都记录在 terminfo 数据库里，按值的类型分三类查询：
        //   getNumericCapability  —— 数值（如 max_colors：支持多少种颜色）
        //   getBooleanCapability  —— 布尔（如 auto_right_margin：是否自动折行）
        //   getStringCapability   —— 字符串（某操作对应的“控制序列”）
        terminal.writer().println("终端能力来自 terminfo，分数值/布尔/字符串三类。下面各看一个：");

        // ① 数值能力 max_colors：终端调色板能用多少种颜色（你能往 background(i) 填的 i 的上限）。
        //    不止打印数字——直接用前 8 个颜色索引画色块，数字才有意义。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("① getNumericCapability(max_colors) = " + maxColors
                + "（能用的颜色数）。下面用颜色索引 0~7 各画一块：");
        AttributedStringBuilder swatches = new AttributedStringBuilder();
        for (int i = 0; i < 8; i++) {
            swatches.style(AttributedStyle.DEFAULT.background(i)).append("   ");
        }
        terminal.writer().println(swatches.toAnsi(terminal));

        // ② 字符串能力：一段“控制序列”——发给终端让它做某操作的字节。比如“加粗”就是发 ESC[1m。
        //    关键：同一操作在不同终端类型上的字节可能不同；terminfo 按当前终端类型查出正确的那段，
        //    于是你的程序不用硬编码转义码、能跨终端工作。
        //    先把这段序列打印出来看（ESC 显示成 ^[），再【真的用它】打印一段加粗文字给你看。
        String boldSeq = terminal.getStringCapability(Capability.enter_bold_mode);
        terminal.writer().println("② getStringCapability(enter_bold_mode) = " + visible(boldSeq)
                + "  ←“加粗”对应的控制序列");
        terminal.writer().print("   用 puts 把它发出去 → ");
        terminal.puts(Capability.enter_bold_mode);          // 发“开始加粗”序列
        terminal.writer().print("这几个字真的加粗了");
        terminal.puts(Capability.exit_attribute_mode);      // 发“关闭属性”序列，恢复正常
        terminal.writer().println(" ← 已用 exit_attribute_mode 关掉加粗");

        // ③ puts(cap, 参数...)：替你“查出该能力的序列 → 填好参数 → 发给终端执行”，并返回是否支持。
        //    为什么不直接打印 getStringCapability 的串？因为带参数的能力（如 cursor_address 含 %p1/%p2
        //    占位符）必须填入行列才有效，正是 puts 在做这件事。这里用 bell（无参数）演示：发出去会响一声，
        //    效果立刻能感知、且不破坏屏幕已有内容。
        terminal.writer().println("③ 调用 puts(bell)（终端应响一声或闪一下）……");
        boolean rang = terminal.puts(Capability.bell);
        terminal.flush();
        terminal.writer().println(rang ? "   puts 返回 true：已发送 bell 序列" : "   puts 返回 false：本终端不支持 bell");
        terminal.flush();
    }

    /** 把控制序列里的不可见字符显示出来：ESC → ^[，其它控制符 → ^X。 */
    private static String visible(String s) {
        if (s == null) {
            return "null（本终端不支持该能力）";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 27) {
                b.append("^[");
            } else if (c < 32) {
                b.append('^').append((char) (c + 64));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** 第 4 块 Terminal Attributes：进入原始模式，等你按一个键，看“无回显、不等回车”的效果。 */
    private void block4Attributes(Terminal terminal) throws java.io.IOException {
        title(terminal, "4. Terminal Attributes（属性/模式）");
        // 默认是“行模式”（要回车、有回显）。enterRawMode() 进入“原始模式”：逐字符、不回显、不等回车，
        // 返回旧属性；用完务必 setAttributes(prev) 复原（try/finally）。
        terminal.writer().print("请按一个键（原始模式下会立刻读到、且看不到回显）……");
        terminal.flush();
        Attributes prev = terminal.enterRawMode();
        try {
            int ch = terminal.reader().read();   // 阻塞等你真的按下（不再用超时一闪而过）
            if (ch == NonBlockingReader.EOF) {
                terminal.writer().println("\n（没有输入 / EOF）");
            } else {
                terminal.writer().println("\n你按了：'" + visibleChar(ch) + "'（码值 " + ch + "）"
                        + " —— 看到了吗？没有回显、也没等回车，按下立刻就读到了。");
            }
            terminal.flush();
        } finally {
            terminal.setAttributes(prev);   // 复原回行模式
        }
    }

    /** 第 5 块 Signal Handling：注册 WINCH 处理器，提示你拖动改变窗口大小，由你触发、当场看到处理器打印新尺寸。 */
    private void block5Signals(Terminal terminal) throws java.io.IOException {
        title(terminal, "5. Signal Handling（信号）");
        // handle(Signal, handler)：注册信号处理器，返回旧 handler 便于复原。
        // 这里用 WINCH（窗口大小变化）演示——你拖动改变终端窗口大小，终端就发 WINCH 信号，处理器被调用、打印新尺寸。
        // （另一个常见信号是 INT：用户按 Ctrl+C 触发。这里选 WINCH 是因为它最容易由你亲手触发、最直观，
        //   而且 WINCH 在任何终端模式下都能收到，不受下面 enterRawMode 影响。）
        Terminal.SignalHandler old = terminal.handle(Terminal.Signal.WINCH, sig -> {
            Size s = terminal.getSize();
            terminal.writer().println("  → [handler] 收到 " + sig + " 信号！窗口新尺寸 = "
                    + s.getColumns() + " 列 x " + s.getRows() + " 行");
            terminal.flush();
        });
        // 进入无回显模式：避免你等待时按键回显成乱码。WINCH 不受此影响，仍会照常触发。
        Attributes prev = terminal.enterRawMode();
        try {
            terminal.writer().println("已注册 WINCH 处理器。现在【拖动改变终端窗口大小】试试（可多拖几次）；按任意键继续……");
            terminal.flush();
            // 一直轮询等待：你每改一次窗口大小，上面的 handler 就打印一次新尺寸；按任意键则退出本节。
            while (terminal.reader().read(200L) == NonBlockingReader.READ_EXPIRED) {
                // 超时即继续轮询，给 WINCH 留出触发时间；读到任意按键/EOF 则跳出。
            }
        } finally {
            terminal.handle(Terminal.Signal.WINCH, old != null ? old : Terminal.SignalHandler.SIG_DFL);
            terminal.setAttributes(prev);   // 复原
        }
    }

    /** 第 6 块 Mouse Support：开启鼠标跟踪后，循环显示你每一次点击的坐标，按 q 结束。 */
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
        // trackMouse(Normal) 返回是否成功开启鼠标跟踪：true 才会有鼠标事件发来。打印出来便于排查。
        boolean tracking = terminal.trackMouse(Terminal.MouseTracking.Normal);
        try {
            terminal.writer().println("trackMouse(Normal) = " + tracking
                    + "。现在用鼠标在窗口里【点几下】，每点一下显示一行坐标；按 q 结束……");
            terminal.flush();
            BindingReader br = new BindingReader(terminal.reader());
            KeyMap<String> km = new KeyMap<>();
            km.bind("quit", "q");
            km.bind("mouse", key(terminal, Capability.key_mouse)); // 鼠标事件的输入序列前缀
            km.setNomatch("other");
            while (true) {
                String op = br.readBinding(km);
                if ("quit".equals(op)) {
                    break;
                }
                if ("mouse".equals(op)) {
                    MouseEvent e = terminal.readMouseEvent();        // 解析出鼠标事件
                    terminal.writer().printf("  鼠标 %s  按钮=%s  位置=(%d,%d)%n",
                            e.getType(), e.getButton(), e.getX(), e.getY());
                    terminal.flush();
                }
            }
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

    /** 节与节之间停一下，等用户按键再继续，避免内容一闪而过。 */
    private static void sep(Terminal terminal) throws java.io.IOException {
        pressAnyKey(terminal, "\n—— 按任意键看下一节 ——");
    }

    /** 把一个字符显示成可读形式：回车/控制字符不直接打印，免得看起来像乱码。 */
    private static String visibleChar(int c) {
        if (c == '\r' || c == '\n') {
            return "回车";
        }
        if (c == 27) {
            return "^[";
        }
        if (c >= 0 && c < 32) {
            return "^" + (char) (c + 64);
        }
        return String.valueOf((char) c);
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
}
