package io.github.javaside.springai.codetui.agent.goal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.javaside.springai.codetui.agent.AgentTools;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 全链装配（Task 8）的两条守卫：
 *
 * <ol>
 *   <li><b>离线装配同一实例</b>——{@code AgentTools.build} 第 7 参传入的 {@link GoalManager} 必须
 *       原样暴露到 {@code runtime.goalManager()}：状态机只有一份，View goal 槽 / CodingAgent（Task 9
 *       的 bindGoal）都从 runtime 取。另建一个等于 UI 永远看不到真相（同 interjections 字段先例）。</li>
 *   <li><b>aux 回退显式告警</b>——{@code evaluatorModel="ghost:ghost"}（registry 无此家）时
 *       {@link ChatClientGoalEvaluator#create} 必须回退且<b>恰为</b> {@link DynamicAuxChatModel} 底座，
 *       并显式 warn 不许静默换家（Task 6 守卫的装配面复认，见
 *       {@code GoalEvaluatorGuardTest.fallbackToAuxWhenModelUnknown}）。</li>
 * </ol>
 */
class GoalWiringTest {

    @Test
    void buildAssemblesGoalComponentsOffline(@TempDir Path root) {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        GoalManager gm = new GoalManager(GoalConfig.from(k -> null), null);

        AgentTools.AgentRuntime rt = AgentTools.build(registry, root, new StubListener(), null,
                AgentTools.testEngine(root), StreamRetryConfig.from(k -> null), gm);

        assertNotNull(rt.goalManager(), "build 第 7 参传入的 GoalManager 必须暴露到 runtime.goalManager()");
        assertSame(gm, rt.goalManager(),
                "runtime 必须与传入实例 same——另建一个等于 UI 永远看不到真相（同 interjections 先例）");
    }

    @Test
    void auxFallbackLoggedExplicitly() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        GoalConfig config = GoalConfig.from(k ->
                GoalConfig.EVALUATOR_MODEL_ENV.equals(k) ? "ghost:ghost" : null);   // registry 无 ghost 家

        // 断言式接管：本类的 logger 走 slf4j→logback，JUL Handler 收不到它——
        // pom 里的 jul-to-slf4j 桥只单向 JUL→SLF4J，logback 事件永不回流 JUL（实测确认，见 task-8 报告）。
        // 故按任务纪律改用同款 ListAppender（照 GoalEvaluatorGuardTest 的既有做法）。
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientGoalEvaluator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 「恰好是该类」断言法（照 AuxClientNotRetryWrappedTest 先例）：回退底座被套任何一层都先在这里红
            assertEquals(DynamicAuxChatModel.class,
                    ChatClientGoalEvaluator.evaluatorChatModel(registry, config).getClass(),
                    "evaluatorModel 解析不了时的回退底座被套了一层/不是 aux——先想清楚那一层会在每次评估时做什么");

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage() != null
                            && e.getFormattedMessage().contains("回退 aux")),
                    "回退必须显式 warn（消息含「回退 aux」），静默换家等于用户配置被无视: " + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
