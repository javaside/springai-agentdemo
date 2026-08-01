package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionDecision;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import io.github.javaside.springai.codetui.agent.permission.ToolTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 权限拦截装饰器——<b>装饰链的最外层</b>：
 * {@code PermissionCallback( ToolEventCallback( MediaExternalizingCallback( 真实工具 ) ) )}。
 *
 * <p><b>为何最外层</b>：放内层的话，被拒绝的调用会先发 {@code onToolStarted}、在 TUI 显示成
 * 「工具开始运行」再变失败；审批等待期间状态栏还会一直显示「工具运行中」。放最外层后，
 * 未获批准的调用根本不产生工具事件，改由独立的权限事件驱动 UI。
 *
 * <p><b>turnId 从 {@link ToolContext} 取，不读 ThreadLocal</b>——此时还没进 {@link ToolEventCallback}，
 * ThreadLocal 尚未压入（读它只会拿到 -1 或<b>上一次</b>工具调用的残值）。
 *
 * <p><b>被拒绝时返回错误字符串，不抛异常</b>：抛异常会顺着 Reactor 流炸掉整个回合，与护栏定位相悖；
 * 返回字符串让模型能自寻替代方案、回合继续。只有「中断本回合」才抛
 * {@link PermissionCancelledException}。
 *
 * <h2>活性依赖（本类不自保，照 {@link UserQuestionBridge} 的纪律）</h2>
 * {@code take()} 无超时，解除阻塞完全依赖两条外部逃生口——
 * ① UI 侧<b>总会</b>应答：审批面板保证，且 {@code ConversationState} 对迟到 / 队满的请求会
 * 就地 DENY，没有接管审批 UI 的落地端也走 {@link AgentListener#onPermissionRequested} 的默认 DENY；
 * ② 回合被 dispose 时框架中断本线程（{@code InterruptedException} → 抛
 * {@link PermissionCancelledException}）。二者缺失则线程永久 park——而它持着回合，
 * 整个 agent 会静默挂死，无报错也无出口。
 *
 * <p>本类自己只保证三件事，且每一件都被测试钉住：
 * <ul>
 *   <li><b>每次调用新建一个 handoff</b>。类型层不阻止多个 {@link PermissionRequest} 共用应答口，
 *       实测过共用的后果：{@code r1.cancel()} 发出的 CANCEL 被等在 {@code r2} 上的线程消费掉；
 *       且一次调用消费完后队列即空，<b>迟到的 CANCEL 会留在里面</b>，被下一次调用读走——
 *       杀掉用户刚刚批准的回合。由此推出两条禁令（见 {@link PermissionResponder}）：
 *       不得写重试循环再读第二次，不得复用 / 重新武装 handoff。</li>
 *   <li><b>{@code take()} 清掉的中断标志必须重新置位</b>，否则中断信号在这里被吞掉，
 *       上层看不到「本回合已被取消」。</li>
 *   <li><b>没有一条路径会带着 park 的线程离开</b>：ALLOW / DENY 根本不等待；ASK 的每个出口
 *       （五种 outcome、中断、asker 抛异常）都要么返回要么抛。</li>
 * </ul>
 *
 * <p><b>已知的良性残留</b>：工具线程被中断醒来时，请求可能还留在 {@code ConversationState}
 * 的模态队列里（本类没有队列句柄，摘不掉）。实际路径上中断总是伴随
 * {@code cancelCurrent()} 的排空，故会被一并清掉；即便漏了，用户作答也只是 offer 进一个
 * 无人读取的队列，不会挂死任何线程。
 */
public final class PermissionCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(PermissionCallback.class);

    /** UI 出口：把审批请求交出去（生产实现是 {@code listener::onPermissionRequested}）。 */
    @FunctionalInterface
    public interface Asker {
        void ask(long turnId, PermissionRequest request);
    }

    private final ToolCallback delegate;
    private final PermissionEngine engine;
    private final Asker asker;

    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, Asker asker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可为 null");
        this.engine = Objects.requireNonNull(engine, "engine 不可为 null");
        this.asker = Objects.requireNonNull(asker, "asker 不可为 null：漏传会让每次 ASK 都 park");
    }

    /** 生产构造：直接挂到 listener 上。 */
    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, AgentListener listener) {
        this(delegate, engine, Objects.requireNonNull(listener, "listener 不可为 null")::onPermissionRequested);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        PermissionDecision decision = engine.decide(name, toolInput);   // 契约：永不抛，出错失败关闭成 ASK

        if (decision.behavior() == PermissionBehavior.ALLOW) {
            return invoke(toolInput, toolContext);
        }
        if (decision.behavior() == PermissionBehavior.DENY) {
            return denyMessage(decision.reason());
        }
        return askThenAct(name, toolInput, toolContext, decision);
    }

    private String askThenAct(String name, String toolInput, ToolContext toolContext,
                              PermissionDecision decision) {
        long turnId = ToolEventCallback.extractTurnId(toolContext);
        String taskId = ToolEventCallback.extractTaskId(toolContext);
        // ⚠ 每次调用一个全新的 handoff——绝不提升成字段、绝不复用（见类注释「活性依赖」第 1 条）
        ArrayBlockingQueue<PermissionOutcome> handoff = new ArrayBlockingQueue<>(1);

        PermissionRequest request = new PermissionRequest(
                turnId, taskId, name, ToolTargets.extract(name, toolInput, engine.root()), toolInput,
                decision.reason(), decision.suggested(), handoff::offer);

        try {
            asker.ask(turnId, request);
        } catch (RuntimeException e) {
            // 出口没送出去就没人会应答，等下去必然永久 park：失败关闭成 DENY，回合继续。
            // 请求可能已入队（异常发生在入队之后），那时用户作答只是 offer 进一个无人读的队列，无害。
            log.error("审批请求未能送达 UI，失败关闭成拒绝：tool={}", name, e);
            return denyMessage("审批请求未能送达界面（" + e.getClass().getSimpleName() + "），本次调用已被拒绝");
        }

        PermissionOutcome outcome;
        try {
            outcome = handoff.take();               // 阻塞工具线程直到 UI 应答（只读一次，不重试）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // take() 清掉了标志，不置位回去上层就看不到取消
            throw new PermissionCancelledException();
        }

        switch (outcome) {
            case ALLOW_ONCE:
                return invoke(toolInput, toolContext);
            case ALLOW_SESSION:
                remember(decision.suggested(), RuleScope.SESSION, name);
                return invoke(toolInput, toolContext);
            case ALLOW_ALWAYS:
                remember(decision.suggested(), RuleScope.PROJECT, name);
                return invoke(toolInput, toolContext);
            case DENY:
                return denyMessage("用户拒绝了本次操作（" + decision.reason() + "）");
            case CANCEL:
                throw new PermissionCancelledException();
            default:
                // 新增 outcome 却漏改这里：失败关闭成拒绝，绝不静默放行（release=17，没有穷尽性检查兜底）
                log.error("未知的审批结果 {}，失败关闭成拒绝", outcome);
                return denyMessage("无法识别的审批结果 " + outcome);
        }
    }

    /**
     * 记下「别再问」的规则。
     *
     * <p><b>规则为 null 时只放行本次、不记规则</b>：内置危险检查与 ask 规则引发的 ASK 都排在
     * allow 规则之前，加任何 allow 规则都消不掉下次的询问，故引擎刻意不给建议
     * （见 {@code PermissionDecision.askOnly}）。面板本就不该在这种请求上提供这两个选项，
     * 真到了这里就当「本次允许」处理——比凭空造一条不会生效的规则诚实。
     */
    private void remember(PermissionRule suggested, RuleScope scope, String toolName) {
        PermissionRule rule = rescope(suggested, scope);
        if (rule == null) {
            log.debug("{} 的这次审批没有可用的建议规则，只放行本次（内置危险检查 / ask 规则引发的 ASK）", toolName);
            return;
        }
        if (scope == RuleScope.SESSION) {
            engine.addSessionRule(rule);
            return;
        }
        // 落盘失败时 engine 已自行降级成会话规则；被 deny 遮蔽时什么都不加（两种情况都由 engine 记日志）
        if (!engine.addPersistentRule(rule)) {
            log.warn("规则 '{}' 未能写入项目层 permissions.json，仅本次会话生效或未生效（详见上一条日志）",
                    rule.toDsl());
        }
    }

    private String invoke(String toolInput, ToolContext toolContext) {
        return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
    }

    /** 把建议规则换个存放层（面板选「本会话」还是「永久」决定）。 */
    private static PermissionRule rescope(PermissionRule r, RuleScope scope) {
        return r == null ? null
                : new PermissionRule(r.toolName(), r.pattern(), PermissionBehavior.ALLOW, scope);
    }

    /** 给模型看的拒绝串——措辞要引导它换做法，而不是反复重试同一个动作。 */
    static String denyMessage(String reason) {
        return "Permission denied: " + reason + "。若确有必要，请说明理由或换一种做法。";
    }
}
