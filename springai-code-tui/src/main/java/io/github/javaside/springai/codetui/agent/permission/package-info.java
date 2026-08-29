/**
 * 权限引擎（纯规则域）：{@code PermissionEngine} 按 {@code permission.json} 规则与当前
 * 模式（DEFAULT/ACCEPT_EDITS/BYPASS）对工具调用做出允许/拒绝判定，含危险路径表、
 * bash 命令拆段、路径别名归一。
 *
 * <p><b>纯规则包</b>：不依赖 UI、不依赖工具执行、不知道审批面板长什么样——发起审批、
 * 阻塞等 UI 应答是 {@code seam.PermissionRequest} + {@code tools.PermissionCallback} 的事。
 * 这条边界让它可以独立地做规则匹配的穷举测试。
 *
 * <p><b>依赖方向</b>：零依赖（除 JDK/Spring AI 工具定义类型），是整个依赖图的叶子。
 */
package io.github.javaside.springai.codetui.agent.permission;
