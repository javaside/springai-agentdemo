package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.ModelOption;
import com.example.springai.codetui.agent.SubmitHandler;
import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextAreaState;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.InlineToolkit.scope;
import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

/**
 * Claude Code 式行内视图，改用官方 <b>Toolkit 声明式 DSL</b>（{@link InlineApp} + {@code InlineToolkitRunner}）。
 *
 * <p><b>为什么是它</b>：把「固定在输入框上方、原地更新、用完即收起」的计划面板做对，关键在于
 * <em>每帧都按 {@code preferredSize} 调用 {@code setContentHeight}（可增可减）并且每个 tick 都重绘</em>。
 * Toolkit 的运行器正是这么做的：{@code InlineScopeElement} 隐藏时 {@code preferredSize=0}，
 * 于是 {@code column} 收缩 → 视口高度收缩 → 腾出的行被回收；而「每 tick 必重绘」让底层
 * {@code InlineDisplay} 的相对光标记账始终与终端实际一致，从根上规避了此前手写渲染里「跳帧 + 收缩
 * (deleteLines)」导致的光标漂移、面板消失。
 *
 * <p><b>布局（自底向上钉在终端底部，其上是 println 出来的 scrollback）</b>：
 * <pre>
 *   [流式预览]        —— AI 生成中的当前残行（未换行段），{@code scope} 空则收起
 *   [📋 计划面板]     —— 完整清单，✓/▶/○ 分色，{@code scope} 无计划则收起
 *   [圆角输入框]      —— 原生 {@code textArea}，多行/自动增高，自带光标/编辑/中文输入
 *   [状态行]
 * </pre>
 *
 * <p><b>定稿行下沉</b>：{@code pending} 与流式完整行在渲染线程（经
 * {@code scheduleRepeating → runOnRenderThread}，在两帧之间、非绘制中途执行）用 {@code println}
 * 推进 scrollback，markdown/语法高亮沿用 {@link MarkdownRenderer}。
 */
public final class CodeTuiView extends InlineApp {

    private static final int TODO_CAP = 10;      // 计划面板最多显示几条
    private static final String INDENT = "  ";  // 对话内容缩进；工具/计划行自带前缀

    // 配色（层次感）：用户输入=灰色次要，AI 回复=默认亮色（重点）
    private static final Style DIM        = Style.create().fg(Color.DARK_GRAY);
    private static final Style USER       = Style.create().fg(Color.GRAY);
    private static final Color USER_BG     = Color.indexed(238);                              // 用户消息底色=中灰
    private static final Style USER_BLOCK  = Style.create().fg(Color.BRIGHT_WHITE).bg(USER_BG); // 灰底白字，仿 Claude Code
    private static final Style QUEUED      = Style.create().fg(Color.GRAY).bg(Color.indexed(236)); // 排队消息：暗灰底，待发
    private static final Style PICK_TITLE  = Style.create().fg(Color.indexed(215)).bold();        // 选择器标题=暖橙
    private static final Style PICK_SEL    = Style.create().fg(Color.BRIGHT_WHITE).bg(Color.indexed(238)).bold(); // 高亮项=灰底白字
    private static final Style PICK_ITEM   = Style.create().fg(Color.GRAY);                        // 普通项
    private static final Style PICK_DESC   = Style.create().fg(Color.DARK_GRAY);                   // 项说明
    private static final Style TOOL       = Style.create().fg(Color.DARK_GRAY);
    private static final Style OK         = Style.create().fg(Color.GREEN);
    private static final Style FAIL       = Style.create().fg(Color.RED);
    private static final Style TODO       = Style.create().fg(Color.YELLOW);
    private static final Style ERROR      = Style.create().fg(Color.RED).bold();
    private static final Style THINK      = Style.create().fg(Color.YELLOW);
    private static final Style RUNNING    = Style.create().fg(Color.CYAN);
    private static final Style TODO_TITLE = Style.create().fg(Color.YELLOW).bold();
    private static final Style TODO_RUN   = Style.create().fg(Color.LIGHT_YELLOW).bold();  // 进行中：醒目

