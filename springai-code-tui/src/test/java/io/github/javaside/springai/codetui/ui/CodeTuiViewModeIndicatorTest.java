package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限模式的<b>常驻</b>标识：切换后随便按个键，状态栏仍要显示当前档位。
 *
 * <p><b>钉的是一个真实缺陷</b>：此前模式只出现在三个会消失的地方——Shift+Tab 的 sticky notice
 * （下一次按键即被清）、{@code /permissions} 报告与 BYPASS 启动横幅（滚进 scrollback）。
 * 于是切到「自动接受编辑」后按一下键，用户就再也看不出自己在哪一档。
 *
 * <p><b>为什么把元素真渲染进 Buffer 而不是只测 {@link CodeTuiView#modeTag}</b>：
 * 纯函数测试只证明「标识构造得出来」，证不了它<b>出现在屏幕上</b>——而缺陷恰恰是
 * {@code statusLine()} 的分支顺序（notice 分支提前 return）把它挡掉。断言必须落在渲染结果上，
 * 否则就是又一个「不会失败的测试」。真实 ANSI 与路由器那一层仍由 pty 冒烟兜底。
 */
class CodeTuiViewModeIndicatorTest {

    private static final String ACCEPT_TAG = "⏵⏵ 自动接受编辑";
    private static final String BYPASS_TAG = "⚠ 跳过权限检查";

    /** 可切到任意档的桩（{@code bypassAllowed} 模拟 --dangerously-skip-permissions 启动）。 */
    private static class Stub implements SubmitHandler {
        PermissionMode mode = PermissionMode.DEFAULT;
        boolean bypassAllowed;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public PermissionMode cyclePermissionMode() { mode = mode.next(bypassAllowed); return mode; }
    }

    private static KeyEvent shiftTab() {
        return KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT);
    }

    /**
     * 把整棵 UI 渲染进一块离屏 Buffer，回读成文本。
     *
     * <p>宽度取 120：状态行接近终端宽度，窄了会被截断，断言就会因为「没渲染」还是「被截掉」分不清而失真。
     * 宽字符占两格且第二格是 {@code CONTINUATION}（symbol 为空），必须跳过，否则回读文本里会多出空格、
     * 子串匹配全部落空。
     */
    private static String screen(CodeTuiView v) {
        Buffer buf = Buffer.empty(new Rect(0, 0, 120, 12));
        Frame f = Frame.forTesting(buf);
        // 元素注册要求「必须在渲染线程」（RenderThread.checkRenderThread）。库只暴露包私有的
        // markAsRenderThread，测试里反射打标——本项目对 TUI 内部已有同类先例（/clear 反射进私有 Backend）。
        // 不打标就只能退回「只测纯函数」，而那恰恰测不到本用例要测的东西（标识有没有真的进屏幕）。
        onRenderThread(() -> v.renderForTest().render(f, f.area(), RenderContext.empty()));
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

    /** 在「已标记为渲染线程」的状态下跑一段，跑完复位（不复位会污染同 JVM 内的后续用例）。 */
    private static void onRenderThread(Runnable body) {
        try {
            Method mark = Class.forName("dev.tamboui.tui.RenderThread")
                    .getDeclaredMethod("markAsRenderThread");
            Method clear = Class.forName("dev.tamboui.tui.RenderThread")
                    .getDeclaredMethod("clearRenderThread");
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
                    "RenderThread 的包私有打标方法不在了——库升级后本测试的渲染路径需要重新对齐", e);
        }
    }

    @Test
    @DisplayName("切到「自动接受编辑」后按键清掉 notice，标识仍常驻状态栏")
    void tagSurvivesNoticeBeingCleared(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Stub stub = new Stub();
        CodeTuiView v = new CodeTuiView(state, stub, root);

        assertTrue(!screen(v).contains("⏵⏵"), "DEFAULT 是常态，不该有任何模式标识占位");

        v.feedKeyForTest(shiftTab());
        assertEquals(PermissionMode.ACCEPT_EDITS, stub.mode);
        assertTrue(screen(v).contains("权限模式：自动接受编辑"), "切换当下应有 notice 反馈");

        // 关键一步：notice 被下一次按键清掉之后——这正是修复前模式彻底消失的时刻。
        v.feedKeyForTest(KeyEvent.ofChar('a'));
        assertEquals("", state.notice(), "前提：下一次按键确实清掉了 notice（否则本用例测的不是常驻）");
        assertTrue(screen(v).contains(ACCEPT_TAG),
                "notice 清掉后模式标识必须仍在状态栏，否则用户无从得知自己在哪一档:\n" + screen(v));

        // 切回 DEFAULT 后标识必须消失——常态不该有噪声，也防「标识粘住不更新」。
        v.feedKeyForTest(shiftTab());
        v.feedKeyForTest(KeyEvent.ofChar('b'));
        assertEquals(PermissionMode.DEFAULT, stub.mode);
        assertTrue(!screen(v).contains("⏵⏵"), "切回 DEFAULT 后标识必须消失");
    }

    @Test
    @DisplayName("BYPASS 常驻标识用红色警示，且不随启动横幅滚走")
    void bypassTagIsPersistentAndRed(@TempDir Path root) {
        ConversationState state = new ConversationState();
        Stub stub = new Stub();
        stub.bypassAllowed = true;
        stub.mode = PermissionMode.BYPASS;
        CodeTuiView v = new CodeTuiView(state, stub, root);

        assertTrue(screen(v).contains(BYPASS_TAG),
                "BYPASS 是最该持续可见的一档，启动横幅只出现一次不够:\n" + screen(v));

        Span tag = CodeTuiView.modeTag(PermissionMode.BYPASS);
        assertNotNull(tag);
        assertEquals(Theme.MODE_BYPASS, tag.style(), "BYPASS 必须用红色加粗，与 ACCEPT_EDITS 的暖橙区分开");
    }

    @Test
    @DisplayName("modeTag：DEFAULT 不显示，其余两档各有独立样式")
    void modeTagPerMode() {
        assertNull(CodeTuiView.modeTag(PermissionMode.DEFAULT), "常态不占位");
        assertEquals(Theme.MODE_ACCEPT, CodeTuiView.modeTag(PermissionMode.ACCEPT_EDITS).style());
        assertTrue(CodeTuiView.modeTag(PermissionMode.ACCEPT_EDITS).content().contains("自动接受编辑"));
        assertTrue(CodeTuiView.modeTag(PermissionMode.BYPASS).content().contains("跳过权限检查"));
    }
}
