package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>探针，不是功能</b>：回合跑到一半时往会话存储里追加一条 user 消息，下一次模型调用
 * 收到的<b>消息序</b>究竟长什么样。
 *
 * <p>问这个是因为「回合中途注入用户消息」这个需求的安全性完全取决于它：
 *
 * <ul>
 *   <li>若工具循环期间 {@code assistant(tool_calls)} 与对应 {@code tool} 结果<b>尚未落库</b>，
 *       注入就落在原 user 后面，成两条连续 user——安全。</li>
 *   <li>若<b>每迭代都落库</b>，注入可能插进 {@code assistant(tool_calls)} 与 {@code tool} 之间，
 *       造出悬空 tool_calls，下一次请求直接 400。这个项目踩过一次（Esc 中断工具回合）。</li>
 * </ul>
 *
 * <p>字节码只能证明 {@code SessionMemoryAdvisor.before()} 每次都 {@code getEvents}，
 * 证明不了写入时机。只有把真正送到 {@link ChatModel} 的 {@link Prompt} 抓下来才算数。
 */
class MidTurnInjectionProbeTest {

    private static final String SLOW_TOOL = "SlowProbe";
    /** 工具轮数：单轮探针掩盖了「注入只活一次」的缺陷，必须多轮。 */
    private static final int ROUNDS = 3;

    /** 第一次调用回一个 tool_call，之后回纯文本收尾；每次到达的 Prompt 全部留档。 */
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
    @DisplayName("探针：工具卡住时 appendMessage，看下一次调用的消息序")
    void probeInjectionOrdering(@TempDir Path root) throws Exception {
        SessionService sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CapturingModel model = new CapturingModel();

        ChatClient client = ChatClient.builder(model)
                .defaultTools((Object) slowTool(entered, gate))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build())
                .build();

