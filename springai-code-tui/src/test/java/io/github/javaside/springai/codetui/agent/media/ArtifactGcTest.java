package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArtifactGcTest {

    @TempDir Path dir;

    private Path file(String name, int bytes, long mtimeMillis) throws Exception {
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtimeMillis));
        return p;
    }

    @Test
    void deletesOldestUntilUnderLimit() throws Exception {
        Path old1 = file("a.png", 600, 1_000L);
        Path old2 = file("b.png", 600, 2_000L);
        Path fresh = file("c.png", 600, 3_000L);

        ArtifactGc.sweep(dir, 1_500);   // 上限 1500 字节，总量 1800

        assertFalse(Files.exists(old1), "最老的 a.png 应被删除");
        assertTrue(Files.exists(old2), "b.png 删到上限以下后应保留");
        assertTrue(Files.exists(fresh), "最新的 c.png 应保留");
    }

    @Test
    void underLimitIsNoOp() throws Exception {
        Path p = file("a.png", 100, 1_000L);
        ArtifactGc.sweep(dir, 10_000);
        assertTrue(Files.exists(p), "未超上限不该删任何文件");
    }

    /** 目录不存在时必须安静返回——启动路径上绝不能抛。 */
    @Test
    void missingDirectoryIsSilent() {
        ArtifactGc.sweep(dir.resolve("nope"), 1000);
    }

    /**
     * latest.png 这类软链既不该被计入总量，也不该被删掉。
     *
     * <p>构造刻意让两种实现产生可观测差别：两个真文件共 1200 字节，上限 1500——不跟随软链时
     * 总量 1200 未超限，直接 no-op；一旦跟随（{@code Files.isRegularFile} 的默认行为），软链的
     * 600 字节被<b>重复</b>计入变成 1800 超限，于是 GC 开删最旧的 a.png。
     */
    @Test
    void symlinkIsNeitherCountedNorDeleted() throws Exception {
        Path a = file("a.png", 600, 1_000L);
        Path b = file("b.png", 600, 2_000L);
        Path link = dir.resolve("latest.png");
        try {
            Files.createSymbolicLink(link, a.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "本文件系统建不了符号链接，跳过：" + e);
        }

        ArtifactGc.sweep(dir, 1_500);

        assertTrue(Files.exists(a), "软链的大小不该被重复计入，a.png 不该被删");
        assertTrue(Files.exists(b), "b.png 不该被删");
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "软链本身不该被删");
    }
}
