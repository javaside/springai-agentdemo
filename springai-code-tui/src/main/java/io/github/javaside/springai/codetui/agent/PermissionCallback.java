package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionDecision;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfigLoader;
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

    /**
     * 规则记录结果的出口（生产实现是 {@code listener::onRuleRecorded}）。
     *
     * <p>与 {@link Asker} 分成两个窄接缝而不是直接持有 {@code AgentListener}：本类只需要
     * 「问一次」和「报一次结果」两件事，收窄接缝让测试不必实现十几个无关方法。
     *
     * <p><b>漏传只是少一行提示，不会挂死</b>（与 {@code asker} 不同），故默认可为 no-op。
     */
    @FunctionalInterface
    public interface RuleRecorder {
        void recorded(long turnId, boolean ok, String message);
    }

    /**
     * BYPASS 放行了一个命中内置底线的操作时的留痕出口
     * （生产实现是 {@code listener::onGuardrailBypassed}）。
     *
     * <p><b>为什么是这一层报而不是引擎报</b>：引擎是纯判定器，不认识 UI 也拿不到 turnId；
     * 它只把「这次放行踩了哪条底线」写进 {@link PermissionDecision#bypassedGuardrail()}，
     * 由本类翻译成事件。
     *
     * <p>与 {@link RuleRecorder} 一样，<b>漏传只是少一行提示、不会挂死</b>，故默认可为 no-op。
     */
    @FunctionalInterface
    public interface BypassNotifier {
        void bypassed(long turnId, String what);
    }

    private final ToolCallback delegate;
    private final PermissionEngine engine;
    private final Asker asker;
    private final RuleRecorder recorder;
    private final BypassNotifier bypassNotifier;

    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, Asker asker) {
        this(delegate, engine, asker, (t, ok, m) -> { });
    }

    public PermissionCallback(ToolCallback delegate, PermissionEngine engine,
                              Asker asker, RuleRecorder recorder) {
        this(delegate, engine, asker, recorder, (t, w) -> { });
    }

    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, Asker asker,
                              RuleRecorder recorder, BypassNotifier bypassNotifier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可为 null");
        this.engine = Objects.requireNonNull(engine, "engine 不可为 null");
        this.asker = Objects.requireNonNull(asker, "asker 不可为 null：漏传会让每次 ASK 都 park");
        this.recorder = recorder == null ? (t, ok, m) -> { } : recorder;
        this.bypassNotifier = bypassNotifier == null ? (t, w) -> { } : bypassNotifier;
    }

    /** 生产构造：三个接缝都挂到 listener 上。 */
    public PermissionCallback(ToolCallback delegate, PermissionEngine engine, AgentListener listener) {
        this(delegate, engine,
                Objects.requireNonNull(listener, "listener 不可为 null")::onPermissionRequested,
                listener::onRuleRecorded,
                listener::onGuardrailBypassed);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * 包私供装配点测试断言「三处装配点用的是同一个引擎」。
     *
     * <p>这不是可有可无的一句断言：各自新建引擎的话，模式切换（Shift+Tab）只影响其中一批工具，
     * 而用户在主 agent 里授过的会话规则对子 agent / MCP 工具无效——两种失效都不报错，只是行为诡异。
     */
    PermissionEngine engine() {
        return engine;
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
            if (decision.bypassedGuardrail() != null) {
                notifyBypass(ToolEventCallback.extractTurnId(toolContext), decision.bypassedGuardrail(), name);
            }
            return invoke(toolInput, toolContext);
        }
        if (decision.behavior() == PermissionBehavior.DENY) {
            return denyMessage(decision.reason());
        }
        // 后台任务不弹审批面板：它的 turnId 早已过期，请求会被 ConversationState 的迟到过滤
        // 当场 DENY——那是"静默拒绝"，模型只会看到一个没有理由的失败。这里主动拒绝并说明原因。
        // 与 BYPASS 档"永远不停下来等人"同源；BYPASS 本身走不到这里（那一档不产生 ASK）。
        //
        // ⚠ 判据只能是 backgroundTaskId，不能是 taskId：前台子 agent 也有 taskId，
        // 用它判后台会让前台子 agent 的审批面板静默消失（只在"派了子 agent 且需要审批"时才出现）。
        String backgroundTaskId = ToolEventCallback.extractBackgroundTaskId(toolContext);
        if (backgroundTaskId != null) {
            log.info("后台任务 {} 的 {} 调用需要审批，已就地拒绝（后台不弹面板）：{}",
                    backgroundTaskId, name, decision.reason());
            return denyMessage(backgroundDenyReason(decision.reason()));
        }
        return askThenAct(name, toolInput, toolContext, decision);
    }

    /**
     * 后台任务被拒时的理由文本：说清<b>三件事</b>——为什么被拒、当前是哪一档、正确的下一步。
     *
     * <p><b>按档位分支不是锦上添花</b>：只说"被拒了"的话，模型会对同一个操作反复重试直到耗光回合。
     * 这与计划模式当初必须给子 agent 专属提示是同一个教训——把"不知道为什么被拒"换成
     * "知道了、照做了、还是失败"，比不给提示更糟，所以给的那条路必须真的走得通。
     *
     * <p>故计划模式下<b>刻意不提</b> {@code run_in_background=false}：那一档改前台一样被拒，
     * 指这条路等于让它白试一次。
     */
    private String backgroundDenyReason(String engineReason) {
        String head = "后台任务不能弹出审批面板，本次调用已被拒绝。原因：" + engineReason + "。";
        return switch (engine.mode()) {
            case PLAN -> head + " 当前处于计划模式，写操作与非只读命令本来就会被拒绝——"
                    + "请把已有发现整理成结论返回，不要重试。";
            case ACCEPT_EDITS -> head + " 当前处于自动接受编辑档：工作区内的写操作本可自动放行，"
                    + "该目标在工作区外。请改用 run_in_background=false 的前台 Task 重试，"
                    + "或先为该操作添加 allow 规则。";
            default -> head + " 请改用 run_in_background=false 的前台 Task 重试"
                    + "（前台会弹审批面板让用户确认），或先为该操作添加 allow 规则。";
        };
    }

    /**
     * 把「BYPASS 放行了一个通常需要确认的操作」告诉 UI。
     *
     * <p><b>出口抛异常也照样执行工具</b>：留痕失败是少一行提示，把它变成工具调用失败就是
     * 让一条提示决定一次操作的成败——与 {@code asker} 的失败关闭成 DENY 不同，
     * 那里等不到应答会永久 park，这里没有任何活性依赖。日志仍留一条，别静默。
     */
    private void notifyBypass(long turnId, String what, String toolName) {
        log.warn("BYPASS 放行了一个通常需要确认的操作：tool={} 理由={}", toolName, what);
        try {
            bypassNotifier.bypassed(turnId, what);
        } catch (RuntimeException e) {
            log.error("BYPASS 留痕未能送达界面：tool={}", toolName, e);
        }
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
            log.info("{} 的这次审批没有可用的建议规则，只放行本次（内置危险检查 / ask 规则引发的 ASK）", toolName);
            return;
        }
        long turnId = ToolEventCallback.currentTurnId();
        if (scope == RuleScope.SESSION) {
            engine.addSessionRule(rule);
            recorder.recorded(turnId, true, "✓ 本会话不再询问：" + rule.toDsl());
            return;
        }
        // 被 deny 遮蔽与写盘失败是两回事，必须分开告诉用户：前者写下去也永远不会生效，
        // 后者只是这次没落盘。engine.addPersistentRule 对两者都返回 false，故先问 denyingRule。
        PermissionRule blocking = engine.denyingRule(rule);
        if (blocking != null) {
            recorder.recorded(turnId, false,
                    "⚠ 规则 " + rule.toDsl() + " 已被 deny 规则 " + blocking.toDsl() + " 禁止，未记录");
            log.warn("规则 '{}' 被 deny 规则 '{}' 遮蔽，未写入。", rule.toDsl(), blocking.toDsl());
            return;
        }
        java.nio.file.Path file = scope == RuleScope.USER
                ? PermissionConfigLoader.userFile()
                : PermissionConfigLoader.projectFile(engine.root());
        if (engine.addPersistentRule(rule)) {
            recorder.recorded(turnId, true,
                    "✓ 已记下允许规则：" + rule.toDsl() + " → 已写入 " + file);
        } else {
            recorder.recorded(turnId, false,
                    "⚠ 规则 " + rule.toDsl() + " 写盘失败（" + file + "），仅本次会话生效");
            log.warn("规则 '{}' 未能写入 {}，已降级为会话规则。", rule.toDsl(), file);
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
