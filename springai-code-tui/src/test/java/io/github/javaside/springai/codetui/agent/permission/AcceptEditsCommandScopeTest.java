package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ACCEPT_EDITS} 下的命令段必须<b>判目标在哪</b>，不能只看首词。
 *
 * <p>此前 {@code commandByMode} 只问 {@link BashCommandSplitter#isFileSystemWrite}
 * （首词是不是 {@code mkdir}/{@code touch}/{@code mv}/{@code cp}），于是
 * {@code mkdir /etc/evil} 在该档直接放行，<b>而放行理由写着「工作区内的文件操作」</b>——
 * 不只是行为有洞，界面还在说一句不实的话。同一档的 {@code Write}/{@code Edit}
 * 是确实判 {@code insideRoot} 的，同一个承诺不该有两种兑现。
 *
 * <p>{@code @TempDir} 在 macOS 上位于 {@code /var/folders/…}，而 {@code /var} 是指向
 * {@code /private/var} 的符号链接；不先 {@code toRealPath()}，root 与目标会分处链接两侧，
 * 用例会因无关的原因红。故一律先取 real path。
 */
class AcceptEditsCommandScopeTest {

    private static PermissionEngine engine(Path root, PermissionMode mode) {
        return new PermissionEngine(root, PermissionConfig.empty(), mode, false);
    }

    private static String bash(String cmd) {
        return "{\"command\":\"" + cmd.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static PermissionDecision decide(Path root, String cmd) throws IOException {
        return engine(root.toRealPath(), PermissionMode.ACCEPT_EDITS).decide("Bash", bash(cmd));
    }

    @Test
    @DisplayName("工作区内仍照常放行，且理由确实说的是「工作区内」")
    void insideRootStillAllowedWithTruthfulReason(@TempDir Path root) throws IOException {
        PermissionDecision d = decide(root, "mkdir -p src/main");

        assertEquals(PermissionBehavior.ALLOW, d.behavior());
        assertTrue(d.reason().contains("工作区内"),
                "放行理由必须与新行为一致；对不上就是界面在说一句不实的话：" + d.reason());
    }

    @Test
    @DisplayName("★ 绝对路径逃出工作区 → ASK（此前直接放行）")
    void absolutePathOutsideRootAsks(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ASK, decide(root, "mkdir /etc/evil").behavior());
    }

    @Test
    @DisplayName("★ ~ 开头 → ASK：当相对路径解析会得到 <root>/~/…，落在工作区内被错误放行")
    void tildeAsks(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ASK, decide(root, "mv ~/notes.txt x").behavior());
        assertEquals(PermissionBehavior.ASK, decide(root, "cp x ~").behavior());
    }

    @Test
    @DisplayName("★ 相对路径向上逃逸 → ASK")
    void relativeEscapeAsks(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ASK, decide(root, "touch ../../x").behavior());
        assertEquals(PermissionBehavior.ASK, decide(root, "cp a ../outside.txt").behavior());
    }

    @Test
    @DisplayName("通配符按首个通配符前的字面前缀判：*.txt 在区内、../*.txt 在区外")
    void globsJudgedByLiteralPrefix(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ALLOW, decide(root, "cp *.txt sub").behavior(),
                "前缀为空 → root 自身 → 区内");
        assertEquals(PermissionBehavior.ASK, decide(root, "cp ../*.txt sub").behavior(),
                "前缀 ../ → 区外");
    }

    @Test
    @DisplayName("多个目标里只要有一个越界就整段 ASK（mv 的源在区外）")
    void anyOutsideArgumentAsks(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ASK, decide(root, "mv /tmp/x.txt inside.txt").behavior());
    }

    @Test
    @DisplayName("默认档不受影响：这四个命令本来就要问，放宽与收紧都不该改变它")
    void defaultModeUnaffected(@TempDir Path root) throws IOException {
        PermissionEngine e = engine(root.toRealPath(), PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("mkdir -p src/main")).behavior());
        assertEquals(PermissionBehavior.ASK, e.decide("Bash", bash("mkdir /etc/evil")).behavior());
    }

    @Test
    @DisplayName("对照：只读段照常放行，不受本次改动波及（它不走 isFileSystemWrite 那条路）")
    void readOnlySegmentsUnaffected(@TempDir Path root) throws IOException {
        PermissionDecision d = decide(root, "ls");
        assertEquals(PermissionBehavior.ALLOW, d.behavior());
        assertTrue(d.reason().contains("只读"), d.reason());
    }

    @Test
    @DisplayName("混合命令：只读段 + 工作区内写段仍整条放行")
    void mixedReadOnlyAndInsideWriteAllowed(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ALLOW, decide(root, "ls && mkdir sub").behavior());
    }

    /** 这几条在 {@link PermissionConfig#empty()} 下没有任何规则，结论只可能来自模式默认。 */
    @Test
    @DisplayName("越界写不会因为「命令里还有一段只读」就被顺带放行")
    void outsideWriteInLaterSegmentStillAsks(@TempDir Path root) throws IOException {
        assertEquals(PermissionBehavior.ASK, decide(root, "ls && mkdir /etc/evil").behavior());
    }

    @Test
    @DisplayName("空目标不误伤：没有参数的 mkdir 仍走原路（没有路径可越界）")
    void noArgumentsIsUnchanged(@TempDir Path root) throws IOException {
        // 没有目标 → pathArguments 空 → 全部「都在区内」为真 → 与改动前同结论。
        // 这条锁的是「空列表不该被当成越界」，那是最容易写反的一处。
        assertEquals(PermissionBehavior.ALLOW, decide(root, "mkdir").behavior());
    }

    @Test
    @DisplayName("List.of() 契约：pathArguments 与引擎判定之间没有 null 通路")
    void engineNeverSeesNullTokens(@TempDir Path root) throws IOException {
        for (String cmd : List.of("mkdir", "mkdir -p", "touch", "cp", "mv")) {
            decide(root, cmd);      // 不抛即可
        }
    }
}
