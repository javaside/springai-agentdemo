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
 * <p>【读源码就能学会】：{@link #run} 里从上到下线性地把 Terminal 接口 Javadoc 划分的 7 大块各演示一遍，
 * 每步都做真实、看得见的操作，并配逐行注释。没有分屏、没有样式框架。覆盖：</p>
 * <ol>
 *   <li>Creating Terminals（创建）—— 见下方类注释 / 启动器</li>
 *   <li>Input and Output（输入输出）—— writer/reader/encoding</li>
 *   <li>Terminal Capabilities（能力）—— getNumericCapability / puts</li>
 *   <li>Terminal Attributes（属性/模式）—— enterRawMode / setAttributes</li>
 *   <li>Signal Handling（信号）—— handle / raise</li>
 *   <li>Mouse Support（鼠标）—— hasMouseSupport / trackMouse / readMouseEvent</li>
 *   <li>Lifecycle（生命周期）—— close()</li>
 * </ol>
 *
 * <p>【创建 & 关闭】由启动器统一负责，本类直接用传入的 terminal。标准写法是 try-with-resources
 * （离开 try 自动 close() 复原），这就是第 1 块和第 7 块：</p>
 * <pre>
 * try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
 *     // ... 使用 terminal ...
 * } // 自动 close()，恢复终端原始状态
 * </pre>
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

    @Override
    public void run(Terminal terminal) throws java.io.IOException {

        // ===== 第 2 块 Input and Output：输出 =====
        // writer() 拿到 PrintWriter，写完要 flush() 才会真正显示（终端可能缓冲输出）。
        terminal.writer().println("你好，Terminal！");
        terminal.flush();

        // ===== 第 2 块 Input and Output：查询信息 =====
        terminal.writer().println("类型 getType()   = " + terminal.getType());
        terminal.writer().println("名称 getName()   = " + terminal.getName());
        Size size = terminal.getSize();                 // 可见窗口尺寸
        terminal.writer().println("尺寸 getSize()   = " + size.getColumns() + " 列 x " + size.getRows() + " 行");
        terminal.writer().println("编码 encoding()  = " + terminal.encoding());
        terminal.flush();

        // ===== 第 3 块 Terminal Capabilities：查 terminfo 能力 =====
        // 三类：getString/Boolean/NumericCapability。不支持时数值型返回 null。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("能力 max_colors  = " + maxColors + "（支持的颜色数，dumb 终端为 null）");
        terminal.flush();

        // ===== 第 2 块：彩色输出 =====
        // AttributedStringBuilder 设样式，toAnsi(terminal) 转成 ANSI 文本再用 writer() 打印。
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()).append("这是一段红色加粗文字");
        terminal.writer().println(sb.toAnsi(terminal));
        terminal.flush();

        // ===== 第 3 块：用能力发送控制序列（清屏）=====
        // puts(Capability) 发送该能力的控制序列并【返回 boolean】：支持才发送并返回 true，
        // 不支持（如 dumb）则什么都不发、返回 false。要看到效果须先让屏幕有内容、按键后再清。
        pressAnyKey(terminal, "\n↑ 以上内容，按任意键演示 clear_screen 把它们清掉……");
        boolean cleared = terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);          // clear_screen 通常已归位，这里显式保险
        terminal.flush();
        terminal.writer().println(cleared
                ? "（屏幕已清空、光标回到左上角——这就是 clear_screen 的效果）"
                : "（本终端不支持 clear_screen：puts 返回 false，未发送任何序列）");
        terminal.flush();

        // ===== 第 4 块 Terminal Attributes + 第 2 块非阻塞读 =====
        // 默认是「行模式」（要回车、有回显）。enterRawMode() 进入「原始模式」：逐字符、不回显、不等回车，
        // 并返回旧属性；用完务必 setAttributes(prev) 复原（所以用 try/finally）。
        // reader().read(超时毫秒) 是非阻塞读：超时返回 READ_EXPIRED(-2)，输入流结束返回 EOF(-1)。
        terminal.writer().println("\n2 秒内按一个键试试（不按则超时）……");
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
            terminal.setAttributes(prev);               // 复原回行模式
        }

        // ===== 第 5 块 Signal Handling：信号处理 =====
        // handle(Signal, handler) 注册处理器，返回【旧 handler】便于用完复原。
        // raise(Signal) 主动触发一次（真实场景里：Ctrl+C 触发 INT、改变窗口大小触发 WINCH）。
        terminal.writer().println("\n注册 INT 处理器，再用 raise(Signal.INT) 主动触发它：");
        terminal.flush();
        Terminal.SignalHandler oldInt = terminal.handle(Terminal.Signal.INT, sig -> {
            terminal.writer().println("  → [handler] 收到信号 " + sig);
            terminal.flush();
        });
        terminal.raise(Terminal.Signal.INT);            // 你会立刻看到上面 handler 打印的那行
        terminal.handle(Terminal.Signal.INT, oldInt != null ? oldInt : Terminal.SignalHandler.SIG_DFL);

        // ===== 第 6 块 Mouse Support：鼠标 =====
        // 先 hasMouseSupport() 判断；trackMouse(Normal) 开启；输入里遇到鼠标序列时 readMouseEvent() 解析。
        // 用 BindingReader + KeyMap 区分「鼠标事件」与「普通按键」；用完 trackMouse(Off) 关闭。
        terminal.writer().println("\n鼠标 hasMouseSupport() = " + terminal.hasMouseSupport());
        terminal.flush();
        if (terminal.hasMouseSupport()) {
            Attributes mprev = terminal.enterRawMode();
            terminal.trackMouse(Terminal.MouseTracking.Normal);
            try {
                terminal.writer().println("点一下鼠标（或按任意键跳过）……");
                terminal.flush();
                BindingReader br = new BindingReader(terminal.reader());
                KeyMap<String> km = new KeyMap<>();
                km.bind("mouse", key(terminal, Capability.key_mouse));
                km.setNomatch("key");                   // 非鼠标输入（普通按键）归为 "key"
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
                terminal.setAttributes(mprev);
            }
        }

        // ===== 第 7 块 Lifecycle：收尾 =====
        // Terminal 的 close() 由启动器（try-with-resources）负责复原，本类不关它。
        pressAnyKey(terminal, "\n全部演示完毕，按任意键返回菜单。");
    }

    /**
     * 等待用户按下任意一个键（无回显）。
     *
     * <p>这本身就是「原始模式读一个键」的标准写法：enterRawMode() 进入原始模式（逐字符、不回显、
     * 不等回车），返回进入前的旧属性；读完一定要 setAttributes(prev) 复原，所以用 try/finally。</p>
     */
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
