/**
 * 终端渲染域（tamboui/TUI）：{@code CodeTuiView}（主视图与键绑定）、{@code ConversationState}
 * （模态请求队列的唯一落地端）、渲染器（diff/markdown/状态栏/回放）与输入处理
 * （图片附件探测、粘贴清洗、中断路由）。
 *
 * <p><b>接缝纪律</b>：实时事件一律经 {@code agent.seam} 的纯 Java 类型（AgentListener /
 * SubmitHandler / ModalRequest 家族）进出，迟到事件靠 turnId 过滤。
 *
 * <p><b>两处既有例外</b>（现状而非目标，别照着扩大）：① {@code -c} 历史回放直接吃会话仓库的
 * {@code List<Message>}，{@code HistoryReplay} 与 {@code ConversationState.replayHistory}
 * 因此是本包唯一 import Spring AI 类型的地方；② 本包直接引用 10 个 agent 子包的类型，
 * 多数是 DTO（llm 的模型列表、mcp 的服务器视图、thinking 的设置、session 的 {@code ContextStats}、
 * skill 的 {@code SkillInfo}），但 {@code CodeTuiView} 也直接持有 {@code PermissionConfigLoader}、
 * {@code BackgroundNotifier}、{@code MediaArtifactStore} 这类加载器/服务。
 */
package io.github.javaside.springai.codetui.ui;
