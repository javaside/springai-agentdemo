/**
 * ToolCallback 装饰链与内置工具：装饰链 {@code PermissionCallback( ToolEventCallback(
 * MediaExternalizingCallback( 真实工具 ) ) )}——权限拦截必须最外层（未获批的调用不产生
 * 工具事件，UI 不会先显示"运行中"再变失败）；{@code ToolEventCallback} 压入 turnId
 * ThreadLocal 供 UI 过滤迟到事件。内置工具：博查网页搜索、TodoWrite 适配。
 *
 * <p><b>韧性</b>：{@code ResilientToolCallingManager} 吸收工具层异常为回合内错误文本
 * （单工具失败不炸整个回合），{@code TimeLimitedToolCallback} 限时。
 *
 * <p><b>依赖方向</b>：依赖 permission（规则引擎）/seam（审批请求、事件）/llm（搜索结果
 * 的 AI 抽取）；与 seam 存在一条已知双向环（seam 的两个 Bridge 调 {@code ToolEventCallback.currentTurnId()}），
 * 见重构遗留清单。
 */
package io.github.javaside.springai.codetui.agent.tools;
