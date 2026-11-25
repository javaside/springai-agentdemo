package io.github.javaside.springai.agent.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.session.tool.SessionRecallTools;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 示例 5：会话召回——找回被压缩的内容。
 *
 * <p>示例 4 的压缩让旧的回合离开视图，但它们仍在日志里。Spring AI 自带一个会话搜索工具
 * {@link SessionRecallTools}（工具名 {@code conversation_search}）：模型按关键词搜整份日志，把已经不在
 * 上下文里的内容找回来。搜索结果只是一段摘录，只进本轮对话；旧事件不会因此回到视图。
 *
 * <p>跑完记得住的一句话：<b>压缩拿走的是「模型看到的」，不是「存下来的」；模型可以用 conversation_search
 * 自己把旧内容搜回来。</b>
 */
public class SessionRecallDemo implements Demo {

    static final String SECRET_KEYWORD = "海棠";

    static final String SECRET_TURN = "请记住：我的项目暗号是『海棠』。";

    static final String RECALL_QUESTION = "我最早告诉你的项目暗号是什么？（上下文里没有的话，用会话搜索工具查）";

    /** 压缩配置与示例 4 第一幕相同：视图超过 3 个回合就压缩，只留最近 2 个回合。 */
    private static final int MAX_TURNS = 3;

    private static final int KEEP_TURNS = 2;

    /** 第 1 轮记住暗号，再聊 3 轮；第 4 轮后触发压缩，暗号那一轮离开视图。 */
    private static final int SEED_TURNS = 4;

    private final ChatClient chatClient;

