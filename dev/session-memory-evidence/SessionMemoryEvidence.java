import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.ChatModelSummarizer;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.session.tool.SessionRecallTools;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Spring AI 2.1.0 Session Memory 取证程序（设计稿《Session Memory 教学示例》附录 A 的实测依据）。
 *
 * <p>不属于任何模块的构建，独立编译运行；不联网、不需要 API key：用「脚本化模型」按 prompt 的
 * 最后一条消息决定回什么，实测五件事——
 * ① 多轮对话里日志 / 视图的条数如何随压缩变化；② 带工具调用的一轮怎样入日志、压缩会不会拆开它；
 * ③ 被压缩覆盖的内容能否被召回；④ 带摘要的压缩在日志里留下什么；⑤ 不压缩、只用 lastN 窗口时模型看到什么。
 * 末尾复现四个会咬人的坑。
 *
 * <p>复跑（在仓库根目录）：
 * <pre>{@code
 * mvn -q -pl springai-agent-demo -am package -DskipTests
 * mvn -q -pl springai-agent-demo dependency:build-classpath -Dmdep.outputFile=/tmp/agent-cp.txt
 * mkdir -p /tmp/session-evidence
 * javac --release 17 -encoding UTF-8 -cp "$(cat /tmp/agent-cp.txt)" -d /tmp/session-evidence \
 *     dev/session-memory-evidence/SessionMemoryEvidence.java
 * java -Dfile.encoding=UTF-8 -cp "/tmp/session-evidence:$(cat /tmp/agent-cp.txt)" SessionMemoryEvidence
 * }</pre>
 * session 两个坐标进 agent-demo 的 pom 之前，classpath 需再追加 ~/.m2 里的
 * spring-ai-session-2.1.0.jar 与 spring-ai-session-advisor-2.1.0.jar。
 */
public class SessionMemoryEvidence {

    static final AtomicInteger TOOL_CALL_IDS = new AtomicInteger();

    public static void main(String[] args) {
        plainTurns();
        toolTurn();
        recall();
        summary();
        lastNWindow();
        pitfalls();
    }

    /** ① 5 轮纯问答：TurnCountTrigger(3) + lastTurns(2)。 */
    static void plainTurns() {
        System.out.println("\n===== ① 多轮对话 + 压缩（trigger: 回合数 > 3，strategy: 保留最近 2 个回合） =====");
        SessionService sessions = newService();
        List<Advisor> advisors = compacting(sessions, 3, 2);
        ScriptedModel model = new ScriptedModel(p -> text("回声：" + lastUserText(p)), true);
        ChatClient client = ChatClient.builder(model).build();
        String sid = "session-plain";
        for (int i = 1; i <= 5; i++) {
            client.prompt().user("第" + i + "句").advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
            dump(sessions, sid, "第 " + i + " 轮结束后", model.seen.get(model.seen.size() - 1));
        }
    }

    /** ② 第 2 轮调用工具；TurnCountTrigger(1) + lastTurns(1)：压缩后视图应恰好是整个工具回合。 */
    static void toolTurn() {
        System.out.println("\n===== ② 带工具调用的一轮（trigger: 回合数 > 1，strategy: 保留最近 1 个回合） =====");
        SessionService sessions = newService();
        List<Advisor> advisors = compacting(sessions, 1, 1);
        ScriptedModel model = new ScriptedModel(p -> {
            if (isToolResult(p)) {
                return text("北京今天晴。");
            }
            return lastUserText(p).contains("天气") ? toolCall("getWeather", "{\"city\":\"北京\"}") : text("你好！");
        }, true);
        ChatClient client = ChatClient.builder(model).build();
        String sid = "session-tool";
        client.prompt().user("你好").tools(new Weather()).advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
        dump(sessions, sid, "第 1 轮结束后", model.seen.get(model.seen.size() - 1));
        client.prompt().user("北京天气怎么样？").tools(new Weather()).advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
        model.seen.forEach(s -> System.out.println("  模型每轮收到的消息类型: " + s));
        dump(sessions, sid, "第 2 轮结束后（已压缩）", model.seen.get(model.seen.size() - 1));
    }

