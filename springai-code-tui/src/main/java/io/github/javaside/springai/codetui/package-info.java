/**
 * springai-code-tui 模块根：终端 AI 编码助手（TUI）。
 *
 * <p>两入口一横切：{@code CodeTuiApplication}（装配模型/工具/TUI 并启动）、{@code AppInfo}
 * （版本信息），以及 {@code agent}（agent 域，全部业务逻辑）与 {@code ui}（终端渲染域）两个子包。
 *
 * <p><b>分层纪律</b>：ui 只经 {@code agent.seam} 的纯 Java 接缝消费 agent 事件，
 * 不触碰任何 Spring AI 类型；本包不承载业务。
 */
package io.github.javaside.springai.codetui;
