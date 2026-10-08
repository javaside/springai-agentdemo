package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 钉死装配契约（同 {@code AgentToolsBackgroundWiringTest} 的立场：零件单测全绿也兜不住漏接一根线）：
 * 1) 真实 build 的 TodoWrite（控制器分支）必须把快照喂进 {@code runtime.todoReminder()}；
 * 2) 子 agent 分支（taskId!=null）的 TodoWrite 不喂快照。
 * 提醒文本经装饰链的追加行为已由 ToolEventCallbackTest 钉死，这里只验「喂没喂」。
 */
class AgentToolsTodoReminderWiringTest {

    @Test
    @DisplayName("真实装配：控制器 TodoWrite 喂快照，子 agent TodoWrite 不喂")
    void controllerTodoWrite_feedsReminder_subagentDoesNot(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root, new StubListener());
        // 开发机显式设 0=停用时不硬测（fromEnv 语义优先）
        assumeTrue(rt.todoReminder().enabled(), "CODETUI_TODO_REMIND_EVERY 显式停用，跳过");

        ToolCallback todo = RuntimeToolSet.toolsOf(rt).get("TodoWrite");
        assertNotNull(todo, "装配产物里必须有 TodoWrite");

        String controllerArgs = """
                {"todos":[
                  {"content":"接线任务甲","activeForm":"接线任务甲中","status":"in_progress"},
                  {"content":"接线任务乙","activeForm":"接线任务乙中","status":"pending"}]}""";
        todo.call(controllerArgs, new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 42L)));

        String note = null;
        for (int i = 0; i < 128 && note == null; i++) {
            note = rt.todoReminder().reminderOrNull(42L, "Bash");
        }
        assertNotNull(note, "控制器 TodoWrite 后快照必须能触发提醒（喂快照的线没接上？）");
        assertTrue(note.contains("接线任务甲"), "快照内容须来自控制器那次写入");

        // 子 agent 分支：同清单形状但内容不同，写入后不得出现在快照里
        String subagentArgs = """
                {"todos":[
                  {"content":"子代理私活","activeForm":"子代理私活中","status":"in_progress"}]}""";
        todo.call(subagentArgs, new ToolContext(Map.of(
                ToolEventCallback.TURN_ID_KEY, 43L, ToolEventCallback.TASK_ID_KEY, "sub-1")));
        String note2 = null;
        for (int i = 0; i < 128 && note2 == null; i++) {
            note2 = rt.todoReminder().reminderOrNull(43L, "Bash");
        }
        assertNotNull(note2, "旧控制器快照仍在，43 回合照常提醒");
        assertTrue(!note2.contains("子代理私活"),
                "子 agent 的 TodoWrite 不得污染控制器快照（分流线接错？）");
    }
}
