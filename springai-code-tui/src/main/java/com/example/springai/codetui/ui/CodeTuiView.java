package com.example.springai.codetui.ui;

import com.example.springai.codetui.agent.SubmitHandler;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.text.CharWidth;
import dev.tamboui.style.Overflow;
import dev.tamboui.widgets.paragraph.Paragraph;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TamboUI 单栏视图。render 画「对话区/输入行/状态栏」；
 * event 处理 字符/Backspace/Enter/Esc/Ctrl+C。
 * 关键：Enter 只 submit + 清输入，绝不直接写 transcript（用户行由 onUserMessage 统一落）。
 *
 * 重绘机制（实测修正）：TamboUI 的 run() 循环【仅在 handle 返回 true 时才 render】，
 * tickRate 只负责按 ~30fps 把 TickEvent 投进事件队列，并不自动重绘。故 handle 必须对
 * TickEvent 返回 true——这才让「后台线程写状态 → 周期重绘刷出」成立（里程碑1 被动重绘闸门）。
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
        if (e instanceof TickEvent) return true;  // 周期重绘：run 循环仅在 handle 返回 true 时 render
        if (!(e instanceof KeyEvent k)) return false;
        if (k.isCtrlC()) { runner.quit(); return true; }
        if (k.isCancel()) {                       // Esc：UI 层取消当前回合
            boolean running = !state.isIdle();    // 真有回合在飞才算「取消」（自然完成后 current 可能是已 dispose 的陈旧句柄）
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.setNotice(running ? "已取消当前回合" : "Esc：当前无进行中回合");  // 状态栏可见反馈
            return true;
        }
        if (k.isConfirm()) {                      // Enter
            if (!state.isIdle()) return true;     // 单飞：上一回合进行中，忽略输入（状态栏已提示）
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);   // 不落 transcript
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        // 可打印输入：用 codePoint 支持 CJK/非 ASCII/星际字符，不再仅认 KeyCode.CHAR（否则中文被漏掉）
        int cp = k.codePoint();
        if (cp > 0 && !Character.isISOControl(cp) && !k.hasCtrl() && !k.hasAlt()) {
            state.typeString(new String(Character.toChars(cp)));
            return true;
        }
        return false;                             // 未知/未消费事件：不强制重绘
    }

    @Override
    public void render(Frame f) {
        Rect a = f.area();
        int h = a.height();
        Rect body    = new Rect(a.x(), a.y(),        a.width(), Math.max(0, h - 2));
        Rect inputR  = new Rect(a.x(), a.y() + h - 2, a.width(), 1);
        Rect statusR = new Rect(a.x(), a.y() + h - 1, a.width(), 1);

        int w = Math.max(1, body.width());

        // 对话区：每条逻辑行按显示宽度折成视觉行，再取末尾 body.height() 行 → 自动贴底跟随流式，
        // 不会因长行/流式增长把最新内容挤出屏幕。
        List<String> visual = new ArrayList<>();
        for (String line : state.transcriptSnapshot()) {
            visual.addAll(wrap(line, w));
        }
        f.renderWidget(clip(String.join("\n", tail(visual, body.height()))), body);

        // 输入行：过长时只显示尾部（光标在末尾），保证正在输入处可见，且不折到状态栏。
        String prefix = "> ";
        int inputAvail = Math.max(1, w - CharWidth.of(prefix));
        String inputText = state.currentInput();
        String shownInput = CharWidth.of(inputText) <= inputAvail
                ? inputText
                : CharWidth.substringByWidthFromEnd(inputText, inputAvail);
        String prompt = prefix + shownInput;
        f.renderWidget(clip(prompt), inputR);

        String base = state.isIdle()
                ? "Enter 发送 · Esc 取消 · Ctrl+C 退出"
                : "上一回合进行中，Esc 取消后再输入 · Ctrl+C 退出";
        String notice = state.notice();
        String hint = notice.isEmpty() ? base : (notice + " · " + base);
        f.renderWidget(clip(hint), statusR);

        // 光标列用显示宽度（CJK 占 2 列），而非字符数
        f.setCursorPosition(inputR.x() + CharWidth.of(prompt), inputR.y());
    }

    /** 定宽渲染：内容已按宽度折好，CLIP 防止 Paragraph 二次折行溢出到相邻区域。 */
    private static Paragraph clip(String s) {
        return Paragraph.builder().text(s).overflow(Overflow.CLIP).build();
    }

    /** 把逻辑行按显示宽度 width 折成视觉行（CharWidth 感知 CJK 双宽，不断开宽字符）。空行保留为一行。 */
    private static List<String> wrap(String line, int width) {
        List<String> rows = new ArrayList<>();
        if (line.isEmpty()) {
            rows.add("");
            return rows;
        }
        String rest = line;
        while (!rest.isEmpty()) {
            String row = CharWidth.substringByWidth(rest, width);
            if (row.isEmpty()) {          // 安全网：宽度容不下下一个字符时也吃一个，避免死循环
                row = rest.substring(0, 1);
            }
            rows.add(row);
            rest = rest.substring(row.length());
        }
        return rows;
    }

    private static List<String> tail(List<String> lines, int n) {
        if (n <= 0) return List.of();
        if (lines.size() <= n) return lines;
        return lines.subList(lines.size() - n, lines.size());
    }
}
