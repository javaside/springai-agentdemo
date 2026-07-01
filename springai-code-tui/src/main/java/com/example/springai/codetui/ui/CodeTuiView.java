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

/**
 * Claude Code 式行内视图（TamboUI {@link InlineTuiRunner}）：定稿行按类型分色 println 进 scrollback，
 * 底部固定 5 行 live 区（流式预览 + 圆角输入框 + 状态行）。live 区高度固定不变，避免行内视口记账错乱。
 */
public final class CodeTuiView implements InlineEventHandler, Renderer {

    private static final int LIVE_HEIGHT = 5;   // 预览 + 上边框 + 输入 + 下边框 + 状态

    // 配色（层次感）
    private static final Style DIM     = Style.create().fg(Color.DARK_GRAY);
    private static final Style USER    = Style.create().fg(Color.CYAN).bold();
    private static final Style TOOL    = Style.create().fg(Color.DARK_GRAY);
    private static final Style OK      = Style.create().fg(Color.GREEN);
    private static final Style FAIL    = Style.create().fg(Color.RED);
    private static final Style TODO    = Style.create().fg(Color.YELLOW);
    private static final Style ERROR   = Style.create().fg(Color.RED).bold();
    private static final Style PROMPT  = Style.create().fg(Color.CYAN).bold();
    private static final Style THINK   = Style.create().fg(Color.YELLOW);
    private static final Style RUNNING = Style.create().fg(Color.CYAN);

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private Disposable current;

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
                Style st = styleFor(ol.kind());
                if (st == null) runner.println(ol.text());           // 助手正文：默认色
                else runner.println(Text.styled(ol.text(), st));
            }
            for (String row : state.takeCompleteStreamingLines(runner.width())) {
                runner.println(row);                                 // 流式整行：默认色
            }
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
        int yPreview = bottom - 4;

        // 流式残段预览（暗色）
        if (yPreview >= a.y()) {
            put(f, x, yPreview, a.width(), Text.styled(fit(state.streaming(), w), DIM));
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
                Span.styled("› ", PROMPT),
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

    private static String fit(String s, int w) {
        if (s.isEmpty() || CharWidth.of(s) <= w) return s;
        return CharWidth.substringByWidth(s, w);
    }
}
