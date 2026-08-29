/**
 * CodingAgent ↔ UI 的唯一接缝：{@code AgentListener}（事件出口，纯 Java 类型，不泄漏
 * Spring AI）、{@code SubmitHandler}（提交入口）、以及三种抢占输入焦点的模态请求
 * （{@code ModalRequest} sealed 家族：问询 Ask / 审批 Permission / 计划 Plan）。
 *
 * <p><b>为何存在</b>：问询、审批、计划审批竞争同一个输入焦点，各搞一套状态必然互相覆盖——
 * 三者统一进 {@code ConversationState} 的模态请求队列（UI 侧），UI 逐个弹。
 *
 * <p><b>sealed 约束</b>：无 module-info（unnamed module），{@code ModalRequest} 的
 * permitted 子类型必须与本接口同包——这就是 {@code PermissionRequest} 在 seam 而非
 * {@code permission} 的原因。消费方用 {@code instanceof} 链（release 17 无按类型模式 switch）。
 *
 * <p><b>依赖方向</b>：向下单向依赖 llm/mcp/media/permission/session/skill/thinking/tools
 * 各功能包（{@code SubmitHandler} 的 default 方法暴露 {@code McpRegistry.ServerView} 等 DTO，
 * 是既有耦合的显形；含 seam↔tools、seam↔mcp 两条已知双向环，见重构遗留清单）。
 */
package io.github.javaside.springai.codetui.agent.seam;
