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
import java.util.ArrayList;
import java.util.List;

/**
 * Claude Code 式<b>行内</b>视图，基于 TamboUI {@link InlineTuiRunner}：
 * <ul>
 *   <li>已定稿的输出行用 {@code println} 推进终端 scrollback —— 自然向上滚动、留在终端原生历史里可上翻，
 *       不再是「固定一屏」；</li>
 *   <li>只有底部一小块 live 区（在建助手行预览 + 输入行 + 状态行）原地重绘。</li>
 * </ul>
 *
 * 重绘：run 循环仅在 handle 返回 true 时 render，故 handle 对 {@link TickEvent} 返回 true；
 * 每个 tick 顺带把 {@code state} 里的定稿行 println 出去，并按在建助手行高度调整 live 区。
 * 关键：Enter 只 submit + 清输入，绝不直接落输出（用户行由 onUserMessage 统一进 pending）。
 */
public final class CodeTuiView implements InlineEventHandler, Renderer {

    /** live 区流式预览最多占多少行（超出只显示尾部；完整内容定稿后进 scrollback）。 */
    private static final int STREAM_PREVIEW_CAP = 10;

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private Disposable current;      // 单飞：任一时刻至多一个活跃回合
    private int lastHeight = -1;     // 记录上次 live 高度，仅在变化时 setContentHeight

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit) {
        this.state = state;
        this.onSubmit = onSubmit;
    }

    public void run() throws Exception {
        InlineTuiConfig cfg = InlineTuiConfig.builder(2).tickRate(Duration.ofMillis(33)).build();
        try (InlineTuiRunner runner = InlineTuiRunner.create(cfg)) {
            runner.run(this, this);
        }
    }

    @Override
    public boolean handle(Event e, InlineTuiRunner runner) {
        if (e instanceof TickEvent) {
            // 1) 定稿行推进 scrollback（终端自动换行，无需手动折行）
            for (String line : state.drainPending()) {
                runner.println(line);
            }
            // 2) 按在建助手行需要的行数调整 live 高度（预览行 + 输入 + 状态）
            int w = Math.max(1, runner.width());
            int preview = Math.min(STREAM_PREVIEW_CAP, wrap(state.streaming(), w).size());
            int h = 2 + preview;
            if (h != lastHeight) {
                runner.setContentHeight(h);
                lastHeight = h;
            }
            return true;
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
            if (!state.isIdle()) return true;     // 单飞：上一回合进行中，忽略输入
            String text = state.takeInput();
            if (!text.isBlank()) current = onSubmit.submit(text);   // 不直接落输出
            return true;
        }
        if (k.isDeleteBackward()) { state.backspace(); return true; }
        // 可打印输入：用 codePoint 支持 CJK/非 ASCII/星际字符
        int cp = k.codePoint();
        if (cp > 0 && !Character.isISOControl(cp) && !k.hasCtrl() && !k.hasAlt()) {
            state.typeString(new String(Character.toChars(cp)));
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame f) {
        Rect a = f.area();          // live 区
        int w = Math.max(1, a.width());
        int h = a.height();
        int x = a.x();
        int y = a.y();

        int previewH = Math.max(0, h - 2);
        Rect previewR = new Rect(x, y, a.width(), previewH);
        Rect inputR = new Rect(x, y + h - 2, a.width(), 1);
        Rect statusR = new Rect(x, y + h - 1, a.width(), 1);

        if (previewH > 0) {
            List<String> rows = wrap(state.streaming(), w);
            f.renderWidget(clip(String.join("\n", tail(rows, previewH))), previewR);
        }

        String prefix = "> ";
        int avail = Math.max(1, w - CharWidth.of(prefix));
        String inputText = state.currentInput();
        String shownInput = CharWidth.of(inputText) <= avail
                ? inputText
                : CharWidth.substringByWidthFromEnd(inputText, avail);
        String prompt = prefix + shownInput;
        f.renderWidget(clip(prompt), inputR);

        f.renderWidget(clip(statusLine()), statusR);
        f.setCursorPosition(inputR.x() + CharWidth.of(prompt), inputR.y());
    }

    private String statusLine() {
        String notice = state.notice();
        if (!notice.isEmpty()) return notice + " · Ctrl+C 退出";
        return switch (state.status()) {
            case IDLE -> "Enter 发送 · Esc 取消 · Ctrl+C 退出";
            case THINKING -> "思考中… · Esc 取消 · Ctrl+C 退出";
            case RUNNING_TOOL -> "运行工具 " + state.activeTool() + "… · Esc 取消 · Ctrl+C 退出";
        };
    }

    /** 定宽渲染：CLIP 防止 Paragraph 二次折行溢出相邻行。 */
    private static Paragraph clip(String s) {
        return Paragraph.builder().text(s).overflow(Overflow.CLIP).build();
    }

    /** 把逻辑行按显示宽度 width 折成视觉行（CharWidth 感知 CJK 双宽，不断开宽字符）。空串 → 0 行。 */
    private static List<String> wrap(String line, int width) {
        List<String> rows = new ArrayList<>();
        if (line.isEmpty()) return rows;
        String rest = line;
        while (!rest.isEmpty()) {
            String row = CharWidth.substringByWidth(rest, width);
            if (row.isEmpty()) row = rest.substring(0, 1);   // 安全网：容不下一个宽字符时也吃一个，避免死循环
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
