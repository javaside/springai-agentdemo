package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * CodeTuiView 的配色 / 样式集中一处。全部 {@code static final}，无行为、无状态——纯调色板。
 *
 * <p>抽出的动机：视图类里曾散落 30+ 个样式常量，改配色要在巨类里翻找。集中后一眼可览层次，
 * 且这些常量本就零耦合。{@link CodeTuiView} 用 {@code import static ...Theme.*} 引入，正文里
 * {@code DIM}/{@code HINT}/… 的写法保持不变。
 *
 * <p><b>暗色终端可读性</b>：正文/信息行避开近黑的 {@code DARK_GRAY}，改用可读灰白（indexed 248/250）；
 * {@code DARK_GRAY} 仅留给固定区的次要装饰（待办○、diff 上下文行号）。
 */
final class Theme {

    private Theme() {}

    // 配色（层次感）：用户输入=灰色次要，AI 回复=默认亮色（重点）
    static final Style DIM        = Style.create().fg(Color.DARK_GRAY);
    // 占位符/空态提示（输入框空态、状态栏空闲行）：用灰白而非近黑的 DARK_GRAY，暗色终端下更清晰可读
    static final Style HINT       = Style.create().fg(Color.indexed(250));
    // 下沉到 scrollback 的信息行（/context 统计、/help、⚙ 模型、压缩结果等）：同样避开近黑的 DIM，
    // 用可读的灰白（略深于 HINT，作为「内容而非提示」的层次）。DIM 仍留给固定区的次要元素（待办○、状态后缀）。
    static final Style INFO_LINE  = Style.create().fg(Color.indexed(248));
    static final Style USER       = Style.create().fg(Color.GRAY);
    static final Color USER_BG     = Color.indexed(238);                              // 用户消息底色=中灰
    static final Style USER_BLOCK  = Style.create().fg(Color.BRIGHT_WHITE).bg(USER_BG); // 灰底白字，仿 Claude Code
    static final Style QUEUED      = Style.create().fg(Color.GRAY).bg(Color.indexed(236)); // 排队消息：暗灰底，待发
    static final Style PICK_TITLE  = Style.create().fg(Color.indexed(215)).bold();        // 选择器标题=暖橙
    // 高亮项：<b>纯前景</b>高亮，<b>不用灰底</b>。行内菜单里带底色的高亮条在本 TUI 的 InlineDisplay 下会
    // 「后半段串到下一项」——它发 ANSI 时裁掉行尾空白，补白/清除都失效，底色无法对齐/清净（已用 pty + pyte
    // 实机复现确认）。用<b>暖橙 215（主题强调色，同 ❯/标题/边框）加粗</b>做高亮：与相邻灰色项是<b>色相</b>差异，
    // 比「亮白 vs 灰」的纯明度差更醒目；纯前景故无底色残留。
    static final Style PICK_SEL    = Style.create().fg(Color.indexed(215)).bold();          // 高亮项=暖橙加粗（无底色）
    static final Style PICK_ITEM   = Style.create().fg(Color.GRAY);                        // 普通项
    static final Style PICK_DESC   = Style.create().fg(Color.DARK_GRAY);                   // 项说明
    static final Style TOOL       = Style.create().fg(Color.LIGHT_YELLOW);   // 工具调用行：淡黄，暗色终端可读（原 DARK_GRAY 近黑看不清）
    static final Style OK         = Style.create().fg(Color.GREEN);
    static final Style FAIL       = Style.create().fg(Color.RED);
    static final Style TODO       = Style.create().fg(Color.YELLOW);
    static final Style ERROR      = Style.create().fg(Color.RED).bold();
    static final Style THINK      = Style.create().fg(Color.YELLOW);
    static final Style RUNNING    = Style.create().fg(Color.CYAN);
    static final Style SHIMMER_HI  = Style.create().fg(Color.BRIGHT_WHITE).bold();   // 状态栏波光高亮
    // 权限模式常驻标识（仅非 DEFAULT 显示，见 CodeTuiView#modeTag）。故意与状态行其余部分<b>拉开色相</b>：
    // 这一段要在你没盯着它的时候仍能被余光扫到——它说明的是「接下来的工具调用会不会问你」。
    static final Style MODE_ACCEPT = Style.create().fg(Color.indexed(215)).bold();   // 自动接受编辑=暖橙加粗（同主题强调色）
    static final Style MODE_BYPASS = Style.create().fg(Color.RED).bold();            // 跳过权限检查=红色加粗（最高警示）
    static final Style MODE_PLAN   = Style.create().fg(Color.indexed(115)).bold();   // 计划模式=冷薄荷加粗（与暖橙的「自动接受编辑」拉开色相）
    static final Style TODO_TITLE = Style.create().fg(Color.YELLOW).bold();
    static final Style TODO_RUN   = Style.create().fg(Color.LIGHT_YELLOW).bold();  // 进行中：醒目

