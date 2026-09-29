package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.llm.LlmProvider;
import io.github.javaside.springai.codetui.agent.llm.ModelOption;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatClientGoalEvaluator} 的正向路径：
 *
 * <ul>
 *   <li><b>精确路由</b>——evaluatorModel 配成 {@code provider:model} 且可解析时，装配期一次绑定该
 *       provider 的 chatModel（评估器是后台旁路，不该随 /model 漂移到用户正在用的那家）。</li>
 *   <li><b>renderUser 结构</b>——评估快照按 brief 的段落标签逐字渲染，占位符约定与 Task 5 的
 *       自动轮模板一致（空账本/首轮=（尚无）/（首轮），无插话=（无））。</li>
 * </ul>
 */
class ChatClientGoalEvaluatorTest {

    /** 记录收到的模型 id 与 maxTokens、返回预置响应（照 DynamicAuxChatModelTest 的桩模板）。 */
    private static final class RecordingChatModel implements ChatModel {
        private final String tag;
        volatile String lastModel;
        volatile Integer lastMaxTokens;

        RecordingChatModel(String tag) {
            this.tag = tag;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastModel = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
            this.lastMaxTokens = prompt.getOptions() == null ? null : prompt.getOptions().getMaxTokens();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(tag))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    /** 最小可用 provider。 */
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

    private static EvaluationInput sampleInput() {
        return new EvaluationInput("迁移 AuthService，mvn -pl server test 退出码 0", 2, 25, 1,
                "✓UserClient 接口替换",
                List.of(new GoalTurnRecord(1, "还有 4 个调用点未迁移", "已替换 2 处调用", 3, null)));
    }

    /** evaluatorModel 配成 provider:model 且可解析 → 精确绑定该家 chatModel，评估请求（含 512 上限）直达它。 */
    @Test
    void createResolvesConfiguredEvaluatorModel() throws Exception {
        RecordingChatModel rec = new RecordingChatModel("VERDICT: SATISFIED\nREASON: done\n"
                + "PROGRESS: advancing\nSTATE: ✓all");
        ProviderRegistry registry = new ProviderRegistry(List.of(new FakeProvider("pa", "model-a", rec)));
        GoalConfig config = GoalConfig.from(k ->
                GoalConfig.EVALUATOR_MODEL_ENV.equals(k) ? "pa:model-a" : null);

        assertSame(rec, ChatClientGoalEvaluator.evaluatorChatModel(registry, config),
                "可解析的 evaluatorModel 必须精确绑定该 provider 的 chatModel（一次装配、不随 /model 漂移）");

        GoalVerdict v = ChatClientGoalEvaluator.create(registry, config).evaluate(sampleInput());
        assertEquals(GoalVerdict.Outcome.SATISFIED, v.outcome());
        assertEquals("model-a", rec.lastModel, "评估请求应打到配置的模型");
        assertEquals(512, rec.lastMaxTokens, "评估请求必须带 512 输出上限");
    }

    /** renderUser 按 brief 结构逐字渲染各段落标签。 */
    @Test
    void renderUserFollowsBriefStructure() {
        EvaluationInput in = new EvaluationInput("迁移完成", 3, 25, 2, "✓A ✗B",
                List.of(new GoalTurnRecord(2, "还有调用点", "尾部文本", 4, "先跑测试")));
        String u = ChatClientGoalEvaluator.renderUser(in);
        assertTrue(u.startsWith("目标条件：迁移完成"), "首段是目标条件: " + u);
        assertTrue(u.contains("轮次：3/25"));
        assertTrue(u.contains("连续停滞轮数：2"));
        assertTrue(u.contains("上一轮 STATE 账本：✓A ✗B"));
        assertTrue(u.contains("最近 1 轮记录："));
        assertTrue(u.contains("--- 轮 2 ---"));
        assertTrue(u.contains("评估结论：还有调用点"));
        assertTrue(u.contains("agent 末文本（尾部 ≤2000）：尾部文本"));
        assertTrue(u.contains("本轮工具调用数：4"));
        assertTrue(u.contains("用户插话：先跑测试"));
    }

    /** 空槽占位：空账本=（尚无）、首轮评估结论=（首轮）、无插话=（无）。 */
    @Test
    void renderUserEmptySlotsUsePlaceholders() {
        EvaluationInput in = new EvaluationInput("migrate", 1, 25, 0, "",
                List.of(new GoalTurnRecord(1, null, "text", 0, null)));
        String u = ChatClientGoalEvaluator.renderUser(in);
        assertTrue(u.contains("上一轮 STATE 账本：（尚无）"));
        assertTrue(u.contains("最近 1 轮记录："));
        assertTrue(u.contains("评估结论：（首轮）"));
        assertTrue(u.contains("用户插话：（无）"));

        String empty = ChatClientGoalEvaluator.renderUser(
                new EvaluationInput("migrate", 1, 25, 0, "", List.of()));
        assertTrue(empty.contains("最近 0 轮记录："), "零记录也要有段落头: " + empty);
        assertTrue(empty.contains("（尚无）"), "零记录给占位，不留悬空标题: " + empty);
    }

    /** maxTurns=0=无上限：轮次行渲染 N/∞，评估器不得把它读成「只剩 0 轮」。 */
    @Test
    void renderUserUnlimitedTurnsRendersInfinity() {
        String u = ChatClientGoalEvaluator.renderUser(
                new EvaluationInput("migrate", 3, 0, 0, "", List.of()));
        assertTrue(u.contains("轮次：3/∞"), "无上限时轮次行写 N/∞，实际：" + u);
    }

    /** 系统协议的锚点行不许被顺手改写（prompt 契约，改动必须过 here）。 */
    @Test
    void systemPromptCarriesProtocolContract() {
        String s = ChatClientGoalEvaluator.SYSTEM_PROMPT;
        assertTrue(s.contains("VERDICT: SATISFIED | UNSATISFIED | IMPOSSIBLE"));
        assertTrue(s.contains("PROGRESS: advancing | stalled"));
        assertTrue(s.contains("STATE: <压缩的累积进度账本，checklist 风格，单行>"));
        assertTrue(s.contains("STATE: ✓UserClient 接口替换 ✗TokenRefresh 调用点 ✗mvn test 全绿"),
                "few-shot 示例行在——防中文小模型输出「满足/未满足」");
        assertTrue(s.contains("除协议行外不要输出任何内容。"));
        assertTrue(s.contains("不假设、不要求执行任何命令。"), "评估器只许看记录，不许执行");
    }
}
