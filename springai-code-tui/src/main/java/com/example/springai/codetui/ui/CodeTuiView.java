package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;

/**
 * TamboUI 单栏视图。tickRate 周期重绘；render 画「对话区/输入行/状态栏」；
 * event 处理 字符/Backspace/Enter/Esc/Ctrl+C。
 * 关键：Enter 只 submit + 清输入，绝不直接写 transcript（用户行由 onUserMessage 统一落）。
 */
public final class CodeTuiView implements EventHandler, Renderer {
    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private Disposable current;   // 单飞：任一时刻至多一个活跃回合

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit) {
        this.state = state;
        this.onSubmit = onSubmit;
    }

    public void run() throws Exception {
        TuiConfig cfg = TuiConfig.builder().tickRate(Duration.ofMillis(33)).build();  // ~30fps
        try (TuiRunner runner = TuiRunner.create(cfg)) {
            runner.run(this, this);
        }
    }

    @Override
    public boolean handle(Event e, TuiRunner runner) {
        if (!(e instanceof KeyEvent k)) return false;
        if (k.isCtrlC()) { runner.quit(); return true; }
        if (k.isCancel()) {                       // Esc：UI 层取消当前回合
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            return true;
        }
        if (k.isConfirm()) {                      // Enter
            if (!state.isIdle()) return true;     // 单飞：上一回合进行中，忽略输入（状态栏已提示）
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);   // 不落 transcript
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        if (k.code() == KeyCode.CHAR) { state.typeChar(k.character()); return true; }
        return false;                             // 其它（含 TickEvent）交给 tickRate 触发重绘
    }

    @Override
    public void render(Frame f) {
        Rect a = f.area();
        int h = a.height();
        Rect body    = new Rect(a.x(), a.y(),        a.width(), Math.max(0, h - 2));
        Rect inputR  = new Rect(a.x(), a.y() + h - 2, a.width(), 1);
        Rect statusR = new Rect(a.x(), a.y() + h - 1, a.width(), 1);

        List<String> all = state.transcriptSnapshot();
        String shown = String.join("\n", tail(all, body.height()));
        f.renderWidget(Paragraph.from(shown), body);

        String prompt = "> " + state.currentInput();
        f.renderWidget(Paragraph.from(prompt), inputR);

        String hint = state.isIdle()
                ? "Enter 发送 · Esc 取消 · Ctrl+C 退出"
                : "上一回合进行中，Esc 取消后再输入 · Ctrl+C 退出";
        f.renderWidget(Paragraph.from(hint), statusR);

        f.setCursorPosition(inputR.x() + prompt.length(), inputR.y());  // 宽字符光标偏移暂简化
    }

    private static List<String> tail(List<String> lines, int n) {
        if (n <= 0 || lines.size() <= n) return lines;
        return lines.subList(lines.size() - n, lines.size());
    }
}
