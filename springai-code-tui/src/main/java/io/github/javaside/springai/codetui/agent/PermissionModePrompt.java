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

    private PermissionModePrompt() {
    }

    /** 该模式对应的提示段；非 PLAN（含 null）一律空串。 */
    public static String of(PermissionMode mode) {
        return mode == PermissionMode.PLAN ? PLAN_GUIDANCE : "";
    }
}
