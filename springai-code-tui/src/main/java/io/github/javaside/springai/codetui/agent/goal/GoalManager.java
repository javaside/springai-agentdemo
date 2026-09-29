package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiChangeSource;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /goal 自主循环的状态机核心：RUNNING / PAUSED(原因) / 六个终态，外加「代」（epoch）防陈旧。
 *
 * <p><b>转移表</b>：
 * <ul>
 *   <li>{@link #activate}：任意态（含终态）→ RUNNING，换代（epoch 递增使旧代在途评估/回调作废），
 *       并置首轮自动轮 pending；空白或超 4000 字符抛 {@link IllegalArgumentException}（View 预判转
 *       notice，不静默截断用户意图）；</li>
 *   <li>{@link #pauseByEsc}：RUNNING→PAUSED(ESC)（第一级，清 pending/gapDeadline）；
 *       PAUSED(ESC)→CANCELLED（第二级）；PAUSED 非 ESC 不升级（spec §3.3）；</li>
 *   <li>{@link #pause}/{@link #terminate}（包内）：RUNNING→PAUSED(原因) / 活动态→终态
 *       （均清 pending/gapDeadline）；</li>
 *   <li>{@link #onUserDispatch}：PAUSED 任意原因→RUNNING 并重置熔断计数；<b>不置 pending</b>——
 *       用户轮结束后先评估该轮（评估输入含插话原文），verdict 再决定下一自动轮（spec §5.2）；</li>
 *   <li><b>终态单调</b>：{@link #onVerdict} / {@link #onTurnError} 等一切迟到事件在
 *       {@code phase().isTerminal()} 时 no-op；{@link #clear} 是用户命令、可从任意非 INACTIVE
 *       相态转 CLEARED。</li>
 * </ul>
 *
 * <p><b>锁纪律</b>（照 {@code Interjections} 先例）：状态字段全部在 {@code synchronized(this)}
 * 内读写；锁内只改数据并经 {@link #changed} 推进版本，<b>锁外</b>才经 {@link #publish} 调
 * listener（UI 醒来要回读本对象的 synchronized 快照，锁内通知等于邀请死锁）；真实变化恰好记账
 * 一次，no-op 不推进版本；listener 抛出的 {@link RuntimeException} 被隔离成日志。
 *
 * <p><b>本 Task 边界</b>：只做状态机。熔断矩阵（stalled/error/evaluator/protocol 计数达限暂停、
 * 预算/轮数软超限口径、dispatchSerial 锁存校验）与滚动记录/完整 prompt 文案分别是 Task 4/5 的
 * 扩展——相关计数器字段已占位，{@link #onVerdict} 只做简版计数与断流防护（UNSATISFIED 恒置
 * pending），不触发暂停。
 */
public final class GoalManager implements UiChangeSource {

    private static final Logger log = LoggerFactory.getLogger(GoalManager.class);

    /** activate 条件的最大长度；超出拒绝而非静默截断。 */
    static final int MAX_CONDITION_LENGTH = 4000;

    private final GoalConfig config;
    /** 会话级 token 累加器；可 null=预算永不清算。 */
    private final TokenUsageAccumulator usage;
    /** 倒计时 deadline 的生成与判定统一走表（测试拨表用；生产 {@link Clock#systemDefaultZone()}）。 */
    private final Clock clock;

    // ── 变化通知（照 Interjections：锁内 changed()、锁外 publish、no-op 不推版本） ──
    private volatile UiChangeListener uiChangeListener = UiChangeListener.noop();
    /** 单调递增的状态版本。仅诊断用，不参与任何跨 source 比较。 */
    private final AtomicLong uiVersion = new AtomicLong();
    /** goal 代号发源器；activate 一次 +1。 */
    private final AtomicLong goalIds = new AtomicLong();

    // 以下全部在 synchronized(this) 内读写
    private GoalPhase phase = GoalPhase.INACTIVE;
    private PauseReason pauseReason;
    private String condition;
    /** 当前 goal 的代；旧代在途评估/回调按 epoch 不符丢弃。 */
    private long epoch;
    private int turnsUsed;
    /** activate 时的 token 基线快照（预算只对增量计账）。 */
    private long basePromptTokens, baseCompletionTokens;
    /** 熔断计数（占位：达限→暂停的判定在 Task 4 接线）。 */
    private int stalledStreak, errorStreak, evalFailures, protocolFailures;
    /** 评估在飞标志（CAS 置位；同代回调即终点自清，activate 换代复位）。 */
    private boolean evalInFlight;
    private boolean autoTurnPending;
    private Long gapDeadlineEpochMs;
    private Instant activatedAt;
    // Task 5：private final Deque<GoalTurnRecord> history = new ArrayDeque<>();（≤8，滚动记录）
    private String stateLedger = "";
    private String lastSummary = "";

    /** 评估结论落库接线（Task 9 由 CodingAgent 两段式调用；均 null 时评估结论不落库）。 */
    private volatile SessionService sessionService;
    private volatile Supplier<String> sessionIdSupplier;

    /** 生产构造。 */
    public GoalManager(GoalConfig config, TokenUsageAccumulator usage) {
        this(config, usage, Clock.systemDefaultZone());
    }

    /** 包内构造：测试拨表用，deadline 判定统一走注入时钟。 */
    GoalManager(GoalConfig config, TokenUsageAccumulator usage, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.usage = usage;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── 变化通知（见类注释「锁纪律」） ──────────────────────────────────

    /** 记录一次有效变化并推进版本。<b>必须在持有本类监视器时调用</b>（版本与状态同序）。 */
    private long changed() {
        return uiVersion.incrementAndGet();
    }

    /** 锁外发布变化：0 直接丢弃（no-op 路径根本不记账）；listener 异常只记日志。 */
    private void publish(long version) {
        if (version <= 0) return;
        try {
            uiChangeListener.onUiChanged(UiDirty.VIEW | UiDirty.CONTROL);
        } catch (RuntimeException e) {
            log.warn("UI change listener failed at goal version {}", version, e);
        }
    }

    @Override
    public void setUiChangeListener(UiChangeListener listener) {
        uiChangeListener = listener == null ? UiChangeListener.noop() : listener;
    }

    @Override
    public long uiVersion() {
        return uiVersion.get();
    }

    // ── 状态机：激活 / 清空 ────────────────────────────────────────────

    /**
     * 设定/替换目标：任意相态（含终态）→ RUNNING，epoch 递增使旧代在途评估作废，并置首轮
     * 自动轮 pending。空白或超 4000 字符抛 {@link IllegalArgumentException}、状态零变更。
     */
    public void activate(String condition) {
        if (condition == null || condition.isBlank()) throw new IllegalArgumentException("goal 条件为空");
        if (condition.length() > MAX_CONDITION_LENGTH) {
            throw new IllegalArgumentException("goal 条件超 4000 字符（当前 " + condition.length() + "）");
        }
        long version;
        synchronized (this) {
            this.condition = condition;
            this.epoch = goalIds.incrementAndGet();
            this.phase = GoalPhase.RUNNING;
            this.pauseReason = null;
            this.turnsUsed = 0;
            this.stalledStreak = this.errorStreak = this.evalFailures = this.protocolFailures = 0;
            var snap = usage == null ? null : usage.snapshot();
            this.basePromptTokens = snap == null ? 0 : snap.promptTokens();
            this.baseCompletionTokens = snap == null ? 0 : snap.completionTokens();
            this.autoTurnPending = true;      // 首轮
            this.gapDeadlineEpochMs = null;
            this.evalInFlight = false;        // 旧 goal 在途评估按 epoch 丢弃
            this.activatedAt = Instant.now(clock);
            // Task 5：history.clear() 随滚动记录字段一并恢复
            this.stateLedger = "";
            this.lastSummary = "";
            version = changed();
        }
        publish(version);
        log.info("goal 已激活（epoch={}，maxTurns={}，预算={}）：{}", epoch, config.maxTurns(),
                config.tokenBudget(), condition);
    }

    /** 清空为 {@link GoalPhase#CLEARED} 终态；INACTIVE（以及已 CLEARED）时 no-op。 */
    public void clear(String via) {
        long version;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase == GoalPhase.CLEARED) return;
            terminateLocked(GoalPhase.CLEARED);
            version = changed();
        }
        publish(version);
        log.info("goal 已清空（via={}）", via);
    }

    // ── 状态机：暂停 / 恢复 / 终态 ─────────────────────────────────────

    /**
     * Esc 两级语义：RUNNING（自动轮在飞/评估中/倒计时中）→ PAUSED(ESC)（第一级，取消待发轮）；
     * PAUSED(ESC) → CANCELLED（第二级）。PAUSED 非 ESC 不升级、INACTIVE/终态 no-op。
     */
    public void pauseByEsc() {
        long version;
        synchronized (this) {
            if (phase == GoalPhase.RUNNING) {
                pauseLocked(PauseReason.ESC);
                version = changed();
            } else if (phase == GoalPhase.PAUSED && pauseReason == PauseReason.ESC) {
                terminateLocked(GoalPhase.CANCELLED);
                version = changed();
            } else {
                return;                       // 迟到事件：no-op 不推版本
            }
        }
        publish(version);
    }

    /** 熔断暂停（包内）：RUNNING→PAUSED(reason)，取消待发轮与倒计时；其余相态 no-op。 */
    void pause(PauseReason reason) {
        Objects.requireNonNull(reason, "reason");
        long version;
        synchronized (this) {
            if (phase != GoalPhase.RUNNING) return;
            pauseLocked(reason);
            version = changed();
        }
        publish(version);
    }

    /** 进入终态（包内）：只有非 INACTIVE 的非终态相态可转；终态单调、迟到调用 no-op。 */
    void terminate(GoalPhase terminal) {
        Objects.requireNonNull(terminal, "terminal");
        long version;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            terminateLocked(terminal);
            version = changed();
        }
        publish(version);
    }

    /** 锁内暂停：清 autoTurnPending/gapDeadline（待发轮取消、倒计时作废）。须持有监视器。 */
    private void pauseLocked(PauseReason reason) {
        phase = GoalPhase.PAUSED;
        pauseReason = reason;
        autoTurnPending = false;
        gapDeadlineEpochMs = null;
    }

    /** 锁内终态：终态单调的单一执行点。须持有监视器；重复调用自然 no-op。 */
    private void terminateLocked(GoalPhase terminal) {
        if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
        phase = Objects.requireNonNull(terminal);
        pauseReason = null;
        autoTurnPending = false;
        gapDeadlineEpochMs = null;
        evalInFlight = false;
    }

    /**
     * 用户派发了一轮对话。PAUSED 任意原因→RUNNING 并重置熔断计数；<b>并清
     * autoTurnPending/gapDeadline</b>——用户插话使挂起的自动轮与旧 verdict 作废（spec §5.2
     * 「挂起 verdict 单槽」）。<b>不置 pending</b>：用户轮结束后 goal 槽照常发起评估（评估输入
     * 含该轮插话原文），verdict 再决定下一自动轮。INACTIVE/终态 no-op。
     */
    public void onUserDispatch() {
        long version = 0;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            boolean real = false;
            if (phase == GoalPhase.PAUSED) {
                phase = GoalPhase.RUNNING;
                pauseReason = null;
                stalledStreak = errorStreak = evalFailures = protocolFailures = 0;   // 对应计数重置
                real = true;
            }
            if (autoTurnPending || gapDeadlineEpochMs != null) {
                autoTurnPending = false;
                gapDeadlineEpochMs = null;
                real = true;
            }
            if (real) version = changed();
        }
        publish(version);
    }

    // ── 评估在飞 / verdict 入口 ────────────────────────────────────────

    /**
     * CAS 置评估在飞：仅 RUNNING 且无在飞评估且无待发自动轮时置位并返回当前 epoch
     * （有待发轮时先发轮、不评估）；否则 -1，状态零变更。
     *
     * <p>终点：同代回调（{@link #onVerdict}）自清在飞标志（「回调即终点」），activate 换代复位，
     * 终态清零——无独立 endEvaluation。dispatchSerial 锁存校验是 Task 4 扩展。
     */
    public long beginEvaluation() {
        long version;
        long captured;
        synchronized (this) {
            if (phase != GoalPhase.RUNNING || evalInFlight || autoTurnPending) return -1;
            evalInFlight = true;
            captured = this.epoch;        // 锁内捕获：锁外读会与 activate 换代竞态，放行陈旧 verdict
            version = changed();
        }
        publish(version);
        return captured;
    }

    /** 评估是否在飞（锁内读；goal 槽防重复发起）。 */
    public boolean evaluationInFlight() {
        synchronized (this) {
            return evalInFlight;
        }
    }

    /**
     * 评估器判定入口（简版：熔断计数达限→暂停是 Task 4 扩展）。旧代迟到或终态单调 no-op；
     * 同代先自清在飞标志（回调即终点），随后 SATISFIED/IMPOSSIBLE → 终态并记摘要；
     * UNSATISFIED → 简版停滞计数（不触发暂停）、合并 STATE 账本、恒置下一自动轮 pending
     * 与倒计时 deadline——绝不允许「pending=false 等 deadline 叫醒」的断流形态。
     */
    public void onVerdict(long epoch, GoalVerdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        long version = 0;
        synchronized (this) {
            if (epoch != this.epoch || phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            evalInFlight = false;             // 回调即终点（同代才清）
            switch (verdict.outcome()) {
                case SATISFIED -> {
                    lastSummary = verdict.reason();
                    terminateLocked(GoalPhase.SATISFIED);
                    version = changed();
                }
                case IMPOSSIBLE -> {
                    lastSummary = verdict.reason();
                    terminateLocked(GoalPhase.IMPOSSIBLE);
                    version = changed();
                }
                case UNSATISFIED -> {
                    stalledStreak = verdict.stalled() ? stalledStreak + 1 : 0;   // 占位：达限熔断 Task 4
                    if (verdict.stateLedger() != null) stateLedger = verdict.stateLedger();
                    autoTurnPending = true;
                    gapDeadlineEpochMs = config.turnGapSeconds() > 0
                            ? clock.millis() + config.turnGapSeconds() * 1000L : null;
                    version = changed();
                }
            }
        }
        publish(version);
    }

    /**
     * 一轮对话以错误收场（简版：只做终态单调防护与计数占位——EmptyStream/Cancellation 豁免、
     * errorRetry 达限→PAUSED(ERROR) 是 Task 4 扩展）。终态/未激活 no-op。
     */
    public void onTurnError(Throwable rootCause) {
        long version = 0;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            errorStreak++;                    // 占位：豁免与达限熔断 Task 4
            version = changed();
        }
        publish(version);
    }

    // ── 自动轮派发（UI 线程空闲批） ────────────────────────────────────

    /** 是否有挂起的自动轮（状态栏提示用）。 */
    public boolean hasAutoTurnPending() {
        synchronized (this) {
            return autoTurnPending;
        }
    }

    /**
     * 取走挂起的自动轮 prompt（UI 线程空闲批调用）：轮次 +1、预算与轮数复检、清 pending 与
     * 倒计时。无可发轮返回 null；预算超限 → {@link GoalPhase#BUDGET_EXCEEDED}、轮数耗尽 →
     * {@link GoalPhase#MAX_TURNS}（进终态并返回 null）。决策点顺序：phase → budget → maxTurns。
     */
    public String takeAutoTurn() {
        String prompt;
        long version;
        synchronized (this) {
            if (phase != GoalPhase.RUNNING || !autoTurnPending) return null;
            if (budgetExceededLocked()) {
                terminateLocked(GoalPhase.BUDGET_EXCEEDED);
                version = changed();
                prompt = null;
            } else if (turnsUsed + 1 > config.maxTurns()) {
                terminateLocked(GoalPhase.MAX_TURNS);
                version = changed();
                prompt = null;
            } else {
                turnsUsed++;
                autoTurnPending = false;
                gapDeadlineEpochMs = null;
                prompt = buildAutoTurnPromptLocked();
                version = changed();
            }
        }
        publish(version);
        return prompt;
    }

    /** 倒计时截止时刻（epoch ms）；无倒计时为 {@code null}。 */
    public Long gapDeadlineEpochMs() {
        synchronized (this) {
            return gapDeadlineEpochMs;
        }
    }

    /** 锁内预算判定：预算 0=关闭、未注入 accumulator 时永不清算；对 activate 基线的增量计账。 */
    private boolean budgetExceededLocked() {
        if (config.tokenBudget() == 0 || usage == null) return false;
        var snap = usage.snapshot();
        long spent = (snap.promptTokens() - basePromptTokens) + (snap.completionTokens() - baseCompletionTokens);
        return spent >= config.tokenBudget();
    }

    /** 锁内合成自动轮 prompt。简版；完整文案（语言跟随条件/上轮结论/累积进度）Task 5。 */
    private String buildAutoTurnPromptLocked() {
        return GoalText.continuePrefix(turnsUsed, config.maxTurns()) + "\n目标：" + condition;
    }

    // ── 只读视图 ───────────────────────────────────────────────────────

    /** 当前 goal 代（= goalId，activate 时递增）。 */
    public long currentEpoch() {
        synchronized (this) {
            return epoch;
        }
    }

    /** 当前相位。 */
    public GoalPhase phase() {
        synchronized (this) {
            return phase;
        }
    }

    /** 面板/状态栏共用的一次性锁内快照（避免两次锁读撕裂）。 */
    public GoalStateSnapshot snapshot() {
        synchronized (this) {
            long spent = 0;
            if (usage != null && condition != null) {
                var snap = usage.snapshot();
                spent = (snap.promptTokens() - basePromptTokens) + (snap.completionTokens() - baseCompletionTokens);
            }
            // recentTraces：Task 5 接入滚动记录前为空表
            return new GoalStateSnapshot(phase, pauseReason, condition, turnsUsed, config.maxTurns(),
                    Math.max(0, spent), config.tokenBudget(), stalledStreak, activatedAt,
                    List.of(), lastSummary);
        }
    }

    // ── 落库接线（Task 9） ─────────────────────────────────────────────

    /**
     * 接会话存储（Task 9 由 CodingAgent 两段式调用）。两者均 null 时评估结论不落库；
     * 只在装配期调用一次，故 volatile 字段直写即可。
     */
    public void bindSession(SessionService sessionService, Supplier<String> sessionIdSupplier) {
        this.sessionService = sessionService;
        this.sessionIdSupplier = sessionIdSupplier;
    }
}
