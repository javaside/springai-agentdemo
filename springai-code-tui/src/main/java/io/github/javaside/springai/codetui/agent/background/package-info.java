/**
 * 后台子 agent 域：{@code BackgroundTaskRegistry}（登记/查询/限额）、
 * {@code BackgroundTaskTool} + {@code BackgroundTaskListTool}（模型侧启动/列出后台任务）、
 * {@code BackgroundNotifier}（完成时通知 UI）、{@code BackgroundDigest}（结果摘要）、
 * {@code TaskResultStore}（结果暂存）。
 *
 * <p><b>与 subagent 的分工</b>：subagent 管"怎么跑一个子 agent"；本包管"作为后台任务跑"
 * ——注册、通知、继续会话（continue seam）、限额。主 agent 会在下个回合的提示里收到
 * 后台任务的完成摘要。
 *
 * <p><b>依赖方向</b>：仅依赖 seam（AgentListener 通知）。
 */
package io.github.javaside.springai.codetui.agent.background;
