package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 springai-code-tui 的「安全现实」，不是证明系统安全，而是把边界的真实情况写死成测试，
 * 防止后续改动误以为整个 root 是沙箱。
 *
 * <p>结论（经 javap + 直接调用工具核实）：<b>本应用没有任何工具做强制目录边界</b>。
 * FileSystemTools/ShellTools/GrepTool/GlobTool 给绝对路径都能读到 root 之外的内容——
 * FileSystemTools 的 allowedDirectory 沙箱是库的 opt-in 特性，本应用<b>刻意不启用</b>
 * （空 allowedDirectories → {@code validateAllowedAccess} 直接放行），因为其余工具都能越界、
 * 单给 FS 设边界形同虚设、反而制造「有沙箱」的假象。全线靠系统提示自律约束，不靠技术强制。
 *
 * <p>section 1 同时钉住：库<b>确实能</b>沙箱（allowedDirectory 生效时越界返回 Error: Access denied），
 * 以及本应用的默认 build（空 builder）<b>确实不</b>沙箱——若哪天误加回 allowedDirectory，
 * {@code appDefault_*} 断言会失败并提醒我们。
 */
class AgentToolsSecurityTest {

    /** FileSystemTools 拒绝越界时返回的错误串前缀（javap+实测确认：返回字符串，不抛异常）。 */
    private static final String ACCESS_DENIED = "Error: Access denied";

    // ---------------------------------------------------------------------
    // 1. FileSystemTools —— 库能沙箱（allowedDirectory 生效），但本应用默认不沙箱（空 builder）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("库能力：配了 allowedDirectory 时，root 外绝对路径被拒（证明沙箱是真的、只是本应用没开）")
    void library_withAllowedDirectory_rejectsOutside(@TempDir Path root) throws Exception {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();

        Path outside = Files.createTempDirectory("escape_").resolve("escape.txt");
        String result = fs.write(outside.toString(), "should be blocked");

        assertTrue(result.startsWith(ACCESS_DENIED),
                "配了 allowedDirectory 时，写 root 之外必须被拒绝，实际返回: " + result);
        assertFalse(Files.exists(outside), "被拒绝的文件不应真的落盘");
    }

    @Test
    @DisplayName("应用现实：默认 build（空 builder）不设边界——root 外绝对路径可写（无沙箱）")
    void appDefault_absolutePathOutsideRoot_isAllowed(@TempDir Path root) throws Exception {
        // 与 AgentTools.build 一致：不调用 allowedDirectory，空 allowedDirectories 即放行任何路径。
        FileSystemTools fs = FileSystemTools.builder().build();

        Path outsideDir = Files.createTempDirectory("app_no_sandbox_");
        Path outside = outsideDir.resolve("escape.txt");
        String result = fs.write(outside.toString(), "written outside root");

        assertFalse(result.startsWith(ACCESS_DENIED),
                "本应用默认无沙箱：写 root 之外不应被拒绝，实际返回: " + result);
        assertTrue(Files.exists(outside), "无沙箱：文件应真的落到 root 之外");
        assertTrue(Files.readString(outside).contains("written outside root"),
                "无沙箱：root 外文件正文应写入成功");
    }

    @Test
    @DisplayName("应用现实：默认 build 时 root 内读写照常成功")
    void appDefault_insideRoot_succeeds(@TempDir Path root) throws Exception {
        FileSystemTools fs = FileSystemTools.builder().build();

        Path inside = root.resolve("hello.txt");
        String writeResult = fs.write(inside.toString(), "hi");
        assertFalse(writeResult.startsWith(ACCESS_DENIED),
                "root 内写入不应被拒绝，实际返回: " + writeResult);
        assertTrue(Files.exists(inside), "文件应真的被写到磁盘");

        String readResult = fs.read(inside.toString(), null, null);
        assertFalse(readResult.startsWith(ACCESS_DENIED),
                "root 内读取不应被拒绝，实际返回: " + readResult);
        assertTrue(readResult.contains("hi"), "读回的内容应包含写入的正文");
    }

    // ---------------------------------------------------------------------
    // 2. 记录性测试（钉住不安全事实）——Shell/Grep/Glob 同样不受 root 限制
    //    命名/注释刻意大写「不受限」，让任何人一眼看出这是「已知不安全」而非疏漏。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("记录：Shell 不受 root 限制——已知残余风险（可读 root 外文件）")
    void KNOWN_UNSAFE_shell_isNotRootSandboxed(@TempDir Path root) throws Exception {
        // root 之外放一个「机密」文件
        Path outsideDir = Files.createTempDirectory("outside_shell_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // ShellTools 没有 root 参数，也不做任何边界检查：绝对路径 cat 直接读到 root 外内容。
        ShellTools sh = ShellTools.builder().build();
        String out = sh.bash("cat " + outsideDir.resolve("secret.txt"), 5000L, "read outside root", false);

        // 这是「已知不安全」事实——若哪天工具真的加了沙箱，这个断言会失败并提醒我们更新此处的安全现实。
        assertTrue(out.contains("TOPSECRET_MARKER"),
                "已知现实：ShellTools 不受 root 限制，能读到 root 外文件。实际返回: " + out);
    }

    @Test
    @DisplayName("记录：Grep 不受 workingDirectory 限制——已知残余风险（可搜 root 外目录）")
    void KNOWN_UNSAFE_grep_isNotRootSandboxed(@TempDir Path root) throws Exception {
        Path outsideDir = Files.createTempDirectory("outside_grep_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // workingDirectory 只是默认基准；传绝对路径就能越界搜索。
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        String out = grep.grep("TOPSECRET_MARKER", outsideDir.toString(),
                null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(out.contains("secret.txt"),
                "已知现实：GrepTool 的 workingDirectory 非强制边界，能搜 root 外目录。实际返回: " + out);
    }

    @Test
    @DisplayName("记录：Glob 不受 workingDirectory 限制——已知残余风险（可列 root 外目录）")
    void KNOWN_UNSAFE_glob_isNotRootSandboxed(@TempDir Path root) throws Exception {
        Path outsideDir = Files.createTempDirectory("outside_glob_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // 同理，传绝对 path 参数即可越界列举。
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        String out = glob.glob("*.txt", outsideDir.toString());

        assertTrue(out.contains("secret.txt"),
                "已知现实：GlobTool 的 workingDirectory 非强制边界，能列 root 外目录。实际返回: " + out);
    }
}
