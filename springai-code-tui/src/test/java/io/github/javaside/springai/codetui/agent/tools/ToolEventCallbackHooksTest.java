package io.github.javaside.springai.codetui.agent.tools;

import io.github.javaside.springai.codetui.agent.hooks.HookRegistry;
import io.github.javaside.springai.codetui.agent.seam.AgentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre/Post 挂载：拦截/放行/追加（命令全 sh 内建；RecordingListener 记事件序）。 */
class ToolEventCallbackHooksTest {

    private static final class RecListener implements AgentListener {
        final List<String> events = new ArrayList<>();
        @Override public void onTurnStarted(long turnId) { }
        @Override public void onUserMessage(long turnId, String text) { }
        @Override public void onAssistantToken(long turnId, String token) { }
        // ⚠ 实现的是【抽象短签名】——4/5 参带 taskId 的是 default 委托版，覆写它们等于没实现抽象方法
        //（同 ToolEventCallbackTest.RecordingListener 的写法）
        @Override public void onToolStarted(long t, String name, String input) { events.add("start:" + name); }
        @Override public void onToolFinished(long t, String name, String output, boolean ok) {
            events.add((ok ? "ok:" : "fail:") + name + ":" + firstLine(output)); }
        @Override public void onSubagentStarted(long t, String id, String n, String d) { }
        @Override public void onSubagentFinished(long t, String id, String f) { }
        @Override public void onTodoUpdated(long t, List<String> lines) { }
        @Override public void onTurnComplete(long turnId) { }
        @Override public void onError(long t, Throwable e) { }
        @Override public void onQuestionAsked(long t, io.github.javaside.springai.codetui.agent.seam.AskRequest r) { }
        @Override public void onCompactionStarted(String r) { }
        @Override public void onCompactionFinished(int e, int s) { }
        @Override public void onCompactionFailed(String m) { }
        private static String firstLine(String s) { return s == null ? "" : s.strip().split("\n", 2)[0]; }
    }

    /** 恒返回固定串、可数调用次数的桩工具。 */
    private static final class CountingTool implements ToolCallback {
        int calls;
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("Bash").description("stub").inputSchema("{}").build(); }
        public String call(String input) { calls++; return "tool-ran"; }
        public String call(String input, org.springframework.ai.chat.model.ToolContext ctx) { calls++; return "tool-ran"; }
    }

    private static HookRegistry registryWith(Path root, String hooksJson) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("user.json"), hooksJson);
        return HookRegistry.load(root, root.resolve("user.json"),
                root.resolve("nope-proj.json"), root.resolve("nope-trust.json"));
    }

    @Test
    void preExit2Blocks_delegateNotCalled_failEventWithReason(@TempDir Path dir) throws Exception {
        HookRegistry reg = registryWith(dir,
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"command\":\"echo 不许跑 >&2; exit 2\"}]}}");
        RecListener l = new RecListener();
        CountingTool tool = new CountingTool();
        ToolEventCallback cb = new ToolEventCallback(tool, l, null, reg);

        String out = cb.call("{\"command\":\"ls\"}");

        assertTrue(out.contains("不许跑"), "拒绝文本回给模型：" + out);
        assertEquals(0, tool.calls, "被拦截：delegate 未执行");
        assertTrue(l.events.get(0).startsWith("start:Bash"), "onToolStarted 先发（与权限 DENY 不同——spec §7 对照）");
        assertTrue(l.events.get(1).startsWith("fail:Bash"), "onToolFinished(false) 收场");
        assertTrue(l.events.get(1).contains("不许跑"));
    }

    @Test
    void preFailureDoesNotBlock_postAppendsBeforeFinish(@TempDir Path dir) throws Exception {
        HookRegistry reg = registryWith(dir, """
                {"hooks":{"PreToolUse":[{"command":"exit 1"}],
                          "PostToolUse":[{"command":"echo lint-clean"}]}}""");
        RecListener l = new RecListener();
        CountingTool tool = new CountingTool();
        ToolEventCallback cb = new ToolEventCallback(tool, l, null, reg);

        String out = cb.call("{}");

        assertEquals(1, tool.calls, "Pre 非阻断失败：工具照跑");
        assertTrue(out.contains("tool-ran") && out.contains("lint-clean"), "Post stdout 追加进结果");
        assertEquals("ok:Bash:tool-ran", l.events.get(1).split("\n")[0], "onToolFinished(true) 先于追加内容可见"
                + "——事件序：start→ok；追加发生在返回值上");
    }

    @Test
    void nullHooks_zeroBehavior_3argConstructorUntouched(@TempDir Path dir) {
        RecListener l = new RecListener();
        CountingTool tool = new CountingTool();
        ToolEventCallback cb = new ToolEventCallback(tool, l, null);   // 3 参构造（McpRegistry 同款）
        assertEquals("tool-ran", cb.call("{}"));
        assertEquals(1, tool.calls);
        assertFalse(l.events.get(1).contains("PostToolUse"));
    }
}
