package io.github.javaside.springai.codetui.ui.output;

import dev.tamboui.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * 严格分批的<b>物理行输出队列</b>（设计 §9.1/§9.2）：把逻辑输出（{@code OutputLine} / 流式完整行）
 * 变成 {@link OutputCursor} 消费流，{@link #drain(int, long)} 在两个预算——物理行数与
 * UI 线程执行时间——任一耗尽时<b>立即</b>返回事件循环，尚未提交的物理行留在游标里，
 * 顺序不变、内容不丢。
 *
 * <p><b>与旧 {@code rowsThisFrame} 软上限的本质区别</b>：旧实现按「条」取，单条 OutputLine
 * 原子展开后一帧可写几百上千行（SLACK=200 的由来）；本队列逐行预算——drain 在取第
 * {@code maxPhysicalRows + 1} 行<b>之前</b>停下，任何一条逻辑输出（超大正文 / 大 diff /
 * 无换行超长行）都不能再把单批撑破上限。
 *
 * <p><b>活跃游标在队头</b>：{@link #drain} 只消费队头游标，耗尽才移除、才创建下一个
 * （创建 = 调 {@code cursorFactory}，对 diff 是读文件 + LCS，<b>只在轮到它时做一次</b>，
 * 不在入队时做）。因此任一时刻 staging 的物化内容 ≤ 一个逻辑项的当前逻辑行展开，
 * 单个超大项的内存/CPU 占用被限制在「一条逻辑行 + O(1) 渲染器状态」。
 *
 * <p><b>流式行不物化</b>：{@link #enqueueStreamingLines} 只记录逻辑行引用列表（这些
 * {@code String} 本就完整存在于 {@code ConversationState.streaming}，取出动作由调用方限量），
 * 渲染在 drain 时逐行进行——一批 300 行只渲染 300 行。
 *
 * <p><b>时间预算</b>：行间检查 {@code System.nanoTime()} 耗尽即停（{@code timeBudgetExhausted}）。
 * 一行渲染是一段有界工作（一条逻辑行 + O(1) 状态推进），预算粒度即足够细。
 *
 * <p><b>线程</b>：只应在 UI（渲染）线程使用，与既有 drain 纪律一致；不做内部同步。
 */
public final class PhysicalOutputQueue {

    /**
     * 一条物理行：要么带样式（markdown/diff/用户块），要么纯文本（默认路径 {@code println(String)}）。
     * 两者对应 {@code ScrollbackPrinter.Sink} 的两个重载，保真不丢样式。
     */
    public record PhysicalLine(String plain, Text styled) {
        public static PhysicalLine plain(String value) { return new PhysicalLine(value, null); }
        public static PhysicalLine styled(Text value) { return new PhysicalLine(null, value); }
    }

    /**
     * 一次 drain 批次的结果。
     *
     * @param rowsWritten         本批实际写出的物理行数（恒 ≤ maxPhysicalRows）
     * @param remaining           队列是否还有未提交内容（游标未耗尽或后面还有项 / 流式行未完）
     * @param timeBudgetExhausted 本批因时间预算（而非行数预算）停止
     */
    public record BatchResult(int rowsWritten, boolean remaining, boolean timeBudgetExhausted) { }

    /** 一个待消费项：cursor 工厂 + 可选的预拆逻辑行（流式行）。 */
    private record Entry(Function<Void, OutputCursor> factory, List<String> lines, int lineAt) {
        static Entry of(Function<Void, OutputCursor> f) { return new Entry(f, null, 0); }
        static Entry streaming(List<String> ls) { return new Entry(null, ls, 0); }
    }

    private final Function<List<String>, OutputCursor> streamingCursorFactory;
    private final Deque<Entry> entries = new ArrayDeque<>();
    /** 活跃游标（队头项的展开）。耗尽即置 null 并移除队头项——见类注释「活跃游标在队头」。 */
    private OutputCursor active;

    /**
     * @param streamingCursorFactory 把一批流式完整逻辑行包装成游标的工厂（由 printer 提供，
     *                               保持其 markdown 围栏/高亮状态）
     */
    public PhysicalOutputQueue(Function<List<String>, OutputCursor> streamingCursorFactory) {
        this.streamingCursorFactory = streamingCursorFactory;
    }

    /** 入队一条定稿逻辑输出。cursor 工厂惰性调用（drain 轮到它时才展开，见类注释）。 */
    public void enqueue(Function<Void, OutputCursor> cursorFactory) {
        entries.addLast(Entry.of(cursorFactory));
    }

    /** 入队一批流式完整逻辑行（只存引用，渲染留给 drain，见类注释「流式行不物化」）。 */
    public void enqueueStreamingLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        entries.addLast(Entry.streaming(lines));
    }

    /**
     * 消费一批：从队头游标（或下一个未展开项）逐行写出，行数达 {@code maxPhysicalRows} 或
     * 时间预算耗尽即停。在取第 {@code maxPhysicalRows + 1} 行之前停下（永不超发一行）。
     *
     * @param maxPhysicalRows 本批物理行硬上限（&gt;0）
     * @param maxNanos        本批时间预算（纳秒；&le;0 视为不限时）
     * @param sink            物理行出口（两条分支对应 {@link PhysicalLine} 的两种形态）
     * @return 批次结果
     */
    public BatchResult drain(int maxPhysicalRows, long maxNanos, PhysicalSink sink) {
        long deadline = maxNanos > 0 ? System.nanoTime() + maxNanos : Long.MAX_VALUE;
        int written = 0;
        boolean timeExhausted = false;
        while (written < maxPhysicalRows) {
            OutputCursor cursor = ensureActive();
            if (cursor == null) break;                       // 队列空：自然收尾
            PhysicalLine line = cursor.next();               // 只物化一行（摊还 O(当前逻辑行)）
            if (line == null) {                              // 游标耗尽（或 hasNext 假阳性）：移除、换下一个
                dropActive();
                continue;
            }
            if (line.styled() != null) sink.printlnStyled(line.styled());
            else sink.printlnPlain(line.plain() == null ? "" : line.plain());
            written++;
            // 时间检查从第二行起：首行前的工厂成本（如 diff 的读文件+LCS，O(一个工具入参) 的一次性
            // 工作）不挤占行吞吐预算——否则慢机器上首行后立即停，一批只出 1 行。
            if (written >= 2 && System.nanoTime() >= deadline) {   // 行间时间检查（设计 §9.1）
                timeExhausted = true;
                break;
            }
        }
        return new BatchResult(written, !isEmpty(), timeExhausted);
    }

    /** 队列是否还有未提交内容（活跃游标未耗尽或还有待展开项）。 */
    public boolean isEmpty() {
        return active == null && entries.isEmpty();
    }

    /** 丢弃全部未提交内容（/clear、重置等语义性丢弃，由调用方决定）。 */
    public void clear() {
        entries.clear();
        active = null;
    }

    /** 队头项未展开则展开；队列空返回 null。 */
    private OutputCursor ensureActive() {
        if (active != null) return active;
        Entry head = entries.peekFirst();
        if (head == null) return null;
        if (head.lines() != null) {
            active = streamingCursorFactory.apply(head.lines());
        } else {
            active = head.factory().apply(null);
        }
        return active;
    }

    private void dropActive() {
        active = null;
        entries.pollFirst();
    }

    /** 物理行出口（由消费方桥接到真实 sink，包一层留底/计数）。 */
    public interface PhysicalSink {
        void printlnPlain(String line);
        void printlnStyled(Text line);
    }
}
