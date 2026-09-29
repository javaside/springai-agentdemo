package io.github.javaside.springai.codetui.agent.goal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 评估器的三道守卫（spec §6.3）：
 *
 * <ol>
 *   <li><b>单轮裸请求</b>——评估请求恰 system+user 两条、零工具回调、零记忆注入。评估器一旦带工具/记忆，
 *       要么递归触发工具、要么把整段会话记忆拖进每次评估（每轮两次调用成本翻倍）；它只许「看滚动记录、给结论」。</li>
 *   <li><b>aux 回退</b>——evaluatorModel 解析不了（未知/不可用/格式坏）必须回退
 *       {@link DynamicAuxChatModel} 底座（恰好是该类，任何装饰都得先在这里被看见一眼），
 *       且<b>显式 warn</b> 不许静默——用户配了评估器模型却打到了别家，不能不留痕迹。</li>
 *   <li><b>协议失败直通</b>——评估器输出不合四行协议时 {@link GoalVerdict.GoalProtocolException}
 *       原样抛出，绝不就地吞掉换默认值：协议熔断计数（protocolFailLimit）靠这个异常驱动，
 *       吞了熔断就永远不触发，/goal 会空转到 maxTurns 烧完 token。</li>
 * </ol>
 */
class GoalEvaluatorGuardTest {

    /** 记录收到的 Prompt、返回预置响应（照 DynamicAuxChatModelTest 的桩模板）。 */
    static final class RecordingChatModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        volatile String lastModel;
        volatile Integer lastMaxTokens;
        volatile ChatResponse next = response("VERDICT: SATISFIED\nREASON: done\nPROGRESS: advancing\nSTATE: ✓all");

