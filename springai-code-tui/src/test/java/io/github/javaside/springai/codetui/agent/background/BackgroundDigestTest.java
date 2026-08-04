package io.github.javaside.springai.codetui.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BackgroundDigest}：给模型看的后台任务摘要。
 *
 * <p><b>本类钉住的核心语义是「哪些状态需要特殊处理」</b>：只有 RUNNING 与 DONE-未消费
 * 会进 {@code forContinue}——因为只有它们会导致<b>重复劳动</b>。FAILED / KILLED 是普通的
 * 「没做完」，模型看 todo 自然会重派，而重派正是 /continue 的定义。
 */
class BackgroundDigestTest {

    /** 造一个处于指定状态的注册表。容量给足，避免淘汰干扰断言。 */
    private static BackgroundTaskRegistry registry() {
        return new BackgroundTaskRegistry(64);
    }

    @Test
    @DisplayName("RUNNING：点名 id 并告诉模型不要重复委派")
    void runningTellsModelNotToRedispatch() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("explore", "扫描鉴权相关代码");

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "必须点名 taskId，否则模型无从对应:\n" + s);
        assertTrue(s.contains("explore"), "应带上 agent 名:\n" + s);
        assertTrue(s.contains("扫描鉴权相关代码"), "应带上描述:\n" + s);
        assertTrue(s.contains("不要重复委派"), "RUNNING 的行动指引是「别重派」:\n" + s);
    }

    @Test
    @DisplayName("DONE 未消费：让模型先取回结果，别重做")
    void doneUnconsumedTellsModelToFetch() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "写完了 5 个用例", true);

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "必须点名 taskId:\n" + s);
        assertTrue(s.contains("TaskOutput"), "应指出用哪个工具取回:\n" + s);
        assertTrue(s.contains("可能已经做完"), "要点破「这个 todo 也许已经完成了」:\n" + s);
    }

    @Test
    @DisplayName("DONE 但已消费：不再出现——结果早送出去了，再提一遍是噪声")
    void doneConsumedIsOmitted() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "写完了", true);
        r.markConsumed(id);

        String s = BackgroundDigest.forContinue(r.all());

        assertFalse(s.contains(id), "已消费的结果不该再提，实际:\n" + s);
    }

    /**
     * <b>本类最要紧的一条</b>。/continue 的本意是「把没做完的接着做完」，
     * FAILED / KILLED 就是普通的「没做完」——模型看 todo 自然会重派，而重派正是要的结果。
     * 一旦有人在这里给它们加了「先看看失败原因再决定」之类的指引，就是替用户做了他已经做过的决定。
     */
    @Test
    @DisplayName("只有 FAILED / KILLED：返回空串，一个字都不提")
    void failedAndKilledProduceNothing() {
        BackgroundTaskRegistry r = registry();
        String failed = r.register("bash", "跑全量回归");
        r.complete(failed, "额度不足（402）", false);
        String killed = r.register("explore", "扫目录");
        r.kill(killed);

        String s = BackgroundDigest.forContinue(r.all());

        assertEquals("", s, "FAILED/KILLED 不需要任何特殊处理，实际:\n" + s);
    }

    @Test
    @DisplayName("空注册表：给一句自条件提醒，覆盖 -c 恢复会话的场景")
    void emptyRegistryWarnsAboutCrossProcess() {
        String s = BackgroundDigest.forContinue(List.of());

        assertTrue(s.contains("不跨进程保存"), "-c 恢复后模型可能干等已死的旧任务:\n" + s);
        assertTrue(s.contains("重新派发"), "要给出正确的下一步:\n" + s);
    }

    @Test
    @DisplayName("forContinue 不含结果正文——塞进去会撑爆提示词，且绕过 markConsumed 互斥")
    void forContinueOmitsResultBody() {
        BackgroundTaskRegistry r = registry();
        String id = r.register("general-purpose", "写单测");
        r.complete(id, "这是一段绝不该出现在提示词里的结果正文", true);

        String s = BackgroundDigest.forContinue(r.all());

        assertTrue(s.contains(id), "前提：这条确实进了清单:\n" + s);
        assertFalse(s.contains("绝不该出现在提示词里"), "结果正文必须由 TaskOutput 取，不能塞进提示词:\n" + s);
    }

    @Test
    @DisplayName("full：四种状态一个不漏")
    void fullListsEveryStatus() {
        BackgroundTaskRegistry r = registry();
        String running = r.register("explore", "还在跑");
        String done = r.register("general-purpose", "完成了");
        r.complete(done, "ok", true);
        String failed = r.register("bash", "失败了");
        r.complete(failed, "boom", false);
        String killed = r.register("plan", "被终止");
        r.kill(killed);

        String s = BackgroundDigest.full(r.all());

        for (String id : List.of(running, done, failed, killed)) {
            assertTrue(s.contains(id), "full 漏了 " + id + "，实际:\n" + s);
        }
    }

    @Test
    @DisplayName("full 空注册表：返回一句话而不是空串——工具返回空串会被当成调用失败")
    void fullOnEmptyRegistryIsNotBlank() {
        String s = BackgroundDigest.full(List.of());

        assertFalse(s.isBlank(), "空串会让模型以为 ListTasks 调用失败");
        assertTrue(s.contains("没有后台任务"), "实际:\n" + s);
    }

    @Test
    @DisplayName("入参为 null 不抛——它跑在提示词构造路径上，抛异常会崩掉整个 TUI")
    void nullSnapshotIsSafe() {
        assertFalse(BackgroundDigest.forContinue(null).isEmpty(),
                "null 按空注册表处理，走跨进程提醒那一支");
        assertFalse(BackgroundDigest.full(null).isBlank());
    }
}
