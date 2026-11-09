package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.tui.InlineEventHandler;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.InlineTuiRunner;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 示例③ 事件循环、按键绑定与粘贴 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>「按键仪表盘」：普通键回显 key 名；方向键经 {@link KeyTrigger} 绑定移动光标块；
 * 窗口缩放触发 {@link ResizeEvent} 重绘；粘贴走 {@link PasteEvent} 三段式教学
 * （正常 → 病 → 药方）。画帧复用示例②的立即模式手法（整帧 setString 进 Frame）。</p>
 *
 * <p>本步核心认知：<b>TUI 应用 = 事件循环 + 每事件按需重绘</b>——这正是 code-tui
 * 「空闲时零周期任务、零 ANSI 输出」的由来；而事件解析层不可靠时（大段粘贴被掐断、
 * CR 泄漏），就得像 code-tui 一样连 EventParser 一起 shadow（见 inline-patch 模块）。</p>
 */
public final class InlineEventsDemo implements TambouiDemo {

    /**
     * live 区高度。教学内容（含三段式教学块 7 行）画到第 12 行 + 底边框 1 行 = 14——
     * 高度不足时 Buffer.setString 对越界行<b>静默丢弃</b>（上游 0.4.0 实测），「药方」末行
     * 会无声消失（终审 Critical 修复：此前为 12，药方第二行每次运行都被丢弃）。
     * InlineEventsPaintTest 钉住「末行可见 + 底边框完整」。
     */
    static final int LIVE_HEIGHT = 14;

    private static final KeyTrigger UP = KeyTrigger.key(KeyCode.UP);
    private static final KeyTrigger DOWN = KeyTrigger.key(KeyCode.DOWN);
    private static final KeyTrigger LEFT = KeyTrigger.key(KeyCode.LEFT);
    private static final KeyTrigger RIGHT = KeyTrigger.key(KeyCode.RIGHT);

    /** 仪表盘状态。全部只在事件线程读写（单线程事件模型）。 */
    private String lastKey = "（按键试试）";
    private String pasteReport = "（粘贴试试）";
    private int cursorX = 10;
    private int cursorY = 8;
    private String sizeReport = "（缩放窗口试试）";

    @Override
    public int order() {
        return 3;
    }

    @Override
    public String title() {
        return "事件循环与按键绑定（粘贴三段式：正常→病→药方）";
    }

    @Override
    public void run() throws Exception {
        // bracketedPaste(true)：启用括号粘贴协议，粘贴才会作为 PasteEvent 整体到达
        // （否则被拆成逐键事件，与「慢慢打字」无法区分）。code-tui 同款开关。
        try (InlineTuiRunner runner = InlineTuiRunner.create(
                InlineTuiConfig.builder(LIVE_HEIGHT).bracketedPaste(true).build())) {

            Renderer renderer = frame -> paint(frame.buffer(), frame.width(), frame.height());

            InlineEventHandler handler = (Event event, InlineTuiRunner r) -> {
                // Java 17 无 switch 模式匹配（21 才有），用 instanceof 模式匹配链平替
                if (event instanceof KeyEvent key) {
                    lastKey = key.toString();
                    if (UP.matches(key)) cursorY = Math.max(3, cursorY - 1);
                    else if (DOWN.matches(key)) cursorY = Math.min(r.height() - 2, cursorY + 1);
                    else if (LEFT.matches(key)) cursorX = Math.max(1, cursorX - 2);
                    else if (RIGHT.matches(key)) cursorX = Math.min(r.width() - 2, cursorX + 2);
                    else if (key.isCharIgnoreCase('q') || key.isKey(KeyCode.ESCAPE)) r.quit();
                } else if (event instanceof PasteEvent paste) {
                    // 粘贴「正常」段：统计对了，说明括号粘贴协议把整段送到了
                    pasteReport = "收到粘贴：" + PasteStats.of(paste.text());
                } else if (event instanceof ResizeEvent resize) {
                    sizeReport = resize.width() + " × " + resize.height();
                }
                // 其余事件（tick 等）不感兴趣；返回 true 表示仪表盘吃掉所有事件（演示性质）
                return true;
            };

            runner.run(handler, renderer);
        }
    }

    /** 整帧重画仪表盘（立即模式：不看上一帧，全量描述）。包内可见供画布适配测试离屏调用。 */
    void paint(Buffer buf, int width, int height) {
        Rect area = new Rect(0, 0, width, height);
        dev.tamboui.widgets.block.Block block = dev.tamboui.widgets.block.Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(dev.tamboui.widgets.block.BorderType.ROUNDED)
                .build();
        block.render(area, buf);
        Rect inner = block.inner(area);

        int y = inner.y();
        buf.setString(inner.x(), y++, "按键仪表盘", Style.create().fg(Color.CYAN).bold());
        buf.setString(inner.x(), y++, "最近按键: " + lastKey, Style.create().fg(Color.WHITE));
        buf.setString(inner.x(), y++, "窗口尺寸: " + sizeReport, Style.create().fg(Color.GRAY));
        buf.setString(inner.x(), y++, "粘贴统计: " + pasteReport, Style.create().fg(Color.WHITE));
        buf.setString(inner.x(), y++, "方向键=移动◆  q/Esc=退出", Style.create().fg(Color.GRAY));
        buf.setString(inner.x(), y, "┌ 三段式粘贴教学 ─────────────────", Style.create().fg(Color.YELLOW));
        buf.setString(inner.x(), y + 1, "│ 1 正常：贴一小段多行文本（统计应正确——", Style.create().fg(Color.GRAY));
        buf.setString(inner.x(), y + 2, "│   终端把粘贴包在 ESC[200~..201~ 里整体送来）", Style.create().fg(Color.GRAY));
        buf.setString(inner.x(), y + 3, "│ 2 病：再贴一大段（几百行）——上游 50ms 静默", Style.create().fg(Color.RED));
        buf.setString(inner.x(), y + 4, "│   预算掐断，剩余字节回按键解析：统计错乱/按键乱跳", Style.create().fg(Color.RED));
        buf.setString(inner.x(), y + 5, "│ 3 药方：code-tui 的 inline-patch shadow 了", Style.create().fg(Color.GREEN));
        buf.setString(inner.x(), y + 6, "│   EventParser（静默预算 + CR 泄漏修复）", Style.create().fg(Color.GREEN));

        buf.set(cursorX, cursorY, new dev.tamboui.buffer.Cell("◆", Style.create().fg(Color.GREEN).bold()));
    }
}