    // diff 展示（Claude Code 式）：整行底色铺满，行号列灰、加/删号亮。
    // ⚠ 背景必须用 256 色 indexed()，不能用 rgb()：目标终端（Apple Terminal 等，COLORTERM 为空）
    //   不支持 truecolor，会直接忽略 48;2;r;g;b 序列 → 底色不显示。indexed 走 48;5;N，稳定可见。
    private static final Color ADD_BG = Color.indexed(22);   // 深绿底=新增
    private static final Color DEL_BG = Color.indexed(52);   // 深红底=删除
    private static final Style DIFF_HEADER = Style.create().fg(Color.BRIGHT_WHITE).bold();
    private static final Style DIFF_NO_ADD = Style.create().fg(Color.indexed(114)).bg(ADD_BG);   // 新增行号=浅绿
    private static final Style DIFF_NO_DEL = Style.create().fg(Color.indexed(210)).bg(DEL_BG);   // 删除行号=浅红
    private static final Style DIFF_NO_CTX = Style.create().fg(Color.DARK_GRAY);                 // 上下文行号=暗灰
    private static final Style DIFF_TRUNC  = Style.create().fg(Color.DARK_GRAY);
    private static final int GUTTER = 4;   // 行号列宽（右对齐到 4 位，够 9999 行）

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private final MarkdownRenderer md = new MarkdownRenderer();      // AI 正文 markdown + 代码语法高亮
    private final TextAreaState inputState = new TextAreaState();    // 输入源（多行编辑模型）
    // 仅用于复用 textArea 的完整编辑键处理（退格/方向/Home/End/字符/中文…）。⚠ 从不渲染它——
    // 一旦渲染，TextAreaElement 会以自增 id 自注册进焦点链、抢走焦点，导致外层拦不到 Enter。
    private final Element inputKeys = textArea(inputState);
    private final DiffRenderer diff;                                 // edit/write → 带真实行号的 diff 行
    private final Path root;                                         // 工作区根目录（欢迎页展示）
    private Disposable current;
    private boolean pickingModel;                                    // /model 选择器是否激活
    private int pickIndex;                                           // 选择器当前高亮项

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root) {
        this.state = state;
        this.onSubmit = onSubmit;
        this.root = root;
        this.diff = new DiffRenderer(root);
    }

    /** 初始高度：圆角输入框(空态 3=边框2+1 行) + 状态行(1)；输入换行后 textArea 的 preferredSize 随行数增高，运行器逐帧跟随。 */
    @Override
    protected int height() {
        return 4;
    }

    /** 每帧构造 UI。scrollback 的 println 放在 {@link #drain} 里另行推进。 */
    @Override
    protected Element render() {
        List<String> todos = state.todoSnapshot();
        List<String> queued = state.queuedSnapshot();
        String tail = lastLine(state.streaming());   // 流式当前残行（未换行段）
        return column(
                scope(!tail.isEmpty(), richText(indented(md.renderPreview(tail))).ellipsisStart()),
                scope(!todos.isEmpty(), todoChildren(todos)),
                scope(!queued.isEmpty(), queuedChildren(queued)),   // 排队消息面板：固定显示在输入框上方
                scope(pickingModel, modelPickerChildren()),         // /model 选择器面板
                inputElement(),
                statusLine());
    }

    @Override
    protected void onStart() {
        runner().runOnRenderThread(this::printWelcome);   // 启动欢迎横幅（一次性下沉 scrollback）
        // 在渲染线程、两帧之间安全推进 scrollback（println 会移动光标/插行，绝不能在绘制中途调用）。
        runner().scheduleRepeating(() -> runner().runOnRenderThread(this::drain), Duration.ofMillis(33));
    }

    // ── 欢迎横幅（仿 Claude Code） ───────────────────────────────────────
    private static final Style WELCOME_BORDER = Style.create().fg(Color.indexed(215));            // 暖橙边框
    private static final Style WELCOME_TITLE  = Style.create().fg(Color.indexed(215)).bold();     // 暖橙加粗标题
    private static final Style WELCOME_BODY   = Style.create().fg(Color.GRAY);
    private static final Style WELCOME_HINT   = Style.create().fg(Color.DARK_GRAY);

    /** 圆角欢迎框：标题 + 简介 + 快捷键 + 工作区路径，下沉到 scrollback 顶部，输入框钉在其下。 */
    private void printWelcome() {
        int w = Math.min(Math.max(terminalWidth() - 1, 48), 76);
        String bar = "─".repeat(Math.max(0, w - 2));
        runner().println(Text.styled("╭" + bar + "╮", WELCOME_BORDER));
        welcomeLine(w, "✻ ", "Spring AI Code TUI", WELCOME_TITLE);
        welcomeLine(w, "  ", "", WELCOME_BODY);
        welcomeLine(w, "  ", "基于 DeepSeek 的编码智能体 · " + onSubmit.currentModel(), WELCOME_BODY);
        welcomeLine(w, "  ", "Enter 发送 · \\+Enter 换行 · /model 切换模型 · Esc 取消 · Ctrl+C 退出", WELCOME_HINT);
        welcomeLine(w, "  ", "", WELCOME_BODY);
        welcomeLine(w, "  ", "cwd: " + root, WELCOME_HINT);
        runner().println(Text.styled("╰" + bar + "╯", WELCOME_BORDER));
        runner().println("");   // 与后续对话留白
    }

    /** 组一行欢迎框内容：{@code │ + 前缀内容(截断/补白到内宽) + │}。 */
    private void welcomeLine(int w, String prefix, String body, Style contentStyle) {
        int inner = Math.max(1, w - 2);
        String content = " " + prefix + body;                     // 左侧留一空格
        if (displayWidth(content) > inner) {                      // 过长（如深路径）：按显示宽度截断
            content = dev.tamboui.text.CharWidth.substringByWidth(content, inner - 1) + "…";
        }
        int pad = Math.max(0, inner - displayWidth(content));
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("│", WELCOME_BORDER));
        spans.add(Span.styled(content, contentStyle));
        if (pad > 0) spans.add(Span.raw(" ".repeat(pad)));
        spans.add(Span.styled("│", WELCOME_BORDER));
        runner().println(Text.from(Line.from(spans)));
    }

    // ── scrollback 下沉（渲染线程） ──────────────────────────────────────
    private void drain() {
        for (OutputLine ol : state.drainPending()) {
            switch (ol.kind()) {
                case USER -> {
                    md.reset();   // 新回合：清 markdown 代码围栏状态
                    printlnUserBlock(ol.text());   // 灰底白字块，仿 Claude Code
                }
                case ASSISTANT ->                                  // AI 正文：markdown/语法高亮 + 缩进
                        runner().println(indented(md.renderFinalized(ol.text())));
                case TOOL_START -> printlnToolStart(ol);           // edit/write：展开成 diff 块；其余单行摘要
                default -> {                                       // 工具/Todo/错误：单色贴左
                    Style st = styleFor(ol.kind());
                    if (st == null) runner().println(ol.text());
                    else runner().println(Text.styled(ol.text(), st));
                }
            }
        }
        for (String row : state.takeCompleteStreamingLines()) {    // 流式完整行：markdown/语法高亮 + 缩进
            runner().println(indented(md.renderFinalized(row)));
        }
        // 回合结束后自动出队下一条排队消息。submit() 同步置 THINKING，故本 tick 只会出队一条，无重复提交竞态。
        if (state.isIdle()) {
            String next = state.pollQueued();
            if (next != null) dispatch(next);
        }
    }

    /** 用户消息：灰底白字块，仿 Claude Code。按终端宽度软折行，每行右侧补白使灰底铺满整行。 */
    private void printlnUserBlock(String text) {
        int width = terminalWidth();
        int inner = Math.max(1, width - displayWidth(INDENT));   // 减去左缩进宽度
        for (String logical : text.split("\n", -1)) {
            for (String seg : wrapSegments(logical, inner)) {
                int pad = Math.max(0, inner - displayWidth(seg));
                runner().println(Text.from(Line.from(
                        Span.styled(INDENT, USER_BLOCK),
                        Span.styled(seg, USER_BLOCK),
                        Span.styled(" ".repeat(pad), USER_BLOCK))));
            }
        }
    }

    /** 工具开始：edit/write 展开成 Claude Code 式 diff 块（读原文件、真实行号、语法高亮 + 增删底色）；其余工具单行摘要。 */
    private void printlnToolStart(OutputLine ol) {
        List<DiffRenderer.DiffLine> lines =
                (ol.raw() == null) ? List.of() : diff.render(ol.toolName(), ol.raw());
        if (lines.isEmpty()) {                                      // 非文件写入 / 无法解析：回退单行摘要
            runner().println(Text.styled(ol.text(), TOOL));
            return;
        }
        int width = terminalWidth();
        String lang = langOf(lines);        // 从 header 的路径推断语言（决定语法高亮规则）
        boolean inBlock = false;            // 跨行块注释状态，按 body 顺序推进
        for (DiffRenderer.DiffLine dl : lines) {
            List<Span> hl = null;
            if (dl.type() != DiffRenderer.Type.HEADER && dl.type() != DiffRenderer.Type.TRUNCATED) {
                SyntaxHighlighter.Result r = SyntaxHighlighter.highlight(dl.text(), lang, inBlock);
                inBlock = r.stillInBlockComment();
                hl = r.spans();
            }
            runner().println(diffLine(dl, hl, width));
        }
    }

    /** 把一行 DiffLine 渲染成整行 Text；ADD/DEL 行把底色铺满整行（含行号列），上下文行只高亮不上底色。 */
    private static Text diffLine(DiffRenderer.DiffLine dl, List<Span> hl, int width) {
        return switch (dl.type()) {
            case HEADER -> Text.from(Line.from(Span.raw(INDENT), Span.styled(dl.text(), DIFF_HEADER)));
            case TRUNCATED -> Text.from(Line.from(
                    Span.raw(INDENT), Span.styled(gutter(null) + "  " + dl.text(), DIFF_TRUNC)));
            case CONTEXT -> bodyLine(gutter(dl.newNo() != null ? dl.newNo() : dl.oldNo()), " ",
                    hl, DIFF_NO_CTX, null, width);
            case ADD -> bodyLine(gutter(dl.newNo()), "+", hl, DIFF_NO_ADD, ADD_BG, width);
            case DEL -> bodyLine(gutter(dl.oldNo()), "-", hl, DIFF_NO_DEL, DEL_BG, width);
        };
    }

    /**
     * 组装一行 diff 主体：{@code 行号 + 符号 + 高亮内容}。
     * bg 非 null（ADD/DEL）时：左缩进/行号/符号/内容/右侧补白全部叠加底色，形成从左到右铺满整行的色带。
     * bg 为 null（CONTEXT）时：无底色，仅显示语法高亮。
     */
    private static Text bodyLine(String num, String sign, List<Span> content,
                                 Style numStyle, Color bg, int width) {
        List<Span> spans = new ArrayList<>();
        // 左缩进：带底色时纳入色带，否则纯留白
        spans.add(bg == null ? Span.raw(INDENT) : Span.styled(INDENT, Style.create().bg(bg)));
        spans.add(Span.styled(num, numStyle));
        spans.add(Span.styled(" " + sign + " ", numStyle));
        int used = displayWidth(INDENT) + displayWidth(num) + 3;   // 3 = " " + sign + " "
        for (Span s : content) {
            spans.add(bg == null ? s : s.bg(bg));                  // 高亮前景上叠加底色
            used += s.width();
        }
        if (bg != null) {                                          // 右侧补白到终端宽度，让色带铺满整行
            int pad = Math.max(0, width - used);
            if (pad > 0) spans.add(Span.styled(" ".repeat(pad), Style.create().bg(bg)));
        }
        return Text.from(Line.from(spans));
    }

    /** 从 diff 的 header（{@code Update(path)}）提取路径后缀，映射成 SyntaxHighlighter 的语言标识。 */
    private static String langOf(List<DiffRenderer.DiffLine> lines) {
        if (lines.isEmpty()) return "";
        String h = lines.get(0).text();               // 形如 Update(src/.../App.java)
        int lp = h.indexOf('(');
        int dot = h.lastIndexOf('.');
        if (lp < 0 || dot <= lp) return "";
        return h.substring(dot + 1).replace(")", "").trim();
    }

    /** 行号右对齐到 {@link #GUTTER} 列；null（新增/删除的对侧）用空白占位保持列对齐。 */
    private static String gutter(Integer no) {
        String s = (no == null) ? "" : String.valueOf(no);
        return " ".repeat(Math.max(0, GUTTER - s.length())) + s;
    }

    /** 终端列数；拿不到时退化为 80。 */
    private int terminalWidth() {
        try {
            int w = runner().tuiRunner().width();
            return w > 0 ? w : 80;
        } catch (Exception e) {
            return 80;
        }
    }

    /** 内容的显示宽度（中文占 2 列），用于底色补齐计算。 */
    private static int displayWidth(String s) {
        return dev.tamboui.text.CharWidth.of(s);
    }

    // ── 输入 ────────────────────────────────────────────────────────────
    private Element inputElement() {
        return new InputBox();
    }

    /**
     * 圆角多行输入框（<b>唯一焦点目标</b>，自绘）。
     *
     * <p><b>为何不直接用 {@code textArea} 元素</b>：{@code TextAreaElement.isFocusable()} 恒为 true，
     * 未显式给 id 时会以自增 id 自注册进焦点链并<em>抢占焦点</em>；而路由器对焦点元素<em>先调内建
     * handleKeyEvent</em>（把 Enter 当换行插入并返回 HANDLED），我们挂的发送逻辑根本轮不到 → Enter 发不出去。
     *
     * <p><b>做法</b>：本元素亲自持有焦点（固定 id + focusable），按键第一手先给 {@link #onInputKey}
     * 拦下 Enter=发送 / Ctrl+C / Esc；其余编辑键（退格/方向/Home/End/字符/中文…）转交给<em>从不渲染</em>的
     * {@link #inputKeys}（复用其完整键处理，但因不渲染故不自注册、不抢焦点）。渲染走底层 {@code TextArea}
     * 控件（不经过会自注册的 element），并补硬件光标供中文 IME 定位。高度 = 行数 + 边框，随行数自动增高。
     */
    private final class InputBox implements Element {
        @Override public boolean isFocusable() { return true; }
        @Override public String id() { return "code-tui-input"; }

        @Override
        public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
            EventResult r = onInputKey(event);             // Ctrl+C / Esc / Enter(发送) 在此拦下
            if (r.isHandled()) return r;
            return inputKeys.handleKeyEvent(event, focused);   // 其余编辑键交给（不渲染的）textArea 键处理
        }

        @Override
        public EventResult handlePasteEvent(PasteEvent event) {
            return inputKeys.handlePasteEvent(event);      // 多行粘贴
        }

        @Override
        public Size preferredSize(int maxW, int maxH, RenderContext ctx) {
            int w = maxW > 0 ? maxW : 80;
            return Size.of(w, visualRowCount(w - 2) + 2); // 自动增高：软折行后的可视行数 + 上下边框
        }

        @Override
        public void render(Frame frame, Rect rect, RenderContext ctx) {
            Buffer buf = frame.buffer();
            Block block = Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
            block.render(rect, buf);
            Rect inner = block.inner(rect);
            int ix = inner.x(), iy = inner.y(), iw = Math.max(1, inner.width()), ih = inner.height();

            if (inputState.text().isEmpty()) {              // 空态：占位符 + 光标在开头
                buf.setString(ix, iy, "输入消息，Enter 发送，\\ + Enter 换行", DIM);
                frame.setCursorPosition(ix, iy);
                return;
            }

            int cr = inputState.cursorRow(), cc = inputState.cursorCol();
            int vis = 0, curRow = 0, curCol = 0;
            int n = inputState.lineCount();
            for (int li = 0; li < n; li++) {                // 逐条逻辑行按框宽软折行，画到连续可视行
                String logical = inputState.getLine(li);
                List<String> segs = wrapSegments(logical, iw);
                int base = 0;
                for (int si = 0; si < segs.size(); si++) {
                    String seg = segs.get(si);
                    if (vis < ih) buf.setString(ix, iy + vis, seg, Style.EMPTY);
                    if (li == cr) {                         // 定位光标所在的可视行/列
                        int segStart = base, segEnd = base + seg.length();
                        boolean last = si == segs.size() - 1;
                        if (cc >= segStart && (cc < segEnd || (last && cc <= segEnd))) {
                            curRow = vis;
                            int within = Math.min(cc - segStart, seg.length());
                            curCol = dev.tamboui.text.CharWidth.of(seg.substring(0, within));
                        }
                    }
                    base += seg.length();
                    vis++;
                }
            }
            // 反显格 + 硬件光标（中文 IME 定位），夹到可视区内
            int cx = ix + Math.min(curCol, iw - 1);
            int cy = iy + Math.min(curRow, Math.max(0, ih - 1));
            Cell cell = buf.get(cx, cy);
            buf.set(cx, cy, cell.patchStyle(Style.EMPTY.reversed()));
            frame.setCursorPosition(cx, cy);
        }
    }

    /** 逻辑行按显示宽度（中文 2 列）软折行成若干可视段；空行返回单个空段。 */
    private static List<String> wrapSegments(String line, int width) {
        int w = Math.max(1, width);
        List<String> segs = new ArrayList<>();
        if (line.isEmpty()) { segs.add(""); return segs; }
        String rest = line;
        while (!rest.isEmpty()) {
            String seg = dev.tamboui.text.CharWidth.substringByWidth(rest, w);
            if (seg.isEmpty()) seg = rest.substring(0, 1);   // 兜底：框窄到放不下 1 个宽字符时也吃 1 个
            segs.add(seg);
            rest = rest.substring(seg.length());
        }
        return segs;
    }

    /** 当前文本在给定内宽下软折行后的总可视行数（≥1）。 */
    private int visualRowCount(int innerWidth) {
        int rows = 0, n = inputState.lineCount();
        for (int i = 0; i < n; i++) rows += wrapSegments(inputState.getLine(i), innerWidth).size();
        return Math.max(1, rows);
    }

    /**
     * 输入框按键（本处理器早于 textArea 内建处理触发，返回 HANDLED 即可拦截）：
     * <ul>
     *   <li>Ctrl+C / quit → 退出；</li>
     *   <li>Esc / cancel → 取消当前回合（返回 HANDLED 以免 Esc 被路由器用去清焦点）；</li>
     *   <li>Enter（无 Shift/Alt）→ 提交并拦截，textArea 不再插入换行；</li>
     *   <li>Shift/Alt+Enter → 放行（UNHANDLED），交给 textArea 插入换行（能否区分取决于终端）。</li>
     * </ul>
     */
    private EventResult onInputKey(KeyEvent k) {
        if (k.isCtrlC() || k.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (pickingModel) return onModelPickerKey(k);   // 选择器激活：按键全部交给它，屏蔽文本编辑
        if (k.isCancel()) {
            boolean running = !state.isIdle();
            int dropped = state.queuedCount();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.clearQueued();                         // 取消时一并清空排队消息
            state.setNotice(running || dropped > 0
                    ? "已取消当前回合" + (dropped > 0 ? "，丢弃 " + dropped + " 条排队" : "")
                    : "");
            return EventResult.HANDLED;
        }
        boolean isEnter = k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n');
        if (isEnter) {
            // Shift/Alt+Enter → 换行（仅当终端能区分修饰键；Apple Terminal 等区分不了，见下反斜杠续行）
            if (k.hasShift() || k.hasAlt()) return EventResult.UNHANDLED;   // 交给 inputKeys 插入 '\n'
            // 反斜杠续行（终端无关的可靠换行，同 Claude Code）：行尾 \ + Enter → 删掉 \ 再换行
            if (cursorAfterBackslash()) {
                inputState.deleteBackward();
                inputState.insert('\n');
                return EventResult.HANDLED;
            }
            submitInput();
            return EventResult.HANDLED;   // 普通 Enter → 发送（不让编辑器当作换行）
        }
        return EventResult.UNHANDLED;
    }

    /** 光标紧跟在一个反斜杠之后（行尾 {@code \} + Enter 用作换行的判定）。 */
    private boolean cursorAfterBackslash() {
        int cr = inputState.cursorRow(), cc = inputState.cursorCol();
        String line = cr >= 0 && cr < inputState.lineCount() ? inputState.getLine(cr) : "";
        return cc > 0 && cc <= line.length() && line.charAt(cc - 1) == '\\';
    }

    /** 提交：忙时把消息入队（回合结束由 {@link #drain} 自动出队提交），空闲时立即提交。均清空输入框。 */
    private void submitInput() {
        String text = inputState.text();
        if (text == null || text.isBlank()) return;
        if (text.strip().equals("/model")) {         // 斜杠命令：打开模型选择器（仿 Claude Code）
            inputState.clear();
            openModelPicker();
            return;
        }
        inputState.clear();
        if (!state.isIdle()) {                       // 忙：排队，不打断当前回合（仿 Claude Code）
            state.enqueue(text);                      // 反馈靠状态行的实时「已排队 N 条」，不用 sticky notice
            return;
        }
        dispatch(text);
    }

    /** 真正发起一个回合：提交给 agent，并在 scrollback 打一行「本回合实际使用的模型」（确定性证据，非模型自述）。 */
    private void dispatch(String text) {
        current = onSubmit.submit(text);
        state.pushInfo("⚙ 使用模型 " + onSubmit.currentModel());
    }

    // ── /model 模型选择器 ───────────────────────────────────────────────
    /** 打开选择器：高亮定位到当前所选模型。 */
    private void openModelPicker() {
        List<ModelOption> models = onSubmit.models();
        if (models.isEmpty()) { state.setNotice("当前没有可选模型"); return; }
        pickIndex = 0;
        String cur = onSubmit.currentModel();
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(cur)) { pickIndex = i; break; }
        }
        pickingModel = true;
    }

    /** 选择器按键：↑↓/kj 移动、数字快选、Enter 确认、Esc 取消。始终 HANDLED（屏蔽文本编辑）。 */
    private EventResult onModelPickerKey(KeyEvent k) {
        List<ModelOption> models = onSubmit.models();
        int n = models.size();
        if (k.isCancel()) { pickingModel = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {           // 数字 1..n 快选
            if (k.isChar((char) ('1' + i))) { pickIndex = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            ModelOption chosen = models.get(pickIndex);
            onSubmit.selectModel(chosen.id());
            pickingModel = false;
            state.setNotice("已切换模型 · " + chosen.label());
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;                       // 其余按键一律吞掉，不落进输入框
    }

    /** 选择器面板：标题 + 每个模型一行（❯ 高亮当前、✓ 标记在用、右侧暗色说明）。 */
    private Element[] modelPickerChildren() {
        List<ModelOption> models = onSubmit.models();
        String cur = onSubmit.currentModel();
        List<Element> els = new ArrayList<>();
        els.add(text("  选择模型（↑↓ 选择 · Enter 确认 · Esc 取消）").style(PICK_TITLE));
        for (int i = 0; i < models.size(); i++) {
            ModelOption m = models.get(i);
            boolean sel = i == pickIndex;
            boolean active = m.id().equals(cur);
            String marker = (sel ? "❯ " : "  ") + (active ? "✓ " : "  ");
            els.add(text("  " + marker + (i + 1) + ". " + m.label() + "   " + m.desc())
                    .style(sel ? PICK_SEL : (active ? PICK_ITEM : PICK_DESC)));
        }
        return els.toArray(new Element[0]);
    }

    // ── 计划面板 / 状态行 ────────────────────────────────────────────────
    private Element[] todoChildren(List<String> todos) {
        List<Element> els = new ArrayList<>();
        els.add(text("📋 计划").style(TODO_TITLE));
        int shown = Math.min(todos.size(), TODO_CAP);
        for (int i = 0; i < shown; i++) els.add(todoRow(todos.get(i)));
        if (todos.size() > TODO_CAP) {
            els.add(text("  … 还有 " + (todos.size() - TODO_CAP) + " 项").style(DIM));
        }
        return els.toArray(new Element[0]);
    }

    /** 排队消息面板：固定在输入框上方，每条一行（暗灰底、› 前缀、超宽截断），仿 Claude Code。 */
    private Element[] queuedChildren(List<String> queued) {
        List<Element> els = new ArrayList<>();
        int inner = Math.max(8, terminalWidth() - displayWidth(INDENT) - 2);   // 减缩进与 "› "
        for (String q : queued) {
            String oneLine = q.replaceAll("\\s+", " ").trim();
            if (displayWidth(oneLine) > inner) oneLine = dev.tamboui.text.CharWidth.substringByWidth(oneLine, inner - 1) + "…";
            els.add(text(INDENT + "› " + oneLine).style(QUEUED));
        }
        return els.toArray(new Element[0]);
    }

    /** 一条计划：✓完成=绿 / ▶进行中=亮黄加粗 / ○待办=暗。 */
    private static Element todoRow(String s) {
        Style st = s.startsWith("✓") ? OK : s.startsWith("▶") ? TODO_RUN : DIM;
        return text("  " + s).style(st);
    }

    private Element statusLine() {
        if (pickingModel) return text("↑↓/kj 选择 · 1-9 快选 · Enter 确认 · Esc 取消").style(THINK);
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        String notice = state.notice();
        if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
        return switch (state.status()) {
            case IDLE -> text("Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + onSubmit.currentModel()).style(DIM);
            case THINKING -> text("● 思考中…" + qs + " · Esc 取消 · Ctrl+C 退出").style(THINK);
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield text("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…" + qs + " · Esc 取消")
                        .style(RUNNING);
            }
        };
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────
    /** 给渲染出的 span 列表加左缩进，组成一行 Text。 */
    private static Text indented(List<Span> spans) {
        List<Span> all = new ArrayList<>(spans.size() + 1);
        all.add(Span.raw(INDENT));
        all.addAll(spans);
        return Text.from(Line.from(all));
    }

    /** 取最后一个真实换行之后的残段（流式预览用；complete 行由 drain 下沉 scrollback）。 */
    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static Style styleFor(OutputLine.Kind kind) {
        return switch (kind) {
            case USER -> USER;
            case ASSISTANT -> null;          // 默认色，正文最易读
            case TOOL_START -> TOOL;
            case TOOL_OK -> OK;
            case TOOL_FAIL -> FAIL;
            case TODO -> TODO;
            case ERROR -> ERROR;
            case INFO -> DIM;
        };
    }
}
