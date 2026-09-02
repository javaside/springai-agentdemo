/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.inline;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.tamboui.terminal.Backend;

/**
 * pty 异步写线程：把「终端字节写出」从渲染线程剥离，根治「输出时打字卡死」。
 *
 * <p><b>根因（本类存在的全部理由）</b>：macOS pty 的内核写缓冲仅 ~1-2 KiB（实测），
 * 终端读端停摆时（中文 IME 合成期主线程忙、终端渲染积压、Terminal.app 自身卡顿），
 * 阻塞式 {@code write(2)} 会挂起数秒到无限期（pty 实验：读端不消费，写端 120s 不返回；
 * 读端 50ms 消费一次，有效吞吐仅 ~20 KiB/s）。旧路径里这笔写发生在渲染线程
 * （{@code InlineTuiRunner.processUiWake → InlineDisplay.submit →
 * backend.writeRaw + flush}）——事件驱动重构约束了<b>每批生成多少字节</b>（12ms / 300 行），
 * 但最后一跳的 pty 写本身是无界同步阻塞：渲染线程睡在 write 里，{@code eventQueue}
 * 中的按键全部排队，用户感知就是「模型输出时打字卡死」。Claude Code 不卡是因为
 * Node 的 stdout 写是异步的（libuv 用户态缓冲），事件循环永不被 write 阻塞。
 *
 * <h2>两级预算与丢帧自愈</h2>
 * 字节在 <b>writeRaw 返回之后</b>才释放预算（在 dequeue 时释放等于没限：写线程卡在
 * write 上时预算已还、数据没走，背压形同虚设）。两级：
 * <ul>
 *   <li><b>软预算 {@code byteBudget}</b>：大批量输出（scrollback 批，单块可达 ~150KB）
 *       超过即拒——调用方（渲染线程）据此降级：延迟 continuation，绝不等待；</li>
 *   <li><b>硬预算 {@code 2×byteBudget}</b>：小帧（live 区差分，几 KB）豁免软预算、
 *       只受硬预算约束——打字回显不能因为「正在输出大块内容」被拒。硬预算也满时
 *       小帧被拒，调用方把差分基线作废（{@code previousFrameValid=false}），恢复后
 *       下一帧全量重画自愈——<b>不阻塞、不花屏、不丢协议流顺序</b>。</li>
 * </ul>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>顺序</b>：写出顺序严格等于提交顺序（pty 是协议字节流，乱序即花屏）；</li>
 *   <li><b>提交永不因慢设备而阻塞</b>：{@link #submit} 饱和立即返回 false，
 *       调用方（display/View）据此延迟重投或暂停产出——绝不等待；</li>
 *   <li><b>flush 是队列项</b>：与数据同序消费；{@link #awaitFlushed} 提供
 *       「此前提交已全部落盘」的确定语义（清屏后重放等关键时序用）；</li>
 *   <li><b>背压可观测</b>：{@link #isSaturated()} 供渲染线程在 UI 层降级；</li>
 *   <li><b>生命周期</b>：{@link #close(long, TimeUnit)} 有界排空后关线程；关闭后提交 no-op。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <pre>
 *   渲染线程                     pty-writer（本类，单守护线程）
 *   ────────                     ────────────────────────────
 *   trySubmit(bytes) ──enqueue─▶ [有界队列，字节记账] ──dequeue──▶ backend.writeRaw
 *   flush()          ──enqueue─▶ (FLUSH 标记项)      ──dequeue──▶ backend.flush
 *                                字节在 write 返回后释放
 * </pre>
 *
 * <p><b>故障契约</b>：写线程遇到 {@link IOException}（pty 关闭、终端消失）按
 * 「设备已死」处理——记录一次、清空队列、此后一切提交为 no-op。终端没了之后
 * 没有任何渲染值得继续；静默降级比把 IOException 甩回渲染线程更符合实际。
 */
public final class AsyncPtyWriter implements AutoCloseable {

    private static final Logger log = Logger.getLogger(AsyncPtyWriter.class.getName());

    /**
     * 队列项：要么是待写字节，要么是 FLUSH 标记（与数据同序消费）。
     * FLUSH 标记<b>携带自己的 latch</b>（审核 m-2）：写线程消费到它时倒数它自带的
     * latch——并发 flush 时各自等到各自的那一次，不会出现「先到的标记倒数掉后到者
     * 的 latch」的错配。
     */
    private static final class Chunk {
        static Chunk flush(CountDownLatch latch) { return new Chunk(null, latch); }
        final String payload;
        /** 非 null = FLUSH 标记：消费时 backend.flush() 后 countdown。 */
        final CountDownLatch flushLatch;

        Chunk(String payload) {
            this(payload, null);
        }

        private Chunk(String payload, CountDownLatch flushLatch) {
            this.payload = payload;
            this.flushLatch = flushLatch;
        }
    }

