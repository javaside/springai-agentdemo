package io.github.javaside.springai.codetui.agent.goal;

import io.github.javaside.springai.codetui.agent.llm.DynamicAuxChatModel;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Objects;

/**
 * {@link GoalEvaluator} 的裸 ChatClient 实现：system（四行协议）+ user（渲染的评估快照）单轮直调，
 * 无工具、无记忆、无 advisor（同 {@code AgentTools} 裸 client 先例）——评估器只许「看滚动记录、
 * 给结论」，带了工具会递归触发执行，带了记忆会把整段会话拖进每轮两次的评估调用。
 *
 * <p><b>底层模型决策</b>（{@link #evaluatorChatModel}，守卫测试盯住的唯一决策点）：
 * {@code evaluatorModel}（{@code CODETUI_GOAL_EVALUATOR_MODEL}，格式 {@code provider:model}）为空 →
 * aux 底座（{@link DynamicAuxChatModel}，跟随 {@code /model} 切换）；解析不了（未知/不可用/格式坏）→
 * <b>显式 warn + aux 底座</b>，绝不静默换家；能解析 → 装配期一次精确绑定该 provider 的 chatModel——
 * 评估器是后台旁路，用户配了专属评估模型就是不想让它随 {@code /model} 漂到主对话那家。
 * 直连时该家的<b>每请求基础 options</b>（含模型 id、必填项）一并用作请求底座，再按字段级盖上
 * 512 上限——否则 provider 的 chatModel 单例只会打它自己的默认模型，配置的 modelId 被静默无视。
 *
 * <p><b>协议直通</b>：{@code call().content()} → {@link GoalVerdict#parse}，协议失败原样抛
 * {@link GoalVerdict.GoalProtocolException}——不吞、不换默认值，{@code protocolFailLimit} 熔断靠它驱动。
 *
 * <p><b>maxTokens=512</b>：STATE 账本是单行 checklist，512 足够；无硬上限时部分家输出可膨胀到输入级。
 * aux 底座按字段级合并保住该值（同摘要路径 8192 的语义），直连 provider 时该值即请求上限。
 */
public final class ChatClientGoalEvaluator implements GoalEvaluator {

    /**
     * 评估器系统协议（brief 契约逐字，<b>不许润色</b>）。few-shot 示例行专治中文小模型
     * 把协议行写成「满足/未满足」。
     */
    public static final String SYSTEM_PROMPT = """
            你是目标验收评估器。只依据给定的滚动记录判断目标是否达成；不假设、不要求执行任何命令。
            严格按以下协议输出四个标记行（标记为行首顶格的大写英文）：
            VERDICT: SATISFIED | UNSATISFIED | IMPOSSIBLE
            REASON: <一句话结论，语言与目标条件一致>
            PROGRESS: advancing | stalled
            STATE: <压缩的累积进度账本，checklist 风格，单行>
            输出示例：
            VERDICT: UNSATISFIED
            REASON: server 模块还有 4 个调用点未迁移。
            PROGRESS: advancing
            STATE: ✓UserClient 接口替换 ✗TokenRefresh 调用点 ✗mvn test 全绿
            除协议行外不要输出任何内容。
            """;

    /** 评估输出上限：账本是单行 checklist，512 足够，也把失控输出挡在线外。 */
    static final int EVAL_MAX_OUTPUT_TOKENS = 512;

    private static final Logger log = LoggerFactory.getLogger(ChatClientGoalEvaluator.class);

    private static final String EMPTY_LEDGER = "（尚无）";
    private static final String FIRST_ROUND = "（首轮）";
    private static final String NO_INTERJECTION = "（无）";

    private final ChatClient client;
    private final String systemPrompt;
    /** 直连家时的每请求基础 options（含模型 id/必填项）；aux 底座时为 null（合并由 aux 模型自己做）。 */
    private final ChatOptions baseOptions;

    public ChatClientGoalEvaluator(ChatClient client, String systemPrompt) {
        this(client, systemPrompt, null);
    }

    ChatClientGoalEvaluator(ChatClient client, String systemPrompt, ChatOptions baseOptions) {
        this.client = Objects.requireNonNull(client, "client");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.baseOptions = baseOptions;
    }

