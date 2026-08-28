package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.session.SessionEvents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import io.github.javaside.springai.codetui.agent.seam.StubListener;

/**
 * 中断（Esc 取消 / 报错）时把会话裁到「最长干净前缀」：只删末尾悬空的 {@code assistant(tool_calls)}，
 * 保留计划与已完成任务，从而支持 {@code /continue} 续跑；同时仍避免坏历史下轮 400。
 *
 * <p>直接验证 {@link CodingAgent#cleanPrefixLength}（纯逻辑）与 {@link CodingAgent#trimDanglingToolCalls}
 * （在真实 {@link DefaultSessionService} + {@link InMemorySessionRepository} 上覆盖写回）。不联网、确定性。
 */
class CodingAgentTrimTest {

    private static CodingAgent agent(SessionService svc, SessionRepository repo, String sid) {
        return new CodingAgent(null, new StubListener(), sid, new AtomicLong(),
                svc, null, null, List.of(), null, repo);
    }

    /** 建会话（真实流程里由 SessionMemoryAdvisor.before 创建；测试需手动建，否则 appendMessage 报 Session not found）。 */
    private static SessionService session(SessionRepository repo, String sid) {
        SessionService svc = DefaultSessionService.builder().sessionRepository(repo).build();
        svc.create(CreateSessionRequest.builder().id(sid).userId("u").build());
        return svc;
    }

    /** 一条带 tool_calls 的 assistant（每个 id 一个 Task 调用）。 */
    private static AssistantMessage asstCalls(String... ids) {
        List<AssistantMessage.ToolCall> calls = Arrays.stream(ids)
                .map(id -> new AssistantMessage.ToolCall(id, "function", "Task", "{}"))
                .toList();
        return AssistantMessage.builder().content("(委派)").toolCalls(calls).build();
    }

    /** 一条 tool 结果（覆盖若干 tool_call id）。 */
    private static ToolResponseMessage toolResults(String... ids) {
        List<ToolResponseMessage.ToolResponse> rs = Arrays.stream(ids)
                .map(id -> new ToolResponseMessage.ToolResponse(id, "Task", "done"))
                .toList();
        return ToolResponseMessage.builder().responses(rs).build();
    }

    private static List<String> texts(SessionService svc, String sid) {
        return svc.getMessages(sid).stream().map(Message::getText).toList();
    }

    @Test
    void trim_keepsCompletedRound_dropsDanglingToolCall() {
        String sid = "trim-1";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        // 已完成回合：user + assistant 文本
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, new AssistantMessage("a1"));
        // 半截回合：当前 user + 悬空 assistant(tool_calls)（无 tool 结果）
        svc.appendMessage(sid, new UserMessage("q2"));
        svc.appendMessage(sid, asstCalls("call_x"));
        assertEquals(4, svc.getEvents(sid).size());

        agent(svc, repo, sid).trimDanglingToolCalls();

