package com.example.springai.codetui.agent;

import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionStrategy;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CodingAgent —— 编码 Agent 核心。把一次用户提交翻译成一轮流式对话，
 * 并把 Spring AI 的响应流「翻译」成纯 Java 的 {@link AgentListener} 事件。
 *
 * <p><b>接缝纪律</b>：{@link ChatClientResponse} 等 Spring AI 类型<b>不得</b>泄漏进
 * {@link AgentListener}——{@code handleChunk/handleError/handleComplete} 都是本类私有，
 * 只把抽取出的纯文本 / Throwable 交给 listener。
 *
 * <p><b>turnId 归属</b>：{@link #activeTurnId} 由本类拥有（每次 submit 自增），
 * <b>不</b>再与 AgentTools 共享；工具 / Todo 事件的 turnId 走 ToolContext / ThreadLocal（见 Task 5/6）。
 *
 * <p><b>取消竞态</b>：{@link #submit} 顶部<b>同步</b>调用 {@code onTurnStarted}（先锁定 UI 的
 * acceptingTurnId）再 subscribe，确保 dispose 时不会漏掉「回合已开始」的信号。
 */
public final class CodingAgent implements SubmitHandler {

    /** 可选模型（DeepSeek V4 现役）。第一个为默认。 */
    public static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"));

    private final ChatClient chatClient;
    private final AgentListener listener;
    private final String sessionId;
    private final AtomicLong activeTurnId;
    private final SessionService sessionService;
    private final CompactionStrategy manualStrategy;
    private final AtomicBoolean compactionInFlight = new AtomicBoolean(false);   // 防止并发/重复触发手动压缩
    private volatile String model = MODELS.get(0).id();   // 运行时可经 /model 切换，对后续回合生效

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy) {
        this.chatClient = chatClient;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
    }

    @Override
    public Disposable submit(String text) {
        long turnId = activeTurnId.incrementAndGet();
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);
        try {
            return chatClient.prompt()
                    .user(text)
                    .options(DeepSeekChatOptions.builder().model(model))   // 每次请求按当前所选模型覆盖
                    // 同步覆盖系统提示里的 {AGENT_MODEL} grounding，使模型自报身份与实际所选一致（其余 param 沿用默认，merge 语义）
                    .system(s -> s.param(AgentEnvironment.AGENT_MODEL_KEY, model))
                    .toolContext(Map.of("turnId", turnId))
                    // 会话记忆键：SessionMemoryAdvisor 按此解析/自动创建会话（值即 chat_memory_conversation_id）
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .stream().chatClientResponse()
                    .doOnNext(resp -> handleChunk(resp, turnId))
                    .doOnError(err -> handleError(err, turnId))
                    .doOnComplete(() -> handleComplete(turnId))
                    .subscribe();
        } catch (RuntimeException ex) {
            // 同步组装/订阅异常不走 doOnError：手动复位状态（onError → IDLE），
            // 否则 UI 会永远卡在 THINKING（无终态事件），且异常会逃逸出 View.handle。
            listener.onError(turnId, ex);
            return Disposables.disposed();
        }
    }

    @Override
    public List<ModelOption> models() { return MODELS; }

    @Override
    public String currentModel() { return model; }

    @Override
    public void selectModel(String id) {
        for (ModelOption m : MODELS) {
            if (m.id().equals(id)) { this.model = id; return; }   // 仅接受已知模型
        }
    }

    /**
     * 手动压缩：在后台线程强制立即压缩本会话。总结 LLM 调用可能耗时数分钟，绝不阻塞调用线程（UI）。
     * 并发闸门在 {@code CodeTuiView}（仅空闲且非压缩中才调用本方法）；此处再用 CAS 兜底一层，
     * 防止任何调用方漏判导致两次压缩并发、把 UI 的「开始/完成」事件交错。已在压缩中则静默忽略。
     */
    @Override
    public void compact() {
        if (!compactionInFlight.compareAndSet(false, true)) {
            return;   // 已有压缩在进行：忽略重复触发（UI 已给出反馈，这里不再叠加）
        }
        Thread t = new Thread(this::runCompaction, "manual-compact");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 同步执行一次手动压缩（供 {@link #compact()} 的后台线程调用；包级可见便于测试直调）。
     * 用恒真触发器绕过 token 阈值，配合激进的手动策略。
     *
     * <p>在调用 {@code sessionService.compact} <b>之前</b>先自行发一次 {@code onCompactionStarted("manual")}：
     * 保证即便压缩在「进入策略前」就抛异常（会话不存在 / I/O 失败），UI 也已显示过「正在压缩」，不会出现
     * 「只有失败行、没有开始提示」的静默错觉。进入策略后 {@link NotifyingCompactionStrategy} 会再发一次
     * started——{@code ConversationState.onCompactionStarted} 是幂等的（只重置计时/原因），无害。
     */
    void runCompaction() {
        try {
            listener.onCompactionStarted("manual");
            sessionService.compact(sessionId, req -> true, manualStrategy);
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
        } finally {
            compactionInFlight.set(false);   // 释放兜底闸门（无论成功/失败）
        }
    }

    /** 从一个流式块里抽取文本增量并发给 listener。全链路 null-guard：工具调用块常常没有输出/文本。 */
    private void handleChunk(ChatClientResponse resp, long turnId) {
        if (resp == null) {
            return;
        }
        ChatResponse chatResponse = resp.chatResponse();
        if (chatResponse == null) {
            return;
        }
        Generation generation = chatResponse.getResult();
        if (generation == null) {
            return;
        }
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            return;
        }
        String delta = output.getText();   // AbstractMessage#getText()（javap 已核实）
        if (delta != null && !delta.isEmpty()) {
            listener.onAssistantToken(turnId, delta);
        }
    }

    private void handleError(Throwable err, long turnId) {
        listener.onError(turnId, err);
    }

    private void handleComplete(long turnId) {
        listener.onTurnComplete(turnId);
    }
}
