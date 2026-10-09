package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import io.github.javaside.springai.codetui.agent.tools.ToolEventCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装配级回归：在<b>真实装配产物</b>上跑一遍「建清单 → 完成事件 → 下一个调用带提醒」的全链路。
 *
 * <p><b>为什么必须补这一层</b>：零件单测（{@code TodoStaleReminderTest}）证明状态机对；
 * {@code AgentToolsTodoReminderWiringTest} 只验「TodoWrite 有没有喂快照」。而
 * 「完成事件真的会在装饰链上装配成提醒、并追加到下一个工具结果尾部」这条<b>跨三个组件的线</b>
 * 此前没有任何用例覆盖——零件全绿而线没接上，正是本项目历史上反复出现的形态
 * （同一立场见 {@code AgentToolsBackgroundWiringTest} 的类注释）。全程离线，不需要 API key。
 */
class AgentToolsTodoReminderEndToEndTest {

    @TempDir Path root;

    /** 控制器级工具上下文（taskId 缺省 = 控制器）。 */
    private static ToolContext controllerCtx() {
        return new ToolContext(Map.of(ToolEventCallback.TURN_ID_KEY, 7L));
    }

    /**
     * 一律批准权限的监听器：继承 {@link StubListener}（各回调均为 no-op 桩）只覆盖权限应答。
     *
     * <p><b>不能用裸 {@link StubListener}</b>：它的默认是 DENY，而装配产物最外层是
     * {@code PermissionCallback}——实测 Bash 直接被拒、命令根本没走到装饰链内侧，提醒也就永远不会
     * 武装。同因见 {@code CodingAgentSpikeTest.Recorder} 的注释。
     */
    private static final class AllowAllListener extends StubListener {
        @Override public void onPermissionRequested(long turnId, PermissionRequest request) {
            request.responder().respond(PermissionOutcome.ALLOW_ONCE);
        }
    }

    /** 临时 git 仓库（提交事件要有真仓库才跑得通；身份用 -c 内联，避免污染全局配置）。 */
    private void initGitRepo() throws Exception {
        Process p = new ProcessBuilder("git", "init", "-q").directory(root.toFile()).start();
        assertTrue(p.waitFor() == 0, "git init 失败");
    }

    /** 经真实装饰链执行一条 Bash 命令（权限一律批准）。 */
    private String runBash(Map<String, ToolCallback> tools, String command) {
        return tools.get("Bash").call(
                "{\"command\":\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                controllerCtx());
    }

    @Test
    @DisplayName("真实装配：TodoWrite 建清单 → git commit 武装 → 下一个调用结果尾部带可照抄提醒")
    void completionEvent_throughRealDecoratedChain_appendsReminder(@TempDir Path unused) throws Exception {
        Files.writeString(root.resolve("note.txt"), "内容");
        initGitRepo();
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root, new AllowAllListener());
        assertTrue(rt.todoReminder().enabled(),
                "提醒器须启用（若本机显式设了 CODETUI_TODO_REMIND_EVERY=0，请清掉再跑）");
        Map<String, ToolCallback> tools = RuntimeToolSet.toolsOf(rt);

        // 1) 真实 TodoWrite（0.13.0 起直用库工具，单层 todos schema）建清单 → 快照进提醒器
        ToolCallback todo = tools.get("TodoWrite");
        assertNotNull(todo, "装配产物里必须有 TodoWrite");
        todo.call("""
                {"todos":[
                  {"content":"写测试","activeForm":"正在写测试","status":"completed"},
                  {"content":"改实现","activeForm":"正在改实现","status":"in_progress"},
                  {"content":"跑全量","activeForm":"正在跑全量","status":"pending"}]}""", controllerCtx());

        // 2) 完成事件：经真实装饰链执行一次真提交（提交能成功，避免「命令失败→异常路径不提醒」干扰）
        assertNotNull(tools.get("Bash"), "装配产物里必须有 Bash");
        String commitOut = runBash(tools,
                "git -C " + root + " -c user.email=t@example.com -c user.name=t commit --allow-empty -q -m t");
        assertFalse(commitOut.contains("[任务面板提醒]"),
                "完成事件当刻不得提醒（那一刻清单必然还没机会更新），实际=" + commitOut);

        // 3) 下一个控制器调用跳过更新 → 结果尾部应带提醒
        ToolCallback read = tools.get("Read");
        assertNotNull(read, "装配产物里必须有 Read");
        String after = read.call("{\"filePath\":\"" + root.resolve("note.txt") + "\"}", controllerCtx());

        assertTrue(after.contains("\n\n[任务面板提醒]"),
                "commit 之后第一个跳过更新的调用须在结果尾部带提醒，实际=" + after);
        assertTrue(after.contains("Bash 已返回"), "提醒须点名完成事件（Bash），实际=" + after);
        assertTrue(after.contains("\"content\":\"改实现\""), "提醒须含快照条目");
        int start = after.indexOf("{\"todos\"");
        String json = after.substring(start, after.lastIndexOf('}') + 1);
        // 照抄即用：拿真实生产适配器解析提醒给的 JSON（不是自己重新解析一遍自证）
        ToolCallback todoAgain = tools.get("TodoWrite");
        String parsed = todoAgain.call(json, controllerCtx());
        assertTrue(parsed.contains("modified successfully"),
                "提醒里的 JSON 必须能被真实装配的 TodoWrite 接受，实际=" + parsed);

        // 4) 更新之后不再重复提醒（每回合至多一次）
        String third = read.call("{\"filePath\":\"" + root.resolve("note.txt") + "\"}", controllerCtx());
        assertFalse(third.contains("[任务面板提醒]"), "更新后不得再提醒，实际=" + third);
    }
}