        // 悬空 assistant 被裁掉；user 消息与已完成回合保留（q2 是当前请求，留存以便续跑）
        assertEquals(List.of("q1", "a1", "q2"), texts(svc, sid), "只删悬空 tool_calls 尾巴");
    }

    @Test
    void cleanPrefixLength_multiTaskPlan_keepsThroughLastCompletedTask() {
        String sid = "trim-2";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        // user, 计划回合, 任务1, 任务2 均已完成（每个 assistant(tool_calls) 都有配对 tool 结果），任务3 悬空
        svc.appendMessage(sid, new UserMessage("做 3 个任务"));   // 0
        svc.appendMessage(sid, asstCalls("plan"));               // 1
        svc.appendMessage(sid, toolResults("plan"));             // 2
        svc.appendMessage(sid, asstCalls("t1"));                 // 3
        svc.appendMessage(sid, toolResults("t1"));               // 4
        svc.appendMessage(sid, asstCalls("t2"));                 // 5
        svc.appendMessage(sid, toolResults("t2"));               // 6  ← 干净截断点
        svc.appendMessage(sid, asstCalls("t3"));                 // 7  ← 悬空

        List<SessionEvent> events = svc.getEvents(sid);
        assertEquals(7, SessionEvents.cleanPrefixLength(events), "裁到任务2 的 tool 结果，丢弃任务3 悬空");

        agent(svc, repo, sid).trimDanglingToolCalls();
        assertEquals(7, svc.getEvents(sid).size());
    }

    @Test
    void cleanPrefixLength_multiId_partialCoverageIsNotClean() {
        String sid = "trim-3";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q"));            // 0
        svc.appendMessage(sid, asstCalls("a", "b"));             // 1 一次两个 tool_call

        // 全覆盖：一条 tool 结果含 a、b 两个 → 视为配平，干净前缀 = 全部 3 条
        SessionService full = session(InMemorySessionRepository.builder().build(), "trim-3b");
        full.appendMessage("trim-3b", new UserMessage("q"));
        full.appendMessage("trim-3b", asstCalls("a", "b"));
        full.appendMessage("trim-3b", toolResults("a", "b"));
        assertEquals(3, SessionEvents.cleanPrefixLength(full.getEvents("trim-3b")), "多 id 被单条结果全覆盖 = 配平");

        // 部分覆盖：只回了 a、b 未回 → assistant 之后 pending 恒非空 → 干净前缀止于 assistant 之前（只剩 user）
        svc.appendMessage(sid, toolResults("a"));               // 2 只覆盖 a
        assertEquals(1, SessionEvents.cleanPrefixLength(svc.getEvents(sid)), "多 id 未全覆盖 = 不干净，裁回 assistant 之前");
    }

    @Test
    void trim_cleanHistory_isNoOp() {
        String sid = "trim-4";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, asstCalls("c1"));
        svc.appendMessage(sid, toolResults("c1"));
        svc.appendMessage(sid, new AssistantMessage("最终答复"));   // 无 tool_calls 的终答 → 干净结尾

        agent(svc, repo, sid).trimDanglingToolCalls();

        List<String> after = texts(svc, sid);
        assertEquals(4, after.size(), "干净历史裁剪为 no-op（含配平的 tool 结果）");
        assertEquals("q1", after.get(0));
        assertEquals("最终答复", after.get(3), "终答仍在，未被裁掉");
    }

    @Test
    void submit_sanitizesOutboundSession_beforeSending() {
        // 层①：上一回合被取消后，迟到的子 agent 写入可能给会话留下坏数据（此处用孤儿 tool 结果模拟）。
        // 活进程会话常驻内存永不重载，若出站不净化，下一条请求（含 /continue）必 400。验证 submit 顶部先净化。
        String sid = "outbound-1";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, new AssistantMessage("a1"));
        svc.appendMessage(sid, toolResults("orphan"));   // 孤儿 tool 结果：其 id 之前并无对应 assistant(tool_calls)
        assertEquals(3, svc.getEvents(sid).size());

        // chatClient=null → submit 会在「顶部净化」之后才因 client.prompt() NPE 被内部 catch（onError→disposed），
        // 不影响我们要验证的「发送前会话已净化」。
        agent(svc, repo, sid).submit("继续");

        assertEquals(List.of("q1", "a1"), texts(svc, sid), "孤儿 tool 结果在出站前被净化剔除，避免下条请求 400");
    }

    @Test
    void submit_foldsTrailingStoredUser_soBeforeAppendCannotMakeConsecutiveUsers() {
        // 坏会话自愈的关键一环：gpt 回合失败后 after() 未落盘 assistant，历史尾部残留一条 user。
        // 下一回合 SessionMemoryAdvisor.before 会无条件再追加新 user → 两条连续 user → DeepSeek 400（模型都没跑）。
        // 故 submit 在发送前把「尾部残留 user」折进出站消息并从会话删除，断开这个 400 循环。
        String sid = "fold-1";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, new AssistantMessage("a1"));
        svc.appendMessage(sid, new UserMessage("older"));   // ← 上个失败回合残留的尾部 user
        assertEquals(3, svc.getEvents(sid).size());

        agent(svc, repo, sid).submit("newer");   // chatClient=null → 折叠之后才 NPE 被内部 catch

        // 尾部残留 user 被折出会话；剩下的历史以 assistant 结尾，故 before 追加新 user 不会造成连续 user。
        assertEquals(List.of("q1", "a1"), texts(svc, sid), "尾部残留 user 被折进出站消息并从会话删除");
    }

    @Test
    void submit_noTrailingUser_foldIsNoOp() {
        String sid = "fold-2";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q1"));
        svc.appendMessage(sid, new AssistantMessage("done"));   // 尾部是 assistant → 折叠应无操作

        agent(svc, repo, sid).submit("newer");

        assertEquals(List.of("q1", "done"), texts(svc, sid), "尾部非 user 时折叠 no-op，会话不变");
    }

    @Test
    void trim_nullRepository_isNoOpAndDoesNotCrash() {
        String sid = "trim-5";
        InMemorySessionRepository repo = InMemorySessionRepository.builder().build();
        SessionService svc = session(repo, sid);
        svc.appendMessage(sid, new UserMessage("q"));
        svc.appendMessage(sid, asstCalls("dangling"));

        CodingAgent agent = agent(svc, null, sid);   // 仓库为 null（测试桩装配）
        assertDoesNotThrow(agent::trimDanglingToolCalls);
        assertEquals(2, svc.getEvents(sid).size(), "无仓库时不裁剪，会话保持不变");
    }
}
