package io.github.javaside.springai.codetui.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一次 git 工作树快照（/diff 与 /review 预检共用）。git 执行抽成 {@link GitCommand} 接缝：
 * 这是全仓生产代码第一处 ProcessBuilder（spec 事实#6 申报）——不接缝则「git 不可用/非仓库/超时」
 * 分支在单测里永远造不出来（得操纵 PATH）。仓库探测不靠 .git 目录存在性：worktree 下它是文件。
 */
public record GitSnapshot(String statLine, List<String> body, List<String> untracked, String error) {

    /** 正常路径紧凑构造。 */
    public static GitSnapshot ok(String statLine, List<String> body, List<String> untracked) {
        return new GitSnapshot(statLine, List.copyOf(body), List.copyOf(untracked), null);
    }

    /** error 路径：其余字段一律空（调用方只看 error，不猜部分结果）。 */
    public static GitSnapshot failed(String error) {
        return new GitSnapshot(null, List.of(), List.of(), error);
    }

    @FunctionalInterface
    public interface GitCommand {
        List<String> run(Path root, String... args) throws IOException;
    }

    /**
     * ProcessBuilder 默认实现：拼 `git -C root <args>` 后委托 {@link #exec}（重定向/超时/清理细节见其 javadoc）。
     */
    public static final GitCommand PROCESS = (root, args) -> {
        List<String> cmd = new ArrayList<>(args.length + 3);
        cmd.add("git"); cmd.add("-C"); cmd.add(root.toString()); cmd.addAll(List.of(args));
        return exec(cmd);
    };

    /**
     * 完整命令的执行体（原 PROCESS 主体，抽成包级静态以便同包测试直接钉「启动失败」分支）：
     * stdout/stderr 重定向到临时文件——**刻意不走管道**。「先 waitFor 后读管道」在输出超过
     * OS 管道缓冲（约 64KB）时互锁：git 阻塞在写、waitFor 永不返回、10s 超时假失败——
     * 而大 diff 恰是 /diff 的主场景（计划评审实证）。10s 超时 destroyForcibly；
     * 非零退出码抛 IOException（stderr 首行入消息）。
     */
    static List<String> exec(List<String> fullCmd) throws IOException {
        Path outTmp = null;
        Path errTmp = null;
        Process p;
        try {
            outTmp = Files.createTempFile("codetui-git-out", ".tmp");
            errTmp = Files.createTempFile("codetui-git-err", ".tmp");
            p = new ProcessBuilder(fullCmd)
                    .redirectOutput(outTmp.toFile())
                    .redirectError(errTmp.toFile())
                    .start();
        } catch (IOException e) {
            // 创建/启动失败（机器无 git 属设计预期分支，见类 javadoc）：此刻可能已有 tmp 建好，
            // 而下方 finally 挂在「启动成功后」那段 try 上够不到这里——不就地删每次 /diff、
            // /review 预检就漏两个 0 字节文件。
            if (outTmp != null) { try { Files.deleteIfExists(outTmp); } catch (IOException ignore) { } }
            if (errTmp != null) { try { Files.deleteIfExists(errTmp); } catch (IOException ignore) { } }
            throw new IOException("git 不可用：" + e.getMessage());
        }
        try {
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("git 超时：" + String.join(" ", fullCmd));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("git 被中断");
            }
            String stderr = Files.readString(errTmp, StandardCharsets.UTF_8).strip();
            if (p.exitValue() != 0) {
                throw new IOException(stderr.isEmpty() ? ("git 退出码 " + p.exitValue())
                        : stderr.lines().findFirst().orElse("git 失败"));
            }
            return Files.readAllLines(outTmp, StandardCharsets.UTF_8);
        } finally {
            try { Files.deleteIfExists(outTmp); } catch (IOException ignore) { }
            try { Files.deleteIfExists(errTmp); } catch (IOException ignore) { }
        }
    };

    /** 是否在 git 工作树内（/review 预检用）。 */
    public static boolean isGitRepo(Path root, GitCommand git) {
        try {
            return git.run(root, "rev-parse", "--is-inside-work-tree").equals(List.of("true"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** 采集 staged+unstaged 合一的 diff（--stat 尾行 + 正文）与未跟踪文件（列名+字节数）。 */
    public static GitSnapshot capture(Path root, GitCommand git) {
        try {
            List<String> stat = git.run(root, "diff", "HEAD", "--stat");
            List<String> body = git.run(root, "diff", "HEAD");
            List<String> rawUntracked = git.run(root, "ls-files", "--others", "--exclude-standard");
            List<String> untracked = new ArrayList<>(rawUntracked.size());
            for (String name : rawUntracked) {
                long size;
                try {
                    size = Files.size(root.resolve(name));
                } catch (IOException e) {
                    size = -1;
                }
                untracked.add(size >= 0 ? name + " (" + size + " bytes)" : name);
            }
            String statLine = stat.isEmpty() ? "" : stat.get(stat.size() - 1);
            return ok(statLine, body, untracked);
        } catch (IOException | RuntimeException e) {
            return failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
