package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathAliasesTest {

    /**
     * {@code @TempDir} 在 macOS 上落在 {@code /var/folders/…}，而 {@code /var} 本身是指向
     * {@code /private/var} 的符号链接——<b>临时目录自己就带一条链</b>。直接拿它当「无符号链接」
     * 的场景用，实现会（正确地）多给一个 {@code /private/var/…} 别名，断言反而被环境噪声打红。
     *
     * <p>故所有用例先把临时目录解成真实路径再往下建，让「有没有链」完全由用例自己控制；
     * 这比把断言放宽成「原写法在里面就行」更强——放宽会连「凭空制造候选」也一并放过。
     */
    private static Path real(Path tempDir) throws IOException {
        return tempDir.toRealPath();
    }

    /** Windows 上建符号链接要特权：跳过而不是失败。 */
    private static Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "本环境不支持创建符号链接：" + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("无符号链接时只有一个写法——别名不该凭空制造候选")
    void plainPathHasSingleAlias(@TempDir Path tmp) throws Exception {
        Path f = Files.createFile(real(tmp).resolve("a.txt"));
        assertEquals(List.of(f.normalize()), PathAliases.of(f));
    }

    @Test
    @DisplayName("目标文件尚不存在、但父目录经符号链接指向别处——这是两步绕过的形态")
    void resolvesThroughSymlinkedParentForMissingFile(@TempDir Path tmp) throws Exception {
        Path dir = real(tmp);
        Path real = Files.createDirectories(dir.resolve("real/sub"));
        Path link = symlink(dir.resolve("link"), dir.resolve("real"));

        // link/sub/new.txt 尚不存在；toRealPath 会直接抛，故必须走「最长已存在祖先」
        Path target = link.resolve("sub/new.txt");
        List<Path> aliases = PathAliases.of(target);

        assertTrue(aliases.contains(target.normalize()), "原写法必须保留：" + aliases);
        assertTrue(aliases.contains(real.resolve("new.txt").normalize()),
                "应解析出真实路径 " + real.resolve("new.txt") + "，实际：" + aliases);
    }

    @Test
    @DisplayName("整条路径都不存在也不抛——退化成只有原写法")
    void missingAncestorsDoNotThrow(@TempDir Path tmp) throws Exception {
        Path target = real(tmp).resolve("no/such/dir/x.txt");
        List<Path> aliases = PathAliases.of(target);
        assertEquals(List.of(target.normalize()), aliases);
    }

    @Test
    @DisplayName("null 与相对路径不抛——调用方在判定热路径上，不该为此加判空")
    void nullAndRelativeAreSafe() {
        assertTrue(PathAliases.of(null).isEmpty());
        assertEquals(1, PathAliases.of(Path.of("relative/x.txt")).size(),
                "相对路径无从解析，原样返回一个");
    }

    @Test
    @DisplayName("目标自己是悬空链接——ln -s <敏感路径> x 再 Write x，是同形状的两步绕过")
    void danglingSymlinkResolvesToItsTarget(@TempDir Path tmp) throws Exception {
        Path dir = real(tmp);
        Path secret = dir.resolve("not-yet/evil.conf");   // 刻意不创建：链接建好时它还不存在
        Path link = symlink(dir.resolve("x"), secret);

        List<Path> aliases = PathAliases.of(link);

        assertTrue(aliases.contains(link.normalize()), "原写法必须保留：" + aliases);
        assertTrue(aliases.contains(secret.normalize()),
                "悬空链接也要解析出 " + secret + "，实际：" + aliases);
    }

    @Test
    @DisplayName("链接目标是相对路径——必须相对链接所在目录解析，不是相对 cwd")
    void relativeSymlinkTargetResolvesAgainstLinkDirectory(@TempDir Path tmp) throws Exception {
        Path dir = real(tmp);
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        // ../b/x 里的 x 同样不存在：这才逼出「读链接内容」而非 toRealPath 那条路
        Path link = symlink(dir.resolve("a/y"), Path.of("../b/x"));

        List<Path> aliases = PathAliases.of(link);

        Path againstLinkDir = dir.resolve("b/x").normalize();
        assertTrue(aliases.contains(againstLinkDir),
                "应解析成 " + againstLinkDir + "（相对链接所在目录），实际：" + aliases);
        // 若错按 cwd 解析，落点会是 <模块目录>/../b/x，与上面这条根本对不上
        assertTrue(aliases.stream().allMatch(Path::isAbsolute), "别名必须都是绝对路径：" + aliases);
    }

    @Test
    @DisplayName("循环链接不得挂死——a→b、b→a 要在正常时间内返回且不抛")
    void symlinkCycleTerminates(@TempDir Path tmp) throws Exception {
        Path dir = real(tmp);
        Path a = symlink(dir.resolve("a"), dir.resolve("b"));
        symlink(dir.resolve("b"), dir.resolve("a"));

        List<Path> aliases = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> PathAliases.of(a), "循环链接把解析转晕了");

        assertTrue(aliases.contains(a.normalize()), "解不开也必须保留原写法：" + aliases);
    }

    @Test
    @DisplayName("别名去重且原写法排第一——调用方靠顺序做「先精确后放宽」的日志归因")
    void aliasesAreDedupedAndOriginalFirst(@TempDir Path tmp) throws Exception {
        Path dir = real(tmp);
        Files.createDirectories(dir.resolve("real"));
        symlink(dir.resolve("link"), dir.resolve("real"));

        Path target = dir.resolve("link/a.txt");
        List<Path> aliases = PathAliases.of(target);
        assertEquals(target.normalize(), aliases.get(0), "原写法必须排第一：" + aliases);
        assertEquals(aliases.size(), aliases.stream().distinct().count(), "不得有重复：" + aliases);
    }
}
