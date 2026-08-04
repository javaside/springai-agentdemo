package io.github.javaside.springai.codetui.agent.background;

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
 * <p>并发：全部方法 {@code synchronized}。持锁时间都是 O(n) 的 map 遍历，n ≤ capacity（默认 64），
 * 不会有长持锁——注册表里绝不做 IO 或调用外部代码。
 */
public final class BackgroundTaskRegistry {

    /** 用 LinkedHashMap 而非 HashMap：淘汰要按"最早登记"顺序找，随机迭代序会淘汰错人。 */
    private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();
    private final int capacity;
    private final Supplier<String> idSupplier;
    private final Supplier<Long> clock;

    public BackgroundTaskRegistry(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.idSupplier = () -> "task_" + UUID.randomUUID().toString().substring(0, 8);
        this.clock = System::currentTimeMillis;
    }

    /** 登记一个新任务（RUNNING），返回 taskId。超容量时先淘汰最旧的已完成任务。 */
    public synchronized String register(String agentName, String description) {
        evictIfNeeded();
        String id = idSupplier.get();
        tasks.put(id, new BackgroundTask(id, agentName, description, clock.get()));
        return id;
    }

    /** 标记完成。ok=true → DONE，false → FAILED。未知 id 或已结束的任务静默忽略。 */
    public synchronized void complete(String taskId, String result, boolean ok) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || t.finished()) return;
        t.finish(ok ? BackgroundTask.Status.DONE : BackgroundTask.Status.FAILED, result, clock.get());
    }

    /** 终止一个运行中的任务（标记 KILLED）。返回是否真的改变了状态——已结束的返回 false。 */
    public synchronized boolean kill(String taskId) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || t.finished()) return false;
        t.finish(BackgroundTask.Status.KILLED, "已被用户终止", clock.get());
        return true;
    }

    /** 终止全部运行中的任务（/clear、/exit 用）。 */
    public synchronized void killAll() {
        for (BackgroundTask t : tasks.values()) {
            if (!t.finished()) {
                t.finish(BackgroundTask.Status.KILLED, "已被用户终止", clock.get());
            }
        }
    }

    /**
     * 标记结果已被消费（已交给模型）。返回是否<b>本次</b>真的把它从未消费变成已消费。
     *
     * <p>返回值是两条回收路径的<b>互斥闸</b>：TaskOutput 与自动送达都调它，
     * 谁先拿到 true 谁负责送，另一条拿到 false 就跳过。没有这个返回值，同一个结果会被送两遍。
     */
    public synchronized boolean markConsumed(String taskId) {
        BackgroundTask t = tasks.get(taskId);
        if (t == null || !t.deliverable() || t.consumed()) return false;
        t.setConsumed();
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
