/**
 * 子 agent 域：{@code SubagentSpec}/{@code SubagentLoader}（从 {@code .codetui/subagents/}
 * 装载定义）、{@code SubagentRunner}（执行：走阻塞 {@code call()}，外包 {@code RetryingChatModel}
 * 重试，可并行）、{@code SubagentTool}（把子 agent 暴露为主 agent 的工具）。
 *
 * <p><b>依赖方向</b>：消费面最广的功能包——依赖 background（后台任务登记）、llm（provider
 * 选择与重试）、mcp（工具注入）、permission（计划模式提示词权限）、prompt（系统提示）、
 * seam（监听器）、tools（事件装饰）。
 */
package io.github.javaside.springai.codetui.agent.subagent;
