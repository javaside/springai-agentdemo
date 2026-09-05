package io.github.javaside.springai.codetui.agent.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前置验证 V1/V3（spec §4，docs/superpowers/specs/2026-09-03-main-agent-stream-retry-design.md）：
 * 用真实 {@link ChatClient} + 真实 {@link SessionMemoryAdvisor} + 假 {@link ChatModel} 钉死
 * L2 续跑管线依赖的两个框架行为前提。装配骨架整段照抄 {@link SessionIdStreamGuardAdvisorTest}。
 *
 * <p><b>V1 三断言（2026-09-04 实测，与 spec §3.3 步骤 3 推演一致）</b>：
 * <ol>
 *   <li><b>断言 1（{@link #beforeAppendsTurnUser_onFirstSubscribe}）</b>：首次订阅时
 *       {@code SessionMemoryAdvisor.before()} 把本回合 user 追加进会话（{@code appendMessage}，
 *       事件尾部为 {@code UserMessage("问题")}）——真实续跑轮里这条来自会话历史的上一轮消息，
 *       本测试简化为空会话上的首条，before() 的 append 语义相同；</li>
 *   <li><b>断言 2（{@link #noUserPrompt_persistsBlankUser_outboundExcludesIt}）</b>：请求不调
 *       {@code .user()}（spec 形状②：{@code spec = client.prompt()} 重建、不重放问题文本）时，
 *       ① 会话尾部落库一条 {@code getText().isEmpty()} 的 {@link UserMessage}（空 user 兜底落库）
 *       ——实测机制：{@code Prompt.getLastUserOrToolResponseMessage()} 找不到 User/ToolResponse
 *       消息时<b>兜底 {@code new UserMessage("")}</b>，before() 无条件 {@code appendMessage} 落库；
 *       ② 出站 {@code prompt.getInstructions()} 中<b>不存在</b>任何 {@code getText().isEmpty()} 的
 *       UserMessage——不调 {@code .user()} 时 {@code DefaultChatClientUtils.toChatClientRequest}
 *       构造的 instructions 列表为空（无兜底注入；空 user 只在 advisor 落库路径产生，不进出站）。
 *       ⚠ 断言写法用 noneMatch——「出站最后一条 user 文本为空」是错误断言：出站根本没有那条消息；</li>
 *   <li><b>断言 3（{@link #coldFlux_sameInstance_secondSubscribe_fails} +
 *       {@link #deferRebuildsSpec_beforeRerunsPerSubscription}）——与 spec §1.2 推演
 *       <b>不符（BLOCKED_V1_MISMATCH，实测纠偏）</b></b>：spec §1.2 称「.stream().chatClientResponse()
 *       每次订阅从链头重跑……重订阅即完整重走」。实测（spring-ai-client-chat 2.0.0 源码核验
 *       {@code DefaultAroundAdvisorChain.nextStream}）：advisor 链是
 *       {@code ConcurrentLinkedDeque}，{@code pop()} <b>破坏性消费</b>，链在每次
 *       {@code .stream()} 调用时构建一次——<b>同一 Flux 实例二次订阅抛
 *       {@code IllegalStateException("No StreamAdvisors available to execute")}</b>，
 *       before() 只执行一次（user 只追加一次）；「冷流确证」只在 <b>defer 内重建 spec</b>
 *       的形态下成立（spec §3.3 L2 伪码恰是这种形态：{@code Flux.defer(() -> client.prompt()
 *       … .stream().chatClientResponse())}，retryWhen 重订阅的是外层 defer 流、每轮重调
 *       {@code .stream()} 建新链）——defer 形态下实测 before() 每轮重跑、user 追加两次。
 *       <b>影响面</b>：§3.3 的 defer 形态不受影响；任何「保存单次 chatClientResponse()
 *       Flux 供重试」的写法都会炸；§1.2 文字需更正（详见 task-0-report.md）。</li>
 * </ol>
 *
 * <p><b>V3 结论区（2026-09-04，抓帧 + spring-ai 2.0.0 字节码核验，校准 spec §3.2 hasContent 口径）</b>：
 * <ul>
 *   <li><b>usage-only 收尾 chunk（choices 非空但无 delta 内容，或 DeepSeek include_usage 的
 *       choices=[] 终末帧）的 {@code AssistantMessage.getText()} 是 {@code null} 而非 {@code ""}</b>：
 *       spring-ai-deepseek 的 {@code DeepSeekApi.ChatCompletionMessage.content()} 是 record 组件，
 *       SSE JSON 里省略该字段时 Jackson 反序列化为 {@code null}，经
 *       {@code DeepSeekAssistantMessage.Builder.content(null)} 原样传给
 *       {@code AbstractMessage.textContent}（ASSISTANT 类型不触发「Content must not be null」断言，
 *       该断言只作用于 SYSTEM/USER）；{@code getText()} 即返回该字段。既有样本证据：
 *       {@link DeepSeekUsageStreamingTest} 第三条用例的 SSE 样本把 usage 放在
 *       {@code "choices":[]} 的独立终末帧——它不产生任何 Generation（choices 为空 → generations
 *       为空列表 → {@code getResult()} 返回 {@code null}），usage 只进 metadata。两种形态下
 *       「text 非空（{@code != null && !isEmpty()}）」口径都判定为<b>无内容</b>，零下发计数不受
 *       收尾帧污染；</li>
 *   <li><b>finish-only chunk（finishReason 非空、无文本、无 tool_calls，如
 *       {@code "delta":{},"finish_reason":"stop"}）</b>：finishReason 进
 *       {@code ChatGenerationMetadata}（不进消息文本），text 仍为 {@code null}——同上判定为无内容；</li>
 *   <li><b>DeepSeek reasoning-only chunk 不进 {@code getText()}</b>：spring-ai-deepseek 把
 *       {@code reasoning_content} 映射到 {@code DeepSeekAssistantMessage.reasoningContent} 独立字段
 *       （{@code DeepSeekApi.ChatCompletionMessage} 的 {@code @JsonProperty("reasoning_content")}
 *       record 组件；{@code buildGeneration} 里 {@code Builder.content(message.content())} 与
 *       {@code Builder.reasoningContent(message.reasoningContent())} 各走各的字段），思考流分片
 *       {@code getText()} 恒为 {@code null}——「text 非空」口径下 thinking-only chunk 不计数，
 *       <b>「零下发 = 下游零观测」不变式成立</b>。本测试补最小桩断言（见
 *       {@link #v3_reasoningOnlyChunk_hasNoText_zeroEmitCaliber}）钉死该口径。</li>
 * </ul>
 *
 * <p>不联网、确定性；不改生产代码（本文件是 spec §4 的验证证据，Task 6 strip 步骤去留的依据）。
 */
class AdvisorResubscribeBehaviorTest {

    /**
     * 可控桩模型：记录每次 stream() 收到的出站 Prompt，返回单条正常响应。
     * brief 指定「返回 Flux.error」验证重订阅路径，但 error 会让 after() 跳过 assistant 落库、
     * 聚合器 doOnError 只记日志，对 V1 三断言无影响；用正常流可同时断言「出站 prompt 形状」
     * 与「会话落库形状」，且场景 A 两次订阅都走完整 advisor 链，before() 重跑证据等价。
     */
    private static final class RecordingModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        final AtomicInteger streamCalls = new AtomicInteger();

        @Override public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            streamCalls.incrementAndGet();
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        }
    }

    private static SessionService newService() {
        return DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();
    }

    private static SessionMemoryAdvisor memory(SessionService svc) {
        return SessionMemoryAdvisor.builder(svc).defaultUserId("u").build();   // 无压缩，隔离被测逻辑
    }

    /** V1 断言 1：首次订阅时 before() 向会话 append 本回合 user。 */
    @Test
    void beforeAppendsTurnUser_onFirstSubscribe() {
        SessionService svc = newService();
        RecordingModel model = new RecordingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(memory(svc))
                .build();

        client.prompt()
                .user("问题")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "v1-a1"))
                .stream().chatClientResponse()
                .collectList().block(Duration.ofSeconds(10));

        List<Message> msgs = svc.getMessages("v1-a1");
        assertEquals(2, msgs.size(), "user + assistant 都应落库");
        assertInstanceOf(UserMessage.class, msgs.get(0), "首条是本回合 user");
        assertEquals("问题", msgs.get(0).getText(), "before() 把 .user() 文本追加进会话");
        assertEquals(1, model.streamCalls.get());
    }

    /**
     * V1 断言 2：不调 .user()（spec 形状② 续跑轮的 spec 重建方式）——
     * 空文本 UserMessage 落库、出站 instructions 不含它。
     */
    @Test
    void noUserPrompt_persistsBlankUser_outboundExcludesIt() {
        SessionService svc = newService();
        RecordingModel model = new RecordingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(memory(svc))
                .build();

        // 形状②前置：会话里已有上一轮 user + assistant（工具循环中途断流裁剪后的最小合法形状），
        // 续跑轮 client.prompt() 不调 .user()。
        String sid = "v1-a2";
        client.prompt()
                .user("上一轮问题")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .stream().chatClientResponse()
                .collectList().block(Duration.ofSeconds(10));

        client.prompt()   // ⚠ 不调 .user()——这是被测行为
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .stream().chatClientResponse()
                .collectList().block(Duration.ofSeconds(10));

        List<Message> msgs = svc.getMessages(sid);
        assertTrue(msgs.size() >= 3, "上一轮 user/assistant + 本轮空 user + 本轮 assistant");
        Message last = msgs.get(msgs.size() - 1);
        assertInstanceOf(AssistantMessage.class, last, "流完成后 assistant 落库（对照先例断言法）");

        // ① before() 落库空 user：不调 .user() 的那轮在会话里追加了一条 UserMessage 且文本为空。
        //    扫描第二轮落库的 user（跳过首轮的 "上一轮问题"）：文本必须为空串。
        List<UserMessage> users = msgs.stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .toList();
        assertEquals(2, users.size(), "两轮各追加一条 user");
        assertEquals("上一轮问题", users.get(0).getText());
        assertEquals("", users.get(1).getText(),
                "不调 .user() 时 before() 仍落库一条空文本 UserMessage（getLastUserOrToolResponseMessage 兜底）");

        // ② 出站不含空 user：第二轮请求的 instructions 里不存在任何空文本 UserMessage。
        //    ⚠ noneMatch——不能写「最后一条 user 文本为空」：出站根本没有那条消息。
        Prompt outbound = model.prompts.get(1);
        assertTrue(outbound.getInstructions().stream()
                        .noneMatch(m -> m instanceof UserMessage && (m.getText() == null || m.getText().isEmpty())),
                "出站 prompt 不含空 user（历史里的上一轮问题在，但绝不出现空文本 user）");
        assertTrue(outbound.getInstructions().stream()
                        .anyMatch(m -> m instanceof UserMessage && "上一轮问题".equals(m.getText())),
                "出站含会话历史里的上一轮问题（before() 注入的记忆）");
    }

    /**
     * V1 断言 3（负向，实测与 spec §1.2「冷流前提」表述不符）：同一 chatClientResponse() Flux
     * 实例二次订阅抛 {@code IllegalStateException("No StreamAdvisors available to execute")}。
     *
     * <p>机制（spring-ai-client-chat 2.0.0 源码）：{@code DefaultChatClientRequestSpec.stream()}
     * 每次调用 {@code buildAdvisorChain()} 建一条 {@code DefaultAroundAdvisorChain}（内部
     * {@code ConcurrentLinkedDeque}）；{@code nextStream()} 每次 {@code pop()} 一个 advisor
     * ——破坏性消费。同一 Flux（{@code Flux.deferContextual} 本身可重订阅）二次订阅时 deque
     * 已被首次订阅耗尽 → 空 deque → 直接 error。before() 只在首次订阅执行。
     */
    @Test
    void coldFlux_sameInstance_secondSubscribe_fails() {
        SessionService svc = newService();
        RecordingModel model = new RecordingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(memory(svc))
                .build();

        Flux<org.springframework.ai.chat.client.ChatClientResponse> f = client.prompt()
                .user("问题")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "v1-a3"))
                .stream().chatClientResponse();

        f.blockLast(Duration.ofSeconds(10));   // 首次订阅正常
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> f.blockLast(Duration.ofSeconds(10)),
                "同一 Flux 实例二次订阅：advisor 链已耗尽，抛 No StreamAdvisors available to execute");
        assertTrue(ex.getMessage().contains("No StreamAdvisors available"),
                "实测异常文案：" + ex.getMessage());

        assertEquals(1, model.streamCalls.get(), "模型只被调了一次（before/链没有重跑）");
        long userCount = svc.getMessages("v1-a3").stream()
                .filter(m -> m instanceof UserMessage)
                .count();
        assertEquals(1, userCount, "before() 只执行一次：user(\"问题\") 只追加一次");
    }

    /**
     * V1 断言 3（正向，spec §3.3 的实际形态）：defer 内重建 spec + retryWhen 重订阅 ——
     * 每次重订阅重调 {@code .stream()} 建新 advisor 链，before() 每轮重跑。
     *
     * <p>这是 L2 续跑伪码的真实形态（{@code Flux.defer(() -> client.prompt()…stream()…)}
     * 外挂 retryWhen）：外层 defer 流是冷的、可重订阅；每次订阅重建整条链。
     * 用 1 次失败 + 1 次成功模拟 mid-stream 断流后的续跑重订阅。
     */
    @Test
    void deferRebuildsSpec_beforeRerunsPerSubscription() {
        SessionService svc = newService();
        RecordingModel model = new RecordingModel();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(memory(svc))
                .build();

        // defer 内重建 spec（§3.3 形态）；首轮正常完成后 concat 一个 error（模拟 mid-stream 断流），
        // retryWhen 重订阅外层 defer 流 → 第二轮重新调 .stream() 建新链。
        Flux<org.springframework.ai.chat.client.ChatClientResponse> firstFails = Flux.defer(() -> {
            Flux<org.springframework.ai.chat.client.ChatClientResponse> turn = client.prompt()
                    .user("问题")
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "v1-a3b"))
                    .stream().chatClientResponse();
            return model.streamCalls.get() == 0
                    ? turn.concatWith(Flux.error(new RuntimeException("模拟断流")))
                    : turn;
        });

        firstFails.retryWhen(reactor.util.retry.Retry.max(1))
                .blockLast(Duration.ofSeconds(10));

        assertEquals(2, model.streamCalls.get(), "defer 重建 spec：两轮订阅各调一次 .stream()（新链各建一次）");
        List<UserMessage> users = svc.getMessages("v1-a3b").stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> (UserMessage) m)
                .toList();
        assertEquals(2, users.size(), "before() 每轮重跑：user(\"问题\") 被追加两次");
        assertEquals("问题", users.get(0).getText());
        assertEquals("问题", users.get(1).getText());
    }

    /**
     * V3 桩断言：reasoning-only chunk（reasoning 放独立字段、content 缺省）→ {@code getText()}
     * 为 null → spec §3.2 hasContent「text 非空」口径下不计数 → 零下发口径成立。
     *
     * <p>按 spring-ai-deepseek 2.0.0 实测映射构造：{@code reasoning_content} →
     * {@code DeepSeekAssistantMessage.reasoningContent}（独立字段），content 未出现 →
     * {@code getText() == null}。用真实 {@link AssistantMessage}（content=null 合法，
     * ASSISTANT 类型不受非空断言约束）复刻该 chunk 的消息形态。
     */
    @Test
    void v3_reasoningOnlyChunk_hasNoText_zeroEmitCaliber() {
        // content=null 的 AssistantMessage：DeepSeek reasoning-only chunk 经 buildGeneration 的产物形态
        AssistantMessage reasoningOnly = DeepSeekAssistantMessageShaper.reasoningOnly("思考中…");
        assertNull(reasoningOnly.getText(),
                "V3：reasoning-only chunk 的 getText() 为 null（reasoning 在独立字段，不进文本）");
        assertTrue(!hasContent(reasoningOnly),
                "V3：reasoning-only chunk 按 spec §3.2 hasContent（text 非空 或 hasToolCalls）判无内容——零下发计数不被思考流污染");
        assertFalse(reasoningOnly.hasToolCalls());

        // usage-only / finish-only 收尾 chunk 的消息形态同构：content 缺省 → getText() null → 不计数。
        AssistantMessage finishOnly = new AssistantMessage((String) null);
        assertNull(finishOnly.getText());
        assertTrue(!hasContent(finishOnly), "V3：finish-only/usage-only 收尾 chunk 同为无内容");
    }

    /** spec §3.2 hasContent 的消息级口径（text 非空（含纯空白）或 hasToolCalls）。 */
    private static boolean hasContent(AssistantMessage m) {
        return (m.getText() != null && !m.getText().isEmpty()) || m.hasToolCalls();
    }

    /** 构造 reasoning-only chunk 形态的 AssistantMessage（content=null + reasoning 独立字段）。 */
    private static final class DeepSeekAssistantMessageShaper {
        static AssistantMessage reasoningOnly(String reasoning) {
            var builder = org.springframework.ai.deepseek.DeepSeekAssistantMessage.builder();
            builder.content(null);                 // content 字段缺省 → getText() 为 null
            builder.reasoningContent(reasoning);   // 思考流放独立字段，不进文本
            return builder.build();
        }
    }
}
