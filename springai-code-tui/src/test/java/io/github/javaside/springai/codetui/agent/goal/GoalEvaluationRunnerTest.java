package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 评估调度的四条契约（spec §6.3 调度面）：回调恰好一次、超时归入调用失败、异常原样、线程面
 * （daemon + 命名 + 单线程）。
 *
 * <p><b>为什么这些断言值得写死</b>：评估是阻塞的模型调用，调度面错一点就静默劣化——超时没挂上，
 * 评估挂到生产上限才动；异常被包一层，调用处的 instanceof 分流失效（协议熔断永不触发）；线程不是
 * daemon，退出时滞留线程拖住 JVM；池不是单线程，评估并发打模型、熔断计数错乱。
 *
 * <p>四支用例全部有界等待（latch + 2s 上限），失败消息写清「等不到=哪根线断了」，绝不无限等。
 */
class GoalEvaluationRunnerTest {

    /**
     * 最小可用配置：{@code evalTimeoutSeconds} 取钳制下限 5——{@link GoalConfig} 的构造器守卫拒绝更小
     * 的值，故本类的超时用例走包私有第二构造直通毫秒，不走这里。
     */
    private static GoalConfig testCfg() {
        return new GoalConfig(25, 3, 5_000_000L, 3, 2, 2, 3, 5, "");
    }

    private static EvaluationInput sampleInput() {
        return new EvaluationInput("迁移 AuthService，mvn -pl server test 退出码 0", 2, 25, 1,
                "✓UserClient 接口替换 ✗TokenRefresh 调用点",
                List.of(new GoalTurnRecord(1, "还有 4 个调用点未迁移", "已替换 2 处调用", 3, null)));
    }

    private static GoalVerdict satisfied() {
        return new GoalVerdict(GoalVerdict.Outcome.SATISFIED, "r", false, null, "raw");
    }

    /** ★ 成功路径：verdict 恰经 onSuccess 送达（回调在 executor 线程触发，不阻塞调用者）。 */
    @Test
    void successDeliversVerdict() throws Exception {
        try (GoalEvaluationRunner r = new GoalEvaluationRunner(testCfg())) {
            CountDownLatch ok = new CountDownLatch(1);
            AtomicReference<GoalVerdict> got = new AtomicReference<>();

            r.submit(1, in -> satisfied(), sampleInput(),
                    v -> {
                        got.set(v);
                        ok.countDown();
                    },
                    x -> {
                    });

            assertTrue(ok.await(2, TimeUnit.SECONDS),
                    "2s 内没等到 onSuccess——评估没被交出去（线程没起来）或回错口子（打到了 onFailure），"
                            + "不是机器慢");
            assertEquals(GoalVerdict.Outcome.SATISFIED, got.get().outcome(), "送达的应是评估器原样结论");
        }
    }

    /**
     * ★ 超时路径：评估跑过 timeoutMs → onFailure 收到 {@link TimeoutException}（归入<b>调用失败</b>熔断，
     * 不是协议失败），且迟到的返回值<b>绝不</b>补一次 onSuccess。
     *
     * <p>50ms 走包私有第二构造：生产构造从 config 换算，而 config 把秒钳在 [5,300]——照 5s 等，这条
     * 「快照」用例会变成 5s 级慢测。
     */
    @Test
    void timeoutCountsAsCallFailure() throws Exception {
        try (GoalEvaluationRunner r = new GoalEvaluationRunner(testCfg(), 50)) {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> got = new AtomicReference<>();
            AtomicReference<GoalVerdict> leaked = new AtomicReference<>();

            // 慢评估：300ms 远超 50ms 超时；超时判定后它照跑完（orTimeout 不中断），结果必须被丢弃
            r.submit(1, in -> {
                        TimeUnit.MILLISECONDS.sleep(300);
                        return satisfied();
                    }, sampleInput(),
                    leaked::set,
                    x -> {
                        got.set(x);
                        failed.countDown();
                    });

            assertTrue(failed.await(2, TimeUnit.SECONDS),
                    "2s 内没等到 onFailure——orTimeout 没挂上（评估会静默挂到生产 60s 上限）"
                            + "或超时被当成协议失败");
            assertInstanceOf(TimeoutException.class, got.get(),
                    "超时必须原样以 TimeoutException 送达 onFailure，实收: " + got.get());
            TimeUnit.MILLISECONDS.sleep(500);   // 慢评估（300ms）于此之前已结束
            assertNull(leaked.get(),
                    "超时判定之后慢评估的迟到结果又触发了一次 onSuccess——双回调会让调用处"
                            + "「回调即终点」的清标志与熔断计数错乱");
        }
    }

