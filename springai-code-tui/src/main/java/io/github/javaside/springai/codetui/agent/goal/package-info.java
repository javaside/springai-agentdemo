/**
 * /goal 自主循环域：用户设定完成条件后，agent 跨多轮自行推进，每轮结束由独立小模型评估器
 * （{@code GoalEvaluator}，裸 client，无工具/无会话记忆）判定是否达成，未达成则把原因注入下一轮。
 * {@code GoalManager} 持有状态机（RUNNING/PAUSED/终态）、滚动记录与 epoch；{@code GoalConfig}
 * 收敛全部可调阈值（CODETUI_GOAL_* env 解析 + 双向钳制）；{@code GoalVerdict} 承载判定结果与
 * STATE 账本。
 *
 * <p><b>UI 线程纪律</b>：自动轮的 dispatch 只发生在 UI 线程空闲批；后台评估线程（命名
 * goal-evaluator）绝不触碰 View 状态，只 publish 结果，迟到 verdict 按 epoch 丢弃。
 *
 * <p><b>锁纪律</b>：{@code GoalManager} 照 {@code Interjections} 先例——锁内改状态、锁外
 * publish；评估"在飞"标志用 CAS 置位、{@code finally} 复位，防异常静默死锁。
 *
 * <p><b>依赖方向</b>：叶子级纯逻辑层，零 UI 依赖，便于单测（状态机/解析/熔断全 fake）。
 */
package io.github.javaside.springai.codetui.agent.goal;
