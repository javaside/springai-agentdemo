package io.github.javaside.springai.agent.demo;

import io.github.javaside.springai.agent.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.EventType;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 示例 4：会话压缩——日志与视图。
 *
 * <p>会话是只追加的事件日志，聊得越久日志越长，而模型的上下文窗口有限。Session 的做法不是删旧消息，而是
 * <b>压缩</b>：一轮结束后，如果视图里的回合数超过阈值（{@link TurnCountTrigger}），就按策略
 * （{@link WindowCompactionStrategy}，保留最近几个回合）算出哪些回合离开视图，然后<b>往日志末尾追加一条
 * COMPACTION 边界事件</b>，记下从这一刻起视图由哪些事件组成。日志里原有的事件一个都不改。
 *
 * <p>跑完记得住的一句话：<b>压缩不改写日志，只追加一条边界；此后模型看到的是边界之后的视图，
 * 且边界永远落在回合之间。</b>
 *
 * <p>第一幕用 5 轮短问答看「日志只增、视图缩回」；第二幕让一轮里带上工具调用，看压缩不会把
 * 「请求调用工具 → 工具返回」拆开（一个回合 = 一条用户消息及其后的全部内容）。
 */
public class SessionCompactionDemo implements Demo {

    /** 第一幕的压缩配置：视图超过 3 个回合就压缩，只留最近 2 个回合。 */
    private static final int PLAIN_MAX_TURNS = 3;

    private static final int PLAIN_KEEP_TURNS = 2;

    private static final int PLAIN_TURNS = 5;

    /** 一轮纯问答固定追加 2 条事件（一问一答）。 */
    private static final int EVENTS_PER_PLAIN_TURN = 2;

    private final ChatClient chatClient;

