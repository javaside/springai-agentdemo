package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 1 课 Hello TUI——渲染模型 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p><b>这一课要建立的三句话</b>（后面五课全部踩在它上面）：</p>
 * <ol>
 *   <li>终端屏幕是一张<b>字符网格</b>（Buffer = Cell 二维数组，一格一个字符+样式）；</li>
 *   <li>你的程序每次只做一件事：<b>把「这一屏现在该长什么样」整张画出来</b>——不留旧状态，
 *       画面永远是当前状态的完整描述（“UI 是状态的纯函数”）；</li>
 *   <li>框架拿本帧与上一帧对比，<b>只把变化的格子发给终端</b>（差分渲染）。</li>
 * </ol>
 *
 * <p><b>怎么让这三件事亲眼发生</b>：按<b>空格</b>在「帧 A ↔ 帧 B」之间切换——你只改了
 * 一个状态位，画面跟着整屏重画（第 2 句）；退出后并排打印两帧的网格与
 * {@link FrameDiffView}（■=变化的格子）——那正是框架替你做的事（第 3 句）。</p>
 *
 * <p>全屏模式（{@link TuiRunner} + 备用屏幕）：运行期间接管终端，退出即复原。
 * 默认退出键 q/Q/Ctrl+C（Esc 绑定的是 cancel 动作）。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** 状态只有一个位：显示帧 A 还是帧 B。第 4 课会把「状态驱动 UI」展开成完整范式。 */
    private boolean showFrameB;

    /** 退出后打印教具用的两帧快照（渲染线程写、run() 返回后主线程读，先后有序）。 */
    private Buffer lastFrameA;
    private Buffer lastFrameB;

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String title() {
        return "Hello TUI——渲染模型（空格切两帧，退出看 diff）";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            // 渲染函数：只看状态、整屏重画——不看上一帧、不算增量（那是框架的事）
            dev.tamboui.tui.Renderer renderer = this::paint;
            // 事件处理：返回值 = 是否需要重绘。空格翻转状态位（变了→true），退出键退出。
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                    return false;
                }
                if (event instanceof KeyEvent key && key.isChar(' ')) {
                    showFrameB = !showFrameB;
                    return true;   // 状态变了 → 请求重绘（下一帧自然反映新状态）
                }
                return false;
            };
            runner.run(handler, renderer);
        }

        printTakeaway();
    }

    /** 画一帧：整屏描述当前状态（帧 A 或帧 B，只差一个状态位）。 */
    private void paint(Frame frame) {
        String which = showFrameB ? "帧 B" : "帧 A";
        String dotAt = showFrameB ? "                    ◆（帧 B：◆ 挪到了右边）" : "◆（帧 A）";
        Text text = Text.from(
                Line.from(Span.styled("你好，TamboUI！现在是 " + which, Style.create().fg(Color.CYAN).bold())).centered(),
                Line.from(Span.raw("")).centered(),
                Line.from(Span.styled(dotAt, Style.create().fg(Color.GREEN))).centered(),
                Line.from(Span.raw("")).centered(),
                Line.from(Span.styled("按【空格】在帧 A ↔ 帧 B 之间切换——注意：代码只翻转一个布尔位，画面整屏重画",
                        Style.create().fg(Color.GRAY))).centered(),
                Line.from(Span.styled("退出后将打印：两帧的网格 + diff 视图（■=框架实际发给终端的格子）",
                        Style.create().fg(Color.GRAY))).centered(),
                Line.from(Span.styled("按 q / Ctrl+C 退出（Esc 是框架的 cancel 动作，不退出）",
                        Style.create().fg(Color.YELLOW))).centered()
        );
        frame.renderWidget(Paragraph.builder().text(text).build(), frame.area());
        // 留两帧快照：退出后的教学打印用。没按过空格时帧 B 快照缺失，退场时离屏补画一份。
        if (showFrameB) {
            lastFrameB = frame.buffer().copy();
        } else {
            lastFrameA = frame.buffer().copy();
        }
    }

    /** 退出全屏后的教学打印：两帧网格 + diff 视图，把渲染模型三句话钉在眼睛上。 */
    private void printTakeaway() {
        // 若某一帧没被画过（用户没按空格），离屏补画——教具必须两帧齐全
        if (lastFrameA == null || lastFrameB == null) {
            boolean cur = showFrameB;
            for (boolean b : new boolean[]{false, true}) {
                if (b == cur ? (b ? lastFrameB : lastFrameA) == null : false) {
                    continue;
                }
                Buffer off = Buffer.empty(new Rect(0, 0, 80, 12));
                Frame fake = Frame.forTesting(off);
                boolean keep = showFrameB;
                showFrameB = b;
                paint(fake);
                showFrameB = keep;
            }
        }
        System.out.println("\n—— 刚才两帧，在 Buffer（字符网格）里的样子 ——");
        System.out.println("（一格一个字符；· 是宽字符的续格。网格视角下「屏幕」不再神秘）");
        if (lastFrameA != null) {
            System.out.println("帧 A：");
            FrameDump.dump(lastFrameA).forEach(l -> System.out.println("  " + l));
        }
        if (lastFrameB != null) {
            System.out.println("帧 B：");
            FrameDump.dump(lastFrameB).forEach(l -> System.out.println("  " + l));
        }
        if (lastFrameA != null && lastFrameB != null) {
            System.out.println("\n—— 两帧的 diff（■ = 框架实际发给终端的格子，其余一个字节都不发）——");
            FrameDiffView.between(lastFrameA, lastFrameB).forEach(l -> System.out.println("  " + l));
        }
        System.out.println("""

                本课核心认知（后面五课都踩在这上面）：
                1. 屏幕 = 字符网格（Buffer），一格一个 Cell；
                2. 你的 render 只管「整屏描述当前状态」——翻转一个布尔位，就是重画整屏的理由；
                3. 框架做 diff，只把 ■ 的格子发给终端。
                第 3 课组件的 render(Rect, Buffer) 是「往网格的一块区域画」；第 4 课的
                「改状态→请求重绘」之所以成立，正因为重绘 = 重新描述整屏。
                """);
    }
}