    /** ③ 压缩覆盖第 1 轮后，让模型用 conversation_search 把暗号找回来。 */
    static void recall() {
        System.out.println("\n===== ③ 召回（conversation_search 找回已被压缩覆盖的第 1 轮） =====");
        SessionService sessions = newService();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(3), WindowCompactionStrategy.builder().lastTurns(2).build())
                .sessionIdInToolContext(true)
                .build();
        ScriptedModel model = new ScriptedModel(p -> {
            if (isToolResult(p)) {
                return text("你最早说的是：" + firstToolResult(p));
            }
            String last = lastUserText(p);
            return last.contains("暗号是什么") ? toolCall("conversation_search", "{\"query\":\"暗号\"}") : text("回声：" + last);
        }, true);
        ChatClient client = ChatClient.builder(model).build();
        String sid = "session-recall";
        for (int i = 1; i <= 4; i++) {
            client.prompt().user(i == 1 ? "第1句，暗号是「海棠」" : "第" + i + "句").tools(SessionRecallTools.builder(sessions).build())
                    .advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
        }
        dump(sessions, sid, "4 轮后（已压缩）", model.seen.get(model.seen.size() - 1));
        System.out.println("  searchEvents(query=暗号) 命中: " + sessions.searchEvents(sid, EventQuery.keyword("暗号")).stream()
                .map(e -> e.getMessageType().getValue() + ":" + clip(e.getMessage().getText())).toList());
        String answer = client.prompt().user("我最早说的暗号是什么？").tools(SessionRecallTools.builder(sessions).build())
                .advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
        System.out.println("  最终回答: " + clip(answer));
        model.seen.forEach(s -> System.out.println("  模型每轮收到的消息类型: " + s));
        dump(sessions, sid, "召回那一轮结束后", model.seen.get(model.seen.size() - 1));
    }

    /** ④ 给窗口压缩配一个摘要器：被覆盖的回合以一条 synthetic 摘要回合留在视图里。 */
    static void summary() {
        System.out.println("\n===== ④ 带摘要的压缩（trigger: 回合数 > 2，strategy: 保留最近 1 个回合 + summarizer） =====");
        SessionService sessions = newService();
        ScriptedModel summarizerModel = new ScriptedModel(p -> text("【摘要】用户说了第1句和第2句。"), true);
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(2), WindowCompactionStrategy.builder().lastTurns(1)
                        .summarizer(ChatModelSummarizer.builder(summarizerModel).build()).build())
                .build();
        ScriptedModel model = new ScriptedModel(p -> text("回声：" + lastUserText(p)), true);
        ChatClient client = ChatClient.builder(model).build();
        String sid = "session-summary";
        for (int i = 1; i <= 3; i++) {
            client.prompt().user("第" + i + "句").advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
            dump(sessions, sid, "第 " + i + " 轮结束后", model.seen.get(model.seen.size() - 1));
        }
        System.out.println("  摘要模型收到的消息类型: " + summarizerModel.seen);
        System.out.println("  压缩后视图 = 日志的第 " + viewPositions(sessions, sid) + " 条（摘要回合在前，保留回合在后）");
    }

    /** ⑤ lastN 窗口：不压缩也能限制模型看到的历史，日志照常全量保存；窗口落在回合中间时向前扩到回合起点。 */
    static void lastNWindow() {
        System.out.println("\n===== ⑤ lastN 窗口（不做压缩，记忆 advisor 只读活跃视图里最近的 4 条） =====");
        SessionService sessions = newService();
        List<Advisor> advisors = SessionAdvisors.builder(sessions)
                .memory(memory -> memory.eventFilter(EventFilter.active().toBuilder().lastN(4).build()))
                .build();
        ScriptedModel model = new ScriptedModel(p -> text("回声：" + lastUserText(p)), true);
        ChatClient client = ChatClient.builder(model).build();
        String sid = "session-last-n";
        for (int i = 1; i <= 5; i++) {
            client.prompt().user("第" + i + "句").advisors(advisors).advisors(SessionAdvisors.session(sid)).call().content();
            System.out.println("  第 " + i + " 轮：模型收到 " + model.seen.get(model.seen.size() - 1).size() + " 条，轮后日志 "
                    + sessions.getEvents(sid, EventFilter.all()).size() + " 条 / 视图 "
                    + sessions.getEvents(sid, EventFilter.active()).size() + " 条");
        }
        System.out.println("  直接读活跃视图 lastN(4)：日志里的第 " + positions(sessions, sid, 4) + " 条");
        System.out.println("  直接读活跃视图 lastN(3)：日志里的第 " + positions(sessions, sid, 3) + " 条（落在回合中间，向前扩到回合起点）");
    }

    /** 活跃视图里的事件在整份日志里各是第几条（按视图内的先后）。 */
    static List<Integer> viewPositions(SessionService sessions, String sid) {
        List<String> logIds = sessions.getEvents(sid, EventFilter.all()).stream().map(SessionEvent::getId).toList();
        return sessions.getEvents(sid, EventFilter.active()).stream().map(e -> logIds.indexOf(e.getId()) + 1).toList();
    }

    /** 这些事件在整份日志里各是第几条。 */
    static List<Integer> positions(SessionService sessions, String sid, int n) {
        List<String> logIds = sessions.getEvents(sid, EventFilter.all()).stream().map(SessionEvent::getId).toList();
        return sessions.getEvents(sid, EventFilter.active().toBuilder().lastN(n).build()).stream()
                .map(e -> logIds.indexOf(e.getId()) + 1)
                .toList();
    }

    /** 复现三个坑：写测试 / 示例时最容易撞上，现象都不直观。 */
    static void pitfalls() {
        System.out.println("\n===== 坑位复现 =====");
        // 坑 1：桩模型的 getOptions() 返回普通 ChatOptions → .tools(...) 被静默丢弃，工具循环根本不启动
        boolean[] toolRan = { false };
        List<Advisor> advisors = SessionAdvisors.builder(newService()).build();
        ScriptedModel plainOptionsModel = new ScriptedModel(p -> toolCall("getWeather", "{\"city\":\"北京\"}"), false);
        ChatClient.builder(plainOptionsModel).build().prompt().user("北京天气怎么样？")
                .tools(new Weather(() -> toolRan[0] = true))
                .advisors(advisors).advisors(SessionAdvisors.session("s-pitfall-1")).call().content();
        System.out.println("  [坑1] 桩模型 getOptions() 返回普通 ChatOptions：工具是否执行 = " + toolRan[0]
                + "，模型被调用次数 = " + plainOptionsModel.seen.size() + "（正常应为 2）");

        // 坑 2：CallResponseSpec 的每个取值方法都会重走一遍 advisor 链，而链是有状态的——同一个 spec 取两次：
        //       模型被重复调用、已弹出的 advisor 不再生效，链被弹空时直接抛异常
        ScriptedModel counted = new ScriptedModel(p -> text("好"), true);
        ChatClient.CallResponseSpec spec = ChatClient.builder(counted).build().prompt().user("你好").call();
        spec.chatClientResponse();
        try {
            spec.content();
            System.out.println("  [坑2] 同一个 CallResponseSpec 取第二次：未报错，但模型被调用次数 = " + counted.seen.size()
                    + "（应为 1）");
        }
        catch (IllegalStateException e) {
            System.out.println("  [坑2] 同一个 CallResponseSpec 取第二次 → " + e);
        }
        // 工具循环直通时（如坑 1 的普通 ChatOptions）整条链在一次取值里被弹空，第二次取值就抛了
        ScriptedModel passThrough = new ScriptedModel(p -> toolCall("getWeather", "{\"city\":\"北京\"}"), false);
        ChatClient.CallResponseSpec toolSpec = ChatClient.builder(passThrough).build().prompt().user("北京天气怎么样？")
                .tools(new Weather())
                .advisors(SessionAdvisors.builder(newService()).build()).advisors(SessionAdvisors.session("s-pitfall-2")).call();
        toolSpec.chatClientResponse();
        try {
            toolSpec.content();
            System.out.println("  [坑2b] 带工具循环的回合重取：未报错");
        }
        catch (IllegalStateException e) {
            System.out.println("  [坑2b] 带工具循环的回合重取 → " + e);
        }

        // 坑 3：模型请求了没注册的工具 → ToolCallingAdvisor 总是自动注册，异常直接冒到调用方
        try {
            ChatClient.builder(new ScriptedModel(p -> toolCall("getWeather", "{\"city\":\"北京\"}"), true)).build()
                    .prompt().user("北京天气怎么样？").call().content();
            System.out.println("  [坑3] 请求未注册的工具：未报错");
        }
        catch (IllegalStateException e) {
            System.out.println("  [坑3] 请求未注册的工具 → " + e);
        }

        // 坑 4：对不带消息的事件（COMPACTION 边界）调 getMessage() / getMessageType() 会抛异常——必须先判 hasMessage()
        SessionEvent boundary = SessionEvent.builder().id("boundary").sessionId("s-pitfall-4")
                .type(EventType.COMPACTION).build();
        try {
            boundary.getMessageType();
            System.out.println("  [坑4] 对 COMPACTION 事件调 getMessageType()：未报错");
        }
        catch (IllegalStateException e) {
            System.out.println("  [坑4] 对 COMPACTION 事件调 getMessageType() → " + e);
        }
    }

    // ---------- 组装与打印 ----------

    static SessionService newService() {
        return DefaultSessionService.builder().sessionRepository(InMemorySessionRepository.builder().build()).build();
    }

    /** 「视图超过 maxTurns 个回合就压缩，只保留最近 keepTurns 个回合」。 */
    static List<Advisor> compacting(SessionService sessions, int maxTurns, int keepTurns) {
        return SessionAdvisors.builder(sessions)
                .compaction(new TurnCountTrigger(maxTurns), WindowCompactionStrategy.builder().lastTurns(keepTurns).build())
                .build();
    }

    /** 打印「日志 N 条 / 视图 M 条 / 模型本轮收到几条」，再逐条列出日志并标出哪些在视图里。 */
    static void dump(SessionService sessions, String sid, String label, List<String> promptSeen) {
        List<SessionEvent> log = sessions.getEvents(sid, EventFilter.all());
        List<SessionEvent> view = sessions.getEvents(sid, EventFilter.active());
        List<String> viewIds = view.stream().map(SessionEvent::getId).toList();
        System.out.println("── " + label + "：日志 " + log.size() + " 条 / 视图 " + view.size() + " 条 / 模型本轮收到 "
                + promptSeen.size() + " 条 " + promptSeen);
        for (int i = 0; i < log.size(); i++) {
            SessionEvent e = log.get(i);
            String role = e.hasMessage() ? e.getMessageType().getValue() : "(无消息)";
            String body = !e.hasMessage() ? "-" : e.hasToolCalls()
                    ? "🔧" + ((AssistantMessage) e.getMessage()).getToolCalls().stream()
                            .map(tc -> tc.name() + tc.arguments()).toList()
                    : clip(e.getMessage().getText());
            System.out.printf("   [%2d] %-10s %-9s turnStart=%-5s synthetic=%-5s %-12s %s%n", i + 1, e.getType(), role,
                    e.isTurnStart(), e.isSynthetic(), viewIds.contains(e.getId()) ? "✓ 在视图" : "  已离开视图", body);
        }
    }

    // ---------- 脚本化模型 ----------

    static String lastUserText(Prompt p) {
        String last = "";
        for (Message m : p.getInstructions()) {
            if ("user".equals(m.getMessageType().getValue())) {
                last = m.getText();
            }
        }
        return last;
    }

    static boolean isToolResult(Prompt p) {
        List<Message> msgs = p.getInstructions();
        return !msgs.isEmpty() && msgs.get(msgs.size() - 1) instanceof ToolResponseMessage;
    }

    static String firstToolResult(Prompt p) {
        for (Message m : p.getInstructions()) {
            if (m instanceof ToolResponseMessage tr) {
                return tr.getResponses().get(0).responseData();
            }
        }
        return "";
    }

    static String clip(String s) {
        return s == null ? "(null)" : s.length() > 44 ? s.substring(0, 44) + "…" : s;
    }

    static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + TOOL_CALL_IDS.incrementAndGet(), "function",
                        name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    /** 与 springai-agent-demo 的 WeatherTools 同名的工具，便于和示例对上号；onCall 用来断言它是否真被执行。 */
    public static class Weather {

        private final Runnable onCall;

        public Weather(Runnable onCall) {
            this.onCall = onCall;
        }

        public Weather() {
            this(() -> {
            });
        }

        @Tool(description = "查询某个城市当前的天气情况")
        public String getWeather(@ToolParam(description = "城市名称，例如 北京") String city) {
            this.onCall.run();
            return city + "：晴，26℃，微风";
        }

    }

    /**
     * 脚本化模型：不联网，按脚本决定每次回什么。
     *
     * <p>{@code getOptions()} 必须返回 {@link ToolCallingChatOptions}：ChatClient 是从
     * {@code chatModel.getOptions().mutate()} 起手装工具的，返回普通 ChatOptions 时 {@code .tools(...)}
     * 被静默丢弃（见 {@link #pitfalls()} 坑 1）。
     */
    static final class ScriptedModel implements ChatModel {

        /** 每次被调用时收到的消息类型序列，用来核对「模型看到的是视图」。 */
        final List<List<String>> seen = new CopyOnWriteArrayList<>();

        private final Function<Prompt, ChatResponse> script;

        private final boolean toolCapableOptions;

        ScriptedModel(Function<Prompt, ChatResponse> script, boolean toolCapableOptions) {
            this.script = script;
            this.toolCapableOptions = toolCapableOptions;
        }

        @Override
        public ChatOptions getOptions() {
            return this.toolCapableOptions ? ToolCallingChatOptions.builder().build() : ChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.seen.add(prompt.getInstructions().stream().map(m -> m.getMessageType().getValue()).toList());
            return this.script.apply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

    }

}
