/**
 * 思考（reasoning）配置域：{@code ThinkingConfig}（模式 + 力度 + 预算的值对象）、
 * {@code ThinkingCapabilities}（各 provider/model 支持哪几种思考形态）、
 * {@code ThinkingConfigStore}（记忆上次选择）。
 *
 * <p><b>依赖方向</b>：叶子级，零 agent 内部依赖；被 llm（构造带 thinking 的请求选项）、
 * seam（设置面板 DTO）、ui 消费。
 */
package io.github.javaside.springai.codetui.agent.thinking;
