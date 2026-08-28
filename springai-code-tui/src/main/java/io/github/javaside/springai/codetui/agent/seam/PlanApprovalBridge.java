package io.github.javaside.springai.codetui.agent.seam;

import io.github.javaside.springai.codetui.agent.ToolEventCallback;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * {@code ExitPlanMode} 的落地端：把工具线程 ↔ UI 线程之间的一次计划审批变成阻塞握手。
 * 形状照抄 {@link UserQuestionBridge}（一次性队列 + {@code take()}），只是载荷不同。
 *
 * <p><b>为何阻塞</b>：工具的 {@code call()} 在工具线程上同步执行，必须返回一个字符串才算执行完毕。
 * 本桥把计划经 {@link AgentListener#onPlanSubmitted} 交给 UI，然后阻塞在一次性队列上，直到用户选完。
 *
 * <p><b>turnId</b>：{@code handle()} 在被 {@link ToolEventCallback} 装饰的工具 call 内同线程同步触发，
 * 故直接读 {@link ToolEventCallback#currentTurnId()}（与 {@link UserQuestionBridge} 同款）。
 *
 * <p><b>切模式发生在工具线程</b>：{@code PermissionEngine.mode} 是 {@code volatile}，
 * 本来就是按「UI 线程与工具线程都可能写」设计的。这里经构造注入的 {@code modeSwitch} 回调去改，
 * 而不是直接依赖引擎类型——桥只管握手，不必认识权限包的实现细节。
 *
 * <p><b>一次性消费</b>：容量 1 的队列 + 非阻塞 {@code offer} 使「首个信号胜出」，重复/交叉的
 * respond 均被安全丢弃。每次 {@link #handle} 新建一个 handoff，<b>绝不复用或重新武装</b>——
 * 复用会让下一次审批读到上一次陈旧的 CANCEL，杀掉用户刚刚批准的计划。
 *
 * <p><b>活性依赖（本类不自保）</b>：{@code take()} 无超时，解除阻塞完全靠两条外部逃生口——
 * ① UI 总会应答（面板保证，且 {@link AgentListener#onPlanSubmitted} 的默认实现也会立刻应答）；
 * ② 回合被 dispose 时框架中断本线程。二者缺失则线程永久 park，<b>而它持着回合</b>。
 */
public final class PlanApprovalBridge {

    private final AgentListener listener;
    private final Consumer<PermissionMode> modeSwitch;

    public PlanApprovalBridge(AgentListener listener, Consumer<PermissionMode> modeSwitch) {
        this.listener = listener;
        this.modeSwitch = modeSwitch;
    }

    /** 提交一份计划、阻塞到用户选完；返回给模型的字符串。 */
    public String handle(String plan) {
        long turnId = ToolEventCallback.currentTurnId();
        ArrayBlockingQueue<Object[]> handoff = new ArrayBlockingQueue<>(1);
        PlanResponder responder = (outcome, feedback) ->
                handoff.offer(new Object[]{outcome, feedback == null ? "" : feedback});

        listener.onPlanSubmitted(turnId, new PlanRequest(turnId, plan, responder));

        Object[] answer;
        try {
            answer = handoff.take();                 // 阻塞工具线程直到 UI 应答
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();       // 重新置位：吞掉它上层就再也看不到取消信号
            throw new PermissionCancelledException();
        }
        PlanOutcome outcome = (PlanOutcome) answer[0];
        String feedback = (String) answer[1];
        switch (outcome) {
            case APPROVE_ACCEPT_EDITS:
                modeSwitch.accept(PermissionMode.ACCEPT_EDITS);
                return "计划已批准，开始执行。后续工作区内的改动会自动放行，无需逐个确认。";
            case APPROVE_DEFAULT:
                modeSwitch.accept(PermissionMode.DEFAULT);
                return "计划已批准，开始执行。后续每个有副作用的操作仍会逐个请求用户确认。";
            case KEEP_PLANNING:
                // 空反馈是合法意图（用户就是不想细说），但「用户希望继续完善计划：」后面接空
                // 会给模型一个悬空的冒号，读起来像是内容被截断了。改成一句明确的话，
                // 让它知道「没有具体意见」而不是「意见丢了」——面板侧刻意不拦空输入，
                // 那会多一个用户得自己想办法退出的状态。
                return feedback.isBlank()
                        ? "用户希望继续完善计划，但没有给出具体意见。请自行复查方案（是否遗漏步骤、"
                                + "验证方式是否充分、风险是否交代清楚），改完再次调用 ExitPlanMode 提交。"
                                + "仍处于计划模式，不要动手改。"
                        : "用户希望继续完善计划：" + feedback
                                + "\n请据此修改方案，然后再次调用 ExitPlanMode 提交。仍处于计划模式，不要动手改。";
            case CANCEL:
            default:
                throw new PermissionCancelledException();
        }
    }

    /** {@code ExitPlanMode} 的入参：一份 markdown 计划。
     *
     * @param plan markdown 格式的实施计划正文
     */
    public record PlanInput(String plan) {
    }

    /**
     * 把桥包成名为 {@code ExitPlanMode} 的 {@link ToolCallback}。
     *
     * <p><b>注册名是这里给的字符串，不是方法名</b>——权限规则、登记表、子 agent 的 allow/deny 过滤
     * 全按注册名匹配（项目踩过：{@code askUserQuestion} 方法名不匹配 {@code AskUserQuestionTool}
     * 注册名，导致 deny 静默失效）。它还必须在 {@code ToolRegistry} 里登记为 {@code INTERNAL}：
     * 未登记即 {@code UNKNOWN}，而 PLAN 模式下 UNKNOWN 是 DENY——本工具会把自己拦住，
     * 计划模式就成了没有出口的死胡同。
     */
    public static ToolCallback exitPlanModeTool(PlanApprovalBridge bridge) {
        return FunctionToolCallback
                .builder("ExitPlanMode",
                        (PlanInput in, ToolContext ctx) -> bridge.handle(in == null ? "" : in.plan()))
                .description("""
                        提交一份实施计划，请用户批准后再开始动手。

                        什么时候调用：你处于「计划模式」（系统提示里会告知），已经把现状调查清楚、
                        想好了要怎么做。把方案写成 markdown 传给 plan 参数。

                        用户可以选择「批准并自动接受编辑」「批准并逐个确认」或「继续完善计划」。
                        批准后模式会自动切换，你才能修改文件；若用户要求继续完善，本工具会把他的
                        反馈返回给你，据此改完再提交一次。

                        不在计划模式时不要调用它。
                        """)
                .inputType(PlanInput.class)
                .build();
    }
}
