package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitSnapshot：git 执行接缝（ProcessBuilder 是全仓生产首例——接缝即测试通道，spec 事实#6）。 */
class GitSnapshotTest {

    /** 真实 git 用例的公共准备：@TempDir 里 init + 配 user（先例 AgentToolsTodoReminderEndToEndTest）。 */
    private static void initRepo(Path dir) throws IOException, InterruptedException {
        runOk(dir, "git", "init", "-q");
        runOk(dir, "git", "config", "user.email", "t@t");
        runOk(dir, "git", "config", "user.name", "t");
    }

    private static void runOk(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd));
        }
    }

    @Test
    void capturesDiffStatBodyAndUntracked(@TempDir Path dir) throws Exception {
        initRepo(dir);
        Files.writeString(dir.resolve("a.txt"), "one\n");
        runOk(dir, "git", "add", "a.txt");
        runOk(dir, "git", "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        Files.writeString(dir.resolve("new.txt"), "全新的文件，内容不必看\n");

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error(), "正常路径无错");
        assertNotNull(s.statLine(), "有 --stat 尾行");
        assertTrue(s.body().stream().anyMatch(l -> l.startsWith("+two")), "diff 正文含新增行，实际: " + s.body());
        assertTrue(s.body().stream().anyMatch(l -> l.startsWith("-one") || l.contains("a.txt")), "含被改文件");
        assertEquals(1, s.untracked().size(), "未跟踪只列一个");
        assertTrue(s.untracked().get(0).startsWith("new.txt"), "未跟踪列名（可带字节数），实际: " + s.untracked());
    }

    @Test
    void cleanTreeGivesEmptySnapshot(@TempDir Path dir) throws Exception {
        initRepo(dir);
        Files.writeString(dir.resolve("a.txt"), "x\n");
        runOk(dir, "git", "add", ".");
        runOk(dir, "git", "commit", "-q", "-m", "init");

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error());
        assertTrue(s.body().isEmpty(), "干净树正文为空");
        assertTrue(s.untracked().isEmpty());
    }

    /**
     * 非 ASCII 未跟踪文件名不许被 git 的默认 core.quotepath 转义：默认值 true 会把
     * {@code 中文文件.txt} 打成 {@code "\344\270\255...txt"}（带引号的八进制串），后续
     * {@code root.resolve(name)} 对不上真实路径、字节数也拼不上。带 -c core.quotepath=false
     * 后列名逐字还原（Apple Git 2.50 实测：默认转义、-c 关闭后原样）。
     */
    @Test
    void untrackedNonAsciiName_staysUnescaped(@TempDir Path dir) throws Exception {
        initRepo(dir);
        Files.writeString(dir.resolve("a.txt"), "x\n");
        runOk(dir, "git", "add", "a.txt");
        runOk(dir, "git", "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("中文文件.txt"), "内容\n");

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error(), "正常路径无错: " + s.error());
        assertEquals(1, s.untracked().size(), "未跟踪只列一个，实际: " + s.untracked());
        assertTrue(s.untracked().get(0).startsWith("中文文件.txt"),
                "列名不得带引号/八进制转义（core.quotepath 未关闭时的形态），实际: " + s.untracked());
    }

    @Test
    void nonGitDirOrBrokenGitGoesToErrorChannel(@TempDir Path dir) {
        GitSnapshot no = GitSnapshot.capture(dir, GitSnapshot.PROCESS);
        assertNotNull(no.error(), "非 git 目录走 error 通道而非异常");
        assertTrue(no.body().isEmpty());

        GitSnapshot broken = GitSnapshot.capture(dir,
                (root, args) -> { throw new IOException("git not found"); });
        assertEquals("git not found", broken.error(), "IOException 的 message 进 error");
    }

    @Test
    void isGitRepoDetectsInsideWorkTree(@TempDir Path dir) throws Exception {
        // ⚠ 假设声明：@TempDir 须不在任何 git 工作树内（rev-parse 会向上搜索父目录；JUnit 默认系统 tmpdir 满足）
        assertFalse(GitSnapshot.isGitRepo(dir, GitSnapshot.PROCESS));
        initRepo(dir);
        assertTrue(GitSnapshot.isGitRepo(dir, GitSnapshot.PROCESS));
    }

    @Test
    void largeDiffOverPipeBuffer_survives(@TempDir Path dir) throws Exception {
        // 主场景钉子：>64KB 的 diff（OS 管道缓冲量级）必须完整捕获——若实现退回「先 waitFor 后读管道」，
        // 此用例必以「git 超时」假失败（计划评审实证的互锁）。
        initRepo(dir);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append("line-").append(i).append("-").append("x".repeat(60)).append('\n');
        }
        Files.writeString(dir.resolve("big.txt"), "seed\n");
        runOk(dir, "git", "add", "big.txt");
        runOk(dir, "git", "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("big.txt"), big.toString());

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error(), "大 diff 不许超时: " + s.error());
        assertTrue(s.body().size() > 2000, "全部行捕获，实际: " + s.body().size());
    }

    @Test
    void stubCoversTimeoutAndNonZeroExit_channels(@TempDir Path dir) {
        // 桩测 error 通道两分支（spec 测试策略明文）：非零退出/超时由 IOException 消息承载进 error
        GitSnapshot.GitCommand failing = (root, args) -> { throw new IOException("fatal: not a git repository"); };
        assertEquals("fatal: not a git repository", GitSnapshot.capture(dir, failing).error());

        GitSnapshot.GitCommand timing = (root, args) -> { throw new IOException("git 超时：diff HEAD"); };
        assertTrue(GitSnapshot.capture(dir, timing).error().contains("超时"));
    }

    /** 系统 tmpdir 下既有的 codetui-git-* 临时文件集合（区别「启动失败就地清理」与并行测试互踩的基线）。 */
    private static Set<Path> codetuiGitTmps(Path dir) throws IOException {
        Set<Path> found = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "codetui-git-*.tmp")) {
            for (Path p : ds) {
                found.add(p);
            }
        }
        return found;
    }

    @Test
    void execStartFailureCleansUpTempFiles() throws IOException {
        // 机器无 git 是设计预期分支（类 javadoc）：start() 抛错时两个刚建的 tmp 必须就地删掉——
        // exec 的 finally 只挂在「启动成功后」那段 try 上，够不到创建-启动段，不清理每次 /diff、
        // /review 预检就漏两个 0 字节 codetui-git-*.tmp（评审 Important#1）。
        Path sysTmp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before = codetuiGitTmps(sysTmp);

        IOException e = assertThrows(IOException.class,
                () -> GitSnapshot.exec(List.of("definitely-not-a-program-codetui-test")));
        assertTrue(e.getMessage().contains("git 不可用"), "消息须指明 git 不可用，实际: " + e.getMessage());

        Set<Path> leaked = new HashSet<>(codetuiGitTmps(sysTmp));
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(), "启动失败不得残留临时文件，实际新增: " + leaked);
    }
}
