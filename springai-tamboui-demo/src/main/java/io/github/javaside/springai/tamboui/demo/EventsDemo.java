package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.list.ListState;
import io.github.javaside.springai.tamboui.TambouiDemo;

import java.time.Duration;

/**
 * 第 6 课 事件与交互 —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>在上一课（布局）的同一块面板上加交互：j/k（或 ↓/↑）移动课程选中，TickEvent 驱动 Gauge
 * 走动。骨架是 TUI 交互的全部范式：</p>
 *
 * <pre>事件 → 状态迁移（EventDashboardState）→（状态变了才）返回 true 请求重绘 → 下一帧自然反映</pre>
 *
 * <p>tickRate(100ms) 让 TickEvent 周期到达；事件自带真实时长（elapsedSeconds()），
 * 本课按计数简化。状态变化与否由状态机回答，渲染函数永远只看状态。另：窗口缩放
 * 由框架自动触发重绘（渲染函数经 frame.area() 拿到新尺寸），不需要自己处理。</p>
 *
 * <p><b>本课核心认知</b>：事件只改状态，「请求重绘」是状态通往屏幕的唯一桥梁；
 * 按需重绘（没变就返回 false）是 TUI 的基本纪律。</p>
 */
public final class EventsDemo implements TambouiDemo {

    /** 全部状态集中在一个纯逻辑对象（可单测）；ListState 与它保持同步。 */
    private final EventDashboardState state = new EventDashboardState();
    private final ListState listState = new ListState();

    @Override
    public int order() {
        return 6;
    }

    @Override
    public String title() {
        return "事件与交互（j/k 移动选中 · Tick 驱动 Gauge · 状态驱动重绘）";
    }

    @Override
    public void run() throws Exception {
        listState.select(state.lessonIndex());
        try (TuiRunner runner = TuiRunner.create(
                TuiConfig.builder().tickRate(Duration.ofMillis(100)).build())) {

            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key) {
                    if (key.isQuit()) {
                        r.quit();
                        return false;
                    }
                    boolean changed = state.onKey(key);
                    listState.select(state.lessonIndex());   // 状态对象是唯一事实源，ListState 只是渲染配件
                    return changed;
                }
                if (event instanceof TickEvent) {
                    state.onTick();   // Gauge 前进——动画 = 状态随脉冲迁移 + 重绘
                    return true;
                }
                return false;
            };
            runner.run(handler, this::paint);
        }
    }

    private void paint(Frame frame) {
        DashboardPainter.paint(frame, listState, state.gaugePercent(),
                "最近按键: " + state.lastKey() + " | j/k 或 ↓/↑ 移动 | q / Ctrl+C 退出");
    }
}
