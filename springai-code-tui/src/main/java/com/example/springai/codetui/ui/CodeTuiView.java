package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.tamboui.toolkit.InlineToolkit.scope;
import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

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
 *   [圆角输入框]      —— 原生 {@code textInput}，自带光标/编辑/中文输入
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
    private final TextInputState inputState = new TextInputState();  // 输入源（原生控件托管，含中文/光标/编辑）
    private final DiffRenderer diff;                                 // edit/write → 带真实行号的 diff 行
    private Disposable current;

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root) {
        this.state = state;
        this.onSubmit = onSubmit;
        this.diff = new DiffRenderer(root);
    }

    /** 初始高度：圆角输入框(3) + 状态行(1)；两个 scope 折叠时为 0，运行器再按 preferredSize 增减。 */
    @Override
    protected int height() {
        return 4;
    }

    /** 每帧构造 UI（纯读状态，不产生副作用；scrollback 的 println 放在 {@link #drain} 里另行推进）。 */
    @Override
    protected Element render() {
        List<String> todos = state.todoSnapshot();
        String tail = lastLine(state.streaming());   // 流式当前残行（未换行段）
        return column(
                scope(!tail.isEmpty(), richText(indented(md.renderPreview(tail))).ellipsisStart()),
                scope(!todos.isEmpty(), todoChildren(todos)),
                inputElement(),
                statusLine());
    }

    @Override
    protected void onStart() {
        // 在渲染线程、两帧之间安全推进 scrollback（println 会移动光标/插行，绝不能在绘制中途调用）。
        runner().scheduleRepeating(() -> runner().runOnRenderThread(this::drain), Duration.ofMillis(33));
    }

    // ── scrollback 下沉（渲染线程） ──────────────────────────────────────
    private void drain() {
        for (OutputLine ol : state.drainPending()) {
            switch (ol.kind()) {
                case USER -> {
                    md.reset();   // 新回合：清 markdown 代码围栏状态
                    runner().println(Text.from(Line.from(Span.raw(INDENT), Span.styled(ol.text(), USER))));
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
        return textInput(inputState)
                // 稳定 id：每帧都重建元素，若用自增自动 id 则焦点无法跨帧保持，光标会一直停在行首。
                .id("code-tui-input")
                .rounded()
                .placeholder("输入消息，Enter 发送")
                .onSubmit(this::onEnter)
                .onKeyEvent(this::onInputKey);
    }

    /** Enter：单飞下忙时忽略；否则取走输入、清空、提交。 */
    private void onEnter() {
        if (!state.isIdle()) return;
        String text = inputState.text();
        if (text == null || text.isBlank()) return;
        inputState.clear();
        current = onSubmit.submit(text);
    }

    /** 输入框未消费的键：Ctrl+C 退出、Esc 取消当前回合（返回 HANDLED 以免 Esc 被路由器用去清焦点）。 */
    private EventResult onInputKey(KeyEvent k) {
        if (k.isCtrlC() || k.isQuit()) {
            quit();
            return EventResult.HANDLED;
        }
        if (k.isCancel()) {
            boolean running = !state.isIdle();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.setNotice(running ? "已取消当前回合" : "");
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
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

    /** 一条计划：✓完成=绿 / ▶进行中=亮黄加粗 / ○待办=暗。 */
    private static Element todoRow(String s) {
        Style st = s.startsWith("✓") ? OK : s.startsWith("▶") ? TODO_RUN : DIM;
        return text("  " + s).style(st);
    }

    private Element statusLine() {
        String notice = state.notice();
        if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
        return switch (state.status()) {
            case IDLE -> text("Enter 发送 · Esc 取消 · Ctrl+C 退出").style(DIM);
            case THINKING -> text("● 思考中… · Esc 取消 · Ctrl+C 退出").style(THINK);
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield text("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "… · Esc 取消")
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
        };
    }
}