    public SessionRecallDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "会话召回：找回被压缩的内容";
    }

    @Override
    public int order() {
        return 5;
    }

    @Override
    public void run() {
        SessionService sessions = SessionDemoSupport.newSessions();
        String sessionId = "demo-recall";

        System.out.println("前 " + SEED_TURNS + " 轮：第 1 轮记住暗号，其余闲聊（不注册工具）；视图超过 "
                + MAX_TURNS + " 个回合就压缩、只留最近 " + KEEP_TURNS + " 个回合。");
        System.out.println("第 " + (SEED_TURNS + 1) + " 轮问暗号，并注册会话搜索工具 conversation_search。\n");

        RecallRun run = seedAndRecall(this.chatClient, sessions, sessionId,
                List.of(new RoundLoggingAdvisor(ToolCallingAdvisor.DEFAULT_ORDER + 100)));

        System.out.println("\n第 5 轮 问：" + RECALL_QUESTION);
        System.out.println("第 5 轮 答：" + run.answer() + "\n");

        System.out.println("第 4 轮压缩后：日志 " + run.logSizeBefore() + " 条 / 视图 " + run.viewSizeBefore()
                + " 条——暗号那一轮已不在视图里。");
        System.out.println("召回之后：日志 " + run.logAfter().size() + " 条 / 视图 " + run.viewAfter().size()
                + " 条。旧事件没有回到视图：进上下文的只是搜索带回的一段摘录，暗号那一轮仍然只在日志里。");
    }

    /** 压缩（3, 2）+ 让会话工具拿得到会话 id 的会话 advisors。 */
    static List<Advisor> recallAdvisors(SessionService sessions) {
        return SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(MAX_TURNS),
                        WindowCompactionStrategy.builder().lastTurns(KEEP_TURNS).build())
                // advisor 把会话 id 放进每次调用的工具上下文：会话搜索工具靠它知道搜哪个会话，
                // 调用方不必每次手写 toolContext
                .sessionIdInToolContext(true)
                .build();
    }

    /**
     * 在同一个会话上：前 4 轮不注册工具，第 5 轮注册会话搜索工具并问暗号。
     *
     * @param extraAdvisors 只并入召回那一问的 advisor 链（比如轮次日志）；前 4 轮不带，免得把轮次编号数进去
     */
    static RecallRun seedAndRecall(ChatClient chatClient, SessionService sessions, String sessionId,
            List<Advisor> extraAdvisors) {
        List<Advisor> advisors = recallAdvisors(sessions);
        for (int i = 1; i <= SEED_TURNS; i++) {
            SessionDemoSupport.ask(chatClient, advisors, sessionId, i == 1 ? SECRET_TURN : "第" + i + "句，随便聊聊。");
        }
        int logSizeBefore = sessions.getEvents(sessionId, EventFilter.all()).size();
        int viewSizeBefore = sessions.getEvents(sessionId, EventFilter.active()).size();

        List<Advisor> recallAdvisors = new ArrayList<>(extraAdvisors);
        recallAdvisors.addAll(advisors);
        String answer = SessionDemoSupport.ask(chatClient, recallAdvisors, sessionId, RECALL_QUESTION,
                SessionRecallTools.builder(sessions).build());

        return new RecallRun(logSizeBefore, viewSizeBefore, answer, sessions.getEvents(sessionId, EventFilter.all()),
                sessions.getEvents(sessionId, EventFilter.active()));
    }

    /** 召回前后的过程数据：第 4 轮压缩后的日志 / 视图条数、召回那一问的回答、召回后的整份日志与视图。 */
    record RecallRun(int logSizeBefore, int viewSizeBefore, String answer, List<SessionEvent> logAfter,
            List<SessionEvent> viewAfter) {
    }

    /**
     * 自定义 CallAdvisor：按「轮次」整齐打印召回那一问里发生的事——本轮可用工具、上一轮的工具返回、
     * 模型本轮的决定。与 {@code ToolSearchDemo}、{@code SkillToolDemo} 里的同名类作用一致：
     * 用 System.out，避免日志框架前缀打乱版面。
     */
    private static final class RoundLoggingAdvisor implements CallAdvisor {

        private final int order;

        private int round = 0;

        RoundLoggingAdvisor(int order) {
            this.order = order;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            int current = ++this.round;
            System.out.println("  ┌─ 第 " + current + " 轮 ──────────────");
            System.out.println("  │ ↗ 本轮模型可用工具: " + availableToolNames(request));
            printToolResult(request);

            ChatClientResponse response = chain.nextCall(request);   // 继续调用链，拿到模型这一轮的响应

            System.out.println("  │ ↘ 模型决定: " + decision(response));
            System.out.println("  └────────────────────────");
            return response;
        }

        @Override
        public String getName() {
            return "RoundLoggingAdvisor";
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        private static String availableToolNames(ChatClientRequest request) {
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions opts) {
                List<String> names = opts.getToolCallbacks().stream()
                        .map(tc -> tc.getToolDefinition().name())
                        .toList();
                if (!names.isEmpty()) {
                    return String.join(", ", names);
                }
            }
            return "（无）";
        }

        /**
         * 工具循环关掉了自带历史，下一轮请求里只带工具结果：所以一轮开始时，请求的最后一条消息就是
         * 上一轮工具原样返回的内容（这里是 conversation_search 返回的 JSON）。
         */
        private static void printToolResult(ChatClientRequest request) {
            List<Message> messages = request.prompt().getInstructions();
            if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof ToolResponseMessage toolResult) {
                String data = toolResult.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .collect(Collectors.joining(" "));
                System.out.println("  │ ↩ 工具返回: " + truncate(clean(data), 160));
            }
        }

        private static String decision(ChatClientResponse response) {
            ChatResponse cr = response.chatResponse();
            if (cr == null || cr.getResult() == null) {
                return "(空响应)";
            }
            AssistantMessage out = cr.getResult().getOutput();
            if (!out.getToolCalls().isEmpty()) {
                String calls = out.getToolCalls().stream()
                        .map(tc -> tc.name() + "(" + clean(tc.arguments()) + ")")
                        .collect(Collectors.joining(", "));
                return "请求调用工具 → " + calls;
            }
            return "直接回答 → " + truncate(clean(out.getText()), 50);
        }

        private static String clean(String s) {
            return s == null ? "" : s.replaceAll("\\s+", " ").trim();
        }

        private static String truncate(String s, int max) {
            return s.length() > max ? s.substring(0, max) + "…" : s;
        }

    }

}
