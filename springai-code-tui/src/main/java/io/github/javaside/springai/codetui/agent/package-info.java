/**
 * agent 域的装配层：{@code CodingAgent}（回合编排：提交、流式消费、取消、上下文压缩）与
 * {@code AgentTools}（工具集装配：读/写/搜索、技能、子 agent、MCP、搜索、记忆等）。
 *
 * <p><b>刻意只有这两个类</b>——2026-08 重构把顶层 88 个类按功能拆进 10 个子包后，
 * 顶层只留「把子包组装成完整 agent」的胶水。新功能先进对应子包，装配接线才进本包。
 *
 * <p><b>依赖方向</b>：单向依赖全部 14 个子包，是依赖图的汇聚点；除 {@code mcp} 经
 * {@code testEngine} 测试工厂存在一条已知回边外（遗留清理项），无子包反向依赖本包的生产代码。
 * 对外的实时事件只经 {@code agent.seam} 出去，但 ui 另有对 10 个子包类型的直接引用
 * （非目标状态，明细见 {@code ui} 的包注释）。
 */
package io.github.javaside.springai.codetui.agent;
