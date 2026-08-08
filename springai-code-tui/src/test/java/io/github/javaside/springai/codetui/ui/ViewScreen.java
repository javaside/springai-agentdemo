package io.github.javaside.springai.codetui.ui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;

import java.lang.reflect.Method;

/**
 * 把整棵 UI 渲染进一块<b>离屏 Buffer</b> 并回读成文本，供单测对「屏幕上到底有什么」下断言。
 *
 * <p><b>为什么需要它</b>：视图里不少缺陷是<b>分支顺序</b>问题——内容构造得出来，却被前面某个分支
 * 提前 return 挡掉了（如 {@code statusLine()} 的 notice 分支盖掉权限模式标识）。只测那个被挡住的
 * 纯函数永远是绿的，是典型的「不会失败的测试」。断言必须落在渲染结果上。
 *
 * <p><b>覆盖边界</b>：本类只覆盖「元素树 → Buffer」这一段。真实 ANSI 输出、{@code InlineDisplay}
 * 的相对光标记账、{@code EventRouter} 的按键分发都<b>不在其中</b>——凡是经路由器的按键行为，
 * 单测绿不构成证据，仍须 pty 实机（见 {@code permission_smoke.py}）。
 */
final class ViewScreen {

    private ViewScreen() {
    }

    /**
     * 渲染并回读屏幕文本。
     *
     * <p>宽度取 120：状态行本就接近终端宽度，窄了会被截断，断言失败时分不清是「没渲染」还是
     * 「被截掉」。宽字符占两格且第二格是 {@code CONTINUATION}（symbol 为空），必须跳过——
     * 否则回读文本里多出空格，子串匹配全部落空。
     */
    static String of(CodeTuiView view) {
        return of(view, 120);
    }

    /**
     * 指定宽度回读。
     *
     * <p><b>默认 120 会漏掉一整类缺陷</b>：状态行的内容是按 {@code terminalWidth()} 拼的，
     * 宽终端下什么都放得下，「重要的那段被不重要的挤出屏幕」永远测不出来。
     * 实测 {@code Task} 长入参在 80 列下会把「插话 N 条 · Esc 取消」整段挤没，
     * 而当时的状态栏测试用的是 {@code "{}"} 两字符入参 + 120 列——两个条件各绕开一半。
     *
     * <p>⚠ 这里只改<b>回读缓冲区</b>的宽度；视图内部 {@code terminalWidth()} 在测试里
     * 取不到真终端、恒为 80。故断言「窄终端下放不放得下」时传 80 才与内部一致。
     */
    static String of(CodeTuiView view, int width) {
        Buffer buf = bufferOf(view, width);
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < buf.height(); y++) {
            for (int x = 0; x < buf.width(); x++) {
                Cell c = buf.get(x, y);
                if (c.isContinuation()) continue;
                sb.append(c.symbol().isEmpty() ? " " : c.symbol());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 同 {@link #of}，但返回渲染后的 {@link Buffer} 本身——回读文本丢掉了样式，而有些断言必须落在样式上
     * （如「选中高亮不得带底色」：底色在本 TUI 的 InlineDisplay 下会串到下一项，只看文本永远绿）。
     */
    static Buffer bufferOf(CodeTuiView view) {
        return bufferOf(view, 120);
    }

    /**
     * 渲染并回读<b>硬件光标</b>落点（{@code frame.setCursorPosition} 的最终值）；未设置返回 null。
     *
     * <p>硬件光标不是可见光标（那是反显块，落在 Buffer 样式里），但它是 IME 预编辑串的锚点
     * ——Terminal.app 把拼音画在硬件光标处，停错行拼字就「错位」。这类断言只能落在 Frame 上。
     */
    static dev.tamboui.layout.Position cursorOf(CodeTuiView view, int width) {
        Buffer buf = Buffer.empty(new Rect(0, 0, width, 20));
        Frame f = Frame.forTesting(buf);
        onRenderThread(() -> view.renderForTest().render(f, f.area(), RenderContext.empty()));
        return f.cursorPosition().orElse(null);
    }

    static Buffer bufferOf(CodeTuiView view, int width) {
        Buffer buf = Buffer.empty(new Rect(0, 0, width, 20));
        Frame f = Frame.forTesting(buf);
        onRenderThread(() -> view.renderForTest().render(f, f.area(), RenderContext.empty()));
        return buf;
    }

    /**
     * 在「已标记为渲染线程」的状态下跑一段，跑完复位。
     *
     * <p>元素注册要求必须在渲染线程（{@code RenderThread.checkRenderThread}），而
     * {@code markAsRenderThread}/{@code clearRenderThread} 是<b>包私有</b>的，故反射打标——
     * 本项目对 TUI 内部已有同类先例（{@code /clear} 反射进私有 Backend）。
     * 不复位会污染同 JVM 内的后续用例。
     */
    private static void onRenderThread(Runnable body) {
        try {
            Class<?> rt = Class.forName("dev.tamboui.tui.RenderThread");
            Method mark = rt.getDeclaredMethod("markAsRenderThread");
            Method clear = rt.getDeclaredMethod("clearRenderThread");
            mark.setAccessible(true);
            clear.setAccessible(true);
            mark.invoke(null);
            try {
                body.run();
            } finally {
                clear.invoke(null);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "RenderThread 的包私有打标方法不在了——库升级后本工具的渲染路径需要重新对齐", e);
        }
    }
}
