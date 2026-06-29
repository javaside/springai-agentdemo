package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

import java.util.List;

/**
 * 场景 0（入门）：Terminal 接口讲解。
 *
 * <p>按 JLine {@code Terminal} 接口 Javadoc 的 7 大块逐节带你过一遍，每节都「讲解 + 现场调用 API
 * 看真实输出 + 给出代码片段」。先用本场景学会 Terminal 怎么用，再去看其它效果场景如何组合这些 API。</p>
 *
 * <p>7 大块（取自接口 Javadoc 的 h2 标题）：Creating Terminals / Terminal Capabilities /
 * Input and Output / Terminal Attributes / Signal Handling / Mouse Support / Lifecycle。</p>
 */
public final class TerminalApiGuideDemo implements Demo {

    /** 一节讲解：标题 + 渲染逻辑。 */
    @FunctionalInterface
    private interface Section {
        void print(Terminal terminal);
    }

    @Override
    public String name() {
        return "Terminal API 讲解（先看这个）";
    }

    @Override
    public String description() {
        return "按接口 7 大块逐节讲解 Terminal 的用法";
    }

    @Override
    public void run(Terminal terminal) throws java.io.IOException {
        List<Section> sections = List.of(
                this::sectionCreating,
                this::sectionCapabilities,
                this::sectionInputOutput,
                this::sectionAttributes,
                this::sectionSignals,
                this::sectionMouse,
                this::sectionLifecycle);

        // dumb 终端无法逐键翻页：一次性顺序打印所有节。
        if (Terminals.isDumb(terminal)) {
            for (int i = 0; i < sections.size(); i++) {
                terminal.writer().printf("%n========== 第 %d/%d 节 ==========%n", i + 1, sections.size());
                sections.get(i).print(terminal);
            }
            terminal.writer().flush();
            return;
        }

        // 真实终端：分屏逐节，按任意键翻到下一节，q 退出。
        Attributes prev = terminal.enterRawMode();
        try {
            NonBlockingReader reader = terminal.reader();
            for (int i = 0; i < sections.size(); i++) {
                Terminals.clear(terminal);
                sections.get(i).print(terminal);
                footer(terminal, i + 1, sections.size());
                int c = reader.read();
                if (c == 'q' || c == 'Q') {
                    break;
                }
            }
        } finally {
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    // ===== 各节内容 =====

    /** 1. Creating Terminals —— 如何创建 Terminal。 */
    private void sectionCreating(Terminal terminal) {
        header(terminal, "1. Creating Terminals（创建终端）");
        body(terminal,
                "Terminal 是 JLine 的核心抽象，屏蔽不同操作系统/终端类型的差异。",
                "它通常由 TerminalBuilder 用流式 API 创建；system(true) 表示绑定当前进程的真实终端。");
        live(terminal, "当前这个 Terminal 实例：");
        liveLine(terminal, "terminal.getName()", terminal.getName());
        liveLine(terminal, "terminal.getType()", terminal.getType());
        code(terminal,
                "Terminal terminal = TerminalBuilder.builder()",
                "        .system(true)   // 绑定真实终端（否则为 dumb）",
                "        .build();");
    }

    /** 2. Terminal Capabilities —— 查询 terminfo 能力。 */
    private void sectionCapabilities(Terminal terminal) {
        header(terminal, "2. Terminal Capabilities（终端能力）");
        body(terminal,
                "终端能力来自 terminfo 数据库，分三类查询：",
                "  getStringCapability  —— 字符串型（如 clear_screen 的控制序列）",
                "  getBooleanCapability —— 布尔型（如 auto_right_margin 是否自动折行）",
                "  getNumericCapability —— 数值型（如 max_colors 支持多少颜色）");
        Integer colors = terminal.getNumericCapability(Capability.max_colors);
        boolean am = terminal.getBooleanCapability(Capability.auto_right_margin);
        boolean hasClear = terminal.getStringCapability(Capability.clear_screen) != null;
        live(terminal, "现场查询本终端：");
        liveLine(terminal, "getNumericCapability(max_colors)", String.valueOf(colors));
        liveLine(terminal, "getBooleanCapability(auto_right_margin)", String.valueOf(am));
        liveLine(terminal, "getStringCapability(clear_screen) != null", String.valueOf(hasClear));
        code(terminal,
                "Integer colors = terminal.getNumericCapability(Capability.max_colors);",
                "boolean wrap  = terminal.getBooleanCapability(Capability.auto_right_margin);",
                "String  clr   = terminal.getStringCapability(Capability.clear_screen);",
                "terminal.puts(Capability.clear_screen);   // 发送该能力对应的控制序列");
    }

    /** 3. Input and Output —— 输入输出流。 */
    private void sectionInputOutput(Terminal terminal) {
        header(terminal, "3. Input and Output（输入与输出）");
        body(terminal,
                "reader()  —— NonBlockingReader，可带超时地逐字符读取输入（交互核心）",
                "writer()  —— PrintWriter，按字符编码写文本输出",
                "input()/output() —— 底层原始字节流，需要直接读写字节时用",
                "encoding() —— 终端使用的字符编码",
                "注意：写完后通常要 flush() 才会真正刷到屏幕。");
        live(terminal, "下面这行就是用 writer() 打印的；编码如下：");
        liveLine(terminal, "terminal.encoding()", String.valueOf(terminal.encoding()));
        code(terminal,
                "terminal.writer().println(\"hello\");",
                "terminal.flush();",
                "int ch = terminal.reader().read(200L); // 非阻塞，最多等 200ms");
    }

    /** 4. Terminal Attributes —— 终端属性（模式/回显）。 */
    private void sectionAttributes(Terminal terminal) {
        header(terminal, "4. Terminal Attributes（终端属性）");
        body(terminal,
                "Attributes 控制终端行为：是否回显(ECHO)、是否行缓冲规范模式(ICANON) 等。",
                "用 getAttributes()/setAttributes() 读改；enterRawMode() 一键进入原始模式",
                "（关回显、关行缓冲，逐字符即时读取）。改前务必保存旧值，用完复原。");
        Attributes attr = terminal.getAttributes();
        live(terminal, "本场景已调用 enterRawMode()，所以现在：");
        liveLine(terminal, "ECHO（回显）", String.valueOf(attr.getLocalFlag(Attributes.LocalFlag.ECHO)));
        liveLine(terminal, "ICANON（规范行模式）", String.valueOf(attr.getLocalFlag(Attributes.LocalFlag.ICANON)));
        body(terminal, "（两者为 false，正是原始模式的效果——所以你按键无需回车、也不回显。）");
        code(terminal,
                "Attributes prev = terminal.enterRawMode(); // 返回旧属性",
                "try {",
                "    int ch = terminal.reader().read();     // 逐字符、无回显",
                "} finally {",
                "    terminal.setAttributes(prev);          // 复原",
                "}");
    }

    /** 5. Signal Handling —— 信号处理。 */
    private void sectionSignals(Terminal terminal) {
        header(terminal, "5. Signal Handling（信号处理）");
        body(terminal,
                "终端能处理信号：INT(Ctrl+C)、QUIT(Ctrl+\\)、WINCH(窗口大小变化) 等。",
                "用 handle(Signal, handler) 注册；handle 会返回旧 handler，便于用完复原。",
                "raise(Signal) 可主动触发一次（下面就用它现场演示，不影响进程）。");
        // 现场演示：临时注册 INT handler，raise 一次，看它被调用，然后复原。
        Terminal.SignalHandler old = terminal.handle(Terminal.Signal.INT, sig ->
                liveLine(terminal, "[handler 被调用] 收到信号", String.valueOf(sig)));
        live(terminal, "调用 terminal.raise(Signal.INT) 主动触发：");
        terminal.raise(Terminal.Signal.INT);
        terminal.handle(Terminal.Signal.INT, old != null ? old : Terminal.SignalHandler.SIG_DFL);
        code(terminal,
                "Terminal.SignalHandler old = terminal.handle(Signal.INT, sig -> {",
                "    terminal.writer().println(\"\\nInterrupted!\");",
                "    terminal.flush();",
                "});",
                "// ... 用完复原：terminal.handle(Signal.INT, old);");
    }

    /** 6. Mouse Support —— 鼠标支持。 */
    private void sectionMouse(Terminal terminal) {
        header(terminal, "6. Mouse Support（鼠标支持）");
        body(terminal,
                "部分终端支持鼠标。先用 hasMouseSupport() 判断；",
                "用 trackMouse(MouseTracking.Normal) 开启跟踪；",
                "再用 readMouseEvent() 读取点击/滚轮事件（含坐标、按钮、类型）。",
                "用完记得 trackMouse(Off) 关闭。");
        live(terminal, "本终端是否支持鼠标：");
        liveLine(terminal, "terminal.hasMouseSupport()", String.valueOf(terminal.hasMouseSupport()));
        code(terminal,
                "if (terminal.hasMouseSupport()) {",
                "    terminal.trackMouse(MouseTracking.Normal);",
                "    MouseEvent e = terminal.readMouseEvent(); // e.getX()/getY()/getType()",
                "    terminal.trackMouse(MouseTracking.Off);",
                "}");
    }

    /** 7. Lifecycle —— 生命周期。 */
    private void sectionLifecycle(Terminal terminal) {
        header(terminal, "7. Lifecycle（生命周期）");
        body(terminal,
                "Terminal 用完必须 close()，以恢复终端的原始状态；否则可能把终端留在异常状态。",
                "最佳实践是 try-with-resources（Terminal 实现了 Closeable）自动关闭。",
                "本演示由启动器统一创建并持有一个 Terminal，各场景共用，所以场景内部绝不 close 它。");
        code(terminal,
                "try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {",
                "    // ... 使用 terminal ...",
                "} // 离开 try 自动 close()，恢复终端原始状态");
        body(terminal, "讲解到此结束，按任意键返回菜单。");
    }

    // ===== 渲染辅助 =====

    private static void header(Terminal terminal, String title) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
                .append("=== ").append(title).append(" ===");
        terminal.writer().println(b.toAnsi(terminal));
        terminal.writer().println();
    }

    private static void body(Terminal terminal, String... lines) {
        for (String line : lines) {
            terminal.writer().println(line);
        }
        terminal.writer().println();
    }

    private static void live(Terminal terminal, String label) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)).append("▶ ").append(label);
        terminal.writer().println(b.toAnsi(terminal));
    }

    private static void liveLine(Terminal terminal, String expr, String value) {
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)).append("    " + expr + " = ");
        b.style(AttributedStyle.DEFAULT.bold()).append(value);
        terminal.writer().println(b.toAnsi(terminal));
        terminal.writer().flush();
    }

    private static void code(Terminal terminal, String... lines) {
        terminal.writer().println();
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE)).append("  // 代码示例");
        terminal.writer().println(b.toAnsi(terminal));
        for (String line : lines) {
            AttributedStringBuilder cb = new AttributedStringBuilder();
            cb.style(AttributedStyle.DEFAULT.faint()).append("    " + line);
            terminal.writer().println(cb.toAnsi(terminal));
        }
        terminal.writer().flush();
    }

    private static void footer(Terminal terminal, int index, int total) {
        terminal.writer().println();
        AttributedStringBuilder b = new AttributedStringBuilder();
        b.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE))
                .append(String.format(" 第 %d/%d 节 — 按任意键继续，q 退出 ", index, total));
        terminal.writer().println(b.toAnsi(terminal));
        terminal.writer().flush();
    }
}
