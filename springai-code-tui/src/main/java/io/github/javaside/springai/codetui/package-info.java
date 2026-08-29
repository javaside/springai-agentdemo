/**
 * springai-code-tui 模块根：终端 AI 编码助手（TUI）。
 *
 * <p>两入口一横切：{@code CodeTuiApplication}（装配模型/工具/TUI 并启动）、{@code AppInfo}
 * （版本信息），以及 {@code agent}（agent 域，全部业务逻辑）与 {@code ui}（终端渲染域）两个子包。
 *
 * <p><b>分层纪律</b>：ui 的实时 agent 事件一律经 {@code agent.seam} 的纯 Java 接缝进出；
 * 本包不承载业务。接缝之外 ui 仍直接引用若干 agent 子包类型、回放路径还吃 Spring AI 的
 * {@code Message}——两处既有例外，明细见 {@code ui} 的包注释。
 */
package io.github.javaside.springai.codetui;
