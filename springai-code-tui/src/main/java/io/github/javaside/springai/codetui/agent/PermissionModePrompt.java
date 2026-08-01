package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

/**
 * 按当前权限模式产出一段系统提示，注入 {@code SYSTEM_TEMPLATE} 的 PERMISSION_MODE 占位符。
 *
 * <p><b>只有 PLAN 有内容</b>：其余模式注入空串。模型在默认档看到「你可以写文件」是废话，
 * 反而稀释真正重要的指令。
 *
 * <p><b>正文里绝不能有花括号</b>：本段<b>作为 param 值</b>注入（不是拼进模板字符串），
 * 但 Spring 的模板引擎会解析渲染后的文本，正文里的花括号会被当占位符炸掉整个回合
 * （项目里接 AGENTS.md 与长期记忆时都踩过）。有一条单测专门钉这一点。
 *
 * <p><b>提示不是护栏</b>：真正拦住写操作的是 {@code PermissionEngine} 的 PLAN 分支（DENY）。
 * 这段话只是让模型<b>知道</b>自己在哪一档、以及出口在哪，从而少撞几次墙、早点去调 ExitPlanMode。
 */
public final class PermissionModePrompt {

    private static final String PLAN_GUIDANCE = """
            当前处于「计划模式」：你只能读取和探索，不能修改任何文件、也不能执行有副作用的命令
            （这不是建议——权限层会直接拒绝这类调用，重试没有意义）。
            请先用 Read / Grep / Glob 与只读命令把现状调查清楚，然后调用 ExitPlanMode 工具，
            把你打算怎么做写成一份 markdown 计划提交给用户；用户批准后才会切换到可以动手的模式。
            """;

    /**
     * 子 agent 版的计划模式提示。
     *
     * <p><b>刻意不提 ExitPlanMode，别「统一成一份文案」</b>：那个工具<b>只装配给主 agent</b>
     * （见 {@code AgentTools.build}——它不进 {@code decoratedList}，故子 agent 的工具集里根本没有）。
     * 给子 agent 指这条路，等于把「不知道为什么被拒」换成「知道了、照做了、还是失败」，更糟。
     * 子 agent 该知道的是另一件事：它在只读调查阶段，<b>把发现报告回主 agent 就是它的交付</b>，
     * 由主 agent 汇总成计划去提交。
     */
    private static final String SUBAGENT_PLAN_GUIDANCE = """
            当前处于「计划模式」：你只能读取和探索，不能修改任何文件、也不能执行有副作用的命令
            （这不是建议——权限层会直接拒绝这类调用，重试没有意义，换个写法绕也绕不过去）。
            你这一趟的任务就是只读调查：用 Read / Grep / Glob 与只读命令把现状查清楚。
            把调查结果和你的判断写进最终回复报告回去，这就是你的交付；
            该怎么动手由主 agent 汇总成计划、交用户批准后再说，不需要你去改任何东西。
            """;

    private PermissionModePrompt() {
    }

    /** 主 agent 版提示段；非 PLAN（含 null）一律空串。 */
    public static String of(PermissionMode mode) {
        return mode == PermissionMode.PLAN ? PLAN_GUIDANCE : "";
    }

    /** 子 agent 版提示段（<b>不含</b> ExitPlanMode，见 {@link #SUBAGENT_PLAN_GUIDANCE}）；非 PLAN（含 null）一律空串。 */
    public static String forSubagent(PermissionMode mode) {
        return mode == PermissionMode.PLAN ? SUBAGENT_PLAN_GUIDANCE : "";
    }
}
