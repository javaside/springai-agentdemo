package com.example.springai.codetui.agent;

import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final AgentListener listener;
    private final String sessionId;
    private final AtomicLong activeTurnId;
    private final SessionService sessionService;
    private final CompactionStrategy manualStrategy;
    private final TokenCountEstimator tokenCountEstimator;   // 与压缩共用，供 /context 估算会话 token
    private final AtomicBoolean compactionInFlight = new AtomicBoolean(false);   // 防止并发/重复触发手动压缩
    private final List<SkillInfo> skills;   // 可用技能清单（/skills 展示）；装配期确定、运行期只读
    private final ToolCallback skillTool;   // 被 ToolEventCallback 装饰的 Skill 工具（可空）；手动 /skill 发送前调用它
    private final SessionRepository sessionRepository;   // 可空：取消回合时回滚会话到回合前快照（见 submit 的 doOnCancel）
    private volatile String model = MODELS.get(0).id();   // 运行时可经 /model 切换，对后续回合生效

    /** 无技能清单的构造（回显桩/测试桩用）：等价于技能为空。 */
    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, List.of());
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, null);
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills, ToolCallback skillTool) {
        this(chatClient, listener, sessionId, activeTurnId, sessionService, manualStrategy,
                tokenCountEstimator, skills, skillTool, null);
    }

    public CodingAgent(ChatClient chatClient, AgentListener listener, String sessionId, AtomicLong activeTurnId,
                       SessionService sessionService, CompactionStrategy manualStrategy,
                       TokenCountEstimator tokenCountEstimator, List<SkillInfo> skills, ToolCallback skillTool,
                       SessionRepository sessionRepository) {
        this.chatClient = chatClient;
        this.listener = listener;
        this.sessionId = sessionId;
        this.activeTurnId = activeTurnId;
        this.sessionService = sessionService;
        this.manualStrategy = manualStrategy;
        this.tokenCountEstimator = tokenCountEstimator;
        this.skills = List.copyOf(skills);
        this.skillTool = skillTool;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public Disposable submit(String text) {
        return submit(text, null);
    }

    @Override
    public Disposable submit(String text, String skillName) {
        long turnId = activeTurnId.incrementAndGet();
        listener.onTurnStarted(turnId);        // 同步：先锁定 acceptingTurnId，消除取消竞态
        listener.onUserMessage(turnId, text);  // UI 展示用户原文（注入只影响发给模型的文本）
        String effectiveText = injectSkill(text, skillName, turnId);
        // 回合前会话快照：取消（Esc → dispose）时用它回滚，删除本回合写入的半截历史。
        // 否则工具回合被中途取消会在会话里留下「带 tool_calls 但无 tool 结果」的悬空 assistant 消息，
        // 下一次请求把这段坏历史发给 DeepSeek 会 400（insufficient tool messages following tool_calls）。
        List<SessionEvent> mark = snapshotSession();
        try {
            return chatClient.prompt()
                    .user(effectiveText)
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
                    // 仅在「被取消」时触发（正常 complete/error 不触发）：把会话回滚到回合前，清掉半截历史。
                    .doOnCancel(() -> rollbackTo(mark))
                    .subscribe();
        } catch (RuntimeException ex) {
            // 同步组装/订阅异常不走 doOnError：手动复位状态（onError → IDLE），
            // 否则 UI 会永远卡在 THINKING（无终态事件），且异常会逃逸出 View.handle。
            listener.onError(turnId, ex);
            return Disposables.disposed();
        }
    }

    /**
     * 手动 /skill：发送前真正调用（被装饰的）Skill 工具——触发工具事件（UI 可见）并拿正文注入到 text 前。
     * skillName/skillTool 任一为空则原样返回（不注入）；工具调用失败也降级为不注入（事件已由装饰器上报）。
     */
    private String injectSkill(String text, String skillName, long turnId) {
        if (skillName == null || skillTool == null) {
            return text;
        }
        try {
            ToolContext ctx = new ToolContext(Map.of("turnId", turnId));
            String body = skillTool.call(toJsonCommand(skillName), ctx);
            return "<skill_instruction>\n" + body + "\n</skill_instruction>\n\n" + text;
        } catch (RuntimeException ex) {
            return text;   // 注入失败不阻断本轮：按原文发送（工具失败事件已由 ToolEventCallback 上报）
        }
    }

    /** 构造 SkillsInput 的 JSON 入参 {"command":"<name>"}。用 Jackson（与 DiffRenderer 一致），避免手写转义/硬编码线格式。 */
    private static String toJsonCommand(String skillName) {
        try {
            return MAPPER.writeValueAsString(Map.of("command", skillName));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 Skill 命令入参失败：" + skillName, e);
        }
    }

    /** 回合前会话事件快照（复制一份，回合内的写入不会改动它）。sessionService 缺失（测试桩）时返回空。 */
    List<SessionEvent> snapshotSession() {
        return sessionService == null ? List.of() : List.copyOf(sessionService.getEvents(sessionId));
    }

    /**
     * 把会话回滚到 {@code mark} 快照（取消回合时调用）：用 {@link SessionRepository#replaceEvents} 覆盖写回，
     * 删除本回合写入的半截历史（尤其是悬空的 {@code assistant(tool_calls)}）。
     * 仓库缺失（测试桩）时静默跳过。
     */
    void rollbackTo(List<SessionEvent> mark) {
        if (sessionRepository != null) {
            sessionRepository.replaceEvents(sessionId, mark);
        }
    }

    @Override
    public List<SkillInfo> skills() { return skills; }

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
     * <p><b>本方法独占手动路径的三个生命周期事件</b>（started/finished/failed）：它<b>直接</b>调用
     * {@code sessionService.compact} 并拿到返回的 {@link CompactionResult} 自行上报，因此
     * {@code manualStrategy} 是<b>未包装</b>的裸策略（见 {@code AgentTools}）——若再套
     * {@link NotifyingCompactionStrategy}，同一次失败会被「装饰器 + 本方法」重复上报。装饰器只服务于
     * 自动路径（advisor 内部调用、拿不到返回值）。
     *
     * <p>先发 started 再调用：保证即便压缩在进入策略前就抛异常（会话不存在 / I/O），UI 也已显示过「正在压缩」，
     * 不会出现「只有失败行、没有开始提示」的静默错觉。{@code catch} 覆盖到 {@link Error}：即便 LLM 摘要抛出
     * {@code Error}，也先发 failed 把 {@code compacting} 清掉（否则 UI 永久卡在「压缩中」、{@code isBusy()}
     * 恒真、后续回合与 /compact 全被堵死），再把 {@code Error} 向上抛出、不吞。
     */
    void runCompaction() {
        try {
            listener.onCompactionStarted("manual");
            CompactionResult result = sessionService.compact(sessionId, req -> true, manualStrategy);
            listener.onCompactionFinished(result.eventsRemoved(), result.tokensEstimatedSaved());
        } catch (RuntimeException e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));
        } catch (Error e) {
            listener.onCompactionFailed(String.valueOf(e.getMessage()));   // 先清状态，避免永久卡「压缩中」
            throw e;                                                        // Error 不吞：清完状态仍向上抛
        } finally {
            compactionInFlight.set(false);   // 释放兜底闸门（无论成功/失败）
        }
    }

    /**
     * 现算一份会话上下文用量快照（供 {@code /context} 命令展示）。
     *
     * <p>读一遍当前会话的事件（数条数、按消息类型分桶）与消息（拼接文本 → JTokkit 估算 token）。
     * 只读、不加锁：若恰有回合在写事件，拿到的是尽力而为的一致视图，对「看个大概用量」足够。
     * 会话尚无事件（未开始对话）时 {@code getEvents} 返回空列表，自然得到全 0 的快照。
     *
     * <p>token 估算走与压缩同一个 {@link TokenCountEstimator}。{@code Message} 只是 {@code Content}（有 getText），
     * 并非 {@code MediaContent}，故不能直接喂 {@code estimate(Iterable)}——改为拼接各消息文本后 {@code estimate(String)}，
     * 与「累计文本 token」的直觉一致。阈值/窗口/保留窗口等策略数取自 {@link AgentTools}（同包常量），保证与实际装配一致。
     */
    @Override
    public ContextStats contextStats() {
        List<SessionEvent> events = sessionService.getEvents(sessionId);
        int user = 0, assistant = 0, tool = 0, other = 0;
        for (SessionEvent e : events) {
            MessageType type = e.getMessageType();
            if (type == MessageType.USER) {
                user++;
            } else if (type == MessageType.ASSISTANT) {
                assistant++;
            } else if (type == MessageType.TOOL) {
                tool++;
            } else {
                other++;   // 系统 / 摘要等
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : sessionService.getMessages(sessionId)) {
            String text = m.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(text).append('\n');
            }
        }
        long tokens = sb.length() == 0 ? 0L : tokenCountEstimator.estimate(sb.toString());
        return new ContextStats(events.size(), user, assistant, tool, other, tokens,
                AgentTools.COMPACTION_TOKEN_THRESHOLD, AgentTools.CONTEXT_WINDOW_TOKENS,
                AgentTools.MAX_EVENTS_TO_KEEP, AgentTools.MANUAL_MAX_EVENTS_TO_KEEP);
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
