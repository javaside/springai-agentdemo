package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * 场景 0：交互式样式调色台。
 *
 * <p>演示 JLine 的样式渲染能力：用 {@link org.jline.utils.AttributedStyle} 组合前景色、背景色、
 * 粗体/下划线/斜体，再由 {@link org.jline.utils.AttributedStringBuilder#toAnsi} 转成当前终端
 * 所需的 ANSI 转义序列并写出。每次按键都立刻重绘（先清屏、再写），产生"实时调色"效果。</p>
 *
 * <p>玩法：f 切前景色，b 切背景色，o 粗体，u 下划线，i 斜体，q 返回。</p>
 *
 * <p>关键 API：{@code terminal.enterRawMode()}、{@link org.jline.utils.NonBlockingReader#read()}、
 * {@link org.jline.utils.AttributedStyle}、{@code terminal.getNumericCapability(max_colors)}。</p>
 */
public final class TerminalPlaygroundDemo implements Demo {

    private static final int[] COLORS = {
            AttributedStyle.BLACK, AttributedStyle.RED, AttributedStyle.GREEN, AttributedStyle.YELLOW,
            AttributedStyle.BLUE, AttributedStyle.MAGENTA, AttributedStyle.CYAN, AttributedStyle.WHITE
    };
    private static final String[] COLOR_NAMES = {"黑", "红", "绿", "黄", "蓝", "品红", "青", "白"};

    @Override
    public String name() {
        return "基础能力调色台";
    }

    @Override
    public String description() {
        return "终端信息 + 实时样式切换（f/b/o/u/i）";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        if (Terminals.isDumb(terminal)) {
            printInfo(terminal);
            terminal.writer().println("[dumb 终端] 交互式调色跳过，仅打印终端信息。");
            terminal.writer().flush();
            return;
        }
        // enterRawMode()：关闭行缓冲（ICANON）和自动回显（ECHO），之后每次 read() 立即返回单个字符，
        // 无需等用户按回车——这是实时响应按键的前提。返回值 prev 是原来的 Attributes，finally 中复原。
        Attributes prev = terminal.enterRawMode();
        int fg = 1, bg = -1;
        boolean bold = false, underline = false, italic = false;
        try {
            // terminal.reader() 返回 NonBlockingReader，在原始模式下每次 read() 阻塞到下一个字符到来。
            NonBlockingReader reader = terminal.reader();
            while (true) {
                renderFrame(terminal, fg, bg, bold, underline, italic);
                int c = reader.read();
                switch (c) {
                    case 'q', 'Q' -> { return; }
                    case 'f' -> fg = (fg + 1) % COLORS.length;
                    // bg 取值 [-1, 7]：-1 表示无背景，按 (COLORS.length+1) 循环
                    case 'b' -> bg = ((bg + 2) % (COLORS.length + 1)) - 1;
                    case 'o' -> bold = !bold;
                    case 'u' -> underline = !underline;
                    case 'i' -> italic = !italic;
                    default -> { /* 忽略 */ }
                }
            }
        } finally {
            // 无论正常退出还是异常，都要：①复原终端属性（重新开启回显和行缓冲），②清屏——
            // 若不复原，退出后用户终端仍处于原始模式，所有键盘输入都不回显，令人困惑。
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void renderFrame(Terminal terminal, int fg, int bg, boolean bold, boolean underline, boolean italic) {
        // clear_screen + cursor_home：每次按键后先清屏再重绘，避免上一帧内容残留。
        // Terminals.clear() 内部调用 terminal.puts(clear_screen) + puts(cursor_home)，均为 terminfo 能力。
        Terminals.clear(terminal);
        printInfo(terminal);
        terminal.writer().println();

        // AttributedStyle 是不可变对象，每次"加属性"都返回新实例（类似 Java String）。
        // foreground()/background() 接受 AttributedStyle 中定义的颜色常量（与 terminfo ANSI 索引对应）。
        AttributedStyle style = AttributedStyle.DEFAULT.foreground(COLORS[fg]);
        if (bg >= 0) {
            style = style.background(COLORS[bg]);
        }
        if (bold) {
            style = style.bold();      // 对应 enter_bold_mode（SGR 1）
        }
        if (underline) {
            style = style.underline(); // 对应 enter_underline_mode（SGR 4）
        }
        if (italic) {
            style = style.italic();    // 对应 enter_italics_mode（SGR 3，部分终端不支持）
        }
        // toAnsi(terminal)：把 AttributedString 内嵌的样式信息转换成当前终端能识别的 ANSI 转义序列字符串。
        // 传入 terminal 是为了让 JLine 根据 terminal.getType() 决定输出哪种格式（有的终端不支持全色）。
        AttributedStringBuilder sample = new AttributedStringBuilder();
        sample.style(style).append("  示例文本 Sample 12345 你好，世界  ");
        terminal.writer().println(sample.toAnsi(terminal));
        terminal.writer().println();

        terminal.writer().printf("前景色[f]=%s  背景色[b]=%s  粗体[o]=%s  下划线[u]=%s  斜体[i]=%s%n",
                COLOR_NAMES[fg], bg < 0 ? "无" : COLOR_NAMES[bg], onOff(bold), onOff(underline), onOff(italic));
        terminal.writer().println("按 f/b/o/u/i 切换样式，q 返回菜单");
        terminal.flush();
    }

    private static String onOff(boolean v) {
        return v ? "开" : "关";
    }

    private void printInfo(Terminal terminal) {
        terminal.writer().println("=== 终端基本信息 ===");
        // getType()：返回 terminfo 数据库中的终端类型名（如 "xterm-256color"、"dumb"），
        // JLine 用它查找当前终端支持哪些能力（capabilities）。
        terminal.writer().println("类型: " + terminal.getType());
        terminal.writer().println("名称: " + terminal.getName());
        // getWidth()/getHeight()：当前终端列数/行数，来自终端实时上报（ioctl TIOCGWINSZ）。
        // 窗口大小改变后这两个值会更新；dumb 终端通常返回 0 或固定值。
        terminal.writer().println("尺寸: " + terminal.getWidth() + " x " + terminal.getHeight());
        // getNumericCapability(max_colors)：从 terminfo 查出该终端调色板大小（如 8、256）；
        // 返回 null 表示该能力未定义（终端不支持颜色），要做 null 检查再用。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("颜色数: " + (maxColors != null ? maxColors : "不支持"));
        // encoding()：终端使用的字符编码（通常 UTF-8），写入 writer() 和 output() 时都应以此编码处理。
        terminal.writer().println("编码: " + terminal.encoding());
        terminal.flush();
    }
}
