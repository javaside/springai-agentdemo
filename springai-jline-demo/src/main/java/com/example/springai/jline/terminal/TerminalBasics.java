package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * Terminal 基础用法（先看这个）。
 *
 * <p>这是一个【读源码就能学会】的例子——没有分屏、没有样式框架。{@link #run} 里从上到下线性地
 * 把 Terminal 最常用的几件事各做一遍，每步都有注释，照着抄即可。</p>
 *
 * <p>关于「创建」与「关闭」：Terminal 由启动器统一负责，本类直接用传入的 terminal、不自行创建/关闭。
 * 启动器里的标准写法是 try-with-resources（离开 try 自动 close() 复原）：</p>
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
        return "最常用 API 的极简逐行示例";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {

        // ① 输出文本：writer() 拿到 PrintWriter，写完要 flush() 才会真正显示。
        terminal.writer().println("你好，Terminal！");
        terminal.flush();

        // ② 查询终端信息：类型、名称、尺寸、编码。
        terminal.writer().println("类型 getType()   = " + terminal.getType());
        terminal.writer().println("名称 getName()   = " + terminal.getName());
        Size size = terminal.getSize();
        terminal.writer().println("尺寸 getSize()   = " + size.getColumns() + " 列 x " + size.getRows() + " 行");
        terminal.writer().println("编码 encoding()  = " + terminal.encoding());

        // ③ 查询能力(capability)：来自 terminfo。这里问它支持多少种颜色。
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("颜色数 max_colors = " + maxColors);
        terminal.flush();

        // ④ 彩色输出：用 AttributedStringBuilder 设置样式，toAnsi(terminal) 转成 ANSI 文本。
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold())
                .append("这是一段红色加粗文字");
        terminal.writer().println(sb.toAnsi(terminal));
        terminal.flush();

        // ⑤ 清屏 + 光标回到左上角：puts(Capability) 会发送该能力对应的控制序列。
        //    要【看到】效果，必须先让屏幕上有内容（上面那几行），按键后再清——你会看到内容被抹掉、
        //    光标跳回左上角。若一上来就清空屏，屏幕本就是空的，肉眼看不出任何变化。
        pressAnyKey(terminal, "\n↑ 以上内容，按任意键演示 clear_screen 把它们清掉……");
        terminal.puts(Capability.clear_screen);
        terminal.puts(Capability.cursor_home);
        terminal.flush();
        terminal.writer().println("（屏幕已清空、光标回到左上角——这就是 clear_screen + cursor_home 的效果）");
        terminal.flush();

        // ⑥ 非阻塞读取：read(超时毫秒)。超时没输入返回 READ_EXPIRED(-2)，输入流结束返回 EOF(-1)。
        //    这是「边做事边监听按键」的基础（可中断的流式输出就靠它）。
        //    enterRawMode() 进入原始模式（逐字符、不回显、不等回车），返回旧属性，用完务必复原。
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
            terminal.setAttributes(prev);          // 复原到行模式
        }

        // ⑦ 收尾返回菜单。Terminal 的 close() 由启动器负责，本类不关它。
        pressAnyKey(terminal, "\n基础演示结束，按任意键返回菜单。");
    }

    /**
     * 等待用户按下任意一个键（无回显）。
     *
     * <p>这本身就是「原始模式读一个键」的标准写法：enterRawMode() 进入原始模式（逐字符、不回显、
     * 不等回车），它返回进入前的旧属性；读完一定要 setAttributes(prev) 复原，所以用 try/finally。</p>
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
