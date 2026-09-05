package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目名的<b>常驻</b>展示：状态行行尾在空闲/思考/跑工具三种常态下都带着启动目录名
 * （多开 code-tui 窗口靠它区分「哪个窗口在跑哪个项目」）。
 *
 * <p><b>定位沿革</b>：先做的 tab 标题方案（v1.20.1）被实际使用推翻——终端普遍截短 tab
 * 标题，项目名多数场景保不住，且与提醒文案互相挤压。v1.21 口径：tab 只承担提醒
 * （见 {@link AttentionTrackerTest}），项目名常驻状态行行尾。
 *
 * <p><b>为什么把元素真渲染进 Buffer 而不是只测拼接函数</b>：与
 * {@link CodeTuiViewModeIndicatorTest} 同理——纯函数测试证明「后缀构造得出来」，
 * 证不了它<b>出现在屏幕上</b>（statusLine 的分支顺序、宽度让位逻辑都可能把它挤掉）。
 * 断言必须落在渲染结果上。
 */
class CodeTuiViewProjectNameStatusTest {

    /**
     * @TempDir 的随机目录名（junit-131125301…）超 24 显示宽会被截断纪律裁掉，断言没法用；
     * 测试统一在 @TempDir 下再建一个固定短名目录当工作区根，目录名可控、稳定不截断。
     */
    private static final String PROJECT = "demo-proj";

    private static CodeTuiView view(ConversationState s, Path root) {
        SubmitHandler stub = new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
            @Override public String currentModel() { return "test-model"; }
        };
        return new CodeTuiView(s, stub, root);
    }

    /** 短名工作区根：目录名 9 列，不触发截断。 */
    private static Path projectRoot(Path tmp) {
        return tmp.resolve(PROJECT);
    }

    @Test
    @DisplayName("空闲态：状态行行尾带启动目录名")
    void idleShowsProjectName(@TempDir Path tmp) {
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s, projectRoot(tmp));
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains(PROJECT), "空闲状态行应含项目名:\n" + screen);
    }

    @Test
    @DisplayName("思考中：项目名仍在（状态行被波光占用也不挤掉项目名）")
    void thinkingShowsProjectName(@TempDir Path tmp) {
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s, projectRoot(tmp));
        s.onUserMessage(1L, "hi");
        s.onTurnStarted(1L);

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("思考中"), screen);
        assertTrue(screen.contains(PROJECT), "思考中状态行应含项目名:\n" + screen);
    }

    @Test
    @DisplayName("跑工具：项目名仍在行尾（fitToolSummary 让位的只是工具摘要，不是项目名）")
    void runningToolShowsProjectName(@TempDir Path tmp) {
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s, projectRoot(tmp));
        s.onUserMessage(1L, "hi");
        s.onTurnStarted(1L);
        s.onToolStarted(1L, "Read", "{\"path\":\"/tmp/x\"}");

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("运行 Read"), screen);
        assertTrue(screen.contains(PROJECT), "跑工具状态行应含项目名:\n" + screen);
    }

    @Test
    @DisplayName("模态接管状态行：不显示项目名（临时覆盖层，收起自然回来，modeTag 同例）")
    void modalDoesNotShowProjectName(@TempDir Path tmp) {
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s, projectRoot(tmp));
        s.onUserMessage(1L, "hi");
        s.onTurnStarted(1L);
        // slash 补全菜单接管状态行（statusLine 的 slashMenuActive 分支）
        v.feedKeyForTest(dev.tamboui.tui.event.KeyEvent.ofChar('/'));
        v.tickForTest();

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("Esc 关闭"), "前提：菜单确实接管了状态行\n" + screen);
        assertFalse(screen.contains(PROJECT),
                "菜单/模态的临时状态行不该带项目名:\n" + screen);
    }

    @Test
    @DisplayName("超长目录名截断：项目名不超 24 显示宽（80 列终端下不挤掉 Esc 取消等关键提示）")
    void overlyLongNameIsTruncated(@TempDir Path tmp) {
        Path deep = tmp.resolve("a-very-long-project-directory-name-exceeding-limit");
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s, deep);

        // 默认 120 列渲染：截断纪律（CharWidth 计 24 列上限）与终端宽度无关，
        // 但 80 列下帮助组会把项目名整段挤出屏——那是「被动挨截」的正确行为，不是截断纪律失效。
        String screen = ViewScreen.of(v);
        assertFalse(screen.contains("a-very-long-project-directory-name-exceeding-limit"),
                "超长项目名必须截断，不得原样上屏:\n" + screen);
        // 前 23 显示列恰好落在 "dir"（a-very-long-project-dir = 23 字符）。
        assertTrue(screen.contains("a-very-long-project-dir…"),
                "截断后应保留前 23 列 + 省略号:\n" + screen);
    }
}
