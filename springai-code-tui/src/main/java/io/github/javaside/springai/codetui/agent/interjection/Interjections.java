package io.github.javaside.springai.codetui.agent.interjection;

import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiChangeSource;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「插话」队列：用户在回合<b>进行中</b>输入的消息，等下一次模型调用时随 prompt 送达。
 *
 * <p><b>解决的问题</b>：过去这类消息进 {@code ConversationState} 的排队队列，要等<b>整个回合</b>
 * （可能十几轮工具调用）跑完才出队。而用户在工具循环中途开口，十有八九是看它跑偏了想纠正——
 * 等事都做完再听，这句话就从「纠偏」降级成了「善后」。改成插话后，等待缩短到<b>当前这一个工具</b>。
 *
 * <p><b>为什么消息要攒在这里、而不是直接写进会话存储</b>（实测结论，见 {@code MidTurnInjectionTest}）：
 * 工具循环期间会话存储里只有 {@code user + assistant(tool_calls)}，工具结果<b>尚未落库</b>
 * （它与「构建下一次 prompt」是同一步）。此刻往存储里追加，消息必然落在 {@code assistant(tool_calls)}
 * 与 {@code tool} 结果<b>之间</b>——那是非法序列，下一次请求直接 400。
 * 合法位置只有「所有工具结果之后」，而只有 {@link InterjectingChatModel} 所在的 ChatModel 层
 * 看得到那张已经配平的完整消息表。
 *
 * <p><b>并发</b>：UI 线程 {@link #offer}，模型线程 {@link #drainForInjection}，
 * 回合结束线程 {@link #takeForHistory}——全部 synchronized，锁粒度是整个对象（操作都是纳秒级）。
 *
 * <p><b>变化通知纪律（事件驱动 UI，Task 4）</b>：本类同时是 {@link UiChangeSource}——
 * <ul>
 *   <li>锁内只改数据、递增 {@link #uiVersion}；<b>锁外</b>才调 listener（UI 醒来要回读本队列的
 *       synchronized 快照，锁内通知等于邀请死锁——与 {@code ConversationState} 同一条纪律，
 *       本类与它还早已存在双锁交叉持有的访问顺序）；</li>
 *   <li>真实变化（入队、pending→delivered、任何真实移除）→ 恰好一次 {@code VIEW|CONTROL}
 *       （插话面板 + 状态栏 pending 计数 / Esc 回填都要重估）；空操作不通知、不推进版本；</li>
 *   <li>{@link #fireDelivered} 是<b>业务</b>回调（scrollback 行）、不改任何状态：
 *       <b>不</b>发 UI 通知——业务回调与 UI wake 刻意不合并，各自走各自的路；</li>
 *   <li>listener 抛出的 {@link RuntimeException} 被隔离成日志，不得回传 UI / 模型线程。</li>
 * </ul>
 */
public final class Interjections implements UiChangeSource {

    private static final Logger log = LoggerFactory.getLogger(Interjections.class);

    /** 尚未送达模型的插话，按输入顺序。 */
    private final Deque<String> pending = new ArrayDeque<>();

    /** 已随 prompt 送达、但尚未补进会话历史的文本（回合末由 {@link #takeForHistory} 取走）。 */
    private String delivered;

    // ── 变化通知（事件驱动 UI；见类注释「变化通知纪律」） ──────────────────
    private volatile UiChangeListener uiChangeListener = UiChangeListener.noop();

    /** 单调递增的状态版本。仅诊断用，不参与任何跨 source 比较。 */
    private final AtomicLong uiVersion = new AtomicLong();

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
            log.warn("UI change listener failed at interjections version {}", version, e);
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


    /**
     * 送达时 prompt 末尾那条 tool 消息的 id，作为「补历史时插在谁后面」的锚。
     *
     * <p>为什么需要锚：回合末会话历史里已经是
     * {@code user → assistant(tool_calls) → tool → assistant(收尾)}，
     * 而插话在时序上属于「tool 之后、收尾之前」。没有锚就只能猜位置，多轮工具时必错。
     * 锚为 null 表示送达时末尾不是 tool 消息（例如模型还在第一次思考就插了话）。
     */
    private String anchorToolCallId;

    /** 用户输入了一条插话。空白 / null 不入队、不通知（no-op）。 */
    public void offer(String text) {
        if (text == null || text.isBlank()) return;
        long version;
        synchronized (this) {
            pending.add(text);
            version = changed();
        }
        publish(version);
    }

    /** 还有几条没送到模型手里（状态栏用）。 */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /**
     * 未送达插话的<b>非破坏性</b>快照，供输入框上方的面板每帧读取（形状同排队面板）。
     *
     * <p><b>只列未送达的</b>：已送达的那条此刻正躺在 scrollback 的信息流里（送达回调打的那一行），
     * 面板再列一遍就是同一句话在屏幕上出现两次。面板的语义是「还没走的」，走了就该从面板消失。
     */
    public synchronized List<String> pendingSnapshot() {
        return List.copyOf(pending);
    }

    /**
     * 送达通知。{@link InterjectingChatModel} 把话塞进 prompt 之后调用，UI 据此把它打进信息流。
     *
     * <p><b>为什么非要有这个信号</b>：注入指引明写「若与当前方向冲突就调整，否则先把手头的做完」，
     * 即<b>模型正确消化了插话却不显式回应，是常态</b>。没有这个信号，屏幕上
     * 「送达并被采纳」「送达但被无视」「接线断了压根没送出去」三者完全无法区分。
     *
     * <p>{@code volatile} 而非 synchronized：回调要在<b>锁外</b>触发（见 {@link #fireDelivered}）。
     */
    private volatile java.util.function.Consumer<String> onDelivered;

    /** 接上送达回调（{@code CodingAgent} 构造时接，转成 {@code listener.onUserMessage}）。传 null 即摘掉。 */
    public void onDelivered(java.util.function.Consumer<String> listener) {
        this.onDelivered = listener;
    }

    /**
     * 触发送达回调。<b>刻意不 synchronized</b>：回调会一路走进 {@code ConversationState}，
     * 而那边的方法全是 synchronized——在本对象的锁里回调进去就形成了两把锁的交叉持有，
     * 而 UI 线程渲染时正好按相反顺序摸这两把锁（先 {@code queuedSnapshot} 后
     * {@link #pendingSnapshot}）。故调用方须在 {@code drainForInjection} 返回<b>之后</b>再调本方法。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public void fireDelivered(String rawText) {
        java.util.function.Consumer<String> l = onDelivered;
        if (l != null) {
            l.accept(rawText);
        }
    }

    /**
     * 只取未送达的（回合<b>正常结束</b>时 UI 兜底出队用）。
     *
     * <p><b>刻意不动 {@code delivered}</b>：那一条归 {@link #takeForHistory} 补历史。两者若共用一个
     * 方法，回合末谁先跑到谁拿走——UI 先拿走的话，已送达的插话既没进历史，又被当成新回合再发一遍，
     * 模型会看到同一句话两次。
     */
    public List<String> takePendingOnly() {
        List<String> out;
        long version = 0L;
        synchronized (this) {
            out = new ArrayList<>(pending);
            if (!out.isEmpty()) {                 // 空队列取走是 no-op：不通知
                pending.clear();
                version = changed();
            }
        }
        publish(version);
        return out;
    }

    /**
     * Esc 取消回合时把插话取走——调用方<b>回填输入框</b>，不是丢弃。
     *
     * <p>按 Esc 通常正是「别跑了，听我的」，那句话不该跟着一起没。旧的排队队列在 Esc 时
     * 直接 {@code clearQueued()}，用户得重打一遍。
     *
     * <p><b>为什么连 {@code delivered} 一起交还</b>（这里跟 {@link #takePendingOnly} 反着来）：
     * 取消走 {@code doOnCancel}，{@code handleComplete} 根本不跑，没人拿 {@code delivered} 去补历史。
     * 不交还的话，那句话模型看过了、会话历史里没有、用户手上也没有——凭空消失。这条路径上不存在
     * 抢夺：补历史的那个消费者压根没被触发。
     *
     * <p>已送达的排在前面，因为它时序上更早（先说的先送达）。交还的是<b>原文</b>，不是
     * {@link InterjectingChatModel} 包裹后的文本——用户要拿回的是自己那句话，好改一改重发。
     */
    public List<String> drainForRefill() {
        List<String> out;
        long version = 0L;
        synchronized (this) {
            out = new ArrayList<>();
            if (delivered != null) {
                out.add(delivered);
            }
            out.addAll(pending);
            if (!out.isEmpty()) {                 // 两边都空是 no-op：不通知
                pending.clear();
                delivered = null;
                anchorToolCallId = null;
                version = changed();
            }
        }
        publish(version);
        return out;
    }

    /**
     * 模型层取走待注入文本，<b>合并成一条</b>。
     *
     * <p>合并而不是逐条追加：多条连续同角色消息在部分 provider 上会被折叠或报错，
     * 这个项目为此已经吃过一次亏（会话记忆里有专门的「折叠连续同角色」处理）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     *
     * @param anchor 当前 prompt 末尾 tool 消息的 id；无则传 null
     */
    public Optional<String> drainForInjection(String anchor) {
        String merged;
        long version = 0L;
        // 锁内只做状态迁移（pending→delivered）+ 记账；publish 在锁外（见类注释）。
        synchronized (this) {
            if (pending.isEmpty()) {
                return Optional.empty();          // no-op：不通知、不推进版本
            }
            merged = String.join("\n", pending);
            pending.clear();
            // 同回合内第二次插话：把两次合并，锚取最新的一次（更靠后，位置仍合法）。
            delivered = delivered == null ? merged : delivered + "\n" + merged;
            anchorToolCallId = anchor;
            version = changed();
        }
        publish(version);
        return Optional.of(merged);
    }

    /**
     * 回合末取走「已送达但未入历史」的文本与锚；取走后清空。无则返回 empty。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public Optional<Delivered> takeForHistory() {
        Delivered d;
        long version = 0L;
        synchronized (this) {
            if (delivered == null) {
                return Optional.empty();          // 无可取：no-op
            }
            d = new Delivered(delivered, anchorToolCallId);
            delivered = null;
            anchorToolCallId = null;
            version = changed();
        }
        publish(version);
        return Optional.of(d);
    }

    /**
     * 待补进历史的一条插话：文本 + 它当时跟在哪条 tool 消息后面（anchor 可为 null）。
     *
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public record Delivered(String text, String anchorToolCallId) { }
}