    @Override
    public GoalVerdict evaluate(EvaluationInput input) {
        // 基础 options（直连家：模型 id/必填项）之上按字段级盖 512 输出上限；aux 路径无基础值
        ChatOptions.Builder<?> opts = baseOptions == null
                ? ChatOptions.builder()
                : baseOptions.mutate();
        String content = client.prompt()
                .system(systemPrompt)
                .user(renderUser(input))
                // 注意本项目的 Spring AI 版本里 options(...) 收 Builder（照 AgentTools.summarizeChunk 形态）
                .options(opts.maxTokens(EVAL_MAX_OUTPUT_TOKENS))
                .call()
                .content();
        return GoalVerdict.parse(content);   // 协议失败直通 GoalProtocolException
    }

    /**
     * 装配工厂：按 {@code config.evaluatorModel()} 解析底层模型与基础 options（见类注释的决策表），
     * 套裸 ChatClient（无 defaultTools / 无 advisors / 无 defaultSystem——协议走每请求 system）。
     */
    public static GoalEvaluator create(ProviderRegistry registry, GoalConfig config) {
        Resolved resolved = resolve(registry, config);
        return new ChatClientGoalEvaluator(
                ChatClient.builder(resolved.model()).build(), SYSTEM_PROMPT, resolved.baseOptions());
    }

    /**
     * 评估器底层 ChatModel 的<b>唯一决策点</b>（照 {@code AgentTools.auxChatModel} 抽法：从工厂里
     * 抽成一处显式决策，守卫测试直接盯返回值的运行时类，任何装饰都逃不过「恰好是该类」断言）。
     */
    static ChatModel evaluatorChatModel(ProviderRegistry registry, GoalConfig config) {
        return resolve(registry, config).model();
    }

    /** 解析结果：model=底层 ChatModel；baseOptions=直连家的每请求基础 options（aux 回退为 null）。 */
    private record Resolved(ChatModel model, ChatOptions baseOptions) { }

    /**
     * spec 格式 {@code provider:model}（同 {@code ProviderRegistry.requestSelection} 的精确路由）；
     * 缺 {@code ':'} 视作格式坏 → 与未知同样走 warn + aux 回退。
     */
    private static Resolved resolve(ProviderRegistry registry, GoalConfig config) {
        String spec = config.evaluatorModel();
        if (spec != null && !spec.isBlank()) {
            int sep = spec.indexOf(':');
            String providerId = sep < 0 ? "" : spec.substring(0, sep).trim();
            String modelId = sep < 0 ? "" : spec.substring(sep + 1).trim();
            try {
                ProviderRegistry.RequestSelection sel = registry.requestSelection(providerId, modelId);
                return new Resolved(sel.provider().chatModel(), sel.options());
            } catch (IllegalArgumentException e) {
                log.warn("goal 评估器模型 '{}' 不可用（{}），回退 aux 底座（跟随 /model 切换）",
                        spec, e.getMessage());
            }
        }
        return new Resolved(new DynamicAuxChatModel(registry), null);
    }

    /**
     * 评估快照 → user 文本。段落标签为 prompt 契约（brief 逐字），评估器语言跟随目标条件；
     * 空槽占位沿用自动轮模板的约定：（尚无）/（首轮）/（无）。
     */
    static String renderUser(EvaluationInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标条件：").append(in.condition()).append('\n');
        sb.append("轮次：").append(in.turn()).append('/').append(GoalText.limitText(in.maxTurns())).append('\n');
        sb.append("连续停滞轮数：").append(in.stalledStreak()).append('\n');
        sb.append("上一轮 STATE 账本：").append(blank(in.stateLedger()) ? EMPTY_LEDGER : in.stateLedger()).append('\n');
        sb.append("最近 ").append(in.recentTurns().size()).append(" 轮记录：\n");
        if (in.recentTurns().isEmpty()) {
            sb.append(EMPTY_LEDGER).append('\n');
        }
        for (GoalTurnRecord r : in.recentTurns()) {
            sb.append("--- 轮 ").append(r.turn()).append(" ---\n");
            sb.append("评估结论：").append(blank(r.evalReason()) ? FIRST_ROUND : r.evalReason()).append('\n');
            sb.append("agent 末文本（尾部 ≤2000）：").append(r.assistantTail() == null ? "" : r.assistantTail()).append('\n');
            sb.append("本轮工具调用数：").append(r.toolCallCount()).append('\n');
            sb.append("用户插话：").append(blank(r.userInterjection()) ? NO_INTERJECTION : r.userInterjection()).append('\n');
        }
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
