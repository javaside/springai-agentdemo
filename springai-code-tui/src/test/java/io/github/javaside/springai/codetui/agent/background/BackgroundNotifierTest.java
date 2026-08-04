package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundNotifierTest {

    private static BackgroundTask done(String id, String agent, String desc, String result) {
        BackgroundTask t = new BackgroundTask(id, agent, desc);
        t.finish(BackgroundTask.Status.DONE, result);
        return t;
    }

    private static BackgroundTask failed(String id, String agent, String desc, String why) {
        BackgroundTask t = new BackgroundTask(id, agent, desc);
        t.finish(BackgroundTask.Status.FAILED, why);
        return t;
    }

    // ── 三条件真值表 ──

    @Test
    void notifiesWhenIdleAndInputEmptyAndHasUnconsumed() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), true, true).isPresent());
    }

    @Test
    void silentWhenBusy() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), false, true).isEmpty());
    }

    @Test
    void silentWhenInputBoxHasText() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(done("ab12", "explore", "调查", "结论")), true, false).isEmpty(),
                "用户正在敲字时不抢跑");
    }

    @Test
    void silentWhenNothingCompleted() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        assertTrue(n.shouldNotify(List.of(), true, true).isEmpty());
    }

    // ── 通知文本 ──

    @Test
    void notificationContainsIdAgentDescriptionAndResult() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(List.of(done("ab12", "explore", "调查登录失败", "是 token 过期")), true, true).get();
        assertTrue(text.contains("ab12"));
        assertTrue(text.contains("explore"));
        assertTrue(text.contains("调查登录失败"));
        assertTrue(text.contains("是 token 过期"));
        assertTrue(text.contains("✓"));
    }

    @Test
    void failedTaskIsMarkedWithCrossNotCheck() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(List.of(failed("cd34", "bash", "跑测试", "连接被重置")), true, true).get();
        assertTrue(text.contains("✗"));
        assertFalse(text.contains("✓"));
        assertTrue(text.contains("连接被重置"));
    }

    @Test
    void multipleTasksAreMergedIntoOneNotification() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        String text = n.shouldNotify(
                List.of(done("ab12", "explore", "调查", "结论A"), done("cd34", "plan", "设计", "结论B")),
                true, true).get();
        assertTrue(text.contains("ab12"));
        assertTrue(text.contains("cd34"));
        assertTrue(text.contains("结论A"));
        assertTrue(text.contains("结论B"));
        // 合并成一条，不是两条：整段只应出现一次抬头
        assertEquals(1, text.split("\\[后台任务完成]", -1).length - 1);
    }

    // ── 失控刹车 ──

    @Test
    void stopsAutoTurnsAfterConsecutiveLimit() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 1
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 2
        assertTrue(n.shouldNotify(one, true, true).isPresent());   // 3
        assertTrue(n.shouldNotify(one, true, true).isEmpty(),      // 4 → 刹车
                "连续 3 次自动回合后必须停止，否则无人值守时会无限烧 token");
    }

    @Test
    void userInputResetsTheBrake() {
        BackgroundNotifier n = new BackgroundNotifier(3);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        n.shouldNotify(one, true, true);
        n.shouldNotify(one, true, true);
        n.shouldNotify(one, true, true);
        assertTrue(n.shouldNotify(one, true, true).isEmpty());

        n.onUserInput();

        assertTrue(n.shouldNotify(one, true, true).isPresent(), "用户一有真实输入就该重新允许自动送达");
    }

    @Test
    void brakeEngagedIsObservableForStatusBar() {
        BackgroundNotifier n = new BackgroundNotifier(1);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        assertFalse(n.brakeEngaged());
        n.shouldNotify(one, true, true);
        assertTrue(n.shouldNotify(one, true, true).isEmpty());
        assertTrue(n.brakeEngaged(), "刹车状态要能被状态栏读到，否则用户不知道为什么没动静");
    }

    @Test
    void brakeCountIsNotAdvancedBySilentCalls() {
        BackgroundNotifier n = new BackgroundNotifier(2);
        List<BackgroundTask> one = List.of(done("ab12", "explore", "d", "r"));
        for (int i = 0; i < 50; i++) {
            n.shouldNotify(List.of(), true, true);        // 无任务：不该消耗额度
            n.shouldNotify(one, false, true);             // 忙：不该消耗额度
        }
        assertTrue(n.shouldNotify(one, true, true).isPresent());
        assertTrue(n.shouldNotify(one, true, true).isPresent());
        assertTrue(n.shouldNotify(one, true, true).isEmpty());
    }
}
