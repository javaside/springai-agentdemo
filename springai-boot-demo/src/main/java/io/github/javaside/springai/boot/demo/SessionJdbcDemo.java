package io.github.javaside.springai.boot.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 示例：会话落库 —— 会话日志存进数据库，进程重启后还在。
 *
 * <p>对比第②步 agent-demo 的会话示例：那边要手动写两行接线
 * （{@code DefaultSessionService.builder().sessionRepository(InMemorySessionRepository…)}），而且仓库在内存里，
 * 进程一退就丢。这里一个构造器参数都不用管：{@code spring-ai-starter-model-session-jdbc} 自动配置了
 * {@code JdbcSessionRepository}（落在 DataSource 指向的数据库上）和它之上的 {@link SessionService}，
 * 本类只管注入使用。
 *
 * <p>会话日志就是表里的行：{@code AI_SESSION_EVENT} 一行一条事件（seq / event_type / message_type /
 * message_content）。本示例固定用同一个会话 id，所以每运行一次，数据库里的历史都会被下一次运行接着用上。
 */
@Component
public class SessionJdbcDemo implements Demo {

    static final String SESSION_ID = "boot-demo-user";

    private static final String COUNT_EVENTS = "SELECT COUNT(*) FROM AI_SESSION_EVENT WHERE session_id = ?";

    private static final String SELECT_EVENTS = "SELECT seq, event_type, message_type, message_content "
            + "FROM AI_SESSION_EVENT WHERE session_id = ? ORDER BY seq";

    private final ChatClient chatClient;

    private final SessionService sessionService;

    private final JdbcTemplate jdbcTemplate;

    private final String jdbcUrl;

    /** 会话 advisors 没有状态，构造时建一次即可；按请求传入，不挂到共享 ChatClient 的默认 advisors 上。 */
    private final List<Advisor> sessionAdvisors;

    public SessionJdbcDemo(ChatClient chatClient, SessionService sessionService, JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.url}") String jdbcUrl) {
        this.chatClient = chatClient;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcUrl = jdbcUrl;
        this.sessionAdvisors = SessionAdvisors.builder(sessionService).build();
    }

    @Override
    public String title() {
        return "会话落库（JDBC starter + H2，重启不丢）";
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void run() {
        long before = countEvents();
        System.out.println("数据库：" + jdbcUrl);
        System.out.println("会话 " + SESSION_ID + " 在库里已有 " + before + " 条历史事件"
                + (before == 0 ? "（首次运行）" : "（来自上一次运行）") + "。\n");

        System.out.println("第 1 问（看它记不记得上一次运行说过的事）");
        System.out.println("答：" + ask("我之前告诉过你我的工号吗？不知道就直说。") + "\n");

        System.out.println("第 2 问（告诉它，之后每次运行都记得）");
        System.out.println("答：" + ask("请记住，我的工号是 A1024。") + "\n");

        System.out.println("第 3 问（同一进程内立刻验证）");
        System.out.println("答：" + ask("我的工号是多少？") + "\n");

        System.out.println("\n库里的会话日志（AI_SESSION_EVENT，一行一条事件）：");
        // 正文在 Java 里截断而不是用 SQL 的 LEFT()：换一种数据库也不用改语句
        jdbcTemplate.query(SELECT_EVENTS, rs -> {
            String content = rs.getString("message_content");
            if (content != null && content.length() > 20) {
                content = content.substring(0, 20) + "…";
            }
            String messageType = rs.getString("message_type");
            System.out.printf("  seq=%-4d %-10s %-9s %s%n", rs.getLong("seq"), rs.getString("event_type"),
                    messageType == null ? "-" : messageType, content);
        }, SESSION_ID);

        System.out.println("\n退出程序再运行本示例：第 1 问就能答对——会话在数据库里，不在 JVM 内存里。");
        System.out.println("想从头演示：删掉 ~/.springai-boot-demo 目录。");
    }

    private String ask(String question) {
        System.out.println("问：" + question);
        return this.chatClient.prompt()
                .user(question)
                // 会话 advisors 按请求传入：会话记忆 advisor 要求每个请求都带会话 id，缺了会抛异常
                .advisors(this.sessionAdvisors)
                .advisors(SessionAdvisors.session(SESSION_ID))
                .call()
                .content();
    }

    private long countEvents() {
        Long count = this.jdbcTemplate.queryForObject(COUNT_EVENTS, Long.class, SESSION_ID);
        return count == null ? 0 : count;
    }

}
