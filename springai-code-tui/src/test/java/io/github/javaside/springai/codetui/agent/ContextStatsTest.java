package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextStatsTest {

    /**
     * 视觉 token 与文本估算是两笔账：图片从不进会话存储，故不该混进 estimatedTokens。
     * 合并会让「为什么请求比面板显示的大」和「压缩阈值为什么没触发」都变得无法解释。
     */
    @Test
    void visionTokensAreReportedSeparatelyFromTextEstimate() {
        ContextStats s = new ContextStats(10, 3, 4, 3, 0, 5_000L,
                100_000L, 128_000L, 120, 20, 2, 3_600L);
        assertEquals(5_000L, s.estimatedTokens());
        assertEquals(3_600L, s.visionTokens());
        assertEquals(2, s.visionImages());
    }

    @Test
    void emptySnapshotHasNoVisionUsage() {
        assertEquals(0, ContextStats.empty().visionImages());
        assertEquals(0L, ContextStats.empty().visionTokens());
    }

    @Test
    void modelContextWindowUsesExplicitOverrideBeforeBuiltInMetadata() {
        ModelContextWindows windows = ModelContextWindows.parse(
                "openai:gpt-5.6-sol=777777", 128_000L);

        assertEquals(777_777L, windows.resolve("openai", "gpt-5.6-sol"));
    }

    @Test
    void modelContextWindowFallsBackConservativelyForUnknownModels() {
        ModelContextWindows windows = ModelContextWindows.parse("", 128_000L);

        assertEquals(128_000L, windows.resolve("custom", "unknown-model"));
        assertTrue(windows.resolve("openai", "gpt-5.6-sol") > 128_000L,
                "内置已知模型应使用能力表，不应退回未知模型保守值");
    }

    @Test
    void gatewaySameNameModelResolvesWindowByModelIdFromBuiltInTable() {
        ModelContextWindows windows = ModelContextWindows.parse("", 128_000L);

        assertEquals(1_000_000L, windows.resolve("opencode-go", "deepseek-v4-pro"),
                "聚合网关提供的同名模型应复用原厂窗口条目，而不是按未知模型保守兜底");
    }

    @Test
    void conflictingSameNameWindowsAreNotGuessed() {
        ModelContextWindows windows = ModelContextWindows.parse(
                "a:model-x=100000,b:model-x=200000", 128_000L);

        assertEquals(128_000L, windows.resolve("gateway", "model-x"),
                "多家同 modelId 声明的窗口互相矛盾时说明网关给的可能不是同一模型，不猜，落保守兜底");
    }

    @Test
    void modelIdOverrideAppliesToGatewayCopyOfSameModel() {
        ModelContextWindows windows = ModelContextWindows.parse(
                "deepseek:deepseek-v4-pro=2000000", 128_000L);

        assertEquals(2_000_000L, windows.resolve("opencode-go", "deepseek-v4-pro"),
                "用户显式覆盖某模型的窗口时，网关同名模型应沿用该覆盖，而不是退回内置原厂值");
    }

    @Test
    void unknownGatewayModelStillFallsBackConservatively() {
        ModelContextWindows windows = ModelContextWindows.parse("", 128_000L);

        assertEquals(128_000L, windows.resolve("opencode-go", "glm-5.2"),
                "网关模型不在能力表且无同名原厂条目时，仍应落保守兜底");
    }

    @Test
    void zhipuGlm53HasMillionTokenWindow() {
        ModelContextWindows windows = ModelContextWindows.parse("", 128_000L);

        assertEquals(1_000_000L, windows.resolve("zhipu", "glm-5.3"),
                "官方模型卡：glm-5.3 上下文 1M");
        // 网关同名回退：opencode-go 的 glm-5.3 应复用原厂条目，而非保守兜底。
        assertEquals(1_000_000L, windows.resolve("opencode-go", "glm-5.3"));
    }

    @Test
    void tokenAwareTriggerFiresForLargeToolOutputEvenWhenMessageTextIsEmpty() {
        SessionEvent tool = SessionEvent.builder().sessionId("s").message(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "Read", "x".repeat(500))))
                        .build()).build();
        var request = org.springframework.ai.session.compaction.CompactionRequest.of(
                org.springframework.ai.session.Session.builder().id("s").userId("u").build(), List.of(tool));

        assertTrue(new CompleteTokenCountTrigger(400, String::length).shouldCompact(request));
    }

    @Test
    void boundedStrategyNeverSendsMoreThanChunkBudgetToSummarizer() {
        List<SessionEvent> events = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> SessionEvent.builder().sessionId("s")
                        .message(new UserMessage("x".repeat(100))).build())
                .toList();
        java.util.ArrayList<Long> seenChunkTokens = new java.util.ArrayList<>();
        BoundedSummarizationCompactionStrategy strategy = new BoundedSummarizationCompactionStrategy(
                400, 200, String::length,
                chunk -> {
                    seenChunkTokens.add((long) chunk.length());
                    return "summary";
                });

        CompactionResult result = strategy.compact(CompactionRequest.of(
                Session.builder().id("s").userId("u").build(), events));

        assertTrue(seenChunkTokens.size() > 1, "超限历史必须拆成多个摘要请求");
        assertTrue(seenChunkTokens.stream().allMatch(tokens -> tokens <= 200),
                "任何一次摘要输入都不能超过 chunk token 预算");
        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= 400,
                "压缩产物必须落回目标预算内");
        assertEquals(result.archivedEvents().size(), result.eventsRemoved(),
                "eventsRemoved 的库契约是 archivedEvents.size()，不是压缩前后列表长度差");
    }

    @Test
    void oversizedNewestTurnIsSummarizedInsteadOfKeptAboveTarget() {
        List<SessionEvent> events = List.of(
                SessionEvent.builder().sessionId("s").message(new UserMessage("old")).build(),
                SessionEvent.builder().sessionId("s").message(new UserMessage("x".repeat(600))).build(),
                SessionEvent.builder().sessionId("s").message(new AssistantMessage("y".repeat(600))).build());
        BoundedSummarizationCompactionStrategy strategy = new BoundedSummarizationCompactionStrategy(
                400, 200, String::length, chunk -> "summary");

        CompactionResult result = strategy.compact(CompactionRequest.of(
                Session.builder().id("s").userId("u").build(), events));

        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= 400,
                "最近完整回合自身超预算时也必须摘要，不能为了保留回合把超限请求继续发出去");
        assertEquals(events.size(), result.archivedEvents().size());
    }

    @Test
    void nonShrinkingSummariesFallBackToBoundedLocalDigest() {
        List<SessionEvent> events = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> SessionEvent.builder().sessionId("s")
                        .message(new UserMessage("x".repeat(100))).build())
                .toList();
        BoundedSummarizationCompactionStrategy strategy = new BoundedSummarizationCompactionStrategy(
                300, 150, String::length, chunk -> "z".repeat(500));

        CompactionResult result = strategy.compact(CompactionRequest.of(
                Session.builder().id("s").userId("u").build(), events));

        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= 300,
                "摘要模型输出膨胀或不收敛时必须本地有界降级，不能写回已知超预算结果");
    }

    @Test
    void summarizerFailureFallsBackLocallyInsteadOfBlockingConversation() {
        List<SessionEvent> events = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> SessionEvent.builder().sessionId("s")
                        .message(new UserMessage("x".repeat(100))).build())
                .toList();
        BoundedSummarizationCompactionStrategy strategy = new BoundedSummarizationCompactionStrategy(
                300, 150, String::length, chunk -> { throw new RuntimeException("summary unavailable"); });

        CompactionResult result = strategy.compact(CompactionRequest.of(
                Session.builder().id("s").userId("u").build(), events));

        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) <= 300,
                "摘要服务失败时仍应本地降级到安全预算，不能让主请求永久卡死");
        assertTrue(result.eventsRemoved() > 0);
    }

    @Test
    void boundedStrategyCanRescueSingleOversizedEvent() {
        SessionEvent huge = SessionEvent.builder().sessionId("s")
                .message(new UserMessage("x".repeat(1_000))).build();
        java.util.ArrayList<Integer> requestSizes = new java.util.ArrayList<>();
        BoundedSummarizationCompactionStrategy strategy = new BoundedSummarizationCompactionStrategy(
                400, 200, String::length, chunk -> {
                    requestSizes.add(chunk.length());
                    return "small summary";
                });

        CompactionResult result = strategy.compact(CompactionRequest.of(
                Session.builder().id("s").userId("u").build(), List.of(huge)));

        assertTrue(requestSizes.size() > 1, "单条巨型事件也必须在本地切块，不能因事件数少而 no-op");
        assertTrue(requestSizes.stream().allMatch(size -> size <= 200));
        assertTrue(SessionTokenEstimator.estimateEvents(result.compactedEvents(), String::length) < 400);
        assertEquals(1, result.eventsRemoved());
    }

    @Test
    void sessionTokenEstimatorCountsToolResultsAndToolCallArguments() {
        SessionEvent user = SessionEvent.builder().sessionId("s").message(new UserMessage("hello")).build();
        SessionEvent assistant = SessionEvent.builder().sessionId("s").message(
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "Read", "{\"path\":\"big.txt\"}")))
                        .build()).build();
        SessionEvent tool = SessionEvent.builder().sessionId("s").message(
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "Read", "x".repeat(100_000))))
                        .build()).build();

        long estimated = SessionTokenEstimator.estimateEvents(List.of(user, assistant, tool), String::length);

        assertTrue(estimated > 100_000L,
                "自动压缩计数必须覆盖 responseData 和 tool-call arguments，不能走 Message.getText() 漏算");
    }

    @Test
    void estimateMessagesByType_bucketsByMessageType_andSumsToTotal() {
        java.util.List<org.springframework.ai.chat.messages.Message> messages = List.of(
                new org.springframework.ai.chat.messages.SystemMessage("sys"),
                new UserMessage("user"),
                AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "Read", "args"))).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "Read", "result"))).build());

        SessionTokenEstimator.Buckets b = SessionTokenEstimator.estimateMessagesByType(messages, String::length);

        assertTrue(b.systemTokens() > 0, "system 消息应进系统桶");
        assertTrue(b.userTokens() > 0, "user 消息应进用户桶");
        assertTrue(b.assistantTokens() > 0, "assistant 消息（含 tool-call 名/参数）应进助手桶");
        assertTrue(b.toolTokens() > 0, "tool 结果应进工具桶");
        // 分桶之和 == 总数：/context 展示依赖「总数 == 各分类之和」恒成立。
        assertEquals(SessionTokenEstimator.estimateMessages(messages, String::length), b.total(),
                "分桶之和必须等于总数");
    }
}
