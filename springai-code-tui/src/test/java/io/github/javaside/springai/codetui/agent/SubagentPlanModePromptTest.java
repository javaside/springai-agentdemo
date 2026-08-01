package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配层断言：模式提示段真的经 {@code AgentTools.build()} 接进了子 agent 的系统提示。
 *
 * <p><b>为什么不满足于测 {@link PermissionModePrompt} 的纯函数</b>：纯函数全绿而装配漏接，
 * 是这类「注入型」改动最典型的失效方式——两边各自自洽，合起来什么也没发生。故这里走真实装配：
 * {@code AgentTools.build(...)} → {@code AgentRuntime.subagentRunner()} →
 * {@code effectiveSystemPrompt(spec)}，即子 agent 派发时真正喂给 {@code .system(...)} 的那个字符串。
 */
class SubagentPlanModePromptTest {

    private static SubagentSpec spec() {
        return new SubagentSpec("explore", "探索型子 agent。", "SUBAGENT 系统提示正文",
                List.of(), List.of(), null, List.of());
    }

    /** 用真实装配路径造一个 runtime，权限引擎由调用方持有以便运行期切档。 */
    private static AgentTools.AgentRuntime buildWith(Path root, PermissionEngine engine) {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        return AgentTools.build(registry, root, new StubListener(), null, engine);
    }

    @Test
    @DisplayName("PLAN 下子 agent 系统提示带上模式段，且不含 ExitPlanMode（它没有那个工具）")
    void planGuidanceReachesSubagentSystemPrompt(@TempDir Path root) {
        PermissionEngine engine = AgentTools.testEngine(root);
        engine.setMode(PermissionMode.PLAN);

        String eff = buildWith(root, engine).subagentRunner().effectiveSystemPrompt(spec());

        assertTrue(eff.startsWith("SUBAGENT 系统提示正文"), "spec 提示仍在最前：" + eff);
        assertTrue(eff.contains("计划模式"), "PLAN 下必须注入模式提示段：" + eff);
        assertTrue(eff.contains("只读") || eff.contains("不能修改"), "要说清边界：" + eff);
        assertFalse(eff.contains("ExitPlanMode"),
                "子 agent 没有 ExitPlanMode，提它就是指一条走不通的路：" + eff);
    }

    @Test
    @DisplayName("非 PLAN 档不注入任何模式段——别给子 agent 灌无关噪音")
    void noGuidanceOutsidePlanMode(@TempDir Path root) {
        PermissionEngine engine = AgentTools.testEngine(root);   // 默认 DEFAULT

        String eff = buildWith(root, engine).subagentRunner().effectiveSystemPrompt(spec());

        assertFalse(eff.contains("计划模式"), "非 PLAN 档不该出现模式提示：" + eff);
    }

    /**
     * 这条是本文件的重点：SubagentRunner 是<b>装配期建一次</b>的长寿对象，若把模式当值烘焙进去，
     * 用户 Shift+Tab 切档后子 agent 会拿到<b>过期</b>的提示——比没有提示更坏，它会理直气壮地按错误前提行动。
     * 故断言同一个 runtime 在切档后立刻改变输出（即每次派发现取现算）。
     */
    @Test
    @DisplayName("模式随 Shift+Tab 切换后立刻反映到下一次派发，不是装配期烘焙的快照")
    void modeIsReadLivePerDispatchNotBakedAtBuild(@TempDir Path root) {
        PermissionEngine engine = AgentTools.testEngine(root);
        SubagentRunner runner = buildWith(root, engine).subagentRunner();   // 装配一次，之后不再重建

        assertFalse(runner.effectiveSystemPrompt(spec()).contains("计划模式"), "起点是 DEFAULT");

        engine.setMode(PermissionMode.PLAN);
        assertTrue(runner.effectiveSystemPrompt(spec()).contains("计划模式"),
                "切进 PLAN 后，同一个 runner 的下一次派发就该带上模式段——烘焙成快照的话这里会红");

        engine.setMode(PermissionMode.DEFAULT);
        assertFalse(runner.effectiveSystemPrompt(spec()).contains("计划模式"),
                "切出 PLAN 后同样要立刻消失，否则子 agent 会被一段过期提示按住不动手");
    }
}
