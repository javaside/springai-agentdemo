package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.tui.InlineEventHandler;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.InlineTuiRunner;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 示例② 立即模式与差分渲染 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>用 {@link InlineTuiRunner}（行内模式：内容钉在终端底部、上方照常滚动——code-tui
 * 同款模式）跑一个最小立即模式应用：每帧由 {@link BufferBlockArtist} 从零画出「完整的一屏」，
 * 库对前后两帧 Buffer 做差分、只把变化的 cell 发给终端。</p>
 *
 * <p>本步核心认知：<b>你只负责描述整屏应该长什么样，库负责算最小差量</b>——
 * 这就是立即模式（immediate mode）的心智模型：没有部件树、没有脏标记，
 * 每帧全量重画反而最简单可靠。</p>
 */
public final class BufferBlockDemo implements TambouiDemo {

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "Buffer 立即模式与差分渲染（Block 画框）";
    }

    @Override
    public void run() throws Exception {
        // 行内运行器：8 行高的 live 区钉在终端底部。AutoCloseable——try-with-resources
        // 保证任何退出路径都恢复终端（原始模式/回显），这是 TUI 应用的第一条纪律。
        try (InlineTuiRunner runner = InlineTuiRunner.create(InlineTuiConfig.defaults(8))) {
            // 每帧重画时被调：我们只把 Artist 画好的整帧 Buffer merge 进 Frame 的 buffer
            Renderer renderer = frame ->
                    frame.buffer().merge(BufferBlockArtist.draw(frame.width(), frame.height(), frameIndex), 0, 0);

            // 事件处理：返回 true 表示事件已消费。空格/回车推动帧，q/Esc 退出，其余事件
            // 交回 runner（如 resize 会自动触发重画——renderer 会用新尺寸再跑一遍）。
            // 注意字符键（空格、q）没有专用 KeyCode，统一以 CHAR 码到达，用 isChar 判定。
            InlineEventHandler handler = (Event event, InlineTuiRunner r) -> {
                if (event instanceof KeyEvent key) {
                    if (key.isChar(' ') || key.isKey(KeyCode.ENTER)) {
                        frameIndex++;
                        return true;
                    }
                    if (key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('q')) {
                        r.quit();
                        return true;
                    }
                }
                return false;
            };

            runner.run(handler, renderer);
        }
    }

    /** 当前帧号。事件线程里只有这个 handler 会改它（单线程事件模型，无需并发控制）。 */
    private long frameIndex;
}