    private final Backend backend;
    private final BlockingQueue<Chunk> queue;
    private final int byteBudget;
    /**
     * 设备错误探针（可 null）：JLine 后端的 {@code writeRaw} 走 PrintWriter——
     * <b>吞掉 IOException 只置 checkError 标志</b>，写线程永远等不到异常
     * （审核 M-2：markDead 不可达、死终端静默流失内容）。每次 drain 后调用本探针，
     * 返回 true 即按设备死亡处理。生产由 runner 反射注入
     * {@code () -> jlinePrintWriter.checkError()}（项目已有 ScreenCleaner 等反射先例）。
     */
    private final java.util.function.BooleanSupplier errorProbe;
    private final AtomicLong bytesQueued = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean dead = new AtomicBoolean();
    private final Thread worker;
    /** 已投递 flush 的代际 latch：每次 flush() 生成新代，awaitFlushed 等当前代。 */
    private volatile CountDownLatch flushGeneration = new CountDownLatch(0);
    /**
     * 排空监听（{@link InlineDisplay} 挂接）：<b>武装后单发</b>——只有调用方因
     * 「有被拒批等待重投」武装过唤醒（{@link #armWakeup()}），写线程把队列消化到空
     * 时才回调一次并解除武装。静止界面零唤醒（写线程的空转 poll 静默超时），
     * 事件驱动重构删除的常驻 tick 不会以任何形式回来。
     * 回调在<b>写线程</b>上执行——只允许做唤醒类动作（如 requestRender 入队），
     * 不得触碰 display/View 状态。
     */
    private volatile Runnable drainListener;
    /** 唤醒武装（单发）：true = 有被拒批在等「队列排空」事件。 */
    private final AtomicBoolean wakeArmed = new AtomicBoolean();

    /** 无探针构造（测试/非 JLine 后端）。 */
    public AsyncPtyWriter(Backend backend, int byteBudget) {
        this(backend, byteBudget, null);
    }

