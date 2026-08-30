package io.github.javaside.springai.codetui.agent.background;

import io.github.javaside.springai.codetui.ui.update.UiChangeListener;
import io.github.javaside.springai.codetui.ui.update.UiChangeSource;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 后台任务的进程内注册表——<b>唯一的并发真相源</b>。
 *
 * <p><b>刻意不认识 UI、不认识 Spring AI、不认识回合</b>：它只是一张带状态机的表。
 * "什么时候该自动起回合"那个最易错的判断在 BackgroundNotifier（纯函数），执行在 UI 层。
 * 三者分开才能各自单测。
 *
 * <p><b>不落盘</b>：后台任务的价值在于"这一次会话里派出去的活"。跨进程恢复一个
 * 已经没有线程在跑的任务只会制造"它还在跑吗"的误解。
 *
 * <p>并发：改表方法走 {@code synchronized(this)} 块（持锁时间 O(n) 的 map 遍历，n ≤ capacity 默认 64，
 * 不会有长持锁——注册表里绝不做 IO 或调用外部代码）；UI 变化通知一律在<b>锁外</b> publish
 * （见下方「变化通知纪律」）。只读快照方法仍为整体 {@code synchronized} 方法。
 *
 * <p><b>变化通知纪律（事件驱动 UI，Task 4）</b>：本类同时是 {@link UiChangeSource}——
 * <ul>
 *   <li>锁内只改表、递增 {@link #uiVersion}；<b>锁外</b>才调 listener（UI 醒来要回读
 *       {@code all()}/{@code completedUnconsumed()} 这些 synchronized 快照）；</li>
 *   <li>register → VIEW；容量淘汰已完成任务 → VIEW；</li>
 *   <li>complete 真实 RUNNING→DONE/FAILED → <b>VIEW|CONTROL</b>——删 drain 轮询后，
 *       后台结果的<b>自动送达全靠这一声唤醒</b>（CONTROL 让 UI 重估「要不要起新回合送结果」）；</li>
 *   <li>kill / killAll / markConsumed 真实状态变化 → VIEW|CONTROL；</li>
 *   <li>unknown id、重复 complete/kill/consume、无可淘汰者 → 不通知、不推进版本；</li>
 *   <li>一次复合 mutation（register 内含淘汰、killAll 含多条）把子变化<b>合并成一次</b>通知；</li>
 *   <li>listener 抛出的 {@link RuntimeException} 被隔离成日志，不得打断注册表自身的写入。</li>
 * </ul>
 */
public final class BackgroundTaskRegistry implements UiChangeSource {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRegistry.class);

    /** 用 LinkedHashMap 而非 HashMap：淘汰要按"最早登记"顺序找，随机迭代序会淘汰错人。 */
    private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();
    private final int capacity;
    private final Supplier<String> idSupplier;

    public BackgroundTaskRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.idSupplier = () -> "task_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── 变化通知（事件驱动 UI；见类注释「变化通知纪律」） ──────────────────
    private volatile UiChangeListener uiChangeListener = UiChangeListener.noop();

    /** 单调递增的状态版本。仅诊断用，不参与任何跨 source 比较。 */
    private long uiVersion;

    /** 记录一次有效变化并推进版本。<b>必须在持有本类监视器时调用</b>（版本与表同序）。 */
    private long changed() {
        return ++uiVersion;
    }

    /** 锁外发布：0 直接丢弃（no-op 路径根本不记账）；listener 异常只记日志。 */
    private void publish(int bits, long version) {
        if (version <= 0 || bits == UiDirty.NONE) return;
        try {
            uiChangeListener.onUiChanged(bits);
        } catch (RuntimeException e) {
            log.warn("UI change listener failed at background-registry version {}", version, e);
        }
    }

    @Override
    public void setUiChangeListener(UiChangeListener listener) {
        uiChangeListener = listener == null ? UiChangeListener.noop() : listener;
    }

    @Override
    public long uiVersion() {
        synchronized (this) {
            return uiVersion;
        }
    }

    /** 登记一个新任务（RUNNING），返回 taskId。超容量时先淘汰最旧的已完成任务。
     *
     * <p>淘汰与登记合并成<b>一次</b> VIEW 通知（都是「面板列表形状变了」这一类事）。
     *
     * @param agentName   subagent_type
     * @param description 委派时给的简述
     * @return 新任务的 taskId
     */
    public String register(String agentName, String description) {
        String id;
        long version;
        synchronized (this) {
            evictIfNeeded();
            id = idSupplier.get();
            tasks.put(id, new BackgroundTask(id, agentName, description));
            version = changed();
        }
        publish(UiDirty.VIEW, version);
        return id;
    }

    /** 标记完成。ok=true → DONE，false → FAILED。未知 id 或已结束的任务静默忽略。
     *
     * <p>真实迁移发布 <b>VIEW|CONTROL</b>——这是删 drain 轮询后后台结果自动送达的唯一唤醒点。
     *
     * @param taskId 任务 id
     * @param result 结果正文；失败时是摊平后的原因
     * @param ok     true → DONE，false → FAILED
     */
    public void complete(String taskId, String result, boolean ok) {
        long version = 0L;
        synchronized (this) {
            BackgroundTask t = tasks.get(taskId);
            if (t == null || t.finished()) return;   // 未知 id / 已结束：no-op
            t.finish(ok ? BackgroundTask.Status.DONE : BackgroundTask.Status.FAILED, result);
            version = changed();
        }
        publish(UiDirty.VIEW | UiDirty.CONTROL, version);
    }

    /** 终止一个运行中的任务（标记 KILLED）。
     *
     * @param taskId 任务 id
     * @return 是否真的改变了状态；已结束或未知 id 返回 false
     */
    public boolean kill(String taskId) {
        long version = 0L;
        synchronized (this) {
            BackgroundTask t = tasks.get(taskId);
            if (t == null || t.finished()) return false;   // no-op：不通知
            t.finish(BackgroundTask.Status.KILLED, "已被用户终止");
            version = changed();
        }
        publish(UiDirty.VIEW | UiDirty.CONTROL, version);
        return true;
    }

    /** 终止全部运行中的任务（/clear、/exit 用）。全部子变化合并成一次 VIEW|CONTROL 通知。 */
    public void killAll() {
        long version = 0L;
        synchronized (this) {
            boolean any = false;
            for (BackgroundTask t : tasks.values()) {
                if (!t.finished()) {
                    t.finish(BackgroundTask.Status.KILLED, "已被用户终止");
                    any = true;
                }
            }
            if (!any) return;                          // 无一在跑：no-op
            version = changed();
        }
        publish(UiDirty.VIEW | UiDirty.CONTROL, version);
    }

    /**
     * 标记结果已被消费（已交给模型）。返回是否<b>本次</b>真的把它从未消费变成已消费。
     *
     * <p>返回值是两条回收路径的<b>互斥闸</b>：TaskOutput 与自动送达都调它，
     * 谁先拿到 true 谁负责送，另一条拿到 false 就跳过。没有这个返回值，同一个结果会被送两遍。
     *
     * @param taskId 任务 id
     * @return 是否<b>本次</b>真的把它从未消费变成已消费
     */
    public boolean markConsumed(String taskId) {
        long version = 0L;
        synchronized (this) {
            BackgroundTask t = tasks.get(taskId);
            if (t == null || !t.deliverable() || t.consumed()) return false;   // no-op：不通知
            t.setConsumed();
            version = changed();
        }
        publish(UiDirty.VIEW | UiDirty.CONTROL, version);
        return true;
    }

    /** 已结束、可送达、且尚未消费的任务（按登记顺序）。KILLED 不在其中。 */
    public synchronized List<BackgroundTask> completedUnconsumed() {
        List<BackgroundTask> out = new ArrayList<>();
        for (BackgroundTask t : tasks.values()) {
            if (t.deliverable() && !t.consumed()) out.add(t);
        }
        return out;
    }

    /** 全部任务快照（按登记顺序），供 ⏱ 面板与 /tasks 面板显示。 */
    public synchronized List<BackgroundTask> all() {
        return new ArrayList<>(tasks.values());
    }

    public synchronized BackgroundTask find(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized int runningCount() {
        int n = 0;
        for (BackgroundTask t : tasks.values()) {
            if (!t.finished()) n++;
        }
        return n;
    }

    /**
     * 容量淘汰：按登记顺序找第一个<b>已结束</b>的任务移除。
     *
     * <p><b>运行中的永不淘汰</b>——淘汰掉一个还在跑的任务，等它跑完时 {@code complete()}
     * 找不到 id 会静默丢结果，用户看到的是"任务凭空消失"。宁可短暂超容量。
     */
    private void evictIfNeeded() {
        while (tasks.size() >= capacity) {
            String victim = null;
            for (BackgroundTask t : tasks.values()) {
                if (t.finished()) { victim = t.taskId(); break; }
            }
            if (victim == null) return;   // 全在跑：不淘汰，允许超容量
            tasks.remove(victim);
        }
    }
}
