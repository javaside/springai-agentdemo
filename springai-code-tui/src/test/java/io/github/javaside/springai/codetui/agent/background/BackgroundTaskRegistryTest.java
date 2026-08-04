package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskRegistryTest {

    private BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(64);
    }

    @Test
    void registerCreatesRunningTask() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "调查登录失败");
        BackgroundTask t = r.find(id);
        assertEquals(BackgroundTask.Status.RUNNING, t.status());
        assertEquals("explore", t.agentName());
        assertEquals("调查登录失败", t.description());
        assertFalse(t.consumed());
    }

    @Test
    void completeMarksDoneAndStoresResult() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "调查结论", true);
        BackgroundTask t = r.find(id);
        assertEquals(BackgroundTask.Status.DONE, t.status());
        assertEquals("调查结论", t.result());
    }

    @Test
    void completeWithFailureMarksFailed() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("bash", "d");
        r.complete(id, "连接被重置", false);
        assertEquals(BackgroundTask.Status.FAILED, r.find(id).status());
    }

    @Test
    void unfinishedTaskIsNotReportedAsCompleted() {
        BackgroundTaskRegistry r = registry();
        r.register("explore", "d");
        assertTrue(r.completedUnconsumed().isEmpty());
    }

    @Test
    void completedUnconsumedListsFinishedButNotYetConsumed() {
        BackgroundTaskRegistry r = registry();
        String a = r.register("explore", "a");
        String b = r.register("plan", "b");
        r.complete(a, "ra", true);
        List<BackgroundTask> got = r.completedUnconsumed();
        assertEquals(1, got.size());
        assertEquals(a, got.get(0).taskId());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(b).status());
    }

    @Test
    void markConsumedIsIdempotentAndRemovesFromUnconsumed() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        r.complete(id, "res", true);
        assertTrue(r.markConsumed(id));
        assertFalse(r.markConsumed(id));          // 第二次返回 false：已消费
        assertTrue(r.completedUnconsumed().isEmpty());
    }

    @Test
    void markConsumedOnRunningTaskReturnsFalse() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "d");
        assertFalse(r.markConsumed(id));          // 没跑完就不该被"消费"
    }

    @Test
    void killMarksKilledOnlyWhenRunning() {
        BackgroundTaskRegistry r = registry();
        String running = r.register("bash", "a");
        String done = r.register("plan", "b");
        r.complete(done, "res", true);
        assertTrue(r.kill(running));
        assertEquals(BackgroundTask.Status.KILLED, r.find(running).status());
        assertFalse(r.kill(done));                 // 已完成的杀不动
        assertEquals(BackgroundTask.Status.DONE, r.find(done).status());
    }

    @Test
    void findUnknownIdReturnsNull() {
        assertNull(registry().find("task_nope"));
    }

    @Test
    void evictionDropsOldestFinishedButNeverRunning() {
        BackgroundTaskRegistry r = new BackgroundTaskRegistry(3);
        String finished1 = r.register("a", "1");
        String running = r.register("b", "2");
        String finished2 = r.register("c", "3");
        r.complete(finished1, "r1", true);
        r.complete(finished2, "r2", true);

        r.register("d", "4");                      // 触发淘汰：容量 3，现在要放第 4 个

        assertNull(r.find(finished1), "最旧的已完成任务应被淘汰");
        assertEquals(BackgroundTask.Status.RUNNING, r.find(running).status(), "运行中的永不淘汰");
        assertEquals(BackgroundTask.Status.DONE, r.find(finished2).status());
    }

    @Test
    void evictionKeepsAllWhenEverythingIsRunning() {
        BackgroundTaskRegistry r = new BackgroundTaskRegistry(2);
        String a = r.register("a", "1");
        String b = r.register("b", "2");
        String c = r.register("c", "3");           // 超容量但无可淘汰者
        assertEquals(BackgroundTask.Status.RUNNING, r.find(a).status());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(b).status());
        assertEquals(BackgroundTask.Status.RUNNING, r.find(c).status());
    }

    @Test
    void runningCountAndKillAllWork() {
        BackgroundTaskRegistry r = registry();
        String a = r.register("a", "1");
        r.register("b", "2");
        r.complete(a, "res", true);
        assertEquals(1, r.runningCount());
        r.killAll();
        assertEquals(0, r.runningCount());
    }

    @Test
    void taskIdsAreUnique() {
        BackgroundTaskRegistry r = registry();
        assertFalse(r.register("a", "1").equals(r.register("a", "1")));
    }
}
