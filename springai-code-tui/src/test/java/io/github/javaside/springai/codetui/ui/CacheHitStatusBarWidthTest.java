package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.ContextStats;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现「缓存命中率不显示」：数据链路（累加器 → contextStats）已证正常，唯一未覆盖的环节是
 * <b>状态行在真实终端宽度下放不放得下</b>。ViewScreen javadoc 实测过「视图内 terminalWidth()
 * 在测试里恒为 80」；IDLE 状态行 = 前缀 56 列 + 模型名 15 列 + suffix 27 列 ≈ 98 列，
 * 缓存命中段从约第 84 列开始 —— 80 列终端上整段被截没。
 */
class CacheHitStatusBarWidthTest {

    private static class CtxStub implements SubmitHandler {
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public ContextStats contextStats() {
            // 复刻线上日志实测快照（hit=78）：events>0、有窗口、有缓存命中。
            return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L, 20, 10, 0, 0L,
                    120_832L, 155_184L, 78);
        }
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
        // 对照：120 列下键位提示应全保留——证明丢弃是宽度驱动、不是无条件砍
        String screen120 = ViewScreen.of(v, 120);
        assertTrue(screen120.contains("缓存命中 78%"), "120 列下应完整显示：\n" + screen120);
        assertTrue(screen120.contains("Enter 发送"), "120 列宽裕时静态提示应保留：\n" + screen120);
    }
}
