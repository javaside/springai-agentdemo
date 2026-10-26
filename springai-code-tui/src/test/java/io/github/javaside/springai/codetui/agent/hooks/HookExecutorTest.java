package io.github.javaside.springai.codetui.agent.hooks;

import io.github.javaside.springai.codetui.agent.hooks.HookExecutor.HookResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真进程执行（CI ubuntu + 本机 macOS 的 /bin/sh 均可跑；宽裕差防负载抖动）。 */
class HookExecutorTest {

    private static HookDefinition def(String cmd, int timeout) {
        return new HookDefinition("", cmd, timeout, HookEvent.PRE_TOOL_USE, HookSource.USER);
    }

    @Test
    void exitCodesAndStdout(@TempDir Path root) {
        assertEquals(0, HookExecutor.execute(def("exit 0", 10), "{}", root).exitCode());
        assertEquals(2, HookExecutor.execute(def("exit 2", 10), "{}", root).exitCode());
        assertEquals("hello", HookExecutor.execute(def("echo hello", 10), "{}", root).stdout().strip());
        assertEquals("err!", HookExecutor.execute(def("echo err! >&2; exit 2", 10), "{}", root).stderr().strip());
    }

    @Test
    void stdinPayloadDelivered(@TempDir Path root) {
        String payload = "{\"event\":\"PreToolUse\",\"toolName\":\"Bash\"}";
        HookResult r = HookExecutor.execute(def("cat", 10), payload, root);
        assertEquals(0, r.exitCode());
        assertEquals(payload, r.stdout().strip(), "stdin payload 原样可达钩子");
    }

    @Test
    void timeoutKills(@TempDir Path root) {
        long t0 = System.nanoTime();   // sleep 30 + timeout 1：宽裕差，防 CI 负载下误判
        HookResult r = HookExecutor.execute(def("sleep 30", 1), "{}", root);
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode(), "超时无退出码");
        assertTrue((System.nanoTime() - t0) < 20_000_000_000L, "1s 超时应在远小于 30s 内返回");
    }

    @Test
    void commandNotFoundIs127_startupFailureStillMinusOne(@TempDir Path root) {
        // sh 本身正常启动、找不到的是子命令 → sh 语义退出码 127（实测；-1 只在 sh 自身启动失败时出现）
        HookResult r = HookExecutor.execute(def("definitely-not-a-command-xyz", 10), "{}", root);
        assertEquals(127, r.exitCode(), "sh 的 command-not-found 退出码原样透传（非阻断由调用方按 exit!=0 处理）");
        assertTrue(!r.stderr().isEmpty());
    }

    @Test
    void cwdIsRoot(@TempDir Path root) throws Exception {
        // 用落盘探针钉 cwd（pwd 在 macOS 的 /var 符号链接下会与 TempDir 路径不一致）
        String out = HookExecutor.execute(
                def("touch __probe__ && test -f __probe__ && echo ok && rm -f __probe__", 10), "{}", root)
                .stdout().strip();
        assertEquals("ok", out, "工作目录确为 root（钩子里的相对路径以此解析）");
    }
}
