package io.github.javaside.springai.codetui.agent.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两层加载：concat 顺序/字段缺省/钳制/坏条目降级（McpConfigLoader 同契约）。 */
class HookConfigLoaderTest {

    private static Path write(Path dir, String name, String json) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, json);
        return f;
    }

    @Test
    void concatenatesUserFirst_allFields(@TempDir Path dir) throws IOException {
        Path user = write(dir, "user.json", """
                {"hooks":{"PreToolUse":[{"matcher":"Bash","command":"u.sh","timeout":30}]}}""");
        Path proj = write(dir, "proj.json", """
                {"hooks":{"PreToolUse":[{"command":"p.sh"}],"Stop":[{"command":"notify.sh"}]}}""");

        List<HookDefinition> hooks = HookConfigLoader.load(user, proj);

        assertEquals(3, hooks.size());
        assertEquals(HookSource.USER, hooks.get(0).source());
        assertEquals(HookSource.PROJECT, hooks.get(1).source(), "同事件 concat：用户层在前");
        assertEquals(60, hooks.get(1).timeoutSeconds(), "timeout 缺省 60");
        assertEquals("", hooks.get(1).matcher(), "matcher 缺省空串=全部");
        assertEquals(HookEvent.STOP, hooks.get(2).event());
        assertTrue(hooks.get(0).matchesTool("Bash"));
        assertTrue(!hooks.get(0).matchesTool("Write"));
        assertTrue(hooks.get(1).matchesTool("Anything"), "空 matcher 匹配全部");
        assertTrue(hooks.get(2).matchesTool(null), "非工具事件恒匹配");
    }

    @Test
    void clampsTimeout_dropsBadEntries_survivesBadJson(@TempDir Path dir) throws IOException {
        Path user = write(dir, "user.json", """
                {"hooks":{"PreToolUse":[
                  {"command":"a.sh","timeout":999},
                  {"command":"b.sh","timeout":0},
                  {"matcher":"([bad","command":"c.sh"},
                  {"timeout":5}
                ]}}""");
        Path proj = write(dir, "proj.json", "{ 不是 JSON");

        List<HookDefinition> hooks = HookConfigLoader.load(user, proj);

        assertEquals(2, hooks.size(), "钳上界保留、超时=0 回退默认保留、非法正则丢、缺 command 丢；坏 JSON 整文件跳过");
        assertEquals(120, hooks.get(0).timeoutSeconds(), "999 钳到 120");
        assertEquals("a.sh", hooks.get(0).command());
        assertEquals(60, hooks.get(1).timeoutSeconds(), "timeout=0 回退默认 60（保留不丢）");
    }

    @Test
    void unknownEventKeySkipped_missingFilesEmpty(@TempDir Path dir) throws IOException {
        Path user = write(dir, "user.json",
                "{\"hooks\":{\"UserPromptSubmit\":[{\"command\":\"x\"}],\"PostToolUse\":[{\"command\":\"y\"}]}}");
        List<HookDefinition> hooks = HookConfigLoader.load(user, dir.resolve("nope.json"));
        assertEquals(1, hooks.size());
        assertEquals(HookEvent.POST_TOOL_USE, hooks.get(0).event());
        assertEquals(List.of(), HookConfigLoader.load(dir.resolve("a.json"), dir.resolve("b.json")));
    }
}
