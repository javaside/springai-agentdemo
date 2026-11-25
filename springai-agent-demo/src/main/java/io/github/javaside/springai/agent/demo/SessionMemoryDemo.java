package io.github.javaside.springai.agent.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 示例 3：会话记忆（Session Memory）—— 会话是一份只追加的事件日志。
 *
 * <p>大模型无状态，多轮对话要把历史一起发给它。Spring AI 2.1 的现行做法是 <b>Session</b>：每个会话
 * （sessionId）对应一份只追加的事件日志（{@link SessionEvent}，每条带 id / 时间戳 / 事件类型 / 消息类型），
 * 模型看到的是这份日志的「视图」。{@link SessionAdvisors} 一次建好两个 advisor：
 * {@code SessionTurnAdvisor} 包住整个回合（回合结束后做压缩，本示例先不配），
 * {@code SessionMemoryAdvisor} 每一轮把视图拼进 prompt，再把新的问与答追加进日志。
 *
 * <p>和示例 2（ChatMemory，已弃用）对照：ChatMemory 是「每个会话一个 {@code List<Message>}，窗口一淘汰就没了」；
 * Session 是「每个会话一个 {@code List<SessionEvent>}，每条带 id / 时间 / 类型，只会追加」。两边用同一个两轮场景。
 *
 * <p>跑完记得住的一句话：<b>会话 = 追加式事件日志；记忆 advisor 每次请求把日志的视图拼进 prompt，
 * 再把新的问与答追加进去。</b>
 */
public class SessionMemoryDemo implements Demo {

    static final String FIRST_QUESTION = "我最喜欢的编程语言是 Java，请记住。";

    static final String SECOND_QUESTION = "我刚才说我最喜欢的编程语言是什么？";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final ChatClient chatClient;

    public SessionMemoryDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "会话记忆（Session Memory：事件日志）";
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void run() {
        String sessionId = "user-1";
        ZoneId zone = ZoneId.systemDefault();

        TwoTurnRun run = runTwoTurns(this.chatClient, SessionDemoSupport.newSessions(), sessionId);

        System.out.println("第1轮 问：" + FIRST_QUESTION);
        System.out.println("第1轮 答：" + run.firstAnswer() + "\n");

        System.out.println("第 2 轮请求会带上这段历史（" + run.historyBeforeSecondTurn().size()
                + " 条，模型看到的就是它们）：");
        printEvents(run.historyBeforeSecondTurn(), zone);
        System.out.println();

        System.out.println("第2轮 问：" + SECOND_QUESTION);
        System.out.println("第2轮 答：" + run.secondAnswer() + "\n");

        System.out.println("会话 " + sessionId + " 的事件日志（" + run.log().size() + " 条，只追加、不修改）：");
        printEvents(run.log(), zone);
        System.out.println("每条事件自带：id（UUID，上面显示前 8 位）、所属会话、时间戳、事件类型（MESSAGE）、"
                + "消息类型（user / assistant / tool）");
    }

    private static void printEvents(List<SessionEvent> events, ZoneId zone) {
        for (int i = 0; i < events.size(); i++) {
            System.out.println("  " + describeEvent(i + 1, events.get(i), zone));
        }
    }

    /** 跑同一个会话上的两轮对话，返回两轮的回答、第 2 轮发出前的活跃视图、两轮后的整份日志。 */
    static TwoTurnRun runTwoTurns(ChatClient chatClient, SessionService sessions, String sessionId) {
        List<Advisor> sessionAdvisors = SessionAdvisors.builder(sessions).build();
        String firstAnswer = SessionDemoSupport.ask(chatClient, sessionAdvisors, sessionId, FIRST_QUESTION);
        // 记忆 advisor 读的正是活跃视图：这里读到几条，第 2 轮请求就带上几条历史
        List<SessionEvent> historyBeforeSecondTurn = sessions.getEvents(sessionId, EventFilter.active());
        String secondAnswer = SessionDemoSupport.ask(chatClient, sessionAdvisors, sessionId, SECOND_QUESTION);
        return new TwoTurnRun(firstAnswer, secondAnswer, historyBeforeSecondTurn,
                sessions.getEvents(sessionId, EventFilter.all()));
    }

    /** 把一条事件渲染成日志里的一行：序号、角色、id 前 8 位、时间、正文。 */
    static String describeEvent(int index, SessionEvent event, ZoneId zone) {
        String time = TIME.format(event.getTimestamp().atZone(zone));
        String id = event.getId().length() > 8 ? event.getId().substring(0, 8) : event.getId();
        if (!event.hasMessage()) {
            // 压缩边界这类事件不带消息：对它调 getMessage() / getMessageType() 会抛 IllegalStateException
            return String.format("#%d  %-9s  %-8s  %s", index, event.getType(), id, time);
        }
        return String.format("#%d  %-9s  %-8s  %s  %s", index, event.getMessageType().getValue(), id, time,
                body(event));
    }

    private static String body(SessionEvent event) {
        return event.getMessage().getText();
    }

    /** 两轮对话的过程数据：两轮的回答、第 2 轮发出前活跃视图里的事件、两轮后的整份日志。 */
    record TwoTurnRun(String firstAnswer, String secondAnswer, List<SessionEvent> historyBeforeSecondTurn,
            List<SessionEvent> log) {
    }

}
