package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.session.ContextStats;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现「缓存命中率不显示」：数据链路（累加器 → contextStats）已证正常，唯一未覆盖的环节是
 * <b>状态行在真实终端宽度下放不放得下</b>。ViewScreen javadoc 实测过「视图内 terminalWidth()
 * 在测试里恒为 80」；IDLE 状态行 = 前缀 56 列 + 模型名 15 列 + suffix 27 列 ≈ 98 列，
 * 缓存命中段从约第 84 列开始 —— 80 列终端上整段被截没。
 */
class CacheHitStatusBarWidthTest {

    private static class CtxStub implements SubmitHandler {
        private final String model;
        private final PermissionMode mode;

        private CtxStub() {
            this("deepseek-v4-pro", PermissionMode.DEFAULT);
        }

        private CtxStub(String model, PermissionMode mode) {
            this.model = model;
            this.mode = mode;
        }

        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentModel() { return model; }
        @Override public PermissionMode permissionMode() { return mode; }
        @Override public ContextStats contextStats() {
            // 复刻线上日志实测快照（hit=78）：events>0、有窗口、有缓存命中。
            return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L, 20, 10, 0, 0L,
                    120_832L, 155_184L, 78);
        }
    }

    private static String idleScreen(Path root, CtxStub stub) {
        ConversationState state = new ConversationState();
        CodeTuiView view = new CodeTuiView(state, stub, root);
        state.onUserMessage(1L, "hello");
        view.ctxUsageForTest().refresh();
        return ViewScreen.of(view, 80);
    }

    @Test
    @DisplayName("80 列终端下 IDLE 状态行的「缓存命中 78%」不得被截断")
    void cacheHitSegmentVisibleAt80Cols(@TempDir Path root) {
        ConversationState state = new ConversationState();
        CodeTuiView v = new CodeTuiView(state, new CtxStub(), root);
        // 需 events>0 才有 suffix：塞一条用户消息（onUserMessage 落事件账，ContextStats.events>0）
        state.onUserMessage(1L, "hello");
        // 测试里没有 drain 循环（animTick 永不推进），手动触发一次节流刷新，等价线上 ~1s 的 refresh
        v.ctxUsageForTest().refresh();

        String screen80 = ViewScreen.of(v, 80);
        assertTrue(screen80.contains("缓存命中 78%"),
                "80 列下缓存命中必须完整可见（修复前：断在「缓存命中」中途，数字被截没）：\n" + screen80);
        assertTrue(screen80.contains("上下文 78%"),
                "80 列下上下文占用也必须保留（静态键位提示应主动让位）：\n" + screen80);
    }

    @Test
    @DisplayName("宽度不足时次要帮助整组隐藏，Enter 与动态状态保留")
    void secondaryHelpDisappearsAsOneGroup(@TempDir Path root) {
        String screen = idleScreen(root, new CtxStub());

        assertTrue(screen.contains("Enter 发送"), screen);
        assertFalse(screen.contains("/model 切换模型"), screen);
        assertFalse(screen.contains("Esc 取消"), screen);
        assertFalse(screen.contains("Ctrl+C 退出"), screen);
        assertTrue(screen.contains("deepseek-v4-pro"), screen);
        assertTrue(screen.contains("上下文 78%"), screen);
        assertTrue(screen.contains("缓存命中 78%"), screen);
    }

    @Test
    @DisplayName("更窄时 Enter 也让位，只保留模型与动态状态")
    void primaryActionDisappearsBeforeCoreStatus(@TempDir Path root) {
        String model = "provider/very-long-context-model-name-xyz";
        String screen = idleScreen(root, new CtxStub(model, PermissionMode.DEFAULT));

        assertFalse(screen.contains("Enter 发送"), screen);
        assertFalse(screen.contains("/model 切换模型"), screen);
        assertTrue(screen.contains(model), screen);
        assertTrue(screen.contains("上下文 78%"), screen);
        assertTrue(screen.contains("缓存命中 78%"), screen);
    }

    @Test
    @DisplayName("权限模式标签占用宽度后，Enter 继续让位给核心状态")
    void permissionModeTagParticipatesInWidthBudget(@TempDir Path root) {
        String model = "deepseek-v4-pro-xxxxxx";
        String screen = idleScreen(root, new CtxStub(model, PermissionMode.ACCEPT_EDITS));

        assertTrue(screen.contains("自动接受编辑"), screen);
        assertFalse(screen.contains("Enter 发送"),
                "不扣权限标签宽度时这段仍放得下，会形成假绿：\n" + screen);
        assertFalse(screen.contains("/model 切换模型"), screen);
        assertFalse(screen.contains("Esc 取消"), screen);
        assertFalse(screen.contains("Ctrl+C 退出"), screen);
        assertTrue(screen.contains(model), screen);
        assertTrue(screen.contains("上下文 78%"), screen);
        assertTrue(screen.contains("缓存命中 78%"), screen);
    }

    @Test
    @DisplayName("候选恰好等于可用宽度时不提前降级")
    void exactWidthKeepsCandidate() {
        String full = "Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · model · 上下文 1%";
        int exactWidth = dev.tamboui.text.CharWidth.of(full);

        assertEquals(full, CodeTuiView.idleHint("model", " · 上下文 1%", exactWidth));
    }

    @Test
    @DisplayName("宽度判断按终端列宽而非 Java 字符数")
    void fittingUsesTerminalColumnWidth() {
        String model = "模型";
        String full = "Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + model;

        assertEquals("Enter 发送 · " + model,
                CodeTuiView.idleHint(model, "", full.length()),
                "中文占两列；若误用 String.length() 会错误保留完整帮助组");
    }
}
