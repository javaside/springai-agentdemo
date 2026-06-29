package com.example.springai.jline.terminal;

import com.example.springai.jline.Demo;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;

/**
 * 场景 0：基础能力（交互式样式调色台）。
 * 演示：终端信息 type/name/width/height/colors、encoding()、AttributedString 全样式、非阻塞按键。
 * 按键：f 切前景色，b 切背景色，o 粗体，u 下划线，i 斜体，q 返回。
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
        Attributes prev = terminal.enterRawMode();
        int fg = 1, bg = -1;
        boolean bold = false, underline = false, italic = false;
        try {
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
            terminal.setAttributes(prev);
            Terminals.clear(terminal);
        }
    }

    private void renderFrame(Terminal terminal, int fg, int bg, boolean bold, boolean underline, boolean italic) {
        Terminals.clear(terminal);
        printInfo(terminal);
        terminal.writer().println();

        AttributedStyle style = AttributedStyle.DEFAULT.foreground(COLORS[fg]);
        if (bg >= 0) {
            style = style.background(COLORS[bg]);
        }
        if (bold) {
            style = style.bold();
        }
        if (underline) {
            style = style.underline();
        }
        if (italic) {
            style = style.italic();
        }
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
        terminal.writer().println("类型: " + terminal.getType());
        terminal.writer().println("名称: " + terminal.getName());
        terminal.writer().println("尺寸: " + terminal.getWidth() + " x " + terminal.getHeight());
        Integer maxColors = terminal.getNumericCapability(Capability.max_colors);
        terminal.writer().println("颜色数: " + (maxColors != null ? maxColors : "不支持"));
        terminal.writer().println("编码: " + terminal.encoding());
        terminal.flush();
    }
}