        String sid = "probe-session";
        Thread turn = new Thread(() -> client.prompt().user("原始提问")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .stream().chatClientResponse().blockLast(), "probe-turn");
        turn.setDaemon(true);
        turn.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "工具没被调用，探针无从观测");

        // ── 回合正卡在工具里。此刻会话存储里有什么？ ──
        List<Message> midTurn = sessions.getMessages(sid);
        System.out.println("### 回合中途，存储里的消息序：");
        for (Message m : midTurn) {
            System.out.println("###   " + describe(m));
        }

        // ── 注入 ──
        sessions.appendMessage(sid, new UserMessage("插播：改用方案 B"));
        gate.countDown();

        // ── 下一次模型调用真正收到了什么 ──
        List<Prompt> prompts = model.awaitPrompts(2);
        System.out.println("### 第二次调用送给模型的消息序：");
        for (Message m : prompts.get(1).getInstructions()) {
            System.out.println("###   " + describe(m));
        }
        turn.join(5000);
    }


    /**
     * 探针二：把注入推迟到「下一次模型调用的入口」——此时工具结果已落库，注入自然排在它后面。
     *
     * <p>挂点用一个 order 比 {@link SessionMemoryAdvisor}（+1000）<b>更靠前</b>的 advisor：
     * 它的 before() 先跑，把待注入消息落库，随后 memoryAdvisor 读事件时就能读到，且位置合法。
     */
    @Test
    @DisplayName("探针二：推迟到模型调用入口再落库，消息序是否合法")
    void probeDeferredInjection(@TempDir Path root) throws Exception {
        SessionService sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CapturingModel model = new CapturingModel();
        java.util.Queue<String> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        String sid = "probe-session-2";

        // 待注入队列的排空点：每次模型调用前跑一次。
        org.springframework.ai.chat.client.advisor.api.CallAdvisor drain =
                new org.springframework.ai.chat.client.advisor.api.CallAdvisor() {
            @Override public String getName() { return "drain"; }
            @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 999; }
            @Override public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
                    org.springframework.ai.chat.client.ChatClientRequest req,
                    org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
                for (String t = pending.poll(); t != null; t = pending.poll()) {
                    sessions.appendMessage(sid, new UserMessage(t));
                }
                return chain.nextCall(req);
            }
        };

        ChatClient client = ChatClient.builder(model)
                .defaultTools((Object) slowTool(entered, gate))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build(), drain)
                .build();

        Thread turn = new Thread(() -> client.prompt().user("原始提问")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .call().chatClientResponse(), "probe-turn-2");
        turn.setDaemon(true);
        turn.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "工具没被调用");
        pending.add("插播：改用方案 B");     // 回合正卡在工具里，只进队列、不落库
        gate.countDown();

        List<Prompt> prompts = model.awaitPrompts(2);
        System.out.println("### 探针二 · 第二次调用送给模型的消息序：");
        for (Message m : prompts.get(1).getInstructions()) {
            System.out.println("###   " + describe(m));
        }
        turn.join(5000);
    }


    /**
     * 探针三：注入点挂到 <b>ChatModel 层</b>——那里看到的是完整的、已含工具结果的消息列表，
     * 把待注入消息<b>追加到末尾</b>即可，位置天然合法。
     *
     * <p>前两个探针都把注入落在了工具结果<b>之前</b>（会话存储层看不到「工具结果何时落库」），
     * 那个位置非法；非法的是位置，不是「中途注入」本身。ChatModel 层没有这个盲区，
     * 而且这个项目已经有 {@code RetryingChatModel} 在这一层装饰的先例。
     */
    @Test
    @DisplayName("探针三：在 ChatModel 层把插话追加到消息末尾")
    void probeInjectAtModelLayer(@TempDir Path root) throws Exception {
        SessionService sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build()).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        java.util.Queue<String> pending = new java.util.concurrent.ConcurrentLinkedQueue<>();
        CapturingModel inner = new CapturingModel();

        // 装饰层：每次真正发往模型之前，把待注入消息追加到消息列表末尾。
        ChatModel injecting = new ChatModel() {
            @Override public ChatOptions getOptions() { return inner.getOptions(); }
            @Override public ChatResponse call(Prompt p) { return inner.call(inject(p)); }
            @Override public Flux<ChatResponse> stream(Prompt p) { return inner.stream(inject(p)); }
            private Prompt inject(Prompt p) {
                if (pending.isEmpty()) return p;
                List<Message> msgs = new java.util.ArrayList<>(p.getInstructions());
                for (String t = pending.poll(); t != null; t = pending.poll()) {
                    msgs.add(new UserMessage(t));
                }
                return new Prompt(msgs, p.getOptions());
            }
        };

        ChatClient client = ChatClient.builder(injecting)
                .defaultTools((Object) slowTool(entered, gate))
                .defaultAdvisors(SessionMemoryAdvisor.builder(sessions).defaultUserId("u").build())
                .build();

        String sid = "probe-session-3";
        Thread turn = new Thread(() -> client.prompt().user("原始提问")
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sid))
                .call().chatClientResponse(), "probe-turn-3");
        turn.setDaemon(true);
        turn.start();

        assertTrue(entered.await(10, TimeUnit.SECONDS), "工具没被调用");
        pending.add("插播：改用方案 B");
        gate.countDown();

        List<Prompt> prompts = inner.awaitPrompts(ROUNDS + 1);
        for (int i = 1; i < prompts.size(); i++) {
            System.out.println("### 探针三 · 第 " + (i + 1) + " 次调用的消息序：");
            for (Message m : prompts.get(i).getInstructions()) {
                System.out.println("###   " + describe(m));
            }
        }
        turn.join(5000);

        System.out.println("### 探针三 · 回合结束后会话存储里的消息序：");
        for (Message m : sessions.getMessages(sid)) {
            System.out.println("###   " + describe(m));
        }
    }

    private static String describe(Message m) {
        String role = m.getMessageType().getValue();
        String extra = "";
        if (m instanceof AssistantMessage am && am.hasToolCalls()) {
            extra = " tool_calls=" + am.getToolCalls().size();
        }
        String text = m.getText() == null ? "" : m.getText();
        if (text.length() > 40) {
            text = text.substring(0, 40) + "…";
        }
        return role + extra + " | " + text.replace("\n", "\\n");
    }
}