    /**
     * ★ 异常路径：评估器抛出的异常<b>原样</b>（同一实例）送达 onFailure。
     *
     * <p>样本特意用 {@link GoalVerdict.GoalProtocolException}：调用处靠 instanceof 把它分流到
     * onProtocolFailure。若 Runner 用 CompletionException/新 RuntimeException 包一层，协议失败会静默
     * 降级成调用失败——protocolFailLimit 熔断永不触发，/goal 空转到烧完 token。
     */
    @Test
    void evaluatorExceptionDelivered() throws Exception {
        GoalVerdict.GoalProtocolException boom = new GoalVerdict.GoalProtocolException("评估输出缺少 VERDICT 行");
        try (GoalEvaluationRunner r = new GoalEvaluationRunner(testCfg())) {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicReference<Throwable> got = new AtomicReference<>();
            AtomicReference<GoalVerdict> leaked = new AtomicReference<>();

            r.submit(1, in -> {
                throw boom;
            }, sampleInput(), leaked::set, x -> {
                got.set(x);
                failed.countDown();
            });

            assertTrue(failed.await(2, TimeUnit.SECONDS),
                    "2s 内没等到 onFailure——评估器的异常在 CompletableFuture 异步边界上被吞了");
            assertSame(boom, got.get(),
                    "评估器异常必须原样（同一实例）送达，实收: " + got.get()
                            + "——包一层就会让 GoalProtocolException 的类型分流失效");
            assertNull(leaked.get(), "异常路径不得再触发 onSuccess");
        }
    }

    /**
     * ★ 线程面：回调跑在名为 {@code goal-evaluator} 的 <b>daemon</b> 线程上，且池是<b>单线程</b>
     * （第一笔评估在飞时，第二笔绝不并发开跑）。
     *
     * <p>daemon：评估超时后底层阻塞调用仍在跑，滞留线程绝不许拖住 JVM 退出。单线程：评估天然串行，
     * 模型侧不会同时吃两次评估请求（评估串行是「一次空闲批一次评估」的本意）。
     */
    @Test
    void threadIsDaemonAndNamed() throws Exception {
        try (GoalEvaluationRunner r = new GoalEvaluationRunner(testCfg())) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch firstDone = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            AtomicReference<Thread> firstThread = new AtomicReference<>();
            AtomicReference<Thread> secondThread = new AtomicReference<>();

            r.submit(1, in -> {                       // 第一笔：卡在评估里，占住唯一的线程
                firstThread.set(Thread.currentThread());
                firstEntered.countDown();
                releaseFirst.await(2, TimeUnit.SECONDS);
                return satisfied();
            }, sampleInput(), v -> firstDone.countDown(), x -> firstDone.countDown());
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS), "2s 内评估没开跑——线程没起来");

            r.submit(2, in -> {                        // 第二笔：只许排队等第一笔让出线程
                secondThread.set(Thread.currentThread());
                return satisfied();
            }, sampleInput(), v -> secondDone.countDown(), x -> secondDone.countDown());

            assertFalse(secondDone.await(200, TimeUnit.MILLISECONDS),
                    "第一笔评估还在飞，第二笔就开跑了——池不是单线程，评估会并发打模型"
                            + "（超时/熔断计数也会错乱）");
            releaseFirst.countDown();
            assertTrue(firstDone.await(2, TimeUnit.SECONDS), "放行后第一笔评估仍未回调");
            assertTrue(secondDone.await(2, TimeUnit.SECONDS), "第一笔让出线程后第二笔仍未回调（池被卡死？）");

            Thread first = firstThread.get();
            assertTrue(first.getName().startsWith("goal-evaluator"),
                    "执行线程名必须以 goal-evaluator 开头（线程转储里一眼认出），实为: " + first.getName());
            assertTrue(first.isDaemon(), "评估线程必须是 daemon，否则滞留的评估会阻止 JVM 退出");
            assertNotSame(Thread.currentThread(), first, "回调不许跑在调用者（UI）线程上");
            assertSame(first, secondThread.get(), "两笔评估必须复用同一线程（单线程池）");
        }
    }
}
