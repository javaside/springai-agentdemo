package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code TaskOutput(block=true)} 的阻塞等待必须给插话让路。
 *
 * <p><b>不让路会怎样</b>：后台派发本身是非阻塞的（{@code runInBackground} 立刻返回任务 id），
 * 但模型拿到 id 后通常紧接着调 {@code TaskOutput(block=true)} 想「就在这个回合里拿到结果」。
 * 那一等最长 300 秒，而它是<b>主 agent 工具线程上的一次普通工具调用</b>——这期间：
 * <ul>
 *   <li>主回合没结束，用户打字走的是插话分支（输入框上方的 ⤷ 面板）；</li>
 *   <li>主 agent 卡在工具里不发模型调用，而<b>插话的唯一送达点就是模型调用</b>。</li>
 * </ul>
 * 净效果是「后台」这两个字被 {@code block=true} 抵消掉，用户要等的时长和前台 Task 一模一样。
 *
 * <p>轮询本来就是 200ms 一轮，顺路问一句「有没有人插话」就能把这段等待从最长 300 秒压到 200 毫秒。
 *
 * <p><b>控制组在 {@code BackgroundTaskToolTest.blockingTimesOutIntoStillRunningNotAnError}</b>：
 * 没有插话时必须<b>真的等满</b>超时窗口。少了那一条，本文件的「提前返回」可能只是因为别的原因根本没等。
 */
class BackgroundTaskToolInterjectionTest {

    /** 超时上限取 5s：够长，提前返回与等满两种结果不会被测量噪声混淆。 */
    private static final int TIMEOUT_SECONDS = 5;

    private static BackgroundTaskTool tool(BackgroundTaskRegistry reg, Path root, AtomicBoolean pending) {
        return new BackgroundTaskTool(reg, new TaskResultStore(root), TIMEOUT_SECONDS, pending::get);
    }

    /**
     * 已经有未送达插话时，压根不该开始等——用户那句话已经在队列里躺着了。
     */
    @Test
    @DisplayName("已有未送达插话时，阻塞等待立刻让位")
    void alreadyPendingInterjectionSkipsTheWaitEntirely(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");        // 永不完成
        AtomicBoolean pending = new AtomicBoolean(true);

        long t0 = System.nanoTime();
        String out = tool(reg, root, pending).fetch(new BackgroundTaskTool.Query(id, true));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 1_000,
                "等满了 " + ms + "ms——插话在队列里躺着，这段时间它一句话也送不出去");
        assertTrue(out.contains("仍在运行"),
                "让位不等于任务出事了，措辞必须还是「仍在运行」：" + out);
    }

    /**
     * 更贴近真实的那一半：用户是在<b>等待过程中</b>才开口的。
     *
     * <p>下界断言（{@code ms >= 200}）不是凑数——没有它，「因为别的原因根本没等」也会让上界通过，
     * 这条用例就变成了恒真。
     */
    @Test
    @DisplayName("等待途中出现插话，当轮轮询就收工")
    void interjectionArrivingMidWaitCutsTheBlockShort(@TempDir Path root) throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");        // 永不完成
        AtomicBoolean pending = new AtomicBoolean(false);

        Thread user = new Thread(() -> {                     // 模拟用户敲下回车的那一刻
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pending.set(true);
        });
        user.start();

        long t0 = System.nanoTime();
        String out = tool(reg, root, pending).fetch(new BackgroundTaskTool.Query(id, true));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        user.join();

        assertTrue(ms >= 200,
                "只用了 " + ms + "ms 就回来了——插话那会儿还没发生，说明提前返回另有原因，"
                        + "本用例并没有测到它该测的东西");
        assertTrue(ms < 2_000,
                "等满了 " + ms + "ms——用户中途插的那句话得等这段阻塞跑完才送得出去");
        assertTrue(out.contains("仍在运行"), out);
    }
}
