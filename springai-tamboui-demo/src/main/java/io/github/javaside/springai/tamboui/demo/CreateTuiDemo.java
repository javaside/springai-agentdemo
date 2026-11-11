package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 1 课 创建 TUI 应用 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>学一个框架的第一件事：知道<b>怎么创建应用、有哪几种方法</b>。本示例本身就是
 * 「方式②」的最小实跑——屏幕上这张对照表即它的渲染产物；五种方式的完整最小代码
 * 与跟做步骤见模块 README 第 1 课。</p>
 *
 * <p>五种方式一览（两轴：全屏/行内 × 层次：声明式/事件循环；另有最底层立即模式）：</p>
 * <ol>
 *   <li>全屏·声明式：{@code extends ToolkitApp} → {@code run()}（第 7 课主角）</li>
 *   <li>全屏·事件循环：{@code TuiRunner.create()} + {@code run(handler, renderer)}（本课）</li>
 *   <li>行内·声明式：{@code extends InlineApp}（第 8 课主角）</li>
 *   <li>行内·事件循环：{@code InlineTuiRunner.create(config)} + run</li>
 *   <li>立即模式：{@code Terminal} + {@code Backend} 全手工（底层，知道即可）</li>
 * </ol>
 *
 * <p><b>本课核心认知</b>：方式越靠上越省事（框架托管越多），越靠下控制力越强；
 * 大多数应用从方式①开始，本课用方式②是因为它把「事件处理」和「画帧」两条腿
 * 摆得最直观。</p>
 */
public final class CreateTuiDemo implements TambouiDemo {

    @Override
    public int order() {
        return 1;
    }

    @Override
    public String title() {
        return "创建 TUI 应用——五种方式对照（本程序=方式②最小实跑）";
    }

    @Override
    public void run() throws Exception {
        // 这 4 行就是方式②的最小骨架：create（接管终端）→ run（事件循环）→ try 关闭自动复原
        try (TuiRunner runner = TuiRunner.create()) {
            Renderer renderer = this::paint;
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                }
                return false;
            };
            runner.run(handler, renderer);
        }
    }

    /**
     * 画一帧：五种创建方式对照表。
     *
     * <p>有意只用纯字符串（{@code Paragraph.from(String)}）——样式、富文本是第 3 课的内容，
     * 第 1 课的示例不允许出现还没教过的 API。</p>
     */
    private void paint(Frame frame) {
        String text = "第 1 课 · 创建 TUI 应用\n"
                + "\n"
                + "五种创建方式（两轴：全屏/行内 × 声明式/事件循环）：\n"
                + "  ① 全屏·声明式    extends ToolkitApp → run()              大多数应用首选（第 7 课）\n"
                + "  ② 全屏·事件循环  TuiRunner.create() + run(handler, renderer)   本程序=此式\n"
                + "  ③ 行内·声明式    extends InlineApp → run()               聊天式 CLI（第 8 课）\n"
                + "  ④ 行内·事件循环  InlineTuiRunner.create(config) + run    行内 + 自定义事件\n"
                + "  ⑤ 立即模式       Terminal + Backend 全手工              最底层，知道即可\n"
                + "\n"
                + "本程序就是方式②的最小实跑；完整最小代码与讲解见模块 README 第 1 课\n"
                + "\n"
                + "按 q / Ctrl+C 退出";
        frame.renderWidget(Paragraph.from(text), frame.area());
    }
}
