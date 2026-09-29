package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.llm.EmptyStreamException;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiChangeSource;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.SessionService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

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
 * <p><b>熔断矩阵</b>（spec §7 同时为真时原因唯一：STALLED &gt; ERROR &gt; EVALUATOR &gt; PROTOCOL）：
 * 四族独立计数——UNSATISFIED+stalled 与协议失败同入 {@code stalledStreak}，达 {@code stalledLimit}
 * → PAUSED(STALLED)；{@link #onTurnError} 非豁免根因连续达 {@code errorRetry+1} → PAUSED(ERROR)；
 * {@link #onEvaluationFailure} 达 {@code evalFailLimit} → PAUSED(EVALUATOR)；{@link #onProtocolFailure}
 * 达 {@code protocolFailLimit} → PAUSED(PROTOCOL)。用户插话（{@link #onUserDispatch}）重置全部计数。
 * 预算/轮数是<b>软超限</b>：只在 {@link #takeAutoTurn} 决策点清算（预算优先于轮数），在飞轮放行到轮末。
 *
 * <p><b>评估「回调即终点」</b>：{@link #beginEvaluation} CAS 置在飞并锁存 {@code dispatchSerial}；
 * onVerdict / onEvaluationFailure / onProtocolFailure 三回调：旧代迟到或终态 → 完全 no-op（不动
 * 当前代标志）；epoch 相符即自清在飞标志（无独立 endEvaluation），但 <b>phase≠RUNNING</b>
 * （PAUSED/INACTIVE——spec §3.3「在途评估不硬中断」，Esc/熔断暂停后的迟到判定一律丢弃，不得
 * terminate/pause 改判）或 <b>serial 不符</b>（评估在飞期间用户插话——spec §5.2「挂起 verdict
 * 单槽」：插话改变上下文，旧判断过期）则丢弃判定、只清标志，让恢复 RUNNING 后的下一空闲批重评。
 *
 * <p><b>滚动记录与评估素材</b>（spec §4）：{@link #recordTurnMaterial} 锁内落 ≤8 条 FIFO 轮记录
 * （agent 末文本经 {@link GoalText#tail} 2000 字符码点安全截断、含用户插话原文，activate 换代清空），
 * {@link #buildEvaluationInput} 锁内一次性取齐评估器输入；自动轮 prompt（spec §4.1）语言跟随条件
 * （含 CJK → 中文模板，否则英文模板），携带上一轮评估结论（首轮「（首轮）」）与累积进度账本
 * （空「（尚无）」）。
 */
public final class GoalManager implements UiChangeSource {

    private static final Logger log = LoggerFactory.getLogger(GoalManager.class);

    /** activate 条件的最大长度；超出拒绝而非静默截断。 */
    static final int MAX_CONDITION_LENGTH = 4000;

    /** 滚动记录上限：评估器只见最近 8 轮（spec §4 FIFO）。 */
    static final int MAX_HISTORY = 8;

    /** agent 末文本入滚动记录的尾部截断预算（字符）；{@link GoalText#tail} 码点对齐不劈代理对。 */
    static final int ASSISTANT_TAIL_CHARS = 2000;

    /** prompt 语言判定：条件含 CJK 统一表意文字 → 中文模板，否则英文模板（spec §4.1）。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

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
    /** 熔断计数（四族独立）：stalled（协议失败同账）/ turn 错误 / 评估失败 / 协议失败，达限 → PAUSED(对应原因)。 */
    private int stalledStreak, errorStreak, evalFailures, protocolFailures;
    /** 对话边界序号：onUserDispatch / takeAutoTurn 各 +1——语义=「评估输入所见的最后对话边界」。 */
    private long dispatchSerial;
    /** {@link #beginEvaluation} 置位时锁存的 {@link #dispatchSerial}；回调比对以识别评估在飞期间的插话。 */
    private long evalSerialLatch;
    /** 评估在飞标志（CAS 置位；同代回调即终点自清，activate 换代复位）。 */
    private boolean evalInFlight;
    private boolean autoTurnPending;
    private Long gapDeadlineEpochMs;
    private Instant activatedAt;
    /** 滚动轮记录（≤{@link #MAX_HISTORY} 条 FIFO，Task 5）：评估素材「最近几轮」最小快照；锁内读写。 */
    private final Deque<GoalTurnRecord> history = new ArrayDeque<>();
    /** 上一轮评估结论（onVerdict 放行时更新；{@code null}=首轮，prompt 写「（首轮）」）。 */
    private String lastEvalReason;
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
            this.history.clear();             // 新 goal 不见旧滚动记录（换代即清）
            this.lastEvalReason = null;       // 首轮：prompt 写「（首轮）」
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
     * 用户派发了一轮对话。<b>推进 dispatchSerial</b>（一次轮对话一次变化），PAUSED 任意原因→RUNNING
     * 并<b>重置全部熔断计数</b>（插话打断连击）；并清 autoTurnPending/gapDeadline——用户插话使挂起
     * 的自动轮与旧 verdict 作废（spec §5.2「挂起 verdict 单槽」），serial+1 同时使在飞评估的锁存
     * serial 过期（其迟到判定将被丢弃、在飞标志由该回调自清）。<b>不置 pending</b>：用户轮结束后
     * goal 槽照常发起评估（评估输入含该轮插话原文），verdict 再决定下一自动轮。INACTIVE/终态 no-op。
     */
    public void onUserDispatch() {
        long version = 0;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            dispatchSerial++;                 // 对话边界推进：使先前锁存的评估 serial 过期
            boolean real = false;
            if (phase == GoalPhase.PAUSED) {
                phase = GoalPhase.RUNNING;
                pauseReason = null;
                real = true;
            }
            if (stalledStreak != 0 || errorStreak != 0 || evalFailures != 0 || protocolFailures != 0) {
                stalledStreak = errorStreak = evalFailures = protocolFailures = 0;   // 插话打断熔断连击
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
     * （有待发轮时先发轮、不评估）；否则 -1，状态零变更。置位同时<b>锁存当前
     * {@code dispatchSerial}</b>——三回调以「epoch + 该锁存值」双校验识别评估输入是否过期。
     *
     * <p>终点：同代回调（onVerdict / onEvaluationFailure / onProtocolFailure）自清在飞标志
     * （「回调即终点」），activate 换代复位，终态清零——无独立 endEvaluation。
     */
    public long beginEvaluation() {
        long version;
        long captured;
        synchronized (this) {
            if (phase != GoalPhase.RUNNING || evalInFlight || autoTurnPending) return -1;
            evalInFlight = true;
            evalSerialLatch = dispatchSerial;   // 锁存评估输入所见的对话边界
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
     * 评估器判定入口。门：旧代迟到（epoch 不符）或终态单调 → 完全 no-op（不动当前代标志）；
     * epoch 相符即自清在飞标志（回调即终点），但 <b>phase≠RUNNING</b>（PAUSED/INACTIVE，spec §3.3
     * 「在途评估不硬中断」：如 Esc 暂停后迟到的 SATISFIED 不得 terminate）或 <b>serial 不符</b>
     * （评估在飞期间用户插话/自动轮派发，spec §5.2「挂起 verdict 单槽」：插话改变上下文，旧判断
     * 过期）则丢弃判定、只清标志（好让恢复 RUNNING 后的下一空闲批重评）。放行后：SATISFIED/IMPOSSIBLE
     * → 终态并记摘要；
     * UNSATISFIED → stalled 计数（达 {@code stalledLimit} → PAUSED(STALLED)，不置 pending）、
     * 否则恒置下一自动轮 pending 与 gap 倒计时——绝不允许「pending=false 等 deadline 叫醒」
     * 的断流形态。
     */
    public void onVerdict(long epoch, GoalVerdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        long version = 0;
        synchronized (this) {
            if (epoch != this.epoch || phase.isTerminal()) return;   // 旧代迟到/终态：完全 no-op
            boolean wasInFlight = evalInFlight;
            evalInFlight = false;             // 回调即终点（同代即清，PAUSED/serial 过期同样清）
            if (phase != GoalPhase.RUNNING || dispatchSerial != evalSerialLatch) {
                // PAUSED/INACTIVE（spec §3.3 迟到判定不硬中断）或评估期间发生对话边界（§5.2）：
                // 判定过期，丢弃但清标志放行恢复后重评
                if (wasInFlight) version = changed();
            } else {
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
                        stalledStreak = verdict.stalled() ? stalledStreak + 1 : 0;
                        if (verdict.stateLedger() != null) stateLedger = verdict.stateLedger();
                        lastEvalReason = verdict.reason();   // 下一自动轮 prompt 的「上一轮结论」
                        if (stalledStreak >= config.stalledLimit()) {
                            pauseLocked(PauseReason.STALLED);   // 熔断即断流：不置 pending/deadline
                        } else {
                            autoTurnPending = true;
                            gapDeadlineEpochMs = config.turnGapSeconds() > 0
                                    ? clock.millis() + config.turnGapSeconds() * 1000L : null;
                        }
                        version = changed();
                    }
                }
            }
        }
        publish(version);
    }

    /**
     * 评估器调用失败入口（超时/抛错同归此）。门同 {@link #onVerdict}（epoch + phase + serial）；放行后
     * {@code evalFailures+1}，达 {@code evalFailLimit} → PAUSED(EVALUATOR)。
     */
    public void onEvaluationFailure(long epoch, Throwable cause) {
        long version = 0;
        synchronized (this) {
            if (epoch != this.epoch || phase.isTerminal()) return;   // 旧代迟到/终态：完全 no-op
            boolean wasInFlight = evalInFlight;
            evalInFlight = false;             // 回调即终点（同代即清，PAUSED/serial 过期同样清）
            if (phase != GoalPhase.RUNNING || dispatchSerial != evalSerialLatch) {
                // PAUSED/INACTIVE（spec §3.3）或评估期间插话：失败判定同样过期，丢弃但清标志
                if (wasInFlight) version = changed();
            } else {
                evalFailures++;
                if (evalFailures >= config.evalFailLimit()) pauseLocked(PauseReason.EVALUATOR);
                version = changed();
            }
        }
        publish(version);
    }

    /**
     * 评估器输出协议失败入口（无 VERDICT 行/非法取值）。门同 {@link #onVerdict}（epoch + phase + serial）；
     * 放行后 {@code protocolFailures+1} 且<b>按 UNSATISFIED+stalled 同账</b>记一轮停滞（但不派发
     * 新轮：不置 pending/deadline）；stalled 达限优先 PAUSED(STALLED)（spec §7 STALLED &gt;
     * PROTOCOL），否则达 {@code protocolFailLimit} → PAUSED(PROTOCOL)，原始输出摘要记入 lastSummary。
     */
    public void onProtocolFailure(long epoch, String rawOutput) {
        long version = 0;
        synchronized (this) {
            if (epoch != this.epoch || phase.isTerminal()) return;   // 旧代迟到/终态：完全 no-op
            boolean wasInFlight = evalInFlight;
            evalInFlight = false;             // 回调即终点（同代即清，PAUSED/serial 过期同样清）
            if (phase != GoalPhase.RUNNING || dispatchSerial != evalSerialLatch) {
                // PAUSED/INACTIVE（spec §3.3）或评估期间插话：失败判定同样过期，丢弃但清标志
                if (wasInFlight) version = changed();
            } else {
                protocolFailures++;
                stalledStreak++;              // 按 UNSATISFIED+stalled 记账（不额外烧轮）
                if (stalledStreak >= config.stalledLimit()) {
                    pauseLocked(PauseReason.STALLED);
                } else if (protocolFailures >= config.protocolFailLimit()) {
                    lastSummary = abbreviateRaw(rawOutput);
                    pauseLocked(PauseReason.PROTOCOL);
                }
                version = changed();
            }
        }
        publish(version);
    }

    /**
     * 一轮对话以错误收场。仅 RUNNING 计账（INACTIVE/终态/PAUSED 的迟到事件 no-op，不改判既有
     * 暂停原因）；根因（cause 链走到底）为 {@link EmptyStreamException}（瞬态空流，交评估器判
     * stalled）或 {@link CancellationException}（Esc 取消）→ 豁免不计；其余 {@code errorStreak+1}，
     * 达 {@code errorRetry+1} 连续 → PAUSED(ERROR)。
     */
    public void onTurnError(Throwable rootCause) {
        long version = 0;
        synchronized (this) {
            if (phase != GoalPhase.RUNNING) return;
            Throwable root = unwrapRoot(rootCause);
            if (root instanceof EmptyStreamException || root instanceof CancellationException) return;
            if (++errorStreak > config.errorRetry()) pauseLocked(PauseReason.ERROR);
            version = changed();
        }
        publish(version);
    }

    /** 一轮对话正常收场：errorStreak 清零（成功重置错误连击）。INACTIVE/终态 no-op；无错误零变更。 */
    public void onTurnCompleted() {
        long version = 0;
        synchronized (this) {
            if (phase == GoalPhase.INACTIVE || phase.isTerminal()) return;
            if (errorStreak != 0) {
                errorStreak = 0;
                version = changed();
            }
        }
        publish(version);
    }

    /** 根因解包：沿 cause 链走到底（自引用/超长链防御，{@code null} 原样返回）。 */
    private static Throwable unwrapRoot(Throwable t) {
        Throwable root = t;
        for (int i = 0; i < 32 && root != null; i++) {
            Throwable cause = root.getCause();
            if (cause == null || cause == root) break;
            root = cause;
        }
        return root;
    }

    /** 协议失败摘要：压平空白 + 码点安全截 200 码点（照 {@code GoalVerdict} 缩略口径）。 */
    private static String abbreviateRaw(String raw) {
        if (raw == null) return "";
        String flat = raw.strip().replaceAll("\\s+", " ");
        if (flat.codePointCount(0, flat.length()) <= 200) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, 200)) + "…";
    }

    // ── 自动轮派发（UI 线程空闲批） ────────────────────────────────────

    /** 是否有挂起的自动轮（状态栏提示用）。 */
    public boolean hasAutoTurnPending() {
        synchronized (this) {
            return autoTurnPending;
        }
    }

    /**
     * 取走挂起的自动轮 prompt（UI 线程空闲批调用）：轮次 +1、<b>推进 dispatchSerial</b>
     * （自动轮也是对话边界）、预算与轮数复检、清 pending 与倒计时。无可发轮返回 null；
     * 预算超限 → {@link GoalPhase#BUDGET_EXCEEDED}、轮数耗尽 → {@link GoalPhase#MAX_TURNS}
     * （进终态并返回 null）。决策点顺序：phase → budget → maxTurns（软超限口径：在飞轮
     * 放行到轮末，判定只在决策点）。
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
                dispatchSerial++;             // 对话边界推进：先前锁存的评估 serial 至此过期
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

    /**
     * 预算是否软超限（对 activate 基线的增量计账：spent = Δprompt + Δcompletion ≥ budget）。
     * 预算 0=关闭、未注入 accumulator → 恒 {@code false}；只读，不触发任何状态转移——
     * 终结只在 {@link #takeAutoTurn} 决策点发生。
     */
    public boolean budgetExceeded() {
        synchronized (this) {
            return budgetExceededLocked();
        }
    }

    /**
     * 锁内合成自动轮 prompt（spec §4.1 完整文案）：语言跟随条件（{@link #CJK} 命中 → 中文模板，
     * 否则英文对照模板）；评估器结论行首轮写「（首轮）」/ "(first turn)"，累积进度空写
     * 「（尚无）」/ "(none yet)"。须持有监视器（读 condition/turnsUsed/lastEvalReason/stateLedger）。
     */
    private String buildAutoTurnPromptLocked() {
        boolean zh = CJK.matcher(condition).find();
        String reasonLine = (zh ? "评估器结论（上一轮）：" : "Evaluator verdict (previous turn): ")
                + (lastEvalReason == null ? (zh ? "（首轮）" : "(first turn)") : lastEvalReason);
        String ledgerLine = (zh ? "累积进度：" : "Cumulative progress: ")
                + (stateLedger == null || stateLedger.isBlank()
                        ? (zh ? "（尚无）" : "(none yet)") : stateLedger);
        if (zh) {
            return GoalText.continuePrefix(turnsUsed, config.maxTurns())
                    + "\n目标：" + condition + "\n" + reasonLine + "\n" + ledgerLine + "\n\n"
                    + "请继续推进。本轮结束时必须给出可复验的审计证据：执行过的命令与退出码、变更的文件列表；\n"
                    + "若认为目标已达成，请附上验证命令的原始输出。不要重复已完成的工作。";
        }
        return GoalText.continuePrefix(turnsUsed, config.maxTurns())
                + "\nGoal: " + condition + "\n" + reasonLine + "\n" + ledgerLine + "\n\n"
                + "Please continue. At the end of this turn you must provide reproducible audit evidence: the commands you ran and their exit codes, and the list of files you changed;\n"
                + "if you believe the goal is achieved, attach the raw output of the verification command. Do not repeat completed work.";
    }

    // ── 滚动记录与评估素材（Task 5） ────────────────────────────────────

    /**
     * 记录一轮素材（评估启动前由 View 空闲批调用；CodingAgent 收集接线在 Task 9）：锁内按
     * 「当前轮次 + 上一轮评估结论 + {@link GoalText#tail} 2000 字符码点安全尾部 + 工具调用数 +
     * 用户插话原文」落 {@link GoalTurnRecord}，超 {@link #MAX_HISTORY} 条 {@code pollFirst}
     * 挤掉最旧。属内部评估素材（{@link GoalStateSnapshot} 不含它）——不改可见状态，不推 UI 版本。
     */
    public void recordTurnMaterial(GoalTurnMaterial material) {
        Objects.requireNonNull(material, "material");
        synchronized (this) {
            history.addLast(new GoalTurnRecord(turnsUsed, lastEvalReason,
                    GoalText.tail(material.assistantTail(), ASSISTANT_TAIL_CHARS),
                    material.toolCallCount(), material.userInterjection()));
            while (history.size() > MAX_HISTORY) history.pollFirst();
        }
    }

    /**
     * 评估器输入快照：锁内一次性取齐条件/轮次/上限/停滞连击/账本/最近轮记录
     * （{@link EvaluationInput} 持防御性不可变副本；Task 6 的 {@code GoalEvaluator} 消费）。
     */
    public EvaluationInput buildEvaluationInput() {
        synchronized (this) {
            return new EvaluationInput(condition, turnsUsed, config.maxTurns(),
                    stalledStreak, stateLedger, List.copyOf(history));
        }
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
            // recentTraces：滚动记录 → UI 投影留待下游接线（Task 9），暂为空表
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
