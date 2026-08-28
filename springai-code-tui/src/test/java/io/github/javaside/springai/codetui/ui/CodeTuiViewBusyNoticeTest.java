package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忙时的 notice 不得盖掉状态栏的运行指示。
 *
 * <p><b>钉的是一个真实缺陷</b>：{@code statusLine()} 里 notice 分支排在 {@code state.status()}
 * 开关之前且无条件 return。回合正在跑时按 Shift+Tab 切个档，「● 思考中…」的波光行就被
 * 「权限模式：X」整条顶掉，且 sticky——不按下一个键不还回来。用户看不出回合还在跑。
 *
 * <p><b>为什么必须渲染进 Buffer 再回读</b>：这是分支顺序缺陷，被挡住的那一段本身构造得出来。
 * 只断言 {@code statusLine()} 的返回对象或只测某个纯函数，永远是绿的——而且测不到 shimmer
 * 到底有没有把 suffix 拼进去。断言必须落在屏幕文本上。真实 ANSI 与路由器那一层由 pty 冒烟兜底。
 */
class CodeTuiViewBusyNoticeTest {

    /**
     * <b>刻意不覆写 {@code cyclePermissionMode}</b>：本类一次 Shift+Tab 都不按，
     * notice 全靠 {@code state.setNotice} 直接设。覆写它就要调 {@code mode.next(…)}，
     * 于是本文件被绑到那个方法的签名上——而它正在另一条并行赛道上被改
     * （{@code next(boolean)} → {@code next()}），合并时必炸。桩只提供用得到的东西。
     */
    private static class Stub implements SubmitHandler {
        PermissionMode mode = PermissionMode.DEFAULT;
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public String currentModel() { return "deepseek-chat"; }
    }

    private static CodeTuiView view(ConversationState state, Path root) {
        return new CodeTuiView(state, new Stub(), root);
    }

    @Test
    @DisplayName("思考中设 notice：转轮与切换反馈同屏，转轮不被顶掉")
    void thinkingKeepsSpinner(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);                      // → THINKING
        state.setNotice("已切到 计划模式");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("思考中"), "运行指示必须还在:\n" + s);
        assertTrue(s.contains("已切到 计划模式"), "切换反馈必须也在:\n" + s);
    }

    @Test
    @DisplayName("跑工具时设 notice：工具名与切换反馈同屏")
    void runningToolKeepsToolName(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);
        state.onToolStarted(1L, "Bash", "{\"command\":\"npm test\"}");   // → RUNNING_TOOL
        state.setNotice("已切到 跳过权限检查");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("运行 Bash"), "正在跑哪个工具必须还看得见:\n" + s);
        assertTrue(s.contains("已切到 跳过权限检查"), "切换反馈必须也在:\n" + s);
    }

    @Test
    @DisplayName("空闲时 notice 仍独占整行——那时没有动态信息要保")
    void idleNoticeStillTakesWholeLine(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.setNotice("已切到 计划模式");

        String s = ViewScreen.of(v);
        assertTrue(s.contains("已切到 计划模式"), "notice 必须显示:\n" + s);
        assertFalse(s.contains("deepseek-chat"),
                "空闲态 notice 是独占的，不该与常态提示行并存（并存会把行挤爆）:\n" + s);
    }

    /**
     * 杀掉「{@code String ns = " · " + notice;} 没判空」这个变异：
     * 空 notice 时会渲染出一段悬空的分隔符。
     */
    @Test
    @DisplayName("notice 为空时不留悬空分隔符")
    void emptyNoticeLeavesNoDanglingSeparator(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = view(state, root);
        state.onTurnStarted(1L);

        String s = ViewScreen.of(v);
        assertTrue(s.contains("思考中"), "前提：确实在思考态:\n" + s);
        assertFalse(s.contains("·  ·"), "空 notice 拼出了悬空分隔符:\n" + s);
    }
}
