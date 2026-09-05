package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.interjection.InterjectingChatModel;
import io.github.javaside.springai.codetui.agent.interjection.Interjections;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.RetryReporter;
import io.github.javaside.springai.codetui.agent.llm.RetryingStreamChatModel;
import io.github.javaside.springai.codetui.agent.llm.StreamInterruptedException;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig.StreamRetryMode;
import io.github.javaside.springai.codetui.agent.llm.UsageRecordingChatModel;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.subagent.SubagentRunner;
import io.github.javaside.springai.codetui.agent.subagent.SubagentSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodingAgent 回合级续跑（L2 主体）的端到端契约，spec §3.3 / 计划 Task 6 的 17 用例。
 *
 * <p>装配：全参构造（registry + 单 entry clientsByProvider + 记录型 listener + 真实
 * DefaultSessionService + InMemory/包装桩 SessionRepository + 桩 SubagentRunner——先例
 * MidTurnInjectionTest）+ 脚本化桩 ChatModel（Flux.defer/计数照抄 RetryingChatModelTest.flaky）。
 * ChatClient 显式挂真实 SessionMemoryAdvisor（类型穿透全覆盖——SII 穿过真实 advisor 命中 L2 白名单）。
 *
 * <p>⚠ 既有 StubListener/AgentListenerAdapter 对 {@code onRetryScheduled} 是空 default——本类必须用
 * recording listener 并 override {@code onRetryScheduled}（否则用例 6/7/9/11 静默拿不到事件）。
 */
class CodingAgentTurnResumeTest {

    /** 与 CodingAgent.RESUME_NOTICE 完全一致的续跑通知文本（测试钉住生产文案，防两处漂移）。 */
    static final String NOTICE = "<system-notice>\n"
            + "网络中断，你的上一段输出未被保留。请从中断处继续完成回答，不要重复已输出的内容。\n"
            + "</system-notice>";

    private static final String TOOL = "QuickProbe";
    private static final StreamRetryConfig CFG_ALL = new StreamRetryConfig(StreamRetryMode.ALL);

    // ── 记录型 listener：本测试不用空桩的 onRetryScheduled ──────────────────────────
    static class RecordingListener extends StubListener {
        final List<Object[]> retries = new CopyOnWriteArrayList<>();     // [turnId, attempt, maxAttempts, backoffMs, reason]
        final List<Object[]> errors = new CopyOnWriteArrayList<>();      // [turnId, throwable]
        final List<Object[]> tokens = new CopyOnWriteArrayList<>();      // [turnId, token]
        final List<Long> completed = new CopyOnWriteArrayList<>();
        final List<Long> turnStarted = new CopyOnWriteArrayList<>();
        final List<Long> userMessages = new CopyOnWriteArrayList<>();

        @Override public void onTurnStarted(long turnId) { turnStarted.add(turnId); }
        @Override public void onUserMessage(long turnId, String text) { userMessages.add(turnId); }
        @Override public void onAssistantToken(long turnId, String token) { tokens.add(new Object[]{turnId, token}); }
        @Override public void onRetryScheduled(long turnId, int attempt, int maxAttempts, long backoffMs, String reason) {
            retries.add(new Object[]{turnId, attempt, maxAttempts, backoffMs, reason});
        }
        @Override public void onError(long turnId, Throwable error) { errors.add(new Object[]{turnId, error}); }
        @Override public void onTurnComplete(long turnId) { completed.add(turnId); }
    }

    // ── 工具：快工具（立即返回），供形状②的工具循环 ───────────────────────────────
    static ToolCallback quickTool() {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(TOOL).description("快工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) { return "工具结果"; }
            @Override public String call(String toolInput, org.springframework.ai.chat.model.ToolContext ctx) {
                return call(toolInput);
            }
        };
    }

