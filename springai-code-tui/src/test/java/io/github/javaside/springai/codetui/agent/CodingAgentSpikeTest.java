package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;

/**
 * CodingAgentSpikeTest —— 里程碑2「硬验收」的真实链路 spike。
 *
 * <p><b>门控</b>：整个类用 {@link EnabledIfEnvironmentVariable} 绑 DEEPSEEK_API_KEY。
 * 有 key → {@code mvn test} 自动跑；无 key → 自动 SKIP（既不跑、也不算失败）。
 * 这里用 {@code *Test} + 该注解（而非 {@code *IT}/failsafe），因为 spike 需要被 surefire 收进
 * {@code mvn test}，仅在缺 key 时优雅跳过。
 *
 * <p>真实调用 DeepSeek，需要网络与有效 key，故由人工在里程碑2硬门处跑一次。
 * 无 key 的 headless CI 里它永远是 skipped。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")   // 默认不跑：联网、花钱、且墙钟断言天生不稳
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class CodingAgentSpikeTest {

    /** 单轮上限，避免挂死。 */
    private static final long TURN_TIMEOUT_MS = 60_000L;

    @TempDir
    Path tempRoot;

    /** 手写的记录型 AgentListener：回调来自 Reactor 线程，集合必须线程安全。 */
    private static final class Recorder implements AgentListener {
        final List<long[]> started = new CopyOnWriteArrayList<>();           // [turnId]（占位）
        // turnId -> 累积的 assistant 文本
        final Map<Long, StringBuilder> assistantText = new ConcurrentHashMap<>();
        final Map<Long, Integer> tokenCount = new ConcurrentHashMap<>();
        final List<Object[]> toolStarted = new CopyOnWriteArrayList<>();     // [turnId, name, input]
        final List<Object[]> toolFinished = new CopyOnWriteArrayList<>();    // [turnId, name, output, ok]
        final List<Object[]> todoUpdated = new CopyOnWriteArrayList<>();     // [turnId, lines]
        final List<Object[]> errors = new CopyOnWriteArrayList<>();          // [turnId, throwable]
        final java.util.Set<Long> completed = ConcurrentHashMap.newKeySet();

        @Override public void onTurnStarted(long turnId) { started.add(new long[]{turnId}); }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) {
            assistantText.computeIfAbsent(turnId, k -> new StringBuilder()).append(token);
            tokenCount.merge(turnId, 1, Integer::sum);
        }
        @Override public void onToolStarted(long turnId, String toolName, String input) {
            toolStarted.add(new Object[]{turnId, toolName, input});
        }
        @Override public void onToolFinished(long turnId, String toolName, String output, boolean ok) {
            toolFinished.add(new Object[]{turnId, toolName, output, ok});
        }
        @Override public void onSubagentStarted(long turnId, String taskId, String agentName, String description) { }
        @Override public void onSubagentFinished(long turnId, String taskId, String finalText) { }
        @Override public void onTodoUpdated(long turnId, List<String> todoLines) {
            todoUpdated.add(new Object[]{turnId, todoLines});
        }
        @Override public void onTurnComplete(long turnId) { completed.add(turnId); }
        @Override public void onError(long turnId, Throwable error) {
            errors.add(new Object[]{turnId, error});
        }
        @Override public void onQuestionAsked(long turnId, AskRequest request) { }

        /**
         * 本 spike 一律批准，恢复权限层接入（Task 12）之前的行为——它测的是流式 / 取消 / turnId 绑定，
         * 不是权限。
         *
         * <p><b>不能用 {@link AgentListener} 的默认 DENY</b>：实测那会让模型在
         * 「{@code find}/{@code ls -R} 被拒 → 换个办法 → 再被拒」上多绕 3 个 LLM 往返，
         * 单轮从 19s 涨到 60s 以上，把本类的 {@link #TURN_TIMEOUT_MS} 顶穿——
         * 于是一个「模型依赖的观察型用例」会因为权限层而红，指向的却不是任何缺陷。
         */
        @Override public void onPermissionRequested(long turnId, PermissionRequest request) {
            request.responder().respond(PermissionOutcome.ALLOW_ONCE);
        }

        @Override public void onCompactionStarted(String reason) { }
        @Override public void onCompactionFinished(int eventsRemoved, int tokensSaved) { }
        @Override public void onCompactionFailed(String message) { }

        int tokens(long turnId) { return tokenCount.getOrDefault(turnId, 0); }
        String text(long turnId) {
            StringBuilder b = assistantText.get(turnId);
            return b == null ? "" : b.toString();
        }
    }

    private ProviderRegistry buildRegistry() {
        // 多 provider 后：spike 仍只跑 DeepSeek（本类绑 DEEPSEEK_API_KEY）。
        return new ProviderRegistry(List.of(new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY"))));
    }

    /** 忙等某回合完成，超时即 fail（带清晰信息）。 */
    private void awaitComplete(Recorder rec, long turnId, String what) {
        long deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (rec.completed.contains(turnId)) {
                return;
            }
            if (!rec.errors.isEmpty()) {
                for (Object[] e : rec.errors) {
                    if ((Long) e[0] == turnId) {
                        fail(what + "：回合 " + turnId + " 报错：" + e[1]);
                    }
                }
            }
            sleep(100);
        }
        fail(what + "：回合 " + turnId + " 在 " + TURN_TIMEOUT_MS + "ms 内未完成（超时）");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---- 1. 文本抽取（硬） ----
    @Test
    void streamingTextExtraction() {
        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        AgentTools.AgentRuntime rt = AgentTools.build(buildRegistry(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-text", active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        agent.submit("用一句话回答：1+1 等于几？");
        long turnId = active.get();
        awaitComplete(rec, turnId, "文本抽取");

        assertTrue(rec.tokens(turnId) >= 1,
                "应至少收到 1 个 onAssistantToken（流式增量抽取生效），实际=" + rec.tokens(turnId));
        assertTrue(!rec.text(turnId).isEmpty(), "assistant 文本不应为空");
    }

    // ---- 2. 工具循环可观察（硬） ----
    @Test
    void toolLoopObservable() throws Exception {
        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        Path file = tempRoot.resolve("note.txt");
        Files.writeString(file, "香蕉苹果西瓜-SPIKE-42");

        AgentTools.AgentRuntime rt = AgentTools.build(buildRegistry(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-tool", active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        agent.submit("读取文件 " + file.getFileName() + " 并原样告诉我它的内容。");
        long turnId = active.get();
        awaitComplete(rec, turnId, "工具循环");

        boolean started = rec.toolStarted.stream().anyMatch(a -> (Long) a[0] == turnId);
        boolean finished = rec.toolFinished.stream().anyMatch(a -> (Long) a[0] == turnId);
        assertTrue(started, "应观察到 onToolStarted（工具装饰器触发），本回合 turnId=" + turnId);
        assertTrue(finished, "应观察到 onToolFinished，本回合 turnId=" + turnId);
    }

    // ---- 3. 多轮记忆（默认硬断言） ----
    // 注：ToolMemoryAdvisorDemo 有 advisor 顺序的坑——若记忆异常，需检查 MessageChatMemoryAdvisor
    // 与工具调用 advisor 的相对顺序。这里默认硬断言，若真需调顺序再改成记录性观察。
    @Test
    void multiTurnMemory() {
        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        AgentTools.AgentRuntime rt = AgentTools.build(buildRegistry(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-memory", active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        agent.submit("我叫张三，请记住。");
        long turn1 = active.get();
        awaitComplete(rec, turn1, "记忆-turn1");

        agent.submit("我叫什么名字？");
        long turn2 = active.get();
        awaitComplete(rec, turn2, "记忆-turn2");

        assertTrue(rec.text(turn2).contains("张三"),
                "turn2 的回答应包含「张三」（同 sessionId 记忆生效），实际=" + rec.text(turn2));
    }

    // ---- 4. 取消（硬：UI 层保证 token 停增；后端是否真停仅观察） ----
    @Test
    void cancellationStopsUiTokens() {
        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        AgentTools.AgentRuntime rt = AgentTools.build(buildRegistry(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-cancel", active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        Disposable d = agent.submit("请写一篇不少于 500 字的短文，介绍杭州西湖的四季景色。");
        long turnId = active.get();
        // 立即取消
        d.dispose();

        // 采样 -> 等待 -> 再采样：dispose 后 UI 层不应再收到该回合的新 token。
        sleep(500);
        int sample1 = rec.tokens(turnId);
        sleep(1500);
        int sample2 = rec.tokens(turnId);

        assertEquals(sample1, sample2,
                "dispose 后 UI 层 token 计数应停止增长（UI 层硬保证），sample1=" + sample1 + " sample2=" + sample2);
        // 后端是否真的停止推理 → 仅观察，不硬断言（见 spec §6：取消是 UI 层保证）。
        System.out.println("[spike] 取消后端观察：dispose 后 token 停在 " + sample2 + "（后端是否真停未断言）");
    }

    // ---- 5. Todo turnId 正确（观察，模型依赖） ----
    @Test
    void todoTurnIdBinding() {
        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        AgentTools.AgentRuntime rt = AgentTools.build(buildRegistry(), tempRoot, rec);
        CodingAgent agent = new CodingAgent(rt.client(), rec, "spike-session-todo", active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        agent.submit("制定一个包含 3 个步骤的计划来整理这个目录，用 TodoWrite 记录计划。");
        long turnId = active.get();
        awaitComplete(rec, turnId, "Todo 绑定");

        List<Object[]> mine = rec.todoUpdated.stream()
                .filter(a -> a.length > 0)
                .toList();
        if (mine.isEmpty()) {
            System.out.println("[spike] TodoWrite not triggered this run —— 跳过 turnId 断言（模型依赖）。");
            return;
        }
        for (Object[] ev : mine) {
            long evTurn = (Long) ev[0];
            assertEquals(turnId, evTurn,
                    "onTodoUpdated 的 turnId 应等于本回合 id（ThreadLocal 绑定有效），实际=" + evTurn);
            assertNotEquals(-1L, evTurn, "Todo turnId 不应为 -1（ThreadLocal 未绑定的哨兵值）");
        }
    }
}
