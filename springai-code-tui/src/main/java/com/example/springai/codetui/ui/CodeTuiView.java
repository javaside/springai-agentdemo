package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Overflow;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
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
 * Claude Code 式<b>行内</b>视图（TamboUI {@link InlineTuiRunner}）：
 * <ul>
 *   <li>已定稿行 / 流式已凑满的整行 用 {@code println} 推进终端 scrollback —— 自然向上滚、可翻历史；</li>
 *   <li>底部 <b>固定 5 行</b> live 区（不动态改高，避免行内视口记账错乱导致输入卡死）：
 *       [流式残段预览][上横线][输入框][下横线][状态行]。</li>
 * </ul>
 * 重绘：run 循环仅在 handle 返回 true 时 render，故 handle 对 {@link TickEvent} 返回 true，
 * 并在每 tick 把定稿行 / 流式整行 println 出去。
 */
public final class CodeTuiView implements InlineEventHandler, Renderer {

    private static final int LIVE_HEIGHT = 5;   // 预览 + 上横线 + 输入 + 下横线 + 状态（固定，不变）

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private Disposable current;      // 单飞：任一时刻至多一个活跃回合

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
            for (String line : state.drainPending()) runner.println(line);           // 定稿行下沉 scrollback
            for (String line : state.takeCompleteStreamingLines(runner.width())) runner.println(line); // 流式整行下沉
            return true;                                                              // 触发 live 区重绘
        }
        if (!(e instanceof KeyEvent k)) return false;
        if (k.isCtrlC()) { runner.quit(); return true; }
        if (k.isCancel()) {                       // Esc：UI 层取消当前回合
            boolean running = !state.isIdle();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.setNotice(running ? "已取消当前回合" : "Esc：当前无进行中回合");
            return true;
        }
        if (k.isConfirm()) {                      // Enter
            if (!state.isIdle()) return true;     // 单飞：上一回合进行中，忽略提交
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        int cp = k.codePoint();                   // 可打印输入：codePoint 支持 CJK/非 ASCII
        if (cp > 0 && !Character.isISOControl(cp) && !k.hasCtrl() && !k.hasAlt()) {
            state.typeString(new String(Character.toChars(cp)));
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame f) {
        Rect a = f.area();
        int w = Math.max(1, a.width());
        int x = a.x();
        int bottom = a.y() + a.height() - 1;      // 从底部往上锚定，防终端给的高度不足 5

        int yStatus = bottom;
        int yBottomBorder = bottom - 1;
        int yInput = bottom - 2;
        int yTopBorder = bottom - 3;
        int yPreview = bottom - 4;

        String border = "─".repeat(w);

        if (yPreview >= a.y()) {
            f.renderWidget(clip(fitWidth(state.streaming(), w)), new Rect(x, yPreview, a.width(), 1));
        }
        if (yTopBorder >= a.y()) {
            f.renderWidget(clip(border), new Rect(x, yTopBorder, a.width(), 1));
        }

        // 输入行：过长显示尾部，光标恒在末尾
        String prefix = "› ";
        int avail = Math.max(1, w - CharWidth.of(prefix));
        String inputText = state.currentInput();
        String shownInput = CharWidth.of(inputText) <= avail
                ? inputText
                : CharWidth.substringByWidthFromEnd(inputText, avail);
        String prompt = prefix + shownInput;
        f.renderWidget(clip(prompt), new Rect(x, yInput, a.width(), 1));

        f.renderWidget(clip(border), new Rect(x, yBottomBorder, a.width(), 1));
        f.renderWidget(clip(statusLine()), new Rect(x, yStatus, a.width(), 1));

        f.setCursorPosition(x + CharWidth.of(prompt), yInput);
    }

    private String statusLine() {
        String notice = state.notice();
        if (!notice.isEmpty()) return notice + " · Ctrl+C 退出";
        return switch (state.status()) {
            case IDLE -> "Enter 发送 · Esc 取消 · Ctrl+C 退出";
            case THINKING -> "● 思考中… · Esc 取消 · Ctrl+C 退出";
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield "⏳ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "… · Esc 取消";
            }
        };
    }

    /** 截断到显示宽度 w（CJK 感知），供定宽单行区域使用。 */
    private static String fitWidth(String s, int w) {
        if (s.isEmpty() || CharWidth.of(s) <= w) return s;
        return CharWidth.substringByWidth(s, w);
    }

    private static Paragraph clip(String s) {
        return Paragraph.builder().text(s).overflow(Overflow.CLIP).build();
    }
}
