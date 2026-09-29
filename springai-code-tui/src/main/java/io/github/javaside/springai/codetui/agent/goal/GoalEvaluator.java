package io.github.javaside.springai.codetui.agent.goal;

/**
 * /goal 循环的评估器：拿一份评估快照（{@link EvaluationInput}），换一个结构化结论
 * （{@link GoalVerdict}，spec §6.3 四行协议）。
 *
 * <p><b>实现纪律</b>（见 {@link ChatClientGoalEvaluator}）：单轮裸调用——无工具、无记忆、无 advisor；
 * 评估器只许「看滚动记录、给结论」，不许假设或执行任何命令。实现<b>不吞</b>
 * {@link GoalVerdict.GoalProtocolException}——协议熔断（protocolFailLimit）与评估失败熔断
 * （evalFailLimit）都靠调用方计数这些异常驱动。
 *
 * <p>{@code throws Exception} 是给装饰实现（Task 7 的超时/线程包裹）留的口子；
 * 本体实现只抛运行时异常。
 */
public interface GoalEvaluator {

    /**
     * 评估当前目标状态。
     *
     * @param input 评估输入快照（{@code GoalManager.buildEvaluationInput} 锁内取齐）
     * @return 解析后的结论
     * @throws GoalVerdict.GoalProtocolException 评估器输出不合四行协议（直通，不吞）
     * @throws Exception 模型调用等失败（由调用方按 evalFailLimit 计数熔断）
     */
    GoalVerdict evaluate(EvaluationInput input) throws Exception;
}
