package io.github.javaside.springai.codetui.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

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
 */
public final class Interjections {

    /** 尚未送达模型的插话，按输入顺序。 */
    private final Deque<String> pending = new ArrayDeque<>();

    /** 已随 prompt 送达、但尚未补进会话历史的文本（回合末由 {@link #takeForHistory} 取走）。 */
    private String delivered;

    /**
     * 送达时 prompt 末尾那条 tool 消息的 id，作为「补历史时插在谁后面」的锚。
     *
     * <p>为什么需要锚：回合末会话历史里已经是
     * {@code user → assistant(tool_calls) → tool → assistant(收尾)}，
     * 而插话在时序上属于「tool 之后、收尾之前」。没有锚就只能猜位置，多轮工具时必错。
     * 锚为 null 表示送达时末尾不是 tool 消息（例如模型还在第一次思考就插了话）。
     */
    private String anchorToolCallId;

    /** 用户输入了一条插话。 */
    public synchronized void offer(String text) {
        if (text != null && !text.isBlank()) {
            pending.add(text);
        }
    }

    /** 还有几条没送到模型手里（状态栏用）。 */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /**
     * Esc 取消回合时把未送达的插话取走——调用方<b>回填输入框</b>，不是丢弃。
     *
     * <p>按 Esc 通常正是「别跑了，听我的」，那句话不该跟着一起没。旧的排队队列在 Esc 时
     * 直接 {@code clearQueued()}，用户得重打一遍。
     */
    public synchronized List<String> drainForRefill() {
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        delivered = null;
        anchorToolCallId = null;
        return out;
    }

    /**
     * 模型层取走待注入文本，<b>合并成一条</b>。
     *
     * <p>合并而不是逐条追加：多条连续同角色消息在部分 provider 上会被折叠或报错，
     * 这个项目为此已经吃过一次亏（会话记忆里有专门的「折叠连续同角色」处理）。
     *
     * @param anchor 当前 prompt 末尾 tool 消息的 id；无则传 null
     */
    synchronized Optional<String> drainForInjection(String anchor) {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        String merged = String.join("\n", pending);
        pending.clear();
        // 同回合内第二次插话：把两次合并，锚取最新的一次（更靠后，位置仍合法）。
        delivered = delivered == null ? merged : delivered + "\n" + merged;
        anchorToolCallId = anchor;
        return Optional.of(merged);
    }

    /** 回合末取走「已送达但未入历史」的文本与锚；取走后清空。无则返回 empty。 */
    synchronized Optional<Delivered> takeForHistory() {
        if (delivered == null) {
            return Optional.empty();
        }
        Delivered d = new Delivered(delivered, anchorToolCallId);
        delivered = null;
        anchorToolCallId = null;
        return Optional.of(d);
    }

    /** 待补进历史的一条插话：文本 + 它当时跟在哪条 tool 消息后面（anchor 可为 null）。 */
    record Delivered(String text, String anchorToolCallId) { }
}
