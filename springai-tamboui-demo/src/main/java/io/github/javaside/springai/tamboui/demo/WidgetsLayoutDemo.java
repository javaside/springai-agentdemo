package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.list.ListState;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 3 课 组件与布局 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>全屏静态面板：头部 Paragraph + Block 底边框，中段左右两栏（ListWidget 课程目录 +
 * Gauge 进度条），尾部提示。布局由 {@link DashboardLayout} 用 Layout/Constraint 切分，
 * 绘制由 {@link DashboardPainter} 完成——demo 类本身只剩装配。</p>
 *
 * <p><b>本课核心认知</b>：先把屏幕切成区域（Layout.split + Constraint），再往每个区域放
 * 组件；组件分无状态（Widget）与有状态（StatefulWidget，状态外部持有——如 ListState）两种。</p>
 */
public final class WidgetsLayoutDemo implements TambouiDemo {

    /** List 状态外置（StatefulWidget 的教学习惯）；本课静态，选中固定第 3 行。 */
    private final ListState listState = new ListState();

    @Override
    public int order() {
        return 3;
    }

    @Override
    public String title() {
        return "组件与布局（Block / List / Gauge / Layout 切分）";
    }

    @Override
    public void run() throws Exception {
        listState.select(2);   // 高亮「第 3 课」那行
        try (TuiRunner runner = TuiRunner.create()) {
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                }
                return false;
            };
            runner.run(handler, this::paint);
        }
    }

    private void paint(Frame frame) {
        DashboardPainter.paint(frame, listState);
    }
}