        @Override
        public ChatResponse call(Prompt p) {
            prompts.add(p);
            this.lastModel = p.getOptions() == null ? null : p.getOptions().getModel();
            this.lastMaxTokens = p.getOptions() == null ? null : p.getOptions().getMaxTokens();
            return next;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt p) {
            throw new UnsupportedOperationException("goal 评估器只走阻塞 call，不该有人 stream 它");
        }
    }

    static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 最小可用 provider：一个可辨识的 ChatModel + 一个模型 id（照 DynamicAuxChatModelTest）。 */
    private static final class FakeProvider implements LlmProvider {
        private final String id;
        private final String modelId;
        private final ChatModel model;

        FakeProvider(String id, String modelId, ChatModel model) {
            this.id = id;
            this.modelId = modelId;
            this.model = model;
        }

        @Override public String id() { return id; }
        @Override public boolean available() { return true; }
        @Override public ChatModel chatModel() { return model; }
        @Override public ChatOptions options(String modelId) { return ChatOptions.builder().model(modelId).build(); }
        @Override public List<ModelOption> models() { return List.of(new ModelOption(modelId, modelId, "")); }
        @Override public String defaultModel() { return modelId; }
    }

    private static ProviderRegistry registryWith(LlmProvider provider) {
        return new ProviderRegistry(List.of(provider));
    }

    private static GoalConfig configWithEvaluatorModel(String spec) {
        return GoalConfig.from(k -> GoalConfig.EVALUATOR_MODEL_ENV.equals(k) ? spec : null);
    }

    private static EvaluationInput sampleInput() {
        return new EvaluationInput("迁移 AuthService，mvn -pl server test 退出码 0", 2, 25, 1,
                "✓UserClient 接口替换 ✗TokenRefresh 调用点",
                List.of(new GoalTurnRecord(1, "还有 4 个调用点未迁移", "已替换 2 处调用", 3, null)));
    }

    /** ★ 守卫一：评估请求恰 system+user 两条、无工具回调——单轮裸调用，无 advisor 注入空间。 */
    @Test
    void requestHasNoToolsNoMemorySingleTurn() throws Exception {
        RecordingChatModel rec = new RecordingChatModel();
        GoalEvaluator ev = new ChatClientGoalEvaluator(ChatClient.builder(rec).build(),
                ChatClientGoalEvaluator.SYSTEM_PROMPT);

        GoalVerdict v = ev.evaluate(sampleInput());

        assertEquals(GoalVerdict.Outcome.SATISFIED, v.outcome(), "协议输出应被解析成 SATISFIED");
        Prompt sent = rec.prompts.get(0);
        assertEquals(2, sent.getInstructions().size(),
                "评估请求必须恰 system+user 两条——多出的任何一条都是 advisor/记忆在注入");
        assertInstanceOf(SystemMessage.class, sent.getInstructions().get(0), "第一条应是系统协议");
        assertInstanceOf(UserMessage.class, sent.getInstructions().get(1), "第二条应是渲染的评估快照");
        assertTrue(sent.getInstructions().get(1).getText().contains("目标条件："),
                "user 文本须含目标条件段（renderUser 契约）");
        assertTrue(sent.getInstructions().stream().noneMatch(m -> m instanceof ToolResponseMessage),
                "不许有工具结果消息混进评估请求");
        if (sent.getOptions() instanceof ToolCallingChatOptions toolOpts) {
            assertTrue(toolOpts.getToolCallbacks().isEmpty(),
                    "评估请求不得携带任何工具回调（Spring AI 2.0 的工具挂在 options 上）");
        }
    }

    /** ★ 守卫二：evaluatorModel 未知 → 回退后底层 ChatModel <b>恰好</b>是 DynamicAuxChatModel + 显式 warn。 */
    @Test
    void fallbackToAuxWhenModelUnknown() throws Exception {
        RecordingChatModel rec = new RecordingChatModel();
        ProviderRegistry registry = registryWith(new FakeProvider("pa", "model-a", rec));
        GoalConfig config = configWithEvaluatorModel("x:y");   // registry 里没有 x 家

        // 「恰好是该类」断言法（照 AuxClientNotRetryWrappedTest）：套任何一层都得先在这里红
        assertEquals(DynamicAuxChatModel.class,
                ChatClientGoalEvaluator.evaluatorChatModel(registry, config).getClass(),
                "evaluatorModel 未知时的回退底座被套了一层/不是 aux——先想清楚那一层会在每次评估时做什么");

        // 回退必须显式 warn，不许静默
        Logger logger = (Logger) LoggerFactory.getLogger(ChatClientGoalEvaluator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatClientGoalEvaluator.evaluatorChatModel(registry, config);
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                    "evaluatorModel 解析不了必须显式 warn，静默回退等于用户配置被无视:" + appender.list);
        } finally {
            logger.detachAppender(appender);
        }

        // 回退不是摆设：create 出来的评估器经 aux 底座真打通 active provider（pa），且 512 上限字段级合并后仍在
        GoalEvaluator ev = ChatClientGoalEvaluator.create(registry, config);
        assertEquals(GoalVerdict.Outcome.SATISFIED, ev.evaluate(sampleInput()).outcome(),
                "aux 回退后评估器必须可用");
        Prompt sent = rec.prompts.get(rec.prompts.size() - 1);
        assertEquals("model-a", sent.getOptions().getModel(), "应打到 active provider 的模型");
        assertEquals(512, sent.getOptions().getMaxTokens(),
                "aux 底座的字段级合并必须保住评估请求的 512 输出上限");
    }

    /** 守卫二补：evaluatorModel 空（默认）→ 同样落 aux 底座（这是出厂默认路径）。 */
    @Test
    void blankEvaluatorModelFallsBackToAux() {
        RecordingChatModel rec = new RecordingChatModel();
        ProviderRegistry registry = registryWith(new FakeProvider("pa", "model-a", rec));
        assertEquals(DynamicAuxChatModel.class,
                ChatClientGoalEvaluator.evaluatorChatModel(registry, GoalConfig.from(k -> null)).getClass(),
                "未配置 evaluatorModel 的默认底座必须是 aux（跟随 /model）");
    }

    /** ★ 守卫三：协议失败直通 GoalProtocolException——绝不吞，protocolFailLimit 熔断靠它驱动。 */
    @Test
    void parseFailureSurfacesAsProtocolException() {
        RecordingChatModel rec = new RecordingChatModel();
        rec.next = response("模型语无伦次没有标记行");
        GoalEvaluator ev = new ChatClientGoalEvaluator(ChatClient.builder(rec).build(),
                ChatClientGoalEvaluator.SYSTEM_PROMPT);

        GoalVerdict.GoalProtocolException ex = assertThrows(GoalVerdict.GoalProtocolException.class,
                () -> ev.evaluate(sampleInput()),
                "协议失败必须原样抛出——吞掉换默认值会让协议熔断永远不触发");
        assertTrue(ex.getMessage().contains("VERDICT"),
                "异常消息应指出缺 VERDICT 行（助诊断）: " + ex.getMessage());
    }
}
