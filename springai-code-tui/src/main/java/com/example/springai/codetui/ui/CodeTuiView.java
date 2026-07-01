package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.InlineEventHandler;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.InlineTuiRunner;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code 式行内视图（TamboUI {@link InlineTuiRunner}）：定稿行按类型分色 println 进 scrollback，
 * 底部固定 5 行 live 区（流式预览 + 圆角输入框 + 状态行）。live 区高度固定不变，避免行内视口记账错乱。
 */
public final class CodeTuiView implements InlineEventHandler, Renderer {

    private static final int LIVE_HEIGHT = 5;   // 预览 + 上边框 + 输入 + 下边框 + 状态（固定部分）
    private static final int TODO_CAP = 12;      // 计划面板最多显示几条
    private static final String INDENT = "  ";  // 对话内容（用户/AI）缩进；工具行不缩进

    // 配色（层次感）：用户输入=灰色次要，AI 回复=默认亮色（重点）
    private static final Style DIM     = Style.create().fg(Color.DARK_GRAY);
    private static final Style USER    = Style.create().fg(Color.GRAY);     // 用户输入：灰色，次要
    private static final Style TOOL    = Style.create().fg(Color.DARK_GRAY);
    private static final Style OK      = Style.create().fg(Color.GREEN);
    private static final Style FAIL    = Style.create().fg(Color.RED);
    private static final Style TODO    = Style.create().fg(Color.YELLOW);
    private static final Style ERROR   = Style.create().fg(Color.RED).bold();
    private static final Style THINK   = Style.create().fg(Color.YELLOW);
    private static final Style RUNNING = Style.create().fg(Color.CYAN);
    private static final Style TODO_TITLE = Style.create().fg(Color.YELLOW).bold();
    private static final Style TODO_RUN   = Style.create().fg(Color.LIGHT_YELLOW).bold();  // 进行中：醒目

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private final MarkdownRenderer md = new MarkdownRenderer();   // AI 正文 markdown + 代码语法高亮
    private Disposable current;
    private int lastHeight = -1;                                  // 仅当计划面板行数变化时才 setContentHeight

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit) {
        this.state = state;
        this.onSubmit = onSubmit;
    }

    public void run() throws Exception {
        InlineTuiConfig cfg = InlineTuiConfig.builder(LIVE_HEIGHT).tickRate(Duration.ofMillis(33)).build();
        try (InlineTuiRunner runner = InlineTuiRunner.create(cfg)) {
            runner.run(this, this);
        }
    }

    @Override
    public boolean handle(Event e, InlineTuiRunner runner) {
        if (e instanceof TickEvent) {
            for (OutputLine ol : state.drainPending()) {
                switch (ol.kind()) {
                    case USER -> {
                        md.reset();                                  // 新回合：清 markdown 代码围栏状态
                        runner.println(Text.from(Line.from(Span.raw(INDENT), Span.styled(ol.text(), USER))));
                    }
                    case ASSISTANT ->                                // AI 正文：markdown/语法高亮 + 缩进
                            runner.println(indented(md.renderFinalized(ol.text())));
                    default -> {                                     // 工具/Todo/错误：单色贴左
                        Style st = styleFor(ol.kind());
                        if (st == null) runner.println(ol.text());
                        else runner.println(Text.styled(ol.text(), st));
                    }
                }
            }
            for (String row : state.takeCompleteStreamingLines()) {  // 流式完整行：markdown/语法高亮 + 缩进
                runner.println(indented(md.renderFinalized(row)));
            }
            // 计划面板行数变化时才调 live 高度（低频，安全，不会像逐帧改高那样卡死）
            int desired = LIVE_HEIGHT + todoBlockHeight(state.todoSnapshot().size());
            if (desired != lastHeight) { runner.setContentHeight(desired); lastHeight = desired; }
            return true;
        }
        if (!(e instanceof KeyEvent k)) return false;
        if (k.isCtrlC()) { runner.quit(); return true; }
        if (k.isCancel()) {
            boolean running = !state.isIdle();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.setNotice(running ? "已取消当前回合" : "");
            return true;
        }
        if (k.isConfirm()) {
            if (!state.isIdle()) return true;
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        int cp = k.codePoint();
        if (cp > 0 && !Character.isISOControl(cp) && !k.hasCtrl() && !k.hasAlt()) {
            state.typeString(new String(Character.toChars(cp)));
            return true;
        }
        return false;
    }

    /** 计划面板占多少行：标题(1) + 条目(封顶 TODO_CAP) + 溢出提示(1)。空则 0。 */
    private static int todoBlockHeight(int n) {
        if (n <= 0) return 0;
        return 1 + Math.min(n, TODO_CAP) + (n > TODO_CAP ? 1 : 0);
    }

    /** 一条计划：按状态标记（✓完成/▶进行中/○待办）分色。 */
    private static Text todoLine(String s) {
        Style st;
        if (s.startsWith("✓")) st = OK;                 // 完成：绿
        else if (s.startsWith("▶")) st = TODO_RUN;      // 进行中：亮黄加粗
        else st = DIM;                                  // 待办：暗
        return Text.styled("  " + s, st);
    }

    /** 给渲染出的 span 列表加左缩进，组成一行 Text。 */
    private static Text indented(List<Span> spans) {
        List<Span> all = new ArrayList<>(spans.size() + 1);
        all.add(Span.raw(INDENT));
        all.addAll(spans);
        return Text.from(Line.from(all));
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

    @Override
    public void render(Frame f) {
        Rect a = f.area();
        int w = Math.max(1, a.width());
        int x = a.x();
        int bottom = a.y() + a.height() - 1;

        int yStatus = bottom;
        int yBottom = bottom - 1;
        int yInput = bottom - 2;
        int yTop = bottom - 3;

        List<String> todos = state.todoSnapshot();
        int todoBlock = todoBlockHeight(todos.size());
        int yTodoTop = yTop - todoBlock;      // 计划面板首行
        int yPreview = yTodoTop - 1;

        // 流式残行预览（AI 生成中——markdown/语法高亮 + 缩进；用当前状态但不改变它）
        if (yPreview >= a.y()) {
            String s = state.streaming();
            if (s.isEmpty()) {
                put(f, x, yPreview, a.width(), Text.from(""));
            } else {
                String shown = fitEnd(s, Math.max(1, w - INDENT.length()));
                put(f, x, yPreview, a.width(), indented(md.renderPreview(shown)));
            }
        }
        // 计划进度面板（固定在输入框上方，原地更新，不进 scrollback）
        if (todoBlock > 0 && yTodoTop >= a.y()) {
            put(f, x, yTodoTop, a.width(), Text.styled("📋 计划", TODO_TITLE));
            int shown = Math.min(todos.size(), TODO_CAP);
            for (int i = 0; i < shown; i++) {
                put(f, x, yTodoTop + 1 + i, a.width(), todoLine(todos.get(i)));
            }
            if (todos.size() > TODO_CAP) {
                put(f, x, yTodoTop + 1 + shown, a.width(),
                        Text.styled("  … 还有 " + (todos.size() - TODO_CAP) + " 项", DIM));
            }
        }
        // 圆角输入框
        if (w >= 4) {
            put(f, x, yTop, a.width(), Text.styled("╭" + "─".repeat(w - 2) + "╮", DIM));
            put(f, x, yInput, a.width(), inputBoxLine(w));
            put(f, x, yBottom, a.width(), Text.styled("╰" + "─".repeat(w - 2) + "╯", DIM));
        } else {
            put(f, x, yInput, a.width(), Text.from("› " + state.currentInput()));
        }
        // 状态行
        put(f, x, yStatus, a.width(), statusText());

        // 光标：│ + 空格 + "› " + 输入
        int cursorCol = x + 2 + CharWidth.of("› ") + CharWidth.of(currentShownInput(w));
        f.setCursorPosition(cursorCol, yInput);
    }

    /** 输入行：│ ‹prompt› ...pad... │，边框暗色、提示符青色、输入默认色。 */
    private Text inputBoxLine(int w) {
        int inner = Math.max(0, w - 4);              // "│ " + inner + " │"
        String shownInput = currentShownInput(w);
        int used = CharWidth.of("› ") + CharWidth.of(shownInput);
        int pad = Math.max(0, inner - used);
        return Text.from(Line.from(
                Span.styled("│ ", DIM),
                Span.raw("› "),
                Span.raw(shownInput),
                Span.raw(" ".repeat(pad)),
                Span.styled(" │", DIM)));
    }

    /** 输入过长时显示尾部（光标端），使正在输入处始终可见。 */
    private String currentShownInput(int w) {
        int avail = Math.max(0, (w - 4) - CharWidth.of("› "));
        String t = state.currentInput();
        return CharWidth.of(t) <= avail ? t : CharWidth.substringByWidthFromEnd(t, avail);
    }

    private Text statusText() {
        String notice = state.notice();
        if (!notice.isEmpty()) return Text.styled(notice + " · Ctrl+C 退出", THINK);
        return switch (state.status()) {
            case IDLE -> Text.styled("Enter 发送 · Esc 取消 · Ctrl+C 退出", DIM);
            case THINKING -> Text.styled("● 思考中… · Esc 取消 · Ctrl+C 退出", THINK);
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield Text.styled("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "… · Esc 取消",
                        RUNNING);
            }
        };
    }

    private static void put(Frame f, int x, int y, int w, Text t) {
        f.renderWidget(Paragraph.builder().text(t).overflow(Overflow.CLIP).build(), new Rect(x, y, w, 1));
    }

    /** 显示尾部（最新生成的部分），供流式预览用。 */
    private static String fitEnd(String s, int w) {
        if (s.isEmpty() || CharWidth.of(s) <= w) return s;
        return CharWidth.substringByWidthFromEnd(s, w);
    }
}
