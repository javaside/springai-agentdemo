package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AgentListener;
import io.github.javaside.springai.codetui.agent.AskRequest;
import io.github.javaside.springai.codetui.agent.ModalRequest;
import io.github.javaside.springai.codetui.agent.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.PermissionRequest;
import io.github.javaside.springai.codetui.agent.PlanOutcome;
import io.github.javaside.springai.codetui.agent.PlanRequest;
import dev.tamboui.text.CharWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 线程安全共享状态，兼任 {@link AgentListener} 落地端。<b>Claude Code 式行内滚动模型</b>：
 *
 * <ul>
 *   <li><b>pending</b>：已定稿的输出行（带 {@link OutputLine.Kind 类型}，供 UI 分色做层次），
 *       交渲染线程用 {@code InlineTuiRunner.println} 推进终端 scrollback。</li>
 *   <li><b>streaming</b>：在建助手行；凑满整（显示宽度）行即 {@link #takeCompleteStreamingLines}
 *       下沉 scrollback，只把最后残段留在底部 live 区预览。</li>
 * </ul>
 *
 * 并发：写在 Reactor 线程、读/drain 在渲染线程；复合操作 {@code synchronized}、标志 {@code volatile}；
 * 每个带 turnId 的写入先做迟到过滤（{@link #onTurnStarted} 例外——它设定 acceptingTurnId）。
 *
 * <p><b>活性责任（本类是别人的逃生口）</b>：模态请求队列里每一个元素背后都<b>阻塞着一个工具线程</b>，
 * 而那个线程持着回合。故凡是让请求离队的路径都必须应答它一次：入队失败（迟到 / 队满）由入队方法当场应答，
 * 已入队的由 {@link #clearModals()}（{@link #cancelCurrent()} / {@link #resetForNewSession()} 调）唤醒，
 * 正常处理完的由 UI 应答后调 {@link #removeModal}。漏任一条就是工具线程永久 park ——
 * 整个 agent 静默挂死，无报错也无出口（见 {@code ModalRequest#cancel()} 的活性纪律）。
 */
public final class ConversationState implements AgentListener {
    /** 日志只落文件（logback 无 CONSOLE appender）——任何 stdout 输出都会撕裂内联 TUI 画面。 */
    private static final Logger log = LoggerFactory.getLogger(ConversationState.class);

    public enum Status { IDLE, THINKING, RUNNING_TOOL }

    /**
     * 一条定稿输出行 + 其语义类型（UI 据此上色）。
     *
     * @param text     供无差别显示/断言的文本
     * @param kind     语义类型
     * @param toolName 仅 {@code TOOL_START}：工具名（供 UI 判断是否渲染 diff）；其余为 null
     * @param raw      仅 {@code TOOL_START}：工具原始 JSON 入参（供 UI 侧 {@link DiffRenderer} 渲染 diff）；其余为 null
     */
    public record OutputLine(String text, Kind kind, String toolName, String raw) {
        public enum Kind { USER, ASSISTANT, TOOL_START, TOOL_OK, TOOL_FAIL, TODO, ERROR, INFO,
                           SUBAGENT_START, SUBAGENT_TOOL, SUBAGENT_END }

        /** 普通行（无工具元数据）。 */
        public OutputLine(String text, Kind kind) {
            this(text, kind, null, null);
        }
    }

    /** 子任务（一次子 agent 作业）状态——<b>任务面板</b>显示。 */
    public enum SubtaskStatus { RUNNING, DONE, FAILED }

    /** 子任务只读快照（供渲染线程读，与内部可变状态解耦）。
     *
     * @param agentName   subagent_type
     * @param description 委派时给的简述
     * @param status      当前状态
     * @param currentTool 正在跑的工具名；无则为 null
     */
    public record SubtaskView(String agentName, String description, SubtaskStatus status, String currentTool) {}

    /** 内部可变持有者：status/currentTool 就地更新。仅本类访问。 */
    private static final class Subtask {
        final String taskId;
        final String agentName;
        final String description;
        SubtaskStatus status = SubtaskStatus.RUNNING;
        String currentTool = "";
        Subtask(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }

    /**
     * 后台任务状态——⏱ 面板显示。与 {@link SubtaskStatus} 分开：生命周期不同，别复用。
     *
     * <p><b>{@code KILLED} 不是 {@code FAILED} 的同义词</b>：失败是任务自己出的事，终止是用户下的手。
     * 混成一个会让 {@code /tasks} 面板对着用户刚亲手终止的任务写「✗ 失败」，读起来像是"它崩了"。
     */
    public enum BackgroundStatus { RUNNING, DONE, FAILED, KILLED }

    /**
     * 后台任务只读快照（供渲染线程读）。
     *
     * <p>{@code startedAt} / {@code finishedAt} 是<b>本镜像自己</b>记的时刻（epoch 毫秒），面板据此渲染耗时。
     * 刻意不去读注册表里那份时间——UI 只认 {@code ConversationState} 这一个真相源，
     * 两份时间迟早会各说各话（事件迟到、任务被淘汰），而面板上「跑了多久」正是判断任务是否卡死的唯一线索。
     *
     * <p>{@code finishedAt} 为 0 表示仍在跑。<b>结束了就得把耗时钉住</b>：否则已完成任务的耗时会
     * 一直往上涨，读起来像是它还在跑。
     *
     * <p>{@code result} 是子 agent 的<b>完整</b>结果（未结束则为空串），供 {@code /tasks} 面板展开查看。
     * 这是用户能看到全文的<b>唯一</b>途径：后台任务的过程与结果都不进 scrollback，交给模型的那份还会被
     * {@code TaskResultStore} 限幅。多存一份不额外占内存——与注册表里那份是<b>同一个 String 引用</b>。
     *
     * @param taskId      后台任务 id（{@code TaskOutput} 取回时用的那个）
     * @param agentName   subagent_type
     * @param description 委派时给的简述
     * @param status      当前状态
     * @param currentTool 正在跑的工具名；无则为 null
     * @param startedAt   开始时刻（epoch millis）
     * @param finishedAt  结束时刻（epoch millis）；未结束为 0，耗时据此定格
     * @param result      子 agent 的完整结果；未结束为空串
     */
    public record BackgroundView(String taskId, String agentName, String description,
                                 BackgroundStatus status, String currentTool,
                                 long startedAt, long finishedAt, String result) {}

    /** 内部可变持有者。仅本类访问。 */
    private static final class BackgroundEntry {
        final String taskId;
        final String agentName;
        final String description;
        final long startedAt = System.currentTimeMillis();
        long finishedAt;                  // 0 = 仍在跑
        BackgroundStatus status = BackgroundStatus.RUNNING;
        String currentTool = "";
        String result = "";               // 完整结果正文（/tasks 面板展开用）
        BackgroundEntry(String taskId, String agentName, String description) {
            this.taskId = taskId;
            this.agentName = agentName;
            this.description = description;
        }
    }

    private final Deque<OutputLine> pending = new ArrayDeque<>();

    /** 排队的用户消息 + 其挂载技能（可空）。挂载随消息入队，出队时一并带出。
     *
     * @param text  用户输入的原文
     * @param skill {@code /skill} 挂载的技能名；无则为 null
     */
    public record Queued(String text, String skill) {}

    /**
     * 在建残行（未换行段）的<b>字符上限</b>。
     *
     * <p>模型输出可能长时间不换行（长代码/长文本），若残行无限累积（实测会话里出现百万字符单行），
     * 渲染线程每帧都要对整段残行做预览+语法高亮+折行——O(百万) 的每帧字符串操作会把渲染线程拖死，
     * 用户一打字（事件排队）就表现为"卡死"。超过上限把多余部分切行下沉（一次性定稿），
     * 残行预览永远只面对 ≤ 此长度的字符串。
     */
    private static final int MAX_STREAMING_PREVIEW = 200_000;

    private final Deque<Queued> queued = new ArrayDeque<>();       // 忙时排队的用户消息（回合结束后自动出队提交）
    private final StringBuilder streaming = new StringBuilder();
    private final List<String> todo = new ArrayList<>();          // 主 agent（控制器）的 todo/计划（todo 面板，不进 scrollback）
    private final List<Subtask> subtasks = new ArrayList<>();     // 本回合派出的子 agent 状态（任务面板，不进 scrollback）

    /**
     * 后台子 agent（run_in_background）状态——⏱ 面板，<b>不进 scrollback 的中间态</b>。
     *
     * <p><b>与 {@link #subtasks} 分开的理由是生命周期</b>：subtasks 由 {@link #onTurnStarted}
     * 清空（回合级），后台任务跨回合存活。混在一个列表里，清空语义迟早会写错——
     * 而写错的后果是「任务凭空消失」，用户无从判断它是跑完了还是被吃了。
     */
    private final List<BackgroundEntry> backgroundTasks = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private volatile Status status = Status.IDLE;
    private volatile String notice = "";
    private volatile String activeTool = "";
    private volatile String activeToolSummary = "";
    private volatile long acceptingTurnId = -1L;

    // ── 会话压缩瞬态状态（供状态行显示；独立于 Status，自动/手动共用） ──
    private volatile boolean compacting = false;
    private volatile long compactStartNanos = 0L;
    private volatile String compactReason = "";

    // ── 模态请求队列（问询 + 审批共用；渲染线程读、工具线程写；迟到过滤后置入） ──
    // 为何是队列而非单字段：ParallelTasks 下多个子 agent 线程可能同时判出 ASK，
    // 单个 volatile 字段会让后来者覆盖前者、被覆盖的那个工具线程永久 park。
    private final Deque<ModalRequest> modals = new ArrayDeque<>();

    /** 队列上限：防失控回合塞爆队列；超出的请求直接被拒（DENY，回合继续）。 */
    static final int MODAL_QUEUE_CAP = 8;

    // ── BYPASS 留痕（--dangerously-skip-permissions 放行掉的内置底线） ──
    // LinkedHashSet 而不是 List：天然去重（同一个操作在一个回合里可能命中很多次，
    // 汇总里列十遍 rm -rf 只会淹没别的项）且保序（按第一次发生的先后列出）。
    private final Set<String> bypassed = new LinkedHashSet<>();
    // 累积归属的回合。换回合即清空：上一回合的账不能算到这一回合头上，
    // 也保证没能 flush 的回合（被 Esc 取消等）不会把记录永远留在集合里。
    private long bypassTurnId = -1L;

    // ── 输入缓冲 ────────────────────────────────────────────────────────
    public synchronized void typeChar(char c) { notice = ""; input.append(c); }
    public synchronized void typeString(String s) { notice = ""; input.append(s); }
    public synchronized void backspace() { if (input.length() > 0) input.deleteCharAt(input.length() - 1); }
    public synchronized String takeInput() { notice = ""; String s = input.toString(); input.setLength(0); return s; }
    public synchronized String currentInput() { return input.toString(); }

    /** 追加一条信息行（灰色，进 scrollback）。用于「本回合实际使用的模型」等确定性提示。 */
    public synchronized void pushInfo(String text) { pending.add(new OutputLine(text, OutputLine.Kind.INFO)); }

    /**
     * MCP 后台连接全部结束。零工具时<b>一个字都不说</b>——没配 MCP 的用户占多数，
     * 给他们每次启动看一行「已发现 0 个工具」纯属噪声。连接失败的详情在 {@code /mcp} 面板里。
     */
    @Override
    public synchronized void onMcpReady(int serverCount, int toolCount) {
        if (toolCount > 0) {
            pushInfo("（MCP：已发现 " + toolCount + " 个工具。）");
        }
    }

    /**
     * -c 恢复启动：把历史消息回放进 scrollback（仿 Claude Code --continue），直观重现上次对话，
     * 而非只提示「已恢复 N 条」。转换出的定稿行走正常 drain 通道下沉，故排在欢迎横幅之后、首条新输入之前。
     * 空历史则什么都不做。
     */
    public synchronized void replayHistory(List<Message> messages) {
        List<OutputLine> body = HistoryReplay.toReplayLines(messages);
        if (body.isEmpty()) return;
        pending.add(new OutputLine("↺ 已恢复上次会话（" + HistoryReplay.userTurns(messages) + " 轮对话）",
                OutputLine.Kind.INFO));
        pending.addAll(body);
        pending.add(new OutputLine("──── 以上为历史 · 可继续对话，或 /continue 续跑未完成的计划 ────",
                OutputLine.Kind.INFO));
    }

    // ── 消息队列（忙时排队，回合结束自动出队） ───────────────────────────
    public synchronized void enqueue(String msg, String skill) { queued.add(new Queued(msg, skill)); }
    public synchronized Queued pollQueued() { return queued.poll(); }
    public synchronized int queuedCount() { return queued.size(); }
    public synchronized void clearQueued() { queued.clear(); }
    public synchronized List<String> queuedSnapshot() { return queued.stream().map(Queued::text).toList(); }

    /**
     * {@code /clear}：把面板与状态复位到「刚启动」——清 todo 面板、子 agent 任务面板、未定稿输出、排队消息、状态提示。
     * 不动会话事件（那是 {@link io.github.javaside.springai.codetui.agent.CodingAgent#clearContext()} 的职责）。
     */
    public synchronized void resetForNewSession() {
        clearModals();      // 别把待处理模态留给新会话：它们背后各有一个 park 着的工具线程
        todo.clear();
        subtasks.clear();
        backgroundTasks.clear();     // /clear：⏱ 面板一并清空（任务本身的终止由 CodeTuiView 调注册表完成）
        pending.clear();
        queued.clear();
        notice = "";
    }

    // ── 单飞 / 状态 ─────────────────────────────────────────────────────
    public boolean isIdle() { return status == Status.IDLE; }
    public Status status() { return status; }

    /**
     * 一次 Enter 路由所需的原子状态快照。
     *
     * <p>不能在 UI 里先调 {@link #isBusy()}、再调 {@link #isIdle()}：Reactor 线程可能恰好在两次读取之间
     * 结束回合，导致同一次提交先看到「忙」、随后又看到「空闲」，最终把本应插话的消息误塞进普通队列。
     *
     * @param busy       是否忙（含压缩中、有模态、有在飞子 agent）
     * @param activeTurn 是否有活跃回合（决定能否插话）
     */
    public record SubmissionSnapshot(boolean busy, boolean activeTurn) {}

    public synchronized SubmissionSnapshot submissionSnapshot() {
        boolean activeTurn = status != Status.IDLE;
        return new SubmissionSnapshot(activeTurn || compacting || !modals.isEmpty(), activeTurn);
    }
    public void setNotice(String n) { this.notice = n; }
    public String notice() { return notice; }
    public String activeTool() { return activeTool; }
    public String activeToolSummary() { return activeToolSummary; }
    public long acceptingTurnId() { return acceptingTurnId; }

    /** 队首模态请求（无则 null）；渲染线程读，<b>不出队</b>。 */
    public synchronized ModalRequest peekModal() { return modals.peek(); }

    /**
     * 按<b>身份</b>移除一个已处理完的模态请求（答完 / 批完 / 取消后由 UI 调）。
     *
     * <p>不用 {@code Deque.remove(Object)}：它按 {@code equals} 找，而 {@link AskRequest} /
     * {@link PermissionRequest} 都是 record（逐分量 equals），分量恰好相同的两个请求会让它摘错人——
     * 被误摘的那个请求再无人应答，其工具线程永久 park。
     *
     * <p>不负责应答：调用方（UI）已经应答过了。要「移除并唤醒」用 {@link #clearModals()}。
     */
    public synchronized void removeModal(ModalRequest r) {
        modals.removeIf(m -> m == r);
    }

    /** 是否有待处理模态（计入 {@link #isBusy()}）。 */
    public synchronized boolean hasModal() { return !modals.isEmpty(); }

    /**
     * 入队；队列已满返回 false（调用方负责应答，绝不能静默丢弃——丢了就是永久 park）。
     */
    private boolean offerModal(ModalRequest r) {
        if (modals.size() >= MODAL_QUEUE_CAP) return false;
        modals.add(r);
        return true;
    }

    /**
     * 唤醒并清空全部待处理模态（取消回合 / 开新会话）。
     *
     * <p>先清队列再逐个 {@code cancel()}：① 应答口可能回调进本类（{@code synchronized} 可重入），
     * 边遍历边改会 {@code ConcurrentModificationException}；② 万一某个 {@code cancel()} 抛异常，
     * 队列也已经是干净的，不会把剩下的请求留在里面反复重试。
     * 被唤醒的工具线程随后要抢本类的锁写迟到事件——它们会等到本方法所在的 {@code synchronized}
     * 块（{@link #cancelCurrent()} / {@link #resetForNewSession()}）走完，那时 acceptingTurnId 已复位，迟到写入自然被过滤。
     *
     * <p><b>逐个 try/catch 是契约兜底，不是防御性编程</b>：{@code ModalRequest.cancel()} 明文规定
     * 实现不得抛异常。但队列 {@code [A, B]} 里若 A 违约抛了，不兜底就会：① B 的工具线程<b>永不被唤醒</b>
     * ——正是本类要防的那种挂死；② 异常沿 {@code synchronized} 的 {@link #cancelCurrent()} 传到 UI 线程的
     * Esc 处理器。一个坏元素不得殃及它后面的每一个。故记日志后继续排空。
     */
    public synchronized void clearModals() {
        if (modals.isEmpty()) return;
        List<ModalRequest> doomed = new ArrayList<>(modals);
        modals.clear();
        for (ModalRequest r : doomed) {
            try {
                r.cancel();
            } catch (RuntimeException e) {
                // 违约的实现（如 null responder 引发的 NPE）：吞掉并继续，保证后面的元素照样被唤醒。
                log.warn("模态请求 cancel() 抛异常（违反 ModalRequest.cancel() 契约），继续唤醒其余请求", e);
            }
        }
    }

    // ── 压缩状态读取（渲染线程用） ──
    public boolean isCompacting() { return compacting; }
    public String compactReason() { return compactReason; }
    /** 距压缩开始的经过纳秒（用于状态行计时）。 */
    public long compactElapsedNanos() { return compacting ? System.nanoTime() - compactStartNanos : 0L; }

    /** 「忙」= 有活跃回合 / 正在压缩 / 有待处理模态：此时不应发起新回合（排队）、也不应触发手动压缩。 */
    public boolean isBusy() { return !isIdle() || compacting || hasModal(); }

    /** 渲染线程调用：取走并清空「待 println」的定稿行。 */
    public synchronized List<OutputLine> drainPending() {
        if (pending.isEmpty()) return List.of();
        List<OutputLine> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /**
     * 渲染线程调用：取走<b>一条</b>定稿行（队空返回 null）。
     *
     * <p>限速的计量单位必须是「真实写进终端的物理行」，而一条 {@link OutputLine} 经折行 / diff 展开
     * 可以变成几十上百个物理行——按条数取就只能<b>估</b>本帧写了多少。故改为单条取：调用方每打完一条
     * 就核对一次自己的物理行预算，超了立刻收手，剩下的自然留在队列里等下一帧。
     *
     * <p>限速的存在理由见 {@code CodeTuiView.MAX_ROWS_PER_DRAIN}：一帧灌几百 KB 会让渲染线程
     * 长时间占住（按键排队，用户感知「打字卡死」），也会把终端推进它自己的崩溃区。
     */
    public synchronized OutputLine pollPending() {
        return pending.pollFirst();
    }

    /**
     * 渲染线程调用：把在建助手行里<b>已换行（遇到真实 \n）</b>的完整逻辑行取出去下沉 scrollback，
     * 只保留最后一段未换行的残行继续预览。按真实 {@code \n} 切分（不是按显示宽度——终端自己会折长行），
     * 从根上避免多行内容 + 预览叠加造成的重复。锁内完成，避免与 {@link #onAssistantToken} 竞争。
     */
    public synchronized List<String> takeCompleteStreamingLines() {
        return takeCompleteStreamingLines(Integer.MAX_VALUE);
    }

    /**
     * 同上，但本次最多取 {@code maxLines} 个完整逻辑行，其余<b>留在缓冲区</b>等下一帧。
     *
     * <p><b>为什么必须能限量</b>：模型一次吐出一个几千行的代码块（或工具结果回显）时，无参版本会把
     * 全部完整行一次交给渲染线程 println——那是<b>每帧 pty 写入限速唯一漏掉的一条路径</b>，偏偏
     * 「窗口正在输出」走的就是它。一帧灌几千行 = 几百 KB 突发：渲染线程被占住（按键排队，用户感知
     * 「打字卡死」），终端也被推进它自己的崩溃区。
     *
     * <p>留在缓冲区不影响残行预览语义：预览取的是最后一个 {@code \n} 之后的残段，前面留多少完整行都不参与。
     *
     * @param maxLines 本次最多取走的完整逻辑行数；{@code ≤0} 表示一行都不取
     * @return 取走的完整逻辑行（已去掉行尾 {@code \r}），按原顺序
     */
    public synchronized List<String> takeCompleteStreamingLines(int maxLines) {
        if (maxLines <= 0) return List.of();
        int idx = -1;                                       // 第 maxLines 个 \n 的下标（不足则为最后一个）
        int seen = 0;
        for (int i = 0; i < streaming.length() && seen < maxLines; i++) {
            if (streaming.charAt(i) == '\n') { idx = i; seen++; }
        }
        if (idx < 0) return List.of();                      // 还没换行，全留着预览
        String complete = streaming.substring(0, idx);      // 含 idx 之前的若干完整行
        streaming.delete(0, idx + 1);                       // 只切掉已取走的部分，剩余（含残行）原地保留
        List<String> out = new ArrayList<>();
        for (String l : complete.split("\n", -1)) {
            out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
        return out;
    }

    /**
     * 把已取走但本帧<b>没来得及打</b>的完整行放回缓冲区头部（配合物理行预算用尽时收手）。
     *
     * <p>插在头部而非尾部：这些行在时序上早于缓冲区里现存的内容，顺序错了 scrollback 就乱了。
     * 「最后一段是残行」的语义不受影响——插进来的每一行后面都跟着 {@code \n}。
     *
     * @param rows 待放回的完整逻辑行，按原顺序；空列表为 no-op
     */
    public synchronized void unshiftStreamingLines(List<String> rows) {
        if (rows == null || rows.isEmpty()) return;
        StringBuilder head = new StringBuilder();
        for (String r : rows) head.append(r).append('\n');
        streaming.insert(0, head);
    }

    /** live 区显示：在建助手行的当前残行（未换行段）。 */
    public synchronized String streaming() { return streaming.toString(); }

    /**
     * Esc 取消当前回合：唤醒全部待处理模态、定稿在建行、acceptingTurnId=-1、状态回 IDLE。
     *
     * <p>{@link #clearModals()} 放在最前：它是本方法里唯一会调用外部代码的一步，
     * 排在前面保证后续任何一步抛异常都不会把请求连同其阻塞的工具线程留在队列里（漏了就是永久 park）。
     */
    public synchronized void cancelCurrent() {
        clearModals();                 // 必须最先做：漏了这步，阻塞中的工具线程永久 park
        flushStreaming();
        acceptingTurnId = -1L;
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    // ── AgentListener 落地端 ────────────────────────────────────────────
    @Override
    public synchronized void onTurnStarted(long turnId) {
        acceptingTurnId = turnId;
        status = Status.THINKING;
        streaming.setLength(0);
        // 新回合清空上一份计划：面板内容变空（用完即走）。这只是清内容、不改 live 高度，
        // 不触发 InlineDisplay 收缩(deleteLines)的漂移，因此不会复现「面板消失」。
        todo.clear();
        subtasks.clear();                 // 新回合清空任务面板（子 agent 状态），与 todo 同生命周期
    }

    @Override
    public synchronized void onUserMessage(long turnId, String text) {
        if (turnId != acceptingTurnId) return;
        pending.add(new OutputLine("", OutputLine.Kind.ASSISTANT));   // 回合间留白，分隔更清晰
        pending.add(new OutputLine("› " + text, OutputLine.Kind.USER));   // 保留 › 提示符，去掉「你」
    }

    @Override
    public synchronized void onAssistantToken(long turnId, String token) {
        if (turnId != acceptingTurnId) return;
        streaming.append(token);
        if (streaming.length() > MAX_STREAMING_PREVIEW) {
            // 残行超上限：把超出部分切行下沉（一次性定稿），残行预览回到上限内。
            // 只在当前残行<b>包含换行</b>时切：此刻能按 \n 把整段拆干净，不破坏仍在同一逻辑行的内容。
            // 若这一行真的就是超长单行，它最终会随 turn 结束/取消经 flushStreaming 定稿。
            int idx = streaming.lastIndexOf("\n");
            if (idx > 0) {
                String complete = streaming.substring(0, idx);
                String partial = streaming.substring(idx + 1);
                streaming.setLength(0);
                streaming.append(partial);
                for (String l : complete.split("\n", -1)) {
                    pending.add(new OutputLine(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l,
                            OutputLine.Kind.ASSISTANT));
                }
            }
        }
    }

    @Override
    public synchronized void onToolStarted(long turnId, String toolName, String toolInput) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        status = Status.RUNNING_TOOL;
        activeTool = toolName;
        activeToolSummary = summarize(toolInput);
        String line = "⏺ " + toolName + (activeToolSummary.isEmpty() ? "" : "  " + activeToolSummary);
        // 文件写入工具（edit/write）额外携带原始 JSON 入参：渲染线程据此读原文件、生成带真实行号的 diff。
        String raw = DiffRenderer.isFileWrite(toolName) ? toolInput : null;
        pending.add(new OutputLine(line, OutputLine.Kind.TOOL_START, toolName, raw));
    }

    @Override
    public synchronized void onToolFinished(long turnId, String toolName, String output, boolean ok) {
        if (turnId != acceptingTurnId) return;
        status = Status.THINKING;
        activeTool = "";
        activeToolSummary = "";
        pending.add(new OutputLine("  ⎿ " + toolName + (ok ? " ✓" : " ✗"),
                ok ? OutputLine.Kind.TOOL_OK : OutputLine.Kind.TOOL_FAIL));
    }

    @Override
    public synchronized void onSubagentStarted(long turnId, String taskId, String agentName, String description) {
        if (turnId != acceptingTurnId) return;           // 迟到过滤，与其它事件一致
        flushStreaming();                                // 把在建助手残行定稿，子 agent 块另起
        String d = summarize(description);               // 折叠空白/换行，守住「一 OutputLine=一物理行」不变量
        pending.add(new OutputLine("▸ Task(" + agentName + ")" + (d.isEmpty() ? "" : " " + d),
                OutputLine.Kind.SUBAGENT_START));
        subtasks.add(new Subtask(taskId, agentName, d));   // 任务面板追加一条运行中子 agent（d 已 summarize=一物理行）
    }

    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText) {
        onSubagentFinished(turnId, taskId, finalText, true);
    }

    @Override
    public synchronized void onSubagentFinished(long turnId, String taskId, String finalText, boolean ok) {
        if (turnId != acceptingTurnId) return;
        String prefix = ok ? "  ⎿ " : "  ⎿ ✗ ";
        pending.add(new OutputLine(prefix + firstLine(finalText), OutputLine.Kind.SUBAGENT_END));   // 保留 scrollback 结论行
        Subtask st = findSubtask(taskId);
        if (st != null) {
            st.status = ok ? SubtaskStatus.DONE : SubtaskStatus.FAILED;
            st.currentTool = "";
        }
    }

    // ── 后台子 agent（不带 turnId，故<b>不做迟到过滤</b>，也不被 onTurnStarted 清空） ──

    @Override
    public synchronized void onBackgroundTaskStarted(String taskId, String agentName, String description) {
        String d = summarize(description);      // 折叠换行：守住「一 OutputLine = 一物理行」
        backgroundTasks.add(new BackgroundEntry(taskId, agentName, d));
        pending.add(new OutputLine("⏱ 后台任务已启动  " + taskId + " · " + agentName
                + (d.isEmpty() ? "" : " · " + d), OutputLine.Kind.INFO));
    }

    @Override
    public synchronized void onBackgroundTaskFinished(String taskId, String finalText, boolean ok) {
        BackgroundEntry e = findBackground(taskId);
        // 已被用户终止的任务<b>不再接受完成事件</b>：注册表的 kill 只改状态、并不打断那条线程
        // （见 SubagentRunner.runBackgroundBody——它跑完照样发完成事件），不挡住就会把用户亲手终止的
        // 任务翻回「✓ 已完成」，面板对着他撒谎；那份迟到结果也不该再进 scrollback。
        if (e != null && e.status == BackgroundStatus.KILLED) return;
        if (e != null) {
            e.status = ok ? BackgroundStatus.DONE : BackgroundStatus.FAILED;
            e.currentTool = "";
            e.result = finalText == null ? "" : finalText;   // 完整正文留给 /tasks 面板展开
            e.finishedAt = System.currentTimeMillis();   // 钉住耗时，见 BackgroundView
        }
        pending.add(new OutputLine((ok ? "✓ 后台任务完成  " : "✗ 后台任务失败  ") + taskId
                + (e == null ? "" : " · " + e.description)
                + (ok ? "" : " · " + summarize(firstLine(finalText))), OutputLine.Kind.INFO));
    }

    /**
     * 标记某个后台任务已被用户终止（{@code /tasks} 面板的 {@code k}）。返回是否真的改了状态。
     *
     * <p><b>UI 镜像必须自己记这一笔</b>：真正的终止发生在注册表里，而注册表不发事件——不补这一下，
     * 用户按完 k 面板上那条会一直转到天荒地老（那条线程确实还在跑，但结果已经不会被送达了）。
     */
    public synchronized boolean markBackgroundKilled(String taskId) {
        BackgroundEntry e = findBackground(taskId);
        if (e == null || e.status != BackgroundStatus.RUNNING) return false;
        e.status = BackgroundStatus.KILLED;
        e.currentTool = "";
        e.finishedAt = System.currentTimeMillis();
        return true;
    }

    private BackgroundEntry findBackground(String taskId) {
        for (BackgroundEntry e : backgroundTasks) {
            if (e.taskId.equals(taskId)) return e;
        }
        return null;
    }

    /** ⏱ 面板只读快照。 */
    public synchronized List<BackgroundView> backgroundTasks() {
        List<BackgroundView> out = new ArrayList<>(backgroundTasks.size());
        for (BackgroundEntry e : backgroundTasks) {
            out.add(new BackgroundView(e.taskId, e.agentName, e.description, e.status, e.currentTool,
                    e.startedAt, e.finishedAt, e.result));
        }
        return out;
    }

    /** 是否有后台任务在跑（状态栏后缀用）。 */
    public synchronized int backgroundRunningCount() {
        int n = 0;
        for (BackgroundEntry e : backgroundTasks) {
            if (e.status == BackgroundStatus.RUNNING) n++;
        }
        return n;
    }

    /** 子 agent 内部工具（taskId 非空）：缩进一级挂在当前 Task 块下；taskId 为空则走主流工具路径。 */
    @Override
    public synchronized void onToolStarted(long turnId, String taskId, String toolName, String input) {
        if (taskId == null) { onToolStarted(turnId, toolName, input); return; }
        // 后台任务：只更新 ⏱ 面板的「当前工具」，绝不进 scrollback（否则会插进你与主 agent 的对话里），
        // 也绝不做 turnId 迟到过滤（后台任务的 turnId 恒为 -1，过滤会把它全丢掉）。
        BackgroundEntry bg = findBackground(taskId);
        if (bg != null) { bg.currentTool = toolName; return; }
        Subtask st = findSubtask(taskId);
        if (st == null) return;      // 见下：两份镜像都没有 ⇒ 丢弃，绝不退回 turnId 迟到过滤
        if (turnId != acceptingTurnId) return;
        String s = summarize(input);
        pending.add(new OutputLine("    ⎿ " + toolName + (s.isEmpty() ? "" : " " + s),
                OutputLine.Kind.SUBAGENT_TOOL));
        st.currentTool = toolName;   // 任务面板：更新该子 agent 的当前工具
    }

    /**
     * 子 agent 内部工具结束：taskId 非空时不再单独出行（起始行已够，减少噪音）；taskId 为空走主流。
     *
     * <p><b>与 {@link #onToolStarted(long, String, String, String)} 共用同一条纪律：taskId 非空但两份镜像
     * （⏱ 后台 / ⟐ 前台子 agent）里都找不到 ⇒ 一律丢弃，不再退回 turnId 迟到过滤。</b>
     * 因为那道过滤在这里挡不住任何东西：后台任务的 turnId 恒为 -1，而空闲 / 被 Esc 取消后的
     * acceptingTurnId 也恰好是 -1（真实回合 id 从 1 起）。{@code /clear} 之后旧任务的线程还在跑
     * （shutdownNow 只是 interrupt），镜像却已清空 —— 于是它的工具行一条条漏进新会话的对话里。
     * 镜像登记（{@link #onSubagentStarted} / {@link #onBackgroundTaskStarted}）一定早于同一任务的
     * 工具事件，故"找不到"只可能是迟到或已被清空，两种都不该显示。
     */
    @Override
    public synchronized void onToolFinished(long turnId, String taskId, String toolName, String output, boolean ok) {
        if (taskId == null) { onToolFinished(turnId, toolName, output, ok); return; }
        if (findBackground(taskId) != null) return;   // 后台任务的工具结束不出行
        if (findSubtask(taskId) == null) return;      // 来路不明的 taskId：见方法注释
        // 子 agent 内部工具：仅在失败时补一行更深缩进的告警，成功时静默（起始行已展示活动）。
        if (turnId != acceptingTurnId || ok) return;
        pending.add(new OutputLine("      ✗ " + toolName, OutputLine.Kind.SUBAGENT_TOOL));
    }

    @Override
    public synchronized void onTodoUpdated(long turnId, List<String> todoLines) {
        onTodoUpdated(turnId, null, todoLines);   // 无 taskId：视为控制器计划（任务面板）
    }

    /**
     * 分流：taskId==null 是主 agent（控制器）的 todo → todo 面板；
     * taskId!=null 是子 agent 内部 todo → 丢弃（不进任何面板，仅 scrollback 有其工具活动行）。
     */
    @Override
    public synchronized void onTodoUpdated(long turnId, String taskId, List<String> todoLines) {
        if (turnId != acceptingTurnId) return;
        if (taskId != null) return;       // 子 agent 内部 todo 不上面板
        todo.clear();                     // todo 面板原地替换：不进 scrollback
        todo.addAll(todoLines);
    }

    /** todo 面板快照（主 agent 的 todo/计划）。 */
    public synchronized List<String> todoSnapshot() { return List.copyOf(todo); }

    /** 任务面板快照：本回合派出的子 agent 状态。返回不可变值副本，与内部可变状态解耦。 */
    public synchronized List<SubtaskView> subtaskSnapshot() {
        List<SubtaskView> out = new ArrayList<>(subtasks.size());
        for (Subtask s : subtasks) out.add(new SubtaskView(s.agentName, s.description, s.status, s.currentTool));
        return List.copyOf(out);
    }

    /** 按 taskId 定位子任务；找不到（迟到/已清）返回 null，调用方静默忽略。 */
    private Subtask findSubtask(String taskId) {
        for (Subtask s : subtasks) if (s.taskId.equals(taskId)) return s;
        return null;
    }

    /**
     * BYPASS 放行了一个通常需要确认的操作：<b>即时</b>打一行进 scrollback，并按回合累积，
     * 供 {@link #onTurnComplete} 汇总一次。
     *
     * <p><b>刻意不做迟到过滤</b>（别处那句 {@code turnId != acceptingTurnId} 这里没有）：
     * 迟到过滤的用意是「已取消回合的输出不要再显示」，而这件事<b>已经真的发生在磁盘上了</b>——
     * 回合作不作数，与「.git/hooks 被写过」这个事实无关，留痕不该被回合状态吞掉。
     *
     * <p>不阻塞、不弹窗：BYPASS 的定义就是不问，在这里问就是把它要解决的死锁请回来。
     */
    @Override
    public synchronized void onGuardrailBypassed(long turnId, String what) {
        String reason = what == null ? "（未给出理由）" : what;
        if (turnId != bypassTurnId) {
            bypassed.clear();                 // 换回合：上一回合没 flush 掉的残留不带进来
            bypassTurnId = turnId;
        }
        bypassed.add(reason);
        pending.add(new OutputLine("⚠ BYPASS 放行：" + reason + "（通常需要确认）", OutputLine.Kind.INFO));
    }

    /**
     * 回合末汇总本回合被 BYPASS 放行的操作，然后清空。
     *
     * <p><b>为什么要汇总</b>：即时行保证「正在发生时屏幕上有」，但半无人值守场景下人回来时
     * scrollback 已经几百行，汇总是他唯一读得完的那份账。
     *
     * <p><b>为什么排在 {@link #onTurnComplete} 的迟到过滤之前</b>：回合被 Esc 取消后
     * acceptingTurnId 已复位成 -1，跟着一起被过滤的话这批记录既不显示、也不清空，
     * 会一直挂到下一次有放行时才被顶掉——静默丢账，正是本功能要防的。
     */
    private void flushGuardrailBypasses(long turnId) {
        if (turnId != bypassTurnId || bypassed.isEmpty()) return;
        pending.add(new OutputLine("⚠ 本回合 BYPASS 放行了 " + bypassed.size() + " 个通常需要确认的操作：",
                OutputLine.Kind.INFO));
        for (String w : bypassed) {
            // ⚠ 一个 OutputLine = 一个物理行：多行分多次 push，绝不在一个字符串里塞 \n
            // （scrollback 用 println 下沉，\n 会被塌成一行并截断，人看到的是半句话）
            pending.add(new OutputLine("   · " + w, OutputLine.Kind.INFO));
        }
        bypassed.clear();
        bypassTurnId = -1L;
    }

    @Override
    public synchronized void onTurnComplete(long turnId) {
        flushGuardrailBypasses(turnId);        // 必须在迟到过滤之前，见方法注释
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    @Override
    public synchronized void onError(long turnId, Throwable error) {
        if (turnId != acceptingTurnId) return;
        flushStreaming();
        pending.add(new OutputLine("⚠ 出错：" + formatError(error), OutputLine.Kind.ERROR));
        activeTool = "";
        activeToolSummary = "";
        status = Status.IDLE;
    }

    @Override
    public synchronized void onQuestionAsked(long turnId, AskRequest request) {
        if (turnId != acceptingTurnId || !offerModal(request)) {
            // 迟到（回合已取消/切换）或队列已满：桥侧的 take() 靠取消路径唤醒，这里直接取消不弹面板。
            request.cancel();
        }
    }

    /**
     * 审批请求入队。<b>迟到与队满一律 DENY 而非 CANCEL</b>：
     * 拒绝让模型自寻替代、回合继续；CANCEL 会中断整个回合，语义过重。
     * 两条路径都<b>必须</b>应答——静默丢弃就是工具线程永久 park。
     *
     * <p><b>不阻塞</b>（契约要求）：本方法与 {@link #drainPending()} / {@link #cancelCurrent()}
     * 共用同一把监视器锁，这里一旦阻塞冻住的是<b>整个 TUI</b>而不只是一个工具线程。
     * 实现只做 O(1) 的入队判断；应答走的是消费方一次性、非阻塞的 handoff，故必然立即返回。
     *
     * <p><b>队满溢出必须留下用户看得见的一行</b>：线程一定会醒（活性没问题），但「有个工具被悄悄拒了」
     * 只在日志里就等于没发生——用户会以为模型自己改了主意。迟到请求<b>不</b>提示：那个回合已经取消/切换，
     * 再打一行只是噪音。
     */
    @Override
    public synchronized void onPermissionRequested(long turnId, PermissionRequest request) {
        if (turnId != acceptingTurnId) {
            request.responder().respond(PermissionOutcome.DENY);
            return;
        }
        if (!offerModal(request)) {
            pending.add(new OutputLine("⚠ 待审批请求过多（已达上限 " + MODAL_QUEUE_CAP + "），已自动拒绝 "
                    + request.toolName() + "：请先处理面板里的请求", OutputLine.Kind.ERROR));
            request.responder().respond(PermissionOutcome.DENY);
        }
    }

    /**
     * 计划审批请求入队。
     *
     * <p><b>迟到 → CANCEL，队满 → KEEP_PLANNING</b>，两条路径都<b>必须</b>应答——静默丢弃
     * 就是工具线程永久 park。二者刻意不同：迟到的那个回合已经取消/切换，让它抛异常随流丢弃即可；
     * 而队满只是面板挤，不该因此杀掉整个回合，故给一个能让模型继续下去的答复 + 一行用户看得见的提示
     * （迟到<b>不</b>提示：那个回合已经没了，再打一行只是噪音）。
     *
     * <p><b>不阻塞</b>（契约要求）：本方法与 {@link #drainPending()} / {@link #cancelCurrent()}
     * 共用同一把监视器锁，这里一旦阻塞冻住的是<b>整个 TUI</b>而不只是一个工具线程。
     */
    @Override
    public synchronized void onPlanSubmitted(long turnId, PlanRequest request) {
        if (turnId != acceptingTurnId) {
            request.responder().respond(PlanOutcome.CANCEL, "");
            return;
        }
        if (!offerModal(request)) {
            pending.add(new OutputLine("⚠ 待处理的模态请求过多（已达上限 " + MODAL_QUEUE_CAP
                    + "），本次计划未能展示：请先处理面板里的请求", OutputLine.Kind.ERROR));
            request.responder().respond(PlanOutcome.KEEP_PLANNING,
                    "（界面繁忙，未能展示计划，请稍后重新提交）");
        }
    }

    /**
     * 规则记录结果（工具线程写完后回报，见 {@link AgentListener#onRuleRecorded}）。
     *
     * <p>失败走 {@code ERROR} 行而不是普通信息行：写盘失败与「被 deny 遮蔽」都意味着
     * <b>用户以为记下了、其实没有</b>，那必须显眼。
     */
    @Override
    public synchronized void onRuleRecorded(long turnId, boolean ok, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        pending.add(new OutputLine(message, ok ? OutputLine.Kind.INFO : OutputLine.Kind.ERROR));
    }

    @Override
    public synchronized void onCompactionStarted(String reason) {
        compactStartNanos = System.nanoTime();
        compactReason = reason == null ? "" : reason;
        compacting = true;   // 最后写：作为安全发布标志，读者见到 true 即保证看到上面两个字段的新值
    }

    @Override
    public synchronized void onCompactionFinished(int eventsRemoved, int tokensSaved) {
        compacting = false;
        compactReason = "";
        if (eventsRemoved <= 0) {
            pending.add(new OutputLine("• 无可压缩内容（历史尚短）", OutputLine.Kind.INFO));
        } else {
            pending.add(new OutputLine(
                    "✓ 已压缩会话：移除 " + eventsRemoved + " 个事件，约省 " + tokensSaved + " tokens",
                    OutputLine.Kind.INFO));
        }
    }

    @Override
    public synchronized void onCompactionFailed(String message) {
        compacting = false;
        compactReason = "";
        pending.add(new OutputLine("✗ 压缩失败：" + (message == null ? "unknown" : message),
                OutputLine.Kind.ERROR));
    }

    // ── 内部 ────────────────────────────────────────────────────────────
    private void flushStreaming() {
        if (streaming.length() == 0) return;
        for (String l : streaming.toString().split("\n", -1)) {   // 残段也按真实换行拆，避免嵌入 \n 的整块
            pending.add(new OutputLine(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l,
                    OutputLine.Kind.ASSISTANT));
        }
        streaming.setLength(0);
    }

    private static String summarize(String toolInput) {
        if (toolInput == null) return "";
        String oneLine = toolInput.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) oneLine = oneLine.substring(0, 200);
        if (CharWidth.of(oneLine) <= 80) return oneLine;
        return CharWidth.substringByWidth(oneLine, 79) + "…";
    }

    /** 取首行 + 超长按显示宽度截断（子 agent 结论行用）。 */
    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "";
        String one = s.lines().findFirst().orElse("").strip();
        if (CharWidth.of(one) <= 80) return one;
        return CharWidth.substringByWidth(one, 79) + "…";
    }

    /**
     * 从异常链中提取可读错误描述，优先用 {@code getMessage()}，为空时沿 cause 链向下找，
     * 最终回退到类名（避免界面只显示"null"或一串内部类路径）。
     */
    static String formatError(Throwable error) {
        if (error == null) return "unknown";
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) return msg;
        }
        return error.getClass().getSimpleName();
    }
}
