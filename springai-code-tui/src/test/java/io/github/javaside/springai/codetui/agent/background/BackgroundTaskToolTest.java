package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskToolTest {

    private static BackgroundTaskTool tool(BackgroundTaskRegistry reg, Path root) {
        return new BackgroundTaskTool(reg, new TaskResultStore(root), 1, () -> false);   // 阻塞上限 1s，测试跑得快
    }

    @Test
    void toolIsNamedTaskOutputAndSchemaHasTaskIdAndBlock(@TempDir Path root) {
        ToolCallback tc = BackgroundTaskTool.create(
                new BackgroundTaskRegistry(64), new TaskResultStore(root), 300, () -> false);
        assertEquals("TaskOutput", tc.getToolDefinition().name());
        String schema = tc.getToolDefinition().inputSchema();
        assertTrue(schema.contains("task_id"));
        assertTrue(schema.contains("block"));
    }

    @Test
    void unknownTaskIdSaysSoInsteadOfHangingOrThrowing(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query("task_nope", false));
        assertTrue(out.contains("未知任务"), out);
        assertTrue(out.contains("已结束的进程"), "要提示可能是 -c 恢复后的陈旧 id");
    }

    /**
     * ★ 被<b>淘汰</b>的任务也走「未知任务」这一支，而它是<b>本进程丢的</b>——
     * {@link BackgroundTaskRegistry#evictIfNeeded} 超容量时会移除最旧的已结束任务，
     * 其中完全可能含已完成但还没送达的那条。
     *
     * <p>只说「可能来自已结束的进程」是在给模型一个<b>错误的因果解释</b>：它会以为这是上个进程的陈旧 id
     * 而放弃，实际上那是它自己这轮派出去、跑完了、被挤掉的结果。文案须同时涵盖两种可能。
     */
    @Test
    void evictedTaskExplainsItMayHaveBeenEvictedInThisProcess(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(2);   // 容量 2，第 3 次登记必触发淘汰
        String oldest = reg.register("explore", "最早那条");
        reg.complete(oldest, "结论", true);                            // 已完成但从未被消费
        reg.register("explore", "第二条");
        reg.register("explore", "第三条");
        assertNull(reg.find(oldest), "前置：最旧的已结束任务确实被淘汰了，否则本用例是空转的");

        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(oldest, false));

        assertTrue(out.contains("未知任务"), out);
        assertTrue(out.contains("淘汰"),
                "被本进程淘汰是另一种可能，不能只说「来自已结束的进程」。实际=" + out);
    }

    @Test
    void runningTaskNonBlockingReturnsStillRunning(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("仍在运行"), out);
    }

    @Test
    void finishedTaskReturnsResultAndMarksConsumed(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "调查结论", true);

        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));

        assertTrue(out.contains("调查结论"));
        assertTrue(reg.find(id).consumed(), "取过就要标记已消费");
        assertTrue(reg.completedUnconsumed().isEmpty(),
                "已被模型取走的结果不得再被自动送一遍——这是两条回收路径的互斥闸");
    }

    @Test
    void failedTaskIsReportedAsFailure(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("bash", "跑测试");
        reg.complete(id, "连接被重置", false);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("失败"));
        assertTrue(out.contains("连接被重置"));
    }

    @Test
    void fetchingTwiceStillReturnsResultButOnlyFirstMarksConsumed(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "结论", true);
        BackgroundTaskTool t = tool(reg, root);

        assertTrue(t.fetch(new BackgroundTaskTool.Query(id, false)).contains("结论"));
        // 第二次仍要给结果（模型可能忘了），但不该报错
        assertTrue(t.fetch(new BackgroundTaskTool.Query(id, false)).contains("结论"));
    }

    @Test
    void blockingTimesOutIntoStillRunningNotAnError(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        long t0 = System.nanoTime();
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, true));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(out.contains("仍在运行"), "超时不等于任务死了，不能报失败：" + out);
        assertTrue(elapsedMs >= 900, "应当真的等满了超时窗口，实测 " + elapsedMs + "ms");
    }

    @Test
    void blockingReturnsAsSoonAsTaskCompletes(@TempDir Path root) throws Exception {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        Thread completer = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            reg.complete(id, "晚到的结论", true);
        });
        completer.start();

        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, true));

        completer.join();
        assertTrue(out.contains("晚到的结论"), out);
    }

    @Test
    void longResultIsTruncatedThroughResultStore(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.complete(id, "z".repeat(20_000), true);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("结果已截断"), "长结果必须走限幅，否则直接把上下文打满");
        assertFalse(out.length() > 8000, "限幅后不该还这么长：" + out.length());
    }

    @Test
    void killedTaskSaysKilledNotSilentEmpty(@TempDir Path root) {
        BackgroundTaskRegistry reg = new BackgroundTaskRegistry(64);
        String id = reg.register("explore", "调查");
        reg.kill(id);
        String out = tool(reg, root).fetch(new BackgroundTaskTool.Query(id, false));
        assertTrue(out.contains("终止"), out);
    }
}
