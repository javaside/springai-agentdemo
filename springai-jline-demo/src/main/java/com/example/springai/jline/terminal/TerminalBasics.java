package com.example.springai.jline.terminal;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * Terminal 入门：最朴素的用法示例。
 *
 * <p>这是一个【读源码就能学会】的单文件例子——没有分屏、没有样式框架、没有抽象。
 * 从上到下线性地把 Terminal 最常用的几件事各做一遍，每步都有注释，照着抄即可。</p>
 *
 * <p>它自带 main()，独立运行（不走菜单启动器）。请在真实终端里运行：</p>
 * <pre>
 * mvn -q -pl springai-jline-demo package
 * java -cp "springai-jline-demo/target/springai-jline-demo-1.0.0.jar:springai-jline-demo/target/lib/*" \
 *      com.example.springai.jline.terminal.TerminalBasics
 * </pre>
 *
 * <p>信号(handle)、鼠标(trackMouse)等更进阶用法，见同包的效果场景
 * {@code InterruptibleStreamDemo} 与 {@code MouseInteractionDemo}。</p>
 */
public final class TerminalBasics {

    public static void main(String[] args) throws Exception {

        // ① 创建终端。
        //    用 try-with-resources：离开 try 会自动 close()，恢复终端原始状态。
        //    system(true) = 绑定当前进程的真实终端（在 IDE/管道里则退化为 "dumb"）。
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {

            // ② 输出文本：writer() 拿到 PrintWriter，写完要 flush() 才会真正显示。
            terminal.writer().println("你好，Terminal！");
            terminal.flush();

            // ③ 查询终端信息：类型、名称、尺寸、编码。
            terminal.writer().println("类型 getType()   = " + terminal.getType());
            terminal.writer().println("名称 getName()   = " + terminal.getName());
            Size size = terminal.getSize();
            terminal.writer().println("尺寸 getSize()   = " + size.getColumns() + " 列 x " + size.getRows() + " 行");
            terminal.writer().println("编码 encoding()  = " + terminal.encoding());

            // ④ 查询能力(capability)：来自 terminfo。这里问它支持多少种颜色。
            Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
            terminal.writer().println("颜色数 max_colors = " + maxColors);
            terminal.flush();

            // ⑤ 彩色输出：用 AttributedStringBuilder 设置样式，toAnsi(terminal) 转成 ANSI 文本。
            AttributedStringBuilder sb = new AttributedStringBuilder();
            sb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold())
                    .append("这是一段红色加粗文字");
            terminal.writer().println(sb.toAnsi(terminal));
            terminal.flush();

            // ⑥ 用能力发送控制序列：清屏 + 光标回到左上角。
            //    puts(Capability) 会把该能力对应的转义序列写到终端。
            terminal.puts(Capability.clear_screen);
            terminal.puts(Capability.cursor_home);
            terminal.flush();
            terminal.writer().println("（屏幕已清空）");
            terminal.flush();

            // ⑦ 读取一个按键：默认终端是「行模式」(要回车、且回显)。
            //    enterRawMode() 进入「原始模式」：逐字符、不回显、不等回车。
            //    它返回进入前的旧属性，用完一定要 setAttributes(prev) 复原。
            terminal.writer().println("请按任意一个键……");
            terminal.flush();
            Attributes prev = terminal.enterRawMode();
            try {
                int ch = terminal.reader().read();         // 阻塞，读到一个字符就返回
                terminal.writer().println("你按下了：'" + (char) ch + "' (ASCII " + ch + ")");
                terminal.flush();
            } finally {
                terminal.setAttributes(prev);              // 复原到行模式
            }

            // ⑧ 非阻塞读取：read(超时毫秒)。超时没输入会返回 NonBlockingReader.READ_EXPIRED(-2)。
            //    这是「边做事边监听按键」的基础（流式输出可中断就是靠它）。
            terminal.writer().println("2 秒内按键试试（不按则超时）……");
            terminal.flush();
            Attributes prev2 = terminal.enterRawMode();
            try {
                int ch = terminal.reader().read(2000L);
                if (ch == NonBlockingReader.READ_EXPIRED) {
                    terminal.writer().println("超时，没等到按键。");
                } else if (ch == NonBlockingReader.EOF) {        // -1：输入流结束
                    terminal.writer().println("输入已结束(EOF)。");
                } else {
                    terminal.writer().println("收到：'" + (char) ch + "'");
                }
                terminal.flush();
            } finally {
                terminal.setAttributes(prev2);
            }

            terminal.writer().println("演示结束，终端将自动 close() 复原。");
            terminal.flush();

        } // ⑨ 这里自动调用 terminal.close()，恢复终端原始状态。
    }

    private TerminalBasics() {
    }
}
