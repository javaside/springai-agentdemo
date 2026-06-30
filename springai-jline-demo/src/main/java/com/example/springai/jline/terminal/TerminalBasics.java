package com.example.springai.jline.terminal;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Terminal 基础用法（简单示例）——每块都用真实运行的代码演示一个方面，逐行注释。
 *
 * <p>只演示稳定、跨终端都能看到效果的核心：创建/关闭、输入输出、能力、原始模式。
 * 独立运行（自带 main，自己创建并关闭一个真实终端）：</p>
 * <pre>
 * mvn -q -pl springai-jline-demo package
 * java -jar springai-jline-demo/target/springai-jline-demo-1.0.0.jar
 * </pre>
 */
public final class TerminalBasics {

    public static void main(String[] args) throws java.io.IOException {
        // 创建/关闭的标准写法：try-with-resources 建一个真实终端，离开 try 自动 close() 复原。
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            new TerminalBasics().run(terminal);
        }
    }

    public void run(Terminal terminal) throws java.io.IOException {
        block1CreatingAndLifecycle(terminal);
        block2InputOutput(terminal);
        block3Capabilities(terminal);
        block4Attributes(terminal);
        terminal.writer().println("\n演示结束。");
        terminal.flush();
    }

    /** 第 1 块 Creating & Lifecycle：真实地 建 → 用 → 关 一个 Terminal。 */
    private void block1CreatingAndLifecycle(Terminal terminal) throws java.io.IOException {
        title(terminal, "1. Creating & Lifecycle（创建 → 使用 → 关闭）");
        terminal.writer().println("用 TerminalBuilder 建一个临时 Terminal，往它写两行，再看它实际产出的内容：");
        terminal.flush();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();   // 用它“接住”临时终端的输出
        try (Terminal demo = TerminalBuilder.builder()                 // 建
                .name("临时演示终端")
                .system(false)
                .streams(new ByteArrayInputStream(new byte[0]), captured)
                .build()) {
            demo.writer().println("第一行：你好，我是被新建出来的 Terminal");   // 用：往它写
            AttributedStringBuilder color = new AttributedStringBuilder();
            color.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()).append("第二行：红色加粗字");
            demo.writer().println(color.toAnsi(demo));
            demo.writer().flush();
        }   // 关：try 结束自动 close()

        terminal.writer().println("—— 临时 Terminal 实际产出的内容 ↓ ——");
        terminal.writer().print(captured.toString(terminal.encoding()));   // 含 ANSI，红色会真显示成红色
        terminal.writer().println("—— 上面内容由它产出，它已 close() ——");
        terminal.flush();
    }

    /** 第 2 块 Input and Output：输出、查编码、读一行你的输入。 */
    private void block2InputOutput(Terminal terminal) throws java.io.IOException {
        title(terminal, "2. Input and Output（输入输出）");
        // writer()：高层文本输出，写完 flush() 才显示。
        terminal.writer().println("【writer()】这行是 writer() 打印的");
        terminal.flush();
        // output()：底层字节流，直接写字节（这里写一段 ANSI 绿色）。
        terminal.output().write("\033[32m【output()】这行是底层字节流 output() 写的（绿色）\033[0m\r\n"
                .getBytes(terminal.encoding()));
        terminal.output().flush();
        // encoding()：终端字符编码。
        terminal.writer().println("【encoding()】= " + terminal.encoding());

        // reader()：读一行你的输入。进入原始模式逐字符读、手动回显、回车结束（无论当前模式都稳定）。
        terminal.writer().print("【reader()】请输入一行文字，回车结束：");
        terminal.flush();
        StringBuilder line = new StringBuilder();
        Attributes prev = terminal.enterRawMode();
        try {
            while (true) {
                int c = terminal.reader().read();
                if (c == -1 || c == '\r' || c == '\n') {
                    break;                              // 回车 / 流结束 → 结束
                }
                if (c == 127 || c == 8) {               // 退格
                    if (line.length() > 0) {
                        line.deleteCharAt(line.length() - 1);
                        terminal.writer().write("\b \b");
                        terminal.flush();
                    }
                } else if (c >= 32) {                   // 可见字符：记录并手动回显
                    line.append((char) c);
                    terminal.writer().write(c);
                    terminal.flush();
                }
            }
        } finally {
            terminal.setAttributes(prev);
        }
        terminal.writer().println("\n你输入了：" + line);
        terminal.flush();
    }

    /** 第 3 块 Terminal Capabilities：用颜色和加粗把“能力”看得见。 */
    private void block3Capabilities(Terminal terminal) {
        title(terminal, "3. Terminal Capabilities（能力）");
        terminal.writer().println("终端“能做什么”记录在 terminfo，分数值/布尔/字符串三类查询。");

        // ① 数值能力 max_colors：能用多少种颜色（就是你能往 background(i) 填的 i 的上限）。用前 8 个画色块。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("① getNumericCapability(max_colors) = " + maxColors + "（能用的颜色数）：");
        AttributedStringBuilder swatches = new AttributedStringBuilder();
        for (int i = 0; i < 8; i++) {
            swatches.style(AttributedStyle.DEFAULT.background(i)).append("   ");
        }
        terminal.writer().println(swatches.toAnsi(terminal));

        // ② 字符串能力：某操作对应的“控制序列”。比如“加粗”就是发 ESC[1m。先看序列，再用 puts 真发出去看效果。
        String boldSeq = terminal.getStringCapability(Capability.enter_bold_mode);
        terminal.writer().println("② getStringCapability(enter_bold_mode) = " + visible(boldSeq) + "（“加粗”的控制序列）");
        terminal.writer().print("   用 puts 把它发出去 → ");
        terminal.puts(Capability.enter_bold_mode);          // 发“开始加粗”
        terminal.writer().print("这几个字真的加粗了");
        terminal.puts(Capability.exit_attribute_mode);      // 发“恢复正常”
        terminal.writer().println(" ← puts(enter_bold_mode) 的效果");
        terminal.flush();
    }

    /** 第 4 块 Terminal Attributes：进入原始模式，等你按一个键，看“无回显、不等回车”。 */
    private void block4Attributes(Terminal terminal) throws java.io.IOException {
        title(terminal, "4. Terminal Attributes（属性/模式）");
        // 默认“行模式”要回车、有回显。enterRawMode() 进“原始模式”：逐字符、不回显、不等回车，返回旧属性；
        // 用完 setAttributes(prev) 复原。
        terminal.writer().print("请按一个键（原始模式下立刻读到、且看不到回显）……");
        terminal.flush();
        Attributes prev = terminal.enterRawMode();
        try {
            int ch = terminal.reader().read();   // 阻塞等你按下，按一个键就返回
            if (ch == -1) {
                terminal.writer().println("\n（没有输入）");
            } else {
                terminal.writer().println("\n你按了：'" + visibleChar(ch) + "'（码值 " + ch
                        + "）—— 没有回显、也没等回车，按下立刻就读到了。");
            }
            terminal.flush();
        } finally {
            terminal.setAttributes(prev);
        }
    }

    // ===== 小工具 =====

    /** 打印分块标题（青色加粗）。 */
    private static void title(Terminal terminal, String text) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
                .append("\n===== ").append(text).append(" =====");
        terminal.writer().println(b.toAnsi(terminal));
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

    /** 把单个字符显示成可读形式（控制字符不直接打印，免得像乱码）。 */
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
}