    // 欢迎横幅：暖橙品牌色作主调，边框退到柔和陶土色让框体后退；
    // 模型名用冷薄荷（与暖橙互补，凸显「当前模型」）；快捷键名提亮、说明用可读灰（避开近黑 DARK_GRAY）。
    static final Style WELCOME_BORDER = Style.create().fg(Color.indexed(173));            // 柔和陶土边框（比标题暗，退后）
    static final Style WELCOME_STAR   = Style.create().fg(Color.indexed(215)).bold();     // ✻ 品牌星标=暖橙
    static final Style WELCOME_TITLE  = Style.create().fg(Color.LIGHT_YELLOW).bold();     // 标题=亮白加粗（最高对比）
    static final Style WELCOME_BODY   = Style.create().fg(Color.indexed(250));            // 正文=可读浅灰
    static final Style WELCOME_ACCENT = Style.create().fg(Color.indexed(115)).bold();     // 强调（模型名）=冷薄荷加粗
    static final Style WELCOME_KEY    = Style.create().fg(Color.indexed(215));            // 快捷键名=暖橙
    static final Style WELCOME_HINT   = Style.create().fg(Color.indexed(245));            // 说明/路径=中灰（可读，非近黑）

    // diff 展示（Claude Code 式）：整行底色铺满，行号列灰、加/删号亮。
    // ⚠ 背景必须用 256 色 indexed()，不能用 rgb()：目标终端（Apple Terminal 等，COLORTERM 为空）
    //   不支持 truecolor，会直接忽略 48;2;r;g;b 序列 → 底色不显示。indexed 走 48;5;N，稳定可见。
    static final Color ADD_BG = Color.indexed(22);   // 深绿底=新增
    static final Color DEL_BG = Color.indexed(52);   // 深红底=删除
    static final Style DIFF_HEADER = Style.create().fg(Color.BRIGHT_WHITE).bold();
    static final Style DIFF_NO_ADD = Style.create().fg(Color.indexed(114)).bg(ADD_BG);   // 新增行号=浅绿
    static final Style DIFF_NO_DEL = Style.create().fg(Color.indexed(210)).bg(DEL_BG);   // 删除行号=浅红
    static final Style DIFF_NO_CTX = Style.create().fg(Color.DARK_GRAY);                 // 上下文行号=暗灰
    static final Style DIFF_TRUNC  = Style.create().fg(Color.DARK_GRAY);

    /** 把一条已下沉行的类型映射到样式；{@code ASSISTANT} 返回 null（用默认色，正文最易读）。 */
    static Style styleFor(OutputLine.Kind kind) {
        return switch (kind) {
            case USER -> USER;
            case ASSISTANT -> null;          // 默认色，正文最易读
            case TOOL_START -> TOOL;
            case TOOL_OK -> OK;
            case TOOL_FAIL -> FAIL;
            case TODO -> TODO;
            case ERROR -> ERROR;
            case INFO -> INFO_LINE;   // 灰白，暗色终端可读（原 DIM=DARK_GRAY 近黑看不清）
            case SUBAGENT_START -> RUNNING;   // ▸ Task(...) 起始：青色，醒目区分委派
            case SUBAGENT_TOOL -> TOOL;       // 内部工具行：与主流工具同为淡黄
            case SUBAGENT_END -> INFO_LINE;   // 结论行：灰白
        };
    }
}
