/**
 * 会话持久化与 token 记账：{@code FileSessionRepository}（事件落盘/加载/恢复裁剪）、
 * {@code SessionEvents}（中断裁剪等纯逻辑，仓库与 CodingAgent 共用）、
 * {@code TokenUsageAccumulator}（会话级用量，主/子/摘要三条路径共用）、
 * {@code CacheUsageExtractor}（从各家 usage 拆缓存读写桶）、{@code ContextStats}
 * （/context 命令的只读快照，纯 Java）。
 *
 * <p><b>依赖方向</b>：叶子级，零 agent 内部依赖——被 compaction（估算共享）、llm（用量
 * 记录）、seam（ContextStats 快照）、ui（{@code /context} 展示）与装配层单向消费。
 */
package io.github.javaside.springai.codetui.agent.session;
