package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例③事件分派的「按需重绘」契约（真机滚屏事故的回归测试）。
 *
 * <p>事故根因：handler 曾对所有事件返回 true（消费即重绘），叠加默认 40ms tick——
 * 上游 0.4.0 的 InlineDisplay 没有差分，未变化的帧也全量重写所有行，25 帧/秒的
 * 全量重写在真实终端上表现为边框线不断滚入 scrollback（用户实报「无法使用」）。</p>
 *
 * <p>契约：只有<b>改变了仪表盘状态</b>的事件才返回 true（触发重绘）；tick 等与状态
 * 无关的事件一律 false。配合 {@code noTick()} 后空闲期零输出——这正是本示例 javadoc
 * 承诺、code-tui 实际采用的「事件驱动重绘」。</p>
 */
class InlineEventsHandlerTest {

    @Test
    void tickDoesNotRequestRedraw() {
        InlineEventsDemo demo = new InlineEventsDemo();
        assertFalse(demo.onEvent(new TickEvent(0, Duration.ZERO), null),
                "tick 没有改变任何状态，返回 true 会触发上游全量重写（滚屏事故根因）");
    }

    @Test
    void keyEventRequestsRedraw() {
        InlineEventsDemo demo = new InlineEventsDemo();
        assertTrue(demo.onEvent(KeyEvent.ofChar('x'), null));
    }

    @Test
    void pasteEventRequestsRedraw() {
        InlineEventsDemo demo = new InlineEventsDemo();
        assertTrue(demo.onEvent(new PasteEvent("粘贴"), null));
    }

    @Test
    void resizeEventRequestsRedraw() {
        InlineEventsDemo demo = new InlineEventsDemo();
        assertTrue(demo.onEvent(ResizeEvent.of(80, 24), null));
    }
}