    /**
     * @param backend    底层 pty backend（只被写线程触碰）
     * @param byteBudget 软预算（UTF-16 char 计，&gt;0；pty 实写 UTF-8 字节——CJK 每
     *                    char 3 字节，预算比记账多 ~3 倍，即真实占用上界 ≈ 3×预算）
     * @param errorProbe 设备错误探针（可 null，见字段注释；每次 drain 后调用）
     */
    public AsyncPtyWriter(Backend backend, int byteBudget,
                          java.util.function.BooleanSupplier errorProbe) {
        this.backend = backend;
        this.byteBudget = Math.max(1, byteBudget);
        this.errorProbe = errorProbe;
        this.queue = new ArrayBlockingQueue<>(4096);
        this.worker = new Thread(this::drainLoop, "pty-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 提交一批待写字节；饱和时<b>立即返回 false，绝不在调用线程上等</b>。
     *
     * <p>两级判定：大批（&gt; 软预算/16，scrollback 批粒度）撞软预算即拒；
     * 小帧（live 差分粒度）豁免软预算、只撞硬预算（2×软预算）才拒。
     * 调用方被拒时的正确动作：作废自己的增量基线（如
     * {@code InlineDisplay.previousFrameValid = false}），恢复后全量重画自愈。
     *
     * <p><b>队空豁免（前进性保证）</b>：队列已排空（{@code current == 0}）时任何
     * 单批都接受——否则「大于软预算的批」（如延迟批合并产物、超大单批）会
     * 0 + cost &gt; budget 恒真、<b>永远无法入队</b>，恢复后死锁。豁免的有界性：
     * 队空才放行 ⇒ 任一时刻至多一个超批在飞，总占用 ≤ 硬预算 + 单批上限。
     */
    public boolean submit(String payload) {
        if (payload == null || payload.isEmpty() || closed.get() || dead.get()) {
            return true;   // 空/关闭态视为已接受（no-op 语义）
        }
        int cost = payload.length();
        int smallFrameThreshold = Math.max(1, byteBudget / 16);
        boolean small = cost <= smallFrameThreshold;
        long limit = small ? byteBudget * 2L : byteBudget;
        while (true) {
            long current = bytesQueued.get();
            if (current != 0 && current + cost > limit) {
                return false;   // 饱和即拒（isSaturated 由 bytesQueued 直读，无需重复置位）
            }
            if (bytesQueued.compareAndSet(current, current + cost)) {
                break;
            }
        }
        if (!queue.offer(new Chunk(payload))) {
            bytesQueued.addAndGet(-cost);   // 条目数满：回滚字节预算
            return false;
        }
        return true;
    }

    /**
     * 请求一次 flush（与已提交数据同序）并返回<b>本次</b>的代际 latch：
     * 写线程消费到这个 FLUSH 标记时 countdown。调用方 {@code await} 自己拿到的
     * latch——并发 flush 互不干扰（审核 m1：共享单个 {@code flushGeneration}
     * 会让先到的调用方等到后到者的语义、或空等到超时）。
     * 设备死/已关返回已开的 latch（立即通过）。
     */
    public CountDownLatch flush() {
        CountDownLatch generation = new CountDownLatch(1);
        if (closed.get() || dead.get()) {
            generation.countDown();
            return generation;
        }
        flushGeneration = generation;   // 兼容旧 awaitFlushed() 语义（等最近一次 flush）
        if (!queue.offer(Chunk.flush(generation))) {
            generation.countDown();   // 条目满：放弃标记（后续数据的 flush 会补上）
        }
        return generation;
    }

    /**
     * 等 {@code timeout} 内「最近一次 {@link #flush()}」写到底层。
     * 超时返回 false（调用方决定是否在乎）。新代码建议直接 {@code flush().await(...)}。
     */
    public boolean awaitFlushed(long timeout, TimeUnit unit) {
        CountDownLatch generation = flushGeneration;
        if (generation == null) {
            return true;
        }
        try {
            return generation.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 软预算是否已耗尽（渲染线程降级判据：延迟 continuation / 跳过预览重画）。
     * 设备死亡后恒 false：队列已清、写已 no-op，无背压可言——继续报饱和会让
     * 上层门控永久关死（freeze-forever，审核 M1）。
     */
    public boolean isSaturated() {
        return !dead.get() && bytesQueued.get() >= byteBudget;
    }

    /** 设备是否已死（写失败后一切提交为 no-op）。 */
    public boolean isDead() {
        return dead.get();
    }

    // ── 写线程 ─────────────────────────────────────────────────────────

    private void drainLoop() {
        try {
            while (!closed.get() || !queue.isEmpty()) {
                Chunk chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    notifyDrainedIfArmed();   // 空转到超时也意味着已排空（单发，静止无回调）
                    continue;
                }
                if (chunk.flushLatch != null) {
                    try {
                        backend.flush();
                    } catch (IOException e) {
                        markDead(e);
                        return;
                    }
                    chunk.flushLatch.countDown();   // 标记自带 latch（审核 m-2）：等自己的那次
                } else {
                    try {
                        backend.writeRaw(chunk.payload);
                    } catch (IOException e) {
                        markDead(e);
                        return;
                    } finally {
                        // 字节在 write 返回（或失败）后释放：写线程卡在 write 上时
                        // 预算保持占用——背压对「慢设备」真实生效。
                        bytesQueued.addAndGet(-chunk.payload.length());
                    }
                    // 错误探针（审核 M-2）：PrintWriter 家族吞 IOException 只置标志，
                    // 每次 drain 后主动探测，true 即设备死亡。
                    if (errorProbe != null && errorProbe.getAsBoolean()) {
                        markDead(new IOException("pty error probe reported failure (checkError)"));
                        return;
                    }
                }
                if (queue.isEmpty()) {
                    notifyDrainedIfArmed();   // 非空到空的边沿（单发）
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // close 的中断：走收尾
        }
    }

    /**
     * 武装一次「队列排空」唤醒（渲染线程在批被拒时调用）。
     * 幂等：已武装时再调无副作用（同一次饱和只等一个事件）。
     */
    public void armWakeup() {
        wakeArmed.set(true);
    }

    /** 排空事件：已武装（CAS 单发）时触发监听并解除武装；静止界面（未武装）零回调。 */
    private void notifyDrainedIfArmed() {
        if (!wakeArmed.compareAndSet(true, false)) {
            return;
        }
        Runnable listener = drainListener;
        if (listener != null && !closed.get() && !dead.get()) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "drain listener 抛异常（忽略，监听器只应做唤醒）", e);
            }
        }
    }

    /** 注入排空监听（见字段注释：回调在写线程执行，只做唤醒）。 */
    public void setDrainListener(Runnable listener) {
        this.drainListener = listener;
    }

    private void markDead(IOException cause) {
        dead.set(true);
        queue.clear();
        bytesQueued.set(0);   // 已清队列：在飞字节记账一并归零（否则 isSaturated 永真，审核 M1）
        CountDownLatch gen = flushGeneration;
        if (gen != null) {
            gen.countDown();
        }
        log.log(Level.WARNING, "pty 写失败，异步写线程转入设备死亡态（后续提交 no-op）", cause);
    }

    // ── 生命周期 ───────────────────────────────────────────────────────

    /**
     * 有界排空后关闭：先置 closed（拒绝新提交），等写线程消费完剩余队列
     * （最多 {@code timeout}），再中断收尾。
     */
    public void close(long timeout, TimeUnit unit) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
    }

    @Override
    public void close() {
        close(2, TimeUnit.SECONDS);
    }
}
