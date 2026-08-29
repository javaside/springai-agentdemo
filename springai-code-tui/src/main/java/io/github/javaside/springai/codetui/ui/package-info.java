/**
 * 终端渲染域（tamboui/TUI）：{@code CodeTuiView}（主视图与键绑定）、{@code ConversationState}
 * （模态请求队列的唯一落地端）、渲染器（diff/markdown/状态栏/回放）与输入处理
 * （图片附件探测、粘贴清洗、中断路由）。
 *
 * <p><b>接缝纪律</b>：本包消费 {@code agent.seam} 的纯 Java 类型（AgentListener /
 * SubmitHandler / ModalRequest 家族），不 import Spring AI 类型；agent 事件的迟到过滤
 * 靠 turnId。除 seam 及各 DTO（llm 的模型列表、mcp 的服务器视图、thinking 的设置等）外
 * 不触碰 agent 内部。
 */
package io.github.javaside.springai.codetui.ui;