    static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** tool_call 单 chunk（完整一帧：让 tool 回合正常完成、assistant(tool_calls) 经 after() 落库）。 */
    static ChatResponse toolCallChunk(int id) {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("call-" + id, "function", TOOL, "{}");
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(tc)).build())));
    }

    static StreamInterruptedException sii(String msg) {
        return new StreamInterruptedException(1, new RuntimeException(msg));
    }

    /** 1 chunk 文本后同流 error——断流在文本段（形状①）。 */
    static Flux<ChatResponse> textThenSii(String text, String msg) {
        return Flux.concat(Flux.just(chunk(text)), Flux.error(sii(msg)));
    }

    /**
     * 脚本化桩模型：按<b>订阅</b>序号出流（照抄 RetryingChatModelTest.flaky 的 Flux.defer + 计数模式——
     * 计数必须落在 defer 体内，L1（RetryingStreamChatModel）重订阅才会重走脚本、按新序号取流；
     * 计数放在 stream() 层会让 L1 每次重订阅都重放同一段脚本）。Prompt 在 stream() 调用时留档
     * （一轮 = 一次 ChatClient 模型调用；含工具循环内的每一轮）。
     */
    static ChatModel stubModel(List<Prompt> sink, List<Supplier<Flux<ChatResponse>>> script) {
        return new ChatModel() {
            final AtomicInteger calls = new AtomicInteger();
            @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt p) {
                sink.add(p);
                return Flux.defer(() -> {
                    int n = calls.incrementAndGet();
                    if (n <= script.size() && script.get(n - 1) != null) {
                        return script.get(n - 1).get();
                    }
                    return Flux.error(new IllegalStateException("未脚本化的第 " + n + " 次订阅"));
                });
            }
        };
    }

    /** 带真实 SessionMemoryAdvisor + Interjecting 装饰层的 ChatClient（interjections 与 CodingAgent 共用同一实例）。 */
    static ChatClient client(ChatModel raw, SessionService sessions, Interjections interjections, Object... tools) {
        ChatClient.Builder b = ChatClient.builder(InterjectingChatModel.wrap(raw, interjections))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build());
        if (tools.length > 0) {
            b = b.defaultTools(tools);
        }
        return b.build();
    }

    /** 全参构造（registry + 单 entry clientsByProvider）。runner 可空。 */
    static CodingAgent agent(SessionService sessions, SessionRepository repo, ChatClient chat,
                             RecordingListener listener, String sid, AtomicLong turnIds,
                             Interjections interjections, SubagentRunner runner, StreamRetryConfig cfg,
                             TokenUsageAccumulator usage) {
        return new CodingAgent(new ProviderRegistry(List.of(new DeepSeekProvider("k"))),
                Map.of("deepseek", chat), listener, sid, turnIds, sessions, null, null,
                List.of(), null, repo, null, runner, null, null, null, null, null, null,
                interjections, usage, 0L, cfg);
    }

    static SessionService sessions(SessionRepository repo) {
        return DefaultSessionService.builder().sessionRepository(repo).build();
    }

    static List<Prompt> awaitPrompts(List<Prompt> prompts, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (prompts.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(prompts.size() >= n, "只等到 " + prompts.size() + " 次模型调用，期望 " + n);
        return List.copyOf(prompts);
    }

    /** 等回合终态（onError 或 onTurnComplete），最多 15s。 */
    static void awaitTurnEnd(RecordingListener l) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (l.errors.isEmpty() && l.completed.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!l.errors.isEmpty() || !l.completed.isEmpty(), "回合 15s 内没有终态（onError/onTurnComplete）");
    }

    static void awaitRetries(RecordingListener l, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (l.retries.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(l.retries.size() >= n, "只收到 " + l.retries.size() + " 次 onRetryScheduled，期望 " + n);
    }

    static Message lastMsg(Prompt p) {
        List<Message> msgs = p.getInstructions();
        assertFalse(msgs.isEmpty(), "prompt 无任何消息");
        return msgs.get(msgs.size() - 1);
    }

    static long userCount(Prompt p) {
        return p.getInstructions().stream().filter(UserMessage.class::isInstance).count();
    }

    static long emptyUserCount(SessionService sessions, String sid) {
        return sessions.getMessages(sid).stream()
                .filter(m -> m instanceof UserMessage)
                .filter(m -> { String t = m.getText(); return t != null && t.isEmpty(); })
                .count();
    }

    static List<String> sessionTexts(SessionService sessions, String sid) {
        return sessions.getMessages(sid).stream().map(Message::getText).toList();
    }

    /** 会话消息的类型标签 + 有效载荷行（tool 结果文本在 ToolResponseMessage.responses 里，不在 getText）。 */
    static List<String> describeKinds(SessionService sessions, String sid) {
        return sessions.getMessages(sid).stream().map(m -> {
            if (m instanceof UserMessage um) {
                return "user[" + um.getText() + "]";
            }
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                return "assistant(tool_calls)";
            }
            if (m instanceof ToolResponseMessage trm) {
                return "tool" + trm.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData).toList();
            }
            if (m instanceof AssistantMessage a) {
                return "assistant[" + a.getText() + "]";
            }
            return m.getClass().getSimpleName();
        }).toList();
    }

    static long occurrences(String hay, String needle) {
        int idx = 0, count = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 脚本助手：1 基下标 → 出流。 */
    @SafeVarargs
    static List<Supplier<Flux<ChatResponse>>> script(Supplier<Flux<ChatResponse>>... entries) {
        List<Supplier<Flux<ChatResponse>>> out = new ArrayList<>();
        for (Supplier<Flux<ChatResponse>> e : entries) {
            out.add(e);
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1 形状①：文本段断流 → 重订阅 user == 问题 + "\n\n" + <system-notice>（composeResumeUser）
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("1 形状①文本断流：续跑轮出站恰一条 user=问题+notice；尾部 user 被删又由 before 重落")
    void case01_fromTextResume() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case01";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半截", "net down"),
                () -> Flux.just(chunk("收尾"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");

        List<Prompt> got = awaitPrompts(prompts, 2);
        awaitTurnEnd(lis);

        UserMessage resumed = assertInstanceOf(UserMessage.class, lastMsg(got.get(1)),
                "续跑轮尾部必须是被重放的 user");
        assertEquals("问题\n\n" + NOTICE, resumed.getText(),
                "composeResumeUser：空插话退化为 问题 + \\n\\n + notice 两段");
        assertEquals(1, lis.completed.size(), "续跑成功应 onTurnComplete");
        assertTrue(lis.errors.isEmpty(), "成功路径不应 onError：" + lis.errors);
        assertEquals(1, lis.retries.size());
        assertEquals(List.of("问题\n\n" + NOTICE, "收尾"), sessionTexts(sessions, sid),
                "会话尾部 user 在 prepareResume 被删后由 before() 重落，且不再有断流轮的残留");
        assertEquals(0, emptyUserCount(sessions, sid), "成功路径不得残留 blank user");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2 形状①+插话：断流前 offer → 续跑轮 user=问题+插话+notice（单条），Interjecting 不再注入
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("2 形状①+插话：takeAllForResumeUser 并入同一条 user；Interjecting 早退不产生第二条 user")
    void case02_fromTextWithInterjection() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case02";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        CountDownLatch streamed = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        ChatModel model = stubModel(prompts, script(
                () -> Flux.concat(Flux.just(chunk("半截")),
                        Flux.defer(() -> {
                            streamed.countDown();
                            try {
                                assertTrue(gate.await(10, TimeUnit.SECONDS), "闸门未放行");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return Flux.error(sii("net down"));
                        })),
                () -> Flux.just(chunk("收尾"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");

        assertTrue(streamed.await(10, TimeUnit.SECONDS), "首轮没流到可插话的窗口");
        interjections.offer("改用方案 B");
        gate.countDown();

        List<Prompt> got = awaitPrompts(prompts, 2);
        awaitTurnEnd(lis);

        assertEquals("问题\n\n改用方案 B\n\n" + NOTICE, ((UserMessage) lastMsg(got.get(1))).getText(),
                "插话并进同一条恢复 user（delivered→pending→takeAll 全量取走）");
        assertEquals(1, userCount(got.get(1)), "出站必须恰一条 user——Interjecting 不得再注入第二条");
        assertEquals(0, interjections.pendingCount(), "takeAllForResumeUser 后 pending 必空");
        assertTrue(lis.errors.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 3 形状②：真跑工具循环后断流 → 续跑轮为 Interjecting notice 注入条（无新独立 user）
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("3 形状②工具循环中断：notice 走 Interjecting 注入，无独立 .user()")
    void case03_fromToolsResume() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case03";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.just(toolCallChunk(1)),
                () -> textThenSii("半", "net down"),
                () -> Flux.just(chunk("完成"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");

        List<Prompt> got = awaitPrompts(prompts, 3);
        awaitTurnEnd(lis);

        // 会话：attempt1 工具循环落库 [user, assistant(tc), tool]；attempt2 before 追加 blank；handleComplete 清伪影。
        assertEquals(List.of("user[问题]", "assistant(tool_calls)", "tool[工具结果]", "assistant[完成]"),
                describeKinds(sessions, sid),
                "会话 = 问题 + assistant(tool_calls) + tool(工具结果) + 收尾，无 blank 残留");

        Prompt attempt2Prompt = got.get(2);
        UserMessage injected = assertInstanceOf(UserMessage.class, lastMsg(attempt2Prompt),
                "FROM_TOOLS 续跑轮末条必须是注入条");
        assertEquals(NOTICE, injected.getText(), "注入条 = 裸 notice（无插话不套 wrapText）");
        assertFalse(lastMsg(attempt2Prompt).getText().startsWith("问题"),
                "不含新的独立 user——本回合问题已在会话历史（attempt1 落库）");
        assertTrue(lis.errors.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4 ①→② 成功路径：多轮续跑后 blank user 在 handleComplete 清除
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("4 ①→② 成功：末轮 FROM_TOOLS 产生的 blank user 由 handleComplete 清除")
    void case04_blanksPurgedOnComplete() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case04";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半", "net down"),          // attempt1 断在文本 → ①
                () -> Flux.just(toolCallChunk(2)),           // attempt2 (FROM_TEXT) 工具轮
                () -> Flux.error(sii("net down")),           // attempt2 断在工具后 → ②
                () -> Flux.just(chunk("收尾成功"))));        // attempt3 (FROM_TOOLS) 成功
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");

        awaitPrompts(prompts, 4);
        awaitTurnEnd(lis);

        assertTrue(lis.completed.contains(1L), "多轮续跑后应成功完成");
        assertTrue(lis.errors.isEmpty());
        assertEquals(2, lis.retries.size(), "应排定 2 次续跑");
        assertEquals(0, emptyUserCount(sessions, sid),
                "末轮 FROM_TOOLS 落下的 blank user 必须在 handleComplete 被清（唯一覆盖点）");
        assertTrue(sessionTexts(sessions, sid).contains("收尾成功"));
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 5 ①→②→断 耗尽：doOnError 收到解包根因、文案含「已自动重试」与根因 message；耗尽后无 blank
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("5 上限耗尽：错误文案含已自动重试+根因；handleErrorWithRetryPrefix 清 blank")
    void case05_exhaustionUnwrapsAndPurges() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case05";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半", "net down"),
                () -> Flux.just(toolCallChunk(3)),
                () -> Flux.error(sii("net down")),
                () -> Flux.error(sii("net down"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");

        awaitPrompts(prompts, 4);
        awaitTurnEnd(lis);

        assertEquals(4, prompts.size(), "首次 + 2 次续跑 = 3 个顶层回合（工具轮计 2 次模型调用）");
        assertEquals(1, lis.errors.size(), "耗尽只报一次 onError");
        Throwable err = (Throwable) lis.errors.get(0)[1];
        String msg = err.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("已自动重试"), "文案应含已自动重试，实际：" + msg);
        assertTrue(msg.contains("net down"), "文案应含根因 message，实际：" + msg);
        assertEquals(2, lis.retries.size(), "2 次续跑各排定一次");
        assertEquals(0, emptyUserCount(sessions, sid),
                "耗尽失败路径的 blank 必须在 handleErrorWithRetryPrefix 末尾被清");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 6 白名单外：IllegalStateException → 1 次订阅、onError 直达、无 onRetryScheduled
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("6 白名单外错误不续跑：直接 onError、无 retry 事件")
    void case06_nonWhitelistedNoRetry() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case06";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.error(new IllegalStateException("bad"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        awaitPrompts(prompts, 1);
        awaitTurnEnd(lis);

        assertEquals(1, prompts.size(), "白名单外错误不得重订阅");
        assertTrue(lis.retries.isEmpty(), "不得有 onRetryScheduled");
        assertEquals(1, lis.errors.size());
        assertInstanceOf(IllegalStateException.class, lis.errors.get(0)[1]);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 7 Esc：退避等待期 dispose → 无后续事件；cancelTurn 生效；repo replaceEvents 不再增长
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("7 Esc 退避期：dispose 后事件冻结 + cancelTurn 打断在飞子 agent + replaceEvents 不再增长")
    void case07_escapeDuringBackoff() throws Exception {
        CountingRepo repo = new CountingRepo();
        SessionService sessions = sessions(repo);
        String sid = "case07";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半截", "net down")));
        // 真实 runner + 阻塞假模型：cancelTurn 打断的是「该 turn 名下的池」
        CountDownLatch subEntered = new CountDownLatch(1);
        SubagentRunner runner = blockingRunner(subEntered);
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, runner, CFG_ALL, null);

        Disposable d = agent.submit("问题");

        awaitRetries(lis, 1);                       // 已进入 1s 退避
        Thread sub = new Thread(() -> {
            try {
                runner.runAll(List.of(new SubagentRunner.Dispatch(spec(), "p1", "d1")), 1L);
            } catch (Throwable ignored) {
            }
        }, "case07-subagent");
        sub.setDaemon(true);
        sub.start();
        assertTrue(subEntered.await(5, TimeUnit.SECONDS), "子 agent 没进场");
        assertTrue(runner.inFlightCount() > 0, "取消前应有在飞子 agent");

        long replaceAtDispose = repo.replaceCount.get();
        int tokensAtDispose = lis.tokens.size();
        d.dispose();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (runner.inFlightCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(runner.inFlightCount() == 0, "cancelTurn 必须被打到（在飞子 agent 被中断归零）");

        Thread.sleep(1300);                          // 略超 1s 退避
        assertEquals(tokensAtDispose, lis.tokens.size(), "Esc 后不得再有 onAssistantToken");
        assertEquals(1, lis.retries.size(), "Esc 后不得再有 onRetryScheduled");
        assertTrue(lis.errors.isEmpty() && lis.completed.isEmpty(), "Esc 后不得有终态事件");
        assertEquals(replaceAtDispose, repo.replaceCount.get(),
                "dispose 后 defer 体不得再 replaceEvents（defer 内 disposed 检查）");
        sub.join(3000);
    }

    static SubagentSpec spec() {
        return new SubagentSpec("explore", "d", "sys", List.of(), List.of(), null, List.of());
    }

    /** 真实 SubagentRunner：假 provider 的模型阻塞到被中断（照抄 SubagentRunnerParallelTest.cancelTurn）。 */
    static SubagentRunner blockingRunner(CountDownLatch entered) {
        io.github.javaside.springai.codetui.agent.llm.LlmProvider provider = new io.github.javaside.springai.codetui.agent.llm.LlmProvider() {
            @Override public String id() { return "fake"; }
            @Override public boolean available() { return true; }
            @Override public ChatModel chatModel() {
                return new ChatModel() {
                    @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
                    @Override public Flux<ChatResponse> stream(Prompt prompt) {
                        return Flux.defer(() -> {
                            entered.countDown();
                            try {
                                Thread.sleep(10_000);
                            } catch (InterruptedException e) {
                                return Flux.error(new RuntimeException("interrupted", e));
                            }
                            return Flux.just(chunk("ok"));
                        });
                    }
                    @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
                };
            }
            @Override public ChatOptions options(String modelId) { return ChatOptions.builder().build(); }
            @Override public List<io.github.javaside.springai.codetui.agent.llm.ModelOption> models() {
                return List.of(new io.github.javaside.springai.codetui.agent.llm.ModelOption("fake-m", "Fake", "d"));
            }
            @Override public String defaultModel() { return "fake-m"; }
        };
        return new SubagentRunner(new ProviderRegistry(List.of(provider)), List.of(), new StubListener(), "", 4);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 8 Esc 窗口：attempt2 advisor-before（notice 已 set、inject 未跑）里 dispose + drainForRefill
    //   → 下一回合出站不含 <system-notice>
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("8 Esc 窗口（latch）：notice 已 set 未注入时取消+drainForRefill，下回合无 notice 泄漏")
    void case08_escapeWindowNoticesCleared() throws Exception {
        GatedRepo repo = new GatedRepo(5);
        SessionService sessions = sessions(repo);
        String sid = "case08";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener() {
            @Override public void onRetryScheduled(long turnId, int attempt, int maxAttempts, long backoffMs, String reason) {
                super.onRetryScheduled(turnId, attempt, maxAttempts, backoffMs, reason);
                repo.arm();                       // 开窗计数从首次续跑排定开始
            }
        };
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.just(toolCallChunk(1)),       // attempt1 工具轮（assistant(tc) 落库）
                () -> textThenSii("半", "net down"),     // attempt1 断在工具后 → ②，setResumeNotice 排定
                () -> Flux.just(chunk("早退")),          // attempt2 若仍被放行则快速终了（信号被取消丢弃）
                () -> Flux.just(chunk("第二问答复"))));  // 新回合
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        Disposable d = agent.submit("问题");
        assertTrue(repo.entered.await(15, TimeUnit.SECONDS), "没等到 attempt2 的 advisor-before 窗口");

        d.dispose();                                  // Esc：取消流
        List<String> taken = agent.takeBackInterjections();   // View 层路径：drainForRefill 清 notice
        assertTrue(taken.isEmpty(), "本回合无待交还插话");
        repo.gate.countDown();                        // 放行被卡的 attempt2 before

        Thread.sleep(300);                            // 让被取消的 attempt2 尘埃落定

        agent.submit("第二问");                        // 下一回合（全新 submit）
        awaitTurnEnd(lis);

        Prompt secondTurnFirst = prompts.stream()
                .filter(p -> p.getInstructions().stream().anyMatch(m -> m instanceof UserMessage
                        && ((UserMessage) m).getText() != null && ((UserMessage) m).getText().startsWith("第二问")))
                .findFirst().orElse(null);
        assertNotNull(secondTurnFirst, "第二回合的首轮出站应含 user(第二问)");
        assertFalse(secondTurnFirst.getInstructions().stream()
                        .map(Message::getText).anyMatch(t -> t != null && t.contains("<system-notice>")),
                "notice 已被 drainForRefill 清掉，下回合不得再注入 <system-notice>");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 9 同 turnId：全程 listener 收到的 turnId 相同
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("9 同 turnId：断流续跑全程事件带同一 turnId")
    void case09_sameTurnIdAcrossResume() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case09";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半截", "net down"),
                () -> Flux.just(chunk("收尾"))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        awaitPrompts(prompts, 2);
        awaitTurnEnd(lis);

        assertEquals(List.of(1L), lis.turnStarted);
        for (Object[] t : lis.tokens) {
            assertEquals(1L, t[0], "onAssistantToken 的 turnId");
        }
        for (Object[] r : lis.retries) {
            assertEquals(1L, r[0], "onRetryScheduled 的 turnId");
        }
        assertEquals(List.of(1L), lis.completed, "onTurnComplete 的 turnId");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 10 L2 关（L1_ONLY / OFF）：断流不续跑直接 onError，且无 onRetryScheduled
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("10 L2 关闭（L1_ONLY 与 OFF）：SII 不续跑、无 onRetryScheduled")
    void case10_l2Disabled() throws Exception {
        for (StreamRetryMode mode : List.of(StreamRetryMode.L1_ONLY, StreamRetryMode.OFF)) {
            SessionRepository repo = InMemorySessionRepository.builder().build();
            SessionService sessions = sessions(repo);
            String sid = "case10-" + mode;
            Interjections interjections = new Interjections();
            RecordingListener lis = new RecordingListener();
            List<Prompt> prompts = new CopyOnWriteArrayList<>();
            ChatModel model = stubModel(prompts, script(
                    () -> Flux.error(sii("net down"))));
            CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                    new AtomicLong(), interjections, null, new StreamRetryConfig(mode), null);

            agent.submit("问题");
            awaitTurnEnd(lis);

            assertEquals(1, prompts.size(), mode + "：不得续跑");
            assertTrue(lis.retries.isEmpty(), mode + "：不得排定 L2 重试");
            assertEquals(1, lis.errors.size(), mode + "：直接 onError");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 11 L1 计数桥：真实 RetryingStreamChatModel 零下发失败 2 次后成功（模拟 Task 7 装配层桥接）
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("11 L1 桥：桩模型层 Retrying 的 report 经 onL1Retry → onRetryScheduled(turnId,2,5,500,reason)")
    void case11_l1BridgeCounts() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case11";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel stub = stubModel(prompts, script(
                () -> Flux.error(new RuntimeException("Request failed", new java.io.EOFException("read failed"))),
                () -> Flux.error(new RuntimeException("Request failed", new java.io.EOFException("read failed"))),
                () -> Flux.just(chunk("done"))));
        SinkHolder bridge = new SinkHolder();
        ChatModel l1 = RetryingStreamChatModel.wrap(stub, bridge);
        AtomicLong turnIds = new AtomicLong();
        CodingAgent agent = agent(sessions, repo, client(l1, sessions, interjections), lis, sid,
                turnIds, interjections, null, CFG_ALL, null);
        bridge.sink = (attempt, backoffMs, reason) -> agent.onL1Retry(attempt, backoffMs, reason);

        agent.submit("问题");
        awaitPrompts(prompts, 1);
        awaitRetries(lis, 2);
        awaitTurnEnd(lis);
        assertTrue(lis.errors.isEmpty(), "L1 重试后应成功：" + lis.errors);
        assertEquals(1L, turnIds.get());
        Object[] first = lis.retries.get(0);
        assertEquals(1L, first[0], "turnId");
        assertEquals(2, first[1], "attempt（首重试 = 2）");
        assertEquals(5, first[2], "L1 maxAttempts=5（UI 拼文案用）");
        assertEquals(500L, first[3], "L1 首重试退避 500ms");
        assertNotNull(first[4], "reason 非空");
    }

    /** 可重绑的 L1 reporter 桩（模拟 Task 7 装配期建、构造后 bind 的两段式）。 */
    static final class SinkHolder implements RetryReporter {
        volatile RetryReporter sink;
        @Override public void report(int attempt, long backoffMs, String reason) {
            RetryReporter s = sink;
            if (s != null) {
                s.report(attempt, backoffMs, reason);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 12 失败前缀：零重试错误（401 直抛）→ onError 文本不含「已自动重试」
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("12 零重试错误文案干净：onError 不含已自动重试")
    void case12_zeroRetryErrorNoPrefix() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case12";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.error(WebClientResponseException.create(401, "Unauthorized", null, null, null))));
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        awaitTurnEnd(lis);

        assertEquals(1, lis.errors.size());
        String msg = String.valueOf(((Throwable) lis.errors.get(0)[1]).getMessage());
        assertFalse(msg.contains("已自动重试"), "零重试错误不得拼「已自动重试」前缀，实际：" + msg);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 13 ①→② 混变防双份：形状②轮的最后一条 user 已含 notice → 跳过 setResumeNotice → notice 恰 1 次
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("13 ①→② 混变：续跑轮 <system-notice> 恰出现 1 次（来自历史 user 文本），末条非注入条")
    void case13_mixedShapesNoDuplicateNotice() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case13";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半", "net down"),       // attempt1 文本断 → ①
                () -> Flux.just(toolCallChunk(4)),        // attempt2 (FROM_TEXT) 工具轮
                () -> textThenSii("半", "net down"),      // attempt2 断在工具后 → ②
                () -> Flux.just(chunk("完成"))));         // attempt3 (FROM_TOOLS)
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        List<Prompt> got = awaitPrompts(prompts, 4);
        awaitTurnEnd(lis);

        assertTrue(lis.errors.isEmpty());
        Prompt attempt3 = got.get(3);
        String all = String.join("\n", attempt3.getInstructions().stream().map(Message::getText).toList());
        assertEquals(1, occurrences(all, "<system-notice>"),
                "形状②轮已含 notice 的历史 user 存在 → 跳过 setResumeNotice → notice 不得双份");
        assertFalse(lastMsg(attempt3).getText().contains("<system-notice>"),
                "末条不得是注入条——历史 user 已带 notice 时该轮不注入");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 14 ②→① 边界误判（R11 声明行为）：形状②轮断在 blank user → 误判 ① 重放问题 → 会话含两份问题文本
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("14 ②→① 边界误判：重放问题、会话含两份问题文本、形状合法、不丢数据")
    void case14_misjudgedBoundaryKeepsData() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case14";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.just(toolCallChunk(5)),        // attempt1 工具轮完成
                () -> textThenSii("半", "net down"),      // attempt1 断在工具后 → ②
                () -> Flux.error(sii("net down")),        // attempt2 (FROM_TOOLS) 断在 before 后（blank 尾部）
                () -> Flux.just(chunk("收尾"))));         // attempt3 误判 ① → 重放问题 → 成功
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        awaitTurnEnd(lis);

        assertTrue(lis.completed.contains(1L), "边界误判路径也应成功完成（R11：行为合法）");
        assertTrue(lis.errors.isEmpty());
        long questions = sessions.getMessages(sid).stream()
                .filter(m -> m instanceof UserMessage)
                .filter(m -> m.getText() != null && m.getText().startsWith("问题"))
                .count();
        assertEquals(2, questions, "重放使会话含两份问题文本（声明的误判行为，不丢数据）");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 15 续跑后新回合卫生：回合 B 全新 submit 首轮无 notice、无 strip 副作用
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("15 续跑后新回合卫生：新 submit 首轮出站无 notice")
    void case15_newTurnHygiene() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case15";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> textThenSii("半", "net down"),     // 回合 A 断 1 次
                () -> Flux.just(chunk("答复A")),         // 回合 A 续跑成功
                () -> Flux.just(chunk("答复B"))));       // 回合 B 全新
        AtomicLong turnIds = new AtomicLong();
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections), lis, sid,
                turnIds, interjections, null, CFG_ALL, null);

        agent.submit("A 问题");
        awaitPrompts(prompts, 2);
        awaitTurnEnd(lis);
        assertEquals(List.of(1L), lis.completed, "回合 A 完成");

        agent.submit("B 问题");
        List<Prompt> got = awaitPrompts(prompts, 3);
        awaitTurnEnd(lis);

        UserMessage bUser = assertInstanceOf(UserMessage.class, lastMsg(got.get(2)),
                "回合 B 首轮尾部 = 新 user");
        assertEquals("B 问题", bUser.getText(), "新回合首轮不得重放/注入 notice");
        assertEquals(List.of(1L, 2L), lis.completed, "回合 B（turnId=2）完成");
        assertEquals(2L, turnIds.get(), "两个回合各占一个 turnId");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 16 空流带 usage 记两笔（spec §5 L1 行）：RetryStream(Usage(stub))，空流+usage 后成功
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("16 空流带 usage：L1 空流重试后 acc 记 2 笔、cacheHitPercent 不失真")
    void case16_emptyStreamWithUsageRecordsTwice() {
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        ChatResponse withUsage = new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20, 120, null, 50L, 0L)).build());
        ChatResponse doneUsage = new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20, 120, null, 50L, 0L)).build());
        AtomicInteger calls = new AtomicInteger();
        ChatModel stub = new ChatModel() {
            @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (calls.incrementAndGet() == 1) {
                        return Flux.just(withUsage);        // 空 content 但带 usage → L1 空流守卫重试
                    }
                    return Flux.just(doneUsage);
                });
            }
        };
        ChatModel chained = RetryingStreamChatModel.wrap(new UsageRecordingChatModel(stub, acc), null);

        String text = chained.stream(new Prompt("hi")).blockLast().getResult().getOutput().getText();
        assertEquals("done", text);
        assertEquals(2, calls.get(), "空流守卫应重试一次");
        TokenUsageAccumulator.Snapshot snap = acc.snapshot();
        assertEquals(200L, snap.billedInputTokens(), "两次尝试各记一笔 usage（含空流那一笔）");
        assertEquals(50, snap.cacheHitPercent(), "cacheHitPercent 不失真（两次同构 usage）");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 17 执行序对抗夹具：断在首个 tool_call、无工具结果落库 → trim 后判 ① → 续跑出站恰一条 user 含 notice
    // ════════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("17 断在首个 tool_call：trim→判定后为①，续跑轮出站恰一条 user 且含 notice")
    void case17_breakAtFirstToolCall() throws Exception {
        SessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = sessions(repo);
        String sid = "case17";
        Interjections interjections = new Interjections();
        RecordingListener lis = new RecordingListener();
        List<Prompt> prompts = new CopyOnWriteArrayList<>();
        ChatModel model = stubModel(prompts, script(
                () -> Flux.concat(Flux.just(toolCallChunk(6)),
                        Flux.error(sii("net down"))),      // 订阅1：tool_call 后立即断，工具结果未落库
                () -> Flux.just(chunk("完成"))));          // 订阅2（重放）
        CodingAgent agent = agent(sessions, repo, client(model, sessions, interjections, quickTool()),
                lis, sid, new AtomicLong(), interjections, null, CFG_ALL, null);

        agent.submit("问题");
        List<Prompt> got = awaitPrompts(prompts, 2);
        awaitTurnEnd(lis);

        assertTrue(lis.errors.isEmpty(), "重放后应成功：" + lis.errors);
        assertEquals(1, userCount(got.get(1)),
                "续跑轮出站必须恰一条 UserMessage（trim→判定→strip→重放，无悬空 tc 干扰）");
        assertTrue(lastMsg(got.get(1)).getText().contains("<system-notice>"),
                "重放的 user 必须含 <system-notice>");
    }

    // ── SessionRepository 包装桩 ───────────────────────────────────────────────

    /** 计数 replaceEvents（dispose 后不再增长的断言载体）。 */
    static final class CountingRepo implements SessionRepository {
        final InMemorySessionRepository delegate = InMemorySessionRepository.builder().build();
        final AtomicLong replaceCount = new AtomicLong();

        @Override public Session save(Session s) { return delegate.save(s); }
        @Override public Optional<Session> findById(String id) { return delegate.findById(id); }
        @Override public List<Session> findByUserId(String userId) { return delegate.findByUserId(userId); }
        @Override public List<String> findExpiredSessionIds(Instant before) { return delegate.findExpiredSessionIds(before); }
        @Override public void delete(String sessionId) { delegate.delete(sessionId); }
        @Override public void appendEvent(SessionEvent e) { delegate.appendEvent(e); }
        @Override public void replaceEvents(String sessionId, List<SessionEvent> events) {
            replaceCount.incrementAndGet();
            delegate.replaceEvents(sessionId, events);
        }
        @Override public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
            return delegate.replaceEvents(sessionId, events, expectedVersion);
        }
        @Override public long getEventVersion(String sessionId) { return delegate.getEventVersion(sessionId); }
        @Override public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
            return delegate.findEvents(sessionId, filter);
        }
    }

    /** arm 后第 gateAt 次 findEvents 卡闸门（Esc 窗口用）。 */
    static final class GatedRepo implements SessionRepository {
        final InMemorySessionRepository delegate = InMemorySessionRepository.builder().build();
        final int gateAt;
        final AtomicLong sinceArm = new AtomicLong();
        final AtomicBoolean armed = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);

        GatedRepo(int gateAt) { this.gateAt = gateAt; }

        void arm() { armed.set(true); }

        @Override public List<SessionEvent> findEvents(String sessionId, EventFilter filter) {
            if (armed.get()) {
                long n = sinceArm.incrementAndGet();
                if (n == gateAt) {
                    entered.countDown();
                    try {
                        gate.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return delegate.findEvents(sessionId, filter);
        }
        @Override public Session save(Session s) { return delegate.save(s); }
        @Override public Optional<Session> findById(String id) { return delegate.findById(id); }
        @Override public List<Session> findByUserId(String userId) { return delegate.findByUserId(userId); }
        @Override public List<String> findExpiredSessionIds(Instant before) { return delegate.findExpiredSessionIds(before); }
        @Override public void delete(String sessionId) { delegate.delete(sessionId); }
        @Override public void appendEvent(SessionEvent e) { delegate.appendEvent(e); }
        @Override public void replaceEvents(String sessionId, List<SessionEvent> events) { delegate.replaceEvents(sessionId, events); }
        @Override public boolean replaceEvents(String sessionId, List<SessionEvent> events, long expectedVersion) {
            return delegate.replaceEvents(sessionId, events, expectedVersion);
        }
        @Override public long getEventVersion(String sessionId) { return delegate.getEventVersion(sessionId); }
    }
}