    public SessionCompactionDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "会话压缩：日志与视图";
    }

    @Override
    public int order() {
        return 4;
    }

    @Override
    public void run() {
        ZoneId zone = ZoneId.systemDefault();

        System.out.println("第一幕：5 轮短问答。视图超过 " + PLAIN_MAX_TURNS + " 个回合就压缩，只留最近 "
                + PLAIN_KEEP_TURNS + " 个回合。\n");
        SessionService sessions = SessionDemoSupport.newSessions();
        String sessionId = "demo-compaction";
        List<TurnStat> stats = playPlainTurns(this.chatClient, sessions, sessionId, PLAIN_TURNS);

        System.out.println("轮次   日志   视图");
        for (int i = 0; i < stats.size(); i++) {
            System.out.println("  " + (i + 1) + "      " + stats.get(i).logSize() + "      "
                    + stats.get(i).viewSize() + note(stats, i));
        }

        List<SessionEvent> log = sessions.getEvents(sessionId, EventFilter.all());
        List<SessionEvent> view = sessions.getEvents(sessionId, EventFilter.active());
        System.out.println("\n事件日志（" + log.size() + " 条，只增不减）——✓ 还在视图里，✗ 已被压缩覆盖：");
        renderLog(log, view, zone).forEach(System.out::println);

        System.out.println("\n第二幕：另一段会话，第 2 轮调用工具。视图超过 1 个回合就压缩，只留最近 1 个回合。\n");
        ToolTurnRun toolTurn = playToolTurns(this.chatClient, SessionDemoSupport.newSessions(), "demo-tool-turn");
        renderLog(toolTurn.log(), toolTurn.view(), zone).forEach(System.out::println);
        System.out.println("压缩后的视图恰好是整个工具回合（" + toolTurn.view().size()
                + " 条）：压缩按整回合切，不会让模型看到一个没有结果的工具调用。");
    }

    /** 条数表每一行后面的说明：哪一轮之后触发了压缩、下一轮模型实际拿到了多少历史。 */
    private static String note(List<TurnStat> stats, int i) {
        if (i == 0) {
            return "";
        }
        TurnStat current = stats.get(i);
        TurnStat previous = stats.get(i - 1);
        // 一轮纯问答固定追加 2 条事件；多出来的那 1 条就是压缩边界
        if (current.logSize() - previous.logSize() > EVENTS_PER_PLAIN_TURN) {
            return "    ← 第 " + (i + 1) + " 轮后触发压缩：日志多 1 条边界，视图缩回最近 " + PLAIN_KEEP_TURNS + " 个回合";
        }
        if (previous.logSize() > previous.viewSize()) {
            return "    ← 第 " + (i + 1) + " 轮发出时，模型拿到的是视图里的 " + previous.viewSize()
                    + " 条历史 + 新提问，不是日志的 " + previous.logSize() + " 条";
        }
        return "";
    }

    /** 「视图超过 maxTurns 个回合就压缩，只留最近 keepTurns 个回合」的会话 advisors。 */
    static List<Advisor> compactingAdvisors(SessionService sessions, int maxTurns, int keepTurns) {
        return SessionAdvisors.builder(sessions)
                // 触发条件与保留窗口都按「回合」数计：一个回合 = 一条用户消息及其后的全部内容（含工具调用）
                .compaction(new TurnCountTrigger(maxTurns),
                        WindowCompactionStrategy.builder().lastTurns(keepTurns).build())
                .build();
    }

    /** 在同一个会话上连问 turns 轮「第i句」，返回每轮结束后的（日志条数，视图条数）。 */
    static List<TurnStat> playPlainTurns(ChatClient chatClient, SessionService sessions, String sessionId,
            int turns) {
        List<Advisor> advisors = compactingAdvisors(sessions, PLAIN_MAX_TURNS, PLAIN_KEEP_TURNS);
        List<TurnStat> stats = new ArrayList<>();
        for (int i = 1; i <= turns; i++) {
            SessionDemoSupport.ask(chatClient, advisors, sessionId, "第" + i + "句");
            stats.add(new TurnStat(sessions.getEvents(sessionId, EventFilter.all()).size(),
                    sessions.getEvents(sessionId, EventFilter.active()).size()));
        }
        return List.copyOf(stats);
    }

    /** 两轮对话，第 2 轮问天气并调用工具；返回两轮后的（整份日志，视图）。 */
    static ToolTurnRun playToolTurns(ChatClient chatClient, SessionService sessions, String sessionId) {
        List<Advisor> advisors = compactingAdvisors(sessions, 1, 1);
        Object weather = new WeatherTools();
        SessionDemoSupport.ask(chatClient, advisors, sessionId, "你好", weather);
        SessionDemoSupport.ask(chatClient, advisors, sessionId, "北京天气怎么样？", weather);
        return new ToolTurnRun(sessions.getEvents(sessionId, EventFilter.all()),
                sessions.getEvents(sessionId, EventFilter.active()));
    }

    /** 把整份日志渲染成一行一条：✓ 还在视图里，✗ 已被压缩覆盖；边界那一行单独说明。 */
    static List<String> renderLog(List<SessionEvent> log, List<SessionEvent> view, ZoneId zone) {
        Set<String> viewIds = view.stream().map(SessionEvent::getId).collect(Collectors.toSet());
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            SessionEvent event = log.get(i);
            boolean inView = viewIds.contains(event.getId());
            StringBuilder line = new StringBuilder("  ").append(inView ? "✓" : "✗").append(' ')
                    .append(SessionMemoryDemo.describeEvent(i + 1, event, zone));
            if (event.getType().equals(EventType.COMPACTION)) {
                line.append("  ← 压缩边界：只追加这一条，上面任何一条都没被改写");
            }
            else if (!inView) {
                line.append("  ← 已被压缩覆盖：仍在日志里，可召回（见示例 5）");
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 一轮结束后，整份日志与活跃视图各有多少条事件。 */
    record TurnStat(int logSize, int viewSize) {
    }

    /** 两轮后的整份日志与活跃视图。 */
    record ToolTurnRun(List<SessionEvent> log, List<SessionEvent> view) {
    }

}
