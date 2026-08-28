package io.github.javaside.springai.codetui.agent.interjection;

import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import io.github.javaside.springai.codetui.agent.CodingAgent;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「回合中插话」的端到端契约：回合<b>正卡在工具里</b>时投递的插话，随<b>下一次</b>模型调用送达，
 * 且落在一个合法位置上；回合末补进会话历史，且补历史发生在通知 listener <b>之前</b>。
 *
 * <p>本类由三个一次性探针改造而来（那三个探针对比了「会话存储层 / advisor 层 / ChatModel 层」
 * 三个注入点该选哪个，结论已写进 spec：只有 ChatModel 层看得到已配平的完整消息表）。
 * 留下的是脚手架——{@link CapturingModel} 的多轮工具循环、{@link #slowTool} 的闸门窗口——
 * 和真正的契约断言。
 *
 * <p>为什么必须是这种「真跑一个工具循环」的重装配：插话的安全性完全取决于它落在消息表的哪个
 * 位置。落在 {@code assistant(tool_calls)} 与 {@code tool} 结果<b>之间</b>就是悬空 tool_calls，
 * 下一次请求直接 400（这个项目踩过一次，见 Esc 中断工具回合）。而「工具结果何时进入消息表」
 * 只有把真正送到 {@link ChatModel} 的 {@link Prompt} 抓下来才看得见。
 */
class MidTurnInjectionTest {

    private static final String SLOW_TOOL = "SlowProbe";
    /** 工具轮数：单轮掩盖了「注入只活一次」的缺陷，必须多轮。 */
    private static final int ROUNDS = 3;

    /** 前 {@link #ROUNDS} 次调用回一个 tool_call、之后回纯文本收尾；每次到达的 Prompt 全部留档。 */
    private static final class CapturingModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override public ChatResponse call(Prompt p) {
            prompts.add(p);
            return reply();
        }

        @Override public Flux<ChatResponse> stream(Prompt p) {
            prompts.add(p);
            return Flux.just(reply());
        }

        private ChatResponse reply() {
            if (calls.getAndIncrement() < ROUNDS) {
                AssistantMessage.ToolCall tc =
                        new AssistantMessage.ToolCall("call-" + calls.get(), "function", SLOW_TOOL, "{}");
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(tc)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("收工"))));
        }

        List<Prompt> awaitPrompts(int n) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (prompts.size() < n && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(prompts.size() >= n,
                    "只等到 " + prompts.size() + " 条 Prompt，期望 " + n + "——工具循环没跑起来");
            return List.copyOf(prompts);
        }
    }

    /** 工具执行时先放行 entered、再卡在 gate 上，给测试线程一个「回合正跑到一半」的窗口。 */
    private static ToolCallback slowTool(CountDownLatch entered, CountDownLatch gate) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(SLOW_TOOL).description("卡住不返回")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String toolInput) {
                entered.countDown();
                try {
                    assertTrue(gate.await(10, TimeUnit.SECONDS), "闸门没放行");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "工具结果";
            }
            @Override public String call(String toolInput, ToolContext ctx) { return call(toolInput); }
        };
    }

    @Test
    @DisplayName("工具循环中途插话，随下一次模型调用送达且位置合法")
    void interjectionArrivesAtNextModelCall() throws Exception {
        SessionService sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CapturingModel model = new CapturingModel();
        Interjections interjections = new Interjections();

        ChatClient client = ChatClient.builder(InterjectingChatModel.wrap(model, interjections))
                .defaultTools((Object) slowTool(entered, gate))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build())
                .build();

        String sid = "interjection-session";
        Thread turn = new Thread(() -> client.prompt().user("原始提问")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .stream().chatClientResponse().blockLast(), "interjection-turn");
        turn.setDaemon(true);
        turn.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "工具没被调用，回合没跑到「中途」");

        interjections.offer("改用方案 B");
        gate.countDown();                       // 放行工具，让循环进入下一次模型调用

        List<Prompt> prompts = model.awaitPrompts(2);
        List<Message> second = prompts.get(1).getInstructions();

        assertInstanceOf(ToolResponseMessage.class, second.get(second.size() - 2),
                "插话前一条必须是 tool 结果——落在 assistant(tool_calls) 与 tool 之间就是 400");
        assertEquals(InterjectingChatModel.wrapText("改用方案 B"),
                second.get(second.size() - 1).getText(),
                "送达的必须是包裹后的文本：注入与补历史共用 wrapText，两处不一致不会报错，"
                        + "只会在 -c 恢复后表现为「历史里的话和模型当时的反应对不上」");
        assertEquals(0, interjections.pendingCount(), "送达后队列应清空");

        turn.join(5000);
    }

    /**
     * 补历史必须排在 {@code listener.onTurnComplete} <b>之前</b>。
     *
     * <p>把 {@code handleComplete} 那两行对调不会报任何错：UI 的兜底出队钩子靠 {@code !busy()} 放行，
     * 而 state 回 IDLE 由 listener 事件驱动——通知在前的话，新回合的
     * {@code SessionMemoryAdvisor.before()} 会先写入新 user，随后按 anchor 插入就错位了。
     * 唯一能钉住这个顺序的断言，就是在 {@code onTurnComplete} 回调里读会话历史，
     * 要求插话<b>此刻已经在里面</b>。
     */
    @Test
    @DisplayName("回合末补历史发生在通知 listener 之前")
    void interjectionPersistedBeforeTurnCompleteNotification() throws Exception {
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService sessions = DefaultSessionService.builder().sessionRepository(repo).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CapturingModel model = new CapturingModel();
        Interjections interjections = new Interjections();
        String sid = "persist-order-session";

        ChatClient client = ChatClient.builder(InterjectingChatModel.wrap(model, interjections))
                .defaultTools((Object) slowTool(entered, gate))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build())
                .build();

        // onTurnComplete 那一刻的会话历史快照。断言放到回调之外——回调里抛异常会被 reactive 链吞掉。
        AtomicReference<List<String>> historyAtNotify = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AgentListener listener = new StubListener() {
            @Override public void onTurnComplete(long turnId) {
                historyAtNotify.set(sessions.getMessages(sid).stream()
                        .map(m -> m.getText() == null ? "" : m.getText()).toList());
                done.countDown();
            }
        };

        CodingAgent agent = new CodingAgent(
                new ProviderRegistry(List.of(new DeepSeekProvider("k"))), Map.of("deepseek", client),
                listener, sid, new AtomicLong(),
                sessions, null, null, List.of(), null, repo,
                null, null, null, null, null, null, null, null, interjections);

        agent.submit("原始提问");

        assertTrue(entered.await(10, TimeUnit.SECONDS), "工具没被调用，回合没跑到「中途」");
        agent.interject("改用方案 B");
        gate.countDown();

        assertTrue(done.await(15, TimeUnit.SECONDS), "回合没完成，onTurnComplete 没被调用");

        List<String> history = historyAtNotify.get();
        assertNotNull(history, "没抓到 onTurnComplete 那一刻的历史");
        assertTrue(history.contains(InterjectingChatModel.wrapText("改用方案 B")),
                "通知 listener 时插话还没进历史 → persistInterjection 排到了 onTurnComplete 之后。实际历史：" + history);
    }
}
