/**
 * 上下文压缩域（超限时怎么裁）：{@code BoundedSummarizationCompactionStrategy}（分块摘要，
 * 带跨次校准 {@code CalibrationState} 与超限回退 {@code SummarizerOverflow}——绝不拿网络
 * 错误调预算）、{@code NotifyingCompactionStrategy}（压缩事件通知 UI）、
 * {@code PreflightCompactionAdvisor} + {@code CompleteTokenCountTrigger}（按完整 token 量
 * 触发，含工具输出与调用参数）、{@code ModelContextWindows}（上下文窗口解析）。
 *
 * <p><b>依赖方向</b>：单向依赖 session（{@code SessionTokenEstimator} 估算共享，与展示
 * 同口径）；seam 仅出现在事件通知的接口参数里。压缩策略近月迭代频繁，与会话仓库的稳定
 * 变更节奏不同——这正是拆包理由之一。
 */
package io.github.javaside.springai.codetui.agent.compaction;
