/**
 * 系统提示片段加载：{@code MemoryPrompt}（长期记忆系统提示，classpath 覆盖版）、
 * {@code ProjectInstructions}（AGENTS.md 两层加载：用户级 → 项目级，后者优先）、
 * {@code PermissionModePrompt}（按当前权限模式注入行为约束段）。
 *
 * <p><b>为何独立成包</b>：三者同构——从外部资源读文本、拼进系统提示，都无状态。
 * 加载顺序与缺失时的空段语义由装配层（AgentTools）统一决定。
 *
 * <p><b>依赖方向</b>：仅依赖 permission（PermissionMode 枚举）。
 */
package io.github.javaside.springai.codetui.agent.prompt;
