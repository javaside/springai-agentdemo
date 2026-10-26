package io.github.javaside.springai.codetui.agent.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 信任模型 + hooksFor 过滤 + run* 语义（命令用 sh 内建，全真进程）。 */
class HookRegistryTest {

    private static Path w(Path dir, String name, String json) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void untrustedProjectLayerSkipped_trustFlipsImmediately(@TempDir Path root) throws Exception {
        Path user = w(root, "user.json",
                "{\"hooks\":{\"PreToolUse\":[{\"command\":\"exit 0\"}]}}");
        Path proj = w(root, "proj.json",
                "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"command\":\"exit 2\"}]}}");
        Path trust = root.resolve("trust.json");

        HookRegistry reg = HookRegistry.load(root, user, proj, trust);
        assertTrue(reg.projectLayerUntrusted(), "无信任记录：项目层未信任");
        assertEquals(2, reg.allFor(HookEvent.PRE_TOOL_USE).size(),
                "allFor 只读列表口不受信任过滤——未信任的项目层钩子也要可见（面板才能决定信任）");
        // 未信任：项目层 exit-2 钩子不跑，只剩用户层 exit 0 → 不拦截
        HookRegistry.PreResult r = reg.runPre("Bash", "{}", "s1", 1L, null);
        assertFalse(r.blocked());

        reg.trustProjectLayer();
        assertFalse(reg.projectLayerUntrusted(), "t 后即时生效（同一实例）");
        HookRegistry.PreResult r2 = reg.runPre("Bash", "{}", "s1", 1L, null);
        assertTrue(r2.blocked(), "信任后项目层 exit 2 生效");
        assertTrue(r2.message().contains("exit 2"), "拒绝文本含命令与 stderr：" + r2.message());

        // 配置变更即失信任：改 proj.json 内容 → hash 失配
        w(root, "proj.json", "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"Bash\",\"command\":\"exit 2\"},{\"command\":\"echo x\"}]}}");
        HookRegistry reloaded = HookRegistry.load(root, user, root.resolve("proj.json"), trust);
        assertTrue(reloaded.projectLayerUntrusted(), "内容变更即失信任（hash 失配）");
    }

    @Test
    void hooksForFiltersByEventAndMatcher(@TempDir Path root) throws Exception {
        Path user = w(root, "user.json", """
                {"hooks":{"PreToolUse":[{"matcher":"^Bash$","command":"a"},
                                        {"matcher":"Bash|Write","command":"b"}],
                          "PostToolUse":[{"command":"c"}]}}""");
        HookRegistry reg = HookRegistry.load(root, user, root.resolve("nope.json"), root.resolve("nope.json"));
        assertEquals(2, reg.hooksFor(HookEvent.PRE_TOOL_USE, "Bash").size(), "两条 matcher 都命中 Bash");
        assertEquals(1, reg.hooksFor(HookEvent.PRE_TOOL_USE, "Write").size(), "只有 Bash|Write 命中");
        assertEquals(List.of(), reg.hooksFor(HookEvent.PRE_TOOL_USE, "Read"));
        assertEquals(1, reg.hooksFor(HookEvent.POST_TOOL_USE, null).size());
        assertFalse(reg.projectLayerUntrusted(), "无项目层配置恒非未信任");
        assertEquals("", reg.projectFileMtimeNotice(), "无项目层配置无可疑提示（返回空串非 null）");
    }

    @Test
    void runPostAppends_failureNoticedNotAppended(@TempDir Path root) throws Exception {
        Path user = w(root, "user.json", """
                {"hooks":{"PostToolUse":[
                  {"command":"printf lint-out"},
                  {"command":"echo bad >&2; exit 1"}]}}""");
        HookRegistry reg = HookRegistry.load(root, user, root.resolve("nope.json"), root.resolve("nope.json"));
        StringBuilder notices = new StringBuilder();
        String append = reg.runPost("Write", "{}", "wrote", "s", 1L, null, notices::append);
        assertTrue(append.contains("lint-out"), "exit 0 的 stdout 追加：" + append);
        assertTrue(!append.contains("bad"), "exit 1 的失败钩子不追加（stderr 不进结果）");
        assertTrue(notices.toString().contains("bad"), "失败钩子经 notice 回执一行（spec：UI 提示）：" + notices);
    }

    @Test
    void runNotifyDeliversFailureNotice(@TempDir Path root) throws Exception {
        Path user = w(root, "user.json",
                "{\"hooks\":{\"SessionStart\":[{\"command\":\"echo env-ready >&2; exit 1\"}]}}");
        HookRegistry reg = HookRegistry.load(root, user, root.resolve("nope.json"), root.resolve("nope.json"));
        StringBuilder notices = new StringBuilder();
        reg.runNotify(HookEvent.SESSION_START, notices::append);
        assertTrue(notices.toString().contains("env-ready"),
                "通知型按 spec 只回执失败（exit 0 输出忽略）——失败 stderr 进 notice：" + notices);
    }
}
